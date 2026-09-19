/**
 * 本地配方缓存与索引。
 *
 * 这是引擎层唯一的数据来源。它负责三件事：
 *   1. 从 Bridge 拉全量数据并建索引
 *   2. 以 dataVersion 为准判断缓存是否失效
 *   3. 游戏没在运行时，退化到磁盘上的上次快照（只读查询仍然可用）
 *
 * 索引是必须的：原版只按产出建了索引，「哪些配方消耗了 X」要自己建倒排索引，
 * 否则每次查询都要遍历上万条配方。
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { BridgeClient } from "./bridge.js";
import { type Ingredient, type Recipe } from "./types.js";

export type StackKind = "item" | "fluid";

interface DiskCache {
  savedAt: string;
  dataVersion: number;
  recipes: Recipe[];
  tags: Record<string, string[]>;
  itemNames: Record<string, string>;
}

/** 磁盘缓存目录。放在用户目录下，避免依赖 MCP Server 的启动工作目录（那是不确定的）。 */
export function cacheDir(): string {
  return process.env.CRAFTGRAPH_CACHE_DIR ?? path.join(os.homedir(), ".craftgraph", "cache");
}

const CACHE_FILE = "snapshot.json";

function refKey(kind: StackKind, id: string): string {
  return `${kind}\u0000${id}`;
}

function tagKey(tagId: string): string {
  return `tag\u0000${tagId}`;
}

export interface StoreStatus {
  dataVersion: number;
  recipeCount: number;
  tagCount: number;
  /** 连带不上游戏，用的是磁盘上的旧快照 */
  offline: boolean;
  offlineReason: string | null;
  loadedAt: number;
}

export class RecipeStore {
  private readonly recipesById = new Map<string, Recipe>();
  private readonly byOutput = new Map<string, Recipe[]>();
  private readonly byInput = new Map<string, Recipe[]>();
  private readonly byType = new Map<string, Recipe[]>();
  private readonly tags = new Map<string, string[]>();
  /** item id -> 包含它的标签 id 列表。用于回答「这个物品属于哪些标签」。 */
  private readonly tagsOf = new Map<string, string[]>();
  private readonly itemNames = new Map<string, string>();

  private constructor(
    private readonly _dataVersion: number,
    private readonly _offline: boolean,
    private readonly _offlineReason: string | null,
    private readonly loadedAt: number,
  ) {}

  // ------------------------------------------------------------ 构建

  /**
   * 从 Bridge 拉数据建索引。连不上时退化到磁盘缓存。
   *
   * @throws 连不上且没有磁盘缓存时抛出 BridgeUnreachableError
   */
  static async load(client: BridgeClient): Promise<RecipeStore> {
    try {
      const health = await client.health();

      // 标签要全量拉：配方槽位里的 tag 必须展开成具体物品，
      // 否则「哪些配方消耗铁锭」会漏掉所有用 #forge:ingots/iron 的配方。
      const tags = await RecipeStore.fetchAllTags(client);

      const snapshot = await client.snapshot();

      const itemNames = await RecipeStore.fetchItemNames(client);

      const store = new RecipeStore(snapshot.dataVersion, false, null, Date.now());
      store.buildIndexes(snapshot.recipes, tags, itemNames);
      store.saveToDisk();

      if (snapshot.dataVersion !== health.dataVersion) {
        // 不是错误：拉取过程中配方重载了，快照本身是自洽的（Bridge 保证一致）。
        // 只是提醒一下，下次 health 检查会发现版本又变了。
      }

      return store;
    } catch (err) {
      const fromDisk = RecipeStore.loadFromDisk();
      if (fromDisk) return fromDisk;
      throw err;
    }
  }

  private static async fetchAllTags(client: BridgeClient): Promise<Map<string, string[]>> {
    const out = new Map<string, string[]>();
    for (const kind of ["items", "blocks", "fluids"] as const) {
      try {
        const page = await client.tagsAll(kind);
        for (const [tagId, members] of Object.entries(page.tags)) {
          out.set(tagId, members);
        }
      } catch {
        // 某个 kind 拉不到不影响其他，继续
      }
    }
    return out;
  }

  private static async fetchItemNames(client: BridgeClient): Promise<Map<string, string>> {
    const names = new Map<string, string>();
    try {
      let offset = 0;
      for (let i = 0; i < 50; i++) {
        const page = await client.registry("items", { offset, limit: 1000 });
        for (const e of page.entries) {
          // 即使没有显示名也要记下来，这样 itemNames 的键集合就是全部物品 id，
          // search_items 才能脱离游戏工作。
          names.set(e.id, e.displayName ?? e.id);
        }
        if (page.entries.length === 0 || offset + page.entries.length >= page.total) break;
        offset += page.entries.length;
      }
    } catch {
      // 拉不到不影响配方查询，只是 search_items 不可用
    }
    return names;
  }

  private buildIndexes(recipes: Recipe[], tags: Map<string, string[]>, itemNames: Map<string, string>): void {
    for (const [k, v] of tags) this.tags.set(k, v);

    // 反向标签索引：物品 -> 它所属的标签
    for (const [tagId, members] of this.tags) {
      for (const member of members) {
        const list = this.tagsOf.get(member);
        if (list) list.push(tagId);
        else this.tagsOf.set(member, [tagId]);
      }
    }

    for (const [k, v] of itemNames) this.itemNames.set(k, v);

    for (const recipe of recipes) {
      this.recipesById.set(recipe.id, recipe);

      const byTypeList = this.byType.get(recipe.type);
      if (byTypeList) byTypeList.push(recipe);
      else this.byType.set(recipe.type, [recipe]);

      // ---- 产出索引 ----
      // opaque 配方照样建索引：它确实存在，只是读不懂。查询时能查到，
      // 由上层决定怎么告诉用户。
      for (const out of recipe.outputs) {
        this.push(this.byOutput, refKey("item", out.item), recipe);
      }
      for (const out of recipe.fluidOutputs ?? []) {
        this.push(this.byOutput, refKey("fluid", out.fluid), recipe);
      }
      for (const out of recipe.chanceOutputs ?? []) {
        // 概率产出也算产出，但要在上层区分开（期望值 vs 必然值）
        this.push(this.byOutput, refKey("item", out.stack.item), recipe);
      }

      // ---- 输入索引 ----
      for (const ing of recipe.inputs) {
        const kind: StackKind = ing.kind === "fluid" ? "fluid" : "item";
        for (const opt of ing.options) {
          if (opt.type === "tag") {
            // 索引到标签本身
            this.push(this.byInput, tagKey(opt.id), recipe);
            // 也索引到每个成员 —— 否则「哪些配方消耗铁锭」会漏掉用
            // #forge:ingots/iron 的配方，这是最容易出错的地方。
            for (const member of this.tags.get(opt.id) ?? []) {
              this.push(this.byInput, refKey(kind, member), recipe);
            }
          } else {
            const optKind: StackKind = opt.type === "fluid" ? "fluid" : "item";
            this.push(this.byInput, refKey(optKind, opt.id), recipe);
          }
        }
      }
    }
  }

  /** 入桶并去重（同一条配方可能因为多个标签成员而重复入同一个桶）。 */
  private push(map: Map<string, Recipe[]>, key: string, recipe: Recipe): void {
    const list = map.get(key);
    if (!list) {
      map.set(key, [recipe]);
      return;
    }
    if (list[list.length - 1] !== recipe) list.push(recipe);
  }

  // ------------------------------------------------------------ 磁盘缓存

  private saveToDisk(): void {
    try {
      const dir = cacheDir();
      fs.mkdirSync(dir, { recursive: true });
      const payload: DiskCache = {
        savedAt: new Date().toISOString(),
        dataVersion: this._dataVersion,
        recipes: [...this.recipesById.values()],
        tags: Object.fromEntries(this.tags),
        itemNames: Object.fromEntries(this.itemNames),
      };
      fs.writeFileSync(path.join(dir, CACHE_FILE), JSON.stringify(payload), "utf8");
    } catch {
      // 写缓存失败不影响功能，只是下次启动要重新拉
    }
  }

  private static loadFromDisk(): RecipeStore | null {
    const file = path.join(cacheDir(), CACHE_FILE);
    let parsed: DiskCache;
    try {
      parsed = JSON.parse(fs.readFileSync(file, "utf8")) as DiskCache;
    } catch {
      return null;
    }

    const store = new RecipeStore(
      parsed.dataVersion,
      true,
      `游戏没在运行，正在使用 ${parsed.savedAt} 保存的离线快照。数据可能已经过时。`,
      Date.now(),
    );
    store.buildIndexes(parsed.recipes ?? [], new Map(Object.entries(parsed.tags ?? {})), new Map(Object.entries(parsed.itemNames ?? {})));
    return store;
  }

  // ------------------------------------------------------------ 查询

  get status(): StoreStatus {
    return {
      dataVersion: this._dataVersion,
      recipeCount: this.recipesById.size,
      tagCount: this.tags.size,
      offline: this._offline,
      offlineReason: this._offlineReason,
      loadedAt: this.loadedAt,
    };
  }

  getRecipe(id: string): Recipe | undefined {
    return this.recipesById.get(id);
  }

  allRecipes(): Recipe[] {
    return [...this.recipesById.values()];
  }

  recipesOfType(type: string): Recipe[] {
    return this.byType.get(type) ?? [];
  }

  recipeTypes(): { type: string; label: string; count: number }[] {
    const out: { type: string; label: string; count: number }[] = [];
    for (const [type, list] of this.byType) {
      const first = list[0];
      out.push({ type, label: first?.typeLabel ?? type, count: list.length });
    }
    out.sort((a, b) => b.count - a.count);
    return out;
  }

  /** 产出该物品的配方。包含概率产出。 */
  recipesProducing(kind: StackKind, id: string): Recipe[] {
    return this.byOutput.get(refKey(kind, id)) ?? [];
  }

  /**
   * 消耗该物品作为输入的配方。
   *
   * 已包含「配方用的是包含该物品的标签」这种情况 —— 倒排索引在建索引时
   * 就把标签展开过了。
   */
  recipesConsuming(kind: StackKind, id: string): Recipe[] {
    return this.byInput.get(refKey(kind, id)) ?? [];
  }

  /** 直接以该标签作为输入的配方。 */
  recipesConsumingTag(tagId: string): Recipe[] {
    return this.byInput.get(tagKey(tagId)) ?? [];
  }

  expandTag(tagId: string): string[] | undefined {
    return this.tags.get(tagId);
  }

  /** 该物品属于哪些标签。 */
  tagsContaining(id: string): string[] {
    return this.tagsOf.get(id) ?? [];
  }

  itemName(id: string): string {
    return this.itemNames.get(id) ?? id;
  }

  /**
   * 按名称或 id 搜索物品。
   *
   * 本地完成，不需要游戏在运行 —— 物品表在缓存建立时就一起存下来了。
   */
  searchItems(query: string, limit: number): { id: string; displayName: string }[] {
    const q = query.toLowerCase();
    const exact: { id: string; displayName: string }[] = [];
    const startsWith: { id: string; displayName: string }[] = [];
    const contains: { id: string; displayName: string }[] = [];

    for (const [id, displayName] of this.itemNames) {
      const lowerId = id.toLowerCase();
      if (lowerId === q) exact.push({ id, displayName });
      else if (lowerId.endsWith(`:${q}`) || lowerId.startsWith(q)) startsWith.push({ id, displayName });
      else if (lowerId.includes(q) || displayName.toLowerCase().includes(q)) contains.push({ id, displayName });
    }

    return [...exact, ...startsWith, ...contains].slice(0, limit);
  }

  /** 缓存里已知的全部物品 id。 */
  allItemIds(): string[] {
    return [...this.itemNames.keys()];
  }

  /** 把配方槽位里出现的标签整理出来，用于给用户提示「这里做了选择」。 */
  tagsUsedIn(recipe: Recipe): string[] {
    const out = new Set<string>();
    for (const ing of recipe.inputs) {
      for (const opt of ing.options) {
        if (opt.type === "tag") out.add(opt.id);
      }
    }
    return [...out];
  }
}

/** 把配方槽位摊平成便于展示的形式。 */
export function describeIngredient(ing: Ingredient): string {
  const parts = ing.options.map((o) => (o.type === "tag" ? `#${o.id}` : o.id));
  const unit = ing.kind === "fluid" ? "mB" : "";
  return `${ing.count}${unit} × (${parts.join(" | ")})`;
}
