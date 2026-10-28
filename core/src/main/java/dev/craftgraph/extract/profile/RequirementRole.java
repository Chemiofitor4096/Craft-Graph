package dev.craftgraph.extract.profile;

/**
 * 一个 requirement 对原料表意味着什么。
 *
 * <p>这个分类是「读配方」的核心判断之一，和具体模组无关：
 *
 * <ul>
 *   <li>{@link #ITEM} / {@link #FLUID}：真的进原料表/产出表。</li>
 *   <li>{@link #TOOL}：读得到，但语义是「工具磨损」，<b>不进原料表</b>。</li>
 *   <li>{@link #CONDITION}：世界/机器条件（结构、天气、能量），不消耗也不产出物品。</li>
 *   <li>{@link #INCOMPLETE}：可能有产出或原料，但这个形状我们表达不了 —— 整条配方降级为 opaque。</li>
 * </ul>
 *
 * <p>未知类型一律按 {@link #INCOMPLETE}：没见过不等于不重要。
 */
public enum RequirementRole {
    ITEM,
    FLUID,
    TOOL,
    CONDITION,
    INCOMPLETE
}
