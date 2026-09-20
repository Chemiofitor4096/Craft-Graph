package dev.craftgraph.extract;

import com.mojang.logging.LogUtils;

import dev.craftgraph.api.Models;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 适配器登记处（**1.20.1 版**）：按配方对象挑一个能读它特有字段的适配器。
 *
 * <p>与 1.21.1 那份逻辑相同，差别只有 loader 的包名
 * （{@code net.minecraftforge.fml.ModList}），以及 Create 在 1.20.1 上多了个
 * {@code CreateFluids} 帮手（那边没有通用的流体原料类型）。
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
 *
 * <p>本类住在 {@code mod} 而适配器接口住在 {@code core}，是因为这里要用
 * {@link ModList} 与 {@code Recipe<?>}（都是 MC/loader 类型）；
 * 接口本身只谈我们自己的类型，所以两边能分开。
 */
public final class RecipeAdapters {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 惰性构建，见类注释。抽取只在主线程做，所以不需要同步。 */
    private static List<RecipeTypeAdapter<Recipe<?>>> adapters;

    private RecipeAdapters() {
    }

    private static List<RecipeTypeAdapter<Recipe<?>>> adapters() {
        if (adapters == null) {
            List<RecipeTypeAdapter<Recipe<?>>> list = new ArrayList<>();
            list.add(new CookingAdapter());
            list.add(new SmithingAdapter());
            addIfLoaded(list, "create", "Create", CreateAdapter::new);
            // 序列组装不是 ProcessingRecipe 的子类，所以是另一个适配器。
            // 两个都在同一个 isLoaded 判断下 —— 它们共用一份 Create 软依赖。
            addIfLoaded(list, "create", "Create（序列组装）", SequencedAssemblyAdapter::new);
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
    private static void addIfLoaded(List<RecipeTypeAdapter<Recipe<?>>> list, String modId, String displayName,
                                    Supplier<RecipeTypeAdapter<Recipe<?>>> factory) {
        if (!ModList.get().isLoaded(modId)) return;
        try {
            RecipeTypeAdapter<Recipe<?>> adapter = factory.get();
            list.add(adapter);
            LOGGER.debug("CraftGraph enabled the {} recipe adapter", displayName);
        } catch (Throwable t) {
            // 用 Throwable 而不是 Exception：类加载失败是 Error。
            // 后果限定为「少一个适配器」，而不是整个快照建不出来。
            LOGGER.warn("CraftGraph failed to load the {} recipe adapter; "
                    + "falling back to generic reading (its recipes may report incomplete outputs): {}",
                    displayName, t.toString());
        }
    }

    /** 找能处理这条配方的适配器。没有就返回 {@code null}。 */
    public static RecipeTypeAdapter<Recipe<?>> forRecipe(Recipe<?> recipe) {
        for (RecipeTypeAdapter<Recipe<?>> adapter : adapters()) {
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
        RecipeTypeAdapter<Recipe<?>> adapter = forRecipe(recipe);
        return adapter == null ? null : adapter.duration(recipe);
    }

    /**
     * 读输入槽位（物品）。
     *
     * @param generic 通用接口（{@code getIngredients()}）读出来的槽位
     * @return 适配器给的槽位；没有适配器能处理、或适配器声明「不归我管」时返回 {@code generic}
     */
    public static List<RawSlot> ingredients(Recipe<?> recipe, List<RawSlot> generic) {
        RecipeTypeAdapter<Recipe<?>> adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<RawSlot> override = adapter.ingredients(recipe);
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
    public static List<Models.ItemStack> results(Recipe<?> recipe, List<Models.ItemStack> generic) {
        RecipeTypeAdapter<Recipe<?>> adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<Models.ItemStack> override = adapter.results(recipe);
        return override == null ? generic : override;
    }

    /**
     * 读概率产出。
     *
     * <p>这个方法没有「通用回退」——原版和 NeoForge 都没有任何接口能给出带概率的产出，
     * 它只存在于各模组自己的字段里。所以返回空列表就是「没有」，不存在歧义。
     */
    public static List<Models.ChanceOutput> chanceResults(Recipe<?> recipe) {
        RecipeTypeAdapter<Recipe<?>> adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<Models.ChanceOutput> out = adapter.chanceResults(recipe);
        return out == null ? List.of() : out;
    }

    /** 读流体输出。同样没有通用回退。 */
    public static List<Models.FluidStack> fluidResults(Recipe<?> recipe) {
        RecipeTypeAdapter<Recipe<?>> adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<Models.FluidStack> out = adapter.fluidResults(recipe);
        return out == null ? List.of() : out;
    }

    /** 读流体输入。同样没有通用回退。 */
    public static List<RawSlot> fluidIngredients(Recipe<?> recipe) {
        RecipeTypeAdapter<Recipe<?>> adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<RawSlot> out = adapter.fluidIngredients(recipe);
        return out == null ? List.of() : out;
    }
}
