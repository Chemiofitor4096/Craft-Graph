/**
 * MCP 协议层端到端测试。
 *
 *   npm run e2e
 *
 * 冒烟测试只验证算法，这个验证的是真正的 MCP 链路：
 * 用 stdio 起一个真实的客户端，完成握手、列工具、调工具。
 * 类型检查通过不代表协议能跑通 —— 比如工具 schema 写错、往 stdout 打了日志，
 * 都要到这里才会暴露。
 */

import path from "node:path";
import { fileURLToPath } from "node:url";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PORT = 25598;

const { startMockBridge } = await import("./mock-bridge.js");

const server = await startMockBridge(PORT);

const transport = new StdioClientTransport({
  command: process.execPath,
  args: ["--import", "tsx", path.join(HERE, "index.ts")],
  cwd: path.resolve(HERE, ".."),
  env: {
    ...process.env,
    CRAFTGRAPH_BRIDGE_URL: `http://127.0.0.1:${PORT}`,
    CRAFTGRAPH_CACHE_DIR: path.join(HERE, "..", ".cache", "e2e"),
  },
  stderr: "pipe",
});

const client = new Client({ name: "craftgraph-e2e", version: "1.0.0" });

let failures = 0;
function check(name: string, ok: boolean, detail = ""): void {
  process.stdout.write(`${ok ? "  ✅" : "  ❌"} ${name}\n`);
  if (!ok) {
    failures++;
    if (detail) process.stdout.write(`       ${detail.replace(/\n/g, "\n       ")}\n`);
  }
}

try {
  await client.connect(transport);
  process.stdout.write("\n已连接到 MCP Server\n\n");

  // ---- 工具列表 ----
  const { tools } = await client.listTools();
  const names = tools.map((t) => t.name);
  process.stdout.write(`注册了 ${names.length} 个工具：${names.join(", ")}\n\n`);

  const expected = [
    "get_bridge_status",
    "refresh_recipes",
    "search_items",
    "get_registry",
    "list_recipe_types",
    "get_recipes_for_output",
    "get_recipes_for_input",
    "get_recipe_details",
    "find_alternative_recipes",
    "build_recipe_tree",
    "calculate_production_plan",
    "expand_tag",
  ];
  for (const name of expected) {
    check(`工具存在：${name}`, names.includes(name));
  }

  check("每个工具都有描述（AI 靠它选工具）", tools.every((t) => (t.description ?? "").length > 10));

  // ---- 实际调用 ----
  const status = await client.callTool({ name: "get_bridge_status", arguments: {} });
  check("get_bridge_status 可用", !status.isError, JSON.stringify(status.content).slice(0, 200));

  const search = await client.callTool({ name: "search_items", arguments: { query: "iron" } });
  check("search_items 可用", !search.isError);

  // ---- 配方树（打印完整输出，人工确认可读性）----
  const tree = await client.callTool({
    name: "build_recipe_tree",
    arguments: { item: "minecraft:iron_block", count: 1 },
  });
  check("build_recipe_tree 可用", !tree.isError);

  const treeText = (tree.content as { type: string; text: string }[])[0]?.text ?? "";
  process.stdout.write("\n" + "─".repeat(70) + "\n配方树输出：\n" + "─".repeat(70) + "\n");
  process.stdout.write(treeText + "\n");

  // ---- 产线规划 ----
  const plan = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: "minecraft:iron_ingot", ratePerMinute: 10 },
  });
  check("calculate_production_plan 可用", !plan.isError);

  const planText = (plan.content as { type: string; text: string }[])[0]?.text ?? "";
  process.stdout.write("─".repeat(70) + "\n产线规划输出：\n" + "─".repeat(70) + "\n");
  process.stdout.write(planText + "\n");

  // ---- 错误处理：不存在的物品 ----
  const missing = await client.callTool({
    name: "get_recipe_details",
    arguments: { recipeId: "does:not_exist" },
  });
  check(
    "查不到配方时返回可读错误而不是崩溃",
    missing.isError === true && JSON.stringify(missing.content).includes("没有 id 为"),
    JSON.stringify(missing.content).slice(0, 300),
  );

  // ---- 批量详情：省往返 ----
  //
  // 在大包里实测过这个痛点：模型先 get_recipes_for_output 拿列表，再对每条逐次调
  // get_recipe_details —— 12 条配方就是 13 次往返。批量入参是为了把它压成一次。
  // 这两个 id 取自 mock bridge 的固定配方（见 mock-bridge.ts）。
  const ids = ["examplepack:iron_ingot_from_crushed_iron", "create:crushing/iron_ore"];
  const batch = await client.callTool({ name: "get_recipe_details", arguments: { recipeIds: ids } });
  const batchText = (batch.content as { type: string; text: string }[])[0]?.text ?? "";
  check(
    `recipeIds 一次拿到 ${ids.length} 条详情`,
    !batch.isError && ids.every((id) => batchText.includes(id)),
    `请求 ${ids.join(", ")}\n${batchText.slice(0, 300)}`,
  );
  // 混一个不存在的 id：其余几条必须照常返回，并说明跳过了哪条
  const mixed = await client.callTool({
    name: "get_recipe_details",
    arguments: { recipeIds: [...ids.slice(0, 2), "does:not_exist"] },
  });
  const mixedText = (mixed.content as { type: string; text: string }[])[0]?.text ?? "";
  check(
    "批量详情里部分找不到时不整批失败，而是跳过并说明",
    !mixed.isError && mixedText.includes(ids[0]!) && mixedText.includes("找不到"),
    mixedText.slice(0, 300),
  );

  // ---- 单条入参仍然可用（兼容模型已习惯的调用方式）----
  const single = await client.callTool({ name: "get_recipe_details", arguments: { recipeId: ids[0]! } });
  check(
    "recipeId 单条入参仍然可用",
    !single.isError && JSON.stringify(single.content).includes(ids[0]!),
    JSON.stringify(single.content).slice(0, 200),
  );

  // ---- 「不产出」不能被说成「读不懂」----
  //
  // 两者都让 outputs 为空，但一个是「读清楚了：它不产出」，另一个是「没读懂」。
  // 混为一谈会让 AI 对玩家说错话，所以这条既查详情页的措辞，也查它没有被标成读不懂。
  const fuel = await client.callTool({
    name: "get_recipe_details",
    arguments: { recipeId: "examplepack:liquid_burning/biofuel" },
  });
  const fuelText = (fuel.content as { type: string; text: string }[])[0]?.text ?? "";
  // 判据是「没有 opaque 警告标记」，不是「不含『读不懂』三个字」——
  // 后者会被文案里的澄清句（「输入是读到了的，不是读不懂」）误伤。
  check(
    "★ 「本来就不产出」在详情页说成「不产出物品」，且没有 opaque 警告",
    !fuel.isError && fuelText.includes("不产出物品") && !fuelText.includes("⚠️"),
    fuelText.slice(0, 400),
  );
  check(
    "该配方的输入仍然正常渲染（读到了才敢说不产出）",
    /输入：[\s\S]*- 1000 mB/.test(fuelText),
    fuelText.slice(0, 400),
  );
  const fuelList = await client.callTool({
    name: "get_recipes_for_input",
    arguments: { item: "examplepack:biofuel", kind: "fluid" },
  });
  const fuelListText = (fuelList.content as { type: string; text: string }[])[0]?.text ?? "";
  check(
    "列表页对它显示「（不产出物品）」而不是「（产出未知）」",
    fuelListText.includes("不产出物品") && !fuelListText.includes("产出未知"),
    fuelListText.slice(0, 300),
  );

  // ---- 参数校验 ----
  const badArgs = await client.callTool({
    name: "calculate_production_plan",
    arguments: { item: "minecraft:iron_ingot", ratePerMinute: -5 },
  });
  check("非法参数被 schema 拦下", badArgs.isError === true, JSON.stringify(badArgs.content).slice(0, 200));

  // 两个 id 参数都不给时要说清楚要什么，而不是抛异常
  const noId = await client.callTool({ name: "get_recipe_details", arguments: {} });
  check(
    "既不给 recipeId 也不给 recipeIds 时返回可读提示",
    noId.isError === true && JSON.stringify(noId.content).includes("recipeId"),
    JSON.stringify(noId.content).slice(0, 200),
  );
} catch (err) {
  check("端到端流程未抛异常", false, err instanceof Error ? `${err.message}\n${err.stack}` : String(err));
} finally {
  await client.close().catch(() => {});
  await new Promise<void>((resolve) => server.close(() => resolve()));
}

process.stdout.write(`\n${failures === 0 ? "全部通过" : `${failures} 项失败`}\n\n`);
process.exit(failures === 0 ? 0 : 1);
