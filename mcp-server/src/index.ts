#!/usr/bin/env node
/**
 * CraftGraph MCP Server 入口。
 *
 * ⚠️ 关键约束：这是个 stdio 传输的 MCP 服务器，**stdout 是协议通道**。
 * 往 stdout 打任何日志都会破坏协议、让客户端解析失败。
 * 所有日志一律走 stderr。
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { BridgeClient } from "./bridge.js";
import { resolveBridgeLocation } from "./config.js";
import { StoreManager } from "./manager.js";
import { registerTools } from "./tools.js";

/**
 * Server 版本。与 Mod 的 `mod_version`（mod/gradle.properties）保持一致 ——
 * 两者是同一个产品的两半，版本号分开走只会让人对着两个数字猜「哪个是新的」。
 * npm 发布是独立的事，但版本号同步没有问题。
 */
const VERSION = "0.3.0";

/**
 * 这段会作为 server instructions 发给 AI 客户端。
 * 它是 AI 理解「这个服务器该怎么用」的第一手材料，值得写清楚。
 */
const INSTRUCTIONS = `CraftGraph 让 AI 查询正在运行的 Minecraft 的配方数据，并规划产线。

数据来自游戏内的 CraftGraph Mod，通过本地 HTTP 提供。**游戏必须处于运行状态**
才能拿到最新数据；游戏关着时只能读磁盘上的上次快照，且结果里会明确标注数据可能过时。

推荐的使用顺序：
1. 用户用模糊说法（"铁锭"、"钢"）时，先调 search_items 拿到准确的物品 id。
2. 问"X 怎么做"→ build_recipe_tree；问"X 有什么用"→ get_recipes_for_input；
   问"每分钟 X 个 Y 怎么建产线"→ calculate_production_plan。
3. 任何工具报"连不上游戏"时，调 get_bridge_status 确认状态，然后把情况告诉用户，
   不要自己猜测配方内容。

几个必须如实转达给用户的点：
- 配方输入里的 #xxx 是**标签**，表示"这类物品任意一个都行"。工具已经自动挑了一个具体物品，
  并在结果里注明"从 #xxx 选定"。如果用户在意具体用哪种，让他指定。
- 标记 opaque 的配方表示**输入输出读不懂**（模组用了自定义格式）。
  这不等于"不需要原料"，一定要如实说读不到，不要编造。
- 概率产出的产量是期望值，不是保证值。
- 结果里标注"不完整"或"被截断"时，说明原料表偏低，必须转达，不要让用户以为那是全部。
- 循环依赖会被自动截断并说明，不要假装没这回事。`;

async function main(): Promise<void> {
  const location = resolveBridgeLocation();
  const client = new BridgeClient(location);
  const manager = new StoreManager(client);

  const server = new McpServer({ name: "craftgraph", version: VERSION }, { instructions: INSTRUCTIONS });
  registerTools(server, manager);

  const transport = new StdioServerTransport();
  await server.connect(transport);

  process.stderr.write(
    `[craftgraph] MCP Server v${VERSION} 已启动（stdio）\n` +
      `[craftgraph] Bridge 地址：${client.describeLocation}\n`,
  );

  // 预热：后台开始拉配方，让用户第一次提问时不用等完整快照。
  // 失败不报错 —— 游戏可能压根没开，工具调用时会给用户明确提示。
  void manager
    .get()
    .then((store) => {
      const s = store.status;
      process.stderr.write(
        s.offline
          ? `[craftgraph] 游戏未运行，已加载离线快照：${s.recipeCount} 条配方\n`
          : `[craftgraph] 已从游戏加载 ${s.recipeCount} 条配方、${s.tagCount} 个标签（dataVersion=${s.dataVersion}）\n`,
      );
    })
    .catch((err: unknown) => {
      process.stderr.write(`[craftgraph] 预热失败（不影响启动）：${err instanceof Error ? err.message : String(err)}\n`);
    });
}

main().catch((err: unknown) => {
  process.stderr.write(`[craftgraph] 启动失败：${err instanceof Error ? err.stack : String(err)}\n`);
  process.exit(1);
});
