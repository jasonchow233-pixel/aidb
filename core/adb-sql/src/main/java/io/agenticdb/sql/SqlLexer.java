package io.agenticdb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 宽容的 SQL 词法器：只做「上色 / 分句 / 错误定位」需要的事，不做语法分析。
 *
 * <p>它必须在 SQL 有语法错误时仍然返回稳定的 token 区间，否则用户刚写错一个
 * 引号，高亮和错误定位就一起失效。因此未闭合字符串/注释也照常收尾。
 */
public final class SqlLexer {

    private SqlLexer() {
    }

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "IS", "IN",
            "LIKE", "GLOB", "REGEXP", "BETWEEN", "ORDER", "BY", "GROUP", "HAVING",
            "LIMIT", "OFFSET", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "VIEW", "INDEX", "TRIGGER", "DROP", "ALTER", "ADD",
            "COLUMN", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK",
            "DEFAULT", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS",
            "ON", "AS", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END", "UNION",
            "ALL", "EXCEPT", "INTERSECT", "WITH", "RECURSIVE", "EXPLAIN", "PRAGMA",
            "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "IF", "EXISTS", "CAST",
            "ASC", "DESC", "TRUE", "FALSE", "INTEGER", "INT", "TEXT", "REAL",
            "BLOB", "NUMERIC", "VARCHAR", "CHAR", "DATE", "TIME", "TIMESTAMP",
            "BOOLEAN", "JSON", "AUTOINCREMENT", "REPLACE", "RETURNING", "ATTACH",
            "DETACH", "VACUUM", "ANALYZE", "REINDEX", "GRANT", "REVOKE", "SHOW",
            "DESCRIBE", "USE", "DATABASE", "SCHEMA", "TEMP", "TEMPORARY",
            // ↓ 语句级动词：危险分级（DangerClassifier）要靠「是不是关键字」把
            //   标识符（表名/列名）和真正的语句头区分开，缺一个就会误判。
            "MERGE", "UPSERT", "CALL", "DO", "COPY", "LOCK", "RESET", "SAVEPOINT",
            "RELEASE", "START");

    /**
     * 把 SQL 切成 token。空白也保留，这样 UI 可以直接按起止 offset 覆盖整段文本。
     */
    public static List<SqlToken> tokenize(String sql) {
        if (sql == null || sql.isEmpty()) {
            return List.of();
        }
        List<SqlToken> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            int start = i;

            if (Character.isWhitespace(c)) {
                i++;
                while (i < n && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                tokens.add(token(SqlTokenType.WHITESPACE, sql, start, i));
                continue;
            }

            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                tokens.add(token(SqlTokenType.COMMENT, sql, start, i));
                continue;
            }

            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                tokens.add(token(SqlTokenType.COMMENT, sql, start, i));
                continue;
            }

            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                SqlTokenType type = c == '\'' ? SqlTokenType.STRING
                        : SqlTokenType.QUOTED_IDENTIFIER;
                tokens.add(token(type, sql, start, i));
                continue;
            }

            if (c == '[') {
                i = skipBracketIdentifier(sql, i);
                tokens.add(token(SqlTokenType.QUOTED_IDENTIFIER, sql, start, i));
                continue;
            }

            if (c == '$') {
                int dollarEnd = dollarQuoteEnd(sql, i);
                if (dollarEnd > i) {
                    i = dollarEnd;
                    tokens.add(token(SqlTokenType.STRING, sql, start, i));
                    continue;
                }
                if (i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                    i += 2;
                    while (i < n && Character.isDigit(sql.charAt(i))) {
                        i++;
                    }
                    tokens.add(token(SqlTokenType.PARAMETER, sql, start, i));
                    continue;
                }
            }

            if (c == '?') {
                i++;
                tokens.add(token(SqlTokenType.PARAMETER, sql, start, i));
                continue;
            }

            if (c == ':' && i + 1 < n && sql.charAt(i + 1) != ':'
                    && isIdentifierStart(sql.charAt(i + 1))) {
                i += 2;
                while (i < n && isIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                tokens.add(token(SqlTokenType.PARAMETER, sql, start, i));
                continue;
            }

            if (c == '@' && i + 1 < n && isIdentifierStart(sql.charAt(i + 1))) {
                i += 2;
                while (i < n && isIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                tokens.add(token(SqlTokenType.PARAMETER, sql, start, i));
                continue;
            }

            if (Character.isDigit(c) || (c == '.' && i + 1 < n
                    && Character.isDigit(sql.charAt(i + 1)))) {
                i = skipNumber(sql, i);
                tokens.add(token(SqlTokenType.NUMBER, sql, start, i));
                continue;
            }

            if (isIdentifierStart(c)) {
                i++;
                while (i < n && isIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                String word = sql.substring(start, i);
                SqlTokenType type = KEYWORDS.contains(word.toUpperCase(Locale.ROOT))
                        ? SqlTokenType.KEYWORD : SqlTokenType.IDENTIFIER;
                tokens.add(token(type, sql, start, i));
                continue;
            }

            if (c == ';') {
                i++;
                tokens.add(token(SqlTokenType.SEMICOLON, sql, start, i));
                continue;
            }

            if (isOperatorChar(c)) {
                i++;
                while (i < n && isOperatorChar(sql.charAt(i))
                        && !(sql.charAt(i) == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                        && !(sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*')) {
                    i++;
                }
                tokens.add(token(SqlTokenType.OPERATOR, sql, start, i));
                continue;
            }

            if ("(),.[]{}".indexOf(c) >= 0) {
                i++;
                tokens.add(token(SqlTokenType.PUNCTUATION, sql, start, i));
                continue;
            }

            i++;
            tokens.add(token(SqlTokenType.UNKNOWN, sql, start, i));
        }
        return List.copyOf(tokens);
    }

    private static SqlToken token(SqlTokenType type, String sql, int start, int end) {
        return new SqlToken(type, sql.substring(start, end), start, end);
    }

    private static int skipBlockComment(String sql, int start) {
        int i = start + 2;
        int depth = 1;
        while (i < sql.length() && depth > 0) {
            if (i + 1 < sql.length() && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                i += 2;
            } else if (i + 1 < sql.length()
                    && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                depth--;
                i += 2;
            } else {
                i++;
            }
        }
        return i;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipBracketIdentifier(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == ']') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == ']') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /**
     * 识别 PostgreSQL 的 {@code $$...$$} / {@code $tag$...$tag$}。
     *
     * @return 结束位置；不是合法 dollar quote 时返回 {@code start}
     */
    private static int dollarQuoteEnd(String sql, int start) {
        int tagEnd = sql.indexOf('$', start + 1);
        if (tagEnd < 0) {
            return start;
        }
        String tag = sql.substring(start + 1, tagEnd);
        if (!tag.isEmpty()) {
            if (!isIdentifierStart(tag.charAt(0))) {
                return start;
            }
            for (int i = 1; i < tag.length(); i++) {
                if (!isIdentifierPart(tag.charAt(i))) {
                    return start;
                }
            }
        }
        String delimiter = "$" + tag + "$";
        int close = sql.indexOf(delimiter, tagEnd + 1);
        return close < 0 ? sql.length() : close + delimiter.length();
    }

    private static int skipNumber(String sql, int start) {
        int i = start;
        boolean dot = false;
        if (sql.charAt(i) == '.') {
            dot = true;
            i++;
        }
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && i + 1 < sql.length()
                    && (Character.isDigit(sql.charAt(i + 1))
                    || ((sql.charAt(i + 1) == '+' || sql.charAt(i + 1) == '-')
                    && i + 2 < sql.length() && Character.isDigit(sql.charAt(i + 2))))) {
                i += 2;
                while (i < sql.length() && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c >= 0x80;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c >= 0x80;
    }

    private static boolean isOperatorChar(char c) {
        return "!<>=~@#%^&|+-*/:?".indexOf(c) >= 0;
    }
}
