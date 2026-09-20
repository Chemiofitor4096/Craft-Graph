package dev.craftgraph.extract;

import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;

/**
 * 熔炼类配方的适配器（**1.20.1 版**）：耗时来自 {@code AbstractCookingRecipe#getCookingTime()}。
 *
 * <p>与 1.21.1 那份的唯一差别在 loader 的映射上（{@code compileOnly} 的类型全是官方名，
 * 与 1.21.1 相同）—— 逻辑一行没改，因为取值范围那条规则在 {@code core} 的 TickDuration 里。
 *
 * <p>覆盖原版 4 种 —— 熔炼（200 刻）、高炉（100）、烟熏（100）、营火（600）——
 * 因为它们都继承 {@code AbstractCookingRecipe}。
 *
 * <p>判据是 {@code instanceof} 而不是类型 id 白名单，理由见
 * {@link RecipeTypeAdapter} 的类注释：模组复用原版烹饪序列化器时类型 id 是它自己的，
 * 但类没变，耗时照样读得到。
 *
 * <p>取值范围（0 不是「瞬间完成」）由 {@link TickDuration} 负责，这里只取数字。
 */
final class CookingAdapter implements RecipeTypeAdapter<Recipe<?>> {

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof AbstractCookingRecipe;
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (!(recipe instanceof AbstractCookingRecipe cooking)) return null;
        return TickDuration.ofOrNull(cooking.getCookingTime());
    }
}
