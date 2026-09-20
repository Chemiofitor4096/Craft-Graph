# 1.20.1 版本：调查结论与方案

**状态：计划，尚未落地。** 这里记的是「已核实的事实」和「待定的决定」，
不是已经锁定的决策 —— 锁定的决策在 [decisions.md](decisions.md)。

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
真正的风险不是 fork 本身，而是**版本漂移** —— 用 47.2~47.4 新增的 API 就会在
NeoForge 20.1 上找不到。我们用到的那点 API（`Recipe`、`Registry`、`BuiltInRegistries`、
事件总线）都是 1.20 之前就有的老面孔，所以实际风险很小，
但**结论按「编译目标 = Forge 47.4.x」定，因为整合包跑的是它**。

---

## 2. 这个 port 到底要改多少（用官方映射逐条核对过）

核对手段：Mojang 官方的 1.20.1 client mappings（`piston-data.mojang.com` 上的 `client.txt`）。
它给出 1.20.1 里**存在哪些类、哪些方法、签名是什么** —— 比凭记忆猜可靠。

边界不变：16 个文件 / 2089 行完全不碰 MC，占 53%；碰 MC 的是 10 个文件 / 952 行。

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
| **A. 薄核心** | 那 2089 行 MC-free 代码；`extract/` 整个按版本各一份 | 重复约 950 行，其中约 470 行是适配器/抽取逻辑，会各自漂移 |
| **A+. 薄核心 + 纯函数上提** | A，外加把适配器里**已经纯的**部分（`ResultChance` 已经是）提到共享 | 几乎不额外花时间，但只捞回一小部分 |
| **B. 适配器只说自己的类型** | `extract/` 里除「读 MC 对象」以外的全部（断言、概率分类、池子归一、过渡物品排除） | 要先改 `RecipeTypeAdapter` 的签名，让它收/发我们自己的 DTO 而不是 `ItemStack`/`FluidStack`/`SizedFluidIngredient`；共享比例升到大约八成，并且**顺手把流体那个洞补上** |

**我推荐先 A、并把 B 记成待办**，理由：我们目前还不知道 1.20.1 这条线会不会长期维护；
B 的收益建立在那之上，而现在花这份钱是在给一个还没发生的事做结构。
但要说清 B 的反面：两份 `extract/` 分开越久越难合，B 会越来越贵。

如果决定「两个版本都长期维护」，那就直接把 B 做了 —— 那时它不是重构，是省事。

不管选哪条，**第 0 步都是同一件事**：把 2089 行 MC-free 代码抽成两边共享的源码集。
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
分成 `1.20.1-0.2.2` 这种只会让人对着两个数字猜）。文件名区分：

- `craftgraph-0.2.2.jar`（1.21.1，保持不变，现有链接和校验都还指得上）
- `craftgraph-0.2.2-mc1.20.1.jar`

Release workflow 现有的「tag 与 `mod_version` 一致」校验对两个 jar 都成立，不用改
（但要把新 jar 一并挂上去）。真正的 MC 版本信息在两份 `mods.toml` 里，那才是 loader 看的。

---

## 7. 待定的决定

1. **他们测的 1.20.1 包跑的是哪条线、哪个版本？** 决定编译目标钉在 `47.4.23` 还是别的。
   按上面的数据我默认钉 Forge 线。
2. **结构选 A 还是 B？**（我默认 A，若「两边都长期维护」则 B）
3. **那些包里的 Create 是 6.0.x 还是 0.5.1.f？** 6.0.x 的话适配器基本照搬；
   0.5.1.f 在 `maven.createmod.net` 上**没有** `create-1.20.1` 的对应版本，
   得另找来源才能编译期核对 —— 那种情况下它只会通过懒注册静默缺席（表现为那些类型变 opaque），
   不会崩，但也就等于没适配。
