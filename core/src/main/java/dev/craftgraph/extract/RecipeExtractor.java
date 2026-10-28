package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.Humanize;
import dev.craftgraph.normalize.Readability;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条配方翻译成协议 DTO（**与 Minecraft 版本无关**）。
 *
 * <h2>这一层只做搬运，判断都在别处</h2>
 *
 * 归一化与标签还原交给 {@link ExtractionContext}，配方类型特有的字段交给
 * {@link RecipeTypeAdapter}，机器推断交给 {@link MachineTable}，可读性判定交给
 * {@link Readability}。这里只负责按协议把结果拼起来。
 *
 * <p>MC 版本的差异全部收在 {@link RecipePlatform} 里：它回答「类型 id 是什么、
 * 通用输入/产出是什么、toast symbol 是什么」。所以 1.20.1 与 1.21.1 不再各有一份
 * 340 行的近似拷贝 —— 那正是「改了这边忘了那边」的来源。
 *
 * <h2>两个字段曾经全是 null</h2>
 *
 * {@code duration} / {@code machine} 曾经在真实数据里恒为 null，而四层测试全绿，
 * 因为夹具里手写了 duration。症状是产线计算里每个环节都报「手工」。
 * 所以这里除了读取，还记 {@link FieldCoverage}：**下游要用的字段必须有覆盖度可观测**。
 */
public final class RecipeExtractor<T> {

    private final RecipePlatform<T> platform;
    private final ExtractionContext context;
    private final AdapterRegistry<T> adapters;

    /** 字段覆盖度。由变体打进日志 —— 「字段悄悄全是 null」必须留下痕迹。 */
    private final FieldCoverage coverage = new FieldCoverage();

    /** getResultItem 抛异常的次数。可见性很重要：静默吞异常曾让一个 bug 藏了很久。 */
    private int resultItemFailures;

    /** getToastSymbol 抛异常的次数。正常应为 0。 */
    private int toastSymbolFailures;

    public RecipeExtractor(RecipePlatform<T> platform, ExtractionContext context, AdapterRegistry<T> adapters) {
        this.platform = platform;
        this.context = context;
        this.adapters = adapters;
    }

    /**
     * 提取单条配方。
     *
     * <p>id 与配方是**两个参数**：1.21.1 那边它们成对放在 {@code RecipeHolder} 里，
     * 1.20.1 的 id 是 RecipeManager 内部 map 的 key。变体各自把 id 取出来。
     *
     * @return null 表示 id 或配方为 null，只能跳过
     */
    public Models.Recipe extract(String id, T recipe) {
        if (id == null || recipe == null) return null;

        String typeId = platform.typeId(recipe);
        if (typeId == null) typeId = "unknown:unknown";
        String typeLabel = Humanize.typeLabel(typeId);

        List<Models.Ingredient> inputs = new ArrayList<>();
        boolean opaque = false;

        // 输入槽位走适配器：有些配方类（锻造）通用接口返回空列表，但字段里其实有。
        // 声明「不归我管」的适配器会返回 null，这里就落回通用接口的结果。
        for (RawSlot slot : adapters.ingredients(recipe, platform.genericIngredients(recipe))) {
            if (slot.isEmpty()) continue;

            Models.Ingredient normalized = context.normalize(slot);
            if (normalized == null) {
                // 表示不了这个槽位 → 整条 opaque。这比塞一份「看起来精确、实际荒谬」的
                // 原料表安全得多。
                opaque = true;
                break;
            }
            inputs.add(normalized);
        }

        // 流体输入。漏掉它不会报错，只会让原料表看起来完整却少了东西。
        for (RawSlot slot : adapters.fluidIngredients(recipe)) {
            if (slot.isEmpty()) continue;
            Models.Ingredient normalized = context.normalize(slot);
            if (normalized == null) {
                opaque = true;
                break;
            }
            inputs.add(normalized);
        }
        // 判据用总数：只吃流体的配方也是「读到了输入」。
        int inputCount = inputs.size();

        // 产物。先取通用接口的结果再交给适配器：适配器返回 null 表示「用这个」，
        // 返回空列表表示「这个不对，别报出去」（纹饰锻造的硬编码占位符就是后者）。
        List<Models.ItemStack> genericResults = new ArrayList<>();
        Models.ItemStack result = genericResult(recipe);
        if (result != null) genericResults.add(result);

        List<Models.ItemStack> outputs = adapters.results(recipe, genericResults);
        List<Models.ChanceOutput> chanceOutputs = adapters.chanceResults(recipe);
        List<Models.FluidStack> fluidOutputs = adapters.fluidResults(recipe);

        // 真实配方不可能不消耗东西 —— 有产出但没输入，一定是「读不到输入」。
        int outputCount = outputs.size() + chanceOutputs.size() + fluidOutputs.size();

        boolean declaredNoOutput = adapters.declaresNoOutput(recipe);

        Readability.Outcome verdict = Readability.outcome(inputCount, outputCount, opaque, declaredNoOutput);
        // 适配器声明「只读懂了一部分」时，整条也要标 opaque —— 一份「看起来完整、
        // 少了战利品表产出」的原料表，比「读不懂」危险得多。
        boolean partial = !adapters.complete(recipe);
        opaque = verdict == Readability.Outcome.OPAQUE || partial;
        boolean producesNothing = verdict == Readability.Outcome.PRODUCES_NOTHING && !partial;

        // 类型特有的字段：耗时、机器。
        Integer duration = adapters.duration(recipe);
        // 机器优先问适配器；适配器不认这条配方时返回 null，再退回 MachineTable 的推断。
        String machine = adapters.machine(recipe);
        if (machine == null) {
            machine = MachineTable.machineFor(typeId, toastItemId(recipe));
        }

        coverage.record(typeId, duration != null, machine != null);

        return new Models.Recipe(
                id,
                typeId,
                typeLabel,
                List.copyOf(inputs),
                List.copyOf(outputs),
                List.copyOf(fluidOutputs),
                List.copyOf(chanceOutputs),
                machine,
                duration,
                null,        // energy：原版确实没有这个数据。**不要伪造** —— 编一个比 null 危险得多。
                "vanilla",
                opaque,
                producesNothing);
    }

    /** getResultItem 抛异常的配方数。正常应为 0。 */
    public int resultItemFailures() {
        return resultItemFailures;
    }

    /** getToastSymbol 抛异常的配方数。正常应为 0。 */
    public int toastSymbolFailures() {
        return toastSymbolFailures;
    }

    /** 字段覆盖度。每次抽取完读一次，打进日志。 */
    public FieldCoverage coverage() {
        return coverage;
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 取产物。平台抛异常时计数并返回 null —— **失败必须有声音**，
     * 静默吞异常正是「传了 null 注册表」那个 bug 藏了这么久的原因。
     */
    private Models.ItemStack genericResult(T recipe) {
        try {
            return platform.genericResult(recipe);
        } catch (Throwable t) {
            resultItemFailures++;
            return null;
        }
    }

    private String toastItemId(T recipe) {
        try {
            return platform.toastItemId(recipe);
        } catch (Throwable t) {
            toastSymbolFailures++;
            return null;
        }
    }
}
