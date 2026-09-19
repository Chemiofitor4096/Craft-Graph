package dev.craftgraph.extract;

import net.minecraft.world.item.crafting.Recipe;

/**
 * 一个配方类型适配器：从某种形状的配方对象里读出该类型特有的字段。
 *
 * <h2>为什么需要这一层</h2>
 *
 * 原版的通用接口（{@code getIngredients} / {@code getResultItem}）能给出输入产出，
 * 但**给不出耗时和机器**。耗时只存在于各配方类自己的字段里，
 * 只有知道「这是哪种配方」才读得到 —— 这就是适配器要解决的问题。
 *
 * <p>在加这一层之前，这两条路径完全没被走过：1290 条真实配方的
 * {@code duration} / {@code machine} / {@code energy} 全是 null，
 * 于是 {@code calculate_production_plan} 每个环节都报「手工」，机器数计算整个失效。
 * 而单元测试全绿 —— 因为夹具里手写了 duration。
 * **「测试全绿但功能是死的」就是这么来的。**
 *
 * <h2>判据用「是什么类」而不是「类型 id 是什么」</h2>
 *
 * 这一点是刻意的：模组加一台「合金熔炉」并复用原版的烹饪序列化器时，
 * 类型 id 是它自己的，但对象仍然是 {@code AbstractCookingRecipe}，耗时照样读得到。
 * 按类型 id 白名单写就会漏掉这批，而漏掉的症状正好是「duration 又是 null」——
 * 跟这次要修的 bug 是同一副面孔。所以 {@link #handles} 拿对象判断。
 *
 * <h2>约定</h2>
 *
 * 读不到字段时返回 {@code null}，**绝不用 0 或空串冒充**。
 * 0 刻会被下游理解成「不用时间」，null 才会被理解成「未知」——
 * 协议里 {@code duration} 的语义就是「未知为 null」。
 */
public interface RecipeTypeAdapter {

    /** 这个适配器能不能处理这条配方。 */
    boolean handles(Recipe<?> recipe);

    /**
     * 耗时，单位游戏刻（20 刻 = 1 秒）。
     *
     * @return 读不到时为 {@code null}
     */
    Integer duration(Recipe<?> recipe);
}
