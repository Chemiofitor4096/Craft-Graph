package dev.craftgraph.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取结果的**字段覆盖度**审计。
 *
 * <h2>它要防的是哪一种 bug</h2>
 *
 * 「测试全绿但功能是死的」：夹具里手写了 {@code duration}，真实数据里全是 null，
 * 而四个测试层没有一层断言过这两个字段 —— 于是 {@code duration} / {@code machine}
 * 静默失效了很久，症状是 {@code calculate_production_plan} 每个环节都报「手工」。
 *
 * <p>单元测试测不到这个：它们跑在夹具上，而夹具是按「我以为的真实数据」造的。
 * 所以除了在 {@code npm run live} 里对真游戏加断言，还要在**运行时**留下一份覆盖度记录 ——
 * 大整合包是用户自己跑的，那里的配方形状谁也没见过，日志必须自己说出来。
 *
 * <h2>{@link #brokenTypes()} 是这里真正有价值的部分</h2>
 *
 * 「耗时覆盖 8.7%」这个数字本身不说明有没有问题：原版本来就只有熔炼类有耗时，
 * 8.7% 是正确的。所以还需要一条**与实现无关**的判据：
 * 某些类型的耗时是平台保证有的（{@link #MUST_HAVE_DURATION}），
 * 它们「有条目但一条耗时都没读到」就一定是解析坏了。
 *
 * <p>这条判据刻意不复用适配器自己的逻辑 —— 拿实现给自己打分等于没测。
 * 这里写死的是「Minecraft 的事实」。
 *
 * <p>纯逻辑，不依赖 Minecraft，所以能完整测试。
 */
public final class FieldCoverage {

    /**
     * 这些原版配方类型的耗时是平台保证有的：它们都继承 {@code AbstractCookingRecipe}，
     * 构造时就必须传入 {@code cookingTime}。
     *
     * <p>反过来说，「{@code minecraft:smelting} 有 70 条配方，但一条耗时都没读到」
     * 不可能是数据缺失，只能是我们的适配器没生效。这正是这次要修的那个 bug 的样子。
     */
    public static final List<String> MUST_HAVE_DURATION = List.of(
            "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking");

    /** 单个类型的计数。total / 有耗时 / 有机器。 */
    private record Stat(int total, int withDuration, int withMachine) {
        Stat plus(boolean duration, boolean machine) {
            return new Stat(total + 1, withDuration + (duration ? 1 : 0), withMachine + (machine ? 1 : 0));
        }
    }

    private final Map<String, Stat> byType = new LinkedHashMap<>();
    private int total;
    private int withDuration;
    private int withMachine;

    /** 记一条配方。类型 id 未知时传 {@code "unknown:unknown"}，同样计入总数。 */
    public void record(String typeId, boolean hasDuration, boolean hasMachine) {
        String key = typeId == null ? "unknown:unknown" : typeId;
        byType.compute(key, (k, existing) -> existing == null
                ? new Stat(1, hasDuration ? 1 : 0, hasMachine ? 1 : 0)
                : existing.plus(hasDuration, hasMachine));
        total++;
        if (hasDuration) withDuration++;
        if (hasMachine) withMachine++;
    }

    public int total() {
        return total;
    }

    public int withDuration() {
        return withDuration;
    }

    public int withMachine() {
        return withMachine;
    }

    /**
     * 「本该有耗时却没读到」的类型，每个元素是一句可以直接打日志的话。
     *
     * <p>返回空列表表示没发现问题。它不应该在正常游戏里非空 ——
     * 非空意味着适配器没生效，而症状会一路传到产线计算里。
     *
     * <p>判据是「覆盖率不是 100%」而不是「覆盖率为 0」。
     * 部分读不到同样是 bug：70 条熔炼里只有 3 条读到耗时，
     * 说明有 67 条走了别的代码路径，而那正是「读不到」的样子。
     * 只判 0 会把这种情况放进「看起来正常」的一档。
     */
    public List<String> brokenTypes() {
        List<String> out = new ArrayList<>();
        for (String typeId : MUST_HAVE_DURATION) {
            Stat stat = byType.get(typeId);
            if (stat == null || stat.total() == 0) continue;
            if (stat.withDuration() == stat.total()) continue;

            String detail = stat.withDuration() == 0
                    ? "none of them has a duration"
                    : "only " + stat.withDuration() + " has a duration";
            out.add(typeId + ": " + stat.total() + " recipes, but " + detail);
        }
        return out;
    }

    /** 一个类型的耗时覆盖率，形如 {@code 70/70}。类型不存在时返回 null。 */
    public String durationRatio(String typeId) {
        Stat stat = byType.get(typeId);
        return stat == null ? null : stat.withDuration() + "/" + stat.total();
    }

    /** 有耗时数据的类型及其覆盖率，按类型 id 排序。给诊断输出用。 */
    public List<String> typesWithDuration() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Stat> e : byType.entrySet()) {
            if (e.getValue().withDuration() > 0) {
                out.add(e.getKey() + " " + e.getValue().withDuration() + "/" + e.getValue().total());
            }
        }
        out.sort(null);
        return out;
    }

    /** 一行摘要，直接进日志。 */
    public String summary() {
        if (total == 0) return "no recipes, coverage unknown";
        return String.format("duration %d/%d (%s%%), machine %d/%d (%s%%)",
                withDuration, total, percent(withDuration),
                withMachine, total, percent(withMachine));
    }

    private String percent(int n) {
        return String.format("%.1f", total == 0 ? 0.0 : n * 100.0 / total);
    }
}
