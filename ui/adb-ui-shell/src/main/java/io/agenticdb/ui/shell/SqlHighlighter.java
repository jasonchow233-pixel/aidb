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
        for (int i = 0; i < tokens.size(); i++) {
            SqlToken token = tokens.get(i);
            builder.add(styleClass(token, tokens, i), token.length());
        }
        return builder.create();
    }

    /**
     * token → 样式类。
     *
     * <p>函数名用独立的配色（{@code syn-function}）：词法器不区分
     * 「标识符」和「函数名」，但「后面紧跟左括号」这个规律足够可靠 ——
     * {@code count(*)} 里的 count 是函数，{@code users} 不是。
     */
    private static Collection<String> styleClass(SqlToken token, List<SqlToken> tokens, int index) {
        if (token.type() == SqlTokenType.IDENTIFIER && isFunctionCall(tokens, index)) {
            return List.of("sql-function");
        }
        return switch (token.type()) {
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

    /** 下一个 token 是左括号 → 这是一个函数调用。 */
    private static boolean isFunctionCall(List<SqlToken> tokens, int index) {
        for (int i = index + 1; i < tokens.size(); i++) {
            SqlTokenType type = tokens.get(i).type();
            if (type == SqlTokenType.WHITESPACE || type == SqlTokenType.COMMENT) {
                continue;
            }
            return type == SqlTokenType.PUNCTUATION && "(".equals(tokens.get(i).text());
        }
        return false;
    }
}
