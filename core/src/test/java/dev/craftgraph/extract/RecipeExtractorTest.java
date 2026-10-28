package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.TagIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecipeExtractor} 的测试 —— **现在它住在 core，所以能脱离 Minecraft 测了**。
 *
 * <p>这是 SPI 的直接收益：以前这段逻辑在每个变体里各一份，只能靠编译 + 进游戏验证；
 * 现在用一个假平台就能断言「归一化、可读性、部分读取降级」这些真正的判断。
 */
class RecipeExtractorTest {

    // ---------------------------------------------------------------- 假平台

    private record FakePlatform(
            String type,
            List<RawSlot> ingredients,
            Models.ItemStack result,
            String toast) implements RecipePlatform<String> {

        @Override
        public String typeId(String recipe) {
            return type;
        }

        @Override
        public List<RawSlot> genericIngredients(String recipe) {
            return ingredients;
        }

        @Override
        public Models.ItemStack genericResult(String recipe) {
            return result;
        }

        @Override
        public String toastItemId(String recipe) {
            return toast;
        }
    }

    private static FakePlatform platform(List<RawSlot> ingredients, Models.ItemStack result) {
        return new FakePlatform("test:machine", ingredients, result, null);
    }

    private static RecipeExtractor<String> extractor(RecipeTypeAdapter<String> adapter) {
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(AdapterRegistry.Candidate.always("stub", () -> adapter)),
                modId -> true);
        return new RecipeExtractor<>(platform(
                List.of(RawSlot.items(List.of("minecraft:iron_ingot"))),
                Models.ItemStack.of("minecraft:gold_ingot", 1)),
                new ExtractionContext(TagIndex.empty(), TagIndex.empty()),
                registry);
    }

    /** 什么都不覆盖的适配器：所有字段都落回通用接口。 */
    private static final class StubAdapter implements RecipeTypeAdapter<String> {
        boolean complete = true;
        boolean declaresNoOutput = false;
        Integer duration;
        String machine;
        List<RawSlot> ingredients;
        List<Models.ItemStack> results;

        @Override
        public boolean handles(String recipe) {
            return true;
        }

        @Override
        public boolean complete(String recipe) {
            return complete;
        }

        @Override
        public boolean declaresNoOutput(String recipe) {
            return declaresNoOutput;
        }

        @Override
        public Integer duration(String recipe) {
            return duration;
        }

        @Override
        public String machine(String recipe) {
            return machine;
        }

        @Override
        public List<RawSlot> ingredients(String recipe) {
            return ingredients;
        }

        @Override
        public List<Models.ItemStack> results(String recipe) {
            return results;
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void readsGenericInputAndOutput() {
        Models.Recipe recipe = extractor(new StubAdapter()).extract("test:r", "recipe");

        assertEquals("test:r", recipe.id());
        assertEquals("test:machine", recipe.type());
        assertEquals(1, recipe.inputs().size());
        assertEquals("minecraft:iron_ingot", recipe.inputs().get(0).options().get(0).id());
        assertEquals(1, recipe.outputs().size());
        assertEquals("minecraft:gold_ingot", recipe.outputs().get(0).item());
        assertFalse(recipe.opaque());
        assertFalse(recipe.producesNothing());
    }

    @Test
    void adapterCanOverrideIngredients() {
        StubAdapter adapter = new StubAdapter();
        adapter.ingredients = List.of(RawSlot.items(List.of("minecraft:copper_ingot")));

        Models.Recipe recipe = extractor(adapter).extract("test:r", "recipe");

        assertEquals("minecraft:copper_ingot", recipe.inputs().get(0).options().get(0).id());
    }

    @Test
    void partialReadBecomesOpaque() {
        StubAdapter adapter = new StubAdapter();
        adapter.complete = false;

        Models.Recipe recipe = extractor(adapter).extract("test:r", "recipe");

        assertTrue(recipe.opaque(), "适配器说只读懂了一部分时，整条必须标 opaque");
    }

    @Test
    void outputsWithoutInputsAreOpaque() {
        // 真实配方不可能不消耗东西 —— 有产出没输入 = 读不到输入
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(AdapterRegistry.Candidate.always("stub", StubAdapter::new)),
                modId -> true);
        RecipeExtractor<String> extractor = new RecipeExtractor<>(
                platform(List.of(), Models.ItemStack.of("minecraft:gold_ingot", 1)),
                new ExtractionContext(TagIndex.empty(), TagIndex.empty()),
                registry);

        assertTrue(extractor.extract("test:r", "recipe").opaque());
    }

    @Test
    void declaredNoOutputIsNotOpaque() {
        StubAdapter adapter = new StubAdapter();
        adapter.declaresNoOutput = true;

        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(AdapterRegistry.Candidate.always("stub", () -> adapter)),
                modId -> true);
        RecipeExtractor<String> extractor = new RecipeExtractor<>(
                platform(List.of(RawSlot.items(List.of("minecraft:iron_ingot"))), null),
                new ExtractionContext(TagIndex.empty(), TagIndex.empty()),
                registry);

        Models.Recipe recipe = extractor.extract("test:r", "recipe");

        assertTrue(recipe.producesNothing());
        assertFalse(recipe.opaque());
        assertTrue(recipe.outputs().isEmpty());
    }

    @Test
    void adapterSuppliesDurationAndMachine() {
        StubAdapter adapter = new StubAdapter();
        adapter.duration = 200;
        adapter.machine = "test:furnace";

        Models.Recipe recipe = extractor(adapter).extract("test:r", "recipe");

        assertEquals(200, recipe.duration());
        assertEquals("test:furnace", recipe.machine());
    }

    @Test
    void coverageCountsDurationAndMachine() {
        StubAdapter adapter = new StubAdapter();
        adapter.duration = 200;
        adapter.machine = "test:furnace";
        RecipeExtractor<String> extractor = extractor(adapter);
        extractor.extract("test:r", "recipe");

        assertEquals(1, extractor.coverage().total());
        assertEquals(1, extractor.coverage().withDuration());
        assertEquals(1, extractor.coverage().withMachine());
    }

    @Test
    void nullIdIsSkipped() {
        assertNull(extractor(new StubAdapter()).extract(null, "recipe"));
    }
}
