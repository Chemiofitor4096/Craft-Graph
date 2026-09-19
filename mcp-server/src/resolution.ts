/**
 * 配方选择与槽位解析 —— 配方树和产线计算共用的底层逻辑。
 *
 * 为什么要抽出来：如果树和产线各写一份「选哪条配方、标签挑哪个成员」，
 * 两边迟早会给出不一致的答案（树说用高炉，产线按熔炉算），
 * 而且这种 bug 极难排查。所有取舍规则只能有一份实现。
 */

import { type RecipeStore, type StackKind } from "./cache.js";
import { type Ingredient, type Recipe } from "./types.js";

export interface ExpansionOptions {
  /** 最大展开深度。默认 8。 */
  maxDepth: number;
  /** 节点总数上限，防止爆炸。默认 400。 */
  maxNodes: number;
  /** 指定某个物品用哪条配方：itemId -> recipeId */
  recipeChoice: Record<string, string>;
  /** 指定某个标签选哪个成员：tagId -> itemId */
  tagChoice: Record<string, string>;
  /** 视为基础原料、不再展开的物品 id */
  rawMaterials: string[];
}

export const DEFAULT_EXPANSION_OPTIONS: ExpansionOptions = {
  maxDepth: 8,
  maxNodes: 400,
  recipeChoice: {},
  tagChoice: {},
  rawMaterials: [],
};

/**
 * 合并用户选项与默认值。
 *
 * ⚠️ 不能用 `{ ...DEFAULT, ...partial }`。JS 的展开运算会把显式传进来的
 * `undefined` 一起覆盖上去，于是 `{ rawMaterials: undefined }` 会把默认的 `[]`
 * 抹掉，后面 `rawMaterials.includes(...)` 就炸了。
 *
 * 这个 bug 很容易漏掉：直接调用时通常只传需要的字段，看不出问题；
 * 只有走 MCP 工具（它的处理器会把每个参数都列出来，未传的就是 undefined）
 * 才会暴露。所以这里必须逐字段判空。
 */
export function normalizeOptions(partial?: Partial<ExpansionOptions>): ExpansionOptions {
  return {
    maxDepth: partial?.maxDepth ?? DEFAULT_EXPANSION_OPTIONS.maxDepth,
    maxNodes: partial?.maxNodes ?? DEFAULT_EXPANSION_OPTIONS.maxNodes,
    recipeChoice: partial?.recipeChoice ?? DEFAULT_EXPANSION_OPTIONS.recipeChoice,
    tagChoice: partial?.tagChoice ?? DEFAULT_EXPANSION_OPTIONS.tagChoice,
    rawMaterials: partial?.rawMaterials ?? DEFAULT_EXPANSION_OPTIONS.rawMaterials,
  };
}

/** 一个配方槽位解析后的结果。 */
export interface ResolvedInput {
  kind: StackKind;
  id: string;
  /** 这个物品是从哪个标签里挑出来的 */
  fromTag?: string;
  /** 同一个标签里的其他候选 */
  alternatives: string[];
}

/** 合并后的槽位：若干指向同一物品的原始槽位合成一个。 */
export interface MergedSlot {
  kind: StackKind;
  id: string;
  /** 合并后的总数量 */
  count: number;
  fromTag?: string;
  alternatives: string[];
  /** 由几个原始槽位合并而来。>1 说明原配方把它们拆开了。 */
  slotCount: number;
}

export interface MergedInputs {
  merged: MergedSlot[];
  /** 无法解析的槽位，原样带出去让上层如实报告 */
  unresolved: Ingredient[];
}

export interface YieldInfo {
  /** 一次配方产出多少个目标物品。概率产出时为期望值。 */
  perCraft: number;
  /** perCraft 是期望值而非确定值 */
  probabilistic: boolean;
}

export interface RecipeChoice {
  recipe: Recipe;
  /** 首选配方会成环，被迫换了另一条 */
  avoidedCycle: boolean;
}

export class ResolutionEngine {
  /** 展开过程中产生的警告，调用方负责原样呈现给用户。 */
  readonly warnings: string[] = [];

  constructor(
    protected readonly store: RecipeStore,
    protected readonly opts: ExpansionOptions,
  ) {}

  // ---------------------------------------------------------------- 候选

  /** 所有产出该物品的配方，含 opaque 和概率产出。 */
  candidatesFor(kind: StackKind, id: string): Recipe[] {
    return this.store.recipesProducing(kind, id);
  }

  /** 候选里输入输出读得懂的。 */
  usableFor(kind: StackKind, id: string): Recipe[] {
    return this.candidatesFor(kind, id).filter((r) => !r.opaque);
  }

  // ---------------------------------------------------------------- 选择

  /**
   * 从多个候选配方里挑一个。
   *
   * 评分刻意做成确定性的 —— 同样的输入必须得到同样的输出，
   * 否则用户会怀疑结果在乱跳，也没法写测试。
   */
  chooseRecipe(id: string, usable: Recipe[], path: string[]): RecipeChoice {
    const overridden = this.opts.recipeChoice[id];
    if (overridden) {
      const found = usable.find((r) => r.id === overridden);
      if (found) return { recipe: found, avoidedCycle: false };
      this.warnings.push(`指定的配方 ${overridden} 不能产出 ${id} 或读不懂，已忽略该指定`);
    }

    const scored = usable.map((r) => ({
      recipe: r,
      cycles: this.wouldCycle(r, path, id),
      score: this.scoreRecipe(id, r, path),
    }));

    scored.sort((a, b) => (a.score !== b.score ? a.score - b.score : a.recipe.id.localeCompare(b.recipe.id)));

    const best = scored[0]!;
    const avoidedCycle = best.cycles && scored.some((s) => !s.cycles);
    if (avoidedCycle) {
      this.warnings.push(`物品 ${id} 的首选配方会形成循环依赖，已改用 ${best.recipe.id}`);
    }
    return { recipe: best.recipe, avoidedCycle };
  }

  private scoreRecipe(id: string, recipe: Recipe, path: string[]): number {
    let score = 0;

    // 会导致循环依赖的，重罚
    if (this.wouldCycle(recipe, path, id)) score += 10_000;

    // 只用概率产出该物品的，重罚（数量算不准）
    if (this.guaranteedYield(id, recipe) <= 0) score += 1_000;

    // 输入槽位越少越简单
    score += recipe.inputs.length * 10;

    // 一次产出越多越好（略微偏好），上限 9 避免支配其他因素
    score -= Math.min(this.computeYield("item", id, recipe).perCraft, 9);

    if (recipe.opaque) score += 500;

    return score;
  }

  /**
   * 这条配方会不会导致循环依赖。
   *
   * **必须做两级判断，只看直接输入是不够的。** 反例：
   *   「1 铁块 → 9 铁锭」的输入是铁块，不在路径上，看起来完全正常；
   *   但铁块的配方要 9 个铁锭，展开一步就绕回来了。
   * 只看一级的话，规划「每分钟 10 个铁锭」会选中这条循环路线
   * （因为它输入槽位少、产出多，评分反而最高），整个结果就没有意义了。
   *
   * 所以这里既检查直接输入，也检查「输入的配方」的输入。
   */
  wouldCycle(recipe: Recipe, path: string[], targetId?: string): boolean {
    const forbidden = new Set(path);
    if (targetId) forbidden.add(targetId);

    for (const ing of recipe.inputs) {
      // --- 第一级：直接输入 ---
      const direct = this.optionIds(ing, forbidden);
      if (direct) return true;

      // --- 第二级：这个输入的配方会不会用到禁用品 ---
      const resolved = this.resolveIngredient(ing);
      if (!resolved) continue;
      for (const nested of this.store.recipesProducing(resolved.kind, resolved.id)) {
        if (nested.opaque) continue;
        for (const nestedIng of nested.inputs) {
          if (this.optionIds(nestedIng, forbidden)) return true;
        }
      }
    }
    return false;
  }

  /** 槽位里是否有任何选项命中禁用品集合（标签会展开后再比对）。 */
  private optionIds(ing: Ingredient, forbidden: Set<string>): boolean {
    for (const opt of ing.options) {
      if (opt.type === "tag") {
        for (const member of this.store.expandTag(opt.id) ?? []) {
          if (forbidden.has(member)) return true;
        }
      } else if (forbidden.has(opt.id)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- 产量

  /** 目标物品作为「必然产出」时的每次产量。0 表示只出现在概率产出里。 */
  guaranteedYield(id: string, recipe: Recipe): number {
    let total = 0;
    for (const o of recipe.outputs) if (o.item === id) total += o.count;
    for (const o of recipe.fluidOutputs ?? []) if (o.fluid === id) total += o.amount;
    return total;
  }

  computeYield(kind: StackKind, id: string, recipe: Recipe): YieldInfo {
    const guaranteed = this.guaranteedYield(id, recipe);
    if (guaranteed > 0) return { perCraft: guaranteed, probabilistic: false };

    // 只看概率产出：按期望值算。这是近似 —— 概率产出意味着实际需要更多次，
    // 调用方必须把 probabilistic 标记透传给用户，不能静默当成确定值。
    let expected = 0;
    for (const c of recipe.chanceOutputs ?? []) {
      if (c.stack.item === id) expected += c.stack.count * c.chance;
    }
    if (expected > 0) return { perCraft: expected, probabilistic: true };

    return { perCraft: 1, probabilistic: true };
  }

  /**
   * 把配方的输入槽位按「解析后的物品」合并。
   *
   * <h2>为什么必须合并</h2>
   *
   * 原版的有序合成（shaped）把「3 个铁锭」表示成 **3 个各含 1 个铁锭的槽位**。
   * 不合并的话，配方树里会出现 3 棵完全相同的子树 —— 实测铁镐的树里
   * `1 × 铁锭` 出现三次，各带一棵一样的子树。后果有两个：
   *
   * <ul>
   *   <li><b>token 白白多花</b>：有序合成是原版最常见的配方类型，
   *       多槽位配方最多能把输出撑到三倍</li>
   *   <li><b>看起来像 bug</b>：报告里并排三棵一模一样的子树，
   *       用户第一反应是「这工具算重了」，而不是「哦这里要 3 个」</li>
   * </ul>
   *
   * <p>合并还有个附带好处：数量合并后算出的合成次数更准。
   * 原来 3 个「各要 1 个」的槽位会各自向上取整（可能算出 3 次），
   * 合并成「要 3 个」之后一次取整（可能只要 1 次）。
   *
   * <p>合并键是解析后的物品（不是原始槽位），所以两个不同槽位恰好解析到
   * 同一个物品时也会合并 —— 那正是我们想要的。
   */
  mergeInputs(inputs: Ingredient[]): MergedInputs {
    const merged = new Map<string, MergedSlot>();
    const unresolved: Ingredient[] = [];

    for (const ing of inputs) {
      const resolved = this.resolveIngredient(ing);
      if (!resolved) {
        unresolved.push(ing);
        continue;
      }

      const key = `${resolved.kind}\u0000${resolved.id}`;
      const existing = merged.get(key);
      if (existing) {
        existing.count += ing.count;
        existing.slotCount++;
      } else {
        const slot: MergedSlot = {
          kind: resolved.kind,
          id: resolved.id,
          count: ing.count,
          alternatives: resolved.alternatives,
          slotCount: 1,
        };
        if (resolved.fromTag) slot.fromTag = resolved.fromTag;
        merged.set(key, slot);
      }
    }

    return { merged: [...merged.values()], unresolved };
  }

  // ---------------------------------------------------------------- 槽位解析

  /**
   * 把一个配方槽位解析成一个具体物品。
   *
   * 优先取具体物品；只有标签时才展开并从成员里挑一个。挑选规则是
   * 「优先 minecraft: 命名空间，然后按字典序」，保证结果稳定可复现。
   * 用户可以用 tagChoice 覆盖。
   */
  resolveIngredient(ing: Ingredient): ResolvedInput | null {
    const concrete = ing.options.find((o) => o.type === "item" || o.type === "fluid");
    if (concrete) {
      return {
        kind: concrete.type === "fluid" ? "fluid" : "item",
        id: concrete.id,
        alternatives: [],
      };
    }

    for (const opt of ing.options) {
      if (opt.type !== "tag") continue;
      const members = this.store.expandTag(opt.id);
      if (!members || members.length === 0) continue;

      const chosen = this.opts.tagChoice[opt.id] ?? pickCanonical(members);
      return {
        kind: ing.kind === "fluid" ? "fluid" : "item",
        id: chosen,
        fromTag: opt.id,
        alternatives: members.filter((m) => m !== chosen),
      };
    }

    return null;
  }
}

/**
 * 从标签成员里挑一个「最像基础物品」的。
 *
 * 规则依次是：优先 {@code minecraft:} 命名空间 → 优先 id 短的 → 字典序。
 *
 * <h2>为什么「id 短」比字典序好</h2>
 *
 * 纯字典序在真实数据上挑出来的东西很反直觉（实测）：
 *
 * <ul>
 *   <li>{@code #minecraft:coals} → {@code charcoal}（因为 "charcoal" &lt; "coal"），
 *       但玩家说的「煤炭」是 {@code coal}</li>
 *   <li>{@code #minecraft:planks} → {@code acacia_planks}（acacia 字母序最前），
 *       而任何人都会默认是橡木</li>
 *   <li>{@code #minecraft:logs_that_burn} → {@code acacia_log}</li>
 * </ul>
 *
 * 换成「短的优先」这三个都对了：{@code coal}(4) &lt; {@code charcoal}(8)、
 * {@code oak_planks} &lt; {@code acacia_planks}、{@code oak_log} &lt; {@code acacia_log}。
 *
 * 这个启发式有实际道理：模组给基础物品加前缀或后缀来造变体
 * （橡木 → 金合欢木、煤炭 → 木炭），所以**基础物品的 id 通常最短**。
 *
 * 它仍是个启发式，一定会挑错某些标签 —— 所以报告里总是显示「【#标签】」，
 * 并且可以用 {@code tagChoice} 覆盖。挑错时用户能看出来，这比挑错还不知道要好。
 */
export function pickCanonical(members: string[]): string {
  const sorted = [...members].sort((a, b) => {
    const av = a.startsWith("minecraft:") ? 0 : 1;
    const bv = b.startsWith("minecraft:") ? 0 : 1;
    if (av !== bv) return av - bv;
    if (a.length !== b.length) return a.length - b.length;
    return a.localeCompare(b);
  });
  return sorted[0]!;
}
