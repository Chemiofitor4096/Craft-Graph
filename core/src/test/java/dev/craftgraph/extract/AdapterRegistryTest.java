package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.extract.AdapterRegistry.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器登记处的语义测试。
 *
 * <p>配方类型 {@code T} 用 {@link String}：注册表是纯泛型的，
 * 测试里不需要、也不该出现任何 Minecraft 类型 —— 这本身就是 core 纯度的体现。
 *
 * <p>这里守的是几条出过事故的约定：惰性注册（没装的模组不能连类加载都炸）、
 * 运行时异常兜底（一个坏配方不能带崩快照）、null 与空列表的分发语义
 * （锻造的「产出占位符不能当真」就靠空列表赢过通用结果）。
 */
class AdapterRegistryTest {

    /**
     * 最小适配器：默认全部「不归我管」（返回 null），要测哪条语义就开哪个开关。
     */
    private static final class StubAdapter implements RecipeTypeAdapter<String> {
        final boolean handles;
        Integer duration;
        List<RawSlot> ingredientOverride;
        List<Models.ItemStack> resultOverride;
        Boolean declaresNoOutput;
        boolean failDuration;
        boolean failDeclaresNoOutput;

        StubAdapter(boolean handles) {
            this.handles = handles;
        }

        @Override
        public boolean handles(String recipe) {
            return handles;
        }

        @Override
        public Integer duration(String recipe) {
            if (failDuration) throw new IllegalStateException("boom");
            return duration;
        }

        @Override
        public List<RawSlot> ingredients(String recipe) {
            return ingredientOverride;
        }

        @Override
        public List<Models.ItemStack> results(String recipe) {
            return resultOverride;
        }

        @Override
        public boolean declaresNoOutput(String recipe) {
            if (failDeclaresNoOutput) throw new IllegalStateException("boom");
            return Boolean.TRUE.equals(declaresNoOutput);
        }
    }

    @Test
    @DisplayName("模组没装：候选不注册，查询得到「读不到」的 null —— 绝不是 0")
    void notLoadedCandidateIsInvisible() {
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(new Candidate<>("somemod", "Stub", () -> new StubAdapter(true))),
                modId -> false);

        assertTrue(registry.all().isEmpty());
        assertNull(registry.duration("x"));
        assertNull(registry.forRecipe("x"));
    }

    @Test
    @DisplayName("模组装了：正常注册与派发")
    void loadedCandidateDispatches() {
        StubAdapter stub = new StubAdapter(true);
        stub.duration = 200;
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(new Candidate<>("somemod", "Stub", () -> stub)),
                modId -> true);

        assertEquals(1, registry.all().size());
        assertSame(stub, registry.forRecipe("x"));
        assertEquals(200, registry.duration("x"));
    }

    @Test
    @DisplayName("工厂抛 Error（模拟类加载失败）：该候选被跳过，其余照常 —— 后果是少一个适配器")
    void throwingFactoryIsSkippedNotFatal() {
        StubAdapter survivor = new StubAdapter(true);
        survivor.duration = 10;
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(
                        new Candidate<>("broken", "Broken", () -> {
                            throw new NoClassDefFoundError("brokenmod:NotInstalled");
                        }),
                        new Candidate<>(null, "Survivor", () -> survivor)),
                modId -> true);

        assertEquals(1, registry.all().size());
        assertEquals(10, registry.duration("x"));
    }

    @Test
    @DisplayName("运行时异常：退回 fallback，失败逐次计数 —— 不是禁用整个适配器")
    void runtimeFailureFallsBackAndCounts() {
        StubAdapter stub = new StubAdapter(true);
        stub.failDuration = true;
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(new Candidate<>(null, "Stub", () -> stub)),
                modId -> true);

        assertNull(registry.duration("x"));
        assertNull(registry.duration("x"));
        assertEquals(2, registry.failures());
    }

    @Test
    @DisplayName("ingredients：适配器不接管时原样返回 generic（同一个实例）")
    void ingredientsNullOverrideReturnsGeneric() {
        StubAdapter stub = new StubAdapter(true);
        List<RawSlot> generic = List.of(new RawSlot("item", 1, List.of("minecraft:iron_ingot")));
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(new Candidate<>(null, "Stub", () -> stub)),
                modId -> true);

        assertSame(generic, registry.ingredients("x", generic));
    }

    @Test
    @DisplayName("results：适配器接管但不接管为「空列表」时，空列表赢过通用结果 —— 锻造占位符就靠它拦住")
    void resultsEmptyOverrideBeatsGenericPlaceholder() {
        StubAdapter overriding = new StubAdapter(true);
        overriding.resultOverride = List.of();
        StubAdapter noncommittal = new StubAdapter(true);
        List<Models.ItemStack> generic = List.of(Models.ItemStack.of("fake:placeholder", 1));
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(new Candidate<>(null, "Stub", () -> overriding)),
                modId -> true);
        AdapterRegistry<String> registry2 = new AdapterRegistry<>(
                List.of(new Candidate<>(null, "Stub", () -> noncommittal)),
                modId -> true);

        assertTrue(registry.results("x", generic).isEmpty());
        // 而「不归我管」的 null 必须退回通用结果 —— 两者混为一谈就是当年的假产物事故
        assertSame(generic, registry2.results("x", generic));
    }

    @Test
    @DisplayName("概率/流体产出没有通用回退：没有适配器时也是空列表（与 duration 的 null 语义刻意不同）")
    void chanceAndFluidHaveNoGenericFallback() {
        AdapterRegistry<String> registry = new AdapterRegistry<>(List.of(), modId -> true);

        assertTrue(registry.chanceResults("x").isEmpty());
        assertTrue(registry.fluidResults("x").isEmpty());
        assertTrue(registry.fluidIngredients("x").isEmpty());
    }

    @Test
    @DisplayName("declaresNoOutput：适配器异常时必须返回 false —— 宁可说「读不懂」也不能断言「不产出」")
    void declaresNoOutputFailureMeansFalse() {
        StubAdapter failing = new StubAdapter(true);
        failing.failDeclaresNoOutput = true;
        StubAdapter asserting = new StubAdapter(true);
        asserting.declaresNoOutput = true;
        AdapterRegistry<String> failingRegistry = new AdapterRegistry<>(
                List.of(new Candidate<>(null, "Stub", () -> failing)), modId -> true);
        AdapterRegistry<String> assertingRegistry = new AdapterRegistry<>(
                List.of(new Candidate<>(null, "Stub", () -> asserting)), modId -> true);

        assertFalse(failingRegistry.declaresNoOutput("x"));
        assertTrue(assertingRegistry.declaresNoOutput("x"));
        // 没有适配器作证时一律 false（按 opaque 处理），不能默认「不产出」
        assertFalse(new AdapterRegistry<String>(List.of(), modId -> true).declaresNoOutput("x"));
    }

    @Test
    @DisplayName("顺序有意义：先注册的先匹配")
    void firstMatchWins() {
        StubAdapter first = new StubAdapter(true);
        first.duration = 1;
        StubAdapter second = new StubAdapter(true);
        second.duration = 2;
        AdapterRegistry<String> registry = new AdapterRegistry<>(
                List.of(
                        new Candidate<>(null, "First", () -> first),
                        new Candidate<>(null, "Second", () -> second)),
                modId -> true);

        assertEquals(1, registry.duration("x"));
    }
}
