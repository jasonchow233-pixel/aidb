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
 * Ribbon 64px 单行 6 格 → **91px、12 格磁贴**（Connection · New Query · Table · View ·
 * Function · User · Others ｜ Query · Backup · Automation · Model · BI，真源 §1.2 顺序）。
 * 顶区 = 菜单 24 + Ribbon 91 + 页签行 25 = 140px。
 *
 * <p>⭐ 2026-11-04 R63-2：右端工具件只剩**主题 + ⋯**。原先这里的「当前连接胶囊 ▾」和
 * 「搜索对象或命令」框都删了 —— Navicat 17 实拍的磁贴带右端只有 ⓘ / ☆ / 头像，
 * 既没有连接下拉也没有搜索框；而且胶囊那句「我在哪家库」状态栏本来就在说
 * （灯 + 厂商图形 + 方言 · 连接名），搜索框则是一颗**假的**输入框：
 * 点它弹出的是命令面板，正文永远空白、placeholder 永远不被替换。
 * 切换连接的入口是左侧对象树与「连接」页签，⌘K 仍在（键盘与菜单栏）。
 *
 * <p>12 格里有六格（Function · User · Backup · Automation · Model · BI）背后是还没建的
 * 能力面（N28，R42）—— 按诚实条款它们落到**如实说明页**（工具页签 + 一句「本版本未提供」），
 * 不留死按钮；门禁 {@code RibbonCapabilityAuditTest} 逐格钉「必须落到真实目标」。
 * ⭐ R62-a H5-i（Q8 ②）：这六格同时留在原位不动，只在右上角挂一枚统一角标，
 * 把「这版没有」提前说给用户，而不是等点下去才读到 —— 名单见 {@link #PENDING_TILES}。
 *
 * <p>所有按钮的行为仍来自 {@link ActionRegistry}，没有装饰性假按钮。
 */
public final class AppBar extends VBox {

    /**
     * ⭐ R62-a H5-i（拍板 Q8 第②案）：落「如实说明页」的那几格，挂统一角标。
     *
     * <p>判据只有一条，而且不是我自己定的：<b>这格的动作处理器调的是
     * {@code openRibbonTool(SideBar.TOOL_…)}</b> —— 也就是它开出的页签
     * 由 {@code WorkspaceView} 给一句「本版本未提供」。今天命中六格
     * （Function·User·Backup·Automation·Model·BI），将来哪格建好了能力面，
     * 它的处理器就不再走那条通道，门禁会逼我把角标同一批摘掉
     * （见 {@code RibbonCapabilityAuditTest#thePendingBadgesMatchTheHonestLandingPoints}）。
     */
    private static final java.util.Set<String> PENDING_TILES = java.util.Set.of(
            "ribbon.function", "ribbon.user", "ribbon.backup",
            "ribbon.automation", "ribbon.model", "ribbon.bi");

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
                ribbonButton("ribbon.automation", Icons.Icon.HISTORY, I18n.t("ribbon.automation"),
                        "adb-ribbon__icon--automation"),
                ribbonButton("ribbon.model", Icons.Icon.STRUCTURE, I18n.t("ribbon.model"),
                        "adb-ribbon__icon--model"),
                ribbonButton("ribbon.bi", Icons.Icon.GRID, I18n.t("ribbon.bi"),
                        "adb-ribbon__icon--bi"));
        tiles.setId("adb-ribbon");
        tiles.getStyleClass().add("adb-ribbon");
        tiles.setAlignment(Pos.CENTER_LEFT);
        // ⭐ H5-i：角标挂在排上、不挂按钮里 —— 排在布局时会把每颗按钮的 layoutX/Y
        //    与宽高算好，环绑在那上面贴右上角，自己 managed=false 不吃排版（详见 PendingMark）。
        PendingMark.attach(tiles, pendingTiles);

        // ⭐ 12 格按英文标签排开就超过 1050px，再加右端工具件必然超过 1280 宽窗口。
        //    Navicat 磁贴定宽 70 吃的是中文；我们的英文标签截不得（标签不许省略号门禁）。
        //    所以磁贴排包进横向 ScrollPane：宽窗不出条，窄窗滚动而不是裁字；
        //    右端工具件（主题 / ⋯）在滚动区之外，任何宽度下都可达。
        ScrollPane tileScroll = new ScrollPane(tiles);
        tileScroll.getStyleClass().add("adb-ribbon__scroll");
        tileScroll.setFitToHeight(true);
        tileScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        tileScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tileScroll.setPannable(true);

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
