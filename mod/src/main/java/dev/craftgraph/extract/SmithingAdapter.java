package dev.craftgraph.extract;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;

import java.util.List;

/**
 * 锻造配方的适配器：输入来自三个包私有字段（靠
 * {@code META-INF/accesstransformer.cfg} 变公开），产物按两个子类分别处理。
 *
 * <h2>为什么需要它</h2>
 *
 * {@code SmithingRecipe} 不覆写 {@code getIngredients()}，继承的默认实现返回空列表，
 * 所以通用接口一条输入都读不到 —— 原版的 27 条锻造配方因此全被标成 opaque。
 * 反过来说，玩家问得最多的「下界合金镐怎么做」正是这一批。
 *
 * <h2>这里有个必须一起处理的陷阱</h2>
 *
 * 只看输入是不够的。{@code SmithingTrimRecipe.getResultItem()} 返回的是一个
 * <b>硬编码的占位产物</b>：
 *
 * <pre>{@code
 * ItemStack itemstack = new ItemStack(Items.IRON_CHESTPLATE);   // 永远是这个
 * // 再挂上第一个纹饰图案 + 红石
 * return itemstack;
 * }</pre>
 *
 * 也就是说 18 条纹饰配方的产物一律报成「铁胸甲」，那是个假答案 ——
 * 真实产物是「你放进去的那件盔甲 + 纹饰」，是组合式的，无法用单一物品表达。
 *
 * <p>现在它被 {@code opaque = true} 挡住了所以没造成伤害。但如果只补上输入，
 * 输入就非空了，{@code Readability} 会判定这条配方可读，
 * 于是那个铁胸甲从「被标记的占位符」变成「一个理直气壮的答案」。
 * 这跟 {@code development.md} 里记的那次锻造陷阱是同一个形状：
 * **修一半等于制造静默的错误答案。** 所以 {@link #results} 对纹饰返回空列表。
 *
 * <h2>两个子类的差别</h2>
 *
 * <table>
 *   <tr><td>{@link SmithingTransformRecipe}</td><td>下界合金升级（9 条）</td>
 *       <td>{@code getResultItem()} 返回真实的 {@code result} 字段 → 交给通用路径</td></tr>
 *   <tr><td>{@link SmithingTrimRecipe}</td><td>盔甲纹饰（18 条）</td>
 *       <td>{@code getResultItem()} 是占位符 → 返回空列表，诚实报「读不到产出」</td></tr>
 * </table>
 *
 * <p>所以修完之后：9 条下界合金配方真正可读；18 条纹饰仍然是 opaque，
 * 但输入读到了、假产物消失了，{@code Readability.reason} 会明说是「读不到产出」
 * 而不是「输入产出都读不到」。
 *
 * <p><b>验证方式</b>：{@code instanceof} 派发需要真实的 MC 类，而测试源码集看不到
 * Minecraft，所以这一环由 {@code npm run live} 在真游戏上断言（见 live.ts 的锻造段）。
 */
final class SmithingAdapter implements RecipeTypeAdapter {

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof SmithingTransformRecipe || recipe instanceof SmithingTrimRecipe;
    }

    @Override
    public List<Ingredient> ingredients(Recipe<?> recipe) {
        // 三个字段是包私有的，靠 accesstransformer.cfg 变公开。
        // 顺序按 JEI 的做法：template / base / addition。
        if (recipe instanceof SmithingTransformRecipe r) {
            return List.of(r.template, r.base, r.addition);
        }
        if (recipe instanceof SmithingTrimRecipe r) {
            return List.of(r.template, r.base, r.addition);
        }
        return null;
    }

    @Override
    public List<ItemStack> results(Recipe<?> recipe) {
        if (recipe instanceof SmithingTrimRecipe) {
            // 坚决不用 getResultItem()：那是硬编码的铁胸甲占位符。
            // 空列表 → 上层标 opaque，AI 会被告知「产出读不到」而不是拿到一个假答案。
            return List.of();
        }
        // 升级配方：getResultItem() 返回真实的 result 字段，通用路径就够。
        return null;
    }
}
