package io.agenticdb.ui.shell;

/**
 * ⭐ V-11：弹窗宽度**五档**（原为三档，第十三轮加 {@link #TABLE}，
 * UI 重构任务书 §6.1 加 {@link #PICKER}）。
 *
 * <p>以前每个弹窗的宽度都是随手写的（有的没写 → 由内容撑开），
 * 于是同一种弹窗在不同页面宽度不一样，窗口会「跳」。
 *
 * <p>五档：
 * <ul>
 *   <li>{@link #FORM} —— 简单表单（条件 / 变量 / 表注释）；</li>
 *   <li>{@link #LONG_TEXT} —— 有多行文本（触发器体 / DDL 预览）；</li>
 *   <li>{@link #WIDE} —— 有表格或列选择器（导入导出向导 / 外键）；</li>
 *   <li>{@link #PICKER} —— <b>双列选择</b>（新建索引）：可用列 + 已选列两个列表并排，
 *       480 档下每个列表只剩不到 150px，列名被截断；720 档下中间那列按钮
 *       会和列表挤在一起（实测）。800 是让「两个列表 + 中间按钮 + 内边距」
 *       都能直立的最近一档。</li>
 *   <li>{@link #TABLE} —— <b>建表</b>：字段网格 + 表信息 + SQL 预览三段同屏，
 *       720px 下「默认值」「注释」两列会被压到只能看见一个省略号，
 *       用户改一个默认值得先横向滚（实测）。</li>
 * </ul>
 */
final class DialogSizes {

    /** 表单档。 */
    static final double FORM = 480;

    /** 长文本档。 */
    static final double LONG_TEXT = 560;

    /** 宽表格档。 */
    static final double WIDE = 720;

    /** 双列选择档（可用列 / 索引列并排 + SQL 预览）。 */
    static final double PICKER = 800;

    /** 建表档（字段网格 + 表信息 + SQL 预览）。 */
    static final double TABLE = 900;

    private DialogSizes() {
    }
}
