/**
 * 配方覆盖度诊断：能读哪些、读不懂哪些、为什么。
 *
 *   npm run inspect
 *
 * 前置：Minecraft 正在运行（和 live.ts 一样，靠服务发现自己找游戏）。
 *
 * <h2>为什么需要它</h2>
 *
 * 日志里的「N 条读不懂，X%」只给了一个比例，但**比例本身不告诉你该不该做优化**。
 * 需要知道的是：读不懂的是哪些配方类型？是原版本来就无法声明式表达的
 * （特殊合成、代码驱动），还是某个模组的自定义格式？
 *
 * 前者是物理限制，改不了；后者才值得写适配器（或让 JEI 兜底）。
 * 这个脚本把这两类分开。
 */



// 刻意**不**覆盖 CRAFTGRAPH_CACHE_DIR：作为诊断工具，它应该用真实的缓存目录。
// 好处是游戏没开时也能分析上次保存的快照 —— 排查「上次那批读不懂的配方是什么」
// 往往正是游戏关掉之后才想做的事。
const { resolveBridgeLocation } = await import("./config.js");
const { BridgeClient } = await import("./bridge.js");
const { RecipeStore } = await import("./cache.js");
const { buildRecipeTree } = await import("./tree.js");

const location = resolveBridgeLocation();
const client = new BridgeClient(location);
const store = await RecipeStore.load(client);

const s = store.status;
process.stdout.write(`\nBridge：${client.describeLocation}\n`);
process.stdout.write(`配方 ${s.recipeCount} 条 / 标签 ${s.tagCount} 个 / dataVersion ${s.dataVersion}\n`);

if (s.offline) {
  process.stdout.write(`\n⚠️  ${s.offlineReason}\n以下分析基于离线快照，可能与当前游戏不一致。\n`);
}

const recipes = store.allRecipes();

// ============================================================ 配方类型分布

const byType = new Map<string, { total: number; opaque: number }>();
for (const r of recipes) {
  const e = byType.get(r.type) ?? { total: 0, opaque: 0 };
  e.total++;
  if (r.opaque) e.opaque++;
  byType.set(r.type, e);
}

process.stdout.write(`\n${"=".repeat(72)}\n配方类型分布（共 ${byType.size} 种）\n${"=".repeat(72)}\n\n`);
process.stdout.write("| 配方类型 | 总数 | 读不懂 | 可读率 |\n|---|---:|---:|---:|\n");
for (const [type, e] of [...byType.entries()].sort((a, b) => b[1].total - a[1].total)) {
  const rate = e.total === 0 ? 0 : Math.round(((e.total - e.opaque) / e.total) * 100);
  process.stdout.write(`| \`${type}\` | ${e.total} | ${e.opaque} | ${rate}% |\n`);
}

// ============================================================ 读不懂的配方

const opaque = recipes.filter((r) => r.opaque);
process.stdout.write(`\n${"=".repeat(72)}\n读不懂的配方（${opaque.length} 条，占 ${Math.round((opaque.length / recipes.length) * 100)}%）\n${"=".repeat(72)}\n\n`);

if (opaque.length === 0) {
  process.stdout.write("没有读不懂的配方。\n");
} else {
  const opaqueByType = new Map<string, string[]>();
  for (const r of opaque) {
    const list = opaqueByType.get(r.type) ?? [];
    list.push(r.id);
    opaqueByType.set(r.type, list);
  }

  for (const [type, ids] of [...opaqueByType.entries()].sort((a, b) => b[1].length - a[1].length)) {
    process.stdout.write(`\n【${type}】${ids.length} 条\n`);
    for (const id of ids.slice(0, 8)) {
      const r = store.getRecipe(id);
      const out = r?.outputs[0];
      process.stdout.write(
        `  ${id}\n      输入 ${r?.inputs.length ?? 0} 个槽位，产出 ${out ? `${out.count} × ${out.item}` : "（空）"}\n`,
      );
    }
    if (ids.length > 8) process.stdout.write(`  …另有 ${ids.length - 8} 条\n`);
  }
}

// ============================================================ 非工作台配方能否读

process.stdout.write(`\n${"=".repeat(72)}\n非工作台配方的可读性抽查\n${"=".repeat(72)}\n\n`);
process.stdout.write(
  "「能读到」和「能读懂」是两回事。下面挑几个**不是工作台合成**的类型，" +
    "看它们的输入输出有没有被正确解析：\n\n",
);

for (const [type] of [...byType.entries()].filter(([t]) => !t.includes("crafting")).sort((a, b) => b[1].total - a[1].total).slice(0, 5)) {
  const sample = recipes.find((r) => r.type === type && !r.opaque);
  if (!sample) {
    process.stdout.write(`【${type}】没有可读样本\n\n`);
    continue;
  }
  const out = sample.outputs[0];
  process.stdout.write(`【${type}】\n  ${sample.id}\n`);
  process.stdout.write(`  耗时 ${sample.duration ?? "未知"} 刻，机器 ${sample.machine ?? "未识别"}\n`);
  process.stdout.write(`  输入：${sample.inputs.map((i) => `${i.count} × [${i.options.map((o) => (o.type === "tag" ? `#${o.id}` : o.id)).join(" | ")}]`).join("，") || "（无）"}\n`);
  process.stdout.write(`  产出：${out ? `${out.count} × ${out.item}` : "（无）"}\n\n`);
}

// ============================================================ 结论提示

const opaqueTypes = new Set(opaque.map((r) => r.type));
const specialOnly = [...opaqueTypes].every((t) => t.includes("special") || t.includes("crafting_special"));

process.stdout.write(`${"=".repeat(72)}\n判读\n${"=".repeat(72)}\n\n`);
if (opaque.length === 0) {
  process.stdout.write("全部配方都能读懂。\n");
} else if (specialOnly) {
  process.stdout.write(
    "读不懂的全部是 `crafting_special_*` 这类**代码驱动的特殊合成**（染色、地图复制、烟花、修复等）。\n\n" +
      "这类配方在 Minecraft 里本来就没有声明式的输入输出 —— 逻辑写在 Java 代码里，\n" +
      "`getIngredients()` 返回空是设计如此，不是我们解析失败。**这是物理限制，不用优化。**\n",
  );
} else {
  process.stdout.write(
    `读不懂的涉及 ${opaqueTypes.size} 种配方类型，其中含非特殊合成：\n` +
      [...opaqueTypes].filter((t) => !t.includes("special")).map((t) => `  - ${t}`).join("\n") +
      "\n\n这些可能是模组的自定义配方格式 —— 给对应模组写适配器就能读出来。\n" +
      "（原版那几种由代码驱动的配方谁都读不到，属于平台限制，不是解析问题。）\n",
  );
}

process.stdout.write("\n");
