package dev.craftgraph.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * JSON 序列化配置。
 *
 * 两个刻意的选择：
 *
 * <ol>
 *   <li><b>{@code serializeNulls()}</b>：默认情况下 Gson 会省略 null 字段，
 *       而 doc/protocol.md 里明确写了 {@code "machine": null} 这样的字段要出现。
 *       MCP Server 侧靠字段存在与否判断「读不到」还是「没有」，省略会改变语义。</li>
 *   <li><b>不缩进、不 pretty</b>：输出会进模型上下文，缩进纯粹是浪费 token
 *       （实测缩进版贵 39%）。这是给程序看的，不是给人看的。</li>
 * </ol>
 *
 * 关于 record 支持：Gson 从 2.10 起支持 record。Minecraft 自带的是哪个版本我不确定，
 * 所以 {@code JsonTest} 里有一个测试专门验证 record 能被正确序列化 —— 如果哪天
 * Minecraft 降级了 Gson，那个测试会立刻失败，而不是等到运行时才发现所有响应都是空的。
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private Json() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
