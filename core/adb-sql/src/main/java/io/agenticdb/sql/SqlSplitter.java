package io.agenticdb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 按分号拆分 SQL，但跳过字符串、引用标识符和注释里的分号。
 *
 * <p>返回的 {@code start/end} 是原始文本 offset（end 不包含），
 * 这样编辑器可以把错误位置、当前语句和结果页签准确对应起来。
 */
public final class SqlSplitter {

    private SqlSplitter() {
    }

    public static List<SqlStatement> split(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<SqlToken> tokens = SqlLexer.tokenize(sql);
        List<SqlStatement> statements = new ArrayList<>();
        int segmentStart = 0;
        int triggerDepth = 0;
        int caseDepth = 0;
        boolean triggerStatement = false;

        for (SqlToken token : tokens) {
            if (token.type() == SqlTokenType.KEYWORD) {
                String word = token.text().toUpperCase(java.util.Locale.ROOT);
                switch (word) {
                    case "CREATE", "TRIGGER" -> triggerStatement = true;
                    case "CASE" -> caseDepth++;
                    case "BEGIN" -> {
                        if (triggerStatement && caseDepth == 0) {
                            triggerDepth++;
                        }
                    }
                    case "END" -> {
                        if (caseDepth > 0) {
                            caseDepth--;
                        } else if (triggerDepth > 0) {
                            triggerDepth--;
                        }
                    }
                    default -> {
                    }
                }
            }
            if (token.type() != SqlTokenType.SEMICOLON || triggerDepth > 0) {
                continue;
            }
            addIfMeaningful(statements, sql, segmentStart, token.end());
            segmentStart = token.end();
            triggerStatement = false;
            caseDepth = 0;
        }
        addIfMeaningful(statements, sql, segmentStart, sql.length());
        return List.copyOf(statements);
    }

    /**
     * 光标所在语句。光标落在两条语句之间的空白时返回下一条，
     * 光标在第一条之前时返回第一条，超过末尾时返回最后一条。
     */
    public static Optional<SqlStatement> statementAt(String sql, int caret) {
        List<SqlStatement> statements = split(sql);
        if (statements.isEmpty()) {
            return Optional.empty();
        }
        int position = Math.max(0, Math.min(caret, sql == null ? 0 : sql.length()));
        SqlStatement last = statements.getLast();
        for (SqlStatement statement : statements) {
            if (position < statement.start()) {
                return Optional.of(statement);
            }
            if (position <= statement.end()) {
                return Optional.of(statement);
            }
            last = statement;
        }
        return Optional.of(last);
    }

    private static void addIfMeaningful(List<SqlStatement> statements,
                                        String sql, int start, int end) {
        int from = start;
        while (from < end && Character.isWhitespace(sql.charAt(from))) {
            from++;
        }
        int to = end;
        while (to > from && Character.isWhitespace(sql.charAt(to - 1))) {
            to--;
        }
        if (from >= to) {
            return;
        }
        String text = sql.substring(from, to);
        boolean meaningful = SqlLexer.tokenize(text).stream()
                .anyMatch(token -> token.type() != SqlTokenType.SEMICOLON
                        && token.type().significant());
        if (!meaningful) {
            return;
        }
        statements.add(new SqlStatement(text, from, to));
    }

    /**
     * 一条可执行语句。
     *
     * @param text  去掉首尾空白后的 SQL（保留分号）
     * @param start 原始文本中的起始 offset
     * @param end   原始文本中的结束 offset（不包含）
     */
    public record SqlStatement(String text, int start, int end) {
        public SqlStatement {
            Objects.requireNonNull(text, "text");
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("非法语句区间: " + start + ".." + end);
            }
        }

        public int length() {
            return end - start;
        }
    }
}
