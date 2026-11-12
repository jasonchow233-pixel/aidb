package io.agenticdb.redis;

import java.util.List;

/** {@code stream} 值：流条目（id → 字段映射），按时间倒序取最近若干条。 */
public record RedisStream(String type, List<StreamEntry> entries) implements RedisValue {
}
