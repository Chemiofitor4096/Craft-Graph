# CraftGraph Bridge Mod

把运行中的 Minecraft 配方数据读出来，通过本地 HTTP 供给游戏外的 MCP Server。

**这个 Mod 只做数据搬运，不做任何计算。** 配方树、产线规划这些全在 MCP Server 侧。
理由是那些算法跑在游戏进程里既没法脱离游戏测试，游戏一崩还连带它们一起挂。
接口约定见 [`../doc/protocol.md`](../doc/protocol.md)，那份文档是唯一的依据。

## 当前状态

| 部分 | 状态 | 验证方式 |
|---|---|---|
| Gradle 构建（ModDevGradle 2.0.147 + NeoForge 21.1.251 + Java 21） | ✅ | `./gradlew build` |
| 协议数据结构 `api/Models` + Gson 配置 | ✅ | `JsonTest`（6 用例） |
| 不可变快照 + 倒排索引 `snapshot/RecipeSnapshot` | ✅ | `RecipeSnapshotTest`（16 用例） |
| 槽位归一化 / 标签还原 `normalize/*` | ✅ | 24 用例 |
| HTTP 服务（路由、鉴权、分页、错误码） | ✅ | `BridgeHttpServerTest`（20 用例） |
| 快照生命周期 `bridge/BridgeService` | ✅ | `BridgeServiceTest`（7 用例，含并发读取） |
| 跨语言契约（Java 输出 → TS 消费） | ✅ | `ContractDumpTest` + `npm run contract`（17 项） |
| 线程派发 `MainThreadDispatcher` | ✅ | 编译通过 |
| Mod 入口接线 `client/ClientBridge` + `CraftGraph` | 🟡 | 编译通过；**需进游戏验证** |
| 配方抽取 `extract/RecipeExtractor` | 🟡 | 编译通过；**语义需进游戏验证** |
| 服务发现 `DiscoveryFile` | 🟡 | 编译通过；需游戏内验证 |
| EMI 软依赖增强 | ⬜ | 提高 opaque 配方的解析率 |

**测试共 74 个用例，全部不需要启动 Minecraft。**

```bash
cd mod && ./gradlew test        # Java 侧 74 用例
cd ../mcp-server && npm run contract   # 跨语言契约 17 项
```

### 跨语言契约验证怎么做的

两侧一直是各自对着假数据开发的：TypeScript 侧用手写的假 Bridge，Java 侧用自己的内存数据。
**它们从没真正对过话。** 两边都照着 `doc/protocol.md` 写，但契约本身没被跑通过 ——
字段名差一个字母、`null` 处理不一致，都要等进游戏同时调两个系统时才发现。

做法：

1. `ContractDumpTest` 把 Java 侧**真实的 HTTP 响应体**写到 `shared/fixtures/bridge-dump/`，
   覆盖易错形态：`null` 字段、概率产出、opaque 配方、流体、中文显示名
2. TypeScript 侧的 `npm run contract` 用这些样本回放出一个 Bridge，
   让整套索引和处理链路跑在**真实字节**上，并断言两侧对同一份数据得出一致的结论

它顺带是「金标准样本」：那批 JSON 就是 Java 实际会发出的东西，可以直接拿来看。

## 编译期验证出来的 API 事实

以下几条是写代码时编译器纠正的，记下来省得下次再猜：

| 事实 | 说明 |
|---|---|
| `Ingredient#getItems()` 返回 **`ItemStack[]` 数组**，不是 `Stream` | 用 for-each |
| `HolderLookup.Provider#lookupOrThrow()` 返回 **`RegistryLookup<T>`** | 只读视图，枚举标签用 `listTags()` |
| Minecraft 1.21.1 自带 **Gson 2.10.1**（`strictly` 锁定） | record 支持需要 ≥2.10 |
| `RecipeManager#getRecipes()` 返回 `Collection<RecipeHolder<?>>` | 配方 id 从 holder 取 |
| **没有 `GameShutdownEvent`** | 改用 JVM 关闭钩子（也更可靠，见下） |
| `RecipesUpdatedEvent#getRecipeManager()` 可用 | 客户端配方同步完成的信号 |

测试类路径上默认**看不到** Gson —— ModDevGradle 只把它加到主源码集。
`build.gradle` 里显式声明了 `testImplementation 'gson:2.10.1'`，版本必须与游戏一致。

### 为什么关闭用 JVM 钩子而不是游戏事件

NeoForge 的关闭事件不是所有退出路径都会触发（崩溃、被启动器强杀等）。
而残留的发现文件会让 MCP Server 以为游戏还在运行，然后一直连接超时，
报错信息还指向一个「看起来没问题」的地址 —— 很难查。JVM 钩子在正常终止时一定会跑。


## 架构决策：一次性快照，而不是每次请求现查


**这是本 Mod 最重要的设计决定，先看懂再写代码。**

朴素做法是每个 HTTP 请求都去问一次游戏：`/recipes?output=X` 就遍历 `RecipeManager` 找一遍。
问题是遍历上万条配方只能在主线程做，而每次请求都要走一趟主线程 —— 玩家的体验就是
AI 每问一句游戏卡一下。

正确做法是**快照**：

```
配方加载完成（或重载）
      │
      ├─ 主线程：把 Recipe 对象翻译成 DTO（这一步只能在主线程）
      │           ↓ 交出一个不可变的 List<Recipe>
      └─ 后台线程：建索引（按产出、按输入的倒排、反向标签索引）
                   ↓
              发布给一个 volatile 字段
                   ↓
        所有 HTTP 请求直接读这个快照，完全不碰主线程
```

好处：

- **HTTP 请求路径上完全不接触游戏对象**，线程安全问题从根上消失 ——
  `BridgeHttpServer` 里没有一处需要 `MainThreadDispatcher`
- 请求响应快，不随整合包大小变慢
- 快照不可变，可以放心跨线程共享

代价：**构建快照那一下会占用主线程**。大整合包里翻译上万个配方可能需要 1~3 秒。
`RecipeSnapshot.build()` 会把建索引耗时记下来（`buildMillis()`），先测量再决定要不要分帧切片。
不要在没测量之前就优化。

### 快照什么时候重建

NeoForge 会在配方重载时触发事件（数据包重载、`/reload`、KubeJS 注入等）。
每次重建都要 **`dataVersion` +1**。MCP Server 靠这个数字判断缓存是否失效 ——
不要用启动时间或配方数量代替，数量可能不变而内容变了。

### `ready` 标志

游戏刚启动、世界正在加载时，`/health` 能响应但配方还没索引完。
这时必须返回 `ready: false`，其他端点返回 `503`。

**不能只靠 `recipeCount == 0` 来表达这个状态** —— 调用方会据此建出一个空缓存，
症状是「查什么都说没有」而不是「还在加载」，用户会以为 Mod 坏了。

## 线程规则（必须遵守）

HTTP 请求由 HttpServer 自己的工作线程处理，**不是游戏主线程**。
`RecipeManager`、`RegistryAccess`、`Level` 只能在主线程访问。

在错误的线程上读它们不会立刻报错，而是随机崩溃、读到陈旧数据、或者死锁 ——
而且往往跑很久才出现一次，极难复现。规则：

1. **所有要读游戏对象的工作，走 `MainThreadDispatcher`。**
2. **绝不在主线程上做重活。** 遍历上万条配方会卡住主线程，玩家体验就是掉帧。
   分帧切片，或者把「翻译成 DTO」和「建索引」分开：前者必须主线程，后者可以后台。
3. **主线程派发的任务里必须捕获 `Throwable`**（不是 `Exception`）。
   逃逸出去的异常会把游戏搞崩。

按快照架构做的话，HTTP 请求路径上**完全不需要** dispatcher —— 它只在构建快照、
以及可选的 `POST /reload` 上用。`BridgeHttpServer` 里没有任何一处调用它，
这不是巧合，而是设计的结果。


## 实现顺序

建议按这个顺序做，每步都能独立验证。不要跳步。

### 1. HTTP 服务 + `/health`（半天）

用 JDK 自带的 `com.sun.net.httpserver.HttpServer`，不要引入 Netty ——
为一个只监听回环地址的只读服务引入网络框架不值得。

`/health` 不需要 token，**必须在任何时刻、任何线程都能立刻返回**，
包括游戏还在加载时。MCP Server 靠它判断游戏在不在。

先让 `curl http://127.0.0.1:25585/health` 有响应，再往下做。

### 2. 服务发现文件 + token 校验（半天）

`DiscoveryFile` 已经写好了，接上去即可。启动时生成 token，
每个请求校验 `Authorization: Bearer <token>`。

别忘了 CORS：**不要**发 `Access-Control-Allow-Origin` 头。
不发的话浏览器就会拦住网页脚本读取你的游戏数据。

### 3. 配方归一化（2~3 天，重头戏）

把 `Recipe` 翻译成 `Models.Recipe`。原版通用接口能覆盖大部分情况：

- `Recipe#getIngredients()` → `List<Ingredient>`（每个 Ingredient 里有 `Ingredient.Value` 列表）
- `Recipe#getResultItem(RegistryAccess)` → 产物
- 输入里的 `TagValue` → 转成 `Option("tag", tagId)`
- `ItemValue` → `Option("item", itemId)`

**归一化不了的必须标记 `opaque = true`，不要返回空 inputs。**
空 inputs 看起来像"这配方不要原料"，AI 会据此编出错误答案。触发 `opaque` 的典型情况：
自定义配方格式的模组机器、解析时抛异常、字段结构不认识。

`typeLabel` 和 `machine` 原版拿不到，先给 null；接了 EMI 之后再补。

### 4. 倒排索引 + `/snapshot` + `/tags/{kind}/all`（1~2 天）

`/recipes?input=X`（哪些配方用 X）在原版**没有现成索引**，必须自己建。
建索引时**必须把标签展开**：用 `#forge:ingots/iron` 作输入的配方，
也要能被 `input=minecraft:iron_ingot` 查到。漏了这一步，
"哪些配方用铁锭"在整合包里会漏掉绝大多数结果 —— 这是最容易出错的地方。

建索引放后台线程。索引没好之前 `/recipes?input=` 返回 `503` + `Retry-After`，
**不要阻塞**。

`/tags/{kind}/all` 是批量端点，MCP Server 建索引时必须用它，
逐个标签拉在大整合包里是上千次往返。

### 5. WebSocket 事件（1 天）

`GET /events`，推送 `recipe_reload` / `registry_reload` / `bridge_shutdown`。
MCP Server 收到后只把本地缓存标记为失效，下次请求时重建 —— 不要在事件里推全量数据。

`bridge_shutdown` 用于游戏退出时让 MCP Server 立刻答复"游戏已关闭"，
而不是干等 HTTP 超时。

### 6. EMI 软依赖增强（3~5 天）

用 `compileOnly` + 反射/可选加载，装了就用来补 `typeLabel` / `machine`，
并提高 `opaque` 配方的解析率。**去掉 EMI 必须仍然能编译和运行。**

EMI 版本：`1.1.24+1.21.1`（Modrinth 标注 `client_only`，这也是本 Mod 做成客户端模组的原因）。

## 构建

```bash
cd mod
./gradlew build          # 编译 + 打包（已验证可用）
./gradlew test           # 83 个 JUnit 用例，不需要启动 Minecraft
./gradlew runClient      # 启动带 Mod 的游戏
```

wrapper 已经生成好了，不需要本机装 Gradle。用 IDE 的话直接把这个目录当 Gradle 项目打开即可。

**已验证**：`BUILD SUCCESSFUL`，产出 `build/libs/craftgraph-0.1.0.jar`，
包含 `CraftGraph`、`MainThreadDispatcher`、`DiscoveryFile`、`api.Models.*`。
不设 `JAVA_HOME` 也能构建（toolchain 从 PATH 找到 JDK 21）。

## 发布到 Maven

```bash
# 凭据只从环境变量读，不写进仓库
export K_MAVEN_USERNAME=... K_MAVEN_TOKEN=...    # Git Bash
# $env:K_MAVEN_USERNAME='...'                    # PowerShell
# set K_MAVEN_USERNAME=...                       # cmd.exe

./gradlew publish
```

产物坐标 `dev.craftgraph:craftgraph:<version>`，含 sources jar。

只想验证产物本身对不对，用 `./gradlew publishToMavenLocal` —— 它不需要凭据，
会写到 `~/.m2/repository/`，可以拿来检查生成的 POM。

没设环境变量时不会得到一个含糊的 401，而是明确告诉你缺哪两个变量以及怎么设 ——
这段提示在 `publishToMavenRepository` 任务的 `doFirst` 里。放在任务里而不是配置阶段，
是因为日常 `gradlew build` 不该因为没设发布凭据而失败。

### ⚠️ gradle.properties 是 ISO-8859-1 读的

**Gradle 按 ISO-8859-1 读取 `gradle.properties`**（为保证向后兼容，是既定行为）。
那里写非 ASCII 字符会变成乱码，而且这个值会被展开进 `neoforge.mods.toml` ——
症状是**游戏里的 Mod 描述显示成一堆 `æè¿è¡ä¸ç Minecraft...`**。

所以：

| 内容 | 放哪 | 编码 |
|---|---|---|
| 中文 Mod 描述（玩家看到的） | `src/main/templates/META-INF/neoforge.mods.toml` | UTF-8 |
| 英文描述（Maven 消费者看到的） | `gradle.properties` 的 `mod_description` | 只能 ASCII |
| 其余元信息（id/name/license/version） | `gradle.properties` | ASCII |

`generateModMetadata` 里显式设了 `filteringCharset = 'UTF-8'`，
不依赖 JVM 默认字符集 —— 否则哪天有人带 `-Dfile.encoding` 跑构建又会变乱码。


### 构建偶发失败先重试

如果构建环境走了 HTTP 代理（`~/.gradle/gradle.properties` 里的 `systemProp.*.proxy*`），
通过代理拉 `maven.neoforged.net` 时可能出现：

```
SSLHandshakeException: Remote host terminated the handshake
```

重试即可成功 —— 这通常是代理把请求路由到了挂掉的节点，属于瞬时故障。
**先重试再排查，不要急着改配置。** 如果频繁出现，可以临时注释掉那几行代理配置
（实测这类仓库直连也能通）。

## 已知的坑

**线程**：见上面。这是这类模组最常见的死法，而且往往跑很久才崩一次，极难复现。

**标签**：任何时候看到 `#xxx`，都意味着"这类物品任意一个都行"。当成普通物品处理是错的。

**客户端 vs 服务端**：本 Mod 是**客户端模组**（`neoforge.mods.toml` 里声明的）。
JEI/EMI 是客户端模组，所以只能这么做。好消息是客户端也有全量配方数据
（原版靠它做配方书），所以读得到的东西不缺。

**自定义 `.minecraft` 路径**：用第三方启动器时游戏目录不在默认位置，
所以发现文件写了两份，见 `DiscoveryFile` 的注释。
