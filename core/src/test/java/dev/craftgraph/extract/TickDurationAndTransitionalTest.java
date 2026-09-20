package dev.craftgraph.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条从适配器里搬进 core 的规则：耗时的取值范围、中间产物的排除判据。
 *
 * <p>它们原来各有注释互相引用（「与 CookingAdapter 同一条约定」），
 * 也就是同一条规则写在两处、靠注释保持同步 —— 现在只有一处定义、一处测试。
 */
class TickDurationAndTransitionalTest {

    // ------------------------------------------------------------ 耗时

    @Test
    @DisplayName("★ 0 和负数不是「瞬间完成」，是「没读到」")
    void zeroIsUnknownNotInstant() {
        assertNull(TickDuration.ofOrNull(0),
                "返回 0 会让下游以为不用时间 —— 分母为零或者干脆被当成手工合成。"
                        + "真相是我们没读到，那必须说成「未知」");
        assertNull(TickDuration.ofOrNull(-5));
    }

    @Test
    @DisplayName("正常耗时原样返回（20 刻 = 1 秒）")
    void keepsPositiveTicks() {
        assertEquals(200, TickDuration.ofOrNull(200));   // 熔炼
        assertEquals(1, TickDuration.ofOrNull(1));
    }

    // ------------------------------------------------------------ 中间产物

    @Test
    @DisplayName("★ 全是中间产物才排除：槽位里混了真实原料时必须留下")
    void excludesOnlyWhenAllCandidatesAreTransitional() {
        String transitional = "create:incomplete_precision_mechanism";

        assertTrue(TransitionalItem.isOnlyTransitional(List.of(transitional), transitional));
        assertFalse(TransitionalItem.isOnlyTransitional(
                        List.of(transitional, "minecraft:gold_ingot"), transitional),
                "「含有就排除」会把真实原料一起丢掉 —— 而这种错误是静默的，"
                        + "原料表看起来仍然完整，只是少了一项");
    }

    @Test
    @DisplayName("没有中间产物这个概念时不排除任何东西")
    void noTransitionalMeansNoExclusion() {
        assertFalse(TransitionalItem.isOnlyTransitional(List.of("minecraft:gold_ingot"), null),
                "null 表示这条配方没有中间产物，不能反过来把所有槽位都判成中间产物");
    }

    @Test
    @DisplayName("空槽位不是「只有中间产物」")
    void emptySlotIsNotTransitionalOnly() {
        assertFalse(TransitionalItem.isOnlyTransitional(List.of(), "create:incomplete_thing"));
    }
}
