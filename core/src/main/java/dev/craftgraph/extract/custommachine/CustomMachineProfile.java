package dev.craftgraph.extract.custommachine;

import dev.craftgraph.extract.profile.RecipeProfile;
import dev.craftgraph.extract.profile.RequirementRole;

import java.util.Map;

/**
 * Custom Machinery 的 profile（**Stage A：先写成代码常量**）。
 *
 * <h2>为什么这些类型是这些角色</h2>
 *
 * <ul>
 *   <li><b>item / fluid</b>：真的进原料表/产出表。</li>
 *   <li><b>durability</b>：工具磨损。组装机每条配方都挂一张锡制机械硬盘（1024 耐久），
 *       当原料算的话，每 80 tick 就「消耗」一张硬盘，原料表直接错。</li>
 *   <li><b>structure / energy / position / …</b>：世界或机器条件，不消耗也不产出物品。</li>
 *   <li><b>loot_table / drop / item_transform / fuel / fluid_per_tick</b>：会产出或有原料，
 *       但形状表达不了 —— 整条配方必须降级为 opaque，不能报告一份缺东西的原料表。</li>
 * </ul>
 *
 * <p>表里没写的类型由 {@link RecipeProfile#roleOf} 按 {@code INCOMPLETE} 处理：
 * 模组可以注册自己的 requirement，「没见过」不等于「不重要」。
 */
public final class CustomMachineProfile {

    private static final String NS = "custommachinery:";

    /** CM 配方 JSON 的字段名与 requirement 角色表。 */
    public static final RecipeProfile PROFILE = new RecipeProfile(
            "custommachinery:custom_machine",
            "machine",
            "time",
            "requirements",
            "type",
            "mode",
            "item",
            "fluid",
            "amount",
            "chance",
            Map.ofEntries(
                    Map.entry(NS + "item", RequirementRole.ITEM),
                    Map.entry(NS + "fluid", RequirementRole.FLUID),
                    Map.entry(NS + "durability", RequirementRole.TOOL),

                    Map.entry(NS + "energy", RequirementRole.CONDITION),
                    Map.entry(NS + "energy_per_tick", RequirementRole.CONDITION),
                    Map.entry(NS + "structure", RequirementRole.CONDITION),
                    Map.entry(NS + "position", RequirementRole.CONDITION),
                    Map.entry(NS + "biome", RequirementRole.CONDITION),
                    Map.entry(NS + "dimension", RequirementRole.CONDITION),
                    Map.entry(NS + "block", RequirementRole.CONDITION),
                    Map.entry(NS + "entity", RequirementRole.CONDITION),
                    Map.entry(NS + "effect", RequirementRole.CONDITION),
                    Map.entry(NS + "light", RequirementRole.CONDITION),
                    Map.entry(NS + "redstone", RequirementRole.CONDITION),
                    Map.entry(NS + "sky", RequirementRole.CONDITION),
                    Map.entry(NS + "time", RequirementRole.CONDITION),
                    Map.entry(NS + "weather", RequirementRole.CONDITION),
                    Map.entry(NS + "command", RequirementRole.CONDITION),
                    Map.entry(NS + "function", RequirementRole.CONDITION),
                    Map.entry(NS + "chunkload", RequirementRole.CONDITION),
                    Map.entry(NS + "button", RequirementRole.CONDITION),
                    Map.entry(NS + "speed", RequirementRole.CONDITION),
                    Map.entry(NS + "item_filter", RequirementRole.CONDITION),
                    Map.entry(NS + "experience", RequirementRole.CONDITION),
                    Map.entry(NS + "experience_per_tick", RequirementRole.CONDITION),

                    Map.entry(NS + "fluid_per_tick", RequirementRole.INCOMPLETE),
                    Map.entry(NS + "fuel", RequirementRole.INCOMPLETE),
                    Map.entry(NS + "item_transform", RequirementRole.INCOMPLETE),
                    Map.entry(NS + "loot_table", RequirementRole.INCOMPLETE),
                    Map.entry(NS + "drop", RequirementRole.INCOMPLETE)));

    private CustomMachineProfile() {
    }
}
