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
import dev.craftgraph.extract.RecipeAdapters;
import dev.craftgraph.extract.RecipeExtractor;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.versions.forge.ForgeVersion;
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
 * 客户端桥接的生命周期（**1.20.1 版**）。
 *
 * <h2>与 1.21.1 那份的差别</h2>
 *
 * 逻辑一模一样，只有 loader 与注册表 API 的形状不同：
 *
 * <ul>
 *   <li>事件总线是 {@code MinecraftForge.EVENT_BUS}（1.21.1 是 {@code NeoForge.EVENT_BUS}），
 *       事件类同名的在 {@code net.minecraftforge.client.event} 下。</li>
 *   <li>版本号从 {@code ForgeVersion} 取（1.21.1 是 {@code NeoForgeVersion}）。</li>
 *   <li>注册表访问是 {@code RegistryAccess}，标签枚举用
 *       {@code registry.getTagNames()} + {@code getTag(tagKey)}
 *       （1.21.1 是 {@code HolderLookup.Provider#listTags()}）。</li>
 * </ul>
 *
 * <h2>启动时机</h2>
 *
 * HTTP 服务在 **Mod 构造时**就启动，不等进入世界。理由：
 * {@code /health} 按协议必须任何时刻都能响应，MCP Server 靠它判断游戏在不在；
 * 而且这样用户在启动器里点了「开始游戏」之后，AI 客户端就已经能连上了。
 *
 * <p>此时 {@code /health} 返回 {@code ready: false}，其他端点返回 503 —— 这是刻意的。
 *
 * <h2>重建时机</h2>
 *
 * {@link RecipesUpdatedEvent} 在客户端配方同步完成（以及 {@code /reload}、
 * 数据包重载、KJS 注入之后）触发。每次重建都会让 {@code dataVersion} +1，
 * MCP Server 靠它判断缓存失效。
 *
 * <h2>抽取在主线程，建索引在后台</h2>
 *
 * 遍历 RecipeManager 只能在主线程；建索引是纯计算，放后台线程。
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
     * 因为静默换端口会让 MCP Server 找不到，症状变成「明明启动了却连不上」。
     */
    public static ClientBridge start(BridgeConfig config) {
        ClientBridge bridge = new ClientBridge(config);
        try {
            bridge.discovery = DiscoveryFile.publish(FMLPaths.GAMEDIR.get(), "127.0.0.1", config.port());
            bridge.server = new BridgeHttpServer(config, bridge.service, bridge.discovery.token());
            bridge.server.start();

            // 日志里打**实际绑定的地址**，不是配置里的值。
            InetSocketAddress bound = bridge.server.boundAddress();
            LOGGER.info("CraftGraph bridge started on {} (recipes load once you enter a world)", bound);

            selfCheck(bound);
        } catch (IOException e) {
            LOGGER.error("CraftGraph bridge failed to start (port {} may be in use): {}",
                    config.port(), e.getMessage());
            bridge.shutdown();
            return null;
        }

        // 立刻注册事件监听
        MinecraftForge.EVENT_BUS.addListener(bridge::onRecipesUpdated);
        MinecraftForge.EVENT_BUS.addListener(bridge::onLoggingOut);

        // 用 JVM 关闭钩子而不是游戏事件：关闭事件不是所有退出路径都会触发
        // （崩溃、被启动器强杀等），而钩子在正常终止时一定会跑。
        // 这很重要 —— 残留的发现文件会让 MCP Server 以为游戏还在运行。
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::shutdown, "craftgraph-shutdown"));
        return bridge;
    }

    /**
     * 启动后立刻从 JVM 内部连一次自己的 {@code /health}。
     *
     * <p>「curl 连不上」有好几种完全不同的原因，从外部看症状一模一样：
     * 服务没起来、绑到了 ::1、端口被别人占了、或者服务好好的但被防火墙拦了。
     * 自己连自己一次就能把前三种和第四种分开：<b>自检通过 = 服务在本机确实可访问，
     * 那么外部连不上就是环境问题</b>。
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
                LOGGER.info("CraftGraph self-check OK: http://127.0.0.1:{} reachable from this machine (HTTP {})."
                                + " If an external curl still cannot connect, the problem is the "
                                + "firewall, not the service.",
                        bound.getPort(), response.statusCode());
            } catch (Throwable e) {
                LOGGER.error("CraftGraph self-check FAILED: bound to {} but unreachable from this machine."
                                + " That points at the service, not the environment -- check the bind "
                                + "address is 127.0.0.1 (IPv4).", bound, e);
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
     */
    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (service.isReady()) {
            LOGGER.info("CraftGraph disconnected; recipe snapshot cleared");
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
            // 上一次还在建 —— 直接跳过。配方重载事件可能连续触发多次。
            LOGGER.debug("CraftGraph previous rebuild still running; skipping this one");
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        rebuildInFlight = true;
        long start = System.nanoTime();
        try {
            RegistryAccess registries = mc.getConnection().registryAccess();

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
                    LOGGER.error("CraftGraph index build failed: {}", err.getMessage());
                    return;
                }
                // 耗时必须打出来：主线程占用多少直接决定玩家有没有感觉到卡顿。
                LOGGER.info("CraftGraph snapshot rebuilt: {} recipes ({} unreadable, {}%), "
                                + "{} tags, dataVersion={}; main-thread extraction {} ms, indexing {} ms",
                        snap.recipeCount(),
                        opaque,
                        snap.recipeCount() == 0 ? 0 : Math.round(opaque * 100.0 / snap.recipeCount()),
                        snap.tagCount(),
                        snap.dataVersion(),
                        extractMillis,
                        snap.buildMillis());

                // getResultItem 抛异常的次数单独报。正常应为 0。
                // 之前这个异常被静默吞掉，导致「传了 null 注册表」那个 bug
                // 伪装成「Minecraft 的限制」藏了很久 —— 所以失败必须有声音。
                if (extractor.resultItemFailures() > 0) {
                    LOGGER.warn("CraftGraph: getResultItem threw for {} recipes; their outputs will read as "
                                    + "unknown. That is a bug signal, not a normal condition.",
                            extractor.resultItemFailures());
                }

                // 适配器调用失败的次数。正常应为 0 —— 非 0 意味着「某个类型退回了通用读取」，
                // 而通用读取对这些类型是**少信息**的（概率产出、耗时、机器都会缺），所以必须有声音。
                if (RecipeAdapters.adapterFailures() > 0) {
                    LOGGER.warn("CraftGraph: recipe adapter calls failed {} times; those recipes were read the "
                                    + "generic way (probability outputs, duration or machine may be missing). "
                                    + "Usually a mod version mismatch -- see the per-call warning above.",
                            RecipeAdapters.adapterFailures());
                }

                if (extractor.toastSymbolFailures() > 0) {
                    LOGGER.warn("CraftGraph: getToastSymbol threw for {} recipes; their machine cannot be inferred."
                                    + " That is a bug signal, not a normal condition.",
                            extractor.toastSymbolFailures());
                }

                // 字段覆盖度。duration / machine 曾经在真实数据里**全是 null**
                // 而四个测试层都没发现（夹具里手写了 duration），
                // 代价是产线计算的机器数整个失效了很久。所以这两个字段的覆盖度必须每次都报出来。
                FieldCoverage coverage = extractor.coverage();
                LOGGER.info("CraftGraph field coverage: {}", coverage.summary());
                if (coverage.withDuration() > 0) {
                    LOGGER.info("CraftGraph recipe types with duration data: {}",
                            String.join(", ", coverage.typesWithDuration()));
                }

                // 「本该有耗时却没读到」= 适配器没生效，不是数据缺失。
                for (String broken : coverage.brokenTypes()) {
                    LOGGER.warn("CraftGraph field coverage problem: {}. These types are guaranteed to carry a "
                                    + "duration, so reading null points at a parsing problem rather than "
                                    + "missing data -- production planning will treat them as manual.",
                            broken);
                }

                if (opaque > 0) {
                    LOGGER.info("CraftGraph {}% of recipes are unreadable. To see which types and whether it is a "
                                    + "platform limit or a parsing gap, run: npm run inspect",
                            Math.round(opaque * 100.0 / Math.max(1, snap.recipeCount())));
                }
            });
        } catch (Throwable t) {
            rebuildInFlight = false;
            // 必须捕获 Throwable：主线程上逃逸出去的异常会把游戏搞崩，
            // 而「配方读不出来」远没有「游戏崩了」严重。
            LOGGER.error("CraftGraph failed to rebuild the recipe snapshot", t);
        }
    }

    // ---------------------------------------------------------------- 数据收集

    /** 收集物品和流体标签。建倒排索引要用它把标签展开成具体物品。 */
    private static Map<String, List<String>> collectTags(RegistryAccess registries) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        collectTagsFor(registries, Registries.ITEM, out);
        collectTagsFor(registries, Registries.FLUID, out);
        return out;
    }

    /**
     * 1.20.1 的枚举方式：{@code getTagNames()} 给标签名，{@code getTag(tagKey)} 给成员。
     *
     * <p>退回一步想：这个方法的**产出数量必须能被观测到**。标签若取不到，
     * 标签还原会静默退化成「一串具体物品」—— 原料表看起来仍然完整，
     * 只是丢了「任意一种都行」的语义。所以 ClientBridge 一直把
     * {@code {} tags} 打进那行日志，live 层也断言它。
     */
    private static <T> void collectTagsFor(
            RegistryAccess registries,
            ResourceKey<? extends Registry<T>> key,
            Map<String, List<String>> out) {
        Registry<T> registry = registries.registryOrThrow(key);
        registry.getTagNames().forEach(tagKey -> {
            List<String> members = new ArrayList<>();
            registry.getTag(tagKey).ifPresent(namedSet -> namedSet.forEach(holder -> holder.unwrapKey()
                    .ifPresent(k -> members.add(k.location().toString()))));
            if (!members.isEmpty()) {
                out.put(tagKey.location().toString(), members);
            }
        });
    }

    /**
     * 收集注册表 id 列表。
     *
     * <p>全量收集是有意的：{@code /registry/{kind}} 和 MCP Server 的
     * itemName 映射都依赖它。注册表本身不大（原版几千项），放进快照没问题。
     */
    private static Map<String, List<String>> collectRegistries(RegistryAccess registries) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        // 这几个用的是静态的 BuiltInRegistries（与 1.21.1 相同）——
        // 它们本来就是全局注册表，拿到 RegistryAccess 只是为了让签名统一。
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
        String modVersion = modVersion(CraftGraph.MOD_ID);
        // ⚠️ 不能用 Minecraft.getInstance().getLaunchedVersion()。
        // 它返回的是启动参数里的 --version，而在开发环境（runClient）下那个值是
        // loader 的版本号，不是 Minecraft 版本 —— 实测就报成了 "mcVersion":"21.1.251"。
        String mcVersion = SharedConstants.getCurrentVersion().getName();
        String loader = ForgeVersion.getVersion();

        return new BridgeInfo(
                modVersion == null ? "unknown" : modVersion,
                mcVersion,
                "forge-" + loader,
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
