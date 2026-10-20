package io.agenticdb.ui.shell;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
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
                actions.menuItem("new-query"),
                actions.menuItem("open-sql"),
                new SeparatorMenuItem(),
                actions.menuItem("tab.close"),
                actions.menuItem("window.close"),
                new SeparatorMenuItem(),
                actions.menuItem("app.quit"));
    }

    private Menu editMenu() {
        return new Menu("编辑", null,
                disabled("撤销"),
                disabled("重做"),
                new SeparatorMenuItem(),
                disabled("剪切"),
                disabled("复制"),
                disabled("粘贴"),
                new SeparatorMenuItem(),
                actions.menuItem("focus-search"),
                actions.menuItem("command-palette"));
    }

    private Menu viewMenu() {
        Menu density = new Menu("密度");
        ToggleGroup group = new ToggleGroup();
        density.getItems().addAll(
                densityItem("紧凑", "density-compact", group),
                densityItem("标准", "density-normal", group),
                densityItem("宽松", "density-comfort", group));

        return new Menu("视图", null,
                actions.menuItem("sidebar.toggle"),
                new SeparatorMenuItem(),
                actions.menuItem("theme.toggle"),
                density,
                new SeparatorMenuItem(),
                actions.menuItem("window.zoom"));
    }

    private Menu databaseMenu() {
        return new Menu("数据库", null,
                actions.menuItem("refresh"),
                actions.menuItem("demo.open"),
                actions.menuItem("new-query"),
                new SeparatorMenuItem(),
                disabled("执行语句"),
                disabled("解释执行计划"));
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

    private RadioMenuItem densityItem(String label, String actionId, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(label);
        item.setToggleGroup(group);
        item.setOnAction(event -> actions.run(actionId));
        return item;
    }

    private static MenuItem disabled(String label) {
        MenuItem item = new MenuItem(label);
        item.setDisable(true);
        return item;
    }
}
