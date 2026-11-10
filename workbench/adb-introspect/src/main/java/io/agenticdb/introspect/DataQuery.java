package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据页当前查询状态。不可变，UI 每次改动生成新实例。
 *
 * <p>⭐ B1：筛选由若干 {@link FilterGroup} 组成，组内 {@code AND}、组间 {@code OR}。
 * 单组（默认）等价于旧版的平铺 AND。
 * 排序最多 3 级：再多既难读，也会让“列头小箭头”失去意义。
 *
 * <p>{@link #filters()} 把所有组的条件扁平化后返回，供「表头筛选钮高亮 / 条件 chip」
 * 这类只读展示使用；需要组结构（SQL 生成、面板编辑）的地方请走 {@link #groups()}。
 */
public record DataQuery(List<FilterGroup> groups, List<SortSpec> sorts) {

    public static final int MAX_SORTS = 3;
    public static final int MAX_FILTERS = 50;
    public static final int MAX_GROUPS = 8;

    public DataQuery {
        groups = List.copyOf(groups == null ? List.of() : groups);
        sorts = List.copyOf(sorts == null ? List.of() : sorts);
        if (sorts.size() > MAX_SORTS) {
            throw new IllegalArgumentException("最多支持 " + MAX_SORTS + " 级排序");
        }
        int total = 0;
        for (FilterGroup group : groups) {
            total += group.conditions().size();
        }
        if (total > MAX_FILTERS) {
            throw new IllegalArgumentException("筛选条件不能超过 " + MAX_FILTERS + " 个");
        }
        if (groups.size() > MAX_GROUPS) {
            // Defensive backstop; the UI panel already enforces the localized
            // "filter.panel.error.groupLimit" message, so this guard stays neutral.
            throw new IllegalArgumentException("filter groups must not exceed " + MAX_GROUPS);
        }
    }

    public static DataQuery empty() {
        return new DataQuery(List.of(FilterGroup.empty()), List.of());
    }

    public boolean hasFilters() {
        return groups.stream().anyMatch(group -> !group.isEmpty());
    }

    public boolean hasSorts() {
        return !sorts.isEmpty();
    }

    public boolean isDefault() {
        return !hasFilters() && !hasSorts();
    }

    /** 全部条件扁平化（只读展示用：表头高亮、条件 chip）。 */
    public List<FilterCondition> filters() {
        List<FilterCondition> all = new ArrayList<>();
        for (FilterGroup group : groups) {
            all.addAll(group.conditions());
        }
        return List.copyOf(all);
    }

    public DataQuery withFilters(List<FilterCondition> newFilters) {
        return new DataQuery(List.of(FilterGroup.of(newFilters)), sorts);
    }

    /** 列头筛选 / 单条件写入：作用于组 0（默认 AND 组），同列替换。 */
    public DataQuery withFilter(FilterCondition filter) {
        List<FilterGroup> next = new ArrayList<>(groups);
        if (next.isEmpty()) {
            next.add(FilterGroup.empty());
        }
        next.set(0, next.get(0).withCondition(filter));
        return new DataQuery(next, sorts);
    }

    /** 移除某个条件（从包含它的任一组里移除）。 */
    public DataQuery withoutFilter(FilterCondition filter) {
        List<FilterGroup> next = groups.stream()
                .map(group -> group.without(filter))
                .toList();
        return new DataQuery(next, sorts);
    }

    /** 移除某列的全部条件（所有组）。 */
    public DataQuery withoutFiltersFor(String column) {
        List<FilterGroup> next = groups.stream()
                .map(group -> group.withoutColumn(column))
                .toList();
        return new DataQuery(next, sorts);
    }

    public DataQuery clearFilters() {
        List<FilterGroup> cleared = groups.stream()
                .map(group -> FilterGroup.empty())
                .toList();
        return new DataQuery(cleared, sorts);
    }

    public DataQuery withSorts(List<SortSpec> newSorts) {
        return new DataQuery(groups, newSorts);
    }

    /** 列头点击语义：无 → 升序 → 降序 → 移除；已有其他列时追加为下一级。 */
    public DataQuery cycleSort(String column, boolean append) {
        List<SortSpec> next = new ArrayList<>(append ? sorts : List.of());
        SortSpec current = sortFor(column);
        if (!append) {
            if (current == null) {
                next.add(new SortSpec(column, true));
            } else if (current.ascending()) {
                next.add(current.reversed());
            }
        } else if (current != null) {
            int index = indexOfSort(column);
            next.set(index, current.reversed());
        } else {
            next.add(new SortSpec(column, true));
        }
        while (next.size() > MAX_SORTS) {
            next.removeLast();
        }
        return new DataQuery(groups, next);
    }

    public DataQuery withoutSort(String column) {
        List<SortSpec> next = new ArrayList<>(sorts);
        next.removeIf(sort -> sort.column().equalsIgnoreCase(column));
        return new DataQuery(groups, next);
    }

    public DataQuery clearSorts() {
        return new DataQuery(groups, List.of());
    }

    public int indexOfSort(String column) {
        for (int i = 0; i < sorts.size(); i++) {
            if (sorts.get(i).column().equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    public SortSpec sortFor(String column) {
        int index = indexOfSort(column);
        return index < 0 ? null : sorts.get(index);
    }
}
