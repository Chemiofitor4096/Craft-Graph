package dev.craftgraph.bridge;

import dev.craftgraph.snapshot.RecipeSnapshot;

/**
 * 数据来源的抽象，把 Minecraft 隔离在 HTTP 层之外。
 *
 * <p>这么做是为了让 {@link BridgeHttpServer} 可以用普通 JUnit 测试 ——
 * 实现一个返回假数据的 source 就能验完整个 HTTP 层（路由、鉴权、分页、错误码），
 * 不需要启动游戏。真正接触 Minecraft 的只有 {@code extract} 包。
 *
 * <h2>线程约定</h2>
 *
 * {@link #snapshot()} 会被 HTTP 工作线程调用，**不是游戏主线程**。
 * 所以实现必须返回一个已经构建好的不可变对象，不能在里面现读游戏状态 ——
 * 那正是这个接口只有两个方法的原因。
 */
public interface BridgeDataSource {

    /**
     * 当前快照。游戏还没加载完时返回 null。
     *
     * 实现必须保证：返回的对象是完整构建过的、不可变的，可以安全地跨线程读取。
     * 推荐做法是用一个 volatile 字段持有它，重建时整体替换（而不是原地修改）。
     */
    RecipeSnapshot snapshot();

    /** 用于 /health 的静态信息。任意线程可调用。 */
    BridgeInfo info();
}
