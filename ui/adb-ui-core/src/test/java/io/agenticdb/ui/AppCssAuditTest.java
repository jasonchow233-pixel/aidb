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
 * 现在分别是 9 / 7 / 3 种。
 */
class AppCssAuditTest {

    /** 允许的字号（= tokens.json 的 fs-*）。 */
    private static final Set<String> FONT_SIZES = Set.of(
            "10px", "11px", "12px", "13px", "14px", "16px", "20px", "32px", "40px");

    /** CSS 里允许的间距（含 0 —— 归零不是「另一个尺度」）。 */
    private static final Set<String> SPACING = Set.of("0px", "2px", "4px", "8px",
            "12px", "16px", "24px", "32px");

    /** 令牌里的间距（不含 0 —— 0 不需要令牌）。 */
    private static final Set<String> TOKEN_SPACING = Set.of("2px", "4px", "8px",
            "12px", "16px", "24px", "32px");

    /**
     * CSS 里允许的圆角（含 0 —— 直角也是合法圆角）。
     *
     * <p>r-sm 4 / r-md 6 / r-lg 8 / r-xl 12（弹窗）+ r-full 999。
     */
    private static final Set<String> RADII = Set.of("0px", "4px", "6px", "8px", "12px", "999px");

    /** 令牌里的圆角。 */
    private static final Set<String> TOKEN_RADII = Set.of("4px", "6px", "8px", "12px", "999px");

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
                .as("字号必须落在 9 档阶梯上（fs-2xs…fs-4xl）：\n  %s",
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
                .as("圆角必须落在 3 档上（r-sm 3 / r-md 5 / r-lg 8）+ r-full 999：\n  %s",
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

    // ─────────────────────────── 工具 ───────────────────────────

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
