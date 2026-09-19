# 已锁定的技术决策

记录日期：2026-09-19。改这些决定前先读「代价」一栏。

| # | 决定 | 为什么 | 改动的代价 |
|---|---|---|---|
| 1 | **Minecraft 1.21.1 + NeoForge 21.1.x** | 当前模组生态最完整的版本之一，JEI/EMI 都成熟。NeoForge 21.1.x 至今仍在持续发版（最新 21.1.251），与 21.11、26.x 等新版本线并行维护，说明生态活跃。Java 21。 | 高。整个 Gradle 脚手架、配方 API 调用、映射都要重写。 |
| 2 | **配方查看器（JEI / EMI）是软依赖，且只用来补「只有视图器才知道的东西」** | 模组机器配方的输入、催化剂（机器）、能耗只有视图器的插件读得出来 —— 模组的配方类没有通用接口。但**能自己读的绝不交给视图器**：锻造靠一份访问转换器就解决了（见第 9 条），而那占了原版 opaque 的一大半。普及率上 JEI 远高于 EMI，所以先做 JEI（它有 `INPUT/OUTPUT/CATALYST` 角色，催化剂正好就是机器，且配方对象是 `RecipeHolder`，能按 id 与我们已有的抽取结果 join）。 | 中。降级路径（不装任何视图器）必须始终可测，否则软依赖会退化成事实上的硬依赖。EMI 因为普及率低，排在 JEI 之后，可能一直不做。 |
| 3 | **MVP 不做游戏内 UI** | 原计划的阶段 5（画布 + 跨 GUI 拖拽）比阶段 1~4 总和还大。核心价值在聊天窗口就能交付。 | 低。UI 是加法，后做不影响任何已有部分。 |
| 4 | **MCP Server 用 TypeScript + 官方 `@modelcontextprotocol/sdk`** | 生态最成熟、示例最多、代码量小。且全部算法逻辑可脱离游戏测试。 | 中。换语言要重写 server。 |
| 5 | **归一化在 Mod 侧，算法在 Server 侧** | 翻译 `Recipe` 需要 MC 类，只有游戏进程做得到；图算法放游戏里则没法脱离游戏测试，且游戏崩溃会连带算法挂掉。 | 中。见 protocol.md §0。 |
| 6 | **产线计算用贪心展开，不用线性规划** | 通用解要解方程组 + 引求解器，工期从 1 周变 1 个月。贪心（每种物品固定选一个配方 + 允许覆盖 + 逐层取整）结果可解释，够用。 | 低。求解器可以后加，接口不变。 |
| 7 | **MVP 不支持多加载器** | 先做 NeoForge。抽象加载器接口是后期的事，现在做纯属浪费。 | 中。但比一开始就抽象要省得多。 |
| 8 | **`duration` / `machine` 用原版接口读，不等 EMI** | 曾经以为「原版 API 拿不到这两个字段，接 EMI 后补」，于是它们被写成 `null` —— 结果真实数据里 1290 条配方**全是 null**，`calculate_production_plan` 的机器数计算整个失效，而四个测试层全绿（夹具里手写了 duration）。核实反编译源码后发现原版完全给得出：`AbstractCookingRecipe#getCookingTime()` 给耗时，`Recipe#getToastSymbol()` 给机器。**「等一个未来依赖」是最容易把字段留成 null 的理由**，而 null 会一路静默传进产线结果。 | 低。适配器层是加法，接 EMI 时在同一个登记处加更全的适配器即可。 |
| 9 | **锻造用访问转换器读，不靠视图器** | 27 条锻造配方读不到输入，是因为 `SmithingRecipe` 不覆写 `getIngredients()`，三个槽位藏在 `SmithingTransformRecipe` / `SmithingTrimRecipe` 的**包私有字段**里。原版接口只剩三个谓词，用它们反推只能得到物品集合、会丢掉标签身份。而访问转换器（`META-INF/accesstransformer.cfg`）能把字段变公开 —— **JEI 做的就是这件事**，它不是哪个 mod 的特权。于是这 27 条不装 JEI/EMI 就能修，opaque 从 40 降到 31。 | 低。AT 只在 1.21.1 上验证；字段改名会**编译失败**（适配器读的正是那些字段），`validateAccessTransformers` 再兜一层。 |
| 10 | **适配器用 `null` 与空列表区分「不归我管」和「确定读不到」** | 修锻造时差点制造一个静默错误答案：`SmithingTrimRecipe#getResultItem()` 返回硬编码的「铁胸甲」占位符，只补输入会让输入非空、`Readability` 判定可读，那个占位符就从「被标记的假数据」升级成「理直气壮的答案」。所以适配器必须能表达「产出我确定读不到」—— 这个语义只能用空列表说，而「用通用接口的结果」用 `null`。两者混为一谈就会退回那个假答案。 | 低。约定写在接口注释里，`SmithingAdapter` 的两个分支是第一个用例。 |
| 11 | **模组适配器一律惰性注册 + `compileOnly` 编译验证** | `CreateAdapter` 直接引用 Create 的类，一旦放进静态列表，**类加载就会连带解析它们** —— 没装 Create 的实例会在构建快照时 `NoClassDefFoundError`，整个桥接不可用。所以列表懒建、先用 `ModList.isLoaded` 判断、`new` 留在 lambda 里（JVM 按指令惰性解析），外面再包 `catch (Throwable)` 把后果限定成「少一个适配器」。编译期用 `compileOnly` + `transitive = false`：**它的作用是让编译器逐条核对我们的调用与真实 API 一致**，比反射强得多（反射连拼写错误都发现不了）。 | 低。加模组适配器时照这个模式写。实测：原版实例启动后日志里 `NoClassDefFoundError` 出现 0 次。 |
| 12 | **条件断言必须显式报「未验证」，不能静默跳过** | `npm run live` 里的 Create 段落只在装了 Create 的实例上有意义。条件断言最危险的失效方式是「条件不成立所以什么都没测，而输出看起来一切正常」—— 这正是本项目最贵那次教训的形状（`duration` 全是 null 而四层全绿）。所以跳过的段落会单独列在结尾，把「全部通过」和「所有东西都验过了」区分开。 | 低。多打三行字，换来的是「绿色」这个词不含糊。 |
| 14 | **「不产出」与「读不懂」分开，且只有适配器能作证** | 燃料定义（`createaddition:liquid_burning`、`petrochem:*_fuel`）的输入读得到、产出确实是空的。把它们标成 `opaque` 会让 AI 说「这条我读不懂」—— 而我们明明读清楚了。反过来更糟：把读不懂的降级成「不产出」等于告诉 AI 这配方不需要产出。所以**只有读过该类型自己的产出字段**的适配器才能作证（`RecipeTypeAdapter#declaresNoOutput`），其余「空产出」一律仍按 `opaque`。 | 低。协议加一个可选字段 `producesNothing`：旧 Mod 不发，调用方按 false 处理 —— 不是破坏性变更，`protocolVersion` 不变。 |
| 13 | **日志英文、注释中文、HTTP 错误消息中文** | 中文 Windows 上 Minecraft 按 **GBK** 写日志，而整份日志里只有我们这几行含中文 —— 于是 UTF-8 查看器里只有 CraftGraph 的行是乱码（用户实测报过）。日志是排查性能与覆盖度的唯一入口，读不出来等于没有。而 HTTP 错误消息走 JSON 且声明了 `charset=utf-8`，是给 AI 读的，没有这个问题，中文更准确。 | 低。`LogEncodingTest` 扫描所有 `LOGGER.*` 语句断言 ASCII（并要求至少扫到 15 条，否则「一条没扫到」也会全绿）。 |

## 环境要求

| 组件 | 要求 | 说明 |
|---|---|---|
| Java | 21 | NeoForge 1.21.1 要求 |
| Node.js | ≥ 20 | MCP Server 侧 |
| Gradle | **不需要安装** | `mod/gradlew` 已生成，用它即可 |

`JAVA_HOME` **不需要设置**：Gradle 的 toolchain 会从 PATH 找到 JDK 21。
如果 PATH 上的 Java 不是 21，用 `org.gradle.java.installations.paths` 指定，
或者设 `JAVA_HOME` 指向一个 21 的 JDK。

### 构建偶发失败先重试

如果构建环境走了 HTTP 代理，通过代理拉取 `maven.neoforged.net` 时可能出现：

```
SSLHandshakeException: Remote host terminated the handshake
```

实测这通常是**代理节点的瞬时故障** —— 重试即可成功，不要急着改配置。
该仓库直连与走代理都能通。

## 与原计划「非目标」的一致性

原计划的非目标（完整物流模拟、3D 布局、所有模组特殊机制）继续有效。
本文件补充的第 3、6、7 条是**范围收缩**，理由都是工期与风险，不是能力限制。
