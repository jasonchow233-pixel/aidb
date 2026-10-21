package io.agenticdb.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 焦点与交互态门禁。
 *
 * <p>实测发现：{@code app.css} 里有 **18 条 hover**，却只有 **3 条 focus** ——
 * <b>鼠标能看见焦点，键盘看不见</b>。纯键盘操作用户无法知道自己在哪。
 * 这是可访问性硬伤，不只是好不好看。
 *
 * <p>门禁要求：
 * <ol>
 *   <li>每个可聚焦的组件类都要有 {@code :focused} 规则；</li>
 *   <li>{@code -fx-opacity} 只允许剩一处（命令面板遮罩）——
 *       禁用态不许用整体压暗，那会把文字和图标一起稀释掉。</li>
 * </ol>
 */
class FocusRingAuditTest {

    /** 必须有焦点环的组件类。 */
    private static final List<String> FOCUSABLE = List.of(
            ".adb-button",
            ".adb-icon-button",
            ".adb-search-trigger",
            ".adb-feature",
            ".adb-rail__button",
            ".adb-sidebar__nav",
            ".adb-favorite",
            ".adb-tab",
            ".adb-toast__action",
            ".adb-grid__filter-button");

    @Test
    void everyFocusableComponentHasAFocusRing() throws IOException {
        String css = css();
        List<String> missing = new ArrayList<>();
        for (String component : FOCUSABLE) {
            if (!css.contains(component + ":focused")) {
                missing.add(component);
            }
        }
        assertThat(missing)
                .as("这些可聚焦组件没有焦点环 —— 键盘用户看不见自己在哪：\n  %s",
                        String.join("\n  ", missing))
                .isEmpty();
    }

    @Test
    void inputsAndGridsAlsoShowFocus() throws IOException {
        String css = css();
        // 输入类与表格 / 树的焦点由通用规则覆盖
        for (String component : List.of(".text-field", ".text-area", ".combo-box",
                ".list-view", ".tree-view", ".table-view")) {
            assertThat(css)
                    .as("%s 必须有焦点反馈", component)
                    .contains(component + ":focused");
        }
    }

    @Test
    void opacityIsNeverUsedToDimContent() throws IOException {
        String css = css();
        Matcher matcher = Pattern.compile("-fx-opacity\\s*:\\s*([0-9.]+);").matcher(css);
        List<String> dimming = new ArrayList<>();
        int allowed = 0;
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            if (value == 0.0) {
                // 0 = **藏起来**（例如列头筛选钮常态隐藏），不是压暗。
                continue;
            }
            if (value >= 1.0) {
                // 显式写 1 = 抵消 Modena 的「所有禁用控件 0.4 透明度」，不是压暗。
                continue;
            }
            // 0 与 1 之间的值 = 半透明。整份 CSS 里只允许命令面板遮罩这一处。
            allowed++;
            if (allowed > 1) {
                dimming.add(matcher.group(1));
            }
        }
        assertThat(dimming)
                .as("-fx-opacity 只允许用在命令面板遮罩这一处；"
                        + "别处用它压暗会把文字和图标一起稀释掉"
                        + "（0 = 藏起来、1 = 抵消 Modena，这两种是允许的）")
                .isEmpty();
    }

    @Test
    void disabledControlsNeutraliseModenasOpacity() throws IOException {
        // Modena 的 modena.css 给所有 :disabled 控件加 -fx-opacity: 0.4。
        // 不显式抵消，禁用的按钮就会「消失」（实测：数据页的保存按钮）。
        String css = css();
        assertThat(css)
                .as("必须显式把禁用态的透明度还原成 1")
                .containsPattern("\\.adb-button:disabled[^}]*-fx-opacity:\\s*1;");
    }

    @Test
    void disabledButtonsKeepTheirShape() throws IOException {
        String css = css();
        Matcher matcher = Pattern.compile(
                "\\.adb-button:disabled\\s*\\{([^}]*)\\}", Pattern.DOTALL).matcher(css);
        assertThat(matcher.find()).as("必须有 .adb-button:disabled 规则").isTrue();
        String body = matcher.group(1);
        assertThat(body)
                .as("禁用态要改文字颜色，而不是整体压暗")
                .contains("-adb-text-disabled")
                .doesNotContain("-fx-opacity");
    }

    @Test
    void thereAreFarMoreHoverRulesThanBefore() throws IOException {
        String css = css();
        int hover = count(css, ":hover");
        int focus = count(css, ":focused");
        assertThat(hover)
                .as("hover 规则不应被这次改动删掉")
                .isGreaterThanOrEqualTo(18);
        assertThat(focus)
                .as("focus 规则必须显著多于改之前的 3 条")
                .isGreaterThanOrEqualTo(10);
    }

    private static int count(String text, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String css() throws IOException {
        Path path = Path.of("..", "adb-ui-shell", "src", "main", "resources",
                "io", "agenticdb", "ui", "app.css");
        if (!Files.exists(path)) {
            path = Path.of("ui", "adb-ui-shell", "src", "main", "resources",
                    "io", "agenticdb", "ui", "app.css");
        }
        assertThat(path).as("app.css 路径").exists();
        return Files.readString(path, StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "");
    }
}
