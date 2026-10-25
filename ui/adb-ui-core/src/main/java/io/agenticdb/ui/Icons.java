package io.agenticdb.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * ⭐ 一套统一的矢量图标 —— 形状来自 <b>Lucide 官方图标集</b>。
 *
 * <h2>来源与形态</h2>
 * <ul>
 *   <li>源文件：{@code resources/io/agenticdb/ui/icons/lucide/*.svg}
 *       （lucide-static v1.61.0，ISC 许可，许可证随目录内 ISC-LICENSE.txt 分发）；</li>
 *   <li>Lucide 是市面最流行的开源线性图标集（Font Awesome 之后的社区事实标准，
 *       VS Code / Cursor / Codex 等开发者工具的主流选择），24 视框、2px 描边、圆头圆角；</li>
 *   <li>运行时由 {@link LucideConverter} 把 SVG（含 {@code circle}/{@code polygon}/弧命令）
 *       展开成 JavaFX {@code SVGPath} 支持的绝对 {@code M L C Z}，缩放到 16 视框并缓存；
 *       升级图标 = 换 SVG 文件，零代码改动。</li>
 * </ul>
 *
 * <h2>为什么不用 Unicode 符号 / emoji</h2>
 * <ol>
 *   <li><b>字形随系统字体变</b> —— 同一个 {@code ⇤} 在 macOS 和 Windows 上完全不是一个样，
 *       而且有的机器上根本没有这个字（变成方框）。</li>
 *   <li><b>符号与中文词混排</b> —— 宽度忽宽忽窄，视觉重量完全不同。</li>
 *   <li><b>同形不同义</b> —— {@code ⚙} 曾经既表示「结构」又表示「设置」。</li>
 * </ol>
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>视框统一 <b>16×16</b>，描边视觉粗细统一 <b>1.5px</b>（Lucide 原生 2px 在 16px
 *       下偏重，统一降到 1.5 保持全套一致）；圆头圆角；</li>
 *   <li>动作类图标<b>全部单色线性</b>，颜色由 CSS 的 {@code .adb-icon} 类接管，
 *       切主题自动跟色；<b>对象类型与连接状态</b>才允许语义彩色（低饱和，
 *       {@code -adb-kind-*} / 状态令牌），不许把普通按钮染色；</li>
 *   <li>例外：{@link #BRAND}（品牌 mark，Lucide 没有等价物）、{@link #DOT}
 *       （实心状态点，Lucide 只有描边的 circle-dot）为自绘路径。</li>
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

    /** Lucide SVG 资源目录（相对 classpath 根）。 */
    static final String LUCIDE_DIR = "/io/agenticdb/ui/icons/lucide/";

    /** 转换结果缓存：每个图标只在首次使用时解析一次 SVG。 */
    private static final ConcurrentHashMap<String, String> PATH_CACHE = new ConcurrentHashMap<>();

    private Icons() {
    }

    /**
     * 图标集。
     *
     * <p>第二个参数是 Lucide 文件名（不含扩展名）；{@code null} 表示自绘路径
     * （路径字面量直接写在构造器第一个参数里）。
     */
    public enum Icon {

        // ── 通用动作 ──
        /** 关闭（lucide: ×）。 */
        CLOSE(null, "x"),
        /** 新增（lucide: plus）。 */
        ADD(null, "plus"),
        /** 编辑（lucide: pencil）。 */
        EDIT(null, "pencil"),
        /** 复制（lucide: copy）。 */
        COPY(null, "copy"),
        /** 删除（lucide: trash-2）。 */
        DELETE(null, "trash-2"),
        /** 保存（lucide: save）。 */
        SAVE(null, "save"),
        /** 刷新（lucide: refresh-cw）。 */
        REFRESH(null, "refresh-cw"),
        /** 撤销（lucide: undo-2）。 */
        UNDO(null, "undo-2"),
        /** 重做（lucide: redo-2）。 */
        REDO(null, "redo-2"),
        /** 搜索（lucide: search）。 */
        SEARCH(null, "search"),
        /** 筛选（lucide: funnel）。 */
        FILTER(null, "funnel"),
        /** 清除（lucide: circle-slash）。 */
        CLEAR(null, "circle-slash"),
        /** 设置（lucide: settings）。 */
        SETTINGS(null, "settings"),
        /** 收藏（lucide: star，空心）。 */
        STAR(null, "star"),
        /** 已收藏（lucide: star 的填充变体）。 */
        STAR_FILLED(null, "star-filled", true),
        /**
         * 状态点（实心小圆，自绘）。
         *
         * <p>连接存活点 8–12px 档专用；Lucide 的 circle-dot 在这个尺寸下
         * 细外环不可读，实心圆点在任何尺寸都成立。
         */
        DOT("M8 5.6 C9.33 5.6 10.4 6.67 10.4 8 C10.4 9.33 9.33 10.4 8 10.4"
                + " C6.67 10.4 5.6 9.33 5.6 8 C5.6 6.67 6.67 5.6 8 5.6 Z", null, true),
        /**
         * 更多（⋯，lucide: more-horizontal，实心三点）。
         *
         * <p>与其它描边图标不同 —— 三个点必须是**实心**的，
         * 描边的圆圈在 16px 下会糊成一团。
         */
        MORE(null, "more-horizontal", true),

        // ── 导航 ──
        /** 上一页（lucide: chevron-left）。 */
        PREVIOUS(null, "chevron-left"),
        /** 下一页（lucide: chevron-right）。 */
        NEXT(null, "chevron-right"),
        /** 首页（lucide: skip-back）。 */
        FIRST(null, "skip-back"),
        /** 末页（lucide: skip-forward）。 */
        LAST(null, "skip-forward"),
        /** 双左箭头（侧栏折叠，lucide: chevrons-left）。 */
        CHEVRON_LEFT(null, "chevrons-left"),
        /** 双右箭头（侧栏展开，lucide: chevrons-right）。 */
        CHEVRON_RIGHT(null, "chevrons-right"),

        // ── 数据传输 ──
        /** 导出（lucide: download）。 */
        EXPORT(null, "download"),
        /** 导入（lucide: upload）。 */
        IMPORT(null, "upload"),
        /** 双向（导入导出，lucide: arrow-left-right）。 */
        TRANSFER(null, "arrow-left-right"),

        // ── 执行 ──
        /** 执行（lucide: play，实心三角 —— 播放键语义就是填充）。 */
        RUN(null, "play", true),
        /** 全部执行（lucide: play-square）。 */
        RUN_ALL(null, "play-square"),
        /** 停止（lucide: square）。 */
        STOP(null, "square"),
        /** 执行计划（lucide: route，节点迁移 = 计划分支）。 */
        EXPLAIN(null, "route"),

        // ── 对象模型（类型色 -adb-kind-* 只上在这些图标上，文字保持正文色）──
        /** 数据库连接（lucide: database）。 */
        DATABASE(null, "database"),
        /** 数据表（lucide: table）。 */
        TABLE(null, "table"),
        /** 结构（lucide: table-2 —— 行列结构而非数据网格）。 */
        STRUCTURE(null, "table-2"),
        /** 列（lucide: grid-2x2 —— 列设置）。 */
        COLUMNS(null, "grid-2x2"),
        /** 视图（lucide: eye）。 */
        VIEW(null, "eye"),
        /** 视图对象（lucide: box）。 */
        VIEW_OBJECT(null, "box"),
        /** 函数（lucide: braces）。 */
        FUNCTION(null, "braces"),
        /** 触发器（lucide: zap）。 */
        TRIGGER(null, "zap"),
        /** 数据库连接（插头，lucide: plug）。 */
        CONNECT(null, "plug"),
        /** 打开（lucide: external-link）。 */
        OPEN(null, "external-link"),
        /** 分组（lucide: folder-open）。 */
        FOLDER(null, "folder-open"),
        /** SQL 编辑器 / 查询（lucide: code）。 */
        QUERY(null, "code"),
        /** 列表视图（lucide: list）。 */
        LIST(null, "list"),
        /** 网格视图（lucide: layout-grid）。 */
        GRID(null, "layout-grid"),

        // ── 工具 ──
        /** 主题切换（lucide: sun-moon）。 */
        THEME(null, "sun-moon"),
        /** 命令面板（lucide: terminal-square）。 */
        PALETTE(null, "terminal-square"),
        /** 历史记录（lucide: history）。 */
        HISTORY(null, "history"),
        /** 诊断（lucide: activity，心电波）。 */
        DIAGNOSTICS(null, "activity"),
        /**
         * 索引（lucide: list-ordered）。
         *
         * <p>复合索引的命脉是<b>有序列</b>，用「有序清单」比钥匙更贴语义；
         * 颜色走 {@code -adb-kind-index}（索引类型色），与表 / 视图 / 触发器的
         * 类型色同一套语义。
         */
        INDEX(null, "list-ordered"),
        /**
         * 品牌标识（数据圆柱 + 右上角 AI 星芒，自绘）。
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
                + " L12.1 3.6 Z", null),
        ;

        private final String literal;
        private final String resourceName;
        private final boolean filled;

        Icon(String literal, String resourceName) {
            this(literal, resourceName, false);
        }

        Icon(String literal, String resourceName, boolean filled) {
            this.literal = literal;
            this.resourceName = resourceName;
            this.filled = filled;
        }

        /**
         * 路径数据（16×16 视框，JavaFX 兼容的绝对 {@code M L C Z}）。
         *
         * <p>Lucide 来源的图标在首次访问时解析资源并缓存。
         */
        public String path() {
            if (literal != null) {
                return literal;
            }
            return PATH_CACHE.computeIfAbsent(resourceName, name -> {
                String svg = readResource(LUCIDE_DIR + name + ".svg");
                return LucideConverter.toPath(svg, VIEW_BOX);
            });
        }

        /** 是填充图标（实心）？ */
        public boolean filled() {
            return filled;
        }

        /**
         * Lucide 源文件名（不含扩展名）；自绘图标为 {@code null}。
         *
         * <p>包内可见 —— 只给 {@code LucideIconTest} 做「资源 ↔ 引用一一对应」审计，
         * 不进公共 API。
         */
        String lucideResource() {
            return resourceName;
        }
    }

    private static String readResource(String path) {
        try (InputStream in = Icons.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺少图标资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
