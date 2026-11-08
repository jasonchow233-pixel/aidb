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
 * ⭐ U1/N32：catalog（数据库）树的后端门禁。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>「一条连接绑一个库」的方言才提供 catalog 清单</b> ——
 *       MySQL / MariaDB、PostgreSQL、SQL Server 都是切库 = 换连接；
 *       SQLite 没有库的概念、Oracle 一条连接绑一个服务。不假装有；</li>
 *   <li><b>系统库被过滤</b>（方言各判各的：MySQL 的
 *       information_schema / mysql / performance_schema / sys，
 *       SQL Server 的 master / tempdb / model / msdb）——
 *       它们是服务本身，不是用户的库；</li>
 *   <li><b>取不到就返回空列表</b>，不炸对象树。</li>
 * </ol>
 */
class CatalogTreeTest {

    @Test
    void reconnectDialectsOfferCatalogs() {
        for (DialectKind kind : List.of(DialectKind.MYSQL, DialectKind.MARIADB,
                DialectKind.POSTGRESQL, DialectKind.SQLSERVER)) {
            assertThat(kind.dialect().switchesCatalogByReconnect())
                    .as("%s 的一条连接只落在一个库上，切库只能重连", kind)
                    .isTrue();
            assertThat(kind.dialect().catalogsSql())
                    .as("%s 要给出库清单 SQL", kind)
                    .isNotBlank();
        }
        assertThat(DialectKind.MYSQL.dialect().catalogsSql())
                .as("MySQL 从 information_schema.SCHEMATA 读（不要 SHOW 权限）")
                .contains("information_schema.SCHEMATA");
        assertThat(DialectKind.POSTGRESQL.dialect().catalogsSql())
                .as("PG 从 pg_database 读，模板库在 SQL 里排除")
                .contains("pg_database", "NOT datistemplate");
        assertThat(DialectKind.SQLSERVER.dialect().catalogsSql())
                .as("SQL Server 从 sys.databases 读")
                .contains("sys.databases");

        assertThat(DialectKind.SQLITE.dialect().switchesCatalogByReconnect())
                .as("SQLite 没有 catalog 概念").isFalse();
        assertThat(DialectKind.SQLITE.dialect().catalogsSql()).isNull();
        assertThat(DialectKind.ORACLE.dialect().switchesCatalogByReconnect())
                .as("Oracle 一条连接绑一个服务，没有可切的库名").isFalse();
        assertThat(DialectKind.ORACLE.dialect().catalogsSql()).isNull();
    }

    @Test
    void onlySessionSwitchDialectsGetASchemaPicker() {
        // UI 的 schema 切换器只在「切了真能影响查询」时才出现：
        // PG 的 SET search_path、Oracle 的 ALTER SESSION 都算；
        // MySQL 的 schema 就是库（入口在树上的库一层），SQL Server 没有会话级路径。
        assertThat(DialectKind.POSTGRESQL.dialect().switchesSchemaInSession()).isTrue();
        assertThat(DialectKind.ORACLE.dialect().switchesSchemaInSession()).isTrue();
        assertThat(DialectKind.MYSQL.dialect().switchesSchemaInSession()).isFalse();
        assertThat(DialectKind.MARIADB.dialect().switchesSchemaInSession()).isFalse();
        assertThat(DialectKind.SQLSERVER.dialect().switchesSchemaInSession()).isFalse();
        assertThat(DialectKind.SQLITE.dialect().switchesSchemaInSession()).isFalse();
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
    void oracleNeverTouchesTheConnectionForCatalogs() throws SQLException {
        // Oracle 的 FakeDialectSession.connection() 是 null —— 能跑到这里还没 NPE，
        // 就证明 catalogsSql() 为 null 时根本没碰连接。
        assertThat(new SchemaService().listCatalogs(FakeDialectSession.of(DialectKind.ORACLE)))
                .isEmpty();
    }

    @Test
    void mysqlSystemSchemasAreShownLikeNavicatAndEverythingIsSorted() throws SQLException {
        List<String> databases = List.of("orders_db", "information_schema", "mysql",
                "audit_db", "sys", "performance_schema");
        DbSession session = catalogsSession(DialectKind.MYSQL, databases);

        assertThat(new SchemaService().listCatalogs(session))
                .as("⭐ P-8（照 Navicat）：系统库与用户库同列，全部按名称排（大小写不敏感）")
                .containsExactly("audit_db", "information_schema", "mysql",
                        "orders_db", "performance_schema", "sys");
    }

    @Test
    void sqlServerSystemDatabasesAreShownLikeNavicat() throws SQLException {
        List<String> databases = List.of("master", "Sales", "tempdb", "model", "msdb",
                "Operations");
        DbSession session = catalogsSession(DialectKind.SQLSERVER, databases);

        assertThat(new SchemaService().listCatalogs(session))
                .as("⭐ P-8：master / tempdb / model / msdb 不再被藏，全部上树")
                .containsExactly("master", "model", "msdb", "Operations", "Sales", "tempdb");
    }

    @Test
    void postgresListsEveryRealDatabase() throws SQLException {
        DbSession session = catalogsSession(DialectKind.POSTGRESQL,
                List.of("postgres", "app", "audit"));

        assertThat(new SchemaService().listCatalogs(session))
                .as("模板库在 SQL 里已排除，这里拿到的都该留下")
                .containsExactly("app", "audit", "postgres");
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
