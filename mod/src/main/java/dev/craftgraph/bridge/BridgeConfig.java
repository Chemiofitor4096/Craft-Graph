package dev.craftgraph.bridge;

/**
 * 桥接服务配置。
 *
 * <p>故意做成普通 record 而不是直接读 NeoForge 的 ModConfigSpec ——
 * 这样 HTTP 层和它的测试都不依赖 Minecraft。由 Mod 入口负责从配置文件构造它。
 */
public record BridgeConfig(
        /** 监听端口。默认 25585（贴近 Minecraft 的 25565，好记）。 */
        int port,
        /**
         * 是否开放 POST /reload。
         *
         * 默认关闭：这是唯一的写操作（会让游戏重载配方），
         * 而本机任何进程都能访问这个端口。MCP Server 平时靠 dataVersion 轮询就够了，
         * 只有用户明确要求时才需要它。
         */
        boolean exposeReloadEndpoint) {

    public static final int DEFAULT_PORT = 25585;

    public static BridgeConfig defaults() {
        return new BridgeConfig(DEFAULT_PORT, false);
    }

    public BridgeConfig {
        // 允许 0：让操作系统分配临时端口。测试要用，正常使用不会填 0
        // （填 0 的话端口每次都不一样，MCP Server 就找不到了）。
        if (port != 0 && (port < 1024 || port > 65535)) {
            throw new IllegalArgumentException("端口必须是 0（临时端口，仅测试用）或 1024~65535，实际 " + port
                    + "（1024 以下是特权端口，65535 以上无效）");
        }
    }
}
