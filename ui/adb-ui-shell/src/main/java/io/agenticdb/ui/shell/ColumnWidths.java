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

    /**
     * 12px 常规体（结构页与数据网格的<b>单元格文本</b>）的平均字宽。
     *
     * <p>和 {@link #CHAR_UNIT} 分开是量出来的，不是拍的：{@code app.css} 给
     * {@code .adb-structure__table .table-cell} 写的是 12px 常规体，而 {@link #CHAR_UNIT} 是
     * 13px 粗体表头那一档。实测「Unique constraint」= 17 个视觉单位 = 88.7px（约 5.2px/单位），
     * 按 7.2 估会得出 138px —— 比真需要的多塞 30px 空白。这里取 5.6，留约 8% 余量。
     */
    static final double CELL_CHAR_UNIT = 5.6;

    /** 单元格左右内边距（{@code app.css}：{@code .adb-structure__table .table-cell} 是 2px 8px）。 */
    static final double CELL_PADDING = 16;

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
     * @param headerName 列头第一行（只有列名）
     * @param headerType 列头第二行（N19 之后只有类型，如 {@code # 11} / {@code text}）
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

    /**
     * 单元格文本列的可读下限：按<b>这一列会渲出来的最长那条文本</b>估，走 12px 常规体那一档字宽。
     *
     * <p>与 {@link #contentWidth} 的差别只在字宽档：那一条服务表头（13px 粗体），这一条服务
     * 单元格正文。D-99 立案时设计器索引表的 Origin 列只有 85px，而「Unique constraint」
     * 需要 88.7px 字宽 + 16px 内边距 —— 用 7.2 那档估会给出 138px，白占半列。
     */
    static double cellContentWidth(int units, double floor, double ceiling) {
        return Math.max(floor, Math.min(ceiling, units * CELL_CHAR_UNIT + CELL_PADDING));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
