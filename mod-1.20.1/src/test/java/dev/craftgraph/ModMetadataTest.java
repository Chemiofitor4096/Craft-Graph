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
 * 守住 {@code mods.toml} 里那几条**只有游戏会验**的约定（**1.20.1 版**）。
 *
 * <h2>为什么需要它</h2>
 *
 * 这个文件里有两类字段，编译器、单元测试、CI 的构建**都管不着**，而两种错法都踩过一次，
 * 都是「本地一切正常、装进游戏才被拒」，排查要绕一大圈：
 *
 * <ol>
 *   <li><b>依赖块要 {@code mandatory = true}</b>，不是 NeoForge 那套 {@code type = "required"}。
 *       错了游戏在扫描 mod 文件阶段就抛
 *       {@code InvalidModFileException: Missing required field mandatory in dependency}，
 *       表现成「装了但游戏里没有这个 mod」。</li>
 *   <li><b>{@code loaderVersion} 是 javafml 语言加载器的版本（1.20.1 上是 47）</b>，
 *       不是 Forge 的版本号。写成 {@code [47.2.0,)} 时游戏报
 *       {@code Missing language javafml version [47.2.0,) ... found 47}，然后直接起不来。</li>
 * </ol>
 *
 * 所以它们现在有测试盯着：改这个文件时 {@code ./gradlew test} 就会失败，
 * 不用等把 jar 装进游戏。
 *
 * <p>两条的参照物都**不是记忆**：对着真实可加载的 1.20.1 模组
 *（Create 0.5.1.j 的 {@code mods.toml}，{@code loaderVersion="[47,)"}、
 * 依赖块 {@code mandatory=true}）与游戏自带的 {@code forge-1.20.1-47.2.20-universal.jar} 核过。
 *
 * <p>值从 {@code gradle.properties} 读（模板里是 {@code ${...}} 占位符，读不到实际值），
 * 结构从模板读 —— 前者是值的唯一来源，后者是结构的唯一来源。
 */
class ModMetadataTest {

    private static final Path TEMPLATE =
            Path.of("src", "main", "templates", "META-INF", "mods.toml");

    private static String property(String key) throws IOException {
        // gradle.properties 是 ISO-8859-1 读的（Gradle 的既定行为）
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
    @DisplayName("★ loaderVersion 精确等于 [47,) —— 它是 javafml 的版本，不是 Forge 的版本")
    void loaderVersionIsTheLanguageLoaderVersion() throws IOException {
        String range = property("loader_version_range");

        // ⚠️ 刻意用**精确相等**而不是 startsWith("[47")：
        // 我第一版守卫就是 startsWith，而它**放过了 [47.2.0,)** —— 也就是这次踩到的那个值本身。
        // 反向验证（把坏值注进去看它失不失败）才发现这个漏洞。守卫写宽了等于没写。
        assertEquals("[47,)", range,
                "loaderVersion 写的是 " + range + "。它指的是 javafml 语言加载器的版本 —— "
                        + "1.20.1 上就是 47，不是 Forge 的版本号（47.2.0 是 Forge 的版本）。"
                        + "参照物：Create 0.5.1.j 的 mods.toml 里是 loaderVersion=\"[47,)\"。");
    }

    @Test
    @DisplayName("★ 每个依赖块都有 mandatory = true（不是 type = \"required\"）")
    void dependenciesUseMandatory() throws IOException {
        List<String> lines = templateLines();

        int blocks = 0;
        int mandatory = 0;
        for (String line : lines) {
            if (line.startsWith("[[dependencies.")) blocks++;
            if (line.startsWith("mandatory") && line.contains("true")) mandatory++;
        }

        assertTrue(blocks > 0, "模板里一个依赖块都没有 —— 这条断言会变成空转，先确认文件没被改坏");
        assertEquals(blocks, mandatory,
                "依赖块 " + blocks + " 个，而 mandatory = true 只有 " + mandatory + " 处。"
                        + "Forge 1.20.1 每个依赖块都要 mandatory（NeoForge 才是 type=\"required\"）。");

        for (String line : lines) {
            assertTrue(!line.startsWith("type"),
                    "出现了 type = ... 这一行：" + line + "\n那是 NeoForge 的写法，Forge 1.20.1 会拒收。");
        }
    }

    @Test
    @DisplayName("★ 会展开进 mods.toml 的值必须是 ASCII（中文只写在模板里）")
    void chineseLivesInTheTemplateOnly() throws IOException {
        String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);
        assertTrue(template.contains("把运行中的 Minecraft 配方数据"),
                "中文描述应当在 UTF-8 的模板里。放到 gradle.properties 会变成乱码再展开到这里，"
                        + "玩家在游戏里看到的就是乱码。");

        // 只查**值**行：注释里的中文是允许的。
        // Gradle 按 ISO-8859-1 读这个文件，但注释不参与展开 —— 中文注释的后果只是
        // 「用 UTF-8 打开时那些注释是乱码」，而 mod/gradle.properties 一直这么写着、工作正常。
        // 真正会出事的是**值**：它会被 expand 进 mods.toml，再原样显示给玩家。
        for (String line : Files.readAllLines(Path.of("gradle.properties"), StandardCharsets.ISO_8859_1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(line),
                    "这一行会被展开进 mods.toml，但它含非 ASCII。Gradle 按 ISO-8859-1 读这个文件，"
                            + "中文会变成乱码再原样进游戏。中文请写进模板：\n  " + line);
        }
    }
}
