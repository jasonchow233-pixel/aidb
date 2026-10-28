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
 * 顶部 64px 大图标命令带（2026-10-26 用户裁定：保持和 Navicat 一样）。
 *
 * <p>⭐ 2026-10-28 顶区精简（用户裁定：AIDB 品牌那一行是多余的，顶部栏目太多）：
 * 原来 90px = 26px 工具行（品牌 / 连接 / 搜索 / 主题 / 更多）+ 64px 命令带，
 * 现在**工具行整行取消** —— 那几只控件由外壳挂到标签栏那一行（{@link #utilityBar()}），
 * 品牌块直接删掉（品牌由窗口标题与任务栏图标承担，见 {@code BrandMarkAuditTest}）。
 * 顶区从 140px（菜单 24 + 90 + 标签 26）压到 114px（菜单 24 + 64 + 标签 26）。
 *
 * <p>命令带同时裁掉四个**与侧栏 / 页内工具条 / ⋯ 菜单重复**的命令
 * （表清单、刷新、诊断、设置）：它们仍可从菜单、侧栏与 ⌘K 到达，
 * 但不再在顶栏出现第二遍。剩下六个是全局新建/入口语义。
 *
 * <p>所有按钮的行为仍来自 {@link ActionRegistry}，没有装饰性假按钮。
 */
public final class AppBar extends VBox {

    /** 命令带与工具条合计低于这个宽度时，工具条切紧凑形态（搜索降级成图标）。 */
    private static final double COMPACT_CHROME_WIDTH = 780;

    private final MenuButton connectionMenu = new MenuButton();
    private final Label connectionName = new Label();
    private final Label connectionKind = new Label();
    private final SVGPath connectionDot = Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT);
    private final Label searchHint = new Label();
    private final Label searchKbd = new Label();
    private final HBox utility;
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

        HBox ribbon = new HBox(2,
                ribbonButton("new-connection", Icons.Icon.CONNECT, I18n.t("ribbon.newConnection"),
                        "adb-ribbon__icon--connect"),
                ribbonSeparator(),
                ribbonButton("new-query", Icons.Icon.QUERY, I18n.t("ribbon.newQuery"),
                        "adb-ribbon__icon--query"),
                ribbonButton("object.new-table", Icons.Icon.ADD, I18n.t("ribbon.newTable"),
                        "adb-ribbon__icon--table"),
                ribbonSeparator(),
                ribbonButton("import-export", Icons.Icon.TRANSFER, I18n.t("ribbon.importExport"),
                        "adb-ribbon__icon--transfer"),
                ribbonSeparator(),
                ribbonButton("saved-queries", Icons.Icon.HISTORY, I18n.t("ribbon.savedQueries"),
                        "adb-ribbon__icon--query"),
                ribbonButton("favorites.open", Icons.Icon.STAR, I18n.t("ribbon.favorites"),
                        "adb-ribbon__icon--favorite"),
                spacer());
        ribbon.setId("adb-ribbon");
        ribbon.getStyleClass().add("adb-ribbon");
        ribbon.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(ribbon);

        // ⚠️ 工具条必须在构造函数里建好 —— connectionPill() 内部会用空列表初始化一次，
        //    晚建就会把传进来的连接覆盖成「未连接」。建完立刻灌真实连接。
        utility = new HBox(8, connectionPill(actions), separator(), globalSearch(actions),
                themeButton(actions), overflow(actions));
        utility.setId("appbar-utility");
        utility.getStyleClass().add("adb-appbar__utility");
        utility.setAlignment(Pos.CENTER_LEFT);
        setConnections(profiles, activeConnectionId);
    }

    /**
     * 全局工具条：连接胶囊 / 全局搜索 / 主题 / 更多。
     *
     * <p>外壳把它挂到标签栏同一行（{@code adb-chrome-row}）—— 它就是原来那条
     * 26px 工具行，只是不再单独占一行。
     */
    public Node utilityBar() {
        return utility;
    }

    /**
     * 按这一行的可用宽度调整工具条形态。
     *
     * <p>窄窗（侧栏 + 详情面板同时打开时主区可能只剩 400 多像素）放不下
     * 220px 的搜索框：搜索降级成图标按钮、连接胶囊只留名字。
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
     * Navicat 式大图标按钮：26px 图标在上、11px 标签在下。
     *
     * <p>按钮的启用态绑定到动作本身 —— 不可用时是灰的，不是「点了没反应」。
     * {@code toneClass} 传**完整类名字面量**（不用拼接）：样式类覆盖门禁按字面量扫描，
     * 拼接出来的前缀会被当成「Java 用了、CSS 没定义」。
     */
    private Button ribbonButton(String actionId, Icons.Icon icon, String label, String toneClass) {
        Action action = actions.require(actionId);
        SVGPath glyph = Icons.of(icon, Icons.SIZE_RIBBON);
        glyph.getStyleClass().add("adb-ribbon__icon");
        if (toneClass != null) {
            glyph.getStyleClass().add(toneClass);
        }
        Label text = new Label(label);
        text.getStyleClass().add("adb-ribbon__label");
        VBox content = new VBox(3, glyph, text);
        content.setAlignment(Pos.CENTER);

        Button button = new Button();
        button.setId("ribbon-" + actionId);
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
