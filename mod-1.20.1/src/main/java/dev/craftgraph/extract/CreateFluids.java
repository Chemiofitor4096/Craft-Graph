package dev.craftgraph.extract;

import com.simibubi.create.foundation.fluid.FluidIngredient;

import net.minecraftforge.fluids.FluidStack;

import java.util.List;

/**
 * Create 的流体原料 → 我们的记录（**1.20.1 专用**）。
 *
 * <h2>为什么它单独一个类，而不是并进 ItemIds</h2>
 *
 * 1.21.1 那边，流体的通用原料类型是 NeoForge 的 {@code SizedFluidIngredient}，
 * 它**总是存在**（就在 loader 里），所以 {@code ItemIds} 直接收它没有风险。
 *
 * <p>1.20.1 上 Forge 根本没有这个类 —— 流体原料只有各模组自己的形状，
 * Create 用的是 {@code com.simibubi.create.foundation.fluid.FluidIngredient}。
 * 如果把它写进 {@code ItemIds} 的方法签名，那个类就成了「没装 Create 也会被引用」的
 * 类型，正是 {@code RecipeAdapters} 那段注释警告过的形状（构建快照时 NoClassDefFoundError，
 * 整个桥接不可用）。
 *
 * <p>所以这里单独放一个只被 Create 适配器引用的类 —— 那些适配器在
 * {@code ModList.isLoaded("create")} 判断之后才实例化，JVM 按指令惰性解析，
 * 没装 Create 时这个类不会被解析。
 *
 * <p>这个差异是**被迫**的，不是设计选择：1.20.1 没有跨模组通用的流体原料类型。
 * 1.21.1 那份没有对应文件。
 */
final class CreateFluids {

    private CreateFluids() {
    }

    /**
     * @return 槽位；候选全空时返回空槽位（调用方按约定滤掉）
     */
    static RawSlot slot(FluidIngredient ingredient) {
        List<FluidStack> stacks = ingredient.getMatchingFluidStacks();
        // 数量取 Create 要求的量（mB）。这里与 1.21.1 的 SizedFluidIngredient#amount 对应。
        return ItemIds.fluidSlot(ingredient.getRequiredAmount(), ItemIds.fluidIds(stacks));
    }

    /** 这个原料是不是什么都匹配不到 —— 1.21.1 那边对应 {@code hasNoFluids()}。 */
    static boolean isEmpty(FluidIngredient ingredient) {
        return ingredient.getMatchingFluidStacks().isEmpty();
    }
}
