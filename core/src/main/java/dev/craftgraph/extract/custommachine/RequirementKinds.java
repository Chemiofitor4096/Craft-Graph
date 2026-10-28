package dev.craftgraph.extract.custommachine;

import java.util.Map;

/**
 * Custom Machinery 的 requirement 类型 → 「它对原料表意味着什么」。
 *
 * <h2>为什么需要这张表</h2>
 *
 * CM 的一条配方就是一组 requirement，而 requirement 有 30 种：物品、流体、能量、
 * 结构、天气、命令、战利品表…… 直接把「能读到的都塞进原料表」会得到两个错误答案：
 *
 * <ul>
 *   <li><b>把工具当原料</b>：{@code custommachinery:durability} 是让机器里那张硬盘磨损，
 *       不是每条配方消耗一张硬盘。当成原料算，产线原料表会平白多出成千上万个硬盘。</li>
 *   <li><b>把读不懂当成没产出</b>：{@code loot_table} / {@code drop} / {@code item_transform}
 *       确实会产出东西，只是产出不是声明式的。这类必须让整条配方标 opaque，
 *       而不是报告一个「产出为空」。</li>
 * </ul>
 *
 * <h2>五个分类</h2>
 *
 * <ul>
 *   <li>{@link Kind#ITEM} / {@link Kind#FLUID}：真的进原料表/产出表。</li>
 *   <li>{@link Kind#TOOL}：读得到，但语义是「工具磨损」，不进原料表。</li>
 *   <li>{@link Kind#CONDITION}：世界/机器条件（结构、天气、能量、速度），不产出也不消耗物品。</li>
 *   <li>{@link Kind#INCOMPLETE}：可能有产出或有原料，但我们表达不了 —— 整条配方降级为 opaque。</li>
 * </ul>
 *
 * <p>表里没写到的类型一律按 {@link Kind#INCOMPLETE} 处理：模组可以注册自己的 requirement，
 * 而「没见过」的正确含义是「我不敢说读全了」，不是「它不重要」。
 *
 * <p>纯逻辑、只认字符串，所以不依赖 Minecraft，能完整单测。
 */
public final class RequirementKinds {

    /** 一个 requirement 对我们意味着什么。 */
    public enum Kind {
        /** 物品输入/产出。 */
        ITEM,
        /** 流体输入/产出。 */
        FLUID,
        /** 工具/耐久消耗（例如机械硬盘）。读得到，但不该当原料。 */
        TOOL,
        /** 世界或机器条件：结构、天气、能量、速度……不消耗也不产出物品。 */
        CONDITION,
        /** 可能有原料或产出，但这个形状我们表达不了 —— 整条配方要标 opaque。 */
        INCOMPLETE
    }

    private static final String NS = "custommachinery:";

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            // ---- 真正进原料/产出表的 ----
            Map.entry(NS + "item", Kind.ITEM),
            Map.entry(NS + "fluid", Kind.FLUID),

            // ---- 工具磨损：读得到，但不是原料 ----
            // 组装机每条配方都挂一张锡制机械硬盘（1024 耐久），它是工具不是耗材。
            Map.entry(NS + "durability", Kind.TOOL),

            // ---- 条件：不消耗物品，也不产出物品 ----
            Map.entry(NS + "energy", Kind.CONDITION),
            Map.entry(NS + "energy_per_tick", Kind.CONDITION),
            Map.entry(NS + "structure", Kind.CONDITION),
            Map.entry(NS + "position", Kind.CONDITION),
            Map.entry(NS + "biome", Kind.CONDITION),
            Map.entry(NS + "dimension", Kind.CONDITION),
            Map.entry(NS + "block", Kind.CONDITION),
            Map.entry(NS + "entity", Kind.CONDITION),
            Map.entry(NS + "effect", Kind.CONDITION),
            Map.entry(NS + "light", Kind.CONDITION),
            Map.entry(NS + "redstone", Kind.CONDITION),
            Map.entry(NS + "sky", Kind.CONDITION),
            Map.entry(NS + "time", Kind.CONDITION),
            Map.entry(NS + "weather", Kind.CONDITION),
            Map.entry(NS + "command", Kind.CONDITION),
            Map.entry(NS + "function", Kind.CONDITION),
            Map.entry(NS + "chunkload", Kind.CONDITION),
            Map.entry(NS + "button", Kind.CONDITION),
            Map.entry(NS + "speed", Kind.CONDITION),
            Map.entry(NS + "item_filter", Kind.CONDITION),
            // 经验不是物品也不是流体，协议里没有这一维；它不改变原料表。
            Map.entry(NS + "experience", Kind.CONDITION),
            Map.entry(NS + "experience_per_tick", Kind.CONDITION),

            // ---- 会产出/消耗，但形状表达不了 ----
            // 按 tick 计量的流体：我们可以写成「每次 N mB」，但那跟时间耦合，会算错。
            Map.entry(NS + "fluid_per_tick", Kind.INCOMPLETE),
            // 燃料：消耗的是玩家塞进去的任意燃料，不是声明式原料。
            Map.entry(NS + "fuel", Kind.INCOMPLETE),
            // 把输入物品变成另一个物品：两端都在，但我们只读一侧就会算错。
            Map.entry(NS + "item_transform", Kind.INCOMPLETE),
            // 随机产出，产出物写在战利品表里。
            Map.entry(NS + "loot_table", Kind.INCOMPLETE),
            // 在地上掉落/拾取物品。
            Map.entry(NS + "drop", Kind.INCOMPLETE));

    private RequirementKinds() {
    }

    /**
     * 这个 requirement 类型属于哪一类。
     *
     * <p>未知类型返回 {@link Kind#INCOMPLETE} —— 见类注释：没见过不等于不重要。
     */
    public static Kind of(String typeId) {
        if (typeId == null) return Kind.INCOMPLETE;
        return KINDS.getOrDefault(typeId, Kind.INCOMPLETE);
    }

    /** 这个类型是不是我们明确认识的。给诊断/日志用。 */
    public static boolean isKnown(String typeId) {
        return typeId != null && KINDS.containsKey(typeId);
    }
}
