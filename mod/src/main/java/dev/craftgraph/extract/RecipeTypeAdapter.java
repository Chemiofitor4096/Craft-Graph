package dev.craftgraph.extract;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.List;

/**
 * 一个配方类型适配器：从某种形状的配方对象里读出该类型特有的字段。
 *
 * <h2>为什么需要这一层</h2>
 *
 * 原版的通用接口（{@code getIngredients} / {@code getResultItem}）能给出输入产出，
 * 但它有两个盲区：
 *
 * <ol>
 *   <li><b>给不出耗时和机器。</b>耗时只存在于各配方类自己的字段里。</li>
 *   <li><b>有些配方类的通用接口是空的或假的。</b>最典型的是锻造：
 *       {@code SmithingRecipe} 不覆写 {@code getIngredients()}（返回空列表），
 *       而 {@code SmithingTrimRecipe.getResultItem()} 返回一个硬编码的占位产物。</li>
 * </ol>
 *
 * 在加这一层之前，{@code duration} / {@code machine} 在 1290 条真实配方上全是 null，
 * 于是 {@code calculate_production_plan} 每个环节都报「手工」，机器数计算整个失效。
 * 而单元测试全绿 —— 因为夹具里手写了 duration。
 * **「测试全绿但功能是死的」就是这么来的。**
 *
 * <h2>判据用「是什么类」而不是「类型 id 是什么」</h2>
 *
 * 这一点是刻意的：模组加一台「合金熔炉」并复用原版的烹饪序列化器时，
 * 类型 id 是它自己的，但对象仍然是 {@code AbstractCookingRecipe}，耗时照样读得到。
 * 按类型 id 白名单写就会漏掉这批，而漏掉的症状正好是「duration 又是 null」——
 * 跟要修的 bug 是同一副面孔。所以 {@link #handles} 拿对象判断。
 *
 * <h2>约定：null 与空列表是两件事</h2>
 *
 * {@link #ingredients} / {@link #results} 返回 {@code null} 表示
 * <b>「这个字段不归我管，用通用接口的结果」</b>；
 * 返回空列表表示 <b>「我确定这里读不到东西」</b>，上层会据此把配方标成 opaque。
 *
 * <p>这个区分必须保留，不能都用空列表糊过去：锻造的纹饰配方要的正是
 * 「输入我读得到、产出确实读不到」，如果返回 {@code null}，
 * {@code getResultItem()} 那个硬编码的假产物就会被当成真的报出去。
 *
 * <p>{@link #duration} 没有这个区分 —— 它只返回 null 表示读不到（协议里
 * 「未知为 null」），因为不存在「耗时确定为空」这种情况。
 */
public interface RecipeTypeAdapter {

    /** 这个适配器能不能处理这条配方。 */
    boolean handles(Recipe<?> recipe);

    /**
     * 耗时，单位游戏刻（20 刻 = 1 秒）。
     *
     * @return 读不到时为 {@code null}
     */
    default Integer duration(Recipe<?> recipe) {
        return null;
    }

    /**
     * 输入槽位。通用接口读不到（或读不对）时由适配器提供。
     *
     * @return {@code null} 表示用通用接口的结果；空列表表示确定读不到输入
     */
    default List<Ingredient> ingredients(Recipe<?> recipe) {
        return null;
    }

    /**
     * 产出。通用接口读不到（或返回的是占位符）时由适配器提供。
     *
     * @return {@code null} 表示用 {@code getResultItem} 的结果；
     *         空列表表示确定读不到产出 —— 上层会标 opaque，
     *         而不是把 {@code getResultItem} 的占位符当成真产物
     */
    default List<ItemStack> results(Recipe<?> recipe) {
        return null;
    }

    /**
     * 概率产出（必然产出的那一部分由 {@link #results} 给）。
     *
     * <p>分开是因为下游算法不同：必然产出直接乘合成次数，概率产出要乘概率。
     * 混在一起会让「必然产出 1 个」被当成「期望 1 个」——看起来一样，语义不同，
     * 而概率不是 1 时就会直接把产量算错。
     *
     * @return {@code null} 表示没有概率产出；概率必须落在 (0, 1) 开区间
     */
    default List<ChanceResult> chanceResults(Recipe<?> recipe) {
        return null;
    }

    /**
     * 流体输出（协议里的 {@code fluidOutputs}）。
     *
     * @return {@code null} 表示没有
     */
    default List<FluidStack> fluidResults(Recipe<?> recipe) {
        return null;
    }

    /**
     * 流体输入。
     *
     * <p>必须能读出来 —— 漏掉它不会报错，只会让原料表**看起来完整却少了东西**。
     * Create 的 compacting 就是「燧石×2 + 砂砾 + 100mB 岩浆」，
     * 少了岩浆玩家会照着建一条错的产线。
     *
     * @return {@code null} 表示没有流体输入
     */
    default List<SizedFluidIngredient> fluidIngredients(Recipe<?> recipe) {
        return null;
    }

    /**
     * 一个概率产出。
     *
     * @param stack  产出的物品
     * @param chance 概率，必须落在 (0, 1) 开区间 —— 边界由 {@link ResultChance} 判过
     */
    record ChanceResult(ItemStack stack, float chance) {
    }
}
