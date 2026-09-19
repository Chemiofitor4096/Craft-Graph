package dev.craftgraph.client;

import com.mojang.logging.LogUtils;
import dev.craftgraph.CraftGraph;
import dev.craftgraph.api.Models;
import dev.craftgraph.bridge.BridgeConfig;
import dev.craftgraph.bridge.BridgeHttpServer;
import dev.craftgraph.bridge.BridgeInfo;
import dev.craftgraph.bridge.BridgeService;
import dev.craftgraph.bridge.DiscoveryFile;
import dev.craftgraph.bridge.MainThreadDispatcher;
import dev.craftgraph.extract.FieldCoverage;
import dev.craftgraph.extract.RecipeExtractor;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 客户端桥接的生命周期。
 *
 * <h2>启动时机</h2>
 *
 * HTTP 服务在 **Mod 构造时**就启动，不等进入世界。理由：
 * {@code /health} 按协议必须任何时刻都能响应，MCP Server 靠它判断游戏在不在；
 * 而且这样用户在启动器里点了「开始游戏」之后，AI 客户端就已经能连上了，
 * 不用等世界加载完才发现连不上。
 *
 * 此时 {@code /health} 返回 {@code ready: false}，其他端点返回 503 —— 这是刻意的。
 *
 * <h2>重建时机</h2>
 *
 * {@link RecipesUpdatedEvent} 在客户端配方同步完成（以及 {@code /reload}、
 * 数据包重载、KJS 注入之后）触发。每次重建都会让 {@code dataVersion} +1，
 * MCP Server 靠它判断缓存失效。
 *
 * <h2>抽取在主线程，建索引在后台</h2>
 *
 * 遍历 RecipeManager 只能在主线程；建索引是纯计算，放后台线程，
 * 主线程只负责「把数据捞出来交出去」这一下。
 */
public final class ClientBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final BridgeConfig config;
    private final BridgeService service;
    private final MainThreadDispatcher dispatcher;
    private final ExecutorService indexExecutor;

    private BridgeHttpServer server;
    private DiscoveryFile discovery;
    private volatile boolean rebuildInFlight;

    private ClientBridge(BridgeConfig config) {
        this.config = config;
        this.dispatcher = new MainThreadDispatcher(Minecraft.getInstance(), 5000);
        this.indexExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "craftgraph-index"));
        this.service = new BridgeService(collectInfo(), indexExecutor);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        // 守护线程：游戏退出时不要因为这些线程还活着而卡住进程
        t.setDaemon(true);
        return t;
    }

    /**
     * 启动桥接。绑定失败**不静默降级** —— 端口被占时直接报错，
     * 因为静默换端口会让 MCP Server 找不到，症状变成「明明启动了却连不上」，
     * 比直接报错难查得多。
     */
    public static ClientBridge start(BridgeConfig config) {
        ClientBridge bridge = new ClientBridge(config);
        try {
            bridge.discovery = DiscoveryFile.publish("127.0.0.1", config.port());
            bridge.server = new BridgeHttpServer(config, bridge.service, bridge.discovery.token());
            bridge.server.start();

            // 日志里打**实际绑定的地址**，不是配置里的值。
            // 「配置写 25585 所以日志也说 25585」这种自说自话会掩盖绑错地址的情况。
            InetSocketAddress bound = bridge.server.boundAddress();
            LOGGER.info("CraftGraph bridge 已启动：{}（等待进入世界后加载配方）", bound);

            selfCheck(bound);
        } catch (IOException e) {
            LOGGER.error("CraftGraph bridge 启动失败（端口 {} 可能被占用）：{}", config.port(), e.getMessage());
            bridge.shutdown();
            return null;
        }

        // 立刻注册事件监听
        NeoForge.EVENT_BUS.addListener(bridge::onRecipesUpdated);
        NeoForge.EVENT_BUS.addListener(bridge::onLoggingOut);

        // 用 JVM 关闭钩子而不是游戏事件：NeoForge 的关闭事件不是所有退出路径都会触发
        // （崩溃、被启动器强杀等），而钩子在正常终止时一定会跑。
        // 这很重要 —— 残留的发现文件会让 MCP Server 以为游戏还在运行，
        // 然后一直连接超时，报错信息还指向一个「看起来没问题」的地址。
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::shutdown, "craftgraph-shutdown"));
        return bridge;
    }

    /**
     * 启动后立刻从 JVM 内部连一次自己的 {@code /health}。
     *
     * <h2>为什么值得做</h2>
     *
     * 「curl 连不上」有好几种完全不同的原因，从外部看症状一模一样：
     *
     * <ul>
     *   <li>服务压根没起来（代码问题）</li>
     *   <li>绑到了 ::1 而不是 127.0.0.1（代码问题，而且 bind 本身不报错）</li>
     *   <li>端口被别的程序占了，绑到了别处</li>
     *   <li>服务好好的，但外部连接被防火墙拦了（环境问题，不怪代码）</li>
     * </ul>
     *
     * 自己连自己一次就能把前三种和第四种分开：<b>自检通过 = 服务在本机确实可访问，
     * 那么外部连不上就是环境问题</b>。省掉一轮「是不是你代码写错了」的来回排查。
     *
     * 放后台线程做，不阻塞客户端初始化；3 秒超时，失败也不会挂住游戏。
     */
    private static void selfCheck(InetSocketAddress bound) {
        Thread t = new Thread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + bound.getPort() + "/health"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                LOGGER.info("CraftGraph 自检通过：本机 http://127.0.0.1:{} 可访问（HTTP {}）。"
                                + "若外部 curl 仍连不上，问题不在服务本身，而在防火墙。",
                        bound.getPort(), response.statusCode());
            } catch (Throwable e) {
                LOGGER.error("CraftGraph 自检失败：服务已绑定到 {}，但从本机连不上。"
                                + "问题在服务侧而不是环境 —— 检查绑定地址是否为 127.0.0.1（IPv4）。", bound, e);
            }
        }, "craftgraph-selfcheck");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 事件

    private void onRecipesUpdated(RecipesUpdatedEvent event) {
        rebuild(event.getRecipeManager());
    }

    /**
     * 断开连接时清空快照。
     *
     * <p>必须清 —— 否则 {@code /health} 会继续报 {@code ready: true}，
     * 而 MCP Server 会拿着上一个世界（或上一个服务器）的配方回答新问题。
     * 玩家切换存档时看到「配方对不上」，原因就在这里。
     */
    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (service.isReady()) {
            LOGGER.info("CraftGraph 断开连接，清空配方快照");
            service.clear();
        }
    }

    // ---------------------------------------------------------------- 重建

    /**
     * 重建快照。必须在主线程调用（会遍历 RecipeManager 和注册表）。
     */
    public void rebuild(RecipeManager manager) {
        if (manager == null) return;
        if (rebuildInFlight) {
            // 上一次还在建 —— 直接跳过。配方重载事件可能连续触发多次，
            // 排队等没意义（每次都会覆盖前一次的结果），而且会堆积主线程任务。
            LOGGER.debug("CraftGraph 上一次重建还没完成，跳过这次");
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        rebuildInFlight = true;
        long start = System.nanoTime();
        try {
            HolderLookup.Provider registries = mc.getConnection().registryAccess();

            RecipeExtractor extractor = RecipeExtractor.create(registries);
            List<Models.Recipe> recipes = extractor.extractAll(manager);
            Map<String, List<String>> tags = collectTags(registries);
            Map<String, List<String>> registriesById = collectRegistries(registries);
            Map<String, String> names = collectDisplayNames();

            long extractMillis = (System.nanoTime() - start) / 1_000_000;

            long opaque = recipes.stream().filter(Models.Recipe::opaque).count();

            service.publish(recipes, tags, registriesById, names).whenComplete((snap, err) -> {
                rebuildInFlight = false;
                if (err != null) {
                    LOGGER.error("CraftGraph 建索引失败：{}", err.getMessage());
                    return;
                }
                // 耗时必须打出来：主线程占用多少直接决定玩家有没有感觉到卡顿。
                // 抽取超过 ~200ms 就该考虑分帧切片了 —— 但先测量再优化。
                LOGGER.info("CraftGraph 快照已重建：{} 条配方（其中 {} 条读不懂，{}%），"
                                + "{} 个标签，dataVersion={}；主线程抽取 {} ms，建索引 {} ms",
                        snap.recipeCount(),
                        opaque,
                        snap.recipeCount() == 0 ? 0 : Math.round(opaque * 100.0 / snap.recipeCount()),
                        snap.tagCount(),
                        snap.dataVersion(),
                        extractMillis,
                        snap.buildMillis());

                // getResultItem 抛异常的次数单独报。正常应为 0。
                // 之前这个异常被静默吞掉，导致「传了 null 注册表」这个 bug
                // 伪装成「Minecraft 的限制」藏了很久 —— 所以失败必须有声音。
                if (extractor.resultItemFailures() > 0) {
                    LOGGER.warn("CraftGraph 有 {} 条配方的 getResultItem 抛了异常，"
                                    + "它们的产出会被当作读不到。这是个 bug 信号，不是正常情况。",
                            extractor.resultItemFailures());
                }

                if (extractor.toastSymbolFailures() > 0) {
                    LOGGER.warn("CraftGraph 有 {} 条配方的 getToastSymbol 抛了异常，"
                                    + "这些配方的机器会推断不出来。这是个 bug 信号，不是正常情况。",
                            extractor.toastSymbolFailures());
                }

                // 字段覆盖度。duration / machine 曾经在真实数据里**全是 null**
                // 而四个测试层都没发现（夹具里手写了 duration），
                // 代价是产线计算的机器数整个失效了很久。所以这两个字段的覆盖度必须每次都报出来。
                FieldCoverage coverage = extractor.coverage();
                LOGGER.info("CraftGraph 字段覆盖度：{}", coverage.summary());
                if (coverage.withDuration() > 0) {
                    LOGGER.info("CraftGraph 带耗时数据的配方类型：{}",
                            String.join("、", coverage.typesWithDuration()));
                }

                // 「本该有耗时却没读到」= 适配器没生效，不是数据缺失。
                // 这条判据与适配器实现无关（写死的是 Minecraft 的事实），所以它抓的是真问题。
                for (String broken : coverage.brokenTypes()) {
                    LOGGER.warn("CraftGraph 字段覆盖度异常：{}。这些类型的耗时是平台保证有的，"
                                    + "读到 null 更可能是解析出了问题而不是数据缺失 —— "
                                    + "产线计算会把它们当成手工。", broken);
                }

                if (opaque > 0) {
                    LOGGER.info("CraftGraph 读不懂的配方占比 {}%。想看具体是哪些配方类型、"
                                    + "是物理限制还是解析问题，运行 npm run inspect。",
                            Math.round(opaque * 100.0 / Math.max(1, snap.recipeCount())));
                }
            });
        } catch (Throwable t) {
            rebuildInFlight = false;
            // 必须捕获 Throwable：主线程上逃逸出去的异常会把游戏搞崩，
            // 而「配方读不出来」远没有「游戏崩了」严重。
            LOGGER.error("CraftGraph 重建配方快照时出错", t);
        }
    }

    // ---------------------------------------------------------------- 数据收集

    /** 收集物品和流体标签。建倒排索引要用它把标签展开成具体物品。 */
    private static Map<String, List<String>> collectTags(HolderLookup.Provider registries) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        collectTagsFor(registries, Registries.ITEM, out);
        collectTagsFor(registries, Registries.FLUID, out);
        return out;
    }

    private static <T> void collectTagsFor(
            HolderLookup.Provider registries,
            ResourceKey<? extends Registry<T>> key,
            Map<String, List<String>> out) {
        HolderLookup.RegistryLookup<T> lookup = registries.lookupOrThrow(key);
        lookup.listTags().forEach(namedSet -> {
            List<String> members = new ArrayList<>();
            namedSet.forEach(holder -> holder.unwrapKey()
                    .ifPresent(k -> members.add(k.location().toString())));
            if (!members.isEmpty()) {
                out.put(namedSet.key().location().toString(), members);
            }
        });
    }

    /**
     * 收集注册表 id 列表。
     *
     * <p>全量收集是有意的：{@code /registry/{kind}} 和 MCP Server 的
     * itemName 映射都依赖它。注册表本身不大（原版几千项），放进快照没问题。
     */
    private static Map<String, List<String>> collectRegistries(HolderLookup.Provider registries) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("items", idsOf(BuiltInRegistries.ITEM));
        out.put("blocks", idsOf(BuiltInRegistries.BLOCK));
        out.put("fluids", idsOf(BuiltInRegistries.FLUID));
        out.put("entities", idsOf(BuiltInRegistries.ENTITY_TYPE));
        return out;
    }

    private static <T> List<String> idsOf(Registry<T> registry) {
        List<String> out = new ArrayList<>(registry.size());
        for (ResourceLocation id : registry.keySet()) {
            out.add(id.toString());
        }
        return out;
    }

    /** 显示名。中文语言包下会是「铁锭」而不是 minecraft:iron_ingot，报告的易读性靠它。 */
    private static Map<String, String> collectDisplayNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            try {
                var item = BuiltInRegistries.ITEM.get(id);
                if (item != null) {
                    out.put(id.toString(), item.getDescription().getString());
                }
            } catch (Throwable t) {
                // 个别模组物品取名字可能抛异常，不能因此丢掉整张表
            }
        }
        return out;
    }

    private static BridgeInfo collectInfo() {
        String modVersion = ModList.get().getModContainerById(CraftGraph.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        // ⚠️ 不能用 Minecraft.getInstance().getLaunchedVersion()。
        // 它返回的是启动参数里的 --version，而在开发环境（runClient）下那个值是
        // NeoForge 的版本号（21.1.251），不是 Minecraft 版本 —— 实测就报成了
        // "mcVersion":"21.1.251"。某些第三方启动器也会这样传参。
        String mcVersion = SharedConstants.getCurrentVersion().getName();
        String loader = NeoForgeVersion.getVersion();

        return new BridgeInfo(
                modVersion,
                mcVersion,
                "neoforge-" + loader,
                modVersion("emi"),
                modVersion("jei"),
                System.currentTimeMillis());
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(null);
    }

    // ---------------------------------------------------------------- 关闭

    public void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (discovery != null) {
            // 留着过期的发现文件会让 MCP Server 以为游戏还在运行，然后连接超时
            discovery.remove();
            discovery = null;
        }
        indexExecutor.shutdownNow();
        try {
            indexExecutor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public BridgeService service() {
        return service;
    }
}
