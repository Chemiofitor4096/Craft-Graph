package dev.craftgraph.normalize;

import dev.craftgraph.api.Models;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 Minecraft 的 {@code Ingredient} 翻译成协议里的槽位表示。
 *
 * <h2>要解决的两个问题</h2>
 *
 * <b>1. 恢复标签。</b> {@code Ingredient.getItems()} 只给物品列表，不说它是不是标签。
 * 拿物品集合去 {@link TagIndex} 反查就能还原 —— 见 TagIndex 的注释说明为什么必须还原。
 *
 * <b>2. 表示不了的槽位要诚实标记，不能硬塞。</b> 某些模组的槽位接受「任意物品」
 * 或返回上千个候选。硬塞进协议会让 JSON 爆炸，而且下游拿到的是一份
 * 「看起来精确、实际荒谬」的原料表。这种情况返回 {@code null}，
 * 由调用方把整条配方标成 {@code opaque}。
 *
 * 纯计算，不依赖 Minecraft，所以能完整测试。
 */
public final class IngredientNormalizer {

    /**
     * 没有标签能解释、且物品数超过这个上限时，认为无法忠实表示，返回 null。
     *
     * 定 64 的理由：整合包里正常的多方案槽位（几个模组的同类锭、几种木板）
     * 最多也就十几个。上百个候选基本只可能是「任意物品」这类槽位，
     * 而那种槽位逐条列出对下游毫无用处 —— 列 500 个候选不如直接说「读不懂」。
     */
    public static final int MAX_UNEXPLAINED_OPTIONS = 64;

    private IngredientNormalizer() {
    }

    /**
     * 翻译一个槽位。
     *
     * @param kind     {@code item} 或 {@code fluid}
     * @param count    数量（流体是 mB）
     * @param itemIds  该槽位接受的物品/流体 id
     * @param tags     标签索引
     * @return 翻译结果；无法忠实表示时返回 {@code null}
     */
    public static Models.Ingredient normalize(String kind, int count, List<String> itemIds, TagIndex tags) {
        Set<String> unique = new LinkedHashSet<>(itemIds);
        if (unique.isEmpty()) return null;

        // --- 1. 恰好等于某个标签 ---
        String exact = tags.findExact(unique);
        if (exact != null) {
            return new Models.Ingredient(kind, count, List.of(Models.Option.tag(exact)));
        }

        // --- 2. 「标签 + 额外物品」---
        // 常见形态：某个配方接受 #forge:ingots/iron 的任意一种，外加一个特殊锭。
        // 还原成「标签 + 补充物品」比逐条列出所有成员忠实得多。
        if (unique.size() > 1) {
            String contained = tags.findContained(unique);
            if (contained != null) {
                List<Models.Option> options = new ArrayList<>();
                options.add(Models.Option.tag(contained));
                Set<String> rest = new LinkedHashSet<>(unique);
                rest.removeAll(tags.membersOf(contained));
                for (String id : rest) options.add(Models.Option.item(id));
                return new Models.Ingredient(kind, count, List.copyOf(options));
            }
        }

        // --- 3. 只能逐条列出 ---
        if (unique.size() > MAX_UNEXPLAINED_OPTIONS) {
            // 表示不了就如实说，不要硬塞一份会让下游误判的原料表
            return null;
        }
        List<Models.Option> options = new ArrayList<>(unique.size());
        for (String id : unique) options.add(Models.Option.item(id));
        return new Models.Ingredient(kind, count, List.copyOf(options));
    }
}
