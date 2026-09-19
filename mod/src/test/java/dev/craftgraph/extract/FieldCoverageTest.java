package dev.craftgraph.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段覆盖度审计的测试。
 *
 * <h2>它守的是「测试全绿但功能是死的」这一类 bug</h2>
 *
 * {@code duration} / {@code machine} 曾经在 1290 条真实配方上**全是 null**，
 * 而 41 项算法测试 + 83 个 JUnit 用例 + 17 项契约测试全部通过 ——
 * 因为夹具里手写了 duration，而没有任何一层断言过真数据里的这两个字段。
 *
 * <p>{@link FieldCoverage#brokenTypes()} 是运行时的那道网：它不依赖夹具，
 * 也不依赖适配器的实现（判据是「Minecraft 保证有」这个事实），
 * 所以它能在用户的整合包里发现「适配器没生效」。
 *
 * <p>这里用真实快照的类型分布造数据：crafting 901 / stonecutting 250 /
 * smelting 70 / smithing 27 / blasting 24 / campfire_cooking 9 / smoking 9。
 */
class FieldCoverageTest {

    /** 按实测分布记一遍。耗时只给熔炼那 4 类，跟修复后的真实数据一致。 */
    private static FieldCoverage realistic() {
        FieldCoverage c = new FieldCoverage();
        for (int i = 0; i < 901; i++) c.record("minecraft:crafting", false, true);
        for (int i = 0; i < 250; i++) c.record("minecraft:stonecutting", false, true);
        for (int i = 0; i < 70; i++) c.record("minecraft:smelting", true, true);
        for (int i = 0; i < 27; i++) c.record("minecraft:smithing", false, true);
        for (int i = 0; i < 24; i++) c.record("minecraft:blasting", true, true);
        for (int i = 0; i < 9; i++) c.record("minecraft:campfire_cooking", true, true);
        for (int i = 0; i < 9; i++) c.record("minecraft:smoking", true, true);
        return c;
    }

    // ---------------------------------------------------------------- 计数

    @Test
    @DisplayName("总数与各字段的覆盖数正确")
    void countsAddUp() {
        FieldCoverage c = realistic();
        assertEquals(1290, c.total());
        // 熔炼 70 + 高炉 24 + 烟熏 9 + 营火 9 = 112，与「真实数据里该有多少」一致
        assertEquals(112, c.withDuration());
        assertEquals(1290, c.withMachine());
    }

    @Test
    @DisplayName("空输入的统计不崩（游戏刚启动时会出现）")
    void emptyInput() {
        FieldCoverage c = new FieldCoverage();
        assertEquals(0, c.total());
        assertTrue(c.brokenTypes().isEmpty());
        assertNull(c.durationRatio("minecraft:smelting"));
        assertTrue(c.summary().contains("没有配方"));
    }

    @Test
    @DisplayName("单个类型的覆盖率")
    void ratioPerType() {
        FieldCoverage c = realistic();
        assertEquals("70/70", c.durationRatio("minecraft:smelting"));
        assertEquals("0/901", c.durationRatio("minecraft:crafting"));
        assertNull(c.durationRatio("create:mixing"));
    }

    @Test
    @DisplayName("有耗时数据的类型列表只含真正读到的那些")
    void typesWithDuration() {
        List<String> types = realistic().typesWithDuration();
        assertEquals(List.of(
                "minecraft:blasting 24/24",
                "minecraft:campfire_cooking 9/9",
                "minecraft:smelting 70/70",
                "minecraft:smoking 9/9"), types);
    }

    // ---------------------------------------------------------------- 异常检测

    @Test
    @DisplayName("正常数据下不报异常")
    void healthyDataHasNoBrokenTypes() {
        assertTrue(realistic().brokenTypes().isEmpty(),
                "熔炼类都有耗时，不该报异常：" + realistic().brokenTypes());
    }

    @Test
    @DisplayName("★ 熔炼配方一条耗时都没读到 → 报异常（这就是本次修的那个 bug）")
    void cookingTypeWithoutDurationIsReported() {
        // 修复前的真实数据就是这样：70 条 minecraft:smelting，duration 全是 null。
        // 覆盖度审计必须在日志里喊出来，而不是安静地让产线计算报「手工」。
        FieldCoverage c = new FieldCoverage();
        for (int i = 0; i < 70; i++) c.record("minecraft:smelting", false, true);

        List<String> broken = c.brokenTypes();
        assertEquals(1, broken.size(), broken.toString());
        assertTrue(broken.get(0).contains("minecraft:smelting"), broken.get(0));
        assertTrue(broken.get(0).contains("70"), broken.get(0));
    }

    @Test
    @DisplayName("每个烹饪类型各自独立检查")
    void eachCookingTypeCheckedIndependently() {
        FieldCoverage c = new FieldCoverage();
        c.record("minecraft:smelting", true, true);          // 正常
        c.record("minecraft:blasting", false, true);         // 坏了
        c.record("minecraft:smoking", true, true);           // 正常
        c.record("minecraft:campfire_cooking", false, true); // 坏了

        List<String> broken = c.brokenTypes();
        assertEquals(2, broken.size(), broken.toString());
        assertTrue(broken.get(0).contains("blasting"), broken.get(0));
        assertTrue(broken.get(1).contains("campfire_cooking"), broken.get(1));
    }

    @Test
    @DisplayName("部分读到也算有问题（半数熔炼读不到同样是 bug）")
    void partialCoverageIsNotHealthy() {
        // 「有 70 条，其中 3 条读到耗时」和「一条都没读到」都是适配器出了问题。
        // 只判「== 0」会漏掉「大部分读不到」这种情况。
        FieldCoverage c = new FieldCoverage();
        for (int i = 0; i < 3; i++) c.record("minecraft:smelting", true, true);
        for (int i = 0; i < 67; i++) c.record("minecraft:smelting", false, true);
        assertEquals(1, c.brokenTypes().size(),
                "3/70 的覆盖率应当被判为异常，而不是因为「不为 0」就放过");
    }

    @Test
    @DisplayName("非烹饪类型没有耗时不算异常")
    void nonCookingTypesWithoutDurationAreFine() {
        // crafting / stonecutting / smithing 本来就没有耗时字段，
        // 把它们报成异常会让日志被噪声淹没，真正的问题反而看不见。
        FieldCoverage c = new FieldCoverage();
        for (int i = 0; i < 901; i++) c.record("minecraft:crafting", false, true);
        for (int i = 0; i < 250; i++) c.record("minecraft:stonecutting", false, true);
        for (int i = 0; i < 27; i++) c.record("minecraft:smithing", false, true);
        assertTrue(c.brokenTypes().isEmpty(), c.brokenTypes().toString());
    }

    @Test
    @DisplayName("整合包把熔炼配方删光了 → 不报异常（没有条目就没有「有条目却读不到」）")
    void absentCookingTypeIsNotReported() {
        FieldCoverage c = new FieldCoverage();
        for (int i = 0; i < 100; i++) c.record("create:mixing", true, true);
        assertTrue(c.brokenTypes().isEmpty(), c.brokenTypes().toString());
    }

    @Test
    @DisplayName("模组复用原版类型 id 时也被检查到")
    void moddedRecipesUnderVanillaTypeAreChecked() {
        // KubeJS 之类会往 minecraft:smelting 里塞配方。类型 id 相同，
        // 就该同样享受「必须有耗时」这条保证。
        FieldCoverage c = new FieldCoverage();
        c.record("minecraft:smelting", false, true);
        assertFalse(c.brokenTypes().isEmpty());
    }

    @Test
    @DisplayName("type 为 null 时归到 unknown 桶里，不崩")
    void nullTypeIsBucketed() {
        FieldCoverage c = new FieldCoverage();
        c.record(null, false, false);
        assertEquals(1, c.total());
        // unknown 不在「必须有耗时」的名单里，所以不该报异常 —— 报它会变成噪声
        assertTrue(c.brokenTypes().isEmpty(), c.brokenTypes().toString());
        assertNull(c.durationRatio(null));
    }

    // ---------------------------------------------------------------- 摘要

    @Test
    @DisplayName("摘要里同时有耗时和机器两个比例")
    void summaryMentionsBothFields() {
        String s = realistic().summary();
        assertTrue(s.contains("112/1290"), s);
        assertTrue(s.contains("1290/1290"), s);
    }
}
