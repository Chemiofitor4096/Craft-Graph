package dev.craftgraph.normalize;

import dev.craftgraph.api.Models;

import java.util.List;

/**
 * 判定一条配方是否「读不懂」。
 *
 * <h2>为什么判定逻辑要单独抽出来</h2>
 *
 * 这个判断决定了 AI 会看到「这配方读不懂」还是「这配方不需要材料」——
 * 后者是**静默的错误答案**，比读不懂危险得多。所以它值得单独成类、单独测试，
 * 而不是散在抽取代码的 if 里。
 *
 * <h2>三种情况都算读不懂</h2>
 *
 * <ol>
 *   <li><b>槽位解析失败</b>：某个槽位有上千个候选且无法用标签解释，
 *       硬塞进协议会得到一份「看起来精确、实际荒谬」的原料表。</li>
 *   <li><b>没有产出</b>：不知道它产出什么，这条配方对下游没有价值。</li>
 *   <li><b>没有输入</b>：<b>这条最容易漏掉。</b> 真实配方不可能不消耗任何东西，
 *       所以「无输入」一定是「我们读不到输入」。
 *       典型例子：盔甲纹饰锻造 —— Minecraft 的 {@code SmithingRecipe} 不覆盖
 *       {@code getIngredients()}，继承的默认实现返回空列表，
 *       它的槽位信息藏在 {@code isTemplateIngredient} 这类谓词里。</li>
 * </ol>
 *
 * <p>第 3 条是实测踩出来的：只修「产出为空」的 bug 之后，锻造配方会变成
 * 「有产出、无输入、opaque=false」——看起来像「纹饰不需要材料」。
 * 判定规则必须同时覆盖两头。
 *
 * <p>纯逻辑，不依赖 Minecraft，可测试。
 */
public final class Readability {

    private Readability() {
    }

    /**
     * @param inputs           成功解析出的输入槽位
     * @param outputs          成功解析出的产出
     * @param slotUnparseable  是否有槽位无法解析（由 {@link IngredientNormalizer} 返回 null 触发）
     * @return true 表示应当标记为 opaque
     */
    public static boolean isOpaque(List<Models.Ingredient> inputs, List<Models.ItemStack> outputs, boolean slotUnparseable) {
        if (slotUnparseable) return true;
        if (outputs.isEmpty()) return true;
        if (inputs.isEmpty()) return true;
        return false;
    }

    /**
     * 生成一句人类可读的原因，用于日志和诊断。
     *
     * <p>「读不懂」这个比例本身不告诉你该不该优化，原因才有用：
     * 前者可能是物理限制（代码驱动的配方本来就没有声明式输入），
     * 后者才值得写适配器。
     */
    public static String reason(List<Models.Ingredient> inputs, List<Models.ItemStack> outputs, boolean slotUnparseable) {
        if (slotUnparseable) return "有槽位无法解析";
        if (outputs.isEmpty() && inputs.isEmpty()) return "输入和产出都读不到";
        if (outputs.isEmpty()) return "读不到产出";
        if (inputs.isEmpty()) return "读不到输入（配方用谓词而非声明式槽位）";
        return "";
    }
}
