/**
 * MCP 工具定义。
 *
 * 原则：
 *   1. 所有配方查询都在本地缓存上完成，不打网络 —— 一次快照拉全量，之后都是内存查询。
 *      唯一需要联网的是注册表代理查询和强制刷新。
 *   2. 任何失败都返回 isError 加一句人能看懂的话，绝不抛异常。
 *      AI 看到「游戏没在运行」会去告诉用户，看到堆栈只会胡说八道。
 *   3. 描述文字要写清楚什么时候该用这个工具，这是 AI 选择工具的唯一依据。
 */

import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import type { RecipeStore, StackKind } from "./cache.js";
import { describeStatus, StoreManager } from "./manager.js";
import { opaqueHint } from "./opaque.js";
import { calculatePlan, prunePlan } from "./plan.js";
import { renderPlan, renderPlanTsv, renderTree, renderTreeTsv } from "./report.js";
import { buildRecipeTree, pruneTree } from "./tree.js";
import { BridgeHttpError, BridgeUnreachableError, type Ingredient, type Recipe } from "./types.js";

interface ToolResult {
  // SDK 的 CallToolResult 带索引签名，这里必须跟着声明，否则不可赋值
  [x: string]: unknown;
  content: { type: "text"; text: string }[];
  isError?: boolean;
}

function ok(text: string): ToolResult {
  return { content: [{ type: "text", text }] };
}

/**
 * 紧凑 JSON。
 *
 * 不用 `JSON.stringify(x, null, 2)`：缩进版实测比紧凑版贵 39%，
 * 因为每个节点都重复一遍字段名外加缩进空格。JSON 本身就已经比 markdown
 * 贵 1.6 倍（字段名逐节点重复），缩进更是雪上加霜。
 *
 * 需要人读的时候用 markdown，需要机器处理的时候用 JSON —— 但两者都别缩进。
 */
function json(value: unknown): string {
  return JSON.stringify(value);
}

function fail(text: string): ToolResult {
  return { content: [{ type: "text", text }], isError: true };
}

/** 把异常翻译成 AI 能据以行动的说明。 */
function humanize(err: unknown): string {
  if (err instanceof BridgeUnreachableError) {
    return (
      `❌ 连不上游戏。\n\n${err.message}\n\n` +
      `请告诉用户：需要先启动 Minecraft 并确认 CraftGraph Mod 已加载，然后重试。`
    );
  }
  if (err instanceof BridgeHttpError) {
    return `❌ 游戏返回了错误 [${err.code}]：${err.message}`;
  }
  if (err instanceof Error) return `❌ ${err.message}`;
  return `❌ 未知错误：${String(err)}`;
}

async function guard(fn: () => Promise<ToolResult>): Promise<ToolResult> {
  try {
    return await fn();
  } catch (err) {
    return fail(humanize(err));
  }
}

// ---------------------------------------------------------------- 格式化

function itemLabel(store: RecipeStore, id: string): string {
  const name = store.itemName(id);
  return name === id ? `\`${id}\`` : `${name}（\`${id}\`）`;
}

function ingredientText(ing: Ingredient): string {
  const parts = ing.options.map((o) => (o.type === "tag" ? `#${o.id}` : o.id));
  const unit = ing.kind === "fluid" ? "mB " : "";
  return `${ing.count} ${unit}× [${parts.join(" | ")}]`;
}

function recipeDetail(store: RecipeStore, r: Recipe): string {
  const lines: string[] = [];
  lines.push(`### \`${r.id}\``);
  lines.push(`- 类型：\`${r.type}\`${r.typeLabel ? `（${r.typeLabel}）` : ""}`);
  if (r.machine) lines.push(`- 机器：${itemLabel(store, r.machine)}`);
  if (r.duration != null) lines.push(`- 耗时：${r.duration} 刻（${r.duration / 20} 秒）`);
  if (r.energy != null) lines.push(`- 耗能：${r.energy} FE`);
  lines.push(`- 数据来源：${r.source}${r.opaque ? "（⚠️ 输入输出读不懂）" : ""}`);

  if (r.opaque) {
    lines.push("");
    lines.push("> ⚠️ 这条配方的输入输出没能解析出来（通常是模组用了自定义配方格式）。");
    lines.push("> 下面的输入列表可能是空的，但那不代表它不需要原料。");
  }

  lines.push("");
  lines.push("输入：");
  if (r.inputs.length === 0) lines.push("- （无 / 未解析出）");
  else for (const ing of r.inputs) lines.push(`- ${ingredientText(ing)}`);

  lines.push("");
  lines.push("输出：");
  if (r.producesNothing) {
    // 「本来就不产出」和「产出读不到」必须分开说 —— 前者是我们读清楚了。
    // 混为一谈会让 AI 对玩家说「这条我读不懂」，而真相是「它不产出东西」。
    lines.push("- 这条配方**不产出物品**（燃料/配置类定义；输入是读到了的，不是读不懂）");
  } else if (r.outputs.length === 0) {
    lines.push("- （无）");
  } else {
    for (const o of r.outputs) lines.push(`- ${o.count} × ${itemLabel(store, o.item)}`);
  }

  for (const o of r.fluidOutputs ?? []) lines.push(`- ${o.amount} mB ${itemLabel(store, o.fluid)}`);
  for (const c of r.chanceOutputs ?? []) {
    lines.push(`- ${c.stack.count} × ${itemLabel(store, c.stack.item)}（概率 ${(c.chance * 100).toFixed(1)}%）`);
  }

  const tags = [...new Set(r.inputs.flatMap((i) => i.options.filter((o) => o.type === "tag").map((o) => o.id)))];
  if (tags.length > 0) {
    lines.push("");
    lines.push(`> ℹ️ 输入里的 \`#xxx\` 是标签，表示「这类物品任意一个都行」。涉及 ${tags.length} 个标签：${tags.join(", ")}`);
  }

  return lines.join("\n");
}

function recipeList(store: RecipeStore, recipes: Recipe[], heading: string, total: number): string {
  if (recipes.length === 0) return `## ${heading}\n\n_没有找到。_`;

  const shown = recipes.length;
  const lines = [`## ${heading}`, "", `共 ${total} 条${shown < total ? `，显示前 ${shown} 条` : ""}`, ""];
  for (const r of recipes) {
    const out = r.outputs[0];
    const outText = out
      ? `${out.count} × ${itemLabel(store, out.item)}`
      : r.producesNothing
        ? "（不产出物品）"
        : "（产出未知）";
    lines.push(`- \`${r.id}\` — ${r.typeLabel ?? r.type} → ${outText}${r.opaque ? " ⚠️读不懂" : ""}`);
  }
  // 截断时要把「还有多少、怎么拿」写出来，不能只写「显示前 N 条」。
  //
  // 大包里这条不是小事：实测一个 Create 包的「糖能做什么」有 294 条，默认只回 30 条（10%），
  // 而模型那次**没有**追加请求，直接拿这 10% 下了结论。
  // 光陈述事实不够 —— 得把下一步动作摆在它面前。
  if (shown < total) {
    lines.push(
      "",
      `_还有 ${total - shown} 条没显示。需要更多就调大 limit（上限 200），` +
        `或者用 type 或更具体的关键词把范围收窄 —— 不要把这 ${shown} 条当成全部。_`,
    );
  }
  return lines.join("\n");
}

const STACK_KIND = z.enum(["item", "fluid"]).default("item");

const EXPANSION_PARAMS = {
  maxDepth: z.number().int().min(1).max(32).optional().describe("最大展开深度，默认 8。调大能展开更深的链，但节点数会快速增长。"),
  recipeChoice: z
    .record(z.string(), z.string())
    .optional()
    .describe('指定某物品用哪条配方，形如 {"minecraft:iron_ingot": "minecraft:iron_ingot_from_blasting_iron_ore"}。用户对结果不满意时用这个覆盖。'),
  tagChoice: z
    .record(z.string(), z.string())
    .optional()
    .describe('指定某个标签选哪个成员，形如 {"forge:ingots/iron": "othermod:iron_ingot"}。'),
  rawMaterials: z.array(z.string()).optional().describe("视为基础原料、不再展开的物品 id 列表。比如把某种矿石当作外购原料。"),
};

// ---------------------------------------------------------------- 注册

export function registerTools(server: McpServer, manager: StoreManager): void {
  const store = () => manager.get();

  // ---------------------------------------------------------- 状态

  server.registerTool(
    "get_bridge_status",
    {
      title: "检查与游戏的连接状态",
      description:
        "检查 CraftGraph 是否连上了 Minecraft、缓存了多少条配方、数据版本是多少。" +
        "任何其他工具报错说连不上游戏时，先用这个确认状态。" +
        "用户问「为什么查不到配方」时也先调这个。",
      inputSchema: {},
    },
    async () =>
      guard(async () => {
        const s = await store();
        return ok(describeStatus(s, manager.location));
      }),
  );

  server.registerTool(
    "refresh_recipes",
    {
      title: "重新拉取配方数据",
      description:
        "强制重新从游戏拉取配方并重建缓存。正常情况下不需要调用 —— " +
        "配方重载会被 dataVersion 自动检测到。只在用户明确说「我刚改了配方」或怀疑数据不对时用。",
      inputSchema: {},
    },
    async () =>
      guard(async () => {
        const s = await manager.refresh();
        return ok(`已重新拉取。\n\n${describeStatus(s, manager.location)}`);
      }),
  );

  // ---------------------------------------------------------- 注册表

  server.registerTool(
    "search_items",
    {
      title: "搜索物品",
      description:
        "按名称或 id 搜索物品，返回匹配的 id 列表。用户说「铁锭」「iron」这类模糊说法时，用它确定准确的物品 id，" +
        "再拿去调配方查询工具。不需要游戏在运行。",
      inputSchema: {
        query: z.string().describe("搜索词，可以是 id 的一部分（如 iron_ingot）或显示名（如 铁锭）"),
        limit: z.number().int().min(1).max(200).default(20),
      },
    },
    async ({ query, limit }) =>
      guard(async () => {
        const s = await store();
        const hits = s.searchItems(query, limit);
        if (hits.length === 0) {
          return ok(`没有找到匹配「${query}」的物品。\n\n可以试试用英文 id 的一部分，比如 "iron" 而不是 "铁锭"。`);
        }
        const lines = [`## 匹配「${query}」的物品`, ""];
        for (const h of hits) lines.push(`- \`${h.id}\`${h.displayName !== h.id ? ` — ${h.displayName}` : ""}`);
        return ok(lines.join("\n"));
      }),
  );

  server.registerTool(
    "get_registry",
    {
      title: "查询注册表",
      description:
        "列出 blocks / items / fluids / entities / recipe_types 注册表的内容。需要游戏在运行 —— " +
        "如果只是想找物品 id，优先用 search_items（它读本地缓存，离线也能用）。",
      inputSchema: {
        kind: z.enum(["items", "blocks", "fluids", "entities", "recipe_types"]),
        query: z.string().optional().describe("可选的过滤词"),
        limit: z.number().int().min(1).max(1000).default(100),
      },
    },
    async ({ kind, query, limit }) =>
      guard(async () => {
        const page = await manager.queryRegistry(kind, { query, limit });
        const lines = [`## 注册表 ${kind}`, "", `共 ${page.total} 项${page.entries.length < page.total ? `，显示前 ${page.entries.length} 项` : ""}`, ""];
        for (const e of page.entries) lines.push(`- \`${e.id}\`${e.displayName && e.displayName !== e.id ? ` — ${e.displayName}` : ""}`);
        return ok(lines.join("\n"));
      }),
  );

  server.registerTool(
    "list_recipe_types",
    {
      title: "列出所有配方类型",
      description:
        "列出这个整合包里存在的全部配方类型及各自的配方数量。用户问「这包里有哪些机器/配方类型」时用，" +
        "也用来判断某个模组的配方有没有被读出来。",
      inputSchema: {},
    },
    async () =>
      guard(async () => {
        const s = await store();
        const types = s.recipeTypes();
        const lines = ["## 配方类型", "", `共 ${types.length} 种`, ""];
        lines.push("| 类型 id | 名称 | 配方数 |");
        lines.push("|---|---|---|");
        for (const t of types.slice(0, 100)) lines.push(`| \`${t.type}\` | ${t.label} | ${t.count} |`);
        if (types.length > 100) lines.push("", `_（只显示前 100 种，共 ${types.length} 种）_`);
        return ok(lines.join("\n"));
      }),
  );

  // ---------------------------------------------------------- 配方查询

  server.registerTool(
    "get_recipes_for_output",
    {
      title: "查某物品是怎么做出来的",
      description:
        "查询所有能产出指定物品的配方。用户问「X 怎么做」「X 怎么来的」时用这个。" +
        "会同时返回读不懂的配方（标记 opaque），因为「有配方但读不懂」和「没有配方」是两回事。",
      inputSchema: {
        item: z.string().describe('物品 id，形如 "minecraft:iron_ingot"'),
        kind: STACK_KIND,
        limit: z.number().int().min(1).max(200).default(30),
      },
    },
    async ({ item, kind, limit }) =>
      guard(async () => {
        const s = await store();
        const all = s.recipesProducing(kind as StackKind, item);
        if (all.length === 0) {
          const tags = s.tagsContaining(item);
          let extra = "";
          if (tags.length > 0) extra = `\n\n注意：该物品属于标签 ${tags.map((t) => `#${t}`).join(", ")}，某些配方可能通过标签引用它。`;
          return ok(`没有找到产出 \`${item}\` 的配方。它可能是基础资源（挖矿/采集获得），或者产它的模组配方读不出来。${extra}`);
        }
        return ok(recipeList(s, all.slice(0, limit), `产出 ${itemLabel(s, item)} 的配方`, all.length));
      }),
  );

  server.registerTool(
    "get_recipes_for_input",
    {
      title: "查某物品能用来做什么",
      description:
        "查询所有把指定物品当作输入的配方。用户问「X 有什么用」「X 能做什么」时用。" +
        "已正确处理标签：用 #forge:ingots/iron 这类标签作输入的配方也会被算进来。",
      inputSchema: {
        item: z.string().describe('物品 id，形如 "minecraft:iron_ingot"'),
        kind: STACK_KIND,
        limit: z.number().int().min(1).max(200).default(30),
      },
    },
    async ({ item, kind, limit }) =>
      guard(async () => {
        const s = await store();
        const all = s.recipesConsuming(kind as StackKind, item);
        if (all.length === 0) return ok(`没有找到把 \`${item}\` 当输入用的配方。`);
        return ok(recipeList(s, all.slice(0, limit), `用 ${itemLabel(s, item)} 作为输入的配方`, all.length));
      }),
  );

  server.registerTool(
    "get_recipe_details",
    {
      title: "查看配方的完整信息",
      description:
        "按 id 获取配方的完整输入输出、耗时、耗能。需要先通过其他查询拿到具体配方 id。" +
        "要看多条时用 recipeIds 一次传（最多 20 条）—— 逐条调用会让往返次数随配方数线性增长。",
      inputSchema: {
        recipeId: z.string().optional().describe('单条配方 id，形如 "minecraft:iron_ingot_from_smelting_iron_ore"'),
        recipeIds: z
          .array(z.string())
          .max(20)
          .optional()
          .describe("多条配方 id（最多 20）。给了它就忽略 recipeId"),
      },
    },
    async ({ recipeId, recipeIds }) =>
      guard(async () => {
        const s = await store();
        // 两种入参都接受：只为兼容模型已经习惯的单条调用方式 ——
        // 报错逼它换参数只会白费一轮往返，而往返是要花钱的。
        const ids = recipeIds && recipeIds.length > 0 ? recipeIds : recipeId ? [recipeId] : [];
        if (ids.length === 0) return fail("需要提供 recipeId 或 recipeIds。");

        const found = ids.filter((id) => s.getRecipe(id) !== undefined);
        const missing = ids.filter((id) => s.getRecipe(id) === undefined);

        if (found.length === 0) {
          // 一条都找不到时给「你是不是想找」的提示（保留原来单条调用的行为）
          const probe = ids[0]!;
          const similar = s.allRecipes().filter((x) => x.id.includes(probe)).slice(0, 10);
          const hint =
            similar.length > 0 ? `\n\n你是不是想找：\n${similar.map((x) => `- \`${x.id}\``).join("\n")}` : "";
          return fail(`没有 id 为 \`${probe}\` 的配方。${hint}`);
        }

        const body = found.map((id) => recipeDetail(s, s.getRecipe(id)!)).join("\n\n");
        const tail =
          missing.length > 0 ? `\n\n_（有 ${missing.length} 条找不到，已跳过：${missing.join("、")}）_` : "";
        return ok(body + tail);
      }),
  );

  server.registerTool(
    "find_alternative_recipes",
    {
      title: "查找替代配方",
      description:
        "找出同一物品的所有其他做法，并比较它们的原料和耗时，帮助用户选一条。用户问「还有别的做法吗」「哪条更省」时用。",
      inputSchema: {
        item: z.string().describe("物品 id"),
        kind: STACK_KIND,
      },
    },
    async ({ item, kind }) =>
      guard(async () => {
        const s = await store();
        const all = s.recipesProducing(kind as StackKind, item);
        const usable = all.filter((r) => !r.opaque);

        if (usable.length === 0) {
          if (all.length > 0) {
            return ok(
              `\`${item}\` 有 ${all.length} 条配方，但都读不懂输入输出（类型：${[...new Set(all.map((r) => r.type))].join(", ")}）。` +
                opaqueHint(all.map((r) => r.type)),
            );
          }
          return ok(`\`${item}\` 没有已知配方，是基础资源。`);
        }

        const lines = [`## ${itemLabel(s, item)} 的所有做法`, "", `共 ${usable.length} 条可用配方`, ""];
        for (const r of usable) {
          lines.push(recipeDetail(s, r));
          lines.push("");
        }

        if (usable.length > 1) {
          lines.push("### 怎么选");
          lines.push("");
          lines.push(
            "可以按耗时、原料易得程度、是否需要特定机器来权衡。要按某条配方展开配方树，" +
              "在 build_recipe_tree 里用 recipeChoice 指定，形如 " +
              `{"${item}": "${usable[0]!.id}"}。`,
          );
        }
        return ok(lines.join("\n"));
      }),
  );

  // ---------------------------------------------------------- 树与产线

  server.registerTool(
    "build_recipe_tree",
    {
      title: "构建配方树",
      description:
        "递归展开一个物品的完整制作链，列出每一层需要什么、需要做几次，并汇总基础原料。" +
        "用户问「X 怎么做出来的」「做 X 需要哪些原料」时首选这个工具。" +
        "结果已经处理了循环配方、标签选择、概率产出，并说明所有不确定的地方。" +
        "\n\n默认只展示前 3 层结构（更深的分支用「⋯」标出节点数和原料），" +
        "但基础原料是完整的 —— 未展开的分支也已计算在内。要看某个分支的细节，" +
        "把 detailDepth 调大，或者直接拿那个物品再调用一次本工具。",
      inputSchema: {
        item: z.string().describe("物品 id"),
        kind: STACK_KIND,
        count: z.number().positive().default(1).describe("要制作多少个"),
        format: z
          .enum(["markdown", "json", "tsv"])
          .default("markdown")
          .describe(
            "markdown 默认，可读性最好；tsv 是列式输出，实测在深链上比 markdown 省约 28% token，" +
              "适合结果很大的情况；json 是完整结构化数据但最贵（约 markdown 的 1.7 倍），只在需要程序化处理时用。",
          ),
        detailDepth: z
          .number()
          .int()
          .min(0)
          .max(64)
          .default(2)
          .describe(
            "结构展示到第几层（根节点为第 0 层，默认 2 即展示根、子、孙三层）。" +
              "调大能看更深的展开，token 随之增长。设为 0 则只给原料汇总。",
          ),
        ...EXPANSION_PARAMS,
      },
    },
    async ({ item, kind, count, format, detailDepth, maxDepth, recipeChoice, tagChoice, rawMaterials }) =>
      guard(async () => {
        const s = await store();
        const full = buildRecipeTree(s, kind as StackKind, item, count, {
          maxDepth,
          recipeChoice,
          tagChoice,
          rawMaterials,
        });

        // 裁剪只影响「展示多少结构」；原料汇总仍是全树算出来的，所以答案依然完整
        const pruned = { ...full, root: pruneTree(full.root, detailDepth) };
        const label = `${count} × ${itemLabel(s, item)}`;

        if (format === "json") return ok(json(pruned));
        if (format === "tsv") return ok(renderTreeTsv(s, pruned, label, detailDepth));
        return ok(renderTree(s, pruned, label, detailDepth));
      }),
  );

  server.registerTool(
    "calculate_production_plan",
    {
      title: "计算产线",
      description:
        "给定目标产量（每分钟多少个），算出需要哪些机器各多少台、每分钟消耗多少原料、产出多少副产、总耗能。" +
        "用户问「我要每分钟 X 个 Y，怎么建产线」时用。" +
        "注意这是贪心近似：每种物品固定选一条配方，副产只统计不回代。结果里会说明所有近似之处。" +
        "\n\n顶部那几张汇总表（机器/原料/副产/能耗）才是重点，逐环节明细默认只展示前 3 层。",
      inputSchema: {
        item: z.string().describe("目标物品 id"),
        kind: STACK_KIND,
        ratePerMinute: z.number().positive().describe("目标产量，每分钟多少个（流体是 mB）"),
        format: z
          .enum(["markdown", "json", "tsv"])
          .default("markdown")
          .describe("markdown 默认；tsv 是列式输出，省约 28% token；json 最贵但结构完整"),
        detailDepth: z
          .number()
          .int()
          .min(0)
          .max(64)
          .default(2)
          .describe("逐环节明细展示到第几层（根节点为第 0 层）。默认 2。设为 0 则只看汇总表。"),
        ...EXPANSION_PARAMS,
      },
    },
    async ({ item, kind, ratePerMinute, format, detailDepth, maxDepth, recipeChoice, tagChoice, rawMaterials }) =>
      guard(async () => {
        const s = await store();
        const full = calculatePlan(s, kind as StackKind, item, {
          ratePerMinute,
          maxDepth,
          recipeChoice,
          tagChoice,
          rawMaterials,
        });

        const plan = { ...full, root: prunePlan(full.root, detailDepth) };
        const label = itemLabel(s, item);

        if (format === "json") return ok(json(plan));
        if (format === "tsv") return ok(renderPlanTsv(s, plan, label, detailDepth));
        return ok(renderPlan(s, plan, label, detailDepth));
      }),
  );

  // ---------------------------------------------------------- 标签

  server.registerTool(
    "expand_tag",
    {
      title: "展开标签",
      description:
        "列出某个标签包含的所有物品。配方的输入里出现 #xxx 时，用这个看它到底可以是哪些东西。" +
        "想知道某物品属于哪些标签，用 search_items 或 get_recipes_for_output 的结果。",
      inputSchema: {
        tag: z.string().describe('标签 id，不带 # 前缀，形如 "forge:ingots/iron"'),
      },
    },
    async ({ tag }) =>
      guard(async () => {
        const s = await store();
        const members = s.expandTag(tag);
        if (!members) return fail(`没有找到标签 \`#${tag}\`。注意标签 id 不带 # 前缀。`);

        const lines = [`## 标签 #${tag}`, "", `共 ${members.length} 个成员`, ""];
        for (const m of members) lines.push(`- ${itemLabel(s, m)}`);

        const users = s.recipesConsumingTag(tag);
        if (users.length > 0) {
          lines.push("");
          lines.push(`被 ${users.length} 条配方直接引用，例如：`);
          for (const r of users.slice(0, 10)) lines.push(`- \`${r.id}\``);
        }
        return ok(lines.join("\n"));
      }),
  );
}
