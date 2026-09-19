package dev.craftgraph.api;

import java.util.List;
import java.util.Map;

/**
 * 与 doc/protocol.md 一一对应的数据结构。
 *
 * 用 record 是因为它们只承载数据、没有行为，而且 record 天然不可变 ——
 * 不可变这一点在本项目里很重要：快照会从主线程传到 HTTP 工作线程，
 * 能安全跨线程共享的前提就是没人能改它。
 *
 * 字段名必须与 protocol.md 完全一致，它们是 MCP Server 侧的 JSON key。
 */
public final class Models {

    private Models() {
    }

    // ---------------------------------------------------------------- 物品与流体

    /** 具体、可数的东西。 */
    public record ItemStack(String item, int count, Map<String, Object> components) {
        public static ItemStack of(String item, int count) {
            return new ItemStack(item, count, null);
        }
    }

    /** 流体。amount 单位是毫桶(mB)。 */
    public record FluidStack(String fluid, int amount) {
    }

    // ---------------------------------------------------------------- 配方槽位

    /** 一个可选项：具体物品、标签、或流体。type ∈ item | tag | fluid */
    public record Option(String type, String id) {
        public static Option item(String id) {
            return new Option("item", id);
        }

        public static Option tag(String id) {
            return new Option("tag", id);
        }

        public static Option fluid(String id) {
            return new Option("fluid", id);
        }
    }

    /**
     * 一个配方槽位：满足任意一个 option 都算数。
     *
     * options 里出现 tag 意味着这个槽位有多种可选原料，上层必须做选择。
     * 把 tag 当成一个普通物品处理是这类工具最常见的错误。
     */
    public record Ingredient(String kind, int count, List<Option> options) {
        public static final String KIND_ITEM = "item";
        public static final String KIND_FLUID = "fluid";
    }

    // ---------------------------------------------------------------- 配方

    /** 概率产出，chance 是 0~1。 */
    public record ChanceOutput(ItemStack stack, double chance) {
    }

    /**
     * 一条配方。
     *
     * @param opaque true 表示输入/输出没能被正确解析。
     *               <b>必须显式标记，不能返回空 inputs 了事</b> ——
     *               空 inputs 看起来像"这配方不要原料"，AI 会据此得出错误结论且不自知。
     * @param source 谁归一化的："vanilla" | "emi" | "adapter"
     */
    public record Recipe(
            String id,
            String type,
            String typeLabel,
            List<Ingredient> inputs,
            List<ItemStack> outputs,
            List<FluidStack> fluidOutputs,
            List<ChanceOutput> chanceOutputs,
            String machine,
            Integer duration,
            Integer energy,
            String source,
            boolean opaque) {
    }

    /** 列表接口用的摘要，不含完整 inputs（省流量）。 */
    public record RecipeSummary(
            String id,
            String type,
            String typeLabel,
            ItemStack primaryOutput,
            boolean opaque) {
    }

    // ---------------------------------------------------------------- 元信息

    /** 某个模组是否安装及版本。未安装时整个对象为 null。 */
    public record ModPresence(String version) {
    }

    /**
     * GET /health 的响应。
     *
     * @param ok          服务活着（能响应就为 true）
     * @param ready       配方数据是否已经索引好、可以查询。
     *                    游戏刚启动、或世界正在加载时会为 false —— 此时 /health 能返回，
     *                    但其他端点返回 503。**这个区分很重要**：没有它的话，
     *                    调用方在加载期间会看到 recipeCount=0，然后建出一个空缓存，
     *                    表现为「查什么都说没有」，而不是「还在加载」。
     * @param dataVersion 单调递增。配方或注册表发生任何变化时 +1。
     *                    所有缓存的失效判断都以它为准 —— 不要用启动时间或配方数量代替，
     *                    数量可能不变而内容变了。
     */
    public record Health(
            boolean ok,
            boolean ready,
            int protocolVersion,
            String modVersion,
            String mcVersion,
            String loader,
            ModPresence emi,
            ModPresence jei,
            int recipeCount,
            int itemCount,
            int dataVersion,
            long uptimeMs) {
    }

    public record RegistryEntry(String id, String displayName) {
    }

    public record RegistryPage(String kind, int total, int offset, int limit, List<RegistryEntry> entries) {
    }

    public record TagListEntry(String id, int count) {
    }

    public record TagListPage(String kind, int total, List<TagListEntry> entries) {
    }

    public record TagExpansion(String id, List<String> entries) {
    }

    /** GET /tags/{kind}/all —— 一次拉全部标签，建倒排索引时必需。 */
    public record TagAllPage(String kind, Map<String, List<String>> tags) {
    }

    public record RecipeQueryPage(int total, int offset, int limit, List<RecipeSummary> recipes) {
    }

    /**
     * GET /snapshot 的一页。
     *
     * 每页都带 dataVersion：调用方必须校验各页一致。
     * 中途变了说明游戏重载了配方，之前拉的页全部作废，必须重来。
     */
    public record SnapshotPage(int dataVersion, int cursor, Integer nextCursor, int total, List<Recipe> recipes) {
    }

    // ---------------------------------------------------------------- 错误

    public record ErrorBody(ErrorDetail error) {
        public record ErrorDetail(String code, String message) {
        }

        public static ErrorBody of(String code, String message) {
            return new ErrorBody(new ErrorDetail(code, message));
        }
    }
}
