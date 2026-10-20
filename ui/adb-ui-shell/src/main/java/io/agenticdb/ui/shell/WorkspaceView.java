package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 内容区。GRID 页每个标签独立实例并缓存，切换标签不会重建表格、不会丢分页位置。
 */
public final class WorkspaceView extends StackPane {

    private final ActionRegistry actions;
    private final String connectionName;
    private final Function<TabItem, Node> gridPageFactory;
    private final Map<String, DataGridPage> gridPages = new LinkedHashMap<>();

    private DataGridPage activeGrid;

    public WorkspaceView(ActionRegistry actions, String connectionName,
                         Function<TabItem, Node> gridPageFactory) {
        this.actions = actions;
        this.connectionName = connectionName;
        this.gridPageFactory = gridPageFactory;
        getStyleClass().add("adb-workspace");
        showWelcome();
    }

    public void showWelcome() {
        activeGrid = null;

        Label logo = new Label("◈");
        logo.getStyleClass().add("adb-welcome__logo");

        Label title = new Label("AgenticDB");
        title.getStyleClass().add("adb-welcome__title");

        Label subtitle = new Label("原生数据库工作台 · SQLite 演示库已连接");
        subtitle.getStyleClass().add("adb-welcome__subtitle");

        Label hint = new Label("从左侧双击一张表，或按 ⇧⌘N 新建查询");
        hint.getStyleClass().add("adb-muted");

        Button connect = new Button("新建连接");
        connect.getStyleClass().addAll("adb-button", "adb-button--primary");
        connect.setOnAction(event -> actions.run("new-connection"));

        Button palette = new Button("命令面板  ⌘K");
        palette.getStyleClass().addAll("adb-button", "adb-button--ghost");
        palette.setOnAction(event -> actions.run("command-palette"));

        HBox buttons = new HBox(8, connect, palette);
        buttons.setAlignment(Pos.CENTER);

        VBox card = new VBox(12, logo, title, subtitle, hint, buttons);
        card.getStyleClass().add("adb-welcome");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(520);

        getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.CENTER);
    }

    public void showTab(TabItem tab) {
        if (tab.kind() == TabKind.GRID) {
            DataGridPage page = gridPages.get(tab.id());
            if (page == null) {
                Node created = gridPageFactory.apply(tab);
                if (created instanceof DataGridPage gridPage) {
                    page = gridPage;
                    gridPages.put(tab.id(), page);
                } else if (created != null) {
                    getChildren().setAll(created);
                    activeGrid = null;
                    return;
                }
            }
            if (page != null) {
                activeGrid = page;
                getChildren().setAll(page);
                page.activate();
                return;
            }
        }
        activeGrid = null;
        getChildren().setAll(buildStaticPage(tab));
    }

    /** 标签关闭时释放页面与在途查询；同一页可能还有另一个标签引用，所以按 id 精确移除。 */
    public void closeTab(String id) {
        DataGridPage page = gridPages.remove(id);
        if (page != null) {
            if (page == activeGrid) {
                activeGrid = null;
            }
            page.close();
        }
    }

    public boolean hasDirtyChanges(String id) {
        return dirtyCount(id) > 0;
    }

    public int dirtyCount(String id) {
        DataGridPage page = gridPages.get(id);
        return page == null ? 0 : page.dirtyCount();
    }

    /** F5：只刷新当前数据页；静态页返回 false，由外壳给通用提示。 */
    public boolean refreshActive() {
        if (activeGrid == null) {
            return false;
        }
        activeGrid.refresh();
        return true;
    }

    private VBox buildStaticPage(TabItem tab) {
        VBox page = new VBox();
        page.getStyleClass().add("adb-page");

        HBox header = new HBox(10);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(tab.title());
        title.getStyleClass().add("adb-page__title");

        Label badge = new Label(tab.kind().label());
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label context = new Label(switch (tab.kind()) {
            case GRID -> connectionName + " · 只读";
            case QUERY -> "未执行";
            case STRUCTURE -> connectionName;
            case TOOL -> "工具";
            case SETTINGS -> "本地设置";
        });
        context.getStyleClass().add("adb-muted");

        header.getChildren().addAll(title, badge, spacer, context);

        VBox body = new VBox(14);
        body.getStyleClass().add("adb-page__body");
        body.setAlignment(Pos.TOP_CENTER);

        switch (tab.kind()) {
            case QUERY -> body.getChildren().add(emptyState(
                    "⌨", "SQL 编辑器尚未接入",
                    "P3 会在这里挂 RichTextFX、执行计划与结果表格。"));
            case STRUCTURE -> body.getChildren().add(emptyState(
                    "⚙", "结构页尚未接入",
                    "P4 会在这里显示列、索引、外键、DDL 与危险操作确认。"));
            case TOOL -> body.getChildren().add(emptyState(
                    "◆", tab.title(),
                    "该工具只保留入口；功能按 NAVICAT-TODO 的 P5/P6 接入。"));
            case SETTINGS -> body.getChildren().add(settingsSkeleton());
            default -> body.getChildren().add(emptyState("◇", tab.title(), ""));
        }

        page.getChildren().addAll(header, new Separator(), body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return page;
    }

    private VBox settingsSkeleton() {
        Label theme = new Label("外观");
        theme.getStyleClass().add("adb-section-title");

        Label mode = new Label("主题跟随系统，可在工具栏 ◐ 切换（⌘K 搜索「主题」也可）");
        mode.getStyleClass().add("adb-muted");

        Button toggle = new Button("切换主题");
        toggle.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toggle.setOnAction(event -> actions.run("theme.toggle"));

        Button palette = new Button("打开命令面板");
        palette.getStyleClass().addAll("adb-button", "adb-button--ghost");
        palette.setOnAction(event -> actions.run("command-palette"));

        VBox card = new VBox(10, theme, mode, toggle, palette);
        card.getStyleClass().add("adb-card");
        card.setMaxWidth(520);
        return card;
    }

    private VBox emptyState(String iconText, String titleText, String description) {
        Label icon = new Label(iconText);
        icon.getStyleClass().add("adb-empty__icon");

        Label title = new Label(titleText);
        title.getStyleClass().add("adb-empty__title");

        Label desc = new Label(description);
        desc.getStyleClass().add("adb-muted");
        desc.setWrapText(true);
        desc.setMaxWidth(460);

        VBox box = new VBox(10, icon, title, desc);
        box.getStyleClass().add("adb-empty");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48));
        return box;
    }
}
