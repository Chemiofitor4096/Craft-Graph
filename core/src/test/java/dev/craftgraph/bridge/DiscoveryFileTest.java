package dev.craftgraph.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务发现文件的语义测试。
 *
 * <p>这个类从前住在 mod 变体里，写死的两份路径让它没法在无游戏环境测；
 * 搬进 core 并把两个路径参数化之后，才第一次有了覆盖。
 *
 * <p>⚠️ 测试只许走包私有的 {@code publishTo}（两个路径都由测试给）。
 * 公开的 {@code publish(gameDir, ...)} 会写真实的 ~/.craftgraph/bridge.json ——
 * 那是 MCP Server 的真实缓存区，测试碰它就是把假数据写进用户环境。
 */
class DiscoveryFileTest {

    @TempDir
    Path tempDir;

    private Path gameCopy() {
        return tempDir.resolve("game").resolve("bridge.json");
    }

    private Path userCopy() {
        return tempDir.resolve("user").resolve("bridge.json");
    }

    @Test
    @DisplayName("落盘两份、内容一致、字段齐全（protocolVersion/host/port/token/pid/startedAt）")
    void writesBothCopiesWithFullPayload() throws Exception {
        Path gameCopy = gameCopy();
        Path userCopy = userCopy();

        DiscoveryFile file = DiscoveryFile.publishTo(gameCopy, userCopy, "127.0.0.1", 25585);

        assertTrue(Files.isRegularFile(gameCopy), "游戏目录副本缺失");
        assertTrue(Files.isRegularFile(userCopy), "用户目录副本缺失");

        JsonObject json = JsonParser.parseString(Files.readString(gameCopy)).getAsJsonObject();
        assertEquals(1, json.get("protocolVersion").getAsInt());
        assertEquals("127.0.0.1", json.get("host").getAsString());
        assertEquals(25585, json.get("port").getAsInt());
        assertFalse(json.get("startedAt").isJsonNull());
        assertTrue(json.get("pid").getAsLong() > 0);

        // token 是 16 字节的十六进制（32 字符），且与 getter 一致 —— MCP Server 靠它鉴权
        String token = json.get("token").getAsString();
        assertTrue(token.matches("[0-9a-f]{32}"), "token 应为 32 个十六进制字符：" + token);
        assertEquals(token, file.token());

        assertEquals(Files.readString(gameCopy), Files.readString(userCopy), "两份内容应完全一致");
    }

    @Test
    @DisplayName("remove() 把两份都清掉 —— 残留文件会让 MCP Server 以为游戏还在运行")
    void removeDeletesBothCopies() throws Exception {
        Path gameCopy = gameCopy();
        Path userCopy = userCopy();

        DiscoveryFile file = DiscoveryFile.publishTo(gameCopy, userCopy, "127.0.0.1", 25585);
        assertTrue(Files.exists(gameCopy) && Files.exists(userCopy));

        file.remove();

        assertFalse(Files.exists(gameCopy));
        assertFalse(Files.exists(userCopy));
    }

    @Test
    @DisplayName("写不进去不影响另一半，也不抛异常 —— 发现文件只是便利，不能连累服务")
    void unwritablePathFailsQuietly() throws Exception {
        // 用一个**文件**当父目录：createDirectories 必然失败，模拟磁盘/权限问题
        Path blocker = Files.writeString(tempDir.resolve("blocker"), "x");
        Path impossible = blocker.resolve("bridge.json");
        Path writable = userCopy();

        assertDoesNotThrow(() -> DiscoveryFile.publishTo(impossible, writable, "127.0.0.1", 25585));
        assertTrue(Files.isRegularFile(writable), "另一份应照常写出去");
    }
}
