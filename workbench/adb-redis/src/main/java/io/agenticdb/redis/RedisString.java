package io.agenticdb.redis;

/** {@code string} 值。 */
public record RedisString(String type, String value) implements RedisValue {
}
