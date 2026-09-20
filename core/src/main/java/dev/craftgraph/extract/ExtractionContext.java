package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.IngredientNormalizer;
import dev.craftgraph.normalize.TagIndex;

/**
 * 把一个 {@link RawSlot} 归一化成协议表示所需要的东西 —— 也就是两张标签索引。
 *
 * <h2>为什么不直接在提取器里做</h2>
 *
 * 「按 kind 挑哪张标签索引」「表示不了就返回 null 让整条配方变 opaque」这两条
 * 都是**决定**，不是搬运。放在这里的好处是它能脱离 Minecraft 测试
 * （喂两个手写的 TagIndex 就够），而提取器那一侧只剩「读游戏对象」。
 *
 * <p>这个对象同时是 1.20.1 那条线要构造的东西 —— 它只依赖 {@link TagIndex}，
 * 与 MC 版本无关。
 *
 * @param itemTags  物品标签
 * @param fluidTags 流体标签
 */
public record ExtractionContext(TagIndex itemTags, TagIndex fluidTags) {

    /** 这个 kind 该用哪张标签索引。未知 kind 按物品处理（协议里只有这两种）。 */
    public TagIndex tagsFor(String kind) {
        return Models.Ingredient.KIND_FLUID.equals(kind) ? fluidTags : itemTags;
    }

    /**
     * 归一化一个槽位。
     *
     * @return 协议表示；<b>{@code null} 表示这个槽位无法忠实表示</b>，
     *         调用方**必须**据此把整条配方标成 {@code opaque} ——
     *         忽略这个 null 就意味着一份「看起来精确、实际荒谬」的原料表
     */
    public Models.Ingredient normalize(RawSlot slot) {
        return IngredientNormalizer.normalize(slot.kind(), slot.count(), slot.ids(), tagsFor(slot.kind()));
    }
}
