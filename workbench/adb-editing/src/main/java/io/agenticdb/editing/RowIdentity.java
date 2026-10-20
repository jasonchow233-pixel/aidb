package io.agenticdb.editing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一行数据的稳定身份：只保存主键列的值。
 *
 * <p>编辑缓冲不能用「当前页第几行」做 key：排序、筛选、翻页之后行号会变，
 * 只有主键能保证 UPDATE 命中的还是原来那一行。
 */
public record RowIdentity(Map<String, Object> values) {

    public RowIdentity {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("行身份不能为空");
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static RowIdentity from(List<String> primaryKeys, Map<String, Object> row) {
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            throw new IllegalArgumentException("没有主键的表不能编辑");
        }
        LinkedHashMap<String, Object> keys = new LinkedHashMap<>();
        for (String primaryKey : primaryKeys) {
            if (!row.containsKey(primaryKey)) {
                throw new IllegalArgumentException("结果集缺少主键列: " + primaryKey);
            }
            keys.put(primaryKey, row.get(primaryKey));
        }
        return new RowIdentity(keys);
    }

    public String display() {
        StringBuilder text = new StringBuilder();
        values.forEach((key, value) -> {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(key).append('=').append(value == null ? "NULL" : value);
        });
        return text.toString();
    }
}
