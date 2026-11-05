package io.agenticdb.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对比度门禁（V-8）。
 *
 * <h2>为什么要有它</h2>
 * 颜色是**唯一真相**（{@code tokens.json}），但「令牌本身」不能保证可读 ——
 * 改一个色值，某个文字与背景的对比度可能悄悄掉到 4.5:1 以下，
 * 而**没有任何人会看出来**（要肉眼盯着两处颜色比对）。
 *
 * <p>这条门禁把「对比度」变成可执行的断言：改色 → 门禁红 → 改回来或调整色值。
 *
 * <h2>阈值</h2>
 * WCAG AA：正常文字 <b>4.5:1</b>；大号文字（≥24px，或 ≥18.66px 粗体）3:1。
 * 本项目正文是 12px，所以一律按 4.5:1 要求。
 *
 * <h2>半透明色怎么处理</h2>
 * 风险徽标的底色是 {@code rgba(..., .08)} —— 它**叠在页面底色上**才是真正的底色。
 * 所以算法是：<b>先把半透明底色合成到页面底上，再算对比度</b>。
 * （早期版本忽略 alpha，直接把 8% 的底色当成实色 → 算出来全是 1.00:1，结论是假的。）
 */
class ContrastTest {

    /** WCAG AA：正常文字的最低对比度。 */
    private static final double MIN_NORMAL = 4.5;

    /** ⭐ U12：灰阶相邻两档之间至少要有多少对比度，肉眼才分得出是两档。 */
    private static final double MIN_ADJACENT_GRAY_GAP = 1.4;

    /**
     * 必须达标的「文字 / 背景」配对 —— 每一对都是**实际用到的**组合（见 {@code app.css}）。
     *
     * <p>第三项是「底色叠在谁上面」（半透明底色用），默认叠在页面底色上。
     */
    private static final Map<String, String[]> PAIRS = new LinkedHashMap<>();

    static {
        // ── 亮色主题 ──
        PAIRS.put("正文 / 页面底", new String[]{"text-primary", "bg-canvas", "bg-canvas"});
        PAIRS.put("正文 / 面板", new String[]{"text-primary", "bg-surface", "bg-canvas"});
        PAIRS.put("正文 / 浮层", new String[]{"text-primary", "bg-elevated", "bg-canvas"});
        PAIRS.put("正文 / 凹陷区", new String[]{"text-primary", "bg-inset", "bg-canvas"});
        PAIRS.put("次要文字 / 页面底", new String[]{"text-secondary", "bg-canvas", "bg-canvas"});
        PAIRS.put("次要文字 / 面板", new String[]{"text-secondary", "bg-surface", "bg-canvas"});
        // ⭐ U12：凹陷区（输入框 / 网格底）是次要文字实际落底的地方，
        // 以前只查了页面底 —— 老版 #64748B 在 #F1F5F9 上实测 4.34:1，一直不达 AA。
        PAIRS.put("次要文字 / 凹陷区", new String[]{"text-secondary", "bg-inset", "bg-canvas"});
        PAIRS.put("三级文字 / 页面底", new String[]{"text-tertiary", "bg-canvas", "bg-canvas"});
        PAIRS.put("三级文字 / 浮层", new String[]{"text-tertiary", "bg-elevated", "bg-canvas"});
        PAIRS.put("三级文字 / 凹陷区", new String[]{"text-tertiary", "bg-inset", "bg-canvas"});
        // ⭐ D-57：NULL 格 / 行号 / 布尔 ✗ 三址从 2.1:1 的禁用灰挪进三级文字档后，
        // 行号列的底是面板档（bg-surface）—— 这一对以前台账里没有，门禁永远不问。
        PAIRS.put("三级文字 / 面板", new String[]{"text-tertiary", "bg-surface", "bg-canvas"});
        // ⭐ 2026-10-30 R36 用户裁定：accent 取 Navicat 原值 #0078D7 —— 白字在它上面
        //    实测 4.498:1，差 0.002 够不到 AA。这是**主动放弃**，不是漏网：
        //    第四项给这一对单独 4.4 的下限，其余配对仍按 4.5 卡。
        PAIRS.put("反色文字 / 主色", new String[]{"text-inverse", "accent", "bg-canvas", "4.4"});
        PAIRS.put("错误文字 / 页面底", new String[]{"error", "bg-canvas", "bg-canvas"});
        PAIRS.put("错误文字 / 面板", new String[]{"error", "bg-surface", "bg-canvas"});
        PAIRS.put("警告文字 / 页面底", new String[]{"warning", "bg-canvas", "bg-canvas"});
        // 截断徽标（U13）：橙字现在落在 8% 的 warning 底上，得按实际叠色结果验。
        PAIRS.put("警告文字 / 警告底", new String[]{"warning", "warning-bg", "bg-canvas"});
        PAIRS.put("成功文字 / 页面底", new String[]{"success", "bg-canvas", "bg-canvas"});
        // ⭐ U13：未保存（dirty）现在是自己的一档色，不再借「写入风险」的橙；
        // 它同样以文字形式出现在行号列 / 页签 / 状态栏，所以按文字要求卡。
        PAIRS.put("未保存文字 / 页面底", new String[]{"dirty", "bg-canvas", "bg-canvas"});
        PAIRS.put("未保存文字 / 凹陷区", new String[]{"dirty", "bg-inset", "bg-canvas"});
        PAIRS.put("信息文字 / 页面底", new String[]{"info", "bg-canvas", "bg-canvas"});
        // ── 风险徽标：文字是实色，底是 8% 半透明，叠在页面底上 ──
        PAIRS.put("风险 0 文字 / 风险 0 底", new String[]{"risk-0-read", "risk-0-bg", "bg-canvas"});
        PAIRS.put("风险 1 文字 / 风险 1 底", new String[]{"risk-1-write", "risk-1-bg", "bg-canvas"});
        PAIRS.put("风险 2 文字 / 风险 2 底", new String[]{"risk-2-structure", "risk-2-bg", "bg-canvas"});
        PAIRS.put("风险 3 文字 / 风险 3 底", new String[]{"risk-3-admin", "risk-3-bg", "bg-canvas"});
        // ── 对象类型色（图标颜色，图形按 3:1 要求）──
        PAIRS.put("类型色 表 / 页面底", new String[]{"kind-table", "bg-canvas", "bg-canvas"});
        PAIRS.put("类型色 视图 / 页面底", new String[]{"kind-view", "bg-canvas", "bg-canvas"});
        PAIRS.put("类型色 函数 / 页面底", new String[]{"kind-function", "bg-canvas", "bg-canvas"});
        PAIRS.put("类型色 触发器 / 页面底", new String[]{"kind-trigger", "bg-canvas", "bg-canvas"});
        PAIRS.put("类型色 过程 / 页面底", new String[]{"kind-procedure", "bg-canvas", "bg-canvas"});
    }

    @Test
    void everyTextOnBackgroundPairMeetsWcagAa() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");

        List<String> failures = new ArrayList<>();
        for (String theme : new String[]{"light", "dark"}) {
            for (Map.Entry<String, String[]> pair : PAIRS.entrySet()) {
                String[] keys = pair.getValue();
                double min = keys.length > 3 ? Double.parseDouble(keys[3]) : MIN_NORMAL;
                double ratio = contrast(tokens, keys[0], keys[1], keys[2], theme);
                if (ratio < min) {
                    failures.add(String.format("%s / %s：%.2f:1（要求 ≥ %.2f:1）",
                            theme, pair.getKey(), ratio, min));
                }
            }
        }

        assertThat(failures)
                .as("文字与背景的对比度必须达到 WCAG AA（4.5:1）——\n  %s",
                        String.join("\n  ", failures))
                .isEmpty();
    }

    @Test
    void focusRingStandsOutAgainstTheBackground() throws IOException {
        // 焦点环是「键盘用户能不能看见自己在哪」的唯一依据。
        //
        // ⚠️ 判据是「**与背景**的对比度」，不是「比正文亮」：
        // 暗色主题里正文是接近白的 #e6edf3，而焦点环是亮蓝 —— 焦点环必然比正文**暗**，
        // 但它与深色背景的对比度很高，照样清晰可见。
        // （非文字元素的 WCAG AA 要求是 3:1。）
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        for (String theme : new String[]{"light", "dark"}) {
            double ratio = contrast(tokens, "border-focus", "bg-canvas", "bg-canvas", theme);
            assertThat(ratio)
                    .as("%s 主题：焦点环与页面底的对比度必须 ≥ 3:1（否则键盘用户看不见焦点）", theme)
                    .isGreaterThanOrEqualTo(3.0);
        }
    }

    /**
     * ⭐ U12：三档灰必须**肉眼可分**。
     *
     * <p>对比度过 AA 只保证「读得清」，不保证「看得出这是一档、那是另一档」。
     * 旧值 text-secondary #64748B 与 text-tertiary #5F6E82 都在白底上达标（4.76 / 5.20），
     * 彼此却只差 1.09:1 —— 设计稿里叫「三级层次」，屏幕上实际只有两档，
     * 结果是次要文字与参考信息长得一样，层级白搭。
     *
     * <p>判据：相邻两档灰之间的对比度 ≥ 1.4:1（肉眼能稳定分辨的最小台阶）。
     */
    @Test
    void theThreeGraysAreActuallyThreeSteps() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        String[] ramp = {"text-primary", "text-secondary", "text-tertiary"};
        List<String> failures = new ArrayList<>();
        for (String theme : new String[]{"light", "dark"}) {
            for (int i = 0; i + 1 < ramp.length; i++) {
                double gap = contrast(tokens, ramp[i], ramp[i + 1], "bg-canvas", theme);
                if (gap < MIN_ADJACENT_GRAY_GAP) {
                    failures.add(String.format("%s 主题：%s 与 %s 只差 %.2f:1（要求 ≥ %.2f:1）",
                            theme, ramp[i], ramp[i + 1], gap, MIN_ADJACENT_GRAY_GAP));
                }
            }
        }
        assertThat(failures)
                .as("灰阶相邻两档必须肉眼可分，否则「三级文字」只是三级名字\n  %s",
                        String.join("\n  ", failures))
                .isEmpty();
    }

    /**
     * ⭐ R35：彩色图标的**轮廓色**必须与两种主题的背景都拉开 ≥3:1。
     *
     * <p>图标是**非文字元素**，WCAG 的要求是 3:1 而不是 4.5:1
     * （与上面的焦点环同一判据、同一理由：这里卡的不是可读性，是「看不看得出这是个图形」）。
     *
     * <p>为什么只卡 {@code icon-edge}：分层里 tone-a/b/c 是**装饰性内填充**，
     * 不承担识别 —— 图形身份全靠那圈线。线一糊，整个图标在暗色下就是一团黑（正是 P3
     * 「暗色帧黑底黑标」要防的那个失败模式）。
     */
    @Test
    void coloredIconEdgesStandOutAgainstTheBackground() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        for (String theme : new String[]{"light", "dark"}) {
            double ratio = contrast(tokens, "icon-edge", "bg-canvas", "bg-canvas", theme);
            assertThat(ratio)
                    .as("%s 主题：彩色图标轮廓与页面底的对比度必须 ≥ 3:1", theme)
                    .isGreaterThanOrEqualTo(3.0);
        }
    }

    /**
     * ⭐ R43-a：厂商<b>品牌色</b>同样是非文字元素，两档主题都得与页面底拉开 ≥3:1。
     *
     * <p>为什么单开一条：品牌色是从各家官色<b>抄来的值</b>（MySQL 青、Oracle 红……），
     * 抄的时候很容易照抄「亮色档那个深度」—— 于是暗色档压在黑底上等于隐形。
     * {@code -adb-vendor-*} 的每一档都要自己过一遍这个算式，不能靠轮廓层替它担保：
     * 品牌色走的是<b>填充层</b>，填充面积正是用户读「这是哪家库」的那一块。
     */
    @Test
    void vendorBrandColorsStandOutAgainstTheBackground() throws IOException {
        JsonNode tokens = new ObjectMapper()
                .readTree(readResource("/tokens/tokens.json")).path("tokens");
        List<String> checked = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        tokens.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!key.startsWith("vendor-")) {
                return;
            }
            checked.add(key);
            for (String theme : new String[]{"light", "dark"}) {
                double ratio = contrast(tokens, key, "bg-canvas", "bg-canvas", theme);
                if (ratio < 3.0) {
                    failures.add(String.format("%s 主题：%s 与页面底只差 %.2f:1（要求 ≥ 3:1）",
                            theme, key, ratio));
                }
            }
        });
        assertThat(checked)
                .as("一条品牌色都没扫到 —— 令牌前缀约定改了，这份审计是假绿")
                .isNotEmpty();
        assertThat(failures)
                .as("厂商品牌色在两档主题下都得看得出来：\n  %s", String.join("\n  ", failures))
                .isEmpty();
    }

    // ─────────────────────────── 颜色计算 ───────────────────────────

    /** 对比度：前景文字 vs 背景（背景先合成到 base 上）。 */
    private static double contrast(JsonNode tokens, String fg, String bg, String base, String theme) {
        double[] baseRgb = rgb(tokens, base, theme);
        double[] bgRgb = composite(rgb(tokens, bg, theme), baseRgb);
        double[] fgRgb = composite(rgb(tokens, fg, theme), bgRgb);
        double a = relativeLuminance(fgRgb);
        double b = relativeLuminance(bgRgb);
        double lighter = Math.max(a, b);
        double darker = Math.min(a, b);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** 解析颜色令牌 → {r, g, b, a}（0..1）。 */
    private static double[] rgb(JsonNode tokens, String key, String theme) {
        String value = tokens.path(key).path(theme).asText("").trim();
        if (value.isEmpty()) {
            return new double[]{0, 0, 0, 1};
        }
        if (value.startsWith("rgba")) {
            String body = value.substring(value.indexOf('(') + 1, value.lastIndexOf(')'));
            String[] parts = body.split(",");
            return new double[]{
                    parse(parts[0]), parse(parts[1]), parse(parts[2]),
                    parts.length > 3 ? parse(parts[3]) : 1};
        }
        int hex = parseHex(value);
        return new double[]{
                ((hex >> 16) & 0xFF) / 255.0,
                ((hex >> 8) & 0xFF) / 255.0,
                (hex & 0xFF) / 255.0,
                1};
    }

    /** 半透明色叠在底色上。 */
    private static double[] composite(double[] fg, double[] bg) {
        double alpha = fg[3];
        return new double[]{
                fg[0] * alpha + bg[0] * (1 - alpha),
                fg[1] * alpha + bg[1] * (1 - alpha),
                fg[2] * alpha + bg[2] * (1 - alpha),
                1};
    }

    private static double parse(String value) {
        String text = value.trim();
        if (text.endsWith("%")) {
            return Double.parseDouble(text.substring(0, text.length() - 1)) / 100.0;
        }
        return Double.parseDouble(text);
    }

    private static int parseHex(String hex) {
        String value = hex.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() == 3) {
            value = value.charAt(0) + "" + value.charAt(0)
                    + value.charAt(1) + "" + value.charAt(1)
                    + value.charAt(2) + "" + value.charAt(2);
        }
        return Integer.parseInt(value, 16);
    }

    /** WCAG 相对亮度。 */
    private static double relativeLuminance(double[] rgb) {
        return 0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2]);
    }

    private static double channel(double value) {
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = ContrastTest.class.getResourceAsStream(path)) {
            assertThat(stream).as("缺少资源 %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
