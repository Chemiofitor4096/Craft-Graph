package dev.craftgraph.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 序列化的测试。
 *
 * 存在的理由：我们用的是 **Minecraft 自带的 Gson**，它的具体版本取决于游戏，
 * 而 record 支持是 Gson 2.10 才加的。如果哪天游戏降级了 Gson，
 * 这些测试会立刻失败 —— 而不是等到运行时发现所有响应都变成空对象 {}。
 *
 * 这类「依赖环境的隐式假设」最值得写测试，因为它们坏掉时的症状离原因很远。
 */
class JsonTest {

    @Test
    @DisplayName("record 能被正确序列化（依赖 Gson >= 2.10）")
    void serializesRecords() {
        Models.ItemStack stack = Models.ItemStack.of("minecraft:iron_ingot", 3);
        String json = Json.toJson(stack);

        assertTrue(json.contains("\"item\":\"minecraft:iron_ingot\""), json);
        assertTrue(json.contains("\"count\":3"), json);
    }

    @Test
    @DisplayName("null 字段必须出现（MCP Server 靠字段存在与否区分「读不到」和「没有」）")
    void serializesNulls() {
        Models.ItemStack stack = Models.ItemStack.of("minecraft:iron_ingot", 1);
        String json = Json.toJson(stack);

        // Gson 默认会省略 null 字段，那就变成 {"item":...,"count":1}，
        // MCP Server 侧无法区分「components 是空」和「这个字段根本不存在」
        assertTrue(json.contains("\"components\":null"), "null 字段被省略了：" + json);
    }

    @Test
    @DisplayName("嵌套 record 与列表能正确序列化")
    void serializesNestedRecords() {
        Models.Recipe recipe = new Models.Recipe(
                "test:recipe", "minecraft:smelting", "Smelting",
                List.of(new Models.Ingredient("item", 1, List.of(Models.Option.tag("forge:ores/iron")))),
                List.of(Models.ItemStack.of("minecraft:iron_ingot", 1)),
                List.of(), List.of(),
                "minecraft:furnace", 200, null, "vanilla", false);

        String json = Json.toJson(recipe);

        assertTrue(json.contains("\"type\":\"tag\""), json);
        assertTrue(json.contains("\"id\":\"forge:ores/iron\""), json);
        assertTrue(json.contains("\"energy\":null"), "energy 为 null 也必须出现：" + json);
        assertTrue(json.contains("\"duration\":200"), json);
    }

    @Test
    @DisplayName("地图字段能正确序列化")
    void serializesMaps() {
        Models.TagAllPage page = new Models.TagAllPage("items",
                Map.of("forge:ingots/iron", List.of("minecraft:iron_ingot")));
        String json = Json.toJson(page);

        assertTrue(json.contains("forge:ingots/iron"), json);
        assertTrue(json.contains("minecraft:iron_ingot"), json);
    }

    @Test
    @DisplayName("错误体形状符合 doc/protocol.md")
    void errorBodyShape() {
        String json = Json.toJson(Models.ErrorBody.of("NOT_FOUND", "配方不存在"));
        assertTrue(json.contains("\"error\""), json);
        assertTrue(json.contains("\"code\":\"NOT_FOUND\""), json);
        assertTrue(json.contains("配方不存在"), json);
    }

    @Test
    @DisplayName("不缩进（输出会进模型上下文，缩进是纯浪费）")
    void notPrettyPrinted() {
        Models.ItemStack stack = Models.ItemStack.of("minecraft:iron_ingot", 1);
        String json = Json.toJson(stack);
        assertEquals(-1, json.indexOf('\n'), "输出里不该有换行：" + json);
    }
}
