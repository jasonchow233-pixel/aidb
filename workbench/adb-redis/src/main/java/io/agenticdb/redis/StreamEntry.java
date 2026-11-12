package io.agenticdb.redis;

import java.util.Map;

/** stream 的一个条目：自增 id + 字段映射。 */
public record StreamEntry(String id, Map<String, String> fields) {
}
