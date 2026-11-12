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
    /** ⭐ EXT（P1-5）：Redis 键浏览器页工厂（与 grid / object 同形，返回 Node）。 */
    private final Function<TabItem, Node> redisKeysPageFactory;
    /** ⭐ EXT（P2-8）：对象存储浏览器页工厂。 */
    private final Function<TabItem, Node> objectBrowserPageFactory;
    private final Map<String, DataGridPage> gridPages = new LinkedHashMap<>();
    private final Map<String, QueryPage> queryPages = new LinkedHashMap<>();
    private final Map<String, StructurePage> structurePages = new LinkedHashMap<>();
    private final Map<String, ConnectionsPage> connectionsPages = new LinkedHashMap<>();
    /** 表清单页（自己带内容的标签页，按 tab id 缓存，好让密度能下发给它）。 */
    private final Map<String, TableListPage> tableListPages = new LinkedHashMap<>();
    /** ⭐ EXT（P1-5）：Redis 键浏览器页缓存（按 tab id，密度下发 + 关闭释放都靠它）。 */
    private final Map<String, RedisKeysPage> redisKeysPages = new LinkedHashMap<>();
    /** ⭐ EXT（P2-8）：对象存储浏览器页缓存（同 RedisKeysPage 口径）。 */
    private final Map<String, ObjectBrowserPage> objectBrowserPages = new LinkedHashMap<>();

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

    /** Backup 工具页需要的依赖（与诊断页同源：当前活动连接 + 内省 + 执行器）。 */
    private io.agenticdb.jdbc.DbSession backupSession;
    private io.agenticdb.introspect.SchemaService backupSchemaService;
    private java.util.concurrent.Executor backupExecutor;

    /** User 只读视图需要的依赖（与诊断页同源）。 */
    private io.agenticdb.jdbc.DbSession usersSession;
    private io.agenticdb.introspect.SchemaService usersSchemaService;
    private java.util.concurrent.Executor usersExecutor;

    /** Data Profiling 工具页需要的依赖（与诊断页同源）。 */
    private io.agenticdb.jdbc.DbSession profilingSession;
    private io.agenticdb.introspect.SchemaService profilingSchemaService;
    private java.util.concurrent.Executor profilingExecutor;

    /** 跨库传输工具页需要的依赖：源连接会话 + 目标连接解析器 + 其它连接清单。 */
    private io.agenticdb.jdbc.DbSession transferSession;
    private io.agenticdb.introspect.SchemaService transferSchemaService;
    private java.util.concurrent.Executor transferExecutor;
    private java.util.List<io.agenticdb.session.ConnectionProfile> transferOthers = java.util.List.of();
    private java.util.function.Function<String, io.agenticdb.jdbc.DbSession> transferResolver =
            id -> null;

    /** ⭐ 2026-11-10：例程清单页的依赖（活动连接 + 内省 + 执行器）与「点开某例程」的回调。 */
    private io.agenticdb.jdbc.DbSession routinesSession;
    private io.agenticdb.introspect.SchemaService routinesSchemaService;
    private java.util.concurrent.Executor routinesExecutor;
    /** 点开某一行例程 → 外壳据此打开定义体标签页（名字 + 类型）。 */
    private java.util.function.BiConsumer<String, io.agenticdb.introspect.SchemaService.ObjectKind>
            routinesOpen = (name, kind) -> { };

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
        for (RedisKeysPage page : redisKeysPages.values()) {
            page.setDensity(density);
        }
        for (ObjectBrowserPage page : objectBrowserPages.values()) {
            page.setDensity(density);
        }
    }

    /** ⭐ 8 参兼容构造：既有的 38 项门禁仍按旧签名装配；对象存储工厂缺省为空。 */
    public WorkspaceView(ActionRegistry actions, String connectionName,
                         Function<TabItem, Node> gridPageFactory,
                         Function<TabItem, QueryPage> queryPageFactory,
                         Function<TabItem, StructurePage> structurePageFactory,
                         Function<TabItem, ConnectionsPage> connectionsPageFactory,
                         Function<TabItem, Node> objectHomeFactory,
                         Function<TabItem, Node> redisKeysPageFactory) {
        this(actions, connectionName, gridPageFactory, queryPageFactory,
                structurePageFactory, connectionsPageFactory, objectHomeFactory,
                redisKeysPageFactory, tab -> null);
    }

    /** ⭐ EXT（P2-8）：9 参构造 —— AppShell 注入对象存储浏览器页工厂。 */
    public WorkspaceView(ActionRegistry actions, String connectionName,
                         Function<TabItem, Node> gridPageFactory,
                         Function<TabItem, QueryPage> queryPageFactory,
                         Function<TabItem, StructurePage> structurePageFactory,
                         Function<TabItem, ConnectionsPage> connectionsPageFactory,
                         Function<TabItem, Node> objectHomeFactory,
                         Function<TabItem, Node> redisKeysPageFactory,
                         Function<TabItem, Node> objectBrowserPageFactory) {
        this.actions = actions;
        this.connectionName = connectionName;
        this.gridPageFactory = gridPageFactory;
        this.queryPageFactory = queryPageFactory;
        this.structurePageFactory = structurePageFactory;
        this.connectionsPageFactory = connectionsPageFactory;
        this.objectHomeFactory = objectHomeFactory;
        this.redisKeysPageFactory = redisKeysPageFactory;
        this.objectBrowserPageFactory = objectBrowserPageFactory == null
                ? tab -> null : objectBrowserPageFactory;
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

    /** 接入 Backup 工具页的依赖（活动连接 + 内省 + 执行器）。 */
    public void setBackup(io.agenticdb.jdbc.DbSession session,
                          io.agenticdb.introspect.SchemaService schemaService,
                          java.util.concurrent.Executor executor) {
        this.backupSession = session;
        this.backupSchemaService = schemaService;
        this.backupExecutor = executor;
    }

    /** 接入 User 只读视图的依赖（活动连接 + 内省 + 执行器）。 */
    public void setUsers(io.agenticdb.jdbc.DbSession session,
                         io.agenticdb.introspect.SchemaService schemaService,
                         java.util.concurrent.Executor executor) {
        this.usersSession = session;
        this.usersSchemaService = schemaService;
        this.usersExecutor = executor;
    }

    /** 接入 Data Profiling 工具页的依赖（活动连接 + 内省 + 执行器）。 */
    public void setProfiling(io.agenticdb.jdbc.DbSession session,
                             io.agenticdb.introspect.SchemaService schemaService,
                             java.util.concurrent.Executor executor) {
        this.profilingSession = session;
        this.profilingSchemaService = schemaService;
        this.profilingExecutor = executor;
    }

    /**
     * ⭐ 2026-11-10：接入例程清单页的依赖（活动连接 + 内省 + 执行器）
     * 与「点开某例程 → 打开定义体标签页」的回调。
     */
    public void setRoutines(io.agenticdb.jdbc.DbSession session,
                            io.agenticdb.introspect.SchemaService schemaService,
                            java.util.concurrent.Executor executor,
                            java.util.function.BiConsumer<String,
                                    io.agenticdb.introspect.SchemaService.ObjectKind> opener) {
        this.routinesSession = session;
        this.routinesSchemaService = schemaService;
        this.routinesExecutor = executor;
        this.routinesOpen = opener == null
                ? (name, kind) -> { } : opener;
    }

    /** 接入跨库传输工具页的依赖。 */
    public void setTransfer(io.agenticdb.jdbc.DbSession sourceSession,
                            io.agenticdb.introspect.SchemaService schemaService,
                            java.util.concurrent.Executor executor,
                            java.util.List<io.agenticdb.session.ConnectionProfile> otherConnections,
                            java.util.function.Function<String, io.agenticdb.jdbc.DbSession> resolver) {
        this.transferSession = sourceSession;
        this.transferSchemaService = schemaService;
        this.transferExecutor = executor;
        this.transferOthers = otherConnections == null ? java.util.List.of() : otherConnections;
        this.transferResolver = resolver == null ? id -> null : resolver;
    }

    /** 切到非 SQL 资产页时，把 SQL 侧的「当前活动页」引用一次性清干净。 */
    private void resetActivePages() {
        activeGrid = null;
        activeQuery = null;
        activeStructure = null;
        activeConnections = null;
        activeObjectTab = null;
        activeObjectPage = null;
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
        if (tab.kind() == TabKind.REDIS_KEYS) {
            // ⭐ EXT（P1-5）：Redis 键浏览器。每个 (连接, db) 一个独立实例并缓存，
            // 切标签不会重连、不会丢前缀/类型过滤状态。
            RedisKeysPage page = redisKeysPages.get(tab.id());
            if (page == null) {
                Node created = redisKeysPageFactory.apply(tab);
                if (created instanceof RedisKeysPage redisPage) {
                    page = redisPage;
                    redisKeysPages.put(tab.id(), page);
                } else if (created != null) {
                    activeGrid = null;
                    activeQuery = null;
                    activeStructure = null;
                    activeConnections = null;
                    activeObjectTab = null;
                    activeObjectPage = null;
                    getChildren().setAll(created);
                    return;
                }
            }
            if (page != null) {
                resetActivePages();
                getChildren().setAll(page);
                page.activate();
                return;
            }
        }
        if (tab.kind() == TabKind.OBJECT_BROWSER) {
            // ⭐ EXT（P2-8）：对象存储浏览器。每个 (连接, bucket) 一个实例并缓存，
            // 切标签不重连、不丢当前前缀与滚动位置。
            ObjectBrowserPage page = objectBrowserPages.get(tab.id());
            if (page == null) {
                Node created = objectBrowserPageFactory.apply(tab);
                if (created instanceof ObjectBrowserPage browser) {
                    page = browser;
                    objectBrowserPages.put(tab.id(), page);
                } else if (created != null) {
                    resetActivePages();
                    getChildren().setAll(created);
                    return;
                }
            }
            if (page != null) {
                resetActivePages();
                getChildren().setAll(page);
                page.activate();
                return;
            }
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
        // ⭐ EXT（P1-5）：Redis 键浏览器页同样按 id 精确释放，关掉标签页要断会话。
        RedisKeysPage redis = redisKeysPages.remove(id);
        if (redis != null) {
            redis.close();
        }
        // ⭐ EXT（P2-8）：对象存储浏览器页同理，关标签要释放会话。
        ObjectBrowserPage browser = objectBrowserPages.remove(id);
        if (browser != null) {
            browser.close();
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
     * 某个标签托管的查询页；没建起来或不是查询页则 {@code null}。
     *
     * <p>D-36：当前 SQL 只活在 {@code queryPages} 里 —— {@code TabBar} 按 id 存的是标签条上的
     * 胶囊（{@code HBox}），不是页面本体，拿它取 SQL 会永远得到空串。
     */
    public QueryPage queryPage(String id) {
        return queryPages.get(id);
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
            case REDIS_KEYS, OBJECT_BROWSER -> connectionName;
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
                    // ⭐ 对象类说明页路由 —— 集合当前是空的（Function 已接真实例程清单页）。
                    //    机制留着：将来哪类对象又落回说明页，把路由常量加回
                    //    {@code TOOL_OBJECT_ROUTES} 即可，角标名单会由门禁反推着同步。
                    body.getChildren().add(emptyState(
                            Icons.Icon.STRUCTURE, tab.title(),
                            I18n.t("workspace.tool.objectKind")));
                } else if (SideBar.TOOL_BACKUP.equals(route) && backupSession != null) {
                    // ⭐ P2 Backup：纯 JDBC 转储 / 恢复已落地，这里接上真实工具页。
                    body.getChildren().add(new BackupToolPage(
                            backupSession, backupSchemaService, backupExecutor));
                } else if (SideBar.TOOL_ROUTINES.equals(route) && routinesSession != null) {
                    // ⭐ 2026-11-10：功能区「函数」格接上真实例程清单页（此前落说明页，
                    //    而定义体早已交付 —— 两条路自相矛盾）。双击任一行打开定义体标签页。
                    body.getChildren().add(new RoutineListPage(
                            routinesSession, routinesSchemaService, routinesExecutor,
                            (name, kind) -> routinesOpen.accept(name, kind)));
                } else if (SideBar.TOOL_USERS.equals(route) && usersSession != null) {
                    // ⭐ P2 User 只读视图（H8）：列出账号 / 角色，只读。
                    body.getChildren().add(new UsersToolPage(
                            usersSession, usersSchemaService, usersExecutor));
                } else if (route.startsWith(SideBar.TOOL_PROFILING)
                        && profilingSession != null) {
                    // ⭐ P3 轻量 Data Profiling：单条采样查询在内存里算每列画像。
                    // ⭐ 2026-11-10：路由可以带表名（TOOL_PROFILING + 表名）——
                    //    数据网格工具条那颗「数据画像」按钮用它直达当前表。
                    String table = route.substring(SideBar.TOOL_PROFILING.length()).trim();
                    body.getChildren().add(new DataProfilingPage(
                            profilingSession, profilingSchemaService, profilingExecutor,
                            table.isEmpty() ? null : table));
                } else if (SideBar.TOOL_TRANSFER.equals(route) && transferSession != null) {
                    // ⭐ P3 跨库传输最小切片：源表 → 内存 SQL → 目标连接。
                    body.getChildren().add(new TransferToolPage(
                            transferSession, transferSchemaService, transferExecutor,
                            transferOthers, transferResolver));
                } else if (SideBar.TOOL_UNAVAILABLE_ROUTES.contains(route)) {
                    // ⭐ R36 N6 → P2/P3：User / Backup / Profile / Transfer 已升级为真工具页
                    // （走上面的分支）。2026-11-11 AI 新建表入口撤掉后集合为空；
                    // 机制保留：将来哪条路由又落回说明页，仍按诚实条款给**如实说明页**。
                    // BI 已按 2026-11-09 用户裁定整体移除；Automation / Model 已按 2026-11-10
                    // 用户裁定删除入口（明确不做），不再有这两条路由。
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
            case REDIS_KEYS -> body.getChildren().add(emptyState(Icons.Icon.LIST,
                    tab.title(), ""));
            default -> body.getChildren().add(emptyState(Icons.Icon.VIEW_OBJECT,
                    tab.title(), ""));
        }

        page.getChildren().addAll(header, new Separator(), body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return page;
    }

    private VBox settingsSkeleton() {
        Label theme = sectionTitle(I18n.t("workspace.settings.appearance"));
        Label mode = hint(I18n.t("workspace.settings.modeHint",
                io.agenticdb.ui.Shortcuts.of("Mod+K")));

        Button toggle = IconButtons.of(Icons.Icon.THEME, I18n.t("action.theme.toggle"));
        toggle.getStyleClass().addAll("adb-button");
        toggle.setOnAction(event -> actions.run("theme.toggle"));

        // ⭐ 密度也是**真实设置**（行高令牌），放在设置页比只留在菜单里好找。
        Label density = sectionTitle(I18n.t("workspace.settings.density"));
        Label densityHint = hint(I18n.t("workspace.settings.densityHint"));

        HBox densityRow = new HBox();
        densityRow.getStyleClass().add("adb-toolbar__group");
        // ⭐ D-60：档位清单**来自动作通道**，不在这里另抄一份 ——
        //    以前视图菜单四档、这页写死三颗，最密的 dense 在设置页根本点不到。
        for (Action action : densityActions()) {
            densityRow.getChildren().add(densityButton(action));
        }

        Label shortcuts = sectionTitle(I18n.t("shortcut.title"));

        Button palette = IconButtons.of(Icons.Icon.PALETTE, I18n.t("workspace.settings.openPalette"));
        palette.getStyleClass().addAll("adb-button");
        palette.setOnAction(event -> actions.run("command-palette"));

        Button sheet = IconButtons.of(Icons.Icon.HISTORY, I18n.t("workspace.settings.shortcutSheet"));
        sheet.getStyleClass().addAll("adb-button");
        sheet.setOnAction(event -> actions.run("shortcuts"));

        // ⭐ D-60：两栏版式 —— 标题在左列、说明与控件在右列，卡片铺满内容宽度。
        //    以前三张 520px 的卡片**居中**摆在右半屏（左半屏 200px 是死区），
        //    说明句被卡片定宽裁成「…or the theme icon in the a…」。
        return new VBox(12,
                settingsCard(theme, mode, toggle),
                settingsCard(density, densityHint, densityRow),
                settingsCard(shortcuts, palette, sheet));
    }

    /**
     * 一段设置：左列标题，右列依次是说明句与控件。
     *
     * <p>⭐ 卡片宽度**不设上限**（Region 的算得最大值本来就是无界，铺满内容列是默认行为）——
     * 旧版这里写死 {@code setMaxWidth(520)} 再把整列 {@code TOP_CENTER}，
     * 于是帧上量到「内容从 x≈525 起、左半屏 200px 空、说明句被定宽裁成半句」。
     */
    private VBox settingsCard(Label title, javafx.scene.Node... right) {
        title.getStyleClass().add("adb-settings__label");
        VBox column = new VBox(6, right);
        HBox row = new HBox(title, column);
        row.getStyleClass().add("adb-settings__row");
        HBox.setHgrow(column, Priority.ALWAYS);

        VBox card = new VBox(row);
        card.getStyleClass().add("adb-card");
        return card;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-section-title");
        return label;
    }

    /** 说明句：必须换行 —— 定宽裁掉的半句话等于设置页自己在撒谎说这页只有这么宽。 */
    private Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        label.setWrapText(true);
        return label;
    }

    /** 注册表里的密度档位，按注册顺序（从密到松）。 */
    private java.util.List<Action> densityActions() {
        return actions.all().stream()
                .filter(action -> action.id().startsWith("density-"))
                .toList();
    }

    /**
     * 密度按钮：点击交给动作本身（ActionRegistry 是唯一真相），
     * 当前档由动作的 {@code selected} 上色。
     *
     * <p>⭐ D-81：以前四颗同色同权重，帧上根本看不出选的是哪档 ——
     * 勾选态在菜单里有、在这页没接。样式类跟着 {@code selectedProperty()} 走，
     * 页面自己不再存一份「当前档」。
     *
     * <p>⚠️ 文案取 {@code action.label()}，不在这里拼 {@code "action." + id}：
     * 拼出来的键对 {@code I18nAuditTest} 的正则是「{@code action.}（空后缀）」，
     * 资源里永远没有这个键 —— 门禁当场红（本轮真实踩过一次），
     * 而且就算不红，也是「同一个 label 两处各写一份」，正是 D-60 要收的那种通道漂移。
     */
    private Button densityButton(Action action) {
        String actionId = action.id();
        Button button = new Button(action.label());
        button.setId("settings-density-" + actionId.substring("density-".length()));
        button.getStyleClass().addAll("adb-button");

        javafx.beans.InvalidationListener marked = obs -> {
            if (action.isSelected() && !button.getStyleClass().contains("adb-button--on")) {
                button.getStyleClass().add("adb-button--on");
            } else if (!action.isSelected()) {
                button.getStyleClass().remove("adb-button--on");
            }
        };
        action.selectedProperty().addListener(marked);
        marked.invalidated(action.selectedProperty());

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
