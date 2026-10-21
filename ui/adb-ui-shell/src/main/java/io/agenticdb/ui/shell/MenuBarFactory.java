package io.agenticdb.ui.shell;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

/**
 * 原生菜单栏。菜单项全部来自 {@link ActionRegistry}，不在这里写业务。
 * macOS 上走系统菜单栏（{@code setUseSystemMenuBar(true)}）。
 */
public final class MenuBarFactory {

    private final ActionRegistry actions;

    public MenuBarFactory(ActionRegistry actions) {
        this.actions = actions;
    }

    public MenuBar build() {
        MenuBar bar = new MenuBar();
        bar.setUseSystemMenuBar(true);
        bar.getMenus().addAll(
                fileMenu(), editMenu(), viewMenu(),
                databaseMenu(), toolsMenu(), windowMenu(), helpMenu());
        return bar;
    }

    private Menu fileMenu() {
        return new Menu("文件", null,
                actions.menuItem("new-connection"),
                // 4.5-10：这两个动作以前只注册、没有任何菜单入口，只能靠 ⌘K 找。
                actions.menuItem("connections.manage"),
                actions.menuItem("new-query"),
                actions.menuItem("open-sql"),
                new SeparatorMenuItem(),
                actions.menuItem("tab.close"),
                actions.menuItem("window.close"),
                new SeparatorMenuItem(),
                actions.menuItem("app.quit"));
    }

    private Menu editMenu() {
        // 撤销 / 重做 / 复制委托给当前活动页；剪切 / 粘贴在数据库客户端没有语义，
        // 按 TODO §12「占位要么接要么删」删除，不留永久禁用项。
        return new Menu("编辑", null,
                actions.menuItem("edit.undo"),
                actions.menuItem("edit.redo"),
                new SeparatorMenuItem(),
                actions.menuItem("edit.copy"),
                new SeparatorMenuItem(),
                actions.menuItem("focus-search"),
                actions.menuItem("query.comment"),
                actions.menuItem("command-palette"));
    }

    private Menu viewMenu() {
        ToggleGroup group = new ToggleGroup();

        return new Menu("视图", null,
                actions.menuItem("sidebar.toggle"),
                new SeparatorMenuItem(),
                actions.menuItem("theme.toggle"),
                actions.radioMenuItem("density-compact", group),
                actions.radioMenuItem("density-normal", group),
                actions.radioMenuItem("density-comfort", group),
                new SeparatorMenuItem(),
                actions.menuItem("window.zoom"));
    }

    private Menu databaseMenu() {
        return new Menu("数据库", null,
                actions.menuItem("refresh"),
                // 4.5-10：以前只注册、没有任何菜单入口。
                actions.menuItem("connection.reconnect"),
                actions.menuItem("demo.open"),
                actions.menuItem("new-query"),
                new SeparatorMenuItem(),
                actions.menuItem("query.run"),
                actions.menuItem("query.run-all"),
                actions.menuItem("query.cancel"),
                actions.menuItem("query.explain"),
                new SeparatorMenuItem(),
                actions.menuItem("query.format"),
                actions.menuItem("query.save"));
    }

    private Menu toolsMenu() {
        return new Menu("工具", null,
                actions.menuItem("diagnostics"),
                actions.menuItem("import-export"),
                new SeparatorMenuItem(),
                actions.menuItem("settings"));
    }

    private Menu windowMenu() {
        return new Menu("窗口", null,
                actions.menuItem("window.minimize"),
                actions.menuItem("window.zoom"),
                new SeparatorMenuItem(),
                actions.menuItem("window.close"));
    }

    private Menu helpMenu() {
        return new Menu("帮助", null,
                actions.menuItem("shortcuts"),
                new SeparatorMenuItem(),
                actions.menuItem("about"));
    }
}
