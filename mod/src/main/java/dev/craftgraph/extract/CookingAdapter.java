package dev.craftgraph.extract;

import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;

/**
 * 熔炼类配方的适配器：耗时来自 {@code AbstractCookingRecipe#getCookingTime()}。
 *
 * <p>覆盖原版 4 种 —— 熔炼（200 刻）、高炉（100）、烟熏（100）、营火（600）——
 * 因为它们都继承 {@code AbstractCookingRecipe}。
 *
 * <p>判据是 {@code instanceof} 而不是类型 id 白名单，理由见
 * {@link RecipeTypeAdapter} 的类注释：模组复用原版烹饪序列化器时类型 id 是它自己的，
 * 但类没变，耗时照样读得到。
 */
final class CookingAdapter implements RecipeTypeAdapter {

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof AbstractCookingRecipe;
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (!(recipe instanceof AbstractCookingRecipe cooking)) return null;

        int ticks = cooking.getCookingTime();
        // 0 或负数不是「瞬间完成」，而是没读到一个有意义的值。
        // 返回 0 会被下游当成「不需要时间」，返回 null 才会被当成「未知」——
        // 而且 0 会让 FieldCoverage 的检查失灵（它靠 null 发现适配器没生效）。
        return ticks > 0 ? ticks : null;
    }
}
