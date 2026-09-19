package dev.craftgraph.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

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
 */
public final class DiscoveryFile {

    private static final Logger LOGGER = LogUtils.getLogger();
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
     */
    public static DiscoveryFile publish(String host, int port) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        DiscoveryFile file = new DiscoveryFile(
                FMLPaths.GAMEDIR.get().resolve("craftgraph").resolve("bridge.json"),
                userDirCopyPath(),
                host,
                port,
                token);

        file.write();
        return file;
    }

    /**
     * 固定的用户目录副本路径。与操作系统无关地放在 ~/.craftgraph/ 下，
     * 和 MCP Server 的缓存目录是同一个地方，便于用户排查。
     */
    private static Path userDirCopyPath() {
        return Path.of(System.getProperty("user.home"), ".craftgraph", "bridge.json");
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
