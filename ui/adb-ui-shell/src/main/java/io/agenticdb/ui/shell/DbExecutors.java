package io.agenticdb.ui.shell;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ⭐ A16：**每连接一个执行器**。
 *
 * <h2>为什么不能再用「全局一个线程」</h2>
 * 以前所有页面的数据库操作都跑在<b>同一个</b>单线程执行器上
 * （{@code adb-db}）。于是：
 * <ul>
 *   <li>A 连接的一条慢查询 → <b>B 连接的每一个操作都被堵住</b>；</li>
 *   <li>连接建立与内省也在这个线程上 → 连接慢一点，整个界面就僵。</li>
 * </ul>
 * 多连接并存（U2）之后，这个共享线程成了最刺手的瓶颈。
 *
 * <h2>现在的做法</h2>
 * <ul>
 *   <li><b>每个连接一条单线程执行器</b>：同一连接内仍然**严格串行**
 *       （SQLite 锁竞争、事务语义都靠这一点），不同连接之间**互不阻塞</b>；</li>
 *   <li><b>全局执行器</b>：给与连接无关的任务（诊断、导出等）。</li>
 * </ul>
 *
 * <p>执行器按需创建、用完不回收（连接的数量是有界的），
 * 全部在 {@link #close()} 时关闭。
 */
final class DbExecutors implements AutoCloseable {

    private final Map<String, ExecutorService> byConnection = new ConcurrentHashMap<>();
    private final ExecutorService global = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "adb-db");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 取某个连接的执行器。
     *
     * @param connectionId 连接 id；为空时用全局执行器
     */
    Executor forConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return global;
        }
        return byConnection.computeIfAbsent(connectionId, id ->
                Executors.newSingleThreadExecutor(runnable -> {
                    // 线程名用「adb-db:<连接id>」—— 用冒号而不是连字符，
                    // 免得它长得像样式类（StyleClassCoverageTest 会把 "adb-xxx" 当类名扫）。
                    Thread thread = new Thread(runnable, "adb-db:" + id);
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** 与连接无关的任务用这个。 */
    Executor global() {
        return global;
    }

    /** 当前有多少条连接的执行器（诊断用）。 */
    int connectionCount() {
        return byConnection.size();
    }

    @Override
    public void close() {
        for (ExecutorService service : byConnection.values()) {
            shutdown(service);
        }
        byConnection.clear();
        shutdown(global);
    }

    private static void shutdown(ExecutorService service) {
        service.shutdown();
        try {
            if (!service.awaitTermination(2, TimeUnit.SECONDS)) {
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
