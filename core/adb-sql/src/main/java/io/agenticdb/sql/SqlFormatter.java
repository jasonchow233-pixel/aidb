package io.agenticdb.sql;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量 SQL 格式化：关键字大写、主要子句换行、逗号后空格。
 *
 * <p>刻意不做完整 AST 重排 —— 输入可能有语法错误，格式化器必须仍然可用，
 * 且不能改动字符串、引用标识符和注释内容。
 */
public final class SqlFormatter {

    private SqlFormatter() {
    }

    private static final Set<String> NEWLINE_BEFORE = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "VALUES", "SET", "RETURNING", "INSERT", "UPDATE", "DELETE", "UNION",
            "EXCEPT", "INTERSECT", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL",
            "CROSS", "ON", "CREATE", "ALTER", "DROP");

    private static final Set<String> TIGHT_OPERATORS = Set.of("::", "->", "->>", ".", "#>", "#>>");

    public static String format(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length() + 32);
        List<SqlToken> tokens = SqlLexer.tokenize(sql);
        String previous = "";
        boolean lineStart = true;

        for (SqlToken token : tokens) {
            if (token.type() == SqlTokenType.WHITESPACE) {
                continue;
            }
            if (token.type() == SqlTokenType.COMMENT) {
                if (!lineStart) {
                    appendSpace(out);
                }
                out.append(token.text());
                if (token.text().startsWith("--")) {
                    out.append('\n');
                    lineStart = true;
                } else {
                    out.append(' ');
                }
                previous = token.text();
                continue;
            }
            if (token.type() == SqlTokenType.SEMICOLON) {
                trimTrailingSpace(out);
                out.append(";\n\n");
                lineStart = true;
                previous = ";";
                continue;
            }

            String text = token.type() == SqlTokenType.KEYWORD
                    ? token.text().toUpperCase(Locale.ROOT) : token.text();
            if (token.type() == SqlTokenType.KEYWORD && NEWLINE_BEFORE.contains(text)
                    && out.length() > 0 && !lineStart) {
                trimTrailingSpace(out);
                out.append('\n');
                lineStart = true;
            }

            if (text.equals(",")) {
                trimTrailingSpace(out);
                out.append(", ");
                previous = ",";
                lineStart = false;
                continue;
            }
            if (text.equals("(")) {
                if (needsSpaceBefore(previous, token)) {
                    appendSpace(out);
                }
                out.append('(');
                previous = "(";
                lineStart = false;
                continue;
            }
            if (text.equals(")")) {
                trimTrailingSpace(out);
                out.append(')');
                previous = ")";
                lineStart = false;
                continue;
            }
            if (text.equals(".")) {
                trimTrailingSpace(out);
                out.append('.');
                previous = ".";
                lineStart = false;
                continue;
            }
            if (token.type() == SqlTokenType.OPERATOR) {
                if (TIGHT_OPERATORS.contains(text)) {
                    trimTrailingSpace(out);
                    out.append(text);
                } else {
                    if (!lineStart) {
                        appendSpace(out);
                    }
                    out.append(text);
                    out.append(' ');
                }
                previous = text;
                lineStart = false;
                continue;
            }

            if (needsSpaceBefore(previous, token)) {
                appendSpace(out);
            }
            out.append(text);
            previous = text;
            lineStart = false;
        }

        String result = out.toString().stripTrailing();
        return result.isEmpty() ? "" : result + System.lineSeparator();
    }

    private static boolean needsSpaceBefore(String previous, SqlToken token) {
        if (previous.isEmpty() || previous.equals("(") || previous.equals(".")) {
            return false;
        }
        if (previous.equals("::") || previous.equals("->") || previous.equals("->>")
                || previous.equals("#>") || previous.equals("#>>")) {
            return false;
        }
        return switch (token.type()) {
            case PUNCTUATION -> !token.text().equals(")") && !token.text().equals(",");
            case OPERATOR -> true;
            default -> true;
        };
    }

    private static void appendSpace(StringBuilder out) {
        if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
            out.append(' ');
        }
    }

    private static void trimTrailingSpace(StringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
    }
}
