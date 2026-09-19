/**
 * 跨语言契约验证。
 *
 *   npm run contract
 *
 * 前置：先在 mod 目录跑 `./gradlew test`（ContractDumpTest 会刷新样本）。
 *
 * <h2>它验证什么</h2>
 *
 * TypeScript 侧整套索引和处理链路，跑在 **Java 侧实际会发出的字节**上，
 * 而不是我手写的假数据上。具体来说它回答一个问题：
 *
 *   「Java 发出来的东西，TypeScript 到底吃不吃得下？」
 *
 * 这个问题之前从没被验证过 —— 两侧各自对着假数据开发，都照着 protocol.md 写，
 * 但契约本身没被跑通过。字段名差一个字母、null 处理不一致、数组和对象搞混，
 * 这些都要等用户进游戏、同时调两个系统时才会暴露，而且极难定位。
 *
 * 所以这里刻意**不**用手写的 fixture，全部来自 Java 导出的响应体。
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// 测试用临时缓存目录，不污染用户目录
const TMP_CACHE = fs.mkdtempSync(path.join(os.tmpdir(), "craftgraph-contract-"));
process.env.CRAFTGRAPH_CACHE_DIR = TMP_CACHE;

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = 25593;

const { startDumpBridge, loadBridgeDump, defaultDumpDir } = await import("./mock-bridge.js");
const { BridgeClient } = await import("./bridge.js");
const { RecipeStore } = await import("./cache.js");
const { buildRecipeTree, flattenTree } = await import("./tree.js");
const { calculatePlan } = await import("./plan.js");
const { renderTree } = await import("./report.js");

const DUMP_DIR = defaultDumpDir();

if (!fs.existsSync(DUMP_DIR)) {
  process.stderr.write(
    `\n找不到契约样本目录：${DUMP_DIR}\n` +
      `请先在 mod 目录运行 ./gradlew test 生成（ContractDumpTest 负责导出）。\n\n`,
  );
  process.exit(1);
}

// ---- 过期检测 ----
//
// 样本是 gradlew test 生成的。如果 Java 侧源码在样本生成之后改过，
// 那么这里跑的是**旧样本** —— 契约测试会假通过，而真实响应体可能已经变了。
// 这种假通过比测试失败危险得多，所以这里显式检查并告警。
const newestJavaSource = (() => {
  const root = path.resolve(HERE, "..", "..", "mod", "src", "main", "java");
  let newest = 0;
  const walk = (dir: string): void => {
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.name.endsWith(".java")) newest = Math.max(newest, fs.statSync(full).mtimeMs);
    }
  };
  walk(root);
  return newest;
})();

const newestDump = Math.max(...fs.readdirSync(DUMP_DIR).map((n) => fs.statSync(path.join(DUMP_DIR, n)).mtimeMs));

const dumpNames = Object.keys(loadBridgeDump(DUMP_DIR)).sort();
process.stdout.write(`\n契约样本目录：${DUMP_DIR}\n`);
process.stdout.write(`共 ${dumpNames.length} 个响应样本：${dumpNames.join(", ")}\n\n`);

const results: { name: string; ok: boolean; detail: string }[] = [];
let stalenessWarning = false;
function check(name: string, ok: boolean, detail = ""): void {
  results.push({ name, ok, detail });
}

const server = await startDumpBridge(DUMP_DIR, PORT);
const client = new BridgeClient({ host: "127.0.0.1", port: PORT, token: null, source: "env-url" });

try {
  // ============================================================ 整个加载链路
  // 这一步本身就在验契约：health → tags all → snapshot → registry 全部要能解析，
  // 任何字段名对不上都会在这里抛异常。
  let store;
  try {
    store = await RecipeStore.load(client);
    check("MCP Server 能用 Java 侧的真实响应建立缓存", true);
  } catch (err) {
    check("MCP Server 能用 Java 侧的真实响应建立缓存", false, err instanceof Error ? err.message : String(err));
    throw err;
  }

  if (newestJavaSource > newestDump) {
    process.stdout.write(
      "⚠️  契约样本比 Java 源码旧 —— 下面验的是旧样本，改了 DTO 的话这次结果不作数。\n" +
        "   重新生成：cd mod && ./gradlew test\n\n",
    );
    stalenessWarning = true;
  }

  const status = store.status;
  check(
    "配方数量与 Java 侧一致（health.recipeCount = 5）",
    status.recipeCount === 5,
    `实际 ${status.recipeCount}`,
  );
  check("标签数量解析正确（4 个）", status.tagCount === 4, `实际 ${status.tagCount}`);
  check("dataVersion 被正确读取（42）", status.dataVersion === 42, `实际 ${status.dataVersion}`);
  check("未误判为离线", !status.offline);

  // ============================================================ 索引语义
  // 这些验的是「Java 的建索引逻辑」与「TypeScript 的建索引逻辑」是否一致 ——
  // 两边各有一套独立的实现，跑在同一份数据上必须得出同样的结论。

  const consumers = store.recipesConsuming("item", "minecraft:iron_ingot").map((r) => r.id);
  check(
    "倒排索引：TS 侧也把标签展开了（用 iron_ingot 能查到 iron_block）",
    consumers.includes("minecraft:iron_block"),
    `实际：${JSON.stringify(consumers)}。Java 侧的 recipes-input.json 里也是这个结果`,
  );

  const producers = store.recipesProducing("item", "minecraft:iron_ingot").map((r) => r.id);
  check(
    "按产出查可用",
    producers.includes("minecraft:iron_ingot_from_smelting_iron_ore"),
    JSON.stringify(producers),
  );

  const nugget = store.recipesProducing("item", "minecraft:iron_nugget").map((r) => r.id);
  check(
    "概率产出也被索引（chanceOutputs 解析正确）",
    nugget.includes("create:crushing/iron_ore"),
    JSON.stringify(nugget),
  );

  const opaque = store.recipesProducing("item", "somemod:tungsten_steel_ingot");
  check(
    "opaque 配方解析正确且标记保留",
    opaque.length === 1 && opaque[0]!.opaque === true,
    JSON.stringify(opaque.map((r) => ({ id: r.id, opaque: r.opaque }))),
  );

  const obsidian = store.recipesProducing("item", "minecraft:obsidian");
  check(
    "流体槽位解析正确（kind=fluid、count=1000）",
    obsidian.length === 1 &&
      obsidian[0]!.inputs.length === 2 &&
      obsidian[0]!.inputs.every((i) => i.kind === "fluid" && i.count === 1000),
    JSON.stringify(obsidian[0]?.inputs ?? null),
  );

  const crushing = store.getRecipe("create:crushing/iron_ore");
  check(
    "duration / energy 数值解析正确（100 / 4400）",
    crushing?.duration === 100 && crushing?.energy === 4400,
    `duration=${crushing?.duration} energy=${crushing?.energy}`,
  );
  check(
    "null 字段保持 null（machine 有值、crafting 配方没耗时）",
    crushing?.machine === "create:crushing_wheel" &&
      store.getRecipe("minecraft:iron_block")?.duration === null,
    `machine=${crushing?.machine} craftingDuration=${store.getRecipe("minecraft:iron_block")?.duration}`,
  );

  // ============================================================ 中文显示名
  check(
    "注册表里的中文显示名被正确解析",
    store.itemName("minecraft:iron_ingot") === "铁锭",
    `实际：${store.itemName("minecraft:iron_ingot")}`,
  );

  // ============================================================ 引擎跑在真实数据上
  const tree = buildRecipeTree(store, "item", "minecraft:iron_block", 1);
  const nodes = flattenTree(tree.root);
  check(
    "配方树能在 Java 数据上展开",
    tree.root.kind === "craft" && nodes.length >= 3,
    `根节点 kind=${tree.root.kind}，共 ${nodes.length} 节点`,
  );
  check(
    "标签槽位被解析成具体物品并记录了来源",
    nodes.some((n) => n.chosenFromTag === "forge:ingots/iron" && n.item === "minecraft:iron_ingot"),
    nodes.map((n) => `${n.item}${n.chosenFromTag ? `(from #${n.chosenFromTag})` : ""}`).join(" → "),
  );

  const plan = calculatePlan(store, "item", "minecraft:iron_ingot", { ratePerMinute: 10 });
  check(
    "产线计算能在 Java 数据上跑通",
    plan.root.recipeId !== undefined && plan.machines.length > 0,
    `选中 ${plan.root.recipeId}，机器 ${JSON.stringify(plan.machines.map((m) => `${m.machine}×${m.count}`))}`,
  );

  // 渲染一遍，确认报告能生成（模板里用到 store 的显示名，也是契约的一部分）
  const rendered = renderTree(store, tree, "契约验证", 2);
  check("报告能正常渲染", rendered.includes("基础原料汇总") && rendered.length > 200, `${rendered.length} 字符`);

  process.stdout.write("─".repeat(70) + "\n用 Java 数据渲染出的配方树（截取）：\n" + "─".repeat(70) + "\n");
  process.stdout.write(rendered.split("\n").slice(0, 12).join("\n") + "\n\n");
} catch (err) {
  check("契约验证未抛出异常", false, err instanceof Error ? `${err.message}\n${err.stack}` : String(err));
} finally {
  if (server.listening) await new Promise<void>((r) => server.close(() => r()));
  fs.rmSync(TMP_CACHE, { recursive: true, force: true });
}

// ---------------------------------------------------------------- 输出
const failed = results.filter((r) => !r.ok);
for (const r of results) {
  process.stdout.write(`${r.ok ? "  ✅" : "  ❌"} ${r.name}\n`);
  if (!r.ok && r.detail) process.stdout.write(`       ${r.detail.replace(/\n/g, "\n       ")}\n`);
}
// 过期提醒必须在结论处再出现一次。测试都过了、只看最后一行的人很容易忽略开头的告警，
// 而「用旧样本跑出的全绿」是最危险的假通过。
if (stalenessWarning) {
  process.stdout.write("⚠️  注意：本次用的是过期的契约样本，全绿不代表当前代码通过。请先跑 cd mod && ./gradlew test。\n");
}

process.stdout.write(`\n${results.length - failed.length}/${results.length} 通过\n\n`);

if (failed.length > 0) {
  process.stdout.write("失败的检查：\n");
  for (const r of failed) process.stdout.write(`  - ${r.name}: ${r.detail}\n`);
  process.stdout.write("\n两侧契约不一致 —— 先看 doc/protocol.md，再看是哪一侧实现跑偏了。\n\n");
  // 用 exitCode 而不是 process.exit()：后者会在 libuv 句柄清理完成前强杀进程，
  // 在 Windows 上会打出 "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)" 的噪音。
  process.exitCode = 1;
}
