package io.agenticdb.redis;

import java.util.List;

/** {@code list} 值：按索引有序的元素列表。 */
public record RedisList(String type, List<String> elements) implements RedisValue {
}
