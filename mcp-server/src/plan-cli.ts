/**
 * 命令行入口：不经过 AI 客户端，直接算产线/配方树 —— 给整合包作者与排障用。
 *
 *   npm run plan -- --item create:brass_ingot --rate 100
 *   npm run plan -- --item create:brass_ingot --rate 100 --html
 *   npm run plan -- --item minecraft:iron_pickaxe --count 1 --tree
 *   npm run plan -- --item minecraft:iron_pickaxe --count 1 --tree --html
 *
 * 数据源与 MCP Server 完全相同：游戏在运行就读实时数据，
 * 否则读磁盘上的上次快照（~/.craftgraph/cache），所以**离线可用**。
 * 连快照都没有时会给一句人能看懂的指引，而不是堆栈。
 *
 * markdown 打到 stdout；--html 写入缓存目录的 explorer/ 并只打印文件路径
 * （与 MCP 工具的 format:"html" 是同一套渲染，口径一致）。
 */

import { BridgeClient } from "./bridge.js";
import { type StackKind } from "./cache.js";
import { resolveBridgeLocation } from "./config.js";
import { renderPlanHtml, renderTreeHtml, writeExplorerFile } from "./explorer.js";
import { StoreManager } from "./manager.js";
import { calculatePlan } from "./plan.js";
import { renderPlan, renderTree } from "./report.js";
import { buildRecipeTree } from "./tree.js";

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

function hasFlag(name: string): boolean {
  return process.argv.includes(`--${name}`);
}

const USAGE = `用法：
  npm run plan -- --item <物品id> --rate <每分钟产量> [--kind item|fluid] [--html]
  npm run plan -- --item <物品id> --count <个数> --tree [--kind item|fluid] [--html]

例：
  npm run plan -- --item create:brass_ingot --rate 100
  npm run plan -- --item minecraft:iron_pickaxe --count 1 --tree`;

async function main(): Promise<void> {
  const item = arg("item");
  if (!item || item.startsWith("--")) {
    process.stderr.write(`${USAGE}\n`);
    process.exit(1);
  }

  const kind: StackKind = arg("kind") === "fluid" ? "fluid" : "item";
  const tree = hasFlag("tree");
  const html = hasFlag("html");
  // markdown 模式的结构展示深度，与 MCP 工具的默认一致（2）。html 模式不受它影响 ——
  // 网页永远是全量结构，这正是它与 markdown 报告的分工。
  const depth = Number(arg("depth") ?? "2");

  const client = new BridgeClient(resolveBridgeLocation());
  const manager = new StoreManager(client);
  const store = await manager.get();

  const s = store.status;
  if (s.offline) {
    process.stderr.write(
      `[plan] 游戏未在运行，使用离线快照：${s.recipeCount} 条配方（dataVersion=${s.dataVersion}，可能过时）\n`,
    );
  }

  const name = store.itemName(item);
  const label = name === item ? item : `${name}（${item}）`;

  if (tree) {
    const count = Number(arg("count") ?? "1");
    if (!(count > 0)) {
      process.stderr.write(`--count 必须是正数，收到：${arg("count")}\n`);
      process.exit(1);
    }
    const result = buildRecipeTree(store, kind, item, count);
    if (html) {
      const p = writeExplorerFile(`tree-${item}-${count}`, renderTreeHtml(store, result, `${count} × ${label}`));
      process.stdout.write(`已生成：${p}\n`);
    } else {
      process.stdout.write(renderTree(store, result, `${count} × ${label}`, depth) + "\n");
    }
    return;
  }

  const rate = Number(arg("rate"));
  if (!(rate > 0)) {
    process.stderr.write(`需要 --rate <每分钟产量>（正数）。配方树模式用 --tree --count <个数>。\n\n${USAGE}\n`);
    process.exit(1);
  }
  const plan = calculatePlan(store, kind, item, { ratePerMinute: rate });
  if (html) {
    const p = writeExplorerFile(`plan-${item}-${rate}`, renderPlanHtml(store, plan, label));
    process.stdout.write(`已生成：${p}\n`);
  } else {
    process.stdout.write(renderPlan(store, plan, label, depth) + "\n");
  }
}

main().catch((err: unknown) => {
  process.stderr.write(`[plan] ${err instanceof Error ? err.message : String(err)}\n`);
  process.exit(1);
});
