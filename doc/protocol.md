# CraftGraph 接口契约（Bridge HTTP API v1）

这份文档是**游戏内 Mod** 和 **MCP Server** 之间唯一的约定。两侧可以完全并行开发：
只要都符合这里的定义，它们就一定能对接上。

> 修改此文档前请三思。任何字段改名/改语义都会同时影响两侧，属于破坏性变更，
> 必须同步升 `protocolVersion`。

---

## 0. 职责划分

| 层 | 负责 | 不负责 |
|---|---|---|
| **Bridge Mod**（Java，游戏内） | 从游戏读出配方/注册表；**归一化**成下面的模型；提供只读 HTTP + WebSocket | 任何图算法、产线计算、缓存策略 |
| **MCP Server**（TypeScript，游戏外） | 配方树递归、产线计算、缓存/索引、MCP 工具定义 | 不碰 Minecraft 类，不解析 NBT |

**为什么把归一化放在 Mod 里**：把 `Recipe` 对象翻译成"输入/输出"需要 Minecraft 和 EMI 的类，
只有游戏进程里做得到。反过来，图算法放在 Mod 里是灾难——游戏崩溃会连带算法挂掉，
而且算法没法脱离游戏测试。**Mod 越薄越好**，它跑在一个脆弱的环境里。

**重要推论**：MCP Server 的全部核心逻辑都能用 `shared/fixtures/` 里的假数据测试，
不需要启动游戏。这是你调试算法的主要方式，请务必利用起来。

---

## 1. 传输与安全

- 监听 `127.0.0.1`（**仅**本地回环，不监听 0.0.0.0）。
- 默认端口 `25585`，可在 Mod 配置里改。
- 所有请求带 `Authorization: Bearer <token>`，token 首次启动随机生成。
  不校验会怎样？任何网页都能用 `fetch('http://localhost:25585/...')` 探测并读取你的游戏数据。
- **不发送 `Access-Control-Allow-Origin` 头**（即禁止浏览器跨域读取）。
- 所有响应 `Content-Type: application/json; charset=utf-8`。

### 1.1 服务发现文件（重要）

Mod 启动后把实际监听的地址和 token 写到**两个**位置：

1. **游戏目录下**：`<gameDir>/craftgraph/bridge.json`
   （原版启动器下即 `%APPDATA%\.minecraft\craftgraph\bridge.json`）
2. **固定用户目录**：`~/.craftgraph/bridge.json`

```jsonc
{
  "protocolVersion": 1,
  "host": "127.0.0.1",
  "port": 25585,
  "token": "3f9a...c1",
  "pid": 12345,
  "startedAt": "2026-09-19T12:00:00Z"
}
```

**为什么需要这个**：否则你要在 Mod 配置和 MCP Server 配置里各填一次端口和 token，
改一个忘一个，然后花半小时 debug 为什么连不上。有了这个文件，用户零配置。

**为什么是两个位置**：用第三方启动器（PCL2 / HMCL / Prism）时游戏目录不在默认位置，
外面的 MCP Server 无从得知。固定用户目录那份解决了这个问题。MCP Server 的查找顺序是：

1. `CRAFTGRAPH_BRIDGE_URL` 环境变量
2. `CRAFTGRAPH_BRIDGE_FILE` 指定的文件
3. `~/.craftgraph/bridge.json`
4. 传统默认位置 `<默认 .minecraft>/craftgraph/bridge.json`

**多实例**：同时开两个游戏实例时，后启动的会覆盖先启动的，MCP Server 连到后者。
这是刻意的取舍——大多数用户只有一个实例，为此引入实例选择逻辑不值得。
要连特定实例，用 `CRAFTGRAPH_BRIDGE_FILE` 直接指定那个实例游戏目录下的文件。

游戏正常退出时两份都会被删除。崩溃时文件会残留，但这时 MCP Server 连接会失败并
报「连不上」，提示依然准确，不会误导。

---

## 2. 核心数据模型

### 2.1 物品与流体

```jsonc
// ItemStack —— 具体、可数的东西
{
  "item": "minecraft:iron_ingot",   // 注册表 id
  "count": 1,
  "components": { }                  // 1.21 数据组件；MVP 原样透传，不理解内部结构
}

// FluidStack
{
  "fluid": "minecraft:water",
  "amount": 1000                     // 毫桶(mB)，Minecraft 流体的标准单位
}
```

### 2.2 Ingredient（配方槽位）

一个槽位表示"**这些东西中的任意一个**都算数"。这是配方面向标签时必然的形态。

```jsonc
{
  "kind": "item",                    // "item" | "fluid"
  "count": 1,                        // 物品个数；kind=fluid 时是 mB
  "options": [                       // 满足任意一项即可
    { "type": "item",  "id": "minecraft:iron_ingot" },
    { "type": "tag",   "id": "forge:ingots/iron" },
    { "type": "fluid", "id": "minecraft:water" }
  ]
}
```

**注意**：`options` 里出现 `tag` 意味着**这个槽位有多种可选原料**。
产线计算时必须做选择（默认取 tag 展开后的第一个，允许用户覆盖）。
忽略这一点是做配方树最常见的错误——直接把 tag 当成一个叫 `#forge:ingots/iron` 的"物品"，
结果算出来的原料表玩家根本用不了。

### 2.3 Recipe

```jsonc
{
  "id": "minecraft:iron_ingot_from_smelting_iron_ore",
  "type": "minecraft:smelting",      // 配方类型 id
  "typeLabel": "Smelting",           // 给人看的名字（装了 EMI/JEI 时更准）
  "inputs":  [ /* Ingredient[] */ ],
  "outputs": [ /* ItemStack[] */ ],
  "fluidOutputs": [ /* FluidStack[] */ ],
  "chanceOutputs": [                 // 概率产出
    { "stack": { "item": "...", "count": 1 }, "chance": 0.05 }
  ],
  "machine": "minecraft:furnace",    // 机器方块 id；推断不出来时为 null
  "duration": 200,                   // 游戏刻(ticks)，20 tick = 1 秒；未知为 null
  "energy": null,                    // 消耗能量(FE)；未知为 null
  "source": "vanilla",               // "vanilla" | "emi" | "adapter"
  "opaque": false,                   // 见下方说明
  "producesNothing": false           // 本来就不产出物品（燃料/配置类）。见下方说明
}
```

#### `opaque` 字段：诚实标记

`opaque: true` 表示**这个配方的输入/输出没能被正确解析**。触发场景：

- 模组用了自定义配方格式，输入藏在自定义字段里（Create 的混合、GT 的机器配方等）
- 没装 EMI/JEI，拿不到归一化后的视图
- 解析器抛异常

**为什么必须显式标记而不是返回空 `inputs`**：空 `inputs` 看起来像"这配方不要原料"，
AI 会据此得出错误结论并且不自知。标记成 `opaque` 后，MCP Server 会告诉 AI
"这条配方我读不懂"，AI 就能诚实地说不知道，而不是编一个假答案。

#### `producesNothing` 字段：区分「不产出」和「读不到」

`producesNothing: true` 表示**这条配方本来就不产出物品**，而不是「产出读不到」。

两者都会让 `outputs` 为空，含义却相反：

| 情况 | `opaque` | `producesNothing` | AI 该说的话 |
|---|---|---|---|
| 正常 | false | false | 正常回答 |
| 产出读不到（模组自定义格式） | **true** | false | 「这条配方我读不懂」 |
| 本来就不产出（燃料定义、刷怪配置） | **false** | **true** | 「这条配方不产出物品」 |

**为什么必须分开**：燃料配方（`createaddition:liquid_burning`、`petrochem:*_fuel`）描述的是
"烧掉什么换能量"，输入完全读得到、产出确实是空的。把它标成 `opaque` 会让 AI 说
"这条我读不懂" —— 而我们明明读清楚了。反过来，把读不懂的配方说成"不产出"更糟：
那等于告诉 AI 这个配方不需要产出，又是一个静默的错误答案。

**谁来作证**：只有**读过该配方类型自己的产出字段**的适配器才能声明这一点
（Mod 侧的 `RecipeTypeAdapter#declaresNoOutput`）。没有适配器作证的「产出为空」
一律按 `opaque` 处理 —— 宁可说"读不懂"，也不能把未读到的产出说成"不存在"。

**约定**：为 true 时保证 `opaque === false` 且 `outputs` 为空。
旧版 Mod 不发这个字段，调用方按 false 处理（它是可选字段，不是破坏性变更）。

**`source` 字段**告诉你这条数据是谁归一化的：

- `vanilla`：只用原版 `RecipeManager` + 通用接口读出来的
- `emi`：通过 EMI 的归一化视图读出来的（信息最全）
- `adapter`：为某个特定模组手写的适配器读出来的

---

## 3. HTTP 端点

所有列表端点都分页。默认 `limit=100`，最大 `limit=1000`。

### `GET /health`

不需要 token。**必须能在任何线程、任何时刻立刻返回**（包括游戏还在加载时）。
MCP Server 靠它判断游戏在不在。

```jsonc
{
  "ok": true,
  "ready": true,
  "protocolVersion": 1,
  "modVersion": "0.1.0",
  "mcVersion": "1.21.1",
  "loader": "neoforge-21.1.251",
  "emi": { "version": "1.1.22" },    // 未安装为 null
  "jei": null,
  "recipeCount": 12345,
  "itemCount": 2345,
  "dataVersion": 7,
  "uptimeMs": 123456
}
```

- `ok`：服务活着（能响应就是 true）
- `ready`：**配方数据是否已索引好、可以查询**

#### `ready` 为什么必须单独存在

游戏刚启动、或世界正在加载时，`/health` 能立刻响应，但配方还没索引完。
这时候如果只靠 `recipeCount` 判断，调用方会看到 0 条配方，然后**建出一个空缓存** ——
症状是「查什么都说没有」，而不是「还在加载」。前者会让用户以为 Mod 坏了。

有了 `ready`：

| `ready` | 其他端点行为 | 调用方应该 |
|---|---|---|
| `false` | 一律返回 `503` + `Retry-After` | 等一会儿重试，**不要建缓存** |
| `true` | 正常 | 按 `dataVersion` 判断要不要重建缓存 |

兼容性：旧版 Mod 没有这个字段时，调用方按 `true` 处理。

#### 3.1 `dataVersion` —— 缓存失效的唯一依据

一个单调递增的整数。**配方或注册表发生任何变化时 +1**（`/reload`、KubeJS 注入、
数据包重载、服务器下发新配方等）。

MCP Server 的全部缓存都以它为准。不要用"启动时间"或"配方数量"代替：
数量可能不变而内容变了（改一条配方），那样缓存永远不会失效。

### `GET /registry/{kind}`

`kind` ∈ `items` | `blocks` | `fluids` | `entities` | `recipe_types`

查询参数：`query`（子串匹配 id 或显示名，大小写不敏感）、`offset`、`limit`

```jsonc
{
  "kind": "items",
  "total": 2345,
  "offset": 0,
  "limit": 100,
  "entries": [
    { "id": "minecraft:iron_ingot", "displayName": "铁锭" }
  ]
}
```

### `GET /tags/{kind}`

`kind` ∈ `items` | `blocks` | `fluids`。列出所有标签及成员数量。

```jsonc
{ "kind": "items", "total": 812, "entries": [ { "id": "forge:ingots/iron", "count": 3 } ] }
```

### `GET /tags/{kind}/{tagId}`

展开一个标签的全部成员。用于把 §2.2 里的 `tag` 选项变成具体物品。

```jsonc
{ "id": "forge:ingots/iron", "entries": ["minecraft:iron_ingot", "othermod:iron_ingot"] }
```

### `GET /tags/{kind}/all`

**一次返回该 kind 下所有标签及成员。**

```jsonc
{
  "kind": "items",
  "tags": {
    "forge:ingots/iron": ["minecraft:iron_ingot", "othermod:iron_ingot"],
    "minecraft:planks": ["minecraft:oak_planks", "minecraft:birch_planks"]
  }
}
```

**为什么必须有这个端点**：MCP Server 建倒排索引时必须把标签展开成具体物品。
不展开的话，"哪些配方消耗铁锭"会漏掉所有用 `#forge:ingots/iron` 作为输入的配方 ——
而这在整合包里是绝大多数。没有批量端点就得逐个标签发请求，大整合包是上千次往返，
冷启动慢到不可接受。

> 实现提示：`/tags/{kind}/all` 必须注册在 `/tags/{kind}/{tagId}` **之前**，
> 否则 `all` 会被当成一个标签名匹配掉。

### `GET /recipes`

查询参数（可组合，全部为 AND 关系）：

| 参数 | 含义 |
|---|---|
| `output` | 产出该物品的配方 |
| `input` | 消耗该物品作为输入的配方 |
| `type` | 指定配方类型 |

外加 `offset`、`limit`。

**`input` 查询是这里唯一有性能陷阱的**：原版只按产出建了索引，按输入查需要遍历全部配方。
Mod 侧应在配方加载时后台建好倒排索引，否则这个请求会卡住主线程好几秒。
索引没建好时请返回 `503` 加 `Retry-After`，不要阻塞。

返回**摘要**（不含完整 inputs，省流量）：

```jsonc
{
  "total": 3,
  "offset": 0,
  "limit": 100,
  "recipes": [
    { "id": "minecraft:iron_ingot_from_smelting_iron_ore",
      "type": "minecraft:smelting", "typeLabel": "Smelting",
      "primaryOutput": { "item": "minecraft:iron_ingot", "count": 1 },
      "opaque": false }
  ]
}
```

### `GET /recipes/{recipeId}`

单条完整配方，返回 §2.3 的 `Recipe` 对象。找不到返回 `404`。

### `GET /snapshot`

给 MCP Server 建本地缓存用的全量导出。分页游标式。

查询参数：`cursor`（从 0 开始）、`limit`（默认 500，最大 2000）

```jsonc
{
  "dataVersion": 7,                  // 每页都带，用于检测中途失效
  "cursor": 0,
  "nextCursor": 500,                 // 为 null 表示这是最后一页
  "total": 12345,
  "recipes": [ /* Recipe[]，本页的 */ ]
}
```

**调用方必须校验每页的 `dataVersion` 一致**。若中途变了，说明游戏重载了配方，
之前拉的页全部作废，必须从头再来。不做这个校验会得到一个"一半旧一半新"的缓存，
而且症状诡异到几乎无法排查。

### `POST /reload`（可选，默认关闭）

请求游戏重载配方缓存。因为它是**写操作**，需要配置里显式开启。
MCP 的 `refresh_recipes` 工具优先走 `dataVersion` 轮询，只有在用户明确要求时才调它。

---

## 4. WebSocket 事件

升级端点：`GET /events`（带 token）

```jsonc
{ "type": "recipe_reload", "dataVersion": 8, "recipeCount": 12350 }
{ "type": "registry_reload", "dataVersion": 9 }
{ "type": "bridge_shutdown" }
```

MCP Server 收到事件后**只做一件事**：把本地缓存标记为失效，并在下次请求时重建。
不要在这里推送全量数据。

`bridge_shutdown` 用于游戏退出时通知 MCP Server 立刻答复"游戏已关闭"，
而不是等 HTTP 超时。

---

## 5. 错误格式

所有非 2xx 响应统一：

```jsonc
{ "error": { "code": "NOT_FOUND", "message": "recipe 'foo:bar' 不存在" } }
```

| HTTP | code | 含义 |
|---|---|---|
| 400 | `BAD_REQUEST` | 参数非法 |
| 401 | `UNAUTHORIZED` | token 缺失或错误 |
| 404 | `NOT_FOUND` | 对象不存在 |
| 503 | `NOT_READY` | 数据还在加载/索引未建好，附 `Retry-After` |
| 504 | `TIMEOUT` | 已派发到游戏线程但超时未完成 |
| 500 | `INTERNAL` | 其他 |

---

## 6. 线程模型（Mod 侧必须遵守）

这是整个 Mod 里最容易出错的地方，单独强调。

HTTP 请求由 Netty 工作线程处理，**不是游戏主线程**。而 Minecraft 的
`RecipeManager`、`RegistryAccess`、`Level` 等对象**只能在主线程访问**。
在错误的线程上读它们不会立刻报错，而是随机崩溃、返回陈旧数据、或者死锁。

规则：

1. `/health` 和 `/events` **只在主线程写着、其他线程只读**的不可变快照上工作，
   任何线程都能直接答复，不派发。
2. 其余端点：把任务丢进主线程队列（`Minecraft.getInstance().execute(...)`），
   然后用 `CompletableFuture` 等其他线程回复。**设置 5 秒超时**，超时返回 504。
3. **绝不在主线程上做重活**。全量遍历建倒排索引这种，用后台线程读主线程已经
   拷贝出来的不可变快照，或者分帧切片处理。卡住主线程 = 游戏掉帧 = 玩家卸载这个 Mod。

---

## 7. 版本演进

`GET /health` 里的 `protocolVersion` 是整数。破坏性变更 +1。
MCP Server 启动时检查，不匹配就明确报错，而不是发出畸形请求后神秘失败。
