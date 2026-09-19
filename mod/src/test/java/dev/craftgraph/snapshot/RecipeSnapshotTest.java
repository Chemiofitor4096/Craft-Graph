package dev.craftgraph.snapshot;

import dev.craftgraph.api.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照索引的测试。
 *
 * 全部不依赖 Minecraft —— 这正是把索引逻辑做成纯 Java 的目的：
 * 最容易出错的部分可以在几毫秒内测完，不用启动游戏。
 */
class RecipeSnapshotTest {

    // ---------------------------------------------------------------- 测试数据

    private static Models.ItemStack item(String id, int count) {
        return new Models.ItemStack(id, count, null);
    }

    private static Models.Ingredient itemIng(int count, Models.Option... options) {
        return new Models.Ingredient("item", count, List.of(options));
    }

    private static Models.Recipe recipe(String id, String type,
                                        List<Models.Ingredient> inputs,
                                        List<Models.ItemStack> outputs) {
        return new Models.Recipe(id, type, null, inputs, outputs, List.of(), List.of(), null, null, null, "vanilla", false);
    }

    /**
     * 一份刻意设计的小数据集，覆盖所有容易出错的形态：
     * 标签输入、概率产出、opaque 配方、多方案、同一物品被两种方式引用。
     */
    private static RecipeSnapshot sample() {
        Map<String, List<String>> tags = Map.of(
                "forge:ingots/iron", List.of("minecraft:iron_ingot", "othermod:iron_ingot"),
                "forge:ores/iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"));

        Models.Recipe smelting = recipe("minecraft:iron_ingot_from_smelting",
                "minecraft:smelting",
                List.of(itemIng(1, Models.Option.tag("forge:ores/iron"))),
                List.of(item("minecraft:iron_ingot", 1)));

        Models.Recipe blockFromIngots = recipe("minecraft:iron_block",
                "minecraft:crafting",
                List.of(itemIng(9, Models.Option.tag("forge:ingots/iron"))),
                List.of(item("minecraft:iron_block", 1)));

        Models.Recipe blockToIngots = recipe("minecraft:iron_ingot_from_block",
                "minecraft:crafting",
                List.of(itemIng(1, Models.Option.item("minecraft:iron_block"))),
                List.of(item("minecraft:iron_ingot", 9)));

        // 概率产出：产物只出现在 chanceOutputs 里
        Models.Recipe crushing = new Models.Recipe("create:crushing", "create:crushing", "Crushing",
                List.of(itemIng(1, Models.Option.item("minecraft:iron_ore"))),
                List.of(item("create:crushed_iron", 1)),
                List.of(),
                List.of(new Models.ChanceOutput(item("minecraft:iron_nugget", 1), 0.75)),
                "create:crushing_wheel", 100, null, "vanilla", false);

        // 读不懂的配方：inputs 是空的，但必须仍然能被查到，否则上层会以为这物品凭空来的
        Models.Recipe opaque = new Models.Recipe("somemod:alloy", "somemod:alloy_smelting", null,
                List.of(), List.of(item("somemod:tungsten_steel", 1)),
                List.of(), List.of(), "somemod:smelter", null, null, "vanilla", true);

        // 同一个槽位里两个选项都指向同一物品 —— 索引里不能出现两条重复
        Models.Recipe dupOptions = recipe("test:dup_options", "minecraft:crafting",
                List.of(new Models.Ingredient("item", 1, List.of(
                        Models.Option.item("minecraft:iron_ingot"),
                        Models.Option.tag("forge:ingots/iron")))),
                List.of(item("test:thing", 1)));

        return RecipeSnapshot.builder(7)
                .tags(tags)
                .registry("items", List.of("minecraft:iron_ore", "minecraft:iron_ingot",
                        "minecraft:iron_block", "create:crushed_iron"))
                .displayNames(Map.of("minecraft:iron_ingot", "铁锭"))
                .addRecipe(smelting)
                .addRecipe(blockFromIngots)
                .addRecipe(blockToIngots)
                .addRecipe(crushing)
                .addRecipe(opaque)
                .addRecipe(dupOptions)
                .build();
    }

    // ---------------------------------------------------------------- 基本索引

    @Test
    @DisplayName("按产出查：能查到直接产出的配方")
    void producingByItem() {
        RecipeSnapshot s = sample();
        List<String> ids = s.recipesProducing("item", "minecraft:iron_ingot").stream().map(Models.Recipe::id).toList();
        assertTrue(ids.contains("minecraft:iron_ingot_from_smelting"), ids.toString());
        assertTrue(ids.contains("minecraft:iron_ingot_from_block"), ids.toString());
    }

    @Test
    @DisplayName("按产出查：概率产出也算产出，否则这条配方等于不存在")
    void producingIncludesChanceOutputs() {
        RecipeSnapshot s = sample();
        List<String> ids = s.recipesProducing("item", "minecraft:iron_nugget").stream().map(Models.Recipe::id).toList();
        assertEquals(List.of("create:crushing"), ids);
    }

    @Test
    @DisplayName("读不懂的配方仍然能被查到（不能被静默丢掉）")
    void opaqueRecipeIsStillIndexed() {
        RecipeSnapshot s = sample();
        List<Models.Recipe> found = s.recipesProducing("item", "somemod:tungsten_steel");
        assertEquals(1, found.size());
        assertTrue(found.get(0).opaque(), "必须保留 opaque 标记，上层才知道它读不懂");
        assertEquals(List.of(), found.get(0).inputs(), "读不懂时 inputs 为空，但 opaque=true 让上层能区分");
    }

    // ---------------------------------------------------------------- 倒排索引（最关键）

    @Test
    @DisplayName("按输入查：标签必须被展开，否则会漏掉绝大多数结果")
    void consumingExpandsTags() {
        RecipeSnapshot s = sample();
        // minecraft:iron_block 的输入是标签 #forge:ingots/iron，不是直接的 iron_ingot。
        // 建索引时没展开标签的话，这里就查不到它。
        List<String> ids = s.recipesConsuming("item", "minecraft:iron_ingot").stream().map(Models.Recipe::id).toList();
        assertTrue(ids.contains("minecraft:iron_block"),
                "用 #forge:ingots/iron 作输入的配方必须能被 iron_ingot 查到，实际：" + ids);

        // 标签的另一个成员也要能查到
        List<String> other = s.recipesConsuming("item", "othermod:iron_ingot").stream().map(Models.Recipe::id).toList();
        assertTrue(other.contains("minecraft:iron_block"), other.toString());
    }

    @Test
    @DisplayName("按输入查：标签本身也能直接查")
    void consumingByTagDirectly() {
        RecipeSnapshot s = sample();
        List<String> ids = s.recipesConsumingTag("forge:ores/iron").stream().map(Models.Recipe::id).toList();
        assertEquals(List.of("minecraft:iron_ingot_from_smelting"), ids);
    }

    @Test
    @DisplayName("按输入查：同一配方不会因为多选项命中而重复出现")
    void consumingDeduplicates() {
        RecipeSnapshot s = sample();
        List<String> ids = s.recipesConsuming("item", "minecraft:iron_ingot").stream().map(Models.Recipe::id).toList();
        long dupCount = ids.stream().filter("test:dup_options"::equals).count();
        assertEquals(1, dupCount, "同一槽位的两个选项都指向 iron_ingot，索引里只能有一条：" + ids);
    }

    @Test
    @DisplayName("按输入查：未涉及的物品返回空而不是报错")
    void consumingUnknownReturnsEmpty() {
        RecipeSnapshot s = sample();
        assertEquals(List.of(), s.recipesConsuming("item", "nonexistent:nothing"));
    }

    @Test
    @DisplayName("物品与流体用 kind 区分，同名不串")
    void itemAndFluidDoNotCollide() {
        Models.Recipe fluidRecipe = new Models.Recipe("test:fluid", "test:mixing", null,
                List.of(new Models.Ingredient("fluid", 1000,
                        List.of(Models.Option.fluid("test:water")))),
                List.of(item("test:output", 1)),
                List.of(), List.of(), null, 20, null, "vanilla", false);

        RecipeSnapshot s = RecipeSnapshot.builder(1).addRecipe(fluidRecipe).build();

        assertEquals(1, s.recipesConsuming("fluid", "test:water").size());
        assertEquals(0, s.recipesConsuming("item", "test:water").size(),
                "流体和物品同 id 时不能互相串到对方的索引里");
    }

    // ---------------------------------------------------------------- 标签

    @Test
    @DisplayName("标签展开：不存在返回 null，与「存在但为空」区分")
    void expandTagDistinguishesMissingFromEmpty() {
        RecipeSnapshot s = sample();
        assertNotNull(s.expandTag("forge:ingots/iron"));
        assertNull(s.expandTag("not:a_tag"));
    }

    @Test
    @DisplayName("反向标签索引：能查出物品属于哪些标签")
    void tagsContaining() {
        RecipeSnapshot s = sample();
        assertTrue(s.tagsContaining("minecraft:iron_ingot").contains("forge:ingots/iron"));
    }

    // ---------------------------------------------------------------- 组合查询

    @Test
    @DisplayName("组合查询：output + type 取交集")
    void queryCombinesFilters() {
        RecipeSnapshot s = sample();
        Models.RecipeQueryPage page = s.queryRecipes("minecraft:iron_ingot", null, "minecraft:crafting", 0, 50);
        assertEquals(1, page.total(), "iron_ingot 的两条配方里只有一条是 crafting");
        assertEquals("minecraft:iron_ingot_from_block", page.recipes().get(0).id());
    }

    @Test
    @DisplayName("分页：total 是过滤后的总数，不是本页数量")
    void queryPaginationReportsTotal() {
        RecipeSnapshot s = sample();
        Models.RecipeQueryPage page = s.queryRecipes("minecraft:iron_ingot", null, null, 0, 1);
        assertEquals(2, page.total());
        assertEquals(1, page.recipes().size());

        Models.RecipeQueryPage second = s.queryRecipes("minecraft:iron_ingot", null, null, 1, 1);
        assertEquals(2, second.total());
        assertFalse(second.recipes().get(0).id().equals(page.recipes().get(0).id()), "第二页应是另一条");
    }

    @Test
    @DisplayName("快照分页：最后一页 nextCursor 为 null")
    void snapshotPaginationTerminates() {
        RecipeSnapshot s = sample();
        Models.SnapshotPage first = s.snapshotPage(0, 4);
        assertEquals(6, first.total());
        assertEquals(4, first.nextCursor());

        Models.SnapshotPage last = s.snapshotPage(4, 4);
        assertNull(last.nextCursor(), "最后一页必须返回 null，否则调用方会无限循环");
        assertEquals(2, last.recipes().size());
    }

    @Test
    @DisplayName("快照分页：游标越界不抛异常")
    void snapshotPaginationClampsOutOfRangeCursor() {
        RecipeSnapshot s = sample();
        Models.SnapshotPage page = s.snapshotPage(9999, 10);
        assertEquals(0, page.recipes().size());
        assertNull(page.nextCursor());
    }

    // ---------------------------------------------------------------- 注册表

    @Test
    @DisplayName("注册表查询：按 id 和显示名都能匹配")
    void registrySearchMatchesIdAndDisplayName() {
        RecipeSnapshot s = sample();
        // 注册表里有 iron_ore / iron_ingot / iron_block 三个含 "iron_" 的；
        // create:crushed_iron 不含 "iron_"（后面没有下划线），不该被匹配到
        assertEquals(3, s.registry("items", "iron_", 0, 50).total());
        assertEquals(1, s.registry("items", "铁锭", 0, 50).total(), "应能按中文显示名搜到");
    }

    @Test
    @DisplayName("注册表查询：没有显示名时回退成 id")
    void registryFallsBackToId() {
        RecipeSnapshot s = sample();
        assertEquals("minecraft:iron_ore", s.displayName("minecraft:iron_ore"));
        assertEquals("铁锭", s.displayName("minecraft:iron_ingot"));
    }
}
