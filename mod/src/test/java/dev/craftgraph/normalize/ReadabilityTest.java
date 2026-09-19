package dev.craftgraph.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「读不懂」判定规则的测试。
 *
 * 这个判断决定 AI 看到的是「这配方读不懂」还是「这配方不需要材料」。
 * 后者是静默的错误答案 —— 比读不懂危险得多，所以规则本身值得单独测。
 *
 * <p>参数是**数量**而不是列表，因为判据是「有没有读到东西」：
 * Create 的洗涤配方只有概率产出，用「必然产出列表为空」判会把它们误判成读不懂。
 * 这里有一条用例专门钉住这一点。
 */
class ReadabilityTest {

    @Test
    @DisplayName("输入产出都正常 → 可读")
    void readableWhenBothPresent() {
        assertFalse(Readability.isOpaque(1, 1, false));
    }

    @Test
    @DisplayName("槽位无法解析 → 读不懂")
    void opaqueWhenSlotUnparseable() {
        // 某个槽位有上千候选且没有标签能解释它（比如「任意物品」槽位）。
        // 硬塞进协议会得到一份「看起来精确、实际荒谬」的原料表。
        assertTrue(Readability.isOpaque(1, 1, true));
    }

    @Test
    @DisplayName("没有产出 → 读不懂")
    void opaqueWhenNoOutput() {
        assertTrue(Readability.isOpaque(1, 0, false));
    }

    @Test
    @DisplayName("没有输入 → 读不懂（真实配方不可能不消耗东西）")
    void opaqueWhenNoInput() {
        // 这是实测踩出来的关键一条。
        //
        // 盔甲纹饰锻造：Minecraft 的 SmithingRecipe 不覆盖 getIngredients()，
        // 继承的默认实现返回空列表。如果判定只看「产出是否为空」，
        // 那它会是「有产出、无输入、opaque=false」，也就是**看起来不需要任何材料**。
        assertTrue(Readability.isOpaque(0, 1, false),
                "无输入必须判为读不懂，否则 AI 会以为这配方不要材料");
    }

    @Test
    @DisplayName("输入产出都没有 → 读不懂（特殊合成就是这样）")
    void opaqueWhenNeitherPresent() {
        // crafting_special_* 这类代码驱动的配方：逻辑写在 Java 里，
        // getIngredients() 返回空、getResultItem() 返回 EMPTY。这是设计如此。
        assertTrue(Readability.isOpaque(0, 0, false));
    }

    @Test
    @DisplayName("★ 只有概率产出 → 可读（Create 的洗涤配方就是这样）")
    void readableWithOnlyProbabilisticOutputs() {
        // create:splashing 的产出全是 chance: 0.25 / 0.05，没有必然产出。
        // 如果判据是「必然产出列表为空」，这条明明读得很清楚的配方会被判成 opaque，
        // 然后产线计算会把它整条跳过 —— 而在整合包里这类配方很多。
        assertFalse(Readability.isOpaque(1, 2, false),
                "只有概率产出的配方必须算可读，数量由调用方把三类产出加起来");
    }

    @Test
    @DisplayName("★ 只有流体产出 → 可读")
    void readableWithOnlyFluidOutputs() {
        // create:emptying 除了物品还产出流体，模组机器配方里只出流体的情况也有
        assertFalse(Readability.isOpaque(1, 1, false));
    }

    // ---------------------------------------------------------------- 不产出 vs 读不到

    @Test
    @DisplayName("★ 适配器确认「本来就不产出」→ PRODUCES_NOTHING，不是读不懂")
    void declaredNoOutputIsNotOpaque() {
        // 燃料定义：输入（1 桶生物燃料）读到了，产出确实是空的。
        // 说它「读不懂」是错的 —— 我们明明读清楚了。
        assertEquals(Readability.Outcome.PRODUCES_NOTHING, Readability.outcome(1, 0, false, true));
    }

    @Test
    @DisplayName("★ 没有适配器作证时，产出为空仍然是读不懂（不许被降级成「不产出」）")
    void emptyOutputWithoutDeclarationIsStillOpaque() {
        // 这条是整件事的关键：把「读不到产出」误判成「本来就不产出」，
        // 会让 AI 说出「这配方不需要产出」—— 又是一个静默的错误答案。
        assertEquals(Readability.Outcome.OPAQUE, Readability.outcome(1, 0, false, false));
        assertEquals(Readability.Outcome.OPAQUE, Readability.outcome(0, 0, false, false));
    }

    @Test
    @DisplayName("有产出时，作证与否都不影响判定")
    void declaredNoOutputIgnoredWhenOutputsExist() {
        assertEquals(Readability.Outcome.READABLE, Readability.outcome(1, 1, false, true));
    }

    @Test
    @DisplayName("槽位解析失败优先于「不产出」")
    void slotFailureBeatsProducesNothing() {
        assertEquals(Readability.Outcome.OPAQUE, Readability.outcome(1, 0, true, true));
    }

    @Test
    @DisplayName("不产出的原因文案与「读不到产出」不同")
    void reasonDistinguishesProducesNothing() {
        assertEquals("不产出物品（燃料/配置类定义）", Readability.reason(1, 0, false, true));
        assertEquals("读不到产出", Readability.reason(1, 0, false, false));
    }

    // ---------------------------------------------------------------- 原因

    @Test
    @DisplayName("原因说明能区分「读不到输入」和「读不到产出」")
    void reasonDistinguishesCauses() {
        // 原因很重要：「读不懂 2%」这个比例本身不告诉你该不该优化。
        // 是物理限制（代码驱动的配方）还是我们的解析问题，得看原因。
        assertEquals("有槽位无法解析", Readability.reason(1, 1, true));
        assertEquals("输入和产出都读不到", Readability.reason(0, 0, false));
        assertEquals("读不到产出", Readability.reason(1, 0, false));
        assertEquals("读不到输入（配方用谓词而非声明式槽位）", Readability.reason(0, 1, false));
        assertEquals("", Readability.reason(1, 1, false));
    }

    @Test
    @DisplayName("槽位解析失败时优先报这个原因（它比缺输入产出更根本）")
    void slotFailureTakesPrecedence() {
        assertEquals("有槽位无法解析", Readability.reason(0, 0, true));
    }
}
