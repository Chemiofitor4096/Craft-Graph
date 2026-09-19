/**
 * 配方树 / 产线输出的成本分解。
 *
 *   npm run analyze
 *
 * 回答两个问题：
 *   1. token 到底花在哪一行上（按行分类的真实分解）
 *   2. 索引查询延迟是否有效
 *
 * 格式对比（markdown / 列式 TSV / 字典编码）见 format-compare.ts。
 *
 * 注：这里曾有一段「优化收益模拟」（对输出做文本变换来估算各项优化的省幅）。
 * 那些优化已经实施进 report.ts，模拟代码已移除 —— 留着会误导，
 * 因为脚本面对的已经是优化后的输出了。
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { encode } from "gpt-tokenizer";

// 合成数据必须写进独立的缓存目录。
//
// 踩过的坑：这两个脚本原本用默认缓存目录（~/.craftgraph/cache），
// 于是它们生成的**合成**快照把真实游戏的快照覆盖掉了。
// 后果不只是脏数据 —— 之后游戏没开时做离线查询，会把 10000 条假配方
// 当成真数据返回，而且报告看起来完全正常。诊断工具必须与真实缓存隔离。
const TMP_CACHE = fs.mkdtempSync(path.join(os.tmpdir(), "craftgraph-synth-"));
process.env.CRAFTGRAPH_CACHE_DIR = TMP_CACHE;

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = 25596;

const { startMockBridge, generateSyntheticPack } = await import("./mock-bridge.js");
const { BridgeClient } = await import("./bridge.js");
const { RecipeStore } = await import("./cache.js");
const { buildRecipeTree } = await import("./tree.js");
const { calculatePlan } = await import("./plan.js");
const { renderTree, renderPlan } = await import("./report.js");

const fixture = generateSyntheticPack(10000);
const server = await startMockBridge(PORT, fixture);
const store = await RecipeStore.load(
  new BridgeClient({ host: "127.0.0.1", port: PORT, token: null, source: "env-url" }),
);

// ---------------------------------------------------------------- 索引性能

function bench(label: string, fn: () => unknown, iterations = 2000): void {
  fn(); // 预热
  const t0 = performance.now();
  for (let i = 0; i < iterations; i++) fn();
  const per = ((performance.now() - t0) / iterations) * 1000;
  process.stdout.write(`  ${label.padEnd(44)} ${per.toFixed(2)} µs/次\n`);
}

process.stdout.write(`\n===== 索引查询延迟（${store.status.recipeCount} 条配方）=====\n`);
const allItems = store.allItemIds();
const someItem = allItems[Math.floor(allItems.length * 0.7)]!;
bench("按产出查（byOutput 直接命中）", () => store.recipesProducing("item", someItem));
bench("按输入查（byInput 倒排，已展开标签）", () => store.recipesConsuming("item", someItem));
bench("标签展开（tags 直接命中）", () => store.expandTag("forge:ingot/minecraft_group_3"));
bench("物品搜索（线性扫描全部物品 id）", () => store.searchItems("ingot", 20));
bench("取单条配方（byId）", () => store.getRecipe(fixture.recipes[0]!.id));
bench("命中不存在的物品（最坏情况）", () => store.recipesProducing("item", "nonexistent:nothing"));

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
const md = renderTree(store, tree, best.item);
const plan = calculatePlan(store, "item", best.item, { ratePerMinute: 10 });
const planMd = renderPlan(store, plan, best.item);

// ---------------------------------------------------------------- 成本分解

function classify(line: string): string {
  const t = line.trim();
  if (t === "") return "(空行)";
  if (t.startsWith("_#") && t.includes("候选物品")) return "标签候选计数";
  if (t.startsWith("_另有")) return "替代配方计数";
  if (t.startsWith("_") && t.endsWith("_")) return "斜体说明 note";
  if (t.startsWith("↳")) return "配方行 ↳";
  if (t.includes("**")) return "物品行";
  if (t.startsWith("|")) return "表格行";
  if (t.startsWith("#")) return "标题";
  if (t.startsWith(">")) return "引用块（图例/警告）";
  return "其他";
}

function breakdown(name: string, text: string): void {
  const buckets = new Map<string, { lines: number; chars: number }>();
  for (const line of text.split("\n")) {
    const k = classify(line);
    const b = buckets.get(k) ?? { lines: 0, chars: 0 };
    b.lines++;
    b.chars += line.length + 1;
    buckets.set(k, b);
  }
  process.stdout.write(`\n===== ${name}：${encode(text).length.toLocaleString()} tokens =====\n`);
  for (const [k, b] of [...buckets.entries()].sort((a, b) => b[1].chars - a[1].chars)) {
    const pct = ((b.chars / text.length) * 100).toFixed(1);
    process.stdout.write(
      `  ${k.padEnd(22)} ${String(b.lines).padStart(5)} 行 ${b.chars.toLocaleString().padStart(9)} 字符 ${(pct + "%").padStart(8)}\n`,
    );
  }
}

breakdown(`配方树 ${best.item}（${tree.nodeCount} 节点）`, md);
breakdown(`产线规划 ${best.item}`, planMd);

process.stdout.write(
  `\n平均每节点：${(md.length / tree.nodeCount).toFixed(0)} 字符 / ${(encode(md).length / tree.nodeCount).toFixed(0)} tokens\n\n`,
);

await new Promise<void>((r) => server.close(() => r()));
