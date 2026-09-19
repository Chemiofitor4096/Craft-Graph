/**
 * 缓存生命周期管理。
 *
 * 这里处理的是这个项目最日常的体验问题：**玩家不会一直开着游戏**。
 *
 *   - 游戏开着且数据没变 → 直接用内存里的缓存，不产生任何网络请求
 *   - 游戏开着但重载了配方 → 自动重建（靠 dataVersion 判断）
 *   - 游戏关掉了 → 退回上次的磁盘快照，只读查询照常工作，但会明确标注数据可能过时
 *   - 游戏关掉了且没有快照 → 抛一句人能看懂的话，而不是 ECONNREFUSED
 */

import { BridgeClient } from "./bridge.js";
import { RecipeStore } from "./cache.js";
import { BridgeHttpError, type RegistryPage } from "./types.js";

/** 多久探测一次 Bridge 的状态。太短会浪费请求，太长会让配方重载后反应迟钝。 */
const PROBE_TTL_MS = 10_000;

export class StoreManager {
  private store: RecipeStore | null = null;
  private lastProbe = 0;
  private loading: Promise<RecipeStore> | null = null;

  constructor(private readonly client: BridgeClient) {}

  get location(): string {
    return this.client.describeLocation;
  }

  /**
   * 注册表是「活」的查询，直接走 Bridge，不进快照缓存。
   *
   * 为什么不缓存：注册表查询本身很快，而且用户问的往往是「这个包里到底有没有
   * 某个模组的方块」这类当前状态问题，缓存反而容易给出过时答案。
   */
  async queryRegistry(kind: string, opts: { query?: string; limit?: number }): Promise<RegistryPage> {
    return this.client.registry(kind, opts);
  }

  /**
   * 拿到可用的缓存。必要时会探测 Bridge 状态并重建。
   */
  async get(): Promise<RecipeStore> {
    if (this.store && Date.now() - this.lastProbe < PROBE_TTL_MS) {
      return this.store;
    }

    // 并发调用时只重建一次
    if (this.loading) return this.loading;

    this.loading = this.probeAndLoad();
    try {
      return await this.loading;
    } finally {
      this.loading = null;
    }
  }

  private async probeAndLoad(): Promise<RecipeStore> {
    this.lastProbe = Date.now();

    let dataVersion: number | null = null;
    try {
      const health = await this.client.health();

      // 游戏在运行，但配方还没索引好（通常是在启动或加载世界）。
      //
      // 这里必须显式拦一下：不能拿 recipeCount=0 去建缓存。否则会缓存一个空数据集，
      // 之后即使游戏加载完了，用户看到的也是「查什么都说没有」——
      // 而不是「还在加载」。前者会让人以为 Mod 坏了。
      if (health.ready === false) {
        if (this.store) return this.store;
        throw new BridgeHttpError(
          503,
          "NOT_READY",
          "游戏正在加载，配方数据还没准备好。等进入世界后再试，或先用 get_bridge_status 查看状态。",
        );
      }

      dataVersion = health.dataVersion;
    } catch (err) {
      if (err instanceof BridgeHttpError) throw err;
      // 连不上游戏 —— 手上已有缓存就继续用，否则让 load() 去试磁盘快照
      if (this.store) return this.store;
      this.store = await RecipeStore.load(this.client);
      return this.store;
    }

    if (this.store && !this.store.status.offline && this.store.status.dataVersion === dataVersion) {
      return this.store;
    }

    this.store = await RecipeStore.load(this.client);
    return this.store;
  }

  /** 强制重建，忽略 TTL。 */
  async refresh(): Promise<RecipeStore> {
    this.lastProbe = Date.now();
    this.store = await RecipeStore.load(this.client);
    return this.store;
  }
}

/**
 * 把缓存状态渲染成一段给 AI 看的提示。
 *
 * 离线时必须显式说出来 —— 否则 AI 会拿一份过时数据给出看起来很确定的答案，
 * 而玩家不会知道它是旧的。
 */
export function describeStatus(store: RecipeStore, location: string): string {
  const s = store.status;
  const lines: string[] = [];

  if (s.offline) {
    lines.push("## ⚠️ 数据是离线的");
    lines.push("");
    lines.push(s.offlineReason ?? "游戏没在运行。");
    lines.push(`当前用的是磁盘上的快照（dataVersion=${s.dataVersion}）。重新打开游戏后会自动更新。`);
  } else {
    lines.push("## ✅ 已连接游戏");
    lines.push("");
    lines.push(`- 配方 ${s.recipeCount} 条，标签 ${s.tagCount} 个`);
    lines.push(`- 数据版本 dataVersion = ${s.dataVersion}（配方重载时会自动重建缓存）`);
  }

  lines.push("");
  lines.push(`- Bridge 地址：${location}`);
  return lines.join("\n");
}
