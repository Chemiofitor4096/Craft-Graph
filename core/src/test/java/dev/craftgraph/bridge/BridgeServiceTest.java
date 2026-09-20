package dev.craftgraph.bridge;

import dev.craftgraph.api.Models;
import dev.craftgraph.snapshot.RecipeSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照生命周期的测试。
 *
 * 重点验两件事：
 *   1. dataVersion 单调递增且与「未就绪」的哨兵值不冲突
 *   2. 发布是原子替换 —— 并发读取时不能看到半成品
 */
class BridgeServiceTest {

    private static final Executor DIRECT = Runnable::run;

    private static Models.Recipe recipe(String id) {
        return new Models.Recipe(id, "minecraft:crafting", null,
                List.of(new Models.Ingredient("item", 1, List.of(Models.Option.item("minecraft:iron_ingot")))),
                List.of(new Models.ItemStack("minecraft:iron_block", 1, null)),
                List.of(), List.of(), null, null, null, "vanilla", false, false);
    }

    private static BridgeService newService() {
        return new BridgeService(BridgeInfo.unknown(), DIRECT);
    }

    @Test
    @DisplayName("初始状态：未就绪，snapshot 为 null")
    void startsNotReady() {
        BridgeService svc = newService();
        assertFalse(svc.isReady(), "一开始没有数据，必须报未就绪");
        assertNull(svc.snapshot());
        assertEquals(1, svc.currentVersion(), "版本从 1 开始，0 留给「未就绪」当哨兵");
    }

    @Test
    @DisplayName("发布后变为就绪，且能查到数据")
    void readyAfterPublish() {
        BridgeService svc = newService();
        RecipeSnapshot snap = svc.publish(
                List.of(recipe("test:a")),
                Map.of("forge:ingots/iron", List.of("minecraft:iron_ingot")),
                Map.of("items", List.of("minecraft:iron_ingot")),
                Map.of()).join();

        assertTrue(svc.isReady());
        assertEquals(1, snap.recipeCount());
        assertEquals(1, snap.dataVersion());
        assertEquals(1, svc.snapshot().recipesProducing("item", "minecraft:iron_block").size());
    }

    @Test
    @DisplayName("每次发布 dataVersion 递增（MCP Server 靠它判断缓存失效）")
    void versionIncrementsMonotonically() {
        BridgeService svc = newService();

        int v1 = svc.publish(List.of(), Map.of(), Map.of(), Map.of()).join().dataVersion();
        int v2 = svc.publish(List.of(), Map.of(), Map.of(), Map.of()).join().dataVersion();
        int v3 = svc.publish(List.of(), Map.of(), Map.of(), Map.of()).join().dataVersion();

        assertTrue(v2 > v1 && v3 > v2, "版本必须递增： " + v1 + " → " + v2 + " → " + v3);
        // 关键：任何一次发布都不能是 0。0 是「未就绪」的哨兵，
        // 如果某次发布得到 0，MCP Server 会误判成「游戏还没加载完」。
        assertTrue(v1 >= 1 && v2 >= 1 && v3 >= 1, "dataVersion 不能等于哨兵值 0");
    }

    @Test
    @DisplayName("版本号与发布时的内容对应，不会串号")
    void versionMatchesContent() {
        BridgeService svc = newService();
        svc.publish(List.of(recipe("first")), Map.of(), Map.of(), Map.of()).join();
        RecipeSnapshot second = svc.publish(
                List.of(recipe("first"), recipe("second")), Map.of(), Map.of(), Map.of()).join();

        assertEquals(2, second.recipeCount());
        assertEquals(2, second.dataVersion());
        assertNotNull(second.recipe("second"));
        assertEquals(3, svc.currentVersion(), "两次发布后，下一次发布应该用版本 3");
    }

    @Test
    @DisplayName("clear 后回到未就绪（游戏退出时不能让 /health 继续谎报 ready）")
    void clearReturnsToNotReady() {
        BridgeService svc = newService();
        svc.publish(List.of(recipe("a")), Map.of(), Map.of(), Map.of()).join();
        assertTrue(svc.isReady());

        svc.clear();
        assertFalse(svc.isReady(), "断开连接后必须报未就绪，否则调用方会拿旧数据当新数据");
        assertNull(svc.snapshot());
    }

    @Test
    @DisplayName("clear 之后 dataVersion 仍然递增，不会倒退")
    void versionDoesNotGoBackwardsAfterClear() {
        BridgeService svc = newService();
        int before = svc.publish(List.of(recipe("a")), Map.of(), Map.of(), Map.of()).join().dataVersion();
        svc.clear();
        int after = svc.publish(List.of(recipe("b")), Map.of(), Map.of(), Map.of()).join().dataVersion();

        assertTrue(after > before,
                "重连后版本必须继续递增。如果倒退回旧值，MCP Server 会认为自己的缓存还有效，"
                        + "于是拿着上一个世界的数据回答：" + before + " → " + after);
    }

    @Test
    @DisplayName("并发读取只会看到完整快照，不会看到半成品")
    void concurrentReadersSeeCompleteSnapshots() throws Exception {
        BridgeService svc = newService();
        var pool = Executors.newFixedThreadPool(4);
        try {
            svc.publish(List.of(recipe("warmup")), Map.of(), Map.of(), Map.of()).join();

            // 一个线程反复发布，其他线程反复读取。
            // 因为发布是整体替换一个 volatile 引用，读者永远拿到某个完整版本，
            // 不会拿到「索引建了一半」的对象。
            Thread writer = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    svc.publish(List.of(recipe("a"), recipe("b"), recipe("c")),
                            Map.of(), Map.of(), Map.of()).join();
                }
            });
            writer.start();

            for (int t = 0; t < 3; t++) {
                pool.submit(() -> {
                    long deadline = System.currentTimeMillis() + 3000;
                    while (System.currentTimeMillis() < deadline) {
                        RecipeSnapshot s = svc.snapshot();
                        if (s == null) continue;
                        // 每次发布都是同一份内容。如果发布过程能被观察到中间状态，
                        // 就可能读到 1 条或 2 条配方。
                        assertEquals(3, s.recipeCount(),
                                "读到了不完整的快照（版本 " + s.dataVersion() + "）");
                        assertTrue(s.dataVersion() >= 1);
                    }
                });
            }
            writer.join();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
