# CraftGraph MCP

让 AI 查询 Minecraft 的配方数据，构建配方树，规划产线。

```
AI 客户端 (Claude Code / Cursor)
      │ MCP (stdio)
      ▼
MCP Server (TypeScript)          ← ✅ 已完成并测试
      │ HTTP + WebSocket (仅 localhost)
      ▼
Bridge Mod (Java / NeoForge)     ← ⬜ 待开发（下一阶段）
      │ RecipeManager / EMI API
      ▼
Minecraft 1.21.1 + 你的整合包
```

## 当前状态

| 部分 | 状态 | 说明 |
|---|---|---|
| 接口契约 | ✅ | [`doc/protocol.md`](doc/protocol.md) —— 两侧唯一的约定 |
| 技术决策 | ✅ | [`doc/decisions.md`](doc/decisions.md) |
| MCP Server | ✅ | 12 个工具；36 项算法测试 + 协议端到端测试全通过 |
| 假 Bridge | ✅ | 让 MCP Server 完全脱离游戏开发和测试 |
| Bridge Mod | 🟡 | 代码与测试完成（74 个 JUnit 用例），**尚未进游戏验证** |
| 跨语言契约 | ✅ | Java 真实输出 → TS 消费，17 项断言（`npm run contract`） |
| 进游戏验证 | ⬜ | 需要你：`./gradlew runClient` + `curl` |

**关键点：MCP Server 现在就能跑。** 用假的 Bridge 喂测试数据，你可以立刻接进
AI 客户端看效果，不需要先写 Mod。

## 立刻试一下

```bash
cd mcp-server
npm install

# 终端 1：启动假 Bridge（提供测试用的配方数据）
npm run mock

# 终端 2：启动 MCP Server，指向假 Bridge
CRAFTGRAPH_BRIDGE_URL=http://127.0.0.1:25585 npm run dev
```

## 验证方式

```bash
# MCP Server（TypeScript）
cd mcp-server
npm run typecheck    # 类型检查
npm run smoke        # 算法层 41 项：索引、标签挑选、合并槽位、循环检测、离线降级
npm run e2e          # 协议层：真实 stdio 握手 + 工具调用
npm run contract     # 跨语言契约 17 项：吃 Java 侧的真实输出
npm run live         # ★ 对运行中的真实游戏做体检（需 Minecraft 已启动并进入世界）
npm run measure      # token 测量（1 万条配方合成数据）
npm run compare      # 格式对比：markdown / 列式 TSV / 字典编码
npm run analyze      # 输出成本分解 + 索引查询延迟

# Bridge Mod（Java）—— 76 个用例，都不需要启动 Minecraft
cd ../mod
./gradlew test
./gradlew build
./gradlew runClient   # 启动带 Mod 的游戏
```

### 三层验证，各自负责不同的东西

| 层 | 数据来源 | 能发现什么 |
|---|---|---|
| `smoke` / JUnit | 手写夹具 | 算法逻辑、边界情况 |
| `contract` | Java 导出的真实响应体 | 两侧字段与 `null` 处理是否一致 |
| **`live`** | **运行中的真游戏** | **真实配方形状、真实标签规模、真机 token 消耗** |

`live` 是唯一跑在真实数据上的。它刻意**不配置任何环境变量**，
靠 `~/.craftgraph/bridge.json` 自己发现游戏 —— 顺带验证了服务发现机制。

**它已经抓到过三个假数据发现不了的问题**：原版有序合成导致的重复子树（`3 × 铁锭` 拆成 3 个槽位）、
标签成员挑选反直觉（煤炭挑成木炭、橡木板挑成金合欢木板）、以及 `mcVersion` 报成了 NeoForge 版本。
详见下面「真实数据暴露的问题」。

## 还差什么

**进游戏验证。** 代码齐了、测试齐了，但没人真的在 Minecraft 里跑过。
这是唯一剩下的unknown，具体做法见 [`mod/README.md`](mod/README.md)。

要重点看的：**很多模组配方的 `getResultItem` 返回 `EMPTY`**，那不代表它不产出东西。
这类情况会体现在日志里 `opaque` 的比例上 —— 这是刻意设计的，不会静默出错。


## 真实数据暴露的问题

第一次在真实 Minecraft 里跑通之后（1290 条配方、opaque 2%、主线程抽取 27ms），
`live` 体检抓到三个假数据发现不了的问题。记在这里，因为它们都是**只有真实数据才会触发**的类型：

**一、原版有序合成导致重复子树。** 原版把「3 个铁锭」表示成 **3 个各含 1 个铁锭的槽位**，
于是铁镐的配方树里出现三棵一模一样的「铁锭」分支。这不只是多花两三倍 token ——
**它还算错了数量**：每个槽位各自向上取整，1 个火把需要的基础原料被算成 3 个原木，
而正确答案是 1 个（1 原木 → 4 木板 → 4 木棍 → 4 火把）。

修法是在**递归前按物品合并槽位**，不是事后去重 —— 合并后取整一次，数量才准。

**二、标签成员挑选反直觉。** 原来按字典序挑，实测挑出 `charcoal`（煤炭标签里
"charcoal" < "coal"）、`acacia_planks`（橡木标签里 acacia 字母序最前）。
改成**优先短 id**：模组给基础物品加前缀后缀来造变体（橡木→金合欢木、煤炭→木炭），
所以基础物品的 id 通常最短。三个案例因此全部改对。

**三、`mcVersion` 报成了 NeoForge 版本。** `Minecraft.getInstance().getLaunchedVersion()`
返回的是启动参数里的 `--version`，而开发环境（`runClient`）下那个值是 NeoForge 的版本号。
改用 `SharedConstants.getCurrentVersion().getName()`。

**共同点：三个都不是逻辑错误，而是「我对真实数据的假设错了」。** 假数据是按我的理解造的，
所以测不出我的理解有偏差 —— 这就是为什么必须有 `live` 这一层。

## token 开销

MCP 工具的返回值会进模型上下文，所以**输出大小就是成本**。有两套测量：
`measure` 用 1 万条配方的合成数据（压规模），`live` 用真实游戏（看实际）。

真实数据（近原版 1290 条配方）上的一次体检：

| 项目 | tokens |
|---|---:|
| `search_items`（中文搜索） | 25 |
| `list_recipe_types` | 131 |
| `build_recipe_tree`（火把 / 铁镐 / 金苹果） | 433 / 444 / 289 |
| `calculate_production_plan`（每分钟 60 火把） | 522 |
| `expand_tag`（`#minecraft:planks`） | 283 |
| `get_recipes_for_input`（铁锭能做什么） | 833 |
| 工具定义（固定开销，每次会话） | 2,598 |

配方树在真实数据上是 **300~450 tokens** 量级 —— 比合成深链（1,855~15,585）小一个数量级。
说明分层返回在真实场景下效果显著：多数真实查询本来就浅，加上只展示三层，
默认情况下根本碰不到成本上限。

合成数据下的优化前后对比（318 节点的深链）：

| 项目 | 优化前 | 现在 | 变化 |
|---|---:|---:|---:|
| 配方树（完全展开） | 15,585 | 1,855 | **-88%** |
| 产线规划 | 20,304 | 6,832 | **-66%** |
| 典型会话 | 16,861 | 3,131 | **-81%** |
| 最坏会话 | 37,350 | 10,148 | **-73%** |

**格式选型的实测结论见 [`doc/format-evaluation.md`](doc/format-evaluation.md)。**
核心发现：字典编码的收益在每次调用都要重发字典时会完全蒸发；真正值钱的是「少返回」。


## 接进 AI 客户端

以 Claude Code 为例，在项目或用户配置里加：

```jsonc
{
  "mcpServers": {
    "craftgraph": {
      "command": "node",
      "args": ["C:/Projects/craftgraph/mcp-server/dist/index.js"]
    }
  }
}
```

先 `npm run build` 生成 `dist/`。调试阶段也可以直接用 `npx tsx src/index.ts`。

**不需要配置端口和 token** —— 游戏里的 Mod 会把它们写到
`%APPDATA%\.minecraft\craftgraph\bridge.json`，MCP Server 自己去找。
游戏装在非默认目录时，用 `CRAFTGRAPH_BRIDGE_FILE` 或 `CRAFTGRAPH_BRIDGE_URL` 指定。

### 环境变量

| 变量 | 作用 |
|---|---|
| `CRAFTGRAPH_BRIDGE_URL` | 直接指定 Bridge 地址，跳过发现文件 |
| `CRAFTGRAPH_BRIDGE_FILE` | 指定发现文件路径 |
| `CRAFTGRAPH_BRIDGE_TOKEN` | 手动指定 token |
| `CRAFTGRAPH_CACHE_DIR` | 缓存目录，默认 `~/.craftgraph/cache` |
| `CRAFTGRAPH_TIMEOUT_MS` | 请求超时，默认 8000 |

## 提供的 MCP 工具

| 工具 | 作用 |
|---|---|
| `get_bridge_status` | 检查与游戏的连接状态。**其他工具报连不上时先调这个** |
| `search_items` | 按名称/id 搜物品，把"铁锭"变成 `minecraft:iron_ingot` |
| `get_registry` | 查 blocks/items/fluids/entities 注册表 |
| `list_recipe_types` | 列出整合包里所有配方类型 |
| `get_recipes_for_output` | 某物品怎么做出来的 |
| `get_recipes_for_input` | 某物品能用来做什么（已正确展开标签） |
| `get_recipe_details` | 单条配方完整信息 |
| `find_alternative_recipes` | 同一物品的所有做法对比 |
| `build_recipe_tree` | 递归展开完整制作链 |
| `calculate_production_plan` | 按目标速率算机器数、原料、副产、能耗 |
| `expand_tag` | 展开标签看它包含哪些物品 |
| `refresh_recipes` | 强制重新拉取配方 |

> 原计划里的 `export_plan` 合并成了各工具的 `format: "json"` 参数；
> `show_in_jei` / `show_in_emi` 按决策 3 移出 MVP。

## 目录结构

```
craftgraph/
├── doc/
│   ├── protocol.md      # 接口契约（改代码前先看这个）
│   ├── decisions.md     # 已锁定的技术决策
│   └── raw-plan.md      # 最初的粗略计划
├── mcp-server/          # ✅ TypeScript MCP Server
│   └── src/
│       ├── index.ts         # 入口（stdio）
│       ├── types.ts         # 协议类型，与 protocol.md 对应
│       ├── config.ts        # 服务发现
│       ├── bridge.ts        # HTTP 客户端
│       ├── cache.ts         # 缓存与索引
│       ├── resolution.ts    # 选配方 / 解析标签（树和产线共用）
│       ├── tree.ts          # 配方树递归
│       ├── plan.ts          # 产线计算
│       ├── report.ts        # Markdown 渲染
│       ├── tools.ts         # MCP 工具定义
│       ├── manager.ts       # 缓存生命周期
│       ├── mock-bridge.ts   # 假 Bridge
│       ├── smoke.ts         # 算法测试
│       └── e2e.ts           # 协议端到端测试
├── mod/                 # 🟡 NeoForge Mod（骨架已完成并构建通过）
│   ├── README.md            # 实现顺序、架构决策、坑位说明 —— 写 Mod 前先看这个
│   └── src/main/java/dev/craftgraph/
│       ├── CraftGraph.java          # Mod 入口
│       ├── api/Models.java          # 协议数据结构（与 protocol.md 对应）
│       └── bridge/
│           ├── MainThreadDispatcher.java  # 线程派发（关键）
│           └── DiscoveryFile.java         # 服务发现文件
└── shared/
    └── fixtures/        # 测试数据（脱离游戏开发的关键）
```

## 算法上的几个刻意取舍

这些地方看起来「不够完善」，但都是有意为之，改之前请先看懂原因：

**产线计算是贪心近似，不是线性规划。** 每种物品固定选一条配方，副产只统计不回代。
通用解要处理「一个配方同时产 A 和 B」的联立关系，本质是线性规划，工期从一周变一个月。
现在的做法结果可解释、可复现，对绝大多数实际问题是够用的。

**标签成员的挑选规则是启发式的。** `#forge:ingots/iron` 会挑 `minecraft:iron_ingot`，
但 `#forge:ores/iron` 会挑到 `minecraft:deepslate_iron_ore`（按命名空间优先 + 字典序，
`d` 在 `i` 前面）。一定会挑错某些标签 —— 所以结果里总是显示「从 #xxx 选定」，
并且可以用 `tagChoice` 覆盖。

**循环检测做了两级前瞻。** 只看直接输入是不够的：「1 铁块 → 9 铁锭」的输入是铁块，
不在路径上，但铁块的配方要 9 个铁锭，展开一步就绕回来。而这条配方因为
「输入槽位少、产出多」评分反而最高，不拦的话「每分钟 10 个铁锭」会算出循环路线。

**`opaque` 配方必须和「没有配方」区分开。** 读不懂的配方如果返回空输入列表，
看起来就像「这配方不需要原料」，AI 会据此编出错误答案。标记出来它才会说实话。

**离线时显式声明数据可能过时。** 玩家不会一直开着游戏。退回磁盘快照时，
每份报告都会标注数据是旧的，否则 AI 会拿旧数据给出看起来很确定的答案。

## 下一阶段：Bridge Mod

构建配置已完成（用官方 MDK 的配置，不是凭记忆写的），**完整的实现顺序、架构决策和
坑位说明在 [`mod/README.md`](mod/README.md)** —— 那篇文档比这里详细，写 Mod 前先看它。

已核实的版本信息（2026-09-19 查自官方 Maven / Modrinth）：

| 依赖 | 版本 | 备注 |
|---|---|---|
| Minecraft | 1.21.1 | 21.1.x 版本线仍在持续发版（最新 21.1.251），说明生态活跃 |
| NeoForge | **21.1.251** | 注意已有 21.11 / 26.x 等更新版本线 |
| ModDevGradle | 2.0.147 | 构建插件，与官方 MDK 一致 |
| JEI | 19.56.0.441 | 可选 |
| EMI | 1.1.24+1.21.1 | 可选。Modrinth 标注 `client_only`，**印证了 Mod 必须跑在客户端** |

核心顺序（详见 mod/README.md）：

1. HTTP 服务 + `/health`，用 `curl` 验证 —— 通了 MCP Server 就能连上
2. 服务发现文件 + token 校验
3. 配方归一化（重头戏，读不懂的必须标 `opaque`）
4. 倒排索引 + `/snapshot` + `/tags/{kind}/all`
5. WebSocket 事件
6. EMI 软依赖增强

**架构上唯一需要提前想清楚的：用一次性快照，不要每次请求现查。**
每次请求都走主线程会让 AI 每问一句游戏卡一下。详细理由见 mod/README.md。

## 安全

- Bridge 只监听 `127.0.0.1`，只读，不提供任何修改游戏状态的接口
- 需要 Bearer token，首次启动随机生成
- 不发 CORS 头，防止网页脚本读取游戏数据
