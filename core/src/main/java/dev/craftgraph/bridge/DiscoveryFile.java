package dev.craftgraph.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 服务发现文件：把实际监听的地址和 token 写到磁盘，让 MCP Server 自己找到。
 *
 * <h2>为什么需要它</h2>
 * 否则用户要在 Mod 配置和 MCP 配置里各填一次端口和 token，改一个忘一个，
 * 然后花半小时 debug 为什么连不上。
 *
 * <h2>为什么写两份</h2>
 * 用第三方启动器（PCL2 / HMCL / Prism）时，游戏目录不在默认的 {@code %APPDATA%\.minecraft}，
 * 外面的 MCP Server 无从得知它在哪里。所以除了写游戏目录，还写一份到固定的用户目录，
 * 这样无论用哪个启动器，MCP Server 都能找到。
 *
 * 两份内容相同，任一存在即可。
 *
 * <h2>为什么它住在 core</h2>
 * 除了「游戏目录在哪」这一步，其余全是纯 Java（写文件、随机 token、JSON）。
 * 游戏目录由变体作为 {@link Path} 传入 —— NeoForge 与 Forge 取它的方式
 * 恰好同名（{@code FMLPaths.GAMEDIR.get()}）但包名不同，那是变体唯一的一行。
 */
public final class DiscoveryFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryFile.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 与 doc/protocol.md 的 protocolVersion 对应。改动协议时同步 +1。 */
    public static final int PROTOCOL_VERSION = 1;

    private final Path gameDirCopy;
    private final Path userDirCopy;
    private final String host;
    private final int port;
    private final String token;

    private DiscoveryFile(Path gameDirCopy, Path userDirCopy, String host, int port, String token) {
        this.gameDirCopy = gameDirCopy;
        this.userDirCopy = userDirCopy;
        this.host = host;
        this.port = port;
        this.token = token;
    }

    /**
     * 创建并立即落盘。
     *
     * token 每次启动重新生成：它的作用是防止本机其他程序（尤其是网页脚本）
     * 悄悄读取游戏数据，长期不变的 token 会削弱这个保护。
     *
     * @param gameDir 游戏目录（变体从 loader 取，如 {@code FMLPaths.GAMEDIR.get()}）
     */
    public static DiscoveryFile publish(Path gameDir, String host, int port) {
        return publishTo(
                gameDir.resolve("craftgraph").resolve("bridge.json"),
                userDirCopyPath(),
                host,
                port);
    }

    /** 固定的用户目录副本路径。与操作系统无关地放在 ~/.craftgraph/ 下，
     * 和 MCP Server 的缓存目录是同一个地方，便于用户排查。 */
    private static Path userDirCopyPath() {
        return Path.of(System.getProperty("user.home"), ".craftgraph", "bridge.json");
    }

    /**
     * 两个落盘路径都由调用方给。包私有：只有测试用它 ——
     * 测试绝不许碰真实的用户目录（~/.craftgraph 是 MCP Server 的真实缓存区，
     * 曾经被测量脚本污染过一次，教训在先）。
     */
    static DiscoveryFile publishTo(Path gameDirCopy, Path userDirCopy, String host, int port) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        DiscoveryFile file = new DiscoveryFile(gameDirCopy, userDirCopy, host, port, token);
        file.write();
        return file;
    }

    public String token() {
        return token;
    }

    public int port() {
        return port;
    }

    private void write() {
        String json = GSON.toJson(new Payload(
                PROTOCOL_VERSION,
                host,
                port,
                token,
                ProcessHandle.current().pid(),
                Instant.now().toString()));

        writeQuietly(gameDirCopy, json);
        writeQuietly(userDirCopy, json);

        LOGGER.info("CraftGraph bridge discovery file written: {}", gameDirCopy);
    }

    private void writeQuietly(Path path, String json) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 写不进去不影响服务本身，只是 MCP Server 要手动配置地址
            LOGGER.warn("Failed to write discovery file: {} ({})", path, e.getMessage());
        }
    }

    /**
     * 退出时清理。
     *
     * 留着过期的发现文件会让 MCP Server 以为游戏还在运行，然后连接超时 ——
     * 报错信息还指向一个"看起来没问题"的地址，很浪费时间。
     */
    public void remove() {
        deleteQuietly(gameDirCopy);
        deleteQuietly(userDirCopy);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete discovery file: {} ({})", path, e.getMessage());
        }
    }

    private record Payload(
            int protocolVersion,
            String host,
            int port,
            String token,
            long pid,
            String startedAt) {
    }
}
