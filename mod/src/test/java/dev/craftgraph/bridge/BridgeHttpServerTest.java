package dev.craftgraph.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftgraph.api.Models;
import dev.craftgraph.snapshot.RecipeSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 层的端到端测试：真的起一个服务，真的发 HTTP 请求。
 *
 * 不需要 Minecraft —— {@link BridgeHttpServer} 只依赖 {@link BridgeDataSource}，
 * 这里给它一个假实现。于是路由、鉴权、错误码、分页这些容易写错的地方
 * 全部可以在两秒内验完。
 */
class BridgeHttpServerTest {

    private static final String TOKEN = "test-token-abc123";

    private static BridgeHttpServer server;
    private static HttpClient client;
    private static String base;

    /** 可控的数据源：测试里随时切换「已就绪」和「未就绪」。 */
    private static final class FakeSource implements BridgeDataSource {
        volatile RecipeSnapshot snapshot;

        @Override
        public RecipeSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public BridgeInfo info() {
            return new BridgeInfo("0.1.0-test", "1.21.1", "neoforge-21.1.251", null, null, System.currentTimeMillis());
        }
    }

    private static final FakeSource source = new FakeSource();

    @BeforeAll
    static void startServer() throws IOException {
        // 端口 0 = 让系统分配，避免测试之间抢端口
        server = new BridgeHttpServer(new BridgeConfig(0, false), source, TOKEN);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    // ---------------------------------------------------------------- 请求助手

    private static HttpResponse<String> get(String path) throws Exception {
        return get(path, TOKEN);
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> res) {
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static JsonObject errorOf(HttpResponse<String> res) {
        return json(res).getAsJsonObject("error");
    }

    private static Models.ItemStack item(String id, int count) {
        return new Models.ItemStack(id, count, null);
    }

    private static Models.Recipe recipe(String id, String type, List<Models.Ingredient> inputs, List<Models.ItemStack> outputs) {
        return new Models.Recipe(id, type, null, inputs, outputs, List.of(), List.of(), null, 200, null, "vanilla", false);
    }

    private static RecipeSnapshot readySnapshot() {
        return RecipeSnapshot.builder(3)
                .tags(Map.of("forge:ingots/iron", List.of("minecraft:iron_ingot")))
                .registry("items", List.of("minecraft:iron_ingot", "minecraft:iron_block"))
                .addRecipe(recipe("test:block", "minecraft:crafting",
                        List.of(new Models.Ingredient("item", 9, List.of(Models.Option.tag("forge:ingots/iron")))),
                        List.of(item("minecraft:iron_block", 1))))
                .addRecipe(recipe("test:ingot", "minecraft:smelting",
                        List.of(new Models.Ingredient("item", 1, List.of(Models.Option.item("minecraft:iron_ore")))),
                        List.of(item("minecraft:iron_ingot", 1))))
                .build();
    }

    // ---------------------------------------------------------------- 未就绪

    @Test
    @DisplayName("未就绪时：/health 能返回且 ready=false（不能返回 503）")
    void healthWorksBeforeReady() throws Exception {
        source.snapshot = null;
        HttpResponse<String> res = get("/health", null); // 不带 token
        assertEquals(200, res.statusCode(), res.body());

        JsonObject body = json(res);
        assertTrue(body.get("ok").getAsBoolean(), "服务活着就是 ok=true");
        assertFalse(body.get("ready").getAsBoolean(), "数据没准备好时 ready 必须为 false");
        assertEquals(0, body.get("recipeCount").getAsInt());
    }

    @Test
    @DisplayName("未就绪时：其他端点返回 503 + Retry-After，而不是空结果")
    void notReadyReturns503() throws Exception {
        source.snapshot = null;
        HttpResponse<String> res = get("/snapshot");
        assertEquals(503, res.statusCode(), res.body());
        assertEquals("NOT_READY", errorOf(res).get("code").getAsString());
        assertNotNull(res.headers().firstValue("Retry-After").orElse(null),
                "503 必须带 Retry-After，否则调用方不知道该重试");

        // 关键：不能返回「200 + 空列表」，那会让调用方建出一个空缓存，
        // 表现为「查什么都说没有」而不是「还在加载」
        assertEquals(503, get("/recipes").statusCode());
        assertEquals(503, get("/registry/items").statusCode());
    }

    // ---------------------------------------------------------------- 鉴权

    @Test
    @DisplayName("鉴权：不带 token 被拒")
    void rejectsMissingToken() throws Exception {
        source.snapshot = readySnapshot();
        HttpResponse<String> res = get("/recipes", null);
        assertEquals(401, res.statusCode(), res.body());
        assertEquals("UNAUTHORIZED", errorOf(res).get("code").getAsString());
    }

    @Test
    @DisplayName("鉴权：token 错误被拒")
    void rejectsWrongToken() throws Exception {
        source.snapshot = readySnapshot();
        assertEquals(401, get("/recipes", "wrong-token").statusCode());
        assertEquals(401, get("/recipes", "").statusCode());
        assertEquals(401, get("/snapshot", "test-token-abc12").statusCode(), "长度不同的 token 也要拒");
    }

    @Test
    @DisplayName("鉴权：格式不对的 Authorization 头被拒")
    void rejectsMalformedAuthHeader() throws Exception {
        source.snapshot = readySnapshot();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/recipes"))
                .header("Authorization", "Basic " + TOKEN).GET().build();
        assertEquals(401, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    @DisplayName("安全：不发 CORS 头（让浏览器拦住跨域读取）")
    void doesNotSendCorsHeaders() throws Exception {
        source.snapshot = readySnapshot();
        HttpResponse<String> res = get("/recipes");
        assertNull(res.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
                "发了 CORS 头的话，任何网页都能读你的游戏数据");
    }

    // ---------------------------------------------------------------- 路由

    @Test
    @DisplayName("路由：/tags/{kind}/all 不被 /tags/{kind}/{tagId} 抢走")
    void tagsAllIsNotSwallowedByTagExpansion() throws Exception {
        source.snapshot = readySnapshot();
        HttpResponse<String> res = get("/tags/items/all");
        assertEquals(200, res.statusCode(), "all 被当成标签名了：" + res.body());

        JsonObject body = json(res);
        assertEquals("items", body.get("kind").getAsString());
        assertTrue(body.getAsJsonObject("tags").has("forge:ingots/iron"), res.body());
    }

    @Test
    @DisplayName("路由：未知路径返回 404")
    void unknownPathReturns404() throws Exception {
        source.snapshot = readySnapshot();
        HttpResponse<String> res = get("/nonexistent");
        assertEquals(404, res.statusCode());
        assertEquals("NOT_FOUND", errorOf(res).get("code").getAsString());
    }

    @Test
    @DisplayName("路由：不支持的 HTTP 方法返回 405")
    void wrongMethodReturns405() throws Exception {
        source.snapshot = readySnapshot();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/recipes"))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE().build();
        assertEquals(405, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    // ---------------------------------------------------------------- 端点行为

    @Test
    @DisplayName("/health 就绪后带正确的计数与版本")
    void healthAfterReady() throws Exception {
        source.snapshot = readySnapshot();
        JsonObject body = json(get("/health", null));

        assertTrue(body.get("ready").getAsBoolean());
        assertEquals(1, body.get("protocolVersion").getAsInt());
        assertEquals("0.1.0-test", body.get("modVersion").getAsString());
        assertEquals("1.21.1", body.get("mcVersion").getAsString());
        assertEquals(2, body.get("recipeCount").getAsInt());
        assertEquals(3, body.get("dataVersion").getAsInt());
        assertTrue(body.get("emi").isJsonNull(), "未装 EMI 时该字段必须是 null 而不是缺失");
    }

    @Test
    @DisplayName("/recipes 按产出过滤")
    void recipesFilterByOutput() throws Exception {
        source.snapshot = readySnapshot();
        JsonObject body = json(get("/recipes?output=minecraft:iron_block"));
        assertEquals(1, body.get("total").getAsInt());
        JsonArray list = body.getAsJsonArray("recipes");
        assertEquals("test:block", list.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:iron_block",
                list.get(0).getAsJsonObject().getAsJsonObject("primaryOutput").get("item").getAsString());
    }

    @Test
    @DisplayName("/recipes 按输入过滤时标签已展开")
    void recipesFilterByInputExpandsTags() throws Exception {
        source.snapshot = readySnapshot();
        // test:block 的输入是标签 #forge:ingots/iron，用 iron_ingot 查必须能查到
        assertEquals(1, json(get("/recipes?input=minecraft:iron_ingot")).get("total").getAsInt());
    }

    @Test
    @DisplayName("/recipes/{id} 单条；不存在返回 404")
    void recipeById() throws Exception {
        source.snapshot = readySnapshot();
        assertEquals(200, get("/recipes/test:block").statusCode());
        assertEquals(404, get("/recipes/does:not_exist").statusCode());
    }

    @Test
    @DisplayName("/recipes/{id} 支持带冒号的 id")
    void recipeByIdWithColon() throws Exception {
        source.snapshot = readySnapshot();
        // 配方 id 里必然带命名空间冒号，路径编码不能把它吃掉
        JsonObject body = json(get("/recipes/test:ingot"));
        assertEquals("test:ingot", body.get("id").getAsString());
    }

    @Test
    @DisplayName("/snapshot 分页且最后一页 nextCursor 为 null")
    void snapshotPaging() throws Exception {
        source.snapshot = readySnapshot();
        JsonObject first = json(get("/snapshot?cursor=0&limit=1"));
        assertEquals(3, first.get("dataVersion").getAsInt(), "每页都要带 dataVersion，供调用方校验一致性");
        assertEquals(2, first.get("total").getAsInt());
        assertEquals(1, first.get("nextCursor").getAsInt());

        JsonObject last = json(get("/snapshot?cursor=1&limit=1"));
        assertTrue(last.get("nextCursor").isJsonNull(), "最后一页必须为 null，否则调用方无限循环");
    }

    @Test
    @DisplayName("/registry 未知 kind 返回 404")
    void registryUnknownKind() throws Exception {
        source.snapshot = readySnapshot();
        assertEquals(200, get("/registry/items").statusCode());
        assertEquals(404, get("/registry/nonsense").statusCode());
    }

    @Test
    @DisplayName("路由：/tags/{kind}/{tagId} 里的标签 id 含斜杠，必须按编码形式解析")
    void tagExpansion() throws Exception {
        source.snapshot = readySnapshot();
        // 标签 id 本身含斜杠（forge:ingots/iron），真实客户端会用 encodeURIComponent
        // 编成 forge%3Aingots%2Firon。如果服务端先整体解码再切分，斜杠会被当成分隔符，
        // 这里就会 404 —— 这个 bug 是被这条测试抓出来的。
        JsonObject body = json(get("/tags/items/forge%3Aingots%2Firon"));
        assertEquals(1, body.getAsJsonArray("entries").size(), body.toString());

        assertEquals(404, get("/tags/items/not%3Aa_tag").statusCode());
    }

    @Test
    @DisplayName("分页参数：非法值不报错，用默认值兜底而不是 500")
    void toleratesBadPagingParams() throws Exception {
        source.snapshot = readySnapshot();
        assertEquals(200, get("/recipes?offset=abc&limit=xyz").statusCode());
        assertEquals(200, get("/recipes?offset=-5&limit=0").statusCode());
        assertEquals(200, get("/snapshot?cursor=-1&limit=999999").statusCode());
    }

    @Test
    @DisplayName("POST /reload 默认关闭")
    void reloadDisabledByDefault() throws Exception {
        source.snapshot = readySnapshot();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/reload"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, res.statusCode(), "唯一的写操作默认必须关闭");
    }

    // ---------------------------------------------------------------- 绑定地址

    @Test
    @DisplayName("绑定的是 IPv4 回环 127.0.0.1，不是 ::1")
    void bindsToIpv4Loopback() throws Exception {
        // 这条测试是为了钉死一个真实踩过的坑：原本用 InetAddress.getLoopbackAddress()，
        // 它是「实现定义」的 —— 在有 IPv6 的环境下会返回 ::1。
        // 而发现文件里写的是 127.0.0.1，MCP Server 和 curl 也都连 127.0.0.1，
        // 于是谁都连不上。更糟的是 bind 本身不报错，日志看起来一切正常。
        InetSocketAddress bound = server.boundAddress();
        assertNotNull(bound, "服务已启动，绑定地址不该为 null");

        assertTrue(bound.getAddress() instanceof Inet4Address,
                "必须绑 IPv4，实际是 " + bound.getAddress());
        assertEquals("127.0.0.1", bound.getAddress().getHostAddress());

        // 再确认真的连得上（不只是地址看起来对）
        assertEquals(200, get("/health", null).statusCode());
    }

    @Test
    @DisplayName("绑定地址的端口与实际监听端口一致")
    void boundAddressReportsRealPort() throws Exception {
        // 端口 0 让系统分配，所以这里验的是「回读的是真实端口，不是配置里的 0」。
        // 如果日志打的是配置值而不是实际值，排查端口问题时会被误导。
        InetSocketAddress bound = server.boundAddress();
        assertEquals(server.port(), bound.getPort());
        assertTrue(bound.getPort() > 0, "端口 0 表示系统分配，回读必须是真实端口");
    }

    // ---------------------------------------------------------------- 配置校验

    @Test
    @DisplayName("配置：非法端口在构造时就报错，不留到运行时")
    void configRejectsInvalidPort() {
        try {
            new BridgeConfig(80, false);
            org.junit.jupiter.api.Assertions.fail("1024 以下是特权端口，应该被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("端口"), expected.getMessage());
        }
        try {
            new BridgeConfig(70000, false);
            org.junit.jupiter.api.Assertions.fail("超过 65535 应该被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        // 0 是允许的（临时端口，测试用）
        assertEquals(0, new BridgeConfig(0, false).port());
    }
}
