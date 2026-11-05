package io.agenticdb.introspect;

import io.agenticdb.jdbc.Dialect;
import io.agenticdb.model.QueryLimits;

import java.util.List;
import java.util.Locale;

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
     *
     * <p>⭐ D-64：这四条读的是<b>形状</b>，不是原文。字面量与注释里的
     * {@code into} / {@code for update} / {@code ;} 是用户的数据，不是 SQL 结构。
     * 以前用 {@code String.contains} 直接读原文，于是
     * {@code WHERE body LIKE '% into %'} 判成不可分页 —— 而可改性那一层
     * （{@code SqlQueryShape}，走词法）判它可改。两层一撞，症状落在
     * {@code QueryPage.java:1636} 那一支：重取钩子只挂在可分页的路上，
     * 于是「状态那句『已保存』是真的、格子里的值是旧的」，而且不报错。
     */
    public static boolean isPaginable(String sql) {
        if (sql == null) {
            return false;
        }
        String shape = skeleton(sql).masked().strip();
        if (shape.isEmpty() || shape.contains(";")) {
            return false;
        }
        String lower = shape.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select") && !lower.startsWith("with")) {
            return false;
        }
        return !lower.contains("for update") && !lower.contains(" into ");
    }

    /** 总行数查询：由方言决定怎么包装（{@code COUNT(*)} 套同一层）。 */
    public static Dialect.PagedSql countSql(Dialect dialect, String sql, List<Object> params) {
        return dialect.countedSelect(wrapSource(skeleton(sql)), params);
    }

    /**
     * 取第 {@code offset} 行起 {@code limit} 行。
     *
     * <p>参数顺序与占位符出现顺序一致 —— 由 {@link Dialect#pagedSelect} 保证
     * （LIMIT 族是 limit 在前；OFFSET/FETCH 族是 offset 在前）。
     */
    public static Dialect.PagedSql pageSql(Dialect dialect, String sql, List<Object> params,
                                           int limit, long offset) {
        String source = wrapSource(skeleton(sql));
        int safeLimit = QueryLimits.clampPageSize(limit);
        long safeOffset = Math.max(0, offset);
        return dialect.pagedSelect(source, params, safeLimit, safeOffset);
    }

    /**
     * 一条语句的两份读法：{@code masked} 是抹平字面量与注释后的<b>形状</b>（判定用），
     * {@code source} 是<b>还能直接包进外层 SELECT</b> 的原文（改写用）。
     */
    private record Skeleton(String masked, String source, boolean endsInLineComment) {
    }

    /**
     * 扫一遍语句文本：字符串字面量、引号标识符、注释的内容全部抹成空格。
     *
     * <p>抹平是<b>等长</b>的（一个字符对一个空格），所以 {@code masked} 的下标能直接
     * 回指原文 —— 尾部那个分号坐在哪儿，得按形状找：它常常躲在注释前面
     * （{@code SELECT … ; -- 说明}），原文 {@code endsWith(";")} 看不见它。
     *
     * <p>⭐ D-64：判定放行之后，包装这一步必须还能跑得起来。尾部是行注释时补一个换行，
     * 否则 {@code SELECT * FROM (SELECT … -- 说明) AS _p} 里那个右括号会被注释吃掉 ——
     * 放行是<b>判定</b>的事，包得住是<b>改写</b>的事，两件事不能只改一半。
     */
    private static Skeleton skeleton(String sql) {
        String text = sql.trim();
        char[] masked = text.toCharArray();
        boolean endsInLineComment = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = text.indexOf('\n', i);
                int stop = end < 0 ? text.length() : end;
                blank(masked, i, stop);
                endsInLineComment = end < 0;
                i = stop;
                continue;
            }
            if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end + 2;
                blank(masked, i, stop);
                endsInLineComment = false;
                i = stop;
                continue;
            }
            if (c == '\'' || c == '"') {
                int stop = skipQuoted(text, i, c);
                blank(masked, i, stop);
                endsInLineComment = false;
                i = stop;
                continue;
            }
            endsInLineComment = false;
            i++;
        }
        String shape = new String(masked);
        String source = text;
        int last = shape.length();
        while (last > 0 && shape.charAt(last - 1) == ' ') {
            last--;
        }
        // ⭐ SqlSplitter 默认保留语句末尾的分号，判定与改写都必须容忍它。
        // 剥的是<b>形状里最后一个有效字符</b>那个分号 —— 它常常坐在注释前面
        // （{@code SELECT … ; -- 说明}），原文 {@code endsWith(";")} 看不见它。
        // 中间的分号（多条语句）不动，沿用整段取回逻辑。
        if (last > 0 && shape.charAt(last - 1) == ';') {
            shape = shape.substring(0, last - 1);
            source = text.substring(0, last - 1).stripTrailing();
            endsInLineComment = false;
        }
        return new Skeleton(shape, source, endsInLineComment);
    }

    /** 包进外层 SELECT 之前补齐的那一步：尾部行注释要留一个换行给它收尾。 */
    private static String wrapSource(Skeleton skeleton) {
        return skeleton.endsInLineComment() ? skeleton.source() + "\n" : skeleton.source();
    }

    /** 把 {@code [from, to)} 抹成空格；换行留着，行注释的边界才不会糊成一坨。 */
    private static void blank(char[] masked, int from, int to) {
        for (int i = from; i < to; i++) {
            if (masked[i] != '\n') {
                masked[i] = ' ';
            }
        }
    }

    /** 引号串的结束下标（含收尾那个引号之后）：连续两个引号是转义，不是结束。 */
    private static int skipQuoted(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }
}
