package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-25 门禁：{@code SHOW CREATE …} 的定义体按<b>列名</b>取，不按位置猜。
 *
 * <p>这条缺陷是「在 docker 里跑所有类型的库」跑出来的：
 * {@code SHOW CREATE PROCEDURE} 的列是
 * {@code Procedure | sql_mode | Create Procedure | …}（定义体在第 3 列），
 * 但 {@code SHOW CREATE EVENT} 中间多插了一列 {@code time_zone}：
 * {@code Event | sql_mode | time_zone | Create Event | …} —— 第 3 列是 {@code time_zone}，
 * 值是字符串 {@code "SYSTEM"}。于是 MySQL / MariaDB 里<b>每个事件</b>的定义面板
 * 都显示「SYSTEM」，看着像读到了东西，实际一行业务 SQL 都没有。
 *
 * <p>⚠️ 本文件用的是<b>真库实测到的列名与列序</b>做的替身结果集，
 * 不是随手编的三个字符串 —— 列序一改，这条门禁就失去意义。
 * 之所以不只在 {@code MySqlIntegrationTest} 里验：那条要环境、CI 上会跳过，
 * 而这一个是纯装置问题，任何时候都该拦住。
 */
class ShowCreateBodyColumnTest {

    /** MySQL 8.4 实测：SHOW CREATE EVENT 的七个列。 */
    private static final String[] EVENT_COLUMNS = {
            "Event", "sql_mode", "time_zone", "Create Event",
            "character_set_client", "collation_connection", "Database Collation"};

    private static final String[] EVENT_VALUES = {
            "nightly_clean", "", "SYSTEM",
            "CREATE DEFINER=`root`@`localhost` EVENT `nightly_clean` "
                    + "ON SCHEDULE EVERY 1 HOUR DO DELETE FROM logs",
            "latin1", "latin1_swedish_ci", "utf8mb4_0900_ai_ci"};

    /** SHOW CREATE PROCEDURE 少一列 time_zone，定义体在第 3 列。 */
    private static final String[] PROCEDURE_COLUMNS = {
            "Procedure", "sql_mode", "Create Procedure",
            "character_set_client", "collation_connection", "Database Collation"};

    private static final String[] PROCEDURE_VALUES = {
            "sum_orders", "ONLY_FULL_GROUP_BY",
            "CREATE DEFINER=`root`@`localhost` PROCEDURE `sum_orders`() BEGIN SELECT 1; END",
            "latin1", "latin1_swedish_ci", "utf8mb4_0900_ai_ci"};

    /** 只跑到「读一行、按列号取值」这条链上的替身会话。 */
    private static Connection fakeConnection(String[] columns, String[] values) {
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                ShowCreateBodyColumnTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                new Handler(columns, values));
        Statement statement = (Statement) Proxy.newProxyInstance(
                ShowCreateBodyColumnTest.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> "executeQuery".equals(method.getName())
                        ? resultSet : defaultValue(method.getReturnType()));
        return (Connection) Proxy.newProxyInstance(
                ShowCreateBodyColumnTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> "createStatement".equals(method.getName())
                        ? statement : defaultValue(method.getReturnType()));
    }

    private static final class Handler implements InvocationHandler {
        private final String[] columns;
        private final String[] values;

        Handler(String[] columns, String[] values) {
            this.columns = columns;
            this.values = values;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> true;
                case "getMetaData" -> Proxy.newProxyInstance(
                        ShowCreateBodyColumnTest.class.getClassLoader(),
                        new Class<?>[] {ResultSetMetaData.class},
                        (p, m, a) -> switch (m.getName()) {
                            case "getColumnCount" -> columns.length;
                            case "getColumnLabel", "getColumnName" ->
                                    columns[(Integer) a[0] - 1];
                            default -> defaultValue(m.getReturnType());
                        });
                case "getString" -> args[0] instanceof Integer index
                        ? values[index - 1] : values[0];
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }

    @Test
    void eventBodyComesFromTheCreateEventColumnNotTheThirdOne() throws Exception {
        Dialect dialect = DialectKind.MYSQL.dialect();

        String body = dialect.readEventDefinition(
                fakeConnection(EVENT_COLUMNS, EVENT_VALUES), "nightly_clean");

        assertThat(body)
                .as("第 3 列是 time_zone、值是「SYSTEM」——按位置猜就把调度时区当成定义体显示了")
                .contains("ON SCHEDULE EVERY 1 HOUR")
                .doesNotContain("SYSTEM");
    }

    @Test
    void mariadbReadsTheSameShape() throws Exception {
        // MariaDB 的 SHOW CREATE EVENT 列序与 MySQL 一致（实测同一个位置），
        // 但它是另一个发行版，值得单独钉一次。
        Dialect dialect = DialectKind.MARIADB.dialect();

        assertThat(dialect.readEventDefinition(
                fakeConnection(EVENT_COLUMNS, EVENT_VALUES), "nightly_clean"))
                .contains("EVENT `nightly_clean`");
    }

    @Test
    void routineBodyStillComesFromTheCreateProcedureColumn() throws Exception {
        Dialect dialect = DialectKind.MYSQL.dialect();

        assertThat(dialect.readRoutineDefinition(
                fakeConnection(PROCEDURE_COLUMNS, PROCEDURE_VALUES), "sum_orders", true))
                .contains("PROCEDURE `sum_orders`()");
    }

    @Test
    void missingCreateColumnYieldsEmptyInsteadAGuess() throws Exception {
        // 没有 Create … 这一列时返回空串（界面显示「—」），
        // 不许退回某个位置：猜出来的文本会被当成真的定义读下去。
        String[] unexpected = {"Event", "sql_mode", "time_zone"};
        String[] unexpectedValues = {"nightly_clean", "", "SYSTEM"};

        assertThat(DialectKind.MYSQL.dialect().readEventDefinition(
                fakeConnection(unexpected, unexpectedValues), "nightly_clean"))
                .isEmpty();
    }
}
