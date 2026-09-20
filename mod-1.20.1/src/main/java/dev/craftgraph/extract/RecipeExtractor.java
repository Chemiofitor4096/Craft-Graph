package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.Humanize;
import dev.craftgraph.normalize.Readability;
import dev.craftgraph.normalize.TagIndex;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Minecraft 的配方翻译成协议 DTO（**1.20.1 版**）。
 *
 * <h2>这一层只做搬运，判断都在别处</h2>
 *
 * 与 1.21.1 那份同样的分工：判断逻辑委托给已经测过的纯 Java 类
 * （{@link ExtractionContext} 归一化槽位、{@link RecipeTypeAdapter} 的实现读各类型字段、
 * {@link MachineTable} 推断机器），这里只负责「按 id 把 MC 对象取出来」和「按协议拼起来」。
 *
 * <h2>与 1.21.1 那份的差别（都是 1.20.1 的 API 形状）</h2>
 *
 * <ul>
 *   <li><b>没有 {@code RecipeHolder}。</b>1.21.1 用 {@code RecipeHolder} 把「id + 配方」
 *       成对带出来；1.20.1 里 id 是 {@code RecipeManager} 内部 map 的 key，两者是分开的。
 *       这里用 {@code getRecipeIds()} + {@code byKey(id)} 从 **key** 取 id，
 *       而不是信 {@code Recipe#getId()} —— 1.20.1 专门提供 {@code byKey} 这件事
 *       本身就说明「实例上带的 id」不可靠（这条是推断，live 层断言 id 非空且唯一来定案）。</li>
 *   <li><b>注册表访问是 {@code RegistryAccess}</b>（1.21.1 是 {@code HolderLookup.Provider}），
 *       标签枚举相应改成 {@code getTagNames()} + {@code getTag(tagKey)}。</li>
 *   <li>{@code getResultItem(RegistryAccess)} 与 {@code getToastSymbol()} 形状不变。</li>
 * </ul>
 *
 * <h2>类型特有的字段交给适配器</h2>
 *
 * 通用接口只给得出输入和产出。{@code duration} / {@code machine} 藏在各配方类自己的字段里，
 * 需要知道「这是哪种配方」才读得到 —— 见 {@link RecipeAdapters} 与 {@link MachineTable}。
 *
 * <p>这两个字段曾经恒为 null（压根没人去读），而四个测试层全绿，因为夹具里手写了 duration。
 * 症状是产线计算里每个环节都报「手工」。所以除了补上读取逻辑，还加了 {@link FieldCoverage}：
 * **任何下游要用的字段，都必须有一处的覆盖度是可观测的。**
 *
 * <h2>仍然需要进游戏验证的部分</h2>
 *
 * <ul>
 *   <li>{@code getRecipeIds()} + {@code byKey} 取到的 id 是否可靠（见上）。</li>
 *   <li>标签是否真的被绑定过 —— 若 {@code getTagNames()} 给不出东西，
 *       标签会**静默退化成物品列表**（看起来精确、其实丢了「任意一种都行」的语义）。
 *       {@code npm run live} 里有标签数量的断言专门盯这个。</li>
 *   <li>{@code instanceof AbstractCookingRecipe} 是否真的匹配那 4 种烹饪配方。</li>
 *   <li>锻造：AT 用的是 SRG 名，写错不会编译失败，只会让那 27 条又变回 opaque ——
 *       live 层有两条锻造探针。</li>
 * </ul>
 *
 * 这些失败都会体现在 {@code opaque} 比例或字段覆盖度上，不会静默出错 —— 这是刻意设计的。
 */
public final class RecipeExtractor {

    /** 配方类型 id 缓存：RecipeType → id 的映射查一次不贵，但每条配方都查就没必要。 */
    private final Map<Object, String> typeIdCache = new LinkedHashMap<>();

    private final ExtractionContext context;

    /**
     * 注册表访问。**必须传给 {@code getResultItem}，不能传 null。**
     *
     * <p>踩过的坑：原本传 null，绝大多数配方没事（它们不碰注册表），
     * 但需要注册表的配方会直接 NPE —— 比如盔甲纹饰锻造，
     * 它的 {@code getResultItem} 要查 {@code Registries.TRIM_PATTERN}。
     * 更糟的是这个 NPE 被 catch 吞掉，症状表现为「这 18 条配方读不懂」，
     * 看起来像 Minecraft 的限制，实际上是我们的 bug 伪装成了限制。
     */
    private final RegistryAccess registries;

    /** getResultItem 抛异常的配方数。可见性很重要，见 registries 字段的说明。 */
    private int resultItemFailures;

    /** getToastSymbol 抛异常的配方数。正常应为 0。 */
    private int toastSymbolFailures;

    /** 字段覆盖度。由 ClientBridge 打进日志 —— 「字段悄悄全是 null」必须留下痕迹。 */
    private final FieldCoverage coverage = new FieldCoverage();

    private RecipeExtractor(ExtractionContext context, RegistryAccess registries) {
        this.context = context;
        this.registries = registries;
    }

    /**
     * 从注册表构建标签索引。
     *
     * 必须用主线程调用（注册表访问）。只做一次，之后抽取可以复用。
     */
    public static RecipeExtractor create(RegistryAccess registries) {
        return new RecipeExtractor(
                new ExtractionContext(
                        buildTagIndex(registries, Registries.ITEM),
                        buildTagIndex(registries, Registries.FLUID)),
                registries);
    }

    /**
     * 从注册表构建标签索引。
     *
     * <p>1.20.1 的写法：{@code registryOrThrow} 拿注册表，{@code getTagNames()} 枚举标签名，
     * {@code getTag(tagKey)} 拿成员。成员可能是空的（标签没被绑定过）——
     * 那时这个标签会被跳过，而**跳过会让标签还原静默退化**，所以 ClientBridge 会把
     * 标签数量打进日志，live 层也断言它。
     */
    private static <T> TagIndex buildTagIndex(
            RegistryAccess registries,
            ResourceKey<? extends Registry<T>> registryKey) {

        Registry<T> registry = registries.registryOrThrow(registryKey);

        Map<String, List<String>> tags = new LinkedHashMap<>();
        registry.getTagNames().forEach(tagKey -> {
            List<String> members = new ArrayList<>();
            registry.getTag(tagKey).ifPresent(namedSet -> {
                for (Holder<T> holder : namedSet) {
                    holder.unwrapKey().ifPresent(k -> members.add(k.location().toString()));
                }
            });
            if (!members.isEmpty()) {
                tags.put(tagKey.location().toString(), members);
            }
        });
        return TagIndex.of(tags);
    }

    /** 提取全部配方。应在主线程调用（会读 RecipeManager）。 */
    public List<Models.Recipe> extractAll(RecipeManager manager) {
        List<Models.Recipe> out = new ArrayList<>();
        for (ResourceLocation id : manager.getRecipeIds().toList()) {
            Recipe<?> recipe = manager.byKey(id).orElse(null);
            if (recipe == null) continue;
            Models.Recipe extracted = extract(id, recipe);
            if (extracted != null) out.add(extracted);
        }
        return out;
    }

    /**
     * 提取单条。
     *
     * <p>id 与配方在这里是**两个参数**（1.21.1 那边是一个 {@code RecipeHolder}）——
     * 见类注释里关于「id 从 map 的 key 取」的说明。
     *
     * @return null 表示这条配方连 id 都取不到，只能跳过
     */
    public Models.Recipe extract(ResourceLocation id, Recipe<?> recipe) {
        if (id == null || recipe == null) return null;

        String typeId = resolveTypeId(recipe);
        String typeLabel = Humanize.typeLabel(typeId);

        List<Models.Ingredient> inputs = new ArrayList<>();
        boolean opaque = false;

        // 输入槽位走适配器：有些配方类（锻造）通用接口返回空列表，但字段里其实有。
        // 声明「不归我管」的适配器会返回 null，这里就落回通用接口的结果。
        for (RawSlot slot : RecipeAdapters.ingredients(recipe, genericItemSlots(recipe))) {
            if (slot.isEmpty()) continue;

            // 归一化（含标签还原）是 core 里的决定：它知道按 kind 挑哪张标签索引，
            // 也知道表示不了时该返回 null。这里只负责把 null 变成「整条配方 opaque」。
            Models.Ingredient normalized = context.normalize(slot);
            if (normalized == null) {
                // 表示不了这个槽位。整条配方标记为读不懂 —— 这比塞一份
                // 「看起来精确、实际荒谬」的原料表安全得多。
                opaque = true;
                break;
            }
            inputs.add(normalized);
        }

        // 流体输入。漏掉它不会报错，只会让原料表看起来完整却少了东西 ——
        // Create 的 compacting 是「燧石×2 + 砂砾 + 100mB 岩浆」，少了岩浆玩家会照着建错产线。
        for (RawSlot slot : RecipeAdapters.fluidIngredients(recipe)) {
            if (slot.isEmpty()) continue;
            Models.Ingredient normalized = context.normalize(slot);
            if (normalized == null) {
                opaque = true;
                break;
            }
            inputs.add(normalized);
        }
        // 判据用总数而不是物品槽位的数量：一个物品输入都没有、但读到了流体输入，
        // 同样算「读到了输入」（只吃流体的配方是存在的）。
        int inputCount = inputs.size();

        // 产物。注意很多模组配方这条返回 EMPTY —— 那种情况我们会得到一个空 outputs，
        // 必须靠 opaque 标记让下游知道「不是没有产出，是我们读不到」。
        //
        // 先取通用接口的结果再交给适配器：适配器返回 null 表示「用这个」，
        // 返回空列表表示「这个不对，别报出去」。纹饰锻造要的正是后者 ——
        // getResultItem() 在那里返回硬编码的铁胸甲占位符。
        List<Models.ItemStack> genericResults = new ArrayList<>();
        Models.ItemStack result = ItemIds.stack(safeResultItem(recipe));
        if (result != null) {
            genericResults.add(result);
        }

        List<Models.ItemStack> outputs = RecipeAdapters.results(recipe, genericResults);

        // 概率产出与流体产出。三者相加才是「这条配方到底产出什么」——
        // 只数必然产出的话，Create 的洗涤配方（产出全是 chance: 0.25 / 0.05）
        // 会被判成「读不到产出」而整条被产线计算跳过。
        List<Models.ChanceOutput> chanceOutputs = RecipeAdapters.chanceResults(recipe);
        List<Models.FluidStack> fluidOutputs = RecipeAdapters.fluidResults(recipe);

        // 用统一的判定规则。注意它也检查「没有输入」—— 真实配方不可能不消耗东西，
        // 所以无输入一定是「读不到输入」。
        //
        // 锻造的两个子类走的路径不同（见 SmithingAdapter）：
        //   升级配方 → 输入读到了、产出是真的 → 可读
        //   纹饰配方 → 输入读到了、产出被适配器判为读不到 → 仍然 opaque
        int outputCount = outputs.size() + chanceOutputs.size() + fluidOutputs.size();

        // 「产出为空」有两种原因，必须分开：适配器**读过该类型自己的产出字段**并确认空 =
        // 这条配方本来就不产出（燃料定义那种）；否则就是「读不到产出」。
        RecipeTypeAdapter<Recipe<?>> outputAdapter = RecipeAdapters.forRecipe(recipe);
        boolean declaredNoOutput = outputAdapter != null && outputAdapter.declaresNoOutput(recipe);

        Readability.Outcome verdict = Readability.outcome(inputCount, outputCount, opaque, declaredNoOutput);
        opaque = verdict == Readability.Outcome.OPAQUE;
        boolean producesNothing = verdict == Readability.Outcome.PRODUCES_NOTHING;

        // 类型特有的字段：耗时、机器。
        Integer duration = RecipeAdapters.duration(recipe);
        String machine = MachineTable.machineFor(typeId, toastItemId(recipe));

        // 记一份覆盖度，由 ClientBridge 打进日志。没有适配器匹配的字段恒为 null，
        // 而 null 会被产线计算当作「未知」——所以「哪些类型一条都没读到」必须留下痕迹。
        coverage.record(typeId, duration != null, machine != null);

        return new Models.Recipe(
                id.toString(),
                typeId,
                typeLabel,
                List.copyOf(inputs),
                List.copyOf(outputs),
                List.copyOf(fluidOutputs),
                List.copyOf(chanceOutputs),
                machine,
                duration,
                null,        // energy：原版确实没有这个数据，只能靠 EMI 或模组适配器。
                             // **不要伪造** —— 编一个能耗数字比 null 危险得多。
                "vanilla",
                opaque,
                producesNothing);
    }

    /**
     * {@code getToastSymbol()} 返回的物品 id，作为机器推断的输入。
     *
     * <p>取不到（空栈）时返回 null —— 很多模组配方返回 {@code ItemStack.EMPTY}。
     * 保留 catch：个别模组实现这里会抛异常，不能让一条坏配方带崩整个快照构建。
     */
    private String toastItemId(Recipe<?> recipe) {
        try {
            ItemStack toast = recipe.getToastSymbol();
            return ItemIds.itemId(toast);
        } catch (Throwable t) {
            this.toastSymbolFailures++;
            return null;
        }
    }

    // ---------------------------------------------------------------- 小工具

    private String resolveTypeId(Recipe<?> recipe) {
        Object type = recipe.getType();
        String cached = typeIdCache.get(type);
        if (cached != null) return cached;

        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        String id = key != null ? key.toString() : "unknown:unknown";
        typeIdCache.put(type, id);
        return id;
    }

    /**
     * 取产物。
     *
     * <p>必须传真实的注册表 —— 见 {@link #registries} 的说明。
     *
     * <p>仍然保留 catch：个别模组配方会在 getResultItem 上抛异常。但**失败会被计数**
     * 并暴露出去，不再静默 —— 静默吞异常正是上面那个 null 注册表的 bug 藏了这么久的原因。
     */
    private ItemStack safeResultItem(Recipe<?> recipe) {
        try {
            return recipe.getResultItem(this.registries);
        } catch (Throwable t) {
            this.resultItemFailures++;
            return null;
        }
    }

    /** getResultItem 抛异常的配方数。正常应为 0。 */
    public int resultItemFailures() {
        return resultItemFailures;
    }

    /** getToastSymbol 抛异常的配方数。正常应为 0。 */
    public int toastSymbolFailures() {
        return toastSymbolFailures;
    }

    /** 字段覆盖度。每次 {@link #extractAll} 后读一次，打进日志。 */
    public FieldCoverage coverage() {
        return coverage;
    }

    /**
     * 通用接口（{@code getIngredients()}）读出来的输入槽位。
     *
     * <p>空槽位在这里就滤掉：{@code RawSlot} 允许「一个候选都没有」的形态，
     * 但那种槽位对下游没有意义，混进原料表只会让 AI 以为有一个位置要填。
     */
    private static List<RawSlot> genericItemSlots(Recipe<?> recipe) {
        Collection<Ingredient> ingredients = recipe.getIngredients();
        List<RawSlot> out = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            RawSlot slot = ItemIds.itemSlot(ingredient);
            if (!slot.isEmpty()) out.add(slot);
        }
        return out;
    }
}
