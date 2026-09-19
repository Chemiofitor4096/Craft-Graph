package dev.craftgraph.extract;

import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * 适配器登记处：按配方对象挑一个能读它特有字段的适配器。
 *
 * <h2>加新适配器的方式</h2>
 *
 * 写一个 {@link RecipeTypeAdapter} 实现，然后加进 {@link #ADAPTERS}。
 * 顺序有意义：先注册的先匹配。EMI 那类「能读一切」的适配器应该排最后，
 * 让它只在具体类型适配器都不认的时候兜底。
 *
 * <h2>没有适配器匹配时怎么办</h2>
 *
 * 返回 {@code null} 字段，也就是「读不到」——**不是**给一个默认值。
 * 下游拿到 null 会说「耗时未知，无法计算机器数」，拿到 0 会说「不用时间」，
 * 后者是一个不自知的错误答案。见 {@link RecipeTypeAdapter} 的约定。
 */
public final class RecipeAdapters {

    private static final List<RecipeTypeAdapter> ADAPTERS = List.of(
            new CookingAdapter());

    private RecipeAdapters() {
    }

    /** 找能处理这条配方的适配器。没有就返回 {@code null}。 */
    public static RecipeTypeAdapter forRecipe(Recipe<?> recipe) {
        for (RecipeTypeAdapter adapter : ADAPTERS) {
            if (adapter.handles(recipe)) return adapter;
        }
        return null;
    }

    /**
     * 读耗时。
     *
     * @return 游戏刻；没有适配器能处理、或适配器读不到时返回 {@code null}
     */
    public static Integer duration(Recipe<?> recipe) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        return adapter == null ? null : adapter.duration(recipe);
    }
}
