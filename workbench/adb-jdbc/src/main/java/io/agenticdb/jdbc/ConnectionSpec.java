package io.agenticdb.jdbc;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 打开连接所需的最小信息。
 *
 * <p>⛔ {@link #toString()} 不包含口令，日志与异常里可以安全打印。
 */
public record ConnectionSpec(
        DialectKind kind,
        String host,
        int port,
        String database,
        String username,
        String password,
        Map<String, String> options) {

    public ConnectionSpec {
        Objects.requireNonNull(kind, "kind");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static ConnectionSpec sqlite(Path file) {
        return new ConnectionSpec(DialectKind.SQLITE, "", 0,
                file.toAbsolutePath().toString(), null, null, Map.of());
    }

    public static ConnectionSpec sqliteMemory() {
        return new ConnectionSpec(DialectKind.SQLITE, "", 0, ":memory:", null, null, Map.of());
    }

    public static ConnectionSpec server(DialectKind kind, String host, int port,
                                        String database, String username, String password) {
        return new ConnectionSpec(kind, host, port, database, username, password, Map.of());
    }

    /**
     * 带附加 JDBC 参数的连接。
     *
     * <p>例如真库联调时用更短的超时：外部服务器卡一下，
     * 不该让测试干等 30 秒。
     */
    public static ConnectionSpec server(DialectKind kind, String host, int port,
                                        String database, String username, String password,
                                        Map<String, String> options) {
        return new ConnectionSpec(kind, host, port, database, username, password,
                options == null ? Map.of() : Map.copyOf(options));
    }

    /** PostgreSQL 连接。 */
    public static ConnectionSpec postgres(String host, int port, String database,
                                          String username, String password) {
        return server(DialectKind.POSTGRESQL, host, port, database, username, password);
    }

    /** MySQL 连接。 */
    public static ConnectionSpec mysql(String host, int port, String database,
                                       String username, String password) {
        return server(DialectKind.MYSQL, host, port, database, username, password);
    }

    public String jdbcUrl() {
        return kind.jdbcUrl(host, port, database);
    }

    @Override
    public String toString() {
        return "ConnectionSpec[" + kind.id() + ", " + host + ":" + port + "/" + database + "]";
    }
}
