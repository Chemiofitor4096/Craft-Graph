package dev.craftgraph.normalize;

/**
 * 判定一条配方是否「读不懂」。
 *
 * <h2>为什么判定逻辑要单独抽出来</h2>
 *
 * 这个判断决定了 AI 会看到「这配方读不懂」还是「这配方不需要材料」——
 * 后者是**静默的错误答案**，比读不懂危险得多。所以它值得单独成类、单独测试，
 * 而不是散在抽取代码的 if 里。
 *
 * <h2>输入产出用「数量」而不是列表</h2>
 *
 * 一开始这两个参数是原料列表和产物列表，后来加了流体槽位和概率产出就撑不住了：
 * Create 的洗涤配方**只有概率产出**（`chance: 0.25`），拿「必然产出的列表为空」
 * 去判的话，一条明明读得很清楚的配方会被判成 opaque。
 * 所以改成数量 —— 判据本来就是「有没有读到东西」，而不是「读到的是哪一种」。
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
 *       {@code getIngredients()}，继承的默认实现返回空列表。
 *       （那一条现在由 {@code SmithingAdapter} 通过访问转换器读出来了，
 *       但这条规则本身仍然必要：读不到就是读不到。）</li>
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

    /** 一条配方的可读性判定结果。 */
    public enum Outcome {
        /** 输入产出都读到了。 */
        READABLE,
        /**
         * 输入读到了，产出确实是空的 —— 这条配方**本来就不产出东西**。
         *
         * <p>典型是燃料定义（`createaddition:liquid_burning`、`petrochem:*_fuel`）：
         * 它描述的是「烧掉什么换能量」，没有任何物品产出。
         * 这和「产出读不到」是两回事 —— 混在一起会让 AI 说错话。
         */
        PRODUCES_NOTHING,
        /** 有东西没能解析出来。 */
        OPAQUE,
    }

    /**
     * 判定一条配方。
     *
     * @param inputCount        成功解析出的输入槽位数量（物品 + 流体）
     * @param outputCount       成功解析出的产出数量（必然 + 概率 + 流体）
     * @param slotUnparseable   是否有槽位无法解析（由 {@link IngredientNormalizer} 返回 null 触发）
     * @param declaredNoOutput  适配器**读过这个类型自己的产出字段**之后确认「确实不产出」。
     *                          见 {@link dev.craftgraph.extract.RecipeTypeAdapter#declaresNoOutput}
     */
    public static Outcome outcome(int inputCount, int outputCount, boolean slotUnparseable, boolean declaredNoOutput) {
        if (slotUnparseable) return Outcome.OPAQUE;
        if (outputCount > 0) {
            // 有产出但没输入 —— 真实配方不可能不消耗东西，所以那是「读不到输入」
            return inputCount > 0 ? Outcome.READABLE : Outcome.OPAQUE;
        }
        // 产出为空：区分「本来就不产出」和「读不到产出」。
        // 只有适配器明确作证过才算前者 —— 「读不到」绝不能被降级成「不产出」，
        // 那会让 AI 把一条读不懂的配方说成「这配方不需要产出」。
        if (declaredNoOutput) return Outcome.PRODUCES_NOTHING;
        return Outcome.OPAQUE;
    }

    /** 只需要「是不是读不懂」时的便捷重载（不产出也算可读）。 */
    public static boolean isOpaque(int inputCount, int outputCount, boolean slotUnparseable) {
        return outcome(inputCount, outputCount, slotUnparseable, false) == Outcome.OPAQUE;
    }

    /**
     * 生成一句人类可读的原因，用于日志和诊断。
     *
     * <p>「读不懂」这个比例本身不告诉你该不该优化，原因才有用：
     * 前者可能是物理限制（代码驱动的配方本来就没有声明式输入），
     * 后者才值得写适配器。
     *
     * <p>区分「读不到输入」和「读不到产出」尤其有用：盔甲纹饰锻造属于后者
     * （输入靠访问转换器读到了，产出是组合式的、无法用单一物品表达），
     * 这两句话对 AI 的含义完全不同。
     */
    public static String reason(int inputCount, int outputCount, boolean slotUnparseable, boolean declaredNoOutput) {
        if (slotUnparseable) return "有槽位无法解析";
        if (outputCount > 0) return inputCount > 0 ? "" : "读不到输入（配方用谓词而非声明式槽位）";
        if (declaredNoOutput) return "不产出物品（燃料/配置类定义）";
        if (inputCount == 0) return "输入和产出都读不到";
        return "读不到产出";
    }

    /** {@link #reason(int, int, boolean, boolean)} 的便捷重载。 */
    public static String reason(int inputCount, int outputCount, boolean slotUnparseable) {
        return reason(inputCount, outputCount, slotUnparseable, false);
    }
}
