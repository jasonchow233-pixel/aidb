package io.agenticdb.ui.shell;

/**
 * ⭐ V-11：弹窗宽度**四档**（原为三档，第十三轮加 {@link #TABLE}；
 * UI 重构任务书 U21 删掉 {@code PICKER=800} —— 见下）。
 *
 * <p>以前每个弹窗的宽度都是随手写的（有的没写 → 由内容撑开），
 * 于是同一种弹窗在不同页面宽度不一样，窗口会「跳」。
 *
 * <p>四档：
 * <ul>
 *   <li>{@link #FORM} —— 简单表单（条件 / 变量 / 表注释）；</li>
 *   <li>{@link #LONG_TEXT} —— 有多行文本（触发器体 / DDL 预览）；</li>
 *   <li>{@link #WIDE} —— 有表格或列选择器（导入导出向导 / 外键 / 新建索引）；</li>
 *   <li>{@link #TABLE} —— <b>建表</b>：字段网格 + 表信息 + SQL 预览三段同屏，
 *       720px 下「默认值」「注释」两列会被压到只能看见一个省略号，
 *       用户改一个默认值得先横向滚（实测）。</li>
 * </ul>
 *
 * <p>{@code PICKER=800} 曾经是为「可用列 + 索引列并排 + 中间四颗按钮」的双列传输框开的：
 * 480 档下每个列表只剩不到 150px，列名被截断。U21 把新建索引改成单列表之后，
 * 那个宽度前提没了 —— 一档只服务一颗弹窗、且理由已经过时的宽度就是死规格，故删。
 */
final class DialogSizes {

    /** 表单档。 */
    static final double FORM = 480;

    /** 长文本档。 */
    static final double LONG_TEXT = 560;

    /** 宽表格档（表格 / 列选择 + SQL 预览）。 */
    static final double WIDE = 720;

    /** 建表档（字段网格 + 表信息 + SQL 预览）。 */
    static final double TABLE = 900;

    private DialogSizes() {
    }
}
