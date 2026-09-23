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
import type { Recipe } from "./types.js";

/** 一秒 20 游戏刻。 */
const TICKS_PER_SECOND = 20;
const TICKS_PER_MINUTE = TICKS_PER_SECOND * 60;

/**
 * 每个环节最多列出几条候选配方的事实。
 *
 * 上限的理由是 token：整合包里一个物品常有十几条候选（原版就有熔炼/高炉/合成），
 * 全列出来会占掉报告的大头，而读的人真正想知道的是「有没有比它更省的那条」。
 * 3 条按输入种数排序取前几，通常就覆盖了所有「更省」的可能。
 */
const MAX_DETAILED_ALTERNATIVES = 3;

/**
 * 这条候选在**选路规则的排序**下是否胜过选中配方。
 *
 * 刻意只比规则本身的两个判据（输入种数 → 每次产出），**不含任何罚分** ——
 * 它要回答的问题正是「为什么不是这条更优的」，所以必须站在没有罚分的角度比。
 * 与 resolution.ts 的 scoreRecipe 共用同一组判据方向，改那边这里要跟着改。
 */
function beatsByRule(alt: { inputSlots: number | null; perCraft: number | null }, chosenSlots: number | null, chosenPerCraft: number): boolean {
  const av = alt.inputSlots ?? Number.POSITIVE_INFINITY;
  const cv = chosenSlots ?? Number.POSITIVE_INFINITY;
  if (av !== cv) return av < cv;
  return (alt.perCraft ?? 0) > chosenPerCraft;
}

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

  /**
   * 选中配方的**合并后输入种数**（同一种输入算一个，见 mergeInputs）。
   *
   * 它和 {@link alternativesDetail} 里的同一个数字摆在一起，就是「为什么这条」的答案：
   * 选路规则是「不构成循环的候选里取输入种数最少的」。光报候选数量对模型没用 ——
   * 实测它只能自己编一句理由，或者干脆不提还有别的路线。
   */
  inputSlots?: number;
  /**
   * 其余候选里**有可能本该选它**的那几条，按输入种数从少到多取前几条。
   *
   * <h2>为什么只留「有可能本该选它」的</h2>
   *
   * 先量过再定的：一份 404 节点的规划里有 79 个节点带候选，全列数字要 8.5k 字符
   * （占整份报告 21%），而其中**只有 26 个**节点的候选不比选中配方更差 ——
   * 剩下 53 个的候选全都能用规则直接排掉（输入种数更多）。花 6k 字符去报
   * 「有几条明显更差的路线」，读的人不会因此改变任何决定。
   *
   * <p>所以：有可能被换用的列数字，其余只报数量（见 renderPlanNode）。
   * 真要完整清单，`find_alternative_recipes` 一次调用就有。
   */
  alternativesDetail?: PlanAlternative[];

  chosenFromTag?: string;
  tagAlternatives?: string[];
  alternatives: string[];
  children: PlanNode[];
  note?: string;

  /**
   * `status === "raw"` 时，为什么它是基础原料。
   *
   * 两种含义完全不同，必须分开：`no_recipe` 是这个包里**没有**能产出它的配方
   * （玩家得自己想办法），`user_declared` 是调用方声明「到此为止」。
   * 靠解析中文 note 来区分太脆弱 —— 与 status 字段同一个理由。
   */
  rawReason?: "no_recipe" | "user_declared";

  /** 被展示裁剪掉的分支规模。见 tree.ts 的 pruneTree 说明。 */
  subtree?: { nodeCount: number };
}

export interface PlanRawMaterial {
  item: string;
  kind: StackKind;
  ratePerMinute: number;
}

/**
 * 一条**没被选中**的候选配方，以及它输在哪。
 *
 * 字段刻意只有「决定性事实」两三个：选路用的就是这两个数字（外加「会不会成环」），
 * 所以把选中配方和候选配方的同一组数字并排给出，读的人就能自己复核这个选择，
 * 而不必信一句「工具选过了」。
 */
export interface PlanAlternative {
  recipeId: string;
  /** 给人看的类型名 */
  type: string;
  /** 合并后的输入种数。null = 这条配方读不懂输入（opaque） */
  inputSlots: number | null;
  /** 每次产出。null = 读不懂产出，**不编一个数** */
  perCraft: number | null;
  /**
   * 一次配方耗时（秒）。null = 这条读不到 —— 与选中配方的同一个字段对照着看。
   *
   * 它是「选中那条读不到耗时、而这条读得到」的凭据：那种情况下候选不只是换条路线，
   * 而是**唯一能算出台数的路**。实测痛点就在这：整合包里模型只能报「台数未知」，
   * 而同一个物品的另一条配方明明带着 processingTime。
   */
  secondsPerCraft?: number | null;
  /**
   * 按选路规则**本该选它**、却没选的原因。
   *
   * <h2>为什么必须有这个字段</h2>
   *
   * 光把候选的数字摆出来还不够：有时候选在规则下**明显更优**（输入更少、或并列时产出更多），
   * 报告却选了另一条 —— 读的人只会觉得工具选错了。实测就是这个形状：
   * 「1 铁块 → 9 铁锭」输入 1 种、每次产 9，规则下完胜，但它会成环（铁块要 9 个铁锭），
   * 于是被重罚。不写这一句，模型只能自己编理由，或者反过来质疑数字。
   *
   * 三种原因与 scoreRecipe 里的三类罚分一一对应，不额外发明第四种。
   */
  skipReason?: "cycle" | "probabilistic" | "user_choice";
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

/**
 * 一份副产能不能在规划内部被用掉。
 *
 * <h2>它只是提示，不改数字</h2>
 *
 * 报告里的原料表**刻意不假设你会把副产用回去** —— 能不能回代取决于产物能不能运回上游、
 * 你愿不愿意改配方。假设了会让照着表建产线的人拿到一份做不到的账。
 * 但「这份盐酸正好是透镜那条替代配方的输入」是**事实**，工具手里有全部数据却不说，
 * 人就只能自己发现（实测：模型靠手工比对才发现上游能砍一半）。
 * 所以把它算出来告诉人，但明确标注「上面的数字没有假设这个」。
 */
export interface ByproductReuse {
  item: string;
  kind: StackKind;
  ratePerMinute: number;
  /** 规划里已经在吃它的环节 → 这份副产本来就是别处的原料 */
  usedBy: { item: string; recipeId: string }[];
  /** 某个环节的**替代配方**会吃它 → 换过去就能把这份副产变成原料 */
  alternativeFor: { item: string; recipeId: string }[];
}

export interface ProductionPlan {
  target: { item: string; kind: StackKind; ratePerMinute: number };
  root: PlanNode;
  rawMaterials: PlanRawMaterial[];
  machines: PlanMachine[];
  byproducts: PlanByproduct[];
  /**
   * 副产能不能在规划内部被用掉。**只是提示，数字不因它改变**，见 reuseOf 的说明。
   */
  byproductReuse: ByproductReuse[];
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
    const byproducts = [...this.byproductTotals.values()].sort((a, b) => a.item.localeCompare(b.item));

    return {
      target: { item: id, kind, ratePerMinute: this.ratePerMinute },
      root,
      rawMaterials: [...this.rawTotals.values()].sort((a, b) => a.item.localeCompare(b.item)),
      machines: [...this.machineTotals.values()].sort((a, b) => a.machine.localeCompare(b.machine)),
      byproducts,
      byproductReuse: this.reuseOf(root, byproducts),
      totalEnergyPerMinute: this.energyIncomplete ? null : this.energyTotal,
      warnings: this.warnings,
      truncated: this.truncated,
      manualSteps: this.manualSteps,
      manualCraftingSteps: this.manualCraftingSteps,
    };
  }

  /**
   * 副产回代：这份副产能不能被规划里的某个环节吃掉。
   *
   * 只用现成的倒排索引（`recipesConsuming` 在建索引时就把标签展开过了），
   * 所以「某个槽位写的是 #xxx，而这份副产正好是 xxx 的成员」天然命中 ——
   * 这正是配方语义里最容易被忽略的一种「已经有地方在吃它」。
   *
   * 结果只是提示：报告里的数字**没有**假设你把副产用回去（见 ByproductReuse 的说明）。
   */
  private reuseOf(root: PlanNode, byproducts: PlanByproduct[]): ByproductReuse[] {
    if (byproducts.length === 0) return [];

    // 规划里用到的配方 → 哪些物品在用
    const usedRecipes = new Map<string, string[]>();
    // 规划里各物品的候选配方 → 哪些物品可以改用它
    const altRecipes = new Map<string, string[]>();
    const walk = (node: PlanNode): void => {
      if (node.recipeId) {
        const list = usedRecipes.get(node.recipeId);
        if (list) list.push(node.item);
        else usedRecipes.set(node.recipeId, [node.item]);
      }
      for (const alt of node.alternatives) {
        const list = altRecipes.get(alt);
        if (list) list.push(node.item);
        else altRecipes.set(alt, [node.item]);
      }
      node.children.forEach(walk);
    };
    walk(root);

    const out: ByproductReuse[] = [];
    for (const byproduct of byproducts) {
      const usedBy: ByproductReuse["usedBy"] = [];
      const alternativeFor: ByproductReuse["alternativeFor"] = [];
      for (const recipe of this.store.recipesConsuming(byproduct.kind, byproduct.item)) {
        for (const item of usedRecipes.get(recipe.id) ?? []) usedBy.push({ item, recipeId: recipe.id });
        for (const item of altRecipes.get(recipe.id) ?? []) alternativeFor.push({ item, recipeId: recipe.id });
      }
      if (usedBy.length > 0 || alternativeFor.length > 0) {
        out.push({
          item: byproduct.item,
          kind: byproduct.kind,
          ratePerMinute: byproduct.ratePerMinute,
          usedBy,
          alternativeFor,
        });
      }
    }
    return out;
  }

  private expand(kind: StackKind, id: string, rate: number, path: string[], depth: number): PlanNode {
    this.nodeCount++;

    const node: PlanNode = { item: id, kind, status: "craft", ratePerMinute: rate, alternatives: [], children: [] };

    if (this.opts.rawMaterials.includes(id)) {
      node.status = "raw";
      node.rawReason = "user_declared";
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
      node.rawReason = "no_recipe";
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
    const chosenSlots = this.mergedSlotCount(recipe);
    node.inputSlots = chosenSlots ?? undefined;
    node.alternativesDetail = this.describeAlternatives(
      kind,
      id,
      candidates,
      recipe,
      chosenSlots,
      yieldInfo.perCraft,
      recipe.duration != null && recipe.duration > 0 ? recipe.duration / TICKS_PER_SECOND : null,
      path,
    );

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

  /**
   * 合并后的输入种数 —— 选路用的就是它。
   *
   * 与 scoreRecipe 里同一个口径（都走 mergeInputs），**不能各量各的**：
   * 两处用不同的尺子量同一件事，正是这类启发式出怪结果的原因。
   */
  private mergedSlotCount(recipe: Recipe): number | null {
    // opaque = 输入读不懂，不能报 0（那会被读成「不需要材料」）
    if (recipe.opaque) return null;
    return this.mergeInputs(recipe.inputs).merged.length;
  }

  /**
   * 落选候选里、有可能被换用的那几条，按输入种数从少到多取前几条。
   *
   * 排序是刻意的：读的人关心的是「有没有更省的路线」，所以把最省的排前面；
   * 而且一旦最省的那条排在了选中配方前面，就说明这次不是按默认规则选的
   * （成环 / 概率产出 / 用户指定）—— 那就必须写明原因，见 PlanAlternative.skipReason。
   *
   * @param chosenSlots 选中配方的输入种数，null 表示它读不懂输入
   * @param chosenDuration 选中配方的单次耗时（秒），null 表示读不到
   */
  private describeAlternatives(
    kind: StackKind,
    id: string,
    candidates: Recipe[],
    chosen: Recipe,
    chosenSlots: number | null,
    chosenPerCraft: number,
    chosenDuration: number | null,
    path: string[],
  ): PlanAlternative[] | undefined {
    const others = candidates.filter((r) => r.id !== chosen.id);
    if (others.length === 0) return undefined;

    const described: PlanAlternative[] = others.map((r) => ({
      recipeId: r.id,
      type: r.typeLabel ?? r.type,
      inputSlots: this.mergedSlotCount(r),
      // 读不懂产出时给 null，不要 computeYield 那个兜底的 1 —— 那是编的
      perCraft: r.opaque ? null : this.computeYield(kind, id, r).perCraft,
      secondsPerCraft: r.duration != null && r.duration > 0 ? r.duration / TICKS_PER_SECOND : null,
    }));

    described.sort((a, b) => {
      const av = a.inputSlots ?? Number.POSITIVE_INFINITY;
      const bv = b.inputSlots ?? Number.POSITIVE_INFINITY;
      if (av !== bv) return av - bv;
      return (b.perCraft ?? 0) - (a.perCraft ?? 0) || a.recipeId.localeCompare(b.recipeId);
    });

    // 两种候选值得列数字：
    //   ① 不比选中的差（规则有可能本该选它）—— 读不懂输入的进不来，没得比谈不上更优
    //   ② 选中那条读不到耗时、而它读得到 —— 那种情况下它是唯一能算出台数的路
    // 其余（明显更差）只报数量，见 PlanNode.alternativesDetail 的量测说明。
    const competitive = described.filter(
      (a) =>
        (a.inputSlots != null && chosenSlots != null && a.inputSlots <= chosenSlots) ||
        (chosenDuration == null && a.secondsPerCraft != null),
    );
    if (competitive.length === 0) return undefined;

    const shown = competitive.slice(0, MAX_DETAILED_ALTERNATIVES);
    for (const alt of shown) {
      if (!beatsByRule(alt, chosenSlots, chosenPerCraft)) continue;
      const recipe = others.find((r) => r.id === alt.recipeId)!;
      // 顺序与 scoreRecipe 的罚分优先级一致：用户指定 → 成环 → 概率产出
      if (this.opts.recipeChoice[id] === chosen.id) alt.skipReason = "user_choice";
      else if (this.wouldCycle(recipe, path, id)) alt.skipReason = "cycle";
      else if (this.guaranteedYield(id, recipe) <= 0) alt.skipReason = "probabilistic";
    }
    return shown;
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
