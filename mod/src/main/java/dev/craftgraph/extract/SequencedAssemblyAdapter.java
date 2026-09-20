package dev.craftgraph.extract;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;

import dev.craftgraph.api.Models;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.List;

/**
 * Create 的「序列组装」适配器（{@code create:sequenced_assembly}）。
 *
 * <h2>它现在只做一件事：把 Create 的对象读成我们的记录</h2>
 *
 * 「遍数怎么乘」「中间产物怎么排除」「权重怎么归一化」「各步耗时怎么求和」
 * 全在 {@link SequencePlan} 与 {@link WeightPool} 里（core，有单测）。
 * 这个文件剩下的全是访问器调用 —— 换句话说，**1.20.1 那一侧要照着重写的就这些**。
 *
 * <h2>为什么它需要单独一个适配器</h2>
 *
 * {@link CreateAdapter} 处理的是 {@code ProcessingRecipe} 家族，而
 * {@code SequencedAssemblyRecipe} **不继承它**（直接 {@code implements Recipe}）。
 * 它也没有覆写 {@code getIngredients()} —— 所以通用接口读到 0 个输入，
 * 整条配方被标成 opaque。
 *
 * <p>标 opaque 本身是**诚实**的，不是静默错误。但这类配方恰恰是玩家最费料的一环
 * （精准机械动力要 5 轮 × 3 步），所以值得把它读出来。
 *
 * <h2>真实形状</h2>
 *
 * <pre>{@code
 * "ingredient": { "tag": "forge:plates/gold" },   // 基础物品，一个产出只吃 1 个
 * "loops": 5,                                      // 整条序列要走 5 遍
 * "sequence": [ deploying, deploying, deploying ],  // 每一步是一个 ProcessingRecipe
 * "results": [ { "chance": 120.0, "item": "create:precision_mechanism" }, ... ]
 * }</pre>
 *
 * 两条来自源码、不是猜的结论（详述在 core 那两个类的注释里）：
 * 总步数 = {@code sequence.size() × loops}；{@code results} 里的 {@code chance}
 * **是权重不是概率**，而 {@code getResultItem()} 返回的只是权重池的第一个元素。
 *
 * <h2>验证方式</h2>
 *
 * 同 {@link CreateAdapter}：{@code instanceof SequencedAssemblyRecipe} 需要真实的 Create 类，
 * 而测试源码集看不到它，所以运行行为只能靠装了 Create 的实例验证
 * （{@code npm run live} 的 Create 段），本机开发实例没装 Create。
 */
final class SequencedAssemblyAdapter implements RecipeTypeAdapter<Recipe<?>> {

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof SequencedAssemblyRecipe;
    }

    @Override
    public List<RawSlot> ingredients(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        // 返回列表（可能是空的）而不是 null：这条配方的输入由我说了算，
        // 不能让上层退回 getIngredients()（那必然是空列表，配方会变 opaque）。
        return SequencePlan.itemInputs(
                List.of(ItemIds.itemSlot(assembly.getIngredient())),
                stepsOf(assembly),
                transitionalItemId(assembly),
                assembly.getLoops());
    }

    @Override
    public List<RawSlot> fluidIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;
        List<RawSlot> out = SequencePlan.fluidInputs(stepsOf(assembly), assembly.getLoops());
        return out.isEmpty() ? null : out;
    }

    /** 结果池为空 = 这条序列组装确实不产出（同 {@link CreateAdapter#declaresNoOutput}）。 */
    @Override
    public boolean declaresNoOutput(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return false;
        return classifyPool(assembly).isEmpty();
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;
        return SequencePlan.totalDuration(stepsOf(assembly), assembly.getLoops());
    }

    @Override
    public List<Models.ItemStack> results(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        // 产出必然由我说了算：getResultItem() 返回的是权重池的第一个元素，
        // 拿它当必然产出会漏掉「你有可能掷到废料」这件事。
        List<Models.ItemStack> guaranteed = new ArrayList<>();
        for (WeightPool.Classified entry : classifyPool(assembly)) {
            if (entry.kind() == ResultChance.Kind.GUARANTEED) guaranteed.add(entry.stack());
        }
        return guaranteed;
    }

    @Override
    public List<Models.ChanceOutput> chanceResults(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        List<Models.ChanceOutput> out = new ArrayList<>();
        for (WeightPool.Classified entry : classifyPool(assembly)) {
            if (entry.kind() == ResultChance.Kind.PROBABILISTIC) {
                out.add(new Models.ChanceOutput(entry.stack(), entry.probability()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ---------------------------------------------------------------- 读 Create 的对象

    /** 把序列读成 core 的 {@link SequencePlan.Step}。空槽位在这里就滤掉。 */
    private static List<SequencePlan.Step> stepsOf(SequencedAssemblyRecipe assembly) {
        List<SequencePlan.Step> steps = new ArrayList<>();
        for (SequencedRecipe<?> step : assembly.getSequence()) {
            // 注意 1.20.1 上这里是 ProcessingRecipe<?>（只有一个类型参数）
            ProcessingRecipe<?, ?> stepRecipe = step.getRecipe();

            List<RawSlot> items = new ArrayList<>();
            for (Ingredient ingredient : stepRecipe.getIngredients()) {
                RawSlot slot = ItemIds.itemSlot(ingredient);
                if (!slot.isEmpty()) items.add(slot);
            }

            List<RawSlot> fluids = new ArrayList<>();
            for (SizedFluidIngredient fluidIngredient : stepRecipe.getFluidIngredients()) {
                if (fluidIngredient.ingredient().hasNoFluids()) continue;
                RawSlot slot = ItemIds.fluidSlot(fluidIngredient);
                if (!slot.isEmpty()) fluids.add(slot);
            }

            steps.add(new SequencePlan.Step(items, fluids, TickDuration.ofOrNull(stepRecipe.getProcessingDuration())));
        }
        return steps;
    }

    /** 权重池 → 分类结果。归一化与分类规则都在 {@link WeightPool} 里。 */
    private static List<WeightPool.Classified> classifyPool(SequencedAssemblyRecipe assembly) {
        List<WeightPool.Entry> pool = new ArrayList<>();
        for (ProcessingOutput output : assembly.resultPool) {
            if (output == null) continue;
            pool.add(new WeightPool.Entry(ItemIds.stack(output.getStack()), output.getChance()));
        }
        return WeightPool.classify(pool);
    }

    private static String transitionalItemId(SequencedAssemblyRecipe assembly) {
        ItemStack transitional = assembly.getTransitionalItem();
        return ItemIds.itemId(transitional);
    }
}
