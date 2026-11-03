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
 * ⭐ D-38 门禁：{@code readDdl(..., view=true)} 在「表读不出、视图读得出」的两家上必须自己兜住。
 *
 * <p>这条缺陷不是截图通道拍出来的，就是**截图本身**拍出来的：
 * {@code p6-design-view-pg} / {@code p6-design-view-sqlserver} 两帧拍到的是主窗口 +
 * 状态栏「读不出这个视图的查询体」，因为 PG / SQL Server 的 {@code readDdl} 整方法
 * {@code return null}，视图原文只在 {@code readViewDdl} 那条路上。
 *
 * <p>而真库契约当时是**绿的** —— 它自己写了「readDdl 空了就再问一次 readViewDdl」，
 * 于是契约走的路和界面走的路不是同一条。后果分两级：
 * <ul>
 *   <li>视图设计器在 PG / SQL Server 上一次都打不开（读不到就不开窗，这是对的）；</li>
 *   <li>整表重建的「先拆依赖视图、建完再挂回去」清单只调 {@code readDdl}，
 *       读到 null 就当没有依赖视图 —— {@code DROP TABLE} 把视图级联带走之后没人重建它。</li>
 * </ul>
 * 兜底收进方言，界面与契约才共用一条路；这里钉的就是这条路。
 */
class DialectViewReadDdlTest {

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
        return Proxy.newProxyInstance(DialectViewReadDdlTest.class.getClassLoader(),
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

    /** {@code pg_get_viewdef} 交回的是裸查询体，由方言包成完整一句。 */
    private static final String PG_BARE = "SELECT users.id, users.name FROM users WHERE (users.active = true);";

    /** {@code sys.sql_modules.definition} 交回的是当初写的原句。 */
    private static final String MSSQL_STORED = "CREATE VIEW dbo.v_active_users AS\nSELECT id, name FROM users WHERE active = 1";

    @Test
    void postgresReadDdlForViewsIssuesTheCatalogQuery() throws Exception {
        Recorder recorder = new Recorder(List.of(PG_BARE));
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        String definition = DialectKind.POSTGRESQL.dialect().readDdl(connection, "v_active_users", true);

        assertThat(recorder.sqls())
                .as("PG 的视图原文在 pg_get_viewdef 里，readDdl(view=true) 必须自己去问，"
                        + "不能把这件事留给每个调用方兜")
                .hasSize(1);
        assertThat(recorder.sqls().get(0)).contains("pg_get_viewdef");
        assertThat(ViewDefinition.selectBody(definition))
                .as("读回来还要解析得出查询体 —— 解析不出等于设计器不开窗")
                .isEqualTo("SELECT users.id, users.name FROM users WHERE (users.active = true)");
    }

    @Test
    void sqlServerReadDdlForViewsIssuesTheCatalogQuery() throws Exception {
        Recorder recorder = new Recorder(List.of(MSSQL_STORED));
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        String definition = DialectKind.SQLSERVER.dialect().readDdl(connection, "v_active_users", true);

        assertThat(recorder.sqls()).hasSize(1);
        assertThat(recorder.sqls().get(0)).contains("sys.sql_modules").contains("sys.views");
        assertThat(ViewDefinition.selectBody(definition))
                .isEqualTo("SELECT id, name FROM users WHERE active = 1");
    }

    @Test
    void tablePathStillYieldsNullOnBoth() throws Exception {
        // ⚠️ 补的是视图这条路，不是「这两家也能从库里读表 DDL」：
        // 两家都没有 SHOW CREATE TABLE，表 DDL 仍由内省生成、界面标「等价 DDL」。
        // 这条是防下一次有人把 view 的兜底顺手扩到 table 上。
        for (DialectKind kind : List.of(DialectKind.POSTGRESQL, DialectKind.SQLSERVER)) {
            Dialect dialect = kind.dialect();

            assertThat(dialect.readsDdlFromDatabase())
                    .as("%s 的表 DDL 仍然不是从库里读的", kind.id())
                    .isFalse();
            assertThat(dialect.readDdl(null, "users", false))
                    .as("%s：table 那条路仍然交 null，由内省拼等价 DDL", kind.id())
                    .isNull();
        }
    }

    @Test
    void missingViewStillYieldsNull() throws Exception {
        // 视图不存在时不能退成空串：空串在界面是「读到了但正文是空的」，
        // 用户一保存就把真定义盖掉 —— 读不到必须是 null，让设计器拒绝开窗。
        Recorder recorder = new Recorder(List.of());
        Connection connection = (Connection) newProxy(Connection.class, recorder);

        assertThat(DialectKind.POSTGRESQL.dialect().readDdl(connection, "nope", true)).isNull();
    }
}
