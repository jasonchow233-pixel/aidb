package io.agenticdb.redis;

import java.util.List;

/** {@code set} 值：成员集合（Redis 返回即无序，这里按顺序展示）。 */
public record RedisSet(String type, List<String> members) implements RedisValue {
}
