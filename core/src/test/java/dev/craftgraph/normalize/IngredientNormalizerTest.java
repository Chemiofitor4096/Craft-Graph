package dev.craftgraph.normalize;

import dev.craftgraph.api.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 槽位归一化的测试。
 *
 * 这里验的是「标签信息能不能还原」—— 一件在 Minecraft API 上看起来不可能的事
 * （Ingredient 不告诉你它是不是标签），靠反查标签表做到了。
 * 逻辑全在这个包里，所以不用启动游戏就能验。
 */
class IngredientNormalizerTest {

    private static final TagIndex TAGS = TagIndex.of(Map.of(
            "forge:ingots/iron", List.of("minecraft:iron_ingot", "othermod:iron_ingot"),
            "minecraft:planks", List.of("minecraft:oak_planks", "minecraft:birch_planks", "minecraft:spruce_planks"),
            "forge:ores/iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            // 与 forge 那个成员完全相同。用来验证同名选择是确定性的
            "c:iron_ingots", List.of("minecraft:iron_ingot", "othermod:iron_ingot"),
            // 单成员标签：边界情况
            "test:single", List.of("test:only_one")));

    // ---------------------------------------------------------------- 标签还原

    @Test
    @DisplayName("物品集合恰好等于一个标签时，还原成标签而不是列出所有成员")
    void restoresExactTag() {
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("minecraft:iron_ingot", "othermod:iron_ingot"), TAGS);

        assertNotNull(ing);
        assertEquals(1, ing.options().size(), "应该只产生一个标签选项，而不是两个物品选项");
        assertEquals("tag", ing.options().get(0).type());
        assertEquals("forge:ingots/iron", ing.options().get(0).id());
    }

    @Test
    @DisplayName("标签成员顺序不同也能匹配上")
    void tagMatchIgnoresOrder() {
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("othermod:iron_ingot", "minecraft:iron_ingot"), TAGS);
        assertNotNull(ing);
        assertEquals("tag", ing.options().get(0).type());
    }

    @Test
    @DisplayName("多个同名标签时优先 forge 命名空间，保证结果可复现")
    void picksDeterministicTagAmongDuplicates() {
        // forge:ingots/iron 和 c:iron_ingots 成员完全一样（NeoForge 里这是两个约定标签）。
        // 必须每次选同一个，否则同一查询两次跑出来标签名不同，用户会以为数据在乱跳。
        for (int i = 0; i < 5; i++) {
            Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                    List.of("minecraft:iron_ingot", "othermod:iron_ingot"), TAGS);
            assertEquals("forge:ingots/iron", ing.options().get(0).id(),
                    "forge: 是大家更认得的约定，应该优先于 c:");
        }
    }

    @Test
    @DisplayName("标签探测不受集合迭代顺序影响")
    void tagDetectionIsOrderIndependent() {
        // Set.of 的迭代顺序未定义。如果实现只拿「任意一个元素」去探测标签，
        // 探测到不在任何标签里的元素时就会漏匹配 —— 这条测试专门盯这个。
        java.util.Set<String> withNonTaggedFirst = new java.util.LinkedHashSet<>(
                List.of("minecraft:iron_ingot", "othermod:iron_ingot"));

        for (int i = 0; i < 20; i++) {
            Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                    new ArrayList<>(withNonTaggedFirst), TAGS);
            assertEquals("forge:ingots/iron", ing.options().get(0).id(), "第 " + i + " 次结果不稳定");
        }

        // 关键场景：集合里有一个不属于任何标签的元素。
        // 若实现只看第一个元素，恰好拿到那个元素时就会漏掉本可匹配的标签。
        TagIndex tags = TagIndex.of(Map.of("test:group", List.of("a:1", "a:2")));
        assertEquals("test:group", tags.findContained(java.util.Set.of("a:1", "a:2", "untagged:thing")),
                "集合里有不在任何标签里的元素时，仍然应该找到被包含的标签");
    }

    @Test
    @DisplayName("单个物品不还原成标签（除非存在单成员标签）")
    void singleItemStaysItem() {
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 9,
                List.of("minecraft:iron_ingot"), TAGS);
        assertNotNull(ing);
        assertEquals(1, ing.options().size());
        assertEquals("item", ing.options().get(0).type(),
                "单个物品不该被当成标签，否则会把「就要这个」误解成「这类都行」");
    }

    @Test
    @DisplayName("标签成员是物品集合的真子集时，还原成「标签 + 补充物品」")
    void restoresTagPlusExtraItems() {
        // 某个配方：接受 #forge:ingots/iron 的任意一种，外加一个特殊锭
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("minecraft:iron_ingot", "othermod:iron_ingot", "special:star_ingot"), TAGS);

        assertNotNull(ing);
        assertEquals(2, ing.options().size(), "应该是「一个标签 + 一个补充物品」：" + ing.options());
        assertEquals("tag", ing.options().get(0).type());
        assertEquals("item", ing.options().get(1).type());
        assertEquals("special:star_ingot", ing.options().get(1).id());
    }

    @Test
    @DisplayName("补充物品部分不能把标签成员重复列一遍")
    void doesNotRepeatTagMembersAsExtras() {
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("minecraft:iron_ingot", "othermod:iron_ingot", "special:star_ingot"), TAGS);

        List<String> ids = ing.options().stream().map(Models.Option::id).toList();
        assertTrue(ids.contains("special:star_ingot"));
        assertTrue(!ids.contains("minecraft:iron_ingot"),
                "标签已经覆盖了 iron_ingot，不该再作为补充物品列一遍：" + ids);
    }

    @Test
    @DisplayName("没有标签能解释时逐个列出物品")
    void fallsBackToItemList() {
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("a:one", "b:two", "c:three"), TAGS);

        assertNotNull(ing);
        assertEquals(3, ing.options().size());
        assertTrue(ing.options().stream().allMatch(o -> "item".equals(o.type())));
    }

    @Test
    @DisplayName("多方案槽位（几个模组的同类物品）能正常表达")
    void expressesModdedAlternatives() {
        TagIndex tags = TagIndex.of(Map.of(
                "forge:dusts/copper", List.of("thermal:copper_dust", "mekanism:copper_dust", "create:copper_dust")));
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("thermal:copper_dust", "mekanism:copper_dust", "create:copper_dust"), tags);

        assertNotNull(ing);
        assertEquals("tag", ing.options().get(0).type());
        assertEquals("forge:dusts/copper", ing.options().get(0).id());
    }

    // ---------------------------------------------------------------- 必须诚实标记的情况

    @Test
    @DisplayName("候选过多且无标签可解释时返回 null，而不是硬塞一份荒谬的原料表")
    void refusesUnrepresentableWildcardSlot() {
        // 模拟「任意物品」槽位：getItems() 返回上千个候选
        List<String> huge = IntStream.range(0, 5000).mapToObj(i -> "mod:item_" + i).toList();

        assertNull(IngredientNormalizer.normalize("item", 1, huge, TAGS),
                "列 5000 个候选不如直接说读不懂 —— 硬塞进 JSON 会爆炸，" +
                        "而且下游会拿一份看起来精确、实际荒谬的原料表去建产线");
    }

    @Test
    @DisplayName("候选数刚好在上限内仍然正常展开")
    void acceptsListAtTheLimit() {
        int n = IngredientNormalizer.MAX_UNEXPLAINED_OPTIONS;
        List<String> items = new ArrayList<>();
        for (int i = 0; i < n; i++) items.add("mod:item_" + i);

        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1, items, TAGS);
        assertNotNull(ing);
        assertEquals(n, ing.options().size());
    }

    @Test
    @DisplayName("空槽位返回 null（而不是产生一个「不需要原料」的空槽位）")
    void emptySlotReturnsNull() {
        assertNull(IngredientNormalizer.normalize("item", 1, List.of(), TAGS),
                "空槽位如果被当成合法值传给下游，会变成「这配方不要原料」的错觉");
    }

    @Test
    @DisplayName("重复的物品 id 会被去重")
    void deduplicatesItems() {
        Models.Ingredient ing = IngredientNormalizer.normalize("item", 1,
                List.of("a:one", "a:one", "b:two"), TAGS);
        assertEquals(2, ing.options().size(), "同一个物品不该出现两次：" + ing.options());
    }

    // ---------------------------------------------------------------- 数量与 kind 透传

    @Test
    @DisplayName("数量和 kind 原样透传")
    void passesThroughCountAndKind() {
        Models.Ingredient fluid = IngredientNormalizer.normalize("fluid", 1000,
                List.of("minecraft:water"), TAGS);
        assertEquals("fluid", fluid.kind());
        assertEquals(1000, fluid.count());

        Models.Ingredient item = IngredientNormalizer.normalize("item", 9,
                List.of("minecraft:iron_ingot"), TAGS);
        assertEquals("item", item.kind());
        assertEquals(9, item.count());
    }

    // ---------------------------------------------------------------- TagIndex 本身

    @Test
    @DisplayName("TagIndex：findExact 在集合不等时返回 null")
    void findExactRejectsPartialMatch() {
        assertNull(TAGS.findExact("minecraft:iron_ingot"), "只有一个成员，不等于双成员标签");
        assertNull(TAGS.findExact("minecraft:iron_ingot", "othermod:iron_ingot", "extra:thing"),
                "多了一个成员就不是精确匹配了（这种情况该走 findContained）");
        assertNull(TAGS.findExact("no:such_item"));
    }

    @Test
    @DisplayName("TagIndex：findContained 取最大的可容纳标签")
    void findContainedPrefersLargest() {
        TagIndex tags = TagIndex.of(Map.of(
                "small", List.of("a:1", "a:2"),
                "big", List.of("a:1", "a:2", "a:3")));

        assertEquals("big", tags.findContained(java.util.Set.of("a:1", "a:2", "a:3", "a:4")),
                "应该选能装下的最大标签");
        assertEquals("small", tags.findContained(java.util.Set.of("a:1", "a:2", "a:9")));
    }

    @Test
    @DisplayName("TagIndex：空集合不会误匹配")
    void findRejectsEmptySet() {
        assertNull(TAGS.findExact(java.util.Set.of()));
        assertNull(TAGS.findContained(java.util.Set.of()));
    }
}
