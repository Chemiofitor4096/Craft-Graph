package dev.craftgraph.extract.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftgraph.api.Models;
import dev.craftgraph.extract.RawSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 {@link RecipeProfile} 解析一份「配方 JSON」。
 *
 * <h2>它不认识的模组</h2>
 *
 * 这里只认 profile 里声明过的字段和 requirement 类型。任何一处没见过
 * （未知类型、缺字段、标签展开为空）都把 {@code complete} 置 false，
 * 上层据此把整条配方标 opaque —— 这是这个项目唯一能接受的失败方式。
 *
 * <p>纯逻辑、只依赖 Gson，所以能脱离 Minecraft 完整测试。
 */
public final class ProfileRecipeParser {

    /** 标签展开器。{@code kind} 是 {@code "item"} 或 {@code "fluid"}。 */
    @FunctionalInterface
    public interface TagLookup {
        List<String> members(String kind, String tagId);
    }

    /** 适配器用来标记「有 requirement 没能编码成 JSON」的字段名。 */
    public static final String UNENCODED_FIELD = "unencoded";

    private ProfileRecipeParser() {
    }

    /**
     * 解析。
     *
     * @throws RuntimeException JSON 结构不对（调用方应当据此退回「读不懂」）
     */
    public static RecipePlan parse(String json, RecipeProfile profile, TagLookup tags) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Builder b = new Builder();
        b.machine = optString(root, profile.machineField());
        b.duration = optInteger(root, profile.durationField());

        // 适配器编码失败时会计数；它表示「连形状都不知道」，不是「没有这条」
        if (optInt(root, UNENCODED_FIELD, 0) > 0) {
            b.complete = false;
        }

        JsonArray requirements = root.has(profile.requirementsField())
                && root.get(profile.requirementsField()).isJsonArray()
                ? root.getAsJsonArray(profile.requirementsField())
                : new JsonArray();

        for (JsonElement element : requirements) {
            if (!element.isJsonObject()) {
                b.complete = false;
                continue;
            }
            JsonObject requirement = element.getAsJsonObject();
            String type = optString(requirement, profile.typeField());
            if (type == null) {
                b.complete = false;
                continue;
            }
            boolean output = "output".equalsIgnoreCase(optString(requirement, profile.modeField()));
            switch (profile.roleOf(type)) {
                case ITEM -> readItem(requirement, output, profile, tags, b);
                case FLUID -> readFluid(requirement, output, profile, tags, b);
                case TOOL, CONDITION -> {
                    // 读得到，但不进原料表：工具磨损 / 世界条件
                }
                case INCOMPLETE -> b.complete = false;
            }
        }
        return b.build();
    }

    // ---------------------------------------------------------------- requirement

    private static void readItem(JsonObject requirement, boolean output, RecipeProfile profile,
                                 TagLookup tags, Builder b) {
        JsonElement raw = requirement.get(profile.itemField());
        int amount = optInt(requirement, profile.countField(), 1);
        double chance = optDouble(requirement, profile.chanceField(), 1.0D);
        List<String> ids = resolve(raw, "item", tags);
        if (ids.isEmpty() || amount <= 0) {
            b.complete = false;
            return;
        }

        if (!output) {
            // 原版语义：3 个铁锭 = 3 个槽位
            for (int i = 0; i < amount; i++) {
                b.itemInputs.add(RawSlot.items(ids));
            }
            return;
        }

        if (isTag(raw) || ids.size() > 1) {
            // 产出必须是单一具体物品；标签/「多选一」都无法如实表达
            b.complete = false;
            return;
        }
        Models.ItemStack stack = new Models.ItemStack(ids.get(0), amount, null);
        if (chance >= 1.0D) {
            b.results.add(stack);
        } else if (chance > 0.0D) {
            b.chanceResults.add(new Models.ChanceOutput(stack, chance));
        }
        // chance == 0：声明了但永不产出，不计入产出
    }

    private static void readFluid(JsonObject requirement, boolean output, RecipeProfile profile,
                                  TagLookup tags, Builder b) {
        JsonElement raw = requirement.get(profile.fluidField());
        long amount = optLong(requirement, profile.countField(), 1L);
        double chance = optDouble(requirement, profile.chanceField(), 1.0D);
        List<String> ids = resolve(raw, "fluid", tags);
        if (ids.isEmpty() || amount <= 0 || amount > Integer.MAX_VALUE) {
            b.complete = false;
            return;
        }

        if (!output) {
            b.fluidInputs.add(RawSlot.fluids((int) amount, ids));
            return;
        }

        if (isTag(raw) || ids.size() > 1) {
            b.complete = false;
            return;
        }
        if (chance < 1.0D) {
            // 协议里没有「概率流体产出」这一维，不能把它当必然产出报出去
            b.complete = false;
            return;
        }
        b.fluidResults.add(new Models.FluidStack(ids.get(0), (int) amount));
    }

    /**
     * 展开 id 字段：单字符串、{@code #tag}，或字符串数组都接受。
     *
     * <p>数组是给 1.21.x 的形态用的：那边的 {@code SizedIngredient} 只给得出「一堆具体物品」，
     * 标签身份由 TagIndex 反查恢复（和原版 {@code Ingredient} 走同一条路）。
     */
    private static List<String> resolve(JsonElement element, String kind, TagLookup tags) {
        if (element == null || element.isJsonNull()) return List.of();
        if (element.isJsonPrimitive()) return resolveOne(element.getAsString(), kind, tags);
        if (element.isJsonArray()) {
            List<String> out = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) out.addAll(resolveOne(item.getAsString(), kind, tags));
            }
            return out;
        }
        return List.of();
    }

    private static List<String> resolveOne(String raw, String kind, TagLookup tags) {
        if (!raw.startsWith("#")) return List.of(raw);
        String tagId = raw.substring(1);
        if (tagId.isEmpty()) return List.of();
        return tags.members(kind, tagId);
    }

    private static boolean isTag(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsString().startsWith("#");
    }

    // ---------------------------------------------------------------- JSON 小工具

    private static String optString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    private static Integer optInteger(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsInt();
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        Integer value = optInteger(object, key);
        return value == null ? fallback : value;
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        return element.getAsLong();
    }

    private static double optDouble(JsonObject object, String key, double fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        return element.getAsDouble();
    }

    /** 可变构建器，只在 {@link #parse} 内部用。 */
    private static final class Builder {
        private String machine;
        private Integer duration;
        private final List<RawSlot> itemInputs = new ArrayList<>();
        private final List<RawSlot> fluidInputs = new ArrayList<>();
        private final List<Models.ItemStack> results = new ArrayList<>();
        private final List<Models.ChanceOutput> chanceResults = new ArrayList<>();
        private final List<Models.FluidStack> fluidResults = new ArrayList<>();
        private boolean complete = true;

        private RecipePlan build() {
            return new RecipePlan(machine, duration, itemInputs, fluidInputs,
                    results, chanceResults, fluidResults, complete);
        }
    }
}
