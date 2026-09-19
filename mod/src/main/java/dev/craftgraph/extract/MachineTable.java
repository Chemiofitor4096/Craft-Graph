package dev.craftgraph.extract;

import java.util.Map;

/**
 * 配方类型 → 机器的对照表，外加「toast symbol 能不能当机器采信」的判定。
 *
 * <h2>为什么不能直接信 {@code getToastSymbol()}</h2>
 *
 * {@code Recipe#getToastSymbol()} 是原版用来决定「配方解锁时右上角弹哪个图标」的，
 * 语义上确实就是「这条配方在哪台机器上做」。原版 7 种类型各自覆写了它，
 * 逐条对着反编译源码核实过（见 {@link #VANILLA}）。
 *
 * <p>但它有一个**接口级默认实现**返回 {@code minecraft:crafting_table}。
 * 于是「一个没覆写它的模组机器配方」和「真正的工作台合成」返回值完全一样 ——
 * 直接采信的话，整合包里每一台模组机器都会被报成工作台。
 *
 * <h2>所以分两层判断</h2>
 *
 * <ol>
 *   <li>原版 7 种类型查表。这是核实过的事实，不依赖任何推断。</li>
 *   <li>表外的类型（模组自己的配方类型）只在这个值**不等于默认值**时才采信 ——
 *       那说明模组主动覆写了它，是它自己给出来的机器信息。</li>
 *   <li>其余一律 null。</li>
 * </ol>
 *
 * 按协议，{@code machine} 是「推断不出来时为 null」。**一个确定的错答案比 null 危险得多** ——
 * 这正是 {@code opaque} 那条设计原则在单个字段上的应用。
 *
 * <p>纯逻辑，不依赖 Minecraft，所以能完整测试。
 */
public final class MachineTable {

    /**
     * {@code Recipe#getToastSymbol()} 的接口默认实现返回的物品 id。
     *
     * <p>它在表外类型上是有歧义的：既可能是「这真的是工作台配方」，
     * 也可能是「模组没覆写这个方法」。无法区分，所以不采信。
     */
    public static final String TOAST_DEFAULT_ITEM = "minecraft:crafting_table";

    /**
     * 原版配方类型到机器的对照表。
     *
     * <p>每一条都对着反编译源码核实过（{@code neoforge-21.1.251-sources.jar}）：
     * 前 4 个继承 {@code AbstractCookingRecipe}，各自的 {@code getCookingTime()} 给出耗时；
     * {@code StonecutterRecipe} 与 {@code SmithingRecipe} 直接覆写了 {@code getToastSymbol}。
     *
     * <p>虽然这 7 个的 toast symbol 恰好都等于机器，仍然写成显式的表而不是走通用推断：
     * 表里的值是可以逐条核对的，推断出来的值是「看起来对」。
     * 这张表是回归测试的锚点 —— 改坏了会立刻被测试抓住。
     */
    private static final Map<String, String> VANILLA = Map.ofEntries(
            Map.entry("minecraft:crafting", "minecraft:crafting_table"),
            Map.entry("minecraft:smelting", "minecraft:furnace"),
            Map.entry("minecraft:blasting", "minecraft:blast_furnace"),
            Map.entry("minecraft:smoking", "minecraft:smoker"),
            Map.entry("minecraft:campfire_cooking", "minecraft:campfire"),
            Map.entry("minecraft:stonecutting", "minecraft:stonecutter"),
            Map.entry("minecraft:smithing", "minecraft:smithing_table"));

    private MachineTable() {
    }

    /**
     * 原版配方类型的机器。不是原版那 7 种之一时返回 {@code null}。
     *
     * <p>注意这个判断**只看类型 id**，不看配方对象 —— 因此它对模组复用原版类型的配方
     * （比如把 {@code minecraft:smelting} 注册成别的机器）会给出原版的答案。
     * 这是刻意的：类型 id 是数据包里写下来的，比推断可信。
     */
    public static String vanillaMachine(String typeId) {
        return typeId == null ? null : VANILLA.get(typeId);
    }

    /**
     * 综合对照表与 toast symbol 判定机器。
     *
     * @param typeId      配方类型 id
     * @param toastItemId {@code getToastSymbol()} 返回的物品 id；空栈、取不到或抛异常时为 {@code null}
     * @return 机器物品 id；推断不出来时返回 {@code null}
     */
    public static String machineFor(String typeId, String toastItemId) {
        String known = vanillaMachine(typeId);
        if (known != null) return known;

        // 表外类型：默认值无法与「模组没覆写」区分，宁可 null 也不要一个看起来很确定的错答案
        if (toastItemId == null || TOAST_DEFAULT_ITEM.equals(toastItemId)) return null;
        return toastItemId;
    }

    /** 原版 7 种配方类型。给测试和诊断用。 */
    public static java.util.Set<String> vanillaTypes() {
        return VANILLA.keySet();
    }
}
