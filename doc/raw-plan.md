## 项目代号：CraftGraph MCP

### 1. 项目目标

让 AI 通过 MCP 协议实时查询 Minecraft 运行中的配方数据，并支持：

- 查询物品、方块、流体、标签等注册表
- 按输出/输入查询配方
- 递归构建配方树
- 设计产线、计算原料、机器数量、副产、能耗
- 与 JEI / EMI 联动：读取配方、在游戏内展示、从 JEI/EMI 拖拽配方到规划器

**非目标（MVP 不做）**：
- 完整物流模拟
- 3D 工厂布局
- 所有模组特殊机制（GT 超频、Create 应力等先做可扩展接口）

---

### 2. 总体架构

```text
AI Client (Claude/Cursor)
        │ MCP (stdio / SSE)
        ▼
MCP Server (TypeScript / Node.js)
        │ HTTP + WebSocket (localhost)
        ▼
Game Bridge Mod (Java / NeoForge)
        │ EMI API / JEI API
        ▼
Minecraft Runtime + 所有已加载模组
```

关键决策：

- **MCP Server 不直接读游戏内存**，而是连接游戏内 Mod 暴露的本地 HTTP/WS 服务。
- 游戏内 Mod 负责通过 **EMI API**（优先）或 **JEI API** 获取配方。
- MCP Server 负责工具定义、配方树计算、产线计算、缓存。
- 游戏内可选 UI：配方树画布，支持从 JEI/EMI 拖拽。

---

### 3. 核心数据模型

```json
ItemStack { id, count, components }
Ingredient { items, tags, fluids, count }
Recipe {
  id, type, inputs, outputs, machine,
  duration, energy, chance, conditions
}
RecipeNode {
  output, recipe, inputs,
  children, alternatives, depth
}
ProductionPlan {
  target, rate,
  nodes, rawMaterials,
  machines, power, byproducts
}
```

---

### 4. MCP 工具清单（MVP）

| 工具 | 作用 |
|---|---|
| `get_registry` | 查询 blocks/items/fluids/entities |
| `search_items` | 按名称/ID 搜索物品 |
| `list_recipe_types` | 列出所有配方类型 |
| `get_recipes_for_output` | 查询某物品的所有产出配方 |
| `get_recipes_for_input` | 查询某物品作为输入的配方 |
| `get_recipe_details` | 获取单个配方完整信息 |
| `build_recipe_tree` | 递归构建配方树 |
| `find_alternative_recipes` | 查找替代配方 |
| `calculate_production_plan` | 按目标速率计算产线 |
| `export_plan` | 导出 JSON / Markdown |
| `refresh_recipes` | 刷新运行时配方缓存 |
| `show_in_jei` / `show_in_emi` | 在游戏内打开对应配方 |

---

### 5. 阶段计划与 Agent 任务包

#### 阶段 0：立项与选型（1–2 天）
**负责 Agent：项目协调 Agent**

- 确认：MC 版本、加载器、JEI/EMI 优先级
- 建立仓库：`/mod`、`/mcp-server`、`/shared`、`/docs`
- 定义 JSON Schema 和 API 契约

**交付物**：技术选型文档、仓库骨架、接口定义。

---

#### 阶段 1：游戏内数据桥接（约 1 周）
**负责 Agent：Mod 桥接 Agent**

- 创建 NeoForge Mod
- 集成 EMI API，读取所有配方类型与配方
- 实现本地 HTTP 服务：
  - `GET /health`
  - `GET /registry/{type}`
  - `GET /recipe-types`
  - `GET /recipes/output/{itemId}`
  - `GET /recipes/input/{itemId}`
  - `GET /recipe/{recipeId}`
- 支持 WebSocket 事件：配方重载、注册表变化
- 可选：JEI 兼容层，参考 TMRV 思路

**交付物**：可独立运行的 Bridge Mod，curl 能查到配方。

---

#### 阶段 2：MCP Server 基础（3–5 天）
**负责 Agent：MCP Server Agent**

- 使用 `@modelcontextprotocol/sdk` 创建 TypeScript MCP Server
- 实现 stdio 启动，连接 Bridge Mod HTTP API
- 注册 MVP 工具：注册表、搜索、配方查询
- 错误处理、超时、重试、本地缓存

**交付物**：AI 客户端能通过 MCP 查询物品和配方。

---

#### 阶段 3：配方树引擎（约 1 周）
**负责 Agent：配方树 Agent**

- 递归解析配方，处理：
  - 多输出、副产、概率产出
  - 标签输入、流体、NBT/组件
  - 循环配方检测
  - 替代配方策略
- 实现 `build_recipe_tree`
- SQLite 缓存 + FTS5 搜索
- 返回结构化 JSON

**交付物**：AI 能问“铁锭怎么来”，返回完整配方树。

---

#### 阶段 4：产线计算（约 1 周）
**负责 Agent：产线计算 Agent**

- 根据目标速率计算：
  - 总原料需求
  - 每台机器数量
  - 总耗时、总能耗
  - 副产物
- 支持简单并行与机器等级模型
- 实现 `calculate_production_plan`
- 输出可读报告和 JSON

**交付物**：AI 能规划“10个/分钟钢锭”的产线。

---

#### 阶段 5：JEI / EMI 联动 UI（1–2 周）
**负责 Agent：UI 联动 Agent**

- 游戏内配方树画布：
  - 节点、连线、数量视图
  - 从 JEI/EMI 拖拽配方到画布
  - 与 MCP Server 同步数据
- 实现 `show_in_jei` / `show_in_emi`
- 可选：在画布中高亮缺失材料

**交付物**：玩家可拖拽配方并让 AI 分析。

---

#### 阶段 6：测试、打包与发布（约 1 周）
**负责 Agent：测试与发布 Agent**

- Mod：JUnit + GameTest
- MCP Server：Vitest + 集成测试
- 示例整合包、文档、安装指南
- 发布：Modrinth / CurseForge / NPM

**交付物**：MVP 发布版。

---

### 6. 里程碑

| 里程碑 | 内容 |
|---|---|
| M1 | Bridge Mod 可 HTTP 查询配方 |
| M2 | MCP 可查询注册表与配方 |
| M3 | 配方树可递归构建 |
| M4 | 产线计算可用 |
| M5 | JEI/EMI UI 联动 |
| M6 | MVP 发布 |

---

### 7. 主要风险与缓解

| 风险 | 缓解 |
|---|---|
| EMI/JEI API 变化 | 抽象适配层，锁定版本 |
| 动态配方（KubeJS） | 运行时查询，不依赖静态导出 |
| 循环配方 | 深度限制 + 已访问集合 |
| 性能问题 | 缓存、懒加载、分页 |
| 多加载器兼容 | 先单加载器，后抽接口 |
| 本地服务安全 | 仅 localhost，可选 token |

---

### 8. 移交前只需确认三件事

1. **MC 版本与加载器**：1.21.1 NeoForge / 1.20.1 Forge / Fabric？
2. **配方查看器优先级**：EMI 优先，还是 JEI 必须同时支持？
3. **是否要游戏内 UI**：MVP 是否包含画布，还是先只做 MCP 查询？