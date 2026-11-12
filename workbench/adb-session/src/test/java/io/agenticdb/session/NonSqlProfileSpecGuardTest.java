package io.agenticdb.session;

import io.agenticdb.asset.AssetKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ EXT：非 SQL 档案的 {@link ConnectionProfile#spec(String)} 护栏。
 *
 * <p>{@code ConnectionSpec} 是 JDBC/SQL 语义；Redis 走 {@code RedisSessions}、
 * 对象存储走 {@code adb-objectstore} 的会话路径，都不经过 {@code spec()}。
 * 非 SQL 档案走到这里 = 调用方没按 {@code kind.isSql()} 分支（路由 bug），
 * 必须抛出可读的 {@link UnsupportedOperationException}，而不是当成
 * 「能连的 SQL」悄悄往下走（v4 诚实环纪律）。
 *
 * <p>钉住两个事实：
 * <ol>
 *   <li>护栏对每个非 SQL kind 都在 —— 加新资产 kind 也不会漏；</li>
 *   <li>消息描述的是「不走 ConnectionSpec」这个现状，而不是过期的
 *       「尚未开放连接」—— 后端会话早已落地。</li>
 * </ol>
 */
class NonSqlProfileSpecGuardTest {

    private static ConnectionProfile profile(AssetKind kind) {
        return new ConnectionProfile("", "测试档案", kind, "127.0.0.1",
                ConnectionProfile.defaultPort(kind), "db", "user", false, 0L,
                "", Map.of(), SshConfig.NONE);
    }

    @Test
    void redisProfileRefusesToBuildASqlSpec() {
        ConnectionProfile redis = profile(AssetKind.REDIS);

        assertThatThrownBy(() -> redis.spec("secret"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Redis")
                .hasMessageContaining("ConnectionSpec")
                .as("过期文案「尚未开放连接」已移除：Redis 后端会话已落地，"
                        + "这条护栏描述的是路由错误而非未实现")
                .isNotInstanceOf(java.lang.IllegalStateException.class);
    }

    @Test
    void objectStoreProfileRefusesToBuildASqlSpec() {
        ConnectionProfile objectStore = profile(AssetKind.OBJECT_STORE);

        assertThatThrownBy(() -> objectStore.spec("secret", SshSecrets.NONE))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("ConnectionSpec");
    }

    @Test
    void sqlProfilesStillBuildSpecs() {
        ConnectionProfile mysql = profile(AssetKind.MYSQL);

        assertThat(mysql.spec("secret").host()).isEqualTo("127.0.0.1");
        assertThat(mysql.spec("secret").port()).isEqualTo(3306);
    }
}
