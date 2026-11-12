package io.agenticdb.redis;

import java.util.Map;

/** {@code hash} 值：字段 → 值映射。 */
public record RedisHash(String type, Map<String, String> fields) implements RedisValue {
}
