package dev.craftgraph.normalize;

import dev.craftgraph.api.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「读不懂」判定规则的测试。
 *
 * 这个判断决定 AI 看到的是「这配方读不懂」还是「这配方不需要材料」。
 * 后者是静默的错误答案 —— 比读不懂危险得多，所以规则本身值得单独测。
 *
 * 测试用例全部来自真实游戏里的实测数据（见 doc 里的诊断输出）。
 */
class ReadabilityTest {

    private static Models.Ingredient someInput() {
        return new Models.Ingredient("item", 1, List.of(Models.Option.item("minecraft:iron_ingot")));
    }

    private static Models.ItemStack someOutput() {
        return new Models.ItemStack("minecraft:iron_block", 1, null);
    }

    @Test
    @DisplayName("输入产出都正常 → 可读")
    void readableWhenBothPresent() {
        assertFalse(Readability.isOpaque(List.of(someInput()), List.of(someOutput()), false));
    }

    @Test
    @DisplayName("槽位无法解析 → 读不懂")
    void opaqueWhenSlotUnparseable() {
        // 某个槽位有上千候选且没有标签能解释它（比如「任意物品」槽位）。
        // 硬塞进协议会得到一份「看起来精确、实际荒谬」的原料表。
        assertTrue(Readability.isOpaque(List.of(someInput()), List.of(someOutput()), true));
    }

    @Test
    @DisplayName("没有产出 → 读不懂")
    void opaqueWhenNoOutput() {
        assertTrue(Readability.isOpaque(List.of(someInput()), List.of(), false));
    }

    @Test
    @DisplayName("没有输入 → 读不懂（真实配方不可能不消耗东西）")
    void opaqueWhenNoInput() {
        // 这是实测踩出来的关键一条。
        //
        // 盔甲纹饰锻造：Minecraft 的 SmithingRecipe 不覆盖 getIngredients()，
        // 继承的默认实现返回空列表 —— 槽位信息藏在 isTemplateIngredient 这类谓词里。
        //
        // 如果判定只看「产出是否为空」，修复了取产物的 bug 之后这条配方会变成
        // 「有产出、无输入、opaque=false」，也就是**看起来不需要任何材料**。
        // 那是静默的错误答案：AI 会告诉玩家「纹饰随便就能做」。
        assertTrue(Readability.isOpaque(List.of(), List.of(someOutput()), false),
                "无输入必须判为读不懂，否则 AI 会以为这配方不要材料");
    }

    @Test
    @DisplayName("输入产出都没有 → 读不懂（特殊合成就是这样）")
    void opaqueWhenNeitherPresent() {
        // crafting_special_* 这类代码驱动的配方：逻辑写在 Java 里，
        // getIngredients() 返回空、getResultItem() 返回 EMPTY。这是设计如此。
        assertTrue(Readability.isOpaque(List.of(), List.of(), false));
    }

    // ---------------------------------------------------------------- 原因

    @Test
    @DisplayName("原因说明能区分「读不到输入」和「读不到产出」")
    void reasonDistinguishesCauses() {
        // 原因很重要：「读不懂 2%」这个比例本身不告诉你该不该优化。
        // 是物理限制（代码驱动的配方）还是我们的解析问题，得看原因。
        assertEquals("有槽位无法解析",
                Readability.reason(List.of(someInput()), List.of(someOutput()), true));
        assertEquals("输入和产出都读不到",
                Readability.reason(List.of(), List.of(), false));
        assertEquals("读不到产出",
                Readability.reason(List.of(someInput()), List.of(), false));
        assertEquals("读不到输入（配方用谓词而非声明式槽位）",
                Readability.reason(List.of(), List.of(someOutput()), false));
        assertEquals("", Readability.reason(List.of(someInput()), List.of(someOutput()), false));
    }

    @Test
    @DisplayName("槽位解析失败时优先报这个原因（它比缺输入产出更根本）")
    void slotFailureTakesPrecedence() {
        assertEquals("有槽位无法解析", Readability.reason(List.of(), List.of(), true));
    }
}
