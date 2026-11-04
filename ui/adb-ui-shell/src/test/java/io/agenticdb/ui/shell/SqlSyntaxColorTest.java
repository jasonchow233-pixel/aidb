package io.agenticdb.ui.shell;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ N22 门禁（D-50 改判）：查询编辑器的语法配色**真的画在屏幕上**。
 *
 * <h2>为什么这一版把断言从 CSS 文本换成计算值</h2>
 * 原来的 {@code editorStylesReadTokensAndUnderlineTables} 断的是 app.css 里**有**那几条规则 ——
 * 它一直在绿，而编辑器里关键字实际画出来是「加粗的黑字」：
 * {@code .adb-sql-editor .text}（特异性 0,2,0）把 {@code .sql-keyword} 那批（0,1,0）整体压平，
 * 每条 span 的 {@code -fx-fill} 都被兜底那条抢走。样式表里字字都在，计算值一个不生效。
 * → 本代硬口径：**样式类门禁必须读节点计算值，CSS 文本断言不算证据**。
 *
 * <p>三件事仍然要钉：
 * <ol>
 *   <li>五种 token 各自的计算填充色 == 主题令牌的值（走 {@code -adb-syn-*}，不在 app.css 里写死十六进制，
 *       否则暗色档必然漏一处）；</li>
 *   <li>表名（FROM / JOIN / UPDATE / INTO / TABLE 后的标识符）带下划线，正文色不受影响；</li>
 *   <li>括号配对高亮也在同一批 Text 节点上，同样不许被兜底抢走。</li>
 * </ol>
 *
 * <p>⚠️ 期望色一律**从主题文件里读出来**再比，不在这份测试里抄一遍十六进制 ——
 * 抄了就成了「测试和实现各写一份色值」，令牌改了测试跟着改，等于没有门禁。
 */
@ExtendWith(ApplicationExtension.class)
class SqlSyntaxColorTest {

    private static final String LIGHT = "/io/agenticdb/ui/theme-light.css";
    private static final String DARK = "/io/agenticdb/ui/theme-dark.css";

    /** 一句把五种 token 都用上的 SQL；每种都靠**整段文本**去认节点，不靠位置。 */
    private static final String SAMPLE =
            "SELECT name FROM users WHERE score = 42 AND note = 'abc' -- why\n";

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

    // ────────────────── D-50：计算值，不是样式表文本 ──────────────────

    /** 关键字 / 字符串 / 数字 / 注释 / 运算符各自的**计算填充色**就是它那个令牌。 */
    @Test
    void everyTokenKindActuallyRendersInTheColorItsClassDeclares() {
        List<Text> texts = render(SAMPLE, false);
        assertFill(texts, LIGHT, "-adb-syn-keyword", "SELECT", "FROM", "WHERE", "AND");
        assertFill(texts, LIGHT, "-adb-syn-string", "'abc'");
        assertFill(texts, LIGHT, "-adb-syn-number", "42");
        assertFill(texts, LIGHT, "-adb-syn-comment", "-- why");
        assertFill(texts, LIGHT, "-adb-syn-type", "=", "==", "!=", "<>");
    }

    /** 关键字那档除颜色外还带粗体 —— 兜底那条只抢 {@code -fx-fill}，所以粗体一直是有的：
     *  「加粗的黑字」正是这个缺陷在帧上的样子，两条一起钉才拦得住。 */
    @Test
    void keywordsAreBothBoldAndColored() {
        List<Text> texts = render(SAMPLE, false);
        List<Text> select = nodes(texts, "SELECT");
        assertThat(select).as("编辑器里必须真的画出 SELECT 这个 Text 节点").isNotEmpty();
        assertThat(renderedFill(select.get(0)))
                .as("SELECT 的计算填充色必须是关键字令牌（其余节点实测 %s）",
                        select.stream().map(SqlSyntaxColorTest::renderedFill).toList())
                .isEqualTo(themeColor(LIGHT, "-adb-syn-keyword"));
        assertThat(select.get(0).getFont().getStyle()).as("关键字加粗").contains("Bold");
    }

    /** 正文（无 token 类的普通标识符）继续吃 {@code -adb-syn-ident} —— 修特异性不许把兜底修没。 */
    @Test
    void plainIdentifiersStillTakeTheBodyColor() {
        List<Text> texts = render(SAMPLE, false);
        assertFill(texts, LIGHT, "-adb-syn-ident", "name", "score", "note");
    }

    /** 表名下划线（N22）也在同一批 Text 节点上生效，且颜色仍是正文色。 */
    @Test
    void tableNamesAreUnderlinedAndStillBodyColored() {
        List<Text> texts = render(SAMPLE, false);
        List<Text> users = nodes(texts, "users");
        assertThat(users).as("FROM 后面的 users 要画出节点").isNotEmpty();
        assertThat(users.get(0).isUnderline()).as("表名带下划线").isTrue();
        assertFill(texts, LIGHT, "-adb-syn-ident", "users");
    }

    /** 暗色档走同一条链：每个令牌换成暗色档自己的值，而不是漏一处留在亮色上。 */
    @Test
    void theDarkThemeRecolorsEveryTokenKind() {
        List<Text> texts = render(SAMPLE, true);
        assertFill(texts, DARK, "-adb-syn-keyword", "SELECT");
        assertFill(texts, DARK, "-adb-syn-string", "'abc'");
        assertFill(texts, DARK, "-adb-syn-number", "42");
        assertFill(texts, DARK, "-adb-syn-comment", "-- why");
        assertFill(texts, DARK, "-adb-syn-ident", "users");
    }

    /** 括号配对高亮（Q1）与语法配色住在同一个兜底的射程里，一起量。 */
    @Test
    void theBracketMatchHighlightSurvivesTheSameCascade() {
        String sql = "SELECT (1)";
        List<Text> marked = renderAtCaret(sql, 7, false);
        List<Text> brackets = marked.stream()
                .filter(t -> t.getStyleClass().contains(SqlHighlighter.BRACKET_STYLE))
                .toList();
        assertThat(brackets)
                .as("光标停在 ( 后面时，( 与 ) 两个 Text 节点必须带上配对高亮类")
                .isNotEmpty();
        String expected = themeColor(LIGHT, "-adb-accent");
        assertThat(brackets).as("配对括号两枚的计算填充色都要是强调色")
                .allSatisfy(t -> assertThat(renderedFill(t)).isEqualTo(expected));
    }

    // ─────────────────────────── 渲染通道 ───────────────────────────

    /** 在主题场景里真渲染一次，按父→子逐层 applyCss 后收 Text 节点。 */
    private static List<Text> render(String sql, boolean dark) {
        return WaitForAsyncUtils.waitForAsyncFx(10_000, () -> {
            SqlEditor editor = new SqlEditor();
            editor.area().replaceText(sql);
            StackPane host = new StackPane(editor);
            host.getStyleClass().add(dark ? "theme-dark" : "theme-light");
            Scene scene = new Scene(host, 620, 200);
            scene.getStylesheets().add(style(dark
                    ? "/io/agenticdb/ui/theme-dark.css" : "/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            // ⚠️ applyCss() 不向下层叠；而且 Text 节点是布局时才生成的 ——
            //    先布局、再全树 applyCss、再布局一次，漏一步量到的就是 null。
            layoutAndStyle(host);
            return texts(host);
        });
    }

    /** 同上，但光标停在 {@code caret} 处（配对括号高亮要由光标位置驱动）。 */
    private static List<Text> renderAtCaret(String sql, int caret, boolean dark) {
        return WaitForAsyncUtils.waitForAsyncFx(10_000, () -> {
            SqlEditor editor = new SqlEditor();
            editor.area().replaceText(sql);
            editor.moveTo(caret);
            StackPane host = new StackPane(editor);
            host.getStyleClass().add(dark ? "theme-dark" : "theme-light");
            Scene scene = new Scene(host, 620, 200);
            scene.getStylesheets().add(style(dark
                    ? "/io/agenticdb/ui/theme-dark.css" : "/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            layoutAndStyle(host);
            return texts(host);
        });
    }

    private static void layoutAndStyle(Parent host) {
        layoutTree(host);
        applyCssTree(host);
        layoutTree(host);
        applyCssTree(host);
    }

    /** 先子后父：RichTextFX 的 Text 节点是在布局里生成的，顺序反了第一遍就收不到节点。 */
    private static void layoutTree(Parent node) {
        for (Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Parent parent) {
                layoutTree(parent);
            }
        }
        node.layout();
    }

    private static void applyCssTree(Parent node) {
        node.applyCss();
        for (Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Parent parent) {
                applyCssTree(parent);
            } else {
                child.applyCss();
            }
        }
    }

    private static List<Text> texts(Parent node) {
        List<Text> found = new ArrayList<>();
        for (Node child : node.getChildrenUnmodifiable()) {
            if (child instanceof Text text) {
                found.add(text);
            } else if (child instanceof Parent parent) {
                found.addAll(texts(parent));
            }
        }
        return found;
    }

    private static List<Text> nodes(List<Text> all, String text) {
        return all.stream().filter(t -> text.equals(t.getText())).toList();
    }

    /** 断「画出这些文本的节点，计算填充色 == 令牌在这份主题里的值」。 */
    private static void assertFill(List<Text> all, String theme, String token, String... samples) {
        String expected = themeColor(theme, token);
        for (String sample : samples) {
            List<Text> found = nodes(all, sample);
            if (found.isEmpty()) {
                continue; // 这句 SQL 里没有这个形状（比如只有一种运算符）
            }
            assertThat(found.stream().map(SqlSyntaxColorTest::renderedFill).distinct())
                    .as("%s 画出来的计算色必须是 %s（样式表里有规则不等于它生效）", sample, token)
                    .containsExactly(expected);
        }
    }

    /** 从主题文件里读一个令牌的十六进制值，测试不自己抄第二份色值。 */
    private static String themeColor(String themeResource, String token) {
        String css;
        try {
            css = resource(themeResource);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Matcher m = Pattern.compile(Pattern.quote(token) + ":\\s*(#[0-9A-Fa-f]{3,8})")
                .matcher(css);
        assertThat(m.find()).as("主题里必须有令牌 %s", token).isTrue();
        return m.group(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 节点**算出来**的填充色（不是样式表里写了什么）。 */
    private static String renderedFill(Text text) {
        return text.getFill() instanceof Color color ? hex(color) : String.valueOf(text.getFill());
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    private static String style(String path) {
        var url = SqlSyntaxColorTest.class.getResource(path);
        assertThat(url).as("样式表必须能加载：" + path).isNotNull();
        return url.toExternalForm();
    }

    // ─────────────────────────── 分层规则（模型侧） ───────────────────────────

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
