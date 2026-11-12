package io.agenticdb.redis;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 后端行为门禁（P1-1/P1-2）：真 Redis（testcontainers）上验证
 * 连接 / 鉴权 / {@code INFO keyspace} 列库 / SCAN 列 key 含类型与 TTL。
 *
 * <p>不 mock：要让「SCAN 的 TYPE 过滤」「pttl 的毫秒语义」「按类型取大小」这些真实行为
 * 在 CI 里也真跑一遍，而不是对着桩断言。
 */
class RedisScanTest {

    private static final String PASSWORD = "secret";

    static GenericContainer<?> redis;
    static String host;
    static int port;

    @BeforeAll
    static void start() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", PASSWORD);
        redis.start();
        host = redis.getHost();
        port = redis.getMappedPort(6379);
        seed();
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    /** 直接用 Lettuce 往 0 库与 1 库各写几种类型，供会话侧只读校验。 */
    private static void seed() {
        RedisURI uri = RedisURI.Builder.redis(host, port).withPassword(PASSWORD).build();
        try (RedisClient client = RedisClient.create(uri);
             var conn = client.connect()) {
            var cmd = conn.sync();
            cmd.select(0);
            cmd.set("user:1", "alice");
            cmd.set("user:2", "bob");
            cmd.rpush("queue:tasks", "a", "b", "c");
            cmd.sadd("tags", "x", "y");
            cmd.select(1);
            cmd.set("cfg:name", "value");
        }
    }

    private static RedisSession session() {
        ConnectionProfile profile = new ConnectionProfile(
                "t", "t", AssetKind.REDIS, host, port, "0", "",
                false, 0, "", Map.of(), SshConfig.NONE);
        return RedisSessions.connect(profile, PASSWORD);
    }

    @Test
    void databasesReportsOnlyNonEmptyDbs() {
        try (RedisSession session = session()) {
            List<Integer> dbs = session.databases();
            assertThat(dbs).contains(0).contains(1);
        }
    }

    @Test
    void scanReturnsKeysWithTypeAndSize() {
        try (RedisSession session = session()) {
            List<RedisKey> keys = session.scan(0, "", null);
            assertThat(keys).hasSize(4);

            RedisKey user1 = keys.stream().filter(k -> k.key().equals("user:1")).findFirst().orElseThrow();
            assertThat(user1.type()).isEqualTo("string");
            assertThat(user1.size()).isEqualTo(5); // "alice"
            assertThat(user1.ttlMillis()).isEqualTo(-1); // 不过期

            RedisKey queue = keys.stream().filter(k -> k.key().equals("queue:tasks")).findFirst().orElseThrow();
            assertThat(queue.type()).isEqualTo("list");
            assertThat(queue.size()).isEqualTo(3);

            RedisKey tags = keys.stream().filter(k -> k.key().equals("tags")).findFirst().orElseThrow();
            assertThat(tags.type()).isEqualTo("set");
            assertThat(tags.size()).isEqualTo(2);
        }
    }

    @Test
    void scanHonorsTypeFilter() {
        try (RedisSession session = session()) {
            List<RedisKey> onlyLists = session.scan(0, "", "list");
            assertThat(onlyLists).hasSize(1);
            assertThat(onlyLists.get(0).key()).isEqualTo("queue:tasks");
        }
    }

    @Test
    void scanHonorsPrefixFilter() {
        try (RedisSession session = session()) {
            List<RedisKey> users = session.scan(0, "user:", null);
            assertThat(users).hasSize(2);
            assertThat(users).allMatch(k -> k.key().startsWith("user:"));
        }
    }

    @Test
    void scanScopedToRequestedDb() {
        try (RedisSession session = session()) {
            // 库 1 只有一个 key，且不在库 0 里。
            List<RedisKey> db1 = session.scan(1, "", null);
            assertThat(db1).hasSize(1);
            assertThat(db1.get(0).key()).isEqualTo("cfg:name");
        }
    }
}
