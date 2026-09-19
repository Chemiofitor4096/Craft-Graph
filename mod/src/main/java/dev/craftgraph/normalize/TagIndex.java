package dev.craftgraph.normalize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签索引：支持「从一个物品集合反推出它属于哪个标签」。
 *
 * <h2>为什么需要这个</h2>
 *
 * Minecraft 的 {@code Ingredient.getItems()} 只给出一串物品，**不告诉你它原本是个标签**。
 * 「#forge:ingots/iron」和「显式列出 iron_ingot 和 othermod:iron_ingot 两个物品」
 * 在 API 层面看起来一模一样。
 *
 * 而这两者在下游差别很大：标签意味着「这类物品任意一个都行」，是给玩家留的选择空间；
 * 显式列表是一个确定的答案。丢掉标签信息，产线建议就会退化成
 * 「必须用 minecraft:iron_ingot」，而玩家手里可能只有别的模组的铁锭。
 *
 * 解决办法：拿物品集合去标签表里反查。语义上等价的标签就还原成标签。
 *
 * 这是纯计算，不依赖 Minecraft，所以能完整测试。
 */
public final class TagIndex {

    /** tagId -> 成员（有序，保持注册顺序） */
    private final Map<String, List<String>> members;
    /** 成员 id -> 包含它的标签 id */
    private final Map<String, List<String>> containing;

    private TagIndex(Map<String, List<String>> members, Map<String, List<String>> containing) {
        this.members = members;
        this.containing = containing;
    }

    public static TagIndex of(Map<String, List<String>> tags) {
        Map<String, List<String>> members = new LinkedHashMap<>();
        Map<String, List<String>> containing = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : tags.entrySet()) {
            members.put(e.getKey(), List.copyOf(e.getValue()));
            for (String member : e.getValue()) {
                containing.computeIfAbsent(member, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        return new TagIndex(members, containing);
    }

    public static TagIndex empty() {
        return new TagIndex(Map.of(), Map.of());
    }

    public List<String> membersOf(String tagId) {
        return members.getOrDefault(tagId, List.of());
    }

    /** 包含该物品的所有标签。 */
    public List<String> tagsOf(String itemId) {
        return containing.getOrDefault(itemId, List.of());
    }

    public int tagCount() {
        return members.size();
    }

    /**
     * 找一个**成员集合与给定物品集合完全相同**的标签。
     *
     * <p>实现上取「所有元素所属标签的交集」：一个标签要与集合精确相等，
     * 就必须包含集合里的每一个元素。**不能只拿任意一个元素去探测** ——
     * 那个元素可能恰好不在任何标签里，于是漏掉本可匹配的标签，
     * 而且结果会随集合的迭代顺序变化。
     *
     * <p>多个标签成员完全相同时（例如 {@code forge:ingots/iron} 与 {@code c:iron_ingots}），
     * 按 {@link #preference} 排序取最优 —— 见那里的说明。
     *
     * @return 没找到返回 null
     */
    public String findExact(String... itemIds) {
        return findExact(new LinkedHashSet<>(List.of(itemIds)));
    }

    public String findExact(Set<String> itemIds) {
        if (itemIds.isEmpty()) return null;

        // 交集：只有同时出现在每个元素所属标签里的，才可能是精确匹配
        Set<String> candidates = null;
        for (String itemId : itemIds) {
            List<String> owned = containing.get(itemId);
            if (owned == null || owned.isEmpty()) return null; // 有一个元素不属于任何标签 → 不可能精确匹配
            if (candidates == null) candidates = new LinkedHashSet<>(owned);
            else candidates.retainAll(owned);
            if (candidates.isEmpty()) return null;
        }
        if (candidates == null) return null;

        String best = null;
        for (String tagId : candidates) {
            List<String> tagMembers = members.get(tagId);
            if (tagMembers == null || tagMembers.size() != itemIds.size()) continue;
            if (new HashSet<>(tagMembers).equals(itemIds) && better(tagId, best)) best = tagId;
        }
        return best;
    }

    /**
     * 找一个**成员被给定集合完全包含**的最大标签。
     *
     * <p>用于「标签 ∪ 额外物品」这种槽位：例如某配方接受
     * 「#forge:ingots/iron 里的任意一种，外加某个特殊锭」，此时物品集合是标签成员的超集。
     *
     * <p>取「所有元素所属标签的并集」作为候选：若一个标签的成员全在集合里，
     * 它必然包含集合中的至少一个元素，所以一定落在并集里。
     *
     * @return 没找到返回 null
     */
    public String findContained(Set<String> itemIds) {
        if (itemIds.isEmpty()) return null;

        Set<String> candidates = new LinkedHashSet<>();
        for (String itemId : itemIds) {
            candidates.addAll(containing.getOrDefault(itemId, List.of()));
        }

        String best = null;
        int bestSize = -1;
        for (String tagId : candidates) {
            List<String> tagMembers = members.get(tagId);
            if (tagMembers == null || tagMembers.isEmpty()) continue;
            if (tagMembers.size() > itemIds.size()) continue;
            if (!itemIds.containsAll(tagMembers)) continue;
            if (tagMembers.size() > bestSize
                    || (tagMembers.size() == bestSize && better(tagId, best))) {
                best = tagId;
                bestSize = tagMembers.size();
            }
        }
        return best;
    }

    /**
     * 候选标签之间的优劣：先看命名空间偏好，再看字典序。
     *
     * <p>为什么要偏好顺序而不是纯字典序：{@code forge:ingots/iron} 和 {@code c:iron_ingots}
     * 在 NeoForge 里是同一批物品的两个约定标签。纯字典序会选 {@code c:}，
     * 但玩家和整合包文档里更常见的是 {@code forge:}。选一个大家认得的名字，
     * 报告读起来才不别扭。两者都在时优先 {@code forge}，其次 {@code c}（NeoForge 新约定），
     * 其次 {@code minecraft}，再其次其他。
     *
     * <p>本质上这是个展示偏好，不影响数据正确性 —— 两个标签的成员完全一样，
     * 下游 {@code expand_tag} 拿到的结果相同。所以无论选哪个都安全，这里只是选个顺眼的。
     */
    private static final List<String> NAMESPACE_PREFERENCE = List.of("forge", "c", "minecraft");

    private static boolean better(String candidate, String current) {
        if (current == null) return true;
        int a = rank(candidate);
        int b = rank(current);
        if (a != b) return a < b;
        return candidate.compareTo(current) < 0;
    }

    private static int rank(String tagId) {
        int colon = tagId.indexOf(':');
        String ns = colon > 0 ? tagId.substring(0, colon) : "";
        int idx = NAMESPACE_PREFERENCE.indexOf(ns);
        return idx >= 0 ? idx : NAMESPACE_PREFERENCE.size();
    }

    /** 把标签表导出成 {@code tagId -> 成员} 的形式，交给快照建索引用。 */
    public Map<String, List<String>> asMap() {
        return members;
    }

    /** 去重并保持顺序的小工具。 */
    public static Set<String> orderedSet(List<String> items) {
        return new LinkedHashSet<>(items);
    }
}
