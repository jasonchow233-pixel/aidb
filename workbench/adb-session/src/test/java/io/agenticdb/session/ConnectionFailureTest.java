package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.safety.ErrorClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ P5-3：连接失败语义。
 *
 * <p>连接出问题时，用户实际经历的是一条<b>完整的链路</b>：
 * 打开失败 → 会话有没有留下脏状态 → 错误能不能看懂 → 下一步该做什么 →
 * 修好之后能不能重连。只测 {@code SQLException} 被抛出来是不够的。
 *
 * <p>本门禁覆盖四件事：
 * <ol>
 *   <li><b>8 类错误</b>都能穿过会话边界被 {@link ErrorClassifier} 归类，
 *       且给出可执行建议（真实 SQLite 错误 3 类 + 驱动故障注入 5 类）；</li>
 *   <li><b>超时</b>被翻译成人话（带秒数），且不会把会话一起关掉；</li>
 *   <li><b>取消</b>只中断语句，不销毁连接；</li>
 *   <li><b>打开失败 / 连接丢失</b>都不留死会话：重试与重连必须拿到可用的新会话。</li>
 * </ol>
 */
class ConnectionFailureTest {

    @TempDir
    Path temp;

    @Test
    void aFailedOpenRegistersNoSessionAndCanBeRetried() {
        try (SessionManager manager = new SessionManager()) {
            ConnectionSpec broken = ConnectionSpec.sqlite(temp.resolve("missing/db.sqlite"));

            assertThatThrownBy(() -> manager.open("demo", broken))
                    .as("不存在的目录必须给出可读的打开失败")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("does not exist");

            assertThat(manager.isOpen("demo"))
                    .as("打开失败绝不能留下一个坏会话")
                    .isFalse();
            assertThat(manager.find("demo")).isEmpty();
            assertThat(manager.openIds()).isEmpty();

            DbSession retried = manager.open("demo",
                    ConnectionSpec.sqlite(temp.resolve("ok.db")));
            assertThat(retried.isClosed()).isFalse();
            assertThat(retried.query("SELECT 42 AS answer", 1).rows().getFirst())
                    .containsEntry("answer", 42);
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void realSqlErrorsAreClassifiedAcrossTheSessionBoundary() throws Exception {
        try (DbSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)");
            session.execute("INSERT INTO users VALUES (1, 'a@b.c')");

            ErrorClassifier.Diagnosis missing = diagnose(session, "SELECT * FROM missing_table");
            assertThat(missing.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
            assertThat(missing.advice()).as("必须告诉用户查表名").contains("表名");

            ErrorClassifier.Diagnosis syntax = diagnose(session, "SELCT 1");
            assertThat(syntax.kind()).isEqualTo(ErrorClassifier.Kind.SYNTAX);
            assertThat(syntax.advice()).isNotBlank();

            ErrorClassifier.Diagnosis duplicate = diagnose(session,
                    "INSERT INTO users VALUES (2, 'a@b.c')");
            assertThat(duplicate.kind()).isEqualTo(ErrorClassifier.Kind.CONSTRAINT_VIOLATION);
            assertThat(duplicate.advice()).contains("约束");
        }
    }

    @Test
    void connectionLevelFailuresCarryActionableAdvice() throws Exception {
        try (FaultyConnection faulty = new FaultyConnection();
             DbSession session = JdbcSession.wrap(faulty.connection(), "faulty")) {

            faulty.failNextWith("Communications link failure");
            ErrorClassifier.Diagnosis lost = diagnose(session, "SELECT 1");
            assertThat(lost.kind()).isEqualTo(ErrorClassifier.Kind.CONNECTION_LOST);
            assertThat(lost.advice()).as("断线后要能照着做：重新连接").contains("重新连接");

            faulty.failNextWith("Access denied for user 'app'@'10.0.0.3'");
            ErrorClassifier.Diagnosis denied = diagnose(session, "SELECT 1");
            assertThat(denied.kind()).isEqualTo(ErrorClassifier.Kind.PERMISSION_DENIED);
            assertThat(denied.advice()).contains("权限");

            faulty.failNextWith("Deadlock found when trying to get lock");
            ErrorClassifier.Diagnosis lock = diagnose(session, "SELECT 1");
            assertThat(lock.kind()).isEqualTo(ErrorClassifier.Kind.LOCK_CONFLICT);
            assertThat(lock.message()).contains("锁冲突");
        }
    }

    @Test
    void timeoutsAreTranslatedAndDoNotPoisonTheSession() throws Exception {
        try (FaultyConnection faulty = new FaultyConnection();
             DbSession session = JdbcSession.wrap(faulty.connection(), "faulty")) {

            faulty.failNextWith(new SQLTimeoutException("query timeout"));

            assertThatThrownBy(() -> session.query("SELECT 1", 10))
                    .as("超时要说人话，而不是只给驱动原文")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("查询超时")
                    .hasMessageContaining("30 秒");

            assertThat(session.isClosed())
                    .as("语句超时只结束这条语句，不能把会话一起关掉")
                    .isFalse();
            assertThat(session.query("SELECT 7 AS n", 10).rows().getFirst())
                    .as("超时之后会话必须还能用")
                    .containsEntry("n", 7);

            assertThat(ErrorClassifier.classify(new SQLTimeoutException("query timeout")).kind())
                    .isEqualTo(ErrorClassifier.Kind.TIMEOUT);
        }
    }

    @Test
    void cancellingAStatementLeavesTheSessionUsable() throws Exception {
        try (FaultyConnection faulty = new FaultyConnection();
             DbSession session = JdbcSession.wrap(faulty.connection(), "faulty")) {

            session.execute("CREATE TABLE t (v INTEGER)");
            faulty.blockNextExecution();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    session.query("SELECT 1", 10);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            runner.start();

            assertThat(faulty.awaitExecutionStarted())
                    .as("语句必须先跑起来，取消才有意义")
                    .isTrue();
            session.cancelCurrent();
            assertThat(faulty.cancelCount())
                    .as("取消必须真的下发到 Statement.cancel()")
                    .isPositive();

            faulty.releaseExecution();
            runner.join(10_000);
            assertThat(runner.isAlive()).isFalse();

            assertThat(failure.get()).as("释放后查询应正常完成").isNull();
            assertThat(session.isClosed()).as("取消只中断语句，不关连接").isFalse();
            assertThat(session.query("SELECT 3 AS n", 10).rows().getFirst())
                    .containsEntry("n", 3);
        }
    }

    @Test
    void openingAgainAfterAConnectionLossReturnsAUsableSession() throws Exception {
        Path file = temp.resolve("reconnect.db");
        ConnectionSpec spec = ConnectionSpec.sqlite(file);

        try (SessionManager manager = new SessionManager()) {
            DbSession first = manager.open("db", spec);
            first.execute("CREATE TABLE t (v INTEGER)");
            first.execute("INSERT INTO t VALUES (1)");

            // 模拟网络中断 / 服务重启：底层连接已经死了。
            first.connection().close();
            assertThat(first.isClosed()).isTrue();

            DbSession second = manager.open("db", spec);

            assertThat(second)
                    .as("会话已死时必须换新的，不能把死连接再发出去")
                    .isNotSameAs(first);
            assertThat(second.isClosed()).isFalse();
            assertThat(second.query("SELECT COUNT(*) AS c FROM t", 1).rows().getFirst())
                    .as("重连后必须能读到原来的数据")
                    .containsEntry("c", 1);
            assertThat(manager.require("db")).isSameAs(second);
        }
    }

    @Test
    void unknownFailuresFallBackButKeepTheDriverText() throws Exception {
        try (FaultyConnection faulty = new FaultyConnection();
             DbSession session = JdbcSession.wrap(faulty.connection(), "faulty")) {

            faulty.failNextWith("某个没见过的怪错误");
            ErrorClassifier.Diagnosis diagnosis = diagnose(session, "SELECT 1");

            assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.OTHER);
            assertThat(diagnosis.message())
                    .as("归不出来也要把驱动原文留着，否则没法排查")
                    .contains("某个没见过的怪错误");
        }
    }

    private static ErrorClassifier.Diagnosis diagnose(DbSession session, String sql) {
        try {
            session.execute(sql);
            throw new AssertionError("预期这条语句失败：" + sql);
        } catch (SQLException error) {
            return ErrorClassifier.classify(error);
        }
    }

    /**
     * 可注入故障的 SQLite 连接。
     *
     * <p>真实 SQLite 无法稳定制造「断线 / 权限不足 / 死锁」，
     * 但会话层要处理的正是这些<b>驱动抛出来的原文</b>；
     * 所以这里在驱动边界注入异常，其余行为仍然走真实连接。
     */
    private static final class FaultyConnection implements AutoCloseable {

        private final Connection real;
        private final AtomicReference<SQLException> nextFailure = new AtomicReference<>();
        private final AtomicInteger cancels = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean blocking;

        FaultyConnection() throws SQLException {
            real = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    ConnectionFailureTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        Object result = invoke(real, method, args);
                        if (result instanceof Statement statement
                                && ("createStatement".equals(method.getName())
                                || "prepareStatement".equals(method.getName()))) {
                            return spy(statement);
                        }
                        return result;
                    });
        }

        private Statement spy(Statement delegate) {
            // 必须实现 PreparedStatement：query/update 的变量类型就是它。
            return (Statement) Proxy.newProxyInstance(
                    ConnectionFailureTest.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "cancel" -> {
                                cancels.incrementAndGet();
                                return null;
                            }
                            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> {
                                awaitGate();
                                SQLException failure = nextFailure.getAndSet(null);
                                if (failure != null) {
                                    throw failure;
                                }
                            }
                            default -> {
                                // 其余方法原样交给真实语句。
                            }
                        }
                        return invoke(delegate, method, args);
                    });
        }

        private void awaitGate() throws InterruptedException, SQLException {
            if (!blocking) {
                return;
            }
            started.countDown();
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new SQLException("测试闸门等待超时");
            }
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        void failNextWith(String message) {
            nextFailure.set(new SQLException(message));
        }

        void failNextWith(SQLException failure) {
            nextFailure.set(failure);
        }

        void blockNextExecution() {
            blocking = true;
        }

        boolean awaitExecutionStarted() throws InterruptedException {
            return started.await(10, TimeUnit.SECONDS);
        }

        void releaseExecution() {
            blocking = false;
            released.countDown();
        }

        int cancelCount() {
            return cancels.get();
        }

        @Override
        public void close() throws SQLException {
            real.close();
        }
    }
}
