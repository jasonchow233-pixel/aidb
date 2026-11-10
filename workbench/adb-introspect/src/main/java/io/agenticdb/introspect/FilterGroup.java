package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个筛选条件组：组内条件以 {@code AND} 连接，组与组之间以 {@code OR} 连接。
 *
 * <p>⭐ B1（筛选 OR 条件组）：{@link DataQuery} 此前只有平铺 {@code AND}，
 * 现在由若干 {@code FilterGroup} 组成，顶层连接词为 {@code OR}。单组即等价于旧版平铺 AND，
 * 故所有只用到一层条件的路径（列头筛选、写入回查）行为与旧版完全一致。
 */
public record FilterGroup(List<FilterCondition> conditions) {

    public FilterGroup {
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    public static FilterGroup empty() {
        return new FilterGroup(List.of());
    }

    public static FilterGroup of(List<FilterCondition> conditions) {
        return new FilterGroup(conditions);
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /** 同列条件先移除再追加（与旧 {@code DataQuery.withFilter} 同语义）。 */
    public FilterGroup withCondition(FilterCondition condition) {
        List<FilterCondition> next = new ArrayList<>(conditions);
        next.removeIf(existing -> existing.column().equalsIgnoreCase(condition.column()));
        next.add(condition);
        return new FilterGroup(next);
    }

    public FilterGroup without(FilterCondition condition) {
        List<FilterCondition> next = new ArrayList<>(conditions);
        next.remove(condition);
        return new FilterGroup(next);
    }

    public FilterGroup withoutColumn(String column) {
        List<FilterCondition> next = new ArrayList<>(conditions);
        next.removeIf(condition -> condition.column().equalsIgnoreCase(column));
        return new FilterGroup(next);
    }
}
