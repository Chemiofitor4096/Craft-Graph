/**
 * 配方树递归展开。
 *
 * 这是整个项目最容易做错的地方，四个坑都在这个文件里处理：
 *
 *   1. **标签歧义**：一个槽位写的是 #forge:ingots/iron，意味着「任意一种铁锭都行」。
 *      必须挑一个具体的，并把这个选择记录下来告诉用户。直接把标签当成一个叫
 *      "#forge:ingots/iron" 的物品是最常见的错误，算出来的原料表玩家用不了。
 *
 *   2. **循环**：铁块 = 9 铁锭，铁锭 = 1/9 铁块。必须检测并截断。
 *
 *   3. **读不懂的配方**：opaque 配方确实存在但输入输出未知。必须显式区分
 *      「没有配方」和「有配方但读不懂」，否则 AI 会以为这物品凭空来的。
 *
 *   4. **爆炸**：配方图的分支因子很大，必须限制深度和节点总数。
 *
 * 取舍规则（选哪条配方、标签挑哪个成员）在 resolution.ts，与产线计算共用。
 */

import { opaqueHint } from "./opaque.js";
import { type RecipeStore, type StackKind } from "./cache.js";
import { ResolutionEngine, normalizeOptions, type ExpansionOptions } from "./resolution.js";
export type NodeKind =
  /** 有配方，已展开 */
  | "craft"
  /** 没有任何配方能产出它 —— 基础原料 */
  | "raw"
  /** 有配方但读不懂（opaque） */
  | "opaque"
  /** 绕回上层已经展开过的物品，已截断 */
  | "cycle"
  /** 超过深度限制，已截断 */
  | "truncated"
  /** 超过节点总数预算，已截断 */
  | "budget"
  /** 配方槽位无法解析（标签为空等） */
  | "unresolved";

export interface TreeNode {
  /** 具体物品/流体 id */
  item: string;
  /** 需要数量。流体单位是 mB */
  count: number;
  /** 这个节点是物品还是流体 —— 用于汇总原料时区分 */
  stackKind: StackKind;
  kind: NodeKind;

  /** kind === "craft" 时有效 */
  recipeId?: string;
  recipeType?: string;
  /** 需要执行几次配方 */
  crafts?: number;
  /** 一次配方产出多少个目标物品 */
  outputPerCraft?: number;
  /** 产出是概率性的，数量按期望值算的 */
  probabilistic?: boolean;

  /** 若这个物品是从标签里挑出来的，记录来源标签 id */
  chosenFromTag?: string;
  /** 挑这个标签成员时的其他候选（让用户知道有别的选择） */
  tagAlternatives?: string[];

  /** 其他也能产出该物品的配方 id（本节点没用上的） */
  alternatives: string[];

  /**
   * 这个节点由几个原始槽位合并而来。
   *
   * 主要给 JSON 消费者看：原版有序合成把「3 个铁锭」表示成 3 个槽位，
   * 合并后数量是 3、这里是 3。对大多数用途没差别，但想还原合成格布局时需要它。
   */
  mergedSlots?: number;

  children: TreeNode[];
  note?: string;

  /**
   * 被展示裁剪掉的分支的规模统计。
   *
   * 只在 pruneTree 裁剪后出现，让上层能说清楚「这里还有多少没展开」，
   * 而不是让模型以为树就这么大。见 pruneTree 的注释。
   */
  subtree?: SubtreeStats;
}

export interface RawMaterial {
  item: string;
  count: number;
  kind: StackKind;
}

export interface TreeResult {
  root: TreeNode;
  /** 展开过程中收集到的基础原料（已按物品合并） */
  rawMaterials: RawMaterial[];
  /** 展开过程中用到的配方，去重 */
  recipesUsed: string[];
  /** 展开过程中遇到的所有警告，需要原样告诉用户 */
  warnings: string[];
  /** 实际生成的节点数 */
  nodeCount: number;
  /** 是否因为预算/深度被截断（结果不完整） */
  truncated: boolean;
}

class TreeWalker extends ResolutionEngine {
  private nodeCount = 0;
  private readonly rawTotals = new Map<string, RawMaterial>();
  private readonly recipesUsed = new Set<string>();
  private truncated = false;

  constructor(store: RecipeStore, opts: ExpansionOptions) {
    super(store, opts);
  }

  build(kind: StackKind, id: string, count: number): TreeResult {
    const root = this.expand(kind, id, count, [], 0);
    return {
      root,
      rawMaterials: [...this.rawTotals.values()].sort((a, b) => a.item.localeCompare(b.item)),
      recipesUsed: [...this.recipesUsed],
      warnings: this.warnings,
      nodeCount: this.nodeCount,
      truncated: this.truncated,
    };
  }

  private expand(kind: StackKind, id: string, count: number, path: string[], depth: number): TreeNode {
    this.nodeCount++;

    const node: TreeNode = { item: id, count, stackKind: kind, kind: "raw", alternatives: [], children: [] };

    // --- 用户显式声明的基础原料 ---
    if (this.opts.rawMaterials.includes(id)) {
      node.note = "用户指定为基础原料";
      this.addRaw(kind, id, count);
      return node;
    }

    // --- 循环检测 ---
    // 用路径判断而不是全局 visited：同一个物品出现在两条不同分支上是合法的，
    // 只有绕回自己所在的这条链上才是循环。
    if (path.includes(id)) {
      node.kind = "cycle";
      node.note = `循环依赖：${[...path.slice(path.indexOf(id)), id].join(" → ")}`;
      return node;
    }

    if (depth >= this.opts.maxDepth) {
      node.kind = "truncated";
      node.note = `超过深度限制 ${this.opts.maxDepth}，未继续展开`;
      this.truncated = true;
      return node;
    }

    if (this.nodeCount > this.opts.maxNodes) {
      node.kind = "budget";
      node.note = `超过节点总数上限 ${this.opts.maxNodes}，未继续展开`;
      this.truncated = true;
      return node;
    }

    // --- 找配方 ---
    const candidates = this.candidatesFor(kind, id);
    if (candidates.length === 0) {
      node.kind = "raw";
      node.note = "没有配方能产出它，视为基础原料";
      this.addRaw(kind, id, count);
      return node;
    }

    const usable = candidates.filter((r) => !r.opaque);
    if (usable.length === 0) {
      node.kind = "opaque";
      node.alternatives = candidates.map((r) => r.id);
      const types = [...new Set(candidates.map((r) => r.type))].join(", ");
      node.note =
        `有 ${candidates.length} 条配方能产出它，但都读不懂输入输出（配方类型：${types}）。` +
        opaqueHint(candidates.map((r) => r.type));
      this.warnings.push(`${id} 的配方读不懂（类型：${types}），无法继续展开`);
      return node;
    }

    const { recipe } = this.chooseRecipe(id, usable, path);
    const yieldInfo = this.computeYield(kind, id, recipe);
    const crafts = Math.max(1, Math.ceil(count / yieldInfo.perCraft));

    this.recipesUsed.add(recipe.id);

    node.kind = "craft";
    node.recipeId = recipe.id;
    node.recipeType = recipe.type;
    node.crafts = crafts;
    node.outputPerCraft = yieldInfo.perCraft;
    if (yieldInfo.probabilistic) node.probabilistic = true;
    node.alternatives = candidates.filter((r) => r.id !== recipe.id).map((r) => r.id);

    if (yieldInfo.probabilistic) {
      node.note = `该物品是概率产出，数量按期望值估算（每次期望 ${yieldInfo.perCraft}）`;
      this.warnings.push(`${id} 是概率产出，产量按期望值估算，实际需要更多次`);
    }

    // --- 递归输入 ---
    const childPath = [...path, id];

    // 先按物品合并槽位再递归。原版有序合成把「3 个铁锭」拆成 3 个各含 1 个的槽位，
    // 不合并的话树里会出现 3 棵完全相同的子树 —— 实测铁镐就是三棵一样的「铁锭」分支，
    // 既多花两三倍 token，又看起来像工具算重了。见 ResolutionEngine.mergeInputs。
    const { merged, unresolved } = this.mergeInputs(recipe.inputs);

    for (const ing of unresolved) {
      this.nodeCount++;
      const shown = ing.options.map((o) => (o.type === "tag" ? `#${o.id}` : o.id)).join(" | ");
      node.children.push({
        item: "(无法解析)",
        count: ing.count * crafts,
        stackKind: ing.kind === "fluid" ? "fluid" : "item",
        kind: "unresolved",
        alternatives: [],
        children: [],
        note: `配方槽位无法解析：${shown}`,
      });
      this.warnings.push(`配方 ${recipe.id} 有一个槽位无法解析：${shown}`);
    }

    for (const slot of merged) {
      const child = this.expand(slot.kind, slot.id, slot.count * crafts, childPath, depth + 1);
      if (slot.fromTag) {
        child.chosenFromTag = slot.fromTag;
        child.tagAlternatives = slot.alternatives;
      }
      if (slot.slotCount > 1) child.mergedSlots = slot.slotCount;
      node.children.push(child);
    }

    return node;
  }

  private addRaw(kind: StackKind, id: string, count: number): void {
    const existing = this.rawTotals.get(id);
    if (existing) existing.count += count;
    else this.rawTotals.set(id, { item: id, count, kind });
  }
}

export function buildRecipeTree(
  store: RecipeStore,
  kind: StackKind,
  id: string,
  count: number,
  options?: Partial<ExpansionOptions>,
): TreeResult {
  return new TreeWalker(store, normalizeOptions(options)).build(kind, id, count);
}

/** 把树摊平成一个列表，便于统计和渲染。 */
export function flattenTree(node: TreeNode, out: TreeNode[] = []): TreeNode[] {
  out.push(node);
  for (const c of node.children) flattenTree(c, out);
  return out;
}

/** 一棵子树的规模与原料合计。 */
export interface SubtreeStats {
  nodeCount: number;
  /** 该子树里有节点被截断（深度或节点数上限），统计不完整 */
  truncated: boolean;
  /** 该子树的基础原料，已按物品合并 */
  rawMaterials: RawMaterial[];
  /** 该子树用到的配方数 */
  recipeCount: number;
}

/**
 * 统计一棵子树有多大、需要哪些原料。
 *
 * 这是「分层返回」的关键：只在第 N 层展开结构，但把没展开的部分**先算出来**，
 * 只报告摘要。这样 agent 既能看到完整的原料答案（用户真正关心的），
 * 又不用为几百个节点付 token。
 *
 * 注意只把 `raw` 节点计入原料。cycle / opaque / truncated 虽然是叶子，
 * 但它们的原料是**未知**而不是零，混进来会让原料表偏低且看不出来。
 */
export function subtreeStats(node: TreeNode): SubtreeStats {
  const raws = new Map<string, RawMaterial>();
  const recipes = new Set<string>();
  let nodeCount = 0;
  let truncated = false;

  const walk = (n: TreeNode): void => {
    nodeCount++;
    if (n.kind === "truncated" || n.kind === "budget") truncated = true;
    if (n.recipeId) recipes.add(n.recipeId);
    if (n.kind === "raw") {
      const existing = raws.get(n.item);
      if (existing) existing.count += n.count;
      else raws.set(n.item, { item: n.item, count: n.count, kind: n.stackKind });
    }
    for (const c of n.children) walk(c);
  };
  walk(node);

  return {
    nodeCount,
    truncated,
    rawMaterials: [...raws.values()].sort((a, b) => b.count - a.count || a.item.localeCompare(b.item)),
    recipeCount: recipes.size,
  };
}

/**
 * 把树裁剪到指定展示深度，被裁掉的分支挂上规模统计。
 *
 * 为什么要裁剪：一次把 400 个节点倒出来实测要 1.5 万~3 万 token，
 * 而且 72% 的查询还会因为触及节点上限而截断 —— 又贵又不完整。
 * 改成默认只看前三层结构，但未展开的分支都带「还有多少节点、需要什么原料」，
 * agent 看完摘要就能决定要不要深挖，多数情况下根本不需要。
 *
 * @param detailDepth 结构展示到第几层（根节点为第 0 层）。
 *                    0 表示只给根节点加全树摘要；给很大的值即恢复完整展开。
 */
export function pruneTree(root: TreeNode, detailDepth: number): TreeNode {
  const walk = (node: TreeNode, depth: number): TreeNode => {
    if (node.children.length === 0) return node;
    if (depth >= detailDepth) {
      return { ...node, children: [], subtree: subtreeStats(node) };
    }
    return { ...node, children: node.children.map((c) => walk(c, depth + 1)) };
  };
  return walk(root, 0);
}
