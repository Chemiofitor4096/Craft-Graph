package dev.craftgraph.extract;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.List;

/**
 * 适配器登记处：按配方对象挑一个能读它特有字段的适配器。
 *
 * <h2>加新适配器的方式</h2>
 *
 * 写一个 {@link RecipeTypeAdapter} 实现，然后加进 {@link #ADAPTERS}。
 * 顺序有意义：先注册的先匹配。EMI/JEI 那类「能读一切」的适配器应该排最后，
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
            new CookingAdapter(),
            new SmithingAdapter());

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

    /**
     * 读输入槽位。
     *
     * @param generic 通用接口（{@code getIngredients()}）读出来的结果
     * @return 适配器给的槽位；没有适配器能处理、或适配器声明「不归我管」时返回 {@code generic}
     */
    public static List<Ingredient> ingredients(Recipe<?> recipe, List<Ingredient> generic) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<Ingredient> override = adapter.ingredients(recipe);
        return override == null ? generic : override;
    }

    /**
     * 读产出。
     *
     * <p><b>参数里的 generic 已经排除了空栈</b>（调用方用 {@code getResultItem()} 取过之后
     * 过滤掉了 EMPTY）。适配器返回空列表时结果就是空列表 —— 调用方会据此标 opaque，
     * 这正是「产出是占位符，别报出去」所需要的语义。
     *
     * @param generic 通用接口（{@code getResultItem()}）读出来的产物
     * @return 最终产物列表；可能是空的，表示确定读不到产出
     */
    public static List<ItemStack> results(Recipe<?> recipe, List<ItemStack> generic) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<ItemStack> override = adapter.results(recipe);
        return override == null ? generic : override;
    }
}
