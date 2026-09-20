package dev.craftgraph.extract;

import dev.craftgraph.api.Models;

import java.util.List;

/**
 * 一个「原始槽位」：某个输入位置接受哪些 id、要几个。
 *
 * <h2>为什么要有这个中间表示</h2>
 *
 * 槽位在读出来的时候是 Minecraft 对象（{@code Ingredient} / {@code SizedFluidIngredient}），
 * 而落进协议之前必须先还原成「标签还是具体物品」—— 这一步由 {@link TagIndex}
 * 反查完成。两种形态之间需要一个**只含字符串和数字**的中间表示，理由有两条：
 *
 * <ol>
 *   <li><b>让适配器不必知道标签还原的存在。</b>适配器只回答「这个位置接受哪些 id」，
 *       至于它原本是标签还是几个散装物品，交给 {@link ExtractionContext#normalize} 统一决定。</li>
 *   <li><b>让同一个适配器能跨 MC 版本存在。</b>流体的原料类型在各版本里完全不同
 *       （1.21.1 是 NeoForge 的 {@code SizedFluidIngredient}，1.20.1 上 Forge 根本没有这个类，
 *       Create 用的是它自己的 {@code FluidIngredient}）。如果接口的签名里出现这些类型，
 *       适配器的逻辑就会被钉死在某个版本上。</li>
 * </ol>
 *
 * <p>{@code ids} 里的东西是**候选**而不是全部都要：一个槽位满足任意一项都算数，
 * 这一点与协议里的 {@code Ingredient.options} 一致。
 *
 * @param kind  {@code item} 或 {@code fluid}（见 {@link Models.Ingredient#KIND_ITEM}）
 * @param count 数量；物品恒为 1，流体是 mB
 * @param ids   该槽位接受的全部 id
 */
public record RawSlot(String kind, int count, List<String> ids) {

    public RawSlot {
        // 不可变 + 不允许 null 元素：这个对象会跨线程传到 HTTP 那边，能安全共享的前提就是没人能改它。
        ids = List.copyOf(ids);
    }

    /** 物品槽位。物品协议里一个槽位就是 1 个（「3 个铁锭」是 3 个槽位，与原版有序合成一致）。 */
    public static RawSlot items(List<String> ids) {
        return new RawSlot(Models.Ingredient.KIND_ITEM, 1, ids);
    }

    /** 流体槽位，数量单位是 mB。 */
    public static RawSlot fluids(int amount, List<String> ids) {
        return new RawSlot(Models.Ingredient.KIND_FLUID, amount, ids);
    }

    /** 空槽位（一个候选都没有）。读取方应当在构造之前就把这种槽位滤掉。 */
    public boolean isEmpty() {
        return ids.isEmpty();
    }
}
