package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ UX-4 门禁三：单元格的**空值与二进制显示**。
 *
 * <p>一张表里同时可能出现四种「看起来什么都没有」的值：
 * NULL、长度为 0 的字符串、只有空格的字符串、内容真的是文本 {@code "NULL"} 的值。
 * 用户猜错一次就要写错一条 UPDATE，所以这四种必须**长得不一样**，
 * 而且每一种都要有 tooltip 说清楚 —— 这是数据库客户端最不能含糊的地方。
 *
 * <p>BLOB 同理：一格几 MB 的二进制不能把原始字节糊在屏幕上，
 * 只显示大小，完整内容留给详情抽屉 / Hex。
 */
class CellDisplayTest {

    @Test
    void nullEmptyAndBlobEachGetTheirOwnShape() {
        ColumnDef text = column("bio", ColumnType.TEXT);

        CellRenderer.Rendered nullCell = CellRenderer.render(text, null);
        assertThat(nullCell.text()).as("NULL 显示 (NULL)").isEqualTo("(NULL)");
        assertThat(nullCell.styleClass()).as("NULL 走 --null（灰 + 斜体）")
                .isEqualTo("adb-grid__cell--null");
        assertThat(nullCell.tooltip()).as("NULL 的 tooltip 要说清它不是空串")
                .contains("NULL").contains("不是空串");

        CellRenderer.Rendered empty = CellRenderer.render(text, "");
        assertThat(empty.text()).as("空串显示引号").isEqualTo("\"\"");
        assertThat(empty.styleClass()).isEqualTo("adb-grid__cell--empty");
        assertThat(empty.tooltip()).contains("空串").contains("不是 NULL");

        byte[] blob = new byte[4096];
        CellRenderer.Rendered binary = CellRenderer.render(column("avatar", ColumnType.BINARY), blob);
        assertThat(binary.text()).as("BLOB 只显示大小").isEqualTo("⬢ 4.0 KB");
        assertThat(binary.styleClass()).isEqualTo("adb-grid__cell--binary");
        assertThat(binary.tooltip())
                .as("tooltip 给精确字节数，并告诉用户去哪看内容")
                .contains("4096 字节").contains("详情");
    }

    /**
     * ⭐ 这条是 (NULL) 带括号的全部理由：TEXT 列里真的会出现字符串 {@code "NULL"}。
     *
     * <p>如果 NULL 也显示成 {@code NULL}，两条完全不同的数据在 12px 网格里长得一模一样。
     * 变异：把 {@code render} 里的 "(NULL)" 改回 "NULL" → 这条红。
     */
    @Test
    void aTextThatLiterallySaysNullIsNotRenderedAsNull() {
        ColumnDef text = column("bio", ColumnType.TEXT);

        CellRenderer.Rendered nullValue = CellRenderer.render(text, null);
        CellRenderer.Rendered nullString = CellRenderer.render(text, "NULL");

        assertThat(nullString.text())
                .as("内容为 NULL 的文本，显示的就是它自己的四个字母")
                .isEqualTo("NULL");
        assertThat(nullString.styleClass())
                .as("它是普通文本：不许套 NULL 的灰斜体")
                .isNotEqualTo(nullValue.styleClass())
                .doesNotContain("null");
        assertThat(nullString.tooltip())
                .as("没被截断的短文本不需要 tooltip —— 别把数据当噪音解释")
                .isNull();
        assertThat(nullValue.text()).isNotEqualTo(nullString.text());
    }

    @Test
    void blankAndLongTextAreStillDistinguishableAndExplained() {
        ColumnDef text = column("note", ColumnType.TEXT);

        CellRenderer.Rendered blank = CellRenderer.render(text, "   ");
        assertThat(blank.text())
                .as("三个空格不能画成空串 —— 它是「有值，只是看不见」")
                .isNotEqualTo(CellRenderer.render(text, "").text());

        String longText = "第一行\n第二行 " + "很".repeat(120);
        CellRenderer.Rendered wrapped = CellRenderer.render(text, longText);
        assertThat(wrapped.truncated()).as("换行 + 超长要压成单行并标成截断").isTrue();
        assertThat(wrapped.text()).doesNotContain("\n").endsWith("…");
        assertThat(wrapped.styleClass()).isEqualTo("adb-grid__cell--long-text");
        assertThat(wrapped.tooltip())
                .as("截断了就必须给全文 —— tooltip 是唯一的出口")
                .contains("第二行");
    }

    @Test
    void sizeLabelsCoverFourTiers() {
        assertThat(CellRenderer.sizeLabel(0)).isEqualTo("0 B");
        assertThat(CellRenderer.sizeLabel(512)).isEqualTo("512 B");
        assertThat(CellRenderer.sizeLabel(1024)).isEqualTo("1.0 KB");
        assertThat(CellRenderer.sizeLabel(5 * 1024 * 1024)).isEqualTo("5.0 MB");
        assertThat(CellRenderer.sizeLabel(2L * 1024 * 1024 * 1024))
                .as("大对象（图片 / 音视频）就是 GB 级，不能显示成 2048.0 MB")
                .isEqualTo("2.0 GB");
    }

    /**
     * 亮暗两套主题都要能把这三种状态画出来 ——
     * 语义色写在 app.css（主题无关），颜色值来自 theme-*.css 的令牌。
     * 少定义一个令牌，那一档在对应主题里就退化成黑色。
     */
    @ParameterizedTest
    @ValueSource(strings = {"/io/agenticdb/ui/theme-light.css", "/io/agenticdb/ui/theme-dark.css"})
    void bothThemesDefineTheColorsBehindTheThreeStates(String theme) throws IOException {
        String css = readResource(theme);
        for (String token : List.of("-adb-text-disabled", "-adb-text-tertiary",
                "-adb-text-secondary")) {
            assertThat(css)
                    .as("%s 要定义 %s（NULL / 空串 / BLOB 三档语义色都靠它）", theme, token)
                    .contains(token + ":");
        }
        String app = css();
        for (String state : List.of(".adb-grid__cell--null", ".adb-grid__cell--empty",
                ".adb-grid__cell--binary")) {
            assertThat(app).as("app.css 里要有 %s 规则", state).contains(state);
        }
        assertThat(ruleBody(app, ".adb-grid__cell--null"))
                .as("NULL 是灰 + 斜体，和正文一个字号")
                .contains("-adb-text-disabled")
                .contains("-fx-font-style: italic");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static ColumnDef column(String name, ColumnType type) {
        return new ColumnDef(name, type, true, false, null, null,
                null, null, null, null, false);
    }

    private static String ruleBody(String css, String selector) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?m)^" + java.util.regex.Pattern.quote(selector) + "\\s*\\{([^}]*)\\}")
                .matcher(css);
        assertThat(matcher.find()).as("app.css 里找不到选择器 %s", selector).isTrue();
        return matcher.group(1);
    }

    private static String css() throws IOException {
        return readResource("/io/agenticdb/ui/app.css").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = CellDisplayTest.class.getResourceAsStream(path)) {
            assertThat(stream).as("缺少资源 %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
