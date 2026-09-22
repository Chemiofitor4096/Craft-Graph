package dev.craftgraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住 {@code neoforge.mods.toml} 里那几条**只有游戏会验**的约定（1.21.1 版）。
 *
 * <h2>为什么需要它</h2>
 *
 * 1.20.1 那一侧踩了两次「本地全绿、只有游戏拒收」的元数据错误：依赖块该写
 * {@code mandatory} 却写了 {@code type}、{@code loaderVersion} 该是 javafml 的版本
 * 却写了加载器的版本号。两个文件长得很像、很容易互相抄错，
 * 而这个文件恰恰是**反过来**的那一半：这里要 {@code type = "required"}，不要 {@code mandatory}。
 *
 * <p>所以两边各有一个这样的测试 —— 改哪个文件，对应的 {@code ./gradlew test} 就会失败。
 * 这里锁的是**已经在真机验证过能加载的形状**（1.21.1 的 jar 在真实实例上跑通过）。
 *
 * <p>值从 {@code gradle.properties} 读（模板里是 {@code ${...}} 占位符），结构从模板读。
 */
class ModMetadataTest {

    private static final Path TEMPLATE =
            Path.of("src", "main", "templates", "META-INF", "neoforge.mods.toml");

    private static String property(String key) throws IOException {
        if (!Files.exists(Path.of("gradle.properties"))) return "";
        for (String line : Files.readAllLines(Path.of("gradle.properties"), StandardCharsets.ISO_8859_1)) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        throw new AssertionError("gradle.properties 里没有 " + key + " —— 测不了就等于没守");
    }

    private static List<String> templateLines() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(TEMPLATE, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            lines.add(t);
        }
        return lines;
    }

    @Test
    @DisplayName("★ loaderVersion 精确等于 [1,) —— NeoForge 的 javafml 版本是 1，不是 21.1.x")
    void loaderVersionIsTheLanguageLoaderVersion() throws IOException {
        // 精确相等：startsWith 那种写法会放过形如 [1.20.1,) 这类错值（1.20.1 那边正是这么栽的）
        assertEquals("[1,)", property("loader_version_range"),
                "NeoForge 的 javafml 版本是 **1**，不是 NeoForge 的版本号（那是 21.1.x）。"
                        + "写成版本号会让游戏拒绝加载这个 jar。");
    }

    @Test
    @DisplayName("★ 每个依赖块都有 type = \"required\"（不是 mandatory = true）")
    void dependenciesUseType() throws IOException {
        List<String> lines = templateLines();

        int blocks = 0;
        int required = 0;
        for (String line : lines) {
            if (line.startsWith("[[dependencies.")) blocks++;
            if (line.startsWith("type") && line.contains("\"required\"")) required++;
        }

        assertTrue(blocks > 0, "模板里一个依赖块都没有 —— 这条断言会变成空转，先确认文件没被改坏");
        assertEquals(blocks, required,
                "依赖块 " + blocks + " 个，而 type = \"required\" 只有 " + required + " 处。"
                        + "NeoForge 认的是 type；mandatory 是 Forge 1.20.1 的写法，抄过来会让游戏拒收。");

        for (String line : lines) {
            assertTrue(!line.startsWith("mandatory"),
                    "出现了 mandatory 这一行：" + line + "\n那是 Forge 1.20.1 的写法，NeoForge 不认。");
        }
    }

    @Test
    @DisplayName("依赖里声明了 neoforge 与 minecraft")
    void declaresLoaderAndMinecraft() throws IOException {
        String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        assertTrue(template.contains("modId = \"neoforge\""), "缺 neoforge 依赖声明");
        assertTrue(template.contains("modId = \"minecraft\""), "缺 minecraft 依赖声明");
    }

    @Test
    @DisplayName("★ pack.mcmeta 在位，且 pack_format 是 1.21.1 的 34")
    void packMcmetaExistsWithTheRightFormat() throws IOException {
        // 与 1.20.1 那份同理：缺了它游戏报 Missing metadata in pack mod，而构建侧看不见。
        Path mcmeta = Path.of("src", "main", "resources", "pack.mcmeta");
        assertTrue(Files.exists(mcmeta),
                "缺 src/main/resources/pack.mcmeta —— 游戏会报 Missing metadata in pack mod:craftgraph");

        String json = Files.readString(mcmeta, StandardCharsets.UTF_8);
        // 34 = 1.21.1 的资源包格式（取自参考工程 ProtectionEngineering，它能正常加载）。
        assertTrue(json.contains("\"pack_format\": 34"),
                "pack_format 应当是 34（1.21.1）。15 是 1.20.1 的，抄错时游戏会把这个资源包当成别的版本。 实际内容：" + json);
    }
}
