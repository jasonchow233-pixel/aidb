package io.agenticdb.ui;

import org.junit.jupiter.api.DisplayName;
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
 * 字体族门禁。
 *
 * <p>{@link AppCssAuditTest} 钉住了字号、间距、圆角的**阶梯**，但字体**族**是
 * 另一类东西：阶梯可以只有五档，字体栈却必须在每个落点都写全。全仓只有两个
 * 合法栈 —— 正文走系统 UI 栈（含 CJK 与兜底），代码走跨平台等宽栈（含 Windows
 * 上的 Consolas 与中文兜底 Noto Sans Mono）。
 *
 * <p>拦的是这两种真实回归：
 * <ul>
 *   <li>mac 字体名（Menlo / SF Mono）单独出现在栈首 —— Windows 上静默回落
 *       非等宽字体，数字列瞬间失应对齐；</li>
 *   <li>等宽栈漏掉 CJK 兜底 —— 中文注释在等宽列里退回系统 UI 字体，一列两种观感。</li>
 * </ul>
 *
 * <p>只查 CSS：Java 侧设字体只有 {@link Icons} 的图标字体和少数动态构造，
 * 那些不属于「样式表里的字体栈」这一类问题。
 */
@DisplayName("app.css 的字体族：全仓只有 UI 栈与等宽栈两套，且等宽栈必须跨平台")
class FontGuardTest {

    /** 合法的等宽栈必须同时具备的两件事：Windows 有原生等宽、中文有兜底。 */
    private static final List<String> MONO_MUST_CONTAIN = List.of("Consolas", "Noto Sans Mono");

    /** mac 专属字体名。出现在 CSS 里可以，但**不许排在栈首**（Windows 命中不到）。 */
    private static final List<String> MAC_ONLY_FONTS = List.of("Menlo", "SF Mono");

    @Test
    void noMonospaceStackStartsWithAMacOnlyFont() throws IOException {
        String css = readAppCss();
        List<String> offenders = new ArrayList<>();
        Matcher m = Pattern.compile("-fx-font-family:\\s*([^;]+);").matcher(css);
        while (m.find()) {
            String stack = m.group(1).trim();
            String first = stack.split(",")[0].trim().replaceAll("^\"|\"$", "");
            // monospace 是 JavaFX 的族关键字，写在栈首等于「系统默认等宽」，观感不可控。
            if (MAC_ONLY_FONTS.contains(first) || first.equals("monospace")) {
                offenders.add(stack);
            }
        }
        assertThat(offenders)
                .as("等宽/字体栈不许以 mac 专属字体或裸 monospace 开头 —— "
                        + "实拍与主要用户都在 Windows，那儿命中不到（见任务书 U2）：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    void everyMonospaceStackHasAWindowsFaceAndAChineseFallback() throws IOException {
        String css = readAppCss();
        List<String> offenders = new ArrayList<>();
        List<String> stacks = new ArrayList<>();
        Matcher m = Pattern.compile("-fx-font-family:\\s*([^;]+);").matcher(css);
        while (m.find()) {
            String stack = m.group(1).trim();
            // 认「等宽栈」的锚点：Consolas 只可能出现在等宽栈里（UI 栈没有它）。
            if (stack.contains("Consolas")) {
                stacks.add(stack);
                for (String required : MONO_MUST_CONTAIN) {
                    if (!stack.contains(required)) {
                        offenders.add(stack + "（缺 " + required + "）");
                    }
                }
            }
        }
        assertThat(stacks)
                .as("一条等宽栈都没扫到 —— 锚点失效，门禁是假绿")
                .isNotEmpty();
        assertThat(offenders)
                .as("等宽栈必须跨平台（Windows 原生 Consolas）且给中文留兜底"
                        + "（Noto Sans Mono），否则代码区一列混两种观感：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    @Test
    void rootUsesTheCrossPlatformUiStack() throws IOException {
        String css = readAppCss();
        Matcher m = Pattern.compile("\\.root\\s*\\{([^}]*)\\}", Pattern.DOTALL).matcher(css);
        assertThat(m.find()).as(".root 找不到 —— 全局字体失去了唯一落点").isTrue();
        String root = m.group(1);
        Matcher family = Pattern.compile("-fx-font-family:\\s*([^;]+);").matcher(root);
        assertThat(family.find()).as(".root 必须显式声明 UI 字体栈").isTrue();
        String stack = family.group(1).trim();
        assertThat(stack)
                .as(".root 的 UI 栈必须含 Windows 原生 UI 字体与中文兜底")
                .contains("Segoe UI")
                .contains("Microsoft YaHei UI");
    }

    private static String readAppCss() throws IOException {
        // app.css 住在 adb-ui-shell 的产物里，不是本模块的 classpath 资源：
        // 读文件（模块目录 / 仓库根两种落点都试），并去掉注释 —— 文件头抄了
        // 两份规范栈当口径说明，留着会把违规一起洗成「合法」。
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
