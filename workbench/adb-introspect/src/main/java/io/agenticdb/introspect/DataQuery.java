package io.agenticdb.introspect;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据页当前查询状态。不可变，UI 每次改动生成新实例。
 *
 * <p>排序最多 3 级：再多既难读，也会让“列头小箭头”失去意义。
 */
public record DataQuery(List<FilterCondition> filters, List<SortSpec> sorts) {

    public static final int MAX_SORTS = 3;
    public static final int MAX_FILTERS = 50;

    public DataQuery {
        filters = List.copyOf(filters == null ? List.of() : filters);
        sorts = List.copyOf(sorts == null ? List.of() : sorts);
        if (sorts.size() > MAX_SORTS) {
            throw new IllegalArgumentException("最多支持 " + MAX_SORTS + " 级排序");
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("筛选条件不能超过 " + MAX_FILTERS + " 个");
        }
    }

    public static DataQuery empty() {
        return new DataQuery(List.of(), List.of());
    }

    public boolean hasFilters() {
        return !filters.isEmpty();
    }

    public boolean hasSorts() {
        return !sorts.isEmpty();
    }

    public boolean isDefault() {
        return filters.isEmpty() && sorts.isEmpty();
    }

    public DataQuery withFilters(List<FilterCondition> newFilters) {
        return new DataQuery(newFilters, sorts);
    }

    /** 同列条件先移除，再追加；列头筛选和条件构造器都按这个语义工作。 */
    public DataQuery withFilter(FilterCondition filter) {
        List<FilterCondition> next = new ArrayList<>(filters);
        next.removeIf(existing -> existing.column().equalsIgnoreCase(filter.column()));
        next.add(filter);
        return new DataQuery(next, sorts);
    }

    public DataQuery withoutFilter(FilterCondition filter) {
        List<FilterCondition> next = new ArrayList<>(filters);
        next.remove(filter);
        return new DataQuery(next, sorts);
    }

    public DataQuery withoutFiltersFor(String column) {
        List<FilterCondition> next = new ArrayList<>(filters);
        next.removeIf(filter -> filter.column().equalsIgnoreCase(column));
        return new DataQuery(next, sorts);
    }

    public DataQuery withSorts(List<SortSpec> newSorts) {
        return new DataQuery(filters, newSorts);
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
        return new DataQuery(filters, next);
    }

    public DataQuery withoutSort(String column) {
        List<SortSpec> next = new ArrayList<>(sorts);
        next.removeIf(sort -> sort.column().equalsIgnoreCase(column));
        return new DataQuery(filters, next);
    }

    public DataQuery clearSorts() {
        return new DataQuery(filters, List.of());
    }

    public DataQuery clearFilters() {
        return new DataQuery(List.of(), sorts);
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
