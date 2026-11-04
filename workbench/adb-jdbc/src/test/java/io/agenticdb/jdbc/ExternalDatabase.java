package io.agenticdb.jdbc;

import java.util.Locale;
import java.util.Map;

/**
 * ⭐ 外部真库的连接参数。
 *
 * <p><b>凭据只从环境变量读，仓库里不存任何密码。</b>
 *
 * <pre>
 * MySQL：
 *   ADB_TEST_MYSQL_HOST=…  ADB_TEST_MYSQL_PORT=3306  ADB_TEST_MYSQL_DB=…
 *   ADB_TEST_MYSQL_USER=…  ADB_TEST_MYSQL_PASSWORD=…
 *
 * PostgreSQL：
 *   ADB_TEST_PG_HOST=…  ADB_TEST_PG_PORT=5432  ADB_TEST_PG_DB=…
 *   ADB_TEST_PG_USER=…  ADB_TEST_PG_PASSWORD=…
 *
 * MariaDB / SQL Server / Oracle（R46 起，六个库全覆盖）：
 *   ADB_TEST_MARIADB_{HOST,PORT,DB,USER,PASSWORD}
 *   ADB_TEST_MSSQL_{HOST,PORT,DB,USER,PASSWORD}
 *   ADB_TEST_ORACLE_{HOST,PORT,DB,USER,PASSWORD}   ← DB 是**服务名**（如 FREEPDB1）
 * </pre>
 *
 * <p>本地一键起五个库：{@code scripts/dbmatrix.sh up}，
 * 然后 {@code eval "$(scripts/dbmatrix.sh env)"} 把上面这些变量导进当前 shell。
 * 口令由脚本<b>现场生成</b>、只写进未跟踪的 {@code scripts/.dbmatrix.env} —— 仓库里不留任何密码。
 *
 * <p>没配对应变量时 {@link #mysql()} / {@link #postgres()} 返回 {@code null}，
 * 对应的真库测试**整个跳过** —— CI 与本地都不受影响。
 *
 * <p>这个类在 {@code adb-jdbc} 的<b>测试源码</b>里，通过 test-jar 共享给
 * {@code adb-introspect} 的真库测试，避免两处各写一份环境变量名。
 */
public record ExternalDatabase(
        DialectKind kind,
        String host,
        int port,
        String database,
        String user,
        String password) {

    public ExternalDatabase {
        host = host == null ? "" : host.trim();
        database = database == null ? "" : database.trim();
        user = user == null ? "" : user.trim();
    }

    /** MySQL 配了吗？ */
    public static ExternalDatabase mysql() {
        return read("MYSQL", DialectKind.MYSQL, 3306);
    }

    /** PostgreSQL 配了吗？ */
    public static ExternalDatabase postgres() {
        return read("PG", DialectKind.POSTGRESQL, 5432);
    }

    /** ⭐ R46：MariaDB 配了吗？（与 MySQL 同族，但**是另一个服务器**，必须单独验） */
    public static ExternalDatabase mariadb() {
        return read("MARIADB", DialectKind.MARIADB, 3306);
    }

    /** ⭐ R46：SQL Server 配了吗？ */
    public static ExternalDatabase sqlServer() {
        return read("MSSQL", DialectKind.SQLSERVER, 1433);
    }

    /** ⭐ R46：Oracle 配了吗？（{@code _DB} 这里是**服务名**，如 {@code FREEPDB1}） */
    public static ExternalDatabase oracle() {
        return read("ORACLE", DialectKind.ORACLE, 1521);
    }

    private static ExternalDatabase read(String prefix, DialectKind kind, int defaultPort) {
        String host = System.getenv("ADB_TEST_" + prefix + "_HOST");
        if (host == null || host.isBlank()) {
            return null;
        }
        return new ExternalDatabase(
                kind,
                host,
                integer(System.getenv("ADB_TEST_" + prefix + "_PORT"), defaultPort),
                System.getenv("ADB_TEST_" + prefix + "_DB"),
                System.getenv("ADB_TEST_" + prefix + "_USER"),
                System.getenv("ADB_TEST_" + prefix + "_PASSWORD"));
    }

    private static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 拼出连接档案。
     *
     * <p>⭐ 这里用**更短的超时**（5s / 8s）：外部真库是**第三方依赖**，
     * 服务器卡一下不该让测试干等 30 秒。
     * 应用本身的超时是 10s / 30s（见 {@code MySqlDialect}）。
     *
     * <p>⚠️ 但<b>读超时不能短过产品自己用的那个</b>——契约要测的是产品的配置，
     * 不是比产品更严的一份。Oracle 那一格 2026-11-04 量出了反例（D-49 的成因，
     * 三轮全量红在这里却查不到原因）：{@code DBMS_METADATA.GET_DDL} 在全量负载下
     * 实测要 **>8s**（同一个类单跑 22.00s、全量跑 43.26s，失败那一条用例本身 13.41s），
     * 于是驱动抛 {@code ORA-18730 Socket read timed out} —— 而
     * {@code OracleDialect.getDdl} 把 {@code SQLException} 吞成「读不到」返回
     * {@code null}，契约侧收到的就不是「超时」而是「Oracle 说能读 DDL 却没读回来」。
     * 单跑绿、全量红就是这么来的。现在 Oracle 的读超时直接取产品常量，两边不许各写一份。
     * ⚠️ 连接超时**保持 5s 短值**：连不上的主机在 connect 阶段就该快速失败，
     * 这一档的「快速失败」是本节存在的理由，动它等于把挂死风险请回来。
     * 其余四家的读超时没在三轮全量里红过，**没量就不改**。
     *
     * <p>⚠️ 参数名<b>不能一套通吃</b>：MySQL / MariaDB / PostgreSQL 认
     * {@code connectTimeout} + {@code socketTimeout}；SQL Server 的驱动<b>会拒绝</b>
     * 不认识的连接属性（直接抛 {@code The connection property … is not valid}），
     * 而它的超时本来就写在 URL 里（见 {@code SqlServerDialect.jdbcUrl}）；
     * Oracle 的超时只能走 {@code oracle.net.CONNECT_TIMEOUT} /
     * {@code oracle.jdbc.ReadTimeout} 两个属性名。所以这里按库给不同的键。
     */
    public ConnectionSpec spec() {
        return ConnectionSpec.server(kind, host, port, database, user, password, timeoutOptions(kind));
    }

    /**
     * 各家驱动的「短超时」写法。
     *
     * <p>包内可见是为了让 {@code DialectTimeoutTest} 能问「契约这一档比产品更短吗」——
     * 私有方法的话那条门禁只能去 grep 源码字符串，读不到值。
     */
    static Map<String, String> timeoutOptions(DialectKind kind) {
        return switch (kind) {
            case MYSQL, MARIADB, POSTGRESQL, SQLITE -> Map.of(
                    "connectTimeout", "5000",
                    "socketTimeout", "8000");
            case SQLSERVER -> Map.of();
            case ORACLE -> Map.of(
                    "oracle.net.CONNECT_TIMEOUT", "5000",
                    "oracle.jdbc.ReadTimeout", String.valueOf(OracleDialect.READ_TIMEOUT_MS));
        };
    }

    /** 日志里用的名字（**不含口令**）。 */
    public String label() {
        return kind.displayName() + " " + host + ":" + port + "/" + database;
    }

    /** 供 JUnit 的 {@code @EnabledIf} 用：MySQL 配了吗？ */
    public static boolean isMySqlConfigured() {
        return mysql() != null;
    }

    /** 供 JUnit 的 {@code @EnabledIf} 用：PostgreSQL 配了吗？ */
    public static boolean isPostgresConfigured() {
        return postgres() != null;
    }

    /** 供 JUnit 的 {@code @EnabledIf} 用：MariaDB 配了吗？ */
    public static boolean isMariaDbConfigured() {
        return mariadb() != null;
    }

    /** 供 JUnit 的 {@code @EnabledIf} 用：SQL Server 配了吗？ */
    public static boolean isSqlServerConfigured() {
        return sqlServer() != null;
    }

    /** 供 JUnit 的 {@code @EnabledIf} 用：Oracle 配了吗？ */
    public static boolean isOracleConfigured() {
        return oracle() != null;
    }

    /** 库名（MySQL 的 schema 就是数据库，可能为空 → 用 catalog 兜底）。 */
    public String databaseOrCatalog() {
        return database.isBlank() ? null : database;
    }

    /**
     * 同一台服务器上的<b>第二把库</b>（{@code ADB_TEST_<前缀>_DB2}），第 9 条契约要用。
     *
     * <p>形状由 {@link #withDatabase(String)} 换掉库名得到：主机、端口、用户、口令、
     * 超时参数<b>全部不变</b> —— 这正是产品里「切库」的那条路（
     * {@code ConnectionProfile#catalogVariant} 也只做这一件事），
     * 所以契约要验的是「只换库名，落点真的跟着换」，不是「换个地址连得上」。
     *
     * <p>返回 {@code null} 的两种情况含义不同，调用方要分开处理：
     * Oracle 这类<b>根本没有库层</b>的方言没有 DB2 键（那是产品事实），
     * 而「方言有库层但环境只配了一把库」是<b>配置缺</b>，跳过时要能说清缺什么。
     */
    public ExternalDatabase second() {
        String prefix = switch (kind) {
            case MYSQL -> "MYSQL";
            case MARIADB -> "MARIADB";
            case POSTGRESQL -> "PG";
            case SQLSERVER -> "MSSQL";
            case ORACLE, SQLITE -> null;
        };
        if (prefix == null) {
            return null;
        }
        String other = System.getenv("ADB_TEST_" + prefix + "_DB2");
        if (other == null || other.isBlank() || other.trim().equalsIgnoreCase(database)) {
            return null;
        }
        return withDatabase(other.trim());
    }

    /** 换个库名、别的一律不动。 */
    public ExternalDatabase withDatabase(String otherDatabase) {
        return new ExternalDatabase(kind, host, port, otherDatabase, user, password);
    }

    @Override
    public String toString() {
        return label().toLowerCase(Locale.ROOT);
    }
}
