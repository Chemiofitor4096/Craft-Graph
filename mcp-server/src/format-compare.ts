/**
 * 格式对比：验证「紧凑文本 + Schema 外置 + 字典编码」（doc/raw-form.md 的方案）
 * 对这个项目到底能省多少 token。
 *
 * 三个关键的方法论要求，否则结论会失真：
 *
 *   1. **信息量必须对齐。** 不能拿只含 depth/item/recipe 的极简 CCRF 去比
 *      信息完整的 markdown。这里给 CCRF 补齐同样的字段（count/crafts/perCraft/
 *      type/tag/候选数），只在表达形式上做压缩。
 *
 *   2. **字典的开销要算进去。** 字典本身也占 token。更要紧的是：MCP 的工具结果是
 *      互相独立的，客户端还可能丢弃早期结果（上下文压缩）。所以「字典只发一次」
 *      是不可靠的假设。这里同时测「字典复用」和「每次重发字典」两种情形 ——
 *      后者才是可靠的基线。
 *
 *   3. **模型可用性不是免费的。** 省 token 但让模型看不懂，等于把成本转移到
 *      调错工具、多轮试探上。这一点无法自动测量，但必须写进结论。
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { encode } from "gpt-tokenizer";

import type { TreeNode } from "./tree.js";

// 合成数据必须写进独立的缓存目录。
//
// 踩过的坑：这两个脚本原本用默认缓存目录（~/.craftgraph/cache），
// 于是它们生成的**合成**快照把真实游戏的快照覆盖掉了。
// 后果不只是脏数据 —— 之后游戏没开时做离线查询，会把 10000 条假配方
// 当成真数据返回，而且报告看起来完全正常。诊断工具必须与真实缓存隔离。
const TMP_CACHE = fs.mkdtempSync(path.join(os.tmpdir(), "craftgraph-synth-"));
process.env.CRAFTGRAPH_CACHE_DIR = TMP_CACHE;

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = 25594;

const { startMockBridge, generateSyntheticPack } = await import("./mock-bridge.js");
const { BridgeClient } = await import("./bridge.js");
const { RecipeStore } = await import("./cache.js");
const { buildRecipeTree } = await import("./tree.js");
const { calculatePlan } = await import("./plan.js");
const { renderTree, renderPlan } = await import("./report.js");

/** RecipeStore 是动态 import 进来的（值），构造函数私有所以不能直接当类型用。 */
type RecipeStoreT = Awaited<ReturnType<typeof RecipeStore.load>>;

const fixture = generateSyntheticPack(10000);
const server = await startMockBridge(PORT, fixture);
const store = await RecipeStore.load(
  new BridgeClient({ host: "127.0.0.1", port: PORT, token: null, source: "env-url" }),
);

// ---------------------------------------------------------------- 找最深链

const produceCount = new Map<string, number>();
for (const r of fixture.recipes) {
  const o = r.outputs[0];
  if (o) produceCount.set(o.item, (produceCount.get(o.item) ?? 0) + 1);
}
const candidates = [...produceCount.keys()];
let best = { item: candidates[0]!, nodes: 0 };
for (const item of candidates.slice(0, 40)) {
  const res = buildRecipeTree(store, "item", item, 1);
  if (res.nodeCount > best.nodes) best = { item, nodes: res.nodeCount };
}

const tree = buildRecipeTree(store, "item", best.item, 1);
const plan = calculatePlan(store, "item", best.item, { ratePerMinute: 10 });

// ---------------------------------------------------------------- 字典编码

/**
 * 把一组字符串映射成 i0/i1/... 的短引用，并生成字典行。
 * 字典行位置即索引，所以行内不带序号 —— 这是最省的写法。
 */
class Dictionary {
  private readonly index = new Map<string, number>();
  private readonly values: string[] = [];

  ref(value: string): string {
    let i = this.index.get(value);
    if (i === undefined) {
      i = this.values.length;
      this.index.set(value, i);
      this.values.push(value);
    }
    return `i${i}`;
  }

  /** 字典行。位置即索引，所以只写值。 */
  lines(prefix = "D"): string[] {
    return this.values.map((v) => `${prefix}${v}`);
  }

  get size(): number {
    return this.values.length;
  }

  get dictText(): string {
    return this.lines().join("\n");
  }
}

// ---------------------------------------------------------------- 配方树：CCRF

/**
 * 把配方树编码成带字典的列式文本。
 *
 * 列：depth|parent|count|item|recipe|type|crafts|perCraft|tag|tagAltCount|altCount|note
 * 空值用 - 占位。
 */
function treeToCcrf(root: TreeNode): { rows: string; dict: string; dictSize: number } {
  const items = new Dictionary();
  const recipes = new Dictionary();
  const types = new Dictionary();
  const tags = new Dictionary();

  const rows: string[] = [];
  rows.push("S|depth|parent|count|item|recipe|type|crafts|perCraft|tag|tagAlts|alts|status");

  let counter = 0;
  const walk = (node: TreeNode, depth: number, parent: string): void => {
    const id = String(counter++);
    rows.push(
      [
        depth,
        parent,
        node.count,
        items.ref(node.item),
        node.recipeId ? recipes.ref(node.recipeId) : "-",
        node.recipeType ? types.ref(node.recipeType) : "-",
        node.crafts ?? "-",
        node.outputPerCraft ?? "-",
        node.chosenFromTag ? tags.ref(node.chosenFromTag) : "-",
        node.tagAlternatives?.length ?? 0,
        node.alternatives.length,
        node.kind,
      ].join("|"),
    );
    for (const child of node.children) walk(child, depth + 1, id);
  };
  walk(root, 0, "-");

  const dict = [
    ...items.lines("Di"),
    ...recipes.lines("Dr"),
    ...types.lines("Dt"),
    ...tags.lines("Dg"),
  ].join("\n");

  return { rows: rows.join("\n"), dict, dictSize: items.size + recipes.size + types.size + tags.size };
}

// ---------------------------------------------------------------- 配方列表：TSV

function recipeListToTsv(store: RecipeStoreT, item: string): { rows: string; dict: string } {
  const recipes = store.recipesProducing("item", item);
  const items = new Dictionary();
  const types = new Dictionary();

  const rows: string[] = ["S|id|type|outItem|outCount|opaque"];
  for (const r of recipes) {
    const out = r.outputs[0];
    rows.push(
      [
        items.ref(r.id),
        types.ref(r.type),
        out ? items.ref(out.item) : "-",
        out?.count ?? "-",
        r.opaque ? 1 : 0,
      ].join("|"),
    );
  }
  return { rows: rows.join("\n"), dict: [...items.lines("Di"), ...types.lines("Dt")].join("\n") };
}

/** 列式但**不带字典** —— id 直接内联。用于把「列式」和「字典」两项收益分开。 */
function recipeListToTsvNoDict(store: RecipeStoreT, item: string): string {
  const recipes = store.recipesProducing("item", item);
  const rows = ["id|type|outItem|outCount|opaque"];
  for (const r of recipes) {
    const out = r.outputs[0];
    rows.push([r.id, r.type, out?.item ?? "-", out?.count ?? "-", r.opaque ? 1 : 0].join("|"));
  }
  return rows.join("\n");
}

/** 配方树列式、不带字典。 */
function treeToTsvNoDict(root: TreeNode): string {
  const rows = ["depth|parent|count|item|recipe|type|crafts|perCraft|tag|tagAlts|alts|status"];
  let counter = 0;
  const walk = (node: TreeNode, depth: number, parent: string): void => {
    const id = String(counter++);
    rows.push(
      [
        depth, parent, node.count, node.item,
        node.recipeId ?? "-", node.recipeType ?? "-",
        node.crafts ?? "-", node.outputPerCraft ?? "-",
        node.chosenFromTag ?? "-",
        node.tagAlternatives?.length ?? 0, node.alternatives.length, node.kind,
      ].join("|"),
    );
    for (const child of node.children) walk(child, depth + 1, id);
  };
  walk(root, 0, "-");
  return rows.join("\n");
}

// ---------------------------------------------------------------- 对比

const tk = (s: string) => encode(s).length;

const markdownTree = renderTree(store, tree, best.item);
const jsonTree = JSON.stringify(tree);
const ccrfTree = treeToCcrf(tree.root);

const markdownPlan = renderPlan(store, plan, best.item);
const jsonPlan = JSON.stringify(plan);

// 列表对比要用「候选配方最多」的物品，不能用最深链那个 —— 深链物品通常只有一条配方，
// 拿它测列表等于没测。这个坑我踩过一次：第一次跑出来 41 tokens 的"对比"毫无意义。
const hottestItem = [...produceCount.entries()].sort((a, b) => b[1] - a[1])[0]![0];
const markdownList = recipeListLines(store, hottestItem);
const jsonList = JSON.stringify(
  store.recipesProducing("item", hottestItem).map((r) => ({
    id: r.id,
    type: r.type,
    primaryOutput: r.outputs[0] ?? null,
    opaque: r.opaque,
  })),
);
const tsvList = recipeListToTsv(store, hottestItem);

/** 当前 markdown 的列表渲染（复刻 tools.ts 里的形状，用于对比） */
function recipeListLines(s: RecipeStoreT, item: string): string {
  const recipes = s.recipesProducing("item", item);
  const lines = [`## 配方`, "", `共 ${recipes.length} 条`, ""];
  for (const r of recipes) {
    const out = r.outputs[0];
    lines.push(`- \`${r.id}\` — ${r.typeLabel ?? r.type} → ${out ? `${out.count} × ${out.item}` : "（未知）"}${r.opaque ? " ⚠️读不懂" : ""}`);
  }
  return lines.join("\n");
}

const out: string[] = [];
out.push("");
out.push(
  `同一份数据、同等信息量，不同表达形式的 token 对比（${tree.nodeCount} 节点配方树 / ${store.recipesProducing("item", hottestItem).length} 条配方列表）`,
);
out.push("");

function section(name: string, rows: [string, number][]): void {
  out.push(`### ${name}`);
  out.push("");
  out.push("| 格式 | tokens | 相对 markdown |");
  out.push("|---|---:|---:|");
  const base = rows[0]![1];
  for (const [label, tokens] of rows) {
    const delta = ((tokens - base) / base) * 100;
    const sign = delta >= 0 ? "+" : "";
    out.push(`| ${label} | ${tokens.toLocaleString()} | ${sign}${delta.toFixed(0)}% |`);
  }
  out.push("");
}

section("配方树", [
  ["markdown（当前默认）", tk(markdownTree)],
  ["紧凑 JSON", tk(jsonTree)],
  ["列式 TSV，无字典（id 内联）", tk(treeToTsvNoDict(tree.root))],
  ["列式 TSV + 字典，仅数据行（字典已存在）", tk(ccrfTree.rows)],
  ["列式 TSV + 字典，数据行与字典一起发", tk(ccrfTree.rows) + tk(ccrfTree.dict)],
]);

section("配方列表", [
  ["markdown（当前默认）", tk(markdownList)],
  ["紧凑 JSON", tk(jsonList)],
  ["列式 TSV，无字典（id 内联）", tk(recipeListToTsvNoDict(store, hottestItem))],
  ["列式 TSV + 字典，仅数据行（字典已存在）", tk(tsvList.rows)],
  ["列式 TSV + 字典，数据行与字典一起发", tk(tsvList.rows) + tk(tsvList.dict)],
]);

section("产线规划", [
  ["markdown（当前默认）", tk(markdownPlan)],
  ["紧凑 JSON", tk(jsonPlan)],
]);

out.push(`字典规模：配方树用到 ${ccrfTree.dictSize} 个 id，字典本身 ${tk(ccrfTree.dict).toLocaleString()} tokens`);
out.push(`          配方列表用到 ${tk(tsvList.dict).toLocaleString()} tokens 的字典`);
out.push("");

process.stdout.write(out.join("\n"));
await new Promise<void>((r) => server.close(() => r()));
