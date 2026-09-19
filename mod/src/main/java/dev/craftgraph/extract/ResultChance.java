package dev.craftgraph.extract;

/**
 * 把「带概率的产出」分成三类。纯逻辑，不依赖 Minecraft，可完整测试。
 *
 * <h2>为什么这件事值得单独一个类</h2>
 *
 * Create 的配方产出长这样（`ProcessingOutput`）：每个产出带一个 `chance`，
 * 而 `chance` 的语义**不是到处都一样的**：
 *
 * <ul>
 *   <li>普通加工配方（粉碎、铣削、洗涤…）：`chance` 是 0~1 的概率，
 *       等于 1 表示必然产出（JSON 里通常干脆不写这个字段）。</li>
 *   <li>{@code sequenced_assembly}：`chance` 是**权重**，实测见过 120.0 / 8.0 / 5.0，
 *       根本不是概率。它不属于 {@code ProcessingRecipe}，也就不会走到这个类 ——
 *       但这正是必须把边界写清楚的理由：**同一个字段名在不同配方类型里语义不同**。</li>
 * </ul>
 *
 * <h2>三类的含义</h2>
 *
 * <table>
 *   <tr><td>{@link Kind#GUARANTEED}</td><td>必然产出 → 进协议的 {@code outputs}</td></tr>
 *   <tr><td>{@link Kind#PROBABILISTIC}</td><td>概率产出 → 进 {@code chanceOutputs}（带 0~1 的概率）</td></tr>
 *   <tr><td>{@link Kind#NEVER}</td><td>概率 ≤ 0，永远不会产 → 丢掉</td></tr>
 * </table>
 *
 * <p>把必然产出混进 {@code chanceOutputs} 会让下游按期望值算，
 * 于是「必然产出 1 个」被算成「期望 1 个」—— 数字看着一样，但语义错了，
 * 而且当概率不是 1 时会直接把产量算少。反过来把概率产出当成必然产出，
 * 会算多。两个方向都有害，所以这里必须有明确判据。
 */
public final class ResultChance {

    /** 产出应归到哪一类。 */
    public enum Kind {
        /** 必然产出。 */
        GUARANTEED,
        /** 概率产出，概率在 (0, 1) 开区间内。 */
        PROBABILISTIC,
        /** 概率 ≤ 0，永远不产出。 */
        NEVER,
    }

    private ResultChance() {
    }

    /**
     * 判断一个产出的归属。
     *
     * <p>几个刻意的边界决定：
     *
     * <ul>
     *   <li><b>恰好 1.0 → 必然产出。</b>Create 的 JSON 里不写 chance 时默认就是 1.0，
     *       所以 1.0 是最常见的值，必须算必然。</li>
     *   <li><b>大于 1 → 必然产出，而不是钳到 1。</b>在 {@code ProcessingRecipe} 里这不该出现
     *       （它只可能来自 sequenced_assembly 那种「权重」语义）。
     *       真出现了说明我对这个字段的理解有偏差，这时**宁可多报一个产出也不要丢掉它** ——
     *       按概率报会把必然产出算成期望值，悄悄把产量算少。</li>
     *   <li><b>NaN → 必然产出。</b>同上：不丢数据优先。NaN 会污染一切算术，
     *       让它进 {@code chanceOutputs} 的话，期望值计算会整个变成 NaN，
     *       那种失败很难从结果里看出来。</li>
     *   <li><b>0 或负数 → 丢掉。</b>Create 用它表示「这个产出被禁用了」。</li>
     * </ul>
     */
    public static Kind classify(float chance) {
        if (Float.isNaN(chance)) return Kind.GUARANTEED;
        if (chance <= 0f) return Kind.NEVER;
        if (chance >= 1f) return Kind.GUARANTEED;
        return Kind.PROBABILISTIC;
    }

    /**
     * 按权重归一化后分类：概率 = 权重 / 权重和。
     *
     * <p>Create 的 {@code sequenced_assembly} 用的是权重而不是概率 ——
     * 实测那条精准机械动力的池子是 {@code [120, 8, 8, 5, 3, 2, 2, 1, 1]}，
     * 权重和 150，所以产出概率是 120/150 = 80%，其余按比例是废料。
     * Create 自己的 {@code getOutputChance()} 算的就是这个式子。
     *
     * <p>抽成纯函数是因为它有真实的边界，而且**错了不会报错**：
     *
     * <ul>
     *   <li><b>权重和 ≤ 0 → {@link Kind#NEVER}。</b>没法归一化。返回「必然」会让
     *       每条产出都变成 100%，返回「概率」会得到除零后的 Infinity。</li>
     *   <li><b>权重是 Infinity → {@link Kind#GUARANTEED}。</b>{@code Inf / 有限和} = Inf，
     *       经 {@link #classify} 判为必然。这是对的：一个无限大的权重本来就压过所有其他条目。</li>
     *   <li><b>权重是 NaN → {@link Kind#GUARANTEED}。</b>NaN 参与除法仍是 NaN，
     *       同 {@link #classify} 的处理：不丢数据优先。</li>
     *   <li><b>池子里只有一项 → 概率恰好 1.0 → 必然产出。</b>
     *       这一条很重要：{@code sturdy_sheet} / {@code track} 的池子只有一项，
     *       它们其实是必然产出，不该走 {@code chanceOutputs} 被下游按期望值算。</li>
     * </ul>
     */
    public static Kind classifyWeight(float weight, float totalWeight) {
        if (totalWeight <= 0f) return Kind.NEVER;
        return classify(weight / totalWeight);
    }
}
