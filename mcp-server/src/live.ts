/**
 * 对**运行中的真实游戏**做一次体检。
 *
 *   npm run live
 *
 * 前提：Minecraft 正在运行，且 CraftGraph Mod 已加载并进入世界。
 *
 * <h2>它和别的测试有什么不同</h2>
 *
 * 其它测试全部跑在假数据上（手写夹具或 Java 导出的样本）。
 * 这个脚本跑在**真的 Minecraft 配方数据**上，而且是走完整的真实链路：
 *
 *   MCP Server 子进程 → 服务发现文件 → 真实 Bridge → 真实配方 → 索引 → 引擎 → 报告
 *
 * 所以它能发现假数据发现不了的问题：真实配方类型的形状、真实标签的规模、
 * 真实物品的中文名、以及真机上的 token 消耗。
 *
 * 刻意**不配置任何环境变量** —— 让它自己通过 ~/.craftgraph/bridge.json 发现游戏。
 * 这样顺带验证了服务发现机制本身。
 */

import path from "node:path";
import { fileURLToPath } from "node:url";
import { encode } from "gpt-tokenizer";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

const transport = new StdioClientTransport({
  command: process.execPath,
  args: ["--import", "tsx", path.join(HERE, "index.ts")],
  cwd: path.resolve(HERE, ".."),
  // 关键：不设置 CRAFTGRAPH_BRIDGE_URL / FILE，让它自己找发现文件
  env: { ...process.env, CRAFTGRAPH_CACHE_DIR: path.join(HERE, "..", ".cache", "live") },
  stderr: "pipe",
});

const client = new Client({ name: "craftgraph-live", version: "1.0.0" });

const rows: { label: string; tokens: number; chars: number }[] = [];
let failures = 0;

function textOf(result: unknown): string {
  const content = (result as { content?: unknown }).content;
  if (!Array.isArray(content)) return "";
  return content
    .filter((c): c is { type: string; text?: string } => typeof c === "object" && c !== null && (c as { type?: string }).type === "text")
    .map((c) => c.text ?? "")
    .join("\n");
}

function check(name: string, ok: boolean, detail = ""): void {
  process.stdout.write(`${ok ? "  ✅" : "  ❌"} ${name}\n`);
  if (!ok) {
    failures++;
    if (detail) process.stdout.write(`       ${detail.replace(/\n/g, "\n       ")}\n`);
  }
}

function record(label: string, text: string): void {
  rows.push({ label, tokens: encode(text).length, chars: text.length });
}

try {
  await client.connect(transport);
  process.stdout.write("\n已连接 MCP Server（未配置任何环境变量，靠服务发现找到游戏）\n\n");

  // ============================================================ 连接状态
  const status = await client.callTool({ name: "get_bridge_status", arguments: {} });
  const statusText = textOf(status);
  process.stdout.write("─".repeat(70) + "\n" + statusText + "\n" + "─".repeat(70) + "\n\n");
  check("服务发现机制工作正常（没配置也能找到游戏）", !status.isError, statusText.slice(0, 300));
  check("报告为已连接而非离线", statusText.includes("已连接游戏"), statusText.slice(0, 200));

  // ============================================================ 真实查询
  const search = await client.callTool({ name: "search_items", arguments: { query: "铁锭" } });
  const searchText = textOf(search);
  record("search_items（中文搜索：铁锭）", searchText);
  check("能按中文名搜到物品", searchText.includes("minecraft:iron_ingot"),
    searchText.split("\n").slice(0, 6).join(" / "));

  const types = await client.callTool({ name: "list_recipe_types", arguments: {} });
  const typesText = textOf(types);
  record("list_recipe_types", typesText);
  check("配方类型读出来了", typesText.includes("minecraft:crafting"), typesText.split("\n").slice(0, 6).join(" / "));

  const consuming = await client.callTool({
    name: "get_recipes_for_input",
    arguments: { item: "minecraft:iron_ingot" },
  });
  const consumingText = textOf(consuming);
  record("get_recipes_for_input（铁锭能做什么）", consumingText);
  check("按输入查有结果（真实数据上倒排索引有效）", !consumingText.includes("没有找到"),
    consumingText.split("\n").slice(0, 5).join(" / "));

  // ---- 配方树：挑几个真实且有代表性的目标 ----
  // 火把：标签链（木板 → 木棍）+ 煤炭，三层
  // 铁镐：需要标签（木板）和冶炼链
  // 金苹果：8 金锭 + 苹果，金锭来自冶炼
  for (const [item, label] of [
    ["minecraft:torch", "火把（标签链：原木→木板→木棍）"],
    ["minecraft:iron_pickaxe", "铁镐（标签 + 冶炼链）"],
    ["minecraft:golden_apple", "金苹果（8 金锭 + 苹果）"],
  ] as const) {
    const res = await client.callTool({ name: "build_recipe_tree", arguments: { item, count: 1 } });
    const text = textOf(res);
    record(`配方树 ${label}`, text);
    check(`配方树：${label}`, !res.isError && !text.includes("没有找到产出"), text.slice(0, 400));
    if (!res.isError) {
      process.stdout.write("\n" + "·".repeat(70) + `\n${text}\n` + "·".repeat(70) + "\n");
    }
  }

  // ---- 产线 ----
  const plan = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: "minecraft:torch", ratePerMinute: 60 },
  });
  const planText = textOf(plan);
  record("产线规划（每分钟 60 个火把）", planText);
  check("产线计算跑通", !plan.isError, planText.slice(0, 400));

  // ---- 标签展开 ----
  const tag = await client.callTool({ name: "expand_tag", arguments: { tag: "minecraft:planks" } });
  const tagText = textOf(tag);
  record("expand_tag（#minecraft:planks）", tagText);
  check("标签能展开（真实标签）", !tag.isError && tagText.includes("minecraft:"),
    tagText.split("\n").slice(0, 5).join(" / "));

  // ---- TSV 格式（真机上对比一下） ----
  const tsv = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: "minecraft:iron_pickaxe", count: 1, format: "tsv" },
  });
  const tsvText = textOf(tsv);
  record("配方树 tsv（铁镐）", tsvText);
} catch (err) {
  check("实时体检未抛异常", false, err instanceof Error ? `${err.message}\n${err.stack}` : String(err));
} finally {
  await client.close().catch(() => {});
}

// ============================================================ 报告
process.stdout.write("\n" + "=".repeat(70) + "\n真实数据上的 token 消耗\n" + "=".repeat(70) + "\n\n");
process.stdout.write("| 项目 | 字符 | tokens |\n|---|---:|---:|\n");
for (const r of rows) {
  process.stdout.write(`| ${r.label} | ${r.chars.toLocaleString()} | ${r.tokens.toLocaleString()} |\n`);
}

process.stdout.write(`\n${failures === 0 ? "全部通过" : `${failures} 项失败`}\n\n`);
if (failures > 0) process.exitCode = 1;
