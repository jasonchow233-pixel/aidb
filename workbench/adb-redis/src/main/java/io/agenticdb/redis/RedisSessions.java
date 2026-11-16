package io.agenticdb.redis;

import io.agenticdb.jdbc.SshEndpoint;
import io.agenticdb.jdbc.SshTunnel;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshSecrets;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

import java.sql.SQLException;

/**
 * Redis 会话工厂（P1-1）。
 *
 * <p>从 {@link ConnectionProfile} 直接读 host / port / database（库下标）/ username，口令由调用方
 * 从凭据库取出后传入（不进档案，见 {@code ConnectionProfile} 的明文禁令）。SSL 由 options 里的
 * {@code ssl=true} 触发。
 *
 * <p>SSH 隧道（{@code profile.usesSshTunnel()}）：复用与 SQL 连接同一套纯 Java SSH
 * （Apache MINA sshd，见 {@code SshTunnel}），不依赖机器上装没装 openssh。启用时先开一条
 * 本地端口转发到<b>跳板机眼里</b>的 Redis 地址（{@code profile.host()}:{@code profile.port()}），
 * 再让 Lettuce 连 {@code 127.0.0.1} 上的临时本地端口 —— 驱动那一侧只看到环回地址，
 * 加密与跳板都由隧道负责。隧道失败时抛出的错误原文来自 {@link SshTunnel}（英文，与
 * {@code ErrorClassifier} 的「驱动原文」约定一致），界面按 {@code redis.keys.error} 渲染。
 */
public final class RedisSessions {

    private RedisSessions() {
    }

    /** 直连（不走隧道）的便捷入口，等价于 {@link #connect(ConnectionProfile, String, SshSecrets)} 传 {@link SshSecrets#NONE}。 */
    public static RedisSession connect(ConnectionProfile profile, String password) {
        return connect(profile, password, SshSecrets.NONE);
    }

    /**
     * 建 Redis 会话；档案启用 SSH 隧道时先开本地端口转发，再让 Lettuce 走本地端口连 Redis。
     *
     * @param profile    档案（host/port 是<b>跳板机眼里</b>的 Redis 地址；SSH 配置另存于 {@code profile.ssh()}）
     * @param password   Redis 登录口令（从凭据库取，不进档案）
     * @param sshSecrets SSH 跳板机的口令 / 私钥口令短语（从凭据库取，档案里只有非秘密部分）
     */
    public static RedisSession connect(ConnectionProfile profile, String password, SshSecrets sshSecrets) {
        SshTunnel tunnel = null;
        String host = profile.host();
        int port = profile.port();
        if (profile.usesSshTunnel()) {
            SshEndpoint endpoint = profile.ssh().endpoint(sshSecrets);
            if (endpoint != null) {
                try {
                    // remoteHost 是跳板机眼里的 Redis 地址：档案里填的 host/port 原样递过去。
                    tunnel = SshTunnel.open(endpoint, profile.host(), profile.port());
                } catch (SQLException e) {
                    // 认证 / 主机密钥 / 连不上跳板机：直接报隧道自己的错误，不伪装成 Redis 断线。
                    throw new IllegalStateException(
                            "SSH tunnel could not be established: " + e.getMessage(), e);
                }
                host = tunnel.localHost();
                port = tunnel.localPort();
            }
        }

        RedisURI.Builder b = RedisURI.Builder.redis(host, port);

        String db = profile.database();
        if (db != null && !db.isBlank()) {
            try {
                b.withDatabase(Integer.parseInt(db.trim()));
            } catch (NumberFormatException ignored) {
                // 非数字（如占位空串）：留给 Redis 默认库 0。
            }
        }

        if (password != null && !password.isBlank()) {
            if (profile.username() != null && !profile.username().isBlank()) {
                b.withAuthentication(profile.username(), password);
            } else {
                b.withPassword(password);
            }
        }

        if (isTls(profile)) {
            b.withSsl(true);
        }

        RedisURI uri = b.build();
        RedisClient client = RedisClient.create(uri);
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            return new LettuceRedisSession(client, connection, tunnel);
        } catch (RuntimeException e) {
            // Lettuce 连不上时，已开的隧道也要关掉，否则本地端口与 SSH 会话泄漏。
            if (tunnel != null) {
                tunnel.close();
            }
            client.shutdown();
            throw e;
        }
    }

    private static boolean isTls(ConnectionProfile profile) {
        String ssl = profile.options().get("ssl");
        return Boolean.parseBoolean(ssl);
    }
}
