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
        boolean mutated) {

    public RowPage {
        columns = java.util.List.copyOf(columns == null ? java.util.List.of() : columns);
        rows = java.util.List.copyOf(rows == null ? java.util.List.of() : rows);
        truncatedColumns =
                java.util.List.copyOf(truncatedColumns == null ? java.util.List.of() : truncatedColumns);
    }

    public boolean hasKnownTotal() {
        return total != null;
    }

    public int rowCount() {
        return rows.size();
    }
}