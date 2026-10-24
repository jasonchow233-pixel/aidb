package io.agenticdb.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 业务 CSS 的**尺度门禁**。
 *
 * <p>JavaFX 的 CSS 没有自定义属性机制，CSS 侧只能写字面值 ——
 * 所以「令牌是唯一真相」这句话，靠的不是 CSS 引用，而是**这道门禁**：
 * {@code tokens.json} 里定了 9 档字号、7 档间距、3 档圆角，
 * {@code app.css} 里出现的每个值都必须在这些档位上。
 *
 * <p>门禁前的样子（实测）：
 * <ul>
 *   <li>字号 **13 种**、最小 9px、还有 12.5px 孤例；</li>
 *   <li>间距 **47 种**硬编码内边距；</li>
 *   <li>圆角 **5 种**（3/5/6/8/10）写死在 26 处。</li>
 * </ul>
 * 现在分别是 7 / 7 / 3 种（字号被删过两档，见 {@link #FONT_SIZES}）。
 */
class AppCssAuditTest {

    /**
     * 允许的字号（= tokens.json 的 fs-*）。
     *
     * <p>⚠️ 40px（fs-4xl）已于第十九轮批次 C 移除：它唯一的用处是欢迎页 logo 的
     * `-fx-font-size`，而 S-5 把 logo 换成了品牌 PNG —— 尺寸改由代码定，字号阶梯里
     * 不再留一个没人用的档（`TokenParityTest.everyTokenIsActuallyUsed` 会红）。
     *
     * <p>⚠️ 20px（fs-2xl）同因于 S-7 移除：欢迎页那张卡片的标题是它唯一的消费者，
     * 而那张卡片根本不可达（`AppShell` 无条件打开常驻的「对象」首页）—— 页面删了，
     * 档位跟着删。<b>规矩是删令牌，不是给令牌硬造一个消费者</b>（§31.1 第 4 行同一件事）。
     */
    private static final Set<String> FONT_SIZES = Set.of(
            "10px", "11px", "12px", "13px", "14px", "16px", "32px");

    /**
     * CSS 里允许的间距（含 0 —— 归零不是「另一个尺度」）。
     *
     * <p>UX-2 新增 {@code sp-1h} 6px：唯一的半档，只给密集网格的水平内边距用。
     */
    private static final Set<String> SPACING = Set.of("0px", "2px", "4px", "6px", "8px",
            "12px", "16px", "24px", "32px");

    /** 令牌里的间距（不含 0 —— 0 不需要令牌）。 */
    private static final Set<String> TOKEN_SPACING = Set.of("2px", "4px", "6px", "8px",
            "12px", "16px", "24px", "32px");

    /**
     * CSS 里允许的圆角（含 0 —— 直角也是合法圆角）。
     *
     * <p>UX-1 起收敛成四档：r-xs 3（复选框）/ r-sm 4（按钮、输入框）/
     * r-md 6（卡片、面板）/ r-lg 8（对话框）+ r-full 999（徽标、chip）。
     */
    private static final Set<String> RADII = Set.of("0px", "3px", "4px", "6px", "8px", "999px");

    /** 令牌里的圆角。 */
    private static final Set<String> TOKEN_RADII = Set.of("3px", "4px", "6px", "8px", "999px");

    // ─────────────────────────── 字号 ───────────────────────────

    @Test
    void everyFontSizeComesFromTheScale() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String value : values(css(), "-fx-font-size:\\s*([0-9.]+px)")) {
            if (!FONT_SIZES.contains(value)) {
                offenders.add(value);
            }
        }
        assertThat(offenders)
                .as("字号必须落在 7 档阶梯上（fs-2xs…fs-3xl，中间的 20px 档已随 S-7 删除）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    void noFontSizeBelowTenPixels() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String value : values(css(), "-fx-font-size:\\s*([0-9.]+)px")) {
            double size = Double.parseDouble(value);
            if (size < 10) {
                offenders.add(value + "px");
            }
        }
        assertThat(offenders)
                .as("9px 低于任何可读下限 —— 全部废除")
                .isEmpty();
    }

    @Test
    void theFontScaleMatchesTheTokens() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        Set<String> fromTokens = new LinkedHashSet<>();
        tokens.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("fs-")) {
                fromTokens.add(entry.getValue().path("light").asText());
            }
        });
        assertThat(fromTokens)
                .as("字号阶梯的来源只能是 tokens.json")
                .isEqualTo(FONT_SIZES);
    }

    // ─────────────────────────── 间距 ───────────────────────────

    @Test
    void everyPaddingComesFromTheFourPixelScale() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String value : values(css(), "-fx-padding:\\s*([^;]+);")) {
            for (String part : value.trim().split("\\s+")) {
                if (!SPACING.contains(part)) {
                    offenders.add(value.trim() + "（含 " + part + "）");
                }
            }
        }
        assertThat(offenders)
                .as("间距必须落在 4px 阶梯上（sp-1…sp-7）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    void theSpacingScaleMatchesTheTokens() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        Set<String> fromTokens = new LinkedHashSet<>();
        tokens.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("sp-")) {
                fromTokens.add(entry.getValue().path("light").asText());
            }
        });
        assertThat(fromTokens).isEqualTo(TOKEN_SPACING);
    }

    /**
     * ⭐ UX-2：数据区正文必须是 12px（Navicat 实拍密度）。
     *
     * <p>只靠「字号落在阶梯上」不够 —— 13px 也是合法档位。这条门禁盯住
     * **具体落点**：网格 / 清单 / 树 / 列头 / 工具条按钮 / 结构页 / 连接页。
     * 变异：把网格单元格改回 13px → 红。
     */
    @Test
    void dataSurfacesUseTheCompactBodySize() throws IOException {
        String css = css();
        assertThat(fontSizeOf(css, ".adb-grid .table-cell"))
                .as("数据网格正文")
                .isEqualTo("12px");
        assertThat(fontSizeOf(css, ".adb-object-list .table-cell"))
                .as("对象清单正文")
                .isEqualTo("12px");
        assertThat(fontSizeOf(css, ".adb-tree__name"))
                .as("对象树名称")
                .isEqualTo("12px");
        assertThat(fontSizeOf(css, ".adb-grid__column-name"))
                .as("单行列头")
                .isEqualTo("12px");
        assertThat(fontSizeOf(css, ".adb-button"))
                .as("工具条主操作标签")
                .isEqualTo("12px");
        assertThat(fontSizeOf(css, ".adb-structure__table .table-cell"))
                .as("结构页表格正文")
                .isEqualTo("12px");
        assertThat(fontSizeOf(css, ".adb-connections__table .table-cell"))
                .as("连接页表格正文")
                .isEqualTo("12px");
    }

    // ─────────────────────────── 圆角 ───────────────────────────

    @Test
    void everyRadiusComesFromTheScale() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String value : values(css(),
                "-fx-(?:border|background)-radius:\\s*([^;]+);")) {
            for (String part : value.trim().split("\\s+")) {
                if (!RADII.contains(part)) {
                    offenders.add(value.trim() + "（含 " + part + "）");
                }
            }
        }
        assertThat(offenders)
                .as("圆角必须落在四档上（r-xs 3 / r-sm 4 / r-md 6 / r-lg 8）+ r-full 999：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    void theRadiusScaleMatchesTheTokens() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        Set<String> fromTokens = new LinkedHashSet<>();
        tokens.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("r-")) {
                fromTokens.add(entry.getValue().path("light").asText());
            }
        });
        assertThat(fromTokens).isEqualTo(TOKEN_RADII);
    }

    // ─────────────────────────── 投影 ───────────────────────────

    /**
     * ⭐ UX-1：投影只留**浮层一级**。
     *
     * <p>扁平化不是「完全不要阴影」—— 命令面板 / toast 这类浮层没有阴影就会
     * 和页面粘在一起；但卡片、抽屉、对话框再投影就回到「网页卡片」的观感。
     * 所以只允许两类：焦点环（{@code border-focus}，不是阴影）与
     * {@code shadow-1} 浮层投影；{@code shadow-2} 已随令牌删除。
     */
    @Test
    void shadowsAreLimitedToTheFloatingElevation() throws IOException {
        String css = css();
        List<String> effects = values(css, "-fx-effect:\\s*([^;]+);");
        List<String> offenders = effects.stream()
                .filter(value -> !value.trim().equals("null"))
                .filter(value -> !value.contains("-adb-border-focus")
                        && !value.contains("-adb-shadow-1"))
                .toList();
        assertThat(offenders)
                .as("投影只允许「焦点环」与 floating shadow-1；卡片/抽屉/对话框不许投影：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
        assertThat(css)
                .as("shadow-2 已删除（只保留一级浮层投影）")
                .doesNotContain("-adb-shadow-2");
        assertThat(effects.stream().filter(value -> value.contains("-adb-shadow-1")).count())
                .as("浮层投影必须真的用在浮层上（命令面板 / toast）")
                .isBetween(1L, 2L);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 取某个选择器规则块里的字号；找不到规则 / 没写字号直接失败。 */
    private static String fontSizeOf(String css, String selector) {
        // ⚠️ 必须锚在行首：`.adb-tree__name {` 是 `.x .adb-tree__name {` 的
        //    子串，不锚定会先匹配到子选择器那条规则（它只设颜色，没有字号）。
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)\\}")
                .matcher(css);
        assertThat(matcher.find())
                .as("app.css 里找不到选择器 %s", selector)
                .isTrue();
        Matcher font = Pattern.compile("-fx-font-size:\\s*([0-9.]+px)")
                .matcher(matcher.group(1));
        assertThat(font.find())
                .as("选择器 %s 必须显式写字号", selector)
                .isTrue();
        return font.group(1);
    }

    private static List<String> values(String text, String regex) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static String css() throws IOException {
        return Files.readString(businessCss(), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static Path businessCss() {
        Path path = Path.of("..", "adb-ui-shell", "src", "main", "resources",
                "io", "agenticdb", "ui", "app.css");
        if (!Files.exists(path)) {
            path = Path.of("ui", "adb-ui-shell", "src", "main", "resources",
                    "io", "agenticdb", "ui", "app.css");
        }
        assertThat(path).as("app.css 路径").exists();
        return path;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = AppCssAuditTest.class.getResourceAsStream(path)) {
            assertThat(stream).as("缺少资源 %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
