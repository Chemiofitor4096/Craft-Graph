package dev.craftgraph.extract.profile;

import java.util.Map;

/**
 * 一个配方类型的「数据化描述」：字段在哪、每种 requirement 算什么。
 *
 * <h2>为什么要这一层</h2>
 *
 * 有 codec 的模组（Custom Machinery 是第一个）能把配方编码成 JSON，形状稳定、
 * 字段名就是它自己的数据契约。以前这些字段名硬编码在 CM 的解析器里，
 * 换一个模组就要再写一个 Java 类；抽成 profile 之后，新类型只是数据。
 *
 * <p>Stage A 的 profile 还是代码里的常量（见 {@code CustomMachineProfile}），
 * 但形状已经是数据形状：Stage B 直接把它序列化成 JSON 资源即可。
 *
 * @param typeId            配方类型 id（用于诊断，实际匹配由调用方决定）
 * @param machineField      机器 id 的字段名
 * @param durationField     耗时的字段名（游戏刻）
 * @param requirementsField requirement 列表的字段名
 * @param typeField         requirement 的类型字段名
 * @param modeField         input/output 字段名
 * @param itemField         物品 id 字段名（值可以是 {@code "id"} 或 {@code "#tag"}）
 * @param fluidField        流体 id 字段名
 * @param countField        数量字段名（物品是个数，流体是 mB）
 * @param chanceField       概率字段名；缺省视为 1.0
 * @param roles             requirement 类型 → 角色的对照表
 */
public record RecipeProfile(
        String typeId,
        String machineField,
        String durationField,
        String requirementsField,
        String typeField,
        String modeField,
        String itemField,
        String fluidField,
        String countField,
        String chanceField,
        Map<String, RequirementRole> roles) {

    public RecipeProfile {
        roles = Map.copyOf(roles);
    }

    /** 没见过的 requirement 类型按「读不全」处理，而不是忽略。 */
    public RequirementRole roleOf(String requirementType) {
        if (requirementType == null) return RequirementRole.INCOMPLETE;
        return roles.getOrDefault(requirementType, RequirementRole.INCOMPLETE);
    }
}
