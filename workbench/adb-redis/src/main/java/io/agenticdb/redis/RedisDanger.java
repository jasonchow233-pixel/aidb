package io.agenticdb.redis;

import io.agenticdb.model.Danger;

import java.util.Locale;

/**
 * Redis 操作的写安全等级映射（P1-6，复用 SQL 已有的三级门禁语义）。
 *
 * <p>SQL 用的是「语句头 → {@link Danger}」分类器（{@code DangerClassifier}）；Redis 没有 SQL
 * 语句，但有等价的命令语义。这里把命令名映射到同一套 {@link Danger} 等级，UI 据此决定是否
 * 弹确认框、按什么风险等级给用户看。门禁的「分级」与「确认」逻辑与 SQL 完全一致，
 * 只是分类器从 SQL 换成了 Redis 命令名。
 *
 * <pre>
 *   READ_ONLY   GET / TYPE / TTL / PTTL / SCAN / INFO / EXISTS / DBSIZE
 *   MUTATING    SET / APPEND / LPUSH / RPUSH / LSET / LREM / SADD / SREM /
 *               HSET / HDEL / ZADD / ZREM / EXPIRE / PERSIST / RENAME
 *   DESTRUCTIVE DEL / UNLINK / FLUSHDB / FLUSHALL
 *   其它未知    ADMIN（最重，fail-closed，与 DangerClassifier 的未知语句策略一致）
 * </pre>
 */
public final class RedisDanger {

    private RedisDanger() {
    }

    /** 命令名 → 风险等级。大小写不敏感。 */
    public static Danger level(String op) {
        if (op == null || op.isBlank()) {
            return Danger.ADMIN;
        }
        return switch (op.toUpperCase(Locale.ROOT)) {
            case "GET", "TYPE", "TTL", "PTTL", "SCAN", "INFO",
                    "EXISTS", "DBSIZE", "XREVRANGE", "LRANGE", "SMEMBERS",
                    "HGETALL", "ZRANGE" -> Danger.READ_ONLY;
            case "SET", "APPEND", "LPUSH", "RPUSH", "LSET", "LREM",
                    "SADD", "SREM", "HSET", "HDEL", "ZADD", "ZREM",
                    "EXPIRE", "PERSIST", "RENAME" -> Danger.MUTATING;
            case "DEL", "UNLINK", "FLUSHDB", "FLUSHALL" -> Danger.DESTRUCTIVE;
            default -> Danger.ADMIN;
        };
    }
}
