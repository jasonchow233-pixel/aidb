package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-27 门禁：SQL Server 要真能从目录里读回视图定义。
 *
 * <p>这条缺陷是「在 docker 里跑所有类型的库」跑出来的，而且它的症状不是「少一栏显示」：
 * {@code SqlServerDialect.readViewDdl} 原先直接 {@code return null}，而视图设计器开窗前
 * <b>必须</b>先读回库里的定义（读不到就不开窗 —— 用空 body 盖掉真定义更糟），
 * 于是在 SQL Server 上「设计视图」这个入口<b>一次都打不开</b>。
 * 更难看的是 {@link ViewDefinition} 的类注释一直写着「SQL Server 走
 * {@code sys.sql_modules.definition}」—— 文档描述的是一条没实现的路。
 *
 * <p>⚠️ 为什么不只靠真库契约：契约要 Docker + 环境变量，CI 上会跳过；
 * 而「方法整个是空的」这一类回归跟环境无关，任何时候都该拦住。
 * 这里的替身结果集给的是 SQL Server 真会交回的形状（{@code CREATE VIEW dbo.v AS SELECT …}），
 * 不是随手编的字符串。
 */
class SqlServerViewDefinitionTest {

    /** 替身会话：记下 prepareStatement 收到的 SQL，并按给定行回答 {@code getString(1)}。 */
    private static final class Recorder implements InvocationHandler {
        private final List<String> sqls = new ArrayList<>();
        private final List<String> rows;

        Recorder(List<String> rows) {
            this.rows = rows;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "prepareStatement" -> {
                    sqls.add((String) args[0]);
                    yield newProxy(PreparedStatement.class, (p, m, a) ->
                            "executeQuery".equals(m.getName())
                                    ? newProxy(ResultSet.class, cursor(rows))
                                    : defaultValue(m.getReturnType()));
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        List<String> sqls() {
            return sqls;
        }
    }

    /** 逐行回答 {@code next()} / {@code getString(int)} 的光标替身。 */
    private static InvocationHandler cursor(List<String> rows) {
        int[] position = {-1};
        return (proxy, method, args) -> switch (method.getName()) {
            case "next" -> position[0] + 1 < rows.size() && ++position[0] >= 0;
            case "getString" -> rows.get(position[0]);
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object newProxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(SqlServerViewDefinitionTest.class.getClassLoader(),
                new Class<?>[] {iface}, handler);
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

    /** SQL Server 交回的真实形状（不带 CREATE OR ALTER，就是当初写的那句）。 */
    private static final String STORED = "CREATE VIEW dbo.adb_view_demo AS\nSELECT id, id AS adb_marker FROM t";

    @Test
    void sqlServerIssuesARealCatalogQuery() throws Exception {
        Recorder recorder = new Recorder(List.of(STORED));
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        String definition = DialectKind.SQLSERVER.dialect()
                .readViewDdl(connection, "adb_view_demo");

        assertThat(recorder.sqls())
                .as("读视图定义必须真的发一条目录查询 —— 原先整方法是 return null，"
                        + "后果是 SQL Server 上「设计视图」永远不开窗")
                .hasSize(1);
        assertThat(recorder.sqls().get(0))
                .as("定义存在 sys.sql_modules 里，但只有联 sys.views 才保证读回来的是视图而不是函数/同义词")
                .contains("sys.sql_modules")
                .contains("sys.views");
        assertThat(definition).isEqualTo(STORED);
    }

    @Test
    void theReadBackShapeParsesIntoAQueryBody() throws Exception {
        // 设计器预填的是查询体那一段。读回来解析不出 body，等于开窗后是一个空编辑器，
        // 用户保存就把真定义盖掉 —— 所以「读得到」和「解析得出」要一起钉。
        Recorder recorder = new Recorder(List.of(STORED));
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        String definition = DialectKind.SQLSERVER.dialect().readViewDdl(connection, "adb_view_demo");

        assertThat(ViewDefinition.selectBody(definition))
                .as("VIEW 关键字之后的第一个 AS 才是分界；从后往前找会切进列别名")
                .isEqualTo("SELECT id, id AS adb_marker FROM t");
    }

    @Test
    void missingViewYieldsNullSoTheDesignerRefusesToOpen() throws Exception {
        Recorder recorder = new Recorder(List.of());
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        assertThat(DialectKind.SQLSERVER.dialect().readViewDdl(connection, "nope"))
                .as("查不到就是 null —— 空串会让界面走进「读到了但内容为空」，"
                        + "那是「读得到但库里真没有」的意思，不是「读不到」")
                .isNull();
    }

    @Test
    void blankDefinitionAlsoYieldsNull() throws Exception {
        // 权限不够时目录会给回空值；同样按「读不到」处理。
        Recorder recorder = new Recorder(List.of("   "));
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        assertThat(DialectKind.SQLSERVER.dialect().readViewDdl(connection, "adb_view_demo"))
                .isNull();
    }

    @Test
    void tableDdlStillComesFromIntrospectionNotTheDatabase() throws Exception {
        // readsDdlFromDatabase() 仍然是 false：SQL Server 没有 SHOW CREATE TABLE，
        // 表 DDL 由内省生成、界面标「等价 DDL」。补视图这条路不等于「表也能读库」，
        // 两个钩子各判各的，这条就是防下一次有人把它们顺手合并。
        Dialect dialect = DialectKind.SQLSERVER.dialect();

        assertThat(dialect.readsDdlFromDatabase()).isFalse();
        assertThat(dialect.readDdl(null, "any_table", false)).isNull();
    }
}
