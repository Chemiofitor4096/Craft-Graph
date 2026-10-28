package dev.craftgraph.extract.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProfileLoader} 的测试。
 *
 * <p>重点不是「能读」，而是**坏数据必须响**：缺字段、未知角色都要抛错，
 * 否则一份字段名写错的 profile 会安静地产出错误原料表。
 */
class ProfileLoaderTest {

    @TempDir
    Path tempDir;

    private static String json(String machineField, String role) {
        return """
                {
                  "typeId": "test:machine",
                  "machineField": "%s",
                  "durationField": "time",
                  "requirementsField": "requirements",
                  "typeField": "type",
                  "modeField": "mode",
                  "itemField": "item",
                  "fluidField": "fluid",
                  "countField": "amount",
                  "chanceField": "chance",
                  "roles": { "test:item": "%s" }
                }
                """.formatted(machineField, role);
    }

    @Test
    void loadsBuiltinProfilesFromClasspath() {
        Map<String, RecipeProfile> all = ProfileLoader.load(tempDir, getClass().getClassLoader());

        assertTrue(all.containsKey("custommachinery:custom_machine"),
                () -> "内置 profile 没被加载，实际：" + all.keySet());
    }

    @Test
    void userProfileOverridesBuiltin() throws IOException {
        Path dir = tempDir.resolve("profiles");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("override.json"), json("custom_machine", "ITEM"));

        Map<String, RecipeProfile> all = ProfileLoader.load(dir, getClass().getClassLoader());

        assertEquals("custom_machine", all.get("test:machine").machineField());
    }

    @Test
    void unknownRoleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.parse(new StringReader(json("machine", "MAGIC")), "test.json"));
    }

    @Test
    void missingMachineFieldIsRejected() {
        String missing = json("machine", "ITEM").replace("\"machineField\": \"machine\",", "");
        assertThrows(IllegalArgumentException.class,
                () -> ProfileLoader.parse(new StringReader(missing), "test.json"));
    }

    @Test
    void validProfileParsesRoles() {
        RecipeProfile profile = ProfileLoader.parse(new StringReader(json("machine", "TOOL")), "test.json");

        assertEquals(RequirementRole.TOOL, profile.roleOf("test:item"));
        assertEquals(RequirementRole.INCOMPLETE, profile.roleOf("test:unknown"));
    }
}
