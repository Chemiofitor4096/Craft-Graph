package dev.craftgraph.extract;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.foundation.fluid.FluidIngredient;

import dev.craftgraph.api.Models;

import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * Create 的加工配方适配器（**1.20.1 版**）。
 *
 * <h2>与 1.21.1 那份的差别只有类型</h2>
 *
 * 判断逻辑（概率怎么分类、哪些算必然产出）完全一样，都在 core 里
 * （{@link ResultChance}、{@link WeightPool}、{@link TickDuration}）。这个文件不同的地方：
 *
 * <ul>
 *   <li>{@code ProcessingRecipe} 在 1.20.1 上只有**一个**类型参数
 *       （{@code ProcessingRecipe<T extends Container>}，1.21.1 是两个）。</li>
 *   <li>流体栈是 {@code net.minecraftforge.fluids.FluidStack}。</li>
 *   <li>流体原料是 Create 自己的 {@code foundation.fluid.FluidIngredient}
 *       （Forge 1.20.1 没有 NeoForge 的 {@code SizedFluidIngredient}），
 *       所以拆 id 与数量这件事在 {@link CreateFluids} 里。</li>
 * </ul>
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
 * 而且它不会报错，只会安静地少报。
 *
 * <h2>这个适配器读不到机器名</h2>
 *
 * {@code ProcessingRecipe} 没有覆写 {@code getToastSymbol()}，拿到的是接口默认值
 * {@code minecraft:crafting_table}，而 {@link MachineTable} 的规则是「表外类型不采信默认值」，
 * 于是 Create 配方的 {@code machine} 是 null。这是刻意的：与其手写一张猜出来的对照表，
 * 不如等 JEI 的催化剂（权威答案）。{@code duration} 有了但 {@code machine} 是 null 时，
 * 产线仍能算出「N 台」，只是机器清单里没有名字。
 *
 * <p><b>验证方式</b>：{@code instanceof ProcessingRecipe} 需要真实的 Create 类，
 * 测试源码集看不到它，所以只能靠装了 Create 的实例验证（{@code npm run live} 的 Create 段）。
 * 本机开发实例没装 Create，所以运行行为未被实测，能验证的是「编译对着 Create 真实 jar 通过」
 * 和「没装 Create 时不会崩」。
 */
final class CreateAdapter implements RecipeTypeAdapter<Recipe<?>> {

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof ProcessingRecipe<?>;
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?> processing)) return null;
        return TickDuration.ofOrNull(processing.getProcessingDuration());
    }

    @Override
    public List<Models.ItemStack> results(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?> processing)) return null;

        List<Models.ItemStack> guaranteed = new ArrayList<>();
        for (ProcessingOutput output : processing.getRollableResults()) {
            if (ResultChance.classify(output.getChance()) != ResultChance.Kind.GUARANTEED) continue;
            Models.ItemStack stack = ItemIds.stack(output.getStack());
            // 产出里出现空栈时跳过而不是报一个 minecraft:air 出去
            if (stack != null) guaranteed.add(stack);
        }
        // 返回列表（哪怕是空的）而不是 null：这里的语义是「产出由我说了算」，
        // 所以绝不能让上层退回 getResultItem() —— 那只会拿到列表里的第一个。
        return guaranteed;
    }

    @Override
    public List<Models.ChanceOutput> chanceResults(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?> processing)) return null;

        List<Models.ChanceOutput> out = new ArrayList<>();
        for (ProcessingOutput output : processing.getRollableResults()) {
            if (ResultChance.classify(output.getChance()) != ResultChance.Kind.PROBABILISTIC) continue;
            Models.ItemStack stack = ItemIds.stack(output.getStack());
            if (stack != null) out.add(new Models.ChanceOutput(stack, output.getChance()));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ProcessingRecipe} 的产出只有两个来源：{@code results}（物品）与
     * {@code fluidResults}（流体）。两个都空 = 这条配方确实不产出 —— 燃料定义就是这样
     * （实测 {@code petrochem:*_fuel} 的耗时和输入都读到了，产出确实是空的）。
     */
    @Override
    public boolean declaresNoOutput(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?> processing)) return false;
        return processing.getRollableResults().isEmpty() && processing.getFluidResults().isEmpty();
    }

    @Override
    public List<Models.FluidStack> fluidResults(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?> processing)) return null;
        List<Models.FluidStack> out = ItemIds.fluids(processing.getFluidResults());
        return out.isEmpty() ? null : out;
    }

    @Override
    public List<RawSlot> fluidIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?> processing)) return null;
        List<RawSlot> out = new ArrayList<>();
        for (FluidIngredient ingredient : processing.getFluidIngredients()) {
            if (CreateFluids.isEmpty(ingredient)) continue;
            RawSlot slot = CreateFluids.slot(ingredient);
            if (!slot.isEmpty()) out.add(slot);
        }
        return out.isEmpty() ? null : out;
    }
}
