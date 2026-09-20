package dev.craftgraph.extract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守住访问转换器与适配器之间的约定（**1.20.1 版**）。
 *
 * <h2>这个版本与 1.21.1 那份的两点不同</h2>
 *
 * <ol>
 *   <li><b>成员名是 SRG 名</b>（Forge 1.20.1 的硬性要求：官方文档写着
 *       "the SRG name must be used for fields and methods"）。所以这里的条目不长得像
 *       字段名，而长得像 {@code f_265949_} —— 那三个 Transform 的值与 JEI 在 1.20.1 上的
 *       AT 逐字一致；Trim 那三个 JEI 没写，是把 Mojang 官方映射与 Forge 的 mcp_config
 *       串起来查出来的（见 porting 文档）。</li>
 * </ol>
 *
 * <h2>它守的不是「AT 有没有写错」——那个编译器和构建期校验管</h2>
 *
 * {@code SmithingAdapter} 直接读 {@code SmithingTransformRecipe.template} 这类**包私有**字段，
 * 所以删掉 AT 里对应的一行，{@code compileJava} 就会失败 —— 这是最强的一道保障，
 * 报错信息也最准（直接指向适配器那一行）。{@code validateAccessTransformers = true}
 * 负责另一半：条目名不存在时构建期失败。
 *
 * <p>那这个测试还有什么用：守「将来有人把 AT 整套摘掉」这种情况 —— 那时适配器大概
 * 也会一起改成别的读法，编译当然通过，而配方质量会静默退回修复前
 * （9 条下界合金变 opaque、18 条纹饰重新报出「铁胸甲」假产物），
 * 且**看起来跟本来就该这样一模一样**。
 *
 * <p>真正「字段读到了没有」由 {@code npm run live} 在真游戏上断言。
 */
class AccessTransformerTest {

    /** 相对 Gradle 项目目录（测试的工作目录就是这个工程目录）。 */
    private static final Path AT_FILE = Path.of("src", "main", "resources", "META-INF", "accesstransformer.cfg");

    /**
     * 必须存在的条目。与适配器读的字段一一对应。
     *
     * <p>两边任何一侧变了这里都要跟着改 —— 这是刻意的：
     * 「适配器读 a 字段、AT 开放 b 字段」这种错配，编译器发现不了（因为在两个文件里）。
     */
    private static final List<String> REQUIRED = List.of(
            // 下界合金升级（SmithingTransformRecipe）—— 与 JEI 在 1.20.1 上的 AT 一致
            "public net.minecraft.world.item.crafting.SmithingTransformRecipe f_265949_",
            "public net.minecraft.world.item.crafting.SmithingTransformRecipe f_265888_",
            "public net.minecraft.world.item.crafting.SmithingTransformRecipe f_265907_",
            // 盔甲纹饰（SmithingTrimRecipe）—— 这三条是自己查出来的
            "public net.minecraft.world.item.crafting.SmithingTrimRecipe f_265958_",
            "public net.minecraft.world.item.crafting.SmithingTrimRecipe f_266040_",
            "public net.minecraft.world.item.crafting.SmithingTrimRecipe f_266053_");

    private static List<String> effectiveLines() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(AT_FILE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            // AT 的注释以 # 开头；空行忽略
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // 行尾的 "# base" 这类注释要去掉，否则与 REQUIRED 对不上
            int hash = trimmed.indexOf('#');
            if (hash >= 0) trimmed = trimmed.substring(0, hash).trim();
            lines.add(trimmed);
        }
        return lines;
    }

    @Test
    @DisplayName("访问转换器文件存在，且 6 个锻造条目都在")
    void allRequiredEntriesPresent() throws IOException {
        assertTrue(Files.exists(AT_FILE), "找不到 " + AT_FILE.toAbsolutePath()
                + " —— 没有它 SmithingAdapter 根本编译不过，说明构建配置也被改坏了");

        List<String> lines = effectiveLines();
        for (String required : REQUIRED) {
            assertTrue(lines.contains(required),
                    "访问转换器缺少这一条：\n  " + required
                            + "\n少了它对应的一类配方会静默退回修复前的行为。"
                            + "\n当前文件里的条目：\n  " + String.join("\n  ", lines));
        }
    }

    @Test
    @DisplayName("条目都是 public —— 漏了修饰符等于没生效")
    void entriesArePublic() throws IOException {
        for (String line : effectiveLines()) {
            assertTrue(line.startsWith("public "),
                    "AT 条目必须以 public 开头：" + line);
        }
    }

    @Test
    @DisplayName("两个锻造子类各 3 条 —— 少一个就有一半配方退回去")
    void coveredPerClass() throws IOException {
        List<String> lines = effectiveLines();
        long transform = lines.stream().filter(l -> l.contains("SmithingTransformRecipe")).count();
        long trim = lines.stream().filter(l -> l.contains("SmithingTrimRecipe")).count();

        // 升级配方（9 条）和纹饰配方（18 条）是两个不同的类，字段各自独立。
        assertTrue(transform == 3, "SmithingTransformRecipe 应有 3 条（template/base/addition），实际 " + transform);
        assertTrue(trim == 3, "SmithingTrimRecipe 应有 3 条（template/base/addition），实际 " + trim);
    }
}
