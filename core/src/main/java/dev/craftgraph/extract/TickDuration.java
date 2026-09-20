package dev.craftgraph.extract;

/**
 * 耗时的取值范围约定。
 *
 * <h2>为什么值得单独一个类</h2>
 *
 * 「0 或负数不是瞬间完成，是没读到有意义的值」这条判断原本写在好几个适配器里
 * （烹饪、Create、序列组装各自的注释互相引用），而它是**决定**不是搬运：
 *
 * <ul>
 *   <li>返回 {@code 0}，下游会当成「不需要时间」—— 产线计算给出的机器数是无穷大，
 *       或者被当成「手工合成」，而真相是「我们没读到」。</li>
 *   <li>返回 {@code null}，下游会说「耗时未知，算不了台数」—— 这才是诚实的答案。</li>
 * </ul>
 *
 * <p>还有一层：{@code FieldCoverage} 靠 {@code null} 发现「适配器没生效」。
 * 一个把 0 当合法值的适配器会让那条检查失灵 —— 它看着「有值」，实际上是空。
 *
 * <p>放在这里之后，这条约定只有一处定义、一处测试；各适配器只负责把游戏里的数字取出来。
 */
public final class TickDuration {

    private TickDuration() {
    }

    /**
     * @param ticks 游戏刻（20 刻 = 1 秒）
     * @return 有效耗时；{@code ticks <= 0} 时返回 {@code null}（表示未知，不是「瞬间」）
     */
    public static Integer ofOrNull(int ticks) {
        return ticks > 0 ? ticks : null;
    }
}
