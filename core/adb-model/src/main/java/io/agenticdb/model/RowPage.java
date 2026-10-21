package io.agenticdb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一页查询结果。
 *
 * <p>⚠️ 与历史的 Rust DTO 契约保持<b>字段名一致</b>（camelCase），
 * 避免迁移期前端断言失效。
 *
 * @param total 总行数。<b>没统计就必须是 null</b> —— 绝不拿估算糊弄用户。
 *             UI 在 {@code null} 时显示「筛选后总数未统计」而不是编一个数字。
 * @param truncatedColumns 被截断的<b>列</b>名（单元格级截断）。
 * @param rowsTruncated <b>行</b>级截断：结果集比请求的行数多，被硬上限砍掉了。
 *             静默丢数据是正确性问题，UI 必须能看见它。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RowPage(
        java.util.List<ColumnDef> columns,
        java.util.List<java.util.Map<String, Object>> rows,
        Long nextCursor,
        Long total,
        long durationMs,
        java.util.List<String> truncatedColumns,
        boolean mutated,
        boolean rowsTruncated) {

    public RowPage {
        columns = java.util.List.copyOf(columns == null ? java.util.List.of() : columns);
        rows = java.util.List.copyOf(rows == null ? java.util.List.of() : rows);
        truncatedColumns =
                java.util.List.copyOf(truncatedColumns == null ? java.util.List.of() : truncatedColumns);
    }

    /** 兼容旧调用点：不带行级截断标记。 */
    public RowPage(java.util.List<ColumnDef> columns,
                   java.util.List<java.util.Map<String, Object>> rows,
                   Long nextCursor,
                   Long total,
                   long durationMs,
                   java.util.List<String> truncatedColumns,
                   boolean mutated) {
        this(columns, rows, nextCursor, total, durationMs, truncatedColumns, mutated, false);
    }

    public boolean hasKnownTotal() {
        return total != null;
    }

    public int rowCount() {
        return rows.size();
    }
}