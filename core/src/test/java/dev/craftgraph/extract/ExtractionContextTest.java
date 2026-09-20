package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.TagIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽位归一化：从 {@link RawSlot}（一包 id）到协议表示（标签或具体物品）。
 *
 * <h2>它守的是什么</h2>
 *
 * 这段逻辑原来散在提取器里，两处（物品、流体）各写一遍，而它有一个**不能忽略的返回值**：
 * 表示不了时返回 {@code null}，调用方必须据此把整条配方标成 opaque。
 * 一旦有人把 null 当成「没有槽位」跳过，原料表就会「看起来完整但少了东西」——
 * 这正是本项目最警惕的静默错误。所以它现在住在 core、由这些测试盯着。
 */
class ExtractionContextTest {

    private static final TagIndex ITEM_TAGS = TagIndex.of(Map.of(
            "forge:ingots/iron", List.of("minecraft:iron_ingot", "othermod:iron_ingot"),
            "minecraft:planks", List.of("minecraft:oak_planks", "minecraft:birch_planks")));

    private static final TagIndex FLUID_TAGS = TagIndex.of(Map.of(
            "c:lava", List.of("minecraft:lava")));

    private static final ExtractionContext CTX = new ExtractionContext(ITEM_TAGS, FLUID_TAGS);

    @Test
    @DisplayName("按 kind 挑标签索引：流体的槽位不会去物品标签里查")
    void picksTagIndexByKind() {
        assertEquals(ITEM_TAGS, CTX.tagsFor(Models.Ingredient.KIND_ITEM));
        assertEquals(FLUID_TAGS, CTX.tagsFor(Models.Ingredient.KIND_FLUID));
    }

    @Test
    @DisplayName("未知 kind 按物品处理（协议里只有两种，不该有第三种）")
    void unknownKindFallsBackToItems() {
        assertEquals(ITEM_TAGS, CTX.tagsFor("something-else"));
    }

    @Test
    @DisplayName("★ 恰好等于一个标签的槽位被还原成 tag 而不是一串物品")
    void restoresTagSlot() {
        Models.Ingredient normalized = CTX.normalize(RawSlot.items(
                List.of("minecraft:iron_ingot", "othermod:iron_ingot")));

        assertEquals(1, normalized.options().size(), "应当只剩一个 tag 选项：" + normalized);
        assertEquals("tag", normalized.options().get(0).type());
        assertEquals("forge:ingots/iron", normalized.options().get(0).id());
    }

    @Test
    @DisplayName("★ 表示不了的槽位返回 null，而不是截断成一份缺东西的表")
    void unrepresentableSlotReturnsNull() {
        // 超过 IngredientNormalizer.MAX_UNEXPLAINED_OPTIONS（64）且没有标签能解释
        List<String> many = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "somemod:item_" + i)
                .toList();

        assertNull(CTX.normalize(RawSlot.items(many)),
                "读不懂就必须说读不懂 —— 返回一份截断的列表会让下游以为原料就这些");
    }

    @Test
    @DisplayName("流体槽位保留 mB 数量（漏掉数量会让产线按 1mB 算）")
    void fluidSlotKeepsAmount() {
        Models.Ingredient normalized = CTX.normalize(RawSlot.fluids(1000, List.of("minecraft:lava")));

        assertEquals("fluid", normalized.kind());
        assertEquals(1000, normalized.count());
        assertEquals("tag", normalized.options().get(0).type(), "有标签就该还原成标签：" + normalized);
    }

    @Test
    @DisplayName("RawSlot 不可变且拒绝 null 元素（它要跨线程共享）")
    void rawSlotIsImmutable() {
        List<String> mutable = new java.util.ArrayList<>(List.of("minecraft:iron_ingot"));
        RawSlot slot = RawSlot.items(mutable);
        mutable.add("minecraft:gold_ingot");

        assertEquals(List.of("minecraft:iron_ingot"), slot.ids(), "构造之后改原列表不该影响它");
        assertThrows(NullPointerException.class, () -> new RawSlot("item", 1,
                java.util.Arrays.asList("minecraft:iron_ingot", null)),
                "null 元素必须在构造时就炸，而不是拖到比较时才 NPE");
    }

    @Test
    @DisplayName("空槽位能被识别出来（上层据此跳过，而不是报一个空原料出去）")
    void emptySlotIsDetectable() {
        assertTrue(RawSlot.items(List.of()).isEmpty());
        assertTrue(RawSlot.fluids(500, List.of()).isEmpty());
    }
}
