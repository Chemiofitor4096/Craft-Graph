package dev.craftgraph.extract;

import com.mojang.logging.LogUtils;

import dev.craftgraph.extract.RecipeTypeAdapter.ChanceResult;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 适配器登记处：按配方对象挑一个能读它特有字段的适配器。
 *
 * <h2>加新适配器的方式</h2>
 *
 * 写一个 {@link RecipeTypeAdapter} 实现，然后加进 {@link #adapters()}。
 * 顺序有意义：先注册的先匹配。JEI/EMI 那类「能读一切」的适配器应该排最后，
 * 让它只在具体类型适配器都不认的时候兜底。
 *
 * <h2>没有适配器匹配时怎么办</h2>
 *
 * 返回 {@code null} 字段，也就是「读不到」——**不是**给一个默认值。
 * 下游拿到 null 会说「耗时未知，无法计算机器数」，拿到 0 会说「不用时间」，
 * 后者是一个不自知的错误答案。见 {@link RecipeTypeAdapter} 的约定。
 *
 * <h2>⚠️ 模组适配器必须惰性注册</h2>
 *
 * {@link CreateAdapter} 的代码里直接引用了 Create 的类。**一旦它被放进一个静态列表，
 * 类加载就会连带解析那些类**，于是没装 Create 的实例会在加载本类时直接
 * {@code NoClassDefFoundError} —— 而且是在构建快照时，也就是整个桥接都不可用。
 *
 * <p>所以列表是懒建的，并且先用 {@link ModList#isLoaded} 判断。
 * JVM 是**按指令惰性解析**类的：没装 Create 时那个分支不执行，
 * `new CreateAdapter()` 这条指令永远不会被解析，也就不会去找 Create 的类。
 *
 * <p>外面还包了一层 {@code catch (Throwable)}：万一将来某个模组的类加载出了别的问题
 * （版本不匹配、依赖缺失），后果应该是「少一个适配器」，
 * 而不是「整个快照建不出来」。这个项目对「一个坏配方带崩整条链路」是有教训的。
 */
public final class RecipeAdapters {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 惰性构建，见类注释。抽取只在主线程做，所以不需要同步。 */
    private static List<RecipeTypeAdapter> adapters;

    private RecipeAdapters() {
    }

    private static List<RecipeTypeAdapter> adapters() {
        if (adapters == null) {
            List<RecipeTypeAdapter> list = new ArrayList<>();
            list.add(new CookingAdapter());
            list.add(new SmithingAdapter());
            addIfLoaded(list, "create", "Create",
                    () -> new CreateAdapter());
            adapters = List.copyOf(list);
        }
        return adapters;
    }

    /**
     * 装了某个模组才加载它的适配器。
     *
     * <p>传的是个 lambda 而不是实例：把 `new XxxAdapter()` 留在 lambda 里，
     * 类的解析就推迟到 lambda 被调用时 —— 这正是「没装就不解析」的实现方式。
     */
    private static void addIfLoaded(List<RecipeTypeAdapter> list, String modId, String displayName,
                                    java.util.function.Supplier<RecipeTypeAdapter> factory) {
        if (!ModList.get().isLoaded(modId)) return;
        try {
            RecipeTypeAdapter adapter = factory.get();
            list.add(adapter);
            LOGGER.debug("CraftGraph 已启用 {} 的配方适配器", displayName);
        } catch (Throwable t) {
            // 用 Throwable 而不是 Exception：类加载失败是 Error。
            // 后果限定为「少一个适配器」，而不是整个快照建不出来。
            LOGGER.warn("CraftGraph 加载 {} 的配方适配器失败，将退回通用读取（该类配方的产出可能不完整）：{}",
                    displayName, t.toString());
        }
    }

    /** 找能处理这条配方的适配器。没有就返回 {@code null}。 */
    public static RecipeTypeAdapter forRecipe(Recipe<?> recipe) {
        for (RecipeTypeAdapter adapter : adapters()) {
            if (adapter.handles(recipe)) return adapter;
        }
        return null;
    }

    /**
     * 读耗时。
     *
     * @return 游戏刻；没有适配器能处理、或适配器读不到时返回 {@code null}
     */
    public static Integer duration(Recipe<?> recipe) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        return adapter == null ? null : adapter.duration(recipe);
    }

    /**
     * 读输入槽位。
     *
     * @param generic 通用接口（{@code getIngredients()}）读出来的结果
     * @return 适配器给的槽位；没有适配器能处理、或适配器声明「不归我管」时返回 {@code generic}
     */
    public static List<Ingredient> ingredients(Recipe<?> recipe, List<Ingredient> generic) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<Ingredient> override = adapter.ingredients(recipe);
        return override == null ? generic : override;
    }

    /**
     * 读产出。
     *
     * <p><b>参数里的 generic 已经排除了空栈</b>（调用方用 {@code getResultItem()} 取过之后
     * 过滤掉了 EMPTY）。适配器返回空列表时结果就是空列表 —— 调用方会据此标 opaque，
     * 这正是「产出是占位符，别报出去」所需要的语义。
     *
     * @param generic 通用接口（{@code getResultItem()}）读出来的产物
     * @return 最终产物列表；可能是空的，表示确定读不到产出
     */
    public static List<ItemStack> results(Recipe<?> recipe, List<ItemStack> generic) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<ItemStack> override = adapter.results(recipe);
        return override == null ? generic : override;
    }

    /**
     * 读概率产出。
     *
     * <p>这个方法没有「通用回退」——原版和 NeoForge 都没有任何接口能给出带概率的产出，
     * 它只存在于各模组自己的字段里。所以返回空列表就是「没有」，不存在歧义。
     */
    public static List<ChanceResult> chanceResults(Recipe<?> recipe) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<ChanceResult> out = adapter.chanceResults(recipe);
        return out == null ? List.of() : out;
    }

    /** 读流体输出。同样没有通用回退。 */
    public static List<FluidStack> fluidResults(Recipe<?> recipe) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<FluidStack> out = adapter.fluidResults(recipe);
        return out == null ? List.of() : out;
    }

    /** 读流体输入。同样没有通用回退。 */
    public static List<SizedFluidIngredient> fluidIngredients(Recipe<?> recipe) {
        RecipeTypeAdapter adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<SizedFluidIngredient> out = adapter.fluidIngredients(recipe);
        return out == null ? List.of() : out;
    }
}
