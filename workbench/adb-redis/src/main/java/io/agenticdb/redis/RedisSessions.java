package io.agenticdb.redis;

import io.agenticdb.session.ConnectionProfile;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Redis 会话工厂（P1-1）。
 *
 * <p>从 {@link ConnectionProfile} 直接读 host / port / database（库下标）/ username，口令由调用方
 * 从凭据库取出后传入（不进档案，见 {@code ConnectionProfile} 的明文禁令）。SSL 由 options 里的
 * {@code ssl=true} 触发。
 *
 * <p>⚠ SSH 隧道（{@code profile.usesSshTunnel()}）暂未接入：Redis over SSH 复用现有纯 Java SSH 的
 * 本地端口转发，需在 {@code P1-3} 把隧道端点接到 Lettuce 的本地 host/port；当前直连。
 * 这与 {@link io.agenticdb.asset.RedisFamily} 仍是 {@code connectable()=false} 的 WIP 状态一致，
 * 不会「摆出能连却没实现」的假控件（诚实环纪律）。
 */
public final class RedisSessions {

    private RedisSessions() {
    }

    public static RedisSession connect(ConnectionProfile profile, String password) {
        RedisURI.Builder b = RedisURI.Builder.redis(profile.host(), profile.port());

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
        StatefulRedisConnection<String, String> connection = client.connect();
        return new LettuceRedisSession(client, connection);
    }

    private static boolean isTls(ConnectionProfile profile) {
        String ssl = profile.options().get("ssl");
        return Boolean.parseBoolean(ssl);
    }
}
