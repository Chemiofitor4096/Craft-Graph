/**
 * 把配方树 / 产线报告渲染成 Markdown。
 *
 * 目标是给 AI 看的，也是给玩家看的。两个要求：
 *   - 树形结构要能一眼看出层级
 *   - 所有「我替你做了选择」和「这里没算准」都必须显式写出来，
 *     否则玩家会拿一份看起来完整、实际有洞的原料表去建产线
 */

import type { RecipeStore, StackKind } from "./cache.js";
import type { ProductionPlan } from "./plan.js";
import { flattenTree, type NodeKind, type TreeResult, type TreeNode } from "./tree.js";

function name(store: RecipeStore, id: string): string {
  const display = store.itemName(id);
  return display === id ? `\`${id}\`` : `${display}（\`${id}\`）`;
}

function kindUnit(kind: StackKind): string {
  return kind === "fluid" ? " mB" : "";
}

const KIND_MARK: Record<TreeNode["kind"], string> = {
  craft: "",
  raw: "● ",
  opaque: "⚠ ",
  cycle: "⟲ ",
  truncated: "✂ ",
  budget: "✂ ",
  unresolved: "⚠ ",
};

/**
 * 图例：把「这个标记是什么意思」从每个节点上挪到开头说一次。
 *
 * 之前每个基础原料叶子节点都会重复一句「没有配方能产出它，视为基础原料」，
 * 400 个节点的树里这句话出现两百多次，占掉 10% 的输出。
 * 标记本身（●）已经说明了种类，句子是纯冗余。
 */
const LEGEND: Record<TreeNode["kind"], string> = {
  craft: "",
  raw: "● 无配方产出，算作基础原料",
  opaque: "⚠ 有配方但输入输出读不懂",
  cycle: "⟲ 循环依赖，已截断",
  truncated: "✂ 达到深度上限，未展开",
  budget: "✂ 达到节点数上限，未展开",
  unresolved: "⚠ 配方槽位无法解析",
};

/** 这些种类的解释已由图例统一给出，节点上不再重复。 */
const NOTE_COVERED_BY_LEGEND = new Set<TreeNode["kind"]>(["raw", "truncated", "budget"]);

/** 只把输出里实际出现的标记写进图例，不为不存在的情况占 token。 */
function legendFor(nodes: TreeNode[]): string | null {
  const present = new Set(nodes.map((n) => n.kind));
  const parts = [...present]
    .filter((k) => k !== "craft")
    .map((k) => LEGEND[k])
    .filter((s) => s.length > 0);
  return parts.length > 0 ? `> 图例：${parts.join("｜")}` : null;
}

function renderTreeNode(store: RecipeStore, node: TreeNode, depth: number, lines: string[]): void {
  const indent = "  ".repeat(depth);
  const mark = KIND_MARK[node.kind];
  const tagNote = node.chosenFromTag ? ` 【#${node.chosenFromTag}】` : "";

  let line = `${indent}${mark}**${node.count}${kindUnit("item")} × ${name(store, node.item)}**${tagNote}`;

  if (node.kind === "craft" && node.recipeId) {
    line += `\n${indent}  ↳ \`${node.recipeId}\`（${node.recipeType}）执行 ${node.crafts ?? 1} 次，每次产出 ${node.outputPerCraft}`;
    if (node.probabilistic) line += "，概率产出按期望值";
  }
  lines.push(line);

  if (node.note && !NOTE_COVERED_BY_LEGEND.has(node.kind)) {
    lines.push(`${indent}  _${node.note}_`);
  }

  // 标签候选和替代配方都只报数量。
  // 之前在这里列出全部成员/配方 id，实测占掉配方树输出的 27%（标签候选）+ 9%（替代配方），
  // 而且同一个标签在每次出现处都重列一遍。需要时用 expand_tag / find_alternative_recipes 查。
  //
  // 标签 id 不再重复：它已经写在上面的物品行里（【#tag】）了。
  if (node.tagAlternatives && node.tagAlternatives.length > 0) {
    lines.push(`${indent}  _另有 ${node.tagAlternatives.length} 个候选物品_`);
  }
  if (node.alternatives.length > 0) {
    lines.push(`${indent}  _另有 ${node.alternatives.length} 条配方可产出它_`);
  }

  // 被展示裁剪掉的分支：把规模报出来，别让模型以为树就这么大。
  // 原料是「已经算好但没展开」的，所以这里给出的原料信息是准确的，不是估算。
  if (node.subtree) {
    lines.push(`${indent}  ⋯ ${describeSubtree(node.subtree)}`);
  }

  for (const child of node.children) renderTreeNode(store, child, depth + 1, lines);
}

/**
 * 全局说明只在「隐藏的节点足够多」时才加。
 *
 * 那行说明约 150 字符（≈45 tokens），而折叠一个节点通常只省 20 tokens 左右。
 * 小规模树上加它就是净亏 —— 实测一棵 4 节点的树加了说明反而更长。
 * 每个折叠节点上的「⋯ 该分支未展开，共 N 节点」已经自带说明，所以省掉全局那行不影响理解。
 */
const COLLAPSE_NOTE_THRESHOLD = 8;

function totalHiddenNodes(root: TreeNode): number {
  return flattenTree(root).reduce((sum, n) => sum + (n.subtree?.nodeCount ?? 0), 0);
}

/** 把子树摘要渲染成一行。 */
function describeSubtree(s: { nodeCount: number; recipeCount: number; rawMaterials: { item: string; count: number }[]; truncated: boolean }): string {
  const parts = [`该分支未展开，共 ${s.nodeCount} 节点 / ${s.recipeCount} 条配方`];
  if (s.rawMaterials.length > 0) {
    const top = s.rawMaterials.slice(0, 4).map((m) => `${m.item} ×${m.count}`).join("、");
    const more = s.rawMaterials.length > 4 ? ` 等 ${s.rawMaterials.length} 种` : "";
    parts.push(`原料 ${top}${more}`);
  }
  if (s.truncated) parts.push("注意该分支自身也被截断，原料不完整");
  return parts.join("；");
}

export function renderTree(store: RecipeStore, result: TreeResult, targetLabel: string, detailDepth = 2): string {
  const lines: string[] = [];

  lines.push(`# 配方树：${targetLabel}`);
  lines.push("");

  if (result.truncated) {
    lines.push(
      "> ⚠️ **这份配方树不完整**，有节点因为深度或节点数限制没有展开。" +
        "下面的原料表也因此不完整，不要直接拿去建产线。",
    );
    lines.push("");
  }

  const hidden = totalHiddenNodes(result.root);
  if (hidden >= COLLAPSE_NOTE_THRESHOLD) {
    lines.push(
      `> ℹ️ 结构只展示到第 ${detailDepth} 层，更深的分支用「⋯」标出规模 —— ` +
        `但**基础原料是完整的**（未展开的分支也已计算在内）。` +
        `要看某个分支的细节：把 detailDepth 调大，或直接拿那个物品再问一次。`,
    );
    lines.push("");
  }

  const legend = legendFor(flattenTree(result.root));
  if (legend) {
    lines.push(legend);
    lines.push("");
  }

  lines.push("## 展开过程");
  lines.push("");
  renderTreeNode(store, result.root, 0, lines);

  lines.push("");
  lines.push("## 基础原料汇总");
  lines.push("");
  if (result.rawMaterials.length === 0) {
    lines.push("_（没有识别出基础原料）_");
  } else {
    lines.push("| 物品 | 数量 |");
    lines.push("|---|---|");
    for (const mat of result.rawMaterials) {
      lines.push(`| ${name(store, mat.item)} | ${mat.count}${kindUnit(mat.kind)} |`);
    }
  }

  if (result.warnings.length > 0) {
    lines.push("");
    lines.push("## 需要注意");
    lines.push("");
    for (const w of [...new Set(result.warnings)]) lines.push(`- ${w}`);
  }

  lines.push("");
  lines.push(
    `_展开 ${result.nodeCount} 个节点，用到 ${result.recipesUsed.length} 条配方。` +
      `标签（形如 #xxx）的成员是自动挑选的，可以用 tagChoice 指定。_`,
  );

  return lines.join("\n");
}

export function renderPlan(store: RecipeStore, plan: ProductionPlan, targetLabel: string, detailDepth = 2): string {
  const lines: string[] = [];

  lines.push(`# 产线规划：每分钟 ${plan.target.ratePerMinute}${kindUnit(plan.target.kind)} ${targetLabel}`);
  lines.push("");

  if (plan.truncated) {
    lines.push("> ⚠️ **这份规划不完整**，有环节没有展开，原料表偏低，不能直接照着建。");
    lines.push("");
  }
  if (plan.manualSteps > 0) {
    lines.push(
      `> ℹ️ 有 ${plan.manualSteps} 个环节耗时未知（通常是工作台合成），无法计算机器数量，` +
        `只能给出合成次数。`,
    );
    lines.push("");
  }

  const planLegendText = planLegend(plan.root);
  if (planLegendText) {
    lines.push(planLegendText);
    lines.push("");
  }

  // ---- 机器清单 ----
  lines.push("## 需要多少机器");
  lines.push("");
  if (plan.machines.length === 0) {
    lines.push("_（没有可计算机器数的环节）_");
  } else {
    lines.push("| 机器 | 数量 | 用于 |");
    lines.push("|---|---|---|");
    for (const m of plan.machines) {
      lines.push(`| ${name(store, m.machine)} | ${m.count} | ${m.recipeIds.map((r) => `\`${r}\``).join(", ")} |`);
    }
    lines.push("");
    lines.push("_机器数按「满载运行」估算，未考虑上下游没对齐导致的空转。_");
  }

  // ---- 原料 ----
  lines.push("");
  lines.push("## 每分钟原料需求");
  lines.push("");
  if (plan.rawMaterials.length === 0) {
    lines.push("_（没有识别出基础原料）_");
  } else {
    lines.push("| 物品 | 每分钟 |");
    lines.push("|---|---|");
    for (const mat of plan.rawMaterials) {
      lines.push(`| ${name(store, mat.item)} | ${round(mat.ratePerMinute)}${kindUnit(mat.kind)} |`);
    }
  }

  // ---- 副产 ----
  if (plan.byproducts.length > 0) {
    lines.push("");
    lines.push("## 副产物");
    lines.push("");
    lines.push("| 物品 | 每分钟 | 来源 | 说明 |");
    lines.push("|---|---|---|---|");
    for (const bp of plan.byproducts) {
      lines.push(
        `| ${name(store, bp.item)} | ${round(bp.ratePerMinute)}${kindUnit(bp.kind)} | \`${bp.fromRecipe}\` | ${
          bp.probabilistic ? "期望值（概率产出）" : "必然产出"
        } |`,
      );
    }
    lines.push("");
    lines.push("_副产只做统计，没有回代抵扣上游消耗。_");
  }

  // ---- 能耗 ----
  lines.push("");
  lines.push("## 能耗");
  lines.push("");
  if (plan.totalEnergyPerMinute === null) {
    lines.push("_有环节读不到能耗数据（原版配方大多没有这个字段），无法给出总量。_");
  } else {
    lines.push(`合计约 **${round(plan.totalEnergyPerMinute)} FE/分钟**。`);
  }

  // ---- 环节明细 ----
  lines.push("");
  lines.push("## 逐环节明细");
  lines.push("");
  renderPlanNode(store, plan.root, 0, lines);

  if (plan.warnings.length > 0) {
    lines.push("");
    lines.push("## 需要注意");
    lines.push("");
    for (const w of [...new Set(plan.warnings)]) lines.push(`- ${w}`);
  }

  return lines.join("\n");
}

const PLAN_LEGEND: Record<NodeKind, string> = {
  craft: "",
  raw: "● 无配方产出，算作基础原料",
  opaque: "⚠ 配方读不懂，其原料未计入",
  cycle: "⟲ 循环依赖，需求量未能展开",
  truncated: "✂ 达到深度上限，未展开",
  budget: "✂ 达到节点数上限，未展开",
  unresolved: "⚠ 配方槽位无法解析",
};

function planLegend(root: ProductionPlan["root"]): string | null {
  const present = new Set<NodeKind>();
  const walk = (n: ProductionPlan["root"]): void => {
    present.add(n.status);
    for (const c of n.children) walk(c);
  };
  walk(root);

  const parts = [...present]
    .filter((k) => k !== "craft")
    .map((k) => PLAN_LEGEND[k])
    .filter((s) => s.length > 0);
  return parts.length > 0 ? `> 图例：${parts.join("｜")}` : null;
}

function renderPlanNode(store: RecipeStore, node: ProductionPlan["root"], depth: number, lines: string[]): void {
  const indent = "  ".repeat(depth);
  const tagNote = node.chosenFromTag ? ` 【#${node.chosenFromTag}】` : "";

  let line = `${indent}- **${round(node.ratePerMinute)}${kindUnit(node.kind)}/分 × ${name(store, node.item)}**${tagNote}`;

  if (node.recipeId) {
    const craftRate = node.craftsPerMinute !== undefined ? round(node.craftsPerMinute) : "?";
    const machineText = node.machines != null ? `${node.machines} 台` : "手工";
    line += `\n${indent}  ↳ \`${node.recipeId}\`：${craftRate} 次/分 → ${machineText}`;
    if (node.secondsPerCraft != null) line += `，单次 ${round(node.secondsPerCraft)} 秒`;
  }
  lines.push(line);

  // 与配方树同理：说明已进图例，节点上不重复；标签 id 也不重复（物品行上已有）
  if (node.note && !NOTE_COVERED_BY_LEGEND.has(node.status)) lines.push(`${indent}  _${node.note}_`);
  if (node.tagAlternatives && node.tagAlternatives.length > 0) {
    lines.push(`${indent}  _另有 ${node.tagAlternatives.length} 个候选物品_`);
  }
  if (node.subtree) {
    lines.push(`${indent}  ⋯ 该分支未展开，共 ${node.subtree.nodeCount} 节点`);
  }

  for (const child of node.children) renderPlanNode(store, child, depth + 1, lines);
}

function round(n: number): number {
  if (!Number.isFinite(n)) return 0;
  return Math.round(n * 1000) / 1000;
}

// ================================================================ TSV 输出
//
// 列式输出。实测在深链配方树上比 markdown 省 28%，而且不依赖任何跨调用的状态
// （不用字典编码 —— 字典的收益在每次调用都要重发时会完全蒸发，见 doc/format-evaluation.md）。
//
// 分隔符选竖线：实测与逗号打平、略优于制表符，且不会被客户端的空白归一化破坏，
// 字段里也不会出现竖线（物品 id 只用字母数字和 : _ /）。
//
// 表头行留在输出里而不是挪到工具描述里。约 27 tokens 换来数据自描述：
// 模型不用跨「工具描述」和「结果」两处做关联，少一类出错可能。

const TREE_TSV_COLUMNS = "depth|parent|count|item|recipe|type|crafts|perCraft|tag|tagAlts|alts|status";

export function renderTreeTsv(store: RecipeStore, result: TreeResult, targetLabel: string, detailDepth = 2): string {
  const lines: string[] = [];

  lines.push(`# 配方树 ${targetLabel}`);
  if (totalHiddenNodes(result.root) >= COLLAPSE_NOTE_THRESHOLD) {
    lines.push(`# 结构展示到第 ${detailDepth} 层，更深的分支见「未展开分支」段；基础原料为全树合计。`);
  }
  if (result.truncated) lines.push("# ⚠️ 全树被截断，基础原料不完整，不要直接拿去建产线。");
  lines.push(`# 列 ${TREE_TSV_COLUMNS}`);
  lines.push(`# status: craft=有配方 raw=无配方产出 opaque=配方读不懂 cycle=循环依赖 truncated/budget=被截断 unresolved=槽位无法解析`);

  const collapsed: TreeNode[] = [];
  let counter = 0;
  const row = (node: TreeNode, depth: number, parent: string): void => {
    const id = String(counter++);
    lines.push(
      [
        depth,
        parent,
        node.count,
        node.item,
        node.recipeId ?? "-",
        node.recipeType ?? "-",
        node.crafts ?? "-",
        node.outputPerCraft ?? "-",
        node.chosenFromTag ?? "-",
        node.tagAlternatives?.length ?? 0,
        node.alternatives.length,
        node.kind,
      ].join("|"),
    );
    if (node.subtree) collapsed.push(node);
    for (const child of node.children) row(child, depth + 1, id);
  };
  row(result.root, 0, "-");

  if (collapsed.length > 0) {
    lines.push("# 未展开分支");
    lines.push("# 列 item|count|节点数|配方数|是否被截断");
    for (const n of collapsed) {
      const s = n.subtree!;
      lines.push([n.item, n.count, s.nodeCount, s.recipeCount, s.truncated ? 1 : 0].join("|"));
    }
  }

  lines.push("# 基础原料（全树合计）");
  lines.push("# 列 item|count");
  for (const m of result.rawMaterials) lines.push(`${m.item}|${m.count}`);

  if (result.warnings.length > 0) {
    lines.push("# 注意");
    for (const w of [...new Set(result.warnings)]) lines.push(`# ${w}`);
  }

  lines.push(`# 共 ${result.nodeCount} 节点，用到 ${result.recipesUsed.length} 条配方。标签成员为自动挑选，可用 tagChoice 指定。`);

  return lines.join("\n");
}

const PLAN_TSV_COLUMNS = "depth|parent|rate|item|recipe|craftsPerMin|machines|secondsPerCraft|tag|status";

export function renderPlanTsv(store: RecipeStore, plan: ProductionPlan, targetLabel: string, detailDepth = 2): string {
  const lines: string[] = [];

  lines.push(`# 产线规划 每分钟 ${plan.target.ratePerMinute} × ${targetLabel}`);
  if (plan.truncated) lines.push("# ⚠️ 未完全展开，原料偏低，不能直接照着建。");
  if (plan.manualSteps > 0) lines.push(`# 有 ${plan.manualSteps} 个环节耗时未知（多为工作台合成），无法计算机器数。`);

  lines.push("# 机器");
  lines.push("# 列 machine|count|recipes");
  for (const m of plan.machines) lines.push([m.machine, m.count, m.recipeIds.join(",")].join("|"));

  lines.push("# 每分钟原料");
  lines.push("# 列 item|ratePerMinute");
  for (const m of plan.rawMaterials) lines.push(`${m.item}|${round(m.ratePerMinute)}`);

  if (plan.byproducts.length > 0) {
    lines.push("# 副产物");
    lines.push("# 列 item|ratePerMinute|probabilistic|fromRecipe");
    for (const b of plan.byproducts) {
      lines.push([b.item, round(b.ratePerMinute), b.probabilistic ? 1 : 0, b.fromRecipe].join("|"));
    }
  }

  lines.push("# 能耗");
  lines.push(
    plan.totalEnergyPerMinute === null
      ? "# 有环节读不到能耗数据，无法给出总量"
      : `# 合计 ${round(plan.totalEnergyPerMinute)} FE/分钟`,
  );

  lines.push(`# 结构（展示到第 ${detailDepth} 层）`);
  lines.push(`# 列 ${PLAN_TSV_COLUMNS}`);
  lines.push(`# status: craft=有配方 raw=无配方产出 opaque=配方读不懂 cycle=循环依赖 truncated/budget=被截断 unresolved=槽位无法解析`);

  let counter = 0;
  const row = (node: ProductionPlan["root"], depth: number, parent: string): void => {
    const id = String(counter++);
    lines.push(
      [
        depth,
        parent,
        round(node.ratePerMinute),
        node.item,
        node.recipeId ?? "-",
        node.craftsPerMinute !== undefined ? round(node.craftsPerMinute) : "-",
        node.machines ?? "-",
        node.secondsPerCraft != null ? round(node.secondsPerCraft) : "-",
        node.chosenFromTag ?? "-",
        node.status,
      ].join("|"),
    );
    for (const child of node.children) row(child, depth + 1, id);
  };
  row(plan.root, 0, "-");

  if (plan.warnings.length > 0) {
    lines.push("# 注意");
    for (const w of [...new Set(plan.warnings)]) lines.push(`# ${w}`);
  }

  return lines.join("\n");
}
