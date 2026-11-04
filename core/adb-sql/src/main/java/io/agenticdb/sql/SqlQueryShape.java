package io.agenticdb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一条查询的形状：它能不能被认成「一个单表 SELECT」，从而谈得上按主键改回去。
 *
 * <p>⭐ 这一层**只做识别，不做改写**。判不出来就如实说「为什么这条不能改」，
 * 而不是替用户把主键补进投影、或者把 SQL 换个写法重跑一遍 ——
 * 那等于界面上显示一条 SQL、底下跑的是另一条。
 *
 * <p>规则一律**保守**：拿不准就是不能改。后面接的是 {@code EditSqlBuilder} 的真 UPDATE，
 * 误判的代价是把用户以为没动的表改了。
 *
 * <p>⚠️ 本层**不碰数据库**，也不回答「这张表存在吗、有没有主键」——
 * 那是调用方拿 {@code TableSchema} 去核的事（本模块是叶子，不该为判定依赖内省层）。
 */
public final class SqlQueryShape {

    /** 认不出来时卡在哪一步。每条都要能在界面上说成一句人话。 */
    public enum Block {
        /** 一句都没有。 */
        EMPTY,
        /** 不止一条语句 —— 结果集是哪条产出的都说不清。 */
        NOT_ONE_STATEMENT,
        /** 首词不是 SELECT（INSERT / UPDATE / CALL / PRAGMA…）。 */
        NOT_A_SELECT,
        /** {@code WITH …} 公用表表达式：结果行不一定直接来自一张实表。 */
        COMMON_TABLE_EXPRESSION,
        /** 没有 FROM（{@code SELECT 1} 这类），没有可改的表。 */
        NO_SOURCE_TABLE,
        /** FROM 里有 JOIN 或逗号多表：一行数据可能来自多张表。 */
        JOINED,
        /** FROM 里是括号（派生表 / 表值函数）：看不见实表。 */
        DERIVED_TABLE,
        /** 表名限定超过两段（{@code 库.属主.表}）：各家语义不同，不猜。 */
        OVER_QUALIFIED_NAME,
        /** {@code UNION / INTERSECT / EXCEPT}：行来自多条查询。 */
        SET_OPERATION,
        /** {@code SELECT DISTINCT}：看到的行是被压过的，回写可能改到不止一行。 */
        DISTINCT,
        /** {@code GROUP BY} / {@code HAVING}：这一行是聚合组的产物，不是表里的一行。 */
        AGGREGATED,
        /** 投影里有函数 / 算式 / 字面量：那一列没有能写回去的目标列。 */
        EXPRESSION_COLUMN,
        /** {@code SELECT … INTO 新表} / {@code FOR UPDATE} 这类带副作用的子句。 */
        SIDE_CLAUSE,
        /** 读不懂的写法 —— 保守起见一律不认。 */
        UNRECOGNIZED;

        /** 界面文案用的资源键后缀（每种卡点各一句，不用一句「不能编辑」糊过去）。 */
        public String messageKey() {
            return "query.edit.blocked." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 一个投影列。
     *
     * @param column 表里的列名（去掉表前缀与引号）。
     * @param label  结果集里预计显示的名字（有别名就是别名）。
     *               ⚠️ 这是推断，最终对齐以驱动返回的列标签为准（各家大小写不同）。
     */
    public record Column(String column, String label) {
    }

    /**
     * 认出来的单表 SELECT。
     *
     * @param schema     库 / schema 限定名；没写就是空串（此时按当前库理解）。
     * @param table      表名（已去引号）。
     * @param alias      FROM 里给的别名；没有就是空串。
     * @param allColumns 投影里有没有 {@code *} / {@code 前缀.*}。
     *                   ⚠️ 「主键在不在结果里」这一关只有带了 {@code *} 才可能自然通过。
     * @param columns    投影里认出来的裸列。
     */
    public record Select(String schema, String table, String alias,
                         boolean allColumns, List<Column> columns) {
        public Select {
            columns = List.copyOf(columns);
        }
    }

    /** 识别结果：要么是 {@link Readable}，要么带一个 {@link Block} 说明为什么不能。 */
    public sealed interface Shape permits Readable, Refused {
    }

    /** 认出来了。 */
    public record Readable(Select select) implements Shape {
    }

    /**
     * 没认出来。
     *
     * @param reason 卡在哪一步。
     * @param detail 从原文带出的一小段，**只用于日志与门禁报错，不进 SQL**。
     */
    public record Refused(Block reason, String detail) implements Shape {
    }

    /** 终结 FROM 段的子句词。缺一个就会把「表名后面的东西」读成别名。 */
    private static final Set<String> CLAUSE_WORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "WINDOW",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "INTO", "FOR", "LOCK", "RETURNING");

    /**
     * 这些词**不许**当列名读。
     *
     * <p>为什么要有这张表：词法层把 {@code DATE} / {@code TIME} / {@code KEY} 这类也标成 KEYWORD，
     * 而它们恰恰是常见的列名 —— 照词性拒绝会把 `SELECT date FROM t` 判成不可编辑。
     * 反过来 {@code CASE} / {@code CAST} / {@code NULL} 领头的一定不是裸列，必须拒。
     */
    private static final Set<String> NOT_A_NAME = Set.of(
            "CASE", "WHEN", "THEN", "ELSE", "END", "CAST", "INTERVAL", "NOT", "IS", "NULL",
            "IN", "LIKE", "GLOB", "REGEXP", "BETWEEN", "EXISTS", "ALL", "ANY", "SOME",
            "DISTINCT", "AS", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER", "ON",
            "USING", "UNION", "INTERSECT", "EXCEPT", "MINUS", "INTO", "FOR", "LOCK",
            "WINDOW", "FETCH", "ROWS", "ROW", "ONLY", "TOP", "SELECT", "INSERT", "UPDATE",
            "DELETE", "MERGE", "UPSERT", "CALL", "DO", "COPY", "VALUES", "SET", "RETURNING",
            "IF", "ELSEIF", "WHILE", "BEGIN", "COMMIT", "ROLLBACK");

    private SqlQueryShape() {
    }

    /**
     * 一个词法单元加上它的括号层级（{@code 0} = 顶层）。
     *
     * <p>⭐ 括号**不能丢**：丢了就分不清 `SELECT COUNT(*)` 和 `SELECT n`，
     * 更分不清 `FROM (SELECT …) x` 和 `FROM x` —— 后一种误判会把派生表当成实表，
     * 于是对着一张并不存在的表宣布「可编辑」。这一版就是这么错的，所以留着这句话。
     */
    private record At(SqlToken token, int depth) {
    }

    /**
     * 识别一条查询（允许尾分号、允许多行）。
     *
     * <p>关卡顺序：恰好一条语句 → 首词 SELECT（{@code WITH} 单独说）→ 顶层没有集合运算 /
     * {@code DISTINCT} / {@code GROUP} / {@code HAVING} / {@code INTO} / {@code FOR} / {@code LOCK} →
     * 恰好一个顶层 {@code FROM}，其后是 1~2 段限定名 + 可选别名，且没有逗号 / 括号 / JOIN 词 →
     * 投影每一项都是 {@code *}、{@code 前缀.*} 或「裸列 + 可选别名」。
     *
     * <p>⚠️ {@code WHERE} / {@code ORDER BY} / {@code LIMIT} / {@code OFFSET} **不影响**判定：
     * 它们只筛行与排序，行本身还是那张表里带主键的一行 —— 「可编辑」说的是行，不是行集大小。
     */
    public static Shape parse(String sql) {
        List<SqlSplitter.SqlStatement> statements = SqlSplitter.split(sql);
        if (statements.isEmpty()) {
            return new Refused(Block.EMPTY, "");
        }
        if (statements.size() > 1) {
            return new Refused(Block.NOT_ONE_STATEMENT, statements.size() + " 条语句");
        }
        List<SqlToken> raw = significant(SqlLexer.tokenize(statements.get(0).text()));
        if (raw.isEmpty()) {
            return new Refused(Block.EMPTY, "");
        }

        String head = word(raw.get(0));
        if ("WITH".equals(head)) {
            return new Refused(Block.COMMON_TABLE_EXPRESSION, raw.get(0).text());
        }
        if (!"SELECT".equals(head)) {
            return new Refused(Block.NOT_A_SELECT, raw.get(0).text());
        }

        List<At> top = new ArrayList<>();
        int depth = 0;
        for (SqlToken token : raw) {
            String text = token.text();
            if ("(".equals(text)) {
                top.add(new At(token, depth));
                depth++;
                continue;
            }
            if (")".equals(text)) {
                depth = Math.max(0, depth - 1);
                top.add(new At(token, depth));
                continue;
            }
            if (depth == 0) {
                top.add(new At(token, depth));
            }
        }

        for (At at : top) {
            String keyword = word(at.token());
            switch (keyword) {
                case "UNION", "INTERSECT", "EXCEPT", "MINUS" -> {
                    return new Refused(Block.SET_OPERATION, at.token().text());
                }
                case "DISTINCT" -> {
                    return new Refused(Block.DISTINCT, at.token().text());
                }
                case "GROUP", "HAVING" -> {
                    return new Refused(Block.AGGREGATED, at.token().text());
                }
                case "INTO", "FOR", "LOCK" -> {
                    return new Refused(Block.SIDE_CLAUSE, at.token().text());
                }
                default -> {
                    // 其余顶层词交给下面的 FROM / 投影判定。
                }
            }
        }

        int from = -1;
        int fromCount = 0;
        for (int i = 0; i < top.size(); i++) {
            if ("FROM".equals(word(top.get(i).token()))) {
                fromCount++;
                from = i;
            }
        }
        if (fromCount == 0) {
            return new Refused(Block.NO_SOURCE_TABLE, "");
        }
        if (fromCount > 1) {
            return new Refused(Block.JOINED, "多个 FROM");
        }

        List<At> fromSpan = new ArrayList<>();
        for (int i = from + 1; i < top.size(); i++) {
            At at = top.get(i);
            if (CLAUSE_WORDS.contains(word(at.token())) && at.depth() == 0) {
                break;
            }
            fromSpan.add(at);
        }
        for (At at : fromSpan) {
            String text = at.token().text();
            if (",".equals(text)) {
                return new Refused(Block.JOINED, text);
            }
            if ("(".equals(text) || ")".equals(text)) {
                return new Refused(Block.DERIVED_TABLE, text);
            }
            if (Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER",
                            "APPLY", "USING", "TABLESAMPLE", "VALUES")
                    .contains(word(at.token()))) {
                return new Refused(Block.JOINED, text);
            }
        }

        FromItem item = readFromItem(fromSpan);
        if (item.refused() != null) {
            return item.refused();
        }
        if (item.parts().size() > 2) {
            return new Refused(Block.OVER_QUALIFIED_NAME, join(fromSpan));
        }
        String schema = item.parts().size() == 2 ? item.parts().get(0) : "";
        String table = item.parts().get(item.parts().size() - 1);

        List<SqlToken> projection = new ArrayList<>();
        for (int i = 1; i < from; i++) {
            projection.add(top.get(i).token());
        }
        boolean allColumns = false;
        List<Column> columns = new ArrayList<>();
        for (List<SqlToken> part : splitCommas(projection)) {
            if (part.isEmpty()) {
                return new Refused(Block.EXPRESSION_COLUMN, "");
            }
            if (isWildcard(part)) {
                allColumns = true;
                continue;
            }
            Column column = readColumn(part);
            if (column == null) {
                return new Refused(Block.EXPRESSION_COLUMN, join(part));
            }
            columns.add(column);
        }
        if (columns.isEmpty() && !allColumns) {
            return new Refused(Block.EXPRESSION_COLUMN, join(projection));
        }
        return new Readable(new Select(schema, table, item.alias(), allColumns, columns));
    }

    /** FROM 段读出来的表：限定名各段 + 别名。 */
    private record FromItem(List<String> parts, String alias, Refused refused) {

        static FromItem refused(Refused problem) {
            return new FromItem(List.of(), "", problem);
        }
    }

    /**
     * FROM 段的形态：{@code 名[.名…]}，后面顶多跟一个别名（{@code AS} 可有可无）。
     *
     * <p>隐式别名靠「名字链结束后的又一个词」识别；出现第二个这种词就是多表，拒。
     */
    private static FromItem readFromItem(List<At> span) {
        if (span.isEmpty()) {
            return FromItem.refused(new Refused(Block.NO_SOURCE_TABLE, ""));
        }
        List<String> parts = new ArrayList<>();
        String alias = "";
        boolean expectName = true;
        boolean afterAs = false;
        for (At at : span) {
            SqlToken token = at.token();
            if (".".equals(token.text())) {
                expectName = true;
                continue;
            }
            if (isNameToken(token)) {
                if (afterAs) {
                    // `AS` 后面那个词是别名，**不是**限定名的下一段 ——
                    // 一开始就是把它当第三段名字收，于是 `FROM users AS u` 读成表名 "u"。
                    if (!alias.isEmpty()) {
                        return FromItem.refused(new Refused(Block.JOINED, join(span)));
                    }
                    alias = unquote(token.text());
                    afterAs = false;
                    continue;
                }
                if (expectName) {
                    parts.add(unquote(token.text()));
                    expectName = false;
                    continue;
                }
                if (!alias.isEmpty()) {
                    return FromItem.refused(new Refused(Block.JOINED, join(span)));
                }
                alias = unquote(token.text());
                continue;
            }
            if ("AS".equals(word(token))) {
                afterAs = true;
                continue;
            }
            return FromItem.refused(new Refused(Block.UNRECOGNIZED, join(span)));
        }
        if (afterAs) {
            return FromItem.refused(new Refused(Block.UNRECOGNIZED, join(span)));
        }
        if (parts.isEmpty()) {
            return FromItem.refused(new Refused(Block.NO_SOURCE_TABLE, join(span)));
        }
        return new FromItem(parts, alias, null);
    }

    /** {@code *} 或 {@code 前缀.*}。 */
    private static boolean isWildcard(List<SqlToken> item) {
        if (item.isEmpty() || !"*".equals(item.get(item.size() - 1).text())) {
            return false;
        }
        for (int i = 0; i < item.size() - 1; i++) {
            SqlToken token = item.get(i);
            if (!isNameToken(token) && !".".equals(token.text())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一项投影是不是「裸列（可带表前缀）+ 可选别名」。
     *
     * @return 认出来就是 {@link Column}，认不出来给 null（调用方翻成 {@link Block#EXPRESSION_COLUMN}）。
     */
    private static Column readColumn(List<SqlToken> item) {
        List<SqlToken> body = item;
        String alias = null;
        if (item.size() >= 3 && "AS".equals(word(item.get(item.size() - 2)))) {
            body = item.subList(0, item.size() - 2);
            alias = unquote(item.get(item.size() - 1).text());
        } else if (item.size() == 2 && isNameToken(item.get(0)) && isNameToken(item.get(1))
                && item.get(1).type() == SqlTokenType.IDENTIFIER) {
            // 隐式别名只认「一个名字 又一个名字」；限定名后面跟名字同样算别名。
            body = List.of(item.get(0));
            alias = unquote(item.get(1).text());
        }
        List<String> names = new ArrayList<>();
        for (SqlToken token : body) {
            if (isDot(token)) {
                continue;
            }
            if (!isNameToken(token)) {
                // 括号、运算符、字面量、数字都从这儿出去 —— 那一列没有能写回去的目标。
                return null;
            }
            names.add(unquote(token.text()));
        }
        if (names.isEmpty()) {
            return null;
        }
        String column = names.get(names.size() - 1);
        return alias == null || alias.isEmpty() ? new Column(column, column)
                : new Column(column, alias);
    }

    /** 按顶层逗号切投影；括号里的逗号（函数参数）不切，但**括号本身要留下**当证据。 */
    private static List<List<SqlToken>> splitCommas(List<SqlToken> tokens) {
        List<List<SqlToken>> items = new ArrayList<>();
        List<SqlToken> current = new ArrayList<>();
        int depth = 0;
        for (SqlToken token : tokens) {
            String text = token.text();
            if ("(".equals(text)) {
                depth++;
            } else if (")".equals(text)) {
                depth = Math.max(0, depth - 1);
            }
            if (",".equals(text) && depth == 0) {
                items.add(current);
                current = new ArrayList<>();
                continue;
            }
            current.add(token);
        }
        items.add(current);
        return items;
    }

    /**
     * 留下参与判定的词。
     *
     * <p>⚠️ 分号也要丢：{@code SqlSplitter} 给的语句文本**带着尾分号**，
     * 留着它就会被读成「FROM 后面跟了个不懂的词」，于是每条带分号的查询都判成不可编辑。
     */
    private static List<SqlToken> significant(List<SqlToken> tokens) {
        List<SqlToken> kept = new ArrayList<>();
        for (SqlToken token : tokens) {
            if (token.type().significant() && token.type() != SqlTokenType.SEMICOLON) {
                kept.add(token);
            }
        }
        return kept;
    }

    /** 能不能当名字读：标识符 / 引号标识符，或「不在保留词表里的关键字」。 */
    private static boolean isNameToken(SqlToken token) {
        if (token.type() == SqlTokenType.IDENTIFIER
                || token.type() == SqlTokenType.QUOTED_IDENTIFIER) {
            return true;
        }
        return token.type() == SqlTokenType.KEYWORD && !NOT_A_NAME.contains(word(token));
    }

    private static boolean isDot(SqlToken token) {
        return ".".equals(token.text());
    }

    /** 只有 KEYWORD 取大写；标识符按原文（大小写留给调用方去和驱动返回的列标签对齐）。 */
    private static String word(SqlToken token) {
        return token.type() == SqlTokenType.KEYWORD
                ? token.text().toUpperCase(Locale.ROOT) : token.text();
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char open = text.charAt(0);
            char close = text.charAt(text.length() - 1);
            if ((open == '"' && close == '"') || (open == '`' && close == '`')
                    || (open == '[' && close == ']')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text.replace("\"\"", "\"");
    }

    private static String join(List<?> tokens) {
        StringBuilder text = new StringBuilder();
        for (Object entry : tokens) {
            SqlToken token = entry instanceof At at ? at.token()
                    : entry instanceof SqlToken plain ? plain : null;
            if (token != null) {
                text.append(token.text());
            }
        }
        return text.toString();
    }
}
