package io.agenticdb.jdbc;

import io.agenticdb.model.Danger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ T16：危险分级<b>第三层</b>回归门禁 —— 声明等级低于语句实际等级时，驱动侧必须
 * 在<b>碰数据库之前</b>拒绝，语句一次都不许真的执行。
 *
 * <p>前两层（服务端分级 + UI 确认）都在 UI 侧；这一层在数据通路侧，
 * 不依赖 UI 是否走了正确路径。{@link JdbcSession#execute(String, Danger)} 在
 * {@code connection.createStatement()} 之前先调 {@link DbSession#requireDeclared}，
 * 所以「拒了」必须等于「一次都没执行」。
 *
 * <p><b>变异验证</b>：把 {@link DbSession#requireDeclared(Danger, String)} 改成
 * 直接 {@code return;}（放行），本文件的
 * {@link #underDeclaredWritesAreRejectedBeforeReachingTheDriver()} 必须变红 ——
 * 那是 {@code execute(sql, declared)} 唯一的拦截点，放行后 {@code DELETE} / {@code DROP}
 * 会真的走到 {@code Statement.execute}，异常不再抛、计数器也不再是 0。
 *
 * <p>为什么用计数代理连接：参考同目录 {@code JdbcSessionTest.SpyConnection} 的
 * {@code Proxy} 手法 —— 真内存 SQLite 只用来让 {@link JdbcSession#wrap} 的
 * {@code inferDialect} 拿到 URL，执行与否由代理计数说了算，不依赖库里真有表。
 */
class DangerDeclaredGateTest {

    /** DELETE / DROP 声明成 READ_ONLY：拒，且计数为 0（语句没真的执行）。 */
    @Test
    void underDeclaredWritesAreRejectedBeforeReachingTheDriver() throws SQLException {
        try (Connection real = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            CountingConnection spy = new CountingConnection(real);
            JdbcSession session = JdbcSession.wrap(spy.connection(), "danger-gate");

            assertThatThrownBy(() -> session.execute("DELETE FROM t", Danger.READ_ONLY))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("危险等级不符")
                    .hasMessageContaining("已被拒绝");
            assertThatThrownBy(() -> session.execute("DROP TABLE t", Danger.READ_ONLY))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("危险等级不符");

            assertThat(spy.executions.get())
                    .as("拒了就不许碰驱动 —— 两条写语句一次都不能执行")
                    .isZero();
            assertThat(spy.statements.get())
                    .as("连 createStatement 都不该调（拦截在取得 Statement 之前）")
                    .isZero();
        }
    }

    /** 对照：等级声明正确（或保守地更高）时语句要真的执行，门禁不许误伤。 */
    @Test
    void aCorrectlyDeclaredStatementStillExecutes() throws SQLException {
        try (Connection real = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            CountingConnection spy = new CountingConnection(real);
            JdbcSession session = JdbcSession.wrap(spy.connection(), "danger-gate");

            // 等级一致：SELECT ↔ READ_ONLY
            assertThat(session.execute("SELECT 1", Danger.READ_ONLY)).isNotNull();
            assertThat(spy.executions.get()).as("正确声明的查询要真的执行").isPositive();

            int before = spy.executions.get();
            assertThat(session.execute("CREATE TABLE t (id INTEGER)", Danger.DESTRUCTIVE))
                    .isNotNull();
            assertThat(spy.executions.get()).as("按真实等级声明的 DDL 要真的执行")
                    .isGreaterThan(before);

            before = spy.executions.get();
            assertThat(session.execute("DELETE FROM t", Danger.DESTRUCTIVE)).isNotNull();
            assertThat(spy.executions.get()).as("按真实等级声明的 DELETE 要真的执行")
                    .isGreaterThan(before);
        }
    }

    /** 直接钉住复查函数本身：低声明必拒，保守高声明不拦（变异点的最小覆盖）。 */
    @Test
    void requireDeclaredItselfRejectsUnderDeclarations() {
        assertThatThrownBy(() -> DbSession.requireDeclared(Danger.READ_ONLY, "DROP TABLE t"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("危险等级不符");
        assertThatThrownBy(() -> DbSession.requireDeclared(Danger.MUTATING, "DELETE FROM t"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("危险等级不符");
        assertThatCode(() -> DbSession.requireDeclared(Danger.DESTRUCTIVE, "SELECT 1"))
                .doesNotThrowAnyException();
    }

    /**
     * 观测用的连接代理：包装真连接，记录 {@code createStatement} 与
     * {@code Statement.execute} 的调用次数（其余方法原样转发）。
     */
    private static final class CountingConnection implements InvocationHandler {

        private final Connection real;
        final AtomicInteger statements = new AtomicInteger();
        final AtomicInteger executions = new AtomicInteger();

        CountingConnection(Connection real) {
            this.real = real;
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                Object result = method.invoke(real, args);
                if ("createStatement".equals(method.getName())
                        && result instanceof Statement statement) {
                    statements.incrementAndGet();
                    return countingStatement(statement);
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private Statement countingStatement(Statement delegate) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if ("execute".equals(method.getName())) {
                            executions.incrementAndGet();
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
