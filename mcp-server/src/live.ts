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

// ---------------------------------------------------------------- 字段期望
//
// 写的是 **Minecraft 的事实**，不是本项目的行为 —— 对着反编译源码
// （neoforge-21.1.251-sources.jar）逐条核实过。整合包可以删掉某类配方，
// 但删不掉「熔炼配方带耗时」这件事，所以这些期望在任何包上都成立。
//
// duration:
//   true  = 必须读到耗时。这些类型都继承 AbstractCookingRecipe，构造时就必须传 cookingTime。
//   false = 必须是 null。这类配方**平台层面就没有耗时字段**；给它编一个数字，
//           产线会报出「60 个火把需要 2 张工作台」这种看起来精确、实际荒谬的机器数。
const FIELD_EXPECTATIONS: Record<string, { duration: boolean | null; machine: string | null }> = {
  "minecraft:smelting": { duration: true, machine: "minecraft:furnace" },
  "minecraft:blasting": { duration: true, machine: "minecraft:blast_furnace" },
  "minecraft:smoking": { duration: true, machine: "minecraft:smoker" },
  "minecraft:campfire_cooking": { duration: true, machine: "minecraft:campfire" },
  "minecraft:stonecutting": { duration: false, machine: "minecraft:stonecutter" },
  "minecraft:crafting": { duration: false, machine: "minecraft:crafting_table" },
};

interface Coverage {
  total: number;
  withDuration: number;
  withMachine: number;
  types: { type: string; withDuration: number; total: number }[];
}

/**
 * 从 get_bridge_status 的输出里解出字段覆盖度。
 *
 * 解析渲染后的 markdown 是有点脆的，但这是刻意的：agent 看到的就是这个字符串。
 * 如果 agent 读不到这两个数字，那它也就没法告诉玩家「机器数只对一部分配方有效」——
 * 断言和实际使用同一个输入，才不会出现「测试能测到、模型看不到」。
 */
function parseCoverage(text: string): Coverage | null {
  const m = text.match(/字段覆盖：耗时 (\d+)\/(\d+)[^·]*· 机器 (\d+)\/(\d+)/);
  if (!m) return null;

  const types: Coverage["types"] = [];
  const typeLine = text.match(/带耗时的类型：(.*)$/m);
  for (const part of typeLine?.[1]?.split("、") ?? []) {
    const t = part.match(/^([\w:.\-]+) (\d+)\/(\d+)$/);
    if (t) types.push({ type: t[1]!, withDuration: Number(t[2]), total: Number(t[3]) });
  }

  return {
    total: Number(m[2]),
    withDuration: Number(m[1]),
    withMachine: Number(m[4]),
    types,
  };
}

/** 从配方列表里取出 id（渲染形如 ``- `id` — 类型 → 产出``）。 */
function parseRecipeIds(text: string): string[] {
  return [...text.matchAll(/^- `([^`]+)`/gm)].flatMap((m) => (m[1] === undefined ? [] : [m[1]]));
}

function parseType(detail: string): string | null {
  return detail.match(/^- 类型：`([^`]+)`/m)?.[1] ?? null;
}

/** `- 耗时：200 刻（10 秒）` → 200；没有这一行 → null。 */
function parseDuration(detail: string): number | null {
  const m = detail.match(/^- 耗时：(\d+) 刻/m);
  return m ? Number(m[1]) : null;
}

/**
 * `- 机器：熔炉（\`minecraft:furnace\`）` 或 `` - 机器：`minecraft:furnace` `` → id。
 * 取第一个反引号里的内容即可：显示名是纯文本，代码里带反引号的只有 id 本身。
 */
function parseMachine(detail: string): string | null {
  const line = detail.match(/^- 机器：(.*)$/m)?.[1];
  return line?.match(/`([^`]+)`/)?.[1] ?? null;
}

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
  record("get_bridge_status（含字段覆盖度）", statusText);
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
  // ============================================================ 字段覆盖度
  //
  // 这一节是本次修复的重点，也是这个脚本存在的理由。
  //
  // duration / machine 曾经在 1290 条真实配方上**全是 null**：原版通用接口给不出它们，
  // 而没人去读各配方类自己的字段。后果是 calculate_production_plan 每个环节都报「手工」，
  // 机器数计算整个失效 —— 而**四个测试层全部通过**，因为夹具里手写了 duration。
  //
  // 教训：**任何下游要用的字段，live 层都必须断言。**
  // 下面这些期望写的是 Minecraft 的事实（对着反编译源码核实过），不是本项目的行为，
  // 所以它们在任何整合包上都成立：包可以删掉配方，但删不掉「熔炼配方带耗时」这件事。

  // ---- 先看聚合覆盖度（一个调用就能发现「字段全是 null」这种退化）----
  const coverage = parseCoverage(statusText);
  check(
    "get_bridge_status 报出字段覆盖度（否则「字段悄悄全是 null」没人看得见）",
    coverage !== null,
    statusText.split("\n").slice(0, 8).join(" / "),
  );
  if (coverage) {
    check(
      `真实数据里有配方读到了耗时（${coverage.withDuration}/${coverage.total}）` +
        "—— 修复前这里是 0，产线计算因此全报「手工」",
      coverage.withDuration > 0,
      `耗时 ${coverage.withDuration}/${coverage.total}`,
    );
    check(
      `真实数据里有配方读到了机器（${coverage.withMachine}/${coverage.total}）`,
      coverage.withMachine > 0,
      `机器 ${coverage.withMachine}/${coverage.total}`,
    );
    // 列出来的每个类型都必须是满覆盖：出现 N/M（M>N）说明部分配方走了别的代码路径，
    // 而「部分读不到」和「全读不到」是同一个 bug 的两副面孔。
    const partial = coverage.types.filter((t) => t.withDuration !== t.total);
    check(
      "被列出「带耗时」的配方类型都是满覆盖",
      partial.length === 0,
      partial.map((t) => `${t.type} ${t.withDuration}/${t.total}`).join("、"),
    );
  }

  // ---- 再逐条核对真实配方的字段值 ----
  //
  // 只看总数会漏掉「数量对但值错」：比如机器全报成工作台。
  // 所以这里把配方按类型捞出来，逐条比对耗时和机器。
  const checked: { id: string; type: string; duration: number | null; machine: string | null }[] = [];
  for (const probe of ["minecraft:iron_ingot", "minecraft:cooked_beef", "minecraft:stone_stairs"]) {
    const list = await client.callTool({ name: "get_recipes_for_output", arguments: { item: probe } });
    const listText = textOf(list);
    if (list.isError || listText.includes("没有找到")) continue;

    for (const id of parseRecipeIds(listText)) {
      const detail = await client.callTool({ name: "get_recipe_details", arguments: { recipeId: id } });
      if (detail.isError) continue;
      const detailText = textOf(detail);
      const type = parseType(detailText);
      if (!type || !(type in FIELD_EXPECTATIONS)) continue;
      checked.push({
        id,
        type,
        duration: parseDuration(detailText),
        machine: parseMachine(detailText),
      });
    }
  }

  process.stdout.write("逐条核对的真实配方：\n");
  for (const c of checked) {
    process.stdout.write(
      `  ${c.type}  ${c.id}\n` +
        `    耗时=${c.duration ?? "null"}  机器=${c.machine ?? "null"}\n`,
    );
  }
  process.stdout.write("\n");

  // 「一条都没核对到」必须是失败而不是静默通过 —— 这正是本次要修的教训的反面。
  // 探测用的物品都是原版的，真出现 0 条说明查询链路本身有问题。
  check(
    `逐条核对到了真实配方（${checked.length} 条）`,
    checked.length > 0,
    "探测物品：minecraft:iron_ingot / minecraft:cooked_beef / minecraft:stone_stairs",
  );

  const cooking = checked.filter((c) => FIELD_EXPECTATIONS[c.type]?.duration === true);
  check(
    `核对到了烹饪类配方（${cooking.length} 条）—— 耗时这一项只在它们身上能验证`,
    cooking.length > 0,
    `核对到的类型：${[...new Set(checked.map((c) => c.type))].join("、") || "（无）"}`,
  );

  for (const c of checked) {
    const want = FIELD_EXPECTATIONS[c.type];
    if (!want) continue;
    if (want.duration === true) {
      check(
        `${c.type} 读到了耗时（${c.id}）`,
        c.duration !== null && c.duration > 0,
        `duration=${c.duration}`,
      );
    } else if (want.duration === false) {
      // 反向断言同样重要：不能为了「让机器数算得出来」就给所有配方编一个耗时。
      // crafting 一旦有了耗时，产线会报「每分钟 60 个火把需要 2 张工作台」——
      // 一个看起来精确、实际荒谬的数字。
      check(
        `${c.type} 的耗时必须是 null（不是「瞬间完成」，是「没有这个字段」）`,
        c.duration === null,
        `${c.id} duration=${c.duration}`,
      );
    }

    if (want.machine !== null) {
      check(`${c.type} 的机器是 ${want.machine}`, c.machine === want.machine, `${c.id} machine=${c.machine}`);
    }
  }

  // ============================================================ 产线（机器数）
  //
  // 字段补上之后，这一段才真正验证到「机器数算得出来」。
  // 修复前它只证明「没崩」—— 每个环节都报「手工」也算通过，这就是那个假通过。
  //
  // 目标是玻璃：它在原版只有一条配方（熔炼沙子），所以产线里**必然**有一个熔炉环节。
  // 用它而不是火把，是因为火把的链全是工作台合成 —— 而工作台合成本来就没有耗时，
  // 报「手工」是正确的。拿它做断言会把正确答案判成失败。
  const glassPlan = textOf(
    await client.callTool({
      name: "calculate_production_plan",
      arguments: { item: "minecraft:glass", ratePerMinute: 60 },
    }),
  );
  record("产线规划（每分钟 60 个玻璃，含熔炼环节）", glassPlan);
  check(
    "产线算出了熔炉台数（修复前这里恒为「手工」）",
    glassPlan.includes("minecraft:furnace") && !glassPlan.includes("没有可计算机器数的环节"),
    glassPlan.split("\n").slice(0, 400).join("\n"),
  );

  // 工作台合成不该被报成机器。这是反向断言：防止「给所有配方编一个耗时」这种假修复。
  const torchPlan = textOf(
    await client.callTool({
      name: "calculate_production_plan",
      arguments: { item: "minecraft:torch", ratePerMinute: 60 },
    }),
  );
  check(
    "工作台合成仍然报「手工」，没被编出一个机器台数",
    !/工作台.*\d+ 台/.test(torchPlan),
    torchPlan.split("\n").find((l) => l.includes("工作台")) ?? torchPlan.slice(0, 300),
  );

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
