/**
 * 假的 Bridge —— 把 shared/fixtures 里的测试数据按真实 API 形状提供出来。
 *
 * 存在的意义：整条 MCP Server 链路可以脱离 Minecraft 跑起来。
 * 调算法、改报告格式、接 AI 客户端，都不需要启动游戏。
 *
 * 作为命令行跑：
 *   npm run mock                                   启动在 25585
 *   MOCK_PORT=3000 npm run mock                    换端口
 *   CRAFTGRAPH_BRIDGE_URL=http://127.0.0.1:25585 npm run dev
 *
 * 也可以被 import（smoke.ts 就是这么用的），进程内启动、不占端口冲突。
 *
 * 它刻意不做鉴权，只监听回环地址。
 */

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE_PATH = path.resolve(HERE, "..", "..", "shared", "fixtures", "tiny-pack.json");
const DEFAULT_PORT = Number(process.env.MOCK_PORT ?? 25585);

interface FixtureRecipe {
  id: string;
  type: string;
  typeLabel?: string | null;
  inputs: { kind: string; count: number; options: { type: string; id: string }[] }[];
  outputs: { item: string; count: number }[];
  fluidOutputs?: { fluid: string; amount: number }[];
  chanceOutputs?: { stack: { item: string; count: number }; chance: number }[];
  machine?: string | null;
  duration?: number | null;
  energy?: number | null;
  opaque: boolean;
  /** 本来就不产出物品（燃料/配置类）。见 doc/protocol.md。 */
  producesNothing?: boolean;
}

export interface Fixture {
  health: Record<string, unknown>;
  tags: Record<string, Record<string, string[]>>;
  registry: Record<string, string[]>;
  recipes: FixtureRecipe[];
}

export function loadFixture(fixturePath = FIXTURE_PATH): Fixture {
  return JSON.parse(fs.readFileSync(fixturePath, "utf8")) as Fixture;
}

// ---------------------------------------------------------------- 合成数据集

/** 确定性随机数，保证每次生成的规模测试数据完全一致，测量结果可复现。 */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const NAMESPACES = [
  "minecraft",
  "create",
  "thermal",
  "mekanism",
  "immersiveengineering",
  "gregtech",
  "botania",
  "ars_nouveau",
  "occultism",
  "powah",
];

const SUFFIXES = [
  "ingot",
  "plate",
  "gear",
  "rod",
  "nugget",
  "dust",
  "block",
  "wire",
  "circuit",
  "alloy",
];

const TYPES = [
  "minecraft:crafting",
  "minecraft:smelting",
  "minecraft:blasting",
  "create:crushing",
  "create:mixing",
  "thermal:smelter",
  "mekanism:enriching",
  "immersiveengineering:arc_furnace",
];

/**
 * 生成一份规模接近真实大型整合包的假数据。
 *
 * 用途：
 *   - 测量 token 消耗（夹具只有 11 条，测不出真实规模）
 *   - 测量索引构建和查询性能
 *
 * 结构上刻意还原真实整合包的两个关键特征，否则测量会严重偏乐观：
 *
 *   1. **深加工链**：物品分成若干层，第 t 层的配方消耗第 t+1 层的物品。
 *      大整合包里「铁矿石 → 粉碎矿 → 洗净矿 → 粉 → 锭 → 块」这种链有十几层，
 *      只测三五层的话配方树的开销会被低估好几倍。
 *
 *   2. **热门物品**：少数物品（如铁锭、电路板）有几十条候选配方。
 *      列表接口的开销完全由这个决定，一个只有 2-3 条候选的样品测不出问题。
 */
export function generateSyntheticPack(recipeCount: number, seed = 42): Fixture {
  const rnd = mulberry32(seed);
  const pick = <T>(arr: T[]): T => arr[Math.floor(rnd() * arr.length)]!;
  const int = (lo: number, hi: number): number => lo + Math.floor(rnd() * (hi - lo + 1));

  const itemCount = Math.max(200, Math.floor(recipeCount * 0.8));

  const itemIds: string[] = [];
  for (let i = 0; i < itemCount; i++) {
    itemIds.push(`${pick(NAMESPACES)}:${pick(SUFFIXES)}_${i}`);
  }

  // 分层：最后一层是基础原料（没有配方产出它们），往上每层由下一层加工而来。
  const TIERS = 12;
  const tierSize = Math.floor(itemCount / TIERS);
  const tierOf = (idx: number): number => Math.min(TIERS - 1, Math.floor(idx / tierSize));
  /** 基础原料层 —— 没有配方产出它们 */
  const isRawTier = (idx: number): boolean => tierOf(idx) >= TIERS - 1;
  /** 可以产出物品的层 */
  const producibleIndices: number[] = [];
  for (let i = 0; i < itemCount; i++) if (!isRawTier(i)) producibleIndices.push(i);

  // 热门物品：每层挑两个，它们会分到大量配方
  const hotIndices = new Set<number>();
  for (let t = 0; t < TIERS - 1; t++) {
    hotIndices.add(t * tierSize + int(0, Math.max(0, tierSize - 1)));
    hotIndices.add(t * tierSize + int(0, Math.max(0, tierSize - 1)));
  }

  // 标签：模拟 forge:ingots/iron 这类分组标签，成员来自同一层（合理）
  const tags: Record<string, string[]> = {};
  const tagCount = Math.max(20, Math.floor(itemCount / 60));
  for (let k = 0; k < tagCount; k++) {
    const members = new Set<string>();
    const n = int(3, 12);
    for (let j = 0; j < n; j++) members.add(itemIds[int(0, itemCount - 1)]!);
    tags[`forge:${pick(SUFFIXES)}/${pick(NAMESPACES)}_group_${k}`] = [...members];
  }
  const tagIds = Object.keys(tags);

  const recipes: FixtureRecipe[] = [];
  for (let i = 0; i < recipeCount; i++) {
    // 20% 的配方分给热门物品，其余随机
    const outIdx =
      rnd() < 0.2 ? pick([...hotIndices]) : pick(producibleIndices);

    // 输入来自更靠后的层（更接近原料），保证链能终止、不会成环
    const myTier = tierOf(outIdx);
    const srcTierStart = Math.min(itemCount - 1, (myTier + 1) * tierSize);
    const srcTierEnd = Math.min(itemCount - 1, (myTier + 2) * tierSize - 1);

    const inputs: FixtureRecipe["inputs"] = [];
    const inputCount = int(1, 4);
    for (let k = 0; k < inputCount; k++) {
      // 1/3 概率用标签作输入，模拟整合包里标签的普遍程度
      if (rnd() < 0.33 && tagIds.length > 0) {
        inputs.push({ kind: "item", count: int(1, 4), options: [{ type: "tag", id: pick(tagIds) }] });
      } else {
        const srcIdx = int(srcTierStart, srcTierEnd);
        inputs.push({ kind: "item", count: int(1, 9), options: [{ type: "item", id: itemIds[srcIdx]! }] });
      }
    }

    recipes.push({
      id: `${pick(NAMESPACES)}:${pick(["crafting", "processing", "smelting", "mixing", "crushing"])}/${itemIds[outIdx]!.split(":")[1]}_${i}`,
      type: pick(TYPES),
      typeLabel: null,
      inputs,
      outputs: [{ item: itemIds[outIdx]!, count: int(1, 4) }],
      fluidOutputs: [],
      chanceOutputs:
        rnd() < 0.2 ? [{ stack: { item: itemIds[int(0, itemCount - 1)]!, count: 1 }, chance: 0.25 }] : [],
      machine: `${pick(NAMESPACES)}:${pick(["furnace", "machine", "crusher", "mixer", "smelter"])}`,
      duration: rnd() < 0.85 ? int(20, 400) : null,
      energy: rnd() < 0.3 ? int(100, 8000) : null,
      // 5% 读不懂，模拟自定义配方格式的模组
      opaque: rnd() < 0.05,
    });
  }

  return {
    health: {
      ok: true,
      protocolVersion: 1,
      modVersion: `0.1.0-synthetic-${recipeCount}`,
      mcVersion: "1.21.1",
      loader: "neoforge-21.1.251",
      emi: null,
      jei: null,
      recipeCount: recipes.length,
      itemCount,
      dataVersion: 1,
      uptimeMs: 1000,
    },
    tags: { items: tags, blocks: {}, fluids: {} },
    registry: { items: itemIds },
    recipes,
  };
}

function send(res: http.ServerResponse, status: number, body: unknown): void {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(json),
  });
  res.end(json);
}

function fail(res: http.ServerResponse, status: number, code: string, message: string): void {
  send(res, status, { error: { code, message } });
}

/**
 * 建索引。刻意和真实 Mod 侧要做的事保持一致：按产出建索引，
 * 按输入建**倒排**索引，并把标签展开到每个成员。
 */
function buildIndexes(fixture: Fixture) {
  const byOutput = new Map<string, FixtureRecipe[]>();
  const byInput = new Map<string, FixtureRecipe[]>();
  const byType = new Map<string, FixtureRecipe[]>();

  const push = (map: Map<string, FixtureRecipe[]>, key: string, r: FixtureRecipe): void => {
    const list = map.get(key);
    if (!list) map.set(key, [r]);
    else if (!list.includes(r)) list.push(r);
  };

  for (const r of fixture.recipes) {
    push(byType, r.type, r);
    for (const o of r.outputs) push(byOutput, `item\u0000${o.item}`, r);
    for (const o of r.fluidOutputs ?? []) push(byOutput, `fluid\u0000${o.fluid}`, r);
    for (const o of r.chanceOutputs ?? []) push(byOutput, `item\u0000${o.stack.item}`, r);

    for (const ing of r.inputs) {
      const kind = ing.kind === "fluid" ? "fluid" : "item";
      for (const opt of ing.options) {
        if (opt.type === "tag") {
          push(byInput, `tag\u0000${opt.id}`, r);
          for (const member of fixture.tags.items?.[opt.id] ?? []) {
            push(byInput, `${kind}\u0000${member}`, r);
          }
        } else {
          push(byInput, `${opt.type === "fluid" ? "fluid" : "item"}\u0000${opt.id}`, r);
        }
      }
    }
  }

  return { byOutput, byInput, byType };
}

export function createMockBridgeHandler(fixture: Fixture): http.RequestListener {
  const { byOutput, byInput, byType } = buildIndexes(fixture);

  const summary = (r: FixtureRecipe) => ({
    id: r.id,
    type: r.type,
    typeLabel: r.typeLabel ?? null,
    primaryOutput: r.outputs[0] ?? null,
    opaque: r.opaque,
  });

  return (req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    const segments = url.pathname.split("/").filter(Boolean);
    const q = url.searchParams;

    if (segments.length === 1 && segments[0] === "health") return send(res, 200, fixture.health);

    // /tags/{kind}/all 必须在 /tags/{kind}/{tagId} 之前判断，否则 all 会被当成标签名
    if (segments.length === 3 && segments[0] === "tags" && segments[2] === "all") {
      const kind = segments[1]!;
      return send(res, 200, { kind, tags: fixture.tags[kind] ?? {} });
    }

    if (segments.length === 1 && segments[0] === "snapshot") {
      const cursor = Number(q.get("cursor") ?? 0);
      const limit = Number(q.get("limit") ?? 500);
      const page = fixture.recipes.slice(cursor, cursor + limit);
      const next = cursor + limit < fixture.recipes.length ? cursor + limit : null;
      return send(res, 200, {
        dataVersion: fixture.health.dataVersion,
        cursor,
        nextCursor: next,
        total: fixture.recipes.length,
        recipes: page,
      });
    }

    if (segments.length === 2 && segments[0] === "registry") {
      const kind = segments[1]!;
      const all = fixture.registry[kind];
      if (!all) return fail(res, 404, "NOT_FOUND", `没有这个注册表：${kind}`);
      const query = (q.get("query") ?? "").toLowerCase();
      const filtered = query ? all.filter((id) => id.toLowerCase().includes(query)) : all;
      const offset = Number(q.get("offset") ?? 0);
      const limit = Number(q.get("limit") ?? 100);
      return send(res, 200, {
        kind,
        total: filtered.length,
        offset,
        limit,
        entries: filtered.slice(offset, offset + limit).map((id) => ({ id, displayName: null })),
      });
    }

    if (segments.length === 2 && segments[0] === "tags") {
      const kind = segments[1]!;
      const tags = fixture.tags[kind] ?? {};
      return send(res, 200, {
        kind,
        total: Object.keys(tags).length,
        entries: Object.entries(tags).map(([id, members]) => ({ id, count: members.length })),
      });
    }

    if (segments.length === 3 && segments[0] === "tags") {
      const kind = segments[1]!;
      const tagId = decodeURIComponent(segments[2]!);
      const members = fixture.tags[kind]?.[tagId];
      if (!members) return fail(res, 404, "NOT_FOUND", `标签不存在：${tagId}`);
      return send(res, 200, { id: tagId, entries: members });
    }

    if (segments.length === 2 && segments[0] === "recipes") {
      const id = decodeURIComponent(segments[1]!);
      const r = fixture.recipes.find((x) => x.id === id);
      if (!r) return fail(res, 404, "NOT_FOUND", `配方不存在：${id}`);
      return send(res, 200, r);
    }

    if (segments.length === 1 && segments[0] === "recipes") {
      const output = q.get("output");
      const input = q.get("input");
      const type = q.get("type");

      let list: FixtureRecipe[] = fixture.recipes;
      if (output) list = byOutput.get(`item\u0000${output}`) ?? [];
      else if (input) list = byInput.get(`item\u0000${input}`) ?? [];
      else if (type) list = byType.get(type) ?? [];

      if (output && input) list = list.filter((r) => (byInput.get(`item\u0000${input}`) ?? []).includes(r));
      if (type && (output || input)) list = list.filter((r) => r.type === type);

      const offset = Number(q.get("offset") ?? 0);
      const limit = Number(q.get("limit") ?? 50);
      return send(res, 200, {
        total: list.length,
        offset,
        limit,
        recipes: list.slice(offset, offset + limit).map(summary),
      });
    }

    return fail(res, 404, "NOT_FOUND", `没有这个端点：${url.pathname}`);
  };
}

/** 启动假 Bridge。返回 http.Server，调用方负责 close()。 */
export function startMockBridge(port = DEFAULT_PORT, fixture = loadFixture()): Promise<http.Server> {
  const server = http.createServer(createMockBridgeHandler(fixture));
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

// ---------------------------------------------------------------- 契约回放模式

/** 读取 Java 侧导出的响应体。目录由 mod 的 ContractDumpTest 生成。 */
export function loadBridgeDump(dir: string): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const name of fs.readdirSync(dir)) {
    if (!name.endsWith(".json")) continue;
    out[name.replace(/\.json$/, "")] = JSON.parse(fs.readFileSync(path.join(dir, name), "utf8"));
  }
  return out;
}

/** Java 侧导出的契约样本目录。 */
export function defaultDumpDir(): string {
  return path.resolve(HERE, "..", "..", "shared", "fixtures", "bridge-dump");
}

/**
 * 用 Java 侧导出的**真实响应体**回放出一个 Bridge。
 *
 * <p>这是跨语言契约验证的关键：TypeScript 侧整套索引和处理链路跑在
 * Java 实际会发出的字节上，而不是我手写的假数据上。字段名差一个字母、
 * null 处理不一致这类问题会在这里暴露，而不是等用户进游戏同时调两个系统时。
 *
 * <p>只实现 MCP Server 建缓存时会调用的那几个端点（health / tags all / snapshot / registry）。
 * 任意查询类端点（/recipes?output=...）没法从静态样本回放，也不需要 ——
 * MCP Server 建立缓存后所有查询都在本地索引上完成。
 */
export function createDumpHandler(dump: Record<string, unknown>): http.RequestListener {
  return (req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    const segments = url.pathname.split("/").filter(Boolean);

    if (segments.length === 1 && segments[0] === "health" && dump.health) {
      return send(res, 200, dump.health);
    }
    if (segments.length === 1 && segments[0] === "snapshot" && dump.snapshot) {
      return send(res, 200, dump.snapshot);
    }
    if (segments.length === 3 && segments[0] === "tags" && segments[2] === "all") {
      const key = `tags-${segments[1]}-all`;
      // 没有该 kind 的样本时返回空集而不是 404 —— 与真实 Bridge 一致
      return send(res, 200, dump[key] ?? { kind: segments[1], tags: {} });
    }
    if (segments.length === 2 && segments[0] === "registry") {
      const hasQuery = url.searchParams.has("query") && url.searchParams.get("query") !== "";
      const key = hasQuery ? `registry-${segments[1]}` : `registry-${segments[1]}-all`;
      if (dump[key]) return send(res, 200, dump[key]);
      return fail(res, 404, "NOT_FOUND", `契约样本里没有 ${key}.json`);
    }

    return fail(res, 404, "NOT_FOUND", `契约回放模式不支持这个端点：${url.pathname}`);
  };
}

export function startDumpBridge(dir = defaultDumpDir(), port = DEFAULT_PORT): Promise<http.Server> {
  const server = http.createServer(createDumpHandler(loadBridgeDump(dir)));
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}

// ---------------------------------------------------------------- CLI

const invokedDirectly =
  process.argv[1] !== undefined && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  startMockBridge()
    .then((server) => {
      const count = loadFixture().recipes.length;
      process.stderr.write(
        `[mock-bridge] 假的 Minecraft Bridge 已启动：http://127.0.0.1:${DEFAULT_PORT}\n` +
          `[mock-bridge] 数据源：${FIXTURE_PATH}（${count} 条配方）\n` +
          `[mock-bridge] 用法：CRAFTGRAPH_BRIDGE_URL=http://127.0.0.1:${DEFAULT_PORT} npm run dev\n`,
      );
      const shutdown = () => server.close(() => process.exit(0));
      process.on("SIGINT", shutdown);
      process.on("SIGTERM", shutdown);
    })
    .catch((err: unknown) => {
      process.stderr.write(`[mock-bridge] 启动失败：${String(err)}\n`);
      process.exit(1);
    });
}
