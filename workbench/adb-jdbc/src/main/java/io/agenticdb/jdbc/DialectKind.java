package io.agenticdb.jdbc;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 支持的数据库方言。
 *
 * <p>这个枚举是<b>持久化的判别式</b>（{@code connections.json} 里的 {@code kind} 字段），
 * 而<b>行为</b>在 {@link Dialect} 的实现里。
 *
 * <p>加一种数据库 = 写一个 {@code Dialect} 实现 + 在这里加一项。
 * 以前行为散在五个文件的 {@code switch} 里，加一种库要同时改五处。
 *
 * <p>⭐ 顺序 = 连接向导里磁贴的顺序（{@code DialectKind.values()}）：
 * 先本地文件，再三种最常见的服务端库，最后是新加的两种。
 */
public enum DialectKind {

    SQLITE("sqlite", "SQLite", new SqliteDialect()),
    POSTGRESQL("postgresql", "PostgreSQL", new PostgresDialect()),
    MYSQL("mysql", "MySQL", new MySqlDialect()),
    MARIADB("mariadb", "MariaDB", new MariaDbDialect()),
    SQLSERVER("sqlserver", "SQL Server", new SqlServerDialect()),
    ORACLE("oracle", "Oracle", new OracleDialect());

    private final String id;
    private final String displayName;
    private final Dialect dialect;

    DialectKind(String id, String displayName, Dialect dialect) {
        this.id = id;
        this.displayName = displayName;
        this.dialect = dialect;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 这个方言的行为实现。 */
    public Dialect dialect() {
        return dialect;
    }

    public static DialectKind of(String id) {
        if (id == null) {
            throw new IllegalArgumentException("连接类型不能为空");
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (DialectKind kind : values()) {
            if (kind.id.equals(normalized) || kind.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的数据库类型: " + id);
    }

    /** 由方言实现反查枚举项。 */
    public static DialectKind of(Dialect dialect) {
        return dialect == null ? SQLITE : dialect.kind();
    }

    /** SQL 标识符引用。SQLite/PG 用双引号，MySQL 用反引号。 */
    public String quote(String identifier) {
        return dialect.quote(identifier);
    }

    /** 这个名字裸写进 SQL 是否还落回同一个对象（D-68：Oracle 折大写 / PG 折小写）。 */
    public boolean bareResolvesTo(String name) {
        return dialect.bareResolvesTo(name);
    }

    /**
     * 方言是否支持列注释（MySQL {@code COMMENT} / PostgreSQL {@code COMMENT ON}）。
     * SQLite 没有列注释概念 —— UI 必须据此收起注释列与输入框，
     * 否则就是永远填不进、读不出的死控件（4.5-22）。
     */
    public boolean supportsColumnComment() {
        return dialect.supportsColumnComment();
    }

    public String jdbcUrl(String host, int port, String database) {
        return dialect.jdbcUrl(host, port, database);
    }

    public String sqliteUrl(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath();
    }
}
