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
 * 顶部 90px 功能区（2026-10-26 用户裁定：保持和 Navicat 一样）。
 *
 * <p>结构与 Navicat 17 实测的 Ribbon 对齐：一条 26px 的工具行
 * （品牌 / 当前连接 / 全局搜索 / 主题 / 更多）+ 一条 64px 的大图标命令带
 * （连接 / 新建查询 / 表 / 新建表 / 导入导出 / 查询 / 收藏夹 / 诊断 / 刷新 / 设置）。
 * 合计 90px，与 Navicat 的 Ribbon 同高；菜单 24 + 90 + 标签栏 26 + 工具条 30 = 170px 顶区。
 *
 * <p>所有按钮的行为仍来自 {@link ActionRegistry}，没有装饰性假按钮。
 */
public final class AppBar extends VBox {

    private final MenuButton connectionMenu = new MenuButton();
    private final Label connectionName = new Label();
    private final Label connectionKind = new Label();
    private final SVGPath connectionDot = Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT);
    private final Label searchHint = new Label();
    /** 动作注册表 —— 菜单项与大图标都走同一条命令通道。 */
    private final ActionRegistry actions;

    private Consumer<ConnectionProfile> onSelectConnection = profile -> { };

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

        HBox utility = new HBox(8, brand(), separator(), connectionPill(actions), spacer(),
                primaryActions(actions), separator(), globalSearch(actions),
                themeButton(actions), overflow(actions));
        utility.setId("appbar-utility");
        utility.getStyleClass().add("adb-appbar__utility");
        utility.setAlignment(Pos.CENTER_LEFT);

        HBox ribbon = new HBox(2,
                ribbonButton("new-connection", Icons.Icon.CONNECT, "连接", "adb-ribbon__icon--connect"),
                ribbonSeparator(),
                ribbonButton("new-query", Icons.Icon.QUERY, "新建查询", "adb-ribbon__icon--query"),
                ribbonSeparator(),
                ribbonButton("object.tables", Icons.Icon.TABLE, "表", "adb-ribbon__icon--table"),
                ribbonButton("object.new-table", Icons.Icon.ADD, "新建表", "adb-ribbon__icon--table"),
                ribbonSeparator(),
                ribbonButton("import-export", Icons.Icon.TRANSFER, "导入 / 导出", "adb-ribbon__icon--transfer"),
                ribbonSeparator(),
                ribbonButton("saved-queries", Icons.Icon.HISTORY, "已保存查询", "adb-ribbon__icon--query"),
                ribbonButton("favorites.open", Icons.Icon.STAR, "收藏夹", "adb-ribbon__icon--favorite"),
                ribbonSeparator(),
                ribbonButton("diagnostics", Icons.Icon.DIAGNOSTICS, "诊断", null),
                ribbonButton("refresh", Icons.Icon.REFRESH, "刷新", null),
                spacer(),
                ribbonButton("settings", Icons.Icon.SETTINGS, "设置", null));
        ribbon.setId("adb-ribbon");
        ribbon.getStyleClass().add("adb-ribbon");
        ribbon.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(utility, ribbon);
        // ⚠️ 必须在所有子节点建好之后再灌连接列表 ——
        //    connectionPill() 里会用空列表初始化一次，那会把构造函数传进来的
        //    连接覆盖成「未连接」（AppBarTest 就是抓到这个才补上门禁的）。
        setConnections(profiles, activeConnectionId);
    }

    // ─────────────────────────── 左：品牌 ───────────────────────────

    private Region brand() {
        VBox mark = new VBox();
        mark.setId("appbar-brand-mark");
        mark.getStyleClass().add("adb-appbar__mark");
        mark.setAlignment(Pos.CENTER);
        SVGPath glyph = Icons.of(Icons.Icon.BRAND, Icons.SIZE_LABEL);
        glyph.setId("appbar-brand-glyph");
        glyph.getStyleClass().add("adb-appbar__mark-glyph");
        mark.getChildren().add(glyph);

        Label wordmark = new Label("AIDB");
        wordmark.setId("appbar-brand-name");
        wordmark.getStyleClass().add("adb-appbar__title");

        HBox box = new HBox(6, mark, wordmark);
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

    private Region primaryActions(ActionRegistry actions) {
        Button newQuery = actions.iconButton("new-query", Icons.Icon.QUERY,
                "新建查询", "新建查询");
        newQuery.setId("appbar-new-query");
        newQuery.getStyleClass().add("adb-appbar__action");

        Button manage = actions.iconButton("connections.manage", Icons.Icon.CONNECT,
                "连接管理", "连接管理");
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

        Label kbd = new Label(io.agenticdb.ui.Shortcuts.of("Mod+K"));
        kbd.getStyleClass().add("adb-appbar__search-kbd");

        HBox content = new HBox(8, icon, searchHint, kbd);
        content.setAlignment(Pos.CENTER_LEFT);

        Button search = new Button();
        search.setId("appbar-search");
        search.getStyleClass().addAll("adb-appbar__search");
        search.setGraphic(content);
        search.setAccessibleText("搜索对象或命令");
        search.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled("搜索对象或命令", "Mod+K")));
        search.setOnAction(event -> actions.run("command-palette"));
        return search;
    }

    private Button themeButton(ActionRegistry actions) {
        Button button = actions.iconButton("theme.toggle", Icons.Icon.THEME, "切换主题", "切换主题");
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
        menu.setAccessibleText("更多");
        menu.setTooltip(new Tooltip("更多"));

        menu.getItems().addAll(
                actions.menuItem("connections.manage"),
                actions.menuItem("new-query"),
                actions.menuItem("object.tables"),
                actions.menuItem("import-export"),
                new SeparatorMenuItem(),
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
