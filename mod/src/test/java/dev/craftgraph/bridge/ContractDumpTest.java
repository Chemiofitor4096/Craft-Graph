package dev.craftgraph.bridge;

import dev.craftgraph.api.Json;
import dev.craftgraph.api.Models;
import dev.craftgraph.snapshot.RecipeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出跨语言的契约样本。
 *
 * <h2>为什么需要这个</h2>
 *
 * 两侧的开发一直是各自对着假数据做的：TypeScript 侧用手写的假 Bridge，
 * Java 侧用自己的内存数据。**它们从没真正对过话。** 两边都照着
 * {@code doc/protocol.md} 写，但契约本身从没被验证过 ——
 * 字段名差一个字母、{@code null} 处理不一致、数组和对象搞混，
 * 这些都要等到用户进游戏、同时调两个系统时才会暴露，而且很难定位。
 *
 * 这个测试把 Java 侧**真实的 HTTP 响应体**写到 {@code shared/fixtures/bridge-dump/}，
 * 由 TypeScript 侧的 {@code npm run contract} 读进来，喂给它整个索引和处理链路。
 * 这样契约就被真正验证了：不是「两边都照着文档写」，而是「一边的输出确实能被另一边吃下去」。
 *
 * <h2>它同时是「金标准样本」</h2>
 *
 * 导出的 JSON 就是 Java 侧实际会发出的东西，可以直接拿来看、拿去写文档、
 * 或者当调试参照。跑 {@code ./gradlew test} 时会自动刷新。
 */
class ContractDumpTest {

    private static final Path OUT_DIR = Path.of("..", "shared", "fixtures", "bridge-dump");

    // ---------------------------------------------------------------- 样本数据
    //
    // 刻意覆盖所有「容易两边理解不一致」的形态：
    // 标签输入、概率产出、读不懂的配方、流体、多输出、深链、null 字段。

    private static Models.ItemStack item(String id, int count) {
        return new Models.ItemStack(id, count, null);
    }

    private static Models.Ingredient itemIng(int count, Models.Option... options) {
        return new Models.Ingredient("item", count, List.of(options));
    }

    private static RecipeSnapshot sample() {
        return RecipeSnapshot.builder(42)
                .tags(Map.of(
                        "forge:ingots/iron", List.of("minecraft:iron_ingot", "othermod:iron_ingot"),
                        "forge:ores/iron", List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
                        "minecraft:planks", List.of("minecraft:oak_planks", "minecraft:birch_planks"),
                        "minecraft:coals", List.of("minecraft:coal", "minecraft:charcoal")))
                // 注册表必须包含全部标签成员 —— 真实游戏里注册表是全量的，
                // 而 RecipeSnapshot.tagList(kind) 会按「成员是否属于该注册表」过滤标签。
                // 样本里少放几个物品就会让某些标签凭空消失，测出一个不存在的场景。
                .registry("items", List.of(
                        "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                        "minecraft:iron_ingot", "othermod:iron_ingot",
                        "minecraft:iron_block", "minecraft:iron_nugget",
                        "minecraft:torch", "minecraft:stick",
                        "create:crushed_raw_iron",
                        "minecraft:oak_planks", "minecraft:birch_planks",
                        "minecraft:coal", "minecraft:charcoal",
                        "minecraft:obsidian", "somemod:tungsten_steel_ingot"))
                .registry("fluids", List.of("minecraft:water", "minecraft:lava"))
                .displayNames(Map.of("minecraft:iron_ingot", "铁锭", "minecraft:torch", "火把"))
                .addRecipe(new Models.Recipe(
                        "minecraft:iron_ingot_from_smelting_iron_ore", "minecraft:smelting", "Smelting",
                        List.of(itemIng(1, Models.Option.tag("forge:ores/iron"))),
                        List.of(item("minecraft:iron_ingot", 1)),
                        List.of(), List.of(),
                        "minecraft:furnace", 200, null, "vanilla", false))
                .addRecipe(new Models.Recipe(
                        "minecraft:iron_block", "minecraft:crafting", "Crafting",
                        List.of(itemIng(9, Models.Option.tag("forge:ingots/iron"))),
                        List.of(item("minecraft:iron_block", 1)),
                        List.of(), List.of(),
                        "minecraft:crafting_table", null, null, "vanilla", false))
                // 概率产出：产物只出现在 chanceOutputs 里
                .addRecipe(new Models.Recipe(
                        "create:crushing/iron_ore", "create:crushing", "Crushing",
                        List.of(itemIng(1, Models.Option.item("minecraft:iron_ore"))),
                        List.of(item("create:crushed_raw_iron", 1)),
                        List.of(),
                        List.of(new Models.ChanceOutput(item("minecraft:iron_nugget", 1), 0.75)),
                        "create:crushing_wheel", 100, 4400, "vanilla", false))
                // 流体输入
                .addRecipe(new Models.Recipe(
                        "examplepack:obsidian_from_fluids", "examplepack:fluid_mixer", "Fluid Mixing",
                        List.of(
                                new Models.Ingredient("fluid", 1000, List.of(Models.Option.fluid("minecraft:water"))),
                                new Models.Ingredient("fluid", 1000, List.of(Models.Option.fluid("minecraft:lava")))),
                        List.of(item("minecraft:obsidian", 1)),
                        List.of(), List.of(),
                        "examplepack:mixer", 40, 4000, "vanilla", false))
                // 读不懂的配方：inputs 为空但 opaque=true
                .addRecipe(new Models.Recipe(
                        "somemod:alloy_smelting/tungsten_steel", "somemod:alloy_smelting", null,
                        List.of(), List.of(item("somemod:tungsten_steel_ingot", 1)),
                        List.of(), List.of(),
                        "somemod:alloy_smelter", null, null, "vanilla", true))
                .build();
    }

    // ---------------------------------------------------------------- 导出

    @Test
    @DisplayName("导出 Java 侧真实响应体供 TypeScript 侧验收")
    void dumpHttpResponses() throws IOException {
        RecipeSnapshot snap = sample();
        Files.createDirectories(OUT_DIR);

        // /health —— 注意 emi/jei 为 null，ready 为 true
        write("health.json", new Models.Health(
                true, true, BridgeHttpServer.PROTOCOL_VERSION,
                "0.1.0-contract", "1.21.1", "neoforge-21.1.251",
                null, null,
                snap.recipeCount(), snap.registryIds("items").size(), snap.dataVersion(), 12345L));

        // /snapshot —— 单页，nextCursor 必须是 JSON null
        write("snapshot.json", snap.snapshotPage(0, 500));

        // /recipes?output=... —— 摘要列表
        write("recipes-output.json", snap.queryRecipes("minecraft:iron_ingot", null, null, 0, 50));

        // /recipes?input=... —— 验证标签展开后的倒排索引
        write("recipes-input.json", snap.queryRecipes(null, "minecraft:iron_ingot", null, 0, 50));

        // /recipes/{id} —— 完整配方（含概率产出）
        write("recipe-single.json", snap.recipe("create:crushing/iron_ore"));

        // /recipes/{id} —— opaque 配方
        write("recipe-opaque.json", snap.recipe("somemod:alloy_smelting/tungsten_steel"));

        // /tags/items/all —— 批量标签，建倒排索引必需
        Map<String, List<String>> allTags = new java.util.LinkedHashMap<>();
        for (Models.TagListEntry e : snap.tagList("items")) {
            allTags.put(e.id(), snap.expandTag(e.id()));
        }
        write("tags-items-all.json", new Models.TagAllPage("items", allTags));

        // /registry/items?query=iron
        write("registry-items.json", snap.registry("items", "iron", 0, 100));

        // /registry/items（不带查询）—— MCP Server 建立缓存时会这样调用，用来取全部物品名
        write("registry-items-all.json", snap.registry("items", null, 0, 1000));
        write("registry-fluids-all.json", snap.registry("fluids", null, 0, 1000));

        // 错误响应
        write("error-not-found.json", Models.ErrorBody.of("NOT_FOUND", "配方不存在：foo:bar"));
        write("error-not-ready.json", Models.ErrorBody.of("NOT_READY", "配方数据还在加载中，请稍后重试"));

        // ---- 自检：导出的东西必须能被 Gson 读回来（防止写出畸形 JSON）----
        Models.Health reread = Json.fromJson(
                Files.readString(OUT_DIR.resolve("health.json"), StandardCharsets.UTF_8), Models.Health.class);
        assertEquals(42, reread.dataVersion());
        assertTrue(reread.ready());

        // 关键字段的 JSON 形态必须符合协议约定
        String healthJson = Files.readString(OUT_DIR.resolve("health.json"), StandardCharsets.UTF_8);
        assertTrue(healthJson.contains("\"emi\":null"), "emi 未安装必须是 JSON null：" + healthJson);
        assertTrue(healthJson.contains("\"ready\":true"), healthJson);

        String snapshotJson = Files.readString(OUT_DIR.resolve("snapshot.json"), StandardCharsets.UTF_8);
        assertTrue(snapshotJson.contains("\"nextCursor\":null"),
                "最后一页的 nextCursor 必须是 JSON null（TypeScript 侧靠它判断结束）：" + snapshotJson);

        String recipeJson = Files.readString(OUT_DIR.resolve("recipe-single.json"), StandardCharsets.UTF_8);
        assertTrue(recipeJson.contains("\"chanceOutputs\":[{\"stack\":"), "概率产出形状不对：" + recipeJson);
        assertTrue(recipeJson.contains("\"duration\":100") && recipeJson.contains("\"energy\":4400"), recipeJson);

        String opaqueJson = Files.readString(OUT_DIR.resolve("recipe-opaque.json"), StandardCharsets.UTF_8);
        assertTrue(opaqueJson.contains("\"opaque\":true"), "opaque 标记必须出现：" + opaqueJson);
        assertTrue(opaqueJson.contains("\"typeLabel\":null"), "typeLabel 为 null 也要出现：" + opaqueJson);

        String tagJson = Files.readString(OUT_DIR.resolve("tags-items-all.json"), StandardCharsets.UTF_8);
        assertTrue(tagJson.contains("\"forge:ingots/iron\""), tagJson);
    }

    private static void write(String name, Object value) throws IOException {
        Files.writeString(OUT_DIR.resolve(name), Json.toJson(value), StandardCharsets.UTF_8);
    }
}
