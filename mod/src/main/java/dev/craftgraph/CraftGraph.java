package dev.craftgraph;

import com.mojang.logging.LogUtils;
import dev.craftgraph.bridge.BridgeConfig;
import dev.craftgraph.client.ClientBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * CraftGraph Bridge Mod 入口。
 *
 * <p>这个 Mod 只做一件事：把游戏里已有的配方数据读出来，通过本地 HTTP 提供给
 * 游戏外的 MCP Server。它不做任何计算，也不修改游戏状态。完整接口约定见
 * 仓库根目录的 {@code doc/protocol.md}。
 *
 * <p><b>它是客户端模组。</b> 配方查看器（JEI/EMI）本身是客户端模组，
 * 而客户端也有全量配方数据（原版靠它做配方书），所以没必要做双端。
 * 在专用服务器上会直接跳过。
 */
@Mod(CraftGraph.MOD_ID)
public class CraftGraph {

    public static final String MOD_ID = "craftgraph";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile ClientBridge bridge;

    public CraftGraph(IEventBus modEventBus, ModContainer modContainer) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.info("CraftGraph is a client mod; bridge not started on a dedicated server");
            return;
        }

        // 不在构造函数里启动服务：那时 Minecraft.getInstance() 还不一定就绪。
        // FMLClientSetupEvent 是客户端初始化完成的信号，也是标准的启动点。
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork 把它排到主线程 —— 事件本身可能在并行线程上触发，
        // 而 ServiceLoader、事件注册这些操作必须在主线程做。
        event.enqueueWork(() -> {
            try {
                bridge = ClientBridge.start(BridgeConfig.defaults());
            } catch (Throwable t) {
                // 桥接起不来不该让游戏起不来。玩家装这个 Mod 可能是忘了删，
                // 也可能端口被占 —— 都该记一条日志然后继续游戏。
                LOGGER.error("CraftGraph bridge failed to start; AI queries unavailable", t);
            }
        });
    }

    /** 当前桥接实例；未启动或启动失败时为 null。 */
    public static ClientBridge bridge() {
        return bridge;
    }
}
