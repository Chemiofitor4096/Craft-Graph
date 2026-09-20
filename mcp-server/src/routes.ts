/**
 * 选路质量诊断：当前启发式在**你这个包**上选得怎么样。
 *
 *   npm run routes
 *
 * 和 `inspect` 一样，它刻意用**真实缓存目录**（不隔离）—— 它是诊断工具，
 * 对着用户自己的数据看才有意义；而且游戏没开时也能分析上次的快照。
 *
 * <h2>为什么需要它</h2>
 *
 * `scoreRecipe` 的那条启发式改过两次，两次都证明「光看一两个案例判断不了」：
 *
 * - 第一次（输入槽位改用合并后的数量）在真实包上**改变了 39% 的首选**，
 *   而我提交前只对了一个案例就下了结论 —— 数字是事后才量出来的。
 * - 想再加一条「偏好成池资源」的规则时，连着两条代理（标签数、整合度）
 *   都被同一份数据推翻。**那条信息根本不在配方图里**。
 *
 * 所以这个脚本的作用不是「给出更好的启发式」，而是**在改之前先把现状量出来**，
 * 免得又一次用一个案例代表全部。
 *
 * <h2>它量什么</h2>
 *
 * 一条路线的输入分三类：
 *
 * | 类别 | 判据 | 含义 |
 * |---|---|---|
 * | 成池槽位 | 槽位里带 `tag` 选项 | 「这类东西任意一个都行」= 批量资源 |
 * | 中间产物 | 该物品有产出配方 | 会被展开，不是叶子 |
 * | **叶子** | 没有产出配方 | 真正的原料，玩家得去搞到手 |
 *
 * 叶子再按「整合度」（全包有多少条配方消耗它，**含标签展开**）分：
 * 被大量配方用 = 成池资源（矿石、糖、木板）；几乎没人用 = 要么是技术性/搜刮物品
 * （`create:minecart_contraption`），要么是冷门天然方块（`dead_coral_block`）——
 * **这两者分不开**，所以下面只报数字和例子，让你自己看。
 *
 * <h2>已知的边界</h2>
 *
 * 它**不能**回答「这条路线合不合理」：实测 `minecraft:iron_ore` 与
 * `minecraft:iron_horse_armor` 在整合度上都是 3、标签数 5 vs 1，任何纯数据判据
 * 都分不开「挖的」和「搜刮的」。这个区别只存在于**世界知识**里 ——
 * 所以真正的修法是让模型看见候选（树的「另有 N 条配方」+ `recipeChoice`），
 * 而不是再猜一条启发式。
 */

const { resolveBridgeLocation } = await import("./config.js");
const { BridgeClient } = await import("./bridge.js");
const { RecipeStore } = await import("./cache.js");
import type { Recipe } from "./types.js";

const store = await RecipeStore.load(new BridgeClient(resolveBridgeLocation()));
const s = store.status;

process.stdout.write(`\nBridge：${s.offline ? "（离线快照）" : "已连接"}\n`);
process.stdout.write(`配方 ${s.recipeCount} 条 / 标签 ${s.tagCount} 个\n`);
if (s.offline) process.stdout.write(`⚠️  ${s.offlineReason ?? "游戏没在运行"}\n`);

const recipes = store.allRecipes();

// ---------------------------------------------------------------- 基础统计

/** 全包里这个物品被多少条配方消耗（**含标签展开**：用了 #c:ores/iron 的配方也算在 iron_ore 头上）。 */
const integration = new Map<string, number>();
const bump = (id: string) => integration.set(id, (integration.get(id) ?? 0) + 1);
for (const r of recipes) {
  for (const ing of r.inputs ?? []) {
    for (const o of ing.options ?? []) {
      if (o.type === "item") bump(o.id);
      else if (o.type === "tag") for (const m of store.expandTag(o.id) ?? []) bump(m);
    }
  }
}

const producers = new Map<string, Recipe[]>();
for (const r of recipes) {
  if (r.opaque) continue;
  for (const o of r.outputs ?? []) {
    const list = producers.get(o.item) ?? [];
    list.push(r);
    producers.set(o.item, list);
  }
}
const hasProducer = (id: string) => (producers.get(id)?.length ?? 0) > 0;

/** 模型里不参与「叶子」判断的槽位：流体（另有语义）、成池槽位、中间产物。 */
function leaves(r: Recipe): string[] {
  const out: string[] = [];
  for (const ing of r.inputs ?? []) {
    if (ing.kind === "fluid") continue;
    if (ing.options.some((o) => o.type === "tag")) continue;
    const id = ing.options[0]?.id;
    if (!id || hasProducer(id)) continue;
    out.push(id);
  }
  return out;
}

/** 复刻 `scoreRecipe` 的排序（只保留与选路有关的项：合并输入数、产出量、id）。 */
function pickFor(candidates: Recipe[]): Recipe {
  const cost = (r: Recipe) => r.inputs.length * 10 - Math.min(r.outputs[0]?.count ?? 1, 9);
  return [...candidates].sort((a, b) => cost(a) - cost(b) || a.id.localeCompare(b.id))[0]!;
}

// ---------------------------------------------------------------- 分布

const multi = [...producers.entries()].filter(([, c]) => c.length >= 2);
let withLeaves = 0;
let coldLeaf = 0; // 叶子里有「全包几乎不用」的
const coldExamples: string[] = [];
const hist = new Map<number, number>();

for (const [item, cands] of multi) {
  const pick = pickFor(cands);
  const ls = leaves(pick);
  if (ls.length > 0) withLeaves++;
  for (const id of ls) {
    const n = integration.get(id) ?? 0;
    const bucket = n === 0 ? 0 : n <= 2 ? 1 : n <= 10 ? 2 : n <= 100 ? 3 : 4;
    hist.set(bucket, (hist.get(bucket) ?? 0) + 1);
    if (n <= 1) {
      coldLeaf++;
      if (coldExamples.length < 12) coldExamples.push(`  ${item}  ←  ${id}`);
    }
  }
}

const pct = (n: number) => `${((n / Math.max(1, multi.length)) * 100).toFixed(1)}%`;
process.stdout.write(`\n${"=".repeat(72)}\n首选路线里的叶子原料（候选 ≥2 的物品种数：${multi.length}）\n${"=".repeat(72)}\n\n`);
process.stdout.write(`有叶子原料的首选路线：${withLeaves}（${pct(withLeaves)}）\n`);
const label = ["整合度 0（全包没人用）", "整合度 1-2", "整合度 3-10", "整合度 11-100", "整合度 >100"];
process.stdout.write(`叶子的整合度分布：\n`);
for (const b of [0, 1, 2, 3, 4]) process.stdout.write(`  ${label[b]!.padEnd(26)}${hist.get(b) ?? 0}\n`);

process.stdout.write(
  `\n⚠️  整合度低**不等于**怪路线：冷门天然方块（dead_coral_block、sculk_catalyst）\n` +
    `    和只能搜刮/技术性的物品（create:minecart_contraption）在这里长得一样。\n` +
    `    下面这些是「整合度 ≤1」的叶子，需要你自己判断哪边是哪种：\n\n`,
);
process.stdout.write(coldExamples.join("\n") + "\n");

process.stdout.write(
  `\n${"=".repeat(72)}\n判读\n${"=".repeat(72)}\n\n` +
    `这个脚本不给出「更好」的结论，因为实测**没有**可靠的纯数据判据：\n` +
    `  · 「标签数 ≤1」把 honeycomb(0 标签)、egg(1 标签) 这类能养殖的批量物品也判成具体物品；\n` +
    `  · 「整合度低」把冷门天然方块判成技术性物品；\n` +
    `  · 而 minecraft:iron_ore 与 minecraft:iron_horse_armor 在两者上几乎完全一样。\n` +
    `「这东西玩家能不能批量搞到」只存在于世界知识里 —— 所以改启发式之前，\n` +
    `先看上面这些数字是不是真的变好了，别用一个案例代表全部。\n\n`,
);
