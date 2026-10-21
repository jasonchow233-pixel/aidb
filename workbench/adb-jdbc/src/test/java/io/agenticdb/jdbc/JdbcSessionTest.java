package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnType;
import io.agenticdb.model.Danger;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSessionTest {

    @Test
    void mapsColumnsAndRows() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("""
                    CREATE TABLE users (
                      id INTEGER PRIMARY KEY,
                      name VARCHAR(50) NOT NULL,
                      flag BOOLEAN,
                      payload JSON,
                      created TIMESTAMP
                    )
                    """);
            session.update("INSERT INTO users VALUES (1, '张三', 1, '{\"a\":1}', '2026-10-20 10:00:00')");

            RowPage page = session.query("SELECT * FROM users", 200);

            assertThat(page.rowCount()).isEqualTo(1);
            assertThat(page.columns()).extracting("name")
                    .containsExactly("id", "name", "flag", "payload", "created");
            assertThat(page.columns().get(0).type()).isEqualTo(ColumnType.NUMBER);
            assertThat(page.columns().get(1).type()).isEqualTo(ColumnType.TEXT);
            assertThat(page.columns().get(1).nullable()).isFalse();
            assertThat(page.columns().get(1).charMaxLength()).isEqualTo(50);
            assertThat(page.columns().get(2).type()).isEqualTo(ColumnType.BOOLEAN);
            assertThat(page.columns().get(3).type()).isEqualTo(ColumnType.JSON);
            assertThat(page.columns().get(4).type()).isEqualTo(ColumnType.TIMESTAMP);
            assertThat(page.rows().get(0)).containsEntry("name", "张三");
        }
    }

    @Test
    void keepsNullAndEmptyStringDistinct() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (v TEXT)");
            session.update("INSERT INTO t VALUES (NULL), (''), ('x')");

            RowPage page = session.query("SELECT v FROM t ORDER BY rowid", 200);

            assertThat(page.rows()).hasSize(3);
            assertThat(page.rows().get(0).get("v")).isNull();
            assertThat(page.rows().get(1).get("v")).isEqualTo("");
            assertThat(page.rows().get(2).get("v")).isEqualTo("x");
        }
    }

    @Test
    void enforcesMaxRows() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (id INTEGER)");
            for (int i = 0; i < 150; i++) {
                session.update("INSERT INTO t VALUES (" + i + ")");
            }

            RowPage page = session.query("SELECT id FROM t", 100);

            assertThat(page.rowCount()).isEqualTo(100);
        }
    }

    /** 4.5-2：截断必须能被检测出来，否则 UI 无从提示。 */
    @Test
    void marksResultTruncatedAtPageLimit() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (id INTEGER)");
            session.begin();
            for (int i = 0; i < QueryLimits.MAX_PAGE_SIZE + 5; i++) {
                session.update("INSERT INTO t VALUES (" + i + ")");
            }
            session.commit();

            ExecutionResult result = session.execute("SELECT id FROM t");

            assertThat(result.rowCount()).isEqualTo(QueryLimits.MAX_PAGE_SIZE);
            assertThat(result.isTruncated())
                    .as("库里还有 5 行没读出来，必须告诉用户被截断了")
                    .isTrue();
        }
    }

    @Test
    void queryWithLimitJustBelowTotalIsNotTruncated() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (id INTEGER)");
            for (int i = 0; i < 10; i++) {
                session.update("INSERT INTO t VALUES (" + i + ")");
            }
            ExecutionResult result = session.execute("SELECT id FROM t");
            assertThat(result.rowCount()).isEqualTo(10);
            assertThat(result.isTruncated())
                    .as("结果没到上限就不能报截断")
                    .isFalse();
        }
    }

    @Test
    void transactionRollsBack() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (v TEXT)");

            session.begin();
            assertThat(session.inTransaction()).isTrue();
            session.update("INSERT INTO t VALUES ('tmp')");
            session.rollback();
            assertThat(session.inTransaction()).isFalse();

            assertThat(session.query("SELECT v FROM t", 200).rowCount()).isZero();
        }
    }

    @Test
    void bindsParametersAndDistinguishesNullFromEmptyString() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (v TEXT, n INTEGER)");
            session.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.asList(null, 1));
            session.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.asList("", 2));
            session.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.asList("x", 3));

            RowPage nulls = session.query(
                    "SELECT n FROM t WHERE v IS NULL", 200, java.util.List.of());
            RowPage empties = session.query(
                    "SELECT n FROM t WHERE v = ?", 200, java.util.List.of(""));
            RowPage numbers = session.query(
                    "SELECT n FROM t WHERE n >= ? ORDER BY n", 200, java.util.List.of(2));

            assertThat(nulls.rows()).extracting(row -> row.get("n")).containsExactly(1);
            assertThat(empties.rows()).extracting(row -> row.get("n")).containsExactly(2);
            assertThat(numbers.rows()).extracting(row -> row.get("n")).containsExactly(2, 3);
        }
    }

    @Test
    void invalidSqlKeepsReadableMessage() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            assertThatThrownBy(() -> session.query("SELECT * FROM missing_table", 200))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("missing_table");
        }
    }

    @Test
    void executesQueryAndDmlWithOneApi() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            ExecutionResult create = session.execute("CREATE TABLE t (id INTEGER, name TEXT)");
            assertThat(create.hasRows()).isFalse();
            assertThat(create.updateCount()).isZero();

            ExecutionResult insert = session.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
            assertThat(insert.updateCount()).isEqualTo(2);

            ExecutionResult query = session.execute("SELECT id, name FROM t ORDER BY id");
            assertThat(query.hasRows()).isTrue();
            assertThat(query.columns()).extracting("name").containsExactly("id", "name");
            assertThat(query.rows()).hasSize(2);
            assertThat(query.rows().getFirst()).containsEntry("name", "a");
            assertThat(query.durationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void executeRespectsQueryRowLimit() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            ExecutionResult result = session.execute("""
                    WITH RECURSIVE seq(x) AS (
                      SELECT 1 UNION ALL SELECT x + 1 FROM seq WHERE x < 1500
                    )
                    SELECT x FROM seq
                    """);
            assertThat(result.rowCount()).isEqualTo(io.agenticdb.model.QueryLimits.MAX_PAGE_SIZE);
        }
    }

    /**
     * 4.5-13：{@code query()} / {@code update()} 也必须记录 {@code activeStatement}，
     * 否则数据页的加载与保存<b>无法被 ⌘. 真正中断</b>，只能逻辑放弃。
     *
     * <p>用代理 Statement 精确观测：{@code cancel()} 有没有被调用。
     * 不靠「跑一个长查询然后看时间」来赌，避免 flaky。
     */
    @Test
    void queryRegistersTheStatementSoCancelCanInterruptIt() throws Exception {
        try (SpyConnection spy = new SpyConnection("jdbc:sqlite::memory:")) {
            try (JdbcSession session = JdbcSession.wrap(spy.connection(), "spy")) {
                spy.blockNextExecution();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread runner = new Thread(() -> {
                    try {
                        session.query("SELECT 1", 10);
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
                runner.start();

                assertThat(spy.awaitExecutionStarted())
                        .as("查询必须已经开始执行，才能验证取消")
                        .isTrue();
                session.cancelCurrent();
                assertThat(spy.cancelCount())
                        .as("⌘. 必须真的调用 Statement.cancel()（4.5-13）")
                        .isPositive();
                spy.releaseExecution();
                runner.join(10_000);
                assertThat(runner.isAlive()).isFalse();
            }
        }
    }

    @Test
    void updateRegistersTheStatementSoCancelCanInterruptIt() throws Exception {
        try (SpyConnection spy = new SpyConnection("jdbc:sqlite::memory:")) {
            try (JdbcSession session = JdbcSession.wrap(spy.connection(), "spy")) {
                session.execute("CREATE TABLE t (a INTEGER)");
                spy.blockNextExecution();
                Thread runner = new Thread(() -> {
                    try {
                        session.update("UPDATE t SET a = ?", List.of(1));
                    } catch (Throwable ignored) {
                        // 取消后必然抛异常，这是预期。
                    }
                });
                runner.start();

                assertThat(spy.awaitExecutionStarted()).isTrue();
                session.cancelCurrent();
                assertThat(spy.cancelCount())
                        .as("保存路径的 update() 也必须能被取消（4.5-13）")
                        .isPositive();
                spy.releaseExecution();
                runner.join(10_000);
            }
        }
    }

    /**
     * 观测用的连接代理：包装 {@link Statement}，记录 {@code cancel()} 调用次数，
     * 并可以让下一次执行阻塞在闸门上。
     */
    private static final class SpyConnection implements AutoCloseable {

        private final Connection real;
        private final java.util.concurrent.atomic.AtomicInteger cancels =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.CountDownLatch started =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        private volatile boolean blocking;

        SpyConnection(String url) throws SQLException {
            real = DriverManager.getConnection(url);
        }

        Connection connection() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName())) {
                            return spyStatement((Statement) method.invoke(real, args));
                        }
                        return method.invoke(real, args);
                    });
        }

        private Statement spyStatement(Statement delegate) {
            // ⭐ 代理必须实现 PreparedStatement —— JdbcSession 里的变量类型是
            //    PreparedStatement，只代理 Statement 会在赋值时 ClassCastException。
            return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{java.sql.PreparedStatement.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("cancel".equals(name)) {
                            cancels.incrementAndGet();
                            return null;
                        }
                        if (("executeQuery".equals(name) || "executeUpdate".equals(name))
                                && blocking) {
                            started.countDown();
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        void blockNextExecution() {
            blocking = true;
        }

        boolean awaitExecutionStarted() throws InterruptedException {
            return started.await(10, java.util.concurrent.TimeUnit.SECONDS);
        }

        void releaseExecution() {
            release.countDown();
        }

        int cancelCount() {
            return cancels.get();
        }

        @Override
        public void close() throws SQLException {
            real.close();
        }
    }

    /**
     * ⭐ A6：第三层门禁（驱动侧复查）。
     *
     * <p>前两层（分类 + UI 确认）都在 UI 侧；第三层在数据通路侧，
     * <b>不依赖 UI 是否走了正确的路径</b>。
     */
    @Test
    void driverSideCheckRejectsUnderDeclaredDanger() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER)");

            // 把 DELETE 声明成只读 → 拒绝（这就是「绕过确认」）
            assertThatThrownBy(() -> session.execute("DELETE FROM t", Danger.READ_ONLY))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("危险等级不符")
                    .hasMessageContaining("已被拒绝");

            // 带 WHERE 的 DELETE 才是「写数据」级 → 声明一致，放行
            assertThat(session.execute("DELETE FROM t WHERE id = 1", Danger.MUTATING))
                    .isNotNull();

            // 无 WHERE 的 DELETE 是「改结构」级 —— 声明成「写数据」也拦
            assertThatThrownBy(() -> session.execute("DELETE FROM t", Danger.MUTATING))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("危险等级不符");

            // 声明得比实际高（保守）→ 不拦
            assertThat(session.execute("SELECT 1", Danger.DESTRUCTIVE)).isNotNull();
        }
    }

    @Test
    void driverSideCheckAlsoGuardsParameterisedWrites() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER)");

            assertThatThrownBy(() -> session.update("DELETE FROM t", List.of(), Danger.READ_ONLY))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("危险等级不符");

            assertThat(session.update("INSERT INTO t VALUES (?)", List.of(1), Danger.MUTATING))
                    .isEqualTo(1);
        }
    }

    @Test
    void executeWithoutDeclarationClassifiesItself() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER)");
            // 默认实现自己分类 —— 声明与实际必然一致，不会被自己的门禁拦住。
            assertThat(session.execute("DELETE FROM t")).isNotNull();
        }
    }

    /**
     * ⭐ Q2：带参数执行 —— 值走参数化绑定，**不进 SQL 文本**。
     */
    @Test
    void executeBindsParameters() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER, name TEXT)");

            ExecutionResult result = session.execute(
                    "INSERT INTO t (id, name) VALUES (?, ?)",
                    java.util.List.of(7, "张三"), Danger.MUTATING);

            assertThat(result.updateCount()).isEqualTo(1);

            RowPage page = session.query("SELECT name FROM t WHERE id = ?", 10,
                    java.util.List.of(7));
            assertThat(page.rows()).hasSize(1);
            assertThat(page.rows().getFirst().get("name")).isEqualTo("张三");
        }
    }

    @Test
    void duplicateColumnLabelsGetDistinctKeys() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            RowPage page = session.query(
                    "SELECT 1 AS id, 2 AS id, 3 AS id_2", 200);

            assertThat(page.rows().get(0)).containsKeys("id", "id_2", "id_2_2");
        }
    }

    /**
     * 4.5-2 的补齐：{@code query()} 以前把截断标记丢了，
     * 调用方永远不知道自己拿到的是残缺结果。
     */
    @Test
    void queryPreservesTheTruncationFlag() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE seq (x INTEGER)");
            session.execute("""
                    WITH RECURSIVE n(x) AS (
                      SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 1500
                    )
                    INSERT INTO seq (x) SELECT x FROM n
                    """);

            RowPage truncated = session.query("SELECT x FROM seq", 200);
            assertThat(truncated.rowsTruncated())
                    .as("结果比请求的行数多，必须标出「被截断」")
                    .isTrue();
            assertThat(truncated.rowCount()).isEqualTo(200);

            RowPage exact = session.query("SELECT x FROM seq WHERE x <= 10", 200);
            assertThat(exact.rowsTruncated())
                    .as("没被截断时不能误报")
                    .isFalse();
            assertThat(exact.rowCount()).isEqualTo(10);
        }
    }
}
