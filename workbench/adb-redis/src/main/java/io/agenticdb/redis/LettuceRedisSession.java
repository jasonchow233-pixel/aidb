package io.agenticdb.redis;

import io.lettuce.core.KeyScanArgs;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.ScoredValue;
import io.agenticdb.jdbc.SshTunnel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于 Lettuce 的 {@link RedisSession} 实现（netty 异步客户端，P1-1）。
 *
 * <p>持有单个 {@link StatefulRedisConnection}：{@code databases()} 走 {@code INFO keyspace}
 * （与当前选中库无关），{@code scan()} 先 {@code SELECT db} 再扫。会话按标签页单线程使用，
 * 这种「按需选库」足够，也避免每个库开一条长连。
 */
final class LettuceRedisSession implements RedisSession {

    private static final Pattern KEYSPACE_DB = Pattern.compile("^db(\\d+):keys=(\\d+)", Pattern.MULTILINE);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> sync;
    /** SSH 隧道（直连时为 {@code null}）。会话关闭时一并释放，避免本地端口与 SSH 会话泄漏。 */
    private final SshTunnel tunnel;

    LettuceRedisSession(RedisClient client, StatefulRedisConnection<String, String> connection,
                        SshTunnel tunnel) {
        this.client = client;
        this.connection = connection;
        this.tunnel = tunnel;
        this.sync = connection.sync();
    }

    @Override
    public List<Integer> databases() {
        String info = sync.info("keyspace");
        List<Integer> dbs = new ArrayList<>();
        Matcher m = KEYSPACE_DB.matcher(info);
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            long keys = Long.parseLong(m.group(2));
            if (keys > 0) {
                dbs.add(index);
            }
        }
        return List.copyOf(dbs);
    }

    @Override
    public List<RedisKey> scan(int db, String prefix, String typeFilter) {
        sync.select(db);
        String pattern = ((prefix == null || prefix.isBlank()) ? "" : prefix) + "*";
        KeyScanArgs args = new KeyScanArgs();
        args.match(pattern);
        if (typeFilter != null && !typeFilter.isBlank()) {
            args.type(typeFilter.toLowerCase(Locale.ROOT));
        }

        List<RedisKey> result = new ArrayList<>();
        KeyScanCursor<String> cursor = sync.scan(args);
        collect(cursor.getKeys(), result);
        while (!cursor.isFinished()) {
            cursor = sync.scan(cursor, args);
            collect(cursor.getKeys(), result);
        }
        return List.copyOf(result);
    }

    private void collect(List<String> keys, List<RedisKey> out) {
        for (String key : keys) {
            String type = sync.type(key);
            if (type == null || type.equals("none")) {
                // 扫描期间被删：跳过瞬时态，不污染结果。
                continue;
            }
            long ttl = orZero(sync.pttl(key));
            out.add(new RedisKey(key, type, ttl, sizeOf(key, type)));
        }
    }

    private long sizeOf(String key, String type) {
        return switch (type) {
            case "string" -> orZero(sync.strlen(key));
            case "list" -> orZero(sync.llen(key));
            case "set" -> orZero(sync.scard(key));
            case "zset" -> orZero(sync.zcard(key));
            case "hash" -> orZero(sync.hlen(key));
            case "stream" -> orZero(sync.xlen(key));
            default -> 0L;
        };
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    // ────────────────────────────────────────────────────────────
    // P1-6：值查看 / 编辑 / TTL / 危险操作
    // 每个方法先 SELECT db，保证「按 db 传参」的语义不串库。
    // ────────────────────────────────────────────────────────────

    @Override
    public RedisValue get(int db, String key) {
        sync.select(db);
        String type = sync.type(key);
        if (type == null || type.equals("none")) {
            return null;
        }
        return switch (type) {
            case "string" -> new RedisString("string", orEmpty(sync.get(key)));
            case "list" -> new RedisList("list", List.copyOf(sync.lrange(key, 0, -1)));
            case "set" -> new RedisSet("set", List.copyOf(sync.smembers(key)));
            case "hash" -> new RedisHash("hash", new LinkedHashMap<>(sync.hgetall(key)));
            case "zset" -> new RedisZSet("zset", sync.zrangeWithScores(key, 0, -1).stream()
                    .map(v -> new ScoredMember(v.getValue(), v.getScore()))
                    .collect(Collectors.toList()));
            case "stream" -> new RedisStream("stream", recentStreamEntries(key, 100));
            default -> throw new IllegalStateException("unsupported redis type: " + type);
        };
    }

    private List<StreamEntry> recentStreamEntries(String key, long limit) {
        // 倒序取最近 limit 条（XREVRANGE + 限制条数）。
        List<io.lettuce.core.StreamMessage<String, String>> messages =
                sync.xrevrange(key, io.lettuce.core.Range.unbounded());
        int end = (int) Math.min(limit, messages.size());
        List<StreamEntry> entries = new ArrayList<>(end);
        for (int i = 0; i < end; i++) {
            entries.add(new StreamEntry(messages.get(i).getId(), messages.get(i).getBody()));
        }
        return entries;
    }

    @Override
    public long ttl(int db, String key) {
        sync.select(db);
        return orZero(sync.pttl(key));
    }

    @Override
    public void expire(int db, String key, long ttlMillis) {
        sync.select(db);
        sync.pexpire(key, ttlMillis);
    }

    @Override
    public void persist(int db, String key) {
        sync.select(db);
        sync.persist(key);
    }

    @Override
    public void set(int db, String key, String value) {
        sync.select(db);
        sync.set(key, value);
    }

    @Override
    public void listAppend(int db, String key, String element) {
        sync.select(db);
        sync.rpush(key, element);
    }

    @Override
    public void listSetAt(int db, String key, int index, String value) {
        sync.select(db);
        sync.lset(key, index, value);
    }

    @Override
    public void listRemove(int db, String key, String value) {
        sync.select(db);
        // count=1：只删第一个匹配，避免一次删光所有相同元素。
        sync.lrem(key, 1, value);
    }

    @Override
    public void setAdd(int db, String key, String member) {
        sync.select(db);
        sync.sadd(key, member);
    }

    @Override
    public void setRemove(int db, String key, String member) {
        sync.select(db);
        sync.srem(key, member);
    }

    @Override
    public void hashPut(int db, String key, String field, String value) {
        sync.select(db);
        sync.hset(key, field, value);
    }

    @Override
    public void hashRemove(int db, String key, String field) {
        sync.select(db);
        sync.hdel(key, field);
    }

    @Override
    public void zAdd(int db, String key, double score, String member) {
        sync.select(db);
        sync.zadd(key, score, member);
    }

    @Override
    public void zRemove(int db, String key, String member) {
        sync.select(db);
        sync.zrem(key, member);
    }

    @Override
    public void del(int db, String key) {
        sync.select(db);
        sync.del(key);
    }

    @Override
    public void flushDb(int db) {
        sync.select(db);
        sync.flushdb();
    }

    @Override
    public void flushAll() {
        sync.flushall();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String via() {
        return tunnel == null ? "" : tunnel.via();
    }

    /** 测试用：隧道在本地绑的端口（直连为 -1），证会话关闭后端口确实释放。 */
    int tunnelLocalPort() {
        return tunnel == null ? -1 : tunnel.localPort();
    }

    @Override
    public void close() {
        try {
            connection.close();
        } finally {
            client.shutdown();
            if (tunnel != null) {
                tunnel.close();
            }
        }
    }
}
