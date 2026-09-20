package dev.craftgraph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志文案必须是 ASCII（英文）。
 *
 * <h2>它守的是一个实测出来的可读性缺陷</h2>
 *
 * 在中文 Windows 上，Minecraft 把日志文件按 **GBK** 写出去。
 * 而整份日志里**只有我们这几行含中文** —— 别的行都是 ASCII ——
 * 于是任何按 UTF-8 打开的查看器里，**只有 CraftGraph 的行是乱码**，其余看着都正常。
 *
 * <p>实测证据：`All of Create - Aeronautics` 那个包的 debug.log 里，
 * 日志自带的中文日期是 GBK 字节（`0xD4 0xC2` = 「月」），而我们的
 * 「快照已重建：15241 条配方…主线程抽取 155 ms」在 UTF-8 查看器里全是乱码。
 *
 * <p>一份读不出来的日志等于没有日志：诊断「抽取耗时多少」「字段覆盖度多少」
 * 全靠这几行。而这类信息恰恰是用户（和下一个接手的 agent）最需要的。
 *
 * <h2>为什么不用「main 源码里不许有中文」这种简单判据</h2>
 *
 * 因为那会误伤两类**应该**保留中文的东西：
 *
 * <ul>
 *   <li>注释（不进日志，中文更有信息量）</li>
 *   <li><b>HTTP 错误消息</b> —— 它们走 JSON 且响应头显式声明 {@code charset=utf-8}，
 *       是给 AI 读的，没有编码问题，中文更准确</li>
 * </ul>
 *
 * 所以这里只看 {@code LOGGER.*(...)} 语句内部。
 *
 * <p><b>局限</b>：只覆盖 {@code LOGGER} 这个入口。如果将来有日志走别的通道
 * （比如直接 {@code System.out}），这条守卫不会发现 —— 那时要把它一起加进来。
 */
class LogEncodingTest {

    /** 从 {@code LOGGER.xxx(} 一直取到它自己的分号。DOTALL 让跨行的调用也能整段匹配。 */
    private static final Pattern LOGGER_CALL = Pattern.compile("LOGGER\\.[a-z]+\\(.*?;", Pattern.DOTALL);

    /** 至少应该找到这么多条日志语句。低于它说明扫描逻辑坏了，而不是「恰好没有中文」。 */
    private static final int MIN_EXPECTED_CALLS = 15;

    /**
     * 要扫的源码根。
     *
     * <p>第二个是**共享核心**（另一个 Gradle 工程）。它现在一条日志都没有，
     * 但它将来会有 —— 而那时如果这里只扫 mod 自己的源码，新加的中文日志
     * 会绕过守卫，症状又是「只有我们这几行是乱码」。守卫要覆盖整个 Java 产品，
     * 而不是碰巧覆盖到的那一半。
     *
     * <p>注意守卫留在 mod 工程而不是 core：它守的是「这个产品写日志的方式」，
     * 而 core 单独看并不知道自己还有个 mod 兄弟。
     */
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("..", "core", "src", "main", "java"));

    @Test
    @DisplayName("所有日志语句都是 ASCII（含跨行的拼接）")
    void loggerCallsAreAscii() throws IOException {
        List<String> offenders = new ArrayList<>();
        int total = 0;

        for (Path root : SOURCE_ROOTS) {
            // 根不存在必须失败，不能跳过：静默跳过等于「这一半没检查过」，
            // 而它在报告里看起来和「检查过且干净」一模一样。
            assertTrue(Files.isDirectory(root),
                    "源码根不存在，扫描没有覆盖到它：" + root.toAbsolutePath());

            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter((p) -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher m = LOGGER_CALL.matcher(source);
                    while (m.find()) {
                        total++;
                        String call = m.group();
                        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(call)) {
                            offenders.add(file + "\n    " + call.replace("\n", "\n    "));
                        }
                    }
                }
            }
        }

        // 先确认扫描真的扫到了东西。没有这一条的话，「一个都没扫到」也会全绿 ——
        // 而那种绿色的含义是「什么都没检查」，正是这个项目最警惕的假通过。
        assertTrue(total >= MIN_EXPECTED_CALLS,
                "只扫到 " + total + " 条日志语句（预期至少 " + MIN_EXPECTED_CALLS + "）—— 扫描逻辑可能坏了，"
                        + "这时候全绿不代表日志是 ASCII");

        assertEquals(List.of(), offenders,
                "以下日志语句含非 ASCII 字符。日志会被写成 GBK，在 UTF-8 查看器里只有这些行是乱码：\n"
                        + String.join("\n", offenders)
                        + "\n改写成英文即可（HTTP 错误消息不受此限，它们走 JSON 且声明了 charset=utf-8）。");
    }
}
