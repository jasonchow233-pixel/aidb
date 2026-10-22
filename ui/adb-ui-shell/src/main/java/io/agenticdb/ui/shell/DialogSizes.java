package io.agenticdb.ui.shell;

/**
 * ⭐ V-11：弹窗宽度**三档**。
 *
 * <p>以前每个弹窗的宽度都是随手写的（有的没写 → 由内容撑开），
 * 于是同一种弹窗在不同页面宽度不一样，窗口会「跳」。
 *
 * <p>三档：
 * <ul>
 *   <li>{@link #FORM} —— 简单表单（新建索引 / 条件 / 变量 / 表注释）；</li>
 *   <li>{@link #LONG_TEXT} —— 有多行文本（触发器体 / DDL 预览）；</li>
 *   <li>{@link #WIDE} —— 有表格或列选择器（新建表 / 导入导出向导 / 外键）。</li>
 * </ul>
 */
final class DialogSizes {

    /** 表单档。 */
    static final double FORM = 480;

    /** 长文本档。 */
    static final double LONG_TEXT = 560;

    /** 宽表格档。 */
    static final double WIDE = 720;

    private DialogSizes() {
    }
}
