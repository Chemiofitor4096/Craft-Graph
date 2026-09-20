package dev.craftgraph.bridge;

import dev.craftgraph.api.Models;
import dev.craftgraph.snapshot.RecipeSnapshot;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 快照的生命周期管理：持有当前快照、编排重建、分配 dataVersion。
 *
 * <h2>重建流程</h2>
 *
 * <pre>
 *   主线程：取 RecipeManager、翻译成 DTO      ← 只能在主线程
 *        ↓  交出一个 List&lt;Models.Recipe&gt;
 *   后台线程：建索引（RecipeSnapshot.Builder）  ← 纯计算，不碰游戏
 *        ↓
 *   发布到 volatile 字段                      ← 整体替换，不原地修改
 * </pre>
 *
 * 「整体替换而不是原地修改」是这里唯一要紧的并发约定：
 * HTTP 工作线程随时可能拿到旧快照，只要它是完整且不可变的就没问题。
 * 如果允许原地修改，就会出现「读到一半数据变了」的情况，症状是随机的、
 * 极难复现的脏读。
 *
 * 这个类不依赖 Minecraft —— 数据由调用方在主线程准备好后传进来。
 */
public final class BridgeService implements BridgeDataSource {

    /**
     * dataVersion 单调递增。
     *
     * 从 1 开始而不是 0：0 用作「尚未就绪」的哨兵值，两者必须能区分开。
     */
    private final AtomicInteger version = new AtomicInteger(1);

    private volatile RecipeSnapshot snapshot;
    private volatile BridgeInfo info;

    private final Executor indexExecutor;

    public BridgeService(BridgeInfo info, Executor indexExecutor) {
        this.info = info;
        this.indexExecutor = indexExecutor;
    }

    @Override
    public RecipeSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public BridgeInfo info() {
        return info;
    }

    public void updateInfo(BridgeInfo info) {
        this.info = info;
    }

    /** 数据是否已经就绪。见 doc/protocol.md 里 ready 字段的说明。 */
    public boolean isReady() {
        return snapshot != null;
    }

    public int currentVersion() {
        return version.get();
    }

    /**
     * 用主线程取好的 DTO 在后台建索引并发布。
     *
     * 返回的 future 完成时快照已经可见（volatile 写有 happens-before 保证）。
     * 调用方通常不需要等它 —— 但测试和「先建好再让玩家进世界」的场景会用到。
     *
     * @param recipes      主线程翻译好的配方 DTO
     * @param tags         标签表（tagId → 成员）
     * @param registries   注册表（kind → id 列表），用于 /registry
     * @param displayNames id → 显示名
     */
    public CompletableFuture<RecipeSnapshot> publish(
            List<Models.Recipe> recipes,
            java.util.Map<String, List<String>> tags,
            java.util.Map<String, List<String>> registries,
            java.util.Map<String, String> displayNames) {

        int buildVersion = version.getAndIncrement();

        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            RecipeSnapshot built = RecipeSnapshot.builder(buildVersion)
                    .tags(tags)
                    .displayNames(displayNames)
                    .addAllRegistries(registries)
                    .addAllRecipes(recipes)
                    .build();

            long millis = (System.nanoTime() - start) / 1_000_000;
            // 整体替换。不要在这里修改旧快照 —— 见类注释。
            this.snapshot = built;
            return built;
        }, indexExecutor);
    }

    /** 游戏退出或断开连接时清空 —— 否则 /health 会继续谎报 ready=true。 */
    public void clear() {
        this.snapshot = null;
    }
}
