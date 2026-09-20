package dev.craftgraph.snapshot;

import dev.craftgraph.api.Models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可变的配方快照，含全部查询索引。
 *
 * <h2>为什么是快照而不是每次请求现查</h2>
 *
 * 朴素做法是每个 HTTP 请求都去问一次游戏：{@code /recipes?output=X} 就遍历 RecipeManager 找一遍。
 * 问题是遍历上万条配方只能在游戏主线程做，而每次请求都要走一趟主线程 ——
 * 玩家的体验就是 AI 每问一句游戏卡一下。
 *
 * 快照方案：配方加载完成（或重载）时把数据翻译成 DTO、建好索引、一次性发布。
 * 之后所有 HTTP 请求只读这个不可变对象，**完全不接触游戏对象**，
 * 线程安全问题从根上消失，响应速度也不随整合包大小变化。
 *
 * 代价是构建快照那一下要占用主线程，所以构建耗时会被记下来（见 {@link #buildMillis()}）。
 *
 * <h2>这个类不依赖 Minecraft</h2>
 *
 * 它只操作 DTO，所以索引和查询逻辑可以用普通 JUnit 完整测试，不需要启动游戏 ——
 * 而这里恰恰是最容易出错的地方（见 {@link #recipesConsuming}）。
 */
public final class RecipeSnapshot {

    /** 物品/流体的引用。用 kind 区分是刻意的：{@code minecraft:water} 既可能是物品也可能是流体。 */
    public record Ref(String kind, String id) {
        public static final String ITEM = "item";
        public static final String FLUID = "fluid";
    }

    public record TypeCount(String type, String label, int count) {
    }

    private final int dataVersion;
    private final List<Models.Recipe> recipes;
    private final Map<String, Models.Recipe> byId;
    private final Map<Ref, List<Models.Recipe>> byOutput;
    private final Map<Ref, List<Models.Recipe>> byInput;
    private final Map<String, List<Models.Recipe>> byInputTag;
    private final Map<String, List<Models.Recipe>> byType;
    private final Map<String, List<String>> tags;
    private final Map<String, List<String>> tagsOf;
    private final Map<String, List<String>> registries;
    private final Map<String, String> displayNames;
    private final long builtAtMillis;
    private final long buildMillis;

    private RecipeSnapshot(Builder b) {
        this.dataVersion = b.dataVersion;
        this.recipes = List.copyOf(b.recipes);
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(b.byId));
        this.byOutput = freezeRecipes(b.byOutput);
        this.byInput = freezeRecipes(b.byInput);
        this.byInputTag = freezeRecipes(b.byInputTag);
        this.byType = freezeRecipes(b.byType);
        this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(b.tags));
        this.tagsOf = freezeStrings(b.tagsOf);
        this.registries = Collections.unmodifiableMap(new LinkedHashMap<>(b.registries));
        this.displayNames = Collections.unmodifiableMap(new LinkedHashMap<>(b.displayNames));
        this.builtAtMillis = System.currentTimeMillis();
        this.buildMillis = b.buildMillis;
    }

    private static <K> Map<K, List<Models.Recipe>> freezeRecipes(Map<K, LinkedHashSet<Models.Recipe>> src) {
        Map<K, List<Models.Recipe>> out = new LinkedHashMap<>(src.size() * 2);
        for (Map.Entry<K, LinkedHashSet<Models.Recipe>> e : src.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static <K> Map<K, List<String>> freezeStrings(Map<K, LinkedHashSet<String>> src) {
        Map<K, List<String>> out = new LinkedHashMap<>(src.size() * 2);
        for (Map.Entry<K, LinkedHashSet<String>> e : src.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    // ---------------------------------------------------------------- 构建

    public static Builder builder(int dataVersion) {
        return new Builder(dataVersion);
    }

    /**
     * 建索引。
     *
     * 整个构建过程是纯计算，不接触游戏对象，可以在任意线程上跑 ——
     * 通常的做法是在主线程把数据翻译成 DTO，然后把建索引丢给后台线程。
     */
    public static final class Builder {
        private final int dataVersion;
        private final List<Models.Recipe> recipes = new ArrayList<>();
        private final Map<String, Models.Recipe> byId = new LinkedHashMap<>();
        private final Map<Ref, LinkedHashSet<Models.Recipe>> byOutput = new LinkedHashMap<>();
        private final Map<Ref, LinkedHashSet<Models.Recipe>> byInput = new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<Models.Recipe>> byInputTag = new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<Models.Recipe>> byType = new LinkedHashMap<>();
        private final Map<String, List<String>> tags = new LinkedHashMap<>();
        private final Map<String, LinkedHashSet<String>> tagsOf = new LinkedHashMap<>();
        private final Map<String, List<String>> registries = new LinkedHashMap<>();
        private final Map<String, String> displayNames = new LinkedHashMap<>();
        private long buildMillis;

        private Builder(int dataVersion) {
            this.dataVersion = dataVersion;
        }

        /**
         * 标签表。**要在 {@link #addRecipe} 之前设置** ——
         * 建倒排索引时要用它把标签展开成具体物品，顺序反了索引就是残缺的。
         */
        public Builder tags(Map<String, List<String>> tags) {
            this.tags.putAll(tags);
            return this;
        }

        /** 注册表。kind 取 {@code items} / {@code blocks} / {@code fluids} 等。 */
        public Builder registry(String kind, List<String> ids) {
            this.registries.put(kind, List.copyOf(ids));
            return this;
        }

        public Builder displayNames(Map<String, String> names) {
            this.displayNames.putAll(names);
            return this;
        }

        /** 批量设置注册表。 */
        public Builder addAllRegistries(Map<String, List<String>> registries) {
            for (Map.Entry<String, List<String>> e : registries.entrySet()) {
                registry(e.getKey(), e.getValue());
            }
            return this;
        }

        /**
         * 批量加配方。
         *
         * <p>调用前 {@link #tags} 必须已经设好 —— 建倒排索引时要用它展开标签，
         * 顺序反了索引就是残缺的（症状是「按输入查」结果偏少而不是报错）。
         */
        public Builder addAllRecipes(List<Models.Recipe> recipes) {
            for (Models.Recipe r : recipes) {
                addRecipe(r);
            }
            return this;
        }

        public Builder buildMillis(long millis) {
            this.buildMillis = millis;
            return this;
        }

        public Builder addRecipe(Models.Recipe recipe) {
            recipes.add(recipe);
            byId.put(recipe.id(), recipe);
            refs(byType, recipe.type()).add(recipe);

            // ---- 产出索引 ----
            // opaque 配方照样建索引：它确实存在，只是读不懂。查询时要能查到，
            // 由上层决定怎么告诉用户 —— 不能返回空 inputs 假装它不需要原料。
            for (Models.ItemStack out : recipe.outputs()) {
                refs(byOutput, new Ref(Ref.ITEM, out.item())).add(recipe);
            }
            for (Models.FluidStack out : orEmpty(recipe.fluidOutputs())) {
                refs(byOutput, new Ref(Ref.FLUID, out.fluid())).add(recipe);
            }
            for (Models.ChanceOutput out : orEmpty(recipe.chanceOutputs())) {
                refs(byOutput, new Ref(Ref.ITEM, out.stack().item())).add(recipe);
            }

            // ---- 输入索引（倒排）----
            for (Models.Ingredient ing : recipe.inputs()) {
                String ingKind = Ref.FLUID.equals(ing.kind()) ? Ref.FLUID : Ref.ITEM;
                for (Models.Option opt : ing.options()) {
                    if ("tag".equals(opt.type())) {
                        refs(byInputTag, opt.id()).add(recipe);
                        // 展开成每个成员。漏掉这一步，「哪些配方消耗铁锭」会漏掉所有用
                        // #forge:ingots/iron 的配方，而那是整合包里的绝大多数。
                        // 症状还是「结果偏少」而不是报错，极容易被忽略。
                        for (String member : tags.getOrDefault(opt.id(), List.of())) {
                            refs(byInput, new Ref(ingKind, member)).add(recipe);
                        }
                    } else {
                        String optKind = Ref.FLUID.equals(opt.type()) ? Ref.FLUID : Ref.ITEM;
                        refs(byInput, new Ref(optKind, opt.id())).add(recipe);
                    }
                }
            }

            return this;
        }

        public RecipeSnapshot build() {
            long start = System.nanoTime();
            // 反向标签索引：物品 -> 它属于哪些标签
            for (Map.Entry<String, List<String>> e : tags.entrySet()) {
                for (String member : e.getValue()) {
                    strings(tagsOf, member).add(e.getKey());
                }
            }
            this.buildMillis = (System.nanoTime() - start) / 1_000_000;
            return new RecipeSnapshot(this);
        }

        private static <K> LinkedHashSet<Models.Recipe> refs(Map<K, LinkedHashSet<Models.Recipe>> m, K k) {
            return m.computeIfAbsent(k, x -> new LinkedHashSet<>());
        }

        private static <K> LinkedHashSet<String> strings(Map<K, LinkedHashSet<String>> m, K k) {
            return m.computeIfAbsent(k, x -> new LinkedHashSet<>());
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ---------------------------------------------------------------- 元信息

    public int dataVersion() {
        return dataVersion;
    }

    public int recipeCount() {
        return recipes.size();
    }

    public int tagCount() {
        return tags.size();
    }

    public long buildMillis() {
        return buildMillis;
    }

    public long builtAtMillis() {
        return builtAtMillis;
    }

    // ---------------------------------------------------------------- 查询

    public Models.Recipe recipe(String id) {
        return byId.get(id);
    }

    /** 产出该物品（或流体）的配方。含概率产出。 */
    public List<Models.Recipe> recipesProducing(String kind, String id) {
        return byOutput.getOrDefault(new Ref(kind, id), List.of());
    }

    /**
     * 消耗该物品作为输入的配方。
     *
     * <p><b>已包含「配方用标签引用该物品」的情况</b> —— 建索引时标签就展开过了。
     * 这是整个索引里最要紧的一条。
     */
    public List<Models.Recipe> recipesConsuming(String kind, String id) {
        return byInput.getOrDefault(new Ref(kind, id), List.of());
    }

    /** 直接以该标签作为输入的配方。 */
    public List<Models.Recipe> recipesConsumingTag(String tagId) {
        return byInputTag.getOrDefault(tagId, List.of());
    }

    public List<Models.Recipe> recipesOfType(String type) {
        return byType.getOrDefault(type, List.of());
    }

    /** 展开标签成员。标签不存在返回 null，与「存在但为空」区分开。 */
    public List<String> expandTag(String tagId) {
        return tags.get(tagId);
    }

    /** 该物品属于哪些标签。 */
    public List<String> tagsContaining(String itemId) {
        return tagsOf.getOrDefault(itemId, List.of());
    }

    public List<String> registryIds(String kind) {
        return registries.getOrDefault(kind, List.of());
    }

    public String displayName(String id) {
        return displayNames.getOrDefault(id, id);
    }

    public List<TypeCount> recipeTypes() {
        List<TypeCount> out = new ArrayList<>(byType.size());
        for (Map.Entry<String, List<Models.Recipe>> e : byType.entrySet()) {
            out.add(new TypeCount(e.getKey(), labelOf(e.getValue()), e.getValue().size()));
        }
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out;
    }

    private static String labelOf(List<Models.Recipe> list) {
        for (Models.Recipe r : list) {
            if (r.typeLabel() != null && !r.typeLabel().isBlank()) return r.typeLabel();
        }
        return list.isEmpty() ? "" : list.get(0).type();
    }

    // ---------------------------------------------------------------- 分页查询

    public Models.RecipeQueryPage queryRecipes(String output, String input, String type, int offset, int limit) {
        boolean hasOutput = output != null && !output.isBlank();
        boolean hasInput = input != null && !input.isBlank();
        boolean hasType = type != null && !type.isBlank();

        List<Models.Recipe> base;
        if (hasOutput) {
            base = new ArrayList<>(recipesProducing(Ref.ITEM, output));
        } else if (hasInput) {
            base = new ArrayList<>(recipesConsuming(Ref.ITEM, input));
        } else if (hasType) {
            base = new ArrayList<>(recipesOfType(type));
        } else {
            base = List.copyOf(recipes);
        }

        // 组合条件取交集
        if (hasType && (hasOutput || hasInput)) {
            base.removeIf(r -> !r.type().equals(type));
        }
        if (hasOutput && hasInput) {
            Set<Models.Recipe> consuming = new LinkedHashSet<>(recipesConsuming(Ref.ITEM, input));
            base.removeIf(r -> !consuming.contains(r));
        }

        int total = base.size();
        List<Models.RecipeSummary> page = new ArrayList<>();
        for (int i = Math.max(0, offset); i < Math.min(total, offset + limit); i++) {
            page.add(summary(base.get(i)));
        }
        return new Models.RecipeQueryPage(total, offset, limit, page);
    }

    private static Models.RecipeSummary summary(Models.Recipe r) {
        Models.ItemStack primary = r.outputs().isEmpty() ? null : r.outputs().get(0);
        return new Models.RecipeSummary(r.id(), r.type(), r.typeLabel(), primary, r.opaque());
    }

    public Models.RegistryPage registry(String kind, String query, int offset, int limit) {
        List<String> all = registryIds(kind);
        String q = query == null ? "" : query.toLowerCase();
        List<Models.RegistryEntry> filtered = new ArrayList<>();
        for (String id : all) {
            if (q.isEmpty() || id.toLowerCase().contains(q) || displayName(id).toLowerCase().contains(q)) {
                filtered.add(new Models.RegistryEntry(id, displayName(id)));
            }
        }
        List<Models.RegistryEntry> page = new ArrayList<>();
        for (int i = Math.max(0, offset); i < Math.min(filtered.size(), offset + limit); i++) {
            page.add(filtered.get(i));
        }
        return new Models.RegistryPage(kind, filtered.size(), offset, limit, page);
    }

    /**
     * 列出该 kind 的标签。
     *
     * 标签目前不按 kind 分开存（Minecraft 的物品/方块/流体标签是分开的注册表，
     * 但这里合并了一张表）。所以按「成员是否属于该注册表」来归属 ——
     * 整合包里同一个标签名跨 kind 的情况极少，这个近似够用；
     * 真要精确的话应该在抽取阶段就按 kind 分开存。
     */
    public List<Models.TagListEntry> tagList(String kind) {
        Set<String> registrySet = new LinkedHashSet<>(registryIds(kind));
        List<Models.TagListEntry> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : tags.entrySet()) {
            if (registrySet.isEmpty() || e.getValue().stream().anyMatch(registrySet::contains)) {
                out.add(new Models.TagListEntry(e.getKey(), e.getValue().size()));
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public Models.SnapshotPage snapshotPage(int cursor, int limit) {
        int from = Math.max(0, Math.min(cursor, recipes.size()));
        int to = Math.min(recipes.size(), from + Math.max(1, limit));
        Integer next = to < recipes.size() ? to : null;
        return new Models.SnapshotPage(dataVersion, cursor, next, recipes.size(), List.copyOf(recipes.subList(from, to)));
    }
}
