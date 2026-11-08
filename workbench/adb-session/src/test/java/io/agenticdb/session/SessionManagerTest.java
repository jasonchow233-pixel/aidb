package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionManagerTest {

    @Test
    void opensFindsAndClosesSessions() throws Exception {
        SessionManager manager = new SessionManager();
        DbSession session = manager.open("demo", ConnectionSpec.sqliteMemory());

        assertThat(manager.isOpen("demo")).isTrue();
        assertThat(manager.find("demo")).containsSame(session);
        assertThat(manager.require("demo")).isSameAs(session);

        RowPage page = session.query("SELECT 1 AS n", 1);
        assertThat(((Number) page.rows().getFirst().get("n")).intValue()).isEqualTo(1);

        manager.close("demo");
        assertThat(manager.isOpen("demo")).isFalse();
        assertThat(manager.find("demo")).isEmpty();
        manager.close("demo");
        manager.close();
    }

    @Test
    void openedSessionsArePlainJdbcSessions() throws Exception {
        // ⭐ H3-f 撤池：会话就是直开的 JdbcSession，没有任何池化包装 ——
        //    钉住这个形态，防止哪天把包装层悄悄加回来。
        try (SessionManager manager = new SessionManager()) {
            assertThat(manager.open("demo", ConnectionSpec.sqliteMemory()))
                    .isInstanceOf(io.agenticdb.jdbc.JdbcSession.class);
        }
    }

    @Test
    void requireFailsWithReadableMessage() {
        try (SessionManager manager = new SessionManager()) {
            assertThatThrownBy(() -> manager.require("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Test
    void openingTheSameIdReusesTheExistingSession() throws Exception {
        // ⭐ U2：以前 open() 是「先关掉再重开」——
        //    切到 B 连接时 **A 连接的会话被关掉了**，A 的标签页当场失效。
        //    现在 open() **复用**已开的会话，两个连接才能并存。
        try (SessionManager manager = new SessionManager()) {
            DbSession first = manager.open("demo", ConnectionSpec.sqliteMemory());
            DbSession second = manager.open("demo", ConnectionSpec.sqliteMemory());

            assertThat(second).as("open() 要复用已开的会话").isSameAs(first);
            assertThat(manager.require("demo")).isSameAs(first);
            assertThat(first.isClosed()).as("复用时不能把旧会话关掉").isFalse();
        }
    }

    @Test
    void reopenForcesAFreshSession() throws Exception {
        // ⭐ 「重新连接」走 reopen：一定要换新的（用户想的就是重连）。
        try (SessionManager manager = new SessionManager()) {
            DbSession first = manager.open("demo", ConnectionSpec.sqliteMemory());
            DbSession second = manager.reopen("demo", ConnectionSpec.sqliteMemory());

            assertThat(second).isNotSameAs(first);
            assertThat(manager.require("demo")).isSameAs(second);
            assertThat(first.isClosed()).as("重开时旧会话要关掉").isTrue();
        }
    }

    @Test
    void twoConnectionsCanStayOpenAtTheSameTime() throws Exception {
        // ⭐ U2 的核心：**两个连接并存**，A 的标签页在切到 B 之后仍然可用。
        try (SessionManager manager = new SessionManager()) {
            DbSession a = manager.open("a", ConnectionSpec.sqliteMemory());
            DbSession b = manager.open("b", ConnectionSpec.sqliteMemory());

            assertThat(manager.openIds()).containsExactlyInAnyOrder("a", "b");
            assertThat(a.isClosed()).as("切到 B 不能把 A 关掉").isFalse();
            assertThat(b.isClosed()).isFalse();
            assertThat(manager.find("a")).containsSame(a);
            assertThat(manager.find("b")).containsSame(b);
        }
    }

    /**
     * ⭐ R61-b：一把慢连接不许拖住别的连接，更不许拖住**查表**。
     *
     * <p>这不是理论题。D-39 实拍量过：Oracle 首次连接 8~12 秒。而 {@code open()} 里做的
     * 就是那次真连接，原先整个注册表是**一把全局 {@code synchronized}**（连 {@code find}
     * /{@code isOpen}/{@code require} 都在锁上），于是那 10 秒里：
     * ① 另一把库的 {@code open} 排在后面 —— 「多连接并行」只剩一半；
     * ② FX 线程的 {@code find} 也排在后面 —— 侧栏的存活点与方言解析都走这条路，
     *    症状是「正在连 Oracle，整个界面点不动」。
     *
     * <p>「慢」用同包的注入点造（见 {@code openProbe}）：连一个黑洞地址要等多久
     * 取决于 NAT / 防火墙 / 内核超时，那种门禁第二天就红；注入睡眠才是常量。
     */
    @Test
    void aSlowConnectionDoesNotHoldUpOtherConnectionsOrLookups() throws Exception {
        try (SessionManager manager = new SessionManager()) {
            DbSession fast = manager.open("fast", ConnectionSpec.sqliteMemory());

            final long slowMillis = 2_000;
            final long boundMillis = 600;
            java.util.concurrent.CountDownLatch slowInside = new java.util.concurrent.CountDownLatch(1);
            manager.setOpenProbeForTest(id -> {
                if (id.equals("slow")) {
                    slowInside.countDown();
                    try {
                        Thread.sleep(slowMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });

            java.util.concurrent.CountDownLatch slowDone = new java.util.concurrent.CountDownLatch(1);
            // ⚠️ 存的是**消息**而不是异常本身：SQLException 实现了 Iterable<Throwable>
            //   （链式异常），直接 assertThat(那个异常) 会撞上 AssertJ 的
            //   assertThat(Iterable) / assertThat(T) 重载歧义，编译都过不去。
            java.util.concurrent.atomic.AtomicReference<String> slowFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread slow = new Thread(() -> {
                try {
                    manager.open("slow", ConnectionSpec.sqliteMemory());
                } catch (SQLException e) {
                    slowFailure.set(String.valueOf(e.getMessage()));
                } finally {
                    slowDone.countDown();
                }
            }, "adb-slow-connect");
            slow.setDaemon(true);
            slow.start();
            assertThat(slowInside.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("慢连接得真的在途，否则这一轮什么都没量到").isTrue();

            // ① 查另一把库：旧写法在这里等满 slowMillis（find 自己就在同一把全局锁上）。
            long lookupStart = System.nanoTime();
            assertThat(manager.find("fast")).containsSame(fast);
            assertThat(manager.isOpen("fast")).isTrue();
            long lookupMillis = java.time.Duration.ofNanos(System.nanoTime() - lookupStart).toMillis();
            assertThat(lookupMillis)
                    .as("慢连接在途时，注册表的读操作不该排队（实测等了 " + lookupMillis + "ms）")
                    .isLessThan(boundMillis);

            // ② 开第三把库：也不许排在那次慢连接后面。
            DbSession third = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return manager.open("third", ConnectionSpec.sqliteMemory());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .get(boundMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(third.isClosed()).isFalse();

            // ③ 慢连接本身照常开成 —— 分段锁不是把它跳过了。
            assertThat(slowDone.await(8, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(slowFailure.get()).as("慢连接自己不能被分段锁挤掉").isNull();
            assertThat(manager.openIds()).containsExactlyInAnyOrder("fast", "slow", "third");
        }
    }

    /**
     * ⭐ R61-b 的另一半：**同一个 id** 仍然要互斥 —— 分段锁不能把「两个人同开一把库」
     * 变成「开两条会话」。后到的那个复用先建好的，而不是再连一次。
     */
    @Test
    void twoThreadsOpeningTheSameIdStillShareOneSession() throws Exception {
        try (SessionManager manager = new SessionManager()) {
            java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
            manager.setOpenProbeForTest(id -> {
                if (id.equals("same")) {
                    opens.incrementAndGet();
                    inside.countDown();
                    try {
                        assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });

            java.util.concurrent.CompletableFuture<DbSession> first =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return manager.open("same", ConnectionSpec.sqliteMemory());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.CompletableFuture<DbSession> second =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return manager.open("same", ConnectionSpec.sqliteMemory());
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            release.countDown();

            DbSession a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            DbSession b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(b).as("第二个调用者要复用，不许再开一条会话").isSameAs(a);
            assertThat(opens.get()).as("真正打开的次数（注入点计数）").isEqualTo(1);
        }
    }
}
