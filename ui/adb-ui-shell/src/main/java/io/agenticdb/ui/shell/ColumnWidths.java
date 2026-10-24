package io.agenticdb.ui.shell;

/**
 * 列宽计算：表头「读得出来」的唯一来源。
 *
 * <p>背景（第十九轮 S-6 / 截图 C3）：右侧「对象详情」展开时会把主工作区挤窄。
 * 旧实现用 {@code CONSTRAINED_RESIZE_POLICY} 把所有列等比例压扁，且数据列的最小
 * 宽度只有 72px —— 于是 {@code name / email / active / score} 被截成
 * {@code na… / em… / acti… / sco…}。列头里除了列名还有类型标签、排序徽标和
 * 筛选漏斗，72px 连其中任何一项都放不下。
 *
 * <p>这里的数值不是「像素级精确排版」，而是给列宽一个**可读下限**：
 * 西文按 7.2px/字符、CJK 按 2 个西文单位计，再加上列头的固定装饰占位。
 * 实际字体度量由 JavaFX 决定，但下限必须保证常见列名（{@code created_at}、
 * {@code 创建时间}）完整显示；超过 {@link #DATA_MAX_MIN} 的长列名允许省略，
 * 用户仍可拖宽或看 Tooltip。
 */
final class ColumnWidths {

    /** 数据网格里任一数据列的最小宽度。低于它，表头只剩省略号。 */
    static final double DATA_MIN = 96;

    /** 最小宽度的上限：再长的列名也不允许一列吃掉半个屏幕。 */
    static final double DATA_MAX_MIN = 260;

    /** 13px 粗体西文的平均字宽；CJK 按 2 个单位计。 */
    static final double CHAR_UNIT = 7.2;

    /** 11px 类型标签的平均字宽。 */
    static final double TYPE_CHAR_UNIT = 6.4;

    /** 列头左右内边距 + 排序徽标 + 筛选漏斗 + 间距的固定占位。 */
    static final double HEADER_CHROME = 60;

    /** 清单页普通文本列的表头内边距。 */
    static final double LIST_CHROME = 28;

    private ColumnWidths() {
    }

    /**
     * 文本的视觉长度：CJK（含全角标点）算 2 个西文单位。
     *
     * <p>不能直接用 {@link String#length()}：{@code "创建时间"} 是 4 个 char，
     * 但视觉宽度约等于 8 个西文字符。用 char 数估宽会把中文列头压掉一半。
     */
    static int visualLength(String text) {
        if (text == null) {
            return 0;
        }
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return units;
    }

    /**
     * 数据网格某列的最小宽度。
     *
     * @param headerName 列头第一行（列名，可能带 {@code [PK]} / {@code •}）
     * @param headerType 列头第二行（类型标签，可能带 {@code · 自增}）
     */
    static double dataColumnMin(String headerName, String headerType) {
        double nameWidth = visualLength(headerName) * CHAR_UNIT;
        double typeWidth = visualLength(headerType) * TYPE_CHAR_UNIT;
        return clamp(Math.max(DATA_MIN, Math.max(nameWidth, typeWidth) + HEADER_CHROME),
                DATA_MIN, DATA_MAX_MIN);
    }

    /**
     * 清单页文本列的最小宽度：至少放得下列头，且不低于调用方给的内容下限。
     *
     * @param title 列头
     * @param floor 由列内容决定的绝对下限（例如日期列要放得下 19 个字符）
     */
    static double listColumnMin(String title, double floor) {
        return Math.max(floor, visualLength(title) * CHAR_UNIT + LIST_CHROME);
    }

    /** 按视觉长度估算内容宽度，并夹在 [{@code floor}, {@code ceiling}] 内。 */
    static double contentWidth(int units, double padding, double floor, double ceiling) {
        return Math.max(floor, Math.min(ceiling, units * CHAR_UNIT + padding));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
