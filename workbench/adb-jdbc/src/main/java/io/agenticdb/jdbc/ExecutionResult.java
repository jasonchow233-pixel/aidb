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
        long durationMs) {

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
