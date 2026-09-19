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

// 解析与渲染住在一起、由 smoke 的往返测试钉住 —— 这里曾因为「只在一侧有实现」而把
// 机器覆盖度的分子分母取错，断言因此永远为真。
import { parseFieldCoverage } from "./report.js";

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

/** 从配方列表里取出 id（渲染形如 ``- `id` — 类型 → 产出``）。 */
function parseRecipeIds(text: string): string[] {
  return [...text.matchAll(/^- `([^`]+)`/gm)].flatMap((m) => (m[1] === undefined ? [] : [m[1]]));
}

interface RecipeEntry {
  id: string;
  typeLabel: string;
  opaque: boolean;
}

/**
 * 从配方列表里取出 id + 类型标签。
 *
 * 比只取 id 多一步是必要的：按某个物品查「哪些配方用它」时，
 * 结果里会混着不同的配方类型（拿模板查会同时命中「用模板升级装备」和
 * 「用模板复制模板」两条完全不同的链），只按 id 取第一条会取到错的那类。
 */
function parseRecipeEntries(text: string): RecipeEntry[] {
  const entries: RecipeEntry[] = [];
  for (const m of text.matchAll(/^- `([^`]+)` — (.+?) → /gm)) {
    if (m[1] === undefined || m[2] === undefined) continue;
    entries.push({ id: m[1], typeLabel: m[2].trim(), opaque: m[2].includes("读不懂") || false });
  }
  return entries;
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

/**
 * 本次运行**没能验证**的段落。
 *
 * 条件断言（「装了 X 才测」）最危险的失效方式是「条件不成立所以什么都没测，
 * 而输出看起来一切正常」。所以跳过的段落必须单独列出来，
 * 让「全部通过」这句话不至于误导人。
 */
const skippedSections: string[] = [];
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
  const coverage = parseFieldCoverage(statusText);
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
    // 覆盖度**完整性**只对「平台保证有耗时」的类型断言。
    //
    // 这条断言原本是「凡是被列进『带耗时』的类型都必须满覆盖」，在近原版实例上成立，
    // 一跑到整合包上就红了 —— 因为模组类型的耗时**可能部分缺失**：
    // Create 有些配方类型本身不允许指定 duration（`canSpecifyDuration()` 为 false），
    // 那时值就是 0，我们如实报 null。所以那里出现「N/M（M>N）」是正确行为，不是 bug。
    //
    // 现在只钉住真正的保证：原版那四种烹饪类型（Mod 侧 MUST_HAVE_DURATION 的定义）
    // 只要在这个包里存在，就必须 100% 读到耗时。
    const MUST_HAVE_DURATION = [
      "minecraft:smelting",
      "minecraft:blasting",
      "minecraft:smoking",
      "minecraft:campfire_cooking",
    ];
    const guaranteed = coverage.durationTypes.filter((t) => MUST_HAVE_DURATION.includes(t.type));
    const broken = guaranteed.filter((t) => t.withDuration !== t.total);
    check(
      "原版烹饪类型（熔炼/高炉/烟熏/营火）只要有配方就是满覆盖",
      broken.length === 0,
      broken.length > 0
        ? broken.map((t) => `${t.type} ${t.withDuration}/${t.total}`).join("、")
        : `核对到 ${guaranteed.length} 种：${guaranteed.map((t) => `${t.type} ${t.withDuration}/${t.total}`).join("、")}`,
    );
    // 一种都没核对到 = 烹饪适配器整个没生效。这不该在任何有熔炉配方的包里发生，
    // 所以判失败而不是跳过 —— 「没验到」和「验过了」必须分得开。
    // （注意类型列表会被截断，所以用 durationTypeCount 判断而不是列表长度。）
    check(
      "至少核对到一种原版烹饪类型（否则烹饪适配器没生效）",
      guaranteed.length > 0,
      `带耗时的类型共 ${coverage.durationTypeCount} 种，列表已截断时烹饪类型也可能没被列出来`,
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

  // ============================================================ 锻造（访问转换器）
  //
  // 27 条锻造配方曾经全被标成 opaque：SmithingRecipe 不覆写 getIngredients()，
  // 三个槽位（template / base / addition）藏在包私有字段里，通用接口一条都读不到。
  // 现在 mod 侧带了一份 accesstransformer.cfg 把它们变公开（JEI 做的是同一件事）。
  //
  // 这一节同时守着那个陷阱：**只补输入不处理产出会更糟。**
  // SmithingTrimRecipe.getResultItem() 返回硬编码的「铁胸甲 + 第一个纹饰」占位符，
  // 输入一旦非空、Readability 就会判它可读，那个占位符便从「被标记的假数据」
  // 升级成「一个理直气壮的答案」。所以下面既断言输入读到了，也断言假产物没出现。
  //
  // 怎么找到这些配方：用模板物品去查「哪些配方把它当输入」。
  // 这本身就是修复的端到端证据 —— 修复前锻造没有输入，倒排索引里查不到它们。
  // 标签刻意不带数量：近原版是 9 / 18 条，但整合包里这两个模板还会被别的模组加配方
  // （实测 All of Create 里 smithing 共 63 条）。写死数量只会让日志说谎。
  const SMITHING_PROBES = [
    { template: "minecraft:netherite_upgrade_smithing_template", trimming: false, label: "下界合金升级模板" },
    { template: "minecraft:sentry_armor_trim_smithing_template", trimming: true, label: "盔甲纹饰模板" },
  ] as const;

  for (const probe of SMITHING_PROBES) {
    const found = await client.callTool({
      name: "get_recipes_for_input",
      arguments: { item: probe.template },
    });
    const foundText = textOf(found);

    // 按模板查会同时命中两类配方：「用模板升级/饰纹装备」（我们要的）
    // 和「用模板复制模板」的合成配方。只按类型标签挑出锻造那批。
    // 这里不用 id 里的 "smithing" 做判据 —— 整合包可能给自定义 id。
    const entries = parseRecipeEntries(foundText);
    const smithing = entries.filter((e) => /smith/i.test(e.typeLabel));

    // 修复前这里必然是 0 条：锻造没有输入，倒排索引里就没有它。
    // 所以这一条同时是「AT 生效了」和「倒排索引把新输入索引进去了」的证据。
    check(
      `${probe.label}：按模板物品能查到锻造配方（修复前是 0 条）`,
      smithing.length > 0,
      `共 ${entries.length} 条命中，其中锻造 ${smithing.length} 条：` +
        entries.map((e) => `${e.id}(${e.typeLabel})`).join("、"),
    );

    if (smithing.length === 0) continue;

    const detail = await client.callTool({ name: "get_recipe_details", arguments: { recipeId: smithing[0]!.id } });
    const detailText = textOf(detail);
    process.stdout.write(`\n· 锻造样本 ${probe.label} → \`${smithing[0]!.id}\`\n${detailText}\n\n`);

    check(
      `${probe.label}：配方类型是 smithing`,
      parseType(detailText) === "minecraft:smithing",
      `type=${parseType(detailText)}`,
    );

    // 输入段的行：`- 1 × [minecraft:x | #tag]`
    const inputLines = (detailText.match(/^- \d+ (?:mB )?× \[/gm) ?? []).length;
    check(
      `${probe.label}：读到了 3 个输入槽位（template / base / addition）`,
      inputLines === 3,
      `输入行数=${inputLines}\n${detailText.slice(0, 400)}`,
    );

    const inputOnly = detailText.match(/输入：\n([\s\S]*?)\n\n输出：/)?.[1]?.split("\n") ?? [];

    if (probe.trimming) {
      // 纹饰的 base 是标签（#minecraft:trimmable_armor），断言它没被展开成物品列表。
      // 必须看**第 2 行**：只断言「输入里有 #」会假通过，因为模板复制那条合成配方的输入里也有标签。
      // 这也正是用 AT 而不是「遍历注册表测三个谓词」的理由 ——
      // 谓词只能得到物品集合，会把标签身份展开掉。
      check(
        `${probe.label}：base 槽（第 2 个输入）保留了标签，没被展开成物品列表`,
        (inputOnly[1] ?? "").includes("#"),
        `第 2 个输入 = ${inputOnly[1] ?? "（没有）"}`,
      );
    } else {
      // 升级配方：三个槽必须**互不相同**、且第一个是探针模板 —— 这才说明它们分别来自
      // template / base / addition 三个字段，而不是同一个字段被抄了三遍。
      //
      // 这里刻意**不**断言「base 是钻石工具」：那只在原版下界合金那 9 条上成立。
      // 实测 All of Create 里探针第一个命中的是
      // `create:crafting/appliances/netherite_backtank_from_netherite`，
      // base 是下界合金胸甲、addition 是铜背罐 —— 断言写死就会误报。
      const slots = inputOnly.slice(0, 3);
      check(
        `${probe.label}：三个槽互不相同，且第一个是探针模板（说明来自三个不同字段）`,
        slots.length === 3 && new Set(slots).size === 3 && (slots[0] ?? "").includes(probe.template),
        `输入 = ${slots.join(" | ")}`,
      );
    }

    if (probe.trimming) {
      // 纹饰的产出是组合式的（任意可饰纹盔甲 + 纹饰），无法用单一物品表达。
      // 诚实答案 = 标成读不懂，而不是拿 getResultItem 的占位符顶上去。
      check(
        `${probe.label}：标为读不懂（产出无法用单一物品表达，这是诚实的）`,
        detailText.includes("读不懂"),
        detailText.split("\n").slice(0, 8).join(" / "),
      );
      // ★ 这一条是整段的重点：硬编码占位符不许出现在产出里。
      check(
        `${probe.label}：没有报出 getResultItem 的假产物（铁胸甲）`,
        !/输出：[\s\S]*iron_chestplate/.test(detailText),
        detailText.slice(0, 500),
      );
    } else {
      check(
        `${probe.label}：不再读不懂，且产出是真的（下界合金件）`,
        !detailText.includes("读不懂") && /输出：[\s\S]*netherite_/.test(detailText),
        detailText.slice(0, 500),
      );
    }
  }

  // ============================================================ Create（软依赖适配器）
  //
  // mod 侧有 CreateAdapter，靠 Create 的 ProcessingRecipe API 读多产出 / 概率 / 流体 / 耗时。
  // 它是**软依赖**：没装 Create 时适配器根本不注册。
  //
  // 所以这一段是条件断言：
  //   装了 Create → 逐条验证适配器真的生效了
  //   没装       → **明确打印「本段未验证」**，而不是静默通过
  //
  // 后者是刻意的。这个项目最贵的一次教训就是「测试全绿而功能是死的」，
  // 而条件断言最危险的失效方式，正是「条件不成立所以什么都没测，但输出看着一切正常」。
  const allTypes = textOf(await client.callTool({ name: "list_recipe_types", arguments: {} }));
  const createTypes = [...allTypes.matchAll(/`(create:[a-z_]+)`/g)].map((m) => m[1]!);
  const hasCreate = createTypes.length > 0;

  if (!hasCreate) {
    skippedSections.push("Create 适配器（本实例没装 Create）");
    process.stdout.write(
      "\n⚠️  本实例没装 Create，**Create 适配器这段没有验证**。\n" +
        "   它读的是 ProcessingRecipe 的多产出/概率/流体/耗时，只有装了 Create 才跑得到。\n" +
        "   要验证请在装了 Create 的实例上跑（本项目的 P2 大包测试就是这种实例）。\n\n",
    );
  } else {
    process.stdout.write(`检测到 Create（${createTypes.length} 种配方类型）\n`);

    // 找一条 Create 的处理类配方：拿被加工的常见物品反查。
    // 用类型标签过滤，因为这些物品同时也有原版的合成/熔炼配方。
    const createProbes = ["minecraft:iron_ore", "minecraft:andesite", "minecraft:gravel"];
    let sample: { id: string; typeLabel: string } | undefined;
    for (const probe of createProbes) {
      const list = textOf(await client.callTool({ name: "get_recipes_for_input", arguments: { item: probe } }));
      sample = parseRecipeEntries(list).find((e) => /crushing|milling|mixing|compacting|pressing|splashing/i.test(e.typeLabel));
      if (sample) break;
    }

    check("在装了 Create 的实例上找到了处理类配方样本", sample !== undefined, `探测物品：${createProbes.join(" / ")}`);

    if (sample) {
      const detail = textOf(
        await client.callTool({ name: "get_recipe_details", arguments: { recipeId: sample.id } }),
      );
      process.stdout.write(`\n· Create 样本 → \`${sample.id}\`（${sample.typeLabel}）\n${detail}\n\n`);

      // 输出段里带「概率」的行是概率产出，其余是必然产出。分开数。
      // （原来把整段都算成必然产出，日志里会报出一个不可能的数字。）
      const outLines = (detail.match(/^输出：\n((?:- .*\n)*)/m)?.[1] ?? "")
        .split("\n")
        .filter((l) => l.startsWith("- "));
      const chances = [...detail.matchAll(/概率 ([\d.]+)%/g)].map((m) => Number(m[1]));
      const guaranteed = outLines.filter((l) => !l.includes("概率"));
      const hasFluid = /mB/.test(detail.split("输入：")[1] ?? "") || /- \d+ mB/.test(detail);

      // ① 耗时：Create 机器能不能算出真实台数的关键，而视图器给不了这个数据
      check(
        `Create 配方读到了耗时（${sample.typeLabel}）`,
        /- 耗时：\d+ 刻/.test(detail),
        detail.split("\n").slice(0, 8).join(" / "),
      );
      // ② 多产出：create:crushing 一条配方有 3 个产出。只读 getResultItem 的话只会回来 1 个，
      //    而且不会报错 —— 这正是这个适配器存在的理由。
      check(
        `Create 配方读到了多个产出（必然 ${guaranteed.length} 个 + 概率 ${chances.length} 个）`,
        guaranteed.length + chances.length >= 2,
        detail.slice(0, 500),
      );
      // ③ 概率：必须落在 0~1。Create 的 sequenced_assembly 用 chance 当权重（见过 120.0），
      //    那种值若漏进 chanceOutputs，期望值会被算成几十倍。
      check(
        "概率产出的概率都在 0~1 之间（权重语义没有漏进来）",
        chances.every((c) => c > 0 && c < 100),
        `概率 = ${chances.join(", ")}%`,
      );
      // ④ 流体：compacting 那种「燧石×2 + 砂砾 + 100mB 岩浆」里的岩浆。
      //    漏掉它不报错，只会让原料表看起来完整却少了东西。
      if (/compacting|mixing|filling/i.test(sample.typeLabel)) {
        check(`Create 的${sample.typeLabel}配方读到了流体`, hasFluid, detail.slice(0, 600));
      }
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

process.stdout.write(`\n${failures === 0 ? "全部通过" : `${failures} 项失败`}\n`);

// 跳过的段落单独报。没有这一段的话，「全部通过」会被读成「所有东西都验过了」，
// 而实际上条件断言里的那些一条都没跑 —— 那正是这个项目最贵的一次教训的形状。
if (skippedSections.length > 0) {
  process.stdout.write(
    `\n⚠️  未验证的段落（不是通过，是没测）：\n${skippedSections.map((s) => `     · ${s}`).join("\n")}\n`,
  );
}
process.stdout.write("\n");

if (failures > 0) process.exitCode = 1;
