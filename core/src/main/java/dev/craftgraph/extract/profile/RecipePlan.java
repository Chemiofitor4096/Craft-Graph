package dev.craftgraph.extract.profile;

import dev.craftgraph.api.Models;
import dev.craftgraph.extract.RawSlot;

import java.util.List;

/**
 * 一条 profile 驱动解析出来的「配方材料计划」。
 *
 * <p>它是适配器与 core 解析器之间的中立结果：输入/产出已经拆成协议槽位，
 * 但还没有做标签还原（那是 {@code ExtractionContext} 的事）。
 *
 * <p>{@code complete=false} 时调用方**必须**把整条配方标成 opaque ——
 * 它表示「读到了一部分，但还有读不懂的东西」，不是「没有」。
 */
public record RecipePlan(
        String machine,
        Integer duration,
        List<RawSlot> itemInputs,
        List<RawSlot> fluidInputs,
        List<Models.ItemStack> results,
        List<Models.ChanceOutput> chanceResults,
        List<Models.FluidStack> fluidResults,
        boolean complete) {

    public RecipePlan {
        itemInputs = List.copyOf(itemInputs);
        fluidInputs = List.copyOf(fluidInputs);
        results = List.copyOf(results);
        chanceResults = List.copyOf(chanceResults);
        fluidResults = List.copyOf(fluidResults);
    }

    /** 什么都没读到：编码失败、JSON 坏了、或者碰到未知结构。 */
    public static RecipePlan unreadable() {
        return new RecipePlan(null, null, List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    public boolean hasOutputs() {
        return !results.isEmpty() || !chanceResults.isEmpty() || !fluidResults.isEmpty();
    }
}
