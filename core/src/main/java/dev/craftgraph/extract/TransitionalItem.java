package dev.craftgraph.extract;

import java.util.Collection;

/**
 * 「这个槽位是不是只接受中间产物」—— 序列组装里必须排除的那一类槽位。
 *
 * <h2>为什么需要一个判据，而不是「含有就排除」</h2>
 *
 * Create 的序列组装每一步都吃**中间产物**（例如
 * {@code create:incomplete_precision_mechanism}）—— 那是线上自己造出来的过渡物品，
 * 玩家拿不到。不排除的话，原料表里会凭空多出一个不存在的原料，整棵配方树跟着跑偏。
 *
 * <p>但排除的判据必须是**全部候选都是中间产物**，不能是「候选里含有它」：
 * 万一某个槽位是「中间产物 + 别的可选物」，按「含有」排除会把真实原料一起丢掉 ——
 * 而这种错误是静默的：原料表看起来仍然完整，只是少了一项。
 *
 * <h2>为什么用 id 字符串比较</h2>
 *
 * 它在 1.21.1 与 1.20.1 上是同一个判断（两边的中间产物是同一个物品），
 * 而比较 Item 对象需要 MC 类型。用 id 让这条规则住在不含 MC 的 {@code core} 里，
 * 也就能被单测覆盖 —— 这正是它值得从适配器里搬出来的原因。
 */
public final class TransitionalItem {

    private TransitionalItem() {
    }

    /**
     * @param slotIds         这个槽位接受的全部物品 id
     * @param transitionalId  中间产物的 id；{@code null} 表示这条配方没有中间产物
     * @return 是否应当把这个槽位整个排除
     */
    public static boolean isOnlyTransitional(Collection<String> slotIds, String transitionalId) {
        if (transitionalId == null) return false;
        if (slotIds.isEmpty()) return false;   // 空槽位不是「只有中间产物」，它什么都没有
        for (String id : slotIds) {
            if (!transitionalId.equals(id)) return false;
        }
        return true;
    }
}
