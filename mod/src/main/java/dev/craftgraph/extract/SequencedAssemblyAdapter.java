package dev.craftgraph.extract;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;

import dev.craftgraph.extract.RecipeTypeAdapter.ChanceResult;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.List;

/**
 * Create 的「序列组装」适配器（{@code create:sequenced_assembly}）。
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
 * 语义是读 Create 源码确认的，不是猜的：
 *
 * <ul>
 *   <li>需要走的总步数 = {@code sequence.size() × loops}
 *       （{@code SequencedAssemblyRecipe} 里 {@code (step + 1) / sequence.size() >= loops}）。
 *       所以每一步的原料要按 {@code loops} 倍计。</li>
 *   <li>{@code results} 里的 {@code chance} **是权重不是概率** ——
 *       Create 自己的 {@code getOutputChance()} 就是 {@code getFirst().getChance() / 总权重}。
 *       所以这里按权重归一化成概率。</li>
 *   <li>{@code getResultItem()} 返回的是 {@code resultPool} 的第一个元素，
 *       拿它当「必然产出」是错的：实际是**按权重掷一次**，你有可能拿到废料。</li>
 * </ul>
 *
 * <h2>中间产物必须排除</h2>
 *
 * 每一步的输入里都带着**中间产物**（例如 {@code create:incomplete_precision_mechanism}）——
 * 那是线上自己造出来的过渡物品，不是原料。不排除的话，原料表里会凭空多出一个
 * 玩家根本拿不到的物品，整棵配方树也会跟着跑偏。
 *
 * <h2>倍数怎么表达</h2>
 *
 * 协议的槽位没有「消耗几次」的概念，所以按 {@code loops} 次**重复同一个槽位** ——
 * 这跟原版有序合成的做法一致（「3 个铁锭」就是 3 个各含 1 个铁锭的槽位），
 * TypeScript 侧在递归前会按物品合并并求和。这样接口不用改。
 *
 * <h2>耗时</h2>
 *
 * 总耗时 = {@code loops × Σ 各步耗时}，对应「一条组装线跑完一个产出要多久」,
 * 下游据此算出的「N 台」实际上是「N 条并行组装线」。这个解释是站得住的，
 * 所以如实报出来而不是留 null。机器名仍然是 null（Create 不覆写
 * {@code getToastSymbol()}，且一条组装线本来就对应多个方块），
 * 所以它不会出现在机器清单里，只在逐环节明细里显示台数。
 *
 * <p><b>验证方式</b>同 {@link CreateAdapter}：需要装了 Create 的实例，
 * 本机 dev 实例没有，所以运行行为未被实测，只保证编译对着真实 jar 通过。
 */
final class SequencedAssemblyAdapter implements RecipeTypeAdapter {

    /** 一个产出只吃 1 个基础物品。 */
    private static final int BASE_COUNT = 1;

    @Override
    public boolean handles(Recipe<?> recipe) {
        return recipe instanceof SequencedAssemblyRecipe;
    }

    @Override
    public List<Ingredient> ingredients(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        List<Ingredient> out = new ArrayList<>();
        // 基础物品：一个产出吃一个
        for (int i = 0; i < BASE_COUNT; i++) {
            out.add(assembly.getIngredient());
        }

        Item transitional = transitionalItem(assembly);
        int loops = Math.max(1, assembly.getLoops());
        for (SequencedRecipe<?> step : assembly.getSequence()) {
            ProcessingRecipe<?, ?> stepRecipe = step.getRecipe();
            for (Ingredient ingredient : stepRecipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                // 中间产物不算原料，见类注释
                if (isOnlyTransitionalItem(ingredient, transitional)) continue;
                // 序列要走 loops 遍，所以每一步的原料也吃 loops 次
                for (int i = 0; i < loops; i++) {
                    out.add(ingredient);
                }
            }
        }
        // 返回列表（可能是空的）而不是 null：这条配方的输入由我说了算，
        // 不能让上层退回 getIngredients()（那必然是空列表，配方会变 opaque）。
        return out;
    }

    @Override
    public List<SizedFluidIngredient> fluidIngredients(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        int loops = Math.max(1, assembly.getLoops());
        List<SizedFluidIngredient> out = new ArrayList<>();
        for (SequencedRecipe<?> step : assembly.getSequence()) {
            for (SizedFluidIngredient fluidIngredient : step.getRecipe().getFluidIngredients()) {
                if (fluidIngredient.ingredient().hasNoFluids()) continue;
                for (int i = 0; i < loops; i++) {
                    out.add(fluidIngredient);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        int loops = Math.max(1, assembly.getLoops());
        int total = 0;
        for (SequencedRecipe<?> step : assembly.getSequence()) {
            int stepTicks = step.getRecipe().getProcessingDuration();
            if (stepTicks > 0) total += stepTicks;
        }
        total *= loops;
        return total > 0 ? total : null;
    }

    @Override
    public List<ItemStack> results(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        // 产出必然由我说了算：getResultItem() 返回的是权重池的第一个元素，
        // 拿它当必然产出会漏掉「你有可能掷到废料」这件事。
        List<ItemStack> guaranteed = new ArrayList<>();
        for (PoolEntry entry : normalisePool(assembly)) {
            if (entry.kind() == ResultChance.Kind.GUARANTEED) guaranteed.add(entry.stack());
        }
        return guaranteed;
    }

    @Override
    public List<ChanceResult> chanceResults(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) return null;

        List<ChanceResult> out = new ArrayList<>();
        for (PoolEntry entry : normalisePool(assembly)) {
            if (entry.kind() == ResultChance.Kind.PROBABILISTIC) {
                out.add(new ChanceResult(entry.stack(), entry.probability()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ---------------------------------------------------------------- 权重池

    private record PoolEntry(ItemStack stack, float probability, ResultChance.Kind kind) {
    }

    /**
     * 把权重池归一化成概率，并按 {@link ResultChance} 分类。
     *
     * <p>分类规则全在 {@link ResultChance#classifyWeight} 里（纯逻辑、有单测）。
     * 这里只负责把 {@code Infinity} / {@code NaN} 的权重排除出**权重和**：
     * 它们自己经归一化后会各自判成必然产出，但不能让它们把和变成 Infinity，
     * 否则其他所有条目都会被算成 0。
     */
    private List<PoolEntry> normalisePool(SequencedAssemblyRecipe assembly) {
        float totalWeight = 0f;
        List<ProcessingOutput> pool = assembly.resultPool;
        for (ProcessingOutput entry : pool) {
            if (entry == null) continue;
            float weight = entry.getChance();
            if (weight > 0f && Float.isFinite(weight)) {
                totalWeight += weight;
            }
        }
        if (totalWeight <= 0f) return List.of();

        List<PoolEntry> out = new ArrayList<>();
        for (ProcessingOutput entry : pool) {
            if (entry == null) continue;
            ItemStack stack = entry.getStack();
            if (stack == null || stack.isEmpty()) continue;

            ResultChance.Kind kind = ResultChance.classifyWeight(entry.getChance(), totalWeight);
            if (kind == ResultChance.Kind.NEVER) continue;

            // GUARANTEED 那一支来自权重 >= 权重和（含 Infinity / NaN），概率记 1
            float probability = kind == ResultChance.Kind.GUARANTEED
                    ? 1f
                    : entry.getChance() / totalWeight;
            out.add(new PoolEntry(stack, probability, kind));
        }
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    private static Item transitionalItem(SequencedAssemblyRecipe assembly) {
        ItemStack transitional = assembly.getTransitionalItem();
        return (transitional == null || transitional.isEmpty()) ? null : transitional.getItem();
    }

    /**
     * 这个槽位是不是「只接受中间产物」。
     *
     * <p>判据是**全都**是中间产物才排除，而不是「含有它就排除」：
     * 万一某个槽位是「中间产物 + 别的可选物」，含有就排除会把真实原料一起丢掉。
     */
    private static boolean isOnlyTransitionalItem(Ingredient ingredient, Item transitional) {
        if (transitional == null) return false;
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return false;
        for (ItemStack stack : items) {
            if (stack.getItem() != transitional) return false;
        }
        return true;
    }
}
