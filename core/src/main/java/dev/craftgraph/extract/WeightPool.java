package dev.craftgraph.extract;

import dev.craftgraph.api.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * 权重池 → 概率分类。序列组装那类「`chance` 是权重不是概率」的产出就用这个。
 *
 * <h2>为什么它住在 core</h2>
 *
 * 这段逻辑原先在 {@code SequencedAssemblyAdapter} 里，而它是**纯算术**：
 * 输入是一串「产出 + 权重」，输出是「必然 / 概率 / 丢掉」。
 * 唯一的 Minecraft 痕迹是元素的类型，而那已经被换成 {@link Models.ItemStack}。
 * 搬过来之后它能被 fixtures 覆盖，两个 MC 版本也就不用各写一遍。
 *
 * <h2>两个容易写错的细节</h2>
 *
 * <ol>
 *   <li><b>Infinity / NaN 的权重不进权重和。</b>它们自己经归一化会被判成必然产出，
 *       但不能让它们把和变成 Infinity —— 那会把其他所有条目都算成 0（除出来是 0），
 *       整池产出凭空消失，而且不报错。</li>
 *   <li><b>空栈直接丢掉。</b>报告一个空物品出去会让下游以为有这么一个产出
 *       （见 {@code ItemIds} 的说明），宁可少一项也不要编一项。</li>
 * </ol>
 *
 * <p>分类规则本身全在 {@link ResultChance#classifyWeight} 里 —— 这里只负责
 * 「求和、去掉杂音、把每一项送进去」。
 */
public final class WeightPool {

    private WeightPool() {
    }

    /** 池子里的一项：产出 + 它的**权重**（不是概率）。 */
    public record Entry(Models.ItemStack stack, float weight) {
    }

    /**
     * 分类之后的一项。
     *
     * @param probability 已归一化到 (0, 1]；{@link ResultChance.Kind#GUARANTEED} 记 1
     */
    public record Classified(Models.ItemStack stack, float probability, ResultChance.Kind kind) {
    }

    /**
     * @return 按池子原顺序分类的结果；权重和 ≤ 0（或池子为空）时返回空列表
     */
    public static List<Classified> classify(List<Entry> pool) {
        float totalWeight = 0f;
        for (Entry entry : pool) {
            if (entry == null) continue;
            float weight = entry.weight();
            // 只让「有限且为正」的权重进和，见类注释
            if (weight > 0f && Float.isFinite(weight)) {
                totalWeight += weight;
            }
        }
        if (totalWeight <= 0f) return List.of();

        List<Classified> out = new ArrayList<>(pool.size());
        for (Entry entry : pool) {
            if (entry == null || entry.stack() == null) continue;

            ResultChance.Kind kind = ResultChance.classifyWeight(entry.weight(), totalWeight);
            if (kind == ResultChance.Kind.NEVER) continue;

            // GUARANTEED 那一支来自权重 >= 权重和（含 Infinity / NaN），概率记 1
            float probability = kind == ResultChance.Kind.GUARANTEED
                    ? 1f
                    : entry.weight() / totalWeight;
            out.add(new Classified(entry.stack(), probability, kind));
        }
        return out;
    }
}
