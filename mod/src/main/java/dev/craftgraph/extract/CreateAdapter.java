package dev.craftgraph.extract;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.craftgraph.extract.RecipeTypeAdapter.ChanceResult;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.List;

/**
 * Create 的加工配方适配器。
 *
 * <h2>为什么需要它</h2>
 *
 * Create 的配方（粉碎、铣削、压榨、混合、洗涤、注液…）都继承 {@code ProcessingRecipe}，
 * 它的产出**不是一个物品，而是一个带概率的列表**：
 *
 * <pre>{@code
 * // create:crushing 一条真实配方
 * "processingTime": 400,
 * "results": [
 *   { "item": "create:crushed_raw_aluminum" },              // chance 不写 = 1.0，必然产出
 *   { "chance": 0.75, "item": "create:crushed_raw_aluminum" },
 *   { "chance": 0.75, "item": "create:experience_nugget" }
 * ]
 * }</pre>
 *
 * 走通用接口（{@code getResultItem()}）只会拿到**一个**产出，概率和其余产出全丢 ——
 * 而且它不会报错，只会安静地少报。在 Create 类整合包里这类配方占很大比例
 * （实测一份 Create 数据包 1843 条里约 825 条是 Create 自己的类型）。
 *
 * <h2>顺带解决的两件事</h2>
 *
 * <ul>
 *   <li><b>耗时。</b>{@code getProcessingDuration()} —— 这是 Create 机器能不能算出
 *       真实台数的关键。原版没有这个字段，而 JEI/EMI 的 API 里**根本没有「耗时」的概念**，
 *       所以这一项只能靠各模组自己的适配器，视图器帮不上忙。</li>
 *   <li><b>流体。</b>{@code getFluidIngredients()} / {@code getFluidResults()}。
 *       漏掉流体不会报错，只会让原料表看起来完整却少了东西。</li>
 * </ul>
 *
 * <h2>这个适配器读不到机器名</h2>
 *
 * {@code ProcessingRecipe} **没有覆写 {@code getToastSymbol()}**，所以它拿到的是接口默认值
 * {@code minecraft:crafting_table}，而 {@link MachineTable} 的规则是「表外类型不采信默认值」，
 * 于是 Create 配方的 {@code machine} 是 null。这是刻意的：与其手写一张
 * 「类型 → 机器」的对照表（Create 的 15 种类型里，砂纸的机器是**物品**不是方块，
 * 洗涤和灼烧共用同一台 Encased Fan，注液/排液分散在 Spout 和 Item Drain 上 —— 全是猜），
 * 不如等 JEI 的催化剂，那是权威答案。`duration` 有了但 `machine` 是 null 时，
 * 产线仍能算出「N 台」，只是机器清单里没有名字。
 *
 * <h2>不覆盖范围</h2>
 *
 * {@code sequenced_assembly} 和 {@code mechanical_crafting} **不是** {@code ProcessingRecipe}
 * 的子类，走不到这里。前者嵌套了一整串子配方，后面要单独处理（或者诚实标 opaque）——
 * 按现在的方式读它会「看起来可读」，实际漏掉大部分成本。
 *
 * <p><b>验证方式</b>：{@code instanceof ProcessingRecipe} 需要真实的 Create 类，
 * 而测试源码集看不到它，所以这一环只能靠装了 Create 的实例验证
 * （见 live.ts 里的 Create 段：装了才验，没装会明确说明跳过了）。
 * 本机开发实例没装 Create，所以**这条路径的运行行为尚未被实测过** ——
 * 能验证的是「编译对着 Create 真实 jar 通过」和「没装 Create 时不会崩」。
 */
final class CreateAdapter implements RecipeTypeAdapter {

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof ProcessingRecipe<?, ?>;
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing)) return null;
        int ticks = processing.getProcessingDuration();
        // 与 CookingAdapter 同一条约定：0 或负数不是「瞬间完成」，是没读到有意义的值。
        return ticks > 0 ? ticks : null;
    }

    @Override
    public List<ItemStack> results(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing)) return null;

        List<ItemStack> guaranteed = new ArrayList<>();
        for (ProcessingOutput output : processing.getRollableResults()) {
            if (ResultChance.classify(output.getChance()) == ResultChance.Kind.GUARANTEED) {
                guaranteed.add(output.getStack());
            }
        }
        // 返回列表（哪怕是空的）而不是 null：这里的语义是「产出由我说了算」，
        // 所以绝不能让上层退回 getResultItem() —— 那只会拿到列表里的第一个。
        return guaranteed;
    }

    @Override
    public List<ChanceResult> chanceResults(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing)) return null;

        List<ChanceResult> out = new ArrayList<>();
        for (ProcessingOutput output : processing.getRollableResults()) {
            if (ResultChance.classify(output.getChance()) == ResultChance.Kind.PROBABILISTIC) {
                out.add(new ChanceResult(output.getStack(), output.getChance()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public List<FluidStack> fluidResults(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing)) return null;
        List<FluidStack> out = new ArrayList<>();
        for (FluidStack stack : processing.getFluidResults()) {
            if (!stack.isEmpty()) out.add(stack);
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public List<SizedFluidIngredient> fluidIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing)) return null;
        List<SizedFluidIngredient> out = new ArrayList<>();
        for (SizedFluidIngredient ingredient : processing.getFluidIngredients()) {
            if (!ingredient.ingredient().hasNoFluids()) out.add(ingredient);
        }
        return out.isEmpty() ? null : out;
    }
}
