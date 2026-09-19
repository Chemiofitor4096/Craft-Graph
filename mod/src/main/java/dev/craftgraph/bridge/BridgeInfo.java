package dev.craftgraph.bridge;

/**
 * /health 用的静态信息。
 *
 * <p>{@code emiVersion} / {@code jeiVersion} 为 null 表示未安装 ——
 * 这个区分有用：MCP Server 可以据此提示用户「装 EMI 能提高配方覆盖率」。
 */
public record BridgeInfo(
        String modVersion,
        String mcVersion,
        String loader,
        String emiVersion,
        String jeiVersion,
        long startedAtMillis) {

    public static BridgeInfo unknown() {
        return new BridgeInfo("0.0.0", "unknown", "unknown", null, null, System.currentTimeMillis());
    }
}
