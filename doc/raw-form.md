JSON 确实很费 token，尤其是在配方/注册表这种**大量重复键名、嵌套对象、长命名空间 ID** 的场景。二进制格式（MessagePack、CBOR、Protobuf）虽然字节更小，但给 LLM 时必须转成 base64/hex，token 反而爆炸，且模型难以推理。**所以方向应该是：紧凑文本 + Schema 外置 + 字典编码。**

---

## 1. 推荐优先级

| 格式 | 对 LLM 的 token 友好度 | 说明 |
|---|---|---|
| **TSV / CSV 列式** | ★★★★★ | 表头只出现一次，数据行只有值，最省 |
| **自定义紧凑 DSL** | ★★★★★ | 针对配方树可极致压缩，但需约定解析规则 |
| **短键 JSON / JSONL** | ★★★☆☆ | 比标准 JSON 省，但仍有很多标点 |
| **YAML** | ★★☆☆☆ | 省了引号括号，但缩进空格多，嵌套列表不一定省 |
| **MessagePack / CBOR** | ★☆☆☆☆ | 传输小，但 base64 后 token 多，不推荐 |
| **gzip 压缩** | ★☆☆☆☆ | 省网络流量，不省模型上下文 token |

---

## 2. 核心设计原则

1. **Schema 外置**：列名、字段含义写在 MCP 工具描述里，数据只返回值。
2. **字典编码**：长 ID 如 `minecraft:iron_ingot` 用短 ID `i0` 代替，首次发送字典，后续只引用。
3. **省略默认值**：空值、0、false、默认机器用占位符 `-`。
4. **行列式**：列表数据用 TSV，每行一条记录。
5. **嵌套用引用**：配方树节点只存 `item:count` 和 `recipe_id`，配方详情单独查，避免重复内联。
6. **分隔符选择**：制表符 `\t` 或竖线 `|` 通常比逗号省，因为逗号后常跟空格，且模型对表格熟悉。

---

## 3. 一个可试的紧凑配方格式草案

可以叫它 **CCRF（CraftGraph Compact Recipe Format）**，纯文本、按行、可流式解析。

### 行类型
- `S|` 表头定义
- `D|` 字典条目
- `R|` 配方记录
- `T|` 配方树节点

### 示例：配方列表

```text
S|id|type|machine|time|energy|out|in
D|i0|minecraft:iron_ingot
D|i1|minecraft:iron_ore
D|m0|minecraft:furnace
R|r0|smelting|m0|200|-|i0:1|i1:1
R|r1|crafting|m1|1|-|i0:1|i1:9
```

对比等价 JSON：

```json
[
  {
    "id": "r0",
    "type": "minecraft:smelting",
    "machine": "minecraft:furnace",
    "time": 200,
    "outputs": [{"item": "minecraft:iron_ingot", "count": 1}],
    "inputs": [{"item": "minecraft:iron_ore", "count": 1}]
  }
]
```

CCRF 把键名、引号、括号、重复的 `minecraft:` 都省掉了。

### 示例：配方树

```text
T|0|-|i0:1|r0
T|1|0|i1:1|-
```

含义：
- 深度 0，父节点 `-`，输出 `i0:1`，配方 `r0`
- 深度 1，父节点 0，输入 `i1:1`，无子配方

配方详情通过 `R|r0` 查询，树只保留结构。

### 物品/流体/标签表达
```text
i0:1       物品 id:数量
f0:1000    流体 id:毫桶
#tag:1     标签
i0:1:0.5   物品:数量:概率
i0:1+i1:2  多个输入/输出用 + 连接
```

---

## 4. MCP 工具如何配合

建议提供两个版本：

- `get_recipes_compact`：默认返回 CCRF/TSV
- `get_recipes_json`：调试或需要严格结构时用

工具描述里写明：

```text
返回 CCRF 格式。第一行 S| 定义列。
D| 是字典。R| 是配方。物品用短 ID，字典行给出全名。
多个值用 + 分隔，空值用 -。
```

这样 AI 能理解并解析，不需要每次重复键名。

---

## 5. 如何验证是否真的省 token

不要只看字节数，要用实际模型的 tokenizer 测：

- OpenAI：`tiktoken`
- Claude：Anthropic tokenizer
- Gemini：对应 tokenizer

写一个脚本：
1. 准备同一份配方样本的 JSON。
2. 转成 TSV、短键 JSON、CCRF。
3. 分别计算 token 数。
4. 比较。

通常 **TSV/列式能省 30%~60%**，**字典编码 + 短 ID 后可能省 50%~70%**，但具体取决于数据重复度。

---

## 6. 注意事项

- **不要用二进制给模型**：模型看不到结构，推理会变差。
- **字典不能太复杂**：短 ID 要可预测，否则模型容易混淆。
- **保留可读性**：AI 需要能理解格式，太隐晦会降低工具调用准确率。
- **分页/按需查询**：再省也不如只返回 AI 真正需要的那部分。
- **缓存字典**：同一会话中字典只发一次，后续复用。