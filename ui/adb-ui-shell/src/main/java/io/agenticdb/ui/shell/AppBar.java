package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/**
 * 顶部 91px Ribbon（2026-10-30 R36：按 Navicat 17 实拍逐像素对回来）。
 *
 * <p>⭐ 2026-10-30 R36 顶区重做（撤销 R32-D 的精简，用户裁定「完全照 Navicat」）：
 * Ribbon 64px 单行 6 格 → **91px、12 格磁贴**（真源 §1.2 顺序）。
 * 之后 P3 补 Profile / Transfer 两格；2026-11-09 用户裁定**移除 BI**（既不做 BI 工作区，
 * 也不再保留一个只开说明页的入口）；2026-11-10 用户裁定删除「明确不做」的 Automation / Model 磁贴。
 * 当前是 <b>11 格</b>：Connection · New Query · Table · View · Function · User · Others ｜
 * Query · Backup · Profile · Transfer。顶区 = 菜单 24 + Ribbon 91 + 页签行 25 = 140px。
 *
 * <p>⭐ 2026-11-04 R63-2：右端工具件只剩**主题 + ⋯**。原先这里的「当前连接胶囊 ▾」和
 * 「搜索对象或命令」框都删了 —— Navicat 17 实拍的磁贴带右端只有 ⓘ / ☆ / 头像，
 * 既没有连接下拉也没有搜索框；而且胶囊那句「我在哪家库」状态栏本来就在说
 * （灯 + 厂商图形 + 方言 · 连接名），搜索框则是一颗**假的**输入框：
 * 点它弹出的是命令面板，正文永远空白、placeholder 永远不被替换。
 * 切换连接的入口是左侧对象树与「连接」页签，⌘K 仍在（键盘与菜单栏）。
 *
 * <p>11 格里<b>每一格</b>背后都有真实能力面 —— Automation / Model 因「明确不做」删了入口，
 * Function 于 2026-11-10 接上真实的例程清单页（此前落说明页，而例程定义体早已交付，
 * 两条路自相矛盾）。功能区不再有任何一格需要挂「未提供」角标
 * （名单见 {@link #PENDING_TILES}，当前为空）；User / Backup / Profile / Transfer
 * 四格接的是各自的工具页。
 *
 * <p>所有按钮的行为仍来自 {@link ActionRegistry}，没有装饰性假按钮。
 */
public final class AppBar extends VBox {

    /**
     * ⭐ R62-a H5-i（拍板 Q8 第②案）：落「如实说明页」的那几格，挂统一角标。
     *
     * <p>判据只有一条，而且不是我自己定的：<b>这格的动作处理器调的是
     * {@code openRibbonTool(SideBar.TOOL_…)}</b> —— 也就是它开出的页签
     * 由 {@code WorkspaceView} 给一句「本版本未提供」。
     *
     * <p>⭐ 2026-11-10：这份名单现在是<b>空的</b> ——
     * Automation / Model 因「明确不做」删了入口，Function 接上了真实例程清单页，
     * 于是功能区 11 格<b>全部</b>有真落点，没有一格需要挂角标。
     * 角标机制本身仍在（页内按钮：AI 填充 / 数据画像 / 问 AI / 查询构造器 / OR 条件组
     * 走 {@code PendingMark.saySo} 与 {@code PendingMark.mark}），
     * 将来哪格又落回说明页，这里加上名字，机制会自动挂上去。
     *
     * <p>⚠️ 名单与 {@code SideBar.TOOL_UNAVAILABLE_ROUTES} /
     * {@code SideBar.TOOL_OBJECT_ROUTES} 必须同批维护 ——
     * 门禁 {@code RibbonCapabilityAuditTest#thePendingBadgesMatchTheHonestLandingPoints}
     * 从那两个集合反推该挂哪几格，改一边不改另一边会立刻红。
     */
    private static final java.util.Set<String> PENDING_TILES = java.util.Set.of();

    private final HBox utility;
    /** 磁贴排（Ribbon 里可横向滚动的那一段，窄窗口时靠滚动而不是截标签）。 */
    private final HBox tiles;
    /**
     * ⭐ H5-i（Q8 ②）：落「如实说明页」的那几格，构造期间攒起来，建完磁贴排统一挂角标。
     * 环是<b>排</b>的孩子、{@code managed=false} —— 不进任何一格宽高，位置靠绑定跟着按钮走。
     */
    private final java.util.List<Button> pendingTiles = new java.util.ArrayList<>();
    /** 动作注册表 —— 菜单项与大图标都走同一条命令通道。 */
    private final ActionRegistry actions;

    public AppBar(ActionRegistry actions) {
        this.actions = actions;
        getStyleClass().add("adb-appbar");
        int height = io.agenticdb.ui.Tokens.H_APPBAR_LIGHT;
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);

        // R63-2：Navicat 的磁贴带右端只有主题与「更多」两颗 ——
        // 连接身份归状态栏那颗灯，搜索归 ⌘K，这里不再各摆一份重复的。
        utility = new HBox(8, themeButton(actions), overflow(actions));
        utility.setId("appbar-utility");
        utility.getStyleClass().add("adb-appbar__utility");
        utility.setAlignment(Pos.CENTER_LEFT);

        tiles = new HBox(2,
                ribbonButton("new-connection", Icons.Icon.CONNECT, I18n.t("ribbon.newConnection"),
                        "adb-ribbon__icon--connect"),
                ribbonButton("new-query", Icons.Icon.QUERY, I18n.t("ribbon.newQuery"),
                        "adb-ribbon__icon--query"),
                ribbonButton("object.new-table", Icons.Icon.ADD, I18n.t("ribbon.newTable"),
                        "adb-ribbon__icon--table"),
                ribbonButton("ribbon.view", Icons.Icon.VIEW, I18n.t("ribbon.view"),
                        "adb-ribbon__icon--view"),
                ribbonButton("ribbon.function", Icons.Icon.FUNCTION, I18n.t("ribbon.function"),
                        "adb-ribbon__icon--function"),
                ribbonButton("ribbon.user", Icons.Icon.USER, I18n.t("ribbon.user"),
                        "adb-ribbon__icon--user"),
                ribbonButton("ribbon.others", Icons.Icon.FOLDER, I18n.t("ribbon.others"),
                        "adb-ribbon__icon--others"),
                ribbonSeparator(),
                ribbonButton("saved-queries", Icons.Icon.QUERY, I18n.t("ribbon.query"),
                        "adb-ribbon__icon--query"),
                ribbonButton("ribbon.backup", Icons.Icon.SAVE, I18n.t("ribbon.backup"),
                        "adb-ribbon__icon--backup"),
                ribbonButton("ribbon.profiling", Icons.Icon.DATABASE, I18n.t("ribbon.profiling"),
                        "adb-ribbon__icon--profiling"),
                ribbonButton("ribbon.transfer", Icons.Icon.EXPORT, I18n.t("ribbon.transfer"),
                        "adb-ribbon__icon--transfer"));
        tiles.setId("adb-ribbon");
        tiles.getStyleClass().add("adb-ribbon");
        tiles.setAlignment(Pos.CENTER_LEFT);
        // ⭐ H5-i：角标挂在排上、不挂按钮里 —— 排在布局时会把每颗按钮的 layoutX/Y
        //    与宽高算好，环绑在那上面贴右上角，自己 managed=false 不吃排版（详见 PendingMark）。
        PendingMark.attach(tiles, pendingTiles);

        // ⭐ 当前是 11 格（R36 的 12 格 + P3 两格 − 已移除的 BI − 已删入口的 Automation/Model），
        //    按英文标签排开就超过 1050px，再加右端工具件必然超过 1280 宽窗口。
        //    Navicat 磁贴定宽 70 吃的是中文；我们的英文标签截不得（标签不许省略号门禁）。
        //    所以磁贴排包进横向 ScrollPane：宽窗不出条，窄窗滚动而不是裁字；
        //    右端工具件（主题 / ⋯）在滚动区之外，任何宽度下都可达。
        ScrollPane tileScroll = new ScrollPane(tiles);
        tileScroll.getStyleClass().add("adb-ribbon__scroll");
        tileScroll.setFitToHeight(true);
        tileScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tileScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tileScroll.setPannable(true);
        // ⭐ D-19（1280×800 体感）：溢出条按 H5-h 收成 6px 细轨，笔记本上「够得着」
        //    但很难拖。把竖直滚轮 / 触控板横向增量翻译成 hvalue —— 鼠标停在功能区
        //    直接滚就能看到后面的格子。只在**真的能横滚**时 consume，不吞页面的竖直滚动。
        //    ⚠️ 纯事件 handler，不动任何磁贴几何：61+ 帧的像素对照不受影响。
        tileScroll.setOnScroll(event -> {
            double next = wheelHvalue(tileScroll.getHvalue(), tileScroll.getHmin(),
                    tileScroll.getHmax(), event.getDeltaX(), event.getDeltaY());
            if (next != tileScroll.getHvalue()) {
                tileScroll.setHvalue(next);
                event.consume();
            }
        });

        HBox ribbon = new HBox(tileScroll, spacer(), utility);
        ribbon.getStyleClass().add("adb-appbar__row");
        ribbon.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(ribbon);
    }

    // ─────────────────────────── 右：全局入口 ───────────────────────────

    private Button themeButton(ActionRegistry actions) {
        String label = I18n.t("appbar.toggleTheme");
        Button button = actions.iconButton("theme.toggle", Icons.Icon.THEME, label, label);
        button.setId("appbar-theme");
        button.getStyleClass().add("adb-appbar__action");
        return button;
    }

    private MenuButton overflow(ActionRegistry actions) {
        MenuButton menu = new MenuButton();
        menu.setId("appbar-more");
        menu.getStyleClass().addAll("adb-appbar__action", "adb-appbar__more");
        SVGPath icon = Icons.of(Icons.Icon.MORE);
        menu.setGraphic(icon);
        menu.setAccessibleText(I18n.t("appbar.more"));
        menu.setTooltip(new Tooltip(I18n.t("appbar.more")));

        menu.getItems().addAll(
                actions.menuItem("connections.manage"),
                actions.menuItem("new-query"),
                actions.menuItem("object.tables"),
                actions.menuItem("query-history"),
                actions.menuItem("import-export"),
                new SeparatorMenuItem(),
                actions.menuItem("refresh"),
                actions.menuItem("settings"),
                actions.menuItem("diagnostics"),
                actions.menuItem("shortcuts"),
                actions.menuItem("about"));
        return menu;
    }

    /**
     * Navicat 式磁贴：26px 彩色图标在上、11px 标签在下（实测磁贴 ≈70×90）。
     *
     * <p>按钮的启用态绑定到动作本身 —— 不可用时是灰的，不是「点了没反应」。
     * ⚠️ 但「能力面这版没建」<b>不走</b>这条通道（Q8 第②案）：置灰说的是「条件不满足」，
     * 那种格子留在原位挂一枚 {@link PendingMark} 角标 + 提示语里说清，见 {@link #PENDING_TILES}。
     * {@code toneClass} 传**完整类名字面量**（不用拼接）：样式类覆盖门禁按字面量扫描，
     * 拼接出来的前缀会被当成「Java 用了、CSS 没定义」。
     *
     * <p>⚠️ DOM id 里动作 id 的 {@code :} 与 {@code .} 要换成短横线 —— CSS id 选择器
     * 里它们是分隔符/伪类符，{@code lookup("#ribbon-object.new-table")} 会查不到节点。
     */
    private Button ribbonButton(String actionId, Icons.Icon icon, String label, String toneClass) {
        Action action = actions.require(actionId);
        boolean pending = PENDING_TILES.contains(actionId);
        // R35 N4-a：功能区是「一眼认命令」的地方，Navicat 实测整条带子是全 UI 最彩的，
        // 所以走彩色档。没有彩色资产时 colored() 自己回落单色，不必在这里判断。
        // ⚠️ toneClass 只能当**宿主类**：彩色返回的是 Group（无可绘属性），
        //    颜色必须靠 app.css 里「宿主类 + 后代 .adb-icon-edge」那条规则打在子层上。
        Node glyph = Icons.colored(icon, Icons.SIZE_RIBBON);
        glyph.getStyleClass().add("adb-ribbon__icon");
        if (toneClass != null) {
            glyph.getStyleClass().add(toneClass);
        }
        Label text = new Label(label);
        text.getStyleClass().add("adb-ribbon__label");
        // ⚠️ 图形槽保持原样：角标一旦裹进这里，整排的标签基线就会挪 —— 实测包一层
        //    钉成图标尺寸的容器会把这六格垫下去 6px，压成 0 高又抬上去 7px，
        //    没有哪种尺寸是中性的。所以角标挂在外面的磁贴排上（见 #tiles 之后）。
        VBox content = new VBox(3, glyph, text);
        content.setAlignment(Pos.CENTER);

        Button button = new Button();
        button.setId("ribbon-" + actionId.replaceAll("[^a-zA-Z0-9_-]", "-"));
        button.getStyleClass().add("adb-ribbon__button");
        button.setGraphic(content);
        // ⭐ H5-i：没建的能力不许只藏在落点里 —— 提示语与读屏文案提前说同一句实话。
        String hint = pending ? PendingMark.hint(label) : label;
        button.setAccessibleText(hint);
        button.setTooltip(new Tooltip(hint));
        button.disableProperty().bind(action.enabledProperty().not());
        button.setOnAction(event -> action.run());
        if (pending) {
            pendingTiles.add(button);
        }
        return button;
    }

    /**
     * ⭐ D-19：滚轮增量 → 横向滚动位置的**纯函数**。
     *
     * <p>刻意不碰 {@link ScrollPane}，只收 5 个数：门禁在 headless harness 里
     * 不必构造 {@code ScrollEvent}（那套构造签名跨 JavaFX 版本会变）就能量到方向、
     * 步长与夹取。接线在构造器里的 {@code tileScroll.setOnScroll}。
     *
     * <p>约定：JavaFX 向下滚 / 触控板左滑给负增量，此时向右看后面的磁贴；
     * 水平增量优先（两指横滑不该被竖直分量抢走）。一次最多推进 1/4 行程，
     * 免得一格轮直接跳到末尾；不溢出（{@code max <= min}）时原值返回，
     * 调用方据此<strong>不</strong>吞事件 —— 页面该收到竖直滚动还是得收到。
     */
    static double wheelHvalue(double current, double min, double max,
                              double deltaX, double deltaY) {
        if (max <= min) {
            return current;
        }
        double delta = deltaX != 0 ? deltaX : deltaY;
        if (delta == 0) {
            return current;
        }
        double step = Math.min(0.25, Math.abs(delta) / 320.0);
        double next = current + (delta < 0 ? step : -step);
        return Math.min(max, Math.max(min, next));
    }

    private Region ribbonSeparator() {
        Region region = new Region();
        region.getStyleClass().add("adb-ribbon__separator");
        return region;
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
