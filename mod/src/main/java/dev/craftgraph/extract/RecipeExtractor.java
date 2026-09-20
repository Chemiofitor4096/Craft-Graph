package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.Humanize;
import dev.craftgraph.normalize.Readability;
import dev.craftgraph.normalize.TagIndex;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Minecraft 的配方翻译成协议 DTO。
 *
 * <h2>这一层只做搬运，判断都在别处</h2>
 *
 * 它刻意做得非常薄：**所有判断逻辑都委托给已经测过的纯 Java 类**
 * （{@link ExtractionContext} 负责槽位归一化与标签还原、
 * {@link RecipeTypeAdapter} 的实现负责各配方类型的字段、
 * {@link MachineTable} 负责机器推断），这里只负责
 * 「把 MC 的对象按 id 取出来」和「按协议拼起来」。
 *
 * 这么分的理由：MC API 的细节（方法名、返回类型）编译器能验证，
 * 但**语义**不能 —— 比如「Ingredient.getItems() 返回的到底是不是标签成员」。
 * 把语义判断集中到能测试的地方，这里剩下的就只是搬运。
 *
 * <h2>类型特有的字段交给 {@code extract} 包里的适配器</h2>
 *
 * 通用接口只给得出输入和产出。{@code duration} / {@code machine} 藏在各配方类自己的字段里，
 * 需要知道「这是哪种配方」才读得到 —— 见 {@link RecipeAdapters} 与 {@link MachineTable}。
 *
 * <p>这两个字段曾经恒为 null（压根没人去读），而四个测试层全绿，
 * 因为夹具里手写了 duration。症状是产线计算里每个环节都报「手工」。
 * 所以除了补上读取逻辑，还加了 {@link FieldCoverage}：
 * **任何下游要用的字段，都必须有一处的覆盖度是可观测的。**
 *
 * <h2>仍然需要进游戏验证的部分</h2>
 *
 * 下面这些假设编译器验证不了，第一次进游戏时要重点看：
 *
 * <ul>
 *   <li>{@code Recipe#getResultItem} 对模组机器配方是否返回有意义的值
 *       （很多模组配方返回 {@code ItemStack.EMPTY}，那不代表它不产出东西）</li>
 *   <li>{@code Ingredient#getItems()} 对「任意物品」这类槽位会不会返回上千项
 *       （{@code IngredientNormalizer.MAX_UNEXPLAINED_OPTIONS} 会把它判成读不懂）</li>
 *   <li>配方数量是否符合预期（跟 JEI/EMI 显示的数量对比）</li>
 *   <li>{@code instanceof AbstractCookingRecipe} 是否真的匹配那 4 种烹饪配方 ——
 *       这是 {@link CookingAdapter} 唯一的判据，而它需要真实的 MC 类才能验证，
 *       所以由 {@code npm run live} 在真游戏上断言。见 {@link FieldCoverage#brokenTypes()}</li>
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
    private final HolderLookup.Provider registries;

    /** getResultItem 抛异常的配方数。可见性很重要，见 Registries 字段的说明。 */
    private int resultItemFailures;

    /** getToastSymbol 抛异常的配方数。正常应为 0。 */
    private int toastSymbolFailures;

    /** 字段覆盖度。由 ClientBridge 打进日志 —— 「字段悄悄全是 null」必须留下痕迹。 */
    private final FieldCoverage coverage = new FieldCoverage();

    private RecipeExtractor(ExtractionContext context, HolderLookup.Provider registries) {
        this.context = context;
        this.registries = registries;
    }

    /**
     * 从注册表构建标签索引。
     *
     * 必须用主线程调用（注册表访问）。只做一次，之后抽取可以复用。
     */
    public static RecipeExtractor create(HolderLookup.Provider registries) {
        return new RecipeExtractor(
                new ExtractionContext(
                        buildTagIndex(registries, Registries.ITEM),
                        buildTagIndex(registries, Registries.FLUID)),
                registries);
    }

    /**
     * 从注册表构建标签索引。
     *
     * 必须用主线程调用（访问注册表）。只做一次，之后抽取可以复用。
     */
    private static <T> TagIndex buildTagIndex(
            HolderLookup.Provider registries,
            net.minecraft.resources.ResourceKey<? extends Registry<T>> registryKey) {

        // lookupOrThrow 返回的是 RegistryLookup 而不是 Registry —— 前者只读，
        // 正好够用（我们只需要枚举标签和成员）。
        HolderLookup.RegistryLookup<T> lookup = registries.lookupOrThrow(registryKey);

        Map<String, List<String>> tags = new LinkedHashMap<>();
        lookup.listTags().forEach(namedSet -> {
            List<String> members = new ArrayList<>();
            for (Holder<T> holder : namedSet) {
                holder.unwrapKey().ifPresent(k -> members.add(k.location().toString()));
            }
            if (!members.isEmpty()) {
                tags.put(namedSet.key().location().toString(), members);
            }
        });
        return TagIndex.of(tags);
    }

    /** 提取全部配方。应在主线程调用（会读 RecipeManager）。 */
    public List<Models.Recipe> extractAll(RecipeManager manager) {
        Collection<RecipeHolder<?>> holders = manager.getRecipes();
        List<Models.Recipe> out = new ArrayList<>(holders.size());
        for (RecipeHolder<?> holder : holders) {
            Models.Recipe recipe = extract(holder);
            if (recipe != null) out.add(recipe);
        }
        return out;
    }

    /** 提取单条。返回 null 表示这条配方连 id 都取不到，只能跳过。 */
    public Models.Recipe extract(RecipeHolder<?> holder) {
        ResourceLocation id = holder.id();
        if (id == null) return null;

        Recipe<?> recipe = holder.value();
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
        // 数量单位是 mB，复用同一套标签还原逻辑（流体也有标签，比如 #c:lava）。
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
        // 所以无输入一定是「读不到输入」。只看产出的话，盔甲纹饰锻造会变成
        // 「有产出、无输入、opaque=false」，看起来像「纹饰不需要材料」，那是静默的错误答案。
        //
        // 锻造的两个子类现在走的路径不同（见 SmithingAdapter）：
        //   升级配方 → 输入读到了、产出是真的 → 可读
        //   纹饰配方 → 输入读到了、产出被适配器判为读不到 → 仍然 opaque，
        //              reason 会明说是「读不到产出」而不是「输入和产出都读不到」
        int outputCount = outputs.size() + chanceOutputs.size() + fluidOutputs.size();

        // 「产出为空」有两种原因，必须分开：适配器**读过该类型自己的产出字段**并确认空 =
        // 这条配方本来就不产出（燃料定义那种）；否则就是「读不到产出」。
        // 混为一谈会让 AI 把一条读不懂的配方说成「这配方不产出」。
        boolean declaredNoOutput = RecipeAdapters.declaresNoOutput(recipe);

        Readability.Outcome verdict = Readability.outcome(inputCount, outputCount, opaque, declaredNoOutput);
        opaque = verdict == Readability.Outcome.OPAQUE;
        boolean producesNothing = verdict == Readability.Outcome.PRODUCES_NOTHING;

        // 类型特有的字段：耗时、机器。之前这两个恒为 null —— 通用接口给不出它们，
        // 而没有人去读各配方类自己的字段。症状是产线计算里每个环节都报「手工」。
        Integer duration = RecipeAdapters.duration(recipe);
        String machine = MachineTable.machineFor(typeId, toastItemId(recipe));

        // 记一份覆盖度，由 ClientBridge 打进日志。没有适配器匹配的字段恒为 null，
        // 而 null 会被产线计算当作「未知」——所以「哪些类型一条都没读到」必须留下痕迹，
        // 不能只靠单元测试（它们跑在夹具上，发现不了真实数据的形状）。
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
     * 保留 catch：个别模组实现这里会抛异常，不能让一条坏配方带崩整个快照构建
     * （那会让整个桥接不可用）。失败会被计数并打日志，不静默。
     */
    private String toastItemId(Recipe<?> recipe) {
        try {
            ItemStack toast = recipe.getToastSymbol();
            if (toast == null || toast.isEmpty()) return null;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(toast.getItem());
            return itemId == null ? null : itemId.toString();
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
     * <p>仍然保留 catch：个别模组配方会在 getResultItem 上抛异常，
     * 不能让一条坏配方带崩整个快照构建（那会让整个桥接不可用）。
     * 但**失败会被计数**并暴露出去，不再静默 —— 静默吞异常正是
     * 上面那个 null 注册表的 bug 藏了这么久的原因。
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
        List<RawSlot> out = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            RawSlot slot = ItemIds.itemSlot(ingredient);
            if (!slot.isEmpty()) out.add(slot);
        }
        return out;
    }
}
