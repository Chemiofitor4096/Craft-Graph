package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.extract.AdapterRegistry.Candidate;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * 适配器登记处（1.21.1 变体）。
 *
 * <p>惰性注册、异常兜底、null/空列表分发这些逻辑住在 core 的
 * {@link AdapterRegistry} —— 两个变体曾经各有一份逐字相同的 228 行，
 * 只差 loader 的 import，所以搬了过去。这里只剩变体自己才知道的事：
 * 哪些适配器无条件注册、哪些要等模组装好（{@link ModList#isLoaded} 是
 * 全部与 loader 相关的调用点）。
 *
 * <p>保持静态方法门面：{@link RecipeExtractor} 与 {@code ClientBridge}
 * 的调用方式不变，惰性语义也不变 —— 注册表在第一次抽取时才构建，而不是类加载时。
 */
public final class RecipeAdapters {

    /** 惰性构建，见 {@link #registry()}。抽取只在主线程做，所以不需要同步。 */
    private static AdapterRegistry<Recipe<?>> registry;

    private RecipeAdapters() {
    }

    /**
     * 首次使用时才构建。候选列表必须放在这里（而不是静态字段初始化里）：
     * {@code CreateAdapter::new} 这个方法引用在**求值时**就要解析 CreateAdapter，
     * 放在类加载路径上会让没装 Create 的实例在加载本类时直接
     * {@code NoClassDefFoundError} —— 完整的道理见 AdapterRegistry 的类注释。
     */
    private static AdapterRegistry<Recipe<?>> registry() {
        AdapterRegistry<Recipe<?>> r = registry;
        if (r == null) {
            r = new AdapterRegistry<>(
                    List.<Candidate<Recipe<?>>>of(
                            Candidate.always("Cooking", CookingAdapter::new),
                            Candidate.always("Smithing", SmithingAdapter::new),
                            new Candidate<>("create", "Create", CreateAdapter::new),
                            // 序列组装不是 ProcessingRecipe 的子类，所以是另一个适配器。
                            // 两个都在同一个 isLoaded 判断下 —— 它们共用一份 Create 软依赖。
                            new Candidate<>("create", "Create（序列组装）", SequencedAssemblyAdapter::new)),
                    modId -> ModList.get().isLoaded(modId));
            registry = r;
        }
        return r;
    }

    /** 适配器调用失败次数。由 ClientBridge 打进日志 —— 降级必须有声音。 */
    public static int adapterFailures() {
        return registry().failures();
    }

    public static boolean declaresNoOutput(Recipe<?> recipe) {
        return registry().declaresNoOutput(recipe);
    }

    /** 读耗时（游戏刻）。没有适配器能处理、或适配器读不到时返回 {@code null}。 */
    public static Integer duration(Recipe<?> recipe) {
        return registry().duration(recipe);
    }

    /**
     * 读输入槽位（物品）。
     *
     * @param generic 通用接口（{@code getIngredients()}）读出来的槽位
     * @return 适配器给的槽位；没有适配器能处理、或适配器声明「不归我管」时返回 {@code generic}
     */
    public static List<RawSlot> ingredients(Recipe<?> recipe, List<RawSlot> generic) {
        return registry().ingredients(recipe, generic);
    }

    /**
     * 读产出。适配器返回空列表时结果就是空列表（调用方会据此标 opaque），
     * 见 AdapterRegistry#results。
     */
    public static List<Models.ItemStack> results(Recipe<?> recipe, List<Models.ItemStack> generic) {
        return registry().results(recipe, generic);
    }

    /** 读概率产出。没有通用回退，返回空列表就是「没有」。 */
    public static List<Models.ChanceOutput> chanceResults(Recipe<?> recipe) {
        return registry().chanceResults(recipe);
    }

    /** 读流体输出。没有通用回退。 */
    public static List<Models.FluidStack> fluidResults(Recipe<?> recipe) {
        return registry().fluidResults(recipe);
    }

    /** 读流体输入。没有通用回退。 */
    public static List<RawSlot> fluidIngredients(Recipe<?> recipe) {
        return registry().fluidIngredients(recipe);
    }
}
