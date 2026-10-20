package io.agenticdb.jdbc;

import java.nio.file.Path;
import java.util.Locale;

/** 支持的数据库方言。新增一个库 = 在这里加一项 + 一个 JDBC 驱动依赖。 */
public enum DialectKind {

    SQLITE("sqlite", "SQLite"),
    POSTGRESQL("postgresql", "PostgreSQL"),
    MYSQL("mysql", "MySQL");

    private final String id;
    private final String displayName;

    DialectKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
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

    /** SQL 标识符引用。SQLite/PG 用双引号，MySQL 用反引号。 */
    public String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return this == MYSQL
                ? "`" + identifier.replace("`", "``") + "`"
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public String jdbcUrl(String host, int port, String database) {
        return switch (this) {
            case SQLITE -> "jdbc:sqlite:" + database;
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
        };
    }

    public String sqliteUrl(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath();
    }
}
