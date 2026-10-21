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
        for (SqlToken token : tokens) {
            builder.add(styleClass(token.type()), token.length());
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
