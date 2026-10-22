package io.agenticdb.introspect;

import io.agenticdb.model.QueryLimits;

import java.util.ArrayList;
import java.util.List;

/**
 * ⭐ Q5：把一条任意 SELECT 改写成「可分页」的取数 SQL。
 *
 * <p>查询页以前把结果集一次性塞进 {@code ObservableList}（上限 1000 行），
 * 用户看不到 1000 行之后的数据，且大结果集占满内存。这里提供与数据页
 * 一致的<b>服务端分页</b>：外层套一层 {@code SELECT * FROM (<原句>) AS _p}，
 * 再 {@code LIMIT ? OFFSET ?} 取某一页；总行数用 {@code COUNT(*)} 套同一层得到。
 *
 * <p>三种方言（MySQL / PostgreSQL / SQLite）都支持 {@code LIMIT ? OFFSET ?} 与
 * 带别名的派生表，所以这里<b>不分方言</b> —— 与 {@link DataSqlBuilder} 的
 * 分页写法保持一致，不引入第二套方言分支。
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

    /** 总行数查询：{@code SELECT COUNT(*) FROM (<sql>) AS _p}。 */
    public static PagedSql countSql(String sql, List<Object> params) {
        String source = stripTrailingSemicolon(sql);
        List<Object> bound = params == null ? List.of() : List.copyOf(params);
        return new PagedSql("SELECT COUNT(*) AS adb_count FROM (" + source + ") AS _p", bound);
    }

    /**
     * 取第 {@code offset} 行起 {@code limit} 行。
     *
     * <p>参数顺序：原句的占位符在前，{@code limit} / {@code offset} 在后 —— 与
     * 驱动按出现顺序绑定占位符的语义一致。
     */
    public static PagedSql pageSql(String sql, List<Object> params, int limit, long offset) {
        String source = stripTrailingSemicolon(sql);
        int safeLimit = QueryLimits.clampPageSize(limit);
        long safeOffset = Math.max(0, offset);
        List<Object> bound = new ArrayList<>(params == null ? List.of() : params);
        bound.add(safeLimit);
        // offset 恒在 int 范围内（L4 上限 500_000），显式转 int 避免与 limit 的 Integer 类型不一致。
        bound.add((int) safeOffset);
        return new PagedSql(
                "SELECT * FROM (" + source + ") AS _p LIMIT ? OFFSET ?", bound);
    }

    /** 剥离单个末尾分号（含前后空白），中间的分号不动。 */
    private static String stripTrailingSemicolon(String sql) {
        String text = sql == null ? "" : sql.trim();
        if (text.endsWith(";")) {
            return text.substring(0, text.length() - 1).trim();
        }
        return text;
    }

    /** 一条分页 SQL 与其参数（占位符顺序即绑定顺序）。 */
    public record PagedSql(String sql, List<Object> params) {
        public PagedSql {
            params = List.copyOf(params == null ? List.of() : params);
        }
    }
}
