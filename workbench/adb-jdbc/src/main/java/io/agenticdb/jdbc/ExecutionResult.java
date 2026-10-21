package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一条 SQL 语句的执行结果。
 *
 * <p>查询语句带 {@code columns/rows}；DML/DDL 带 {@code updateCount}。
 * 多语句由 UI 分句后逐条调用 {@link DbSession#execute(String)}，每条结果单独成页。
 */
public record ExecutionResult(
        String statement,
        List<ColumnDef> columns,
        List<Map<String, Object>> rows,
        int updateCount,
        long durationMs,
        boolean truncated) {

    /** 兼容构造：不带截断标记时按「未截断」处理。 */
    public ExecutionResult(String statement, List<ColumnDef> columns,
                           List<Map<String, Object>> rows, int updateCount, long durationMs) {
        this(statement, columns, rows, updateCount, durationMs, false);
    }

    public ExecutionResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public boolean hasRows() {
        return !columns.isEmpty();
    }

    public int rowCount() {
        return rows.size();
    }

    /**
     * 结果是否被行数上限截断。
     *
     * <p>被截断时必须告诉用户 —— 静默丢行会让人以为看到的就是全部，
     * 然后基于不完整的数据做决定。UI 上要显示「已截断」而不是假装没事。
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Map 实际使用的列键。JOIN 出现重名列时会自动加 {@code _2}、{@code _3} 后缀，
     * 与 {@link JdbcSession} 读取结果集时的键完全一致。
     */
    public List<String> columnKeys() {
        List<String> keys = new ArrayList<>(columns.size());
        for (ColumnDef column : columns) {
            String base = column.name();
            if (!keys.contains(base)) {
                keys.add(base);
                continue;
            }
            int suffix = 2;
            while (keys.contains(base + "_" + suffix)) {
                suffix++;
            }
            keys.add(base + "_" + suffix);
        }
        return List.copyOf(keys);
    }
}
