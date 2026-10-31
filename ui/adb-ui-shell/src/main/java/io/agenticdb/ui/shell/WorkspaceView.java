package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

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
    private final Function<TabItem, QueryPage> queryPageFactory;
    private final Function<TabItem, StructurePage> structurePageFactory;
    private final Function<TabItem, ConnectionsPage> connectionsPageFactory;
    /** ⭐ S5：常驻的「对象」首页。 */
    private final Function<TabItem, Node> objectHomeFactory;
    private final Map<String, DataGridPage> gridPages = new LinkedHashMap<>();
    private final Map<String, QueryPage> queryPages = new LinkedHashMap<>();
    private final Map<String, StructurePage> structurePages = new LinkedHashMap<>();
    private final Map<String, ConnectionsPage> connectionsPages = new LinkedHashMap<>();
    /** 表清单页（自己带内容的标签页，按 tab id 缓存，好让密度能下发给它）。 */
    private final Map<String, TableListPage> tableListPages = new LinkedHashMap<>();

    private DataGridPage activeGrid;
    private QueryPage activeQuery;
    private StructurePage activeStructure;
    private ConnectionsPage activeConnections;
    /** ⭐ N33：常驻「对象」标签现在托管表清单页；记住它，切连接后能重新渲染。 */
    private TabItem activeObjectTab;
    private TableListPage activeObjectPage;

    /** 查询历史（真实功能，只是以前侧栏按钮开错了页面）。 */
    private QueryHistory history;
    private java.util.function.Consumer<String> onUseHistory;

    /** 诊断页需要的依赖。 */
    private io.agenticdb.jdbc.DbSession diagnosticSession;
    private io.agenticdb.introspect.SchemaService diagnosticSchemaService;
    private java.util.concurrent.Executor diagnosticExecutor;

    /** 当前活动数据页（编辑菜单据此启用/禁用）；不是数据页时为 {@code null}。 */
    public DataGridPage activeGrid() {
        return activeGrid;
    }

    /**
     * 当前活动的结构页；不是结构页时为 {@code null}。
     *
     * <p>详情面板的「打开 DDL」要用它 —— 打开结构标签后直接把
     * 子页签切到 DDL（规格 §11.1），而不是另开一个只读文本框。
     */
    public StructurePage activeStructure() {
        return activeStructure;
    }

    /**
     * 把密度档位应用到所有已打开的数据页（A19）。
     *
     * <p>以前密度只改对象树的行内边距，<b>数据网格行高纹丝不动</b> ——
     * 「紧凑 / 标准 / 宽松」对最费屏的地方不起作用。
     */
    public void applyDensity(String density) {
        for (DataGridPage page : gridPages.values()) {
            page.setDensity(density);
        }
        for (QueryPage page : queryPages.values()) {
            page.setDensity(density);
        }
        for (StructurePage page : structurePages.values()) {
            page.setDensity(density);
        }
        for (ConnectionsPage page : connectionsPages.values()) {
            page.setDensity(density);
        }
        for (TableListPage page : tableListPages.values()) {
            page.setDensity(density);
        }
    }

    public WorkspaceView(ActionRegistry actions, String connectionName,
                         Function<TabItem, Node> gridPageFactory,
                         Function<TabItem, QueryPage> queryPageFactory,
                         Function<TabItem, StructurePage> structurePageFactory,
                         Function<TabItem, ConnectionsPage> connectionsPageFactory,
                         Function<TabItem, Node> objectHomeFactory) {
        this.actions = actions;
        this.connectionName = connectionName;
        this.gridPageFactory = gridPageFactory;
        this.queryPageFactory = queryPageFactory;
        this.structurePageFactory = structurePageFactory;
        this.connectionsPageFactory = connectionsPageFactory;
        this.objectHomeFactory = objectHomeFactory;
        getStyleClass().add("adb-workspace");
        // ⭐ S-7：这里原来会画一张欢迎页，然后**立刻被对象首页盖掉** ——
        //    AppShell 无条件 openPermanent(object-home)，欢迎页永远没人看得到。
        //    死代码删掉，初始状态就是空的，等第一次 showTab 来填。
    }

    /**
     * 接入查询历史。
     *
     * <p>侧栏「查询历史」入口以前开的是空壳页，而 {@link QueryHistory} 早就存在且可用 ——
     * 现在把真功能接上去。
     */
    public void setHistory(QueryHistory history, java.util.function.Consumer<String> onUse) {
        this.history = history;
        this.onUseHistory = onUse;
    }

    /** 接入诊断页的依赖（表结构 + 执行器）。 */
    public void setDiagnostics(io.agenticdb.jdbc.DbSession session,
                               io.agenticdb.introspect.SchemaService schemaService,
                               java.util.concurrent.Executor executor) {
        this.diagnosticSession = session;
        this.diagnosticSchemaService = schemaService;
        this.diagnosticExecutor = executor;
    }

    public void showTab(TabItem tab) {
        if (tab.kind() == TabKind.OBJECT) {
            // ⭐ N33：常驻的「对象」标签托管表清单页（Navicat 的「对象」同形）。
            Node page = objectHomeFactory.apply(tab);
            activeGrid = null;
            activeQuery = null;
            activeStructure = null;
            activeConnections = null;
            activeObjectTab = tab;
            if (page instanceof TableListPage tableList) {
                activeObjectPage = tableList;
                // 缓存一份，密度与「建表后重读」都靠它找到页面。
                tableListPages.put(tab.id(), tableList);
            } else {
                activeObjectPage = null;
                tableListPages.remove(tab.id());
            }
            if (page != null) {
                getChildren().setAll(page);
            }
            return;
        }
        activeObjectTab = null;
        activeObjectPage = null;
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
                activeQuery = null;
                getChildren().setAll(page);
                page.activate();
                return;
            }
        }
        if (tab.kind() == TabKind.QUERY) {
            QueryPage page = queryPages.get(tab.id());
            if (page == null) {
                page = queryPageFactory.apply(tab);
                if (page == null) {
                    activeGrid = null;
                    activeQuery = null;
                    getChildren().setAll(buildStaticPage(tab));
                    return;
                }
                queryPages.put(tab.id(), page);
            }
            activeGrid = null;
            activeQuery = page;
            getChildren().setAll(page);
            page.activate();
            return;
        }
        if (tab.kind() == TabKind.CONNECTIONS) {
            ConnectionsPage page = connectionsPages.get(tab.id());
            if (page == null) {
                page = connectionsPageFactory.apply(tab);
                if (page == null) {
                    activeGrid = null;
                    activeQuery = null;
                    activeStructure = null;
                    getChildren().setAll(buildStaticPage(tab));
                    return;
                }
                connectionsPages.put(tab.id(), page);
            }
            activeGrid = null;
            activeQuery = null;
            activeStructure = null;
            activeConnections = page;
            getChildren().setAll(page);
            return;
        }
        if (tab.kind() == TabKind.STRUCTURE) {
            StructurePage page = structurePages.get(tab.id());
            if (page == null) {
                page = structurePageFactory.apply(tab);
                if (page == null) {
                    activeGrid = null;
                    activeQuery = null;
                    activeStructure = null;
                    getChildren().setAll(buildStaticPage(tab));
                    return;
                }
                structurePages.put(tab.id(), page);
            }
            activeGrid = null;
            activeQuery = null;
            activeStructure = page;
            activeConnections = null;
            getChildren().setAll(page);
            return;
        }
        activeGrid = null;
        activeQuery = null;
        activeStructure = null;
        activeConnections = null;
        // ⭐ 标签自带页面（表清单）时直接用它，不再走静态占位页。
        if (tab.view() instanceof TableListPage tableList) {
            // ⭐ 缓存一份，密度才能下发给它（表清单页是「自己带内容」的标签页）。
            tableListPages.put(tab.id(), tableList);
        }
        getChildren().setAll(tab.view() != null ? tab.view() : buildStaticPage(tab));
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
        QueryPage query = queryPages.remove(id);
        if (query != null) {
            if (query == activeQuery) {
                activeQuery = null;
            }
            query.close();
        }
        StructurePage structure = structurePages.remove(id);
        if (structure != null) {
            if (structure == activeStructure) {
                activeStructure = null;
            }
            structure.close();
        }
        ConnectionsPage connections = connectionsPages.remove(id);
        if (connections != null) {
            connections.close();
        }
        // 表清单页没有 close()，但缓存必须一起移除 —— 否则关掉的标签页会永远留在
        // applyDensity 的遍历里（关 20 个表清单就多 20 个对象）。
        tableListPages.remove(id);
    }

    public boolean hasDirtyChanges(String id) {
        return dirtyCount(id) > 0;
    }

    public int dirtyCount(String id) {
        DataGridPage page = gridPages.get(id);
        if (page != null) {
            return page.dirtyCount();
        }
        QueryPage query = queryPages.get(id);
        return query == null ? 0 : query.dirtyCount();
    }

    /** F5：刷新当前数据页，或重新执行当前查询页的光标语句。 */
    public boolean refreshActive() {
        if (activeGrid != null) {
            activeGrid.refresh();
            return true;
        }
        if (activeQuery != null) {
            activeQuery.runCurrent();
            return true;
        }
        if (activeStructure != null) {
            activeStructure.reload();
            return true;
        }
        if (activeConnections != null) {
            activeConnections.refresh();
            return true;
        }
        return false;
    }

    /** 结构变更后：刷新所有已打开的数据页与结构页。 */
    public void refreshAll() {
        for (DataGridPage page : gridPages.values()) {
            page.refresh();
        }
        for (StructurePage page : structurePages.values()) {
            page.reload();
        }
    }

    /** ⭐ N1：建表 / 删表 / 改名后，已打开的表清单页要重新统计。 */
    public void refreshTableLists() {
        for (TableListPage page : tableListPages.values()) {
            page.reload();
        }
    }

    /**
     * 某个标签（含常驻「对象」标签）托管的表清单页；没有则 {@code null}。
     *
     * <p>{@code AppShell.showTab} 靠它把「活动表清单页」从常驻 OBJECT 标签上取回 ——
     * TabItem 自身没有 view，只看 {@code tab.view()} 会漏掉整张对象页。
     */
    public TableListPage tableListPage(String id) {
        return tableListPages.get(id);
    }

    /**
     * ⭐ N33 / 连接切换：重新渲染常驻「对象」标签。
     *
     * <p>对象页按连接构建 —— 切库 / 重连后必须再走一次工厂，
     * 否则清单还挂在旧会话上（读的是旧库的表）。
     */
    public void refreshObjectPage() {
        if (activeObjectTab != null) {
            showTab(activeObjectTab);
        }
    }

    /** ⌘F：查询页优先打开自己的查找栏。 */
    public boolean focusSearch() {
        if (activeQuery != null) {
            activeQuery.focusSearch();
            return true;
        }
        return false;
    }

    public boolean runQuery() {
        if (activeQuery == null) {
            return false;
        }
        activeQuery.runCurrent();
        return true;
    }

    public boolean runAllQuery() {
        if (activeQuery == null) {
            return false;
        }
        activeQuery.runAll();
        return true;
    }

    /**
     * 取消当前正在跑的操作（4.5-13）。
     *
     * <p>以前只取消查询页；数据页的加载 / 保存不会被 {@code ⌘.} 打断。
     * 现在两边都覆盖。
     */
    public boolean cancelQuery() {
        boolean cancelled = false;
        if (activeQuery != null && activeQuery.isRunning()) {
            activeQuery.cancel();
            cancelled = true;
        }
        if (activeGrid != null && activeGrid.isBusy()) {
            activeGrid.cancel();
            cancelled = true;
        }
        return cancelled;
    }

    public boolean explainQuery() {
        if (activeQuery == null) {
            return false;
        }
        activeQuery.explainCurrent();
        return true;
    }

    public boolean formatQuery() {
        if (activeQuery == null) {
            return false;
        }
        activeQuery.formatSql();
        return true;
    }

    public boolean toggleQueryComment() {
        if (activeQuery == null) {
            return false;
        }
        activeQuery.toggleComment();
        return true;
    }

    public boolean saveQuery() {
        if (activeQuery == null) {
            return false;
        }
        activeQuery.save();
        return true;
    }

    public QueryPage activeQuery() {
        return activeQuery;
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
        // ⚠️ 标题与徽标同文时（「设置 设置」）徽标是纯噪音 —— 藏掉。
        if (tab.kind().label() != null && tab.kind().label().equals(tab.title())) {
            badge.setVisible(false);
            badge.setManaged(false);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label context = new Label(switch (tab.kind()) {
            case GRID -> I18n.t("workspace.context.grid", connectionName);
            case QUERY -> I18n.t("workspace.context.notRun");
            case STRUCTURE -> connectionName;
            case CONNECTIONS -> I18n.t("workspace.context.connections");
            case TOOL -> I18n.t("category.tools");
            case SETTINGS -> I18n.t("workspace.context.settings");
            case OBJECT -> connectionName;
            case ROUTINE -> I18n.t("workspace.context.routine", connectionName);
        });
        context.getStyleClass().add("adb-muted");
        // ⚠️ 徽标与右侧说明同文时（「工具」/「工具」）右侧那份是纯噪音 —— 藏掉。
        if (tab.kind().label() != null && tab.kind().label().equals(context.getText())) {
            context.setVisible(false);
            context.setManaged(false);
        }

        header.getChildren().addAll(title, badge, spacer, context);

        VBox body = new VBox(14);
        body.getStyleClass().add("adb-page__body");
        body.setAlignment(Pos.TOP_CENTER);

        switch (tab.kind()) {
            case QUERY -> body.getChildren().add(emptyState(
                    Icons.Icon.QUERY, I18n.t("workspace.empty.query.title"),
                    I18n.t("workspace.empty.query.body")));
            case STRUCTURE -> body.getChildren().add(emptyState(
                    Icons.Icon.STRUCTURE, I18n.t("workspace.empty.structure.title"),
                    I18n.t("workspace.empty.structure.body")));
            case CONNECTIONS -> body.getChildren().add(emptyState(
                    Icons.Icon.CONNECT, I18n.t("workspace.empty.connections.title"),
                    I18n.t("workspace.empty.connections.body")));
            case TOOL -> {
                // ⭐ 查询历史与诊断有真功能可接，不再是空壳。
                // ⚠️ 路由看 {@link TabItem#target()}，**不看标题** —— 以前这里判
                //    {@code title.startsWith("诊断")} 再从标题里切表名，于是 R34 第八批
                //    把标题换进资源包之后，两页都会静默退回下面那个「只保留入口」的空壳。
                //    键由 {@link SideBar} 公开，拼错编译不过（见 SideBar.TOOL_*）。
                String route = tab.target() == null ? "" : tab.target();
                if (SideBar.TOOL_HISTORY.equals(route) && history != null) {
                    body.getChildren().add(new HistoryPage(history, onUseHistory));
                } else if (route.startsWith(SideBar.TOOL_DIAGNOSTICS)
                        && diagnosticSession != null) {
                    String table = route.substring(SideBar.TOOL_DIAGNOSTICS.length()).trim();
                    body.getChildren().add(new DiagnosticsPage(diagnosticSession,
                            diagnosticSchemaService, table, diagnosticExecutor));
                } else if (SideBar.TOOL_OBJECT_ROUTES.contains(route)) {
                    // ⭐ N33：视图 / 函数的**编辑器**还没建，而对象清单页只列表 ——
                    // 按诚实条款给如实说明页，并说清去哪儿看它们（左侧树）。
                    body.getChildren().add(emptyState(
                            Icons.Icon.STRUCTURE, tab.title(),
                            I18n.t("workspace.tool.objectKind")));
                } else if (SideBar.TOOL_UNAVAILABLE_ROUTES.contains(route)) {
                    // ⭐ R36 N6：Ribbon 的 User/Backup/Automation/Model/BI 五格 ——
                    // 能力面（N28）没建，按诚实条款给**如实说明页**，不留死磁贴。
                    body.getChildren().add(emptyState(
                            Icons.Icon.DIAGNOSTICS, tab.title(),
                            I18n.t("workspace.tool.unavailable")));
                } else {
                    body.getChildren().add(emptyState(
                            Icons.Icon.DIAGNOSTICS, tab.title(),
                            I18n.t("workspace.tool.stub")));
                }
            }
            case SETTINGS -> body.getChildren().add(settingsSkeleton());
            default -> body.getChildren().add(emptyState(Icons.Icon.VIEW_OBJECT,
                    tab.title(), ""));
        }

        page.getChildren().addAll(header, new Separator(), body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return page;
    }

    private VBox settingsSkeleton() {
        Label theme = new Label(I18n.t("workspace.settings.appearance"));
        theme.getStyleClass().add("adb-section-title");

        Label mode = new Label(I18n.t("workspace.settings.modeHint",
                io.agenticdb.ui.Shortcuts.of("Mod+K")));
        mode.getStyleClass().add("adb-muted");

        Button toggle = IconButtons.of(Icons.Icon.THEME, I18n.t("action.theme.toggle"));
        toggle.getStyleClass().addAll("adb-button");
        toggle.setOnAction(event -> actions.run("theme.toggle"));

        // ⭐ 密度也是**真实设置**（行高令牌），放在设置页比只留在菜单里好找。
        Label density = new Label(I18n.t("workspace.settings.density"));
        density.getStyleClass().add("adb-section-title");

        Label densityHint = new Label(I18n.t("workspace.settings.densityHint"));
        densityHint.getStyleClass().add("adb-muted");

        HBox densityRow = new HBox(6);
        densityRow.getStyleClass().add("adb-toolbar__group");
        densityRow.getChildren().addAll(
                densityButton("density-compact", I18n.t("action.density-compact")),
                densityButton("density-normal", I18n.t("action.density-normal")),
                densityButton("density-comfort", I18n.t("action.density-comfort")));

        Label shortcuts = new Label(I18n.t("shortcut.title"));
        shortcuts.getStyleClass().add("adb-section-title");

        Button palette = IconButtons.of(Icons.Icon.PALETTE, I18n.t("workspace.settings.openPalette"));
        palette.getStyleClass().addAll("adb-button");
        palette.setOnAction(event -> actions.run("command-palette"));

        Button sheet = IconButtons.of(Icons.Icon.HISTORY, I18n.t("workspace.settings.shortcutSheet"));
        sheet.getStyleClass().addAll("adb-button");
        sheet.setOnAction(event -> actions.run("shortcuts"));

        VBox card = new VBox(10, theme, mode, toggle);
        card.getStyleClass().add("adb-card");
        card.setMaxWidth(520);

        VBox densityCard = new VBox(10, density, densityHint, densityRow);
        densityCard.getStyleClass().add("adb-card");
        densityCard.setMaxWidth(520);

        VBox shortcutCard = new VBox(10, shortcuts, palette, sheet);
        shortcutCard.getStyleClass().add("adb-card");
        shortcutCard.setMaxWidth(520);

        VBox all = new VBox(16, card, densityCard, shortcutCard);
        all.setAlignment(Pos.TOP_CENTER);
        return all;
    }

    /** 密度按钮：点击后由动作本身切换（ActionRegistry 是唯一真相）。 */
    private Button densityButton(String actionId, String label) {
        Button button = new Button(label);
        button.setId("settings-density-" + actionId.substring("density-".length()));
        button.getStyleClass().addAll("adb-button");
        button.setOnAction(event -> actions.run(actionId));
        return button;
    }

    private VBox emptyState(Icons.Icon iconType, String titleText, String description) {
        return emptyState(iconType, titleText, description, null, null);
    }

    /**
     * 空状态。
     *
     * <p>⭐ 带一个**明确的下一步按钮** —— 以前只有一段文字，
     * 用户看着空页面不知道能干什么。
     *
     * @param actionText 下一步按钮的文字（{@code null} = 不放按钮）
     * @param action     下一步按钮的动作（{@code null} = 不放按钮）
     */
    private VBox emptyState(Icons.Icon iconType, String titleText, String description,
                            String actionText, Runnable action) {
        SVGPath icon = Icons.of(iconType, Icons.SIZE_EMPTY);
        icon.getStyleClass().add("adb-empty__icon");

        Label title = new Label(titleText);
        title.getStyleClass().add("adb-empty__title");

        Label desc = new Label(description);
        desc.getStyleClass().add("adb-muted");
        desc.setWrapText(true);
        desc.setMaxWidth(460);

        VBox box = new VBox(10, icon, title, desc);
        if (actionText != null && action != null) {
            Button button = new Button(actionText);
            button.getStyleClass().addAll("adb-button", "adb-button--primary");
            button.setOnAction(event -> action.run());
            box.getChildren().add(button);
        }
        box.getStyleClass().add("adb-empty");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48));
        return box;
    }
}
