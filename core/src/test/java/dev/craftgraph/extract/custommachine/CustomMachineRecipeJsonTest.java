package dev.craftgraph.extract.custommachine;

import dev.craftgraph.api.Models;
import dev.craftgraph.extract.RawSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomMachineRecipeJson} 的测试。
 *
 * <p>这些用例对应的是「天真实现会静默算错」的几种形状：工具被当原料、
 * 概率产出被当必然产出、不认识的 requirement 被忽略。
 */
class CustomMachineRecipeJsonTest {

    private static final CustomMachineRecipeJson.TagLookup NO_TAGS = (kind, tag) -> List.of();

    private static CustomMachineRecipeJson.TagLookup tags(String kind, String tag, List<String> members) {
        return (k, t) -> k.equals(kind) && t.equals(tag) ? members : List.of();
    }

    private static String recipe(String requirementsJson) {
        return """
                {
                  "machine": "dut:assembling_machine",
                  "time": 80,
                  "requirements": [%s],
                  "unencoded": 0
                }
                """.formatted(requirementsJson);
    }

    @Test
    void readsMachineAndDuration() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"input","item":"kubejs:speed_module","amount":3}
                        """), NO_TAGS);

        assertEquals("dut:assembling_machine", parsed.machine());
        assertEquals(80, parsed.duration());
        assertTrue(parsed.complete());
    }

    @Test
    void repeatsItemInputsByAmount() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"input","item":"kubejs:speed_module","amount":3}
                        """), NO_TAGS);

        assertEquals(3, parsed.itemInputs().size());
        for (RawSlot slot : parsed.itemInputs()) {
            assertEquals(List.of("kubejs:speed_module"), slot.ids());
        }
    }

    @Test
    void durabilityIsNotAnIngredient() {
        // 组装机每条配方都挂一张 1024 耐久的锡制硬盘；它是工具，不是原料。
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"input","item":"kubejs:speed_module","amount":3},
                        {"type":"custommachinery:durability","mode":"input","item":"kubejs:tin_hard_disk","amount":1024}
                        """), NO_TAGS);

        assertTrue(parsed.complete());
        assertEquals(3, parsed.itemInputs().size());
        assertTrue(parsed.itemInputs().stream()
                .noneMatch(slot -> slot.ids().contains("kubejs:tin_hard_disk")));
    }

    @Test
    void expandsItemTags() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"input","item":"#forge:ingots/tin","amount":1}
                        """),
                tags("item", "forge:ingots/tin", List.of("kubejs:tin_ingot", "create:tin_ingot")));

        assertEquals(1, parsed.itemInputs().size());
        assertEquals(List.of("kubejs:tin_ingot", "create:tin_ingot"), parsed.itemInputs().get(0).ids());
    }

    @Test
    void unresolvedTagMarksIncomplete() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"input","item":"#forge:ingots/unknown","amount":1}
                        """), NO_TAGS);

        assertFalse(parsed.complete());
    }

    @Test
    void splitsGuaranteedAndChanceOutputs() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"output","item":"kubejs:productivity_module","amount":3},
                        {"type":"custommachinery:item","mode":"output","item":"kubejs:scrap","amount":1,"chance":0.25}
                        """), NO_TAGS);

        assertEquals(1, parsed.results().size());
        assertEquals(3, parsed.results().get(0).count());
        assertEquals(1, parsed.chanceResults().size());
        assertEquals(0.25D, parsed.chanceResults().get(0).chance(), 1e-9);
        assertTrue(parsed.complete());
    }

    @Test
    void readsFluidInputsAndOutputs() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:fluid","mode":"input","fluid":"kubejs:tin","amount":1350},
                        {"type":"custommachinery:fluid","mode":"output","fluid":"kubejs:steam","amount":500}
                        """), NO_TAGS);

        assertEquals(1, parsed.fluidInputs().size());
        assertEquals(1350, parsed.fluidInputs().get(0).count());
        assertEquals(1, parsed.fluidResults().size());
        assertEquals(500, parsed.fluidResults().get(0).amount());
        assertTrue(parsed.complete());
    }

    @Test
    void chanceFluidOutputIsIncomplete() {
        // 协议没有「概率流体产出」这一维，不能把它当必然产出
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:fluid","mode":"output","fluid":"kubejs:steam","amount":500,"chance":0.5}
                        """), NO_TAGS);

        assertFalse(parsed.complete());
        assertTrue(parsed.fluidResults().isEmpty());
    }

    @Test
    void unknownRequirementMarksIncomplete() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"input","item":"kubejs:a","amount":1},
                        {"type":"someaddon:magic","mode":"output","item":"kubejs:b","amount":1}
                        """), NO_TAGS);

        assertFalse(parsed.complete());
    }

    @Test
    void lootTableMarksIncomplete() {
        // 产出在战利品表里，读不到 —— 不能说「这条配方不产出」
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:loot_table","mode":"output","table":"minecraft:chests/simple_dungeon"}
                        """), NO_TAGS);

        assertFalse(parsed.complete());
    }

    @Test
    void unencodedRequirementMarksIncomplete() {
        String json = """
                {"machine":"dut:m","time":20,"requirements":[],"unencoded":1}
                """;
        assertFalse(CustomMachineRecipeJson.parse(json, NO_TAGS).complete());
    }

    @Test
    void emptyRequirementsIsCompleteButProducesNothing() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe(""), NO_TAGS);

        assertTrue(parsed.complete());
        assertFalse(parsed.hasOutputs());
        assertEquals(0, parsed.itemInputs().size());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(RuntimeException.class,
                () -> CustomMachineRecipeJson.parse("not json", NO_TAGS));
    }

    @Test
    void chanceZeroIsNotAnOutput() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"output","item":"kubejs:never","amount":1,"chance":0.0}
                        """), NO_TAGS);

        assertTrue(parsed.complete());
        assertEquals(0, parsed.results().size());
        assertEquals(0, parsed.chanceResults().size());
    }

    @Test
    void itemOutputKeepsCount() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                recipe("""
                        {"type":"custommachinery:item","mode":"output","item":"kubejs:productivity_module","amount":3}
                        """), NO_TAGS);

        Models.ItemStack stack = parsed.results().get(0);
        assertEquals("kubejs:productivity_module", stack.item());
        assertEquals(3, stack.count());
    }

    @Test
    void missingMachineIsNull() {
        CustomMachineRecipeJson.Parsed parsed = CustomMachineRecipeJson.parse(
                "{\"time\":20,\"requirements\":[]}", NO_TAGS);

        assertNull(parsed.machine());
    }
}
