package io.agenticdb.redis;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.model.Danger;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 写安全门禁（P1-6 / P1-7）。
 *
 * <p>两层都验：
 * <ol>
 *   <li><b>分级映射</b>：{@link RedisDanger#level} 把命令名映射到 {@link Danger} 等级，
 *       与 SQL 的 {@code DangerClassifier} 同语义（未知命令 fail-closed 到 ADMIN）。
 *       这是「写安全门禁」的可测核心 —— UI 据此决定是否弹确认框。</li>
 *   <li><b>真实变异</b>：在真 Redis（testcontainers）上验证 SET/DEL/FLUSHDB/FLUSHALL
 *       真的改了数据、且 FLUSHDB 只清当前库、FLUSHALL 跨库清空。</li>
 * </ol>
 */
class RedisMutateTest {

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
    }

    @AfterAll
    static void stop() {
        if (redis != null) {
            redis.stop();
        }
    }

    private RedisSession session() {
        ConnectionProfile profile = new ConnectionProfile(
                "t", "t", AssetKind.REDIS, host, port, "0", "",
                false, 0, "", Map.of(), SshConfig.NONE);
        return RedisSessions.connect(profile, PASSWORD);
    }

    // ───────────────────────── 分级映射 ─────────────────────────

    @Test
    void readOpsAreReadOnly() {
        assertThat(RedisDanger.level("GET")).isEqualTo(Danger.READ_ONLY);
        assertThat(RedisDanger.level("SCAN")).isEqualTo(Danger.READ_ONLY);
        assertThat(RedisDanger.level("TTL")).isEqualTo(Danger.READ_ONLY);
        assertThat(RedisDanger.level("info")).isEqualTo(Danger.READ_ONLY);
    }

    @Test
    void writeOpsAreMutating() {
        assertThat(RedisDanger.level("SET")).isEqualTo(Danger.MUTATING);
        assertThat(RedisDanger.level("EXPIRE")).isEqualTo(Danger.MUTATING);
        assertThat(RedisDanger.level("HSET")).isEqualTo(Danger.MUTATING);
        assertThat(RedisDanger.level("ZADD")).isEqualTo(Danger.MUTATING);
    }

    @Test
    void deleteAndFlushAreDestructive() {
        assertThat(RedisDanger.level("DEL")).isEqualTo(Danger.DESTRUCTIVE);
        assertThat(RedisDanger.level("FLUSHDB")).isEqualTo(Danger.DESTRUCTIVE);
        assertThat(RedisDanger.level("FLUSHALL")).isEqualTo(Danger.DESTRUCTIVE);
        // 未知命令 fail-closed：不放行成只读，而是最重的 ADMIN。
        assertThat(RedisDanger.level("WEIRDCMD")).isEqualTo(Danger.ADMIN);
        assertThat(RedisDanger.level(null)).isEqualTo(Danger.ADMIN);
    }

    // ───────────────────────── 值查看 ─────────────────────────

    @Test
    void getValueResolvesEachType() {
        try (RedisSession s = session()) {
            s.set(0, "str:k", "hello");
            s.listAppend(0, "list:k", "a");
            s.listAppend(0, "list:k", "b");
            s.setAdd(0, "set:k", "m1");
            s.hashPut(0, "hash:k", "f", "v");
            s.zAdd(0, "zset:k", 2.5, "zm");

            RedisValue str = s.get(0, "str:k");
            assertThat(str).isInstanceOf(RedisString.class);
            assertThat(((RedisString) str).value()).isEqualTo("hello");

            RedisValue list = s.get(0, "list:k");
            assertThat(list).isInstanceOf(RedisList.class);
            assertThat(((RedisList) list).elements()).containsExactly("a", "b");

            RedisValue set = s.get(0, "set:k");
            assertThat(set).isInstanceOf(RedisSet.class);
            assertThat(((RedisSet) set).members()).containsExactly("m1");

            RedisValue hash = s.get(0, "hash:k");
            assertThat(hash).isInstanceOf(RedisHash.class);
            assertThat(((RedisHash) hash).fields()).containsEntry("f", "v");

            RedisValue zset = s.get(0, "zset:k");
            assertThat(zset).isInstanceOf(RedisZSet.class);
            assertThat(((RedisZSet) zset).members())
                    .extracting(ScoredMember::member, ScoredMember::score)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("zm", 2.5));

            // key 不存在 → null（调用方据此渲染「已删除」而不是崩）。
            assertThat(s.get(0, "nope:missing")).isNull();
        }
    }

    // ───────────────────────── TTL ─────────────────────────

    @Test
    void expireAndPersistControlTtl() {
        try (RedisSession s = session()) {
            s.set(0, "ttl:k", "x");
            assertThat(s.ttl(0, "ttl:k")).isEqualTo(-1); // 默认不过期

            s.expire(0, "ttl:k", 60_000);
            assertThat(s.ttl(0, "ttl:k")).isBetween(59_000L, 60_000L);

            s.persist(0, "ttl:k");
            assertThat(s.ttl(0, "ttl:k")).isEqualTo(-1); // 回到不过期
        }
    }

    // ───────────────────────── 写操作真的改了数据 ─────────────────────────

    @Test
    void collectionEditsMutateState() {
        try (RedisSession s = session()) {
            s.listAppend(0, "l", "one");
            s.listAppend(0, "l", "two");
            s.listRemove(0, "l", "one");
            assertThat(((RedisList) s.get(0, "l")).elements()).containsExactly("two");

            s.hashPut(0, "h", "a", "1");
            s.hashPut(0, "h", "b", "2");
            s.hashRemove(0, "h", "a");
            assertThat(((RedisHash) s.get(0, "h")).fields()).containsOnlyKeys("b");

            s.zAdd(0, "z", 1.0, "first");
            s.zAdd(0, "z", 3.0, "third");
            s.zRemove(0, "z", "first");
            assertThat(((RedisZSet) s.get(0, "z")).members())
                    .extracting(ScoredMember::member).containsExactly("third");
        }
    }

    // ───────────────────────── 危险操作 ─────────────────────────

    @Test
    void delRemovesKey() {
        try (RedisSession s = session()) {
            s.set(0, "d:k", "bye");
            assertThat(s.get(0, "d:k")).isNotNull();
            s.del(0, "d:k");
            assertThat(s.get(0, "d:k")).isNull();
        }
    }

    @Test
    void flushDbClearsOnlyCurrentDb() {
        try (RedisSession s = session()) {
            s.set(0, "db0:k", "a");
            s.set(1, "db1:k", "b"); // 另一个库的 key
            s.flushDb(0);
            assertThat(s.get(0, "db0:k")).isNull();
            // 库 1 不受影响 —— 证明 FLUSHDB 不是 FLUSHALL。
            assertThat(s.get(1, "db1:k")).isNotNull();
        }
    }

    @Test
    void flushAllClearsEveryDb() {
        try (RedisSession s = session()) {
            s.set(0, "a0", "x");
            s.set(1, "a1", "y");
            s.flushAll();
            assertThat(s.get(0, "a0")).isNull();
            assertThat(s.get(1, "a1")).isNull();
        }
    }
}
