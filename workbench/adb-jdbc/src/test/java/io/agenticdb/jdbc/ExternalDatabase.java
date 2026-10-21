package io.agenticdb.jdbc;

import java.util.Locale;

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
 * </pre>
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

    /** 拼出连接档案。 */
    public ConnectionSpec spec() {
        return kind == DialectKind.POSTGRESQL
                ? ConnectionSpec.postgres(host, port, database, user, password)
                : ConnectionSpec.mysql(host, port, database, user, password);
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

    /** 库名（MySQL 的 schema 就是数据库，可能为空 → 用 catalog 兜底）。 */
    public String databaseOrCatalog() {
        return database.isBlank() ? null : database;
    }

    @Override
    public String toString() {
        return label().toLowerCase(Locale.ROOT);
    }
}
