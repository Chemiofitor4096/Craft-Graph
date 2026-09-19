<div align="center">

# CraftGraph MCP

**让 AI 读懂你的 Minecraft 整合包配方，并把它变成产线规划。**

[English](README.md) | 简体中文

</div>

CraftGraph 把**运行中**的 Minecraft 实例通过 MCP 接到 AI 客户端（Claude Code、Cursor 等）上。
问它「火把怎么做」「铁锭能做什么」「每分钟 10 个钢锭的产线怎么建」——
AI 查的是你**当前实际加载的整合包**的实时配方数据。

下面是近原版 1.21.1 实例上的真实输出，问一个火把怎么做：

```text
# 配方树：1 × 火把（minecraft:torch）

**1 × 火把（minecraft:torch）**
  ↳ minecraft:torch（minecraft:crafting）执行 1 次，每次产出 4
  **1 × 煤炭（minecraft:coal）** 【#minecraft:coals】
    ↳ minecraft:coal_from_blasting_coal_ore（minecraft:blasting）执行 1 次，每次产出 1
    ● **1 × 煤矿石（minecraft:coal_ore）**
  **1 × 木棍（minecraft:stick）** 【#c:rods/wooden】
    ↳ minecraft:stick（minecraft:crafting）执行 1 次，每次产出 4
    **2 × 橡木木板（minecraft:oak_planks）** 【#minecraft:planks】
      ↳ minecraft:oak_planks（minecraft:crafting）执行 1 次，每次产出 4
      ⋯ 该分支未展开，共 2 节点 / 1 条配方；原料 minecraft:oak_log ×1

## 基础原料汇总

| 物品 | 数量 |
|---|---|
| 煤矿石（minecraft:coal_ore） | 1 |
| 橡木原木（minecraft:oak_log） | 1 |
```

注意它做对的几件事：`#minecraft:coals` 的意思是「煤炭类任意一种」，它挑了煤炭而不是木炭；
它把木棍配方里两个分开的木板槽位合并成了 `2 × 橡木木板`；
而基础原料的答案是 **1 个橡木原木**，不是 3 个 —— 因为一个原木能出四块木板。

## 为什么不直接用 wiki

整合包里的配方不是静态的。KubeJS 脚本在加载时改写配方，数据包覆盖它们，
每个机器模组注册自己的配方类型和自己的语义。wiki 或者爬虫给你的是原版，
而 CraftGraph 读的是你实例**此刻**加载的东西，包括只在运行时存在的配方。

## 它能做什么

| | |
|---|---|
| **查询** | `search_items`、`get_registry`、`list_recipe_types`、`expand_tag` |
| **配方检索** | `get_recipes_for_output`、`get_recipes_for_input`、`get_recipe_details`、`find_alternative_recipes` |
| **规划** | `build_recipe_tree`（递归配方链）、`calculate_production_plan`（机器数、原料速率、副产、能耗） |
| **诊断** | `get_bridge_status`、`refresh_recipes` |

以下几件事是刻意处理的，因为它们是「天真实现会给出错误答案」的地方：

- **循环配方** —— 铁块 = 9 铁锭，铁锭 = 1/9 铁块。用**两级前瞻**检测，
  因为一条配方的直接输入看起来没问题，展开一步之后却可能绕回来。
- **标签输入** —— `#forge:ingots/iron` 的意思是「这类物品任意一个都行」。
  工具会挑一个具体的，标明它来自哪个标签，并允许你覆盖这个选择。
- **读不懂的配方** —— 标记为 `opaque`，而不是返回空的输入列表。
  「没有配方」和「有配方但我读不懂」是两个不同的答案，AI 会知道它拿到的是哪个。
- **概率产出** —— 按期望值给出，并明确标注它是期望值而不是保证值。

## 环境要求

| | |
|---|---|
| Minecraft | 1.21.1 |
| 模组加载器 | NeoForge 21.1.x |
| 安装侧 | **客户端** —— Mod 从客户端读取，所以单人和多人存档都能用 |
| Node.js | ≥ 20（MCP Server 需要） |

## 安装

> **当前状态**：Mod 和 Server 都还没有发布到 Modrinth / CurseForge / npm。
> 暂时从源码构建，大约两分钟。

```bash
git clone https://github.com/Chemiofitor4096/craftgraph.git
cd craftgraph

# 1. 构建 Mod，把 jar 放进 mods 目录
cd mod && ./gradlew build
# → mod/build/libs/craftgraph-*.jar  →  放进 .minecraft/mods/

# 2. 构建 MCP Server
cd ../mcp-server && npm install && npm run build
```

然后把 AI 客户端指向 `mcp-server/dist/index.js`。以 Claude Code 为例：

```jsonc
{
  "mcpServers": {
    "craftgraph": {
      "command": "node",
      "args": ["/craftgraph 的绝对路径/mcp-server/dist/index.js"]
    }
  }
}
```

**不需要配置端口或 token。** Mod 启动时会写 `~/.craftgraph/bridge.json`，
MCP Server 自己去那里找。原版启动器和第三方启动器的位置都会写一份，所以自定义游戏目录也能用。

启动 Minecraft、进入存档，然后问 AI 客户端一个问题。如果它说连不上游戏，
`get_bridge_status` 会准确告诉你哪里不对。

## 它是怎么工作的

```text
AI 客户端（Claude Code / Cursor）
      │  MCP (stdio)
      ▼
MCP Server（TypeScript）          配方树、产线计算、缓存
      │  HTTP + WebSocket，仅 127.0.0.1，Bearer token
      ▼
桥接 Mod（Java / NeoForge）        读配方、建索引、提供服务
      │  RecipeManager
      ▼
Minecraft 1.21.1 + 你的整合包
```

有两个设计决定值得了解：

**Mod 只是个笨数据源。** 所有图算法和产线逻辑都在 TypeScript 侧，因为 Mod 跑在一个脆弱的
环境里：放在那里的算法没法脱离游戏测试，而且游戏一崩会连带它们一起挂。

**Mod 提供的是不可变快照，不是实时查询。** 遍历整合包的配方管理器只能在主线程做。
每次 HTTP 请求都做一遍，意味着 AI 每问一句游戏就卡一下。所以快照在配方加载时构建一次
（重载时重建），请求路径上完全不接触游戏对象 —— 这同时消掉了一整类线程 bug。

## 文档

| 文档 | 内容 |
|---|---|
| [`doc/protocol.md`](doc/protocol.md) | Bridge HTTP 契约 —— 两侧唯一的约定 |
| [`doc/decisions.md`](doc/decisions.md) | 已锁定的技术决策，以及改动它们要付什么代价 |
| [`doc/development.md`](doc/development.md) | 开发环境、四层测试、token 测量、踩过的坑 |
| [`doc/format-evaluation.md`](doc/format-evaluation.md) | 输出格式的实测评估，含对紧凑 DSL 方案的评估 |

## 当前状态

在真实的 1.21.1 实例上端到端验证过：索引 1290 条配方、2% 标记为读不懂、
主线程抽取 27ms、AI 客户端零配置连接成功。

| | |
|---|---|
| 桥接 Mod | 可用。83 个 JUnit 用例，都不需要启动 Minecraft |
| MCP Server | 可用。41 项算法测试、MCP 协议测试、跨语言契约测试 |
| 配方覆盖 | 所有配方类型都会被读到。原版存在的 7 种类型里有 5 种 100% 可读 |
| 模组配方 | **尚未测量** —— 见下方限制 |
| EMI 集成 | 未开始（会提升重型科技包的覆盖率） |
| 游戏内 UI | 按设计不在 MVP 范围内 |

## 已知限制

**读不懂的配方是真实存在的，而且被计数。** 原版 1290 条里有 29 条标记为 `opaque`。
其中 18 条是盔甲纹饰锻造，Minecraft 本来就不以声明式暴露它的材料 —— 这是平台限制，
不是解析失败。其余是代码驱动的特殊合成（染色、地图复制、烟花）。
想看你自己整合包按类型的分解，运行 `npm run inspect`。

**还没在大型科技包上测过。** 模组机器配方才是有意思的场景，也最可能落进 `opaque`。
如果你在大包上试，那个比例是最该看的东西。

**测量数据来自近原版环境。** 上面的性能和 token 数字都来自近原版实例。
5 万条配方的包表现会不一样 —— 尤其是快照构建的开销，预期会随配方数增长。

**只支持 NeoForge 1.21.1。** 没有 Fabric，没有其他 Minecraft 版本。

## 许可

MIT —— 见 [LICENSE](LICENSE)。
