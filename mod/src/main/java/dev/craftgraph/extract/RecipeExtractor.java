package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.Humanize;
import dev.craftgraph.normalize.IngredientNormalizer;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Minecraft 的配方翻译成协议 DTO。
 *
 * <h2>这是整个 Mod 里唯一接触 Minecraft API 的地方</h2>
 *
 * 它刻意做得非常薄：**所有判断逻辑都委托给已经测过的纯 Java 类**
 * （{@link TagIndex}、{@link IngredientNormalizer}）。
 * 这里只负责「把 MC 的对象取出来、变成字符串和数字」。
 *
 * 这么分的理由：MC API 的细节（方法名、返回类型）编译器能验证，
 * 但**语义**不能 —— 比如「Ingredient.getItems() 返回的到底是不是标签成员」。
 * 把语义判断集中到可测试的地方，这里剩下的就只是搬运。
 *
 * <h2>仍然需要进游戏验证的部分</h2>
 *
 * 下面这些假设编译器验证不了，第一次进游戏时要重点看：
 *
 * <ul>
 *   <li>{@code Recipe#getResultItem} 对模组机器配方是否返回有意义的值
 *       （很多模组配方返回 {@code ItemStack.EMPTY}，那不代表它不产出东西）</li>
 *   <li>{@code Ingredient#getItems()} 对「任意物品」这类槽位会不会返回上千项
 *       （{@link IngredientNormalizer#MAX_UNEXPLAINED_OPTIONS} 会把它判成读不懂）</li>
 *   <li>配方数量是否符合预期（跟 JEI/EMI 显示的数量对比）</li>
 * </ul>
 *
 * 这些失败都会体现在 {@code opaque} 比例上，不会静默出错 —— 这是刻意设计的。
 */
public final class RecipeExtractor {

    /** 配方类型 id 缓存：RecipeType → id 的映射查一次不贵，但每条配方都查就没必要。 */
    private final Map<Object, String> typeIdCache = new LinkedHashMap<>();

    private final TagIndex itemTags;
    private final TagIndex fluidTags;

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

    private RecipeExtractor(TagIndex itemTags, TagIndex fluidTags, HolderLookup.Provider registries) {
        this.itemTags = itemTags;
        this.fluidTags = fluidTags;
        this.registries = registries;
    }

    /**
     * 从注册表构建标签索引。
     *
     * 必须用主线程调用（注册表访问）。只做一次，之后抽取可以复用。
     */
    public static RecipeExtractor create(HolderLookup.Provider registries) {
        return new RecipeExtractor(
                buildTagIndex(registries, Registries.ITEM),
                buildTagIndex(registries, Registries.FLUID),
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

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;

            // getItems() 给出这个槽位接受的所有物品 —— 但**不告诉你它原本是不是标签**。
            // 反查标签表来还原，见 TagIndex 的注释。
            // 注意：1.21.1 里 getItems() 返回的是 ItemStack[]（数组，不是 Stream）。
            Set<String> itemIds = new LinkedHashSet<>();
            for (ItemStack stack : ingredient.getItems()) {
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId != null) itemIds.add(itemId.toString());
            }

            Models.Ingredient normalized = IngredientNormalizer.normalize("item", 1, new ArrayList<>(itemIds), itemTags);
            if (normalized == null) {
                // 表示不了这个槽位。整条配方标记为读不懂 —— 这比塞一份
                // 「看起来精确、实际荒谬」的原料表安全得多。
                opaque = true;
                break;
            }
            inputs.add(normalized);
        }

        // 产物。注意很多模组配方这条返回 EMPTY —— 那种情况我们会得到一个空 outputs，
        // 必须靠 opaque 标记让下游知道「不是没有产出，是我们读不到」。
        List<Models.ItemStack> outputs = new ArrayList<>();
        ItemStack result = safeResultItem(recipe);
        if (result != null && !result.isEmpty()) {
            outputs.add(toItemStack(result));
        }

        // 用统一的判定规则。注意它也检查「没有输入」—— 真实配方不可能不消耗东西，
        // 所以无输入一定是「读不到输入」。只看产出的话，盔甲纹饰锻造会变成
        // 「有产出、无输入、opaque=false」，看起来像「纹饰不需要材料」，那是静默的错误答案。
        opaque = Readability.isOpaque(inputs, outputs, opaque);

        return new Models.Recipe(
                id.toString(),
                typeId,
                typeLabel,
                List.copyOf(inputs),
                List.copyOf(outputs),
                List.of(),   // fluidOutputs：需要流体槽位的配方类型适配器，MVP 先留空
                List.of(),   // chanceOutputs：概率产出需要按配方类型适配，MVP 先留空
                null,        // machine：原版 API 拿不到，接 EMI 后补
                null,        // duration：同上
                null,        // energy：同上
                "vanilla",
                opaque);
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

    private static Models.ItemStack toItemStack(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new Models.ItemStack(id != null ? id.toString() : "unknown:unknown", stack.getCount(), null);
    }
}
