package dev.craftgraph.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型名格式化的测试。
 *
 * 看着是个不起眼的小工具，但它决定了 AI 在报告里看到的是
 * 「Smelting」还是「minecraft:smelting」—— 后者会明显更难读，
 * 也更容易让模型把命名空间当成有意义的信息。
 */
class HumanizeTest {

    @Test
    @DisplayName("去掉命名空间并首字母大写")
    void stripsNamespace() {
        assertEquals("Smelting", Humanize.typeLabel("minecraft:smelting"));
        assertEquals("Crushing", Humanize.typeLabel("create:crushing"));
        assertEquals("Crafting", Humanize.typeLabel("minecraft:crafting"));
    }

    @Test
    @DisplayName("下划线转空格且逐词大写")
    void convertsUnderscores() {
        assertEquals("Crafting Shaped", Humanize.typeLabel("minecraft:crafting_shaped"));
        assertEquals("Alloy Smelting", Humanize.typeLabel("somemod:alloy_smelting"));
    }

    @Test
    @DisplayName("斜杠和点也当分隔符（模组常用 machine/smelter 这种路径式 id）")
    void convertsSlashesAndDots() {
        assertEquals("Machine Smelter", Humanize.typeLabel("thermal:machine/smelter"));
        assertEquals("Crushing Iron Ore", Humanize.typeLabel("create:crushing/iron_ore"));
    }

    @Test
    @DisplayName("没有命名空间的 id 也能处理")
    void handlesIdWithoutNamespace() {
        assertEquals("Smelting", Humanize.typeLabel("smelting"));
    }

    @Test
    @DisplayName("空值与短输入不抛异常")
    void handlesEdgeCases() {
        assertEquals("", Humanize.typeLabel(null));
        assertEquals("", Humanize.typeLabel(""));
        assertEquals("", Humanize.typeLabel("   "));
        assertEquals("", Humanize.typeLabel("minecraft:"), "只有命名空间时返回空串而不是冒号");
    }

    @Test
    @DisplayName("连续分隔符不会产生多余空格")
    void collapsesRepeatedSeparators() {
        assertEquals("A B", Humanize.typeLabel("x:a__b"));
        assertEquals("A B", Humanize.typeLabel("x:a//b"));
        assertEquals("A B", Humanize.typeLabel("x:a_/b"));
    }

    @Test
    @DisplayName("已经是驼峰或大写的 id 不会被破坏")
    void preservesExistingCasing() {
        assertEquals("Smelting", Humanize.typeLabel("minecraft:Smelting"));
        assertEquals("MyType", Humanize.typeLabel("mod:myType"));
    }
}
