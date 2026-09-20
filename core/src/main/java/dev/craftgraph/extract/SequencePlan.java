package dev.craftgraph.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * 序列组装（{@code create:sequenced_assembly}）的纯算法部分。
 *
 * <h2>为什么它住在 core</h2>
 *
 * 「整条序列要走 {@code loops} 遍」「中间产物不是原料」「总耗时是各步之和乘遍数」
 * 这三件事都是**算术与判据**，与 MC 版本无关。留在适配器里的话，1.20.1 那一侧
 * 就要把同一套逻辑再写一遍 —— 而这正是最费料的一类配方（精准机械动力要 5 轮 × 3 步），
 * 逻辑写两遍就会漂。
 *
 * <p>语义是读 Create 源码确认的，不是猜的：
 * {@code SequencedAssemblyRecipe} 里判进度用的是
 * {@code (step + 1) / sequence.size() >= loops}，也就是总步数 = 序列长度 × 遍数。
 *
 * <h2>为什么倍数是「重复槽位」而不是数量</h2>
 *
 * 协议的槽位没有「消耗几次」的概念，所以按 {@code loops} 次重复同一个槽位 ——
 * 这跟原版有序合成的做法一致（「3 个铁锭」就是 3 个各含 1 个铁锭的槽位），
 * TypeScript 侧在递归前会按物品合并并求和，接口因此不用改。
 *
 * <h2>顺序是有意义的</h2>
 *
 * 产出顺序必须是：基础槽位 → 各步的槽位（按序列顺序）。抽取器会把物品槽位与流体槽位
 * 分两次取，所以这里两个方法各自保持自己的顺序。
 */
public final class SequencePlan {

    /** 序列至少走一遍 —— 0 或负数会让所有原料凭空消失。 */
    public static final int MIN_LOOPS = 1;

    private SequencePlan() {
    }

    /**
     * 序列里的一步。
     *
     * @param itemSlots     这一步的物品输入（已按 id 表示）
     * @param fluidSlots    这一步的流体输入
     * @param durationTicks 这一步的耗时；读不到时为 {@code null}
     */
    public record Step(List<RawSlot> itemSlots, List<RawSlot> fluidSlots, Integer durationTicks) {

        public Step {
            itemSlots = List.copyOf(itemSlots);
            fluidSlots = List.copyOf(fluidSlots);
        }

        public static Step ofItemSlots(List<RawSlot> itemSlots, Integer durationTicks) {
            return new Step(itemSlots, List.of(), durationTicks);
        }
    }

    /**
     * 全部物品输入：基础槽位（一个产出吃一个）+ 各步槽位 × 遍数，排除中间产物。
     *
     * @param base               基础槽位（通常是一个）
     * @param steps              序列
     * @param transitionalItemId 中间产物 id；{@code null} 表示没有
     * @param loops              序列走几遍；{@code <= 0} 按 1 算
     */
    public static List<RawSlot> itemInputs(List<RawSlot> base, List<Step> steps,
                                           String transitionalItemId, int loops) {
        int repetitions = loopsOrOne(loops);
        List<RawSlot> out = new ArrayList<>();

        for (RawSlot slot : base) {
            if (slot == null || slot.isEmpty()) continue;
            out.add(slot);
        }

        for (Step step : steps) {
            if (step == null) continue;
            for (RawSlot slot : step.itemSlots()) {
                if (slot == null || slot.isEmpty()) continue;
                // 中间产物不算原料：那是线上自己造出来的过渡物品，玩家拿不到。
                // 判据是「全是中间产物」而不是「含有」（见 TransitionalItem）。
                if (TransitionalItem.isOnlyTransitional(slot.ids(), transitionalItemId)) continue;
                for (int i = 0; i < repetitions; i++) {
                    out.add(slot);
                }
            }
        }
        return out;
    }

    /** 全部流体输入：各步流体槽位 × 遍数（流体没有中间产物这回事）。 */
    public static List<RawSlot> fluidInputs(List<Step> steps, int loops) {
        int repetitions = loopsOrOne(loops);
        List<RawSlot> out = new ArrayList<>();

        for (Step step : steps) {
            if (step == null) continue;
            for (RawSlot slot : step.fluidSlots()) {
                if (slot == null || slot.isEmpty()) continue;
                for (int i = 0; i < repetitions; i++) {
                    out.add(slot);
                }
            }
        }
        return out;
    }

    /**
     * 总耗时 = 各步耗时之和 × 遍数，对应「一条组装线跑完一个产出要多久」,
     * 所以下游据此算出的「N 台」实际上是「N 条并行组装线」。
     *
     * @return 游戏刻；一步都没读到耗时时返回 {@code null}（不是 0）
     */
    public static Integer totalDuration(List<Step> steps, int loops) {
        int total = 0;
        for (Step step : steps) {
            if (step == null || step.durationTicks() == null) continue;
            if (step.durationTicks() > 0) total += step.durationTicks();
        }
        return TickDuration.ofOrNull(total * loopsOrOne(loops));
    }

    private static int loopsOrOne(int loops) {
        return Math.max(MIN_LOOPS, loops);
    }
}
