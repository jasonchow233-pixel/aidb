package io.agenticdb.ui;

import java.util.Locale;

/**
 * 彩色图标的**上色角色** —— 资产里不写颜色，只写角色；颜色由 CSS 从设计令牌取。
 *
 * <h2>为什么不让 SVG 自带 fill 色值</h2>
 * <ol>
 *   <li><b>令牌唯一真相</b>：工程约束 1 规定颜色只在 {@code tokens.json} 里定义，
 *       {@code TokenParityTest} 扫 {@code app.css} 出现 {@code #rrggbb} 直接失败。
 *       图标资产若自带十六进制，等于开第三个颜色来源。</li>
 *   <li><b>暗色档自动跟随</b>：角色 → CSS → {@code -adb-*} looked-up color 的链路
 *       天然吃主题切换，<b>不需要为暗色重画一套资产</b>。
 *       两套位图必然漂移，且暗色帧要重拍才能发现漂移。</li>
 * </ol>
 *
 * <p>资产里用 {@code data-paint="tone-a"} 标注每个图形元素；缺省为 {@link #EDGE}。
 */
public enum IconPaint {

    /** 轮廓 / 描边 —— 彩色图标唯一的线色，对应单色图标的 {@code .adb-icon}。 */
    EDGE("adb-icon-edge"),

    /** 最浅的填充面（如文件夹的下层纸、表体的底色）。 */
    TONE_A("adb-icon-tone-a"),

    /** 中间填充面。 */
    TONE_B("adb-icon-tone-b"),

    /** 最深的填充面（与 EDGE 同族但更实）。 */
    TONE_C("adb-icon-tone-c"),

    /** 语义强调点（状态灯、AI 星芒这类「一眼要看懂」的小块）。 */
    ACCENT("adb-icon-accent");

    private final String styleClass;

    IconPaint(String styleClass) {
        this.styleClass = styleClass;
    }

    /** 该角色的 CSS 类名 —— 颜色规则在 {@code app.css} 的彩色图标段。 */
    public String styleClass() {
        return styleClass;
    }

    /**
     * 解析资产里的 {@code data-paint} 取值。
     *
     * @param raw 原始字符串，可为 {@code null}
     * @return 对应角色；未标注回 {@link #EDGE}
     * @throws IllegalStateException 标注了不存在的角色（写错就炸，不静默变灰）
     */
    public static IconPaint fromAsset(String raw) {
        if (raw == null || raw.isBlank()) {
            return EDGE;
        }
        String value = raw.trim();
        for (IconPaint paint : values()) {
            if (paint.styleClass().equals(value)) {
                return paint;
            }
        }
        String key = value.toUpperCase(Locale.ROOT).replace('-', '_');
        for (IconPaint paint : values()) {
            if (paint.name().equals(key)) {
                return paint;
            }
        }
        throw new IllegalStateException("unknown icon paint role: " + raw
                + " (allowed: " + allowedAssetValues() + ")");
    }

    /**
     * 资产里允许出现的取值字面量 —— 给审计门禁做闭集校验。
     *
     * <p>由枚举自身拼出，不给它硬造第二份清单（两份必然漂移）。
     */
    public static String allowedAssetValues() {
        StringBuilder sb = new StringBuilder();
        for (IconPaint paint : values()) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(paint.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        return sb.toString();
    }
}
