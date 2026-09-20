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
| Minecraft | **1.21.1**（NeoForge 21.1.x）或 **1.20.1**（MinecraftForge 47.2.0+） |
| 安装侧 | **客户端** —— Mod 从客户端读取，所以单人和多人存档都能用 |
| Node.js | ≥ 20（MCP Server 需要） |

两个 MC 版本各有一个 jar，按你的游戏版本选一个（装错版本游戏会直接报加载失败，不会静默不工作）。

## 安装

两半，**两个都要装** —— 只放 jar 是问不出任何东西的，配方树和产线计算全在 Server 侧。

**1. Mod** —— 从 [Releases 页面](https://github.com/Chemiofitor4096/Craft-Graph/releases)
下载对应你游戏版本的那个放进 mods 目录：

| 你的游戏 | 下载 |
|---|---|
| Minecraft 1.21.1 + NeoForge | `craftgraph-<版本>.jar` |
| Minecraft 1.20.1 + Forge 47.2.0+ | `craftgraph-<版本>-mc1.20.1.jar` |

没有对应你游戏版本的 Release？自己构建：

```bash
git clone https://github.com/Chemiofitor4096/Craft-Graph.git
cd Craft-Graph/mod && ./gradlew build          # 1.21.1
cd Craft-Graph/mod-1.20.1 && ./gradlew build   # 1.20.1（需要 JDK 17）
# → mod/build/libs/craftgraph-*.jar  /  mod-1.20.1/build/libs/craftgraph-*-mc1.20.1.jar
```

**2. MCP Server** —— 还没发布到 npm，从源码构建：

```bash
cd Craft-Graph/mcp-server && npm install && npm run build
```

然后把 AI 客户端指向 `mcp-server/dist/index.js`。以 Claude Code 为例：

```jsonc
{
  "mcpServers": {
    "craftgraph": {
      "command": "node",
      "args": ["/Craft-Graph 的绝对路径/mcp-server/dist/index.js"]
    }
  }
}
```

**不需要配置端口或 token。** Mod 启动时会写 `~/.craftgraph/bridge.json`，
MCP Server 自己去那里找。原版启动器和第三方启动器的位置都会写一份，所以自定义游戏目录也能用。

启动 Minecraft、进入存档，然后问 AI 客户端一个问题。如果它说连不上游戏，
`get_bridge_status` 会准确告诉你哪里不对。

## 作为依赖使用

Mod 也发布到了 **KessokuMaven**，可以不从源码构建，直接依赖它。主要用途是：你想自己写一个
对接 bridge 协议的客户端时，复用它的 DTO（`dev.craftgraph.api.Models`），不用手工再抄一遍。

```groovy
repositories {
    maven { url = 'https://maven.kessokuteatime.work/releases' }
}

dependencies {
    // 只在编译期用到 DTO 就写 compileOnly；要在运行时也加载这个 Mod，把 jar 放进 mods 目录
    compileOnly 'dev.craftgraph:craftgraph:0.3.0'            // Minecraft 1.21.1
    // 1.20.1 那个产物用的是另一个 artifactId：
    // compileOnly 'dev.craftgraph:craftgraph-mc1.20.1:0.3.0'
}
```

同时发布了 sources jar，DTO 在 IDE 里可以直接看。它们是
[`doc/protocol.md`](doc/protocol.md) 的逐字镜像 —— **那份文档才是真正的契约**，
两者冲突时以文档为准。

浏览已发布的版本：<https://maven.kessokuteatime.work/#/releases/dev/craftgraph/craftgraph>

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
| [`AGENTS.md`](AGENTS.md) | 给在这个仓库里干活的 AI 的说明 —— 先读什么、以及哪些规则违反了会出静默错误答案 |
| [`doc/protocol.md`](doc/protocol.md) | Bridge HTTP 契约 —— 两侧唯一的约定 |
| [`doc/decisions.md`](doc/decisions.md) | 已锁定的技术决策，以及改动它们要付什么代价 |
| [`doc/development.md`](doc/development.md) | 开发环境、四层测试、token 测量、踩过的坑 |
| [`doc/format-evaluation.md`](doc/format-evaluation.md) | 输出格式的实测评估，含对紧凑 DSL 方案的评估 |

## 当前状态

在真实的 1.21.1 实例上端到端验证过：索引 1290 条配方、2% 标记为读不懂、
主线程抽取约 40ms、AI 客户端零配置连接成功。

| | |
|---|---|
| 桥接 Mod | 可用。107 个 JUnit 用例，都不需要启动 Minecraft |
| MCP Server | 可用。41 项算法测试、MCP 协议测试、跨语言契约测试 |
| 配方覆盖 | 所有配方类型都会被读到。原版存在的 7 种类型里有 5 种 100% 可读 |
| 机器与耗时数据 | 每条配方都有机器；112 条熔炼类配方有耗时。能耗原版根本没有这个数据，**宁可为 null 也不编** |
| 锻造配方 | 靠访问转换器读出，做法与 JEI 相同。下界合金升级完全可读；盔甲纹饰读到了它诚实的上限 |
| 模组配方 | **尚未测量** —— 见下方限制 |
| 配方查看器集成 | 未开始。JEI 优先、EMI 可选 —— 见下方限制 |
| 游戏内 UI | 按设计不在 MVP 范围内 |

## 已知限制

**读不懂的配方是真实存在的，而且被计数。** 原版 1290 条里有 31 条标记为 `opaque`：
18 条盔甲纹饰，13 条代码驱动的特殊合成（染色、地图复制、烟花、旗帜复制）。
想看你自己整合包按类型的分解，运行 `npm run inspect`。

还有第三种情况值得知道：有些配方类型**根本不产出东西** —— 燃料定义
（`createaddition:liquid_burning`、`petrochem:*_fuel`）描述的是「烧掉什么换能量」。
它们的输入读得到、产出确实是空的，所以标成 `opaque` 会让 AI 说「这条我读不懂」，
而真相是「它不产出东西」。这类配方带 `producesNothing: true`。
只有**真读过该类型自己的产出字段**的适配器才能这样声明 —— 产出只是**没读到**的配方仍然是 `opaque`。

那 13 条特殊合成**谁都修不了**：逻辑写在 Java 里，不声明任何输入，
视图器也只是特判显示而不是读出来。这里 `opaque` 就是诚实答案，
AI 被告知要说「这条我读不懂」而不是「这配方不需要材料」。

18 条盔甲纹饰是另一回事，而且值得讲清楚，因为它划出了边界在哪。
它们的输入**是读得到的** —— `SmithingRecipe` 确实不覆写 `getIngredients()`，
但三个槽位在包私有字段里，所以 Mod 带了一份 `META-INF/accesstransformer.cfg`
把它们变公开（JEI 做的就是同一件事）。真正无法表达的是**产出**：
`SmithingTrimRecipe#getResultItem()` 返回一个硬编码的占位符
（`new ItemStack(Items.IRON_CHESTPLATE)` 再挂上第一个纹饰和红石），
因为真实产物是组合式的 —— 任意可饰纹盔甲 + 纹饰。
所以纹饰现在报出三个输入和一个明确的空产出，仍然是 `opaque`，
但原因变成了「读不到产出」，而不是之前的「输入和产出都读不到」。

**那个占位符值得给后来人一句警告：只修输入会比不修更糟。**
输入一旦非空，`Readability` 就不再标记这条配方，于是那个硬编码的铁胸甲
从「一个被标记的占位符」变成「一个理直气壮的答案」。
所以适配器对纹饰显式丢弃了通用接口的产物。

修复前实测的表现：问「下界合金头盔怎么做」时，AI 如实转达了
「输入读不出来，这不代表它不需要原料」—— 然后凭自己的训练数据补出了原版做法，
并标注了「这不是从游戏数据里读到的」。原版上那恰好是对的；
但整合包里 KubeJS 或别的模组可能改过，同样一句话就会变成**理直气壮的错误答案**。
**`opaque` 防的是「静默的错误答案」，防不住模型用记忆把空缺补上** ——
所以把真实覆盖率做上去，比覆盖率那个百分比看起来的更值钱。

**机器数只覆盖链条的一部分。** 原版合成配方没有耗时字段，所以工作台环节被报成「手工」
而不是机器数。这是对的 —— 你不会为每次合成造一张工作台 —— 但意味着像火把这样的目标，
只有熔炼那一环会给出机器数。`get_bridge_status` 会明确报出覆盖率，
让 AI 在回答里说清这一点。

**模组机器配方已经覆盖了一部分，而剩下的那部分视图器也只能解决一半。**
看你给的 Create 配方数据（1843 条，其中 15 种是它自己的类型）能看到它们长什么样：
输入是声明式的，但 `results` 是个**列表**，每条带 `count` 和 `chance`；
`processingTime` 才是耗时；流体和物品在两个方向上混在一起。

所以 Mod 现在带了一个 Create 适配器（软依赖 —— 没装 Create 就不注册，也不会崩），
把这四样全读了：多个产出、每个产出的概率、两个方向的流体、以及加工耗时。
一条 Create 粉碎配方因此从「3 个产出只回来 1 个、没有概率、没有耗时」变成真实数据 ——
而 `processingTime` 正是让 Create 机器算出真实台数的东西。**这一点任何视图器都给不了**：
JEI 和 EMI 的 API 里根本没有「耗时」这个概念。

还没做的，以及为什么它们同样不是视图器问题：

- `sequenced_assembly` 是另一个缺口，现在已经正确展开了。它嵌套了一整串子配方，
  按朴素方式读会**看起来可读**、实际漏掉大部分原料成本。适配器读的是 Create 自己的源码语义
  而不是猜：总步数是 `sequence.size() × loops`（所以每一步的原料要吃 `loops` 次），
  而 `results` 里装的是**权重不是概率**（`getOutputChance()` 就是 `权重 / 权重和`）。
  它还会把每一步输入里的**中间产物**剔除 —— 那是线上自己造出来的过渡物品，
  留着会让原料表里出现一个玩家根本拿不到的东西。总加工耗时也一并报出来，
  下游据此算出的台数就是「几条并行组装线」。
- **模组配方的机器名得靠 JEI。** Create 没有覆写 `getToastSymbol()`，
  于是它的配方拿到接口默认值（`crafting_table`），而我们的策略是刻意不信任这个默认值；
  从配方类型反推也不可靠（Create 的砂纸是**物品**不是方块，洗涤和灼烧共用一台机器，
  注液/排液分散在 Spout 和 Item Drain 上，而一条组装线本身就是好几个方块）。
  JEI 的催化剂才是权威答案 —— 这就是为什么第一个要接的视图器是 JEI 而不是 EMI。
- 能耗仍然全是 `null`。

**已经在真实的 Create 专精整合包上测过。** 一个以 Create 为核心的中型包（15,241 条配方、
3,585 个标签、70 种配方类型）实测：

| | |
|---|---|
| 读不懂的配方 | 632 / 15,241（**4%**） |
| 主线程抽取 | **155 ms**（建索引 2 ms） |
| 带耗时的配方 | 572 / 15,241（3.8%）—— 全是原版熔炼类 |
| 带机器名的配方 | 10,945 / 15,241（71.8%） |

两个数字值得注意。耗时只覆盖 3.8%，正因为**没有任何模组配方类型带耗时** ——
耗时藏在各模组自己的字段里，而那时还没有对应适配器。而 28% 没有机器名的
全是模组机器类型，只有视图器的催化剂能可靠给出 —— 这就是 JEI 值得接的具体理由。

这个包的 `/snapshot` 约 10 MB JSON。抽取耗时随配方数线性增长
（15k 时 155ms ≈ 10 µs/条），5 万条大致落在 500ms —— 超过 200ms 预算，
到那时分帧切片就从「可选」变成「必须」。

**更早的测量来自近原版实例**（1290 条配方），本文里没标明的 token 数字都出自那里。

**支持 1.21.1（NeoForge）与 1.20.1（MinecraftForge 47.2.0+）。** 没有 Fabric，没有其他版本。

1.20.1 那一侧是后来补的，共享同一套算法与协议（`core/`），请优先在 1.21.1 上验证过的功能为准 ——
两个版本的已知差异记在 [doc/porting-1.20.1.md](doc/porting-1.20.1.md)。

## 许可

MIT —— 见 [LICENSE](LICENSE)。
