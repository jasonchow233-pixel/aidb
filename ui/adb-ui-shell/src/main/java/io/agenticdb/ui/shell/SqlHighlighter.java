package io.agenticdb.ui.shell;

import io.agenticdb.sql.SqlLexer;
import io.agenticdb.sql.SqlToken;
import io.agenticdb.sql.SqlTokenType;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** SQL token → RichTextFX 样式类。颜色只来自 theme-*.css 的 -adb-syn-*。 */
final class SqlHighlighter {

    /** ⭐ N22：这些关键字后面的第一个标识符是表名 —— 渲染成带下划线的表名。 */
    private static final java.util.Set<String> TABLE_INTRODUCERS =
            java.util.Set.of("FROM", "JOIN", "UPDATE", "INTO", "TABLE");

    /** 表名样式类（下划线）；颜色仍由 sql-identifier / 默认正文色提供。 */
    static final String TABLE_STYLE = "sql-table";

    private SqlHighlighter() {
    }

    /** 配对括号的高亮样式类。 */
    static final String BRACKET_STYLE = "adb-bracket-match";

    static StyleSpans<Collection<String>> compute(String sql) {
        List<SqlToken> tokens = SqlLexer.tokenize(sql);
        if (tokens.isEmpty()) {
            return StyleSpans.singleton(Collections.emptyList(), sql.length());
        }
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        boolean tableExpected = false;
        boolean inTableList = false;
        for (SqlToken token : tokens) {
            Collection<String> style = styleClass(token.type());
            if (token.type() == SqlTokenType.KEYWORD) {
                String keyword = token.text().toUpperCase(java.util.Locale.ROOT);
                tableExpected = TABLE_INTRODUCERS.contains(keyword);
                inTableList = keyword.equals("FROM") || keyword.equals("JOIN");
            } else if (token.type() == SqlTokenType.PUNCTUATION
                    && ",".equals(token.text())) {
                // FROM a, b —— 逗号后面仍是表名清单。
                tableExpected = inTableList;
            } else if (token.type() == SqlTokenType.IDENTIFIER
                    || token.type() == SqlTokenType.QUOTED_IDENTIFIER) {
                if (tableExpected) {
                    style = token.type() == SqlTokenType.QUOTED_IDENTIFIER
                            ? List.of("sql-identifier", TABLE_STYLE)
                            : List.of(TABLE_STYLE);
                    tableExpected = false;
                }
            } else if (token.type().significant()) {
                // 字符串 / 数字 / 运算符出现 = 表名位已经错过，不再顺延到后面的词。
                tableExpected = false;
            }
            builder.add(style, token.length());
        }
        return builder.create();
    }

    /**
     * ⭐ Q1：在指定位置**叠加**一个样式类（配对括号的高亮）。
     *
     * <p>RichTextFX 0.11 没有 {@code addInlineStyle}，
     * 所以只能把高亮类的样式段拆开、把新类加进去 —— 语法配色不受影响。
     */
    static StyleSpans<Collection<String>> withBracket(StyleSpans<Collection<String>> base,
                                                     int position) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int offset = 0;
        for (StyleSpan<Collection<String>> span : base) {
            int start = offset;
            int end = offset + span.getLength();
            if (position >= start && position < end) {
                if (position > start) {
                    builder.add(span.getStyle(), position - start);
                }
                List<String> merged = new ArrayList<>(span.getStyle());
                merged.add(BRACKET_STYLE);
                builder.add(merged, 1);
                if (end > position + 1) {
                    builder.add(span.getStyle(), end - position - 1);
                }
            } else {
                builder.add(span.getStyle(), span.getLength());
            }
            offset = end;
        }
        return builder.create();
    }

    private static Collection<String> styleClass(SqlTokenType type) {
        return switch (type) {
            case KEYWORD -> List.of("sql-keyword");
            case STRING -> List.of("sql-string");
            case NUMBER -> List.of("sql-number");
            case COMMENT -> List.of("sql-comment");
            case PARAMETER -> List.of("sql-param");
            case QUOTED_IDENTIFIER -> List.of("sql-identifier");
            case OPERATOR, PUNCTUATION, SEMICOLON -> List.of("sql-operator");
            default -> Collections.emptyList();
        };
    }
}
