package io.agenticdb.sql;

import java.util.List;

/**
 * 括号配对（SQL 编辑器的高亮用）。
 *
 * <p>⛔ 关键点：<b>字符串与注释里的括号不算</b>。
 * {@code SELECT '(' FROM t} 里的 {@code (} 是文本的一部分，
 * 按纯字符配对会得出完全错误的结果。
 * 所以这里复用 {@link SqlLexer} 的分词结果 —— 它已经把字符串与注释整块吞掉了。
 */
public final class SqlBrackets {

    private SqlBrackets() {
    }

    /** 一个括号及其配对位置。 */
    public record Match(int start, int end, String open) {

        public int length() {
            return end - start;
        }
    }

    /**
     * 只认圆括号。
     *
     * <p>⚠️ {@code [ ]} 在 SQLite 里是<b>标识符引用</b>（{@code a[1]} 是一个标识符），
     * 不是括号 —— 把它当括号会得出完全错误的配对。
     * {@code { }} 不是 SQL 语法，也不认。
     */
    private static final String OPEN = "(";
    private static final String CLOSE = ")";

    /**
     * 找出光标处的括号配对。
     *
     * <p>光标在括号<b>上</b>或<b>紧邻</b>括号都算（用户按 ← / → 走过括号时会路过）。
     *
     * @return 配对；没有括号或没配上返回 {@code null}
     */
    public static Match matchAt(String sql, int caret) {
        if (sql == null || sql.isEmpty()) {
            return null;
        }
        List<SqlToken> tokens = SqlLexer.tokenize(sql);
        int position = Math.max(0, Math.min(caret, sql.length()));

        SqlToken candidate = null;
        for (SqlToken token : tokens) {
            if (token.type() != SqlTokenType.PUNCTUATION
                    && token.type() != SqlTokenType.OPERATOR) {
                continue;
            }
            String text = token.text();
            if (text.length() != 1 || (OPEN.indexOf(text) < 0 && CLOSE.indexOf(text) < 0)) {
                continue;
            }
            if (token.start() == position || token.end() == position
                    || (token.start() < position && position < token.end())) {
                candidate = token;
                break;
            }
            if (token.start() == position - 1 || token.end() == position + 1) {
                candidate = token;
                break;
            }
        }
        if (candidate == null) {
            return null;
        }
        String text = candidate.text();
        if (OPEN.indexOf(text) >= 0) {
            int end = scanForward(tokens, candidate, text);
            return end < 0 ? null : new Match(candidate.start(), end, text);
        }
        int start = scanBackward(tokens, candidate, text);
        return start < 0 ? null : new Match(start, candidate.start(), openOf(text));
    }

    /** 从 open 括号往后找配对的 close。 */
    private static int scanForward(List<SqlToken> tokens, SqlToken open, String openText) {
        String closeText = closeOf(openText);
        int depth = 0;
        for (SqlToken token : tokens) {
            if (token.start() < open.start()) {
                continue;
            }
            if (!isBracket(token)) {
                continue;
            }
            if (token.text().equals(openText)) {
                depth++;
            } else if (token.text().equals(closeText)) {
                depth--;
                if (depth == 0) {
                    return token.start();
                }
            }
        }
        return -1;
    }

    /** 从 close 括号往前找配对的 open。 */
    private static int scanBackward(List<SqlToken> tokens, SqlToken close, String closeText) {
        String openText = openOf(closeText);
        int depth = 0;
        for (int i = tokens.size() - 1; i >= 0; i--) {
            SqlToken token = tokens.get(i);
            if (token.start() > close.start()) {
                continue;
            }
            if (!isBracket(token)) {
                continue;
            }
            if (token.text().equals(closeText)) {
                depth++;
            } else if (token.text().equals(openText)) {
                depth--;
                if (depth == 0) {
                    return token.start();
                }
            }
        }
        return -1;
    }

    private static boolean isBracket(SqlToken token) {
        if (token.type() != SqlTokenType.PUNCTUATION
                && token.type() != SqlTokenType.OPERATOR) {
            return false;
        }
        String text = token.text();
        return text.length() == 1 && (OPEN.indexOf(text) >= 0 || CLOSE.indexOf(text) >= 0);
    }

    private static String closeOf(String open) {
        return switch (open) {
            case "(" -> ")";
            case "[" -> "]";
            case "{" -> "}";
            default -> open;
        };
    }

    private static String openOf(String close) {
        return switch (close) {
            case ")" -> "(";
            case "]" -> "[";
            case "}" -> "{";
            default -> close;
        };
    }
}
