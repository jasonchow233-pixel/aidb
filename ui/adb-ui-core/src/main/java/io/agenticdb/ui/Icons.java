package io.agenticdb.ui;

import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * ⭐ 一套统一的矢量图标。
 *
 * <h2>为什么不用 Unicode 符号</h2>
 * 以前界面上的图标是 {@code ✕ ▽ ⇤ ⇥ ★ ◈ ↻ ☰ ↶ ↷ ＋ ‹ › ▶ ▦ ⌨ ⇅ ◆} 这些字符。
 * 三个问题：
 * <ol>
 *   <li><b>字形随系统字体变</b> —— 同一个 {@code ⇤} 在 macOS 和 Windows 上完全不是一个样，
 *       而且有的机器上根本没有这个字（变成方框）。</li>
 *   <li><b>符号与中文词混排</b> —— 一排按钮里既有 {@code ↻ 刷新} 又有 {@code 复制}，
 *       宽度忽宽忽窄，视觉重量完全不同。</li>
 *   <li><b>同形不同义</b> —— {@code ⚙} 曾经既表示「结构」又表示「设置」。</li>
 * </ol>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>视框统一 **16×16**，描边 **1.5px**，圆头圆角；</li>
 *   <li><b>全部是描边图标</b>（不填充）—— 统一风格，也便于随主题换色；</li>
 *   <li>颜色由 CSS 的 {@code .adb-icon} 类接管（{@code -adb-text-secondary}），
 *       所以切换主题时图标自动跟着变；</li>
 *   <li>路径数据直接写在 16×16 坐标系里；要更大就整体缩放，
 *       描边宽度会等比放大，所以这里按比例**反向补偿**。</li>
 * </ul>
 */
public final class Icons {

    /** 视框边长。 */
    public static final double VIEW_BOX = 16;

    /** 视觉描边宽度（图标缩放后仍然保持这个视觉粗细）。 */
    public static final double STROKE = 1.5;

    // ── 尺寸档位（V-7：以前各处随手写 8/12/14/16/32/40，视觉重量对不齐）──

    /** 状态点（实心小圆点）：8px。 */
    public static final double SIZE_DOT = 8;

    /** 小徽标（收藏星、关闭钮）：12px。 */
    public static final double SIZE_BADGE = 12;

    /** 标签栏 / 树节点 / 表格行首的图标：14px。 */
    public static final double SIZE_LABEL = 14;

    /** 工具栏 / 功能栏的图标：16px（= 视框原尺寸）。 */
    public static final double SIZE_TOOLBAR = 16;

    /** 页面主工具条（对象级动作）：20px —— 主次靠尺寸拉开。 */
    public static final double SIZE_PRIMARY = 20;

    /** 空状态的大图标：32px。 */
    public static final double SIZE_EMPTY = 32;

    private Icons() {
    }

    /**
     * 图标集。
     *
     * <p>每条路径都在 16×16 视框里；多个子路径用 {@code M} 分隔。
     */
    public enum Icon {

        // ── 通用动作 ──
        /** 关闭（×）。 */
        CLOSE("M4 4 L12 12 M12 4 L4 12"),
        /** 新增（+）。 */
        ADD("M8 3 V13 M3 8 H13"),
        /** 编辑（铅笔）。 */
        EDIT("M3 13 L4 9.5 L11.5 2 L14 4.5 L6.5 12 Z"),
        /** 复制（两张叠放的纸）。 */
        COPY("M6 6 H13 V14 H6 Z M3 10 V3 H10"),
        /** 删除（垃圾桶）。 */
        DELETE("M3.5 4.5 H12.5 M6 4.5 V2.5 H10 V4.5 M4.5 4.5 L5.5 13.5 H10.5 L11.5 4.5"
                + " M6.8 7 V11 M9.2 7 V11"),
        /** 保存（软盘）。 */
        SAVE("M2.5 2.5 H11 L13.5 5 V13.5 H2.5 Z M5 2.5 V6.5 H11 V2.5 M5 13.5 V9 H11 V13.5"),
        /** 刷新（环形箭头）。 */
        REFRESH("M8 3 A5 5 0 1 1 3.6 6.2 M3.6 6.2 L3.6 3.2 M3.6 6.2 L6.6 6.2"),
        /** 撤销（向左的箭头）。 */
        UNDO("M6.5 4 L3.5 7 L6.5 10 M3.5 7 H10 A3.5 3.5 0 0 1 10 14 H7"),
        /** 重做（向右的箭头）。 */
        REDO("M9.5 4 L12.5 7 L9.5 10 M12.5 7 H6 A3.5 3.5 0 0 0 6 14 H9"),
        /** 搜索（放大镜）。 */
        SEARCH("M7 2.5 A4.5 4.5 0 1 0 7 11.5 A4.5 4.5 0 1 0 7 2.5 M10.5 10.5 L13.5 13.5"),
        /** 筛选（漏斗）。 */
        FILTER("M2.5 3 H13.5 L9.5 8 V13 L6.5 11.5 V8 Z"),
        /** 清除（圆圈加斜杠，⊘）。 */
        CLEAR("M8 2.6 A5.4 5.4 0 1 0 8 13.4 A5.4 5.4 0 1 0 8 2.6 M4.6 4.6 L11.4 11.4"),
        /** 设置（齿轮）。 */
        SETTINGS("M8 5.5 A2.5 2.5 0 1 0 8 10.5 A2.5 2.5 0 1 0 8 5.5"
                + " M8 1.5 V3.5 M8 12.5 V14.5 M1.5 8 H3.5 M12.5 8 H14.5"
                + " M3.4 3.4 L4.8 4.8 M11.2 11.2 L12.6 12.6"
                + " M12.6 3.4 L11.2 4.8 M4.8 11.2 L3.4 12.6"),
        /** 收藏（星，空心）。 */
        STAR("M8 2 L9.9 6.1 L14.3 6.6 L11 9.4 L12.1 13.7 L8 11.4 L3.9 13.7"
                + " L5 9.4 L1.7 6.6 L6.1 6.1 Z"),
        /** 已收藏（星，实心）。 */
        STAR_FILLED("M8 2 L9.9 6.1 L14.3 6.6 L11 9.4 L12.1 13.7 L8 11.4 L3.9 13.7"
                + " L5 9.4 L1.7 6.6 L6.1 6.1 Z", true),
        /** 状态点（实心圆点）。 */
        DOT("M8 5.6 A2.4 2.4 0 1 0 8 10.4 A2.4 2.4 0 1 0 8 5.6", true),
        /**
         * 更多（⋯，三个实心点）。
         *
         * <p>溢出菜单的入口图标。与其它描边图标不同 —— 三个点必须是**实心**的，
         * 描边的圆圈在 16px 下会糊成一团。
         */
        MORE("M3.4 8 A0.85 0.85 0 1 0 3.4 9.7 A0.85 0.85 0 1 0 3.4 8"
                + " M8 8 A0.85 0.85 0 1 0 8 9.7 A0.85 0.85 0 1 0 8 8"
                + " M12.6 8 A0.85 0.85 0 1 0 12.6 9.7 A0.85 0.85 0 1 0 12.6 8", true),

        // ── 导航 ──
        /** 上一页（单左箭头）。 */
        PREVIOUS("M10 3 L5 8 L10 13"),
        /** 下一页（单右箭头）。 */
        NEXT("M6 3 L11 8 L6 13"),
        /** 首页（|◀）。 */
        FIRST("M3.5 3 V13 M12 3.5 L5.5 8 L12 12.5 Z"),
        /** 末页（▶|）。 */
        LAST("M12.5 3 V13 M4 3.5 L10.5 8 L4 12.5 Z"),
        /** 双左箭头（侧栏折叠）。 */
        CHEVRON_LEFT("M6 4 L2 8 L6 12 M12 4 L8 8 L12 12"),
        /** 双右箭头（侧栏展开）。 */
        CHEVRON_RIGHT("M4 4 L8 8 L4 12 M10 4 L14 8 L10 12"),

        // ── 数据传输 ──
        /** 导出（箭头向下进托盘）。 */
        EXPORT("M8 2.5 V10 M5 7.5 L8 10.5 L11 7.5 M3 13 H13"),
        /** 导入（箭头向上出托盘）。 */
        IMPORT("M8 10.5 V3 M5 6 L8 3 L11 6 M3 13 H13"),
        /** 双向（导入导出）。 */
        TRANSFER("M3 6 H11 M8 3 L11 6 L8 9 M13 10 H5 M8 7 L5 10 L8 13"),

        // ── 执行 ──
        /** 执行（播放三角）。 */
        RUN("M5 3 L13 8 L5 13 Z"),
        /** 全部执行（双三角）。 */
        RUN_ALL("M2.5 3 L9 8 L2.5 13 Z M8 3 L14.5 8 L8 13 Z"),
        /** 停止（方块）。 */
        STOP("M4.5 4.5 H11.5 V11.5 H4.5 Z"),
        /** 执行计划（分支）。 */
        EXPLAIN("M3.5 3.5 H7 V9 H3.5 Z M9 7.5 H12.5 V13.5 H9 Z M7 6 H9 M7 9 H9"),

        // ── 对象模型 ──
        /** 数据库（圆柱）。 */
        DATABASE("M3 4.5 C3 3.1 5.1 2 8 2 C10.9 2 13 3.1 13 4.5"
                + " C13 5.9 10.9 7 8 7 C5.1 7 3 5.9 3 4.5 Z"
                + " M3 4.5 V11.5 C3 12.9 5.1 14 8 14 C10.9 14 13 12.9 13 11.5 V4.5"
                + " M3 8 C3 9.4 5.1 10.5 8 10.5 C10.9 10.5 13 9.4 13 8"),
        /** 表（网格）。 */
        TABLE("M2.5 3.5 H13.5 V12.5 H2.5 Z M2.5 6.5 H13.5 M2.5 9.5 H13.5"
                + " M6.5 3.5 V12.5 M9.5 3.5 V12.5"),
        /** 结构（层级树 —— 与「列」区分开：那个是竖向分栏，这个是父子结构）。 */
        STRUCTURE("M8 2.5 V6.5 M8 6.5 H4 V9.5 M8 6.5 H12 V9.5"
                + " M2 9.5 H6 V13 H2 Z M10 9.5 H14 V13 H10 Z"),
        /** 列（列设置）。 */
        COLUMNS("M2.5 3.5 H13.5 V12.5 H2.5 Z M6 3.5 V12.5 M10 3.5 V12.5"),
        /** 视图（眼睛）。 */
        VIEW("M1.5 8 C3.5 4.8 5.6 3.5 8 3.5 C10.4 3.5 12.5 4.8 14.5 8"
                + " C12.5 11.2 10.4 12.5 8 12.5 C5.6 12.5 3.5 11.2 1.5 8 Z"
                + " M8 6 A2 2 0 1 0 8 10 A2 2 0 1 0 8 6"),
        /** 视图对象（菱形）。 */
        VIEW_OBJECT("M8 2 L14 8 L8 14 L2 8 Z"),
        /** 过程 / 函数（大括号）。 */
        FUNCTION("M6.5 2.5 C5 2.5 5 5 5 6.5 C5 8 4 8 3 8 C4 8 5 8 5 9.5"
                + " C5 11 5 13.5 6.5 13.5"
                + " M9.5 2.5 C11 2.5 11 5 11 6.5 C11 8 12 8 13 8 C12 8 11 8 11 9.5"
                + " C11 11 11 13.5 9.5 13.5"),
        /** 触发器（闪电）。 */
        TRIGGER("M9.5 2 L4 9 H7.5 L6.5 14 L12 7 H8.5 Z"),
        /** 连接（插头）。 */
        CONNECT("M6.5 2.5 V6 M9.5 2.5 V6 M4.5 6 H11.5 V8"
                + " A3.5 3.5 0 0 1 8 11.5 A3.5 3.5 0 0 1 4.5 8 Z M8 11.5 V14"),
        /** 查询（代码括号）。 */
        QUERY("M6 4 L2.5 8 L6 12 M10 4 L13.5 8 L10 12"),
        /** 列表视图（三行，行首带圆点）。 */
        LIST("M2.5 4 H4.5 M6.5 4 H13.5 M2.5 8 H4.5 M6.5 8 H13.5"
                + " M2.5 12 H4.5 M6.5 12 H13.5"),
        /** 网格视图（四宫格）。 */
        GRID("M2.5 2.5 H7 V7 H2.5 Z M9 2.5 H13.5 V7 H9 Z"
                + " M2.5 9 H7 V13.5 H2.5 Z M9 9 H13.5 V13.5 H9 Z"),

        // ── 工具 ──
        /** 主题（半圆）。 */
        THEME("M8 2.5 A5.5 5.5 0 1 0 8 13.5 A5.5 5.5 0 1 0 8 2.5 M8 2.5 V13.5"),
        /** 命令面板（终端提示符）。 */
        PALETTE("M2.5 3.5 H13.5 V12.5 H2.5 Z M5 6.5 L7 8 L5 9.5 M9 10 H11"),
        /** 历史（时钟）。 */
        HISTORY("M8 2.5 A5.5 5.5 0 1 0 8 13.5 A5.5 5.5 0 1 0 8 2.5 M8 5 V8 L10.2 9.6"),
        /** 诊断（心电波）。 */
        DIAGNOSTICS("M1.5 8 H4.5 L6 4 L9 12 L10.5 8 H14.5"),
        /**
         * 索引（钥匙）。
         *
         * <p>索引是「加速查找的钥匙」—— 结构页「索引」页签用它做行首图标，
         * 颜色走 {@code -adb-kind-index}（索引类型色），与表 / 视图 / 触发器的
         * 类型色同一套语义。
         */
        INDEX("M4.8 3.2 A2.4 2.4 0 1 0 4.8 8 A2.4 2.4 0 1 0 4.8 3.2"
                + " M6.5 7.3 L12.4 13.2 M9.3 10.1 L10.6 8.8 M10.8 11.6 L12.1 10.3"),
        /**
         * 品牌标识（数据圆柱 + 右上角 AI 星芒）。
         *
         * <p>应用栏 26px 方块里原来用的是通用 {@link #DATABASE} —— 所有数据库工具
         * 都画同一个圆柱，品牌等于没有。这里把圆柱收窄到左下、右上补一颗四角星芒，
         * 读起来是「数据 + AI」而不是「又一个 DB 客户端」。
         *
         * <p>⚠️ 必须是**矢量**：{@code AppBarTest.theBrandMarkUsesTheVectorIconSystem}
         * 明确禁止位图（PNG 在暗色主题里不会换色，缩放也糊）。
         */
        BRAND("M2.6 5.6 C2.6 4.4 4.4 3.5 6.5 3.5 C8.6 3.5 10.4 4.4 10.4 5.6"
                + " C10.4 6.8 8.6 7.7 6.5 7.7 C4.4 7.7 2.6 6.8 2.6 5.6 Z"
                + " M2.6 5.6 V11 C2.6 12.2 4.4 13.1 6.5 13.1 C8.6 13.1 10.4 12.2 10.4 11 V5.6"
                + " M2.6 8.3 C2.6 9.5 4.4 10.4 6.5 10.4 C8.6 10.4 10.4 9.5 10.4 8.3"
                + " M12.7 1.9 L13.3 3.6 L15 4.2 L13.3 4.8 L12.7 6.5 L12.1 4.8 L10.4 4.2"
                + " L12.1 3.6 Z"),
        ;

        private final String path;
        private final boolean filled;

        Icon(String path) {
            this(path, false);
        }

        Icon(String path, boolean filled) {
            this.path = path;
            this.filled = filled;
        }

        /** 路径数据（16×16 视框）。 */
        public String path() {
            return path;
        }

        /** 是��填充图标（实心）？ */
        public boolean filled() {
            return filled;
        }
    }

    /** 标准尺寸的图标。 */
    public static SVGPath of(Icon icon) {
        return of(icon, SIZE_TOOLBAR);
    }

    /**
     * 造一个图标。
     *
     * @param size 目标边长（像素）
     */
    public static SVGPath of(Icon icon, double size) {
        double scale = size / VIEW_BOX;
        SVGPath path = new SVGPath();
        path.setContent(icon.path());
        // 颜色交给 CSS（.adb-icon / .adb-icon--filled），这里给个不会消失的兜底。
        path.setFill(icon.filled() ? Color.BLACK : null);
        path.setStroke(icon.filled() ? null : Color.BLACK);
        // ⭐ 缩放会等比放大描边，所以这里反向补偿，视觉粗细始终是 1.5px。
        path.setStrokeWidth(STROKE / scale);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        path.setScaleX(scale);
        path.setScaleY(scale);
        path.getStyleClass().add(icon.filled() ? "adb-icon--filled" : "adb-icon");
        return path;
    }
}
