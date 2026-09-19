# 开发文档

面向要改这个项目的人。用户向的介绍在 [README](../README.md)。

- [仓库结构](#仓库结构)
- [开发环境](#开发环境)
- [测试：四层验证](#测试四层验证)
- [跨语言契约验证怎么做的](#跨语言契约验证怎么做的)
- [真实数据暴露的问题](#真实数据暴露的问题)
- [token 开销](#token-开销)
- [踩过的坑](#踩过的坑)

---

## 仓库结构

| 目录 | 内容 |
|---|---|
| `doc/protocol.md` | **Bridge HTTP API 契约** —— 两侧唯一的约定，改代码前先看这个 |
| `doc/decisions.md` | 已锁定的技术决策及其代价 |
| `doc/format-evaluation.md` | 输出格式的实测评估（含对紧凑 DSL 方案的评估） |
| `mcp-server/` | TypeScript MCP 服务器：工具定义、配方树、产线计算 |
| `mod/` | NeoForge 桥接 Mod：读配方、建索引、暴露本地 HTTP |
| `shared/fixtures/` | 测试夹具。`tiny-pack.json` 手写；`bridge-dump/` 由 Mod 测试生成（不提交） |

`mod/` 自带 `gradlew`，可以单独当 Gradle 项目打开，不需要仓库里其他部分。

## 开发环境

| 组件 | 要求 | 说明 |
|---|---|---|
| Java | 21 | NeoForge 1.21.1 要求。toolchain 会从 PATH 找，不需要设 `JAVA_HOME` |
| Node.js | ≥ 20 | MCP Server 侧 |
| Gradle | 不需要安装 | `mod/gradlew` 已生成 |

```bash
cd mcp-server && npm install
cd ../mod && ./gradlew build
```

## 测试：四层验证

```bash
# MCP Server（TypeScript）
cd mcp-server
npm run typecheck    # 类型检查
npm run smoke        # 算法层：索引、标签挑选、合并槽位、循环检测、离线降级
npm run e2e          # 协议层：真实 stdio 握手 + 工具调用
npm run contract     # 跨语言契约：吃 Java 侧的真实输出
npm run live         # ★ 对运行中的真实游戏做体检（需 Minecraft 已启动并进入世界）
npm run measure      # token 测量（1 万条配方合成数据）
npm run compare      # 格式对比：markdown / 列式 TSV / 字典编码
npm run analyze      # 输出成本分解 + 索引查询延迟
npm run inspect      # 配方覆盖度诊断：哪些配方类型读不懂、为什么

# Bridge Mod（Java）—— 不需要启动 Minecraft
cd ../mod
./gradlew test
./gradlew build
./gradlew runClient   # 启动带 Mod 的游戏
```

四层各自负责不同的东西，**都不能省**：

| 层 | 数据来源 | 能发现什么 |
|---|---|---|
| `smoke` / JUnit | 手写夹具 | 算法逻辑、边界情况 |
| `e2e` | 自建假 Bridge | MCP 协议本身（握手、工具 schema、错误码） |
| `contract` | Java 导出的真实响应体 | 两侧字段与 `null` 处理是否一致 |
| **`live`** | **运行中的真游戏** | **真实配方形状、真实标签规模、真机 token 消耗、以及字段覆盖度** |

`live` 除了跑通链路，还负责**断言下游要用的字段在真数据里确实有值**。
这一条是交过学费的：`duration` 曾经全是 `null` 而另外三层全绿，
因为夹具里手写了它。**光断言「拿到值之后算得对」不够，还要断言「值本身拿得到」。**

`live` 是唯一跑在真实数据上的，也是唯一发现过「我的假设错了」这类问题的层。
它刻意**不配置任何环境变量**，靠 `~/.craftgraph/bridge.json` 自己发现游戏 ——
顺带验证了服务发现机制本身。

### 一个必须知道的顺序依赖

`npm run contract` 消费的样本由 Mod 的 `ContractDumpTest` 生成（`./gradlew test`）。
所以在新克隆的仓库上要按这个顺序：

```bash
cd mod && ./gradlew test        # 先：生成契约样本
cd ../mcp-server && npm run contract
```

样本本身**不提交**（是生成物）。`contract.ts` 会检查样本是否比 Java 源码旧，
过期时在开头和结论处各提醒一次 —— 「用旧样本跑出的全绿」是最危险的假通过。
缺少样本时会给出明确的补跑提示。

### 测量脚本的缓存隔离

所有生成合成数据的脚本（`measure` / `compare` / `analyze`）都把缓存写到各自的独立目录，
**绝不碰 `~/.craftgraph/cache`**。这不是洁癖：它们曾经污染过真实缓存，
后果是之后游戏没开时做离线查询，会把合成数据当成真数据返回，而且报告看起来完全正常。

## 跨语言契约验证怎么做的

两侧一直是各自对着假数据开发的：TypeScript 侧用手写的假 Bridge，Java 侧用自己的内存数据。
**它们从没真正对过话。** 两边都照着 `protocol.md` 写，但契约本身没被跑通过 ——
字段名差一个字母、`null` 处理不一致，都要等进游戏同时调两个系统时才发现，而且极难定位。

做法：

1. `ContractDumpTest` 把 Java 侧**真实的 HTTP 响应体**写到 `shared/fixtures/bridge-dump/`，
   覆盖易错形态：`null` 字段、概率产出、opaque 配方、流体、中文显示名
2. `npm run contract` 用这些样本回放出一个 Bridge，让整条链路
   （HTTP 客户端 → 索引 → 配方树 → 产线 → 报告）跑在**真实字节**上，
   并断言两侧对同一份数据得出一致的结论

它顺带是「金标准样本」：那批 JSON 就是 Java 实际会发出的东西，可以直接拿来看。

## 真实数据暴露的问题

第一次在真实 Minecraft 里跑通之后（1290 条配方、opaque 3%、主线程抽取 37ms；
第 6 条修完之后是 opaque 2%、抽取 40ms 上下），
`live` 抓到几个假数据发现不了的问题。记在这里，因为它们都是**只有真实数据才会触发**的类型。

**关于那个抽取耗时**：它在几次会话里量到过 27 / 37 / 53 ms，都是「本次会话第一次抽取」，
JIT 还没热。所以别把单个数字当性能指标看，当 30~55ms 这个带宽看。
它是大整合包上最该盯的数字（5 万条线性外推约 1~4 秒，远超 200ms 的预算——
但**那是外推不是实测**，得等真数据）。

### 一、原版有序合成导致重复子树（还算错了数量）

原版把「3 个铁锭」表示成 **3 个各含 1 个铁锭的槽位**，于是铁镐的配方树里出现三棵
一模一样的「铁锭」分支。这不只是多花两三倍 token —— **它还算错了数量**：
每个槽位各自向上取整，1 个火把需要的基础原料被算成 3 个原木，而正确答案是 1 个
（1 原木 → 4 木板 → 4 木棍 → 4 火把）。

修法是在**递归前按物品合并槽位**，不是事后去重 —— 合并后只取整一次，数量才准。
见 `mcp-server/src/resolution.ts` 的 `mergeInputs`。

### 二、标签成员挑选反直觉

原来按字典序挑，实测挑出 `charcoal`（煤炭标签里 `"charcoal" < "coal"`）、
`acacia_planks`（橡木标签里 acacia 字母序最前）。

改成**优先短 id**：模组给基础物品加前缀后缀来造变体（橡木→金合欢木、煤炭→木炭），
所以基础物品的 id 通常最短。三个案例因此全部改对。见 `pickCanonical`。

### 三、`mcVersion` 报成了 NeoForge 版本

`Minecraft.getInstance().getLaunchedVersion()` 返回的是启动参数里的 `--version`，
而开发环境（`runClient`）下那个值是 NeoForge 的版本号，不是 Minecraft 版本。
改用 `SharedConstants.getCurrentVersion().getName()`。

### 四、读不懂的配方里混着一个我们自己的 bug

`inspect` 一度显示 29 条 opaque，其中 18 条是盔甲纹饰锻造。读反编译源码后发现是两个原因：

- **输入读不到 —— Minecraft 的真实限制。** `SmithingRecipe` 不覆盖 `getIngredients()`，
  继承的默认实现返回空列表，槽位信息藏在 `isTemplateIngredient` 这类谓词里。
- **产出读不到 —— 我们的 bug。** `SmithingTrimRecipe.getResultItem` 明明会返回正常产物，
  但它要查 `Registries.TRIM_PATTERN`，而我们传了 `null` → NPE → 被 `catch (Throwable)` 吞掉。

**修复时差点踩进一个陷阱**：如果只把 `null` 换成真实注册表，锻造配方会变成
「有产出、无输入、`opaque: false`」——也就是看起来像「纹饰不需要任何材料」。
那正是 `opaque` 设计要防的静默错误答案。所以判定规则同时覆盖两头，
抽成了独立的 `Readability` 类并单独测试。

顺带堵上了「静默吞异常」：`getResultItem` 的失败现在会计数并打 WARN 日志。
**这个 bug 藏这么久，就是因为异常被无声吞掉了。**

### 五、字段全是 null，而测试全绿（最贵的一个）

这一条是四个里最贵的，因为它不是「某个边界情况没处理」，而是**一整块功能从来没生效过，
而所有测试都说没问题**。

现象：真实数据 1290 条配方的 `duration` / `machine` / `energy` 全是 `null`，
于是 `calculate_production_plan` 每个环节都报「手工」，机器数计算完全失效。

为什么没被发现：

| 层 | 数据来源 | 为什么没抓到 |
|---|---|---|
| `smoke` / JUnit | 手写夹具 | **夹具里手写了 duration**，算法当然是对的 |
| `e2e` | 自建假 Bridge | 假 Bridge 也是手写的，同样有 duration |
| `contract` | Java 导出的样本 | 样本是直接构造的 DTO，不经过抽取 |
| `live` | 真游戏 | **当时没有断言这两个字段** |

四个层全绿，而功能是死的。根因是两个：

1. **代码里写着 `null`，注释说「原版 API 拿不到，接 EMI 后补」。** 这是错的 ——
   核实反编译源码后，`AbstractCookingRecipe#getCookingTime()` 给耗时，
   `Recipe#getToastSymbol()` 给机器，两个都是原版接口。
   **「等一个未来依赖」是最容易让字段停在 null 的理由**，而 null 会一路静默传进产线结果。
2. **没有一层断言过「真数据里这个字段有没有值」。** 测的都是「拿到值之后算得对不对」，
   而没测「值本身拿不拿得到」。

修法分两步，缺一不可：

- **补上读取逻辑**：`extract` 包里的适配器层（`RecipeAdapters` / `CookingAdapter` /
  `MachineTable`）。判据用 `instanceof AbstractCookingRecipe` 而不是类型 id 白名单 ——
  模组复用原版烹饪序列化器时类型 id 是它自己的，类没变，按白名单写会漏掉它们，
  而漏掉的症状正好是「duration 又是 null」。
- **把「拿不到」变成可观测的**：`FieldCoverage` 在每次抽取时统计各字段覆盖度，
  由 `ClientBridge` 打进日志。里面有一条**与实现无关**的判据：
  `minecraft:smelting` 这类类型的耗时是平台保证有的，它们「有条目却没读到」就一定是解析坏了。
  它不复用适配器的逻辑（拿实现给自己打分等于没测），写死的是 Minecraft 的事实。
  这样在用户的整合包里也能发现适配器失效，而不只是在我们测过的数据上。

**教训写成规则**：**任何下游要用的字段，`live` 层都必须断言。**
光断言「算得对」不够，还要断言「读得到」——`live.ts` 里现在有两类断言（聚合覆盖度 + 逐条核对字段值），
其中「`crafting` 的耗时必须是 `null`」是反向断言，用来防「给所有配方编一个耗时」这种假修复。

### 六、修一半比不修更糟：锻造的占位产物

第五条的修复给「按类型读字段」这层适配器留了位置，第六条就是往里加第一个真适配器时
踩到的坑 —— 它跟第四条那个锻造陷阱是同一个形状，只是这次在写代码之前就看出来了。

背景：27 条锻造配方读不到输入（`SmithingRecipe` 不覆写 `getIngredients()`），
占当时 `opaque` 的一大半。核实下来的读法有两种：

- **遍历物品注册表去测那三个谓词**（`isTemplateIngredient` 等）——
  不需要任何特权，但只能得到**物品集合**，会丢掉 `#minecraft:trimmable_armor`
  这种标签身份，而标签正是协议要保留的东西。
- **访问转换器**把三个包私有字段变公开 —— JEI 做的就是这件事。
  拿到的是真正的 `Ingredient`，`IngredientNormalizer` 一行都不用改。

选了后者。但**只补输入会制造一个静默的错误答案**：

```java
// SmithingTrimRecipe.getResultItem()
ItemStack itemstack = new ItemStack(Items.IRON_CHESTPLATE);   // 硬编码，永远是这个
// 再挂上第一个纹饰图案 + 红石
```

18 条纹饰配方的产物一律报成「铁胸甲」。修复前它被 `opaque = true` 挡着所以无害；
**输入一旦非空，`Readability` 就不再加这个标记，那个占位符便从「被标记的假数据」
升级成「一个理直气壮的答案」。**

结果是一个只有跑真游戏才会暴露的错：单元测试看不到 Minecraft，
编译通过、测试全绿、jar 也打得出来，而 AI 会告诉玩家「饰纹的产物是铁胸甲」。

所以适配器接口刻意用 **`null` 与空列表**表达两件不同的事：
`null` = 「这个字段不归我管，用通用接口的结果」，空列表 = 「我确定这里读不到」。
纹饰返回空列表，于是它保持 `opaque`，但 `Readability.reason` 从
「输入和产出都读不到」变成「读不到产出」—— 这个区别对 AI 是有意义的。

修复后的实际变化：

| | 修复前 | 修复后 |
|---|---|---|
| opaque 总数 | 40（27 锻造 + 13 特殊合成） | **31**（18 纹饰 + 13 特殊合成） |
| 9 条下界合金升级 | 输入产出都读不到 | **完全可读**（模板 + 钻石工具 + 下界合金产出） |
| 18 条纹饰 | 输入 0 槽位，产出「铁胸甲」 | 输入 3 槽位（含标签），产出**明确为空** |

`live` 层现在有两类锻造断言：一类证明读到了（按模板物品能查到配方 —— 修复前是 0 条，
因为倒排索引里根本没有它们），另一类证明**没读错**（产出里不许出现 `iron_chestplate`）。

### 共同点

六个都不是逻辑错误，而是**「我对真实数据的假设错了」**。假数据是按我的理解造的，
所以测不出我的理解有偏差 —— 这就是为什么必须有 `live` 这一层。

第 5 条还多一层：它暴露的是**「测试覆盖了算法，没覆盖数据来源」**。
夹具能验证「给定 duration 算得对不对」，永远验证不了「duration 拿不拿得到」。

## token 开销

MCP 工具的返回值会进模型上下文，所以**输出大小就是成本**。有两套测量：
`measure` 用 1 万条配方的合成数据（压规模），`live` 用真实游戏（看实际）。

真实数据（近原版 1290 条配方）上的一次体检：

| 项目 | tokens |
|---|---:|
| `get_bridge_status`（含字段覆盖度） | 209 |
| `search_items`（中文搜索） | 25 |
| `list_recipe_types` | 131 |
| `build_recipe_tree`（火把 / 铁镐 / 金苹果） | 433 / 444 / 289 |
| `calculate_production_plan`（每分钟 60 火把） | 568 |
| `calculate_production_plan`（每分钟 60 玻璃） | 256 |
| `expand_tag`（`#minecraft:planks`） | 283 |
| `get_recipes_for_input`（铁锭能做什么） | 833 |
| 工具定义（固定开销，每次会话） | 2,598 |

配方树在真实数据上是 **300~450 tokens** 量级 —— 比合成深链小一个数量级。
真实查询本来就浅，加上默认只展示三层，多数情况下碰不到成本上限。

补上 `duration` 之后火把产线从 522 涨到 568 tokens（+9%）：机器清单里从此有真的
「2 台高炉」，而不再是空的。**这类增长是必要的**——以前那份更便宜的答案是错的。

合成数据下的优化前后对比（318 节点的深链）：

| 项目 | 优化前 | 现在 | 变化 |
|---|---:|---:|---:|
| 配方树（完全展开） | 15,585 | 1,855 | **-88%** |
| 产线规划 | 20,304 | 6,832 | **-66%** |
| 典型会话 | 16,861 | 3,131 | **-81%** |
| 最坏会话 | 37,350 | 10,148 | **-73%** |

两项关键设计：

- **分层返回**：配方树默认只展开到第 3 层，更深的分支用「⋯」标出节点数和原料，
  但**基础原料永远是全树算出来的**。agent 既能拿到完整答案，又不用为几百个节点付 token。
  这是贡献最大的一项，远超格式改动。
- **`format` 三选一**：`markdown`（默认）、`tsv`（列式，省 17~26%）、`json`（最贵但结构完整）

格式选型的实测结论见 [`format-evaluation.md`](format-evaluation.md)。
核心发现：**字典编码的收益在每次调用都要重发字典时会完全蒸发**；真正值钱的是「少返回」。

**改渲染逻辑后跑一遍 `measure` 就知道有没有变贵。** 加输出前先量一下。

## 踩过的坑

### Gradle 按 ISO-8859-1 读 `gradle.properties`

那里写非 ASCII 字符会变成乱码，而且这个值会被展开进 `neoforge.mods.toml` ——
症状是**游戏里的 Mod 描述显示成一堆 `æè¿è¡ä¸ç Minecraft...`**。

所以中文描述写在 `mod/src/main/templates/META-INF/neoforge.mods.toml`（UTF-8），
`generateModMetadata` 里显式设了 `filteringCharset = 'UTF-8'`。

### 行尾：`.gitattributes` 统一为 LF

仓库里原本混着 CRLF（Windows 上写出来的）和 LF（工具生成的），
`mod/build.gradle` 甚至文件内部就是混的。后果是任何跨平台编辑都会让整个文件显示为已修改，
几百行的假 diff 会把真正的改动埋掉。

现在仓库内统一按 LF 存储，两个例外：`*.bat` 检出为 CRLF（某些 cmd.exe 解析 LF 结尾会出错），
`gradlew` / `*.sh` 锁定 LF（CRLF 会让 Linux/macOS 报 `bad interpreter: /bin/sh^M`，
而报错完全不提行尾）。

### 发布凭据守卫放在 `doFirst` 里

放在配置阶段的话，没设环境变量连 `./gradlew build` 都跑不起来 ——
而日常构建根本不需要发布凭据。另外凭据用空串兜底而不是 `null`：
`null` 会让 Gradle 在任务配置校验阶段就失败，报的是
`credentials.username doesn't have a configured value`，**不会告诉你要设哪两个环境变量**。

### 日志文案必须是 ASCII —— 否则只有我们的行是乱码

在中文 Windows 上，Minecraft 把日志文件按 **GBK** 写出去。证据是日志自带的中文日期
（`0xD4 0xC2` = 「月」的 GBK 字节）。而整份日志里**只有我们这几行含中文** ——
别的行都是 ASCII —— 于是任何按 UTF-8 打开的查看器里，**只有 CraftGraph 的行是乱码**，
其余看着都正常。用户报的就是这个：「craftgraph 输出是乱码」。

实测那一份日志里，「快照已重建：15241 条配方…主线程抽取 155 ms」在 UTF-8 查看器里完全读不出来。
**一份读不出来的日志等于没有日志** —— 而这几行恰恰是诊断性能与覆盖度的唯一入口。

所以：**日志英文，注释中文，HTTP 错误消息中文。**

| 内容 | 语言 | 为什么 |
|---|---|---|
| `LOGGER.*` 文案 | **ASCII 英文** | 会被写成 GBK，UTF-8 工具读成乱码 |
| 代码注释 / javadoc | 中文 | 不进日志，中文信息量更大 |
| HTTP 错误消息（`error(...)`） | 中文 | 走 JSON 且响应头声明 `charset=utf-8`，是给 AI 读的，没有编码问题 |
| 游戏内 Mod 描述 | 中文 | 走 UTF-8 的 `neoforge.mods.toml` |

`LogEncodingTest` 把这条规则钉住了：它扫描 `src/main/java` 下所有 `LOGGER.*` 语句
（跨行拼接也算）断言都是 ASCII，并且**先断言至少扫到 15 条**——
否则「一条都没扫到」也会全绿，而那种绿色代表什么都没检查。
它刻意**不**检查「源码里不许有中文」，那会误伤注释和 HTTP 错误消息。

### 性能：外推错了，实测才好

第五条里那个抽取耗时，我按近原版的 27~53ms / 1290 条线性外推，算出 5 万条约 1~4 秒、
15k 约 320~625ms。**实测 15,241 条只要 155 ms。**

原因大概是配方构成不同：原版 1290 条里绝大多数是 N×N 有序合成，每个槽位都要展开
`getItems()`；而这个 Create 包里机器类配方多，槽位明显更简单。
所以**每条配方的成本不是常数**，拿「原版包的每条成本」去外推别的包会偏悲观。

教训跟第二条一样，只是这次外推的是性能：**别用另一份数据的斜率去预测这一份。**

### 真实大包上的 AI 使用：38 次往返，而根因是一类配方读不到

拿 v0.1.1 在一个 Create 专精包里问「Chocolate Candy 怎么合成、产线怎么设计」，
一次提问用了 **38 次工具调用**（近原版同类问题 2~5 次），其中约 30 次花在绕过**一个**断点上。

根因不是「Create 读不懂」，而是**「只产出流体的配方一律读不懂」**：

```java
// ProcessingRecipe.getResultItem()
return getRollableResults().isEmpty() ? ItemStack.EMPTY : ...;
```

`create:mixing` 产出的是流体，物品 `results` 为空 → 返回 EMPTY → 我们的 `outputCount = 0`
→ 判 opaque。Create 自己的代码里甚至专门特判了这个组合
（`getRollableResults().isEmpty() && !getFluidResults().isEmpty()`）。

数据上的分布：这个包 632 条 opaque 里，Create 系占 388 条（其中无产出 267、无输入 146）。
64% 的 opaque 集中在同一类原因上 —— 所以「opaque 4%」这个数字本身不告诉你该修什么，
**按类型拆开才知道**，这也是 `npm run inspect` 存在的意义。

两个副产品：

- **分页默认值在大包里不够用。** 实测「糖能做什么」有 **294 条**，默认只回 30 条（10%），
  而模型**没有**追加请求就直接下了结论。所以列表截断时必须写出「还有多少、怎么拿」，
  光陈述「显示前 30 条」不够。至于把默认值调大：实测默认 30→50 会让最大那次调用
  多花 70% token（1,069 → 1,813），却仍然解决不了 294 那种规模 —— 所以默认值没动，
  改的是提示文案。
- **`get_recipe_details` 只支持单条**，导致「先列表再逐条详情」变成 1+N 次往返
  （那一例里是 12 + 12）。加了 `recipeIds` 批量入参。



### 软依赖模组的适配器：静态注册会把自己炸掉

`CreateAdapter` 的代码里直接引用 Create 的类（`ProcessingRecipe` 等）。
最自然的写法是把适配器放进一个静态列表：

```java
private static final List<RecipeTypeAdapter> ADAPTERS = List.of(
        new CookingAdapter(), new SmithingAdapter(), new CreateAdapter());   // ❌
```

**这行会让没装 Create 的实例直接崩。** 加载 `RecipeAdapters` 会连带解析列表里的所有元素，
于是 JVM 去找 `CreateAdapter` → 它引用 Create 的类 → `NoClassDefFoundError`。
而且崩的时机是**构建快照时**，也就是整个桥接都不可用 —— 玩家看到的是「装了 CraftGraph 但什么都查不到」。

正确写法是懒建 + 先判断 + `new` 留在 lambda 里：

```java
private static List<RecipeTypeAdapter> adapters;   // 懒建

if (ModList.get().isLoaded("create")) {
    try { list.add(() -> new CreateAdapter()); }   // JVM 按指令惰性解析类
    catch (Throwable t) { LOGGER.warn(...); }       // 类加载失败是 Error，不是 Exception
}
```

两个细节都不能省：

- **`new` 必须在 lambda / 分支里。** 直接写 `list.add(new CreateAdapter())` 也不行 ——
  那条 `new` 指令在方法被调用时就会解析。放进 lambda 才把解析推迟到「装了才执行」。
- **`catch (Throwable)` 而不是 `Exception`。** `NoClassDefFoundError` 是 `Error`。
  兜住它，后果就只是「少一个适配器」，而不是「整个快照建不出来」。

实测确认：原版实例（没装 Create）启动后，1290 条配方照常索引，
日志里 `NoClassDefFoundError` / `ClassNotFoundException` / `CreateAdapter` 出现 0 次。

源码里引用模组 API 用 `compileOnly`（`transitive = false`）。**它的价值是让编译器
逐条核对我们的调用与真实 API 一致** —— 方法名、参数、返回类型全被检查。
用反射就完全没有这层保障，连方法名拼错都要等运行时才发现。

### 测试脚本的缓存目录必须隔离

见上面「测量脚本的缓存隔离」。这条是花了代价学到的。

### 选路启发式：改之前先用真实包的缓存量一遍前后

真实大包暴露的另一个问题不在解析，在**选路**。All of Create 里：

```
「金苹果怎么做」→ 选了 createdieselgenerators:basin_fermenting（发酵），
                  原料表里出现 250 mB create:potion
「铁镐怎么做」  → 选了 create:crushing/iron_horse_armor（粉碎铁马铠出铁锭）
```

机制在 `scoreRecipe`：`score = 10 × 输入槽位数 − min(产出量, 9)`，同分按 id 字母序。
两个原因叠在一起：

- 原版有序合成把「8 个金锭 + 1 个苹果」表示成 **9 个槽位** —— 那是 3×3 网格的产物，
  不是复杂度。于是任何 2 输入的机器配方都碾压它（90 vs 20）。
- 启发式**没有任何「这条路合不合理」的概念**：铁马铠只能靠搜刮拿到，
  但它「无产出配方」这个身份和铁矿石在数据上**完全一样**。

修法（已做）：输入改用 **`mergeInputs` 合并后的槽位数**，和递归时用同一个定义。
金苹果那类立刻修好 —— 实测在同一个包上：

| 目标 | 改前 | 改后 |
|---|---|---|
| `minecraft:golden_apple` | `createdieselgenerators:basin_fermenting/...` | **`minecraft:golden_apple`** |
| `minecraft:iron_ingot` | `create:crushing/iron_horse_armor` | 不变 |

**铁那条数据上分不开**，两个候选结构完全相同（都是 1 个具体物品输入、都被 3 条配方消耗、
输入都无产出配方），差别只是「铁马铠是搜刮品、铁矿石是挖的」—— 那是世界知识，工具没有。

## 试过但**不成立**的两条启发式（记下来免得再试一遍）

这两条都是我先想出来、再拿真实数据量、结果被推翻的。**先量再改**在这里省下了两次错改：

| 候选规则 | 为什么不成立 |
|---|---|
| 偏好「输入是标签」的配方 | 实测这个包里 `smelting_iron_ore` 的输入是 **`minecraft:iron_ore` 具体物品**（不是我以为的 `#c:ores/iron`），而金苹果那两个候选**都**用标签 → 两例都区分不了 |
| 偏好「输入被很多配方消耗」的批量资源 | `minecraft:iron_ore` 被 **3** 条消耗，`minecraft:iron_horse_armor` 也被 **3** 条 —— 一模一样 |

顺带一个**可复用的手法**：把一个真实包的快照留在 `~/.craftgraph/cache/`，就能**离线**跑
解析器改动的前后对比（起一个临时脚本走 `RecipeStore.load` 即可，不需要游戏在运行，
也不需要装任何模组）。上面那两张表就是这么量出来的 —— 而且它是**在用户的真实数据上**，
不是夹具。

### 「产出为空」有三种形状，混成一种就会说错话

All of Create 剩下 189 条 opaque，把它们按形状拆开才发现「opaque 1%」这个数字本身
不说明任何事 —— 里面混着三种完全不同的东西：

| 形状 | 例子 | 条数 | 该怎么说 |
|---|---|---|---|
| 输入读到了，**产出确实是空的** | `createaddition:liquid_burning`、`petrochem:*_fuel`（燃料定义） | 17~26 | 「这条配方不产出物品」—— 我们读清楚了 |
| 输入读到了，**产出无法用单一物品表达** | 盔甲纹饰（组合式产出） | 37 | 「产出读不懂」—— 诚实的读不懂 |
| 输入产出都没有 | `justenoughbreeding:*`（繁殖/驯服）、`create_mob_spawners:spawning` | 80+ | 「这个类型不是物品配方」 |

第一类原先被标成「读不到产出」，而 `petrochem:*_fuel` 的**耗时和输入都读到了**
（耗时是我的 Create 适配器给的 —— 这反过来证明它确实是 `ProcessingRecipe`），
所以「读不懂」是错的说法。修法是让**适配器作证**：读过该类型自己的产出字段、
确认是空的，才声明 `producesNothing`。

关键在**作证的资格**：只有真读过产出字段的适配器才能说「它不产出」。
没有作证的「空产出」一律仍按 opaque —— 把没读到的产出说成「不存在」，
比说「读不懂」危险得多。

顺带：纹饰那 37 条形状上和第一类**完全一样**（输入读到了、产出为空），
它们没被误判，只是因为它们不是 `ProcessingRecipe`、适配器不会为它们作证。
这说明「按类作证」这个设计是对的 —— 如果按形状猜，37 条纹饰会被说成「不产出物品」，
而那等于告诉玩家饰纹不需要产出。

**离线能验到什么程度**：从缓存快照能算出「形状像不产出」的有 26 条，但其中
`create_mob_spawners:spawning`（9 条）是不是 `ProcessingRecipe` 离线分不出来
（它的 0 输入 0 输出既可能是自定义 Recipe，也可能是空声明的 ProcessingRecipe）。
所以预计 17~26 条会变，**确切数字要等新 jar 跑一遍**。
