# mod-1.20.1 —— 同一个 Mod 的 Minecraft 1.20.1 版本变体

这是 CraftGraph 桥接 Mod 在 **Minecraft 1.20.1 / MinecraftForge** 上的构建。
功能、协议、算法与 1.21.1 那边**完全一样** —— 它们共享 `../core/` 那批不含 Minecraft 的代码。

## 与 ../mod（1.21.1 / NeoForge）的关系

| | 1.21.1 (`../mod`) | 1.20.1 (这里) |
|---|---|---|
| 加载器 | NeoForge 21.1.x | MinecraftForge 47.2.0+ |
| Java | 21 | **17** |
| 元数据文件 | `META-INF/neoforge.mods.toml` | `META-INF/mods.toml` |
| 访问转换器 | 官方字段名 | **SRG 名**（该版本的硬性要求） |
| 产物 | `craftgraph-<版本>.jar` | `craftgraph-<版本>-mc1.20.1.jar`（reobfuscate 过） |
| 共享代码 | `../core/src/main/java`（`srcDir` 编进来） | 同一批 |
| 各写一份的代码 | 10 个类：读游戏对象 + loader 接线 | 同样 10 个，逻辑相同、类型不同 |

**改动几乎总是要两边都改**，或者更好：先把共有的判断挪进 `../core/`，两边就只剩搬运。
`../core/build.gradle` 顶部的注释解释了为什么那批代码独立成一个工程。

## 构建

```bash
./gradlew build          # 需要 JDK 17（1.20.1 的 Forge 跑在 17 上）
./gradlew runClient      # 起一个装了本 Mod 的开发用客户端
```

产物：

- `build/libs/craftgraph-<版本>-mc1.20.1.jar` —— **发布用**，已经 reobfuscate 成 SRG 名
- `build/devlibs/craftgraph-<版本>-mc1.20.1.jar` —— 开发用，未混淆（ModDevGradle 自动分开）

版本号不在这个目录的 `gradle.properties` 里 —— 从 `../mod/gradle.properties` 读
（两个 jar 是同一产品的两个变体，一处定义）。

## 还没验过的部分

**它在真游戏里还没跑过。** 编译通过只证明 API 对得上，证明不了：

- 访问转换器在**运行时**是否生效（不生效的症状是 27 条锻造配方又变回 opaque，不是崩溃）
- `getRecipeIds()` + `byKey()` 取到的配方 id 是否可靠
- 标签是否真的绑定过（没绑定的话标签还原会静默退化成物品列表）

验收方式：装进一个 1.20.1 + Forge 47.2.0+ 的实例、进世界，然后
`cd ../mcp-server && npm run live`。要验的清单与已知差异见
[`../doc/porting-1.20.1.md`](../doc/porting-1.20.1.md)。
