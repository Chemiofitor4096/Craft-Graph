# AGENTS.md —— 在这个仓库里干活前先读这个

面向的是**改这个仓库的 AI**（人看 [README](README.md) 和 [doc/development.md](doc/development.md)）。
这里只写两件事：**先读什么**、**哪些规则违反了会出静默错误答案**。

## 先读，别猜

| 文档 | 什么时候必须读 |
|---|---|
| [`doc/protocol.md`](doc/protocol.md) | 碰任何字段、端点、错误码之前。**它是两侧唯一的契约**，改字段名/语义 = 破坏性变更，必须同步升 `protocolVersion` |
| [`doc/development.md`](doc/development.md) | 动手之前。四层测试怎么分工、token 怎么量、以及六条踩过的坑（**最省时间的一份**） |
| [`doc/decisions.md`](doc/decisions.md) | 想改「已锁定的决策」之前，先看那一栏的代价 |
| [`mod/README.md`](mod/README.md) | 改 Mod 之前（快照架构、线程规则） |

代码注释里写了大量「为什么这么做、为什么不那么做」——**改之前先读注释**，很多看起来多余的写法是有原因的。

## 项目形状

两半，**职责不能混**：

- `mod/`（Java / NeoForge）—— 只做数据搬运：读游戏里的配方，归一化成 DTO，用本地 HTTP 发出去。
  **它跑在脆弱的环境里，越薄越好**；任何图算法放进去都没法脱离游戏测试，游戏一崩还连带挂掉。
- `mcp-server/`（TypeScript）—— 配方树、产线计算、缓存、MCP 工具。**算法全在这里**，
  所以能用 `shared/fixtures/` 的假数据完整测试，不需要启动游戏。

---

## 四条硬规则

### 1. `opaque` 的语义是「我读不到」，**绝不是**「不需要材料」

读不懂的配方必须显式标 `opaque`，**绝不能用空数组掩盖**。空 `inputs` 看起来像「这配方不用原料」，
AI 会据此编出错误答案而且不自知。同理：

- 读不到就返回 `null`，**不要用 0 或空串冒充**（`duration: 0` 会被当成「不用时间」）
- **不要伪造数据**：`energy` 原版确实没有，就一直 `null`

### 2. 任何下游要用的字段，`live` 层都必须断言

最贵的一次教训：`duration` / `machine` 在 1290 条真实配方上**全是 null**，
而四层测试全绿 —— 因为夹具里手写了 duration。**夹具能验证「给定 duration 算得对不对」，
永远验证不了「duration 拿不拿得到」。**

所以：改了 Mod 的字段读取，就**同时**加 `live` 断言。
`live` 里的断言要包含**反向断言**（例如「`crafting` 的耗时必须是 null」），
否则「给所有配方编一个耗时」这种假修复是绿的。

### 3. 日志 ASCII、注释中文、HTTP 错误消息中文

中文 Windows 上 Minecraft 按 **GBK** 写日志，而整份日志里只有我们这几行含中文 ——
于是 UTF-8 查看器里**只有 CraftGraph 的行是乱码**。实测过：用户报告的就是这个。

| 内容 | 语言 | 守卫 |
|---|---|---|
| `LOGGER.*` 文案 | **ASCII 英文** | `LogEncodingTest` |
| 代码注释 / javadoc | 中文 | — |
| HTTP 错误消息（`error(...)`） | 中文 | 走 JSON 且声明 `charset=utf-8`，没有编码问题 |

### 4. 不确定就去看真实源码

这个项目最大的时间黑洞是**「我以为数据长这样」**。已经栽过至少五次，包括：

- 以为原版给不出 `duration` → 其实 `AbstractCookingRecipe#getCookingTime()` 就有
- 以为多产出是通用能力 → 原版一条多产出配方都没有，NeoForge 也没有通用接口
- 以为锻造读不到是平台限制 → 其实一份访问转换器就解决了（JEI 就是这么做的）
- 以为某个类型要小心 → 它其实已经被覆盖了

**读源码的位置**：

- Minecraft / NeoForge：`mod/build/moddev/artifacts/neoforge-21.1.251-sources.jar`（`./gradlew build` 后就有）
- 模组 API：各自的 Maven（Create = `https://maven.createmod.net`，注意版本号带构建号如 `6.0.11-300`）
- 编译期用 `compileOnly` + `transitive = false` 引模组 API，**让编译器逐条核对我们的调用**，
  比反射强得多（反射连方法名拼错都发现不了）

---

## 测试与验证

```bash
# TypeScript（不需要游戏）
cd mcp-server
npm run typecheck    # 类型检查
npm run smoke        # 算法层：索引、标签挑选、槽位合并、循环检测、离线降级
npm run e2e          # 协议层：真实 stdio 握手 + 工具调用
npm run contract     # 跨语言契约：吃 Java 侧真实输出（**依赖下面 mod 的 test 先跑**）
npm run measure      # token 测量；改了输出/渲染就跑一遍
npm run live         # ★ 对运行中的真游戏体检（需 Minecraft 已进世界）
npm run inspect      # 配方覆盖度诊断：哪些类型读不懂、为什么
npm run routes       # 选路质量诊断：叶子原料与整合度分布（**改选路启发式之前先跑它**）

# Java（不需要启动 Minecraft）
cd mod
./gradlew test       # JUnit
./gradlew build
./gradlew runClient  # 起带 Mod 的游戏
```

**顺序依赖**：`contract` 消费的样本由 Mod 的 `ContractDumpTest` 生成 →
新克隆的仓库要先 `cd mod && ./gradlew test` 再 `npm run contract`。

⚠️ **Gradle 构建缓存会让 `test` 不执行**：`BUILD SUCCESSFUL in 731ms` 那种就是命中缓存了，
`ContractDumpTest` 没跑、样本不会刷新，而 `contract` 会报「样本过期」。
需要真跑时用 `./gradlew test --rerun-tasks --no-build-cache`。

这个陷阱**在 CI 上更隐蔽**：只改 TypeScript 的提交不会让 `:test` 的输入变化，
于是 `./gradlew build` 直接 FROM-CACHE，契约样本从未生成，
红的是 `ci.yml` 里那步 upload（`no files found`）—— 看起来像上传坏了，其实根因是缓存。
所以 CI 里紧跟 `build` 有一道无条件的 `./gradlew test --rerun --no-build-cache`。
**给 test 加新的「写文件到仓库」副作用时，必须同时想清楚 CI 里它会不会被执行到**，
或者更干脆：把那个目录声明成 `test` 的产物（`mod/build.gradle` 里有例子）。

### 什么验不了（别假装验过）

| 东西 | 需要什么 |
|---|---|
| `instanceof` 对 MC / 模组类的派发 | **装了对应模组的真游戏**（测试类路径看不到它们） |
| 字段在真数据里有没有值 | `npm run live` |
| 大包上的 opaque 比例 / 抽取耗时 | 用户自己的包（`npm run inspect` + 游戏日志里那行「主线程抽取 N ms」） |
| 选路/解析改动的效果 | **离线**对比：真实包的快照若在 `~/.craftgraph/cache/`，起个临时脚本走 `RecipeStore.load` 就能在**用户真实数据**上跑前后对比，不需要游戏在运行 |

**改启发式之前先量，而且要量「全部」不能只看一两个案例**：这个项目里有两条候选规则
（「偏好标签输入」「偏好被很多配方消耗的批量物品」）都是先想出来、再拿真实包量、结果被数据推翻的。
反面教材也在里面：有一条改动在真实包上改变了 **39% 的首选**，而提交前只对了一个案例 ——
所以 `npm run routes` 就是为这件事存在的（见 `doc/development.md`）。

另外：**别指望给「路线合不合理」找一条纯数据判据**。实测 `minecraft:iron_ore` 与
`minecraft:iron_horse_armor` 在标签数（5 vs 1）和整合度（3 vs 3）上几乎一样 ——
「这东西玩家能不能批量搞到」只存在于世界知识里，不在配方图里。

`live` 里的条件断言（「装了 X 才验」）**必须显式报「未验证」**，
不能因为条件不成立就静默通过 —— 那正是最贵那次教训的形状。

---

## 改东西时的检查清单

- **改了输出 / 渲染** → `npm run measure`，看 token 涨没涨（输出进模型上下文，大小就是成本）
- **改了 Mod 的字段读取** → 加 `live` 断言（见硬规则 2）
- **加了模组适配器** → **必须惰性注册**：`CreateAdapter` 直接引用 Create 的类，
  放进静态列表会让没装该模组的实例在**构建快照时** `NoClassDefFoundError`，整个桥接不可用。
  用 `ModList.isLoaded` 判断 + `new` 留在 lambda 里（JVM 按指令惰性解析类）+ `catch (Throwable)`
- **改了带概率/流体的配方** → 概率分成必然/概率两拨（下游算法不同），
  且**漏掉流体不报错、只会让原料表看起来完整却少了东西**
- **提交** → Conventional Commits，中文摘要 + bullet body（看 `git log` 里已有的格式）

## 环境陷阱

| 陷阱 | 做法 |
|---|---|
| `gradle.properties` 是 **ISO-8859-1** 读的 | 只写 ASCII；中文描述放 `src/main/templates/.../neoforge.mods.toml`（UTF-8） |
| Windows `core.filemode=false` | 改可执行位要用 `git update-index --chmod=+x`，**提交前不能 `git reset`**（会被重置掉） |
| 行尾 | 仓库内统一 LF（`.gitattributes`）。提交后确认 blob 里 CR 字节为 0 |
| 测量脚本的缓存 | `measure` / `compare` / `analyze` **必须用自己的缓存目录**，绝不碰 `~/.craftgraph/cache`（曾经污染过真实缓存，离线查询把假数据当真数据返回） |
| 已发布的版本号 | **不要复用**。同一个版本号对应不同字节会让依赖方缓存拿到哪个全看运气。改了东西就升 `mod_version` 再发 |
| Create 的 jar 下载 | 大制品（几十 MB 的 jar / sources）可能被中途截断，而症状是「不是 zipfile」（文件没有中央目录）。用 `curl -C -` 续传补齐 |

## 版本号

`mod/gradle.properties` 的 `mod_version`、`mcp-server/package.json`、`mcp-server/src/index.ts` 的
`VERSION` **保持一致** —— 它们是一个产品的两半，分开走只会让人对着两个数字猜哪个是新的。
Release workflow 会校验 `tag` 与 `mod_version` 一致（`v0.2.0` ↔ `0.2.0`），不一致直接失败。
