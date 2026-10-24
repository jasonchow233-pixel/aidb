package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.Icons;
import javafx.geometry.Pos;
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
 * ⭐ 顶部应用栏（应用级状态 + 全局入口）。
 *
 * <p>2026-10 UI 全面重构：旧版顶栏是「大图标 + 文字」的功能栏
 * （连接 / 查询 / 表 / 导入导出 / 工具），五个入口挤在一条 52px 高的带子里，
 * 每个入口都是一张 52px 宽的卡片 —— 一眼看去像**营销页导航**，不是工作界面。
 *
 * <p>新形态遵循 IDE 的通用约定：
 * <ul>
 *   <li><b>左</b>：品牌标识 + 产品名（应用身份），随后是**当前连接**的状态胶囊
 *       （连接状态是应用级状态，属于这里）；</li>
 *   <li><b>右</b>：全局命令搜索（⌘K）、主题切换、设置、更多（溢出菜单）；</li>
 *   <li><b>不做</b>的事：把所有数据库操作塞进顶栏 —— 建表、导入导出、诊断这些
 *       入口在「更多」菜单、⌘K 命令面板与侧栏里都能到。</li>
 * </ul>
 *
 * <p>所有按钮的真实行为都来自 {@link ActionRegistry}，没有装饰性假按钮。
 */
public final class AppBar extends HBox {

    /** 品牌标识的边长。 */
    private static final double BRAND_MARK = 26;

    private final MenuButton connectionMenu = new MenuButton();
    private final Label connectionName = new Label();
    private final Label connectionKind = new Label();
    private final SVGPath connectionDot = Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT);
    private final Label searchHint = new Label();
    /** 动作注册表 —— 「管理连接…」等菜单项要走同一条命令通道。 */
    private final ActionRegistry actions;

    private Consumer<ConnectionProfile> onSelectConnection = profile -> { };

    public AppBar(ActionRegistry actions, List<ConnectionProfile> profiles,
                  String activeConnectionId,
                  Consumer<ConnectionProfile> onSelectConnection) {
        this.actions = actions;
        getStyleClass().add("adb-appbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);
        int height = io.agenticdb.ui.Tokens.H_APPBAR_LIGHT;
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);
        this.onSelectConnection = onSelectConnection == null ? profile -> { } : onSelectConnection;

        getChildren().addAll(brand(), separator(), connectionPill(actions), spacer(),
                primaryActions(actions), separator(), globalSearch(actions),
                themeButton(actions), settingsButton(actions), overflow(actions));
        // ⚠️ 必须在所有子节点建好之后再灌连接列表 ——
        //    connectionPill() 里会用空列表初始化一次，那会把构造函数传进来的
        //    连接覆盖成「未连接」（AppBarTest 就是抓到这个才补上门禁的）。
        setConnections(profiles, activeConnectionId);
    }

    // ─────────────────────────── 左：品牌 ───────────────────────────

    private Region brand() {
        // 品牌标识：圆角方块 + 品牌字形（圆柱 + AI 星芒）。矢量、随主题换色，不用位图。
        VBox mark = new VBox();
        mark.setId("appbar-brand-mark");
        mark.getStyleClass().add("adb-appbar__mark");
        mark.setAlignment(Pos.CENTER);
        SVGPath glyph = Icons.of(Icons.Icon.BRAND, Icons.SIZE_TOOLBAR);
        glyph.setId("appbar-brand-glyph");
        glyph.getStyleClass().add("adb-appbar__mark-glyph");
        mark.getChildren().add(glyph);

        Label wordmark = new Label("AIDB");
        wordmark.setId("appbar-brand-name");
        wordmark.getStyleClass().add("adb-appbar__title");

        HBox box = new HBox(8, mark, wordmark);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("adb-appbar__brand");
        return box;
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
        connectionMenu.setAccessibleText("当前连接");
        connectionMenu.setTooltip(new Tooltip("切换当前连接"));
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
            connectionMenu.setTooltip(new Tooltip("当前连接：" + active.name()
                    + "（" + active.kindDisplay() + "）· 点击切换"));
        } else {
            connectionName.setText("未连接");
            connectionKind.setText("");
            connectionMenu.setTooltip(new Tooltip("尚未连接任何数据库"));
        }

        connectionMenu.getItems().clear();
        if (safe.isEmpty()) {
            MenuItem empty = new MenuItem("没有可用连接");
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
        MenuItem manage = new MenuItem("管理连接…");
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

    /**
     * 主操作组：只有**全局级**的两个高频动作。
     *
     * <p>建表 / 导入导出 / 诊断这些不是「全局」动作 —— 它们属于某个连接或
     * 某个对象，放在对象首页、侧栏与 ⌘K 里更合适。所以顶栏只留两个。
     */
    private Region primaryActions(ActionRegistry actions) {
        Button newQuery = actions.iconButton("new-query", Icons.Icon.QUERY,
                "新建查询 (⇧⌘N)", "新建查询");
        newQuery.setId("appbar-new-query");
        newQuery.getStyleClass().add("adb-appbar__action");

        Button manage = actions.iconButton("connections.manage", Icons.Icon.CONNECT,
                "连接管理 (⌘N)", "连接管理");
        manage.setId("appbar-manage-connections");
        manage.getStyleClass().add("adb-appbar__action");

        HBox group = new HBox(2, newQuery, manage);
        group.getStyleClass().add("adb-appbar__actions");
        group.setAlignment(Pos.CENTER_RIGHT);
        return group;
    }

    private Region globalSearch(ActionRegistry actions) {
        SVGPath icon = Icons.of(Icons.Icon.SEARCH);
        icon.getStyleClass().add("adb-appbar__search-icon");

        searchHint.setText("搜索对象或命令");
        searchHint.getStyleClass().add("adb-appbar__search-hint");

        Label kbd = new Label("⌘K");
        kbd.getStyleClass().add("adb-appbar__search-kbd");

        HBox content = new HBox(8, icon, searchHint, kbd);
        content.setAlignment(Pos.CENTER_LEFT);

        Button search = new Button();
        search.setId("appbar-search");
        search.getStyleClass().addAll("adb-appbar__search");
        search.setGraphic(content);
        search.setAccessibleText("搜索对象或命令");
        search.setTooltip(new Tooltip("搜索对象或命令 (⌘K)"));
        search.setOnAction(event -> actions.run("command-palette"));
        return search;
    }

    private Button themeButton(ActionRegistry actions) {
        Button button = actions.iconButton("theme.toggle", Icons.Icon.THEME, "切换主题", "切换主题");
        button.setId("appbar-theme");
        button.getStyleClass().add("adb-appbar__action");
        return button;
    }

    private Button settingsButton(ActionRegistry actions) {
        Button button = actions.iconButton("settings", Icons.Icon.SETTINGS, "设置 (⌘,)", "设置");
        button.setId("appbar-settings");
        button.getStyleClass().add("adb-appbar__action");
        return button;
    }

    /**
     * 溢出菜单（⋯）。
     *
     * <p>低频但真实存在的入口放这里，而不是在顶栏排一排图标 ——
     * 顶栏只留「全局」的东西。
     */
    private MenuButton overflow(ActionRegistry actions) {
        MenuButton menu = new MenuButton();
        menu.setId("appbar-more");
        menu.getStyleClass().addAll("adb-appbar__action", "adb-appbar__more");
        SVGPath icon = Icons.of(Icons.Icon.MORE);
        menu.setGraphic(icon);
        menu.setAccessibleText("更多");
        menu.setTooltip(new Tooltip("更多"));

        menu.getItems().addAll(
                actions.menuItem("connections.manage"),
                actions.menuItem("new-query"),
                actions.menuItem("object.tables"),
                actions.menuItem("import-export"),
                new SeparatorMenuItem(),
                actions.menuItem("diagnostics"),
                actions.menuItem("shortcuts"),
                actions.menuItem("about"));
        return menu;
    }

    private Region separator() {
        Region region = new Region();
        region.getStyleClass().add("adb-appbar__separator");
        return region;
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }
}
