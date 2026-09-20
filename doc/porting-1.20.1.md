# 1.20.1 版本：调查结论与方案

**状态：构建已完成（第 0~4 步），差真机验收与发布（第 5~6 步）。**
`cd mod-1.20.1 && ./gradlew build` 能产出可发布的 jar
（`build/libs/craftgraph-<版本>-mc1.20.1.jar`，已 reobfuscate）。
**但它在真游戏里还没跑过** —— 编译通过只证明 API 对得上，
证明不了 AT 在运行时生效、也证明不了事件触发时机。见第 5 节。

写在前面：这份文档存在的原因是**这些东西重新查一遍很贵**。每一条都标了是
核实过的（给了来源）还是推断的，请按同样的标准使用。

---

## 1. 先纠正一个错误结论

本轮讨论开始时（以及我上一轮的建议里）把目标定成了
`net.neoforged:forge:1.20.1-47.1.106`。**那是错的，目标是 MinecraftForge 那条线。**

1.20.1 上同时活着两条 loader 线，我原先只看了一条：

| 坐标 | 最新版 | 是什么 |
|---|---|---|
| `net.minecraftforge:forge` | `1.20.1-47.4.23`（Recommended 是 47.4.10） | MinecraftForge 本家，fork 之后**继续开发**到 47.4.x |
| `net.neoforged:forge` | `1.20.1-47.1.106`（共 63 个版本） | NeoForge 在 47.1.106 处 fork 出来的那条，之后停在这里 |

选 Forge 线的证据（均已核实来源）：

- **ModDevGradle 的 legacy 插件就是这条**。插件 id `net.neoforged.moddev.legacyforge`，
  `LEGACY.md` 里 1.20.1 的示例是 `legacyForge { version = "1.20.1-47.3.0" }`，
  它的测试工程用 `1.20.1-47.3.12` + `modCompileOnly('mezz.jei:jei-1.20.1-forge:15.17.0.76')`；
  插件源码里是 `groupId = forgeVersion != null ? "net.minecraftforge" : "net.neoforged"`。
  → 文档路径上的 1.20.1 就是 MinecraftForge。
- **NeoForge 自己的态度**。2023 年度回顾里写着 "we neglected 1.20.1"、
  "Development efforts on 1.20.1 are better spent on Forge rather than NeoForge"。
- **包名**：NeoForge 20.1（1.20.1）仍然用 `net.minecraftforge.*`，改用 `net.neoforged.*`
  是 20.2 才做的（"All the packages have been changed to net.neoforged"）。
  所以两边都接受 `net.minecraftforge` 的 mod。
- **整合包实际在用哪条**：Modrinth 上 1.20.1 的整合包，`categories:forge` 2990 个
  对 `categories:neoforge` 263 个，差一个数量级。（2026-09-20 拉的数，
  CurseForge 没量到，那上面 1.20.1 大包更多。）

**推断（无官方明文，别当事实）**：因为包名、`modLoader="javafml"`、连 NeoForge 20.1 的
`modId` 都还是 `forge`，一个按 47.x 编译的 jar 结构上两个 loader 都能加载。
真正的风险不是 fork 本身，而是**版本漂移** —— 用 47.3/47.4 新增的 API 就会在
NeoForge 20.1 上找不到。

**决定（Luzzi，2026-09-20）：编译目标钉 `net.minecraftforge:forge:1.20.1-47.2.0`，
兼容 47.2.0 以上。** 这是**在最低版本上编译**，比「编译最新版、祈祷旧版能跑」严格更好：
编译器会直接拦下任何 47.2.0 之后才有的 API，漂移风险从「靠自觉」变成「编不过」。
`loaderVersion` 相应写 `[47.2.0,)`。（47.2.x 线最高 47.2.36；47.3/47.4 是它之后的版本。）

---

## 2. 这个 port 到底要改多少（用官方映射逐条核对过）

核对手段：Mojang 官方的 1.20.1 client mappings（`piston-data.mojang.com` 上的 `client.txt`）。
它给出 1.20.1 里**存在哪些类、哪些方法、签名是什么** —— 比凭记忆猜可靠。

边界（用同一套算法量的：去掉空行与注释）：**不碰 MC 的 16 个文件 1125 行（54%）**，
**碰 MC 的 10 个文件 952 行（46%）** —— 移植的工作量全在这 952 行里。

> 更正：这里原先写的是「2089 行 / 53%」，那是我早先一次不同口径的统计，复现不出来。
> 上面的数字来自 `git ls-tree` + 逐文件数（脚本可重跑），并且顺便验证了第 0 步是纯移动 ——
> 抽取前后这两组数字一模一样。

### 2.1 好消息（原本担心、结果不用怕）

| 我们依赖的 | 1.20.1 情况 | 影响 |
|---|---|---|
| `Recipe#getToastSymbol()` | **在** | `MachineTable` 那套两层的机器推断原样可搬，不用换数据源 |
| `Recipe#getResultItem(RegistryAccess)` | **在**，只是参数类型是 `RegistryAccess` 而不是 `HolderLookup.Provider` | 「必须传真注册表、不能传 null」那个修复照旧有效 |
| `Recipe#getId()` | **在** | 配方 id 拿得到 |
| `HolderSet.Named` / `Registry#getTagNames()` / `getTag(TagKey)` | 都在 | 标签枚举能照做 |

还有一条很省事的：**Create 6.0.x 有 1.20.1 的版本**，maven 上是
`com.simibubi.create:create-1.20.1`（271 个版本，最新 `6.0.8-291`）。
所以 1.20.1 的包里 Create 不一定是我原先假设的 0.5.1.f ——
6.x 的 `ProcessingRecipe` 形状（`getProcessingDuration()`、`getRollableResults()`、
`getOutputChance()` 是权重、`getFluidResults()`）和 1.21.1 上我们编译验证过的同一套，
`SequencedAssemblyRecipe` 也仍然直接 `implements Recipe` 并带 `loops`。
**这意味着 `CreateAdapter` / `SequencedAssemblyAdapter` 的逻辑可以照搬，只有访问器的类型名不同。**

### 2.2 机械改动（包名 + 入口，编译器会全列出来）

| 文件 | 改什么 |
|---|---|
| `CraftGraph.java` | `net.neoforged.*` → `net.minecraftforge.*`；**构造函数换成无参**，事件总线用 `FMLJavaModLoadingContext.get().getModEventBus()` |
| `client/ClientBridge.java` | `NeoForge.EVENT_BUS` → `MinecraftForge.EVENT_BUS`；`NeoForgeVersion` → `ForgeVersion`；事件类改到 `net.minecraftforge.client.event.*` |
| `bridge/DiscoveryFile.java` | 一行 import（`FMLPaths`） |
| `extract/RecipeAdapters.java` | 一行 import（`ModList`） |

入口那条是**真的不一样，不是改包名就完**：NeoForge 21.1 的
`(IEventBus, ModContainer)` 里那个 `ModContainer` 参数是 NeoForge 后期才有的；
1.20.1 的写法是无参构造函数（JEI 1.20.1 和 Create 1.20.1 都是无参的，已看源码确认）。

### 2.3 真正的 API 差异

| 位置 | 1.21.1 | 1.20.1 |
|---|---|---|
| `RecipeExtractor.java:144` | `manager.getRecipes()` → `Collection<RecipeHolder<?>>` | → `Collection<Recipe<?>>`，**`RecipeHolder` 类根本不存在** |
| `RecipeExtractor.java:127` | `registries.lookupOrThrow(k)` + `listTags()` | `registryAccess.registryOrThrow(k)` + `getTagNames()` + `getTag(k)` |
| `RecipeExtractor.java:87` | `HolderLookup.Provider` | `RegistryAccess` |
| `RecipeExtractor.java:192` | `SizedFluidIngredient` | **不存在，见下节** |
| `CreateAdapter.java:74` | `ProcessingRecipe<?, ?>`（两个类型参数） | `ProcessingRecipe<T extends Container>`（**一个**） |

配方 id 的取法要留意：1.20.1 里 `RecipeManager` 有 `byKey(id)` 和 `getRecipeIds()`，
而 `Recipe#getId()` 也在。**我倾向前者**（id 来自 map 的 key，而不是实例字段）——
因为 1.20.1 专门加了 `byKey` 这件事本身就说明「实例上的 id 不可靠」。
这条是**推断**（我只核实了两个方法都存在，没核实「getId 不可靠」的官方说法），
按仓库的硬规则 2，落地时要有一条 `live` 断言来定案。

### 2.4 结构性差异：流体（这是唯一一处需要重新设计的）

**Forge 1.20.1 根本没有 `net.minecraftforge.fluids.crafting` 这个包**
（看过 1.20.x 分支的目录：只有 `FluidStack`、`FluidType`、`FluidUtil`、`capability/` 等，
没有 `FluidIngredient`，也没有 `crafting/` 子包）。
`SizedFluidIngredient` 是 NeoForge 1.20.4 以后才有的东西。

而 1.20.1 的 Create 用的**是它自己的**流体原料类型：
`ProcessingRecipe#getFluidIngredients()` 返回 `NonNullList<com.simibubi.create.foundation.fluid.FluidIngredient>`
（看 mc1.20.1/dev 分支源码确认）。

后果：`RecipeExtractor` 里那段「通用流体输入」在 1.20.1 上**没有对应物**，
流体输入只能从适配器来。这不是改包名能解决的，是接口形状的问题 —— 见下一节。

---

## 3. 结构：三条路，以及我推荐哪条

| 方案 | 共享的是什么 | 代价 |
|---|---|---|
| **A. 薄核心** | 那 1125 行 MC-free 代码；`extract/` 整个按版本各一份 | 重复约 950 行，其中约 470 行是适配器/抽取逻辑，会各自漂移 |
| **A+. 薄核心 + 纯函数上提** | A，外加把适配器里**已经纯的**部分（`ResultChance` 已经是）提到共享 | 几乎不额外花时间，但只捞回一小部分 |
| **B. 适配器只说自己的类型** | `extract/` 里除「读 MC 对象」以外的全部（断言、概率分类、池子归一、过渡物品排除） | 要先改 `RecipeTypeAdapter` 的签名，让它收/发我们自己的 DTO 而不是 `ItemStack`/`FluidStack`/`SizedFluidIngredient`；共享比例升到大约八成，并且**顺手把流体那个洞补上** |

**决定（Luzzi，2026-09-20）：走 B，因为这条线打算长期维护。** 在 A 与 B 之间犹豫的理由
本来就只剩「不知道会不会长期维护」—— 那一条定了，B 就不是重构，而是省事。
两条 `extract/` 分开越久越难合，现在合最便宜。

### 第 0 步已落地（2026-09-20）

1125 行 MC-free 代码已经抽成 `core/`，它是**独立的 Gradle 工程**，由 `mod/settings.gradle`
include 进来、`mod` 用 `srcDir` 把它的源码编进自己的 jar。

做这一步时踩到一个值得记的坑：**ModDevGradle 会自己配置 `jar` 任务**，
写 `jar { from project(':core').sourceSets.main.output }` 是**静默无效**的 ——
jar 里一个核心 class 都没有（而 sources jar 反倒有，因为它走的是另一条路径）。
实测确认后改成 `srcDir`，绕开与插件争配置权。这正是本项目最警惕的形状：
**配了、但没生效，而且没人报错。**

「不含 MC」现在由编译器守：往 `core/` 里放一个 `import net.minecraft.world.item.ItemStack`
的文件，`:core:compileJava` 立刻失败（`程序包 net.minecraft.world.item 不存在`），
而同一个文件放在 `mod/` 里照样编得过 —— 两者差别只在类路径，与语法无关。

顺带得到的性质：`mod` 与 `core` 的测试计数分开了（core 11 个类 / 123 个用例，
mod 只剩 2 个类 / 4 个用例 —— 守访问转换器与日志文案的那两个），
而 `cd mod && ./gradlew test` 仍旧一条命令跑完两边（任务名会匹配子工程），
CI 那一步（`test --rerun --no-build-cache`）也因此不用改。

### 为什么不是 jarJar

MDG 确实支持 `jarJar project(':core')`，所以这是能做的。但它的**设计目的是第三方库的版本选择**：
文档里那段是在讲「同一个库被多个 mod 各带一份、甚至版本不同，JiJ 得挑一份」，
所以才要求你写 version range，还要求「嵌套 jar 的 Java 模块名全局唯一」。

拿它装我们自己的源码集，代价是 `dev.craftgraph.*` 会**跨两个 jar**（mod 的 jar 里是
`extract`/`client`，嵌套 jar 里是 `api`/`normalize`）—— 拆分包，生产环境的模块层要靠
package aggregation 兜住；而我们并不需要版本隔离：这是我们自己的代码，只有一个版本。

`srcDir` 得到的是同样的结果 —— 一个自包含的 jar（数过：54 个 class，两半都在），
没有嵌套 jar、没有第二个模块名、没有拆分包。**将来真要打包第三方库（比如 JEI 的 API）时
才该上 jarJar**，那是它擅长的场景。

### 第 1 步已落地（2026-09-20）

适配器接口搬进 `core` 并改成泛型 `RecipeTypeAdapter<T>`：参数是类型变量（1.21.1 填
`Recipe<?>`，1.20.1 也填 `Recipe<?>`），返回值只说 `RawSlot` 与 `Models` 里的协议类型。
配套新增：

| 新增 | 是什么 |
|---|---|
| `RawSlot` | 版本无关的槽位（kind + 数量 + 一包 id）。流体在 1.20.1 上根本没有 `SizedFluidIngredient`，所以中间表示必须是纯字符串 |
| `ExtractionContext` | 槽位归一化：按 kind 挑标签索引、表示不了就返回 null |
| `TickDuration` | 「0 不是瞬间完成」这条规则原本写在三个适配器里互相引用，现在只有一处 |
| `TransitionalItem` | 「全是中间产物才排除」的判据（用 id 比较，所以能住在 core 并单测） |
| `ItemIds`（在 mod） | **MC 对象 → 我们的记录，这是每个 MC 版本唯一必须自己写的东西** |

**行数没有变少**（碰 MC 的 952 → 955 行）：搬走的是**判断**，不是行数。
真正的收益是 1.20.1 那边要写的从「适配器逻辑」缩到「`ItemIds` + 各访问器调用」——
`SequencedAssemblyAdapter` 的权重池归一化、中间产物排除、耗时求和都不必再写第二遍。

实测：core 13 个类 / 135 个用例（比第 0 步后多 12 个），mod 2 个类 / 4 个用例，
四层（smoke/e2e/contract 与 Java 测试）全绿。

**顺带发现一处假数据**：产出池里的空栈原先会经 `toItemStack` 变成
`minecraft:air`（`Items.AIR` 在注册表里有 id，所以那个「取不到就写 unknown:unknown」
的兜底其实没兜住），现在直接跳过。这会在边缘情况下改变 opaque 的判定
（整个产出池都是空栈时，以前是「可读、产出是空气」，现在是「读不到产出」）——
方向是对的（硬规则 1：宁可说读不到，也不要编），而**它需要 `live` 层在真实包上复核**。

不管选哪条，**第 0 步都是同一件事**：把 1125 行 MC-free 代码抽成两边共享的源码集。
这件事本身有价值：它让「哪些是 MC 面」变成目录结构上的事实，而不是靠 grep 才知道。

---

## 4. 访问转换器要改成 SRG 名

1.20.1 的 AT 必须用 SRG 名（Forge 官方文档：
"When using Access Transformers on Minecraft classes, the SRG name must be used for fields and methods"）。
JEI 在 1.20.1 上做的正是这件事，它那份文件里的三行可以直接用：

```
public net.minecraft.world.item.crafting.SmithingTransformRecipe f_265888_ # base
public net.minecraft.world.item.crafting.SmithingTransformRecipe f_265907_ # addition
public net.minecraft.world.item.crafting.SmithingTransformRecipe f_265949_ # template
```

（`#` 在 AT 文件里是注释符，从来不是「官方名」的标记 —— 曾经有人这么以为。）

**这条要是写错，症状是「锻造又变回读不懂」**，而不是编译失败 ——
和 1.21.1 上那次一模一样的形状，所以 `live` 里那两条锻造探针必须保留。

另外 1.20.1 用 `META-INF/mods.toml`（不是 `neoforge.mods.toml`），`loaderVersion="[47,)"`。

---

## 5. 验证：哪些能照搬，哪些必须新加

好消息是**验证的投资几乎全部可以照搬**：

- `smoke` / `e2e` / `contract` 四层里，前三层根本不碰 MC，夹具和断言不用改；
- `npm run live` 是走 HTTP 说话的，**对哪个 MC 版本都成立** —— 它现成就是 1.20.1 的验收工具。

必须在 1.20.1 上**新加**的 `live` 断言（上面那些「核实了存在、没核实语义」的正是它们）：

1. 配方 id 非空且唯一 —— 用来定案 `byKey` vs `getId()` 那条推断；
2. 标签数量与 1.21.1 上量到的量级相当 —— `getTagNames()` 若给不全，
   标签会**静默退化成物品列表**（看起来精确、其实丢了「任意一种都行」的语义），
   这正是硬规则 1 说的那类静默错误；
3. 锻造两条探针（三个槽位互不相同 + slot 0 是模板）；
4. `duration` 的覆盖度 —— 烹饪那 4 类仍然要有耗时，`crafting` 仍然必须为 null（反向断言）。

---

## 6. 版本号与产物命名

两个 jar 共用同一个 `mod_version`（它们是一个产品的两半，
分成 `1.20.1-0.3.2` 这种只会让人对着两个数字猜）。文件名区分：

- `craftgraph-0.3.2.jar`（1.21.1，沿用原来的文件名形状）
- `craftgraph-0.3.2-mc1.20.1.jar`

Release workflow 现有的「tag 与 `mod_version` 一致」校验对两个 jar 都成立，不用改
（但要把新 jar 一并挂上去）。真正的 MC 版本信息在两份 `mods.toml` 里，那才是 loader 看的。

---

## 7. 决定与下一步

已定（Luzzi，2026-09-20）：

1. **编译目标**：`net.minecraftforge:forge:1.20.1-47.2.0`，兼容 47.2.0 以上（在最低版编译）。
2. **结构**：走 B（适配器只说自己的类型），因为打算长期维护。
3. **Create**：1.20.1 的包用 6.0.x，与 1.21.1 同一套 API 形状，适配器逻辑可照搬。

第 0 步（抽 `core/`）已完成，见上文。接下来按顺序：

| 步 | 做什么 | 怎么算完成 | 状态 |
|---|---|---|---|
| 1 | 改 `RecipeTypeAdapter` 的签名：收/发我们自己的记录，而不是 `ItemStack`/`FluidStack`/`SizedFluidIngredient` | `core` 里能编过（签名里不再出现 MC 类型），且四层测试全绿 | **已完成**，见上 |
| 2 | 把 `extract/` 里剩下的纯逻辑挪进 `core/`：序列组装的权重池归一化与 `sequence × loops` 展开 | 那些测试在 `:core:test` 里跑，不启动游戏 | **已完成** |
| 3 | 建 `mod-1.20.1/`：`legacyforge` + Forge 47.2.0 + Create 6.0.x；`mods.toml`、SRG 版 AT、无参构造函数、`MinecraftForge.EVENT_BUS` | `./gradlew build` 编过 | **已完成** |
| 4 | 写 1.20.1 的 MC 侧 shim（`ItemIds` 的流体类型、`RecipeExtractor` 的 id/tag/`RegistryAccess`） | 同上 | **已完成** |
| 5 | 进真游戏验收：`npm run live` + `npm run inspect`，并加上第 5 节那四条断言 | 覆盖度与 1.21.1 同量级；锻造与烹饪那几条断言通过 | 待做 |
| 6 | 发布：同一个 `mod_version`，文件名带 MC 版本；Release 挂两个 jar | tag 校验对两个 jar 都成立 | 待做 |

第 1、2 步都在**不新增任何 MC 代码**的情况下做，风险最低，而且做完之后 1.20.1 那一侧
要写的就只剩「读 MC 对象」那一点点 —— 这也是先做这两步的理由。


---

## 8. 落地记录（第 2~4 步实际发生了什么）

### 实际要改的 API 差异（编译器逐条列出来的）

比预想的少。真正需要动代码的只有四处：

| 位置 | 1.21.1 | 1.20.1 |
|---|---|---|
| 配方身份 | `manager.getRecipes()` → `Collection<RecipeHolder<?>>`，id 从 holder 取 | 没有 `RecipeHolder`：`getRecipeIds()` + `byKey(id)`，id 从 **map 的 key** 取 |
| 注册表访问 | `HolderLookup.Provider` + `listTags()` | `RegistryAccess` + `registryOrThrow` / `getTagNames()` / `getTag(tagKey)` |
| 流体原料 | NeoForge 的 `SizedFluidIngredient`（**总是存在**） | Forge 没有这个类；Create 用它自己的 `foundation.fluid.FluidIngredient`，所以拆 id 的活挪进了 `CreateFluids`（只被 Create 适配器引用，保住「没装 Create 不解析」） |
| 入口 | 构造函数收 `(IEventBus, ModContainer)` | 无参构造函数 + `FMLJavaModLoadingContext.get().getModEventBus()` |

`Recipe#getToastSymbol()`、`getResultItem(RegistryAccess)`、`BuiltInRegistries`、
`getIngredient()` / `getSequence()` / `getLoops()` / `resultPool` 这些**在 1.20.1 上都有**，
所以 `MachineTable`、`FieldCoverage`、两个 Create 适配器的主体逻辑一行没改。

`ProcessingRecipe` 在 1.20.1 上只有**一个**类型参数（`<?>` 而不是 `<?, ?>`）。

### 坑一：Create 在 1.20.1 上不发布无分类器的 jar

`com.simibubi.create:create-1.20.1:6.0.8-291` **解析不了** —— 那个版本目录下只有
`-all` / `-slim` / `-sources` / `-javadoc`，没有默认 jar。Gradle 的报错是
「cannot be resolved」，而 `compileClasspath` 里仍然列着它（只是没有 jar），
于是症状表现成一大片「程序包 com.simibubi.create… 不存在」，很容易往错的方向查。
正确写法是带分类器：`...:6.0.8-291:slim`（选 slim：模组自己的类，all 还捆了 Flywheel）。
**1.21.1 那条线反过来只有无分类器的 jar** —— 所以两个 build.gradle 在这点上不一样，不是笔误。

### 坑二：AT 只能对 Minecraft 的类，而且我差点据此设计错方案

一度以为 1.20.1 的 `SequencedAssemblyRecipe` 读不到（没有 `getSequence()`/`getIngredient()`），
就写了 AT 到 Create 的类上。构建期直接失败：

```
access-transformer:missing-target: The target ...SequencedAssemblyRecipe FIELD ingredient does not exist
```

两层原因：一是这个校验**只看 Minecraft 的类**，模组类不在它视野里；
二是**那个前提本身就是错的** —— 我那次 `grep` 用了 `head -30`，结论被截断了。
完整看一遍：`getIngredient()` 在第 278 行、`getSequence()` 在第 282 行，都是 public。

所以这一项与 1.21.1 一样走访问器，不需要任何特权。
**教训**：查「某个 API 存不存在」时别让 `head` 决定结论；有官方映射可查时优先查映射。

### 坑三：`mods.toml` 里两条**只有游戏会验**的约定（真机连踩两次）

第一版 jar 装进真实的 1.20.1 + Forge 47.2.20 实例后，游戏在**扫描 mod 文件阶段**就拒收：

```
InvalidModFileException: Missing required field mandatory in dependency (craftgraph-0.3.0-mc1.20.1.jar)
```

原因是依赖块写成了 NeoForge 那套 `type = "required"`；Forge 1.20.1 要的是 `mandatory = true`。
（参照物不是记忆：直接读了他们整合包里 Create 0.5.1.j 的 `mods.toml`，以及游戏自带的
`forge-1.20.1-47.2.20-universal.jar`。）

修完这一条之后**第二个错立刻露头**（同一个文件、同一类）：

```
Missing language javafml version [47.2.0,) wanted by craftgraph-0.3.1-mc1.20.1.jar, found 47
ModLoadingException: Mod File ... needs language provider javafml:47.2.0 or above to load
```

`loaderVersion` 我填的是 **Forge 的版本**，而它指的是 **javafml 语言加载器**的版本 ——
1.20.1 上就是 `47`。正确的值 `[47,)` 同样是从 Create 0.5.1.j 的 `mods.toml` 抄的。

这类差异的形状值得记住：**编译、测试、构建、CI 全过，只有游戏会拒**，
而且两次的报错都不指向「我们的文件写错了」，很容易先怀疑游戏环境或别的模组。
所以现在有三道守卫：

1. **单元测试**（`mod/src/test/.../ModMetadataTest.java` 与 `mod-1.20.1/.../ModMetadataTest.java`）：
   锁死 `loaderVersion` 的值、依赖块用 `mandatory` 还是 `type`、
   以及「会展开进 mods.toml 的值必须是 ASCII」。改错文件 → `./gradlew test` 直接失败。
2. **CI / release 的产物校验**：数依赖块数与 `mandatory = true` 是否一一对应、断言 `type =` 不出现。
3. 两处都用**真实产物正反面验证过**：真产物通过，把值改成踩过的那个 → 正确失败。

**写守卫本身也踩了一次**：第一版 `loaderVersion` 断言写的是 `startsWith("[47")`，
而它**放过了 `[47.2.0,)`** —— 也就是要拦的那个值本身。是「把坏值注进去看它失不失败」
这个动作发现的。**守卫写宽了等于没写**，所以现在两条都是精确相等。

流程上的教训：换一个 loader 目标时，`mods.toml` 应当**从该 loader 上能正常加载的真实模组抄**，
再逐字段改 —— 而不是照着另一个 loader 的模板改。AT 那次我这么做了（抄了 JEI），
`mods.toml` 这次没有，于是同一个文件栽了两次。

### 他们包里的 Create 是 0.5.1.j，不是 6.0.x

原先以为 1.20.1 这条线上大家都用 Create 6.0.x（maven 上确实有）。**实际整合包里是 0.5.1.j**，
而我们是对着 6.0.8 编译的。所以直接反编译了他们那个 jar，逐个核对我们在调的方法：

| 类 | 结果 |
|---|---|
| `ProcessingRecipe` | `getRollableResults` / `getProcessingDuration` / `getFluidResults` / `getFluidIngredients` 都在 |
| `ProcessingOutput` | `getStack` / `getChance` 都在 |
| `FluidIngredient` | `getMatchingFluidStacks` / `getRequiredAmount` 都在 |
| `SequencedAssemblyRecipe` | `getIngredient` / `getSequence` / `getLoops` / `getTransitionalItem` 都在，`resultPool` 字段也在 |

即**我们调的每一个方法都存在**，所以 0.5.1.j 上适配器应该能正常工作
（`getResultItem` 不在那份 `javap` 输出里，是因为它在 MC 的 `Recipe` 接口上，属于继承来的）。

### 验证到了什么程度

| 项 | 状态 |
|---|---|
| 编译（对着 Forge 47.2.0 + Create 6.0.8 的真实 jar） | ✅ 编译器逐条核对过 |
| `core/` 的 148 个纯逻辑用例 | ✅ 两个构建各跑一遍 |
| 版本自己的两个守卫测试（访问转换器、日志 ASCII） | ✅ |
| `mods.toml` / `accesstransformer.cfg` 在 jar 里 | ✅ 就地断言过 |
| **产物真的 reobfuscate 了** | ✅ 逐字节看过：发布 jar 里是 `m_175515_` / `m_203613_`，开发 jar 里是 `registryOrThrow` / `getTagNames` |
| **在真游戏里能跑** | 🟡 **进行中**：第一次装进真实实例被 `mods.toml` 的依赖块字段挡下了（见坑三），已修；修完的新 jar 尚未复验 |
| 依赖块形状（`mandatory` 而不是 `type`） | ✅ 真实产物正反面各验一次，并写进 CI/release 的产物校验 |

### 第 5 步要验什么（没验之前不要声称「1.20.1 支持」）

1. **AT 在运行时是否生效**：编译期是 MDG 帮我们把字段变公开的，运行时是 FML 读
   `META-INF/accesstransformer.cfg`。若它不生效，症状是那 27 条锻造配方又变回 opaque
   （**不是崩溃**）。`npm run live` 里那两条锻造探针正是盯这个。
2. **id 从 `byKey` 取是否可靠**：1.20.1 里 id 与配方对象是分开的，
   断言「每条配方 id 非空且唯一」。
3. **标签规模**：`getTagNames()` 若返回空（标签没绑定），标签还原会**静默退化**成物品列表 ——
   原料表看着完整、语义丢了，而且不报错。live 层现在有这条断言（这次补的，见下）。
4. **`RecipeExtractor` 里那处刻意的行为变化**（空栈从 `minecraft:air` 变成跳过）
   在真实数据上的影响 —— 1.21.1 那边也没复核过，一次 `live` 两边都覆盖。

跑法：在 1.20.1 + Forge 47.2.0+ 的实例里装上 `craftgraph-<版本>-mc1.20.1.jar`，
进世界，然后 `cd mcp-server && npm run live`。

### live 层这次补了什么（原来没有，而我一度以为有）

写文档时才发现：原来那套体检里**并没有**「配方 id 唯一」和「标签规模」这两条断言，
而我先在文档里这么写了。补进了 `live.ts` 的「原始协议体检」段 ——
它直接读桥接的 HTTP 接口（不再只走 MCP 工具），因为这两件事只有看原始数据才发现得了：

- **配方 id 非空且唯一**：抽两页 `/snapshot`，断言没有空 id、也没有撞车。
- **物品标签读到了**：读 `/tags/items/all`，下限压得极低（50）—— 它要抓的是
  「标签整个没绑定」（那时是 0），而不是去规定某个整合包该有多少标签。
- 顺带把 `/health` 自报的 `mcVersion` / `loader` 打出来：**一眼看出这份报告来自哪个版本**
  （1.21.1 是 `neoforge-…`，1.20.1 是 `forge-…`），省得对着两份报告猜哪份是哪个。

这三条对两个版本都成立，所以 1.21.1 也一起受益。


### 顺带补上的一处防线：适配器调用失败不再带崩整个快照

`RecipeAdapters` 一直写着「某个模组出问题，后果应该是**少一个适配器**，而不是整个快照建不出来」，
但那句话原来只挡住了**类加载**（懒注册 + lambda）。方法调用这一层没有保护：
适配器运行时抛出的任何异常（版本不匹配的 `NoSuchMethodError`、模组自己代码里的 NPE、
某个畸形配方触发的 `ClassCastException`）都会一路冒到 `ClientBridge.rebuild` 的兜底 catch，
结果是**整个桥接不可用** —— 正是那段注释说要避免的事。

发现它的契机正是上面那件事：他们包里是 0.5.1.j 而我们对着 6.0.8 编译，
于是「版本对不上时会发生什么」从假设变成了要考虑的真实场景。

现在每个适配器调用都过一道 `guarded(...)`：失败就退回通用读取、把次数记下来，
并由 `ClientBridge` 打进日志（`adapterFailures()`，与既有的
`resultItemFailures` / `toastSymbolFailures` 同一套路）。没有采用「禁用整个适配器」，
因为**一个坏配方不该让这个类型的一万条配方都失去适配器**；而覆盖度
（`FieldCoverage`）会随之下降，所以它不是静默降级。
