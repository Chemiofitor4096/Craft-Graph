/**
 * 端到端冒烟测试：进程内启动假 Bridge，跑完整 HTTP + 索引 + 算法链路。
 *
 *   npm run smoke
 *
 * 覆盖的都是「写错了但看起来能跑」的地方：
 *   - 标签展开后的倒排索引（漏了它，"哪些配方用铁锭"会漏掉用标签的配方）
 *   - 循环配方的识别与规避（只看一级输入是不够的）
 *   - opaque 配方必须和「没有配方」区分开
 *   - 机器数换算、副产统计
 *   - 离线降级
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// 必须在 import cache.js 之前设置：测试用临时缓存目录，不污染用户目录
const TMP_CACHE = fs.mkdtempSync(path.join(os.tmpdir(), "craftgraph-smoke-"));
process.env.CRAFTGRAPH_CACHE_DIR = TMP_CACHE;

const { startMockBridge } = await import("./mock-bridge.js");
const { BridgeClient } = await import("./bridge.js");
const { RecipeStore } = await import("./cache.js");
const { buildRecipeTree, flattenTree, pruneTree, subtreeStats } = await import("./tree.js");
const { pickCanonical } = await import("./resolution.js");
const { calculatePlan, prunePlan } = await import("./plan.js");
const { renderPlan, renderPlanTsv, renderTree, renderTreeTsv, computeFieldCoverage, renderFieldCoverage, parseFieldCoverage } =
  await import("./report.js");
const { opaqueHint } = await import("./opaque.js");

const PORT = 25599;
const results: { name: string; ok: boolean; detail: string }[] = [];

function check(name: string, ok: boolean, detail = ""): void {
  results.push({ name, ok, detail });
}

function findNode(nodes: ReturnType<typeof flattenTree>, pred: (n: ReturnType<typeof flattenTree>[number]) => boolean) {
  return nodes.find(pred);
}

const server = await startMockBridge(PORT);
const client = new BridgeClient({
  host: "127.0.0.1",
  port: PORT,
  token: null,
  source: "env-url",
});

try {
  // ============================================================ 加载与索引
  const store = await RecipeStore.load(client);
  const status = store.status;

  check("缓存加载成功", status.recipeCount === 14, `recipeCount=${status.recipeCount}（期望 14）`);
  check("标签加载成功", status.tagCount === 5, `tagCount=${status.tagCount}（期望 5）`);
  check("在线状态标记正确", status.offline === false, `offline=${status.offline}`);

  // --- 关键：标签必须展开进倒排索引 ---
  // minecraft:iron_block 的输入是标签 #forge:ingots/iron，不是直接的 iron_ingot。
  // 如果建索引时没展开标签，这里就会漏掉它。
  const ironIngotConsumers = store.recipesConsuming("item", "minecraft:iron_ingot").map((r) => r.id);
  check(
    "倒排索引展开了标签（哪些配方用铁锭）",
    ironIngotConsumers.includes("minecraft:iron_block"),
    `结果：${JSON.stringify(ironIngotConsumers)}，期望包含 minecraft:iron_block`,
  );

  // --- 概率产出也应该能被查到 ---
  const nuggetProducers = store.recipesProducing("item", "minecraft:iron_nugget").map((r) => r.id);
  check(
    "概率产出也能被查询到",
    nuggetProducers.includes("create:crushing/iron_ore"),
    `结果：${JSON.stringify(nuggetProducers)}`,
  );

  // ============================================================ 标签成员挑选规则
  //
  // 这些断言来自真实游戏里的表现：原来的「字典序优先」在真实标签上挑出来的东西
  // 很反直觉（煤炭挑成木炭、橡木板挑成金合欢木板），换成「短 id 优先」才对。
  check(
    "标签挑选：煤炭优先于木炭（短 id）",
    pickCanonical(["minecraft:coal", "minecraft:charcoal"]) === "minecraft:coal",
    pickCanonical(["minecraft:coal", "minecraft:charcoal"]),
  );
  check(
    "标签挑选：橡木板优先于金合欢木板",
    pickCanonical(["minecraft:acacia_planks", "minecraft:oak_planks", "minecraft:birch_planks"]) ===
      "minecraft:oak_planks",
    pickCanonical(["minecraft:acacia_planks", "minecraft:oak_planks", "minecraft:birch_planks"]),
  );
  check(
    "标签挑选：橡木原木优先于金合欢原木",
    pickCanonical(["minecraft:acacia_log", "minecraft:oak_log"]) === "minecraft:oak_log",
    pickCanonical(["minecraft:acacia_log", "minecraft:oak_log"]),
  );
  check(
    "标签挑选：minecraft 命名空间优先于模组（即使模组 id 更短）",
    pickCanonical(["mod:x", "minecraft:iron_ingot"]) === "minecraft:iron_ingot",
    pickCanonical(["mod:x", "minecraft:iron_ingot"]),
  );
  check(
    "标签挑选：结果稳定可复现（同样输入必出同样结果）",
    (() => {
      const members = ["minecraft:b", "minecraft:a", "minecraft:cc", "minecraft:dd"];
      const first = pickCanonical(members);
      for (let i = 0; i < 10; i++) {
        if (pickCanonical([...members]) !== first) return false;
      }
      return first === "minecraft:a";
    })(),
  );

  // ============================================================ 配方树
  const tree = buildRecipeTree(store, "item", "minecraft:iron_block", 1);
  const treeNodes = flattenTree(tree.root);

  check(
    "配方树根节点选对了配方",
    tree.root.kind === "craft" && tree.root.recipeId === "minecraft:iron_block",
    `kind=${tree.root.kind} recipeId=${tree.root.recipeId}`,
  );

  const tagNode = findNode(treeNodes, (n) => n.chosenFromTag === "forge:ingots/iron");
  check(
    "标签槽位被解析成具体物品并记录了来源",
    tagNode !== undefined && tagNode.item === "minecraft:iron_ingot",
    tagNode ? `选定 ${tagNode.item}（来自 #${tagNode.chosenFromTag}）` : "没有找到带 chosenFromTag 的节点",
  );

  check(
    "自动避开了循环配方（铁锭没有走「铁块分解」）",
    !treeNodes.some((n) => n.kind === "cycle"),
    treeNodes.filter((n) => n.kind === "cycle").map((n) => n.note).join("; ") || "无循环节点",
  );

  check(
    "基础原料汇总正确",
    tree.rawMaterials.length === 1 && tree.rawMaterials[0]!.count === 9,
    JSON.stringify(tree.rawMaterials),
  );

  // ============================================================ 分层返回
  //
  // 这是整个「先给骨架」设计的前提：裁剪只影响「展示多少结构」，
  // 原料汇总必须仍然是全树算出来的。这条一旦坏掉，agent 会拿到偏低且看不出问题的原料表 ——
  // 比「结果太贵」严重得多，所以专门测。

  const prunedAt0 = pruneTree(tree.root, 0);
  const rootStats = prunedAt0.subtree;
  check(
    "detailDepth=0 时根节点的子树统计等于全树原料",
    rootStats !== undefined &&
      rootStats.rawMaterials.length === tree.rawMaterials.length &&
      rootStats.rawMaterials.every((m, i) => {
        const f = tree.rawMaterials[i];
        return f !== undefined && f.item === m.item && f.count === m.count;
      }),
    `子树统计=${JSON.stringify(rootStats?.rawMaterials ?? null)} 全树=${JSON.stringify(tree.rawMaterials)}`,
  );

  check(
    "detailDepth=0 时根节点统计的节点数与全树一致",
    rootStats?.nodeCount === tree.nodeCount,
    `子树 ${rootStats?.nodeCount} vs 全树 ${tree.nodeCount}`,
  );

  const prunedAt2 = pruneTree(tree.root, 2);
  const visibleAt2 = flattenTree(prunedAt2).length;
  const visibleFull = flattenTree(tree.root).length;
  check(
    "裁剪确实减少了可见节点",
    visibleAt2 < visibleFull,
    `depth=2 可见 ${visibleAt2} 节点，完整 ${visibleFull} 节点`,
  );

  // 渲染层面同样要验证：原料表两版必须一致
  const mdLayered = renderTree(store, { ...tree, root: prunedAt2 }, "test", 2);
  const mdFull = renderTree(store, tree, "test", 64);
  const rawTableOf = (md: string): string => {
    const idx = md.indexOf("## 基础原料汇总");
    return idx >= 0 ? md.slice(idx).split("\n\n")[1] ?? "" : "";
  };
  check(
    "裁剪版与完整版的原料表一致",
    // 用反引号包住 id 精确匹配：写成 includes("iron_ore") 的话，
    // "minecraft:deepslate_iron_ore" 也会命中，测不出挑错了哪个。
    rawTableOf(mdLayered) === rawTableOf(mdFull) && rawTableOf(mdFull).includes("`minecraft:iron_ore`"),
    `裁剪版=${JSON.stringify(rawTableOf(mdLayered))} 完整版=${JSON.stringify(rawTableOf(mdFull))}`,
  );
  check(
    "裁剪版明显更短",
    mdLayered.length < mdFull.length,
    `裁剪版 ${mdLayered.length} 字符，完整版 ${mdFull.length} 字符`,
  );
  check(
    "裁剪后出现「未展开」标注",
    mdLayered.includes("⋯"),
    mdLayered.split("\n").filter((l) => l.includes("⋯")).join(" / ") || "(没有找到 ⋯)",
  );

  // ---- TSV 输出 ----
  const tsv = renderTreeTsv(store, { ...tree, root: prunedAt2 }, "test", 2);
  check("TSV 输出带列名头（数据自描述）", tsv.includes("# 列 depth|parent|count|item|recipe|type"), tsv.split("\n")[3] ?? "");
  check(
    "TSV 的数据行列数与表头一致",
    (() => {
      const header = tsv.split("\n").find((l) => l.startsWith("# 列 depth|"));
      const cols = header ? header.replace("# 列 ", "").split("|").length : 0;
      const row = tsv.split("\n").find((l) => /^\d+\|/.test(l));
      return cols > 0 && row !== undefined && row.split("|").length === cols;
    })(),
    tsv.split("\n").filter((l) => /^\d+\|/.test(l))[0] ?? "(没有数据行)",
  );
  check("TSV 里包含基础原料段", tsv.includes("# 基础原料（全树合计）"));

  const tsvPlan = renderPlanTsv(
    store,
    calculatePlan(store, "item", "minecraft:iron_ingot", { ratePerMinute: 10 }),
    "test",
    2,
  );
  check(
    "产线 TSV 包含机器/原料/能耗三段",
    tsvPlan.includes("# 机器") && tsvPlan.includes("# 每分钟原料") && tsvPlan.includes("# 能耗"),
  );

  // ---- 「耗时未知」的归因不能糊成一句（在 Create 整合包上实测过这个误导）----
  //
  // 原来产线头部只写「有 N 个环节耗时未知（通常是工作台合成）」，而同一条输出下面
  // 可能写着「用 create:filling 制作」—— 填充机是机器不是工作台。
  // 把「我们读不到」说成「原版就这样」会让玩家照着建错产线，也会让模型以为不用配比。
  const workbenchPlan = renderPlan(
    store,
    calculatePlan(store, "item", "minecraft:torch", { ratePerMinute: 60 }),
    "test",
    2,
  );
  check(
    "工作台合成的环节仍报「手工」，且说明原因是原版没有耗时字段",
    workbenchPlan.includes("工作台合成") && workbenchPlan.includes("原版设计如此"),
    workbenchPlan.split("\n").slice(0, 12).join(" / "),
  );
  // ---- 选路：用「合并后的槽位数」而不是原始槽位数 ----
  //
  // 原版有序合成把「8 个金锭 + 1 个苹果」表示成 9 个槽位，那是 3×3 网格的产物不是复杂度。
  // 用原始槽位数会让任何 2 输入的机器配方碾压它 —— 实测 All of Create 里
  // 「金苹果怎么做」因此选了发酵（还带 250mB 药水），而不是工作台。
  //
  // 夹具里两条候选：一条 8 槽位但只有 2 种不同输入，一条 3 槽位三种不同输入。
  // 按合并后的数量算，前者（2）应当胜过后者（3）。
  const gadgetTree = renderTree(
    store,
    buildRecipeTree(store, "item", "examplepack:gadget", 1),
    "test",
    2,
  );
  check(
    "★ 选路按合并后的输入数：8 槽位/2 种输入的合成配方胜过 3 槽位/3 种输入的机器配方",
    gadgetTree.includes("examplepack:gadget_shaped") && !gadgetTree.includes("examplepack:gadget_machined"),
    gadgetTree.split("\n").slice(0, 8).join(" / "),
  );

  check(
    "★ 不再把所有「耗时未知」都说成「工作台合成」",
    !workbenchPlan.includes("通常是工作台合成") && !workbenchPlan.includes("多为工作台合成"),
    workbenchPlan.split("\n").slice(0, 12).join(" / "),
  );

  // 模组机器配方没有耗时（夹具里的 examplepack:widget_pressing）：必须说成「读不到数据」，
  // 而且要带上覆盖度，这样模型不必为了这句话专门再去调 get_bridge_status。
  const machinePlan = renderPlan(
    store,
    calculatePlan(store, "item", "examplepack:widget", { ratePerMinute: 60 }),
    "test",
    2,
  );
  check(
    "★ 机器加工但读不到耗时时，说成「读不到数据」而不是「手工合成」",
    machinePlan.includes("机器加工") && machinePlan.includes("读不到耗时"),
    machinePlan.split("\n").slice(0, 12).join(" / "),
  );
  check(
    "该场景下产线自带覆盖度说明（省掉一次 get_bridge_status）",
    /耗时只覆盖 \d+\/\d+ 条/.test(machinePlan),
    machinePlan.split("\n").slice(0, 14).join(" / "),
  );

  // ---- 读不懂的提示文案 ----
  //
  // 原来写「装 EMI 或加适配层后可以读到」：方案已改成 JEI 优先、而且「适配层」是内部概念。
  // 更要紧的是它没区分「模组格式我们没覆盖」（值得写适配器）和「原版代码驱动」（谁都读不到）。
  const moddedHint = opaqueHint(["create:mixing"]);
  check(
    "读不懂的提示对模组类型说的是「还没覆盖，可以写适配器」",
    moddedHint.includes("create:mixing") && moddedHint.includes("适配器") && !moddedHint.includes("EMI"),
    moddedHint,
  );
  const vanillaHint = opaqueHint(["minecraft:crafting"]);
  check(
    "对原版代码驱动的类型说明是平台限制（不说成我们的缺口）",
    vanillaHint.includes("代码驱动") && !vanillaHint.includes("适配器"),
    vanillaHint,
  );
  check(
    "两种原因混在一起时两句话都要有",
    opaqueHint(["create:mixing", "minecraft:crafting"]).includes("适配器") &&
      opaqueHint(["create:mixing", "minecraft:crafting"]).includes("代码驱动"),
    opaqueHint(["create:mixing", "minecraft:crafting"]),
  );
  check(
    "提示里都给出可执行的下一步（用 JEI 核对）",
    opaqueHint(["create:mixing"]).includes("JEI"),
    opaqueHint(["create:mixing"]),
  );

  // ---- 覆盖度：渲染 → 解析 必须回到原值 ----
  //
  // 这条测试是为了一个真实的 bug：解析实现写在 live.ts 里（唯一使用者），于是没有测试碰得到它，
  // 结果它把「机器」那一对数的分母当成了分子。症状很阴险 —— 断言打印「15241/15241」而真实是
  // 「10945/15241」，而且 `withMachine > 0` 因为拿到分母**永远为真**，那条断言不可能失败。
  // 在覆盖度恰好 100% 的实例上永远看不出来，第一次跑到覆盖不全的整合包上才露出来。
  //
  // 所以下面刻意让三个数字**互不相等**：字段取错时往返就会不匹配。
  const fakeRecipes = [
    { type: "minecraft:smelting", duration: 200, machine: "minecraft:furnace" },
    { type: "minecraft:smelting", duration: 200, machine: null },
    { type: "create:crushing", duration: null, machine: null },
  ] as unknown as import("./types.js").Recipe[];
  const computed = computeFieldCoverage(fakeRecipes);
  const parsed = parseFieldCoverage(renderFieldCoverage(computed).join("\n"));
  check(
    "覆盖度往返一致（渲染 → 解析回到原值）",
    parsed !== null &&
      parsed.total === computed.total &&
      parsed.withDuration === computed.withDuration &&
      parsed.withMachine === computed.withMachine,
    `计算 ${computed.withDuration}/${computed.total} 机器 ${computed.withMachine}；` +
      `解析 ${parsed?.withDuration}/${parsed?.total} 机器 ${parsed?.withMachine}`,
  );
  check(
    "★ 覆盖度三个数字互不相等时也不串位（这正是那个取错分子的 bug）",
    computed.total !== computed.withDuration &&
      computed.total !== computed.withMachine &&
      parsed?.withMachine === computed.withMachine &&
      parsed?.withMachine !== parsed?.total,
    `机器 ${parsed?.withMachine}/${parsed?.total}（若等于总数说明又取成分母了）`,
  );
  check(
    "类型列表被截断时能读出总种数（不能把列出来的当成全部）",
    (() => {
      const many = Array.from({ length: 12 }, (_, i) => ({
        type: `somemod:t${i}`,
        duration: 10,
        machine: "somemod:m",
      })) as unknown as import("./types.js").Recipe[];
      const rendered = renderFieldCoverage(computeFieldCoverage(many)).join("\n");
      const back = parseFieldCoverage(rendered);
      return back !== null && back.durationTypeCount === 12 && back.durationTypes.length < 12;
    })(),
    "12 种类型应该只列出前 8 种，但总数要能读回来",
  );

  // ---- 产线裁剪 ----
  const planFullForPrune = calculatePlan(store, "item", "minecraft:iron_ingot", { ratePerMinute: 10 });
  const planPruned = { ...planFullForPrune, root: prunePlan(planFullForPrune.root, 0) };
  check(
    "产线裁剪不改变汇总数据（机器/原料/副产）",
    planPruned.machines.length === planFullForPrune.machines.length &&
      planPruned.rawMaterials.length === planFullForPrune.rawMaterials.length &&
      planPruned.byproducts.length === planFullForPrune.byproducts.length,
    `机器 ${planPruned.machines.length} 原料 ${planPruned.rawMaterials.length} 副产 ${planPruned.byproducts.length}`,
  );
  check(
    "产线裁剪后根节点带未展开统计",
    planPruned.root.subtree !== undefined && planPruned.root.subtree.nodeCount > 1,
    JSON.stringify(planPruned.root.subtree ?? null),
  );
  check(
    "产线 markdown 在裁剪版里显示未展开标注",
    renderPlan(store, planPruned, "test", 0).includes("该分支未展开"),
    "",
  );

  // --- 强制指定会成环的配方，验证环能被识别并如实报告 ---
  const forced = buildRecipeTree(store, "item", "minecraft:iron_block", 1, {
    recipeChoice: { "minecraft:iron_ingot": "minecraft:iron_ingot_from_iron_block" },
  });
  const forcedNodes = flattenTree(forced.root);
  const cycleNode = findNode(forcedNodes, (n) => n.kind === "cycle");
  check(
    "强制指定循环配方时能识别出循环",
    cycleNode !== undefined,
    cycleNode?.note ?? "没有检测到循环",
  );

  // --- opaque 配方必须和「没有配方」区分开 ---
  const opaqueTree = buildRecipeTree(store, "item", "somemod:tungsten_steel_ingot", 1);
  check(
    "opaque 配方被标记为「读不懂」而非「基础原料」",
    opaqueTree.root.kind === "opaque" && opaqueTree.root.note?.includes("读不懂") === true,
    `kind=${opaqueTree.root.kind} note=${opaqueTree.root.note ?? "(无)"}`,
  );

  // --- 回归：显式传入 undefined 的选项不能被当成「覆盖成空」 ---
  // MCP 工具处理器会把每个参数都列出来，没传的就是 undefined。
  // 如果合并选项时用了展开运算，这里会把默认值抹成 undefined 然后崩溃。
  let explicitUndefinedOk = false;
  let explicitUndefinedDetail = "";
  try {
    const r = buildRecipeTree(store, "item", "minecraft:iron_block", 1, {
      maxDepth: undefined,
      recipeChoice: undefined,
      tagChoice: undefined,
      rawMaterials: undefined,
    });
    explicitUndefinedOk = r.root.kind === "craft";
    explicitUndefinedDetail = `kind=${r.root.kind}`;
  } catch (err) {
    explicitUndefinedDetail = err instanceof Error ? err.message : String(err);
  }
  check("选项里显式传 undefined 时仍使用默认值", explicitUndefinedOk, explicitUndefinedDetail);

  // --- 真·基础原料 ---
  const rawTree = buildRecipeTree(store, "item", "minecraft:coal", 1);
  check(
    "没有配方的物品被判定为基础原料",
    rawTree.root.kind === "raw",
    `kind=${rawTree.root.kind} note=${rawTree.root.note ?? "(无)"}`,
  );

  // ============================================================ 产线计算
  const plan = calculatePlan(store, "item", "minecraft:iron_ingot", { ratePerMinute: 10 });

  check(
    "产线避开了循环路线（没选「1 铁块 → 9 铁锭」）",
    plan.root.recipeId === "examplepack:iron_ingot_from_crushed_iron",
    `选中 ${plan.root.recipeId}`,
  );

  check(
    "每分钟合成次数正确",
    plan.root.craftsPerMinute === 10,
    `craftsPerMinute=${plan.root.craftsPerMinute}（每次产 1 个，目标 10/分）`,
  );

  // 10 次/分 × 200 刻 ÷ 1200 刻每分钟 = 1.67 → 向上取整 2 台
  const furnace = plan.machines.find((m) => m.machine === "minecraft:furnace");
  check("熔炉数量正确（10/分、200刻/次 → 2 台）", furnace?.count === 2, `furnace=${furnace?.count}`);

  // 10 次/分 × 100 刻 ÷ 1200 = 0.83 → 1 台
  const crusher = plan.machines.find((m) => m.machine === "create:crushing_wheel");
  check("粉碎轮数量正确（10/分、100刻/次 → 1 台）", crusher?.count === 1, `crusher=${crusher?.count}`);

  check(
    "基础原料速率正确",
    plan.rawMaterials.length === 1 && plan.rawMaterials[0]!.ratePerMinute === 10,
    JSON.stringify(plan.rawMaterials),
  );

  // 0.75 概率 × 1 个 × 10 次/分 = 7.5/分
  const nugget = plan.byproducts.find((b) => b.item === "minecraft:iron_nugget");
  check(
    "概率副产按期望值统计",
    nugget !== undefined && Math.abs(nugget.ratePerMinute - 7.5) < 1e-9 && nugget.probabilistic,
    JSON.stringify(nugget),
  );

  check(
    "读不到能耗时明确返回 null 而不是 0",
    plan.totalEnergyPerMinute === null,
    `totalEnergyPerMinute=${plan.totalEnergyPerMinute}`,
  );

  // --- 手工合成的环节不能谎报机器数 ---
  const torchPlan = calculatePlan(store, "item", "minecraft:torch", { ratePerMinute: 8 });
  check(
    "工作台合成不谎报机器数",
    torchPlan.root.machines === null && torchPlan.manualSteps > 0,
    `machines=${torchPlan.root.machines} manualSteps=${torchPlan.manualSteps}`,
  );

  // ============================================================ 离线降级
  store.allRecipes(); // 确保快照已写盘（saveToDisk 在 load 时已执行）
  await new Promise<void>((resolve) => server.close(() => resolve()));

  const offlineStore = await RecipeStore.load(client);
  check(
    "游戏关掉后能退到磁盘快照",
    offlineStore.status.offline === true && offlineStore.status.recipeCount === 14,
    `offline=${offlineStore.status.offline} recipeCount=${offlineStore.status.recipeCount}`,
  );

  const offlineQuery = offlineStore.recipesProducing("item", "minecraft:iron_ingot");
  check("离线状态下配方查询仍可用", offlineQuery.length === 4, `找到 ${offlineQuery.length} 条（期望 4）`);
} catch (err) {
  check("测试执行未抛出异常", false, err instanceof Error ? `${err.message}\n${err.stack}` : String(err));
} finally {
  // 离线测试里已经关过一次 server，重复 close 会让回调悬空，
  // 进程退出时 libuv 会报 "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)"。
  // 所以先判断是否还在监听。
  if (server.listening) {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
  fs.rmSync(TMP_CACHE, { recursive: true, force: true });
}

// ---------------------------------------------------------------- 输出
const passed = results.filter((r) => r.ok).length;
const failed = results.filter((r) => !r.ok);

process.stdout.write("\n");
for (const r of results) {
  process.stdout.write(`${r.ok ? "  ✅" : "  ❌"} ${r.name}\n`);
  if (!r.ok && r.detail) process.stdout.write(`       ${r.detail.replace(/\n/g, "\n       ")}\n`);
}
process.stdout.write(`\n${passed}/${results.length} 通过\n\n`);

if (failed.length > 0) {
  process.stdout.write("失败的检查：\n");
  for (const r of failed) process.stdout.write(`  - ${r.name}: ${r.detail}\n`);
  process.stdout.write("\n");
  process.exit(1);
}
