package io.agenticdb.jdbc;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 打开连接所需的最小信息。
 *
 * <p>⛔ {@link #toString()} 不包含口令，日志与异常里可以安全打印。
 *
 * <p>⭐ G5a：{@link #ssh()} 非空 = 这条连接<b>先经 SSH 跳板机</b>。
 * 地址与端口是「数据库在哪」的<b>逻辑</b>答案（跳板机那侧按它去开 TCP），
 * 驱动真正连的是 {@link SshTunnel#localHost()} 上那个临时端口 —— 所以
 * {@link #jdbcUrl()} 量的始终是逻辑地址，不是隧道端口（列表、日志、排查都要看前者）。
 */
public record ConnectionSpec(
        DialectKind kind,
        String host,
        int port,
        String database,
        String username,
        String password,
        Map<String, String> options,
        SshEndpoint ssh) {

    public ConnectionSpec {
        Objects.requireNonNull(kind, "kind");
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** ⭐ G5a：不用隧道的老写法（新增组件不该逼所有调用点跟着改）。 */
    public ConnectionSpec(DialectKind kind, String host, int port, String database,
                          String username, String password, Map<String, String> options) {
        this(kind, host, port, database, username, password, options, null);
    }

    /** 加/换跳板机；其余参数原样。 */
    public ConnectionSpec withSsh(SshEndpoint value) {
        return new ConnectionSpec(kind, host, port, database, username, password, options, value);
    }

    /** 这条连接是否要走 SSH 隧道（半填的跳板机表单不算）。 */
    public boolean usesSshTunnel() {
        return ssh != null && ssh.usable();
    }

    /**
     * ⭐ G5a：隧道那头给出的地址 —— 只有驱动该看到它。
     *
     * <p>这里<b>把 ssh 清空</b>，不是手滑漏了：{@code usesSshTunnel()} 的定义是
     * 「打开这条 spec 时要不要起隧道」，而隧道端口<b>已经在</b>跳板机里面了。
     * 留着它，谁哪天把 effective spec 再递一次 {@code JdbcSession.open}（重连接、
     * 连接池里的重试都会做这种事），就会在本机套两层隧道 —— 而且第二层的
     * 「数据库地址」是 127.0.0.1 的隧道端口，症状是连不上，最难查。
     */
    public ConnectionSpec overTunnel(String localHost, int localPort) {
        return new ConnectionSpec(kind, localHost, localPort, database, username, password,
                options, null);
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
