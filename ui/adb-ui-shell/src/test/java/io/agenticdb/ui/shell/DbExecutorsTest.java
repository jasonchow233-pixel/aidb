package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ A16 门禁：**每连接一个执行器**。
 *
 * <p>以前所有数据库操作共用一条全局线程 —— A 连接的慢查询会把 B 连接**全部**堵死。
 * 现在：同一连接内仍严格串行，不同连接互不阻塞。
 */
class DbExecutorsTest {

    @Test
    void differentConnectionsGetDifferentExecutors() {
        try (DbExecutors executors = new DbExecutors()) {
            Executor a = executors.forConnection("a");
            Executor b = executors.forConnection("b");

            assertThat(a).as("不同连接必须是不同的执行器").isNotSameAs(b);
            assertThat(executors.connectionCount()).isEqualTo(2);
        }
    }

    @Test
    void theSameConnectionAlwaysGetsTheSameExecutor() {
        try (DbExecutors executors = new DbExecutors()) {
            assertThat(executors.forConnection("a")).isSameAs(executors.forConnection("a"));
            assertThat(executors.connectionCount())
                    .as("同一连接只应该有一条执行器（串行语义）")
                    .isEqualTo(1);
        }
    }

    @Test
    void workOnOneConnectionDoesNotBlockAnother() throws Exception {
        // ⭐ 核心断言：A 连接上的慢任务**不能**挡住 B 连接。
        try (DbExecutors executors = new DbExecutors()) {
            Executor slow = executors.forConnection("slow");
            Executor fast = executors.forConnection("fast");

            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch releaseSlow = new CountDownLatch(1);
            slow.execute(() -> {
                slowStarted.countDown();
                try {
                    releaseSlow.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(slowStarted.await(2, TimeUnit.SECONDS)).isTrue();

            AtomicBoolean fastDone = new AtomicBoolean(false);
            fast.execute(() -> fastDone.set(true));

            long deadline = System.currentTimeMillis() + 2_000;
            while (!fastDone.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(fastDone.get())
                    .as("⭐ 慢连接不能挡住别的连接（这正是 A16 要修的）")
                    .isTrue();

            releaseSlow.countDown();
        }
    }

    @Test
    void blankConnectionIdFallsBackToTheGlobalExecutor() {
        try (DbExecutors executors = new DbExecutors()) {
            assertThat(executors.forConnection(null)).isSameAs(executors.global());
            assertThat(executors.forConnection("  ")).isSameAs(executors.global());
        }
    }

    @Test
    void tasksRunOnDedicatedThreadsNamedAfterTheConnection() throws Exception {
        try (DbExecutors executors = new DbExecutors()) {
            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            executors.forConnection("shop").execute(() -> {
                threadName.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get())
                    .as("线程名要能看出是哪个连接的（排查卡顿时有用）")
                    .contains("shop");
        }
    }
}
