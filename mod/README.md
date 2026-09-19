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
| 字段覆盖度审计 `extract/FieldCoverage` | ✅ | `FieldCoverageTest`（12 用例） |
| 类型→机器对照表 `extract/MachineTable` | ✅ | `MachineTableTest`（9 用例） |
| 线程派发 `MainThreadDispatcher` | ✅ | 编译通过 |
| Mod 入口接线 `client/ClientBridge` + `CraftGraph` | ✅ | 真游戏跑通（1290 条配方，服务发现零配置） |
| 配方抽取 `extract/RecipeExtractor` | ✅ | 真游戏跑通；`duration` / `machine` 由 `npm run live` 逐条断言 |
| 类型特有字段的适配器 `extract/RecipeAdapters` | 🟡 | 耗时靠 `instanceof` 派发，**只能靠 `npm run live` 验证**（见下） |
| 锻造输入/产出适配器 `extract/SmithingAdapter` + 访问转换器 | ✅ | 真游戏跑通：opaque 40 → 31，9 条下界合金可读、18 条纹饰不再报假产物 |
| Create 适配器 `extract/CreateAdapter`（多产出/概率/流体/耗时） | 🟡 | 编译对着 Create 真实 jar 验过；**运行路径需要装了 Create 的实例**（见下） |
| Create 序列组装适配器 `extract/SequencedAssemblyAdapter` | 🟡 | 同上；`loops` 与权重池语义读 Create 源码确认，不是猜的 |
| 产出概率分类 `extract/ResultChance` | ✅ | `ResultChanceTest`（11 用例，含 NaN / >1 / 权重归一化边界） |
| 访问转换器文件守卫 `AccessTransformerTest` | ✅ | 3 用例（钉住 AT 与适配器的配套关系） |
| 服务发现 `DiscoveryFile` | ✅ | 真游戏跑通（`~/.craftgraph/bridge.json`） |
| 配方查看器集成（JEI 优先） | ⬜ | 只补「只有视图器才知道的东西」，见下 |

**测试共 121 个用例，全部不需要启动 Minecraft。**

### 哪些东西测不了，只能靠进游戏

`RecipeAdapters` 的派发判据是 `instanceof AbstractCookingRecipe` / `instanceof SmithingTrimRecipe` /
`instanceof ProcessingRecipe`，这需要真实的 MC 类（Create 那条还需要真实的 Create 类）才能验证，
而测试源码集**看不到 Minecraft、也看不到 Create**。所以：

| 部分 | 谁来验证 |
|---|---|
| 类型→机器对照表、字段覆盖度审计、产出概率分类、AT 文件内容 | JUnit（纯逻辑，不碰 MC） |
| `instanceof` 派发是否真的匹配那 4 种烹饪配方 / 两个锻造子类 | **`npm run live`**，在真游戏上逐条核对 |
| 锻造的输入真的读到了、假产物真的没出现 | **`npm run live`**，见 live.ts 的锻造段 |
| **Create 适配器的运行行为** | **只能靠装了 Create 的实例**。本机 dev 实例没装 Create，所以这条路径**尚未被实测** —— 能验证的只有「编译对着真实 jar 通过」和「没装 Create 时不会崩」 |
| 没装 Create 时不会因类加载而崩 | 真机启动（日志里 `NoClassDefFoundError` 出现 0 次）+ `npm run live` |

`npm run live` 里的 Create 那一段是**条件断言**：装了 Create 就逐条验，
没装就**明确打印「本段未验证」**并在结尾的「未验证的段落」里列出来。
条件断言最危险的失效方式是「条件不成立所以什么都没测，而输出看起来一切正常」，
所以它不能只是静默跳过。

### 软依赖模组适配器：必须惰性注册

`CreateAdapter` 直接引用 Create 的类。**一旦它被放进静态列表，类加载就会连带解析那些类**，
没装 Create 的实例会在构建快照时直接 `NoClassDefFoundError` —— 整个桥接都不可用。

所以 `RecipeAdapters` 的列表是懒建的，并且先用 `ModList.isLoaded("create")` 判断，
`new CreateAdapter()` 留在 lambda 里（JVM 按指令惰性解析类，分支不执行就不会去找那个类）。
外面还包了 `catch (Throwable)`：万一将来某个模组的类加载出别的问题，
后果应该是「少一个适配器」，而不是「整个快照建不出来」。
**实测**：原版实例（没装 Create）启动 → 1290 条配方、opaque 31，
日志里 `NoClassDefFoundError` / `ClassNotFoundException` / `CreateAdapter` 出现 **0 次**。

源码里引用模组 API 用 `compileOnly` 且 `transitive = false`。它的作用**只是让编译器
逐条核对我们的调用与真实 API 一致** —— 方法名、参数、返回类型都会被检查；
没有它就只能靠反射，那样连拼写错误都发现不了。
（关掉传递依赖是因为 Create 的实现依赖散落在别的仓库，而它们对「签名对不对」毫无帮助。）

这条分工是交过学费的：`duration` 曾经在真数据里全是 `null`，而所有 JUnit 用例全绿。
**「编译器能验证的部分」和「只有真游戏能验证的部分」必须分清，后者要有专门的层去测。**

```bash
cd mod && ./gradlew test        # Java 侧 121 用例
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

`typeLabel` 由 `Humanize.typeLabel` 从类型 id 推出来，不需要 EMI。

**`machine` 和 `duration` 原版给得出，不要留 null。** 这一条是踩过坑才写在这里的：
代码里曾经写着「原版拿不到，接了 EMI 之后再补」，于是这两个字段在真实数据里
**1290 条全是 null**，`calculate_production_plan` 的机器数计算整个失效 ——
而所有单元测试都绿，因为夹具里手写了 duration。实际的原版接口是：

| 字段 | 原版接口 | 覆盖 |
|---|---|---|
| `duration` | `AbstractCookingRecipe#getCookingTime()` | 4 种烹饪配方（熔炼 200 / 高炉 100 / 烟熏 100 / 营火 600 刻） |
| `machine` | `Recipe#getToastSymbol()` 返回的物品 | 通用 |

`getToastSymbol()` 有一个坑：**接口默认实现返回 `minecraft:crafting_table`**，
所以「没覆写它的模组机器配方」和「真正的工作台合成」返回值一样。
直接采信会把整合包里每台模组机器都报成工作台 —— 一个看起来很确定的错答案。
`MachineTable` 因此分两层判断：原版 7 种类型查核实过的表，表外类型只在值不等于默认值时采信。

`energy` 原版**确实没有**这个数据（只有 EMI 或模组适配器拿得到）。
**不要伪造** —— 编一个能耗数字比 null 危险得多。

新增类型时，在 `RecipeAdapters` 里加一个 `RecipeTypeAdapter` 实现即可。
判据用「是什么类」而不是「类型 id 白名单」，见该接口的注释。

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

### 6. 配方查看器增强（JEI 优先，EMI 可选）—— 只补「只有视图器才知道的东西」

**不要把能自己读的交给视图器。** 这条是踩过来的：锻造那 27 条一开始被归给 EMI，
实际上靠一份访问转换器（第 9 条决策）就解决了，零依赖。

真正只有视图器才知道的是这几类：

| 缺口 | 为什么视图器才知道 |
|---|---|
| 模组机器配方的输入 | 模组的配方类没有通用接口，视图器的插件知道怎么读它的自定义字段 |
| 模组机器的名字 | 没有插件的话，`getToastSymbol()` 的接口默认值会把每台模组机器都报成工作台 |
| 能耗 FE | 原版根本没有这个数据 |

JEI 的能力逐条核实过（19.56.0.441 的 api jar + 源码）：

- `createRecipeLookup(RecipeType).get()` 枚举某类别下全部配方；`createRecipeCategoryLookup()` 枚举类别
- **`getRecipeIngredients(category, recipe)` → `IIngredientSupplier`**，
  `getIngredients(RecipeIngredientRole)` 拿归一化配料，**不需要 GUI 绘制**
- 角色是 `INPUT` / `OUTPUT` / `CATALYST` / `RENDER_ONLY` —— 语义正好对上我们的 `inputs` / `outputs` / `machine`
- `createRecipeCatalystLookup(RecipeType).getItemStack()` —— **催化剂就是机器**
- 配方对象是 `RecipeHolder<...>`，**能按配方 id 与我们已有的抽取结果 join**
  （所以是「按 id 补字段」，不是「换一套数据源」）
- 拿 `IJeiRuntime` 的唯一途径是实现 `IModPlugin#onRuntimeAvailable`，
  也就是要注册一个 `@JeiPlugin` 类 —— 软依赖的标准做法，类只在 JEI 存在时加载

**JEI 不提供的两样**（别指望它）：耗时的概念完全没有（模组的 `processingTime`
只能靠各模组自己的适配器读），概率也不作为字段暴露（EMI 的 `EmiStack#getChance()` 有）。

EMI 的 API 同样核实过：`EmiRecipe#getInputs()/getOutputs()/getCatalysts()` 更直接，
且 `getBackingRecipe()` 也能 join。但普及率远低于 JEI，所以排在后面，可能一直不做。

**去掉任何视图器必须仍然能编译和运行**，这条是第 2 条决策的核心。

## 构建

```bash
cd mod
./gradlew build          # 编译 + 打包（已验证可用）
./gradlew test           # 121 个 JUnit 用例，不需要启动 Minecraft
./gradlew runClient      # 启动带 Mod 的游戏
```

wrapper 已经生成好了，不需要本机装 Gradle。用 IDE 的话直接把这个目录当 Gradle 项目打开即可。

**已验证**：`BUILD SUCCESSFUL`，产出 `build/libs/craftgraph-<version>.jar`，
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
仓库地址 `https://maven.kessokuteatime.work/releases`，
已发布的版本可以在 <https://maven.kessokuteatime.work/#/releases/dev/craftgraph/craftgraph> 浏览。

⚠️ **发布过的版本号不要复用。** 同一个 `0.1.0` 覆盖成不同的字节，会让依赖方的缓存
（Gradle、Maven 本地库）拿到哪个版本全看运气，而且报错完全指不到原因。
改了东西就升 `mod_version` 再发。

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
