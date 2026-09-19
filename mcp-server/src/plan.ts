/**
 * 产线计算：给定目标产量/分钟，算原料、机器数、副产、能耗。
 *
 * 这是明确的**贪心近似**，不是通用解。通用解要处理「一个配方同时产 A 和 B」
 * 导致的联立关系，本质是线性规划，工期从一周变成一个多月。
 *
 * 贪心的含义：每种物品固定选一条配方（可选覆盖），自顶向下按比例展开，
 * 副产只统计不参与回代。结果可解释、可复现，玩家能看懂，对绝大多数
 * 「我要每分钟 10 个钢锭，怎么建」这类问题是够用的。
 *
 * 机器数换算（唯一有数学的地方）：
 *   一次配方耗时 duration 刻 = duration/20 秒  → 一台机器每分钟能做 1200/duration 次
 *   需要的次数 craftsPerMinute = 目标速率 / 每次产量
 *   机器数 = ceil(craftsPerMinute / (1200/duration)) = ceil(craftsPerMinute * duration / 1200)
 *
 * 注意向上取整是**逐配方**做的，不能先合并再取整 —— 一台机器同时只能跑一个配方，
 * 不同配方之间没法共享机器。
 */

import { type RecipeStore, type StackKind } from "./cache.js";
import { ResolutionEngine, normalizeOptions, type ExpansionOptions } from "./resolution.js";
import type { NodeKind } from "./tree.js";

/** 一秒 20 游戏刻。 */
const TICKS_PER_SECOND = 20;
const TICKS_PER_MINUTE = TICKS_PER_SECOND * 60;

export interface PlanOptions extends ExpansionOptions {
  /** 目标产量，每分钟多少个（流体是 mB） */
  ratePerMinute: number;
}

export interface PlanNode {
  item: string;
  kind: StackKind;
  /**
   * 这个环节的状态，语义与配方树的 NodeKind 一致。
   *
   * 为什么要显式存这个而不是靠 note 文字判断：报告渲染时需要把
   * 「没有配方产出」「被截断」这类重复说明挪到开头图例里说一次，
   * 靠解析中文 note 来判断太脆弱。同时也是给 JSON 消费者一个可编程的字段。
   */
  status: NodeKind;
  /** 该物品每分钟需要多少 */
  ratePerMinute: number;

  /** kind === "craft" 时有效 */
  recipeId?: string;
  recipeType?: string;
  /** 每分钟需要执行多少次配方（小数，不取整） */
  craftsPerMinute?: number;
  outputPerCraft?: number;
  probabilistic?: boolean;

  /** 需要的机器数。null 表示无法计算（手工合成 / 耗时未知） */
  machines?: number | null;
  machineId?: string | null;
  /** 一次配方耗时（秒） */
  secondsPerCraft?: number | null;

  chosenFromTag?: string;
  tagAlternatives?: string[];
  alternatives: string[];
  children: PlanNode[];
  note?: string;

  /** 被展示裁剪掉的分支规模。见 tree.ts 的 pruneTree 说明。 */
  subtree?: { nodeCount: number };
}

export interface PlanRawMaterial {
  item: string;
  kind: StackKind;
  ratePerMinute: number;
}

export interface PlanMachine {
  machine: string;
  count: number;
  /** 这些机器分别用来跑哪些配方 */
  recipeIds: string[];
}

export interface PlanByproduct {
  item: string;
  kind: StackKind;
  ratePerMinute: number;
  /** 产量是概率性的，这是期望值 */
  probabilistic: boolean;
  fromRecipe: string;
}

export interface ProductionPlan {
  target: { item: string; kind: StackKind; ratePerMinute: number };
  root: PlanNode;
  rawMaterials: PlanRawMaterial[];
  machines: PlanMachine[];
  byproducts: PlanByproduct[];
  /** 每分钟总耗能（FE）。有任一环节读不到能耗时为 null。 */
  totalEnergyPerMinute: number | null;
  warnings: string[];
  truncated: boolean;
  /** 需要机器但耗时未知、只能手工的环节数 */
  manualSteps: number;
  /**
   * 上面那些环节里属于**原版工作台合成**的条数。
   *
   * 单独记一个数，是因为「耗时未知」有两种完全不同的原因，混为一谈会误导人：
   *
   * - `minecraft:crafting` —— 原版**设计上就没有耗时字段**，报「手工」是对的。
   * - 模组机器配方（`create:filling` 之类）—— 是**我们读不到耗时**，那是数据缺口，
   *   产线本该给出机器台数却给不出。
   *
   * 在 Create 专精的整合包上实测过这个混淆：产线头部写「耗时未知（通常是工作台合成）」，
   * 而同一条输出下面写着「用 create:filling 制作」—— 填充机是机器不是工作台，
   * 两句话自相矛盾，而且把「我们的缺口」说成了「原版就这样」。
   */
  manualCraftingSteps: number;
}

class PlanWalker extends ResolutionEngine {
  private nodeCount = 0;
  private truncated = false;
  private manualSteps = 0;
  private manualCraftingSteps = 0;

  private readonly rawTotals = new Map<string, PlanRawMaterial>();
  private readonly machineTotals = new Map<string, PlanMachine>();
  private readonly byproductTotals = new Map<string, PlanByproduct>();
  private energyTotal = 0;
  private energyIncomplete = false;

  constructor(
    store: RecipeStore,
    opts: ExpansionOptions,
    private readonly ratePerMinute: number,
  ) {
    super(store, opts);
  }

  run(kind: StackKind, id: string): ProductionPlan {
    this.energyIncomplete = false;
    this.energyTotal = 0;

    const root = this.expand(kind, id, this.ratePerMinute, [], 0);

    return {
      target: { item: id, kind, ratePerMinute: this.ratePerMinute },
      root,
      rawMaterials: [...this.rawTotals.values()].sort((a, b) => a.item.localeCompare(b.item)),
      machines: [...this.machineTotals.values()].sort((a, b) => a.machine.localeCompare(b.machine)),
      byproducts: [...this.byproductTotals.values()].sort((a, b) => a.item.localeCompare(b.item)),
      totalEnergyPerMinute: this.energyIncomplete ? null : this.energyTotal,
      warnings: this.warnings,
      truncated: this.truncated,
      manualSteps: this.manualSteps,
      manualCraftingSteps: this.manualCraftingSteps,
    };
  }

  private expand(kind: StackKind, id: string, rate: number, path: string[], depth: number): PlanNode {
    this.nodeCount++;

    const node: PlanNode = { item: id, kind, status: "craft", ratePerMinute: rate, alternatives: [], children: [] };

    if (this.opts.rawMaterials.includes(id)) {
      node.status = "raw";
      node.note = "用户指定为基础原料";
      this.addRaw(kind, id, rate);
      return node;
    }

    if (path.includes(id)) {
      node.status = "cycle";
      node.note = `循环依赖：${[...path.slice(path.indexOf(id)), id].join(" → ")}`;
      // 循环链上的产出量无法确定，不计入原料（否则会低估原料需求），
      // 但要留下警告 —— 静默吞掉会让产线报告看起来完整而实际不完整。
      this.warnings.push(`检测到循环依赖，${id} 的需求量未能展开：${node.note}`);
      return node;
    }

    if (depth >= this.opts.maxDepth) {
      node.status = "truncated";
      node.note = `超过深度限制 ${this.opts.maxDepth}，未继续展开`;
      this.truncated = true;
      this.warnings.push(`${id} 因深度限制未展开，原料表不完整`);
      return node;
    }

    if (this.nodeCount > this.opts.maxNodes) {
      node.status = "budget";
      node.note = `超过节点总数上限 ${this.opts.maxNodes}，未继续展开`;
      this.truncated = true;
      this.warnings.push(`${id} 因节点数上限未展开，原料表不完整`);
      return node;
    }

    const candidates = this.candidatesFor(kind, id);
    if (candidates.length === 0) {
      node.status = "raw";
      node.note = "没有配方能产出它，视为基础原料";
      this.addRaw(kind, id, rate);
      return node;
    }

    const usable = candidates.filter((r) => !r.opaque);
    if (usable.length === 0) {
      node.status = "opaque";
      node.note = `有 ${candidates.length} 条配方，但都读不懂输入输出，无法计入计算`;
      node.alternatives = candidates.map((r) => r.id);
      this.warnings.push(`${id} 的配方读不懂，其原料需求未计入，原料表不完整`);
      return node;
    }

    const { recipe } = this.chooseRecipe(id, usable, path);
    const yieldInfo = this.computeYield(kind, id, recipe);
    const craftsPerMinute = rate / yieldInfo.perCraft;
    const childPath = [...path, id];

    node.recipeId = recipe.id;
    node.recipeType = recipe.type;
    node.craftsPerMinute = craftsPerMinute;
    node.outputPerCraft = yieldInfo.perCraft;
    if (yieldInfo.probabilistic) node.probabilistic = true;
    node.machineId = recipe.machine ?? null;
    node.alternatives = candidates.filter((r) => r.id !== recipe.id).map((r) => r.id);

    if (yieldInfo.probabilistic) {
      node.note = `概率产出，按期望产量 ${yieldInfo.perCraft}/次 估算`;
      this.warnings.push(`${id} 是概率产出，机器数按期望值算，实际会偏少`);
    }

    // ---- 机器数与能耗 ----
    if (recipe.duration == null || recipe.duration <= 0) {
      node.machines = null;
      node.secondsPerCraft = null;
      this.manualSteps++;
      // 区分「原版就没有这个字段」和「我们读不到」。crafting 是前者，其余按后者处理 ——
      // 宁可说成「读不到」也不要谎称「这是手工合成」。
      if (recipe.type === "minecraft:crafting") this.manualCraftingSteps++;
      this.warnings.push(`${id} 用 ${recipe.type} 制作，但耗时未知，无法计算机器数`);
    } else {
      node.secondsPerCraft = recipe.duration / TICKS_PER_SECOND;
      const machines = Math.ceil((craftsPerMinute * recipe.duration) / TICKS_PER_MINUTE);
      node.machines = machines;

      if (recipe.machine) {
        const key = recipe.machine;
        const existing = this.machineTotals.get(key);
        if (existing) {
          existing.count += machines;
          if (!existing.recipeIds.includes(recipe.id)) existing.recipeIds.push(recipe.id);
        } else {
          this.machineTotals.set(key, { machine: key, count: machines, recipeIds: [recipe.id] });
        }
      }
    }

    if (recipe.energy == null) {
      this.energyIncomplete = true;
    } else {
      this.energyTotal += recipe.energy * craftsPerMinute;
    }

    // ---- 副产 ----
    this.collectByproducts(recipe, id, craftsPerMinute, kind);

    // ---- 递归 ----
    // 与配方树同理：先按物品合并槽位再递归。原版有序合成把「3 个铁锭」拆成 3 个槽位，
    // 不合并会出现三份相同的机器统计。见 ResolutionEngine.mergeInputs。
    const { merged, unresolved } = this.mergeInputs(recipe.inputs);

    for (const ing of unresolved) {
      const shown = ing.options.map((o) => (o.type === "tag" ? `#${o.id}` : o.id)).join(" | ");
      this.nodeCount++;
      node.children.push({
        item: "(无法解析)",
        kind: ing.kind,
        status: "unresolved",
        ratePerMinute: ing.count * craftsPerMinute,
        alternatives: [],
        children: [],
        note: `配方槽位无法解析：${shown}`,
      });
      this.warnings.push(`配方 ${recipe.id} 有一个槽位无法解析：${shown}`);
    }

    for (const slot of merged) {
      const child = this.expand(slot.kind, slot.id, slot.count * craftsPerMinute, childPath, depth + 1);
      if (slot.fromTag) {
        child.chosenFromTag = slot.fromTag;
        child.tagAlternatives = slot.alternatives;
      }
      node.children.push(child);
    }

    return node;
  }

  /**
   * 统计副产。
   *
   * 两类都算：
   *   - 概率产出（期望值）
   *   - 一条配方除了目标物品以外的其他必然产出（比如某配方同时产 A 和 B，
   *     我们要 A，那 B 就是白送的）
   *
   * 第二类容易被漏掉 —— 只收集 chanceOutputs 会漏掉多输出配方的必然副产。
   */
  private collectByproducts(
    recipe: { id: string; outputs: { item: string; count: number }[]; fluidOutputs?: { fluid: string; amount: number }[]; chanceOutputs?: { stack: { item: string; count: number }; chance: number }[] },
    targetItem: string,
    craftsPerMinute: number,
    targetKind: StackKind,
  ): void {
    for (const out of recipe.outputs) {
      if (out.item === targetItem && targetKind === "item") continue;
      this.addByproduct("item", out.item, out.count * craftsPerMinute, false, recipe.id);
    }

    for (const out of recipe.fluidOutputs ?? []) {
      if (out.fluid === targetItem && targetKind === "fluid") continue;
      this.addByproduct("fluid", out.fluid, out.amount * craftsPerMinute, false, recipe.id);
    }

    for (const c of recipe.chanceOutputs ?? []) {
      if (c.stack.item === targetItem && targetKind === "item") continue;
      this.addByproduct("item", c.stack.item, c.stack.count * c.chance * craftsPerMinute, true, recipe.id);
    }
  }

  private addRaw(kind: StackKind, id: string, rate: number): void {
    const existing = this.rawTotals.get(id);
    if (existing) existing.ratePerMinute += rate;
    else this.rawTotals.set(id, { item: id, kind, ratePerMinute: rate });
  }

  private addByproduct(kind: StackKind, id: string, rate: number, probabilistic: boolean, fromRecipe: string): void {
    const existing = this.byproductTotals.get(id);
    if (existing) {
      existing.ratePerMinute += rate;
      // 只要有一条来源是概率的，合并后的量就是期望值
      existing.probabilistic = existing.probabilistic || probabilistic;
    } else {
      this.byproductTotals.set(id, { item: id, kind, ratePerMinute: rate, probabilistic, fromRecipe });
    }
  }
}

export function calculatePlan(
  store: RecipeStore,
  kind: StackKind,
  id: string,
  options: Partial<PlanOptions> & { ratePerMinute: number },
): ProductionPlan {
  const { ratePerMinute, ...rest } = options;
  const walker = new PlanWalker(store, normalizeOptions(rest), ratePerMinute);
  return walker.run(kind, id);
}

/** 节点是否代表一个可继续展开的配方环节。 */
export function isCraftNode(node: PlanNode): node is PlanNode & { recipeId: string; craftsPerMinute: number } {
  return node.recipeId !== undefined && node.craftsPerMinute !== undefined;
}

/**
 * 把产线结构裁剪到指定展示深度。
 *
 * 与配方树同理：顶部那几张汇总表（机器/原料/副产/能耗）才是价值所在，
 * 「逐环节明细」是成本大头，而且多数问题看汇总就够了。
 *
 * @param detailDepth 结构展示到第几层（根节点为第 0 层）
 */
export function prunePlan(root: PlanNode, detailDepth: number): PlanNode {
  const countNodes = (node: PlanNode): number => {
    let n = 1;
    for (const c of node.children) n += countNodes(c);
    return n;
  };

  const walk = (node: PlanNode, depth: number): PlanNode => {
    if (node.children.length === 0) return node;
    if (depth >= detailDepth) {
      return { ...node, children: [], subtree: { nodeCount: countNodes(node) } };
    }
    return { ...node, children: node.children.map((c) => walk(c, depth + 1)) };
  };
  return walk(root, 0);
}
