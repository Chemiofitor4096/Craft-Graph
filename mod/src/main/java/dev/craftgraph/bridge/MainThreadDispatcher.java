package dev.craftgraph.bridge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 把工作派发到游戏主线程执行。
 *
 * <h2>为什么必须有这个类</h2>
 *
 * HTTP 请求由 HttpServer 的工作线程处理，<b>不是游戏主线程</b>。
 * 而 {@code RecipeManager}、{@code RegistryAccess}、{@code Level} 这些对象
 * <b>只能在主线程访问</b>。
 *
 * 在错误的线程上读它们不会立刻抛异常，而是随机崩溃、读到陈旧数据、或者死锁 ——
 * 而且往往要跑很久才出现一次，极难复现。这类 bug 在"给游戏套 HTTP 服务"的模组里
 * 是最常见的死法，所以从第一天就要按规矩来，不要先写个能跑的再回来补。
 *
 * <h2>反方向同样重要</h2>
 *
 * <b>绝不在主线程上做重活。</b> 全量遍历上万个配方会把主线程卡住，
 * 玩家的体验就是掉帧，然后卸载这个 Mod。重活要么在后台线程上基于
 * 主线程拷贝出来的不可变快照做，要么分帧切片处理。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * dispatcher.supply(() -> recipeManager.getRecipes().size(), 5000)
 *           .thenAccept(count -> respond(200, count))
 *           .exceptionally(err -> respond(504, ...));
 * }</pre>
 */
public final class MainThreadDispatcher {

    /** 主线程执行器。客户端是 Minecraft.getInstance()，服务端是 server.execute。 */
    private final Executor mainThread;

    private final long defaultTimeoutMs;

    public MainThreadDispatcher(Executor mainThread, long defaultTimeoutMs) {
        this.mainThread = mainThread;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public <T> CompletableFuture<T> supply(Supplier<T> work) {
        return supply(work, defaultTimeoutMs);
    }

    /**
     * 在主线程上执行 {@code work}，把结果异步送回来。
     *
     * 超时返回 {@link java.util.concurrent.TimeoutException}。
     * 超时不会取消已经在队列里的任务 —— 它照样会跑，只是结果被丢弃。
     * 这是刻意的：中断主线程上的游戏逻辑比丢弃一个响应危险得多。
     */
    public <T> CompletableFuture<T> supply(Supplier<T> work, long timeoutMs) {
        CompletableFuture<T> future = new CompletableFuture<>();

        mainThread.execute(() -> {
            if (future.isDone()) {
                // 已经超时了，别白干活
                return;
            }
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                // 必须捕获 Throwable 而不是 Exception：
                // 主线程上抛出的任何东西如果逃逸出去，会把游戏搞崩。
                future.completeExceptionally(t);
            }
        });

        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 只读快照：在主线程上取一份不可变的数据，之后就可以在任意线程上慢慢处理。
     *
     * 这是处理大数据的正确姿势 —— 主线程只负责"把数据捞出来"这一下，
     * 遍历、建索引、序列化都放到请求线程上做。
     */
    public <T> CompletableFuture<T> snapshot(Supplier<T> extractor) {
        return supply(extractor);
    }
}
