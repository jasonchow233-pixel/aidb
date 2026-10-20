package io.agenticdb.ui.shell;

import io.agenticdb.sql.SqlLexer;
import io.agenticdb.sql.SqlToken;
import io.agenticdb.sql.SqlTokenType;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** SQL token → RichTextFX 样式类。颜色只来自 theme-*.css 的 -adb-syn-*。 */
final class SqlHighlighter {

    private SqlHighlighter() {
    }

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
