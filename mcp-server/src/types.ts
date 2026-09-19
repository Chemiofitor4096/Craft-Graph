/**
 * 与 doc/protocol.md 一一对应的类型定义。
 *
 * 改这里之前先改文档，否则两侧会悄悄跑偏。
 * 这里只放「Bridge 传过来的数据」，不放算法内部结构（那些在各引擎文件里）。
 */

// ---------------------------------------------------------------- 物品与流体

/** 具体、可数的东西。 */
export interface ItemStack {
  /** 注册表 id，如 "minecraft:iron_ingot" */
  item: string;
  count: number;
  /** 1.21 数据组件。当前只是原样透传，不理解内部结构。 */
  components?: Record<string, unknown> | null;
}

/** 流体。amount 单位是毫桶(mB)，Minecraft 流体的标准单位。 */
export interface FluidStack {
  fluid: string;
  /** mB */
  amount: number;
}

// ---------------------------------------------------------------- 配方槽位

export type OptionType = "item" | "tag" | "fluid";

export interface IngredientOption {
  type: OptionType;
  /** 物品 id、标签 id（不带 # 前缀）或流体 id */
  id: string;
}

/**
 * 一个配方槽位：满足任意一个 option 都算数。
 *
 * options 里出现 tag 意味着这个槽位有多种可选原料，产线计算时必须做选择。
 * 把 tag 当成一个普通物品处理是这类工具最常见的错误。
 */
export interface Ingredient {
  kind: "item" | "fluid";
  /** 物品个数；kind 为 fluid 时是 mB */
  count: number;
  options: IngredientOption[];
}

// ---------------------------------------------------------------- 配方

/** 概率产出。chance 是 0~1 的概率。 */
export interface ChanceOutput {
  stack: ItemStack;
  /** 0~1 */
  chance: number;
}

/** 这条数据是谁归一化的。 */
export type RecipeSource = "vanilla" | "emi" | "adapter";

export interface Recipe {
  id: string;
  /** 配方类型 id，如 "minecraft:smelting" */
  type: string;
  /** 给人看的类型名，装了 EMI/JEI 时更准 */
  typeLabel?: string | null;

  inputs: Ingredient[];
  outputs: ItemStack[];
  fluidOutputs?: FluidStack[];
  chanceOutputs?: ChanceOutput[];

  /** 机器方块 id；推断不出来时为 null */
  machine?: string | null;
  /** 一次合成的耗时，单位游戏刻(ticks)。20 tick = 1 秒。未知为 null。 */
  duration?: number | null;
  /** 一次合成的耗能，单位 FE。未知为 null。 */
  energy?: number | null;

  source: RecipeSource;
  /**
   * true 表示这个配方的输入/输出没能被正确解析。
   *
   * 必须显式标记而不是返回空 inputs —— 空 inputs 看起来像"这配方不要原料"，
   * AI 会据此得出错误结论且不自知。标记后上层就能诚实地说"读不懂"。
   */
  opaque: boolean;
}

/** 列表接口返回的摘要，不含完整 inputs（省流量）。 */
export interface RecipeSummary {
  id: string;
  type: string;
  typeLabel?: string | null;
  primaryOutput: ItemStack | null;
  opaque: boolean;
}

// ---------------------------------------------------------------- 服务元信息

export interface ModPresence {
  version: string;
}

/** GET /health 的响应。不需要 token，任何时刻都必须能立刻返回。 */
export interface Health {
  /** 服务活着（能响应就为 true） */
  ok: boolean;
  /**
   * 配方数据是否已经索引好、可以查询。
   *
   * 游戏刚启动、或世界正在加载时会为 false —— 此时 /health 能返回，但其他端点返回 503。
   * **这个区分很重要**：没有它的话，加载期间会看到 recipeCount=0，
   * 然后建出一个空缓存，表现为「查什么都说没有」而不是「还在加载」。
   *
   * 可选是为了兼容还没实现该字段的旧版 Mod：缺失时按 true 处理。
   */
  ready?: boolean;
  protocolVersion: number;
  modVersion: string;
  mcVersion: string;
  loader: string;
  /** 未安装为 null */
  emi: ModPresence | null;
  jei: ModPresence | null;
  recipeCount: number;
  itemCount: number;
  /**
   * 单调递增。配方或注册表发生任何变化时 +1。
   * 所有缓存的失效判断都以它为准 —— 不要用启动时间或配方数量代替，
   * 数量可能不变而内容变了。
   */
  dataVersion: number;
  uptimeMs: number;
}

export interface RegistryEntry {
  id: string;
  displayName?: string | null;
}

export interface RegistryPage {
  kind: string;
  total: number;
  offset: number;
  limit: number;
  entries: RegistryEntry[];
}

export interface TagListEntry {
  id: string;
  /** 成员数量 */
  count: number;
}

export interface TagListPage {
  kind: string;
  total: number;
  entries: TagListEntry[];
}

export interface TagExpansion {
  id: string;
  entries: string[];
}

/**
 * GET /tags/{kind}/all —— 一次拉全部标签及成员。
 *
 * 建倒排索引时需要把标签展开成具体物品（否则「哪些配方消耗铁锭」会漏掉
 * 所有用 #forge:ingots/iron 的配方），逐个标签拉在大整合包里是上千次请求。
 */
export interface TagAllPage {
  kind: string;
  tags: Record<string, string[]>;
}

export interface RecipeQueryPage {
  total: number;
  offset: number;
  limit: number;
  recipes: RecipeSummary[];
}

/** GET /snapshot 的一页。 */
export interface SnapshotPage {
  dataVersion: number;
  cursor: number;
  /** null 表示这是最后一页 */
  nextCursor: number | null;
  total: number;
  recipes: Recipe[];
}

// ---------------------------------------------------------------- 错误

export interface BridgeErrorBody {
  error: {
    code: string;
    message: string;
  };
}

/** Bridge 返回了非 2xx。 */
export class BridgeHttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "BridgeHttpError";
  }
}

/** 连不上 Bridge（游戏没开、端口不对、token 错等）。 */
export class BridgeUnreachableError extends Error {
  constructor(
    message: string,
    readonly reason?: unknown,
  ) {
    super(message);
    this.name = "BridgeUnreachableError";
  }
}
