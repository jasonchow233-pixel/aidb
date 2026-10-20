package io.agenticdb.introspect;

/**
 * 单列排序。多列排序的顺序就是列表顺序，最多 3 级（见 {@link DataQuery}）。
 */
public record SortSpec(String column, boolean ascending) {

    public SortSpec {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("排序列不能为空");
        }
    }

    public String directionLabel() {
        return ascending ? "升序" : "降序";
    }

    public SortSpec reversed() {
        return new SortSpec(column, !ascending);
    }
}
