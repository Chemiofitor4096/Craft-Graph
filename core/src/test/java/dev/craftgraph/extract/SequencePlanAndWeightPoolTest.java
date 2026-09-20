package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 序列组装的三件纯逻辑：权重池归一化、遍数展开、耗时求和。
 *
 * <p>它们原先在适配器里（需要装了 Create 的实例才能验证），现在搬到了 core，
 * 用 fixtures 就能把边界全钉住 —— 包括那些**错了不会报错**的情形：
 * 权重和算错会让整池产出凭空消失，遍数算错会让原料表少一半。
 */
class SequencePlanAndWeightPoolTest {

    private static Models.ItemStack stack(String id, int count) {
        return new Models.ItemStack(id, count, null);
    }

    // ------------------------------------------------------------ 权重池

    @Test
    @DisplayName("★ 权重归一化成概率：120/8/8/5/3/2/2/1/1（精准机械动力的真实池子）")
    void normalisesWeights() {
        List<WeightPool.Classified> out = WeightPool.classify(List.of(
                new WeightPool.Entry(stack("create:precision_mechanism", 1), 120f),
                new WeightPool.Entry(stack("create:golden_sheet", 1), 8f),
                new WeightPool.Entry(stack("minecraft:iron_nugget", 1), 5f)));

        assertEquals(3, out.size());
        assertEquals(120f / 133f, out.get(0).probability(), 1e-6);
        assertEquals(8f / 133f, out.get(1).probability(), 1e-6);
        // 118/133 是废料，但每一项都得救回来 —— 少一项就等于谎报原料/产出
        assertEquals(ResultChance.Kind.PROBABILISTIC, out.get(0).kind());
    }

    @Test
    @DisplayName("★ Infinity 的权重判成必然产出，但不能把其他条目的比例压成 0")
    void infiniteWeightDoesNotDestroyTheRest() {
        // Inf 若进了权重和，和就成了 Inf，6/Inf 与 4/Inf 都是 0 → 两项都被判成「永不产出」而消失
        List<WeightPool.Classified> out = WeightPool.classify(List.of(
                new WeightPool.Entry(stack("mod:infinity_thing", 1), Float.POSITIVE_INFINITY),
                new WeightPool.Entry(stack("mod:six", 1), 6f),
                new WeightPool.Entry(stack("mod:four", 1), 4f)));

        assertEquals(3, out.size(), "三项都要在：" + out);
        assertEquals(ResultChance.Kind.GUARANTEED, out.get(0).kind());
        assertEquals(1f, out.get(0).probability(), 1e-6);
        assertEquals(0.6f, out.get(1).probability(), 1e-6, "剩下两项要按 6:4 保留比例");
        assertEquals(0.4f, out.get(2).probability(), 1e-6);
    }

    @Test
    @DisplayName("权重和 ≤ 0 时返回空（没法归一化，返回「必然」会让每一项都变成 100%）")
    void emptyWhenTotalWeightIsNotUsable() {
        assertTrue(WeightPool.classify(List.of()).isEmpty());
        assertTrue(WeightPool.classify(List.of(
                new WeightPool.Entry(stack("mod:thing", 1), 0f))).isEmpty());
    }

    @Test
    @DisplayName("权重 ≤ 0 的条目丢掉（Create 用它表示「这个产出被禁用了」）")
    void dropsDisabledEntries() {
        List<WeightPool.Classified> out = WeightPool.classify(List.of(
                new WeightPool.Entry(stack("mod:enabled", 1), 10f),
                new WeightPool.Entry(stack("mod:disabled", 1), 0f)));

        assertEquals(1, out.size());
        assertEquals("mod:enabled", out.get(0).stack().item());
    }

    @Test
    @DisplayName("空栈丢掉：池子里有一项读不出物品时不能报一个空物品出去")
    void skipsEntriesWithoutStack() {
        List<WeightPool.Classified> out = WeightPool.classify(List.of(
                new WeightPool.Entry(null, 10f),
                new WeightPool.Entry(stack("mod:thing", 1), 10f)));

        assertEquals(1, out.size());
        assertEquals("mod:thing", out.get(0).stack().item());
    }

    // ------------------------------------------------------------ 遍数展开

    private static final String TRANSITIONAL = "create:incomplete_precision_mechanism";

    private static SequencePlan.Step step(String ingredientId, int ticks) {
        return SequencePlan.Step.ofItemSlots(List.of(RawSlot.items(List.of(ingredientId))), ticks);
    }

    @Test
    @DisplayName("★ 每一步的原料按遍数重复（5 轮 × 1 步 = 5 个槽位）")
    void repeatsIngredientsPerLoop() {
        List<RawSlot> out = SequencePlan.itemInputs(
                List.of(RawSlot.items(List.of("forge:plates/gold"))),
                List.of(step("minecraft:iron_ingot", 100)),
                null, 5);

        assertEquals(1 + 5, out.size(), "基础物品 1 个 + 每一步原料 5 份：" + out);
        assertEquals("forge:plates/gold", out.get(0).ids().get(0));
        assertEquals("minecraft:iron_ingot", out.get(1).ids().get(0));
    }

    @Test
    @DisplayName("★ 中间产物被排除（否则原料表里会多出一个玩家拿不到的物品）")
    void excludesTransitionalItem() {
        List<RawSlot> out = SequencePlan.itemInputs(
                List.of(),
                List.of(step(TRANSITIONAL, 100), step("minecraft:gold_ingot", 100)),
                TRANSITIONAL, 2);

        assertEquals(2, out.size(), "只有黄金那一步该留下：" + out);
        assertTrue(out.stream().allMatch(s -> s.ids().contains("minecraft:gold_ingot")));
    }

    @Test
    @DisplayName("混合槽位（中间产物 + 真实原料）必须留下：含有就排除会丢掉原料")
    void keepsMixedSlots() {
        List<RawSlot> out = SequencePlan.itemInputs(
                List.of(),
                List.of(SequencePlan.Step.ofItemSlots(
                        List.of(RawSlot.items(List.of(TRANSITIONAL, "minecraft:gold_ingot"))), 100)),
                TRANSITIONAL, 1);

        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("遍数是 0 或负数时按 1 算（否则所有原料会凭空消失）")
    void loopsZeroMeansOne() {
        assertEquals(1, SequencePlan.itemInputs(
                List.of(), List.of(step("minecraft:iron_ingot", 100)), null, 0).size());
        assertEquals(1, SequencePlan.itemInputs(
                List.of(), List.of(step("minecraft:iron_ingot", 100)), null, -7).size());
    }

    @Test
    @DisplayName("流体输入同样按遍数展开")
    void expandsFluidInputs() {
        SequencePlan.Step withFluid = new SequencePlan.Step(
                List.of(),
                List.of(RawSlot.fluids(100, List.of("minecraft:lava"))),
                100);

        List<RawSlot> out = SequencePlan.fluidInputs(List.of(withFluid), 3);

        assertEquals(3, out.size());
        assertEquals(100, out.get(0).count());
    }

    // ------------------------------------------------------------ 耗时

    @Test
    @DisplayName("★ 总耗时 = 各步之和 × 遍数（对应「一条组装线跑完一个产出要多久」）")
    void totalDurationMultipliesByLoops() {
        Integer total = SequencePlan.totalDuration(
                List.of(step("minecraft:iron_ingot", 100), step("minecraft:gold_ingot", 50)), 5);

        assertEquals(750, total);
    }

    @Test
    @DisplayName("一步都读不到耗时 → null 而不是 0（0 会被下游当成「不用时间」）")
    void noDurationIsNullNotZero() {
        assertNull(SequencePlan.totalDuration(
                List.of(step("minecraft:iron_ingot", 0), step("minecraft:gold_ingot", 0)), 5));
    }

    @Test
    @DisplayName("部分步骤读不到耗时时，用读到的那些求和（不因为一步缺失就丢掉整条）")
    void partialDurationsStillSum() {
        assertEquals(200, SequencePlan.totalDuration(
                List.of(step("minecraft:iron_ingot", 100), step("minecraft:gold_ingot", 0)), 2));
    }
}
