package dev.craftgraph.normalize;

/**
 * 把 id 变成给人看的短标签。
 *
 * <p>原版 API 只给得出 {@code minecraft:smelting} 这样的 id，没有人类可读的名字。
 * 接 EMI 之后可以用它提供的名字替换这里的推断结果 —— 所以这里的输出是**兜底**，
 * 不是最终答案。但兜底也要像样：报告里出现 {@code minecraft:smelting} 比
 * 出现 {@code Smelting} 难读得多，而 AI 也是靠这些名字理解配方类型的。
 *
 * <p>纯计算，可测试。
 */
public final class Humanize {

    private Humanize() {
    }

    /**
     * {@code minecraft:smelting} → {@code Smelting}
     * {@code create:crushing} → {@code Crushing}
     * {@code thermal:machine/smelter} → {@code Machine Smelter}
     * {@code minecraft:crafting_shaped} → {@code Crafting Shaped}
     */
    public static String typeLabel(String typeId) {
        if (typeId == null || typeId.isBlank()) return "";

        // 去掉命名空间：「谁家的」对用户理解「这是什么配方类型」没有帮助 ——
        // 真正提供信息的是 smelting / crushing 这后半段。
        int colon = typeId.indexOf(':');
        String path = colon >= 0 ? typeId.substring(colon + 1) : typeId;

        StringBuilder sb = new StringBuilder(path.length() + 4);
        boolean upperNext = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/' || c == '.') {
                // 分隔符统一变成空格，并让下一个词首字母大写
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                upperNext = true;
            } else if (upperNext) {
                sb.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
