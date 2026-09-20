package dev.craftgraph.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 产出概率分类器的测试。
 *
 * <h2>为什么边界要一条条钉住</h2>
 *
 * 这个判据决定一个产出进 {@code outputs} 还是 {@code chanceOutputs}，而两者在下游的算法不同：
 * 前者直接乘合成次数，后者要乘概率。**判错方向都有害** ——
 * 必然产出被当成概率产出会把产量算少，反之会算多，而两种错误在报告里都只表现为一个数字。
 *
 * <p>数值来自真实的 Create 配方数据：
 * `chance: 0.75`（粉碎副产）、`chance: 0.05`（洗涤出种子）、不写 chance（默认 1.0），
 * 以及 sequenced_assembly 里那种 `chance: 120.0`（不是概率，是权重）。
 */
class ResultChanceTest {

    @Test
    @DisplayName("恰好 1.0 → 必然产出（Create 不写 chance 时的默认值）")
    void exactlyOneIsGuaranteed() {
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classify(1.0f));
    }

    @Test
    @DisplayName("(0,1) 之间 → 概率产出")
    void betweenZeroAndOneIsProbabilistic() {
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classify(0.75f));
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classify(0.05f));
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classify(0.9999f));
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classify(0.0001f));
    }

    @Test
    @DisplayName("0 或负数 → 丢掉（Create 用它表示这个产出被禁用）")
    void zeroAndNegativeAreNever() {
        assertEquals(ResultChance.Kind.NEVER, ResultChance.classify(0f));
        assertEquals(ResultChance.Kind.NEVER, ResultChance.classify(-1f));
    }

    @Test
    @DisplayName("★ 大于 1 → 必然产出，而不是钳到 1 或按权重处理")
    void aboveOneIsGuaranteed() {
        // 这个值在 ProcessingRecipe 里不该出现（它只可能来自 sequenced_assembly 的权重语义）。
        // 真出现了说明理解有偏差，此时宁可多报一个产出也不要按概率报 ——
        // 按概率报会把「必然产出」算成期望值，悄悄把产量算少，而且看不出来。
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classify(120.0f));
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classify(1.0001f));
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classify(Float.MAX_VALUE));
    }

    @Test
    @DisplayName("★ NaN → 必然产出（绝不放进 chanceOutputs）")
    void nanIsGuaranteed() {
        // 放进去的话，期望值计算会整个变成 NaN，而那种失败从报告里几乎看不出来：
        // 只是数字变成了 NaN 而不是少了一个数。所以这里取「不丢数据」。
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classify(Float.NaN));
    }

    @Test
    @DisplayName("三类互斥且穷尽（任意 float 都恰好落一类）")
    void classifyIsTotal() {
        float[] samples = { Float.NaN, Float.NEGATIVE_INFINITY, -1f, 0f, 0.5f, 1f, 2f, Float.POSITIVE_INFINITY };
        for (float s : samples) {
            ResultChance.Kind k = ResultChance.classify(s);
            // 断言不为 null 即证明分类器对任何输入都有返回，不会漏分支
            org.junit.jupiter.api.Assertions.assertNotNull(k, "classify(" + s + ") 返回了 null");
        }
    }

    // ---------------------------------------------------------------- 权重归一化
    //
    // 这一组覆盖 Create 的 sequenced_assembly：它的 chance 是**权重不是概率**。
    // 数值全部来自真实配方数据。

    @Test
    @DisplayName("★ 精准机械动力的权重池：120/150 → 概率产出")
    void realPrecisionMechanismPool() {
        // 实测池子 [120, 8, 8, 5, 3, 2, 2, 1, 1]，权重和 150。
        // 主产物 120/150 = 80%，其余是按比例的废料。
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classifyWeight(120f, 150f));
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classifyWeight(8f, 150f));
        assertEquals(ResultChance.Kind.PROBABILISTIC, ResultChance.classifyWeight(1f, 150f));
    }

    @Test
    @DisplayName("★ 池子里只有一项 → 必然产出（sturdy_sheet / track 就是这样）")
    void singleEntryPoolIsGuaranteed() {
        // 这两条配方的 results 只有一项、权重 1，归一化后概率恰好 1.0。
        // 如果判成概率产出，下游会按期望值算 —— 而它其实每次都产出。
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classifyWeight(1f, 1f));
    }

    @Test
    @DisplayName("权重和 ≤ 0 → NEVER（没法归一化，不能当成必然）")
    void nonPositiveTotalIsNever() {
        // 当成必然会让每条产出都变 100%；当成概率会得到除零后的 Infinity。
        // 两种都是错的，只能说「读不到」。
        assertEquals(ResultChance.Kind.NEVER, ResultChance.classifyWeight(1f, 0f));
        assertEquals(ResultChance.Kind.NEVER, ResultChance.classifyWeight(1f, -5f));
    }

    @Test
    @DisplayName("★ Infinity 权重 → 必然产出，且不会污染其他条目")
    void infiniteWeightIsGuaranteed() {
        // Inf / 有限和 = Inf → classify 判为必然。这正是想要的：
        // 无限大的权重本来就压过所有其他条目。
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classifyWeight(Float.POSITIVE_INFINITY, 100f));
    }

    @Test
    @DisplayName("★ NaN 权重 → 必然产出（不丢数据）")
    void nanWeightIsGuaranteed() {
        assertEquals(ResultChance.Kind.GUARANTEED, ResultChance.classifyWeight(Float.NaN, 100f));
    }

    @Test
    @DisplayName("权重为 0 或负数 → NEVER（被禁用的产出）")
    void zeroWeightIsNever() {
        assertEquals(ResultChance.Kind.NEVER, ResultChance.classifyWeight(0f, 100f));
        assertEquals(ResultChance.Kind.NEVER, ResultChance.classifyWeight(-3f, 100f));
    }
}
