package io.agenticdb.introspect;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U1：catalog（数据库）树的后端门禁。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>只有 MySQL 这类「切库要重连」的方言提供 catalog 清单</b> ——
 *       PG 的连接本身绑在一个库上，SQLite 没有库的概念。不假装有；</li>
 *   <li><b>系统库被过滤</b>（information_schema / mysql / performance_schema / sys）——
 *       它们是服务本身，不是用户的库；</li>
 *   <li><b>取不到就返回空列表</b>，不炸对象树。</li>
 * </ol>
 */
class CatalogTreeTest {

    @Test
    void onlyReconnectDialectsOfferCatalogs() {
        assertThat(DialectKind.MYSQL.dialect().switchesCatalogByReconnect())
                .as("MySQL 的 schema 就是数据库，切库只能重连").isTrue();
        assertThat(DialectKind.POSTGRESQL.dialect().switchesCatalogByReconnect())
                .as("PostgreSQL 切 schema 走 SET search_path，不用重连").isFalse();
        assertThat(DialectKind.SQLITE.dialect().switchesCatalogByReconnect())
                .as("SQLite 没有 catalog 概念").isFalse();

        assertThat(DialectKind.MYSQL.dialect().catalogsSql())
                .as("MySQL 从 information_schema.SCHEMATA 读（不要 SHOW 权限）")
                .contains("information_schema.SCHEMATA");
        assertThat(DialectKind.POSTGRESQL.dialect().catalogsSql()).isNull();
        assertThat(DialectKind.SQLITE.dialect().catalogsSql()).isNull();
    }

    @Test
    void sqliteHasNoCatalogsAtAll() throws SQLException {
        DbSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        try {
            assertThat(new SchemaService().listCatalogs(session))
                    .as("SQLite 没有 catalog —— 空列表，对象树不出库一层")
                    .isEmpty();
        } finally {
            session.close();
        }
    }

    @Test
    void postgresHasNoCatalogListEither() throws SQLException {
        // PG 的 FakeDialectSession.connection() 是 null —— 能跑到这里还没 NPE，
        // 就证明 catalogsSql() 为 null 时根本没碰连接。
        assertThat(new SchemaService().listCatalogs(FakeDialectSession.of(DialectKind.POSTGRESQL)))
                .isEmpty();
    }

    @Test
    void systemSchemasAreFilteredAndTheRestIsSorted() throws SQLException {
        List<String> databases = List.of("orders_db", "information_schema", "mysql",
                "audit_db", "sys", "performance_schema");
        DbSession session = catalogsSession(DialectKind.MYSQL, databases);

        assertThat(new SchemaService().listCatalogs(session))
                .as("系统库不进对象树；剩下的按名称排（大小写不敏感）")
                .containsExactly("audit_db", "orders_db");
    }

    @Test
    void emptyServerYieldsAnEmptyList() throws SQLException {
        assertThat(new SchemaService().listCatalogs(catalogsSession(DialectKind.MYSQL, List.of())))
                .isEmpty();
    }

    // ───────────────────────────── 测试基建 ─────────────────────────────

    /** 返回指定库名的假 MySQL 会话（连接走动态代理，不碰真数据库）。 */
    private static DbSession catalogsSession(DialectKind kind, List<String> names) {
        return new DbSession() {
            @Override
            public Connection connection() {
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement(names);
                    case "close" -> null;
                    case "isClosed" -> Boolean.FALSE;
                    default -> null;
                });
            }

            @Override
            public String label() {
                return "fake-mysql";
            }

            @Override
            public Dialect dialect() {
                return kind.dialect();
            }

            @Override
            public RowPage query(String sql, int maxRows, List<Object> params) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int update(String sql, List<Object> params) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ExecutionResult execute(String sql, Danger declared) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }

            @Override
            public void begin() {
            }

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }

            @Override
            public boolean inTransaction() {
                return false;
            }
        };
    }

    private static Statement statement(List<String> names) {
        int[] cursor = {-1};
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> resultSet(names, cursor);
                    case "close" -> null;
                    default -> null;
                });
    }

    private static ResultSet resultSet(List<String> names, int[] cursor) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> ++cursor[0] < names.size();
                    case "getString" -> names.get(cursor[0]);
                    case "close" -> null;
                    default -> null;
                });
    }
}
