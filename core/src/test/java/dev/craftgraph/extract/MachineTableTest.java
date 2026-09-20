package dev.craftgraph.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配方类型 → 机器的对照表测试。
 *
 * <h2>为什么这个判定值得单独测</h2>
 *
 * 机器字段以前恒为 null（通用接口给不出它），产线计算因此每个环节都报「手工」，
 * 而这个 bug 在四个测试层里都没被发现。现在把它填上了，
 * 于是「填对没有」就成了必须守住的东西 —— 这张表是回归测试的锚点。
 *
 * <p>表里的 7 条**逐条对着反编译源码核实过**，测试里写死同样的值：
 * 改坏了会立刻红，而不是等到玩家在游戏里看到「熔炼配方用工作台做」。
 */
class MachineTableTest {

    // ---------------------------------------------------------------- 原版对照表

    @Test
    @DisplayName("原版 7 种配方类型各自的机器")
    void vanillaMachines() {
        // 前 4 个继承 AbstractCookingRecipe，getToastSymbol 分别返回 FURNACE /
        // BLAST_FURNACE / SMOKER / CAMPFIRE；切石与锻造自己覆写了 getToastSymbol。
        assertEquals("minecraft:crafting_table", MachineTable.vanillaMachine("minecraft:crafting"));
        assertEquals("minecraft:furnace", MachineTable.vanillaMachine("minecraft:smelting"));
        assertEquals("minecraft:blast_furnace", MachineTable.vanillaMachine("minecraft:blasting"));
        assertEquals("minecraft:smoker", MachineTable.vanillaMachine("minecraft:smoking"));
        assertEquals("minecraft:campfire", MachineTable.vanillaMachine("minecraft:campfire_cooking"));
        assertEquals("minecraft:stonecutter", MachineTable.vanillaMachine("minecraft:stonecutting"));
        assertEquals("minecraft:smithing_table", MachineTable.vanillaMachine("minecraft:smithing"));
    }

    @Test
    @DisplayName("对照表恰好覆盖原版 7 种类型")
    void tableCoversExactlyVanillaTypes() {
        // 1.21.1 的 RecipeType 只有这 7 个注册项（对着 RecipeType.java 数过）。
        // 多出来的条目意味着表里有没人核实的猜测，少了的意味着有类型会被当成模组类型处理。
        assertEquals(7, MachineTable.vanillaTypes().size(), MachineTable.vanillaTypes().toString());
        assertTrue(MachineTable.vanillaTypes().contains("minecraft:campfire_cooking"));
    }

    @Test
    @DisplayName("非原版类型查表返回 null")
    void unknownTypeNotInTable() {
        assertNull(MachineTable.vanillaMachine("create:crushing"));
        assertNull(MachineTable.vanillaMachine(null));
    }

    // ---------------------------------------------------------------- 通用推断

    @Test
    @DisplayName("原版类型不看 toast symbol，直接查表")
    void vanillaIgnoresToastSymbol() {
        // 数据包可以往 minecraft:smelting 里塞任意配方。类型 id 是数据包里写下来的，
        // 比推断可信 —— 所以即使 toast symbol 是别的物品，也按类型作答。
        assertEquals("minecraft:furnace",
                MachineTable.machineFor("minecraft:smelting", "somemod:weird_machine"));
    }

    @Test
    @DisplayName("模组类型：toast symbol 不是默认值 → 采信它")
    void moddedTypeUsesToastSymbol() {
        // 模组主动覆写了 getToastSymbol，等于它自己告诉我们「这条配方在我这台机器上做」
        assertEquals("create:mechanical_mixer",
                MachineTable.machineFor("create:mixing", "create:mechanical_mixer"));
    }

    @Test
    @DisplayName("模组类型的 toast symbol 是默认值 → 返回 null，不谎报工作台")
    void moddedTypeWithDefaultToastIsUnknown() {
        // 这是这张表存在的主要理由。
        //
        // Recipe#getToastSymbol() 有接口级默认实现，返回 minecraft:crafting_table。
        // 也就是说「一个没覆写它的模组机器配方」和「真正的工作台合成」返回值一模一样。
        // 直接采信的话，整合包里每一台模组机器（Create 的粉碎轮、IE 的冶炼炉……）
        // 都会被报成「工作台」—— 一个看起来很确定的错答案。
        //
        // 按协议 machine 是「推断不出来时为 null」，所以这里必须是 null。
        assertNull(MachineTable.machineFor("create:mixing", MachineTable.TOAST_DEFAULT_ITEM),
                "默认值无法与「模组没覆写」区分，宁可 null 也不要报成工作台");
    }

    @Test
    @DisplayName("模组类型的 toast symbol 取不到 → null")
    void moddedTypeWithNoToastIsUnknown() {
        // 很多模组配方的 getToastSymbol 返回 ItemStack.EMPTY
        assertNull(MachineTable.machineFor("create:mixing", null));
    }

    @Test
    @DisplayName("默认值常量与游戏里的值一致（改错会静默失效）")
    void defaultItemConstant() {
        // 这个字符串是「不能采信」的判据。写错了（比如少一个下划线）
        // 就会让上面那条规则失效，而且症状只是「偶尔多报一个工作台」，很难注意到。
        assertEquals("minecraft:crafting_table", MachineTable.TOAST_DEFAULT_ITEM);
    }
}
