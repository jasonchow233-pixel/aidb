package io.agenticdb.jdbc;

import io.agenticdb.model.QueryLimits;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ A10 门禁：单条语句的**执行超时**。
 *
 * <p>以前只有「连接超时」（握手卡住会失败），但**语句跑起来之后没有任何时限** ——
 * 一条全表扫描可以把界面挂到天荒地老，用户除了强杀进程没有别的办法。
 *
 * <p>本门禁断言两件事：
 * <ol>
 *   <li>超时值**真的被下发到语句上**（用代理对象捕获 {@code setQueryTimeout}）——
 *       只断言「字段存着 30」是不够的，忘了下发照样挂死；</li>
 *   <li>超时有**上限**，用户不能调到 10 小时。</li>
 * </ol>
 */
class QueryTimeoutTest {

    @Test
    void defaultTimeoutIsThirtySeconds() {
        assertThat(QueryLimits.DEFAULT_QUERY_TIMEOUT_SECONDS)
                .as("30 秒：跨机房聚合通常几秒内，超过就该改写查询")
                .isEqualTo(30);
    }

    @Test
    void theTimeoutIsClamped() {
        assertThat(QueryLimits.clampQueryTimeout(0)).as("0 = 不限制").isZero();
        assertThat(QueryLimits.clampQueryTimeout(-5)).as("负数 = 不限制").isZero();
        assertThat(QueryLimits.clampQueryTimeout(120)).isEqualTo(120);
        assertThat(QueryLimits.clampQueryTimeout(999_999))
                .as("即便用户自己调，也不能超过 10 分钟")
                .isEqualTo(QueryLimits.MAX_QUERY_TIMEOUT_SECONDS);
    }

    @Test
    void everyStatementGetsTheTimeoutApplied() throws Exception {
        AtomicInteger applied = new AtomicInteger(-1);
        Statement statement = (Statement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    if ("setQueryTimeout".equals(method.getName())) {
                        applied.set((Integer) args[0]);
                    }
                    return defaultValue(method.getReturnType());
                });

        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaData();
                    }
                    if ("createStatement".equals(method.getName())) {
                        return statement;
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        return (PreparedStatement) Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[]{PreparedStatement.class},
                                (p2, m2, a2) -> {
                                    if ("setQueryTimeout".equals(m2.getName())) {
                                        applied.set((Integer) a2[0]);
                                    }
                                    return defaultValue(m2.getReturnType());
                                });
                    }
                    return defaultValue(method.getReturnType());
                });

        JdbcSession session = JdbcSession.wrap(connection, "fake");
        session.execute("SELECT 1");

        assertThat(applied.get())
                .as("超时必须真的下发到语句上 —— 忘了下发等于没做")
                .isEqualTo(QueryLimits.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    @Test
    void theTimeoutCanBeConfiguredPerConnection() throws Exception {
        // ⭐ 用**真 SQLite** 走一遍 open()：options 里的 queryTimeout 要生效。
        //    （假连接走不到 open() 的 options 解析路径。）
        Path file = Files.createTempDirectory("timeout").resolve("t.db");
        DbSession session = JdbcSession.open(new ConnectionSpec(DialectKind.SQLITE, "", 0,
                file.toAbsolutePath().toString(), null, null, Map.of("queryTimeout", "7")));
        try {
            assertThat(((JdbcSession) session).queryTimeoutSeconds())
                    .as("options 里的 queryTimeout（秒）要生效")
                    .isEqualTo(7);
        } finally {
            session.close();
        }
    }

    /** 假的元数据：只回答「我是什么库」。 */
    private static DatabaseMetaData metaData() {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                QueryTimeoutTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getURL".equals(method.getName())) {
                        return "jdbc:sqlite:fake";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
