package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 适配器登记处的通用实现：按配方对象挑一个能读它特有字段的适配器。
 *
 * <h2>为什么它住在 core</h2>
 *
 * 两个 MC 版本曾经各有一份逐字相同的登记处（228 行里只差 loader 的一个 import），
 * 任何一处改动都要记得同步两份 —— 忘了的那份要等另一个版本的玩家撞上才发现。
 * 这份逻辑本身不认识任何 Minecraft / loader 类型，所以搬进 core 让「两份必须一致」
 * 变成「只有一份」，由编译器保证。
 *
 * <p>它唯一不能替变体做的决定是「模组装没装」：那是 loader API
 * （NeoForge 与 Forge 的 {@code ModList}，包名不同）。所以这个判断以
 * {@link Predicate} 注入，本类不 import 任何 loader 类 —— 否则 core 就装不进
 * 两个变体共用的那个「无 Minecraft 类路径」了。
 *
 * <h2>加新适配器的方式</h2>
 *
 * 写一个 {@link RecipeTypeAdapter} 实现，然后在各变体的登记处把它加进候选列表。
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
 * 模组适配器（如 CreateAdapter）的代码里直接引用了那个模组的类。**一旦候选列表
 * 在类加载时求值，类加载就会连带解析那些类**，于是没装该模组的实例会在
 * 构建快照时直接 {@code NoClassDefFoundError} —— 而且是在构建快照时，
 * 也就是整个桥接都不可用。
 *
 * <p>所以：候选列表在**首次使用时**才求值，并且先问 {@code modLoaded}；
 * {@code new} 留在 lambda / 方法引用里（JVM 按指令惰性解析类），
 * 外面再包一层 {@code catch (Throwable)} —— 万一将来某个模组的类加载出了别的问题
 * （版本不匹配、依赖缺失），后果应该是「少一个适配器」，而不是「整个快照建不出来」。
 * 这个项目对「一个坏配方带崩整条链路」是有教训的。
 * 实测：原版实例启动后日志里 {@code NoClassDefFoundError} 出现 0 次。
 *
 * <h2>线程</h2>
 *
 * 抽取只在游戏主线程做，所以懒建与失败计数都不需要同步。
 *
 * @param <T> 这个版本/平台的配方对象类型（如 {@code Recipe<?>}）
 */
public final class AdapterRegistry<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdapterRegistry.class);

    /** 同一个失败最多打几条日志，避免刷屏（计数仍然照常累加）。 */
    private static final int MAX_LOGGED_FAILURES = 3;

    /**
     * 一个候选适配器。
     *
     * @param modId 依赖的模组 id；{@code null} 表示无条件注册（原版自带的类型）
     * @param displayName 只用于日志的名字
     * @param factory 传 Supplier 而不是实例：把 {@code new XxxAdapter()} 留在 lambda 里，
     *                类的解析就推迟到 lambda 被调用时 —— 这正是「没装就不解析」的实现方式
     * @param <T> 配方对象类型
     */
    public record Candidate<T>(String modId, String displayName, Supplier<? extends RecipeTypeAdapter<T>> factory) {

        /** 无模组依赖、总是注册的适配器。 */
        public static <T> Candidate<T> always(String displayName, Supplier<? extends RecipeTypeAdapter<T>> factory) {
            return new Candidate<>(null, displayName, factory);
        }
    }

    private final List<Candidate<T>> candidates;
    private final Predicate<String> modLoaded;

    /** 惰性构建的适配器列表，见类注释。 */
    private List<RecipeTypeAdapter<T>> adapters;

    /** 适配器调用失败次数，见 {@link #guarded}。 */
    private int adapterFailures;

    public AdapterRegistry(List<Candidate<T>> candidates, Predicate<String> modLoaded) {
        this.candidates = List.copyOf(candidates);
        this.modLoaded = modLoaded;
    }

    /** 所有已启用的适配器，按注册顺序（先注册的先匹配）。首次调用时构建。 */
    public List<RecipeTypeAdapter<T>> all() {
        if (adapters == null) {
            List<RecipeTypeAdapter<T>> list = new ArrayList<>();
            for (Candidate<T> candidate : candidates) {
                if (candidate.modId() != null && !modLoaded.test(candidate.modId())) continue;
                try {
                    list.add(candidate.factory().get());
                    LOGGER.debug("CraftGraph enabled the {} recipe adapter", candidate.displayName());
                } catch (Throwable t) {
                    // 用 Throwable 而不是 Exception：类加载失败是 Error。
                    // 后果限定为「少一个适配器」，而不是整个快照建不出来。
                    LOGGER.warn("CraftGraph failed to load the {} recipe adapter; "
                            + "falling back to generic reading (its recipes may report incomplete outputs): {}",
                            candidate.displayName(), t.toString());
                }
            }
            adapters = List.copyOf(list);
        }
        return adapters;
    }

    /**
     * 调用适配器，并在它抛异常时退回 {@code fallback} 而不是让异常逃出去。
     *
     * <h2>为什么必须有这一层</h2>
     *
     * 类注释承诺了「一个模组出问题，后果是**少一个适配器**，而不是整个快照建不出来」
     * —— 但那只挡住了**类加载**（惰性注册 + lambda）。方法调用这一层需要单独保护：
     * 适配器在运行时抛出的任何异常（{@code NoSuchMethodError}、模组自己代码里的 NPE、
     * 某个畸形配方触发的 {@code ClassCastException}）都会一路冒到快照重建的兜底
     * catch，结果是**整个桥接不可用**。
     *
     * <p>现实里这个场景并不假：Create 在 1.20.1 上同时有 0.5.x 与 6.0.x 两条线，
     * 我们只对着其中一条编译过。版本对不上时，能读到多少算多少、其余退回通用读取，
     * 才是对的失败方式。
     *
     * <h2>为什么不是「禁用这个适配器」</h2>
     *
     * 一个坏配方不该让这个类型的所有配方都失去适配器（那可能是一万条）。
     * 所以这里只跳过这一条，并把次数记下来 —— 由 {@link #failures()} 报进日志，
     * 而覆盖度（{@code FieldCoverage}）会同时下降，所以它不是静默降级。
     */
    private <R> R guarded(RecipeTypeAdapter<T> adapter, String what, Supplier<R> call, R fallback) {
        try {
            return call.get();
        } catch (Throwable t) {
            adapterFailures++;
            if (adapterFailures <= MAX_LOGGED_FAILURES) {
                LOGGER.warn("CraftGraph adapter call failed ({}); reading this recipe the generic way instead: {}",
                        what, t.toString());
            }
            return fallback;
        }
    }

    /** 适配器调用失败的次数。由变体的快照重建打进日志 —— 降级必须有声音。 */
    public int failures() {
        return adapterFailures;
    }

    /**
     * 这条配方是不是「本来就不产出」（见 {@link RecipeTypeAdapter#declaresNoOutput}）。
     *
     * <p>单独包一个方法是为了让它也走 {@link #guarded}：异常时**返回 false**
     * （而不是 true）—— 宁可把这条判成「读不懂」也不要凭空断言「它不产出」。
     */
    public boolean declaresNoOutput(T recipe) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return false;
        return guarded(adapter, "declaresNoOutput", () -> adapter.declaresNoOutput(recipe), Boolean.FALSE);
    }

    /** 找能处理这条配方的适配器。没有就返回 {@code null}。 */
    public RecipeTypeAdapter<T> forRecipe(T recipe) {
        for (RecipeTypeAdapter<T> adapter : all()) {
            if (adapter.handles(recipe)) return adapter;
        }
        return null;
    }

    /**
     * 读耗时。
     *
     * @return 游戏刻；没有适配器能处理、或适配器读不到时返回 {@code null}
     */
    public Integer duration(T recipe) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return null;
        return guarded(adapter, "duration", () -> adapter.duration(recipe), null);
    }

    /**
     * 读输入槽位（物品）。
     *
     * @param generic 通用接口（{@code getIngredients()}）读出来的槽位
     * @return 适配器给的槽位；没有适配器能处理、或适配器声明「不归我管」时返回 {@code generic}
     */
    public List<RawSlot> ingredients(T recipe, List<RawSlot> generic) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<RawSlot> override = guarded(adapter, "ingredients", () -> adapter.ingredients(recipe), null);
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
    public List<Models.ItemStack> results(T recipe, List<Models.ItemStack> generic) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return generic;
        List<Models.ItemStack> override = guarded(adapter, "results", () -> adapter.results(recipe), null);
        return override == null ? generic : override;
    }

    /**
     * 读概率产出。
     *
     * <p>这个方法没有「通用回退」——原版和 NeoForge/Forge 都没有任何接口能给出带概率的产出，
     * 它只存在于各模组自己的字段里。所以返回空列表就是「没有」，不存在歧义。
     */
    public List<Models.ChanceOutput> chanceResults(T recipe) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<Models.ChanceOutput> out = guarded(adapter, "chanceResults", () -> adapter.chanceResults(recipe), null);
        return out == null ? List.of() : out;
    }

    /** 读流体输出。同样没有通用回退。 */
    public List<Models.FluidStack> fluidResults(T recipe) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<Models.FluidStack> out = guarded(adapter, "fluidResults", () -> adapter.fluidResults(recipe), null);
        return out == null ? List.of() : out;
    }

    /** 读流体输入。同样没有通用回退。 */
    public List<RawSlot> fluidIngredients(T recipe) {
        RecipeTypeAdapter<T> adapter = forRecipe(recipe);
        if (adapter == null) return List.of();
        List<RawSlot> out = guarded(adapter, "fluidIngredients", () -> adapter.fluidIngredients(recipe), null);
        return out == null ? List.of() : out;
    }
}
