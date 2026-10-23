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
        // ⭐ X5：顶级菜单加「对象」—— Navicat 就有（表/对象/函数…）。
        //    以前对象动作**只在对象树右键菜单里**，命令面板又因为「未选中就禁用」
        //    把它们藏掉了 —— 用户根本不知道「复制 SELECT」这类能力存在。
        bar.getMenus().addAll(
                fileMenu(), editMenu(), viewMenu(),
                databaseMenu(), objectMenu(), favoritesMenu(), toolsMenu(), windowMenu(), helpMenu());
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
                actions.menuItem("details.toggle"),
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
                actions.menuItem("connection.disconnect"),
                actions.menuItem("demo.open"),
                actions.menuItem("new-query"),
                new SeparatorMenuItem(),
                // 4.5-10 的教训：注册了却没有任何菜单入口的动作等于孤儿。
                // T1–T4 建表 / 清空 / 重命名 / 删表在「数据库」菜单里要有真入口。
                actions.menuItem("object.new-table"),
                actions.menuItem("object.truncate-table"),
                actions.menuItem("object.rename-table"),
                actions.menuItem("object.drop-table"),
                new SeparatorMenuItem(),
                actions.menuItem("query.run"),
                actions.menuItem("query.run-all"),
                actions.menuItem("query.cancel"),
                actions.menuItem("query.explain"),
                new SeparatorMenuItem(),
                actions.menuItem("query.format"),
                actions.menuItem("query.save"),
                new SeparatorMenuItem(),
                // ⭐ Q6：代码片段库
                actions.menuItem("query.save-snippet"),
                actions.menuItem("query.insert-snippet"));
    }

    /**
     * ⭐ X5：「对象」顶级菜单 —— 对象树的动作在这里也有入口。
     *
     * <p>动作的 enabled 状态仍由「当前选中的树节点」决定（没选中就是灰的），
     * 但**它们在菜单里始终可见** —— 用户能知道有哪些能力。
     */
    private Menu objectMenu() {
        return new Menu("对象", null,
                // ⭐ 表清单以前只能靠 ⌘K 或动作 id 打开 —— 菜单里根本找不到。
                //    这是**可发现性 bug**：功能存在≠用户找得到。
                actions.menuItem("object.tables"),
                new SeparatorMenuItem(),
                actions.menuItem("object.open-data"),
                actions.menuItem("object.open-structure"),
                actions.menuItem("object.new-query"),
                new SeparatorMenuItem(),
                actions.menuItem("object.new-table"),
                actions.menuItem("object.truncate-table"),
                actions.menuItem("object.rename-table"),
                actions.menuItem("object.drop-table"),
                new SeparatorMenuItem(),
                actions.menuItem("object.favorite"),
                actions.menuItem("object.copy-name"),
                actions.menuItem("object.copy-select"),
                new SeparatorMenuItem(),
                actions.menuItem("object.refresh-objects"));
    }

    /**
     * ⭐ S6：独立的「收藏夹」顶级菜单（Navicat 的菜单栏就是
     * 文件 / 编辑 / 查看 / 表 / **收藏夹** / 工具 / 窗口 / 帮助）。
     */
    private Menu favoritesMenu() {
        // ⭐ X6：以前只有 1 项（顶级菜单资源被浪费）—— 补上「清空收藏」。
        return new Menu("收藏夹", null,
                actions.menuItem("favorites.open"),
                new SeparatorMenuItem(),
                actions.menuItem("favorites.clear"));
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
