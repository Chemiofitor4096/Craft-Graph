package dev.craftgraph.bridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.craftgraph.api.Json;
import dev.craftgraph.api.Models;
import dev.craftgraph.snapshot.RecipeSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地只读 HTTP 服务，接口约定见 doc/protocol.md。
 *
 * <h2>安全</h2>
 *
 * <ul>
 *   <li><b>只监听回环地址</b>，不监听 0.0.0.0。局域网里访问不到。</li>
 *   <li><b>除 /health 外都要求 Bearer token</b>。不校验的话，任何本地进程、
 *       甚至任何网页里的 {@code fetch('http://localhost:25585/...')} 都能读你的游戏数据。
 *       浏览器会被 CORS 拦住读取响应，但那依赖于浏览器守规矩 —— 本地进程可不会。</li>
 *   <li><b>不发 CORS 头</b>，让浏览器按默认策略拦截跨域读取。</li>
 *   <li><b>只读</b>。唯一的写操作 POST /reload 默认关闭。</li>
 * </ul>
 *
 * <h2>线程</h2>
 *
 * 请求由固定大小的工作线程池处理。**所有数据都来自不可变的 {@link RecipeSnapshot}**，
 * 所以这个类完全不碰游戏对象，也就不需要主线程派发 ——
 * 这是「快照」设计带来的直接好处。唯一会碰主线程的只有 POST /reload，它走 dispatcher。
 */
public final class BridgeHttpServer {

    /** 与 doc/protocol.md 的 protocolVersion 对应。破坏性改动时 +1。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 单次响应上限保护。快照分页由调用方控制，这里只是兜底。 */
    private static final int MAX_LIMIT = 2000;

    private final BridgeConfig config;
    private final BridgeDataSource source;
    private final String token;
    private final long startedAtMillis = System.currentTimeMillis();

    /** POST /reload 用；为 null 表示不支持该端点。 */
    private final MainThreadDispatcher dispatcher;
    private final Runnable reloadAction;

    private HttpServer server;
    private ExecutorService executor;

    public BridgeHttpServer(BridgeConfig config, BridgeDataSource source, String token) {
        this(config, source, token, null, null);
    }

    public BridgeHttpServer(BridgeConfig config, BridgeDataSource source, String token,
                            MainThreadDispatcher dispatcher, Runnable reloadAction) {
        this.config = config;
        this.source = source;
        this.token = token;
        this.dispatcher = dispatcher;
        this.reloadAction = reloadAction;
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 启动服务。绑定失败（端口被占）会抛异常 —— 由调用方决定是记录日志还是让游戏崩。
     * 这里的取舍是：**不静默降级到随机端口**，因为那样 MCP Server 就找不到了，
     * 症状会变成「明明启动了却连不上」，比直接报错难查得多。
     */
    public void start() throws IOException {
        // ⚠️ 显式绑 IPv4 回环，**不要用 InetAddress.getLoopbackAddress()**。
        //
        // 后者是「实现定义」的：在有 IPv6 的 Windows 上它会返回 ::1。
        // 而发现文件里写的是 127.0.0.1，MCP Server 和 curl 也都连 127.0.0.1 ——
        // 绑到 ::1 就谁都连不上。更糟的是 **bind 本身不报错**，
        // 日志看起来一切正常，症状只是「连不上」，极难定位。
        //
        // 用字面量 "127.0.0.1" 而不是主机名，避免任何 DNS 解析参与进来。
        InetSocketAddress bindAddress = new InetSocketAddress("127.0.0.1", config.port());
        server = HttpServer.create(bindAddress, 0);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "craftgraph-bridge-http");
            // 守护线程：游戏退出时不要因为这个线程还活着而卡住进程
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handleSafely);
        server.start();
    }

    /** 实际绑定的地址。日志和自检要用它，而不是拿配置里的值当结果。 */
    public InetSocketAddress boundAddress() {
        return server != null ? server.getAddress() : null;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public int port() {
        return server != null ? server.getAddress().getPort() : config.port();
    }

    // ---------------------------------------------------------------- 请求处理

    private void handleSafely(HttpExchange exchange) {
        try {
            handle(exchange);
        } catch (Throwable t) {
            // 任何未预料的异常都不能让请求悬着 —— 那样调用方会一直等到超时。
            // 必须捕获 Throwable 而不是 Exception：本层是最后一道防线。
            try {
                error(exchange, 500, "INTERNAL", "服务内部错误：" + t);
            } catch (IOException ignored) {
                // 连错误响应都发不出去，只能放弃
            }
        } finally {
            exchange.close();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        // ⚠️ 必须用 getRawPath()，不能用 getPath()。
        // 标签 id 里本身就含斜杠（如 forge:ingots/iron），客户端会把它编码成 %2F。
        // getPath() 会先解码再交给我们，于是 "/tags/items/forge%3Aingots%2Firon"
        // 变成 "/tags/items/forge:ingots/iron"，按 / 切分就成了 4 段，路由永远匹配不上。
        // 正确做法：按原始路径切段，再逐段解码。
        String[] seg = splitPath(exchange.getRequestURI().getRawPath());
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if (!"GET".equals(method) && !"POST".equals(method)) {
            error(exchange, 405, "METHOD_NOT_ALLOWED", "只支持 GET（以及可选的重载用 POST）");
            return;
        }

        // /health 不需要 token：MCP Server 靠它判断游戏在不在，
        // 而 token 可能还没从发现文件里读到。它也不含任何用户数据。
        if (seg.length == 1 && "health".equals(seg[0])) {
            health(exchange);
            return;
        }

        if (!isAuthorized(exchange)) {
            error(exchange, 401, "UNAUTHORIZED", "缺少或错误的 Authorization: Bearer <token>");
            return;
        }

        if (seg.length == 1 && "reload".equals(seg[0])) {
            reload(exchange, method);
            return;
        }

        if (!"GET".equals(method)) {
            error(exchange, 405, "METHOD_NOT_ALLOWED", "/" + String.join("/", seg) + " 只支持 GET");
            return;
        }

        route(exchange, seg, query);
    }

    /** 路由。段数多的分支必须排在前面，否则 /tags/items/all 会被 /tags/{kind}/{id} 抢走。 */
    private void route(HttpExchange exchange, String[] seg, Map<String, String> query) throws IOException {
        if (seg.length == 1 && "snapshot".equals(seg[0])) {
            snapshot(exchange, query);
            return;
        }
        if (seg.length == 1 && "recipes".equals(seg[0])) {
            recipes(exchange, query);
            return;
        }
        if (seg.length == 2 && "recipes".equals(seg[0])) {
            recipeById(exchange, seg[1]);
            return;
        }
        if (seg.length == 2 && "registry".equals(seg[0])) {
            registry(exchange, seg[1], query);
            return;
        }
        // /tags/{kind}/all 必须在 /tags/{kind}/{id} 之前判断
        if (seg.length == 3 && "tags".equals(seg[0]) && "all".equals(seg[2])) {
            tagsAll(exchange, seg[1]);
            return;
        }
        if (seg.length == 2 && "tags".equals(seg[0])) {
            tags(exchange, seg[1]);
            return;
        }
        if (seg.length == 3 && "tags".equals(seg[0])) {
            tagExpansion(exchange, seg[1], seg[2]);
            return;
        }

        error(exchange, 404, "NOT_FOUND", "没有这个端点：/" + String.join("/", seg));
    }

    // ---------------------------------------------------------------- 各端点

    private void health(HttpExchange exchange) throws IOException {
        RecipeSnapshot snap = source.snapshot();
        BridgeInfo info = source.info();

        Models.Health body = new Models.Health(
                true,
                snap != null,
                PROTOCOL_VERSION,
                info.modVersion(),
                info.mcVersion(),
                info.loader(),
                info.emiVersion() == null ? null : new Models.ModPresence(info.emiVersion()),
                info.jeiVersion() == null ? null : new Models.ModPresence(info.jeiVersion()),
                snap == null ? 0 : snap.recipeCount(),
                snap == null ? 0 : snap.registryIds("items").size(),
                snap == null ? 0 : snap.dataVersion(),
                System.currentTimeMillis() - startedAtMillis);

        send(exchange, 200, body);
    }

    private void snapshot(HttpExchange exchange, Map<String, String> query) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        int cursor = intParam(query, "cursor", 0);
        int limit = clamp(intParam(query, "limit", 500), 1, MAX_LIMIT);
        send(exchange, 200, snap.snapshotPage(cursor, limit));
    }

    private void recipes(HttpExchange exchange, Map<String, String> query) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        int offset = intParam(query, "offset", 0);
        int limit = clamp(intParam(query, "limit", 100), 1, MAX_LIMIT);
        send(exchange, 200, snap.queryRecipes(
                query.get("output"), query.get("input"), query.get("type"), offset, limit));
    }

    private void recipeById(HttpExchange exchange, String id) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        Models.Recipe r = snap.recipe(id);
        if (r == null) {
            error(exchange, 404, "NOT_FOUND", "配方不存在：" + id);
            return;
        }
        send(exchange, 200, r);
    }

    private void registry(HttpExchange exchange, String kind, Map<String, String> query) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        if (snap.registryIds(kind).isEmpty()) {
            error(exchange, 404, "NOT_FOUND", "没有这个注册表：" + kind);
            return;
        }
        int offset = intParam(query, "offset", 0);
        int limit = clamp(intParam(query, "limit", 100), 1, MAX_LIMIT);
        send(exchange, 200, snap.registry(kind, query.get("query"), offset, limit));
    }

    private void tags(HttpExchange exchange, String kind) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        List<Models.TagListEntry> entries = snap.tagList(kind);
        send(exchange, 200, new Models.TagListPage(kind, entries.size(), entries));
    }

    private void tagsAll(HttpExchange exchange, String kind) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        // 这个端点是给 MCP Server 建倒排索引用的一次性批量拉取。
        // 没有它就得逐个标签发请求，大整合包是上千次往返。
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (Models.TagListEntry e : snap.tagList(kind)) {
            List<String> members = snap.expandTag(e.id());
            if (members != null) all.put(e.id(), members);
        }
        send(exchange, 200, new Models.TagAllPage(kind, all));
    }

    private void tagExpansion(HttpExchange exchange, String kind, String tagId) throws IOException {
        RecipeSnapshot snap = requireReady(exchange);
        if (snap == null) return;

        List<String> members = snap.expandTag(tagId);
        if (members == null) {
            error(exchange, 404, "NOT_FOUND", "标签不存在：" + tagId);
            return;
        }
        send(exchange, 200, new Models.TagExpansion(tagId, members));
    }

    private void reload(HttpExchange exchange, String method) throws IOException {
        if (!config.exposeReloadEndpoint() || dispatcher == null || reloadAction == null) {
            error(exchange, 404, "NOT_FOUND",
                    "POST /reload 未启用。它是唯一的写操作，需要在 Mod 配置里显式开启。");
            return;
        }
        if (!"POST".equals(method)) {
            error(exchange, 405, "METHOD_NOT_ALLOWED", "/reload 只支持 POST");
            return;
        }
        // 重载必须回主线程：它会读游戏状态
        dispatcher.supply(() -> {
            reloadAction.run();
            return true;
        }).whenComplete((ok, err) -> {
            try {
                if (err != null) {
                    error(exchange, 500, "INTERNAL", "重载失败：" + err.getMessage());
                } else {
                    send(exchange, 200, Map.of("ok", true));
                }
            } catch (IOException ignored) {
                // 响应发不出去只能放弃
            }
        });
    }

    /** 数据还没准备好时统一返回 503 + Retry-After，让调用方知道该重试而不是放弃。 */
    private RecipeSnapshot requireReady(HttpExchange exchange) throws IOException {
        RecipeSnapshot snap = source.snapshot();
        if (snap == null) {
            exchange.getResponseHeaders().set("Retry-After", "2");
            error(exchange, 503, "NOT_READY", "配方数据还在加载中，请稍后重试");
        }
        return snap;
    }

    // ---------------------------------------------------------------- 鉴权

    private boolean isAuthorized(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Authorization");
        if (headers == null || headers.isEmpty()) return false;

        String value = headers.get(0);
        String prefix = "Bearer ";
        if (value == null || !value.startsWith(prefix)) return false;

        String presented = value.substring(prefix.length()).trim();
        // 常量时间比较：避免通过响应耗时逐字节猜 token。
        // 本机场景下有点过度，但成本近乎为零。
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 工具

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // 刻意不设 Access-Control-Allow-Origin：让浏览器按默认策略拦住跨域读取
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, Models.ErrorBody.of(code, message));
    }

    /**
     * 按 {@code /} 切分原始路径，并**逐段解码**。
     *
     * 逐段解码是关键：整体解码会把编码过的 {@code %2F} 变成真斜杠，
     * 于是标签 id（{@code forge:ingots/iron}）里的斜杠被当成路径分隔符，路由就匹配不上了。
     */
    private static String[] splitPath(String rawPath) {
        List<String> out = new ArrayList<>(4);
        for (String s : rawPath.split("/")) {
            if (!s.isEmpty()) out.add(decode(s));
        }
        return out.toArray(new String[0]);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(decode(pair), "");
            else out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    /** 参数不合法时用默认值而不是报错 —— 这是只读查询，宽容比严格更有用。 */
    private static int intParam(Map<String, String> query, String key, int fallback) {
        String raw = query.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
