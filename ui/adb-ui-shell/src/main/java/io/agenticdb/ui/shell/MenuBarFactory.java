package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

/**
 * ⭐ R34 N2：顶级菜单组改成 Navicat 的集合。
 *
 * <p>Navicat 17 实拍的九组是 {@code File / Edit / View / Query / Format / Favorites /
 * Tools / Window / Help}。旧版这里是 {@code 文件/编辑/视图/数据库/对象/收藏夹/工具/窗口/帮助}
 * —— 自有的「数据库」「对象」两组在 Navicat 里不存在。
 *
 * <h2>为什么还多一个 {@code Table}</h2>
 * Navicat 的 {@code Table} 菜单是**随活动页签出现**的：查询页上下文里没有它（实拍正是如此），
 * 打开一张表之后才有。所以这里建了 {@code Table} 菜单但默认隐藏，由
 * {@code AppShell.showTab} 在激活表类页签时显出来 —— {@link #tableMenu()} 就是那个开关的把手。
 *
 * <h2>旧版 X5 的教训仍然守着</h2>
 * 对象动作以前**只存在于树右键菜单**，用户根本不知道有「复制 SELECT」这类能力。
 * 现在它们有 {@code Table} 菜单这个固定去处；{@code Table} 菜单藏起来的时刻，
 * 恰好是没有表在上的时刻 —— 那时这些动作本来也是灰的，
 * 所以藏掉比摆一排灰项更诚实。建新表不依赖活动表，它另有 Ribbon 与表清单工具条两个入口。
 *
 * <p>菜单项全部来自 {@link ActionRegistry}，不在这里写业务。
 * macOS 上走系统菜单栏（{@code setUseSystemMenuBar(true)}）。
 */
public final class MenuBarFactory {

    private final ActionRegistry actions;
    /** 只在表类页签激活时显出来；见类注释「为什么还多一个 Table」。 */
    private Menu tableMenu;

    public MenuBarFactory(ActionRegistry actions) {
        this.actions = actions;
    }

    public MenuBar build() {
        MenuBar bar = new MenuBar();
        bar.setUseSystemMenuBar(true);
        bar.getMenus().addAll(
                fileMenu(), editMenu(), viewMenu(), buildTableMenu(), queryMenu(), formatMenu(),
                favoritesMenu(), toolsMenu(), windowMenu(), helpMenu());
        return bar;
    }

    /**
     * 表级动作菜单。默认隐藏 —— {@code AppShell.showTab} 在激活
     * {@code TableListPage} 之类的表页签时把它显出来。
     */
    public Menu tableMenu() {
        return tableMenu;
    }

    private Menu fileMenu() {
        return new Menu(I18n.t("menu.file"), null,
                actions.menuItem("new-connection"),
                actions.menuItem("connections.manage"),
                new SeparatorMenuItem(),
                actions.menuItem("new-query"),
                actions.menuItem("open-sql"),
                actions.menuItem("demo.open"),
                new SeparatorMenuItem(),
                actions.menuItem("connection.reconnect"),
                actions.menuItem("connection.disconnect"),
                new SeparatorMenuItem(),
                actions.menuItem("tab.close"),
                actions.menuItem("window.close"),
                new SeparatorMenuItem(),
                actions.menuItem("app.quit"));
    }

    private Menu editMenu() {
        // 撤销 / 重做 / 复制委托给当前活动页；剪切 / 粘贴在数据库客户端没有语义，
        // 按「占位要么接要么删」删除，不留永久禁用项。
        return new Menu(I18n.t("menu.edit"), null,
                actions.menuItem("edit.undo"),
                actions.menuItem("edit.redo"),
                new SeparatorMenuItem(),
                actions.menuItem("edit.copy"),
                new SeparatorMenuItem(),
                actions.menuItem("focus-search"),
                actions.menuItem("command-palette"));
    }

    private Menu viewMenu() {
        ToggleGroup group = new ToggleGroup();

        return new Menu(I18n.t("menu.view"), null,
                actions.menuItem("sidebar.toggle"),
                actions.menuItem("details.toggle"),
                new SeparatorMenuItem(),
                actions.menuItem("theme.toggle"),
                actions.radioMenuItem("density-dense", group),
                actions.radioMenuItem("density-compact", group),
                actions.radioMenuItem("density-normal", group),
                actions.radioMenuItem("density-comfort", group),
                new SeparatorMenuItem(),
                actions.menuItem("window.zoom"));
    }

    private Menu buildTableMenu() {
        tableMenu = new Menu(I18n.t("menu.table"), null,
                actions.menuItem("object.open-data"),
                actions.menuItem("object.open-structure"),
                actions.menuItem("object.new-query"),
                new SeparatorMenuItem(),
                actions.menuItem("object.new-table"),
                // ⭐ R42 N28-a：视图三项与表四项同列 —— 右键能做的事，
                //    菜单栏不能看不到（X5 那条「孤儿动作」的教训就在下面这行注释里）。
                //    选中表时这三项是灰的，选中视图时上面表那三项是灰的：
                //    enabled 状态统一由 SideBar.updateObjectActionStates 按节点类型给。
                actions.menuItem("object.new-view"),
                actions.menuItem("object.truncate-table"),
                actions.menuItem("object.rename-table"),
                actions.menuItem("object.drop-table"),
                actions.menuItem("object.design-view"),
                actions.menuItem("object.drop-view"),
                new SeparatorMenuItem(),
                actions.menuItem("object.favorite"),
                actions.menuItem("object.copy-name"),
                actions.menuItem("object.copy-select"),
                new SeparatorMenuItem(),
                actions.menuItem("object.refresh-objects"),
                actions.menuItem("refresh"));
        // 默认隐藏：没有表在上时摆一排灰项，不如不摆。
        tableMenu.setVisible(false);
        return tableMenu;
    }

    private Menu queryMenu() {
        return new Menu(I18n.t("menu.query"), null,
                actions.menuItem("query.run"),
                actions.menuItem("query.run-all"),
                actions.menuItem("query.cancel"),
                new SeparatorMenuItem(),
                actions.menuItem("query.explain"),
                new SeparatorMenuItem(),
                actions.menuItem("query.save"));
    }

    /** Navicat 的 {@code Format} 是独立顶级组 —— 排版类动作不散在编辑/数据库里。 */
    private Menu formatMenu() {
        return new Menu(I18n.t("menu.format"), null,
                actions.menuItem("query.format"),
                actions.menuItem("query.comment"),
                new SeparatorMenuItem(),
                actions.menuItem("query.save-snippet"),
                actions.menuItem("query.insert-snippet"));
    }

    private Menu favoritesMenu() {
        return new Menu(I18n.t("menu.favorites"), null,
                actions.menuItem("favorites.open"),
                new SeparatorMenuItem(),
                actions.menuItem("favorites.clear"));
    }

    private Menu toolsMenu() {
        return new Menu(I18n.t("menu.tools"), null,
                actions.menuItem("object.tables"),
                actions.menuItem("query-history"),
                actions.menuItem("diagnostics"),
                actions.menuItem("import-export"),
                new SeparatorMenuItem(),
                actions.menuItem("settings"));
    }

    private Menu windowMenu() {
        return new Menu(I18n.t("menu.window"), null,
                actions.menuItem("window.new"),
                new SeparatorMenuItem(),
                actions.menuItem("window.minimize"),
                actions.menuItem("window.zoom"),
                new SeparatorMenuItem(),
                actions.menuItem("window.close"));
    }

    private Menu helpMenu() {
        return new Menu(I18n.t("menu.help"), null,
                actions.menuItem("shortcuts"),
                new SeparatorMenuItem(),
                actions.menuItem("about"));
    }
}
