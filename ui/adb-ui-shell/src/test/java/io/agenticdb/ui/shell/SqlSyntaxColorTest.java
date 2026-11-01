package io.agenticdb.ui.shell;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ N22 门禁：查询编辑器的语法配色与 Navicat 17 对齐。
 *
 * <p>三件事：
 * <ol>
 *   <li>四种主色落在主题令牌上（关键字 #097BF7 粗、字符串 #FF1744、注释 #929292、正文 #424242）；</li>
 *   <li>表名（FROM / JOIN / UPDATE / INTO / TABLE 后的标识符）带下划线；</li>
 *   <li>配色走令牌而不是在 app.css 里写死十六进制 —— 否则暗色主题必然漏一处。</li>
 * </ol>
 */
class SqlSyntaxColorTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = SqlSyntaxColorTest.class.getResourceAsStream(path)) {
            assertThat(in).as("资源必须存在：" + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void lightThemeUsesTheNavicatPalette() throws IOException {
        String css = resource("/io/agenticdb/ui/theme-light.css");
        assertThat(css).contains("-adb-syn-keyword: #097BF7;");
        assertThat(css).contains("-adb-syn-string: #FF1744;");
        assertThat(css).contains("-adb-syn-comment: #929292;");
        assertThat(css).contains("-adb-syn-ident: #424242;");
    }

    @Test
    void darkThemeStillDefinesEverySyntaxToken() throws IOException {
        String css = resource("/io/agenticdb/ui/theme-dark.css");
        for (String token : List.of("keyword", "string", "number", "comment",
                "function", "type", "ident", "param", "error")) {
            assertThat(css).as("暗色主题缺 -adb-syn-%s", token)
                    .contains("-adb-syn-" + token + ":");
        }
    }

    @Test
    void editorStylesReadTokensAndUnderlineTables() throws IOException {
        String css = resource("/io/agenticdb/ui/app.css");
        assertThat(css).contains(".adb-sql-editor .text {\n    -fx-fill: -adb-syn-ident;");
        assertThat(css).contains(".sql-keyword {\n    -fx-fill: -adb-syn-keyword;\n    -fx-font-weight: bold;");
        assertThat(css).contains(".sql-table {\n    -fx-underline: true;");
    }

    @Test
    void tableNamesAfterFromJoinUpdateAndIntoAreMarked() {
        String sql = "SELECT * FROM users u, orders o "
                + "JOIN items i ON i.order_id = o.id WHERE u.id > 0;";
        assertThat(stylesAt(sql, "users")).contains(SqlHighlighter.TABLE_STYLE);
        assertThat(stylesAt(sql, "orders")).contains(SqlHighlighter.TABLE_STYLE);
        assertThat(stylesAt(sql, "items")).contains(SqlHighlighter.TABLE_STYLE);
        assertThat(stylesAt(sql, "o.id")).as("别名不是表名").doesNotContain(SqlHighlighter.TABLE_STYLE);
        assertThat(stylesAt(sql, "SELECT")).as("关键字不吃表名下划线")
                .doesNotContain(SqlHighlighter.TABLE_STYLE);
    }

    @Test
    void updateIntoAndTableIntroduceNames() {
        assertThat(stylesAt("UPDATE users SET name = 'x'", "users"))
                .contains(SqlHighlighter.TABLE_STYLE);
        assertThat(stylesAt("INSERT INTO logs (msg) VALUES ('x')", "logs"))
                .contains(SqlHighlighter.TABLE_STYLE);
        assertThat(stylesAt("ALTER TABLE audit DROP COLUMN x", "audit"))
                .contains(SqlHighlighter.TABLE_STYLE);
    }

    private static Collection<String> stylesAt(String sql, String word) {
        int index = sql.indexOf(word);
        assertThat(index).as("测试语句里必须有 %s", word).isGreaterThanOrEqualTo(0);
        StyleSpans<Collection<String>> spans = SqlHighlighter.compute(sql);
        int offset = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            int end = offset + span.getLength();
            if (index >= offset && index < end) {
                return span.getStyle();
            }
            offset = end;
        }
        return List.of();
    }
}
