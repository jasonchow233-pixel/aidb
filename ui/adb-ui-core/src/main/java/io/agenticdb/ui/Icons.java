package io.agenticdb.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.Group;
import javafx.scene.Node;
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
 *   <li>彩色走 {@link #colored(Icon, double)} + {@code icons/color/*.svg}：资产里<b>只标角色</b>
 *       （{@code data-paint="tone-a"}，见 {@link IconPaint}），颜色一律来自 CSS 的
 *       {@code -adb-*} 令牌 —— 于是暗色档自动跟随，<b>不需要画第二套</b>；
 *       同名彩色资产还不存在时自动回退单色，所以资产可以一批批铺；</li>
 *   <li>厂商商标走 {@link #brand(String, double)} + {@code icons/brand/*.svg}（R63-3）：
 *       这一条通道<b>反过来</b> —— 资产里写死官方十六进制色，不吃角色、不吃主题，
 *       因为商标的色就是识别本身；代价与许可见 {@link #BRAND_DIR}；</li>
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

    /** Ribbon 大图标：26px（2026-10-28 顶区精简后命令带 64px，图标档不变）。 */
    public static final double SIZE_RIBBON = 26;

    /** 空状态的大图标：32px。 */
    public static final double SIZE_EMPTY = 32;

    /**
     * 卡片 / 示意图的大图形：44px（N27 连接类型页的 112×108 磁贴、常规页的
     * `AIDB ⟷ Database` 示意图）。比空状态大一号 —— 磁贴里图标是主角，不是配图。
     */
    public static final double SIZE_TILE = 44;

    /** Lucide SVG 资源目录（相对 classpath 根）。 */
    static final String LUCIDE_DIR = "/io/agenticdb/ui/icons/lucide/";

    /**
     * 彩色变体资源目录（相对 classpath 根）。
     *
     * <p>这里放的是<b>自有资产</b>（同风格的多彩图形，逐元素标 {@code data-paint} 角色），
     * 不是 Lucide —— Lucide 是线性图标集，天生单色。
     * <b>不许</b>把竞品或任何第三方位图/图标资产放进来（他人版权素材）：
     * 借用素材只有 {@code lucide/}（ISC）与 {@link #BRAND_DIR}（厂商商标）两个目录，
     * 各自带许可文件，由 {@code ColoredIconTest}／{@code BrandIconTest} 钉住。
     */
    static final String COLOR_DIR = "/io/agenticdb/ui/icons/color/";

    /**
     * ⭐ 厂商商标目录（R63-3）：六家数据库<b>官方标志</b>的拍平矢量。
     *
     * <p>与 {@link #COLOR_DIR} 的分工是硬性的：彩色目录里的东西吃 CSS 角色，
     * 颜色跟着主题走；商标目录里的东西吃<b>字面官方色</b>，颜色跟着商标法走。
     * 混在一起的结果是「MySQL 的商标被刷成中性蓝」或「主题切换时商标跟着变灰」，
     * 两种都等于没有识别度。
     *
     * <p>这里的每个文件都是第三方商标，随目录分发 {@code TRADEMARK-NOTICE.txt}
     * （来源、许可、我们做了什么改动）。资产格式受 {@code BrandIconTest} 约束：
     * 只有 {@code <path>}、只有字面 {@code #rrggbb} 填充、方形零原点 viewBox、
     * 无 transform／渐变／遮罩 —— 因为 {@link LucideConverter} 那条通道只认这些。
     */
    static final String BRAND_DIR = "/io/agenticdb/ui/icons/brand/";

    /** 转换结果缓存：每个图标只在首次使用时解析一次 SVG。 */
    private static final ConcurrentHashMap<String, String> PATH_CACHE = new ConcurrentHashMap<>();

    /** 彩色分层缓存（键 = 资产名）。 */
    private static final ConcurrentHashMap<String, List<LucideConverter.Layer>> LAYER_CACHE =
            new ConcurrentHashMap<>();

    /** 确认过「没有彩色资产」的名字 —— 避免每次落库都重新查一遍 classpath。 */
    private static final Set<String> ABSENT_COLOR_ASSETS = ConcurrentHashMap.newKeySet();

    /** 品牌分层缓存（键 = 资产名）。 */
    private static final ConcurrentHashMap<String, List<LucideConverter.BrandLayer>>
            BRAND_CACHE = new ConcurrentHashMap<>();

    /** 确认过「没有品牌资产」的名字。 */
    private static final Set<String> ABSENT_BRAND_ASSETS = ConcurrentHashMap.newKeySet();

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
        /** 肯定态勾（lucide: check）—— U9：只读布尔用它，不再用文本「✓」。 */
        CHECK(null, "check"),
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
        /** 上移（lucide: arrow-up）—— 表设计器调整列顺序。 */
        MOVE_UP(null, "arrow-up"),
        /** 下移（lucide: arrow-down）—— 表设计器调整列顺序。 */
        MOVE_DOWN(null, "arrow-down"),

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
        /** 用户 / 权限（lucide: user）。 */
        USER(null, "user"),
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

        // ── 集合（导航树里的分组节点，R62-a H6-g）──────────────────────────
        //   规矩只有一条：**分组字形不许和它孩子的字形是同一枚**。以前「Tables」这行
        //   画的就是表图标，「Views」画的就是视图图标 —— 于是一棵树里父与子长得一样，
        //   层级只能靠缩进读，扫视时「这一行是集合还是对象」要停一下才知道。
        //   这五枚的形状说的都是「一摞 / 一个容器」，类型由同一行的文字和孩子节点交代。
        //   ⚠️ 保持**单色**：分组节点拿不到对象类型（{@code iconStyleClass} 按 kind 分色，
        //   GROUP 只有一档灰），给它上色等于宣告「集合也有一类」，而集合不是对象。
        /** 表的集合（lucide: file-stack，三张错开的表页）。 */
        GROUP_TABLES(null, "file-stack"),
        /** 视图的集合（lucide: gallery-thumbnails，一排预览）。 */
        GROUP_VIEWS(null, "gallery-thumbnails"),
        /** 函数 / 过程的集合（lucide: function-square，方框里的 fx）。 */
        GROUP_FUNCTIONS(null, "function-square"),
        /** 已保存查询的集合（lucide: notebook-tabs，带页签的本子）。 */
        GROUP_QUERIES(null, "notebook-tabs"),
        /** 连接的分组（lucide: folders，两只叠着的文件夹 —— 单只 folder-open 是「一个」）。 */
        GROUP_CONNECTIONS(null, "folders"),

        // ── 工具 ──
        /** 主题切换（lucide: sun-moon）。 */
        THEME(null, "sun-moon"),
        /** 命令面板（lucide: terminal-square）。 */
        PALETTE(null, "terminal-square"),
        /** 历史记录（lucide: history）。 */
        HISTORY(null, "history"),
        /** 凭据安全（lucide: lock）—— U27/§2.8：口令落盘位置这类安全提示配锁形图标。 */
        LOCK(null, "lock"),
        /** 诊断（lucide: activity，心电波）。 */
        DIAGNOSTICS(null, "activity"),
        /**
         * AI 星芒（自绘，16×16）：一颗四角主星 + 右上一颗小星。
         *
         * <p>「New Table with AI」用它 —— Navicat 17 实测那一格也是星芒，
         * 但库里的 Lucide 集没有 sparkles，于是自绘（形状与品牌标右上角那颗同源）。
         */
        AI("M9.5 1.8 L11.2 6.3 L15.7 8 L11.2 9.7 L9.5 14.2"
                + " L7.8 9.7 L3.3 8 L7.8 6.3 Z"
                + " M13.6 11.4 L14.2 13 L15.8 13.6 L14.2 14.2"
                + " L13.6 15.8 L13 14.2 L11.4 13.6 L13 13 Z", null),
        /**
         * 警示（lucide: triangle-alert）。
         *
         * <p>替代查询页里当图标用的文本「⚠」—— 文本符号不吃主题色、不吃字号，
         * 暗色下几乎看不见，还与「内容真是 ⚠ 的文本」撞车（与 {@link #CHECK} 同一口径）。
         */
        ALERT(null, "triangle-alert"),
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
         * <p>应用栏品牌方块 2026-10-28 已随顶区精简删除；这个字形现在出现在
         * 「关于」弹窗的图形位。历史上它替掉的是通用 {@link #DATABASE} ——
         * 所有数据库工具都画同一个圆柱，品牌等于没有。圆柱收窄到左下、右上补一颗
         * 四角星芒，读起来是「数据 + AI」而不是「又一个 DB 客户端」。
         *
         * <p>⚠️ 必须是**矢量**（{@code BrandMarkAuditTest} 守着品牌身份）：
         * 位图 PNG 在暗色主题里不会换色，缩放也糊。
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
        String text = readResourceOrNull(path);
        if (text == null) {
            throw new IllegalStateException("missing icon resource: " + path);
        }
        return text;
    }

    private static String readResourceOrNull(String path) {
        try (InputStream in = Icons.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ─────────────────────────── 彩色通道（对象类型 / 连接状态） ───────────────────────────

    /**
     * 这个图标有没有彩色变体（{@code icons/color/&lt;同名&gt;.svg} 在不在）。
     *
     * <p>给门禁做「资产 ↔ 引用一一对应」审计，也给调用方在「彩色档还没铺完」时判断回退。
     */
    public static boolean hasColoredVariant(Icon icon) {
        return layersFor(icon) != null;
    }

    /**
     * 造一个<b>彩色</b>图标 —— 有彩色资产就分层上色，没有就原样退回 {@link #of(Icon, double)}。
     *
     * <h2>为什么允许回退</h2>
     * 彩色资产是一批一批铺的（导航树 → Ribbon → 工具条 → 连接磁贴）。
     * 回退意味着「还没铺到的位置继续用单色」，而不是显示空白或半套配色 ——
     * 每个中间状态都是一个能看、能过门禁的版本。
     *
     * <h2>返回 {@code Node} 而不是 {@code SVGPath}</h2>
     * 分层就是多个 {@code SVGPath}，必须有个容器兜住；单色回退时给的就是 {@code SVGPath}
     * 本身。调用方按 {@code Node} 用（挂进父容器、加类名、做尺寸）即可，
     * <b>不要</b>再往下转型 —— 转型就把自己锁死在「只能是单色」上了。
     *
     * <p>⚠️ 与单色同样一条限制：返回的 {@code Group}/{@code SVGPath} 不是 {@code Control}，
     * <b>挂不了 {@code Tooltip}</b>，需要提示时挂到外层 {@code Control} 上或用
     * {@code Tooltip.install(node, tip)}。
     *
     * @param size 目标边长（像素）
     */
    public static Node colored(Icon icon, double size) {
        List<LucideConverter.Layer> layers = layersFor(icon);
        if (layers == null) {
            return of(icon, size);
        }
        double scale = size / VIEW_BOX;
        Group group = new Group();
        for (LucideConverter.Layer layer : layers) {
            SVGPath path = new SVGPath();
            path.setContent(layer.path());
            path.getStyleClass().add(layer.paint().styleClass());
            if (layer.paint() == IconPaint.EDGE) {
                // 线层与单色图标同一套粗细口径（缩放反向补偿，视觉恒为 1.5px）。
                path.setStrokeWidth(STROKE / scale);
                path.setStrokeLineCap(StrokeLineCap.ROUND);
                path.setStrokeLineJoin(StrokeLineJoin.ROUND);
            }
            group.getChildren().add(path);
        }
        // ⚠️ 容器（Group）不上类名：它在 JavaFX 里没有可声明的绘制属性，
        // 而 StyleClassCoverageTest 要求「Java 里出现过的 adb-* 类必须在 CSS 有规则」。
        // 门禁要认「这是彩色实例」，查子层的角色类（如 .adb-icon-edge）即可。
        group.setScaleX(scale);
        group.setScaleY(scale);
        return group;
    }

    /** 彩色变体的默认档 —— 与单色同一口径，尺寸只从 {@code Icons.SIZE_*} 来。 */
    public static Node colored(Icon icon) {
        return colored(icon, SIZE_TOOLBAR);
    }

    /** 分层解析（带缓存与「确认缺失」缓存）。 */
    private static List<LucideConverter.Layer> layersFor(Icon icon) {
        String key = colorAssetKey(icon);
        if (ABSENT_COLOR_ASSETS.contains(key)) {
            return null;
        }
        List<LucideConverter.Layer> cached = LAYER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String svg = readResourceOrNull(COLOR_DIR + key + ".svg");
        if (svg == null) {
            ABSENT_COLOR_ASSETS.add(key);
            return null;
        }
        List<LucideConverter.Layer> layers = LucideConverter.toLayers(svg, VIEW_BOX);
        LAYER_CACHE.put(key, layers);
        return layers;
    }

    /**
     * 彩色资产的文件名（不含扩展名）。
     *
     * <p>Lucide 来源的图标沿用其源文件名（同名对齐，一眼能看出「这是同一个图形的彩色版」）；
     * 自绘图标（{@link Icon#BRAND} / {@link Icon#DOT}）用枚举名的小写。
     */
    static String colorAssetKey(Icon icon) {
        return icon.lucideResource() != null
                ? icon.lucideResource()
                : icon.name().toLowerCase(Locale.ROOT);
    }

    /** 已缓存的彩色分层 —— 给门禁断言「每层角色都在允许集合内、至少一层是 EDGE」。 */
    static List<LucideConverter.Layer> cachedLayers(Icon icon) {
        return LAYER_CACHE.get(colorAssetKey(icon));
    }

    // ─────────────────────────── 品牌通道（厂商商标） ───────────────────────────

    /** 这个键有没有对应的品牌商标资产（{@code icons/brand/<key>.svg} 在不在）。 */
    public static boolean hasBrandAsset(String key) {
        return brandLayersFor(key) != null;
    }

    /**
     * ⭐ R63-3：造一枚<b>厂商商标</b>图形 —— 字面官方色，不吃主题、不吃 CSS 角色。
     *
     * <p>拿不到资产时返回 {@code null}，<b>不猜</b>：把认不出的方言画成某家的标志就是编造，
     * 调用方自己回落通用图形（与 {@code ConnectionCatalog.graphic} 同一口径）。
     *
     * <p>返回的是 {@code Group}（多层字面填充的 {@code SVGPath}），按 {@code Node} 用即可，
     * 不要往下转型。与 {@link #colored(Icon, double)} 一样，{@code Group} 不是
     * {@code Control}，挂不了 Tooltip。
     *
     * @param key  资产名（小写方言 id，如 {@code mysql}）
     * @param size 目标边长（像素）
     */
    public static Node brand(String key, double size) {
        List<LucideConverter.BrandLayer> layers = brandLayersFor(key);
        if (layers == null) {
            return null;
        }
        double scale = size / VIEW_BOX;
        Group group = new Group();
        for (LucideConverter.BrandLayer layer : layers) {
            SVGPath path = new SVGPath();
            path.setContent(layer.path());
            path.setFill(Color.web(layer.fill()));
            path.setFillRule(layer.evenOdd() ? javafx.scene.shape.FillRule.EVEN_ODD
                    : javafx.scene.shape.FillRule.NON_ZERO);
            group.getChildren().add(path);
        }
        group.setScaleX(scale);
        group.setScaleY(scale);
        return group;
    }

    /** 品牌分层解析（带缓存与「确认缺失」缓存）。 */
    private static List<LucideConverter.BrandLayer> brandLayersFor(String key) {
        if (key == null || ABSENT_BRAND_ASSETS.contains(key)) {
            return null;
        }
        List<LucideConverter.BrandLayer> cached = BRAND_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String svg = readResourceOrNull(BRAND_DIR + key + ".svg");
        if (svg == null) {
            ABSENT_BRAND_ASSETS.add(key);
            return null;
        }
        List<LucideConverter.BrandLayer> layers = LucideConverter.toBrandLayers(svg, VIEW_BOX);
        BRAND_CACHE.put(key, layers);
        return layers;
    }

    /** 已缓存的品牌分层 —— 给门禁断言「每层都是字面官方色、坐标没越界」。 */
    static List<LucideConverter.BrandLayer> cachedBrandLayers(String key) {
        return BRAND_CACHE.get(key);
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
