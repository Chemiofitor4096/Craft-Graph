package dev.craftgraph.extract.custommachine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftgraph.api.Models;
import dev.craftgraph.extract.RawSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Custom Machinery 的配方（由模组自己的 codec 编码成 JSON 后的形状）读成协议 DTO 需要的材料。
 *
 * <h2>为什么输入是 JSON，而不是 CM 的类</h2>
 *
 * CM 的 {@code ItemRequirement} 字段全是 private，没有 getter；它对外公开的是**编解码器**
 * （{@code IRequirement.CODEC}）。把 requirement 用那个 codec 编码回 JSON，拿到的是
 * **和配方 JSON 完全同构**的数据：{@code type} / {@code mode} / {@code item} / {@code amount} /
 * {@code chance}。这比反射字段稳 —— 字段名是模组自己的数据契约，它换实现也不会换 JSON。
 *
 * <p>所以这个类只认 JSON，不 import 任何 Custom Machinery 的类，也就不依赖那个模组的版本。
 * 「怎么把配方变成 JSON」由 1.20.1 变体的 {@code CustomMachineApi} 负责。
 *
 * <h2>它做的判断</h2>
 *
 * <ul>
 *   <li>{@code durability} 是工具磨损，不进原料表（见 {@link RequirementKinds}）。</li>
 *   <li>读到不认识的 requirement 类型时，{@code complete} 置 false —— 整条配方降级为 opaque，
 *       而不是报告一份「看起来完整、少了东西」的原料表。</li>
 *   <li>标签在这里展开成成员 id；还原成协议里的 {@code tag} 由上层的 TagIndex 反查完成。</li>
 * </ul>
 *
 * <p>纯逻辑，不依赖 Minecraft，可完整单测。
 */
public final class CustomMachineRecipeJson {

    /** 标签展开器。{@code kind} 是 {@code "item"} 或 {@code "fluid"}，返回该标签的成员 id。 */
    @FunctionalInterface
    public interface TagLookup {
        List<String> members(String kind, String tagId);
    }

    /**
     * 解析结果。
     *
     * @param machine  机器 id（如 {@code dut:assembling_machine}）；读不到为 null
     * @param duration 耗时（游戏刻）
     * @param complete 是否**所有** requirement 都被认识并读到了。
     *                 false 时上层必须把整条配方标 opaque —— 见类注释
     */
    public record Parsed(
            String machine,
            Integer duration,
            List<RawSlot> itemInputs,
            List<RawSlot> fluidInputs,
            List<Models.ItemStack> results,
            List<Models.ChanceOutput> chanceResults,
            List<Models.FluidStack> fluidResults,
            boolean complete) {

        public Parsed {
            itemInputs = List.copyOf(itemInputs);
            fluidInputs = List.copyOf(fluidInputs);
            results = List.copyOf(results);
            chanceResults = List.copyOf(chanceResults);
            fluidResults = List.copyOf(fluidResults);
        }

        /** 什么都没读到：编码失败、JSON 坏了、或者碰到未知结构。 */
        public static Parsed unreadable() {
            return new Parsed(null, null, List.of(), List.of(), List.of(), List.of(), List.of(), false);
        }

        public boolean hasOutputs() {
            return !results.isEmpty() || !chanceResults.isEmpty() || !fluidResults.isEmpty();
        }
    }

    private CustomMachineRecipeJson() {
    }

    /**
     * 解析。
     *
     * @throws IllegalArgumentException JSON 结构不对（调用方应当据此退回「读不懂」）
     */
    public static Parsed parse(String json, TagLookup tags) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Builder b = new Builder();
        b.machine = optString(root, "machine");
        b.duration = optInteger(root, "time");

        int unencoded = optInt(root, "unencoded", 0);
        if (unencoded > 0) {
            // 有 requirement 没能编码回 JSON —— 我们连它的形状都不知道
            b.complete = false;
        }

        JsonArray requirements = root.has("requirements") && root.get("requirements").isJsonArray()
                ? root.getAsJsonArray("requirements")
                : new JsonArray();

        for (JsonElement element : requirements) {
            if (!element.isJsonObject()) {
                b.complete = false;
                continue;
            }
            JsonObject requirement = element.getAsJsonObject();
            String type = optString(requirement, "type");
            if (type == null) {
                b.complete = false;
                continue;
            }
            boolean output = "output".equalsIgnoreCase(optString(requirement, "mode"));
            switch (RequirementKinds.of(type)) {
                case ITEM -> readItem(requirement, output, tags, b);
                case FLUID -> readFluid(requirement, output, tags, b);
                case TOOL, CONDITION -> {
                    // 读得到，但不进原料表：工具磨损 / 世界条件
                }
                case INCOMPLETE -> b.complete = false;
            }
        }
        return b.build();
    }

    // ---------------------------------------------------------------- requirement

    private static void readItem(JsonObject requirement, boolean output, TagLookup tags, Builder b) {
        String raw = optString(requirement, "item");
        int amount = optInt(requirement, "amount", 1);
        double chance = optDouble(requirement, "chance", 1.0D);
        if (raw == null || amount <= 0) {
            b.complete = false;
            return;
        }
        List<String> ids = resolve(raw, "item", tags);
        if (ids.isEmpty()) {
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

        if (raw.startsWith("#")) {
            // CM 自己就禁止标签作为产出（ItemRequirement 构造器会抛），走到这里说明数据不对
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

    private static void readFluid(JsonObject requirement, boolean output, TagLookup tags, Builder b) {
        String raw = optString(requirement, "fluid");
        long amount = optLong(requirement, "amount", 1L);
        double chance = optDouble(requirement, "chance", 1.0D);
        if (raw == null || amount <= 0 || amount > Integer.MAX_VALUE) {
            b.complete = false;
            return;
        }
        List<String> ids = resolve(raw, "fluid", tags);
        if (ids.isEmpty()) {
            b.complete = false;
            return;
        }

        if (!output) {
            b.fluidInputs.add(RawSlot.fluids((int) amount, ids));
            return;
        }

        if (raw.startsWith("#")) {
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

    /** 标签展开成成员 id；具体 id 原样返回。 */
    private static List<String> resolve(String raw, String kind, TagLookup tags) {
        if (!raw.startsWith("#")) return List.of(raw);
        String tagId = raw.substring(1);
        if (tagId.isEmpty()) return List.of();
        return tags.members(kind, tagId);
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

        private Parsed build() {
            return new Parsed(machine, duration, itemInputs, fluidInputs,
                    results, chanceResults, fluidResults, complete);
        }
    }
}
