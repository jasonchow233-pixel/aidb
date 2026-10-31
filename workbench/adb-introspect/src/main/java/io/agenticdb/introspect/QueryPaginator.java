package io.agenticdb.introspect;

import io.agenticdb.jdbc.Dialect;
import io.agenticdb.model.QueryLimits;

import java.util.List;

/**
 * ⭐ Q5：把一条任意 SELECT 改写成「可分页」的取数 SQL。
 *
 * <p>查询页以前把结果集一次性塞进 {@code ObservableList}（上限 1000 行），
 * 用户看不到 1000 行之后的数据，且大结果集占满内存。这里提供与数据页
 * 一致的<b>服务端分页</b>：外层套一层取某一页，总行数用 {@code COUNT(*)} 套同一层得到。
 *
 * <p>⭐ D-2/D-3 改判：分页写法<b>不再写死在这里</b> —— 曾经「三种方言都支持
 * {@code LIMIT ? OFFSET ?}，所以不分方言」是成立的；SQL Server 与 Oracle 用
 * {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}（参数顺序相反，SQL Server 还要求
 * {@code ORDER BY}、且不许派生表内带 {@code ORDER BY}）。包装策略落回
 * {@link Dialect#pagedSelect} / {@link Dialect#countedSelect}，这里只管
 * 判定可分页性与剥尾分号。
 *
 * <p>只对纯只读结果集（SELECT / WITH 开头）生效；DML、DDL、含 {@code FOR UPDATE}
 * 或 {@code INTO} 的语句不在此分页，沿用原来的「整段取回 + 截断提示」逻辑。
 */
public final class QueryPaginator {

    private QueryPaginator() {
    }

    /**
     * 这条语句能不能做结果集分页。
     *
     * <p>判定很保守：单条、以 {@code select}/{@code with} 开头、不含
     * {@code for update}/{@code into}、不含分号。套派生表对这类语句恒成立，
     * 所以一旦判定为真，{@link #pageSql} / {@link #countSql} 不会失败。
     */
    public static boolean isPaginable(String sql) {
        if (sql == null) {
            return false;
        }
        // ⭐ 语句文本常带末尾分隔符（SqlSplitter 默认保留），这里只剥离单个末尾分号。
        // 中间含分号（多条语句）仍判为不可分页，沿用整段取回逻辑。
        String text = stripTrailingSemicolon(sql);
        if (text.isEmpty() || text.contains(";")) {
            return false;
        }
        String lower = text.toLowerCase();
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            return false;
        }
        return !lower.contains("for update") && !lower.contains(" into ");
    }

    /** 总行数查询：由方言决定怎么包装（{@code COUNT(*)} 套同一层）。 */
    public static Dialect.PagedSql countSql(Dialect dialect, String sql, List<Object> params) {
        return dialect.countedSelect(stripTrailingSemicolon(sql), params);
    }

    /**
     * 取第 {@code offset} 行起 {@code limit} 行。
     *
     * <p>参数顺序与占位符出现顺序一致 —— 由 {@link Dialect#pagedSelect} 保证
     * （LIMIT 族是 limit 在前；OFFSET/FETCH 族是 offset 在前）。
     */
    public static Dialect.PagedSql pageSql(Dialect dialect, String sql, List<Object> params,
                                           int limit, long offset) {
        String source = stripTrailingSemicolon(sql);
        int safeLimit = QueryLimits.clampPageSize(limit);
        long safeOffset = Math.max(0, offset);
        return dialect.pagedSelect(source, params, safeLimit, safeOffset);
    }

    /** 剥离单个末尾分号（含前后空白），中间的分号不动。 */
    private static String stripTrailingSemicolon(String sql) {
        String text = sql == null ? "" : sql.trim();
        if (text.endsWith(";")) {
            return text.substring(0, text.length() - 1).trim();
        }
        return text;
    }
}
