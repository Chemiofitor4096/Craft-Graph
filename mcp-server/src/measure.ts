/**
 * Token 消耗测量。
 *
 *   npm run measure            默认 10000 条配方（大型整合包量级）
 *   npm run measure -- 1000    指定规模
 *
 * 为什么要用合成的大数据集而不是夹具：夹具只有 11 条配方，
 * "列表里返回 50 条配方"这种场景在夹具上根本触发不到，
 * 测出来会严重低估真实消耗。
 *
 * 测的是 AI 客户端实际收到的字节 —— 包括 JSON-RPC 信封，不是理想化的估算。
 */

import path from "node:path";
import { fileURLToPath } from "node:url";
import { encode } from "gpt-tokenizer";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = 25597;
const SCALE = Number(process.argv[2] ?? 10000);

const { startMockBridge, generateSyntheticPack } = await import("./mock-bridge.js");

const fixture = generateSyntheticPack(SCALE);

// ---- 挑选有代表性的查询目标 ----
const produceCount = new Map<string, number>();
for (const r of fixture.recipes) {
  const out = r.outputs[0];
  if (out) produceCount.set(out.item, (produceCount.get(out.item) ?? 0) + 1);
}

const byCount = [...produceCount.entries()].sort((a, b) => b[1] - a[1]);
const hottest = byCount[0];
if (!hottest) {
  process.stderr.write("合成数据里没有找到有配方的物品，请加大规模\n");
  process.exit(1);
}

/**
 * 链最深的物品 —— 这是配方树开销的真实上界。
 *
 * 不能随机挑样品：整合包里物品的链长度差得很远，随机挑一个多半是很短的链，
 * 测出来的 token 会严重偏低。这里实际建树试一批候选，取节点数最多的那个。
 */
async function pickDeepestTarget(
  client: Client,
  candidates: string[],
): Promise<{ item: string; recipes: number; nodes: number; tokens: number }> {
  let best = { item: candidates[0]!, recipes: 0, nodes: 0, tokens: 0 };
  for (const item of candidates.slice(0, 25)) {
    const res = await client.callTool({ name: "build_recipe_tree", arguments: { item, count: 1, format: "json" } });
    const text = textOf(res);
    let nodes = 0;
    try {
      const parsed = JSON.parse(text) as { nodeCount?: number };
      nodes = parsed.nodeCount ?? 0;
    } catch {
      continue;
    }
    if (nodes > best.nodes) {
      best = { item, recipes: produceCount.get(item) ?? 0, nodes, tokens: encode(text).length };
    }
  }
  return best;
}

const server = await startMockBridge(PORT, fixture);

const transport = new StdioClientTransport({
  command: process.execPath,
  args: ["--import", "tsx", path.join(HERE, "index.ts")],
  cwd: path.resolve(HERE, ".."),
  env: {
    ...process.env,
    CRAFTGRAPH_BRIDGE_URL: `http://127.0.0.1:${PORT}`,
    CRAFTGRAPH_CACHE_DIR: path.join(HERE, "..", ".cache", "measure"),
  },
  stderr: "pipe",
});

const client = new Client({ name: "craftgraph-measure", version: "1.0.0" });

interface Row {
  label: string;
  chars: number;
  tokens: number;
}

const rows: Row[] = [];
let perTool: { name: string; tokens: number }[] = [];

function record(label: string, payload: unknown): Row {
  const text = typeof payload === "string" ? payload : JSON.stringify(payload);
  const row = { label, chars: text.length, tokens: encode(text).length };
  rows.push(row);
  return row;
}

/** 从 callTool 的返回里取出纯文本内容。SDK 的返回类型是个联合，这里统一按 unknown 处理。 */
function textOf(result: unknown): string {
  const content = (result as { content?: unknown }).content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((c): c is { type: string; text?: string } => typeof c === "object" && c !== null && (c as { type?: string }).type === "text")
    .map((c) => c.text ?? "")
    .join("\n");
}

try {
  await client.connect(transport);

  // ============================================================ 固定开销
  const toolsList = await client.listTools();
  record("【固定开销】工具定义 tools/list", toolsList);

  const perToolLocal = toolsList.tools.map((t) => ({
    name: t.name,
    tokens: encode(JSON.stringify(t)).length,
  }));
  perToolLocal.sort((a, b) => b.tokens - a.tokens);
  perTool = perToolLocal;

  // ============================================================ 各工具输出
  const search = await client.callTool({ name: "search_items", arguments: { query: "ingot", limit: 20 } });
  record("search_items（20 条）", textOf(search));

  const status = await client.callTool({ name: "get_bridge_status", arguments: {} });
  record("get_bridge_status", textOf(status));

  // 热门物品：候选配方最多。列表接口的开销完全由这个决定。
  const forOutput = await client.callTool({
    name: "get_recipes_for_output",
    arguments: { item: hottest[0] },
  });
  record(`get_recipes_for_output（候选最多的物品，共 ${hottest[1]} 条）`, textOf(forOutput));

  const forOutputIn = await client.callTool({
    name: "get_recipes_for_input",
    arguments: { item: hottest[0] },
  });
  record(`get_recipes_for_input（同一物品作为输入）`, textOf(forOutputIn));

  // 链最深的物品：配方树开销的真实上界
  const deep = await pickDeepestTarget(client, byCount.map(([item]) => item));
  process.stderr.write(`[measure] 最深的链：${deep.item}，${deep.nodes} 个节点\n`);

  const tree = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: deep.item, count: 1 },
  });
  const treeMd = textOf(tree);
  record(`配方树 markdown，默认 detailDepth=2（全树 ${deep.nodes} 节点）`, treeMd);

  const treeFull = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: deep.item, count: 1, detailDepth: 64 },
  });
  const treeFullMd = textOf(treeFull);
  record("配方树 markdown，detailDepth=64（完全展开，改动前的默认行为）", treeFullMd);

  const treeTsv = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: deep.item, count: 1, format: "tsv" },
  });
  record("配方树 tsv，detailDepth=2", textOf(treeTsv));

  const treeTsvFull = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: deep.item, count: 1, format: "tsv", detailDepth: 64 },
  });
  record("配方树 tsv，detailDepth=64", textOf(treeTsvFull));

  const treeJson = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: deep.item, count: 1, format: "json" },
  });
  const treeJsonText = textOf(treeJson);
  record("配方树 json，detailDepth=2", treeJsonText);

  const plan = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: deep.item, ratePerMinute: 10 },
  });
  const planMd = textOf(plan);
  record("产线规划 markdown，默认 detailDepth=2", planMd);

  const planFull = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: deep.item, ratePerMinute: 10, detailDepth: 64 },
  });
  record("产线规划 markdown，detailDepth=64（改动前的默认行为）", textOf(planFull));

  const planTsv = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: deep.item, ratePerMinute: 10, format: "tsv" },
  });
  record("产线规划 tsv，detailDepth=2", textOf(planTsv));

  const planJson = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: deep.item, ratePerMinute: 10, format: "json" },
  });
  record("产线规划 json，detailDepth=2", textOf(planJson));

  const types = await client.callTool({ name: "list_recipe_types", arguments: {} });
  record("list_recipe_types", textOf(types));

  const details = await client.callTool({
    name: "get_recipe_details",
    arguments: { recipeId: fixture.recipes[0]!.id },
  });
  record("get_recipe_details（单条）", textOf(details));

  // ============================================================ 真实会话流程
  // 「X 怎么做」这类问题，agent 的典型调用序列
  record("★ 典型会话（搜索 → 查配方 → 配方树，全默认参数）", [textOf(search), textOf(forOutput), treeMd].join("\n"));
  record("★ 精简会话（搜索 → 直接建树）", [textOf(search), treeMd].join("\n"));
  record("★ 最坏会话（+ 产线规划 + 一条详情）", [
    textOf(search),
    textOf(forOutput),
    treeMd,
    planMd,
    textOf(details),
  ].join("\n"));
} catch (err) {
  process.stderr.write(`测量失败：${err instanceof Error ? err.stack : String(err)}\n`);
} finally {
  await client.close().catch(() => {});
  await new Promise<void>((resolve) => server.close(() => resolve()));
}

// ============================================================ 输出
const out: string[] = [];
out.push("");
out.push(`数据规模：${fixture.recipes.length} 条配方 / ${fixture.registry.items!.length} 个物品 / ${Object.keys(fixture.tags.items!).length} 个标签`);
out.push("");
out.push("| 项目 | 字符数 | tokens |");
out.push("|---|---:|---:|");
for (const r of rows) out.push(`| ${r.label} | ${r.chars.toLocaleString()} | ${r.tokens.toLocaleString()} |`);

out.push("");
out.push("单个工具定义的 token 占用（从大到小，前 6）：");
for (const t of perTool.slice(0, 6)) out.push(`  ${t.tokens.toString().padStart(5)}  ${t.name}`);

process.stdout.write(out.join("\n") + "\n\n");
