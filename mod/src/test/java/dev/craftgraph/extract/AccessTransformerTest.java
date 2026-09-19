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
 * 守住访问转换器与适配器之间的约定。
 *
 * <h2>它守的不是「AT 有没有写错」——那个编译器管</h2>
 *
 * {@code SmithingAdapter} 直接读 {@code SmithingTransformRecipe.template} 这类**包私有**字段，
 * 所以**删掉 AT 里对应的一行，编译就会失败**（实测过：注释掉
 * {@code SmithingTrimRecipe base} 之后 {@code compileJava} 直接报错，根本走不到测试）。
 * 这是最强的一道保障，而且它的报错信息比任何测试都准 —— 直接指向适配器里那一行。
 *
 * <p>{@code validateAccessTransformers = true} 则负责另一半：条目写错（字段名不存在）时构建期失败。
 *
 * <h2>那这个测试还有什么用</h2>
 *
 * 守「将来有人把 AT 整套摘掉」这种情况：那时适配器大概也会一起改掉读取方式
 * （比如改用三个谓词），编译当然通过，而配方质量会静默退回修复前 ——
 * 9 条下界合金变 opaque、18 条纹饰重新报出「铁胸甲」假产物，
 * 且**看起来跟本来就该这样一模一样**。
 *
 * <p>它同时是「这 6 行是有用的、不是历史遗留」的记录：读这个文件的人
 * 一眼能看到 AT 与适配器是配套的。
 *
 * <p>真正「字段读到了没有、假产物有没有消失」由 {@code npm run live}
 * 在真游戏上断言（见 live.ts 的锻造段），这里做不到 —— 单元测试看不到 Minecraft。
 */
class AccessTransformerTest {

    /** 相对 Gradle 项目目录（测试的工作目录就是 mod/）。 */
    private static final Path AT_FILE = Path.of("src", "main", "resources", "META-INF", "accesstransformer.cfg");

    /**
     * 必须存在的条目。与 {@link SmithingAdapter} 读的字段一一对应。
     *
     * <p>两边任何一侧变了这里都要跟着改 —— 这是刻意的：
     * 「适配器读 a 字段、AT 开放 b 字段」这种错配，编译器发现不了（因为在两个文件里），
     * 只有跑起来才知道，而跑起来的症状又是那个安静的退化。
     */
    private static final List<String> REQUIRED = List.of(
            "public net.minecraft.world.item.crafting.SmithingTransformRecipe template",
            "public net.minecraft.world.item.crafting.SmithingTransformRecipe base",
            "public net.minecraft.world.item.crafting.SmithingTransformRecipe addition",
            "public net.minecraft.world.item.crafting.SmithingTrimRecipe template",
            "public net.minecraft.world.item.crafting.SmithingTrimRecipe base",
            "public net.minecraft.world.item.crafting.SmithingTrimRecipe addition");

    private static List<String> effectiveLines() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(AT_FILE, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            // AT 的注释以 # 开头；空行忽略
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            lines.add(trimmed);
        }
        return lines;
    }

    @Test
    @DisplayName("访问转换器文件存在，且 6 个锻造字段条目都在")
    void allSmithingEntriesPresent() throws IOException {
        assertTrue(Files.exists(AT_FILE), "找不到 " + AT_FILE.toAbsolutePath()
                + " —— 没有它 SmithingAdapter 根本编译不过，说明构建配置也被改坏了");

        List<String> lines = effectiveLines();
        for (String required : REQUIRED) {
            assertTrue(lines.contains(required),
                    "访问转换器缺少这一条：\n  " + required
                            + "\n少了它锻造的输入/产出会静默退回修复前的行为（9 条变 opaque、18 条报假产物）。"
                            + "\n当前文件里的条目：\n  " + String.join("\n  ", lines));
        }
    }

    @Test
    @DisplayName("条目都是 public —— 漏了修饰符等于没生效")
    void entriesArePublic() throws IOException {
        // AT 条目不写 public 也「合法」（构建期校验过得去），但字段依旧包私有，
        // 于是适配器编译不过。这条主要是给未来手改这个文件的人一句就近的提示。
        for (String line : effectiveLines()) {
            assertTrue(line.startsWith("public "),
                    "AT 条目必须以 public 开头：" + line);
        }
    }

    @Test
    @DisplayName("两个锻造子类各 3 条 —— 少一个就有一半配方退回去")
    void bothSmithingSubclassesCovered() throws IOException {
        List<String> lines = effectiveLines();
        long transform = lines.stream().filter(l -> l.contains("SmithingTransformRecipe")).count();
        long trim = lines.stream().filter(l -> l.contains("SmithingTrimRecipe")).count();

        // 升级配方（9 条）和纹饰配方（18 条）是两个不同的类，字段各自独立。
        // 只开放其中一个的话，另一个会安静地保持修复前的行为。
        assertTrue(transform == 3, "SmithingTransformRecipe 应有 3 条（template/base/addition），实际 " + transform);
        assertTrue(trim == 3, "SmithingTrimRecipe 应有 3 条（template/base/addition），实际 " + trim);
    }
}
