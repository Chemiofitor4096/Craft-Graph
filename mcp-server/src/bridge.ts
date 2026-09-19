/**
 * 与游戏内 Bridge 的 HTTP 通信。
 *
 * 这一层只负责「把数据拿回来」，不做任何缓存和计算。
 */

import { REQUEST_TIMEOUT_MS, SNAPSHOT_MAX_PAGES, SNAPSHOT_PAGE_SIZE, type BridgeLocation } from "./config.js";
import {
  BridgeHttpError,
  BridgeUnreachableError,
  type Health,
  type Recipe,
  type RecipeQueryPage,
  type RegistryPage,
  type SnapshotPage,
  type TagAllPage,
  type TagExpansion,
  type TagListPage,
} from "./types.js";

export interface SnapshotResult {
  dataVersion: number;
  recipes: Recipe[];
  /** 分页过程中数据版本变过，调用方应该重试。 */
  pageCount: number;
}

export class BridgeClient {
  constructor(private readonly location: BridgeLocation) {}

  get baseUrl(): string {
    return `http://${this.location.host}:${this.location.port}`;
  }

  get describeLocation(): string {
    const where = this.location.filePath ? `（发现文件：${this.location.filePath}）` : "";
    return `${this.baseUrl}${where}`;
  }

  /** 游戏没在运行时给一句人能看懂的话，而不是抛一个 ECONNREFUSED。 */
  private unreachable(err: unknown): BridgeUnreachableError {
    return new BridgeUnreachableError(
      `连不上 Minecraft 里的 CraftGraph Bridge：${this.baseUrl}\n` +
        `可能原因：游戏没在运行 / 游戏还没加载完 / Mod 没装 / 端口不对。\n` +
        `（地址来源：${this.location.source}）`,
      err,
    );
  }

  private async request<T>(pathname: string, params?: Record<string, string | number | undefined>): Promise<T> {
    const url = new URL(pathname, this.baseUrl);
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined) url.searchParams.set(k, String(v));
      }
    }

    const headers: Record<string, string> = { Accept: "application/json" };
    if (this.location.token) headers.Authorization = `Bearer ${this.location.token}`;

    let res: Response;
    try {
      res = await fetch(url, {
        headers,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (err) {
      throw this.unreachable(err);
    }

    if (!res.ok) {
      // 尽量把 Bridge 的结构化错误还原出来；还原不出来就用状态码兜底。
      let code = `HTTP_${res.status}`;
      let message = res.statusText || `HTTP ${res.status}`;
      try {
        const body = (await res.json()) as { error?: { code?: string; message?: string } };
        if (body?.error?.code) code = body.error.code;
        if (body?.error?.message) message = body.error.message;
      } catch {
        /* 响应体不是 JSON，用兜底信息 */
      }
      throw new BridgeHttpError(res.status, code, message);
    }

    return (await res.json()) as T;
  }

  async health(): Promise<Health> {
    return this.request<Health>("/health");
  }

  async registry(kind: string, opts: { query?: string; offset?: number; limit?: number } = {}): Promise<RegistryPage> {
    return this.request<RegistryPage>(`/registry/${encodeURIComponent(kind)}`, {
      query: opts.query,
      offset: opts.offset ?? 0,
      limit: opts.limit ?? 100,
    });
  }

  async tags(kind: string, opts: { query?: string; limit?: number } = {}): Promise<TagListPage> {
    return this.request<TagListPage>(`/tags/${encodeURIComponent(kind)}`, {
      query: opts.query,
      limit: opts.limit ?? 200,
    });
  }

  async expandTag(kind: string, tagId: string): Promise<TagExpansion> {
    return this.request<TagExpansion>(`/tags/${encodeURIComponent(kind)}/${encodeURIComponent(tagId)}`);
  }

  /**
   * 一次性拉取某个 kind 下的全部标签及成员。
   *
   * 为什么要批量：建倒排索引必须把标签展开（否则「哪些配方消耗铁锭」会漏掉
   * 所有用 #forge:ingots/iron 的配方），而逐个标签拉在大整合包里是上千次请求。
   */
  async tagsAll(kind: string): Promise<TagAllPage> {
    return this.request<TagAllPage>(`/tags/${encodeURIComponent(kind)}/all`);
  }

  async queryRecipes(opts: {
    output?: string;
    input?: string;
    type?: string;
    offset?: number;
    limit?: number;
  }): Promise<RecipeQueryPage> {
    return this.request<RecipeQueryPage>("/recipes", {
      output: opts.output,
      input: opts.input,
      type: opts.type,
      offset: opts.offset ?? 0,
      limit: opts.limit ?? 50,
    });
  }

  async recipe(recipeId: string): Promise<Recipe> {
    return this.request<Recipe>(`/recipes/${encodeURIComponent(recipeId)}`);
  }

  /**
   * 拉全量配方快照，用于建本地缓存。
   *
   * 关键点：每页都带 dataVersion，必须校验一致。中途变了说明游戏重载了配方，
   * 之前拉的页全部作废。不做这个校验会得到一个「一半旧一半新」的缓存，
   * 症状诡异到几乎无法排查。
   */
  async snapshot(): Promise<SnapshotResult> {
    const recipes: Recipe[] = [];
    let cursor = 0;
    let expectedVersion: number | null = null;
    let pageCount = 0;

    for (let i = 0; i < SNAPSHOT_MAX_PAGES; i++) {
      const page = await this.request<SnapshotPage>("/snapshot", {
        cursor,
        limit: SNAPSHOT_PAGE_SIZE,
      });
      pageCount++;

      if (expectedVersion === null) {
        expectedVersion = page.dataVersion;
      } else if (page.dataVersion !== expectedVersion) {
        throw new Error(
          `拉取快照期间游戏重载了配方（dataVersion ${expectedVersion} → ${page.dataVersion}），本次快照作废，请重试。`,
        );
      }

      recipes.push(...page.recipes);

      if (page.nextCursor === null) {
        return { dataVersion: page.dataVersion, recipes, pageCount };
      }
      cursor = page.nextCursor;
    }

    throw new Error(`快照页数超过上限 ${SNAPSHOT_MAX_PAGES}，Bridge 行为异常，已中止。`);
  }
}
