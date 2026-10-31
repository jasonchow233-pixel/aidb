package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.List;
import java.util.function.Consumer;

/**
 * 顶部 91px Ribbon（2026-10-30 R36：按 Navicat 17 实拍逐像素对回来）。
 *
 * <p>⭐ 2026-10-30 R36 顶区重做（撤销 R32-D 的精简，用户裁定「完全照 Navicat」）：
 * Ribbon 64px 单行 6 格 → **91px、12 格磁贴**（Connection · New Query · Table · View ·
 * Function · User · Others ｜ Query · Backup · Automation · Model · BI，真源 §1.2 顺序）。
 * 全局工具件（连接胶囊 / 搜索 / 主题 / ⋯）从页签行挪进 **Ribbon 右端** ——
 * 页签行只放页签（N7）。顶区 = 菜单 24 + Ribbon 91 + 页签行 25 = 140px。
 *
 * <p>12 格里的 View / User / Backup / Automation / Model / BI 背后是还没建的能力面
 * （N28，R42）—— 按诚实条款它们落到**如实说明页**（工具页签 + 一句「本版本未提供」），
 * 不留死按钮；门禁 {@code RibbonCapabilityAuditTest} 逐格钉「必须落到真实目标」。
 *
 * <p>所有按钮的行为仍来自 {@link ActionRegistry}，没有装饰性假按钮。
 */
public final class AppBar extends VBox {

    /** Ribbon 行低于这个宽度时工具条切紧凑形态（搜索降级成图标）。 */
    private static final double COMPACT_CHROME_WIDTH = 780;

    private final MenuButton connectionMenu = new MenuButton();
    private final Label connectionName = new Label();
    private final Label connectionKind = new Label();
    private final SVGPath connectionDot = Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT);
    private final Label searchHint = new Label();
    private final Label searchKbd = new Label();
    private final HBox utility;
    /** 磁贴排（Ribbon 里可横向滚动的那一段，窄窗口时靠滚动而不是截标签）。 */
    private final HBox tiles;
    /** 动作注册表 —— 菜单项与大图标都走同一条命令通道。 */
    private final ActionRegistry actions;

    private Consumer<ConnectionProfile> onSelectConnection = profile -> { };
    private boolean chromeCompact;

    public AppBar(ActionRegistry actions, List<ConnectionProfile> profiles,
                  String activeConnectionId,
                  Consumer<ConnectionProfile> onSelectConnection) {
        this.actions = actions;
        getStyleClass().add("adb-appbar");
        int height = io.agenticdb.ui.Tokens.H_APPBAR_LIGHT;
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);
        this.onSelectConnection = onSelectConnection == null ? profile -> { } : onSelectConnection;

        // ⚠️ 工具条必须先建好：connectionPill() 内部会用空列表初始化一次，
        //    晚建会把传进来的真实连接覆盖成「未连接」。
        utility = new HBox(8, connectionPill(actions), separator(), globalSearch(actions),
                themeButton(actions), overflow(actions));
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

        // ⭐ 12 格按英文标签排开就超过 1050px，再加右端工具件必然超过 1280 宽窗口。
        //    Navicat 磁贴定宽 70 吃的是中文；我们的英文标签截不得（标签不许省略号门禁）。
        //    所以磁贴排包进横向 ScrollPane：宽窗不出条，窄窗滚动而不是裁字；
        //    右端工具件（连接 / 搜索 / 主题 / ⋯）在滚动区之外，任何宽度下都可达。
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

        setConnections(profiles, activeConnectionId);
    }

    /**
     * 按 Ribbon 行的可用宽度调整工具条形态。
     *
     * <p>⭐ R36 之后磁贴排本身可滚动，这里收的仍是**工具条外观**：
     * 窄窗口下搜索降级成图标按钮、连接胶囊只留名字，把右端留给磁贴排。
     * 命令面板本身仍可 ⌘K 直达，所以收起的是**外观**不是入口。
     */
    public void fitChromeWidth(double rowWidth) {
        boolean compact = rowWidth > 0 && rowWidth < COMPACT_CHROME_WIDTH;
        if (compact == chromeCompact) {
            return;
        }
        chromeCompact = compact;
        utility.getStyleClass().remove("adb-appbar__utility--compact");
        if (compact) {
            utility.getStyleClass().add("adb-appbar__utility--compact");
        }
        searchHint.setVisible(!compact);
        searchHint.setManaged(!compact);
        searchKbd.setVisible(!compact);
        searchKbd.setManaged(!compact);
        connectionKind.setVisible(!compact);
        connectionKind.setManaged(!compact);
    }

    /** 当前是否处于紧凑形态（门禁读它，不看样式类字符串）。 */
    public boolean isChromeCompact() {
        return chromeCompact;
    }

    // ─────────────────────── 中：当前连接（应用级状态）───────────────────────

    private Region connectionPill(ActionRegistry actions) {
        connectionName.setId("appbar-connection-name");
        connectionName.getStyleClass().add("adb-appbar__connection-name");

        connectionKind.setId("appbar-connection-kind");
        connectionKind.getStyleClass().add("adb-appbar__connection-kind");

        connectionDot.getStyleClass().add("adb-appbar__connection-dot");

        HBox text = new HBox(6, connectionDot, connectionName, connectionKind);
        text.setAlignment(Pos.CENTER_LEFT);

        connectionMenu.setId("appbar-connection-menu");
        connectionMenu.getStyleClass().addAll("adb-appbar__connection", "adb-button");
        connectionMenu.setGraphic(text);
        connectionMenu.setAccessibleText(I18n.t("appbar.connectionAria"));
        connectionMenu.setTooltip(new Tooltip(I18n.t("appbar.switchConnection")));
        setConnections(List.of(), null);
        return connectionMenu;
    }

    /**
     * 刷新连接胶囊的内容与下拉项。
     *
     * <p>下拉里是**真实**的连接列表 + 「管理连接…」，切换后由外壳执行
     * （{@code selectConnection}）—— 不是装饰。
     */
    public void setConnections(List<ConnectionProfile> profiles, String activeConnectionId) {
        List<ConnectionProfile> safe = profiles == null ? List.of() : profiles;
        ConnectionProfile active = safe.stream()
                .filter(profile -> profile.id().equals(activeConnectionId))
                .findFirst().orElse(null);

        if (active != null) {
            connectionName.setText(active.name());
            connectionKind.setText(active.kindDisplay());
            connectionMenu.setTooltip(new Tooltip(I18n.t("appbar.currentConnectionTooltip",
                    active.name(), active.kindDisplay())));
        } else {
            connectionName.setText(I18n.t("appbar.notConnected"));
            connectionKind.setText("");
            connectionMenu.setTooltip(new Tooltip(I18n.t("appbar.noConnectionsYet")));
        }

        connectionMenu.getItems().clear();
        if (safe.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.t("appbar.noConnections"));
            empty.setDisable(true);
            connectionMenu.getItems().add(empty);
        } else {
            for (ConnectionProfile profile : safe) {
                MenuItem item = new MenuItem(profile.name() + "  ·  " + profile.kindDisplay());
                item.setId("appbar-connection-" + profile.id());
                item.setDisable(profile.id().equals(activeConnectionId));
                item.setOnAction(event -> onSelectConnection.accept(profile));
                connectionMenu.getItems().add(item);
            }
        }
        connectionMenu.getItems().add(new SeparatorMenuItem());
        MenuItem manage = new MenuItem(I18n.t("appbar.manageConnections"));
        manage.setId("appbar-connection-manage");
        manage.setOnAction(event -> actions.run("connections.manage"));
        connectionMenu.getItems().add(manage);
        connectionMenu.getItems().add(actions.menuItem("connection.reconnect"));
        connectionMenu.getItems().add(actions.menuItem("connection.disconnect"));
    }

    /** 连接状态点：在线 / 断开 / 出错。 */
    public void setConnectionState(boolean online, boolean failed) {
        String cls = failed ? "adb-appbar__connection-dot--error"
                : online ? "adb-appbar__connection-dot--online"
                : "adb-appbar__connection-dot--offline";
        connectionDot.getStyleClass().removeAll("adb-appbar__connection-dot--online",
                "adb-appbar__connection-dot--offline", "adb-appbar__connection-dot--error");
        connectionDot.getStyleClass().add(cls);
    }

    // ─────────────────────────── 右：全局入口 ───────────────────────────

    private Region globalSearch(ActionRegistry actions) {
        SVGPath icon = Icons.of(Icons.Icon.SEARCH);
        icon.getStyleClass().add("adb-appbar__search-icon");

        searchHint.setText(I18n.t("appbar.searchPlaceholder"));
        searchHint.getStyleClass().add("adb-appbar__search-hint");

        searchKbd.setText(io.agenticdb.ui.Shortcuts.of("Mod+K"));
        searchKbd.getStyleClass().add("adb-appbar__search-kbd");

        HBox content = new HBox(8, icon, searchHint, searchKbd);
        content.setAlignment(Pos.CENTER_LEFT);

        Button search = new Button();
        search.setId("appbar-search");
        search.getStyleClass().addAll("adb-appbar__search");
        search.setGraphic(content);
        search.setAccessibleText(I18n.t("appbar.searchPlaceholder"));
        search.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled(
                I18n.t("appbar.searchPlaceholder"), "Mod+K")));
        search.setOnAction(event -> actions.run("command-palette"));
        return search;
    }

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
     * {@code toneClass} 传**完整类名字面量**（不用拼接）：样式类覆盖门禁按字面量扫描，
     * 拼接出来的前缀会被当成「Java 用了、CSS 没定义」。
     *
     * <p>⚠️ DOM id 里动作 id 的 {@code :} 与 {@code .} 要换成短横线 —— CSS id 选择器
     * 里它们是分隔符/伪类符，{@code lookup("#ribbon-object.new-table")} 会查不到节点。
     */
    private Button ribbonButton(String actionId, Icons.Icon icon, String label, String toneClass) {
        Action action = actions.require(actionId);
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
        VBox content = new VBox(3, glyph, text);
        content.setAlignment(Pos.CENTER);

        Button button = new Button();
        button.setId("ribbon-" + actionId.replaceAll("[^a-zA-Z0-9_-]", "-"));
        button.getStyleClass().add("adb-ribbon__button");
        button.setGraphic(content);
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(label));
        button.disableProperty().bind(action.enabledProperty().not());
        button.setOnAction(event -> action.run());
        return button;
    }

    private Region separator() {
        Region region = new Region();
        region.getStyleClass().add("adb-appbar__separator");
        return region;
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
