package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.ConnectionStore;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.session.SessionManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 六区外壳：菜单 / 工具栏 / 侧栏 / 标签栏 / 内容区 / 状态栏。
 *
 * <p>P0 只验证结构与交互通路：所有高频动作都必须能从菜单、工具栏、
 * 右键菜单或命令面板到达，且启用状态来自同一个 {@link ActionRegistry}。
 */
public final class AppShell extends StackPane {

    private static final int SIDEBAR_MIN = 180;
    private static final int SIDEBAR_MAX = 420;
    private static final int SIDEBAR_DEFAULT = 240;
    private static final int SIDEBAR_COLLAPSED = 48;

    private final SettingsStore settings;
    private final ThemeManager themeManager;
    private final ActionRegistry actions = new ActionRegistry();
    private final SessionManager sessions = new SessionManager();
    private final SchemaService schemaService = new SchemaService();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "adb-db");
        thread.setDaemon(true);
        return thread;
    });

    private final StatusBar statusBar = new StatusBar();
    private final TabBar tabBar = new TabBar(actions);
    private final SideBar sideBar;
    private final WorkspaceView workspace;
    private final CommandPalette palette;
    private final ConnectionStore connectionStore;
    private final CredentialStore credentials;
    private final ObservableList<ConnectionProfile> connections = FXCollections.observableArrayList();
    private final Map<String, List<SchemaObject>> objectsByConnection = new HashMap<>();
    private final Map<String, ConnectionProfile> profilesById = new HashMap<>();
    private final BorderPane root = new BorderPane();
    private final Label toastLabel = new Label();
    private final Button toastAction = new Button("撤销");
    private final HBox toast = new HBox(10, toastLabel, toastAction);
    private String connectionName;
    private DbSession session;
    private ConnectionProfile activeProfile;
    private MainToolBar mainToolBar;
    private final Map<String, String> initialQuerySql = new HashMap<>();
    private final Map<String, File> initialQueryFile = new HashMap<>();
    private int queryCounter;

    private Stage stage;
    private Region resizer;
    private int sidebarWidth;
    private boolean sidebarCollapsed;
    private PauseTransition toastTimer;

    public AppShell(SettingsStore settings, ThemeManager themeManager) {
        this.settings = settings;
        this.themeManager = themeManager;

        Path demoPath = DemoDatabase.defaultPath();
        DemoDatabase.ensure(demoPath);
        this.connectionStore = ConnectionStore.defaultStore();
        this.credentials = connectionStore.credentials();
        this.connectionStore.ensureDemo(demoPath);
        this.connections.setAll(connectionStore.load());
        this.connectionName = demoPath.getFileName() == null
                ? DemoDatabase.DEFAULT_FILE_NAME : demoPath.getFileName().toString();

        this.sideBar = new SideBar(actions, List.of(), connectionName,
                this::openTab, statusBar::setMessage, "demo", connections, this::selectConnection);
        this.sideBar.setOnRefreshObjects(this::reloadObjects);
        this.sideBar.setOnSelectSchema(this::switchSchema);
        this.workspace = new WorkspaceView(actions, connectionName,
                this::createGridPage, this::createQueryPage, this::createStructurePage,
                this::createConnectionsPage);
        this.palette = new CommandPalette(actions);
        // 侧栏「查询历史」接真实功能：QueryHistory 本来就可用，只是侧栏开错了页面。
        this.workspace.setHistory(new QueryHistory(settings), this::openQueryWith);

        registerActions();
        actions.validateUniqueAccelerators();
        // 4.5-30：动作被禁用时给出解释，不再静默 no-op。
        actions.setFeedback(statusBar::setMessage);

        tabBar.setOnActivate(this::showTab);
        tabBar.setOnClose(this::tabClosed);
        tabBar.setCloseGuard(this::confirmClose);
        sideBar.setOnCollapsed(this::sidebarCollapsedChanged);

        this.sidebarWidth = clamp(settings.getInt("ui.sidebar.width", SIDEBAR_DEFAULT),
                SIDEBAR_MIN, SIDEBAR_MAX);
        this.sidebarCollapsed = settings.getBoolean("ui.sidebar.collapsed", false);

        getStyleClass().add("app-shell");
        buildUi();
        applyDensity(settings.get("ui.density", "normal"));
        sideBar.setCollapsed(sidebarCollapsed);
        applySidebarWidth();
        updateTabCloseEnabled();

        refreshProfiles();
        ConnectionProfile demo = profileById("demo");
        if (demo != null) {
            connectProfile(demo, false);
        }
        statusBar.setReadonly(true);
        statusBar.setRange("—");
        if (activeProfile == null) {
            status("演示库不可用：无法打开 " + connectionName);
        } else {
            status("已连接 " + connectionName + " · " + objectsByConnection.getOrDefault(
                    activeProfile.id(), List.of()).size() + " 个对象");
        }
    }

    // ─────────────────────────── 连接管理 ───────────────────────────

    private void refreshProfiles() {
        profilesById.clear();
        for (ConnectionProfile profile : connections) {
            profilesById.put(profile.id(), profile);
        }
        if (mainToolBar != null) {
            mainToolBar.setConnections(connections,
                    activeProfile == null ? "demo" : activeProfile.id());
        }
        sideBar.setConnections(connections);
    }

    private ConnectionProfile profileById(String id) {
        return profilesById.get(id);
    }

    /** 激活一个连接：打开（或复用）会话、读取对象树、同步侧栏与工具栏。 */
    private void connectProfile(ConnectionProfile profile, boolean touch) {
        if (profile == null) {
            return;
        }
        activeProfile = profile;
        connectionName = profile.name();
        session = null;
        List<SchemaObject> objects = List.of();
        try {
            String password = credentials.password(profile.id()).orElse(null);
            DbSession opened = sessions.open(profile.id(), profile.spec(password));
            session = opened;
            objects = schemaService.listObjects(session);
            objectsByConnection.put(profile.id(), objects);
            if (touch) {
                connectionStore.touch(profile);
                ConnectionProfile touched = profile.withLastUsed(System.currentTimeMillis());
                connections.removeIf(existing -> existing.id().equals(touched.id()));
                connections.add(touched);
                connectionStore.save(touched);
            }
        } catch (Exception e) {
            session = null;
            status("连接失败：" + readable(e));
            return;
        }

        refreshProfiles();
        sideBar.setObjects(objects);
        sideBar.setConnection(profile.id(), profile.name());
        // ⭐ schema 切换器：列出这个连接能看到的 schema。
        //    多于一个才显示（SQLite 没有、单 schema 不添乱）。
        try {
            sideBar.setSchemas(schemaService.listSchemas(session));
        } catch (SQLException e) {
            sideBar.setSchemas(List.of());
        }
        statusBar.setConnection(profile.name());
        // 侧栏「诊断」接真实功能：表结构 + 行数的健康概览。会话变了要跟着换。
        workspace.setDiagnostics(session, schemaService, dbExecutor);
        // 4.5-11：状态栏与折叠侧栏的左端徽标显示真实方言，不再是写死的「DEV」。
        String dialectName = session == null ? "—" : session.dialect().displayName();
        statusBar.setDialect(dialectName);
        sideBar.setDialect(dialectName);
        statusBar.setReadonly(true);
        statusBar.setRange("—");
        status("已连接 " + profile.name() + " · " + objects.size() + " 个对象");
        closeOrphanSessions();
    }

    /**
     * 关掉「没有任何标签页引用」的会话。
     *
     * <p>A21：切换连接时旧会话一直留着，每切一次就多占一条数据库连接。
     * 但<b>有标签页在用</b>的连接必须留着 —— 标签页绑定的是 connectionId，
     * 关掉它的会话会让已打开的页面立刻不可用。
     */
    private void closeOrphanSessions() {
        Set<String> used = new HashSet<>();
        for (TabItem tab : tabBar.openTabs()) {
            if (tab.connectionId() != null) {
                used.add(tab.connectionId());
            }
        }
        used.add(activeProfileId());
        for (String id : sessions.openIds()) {
            if (!used.contains(id)) {
                sessions.close(id);
                objectsByConnection.remove(id);
            }
        }
    }

    /**
     * 切换当前 schema。
     *
     * <p>PostgreSQL 用 {@code SET search_path} 切；MySQL 的 schema 就是数据库（切换要重连），
     * SQLite 没有 schema 概念 —— 这些情况下不切，只是重新内省一次。
     */
    private void switchSchema(String schema) {
        if (session == null || schema == null || schema.isBlank()) {
            return;
        }
        try {
            String sql = session.dialect().setSchemaSql(schema);
            if (sql != null) {
                session.execute(sql);
            }
            List<SchemaObject> objects = schemaService.listObjects(session, schema);
            sideBar.setObjects(objects);
            status("当前 schema：" + schema + " · " + objects.size() + " 个对象");
        } catch (SQLException e) {
            status("切换 schema 失败：" + readable(e));
        }
    }

    /** 侧栏/工具栏切换连接。已打开的会话复用，不重开。 */
    private void selectConnection(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        if (activeProfile != null && activeProfile.id().equals(profile.id())
                && sessions.isOpen(profile.id())) {
            status("当前连接：" + profile.name());
            return;
        }
        connectProfile(profile, true);
    }

    private void openConnectionWizard() {
        openConnectionWizard(null);
    }

    private void openConnectionWizard(ConnectionProfile existing) {
        if (stage == null) {
            return;
        }
        ConnectionWizard wizard = new ConnectionWizard(stage, existing, credentials,
                this::testConnection);
        wizard.showAndWait().ifPresent(profile -> {
            if (existing != null) {
                connections.removeIf(item -> item.id().equals(existing.id()));
                credentials.delete(existing.id());
                sessions.close(existing.id());
            }
            connections.add(profile);
            connectionStore.save(profile);
            String password = wizard.password();
            if (password != null && !password.isBlank()) {
                credentials.save(profile.id(), password);
            }
            refreshProfiles();
            connectProfile(profile, true);
            openConnections();
        });
    }

    private String testConnection(ConnectionSpec spec) {
        try (DbSession test = io.agenticdb.session.ConnectionPool.open(spec)) {
            test.query("SELECT 1", 1);
            return null;
        } catch (Exception e) {
            return readable(e);
        }
    }

    private void openConnections() {
        openTab(new TabItem("connections", "连接管理", TabKind.CONNECTIONS));
    }

    private ConnectionsPage createConnectionsPage(TabItem tab) {
        ConnectionsPage page = new ConnectionsPage(connections,
                this::openConnectionWizard,
                this::selectConnection,
                this::openConnectionWizard,
                this::duplicateConnection,
                this::deleteConnection,
                profile -> sessions.isOpen(profile.id()),
                pageFeedback());
        page.setDeleteConfirmation(this::confirmDeleteConnection);
        return page;
    }

    private void duplicateConnection(ConnectionProfile profile) {
        ConnectionProfile copy = profile.duplicate();
        credentials.password(profile.id()).ifPresent(password ->
                credentials.save(copy.id(), password));
        connections.add(copy);
        connectionStore.save(copy);
        refreshProfiles();
        status("已复制连接：" + copy.name());
    }

    private void deleteConnection(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        sessions.close(profile.id());
        credentials.delete(profile.id());
        connectionStore.delete(profile);
        connections.removeIf(existing -> existing.id().equals(profile.id()));
        profilesById.remove(profile.id());
        objectsByConnection.remove(profile.id());
        if (activeProfile != null && activeProfile.id().equals(profile.id())) {
            activeProfile = null;
            session = null;
            ConnectionProfile fallback = profileById("demo");
            if (fallback == null) {
                fallback = connections.isEmpty() ? null : connections.getFirst();
            }
            if (fallback != null) {
                connectProfile(fallback, true);
            } else {
                sideBar.setObjects(List.of());
                statusBar.setConnection("未连接");
            }
        }
        refreshProfiles();
        status("已删除连接：" + profile.name());
    }

    // ⛔ 别再手写 ButtonType("删除", OK_DONE) —— OK_DONE 是默认按钮，回车即删。
    //    统一走 DestructiveConfirm：破坏性按钮用 OTHER，默认按钮落在「取消」上。
    private boolean confirmDeleteConnection(ConnectionProfile profile) {
        DestructiveConfirm confirm = DestructiveConfirm.of(
                "删除连接",
                "删除 " + profile.name() + "？",
                "连接元数据与钥匙串口令会被移除；数据库本身不受影响。",
                "删除");
        styleDialog(confirm.alert());
        return confirm.confirmed();
    }

    private void reconnectConnection() {
        if (activeProfile == null) {
            status("当前没有连接");
            return;
        }
        sessions.close(activeProfile.id());
        session = null;
        connectProfile(activeProfile, false);
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    public void restoreWindow(Stage stage) {
        this.stage = stage;
        int width = settings.getInt("ui.window.width", 0);
        int height = settings.getInt("ui.window.height", 0);
        if (width >= 960) {
            stage.setWidth(width);
        }
        if (height >= 600) {
            stage.setHeight(height);
        }
        stage.setOnCloseRequest(event -> {
            if (!confirmDiscardAll()) {
                event.consume();
                return;
            }
            saveWindowState();
        });
    }

    public void afterShown(Stage stage) {
        this.stage = stage;
        workspace.requestFocus();
        String connection = System.getProperty("adb.connection",
                System.getenv().getOrDefault("ADB_CONNECTION", "")).trim();
        if (!connection.isEmpty()) {
            ConnectionProfile selected = connections.stream()
                    .filter(profile -> profile.id().equals(connection)
                            || profile.name().equalsIgnoreCase(connection))
                    .findFirst().orElse(null);
            if (selected != null) {
                selectConnection(selected);
            }
        }
        String initial = System.getProperty("adb.open",
                System.getenv().getOrDefault("ADB_OPEN", "")).trim();
        if (!initial.isEmpty()) {
            openTab(new TabItem("grid:" + activeProfileId() + ":" + initial, initial,
                    TabKind.GRID, initial, activeProfileId()));
        }
        String structure = System.getProperty("adb.structure",
                System.getenv().getOrDefault("ADB_STRUCTURE", "")).trim();
        if (!structure.isEmpty()) {
            // ⭐ 与 ADB_QUERY 一致：「1 / true」表示「用演示表的 users」，
            //    否则会被当成表名 "1"，于是报「no such table: 1」。
            String table = structure.equals("1") || structure.equalsIgnoreCase("true")
                    ? "users" : structure;
            openStructure(activeProfileId(), table);
        }
        String initialQuery = System.getProperty("adb.query",
                System.getenv().getOrDefault("ADB_QUERY", "")).trim();
        if (!initialQuery.isEmpty()) {
            String sql = initialQuery.equals("1") || initialQuery.equalsIgnoreCase("true")
                    ? "SELECT id, name, email, active FROM users ORDER BY id LIMIT 100;"
                    : initialQuery;
            String id = "query:" + activeProfileId() + ":" + System.nanoTime();
            initialQuerySql.put(id, sql);
            openTab(new TabItem(id, "查询示例", TabKind.QUERY, null, activeProfileId()));
            QueryPage page = workspace.activeQuery();
            if (page != null) {
                page.markClean();
            }
            Platform.runLater(workspace::runQuery);
        }
    }

    /** 窗口关闭时调用：先停掉在途查询，再关连接。 */
    public void close() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sessions.close();
    }

    private void saveWindowState() {
        if (stage == null) {
            return;
        }
        settings.setInt("ui.window.width", (int) Math.round(stage.getWidth()));
        settings.setInt("ui.window.height", (int) Math.round(stage.getHeight()));
        settings.setInt("ui.sidebar.width", sidebarWidth);
        settings.setBoolean("ui.sidebar.collapsed", sidebarCollapsed);
    }

    // ─────────────────────────── 动作 ───────────────────────────

    private void registerActions() {
        actions.register("new-connection", "新建连接…", "文件",
                key("Shortcut+N"), this::openConnectionWizard);
        actions.register("connections.manage", "连接管理…", "文件",
                null, this::openConnections);
        actions.register("new-query", "新建查询", "文件",
                key("Shift+Shortcut+N"), this::openNewQuery);
        actions.register("open-sql", "打开 SQL 文件…", "文件",
                key("Shortcut+O"), this::openSqlFile);
        actions.register("tab.close", "关闭标签", "文件",
                key("Shortcut+W"), () -> tabBar.closeActive());
        // 4.5-29：标签栏右键菜单以前是手搓 MenuItem，现在也走注册表，
        // 于是「关闭其他 / 全部关闭」能在 ⌘K 里搜到，enabled 也统一。
        actions.register("tab.close-others", "关闭其他", "文件",
                null, () -> tabBar.closeOthers(tabBar.activeId()));
        actions.register("tab.close-all", "全部关闭", "文件",
                null, () -> tabBar.closeAll());
        actions.register("window.close", "关闭窗口", "文件",
                key("Shift+Shortcut+W"), () -> {
                    if (stage != null) {
                        stage.close();
                    } else {
                        Platform.exit();
                    }
                });
        actions.register("app.quit", "退出 AgenticDB", "文件",
                key("Shortcut+Q"), () -> {
                    if (stage != null) {
                        stage.close();
                    } else {
                        Platform.exit();
                    }
                });

        actions.register("command-palette", "命令面板", "编辑",
                key("Shortcut+K"), palette::showPalette);
        actions.register("focus-search", "搜索对象", "编辑",
                key("Shortcut+F"), () -> {
                    if (!workspace.focusSearch()) {
                        sideBar.focusSearch();
                    }
                });

        // 编辑菜单三件套：撤销 / 重做 / 复制，全部委托当前活动数据页。
        // 快捷键不在此注册 —— DataGridPage 已在自己的 scene 上绑了 ⌘Z/⇧⌘Z/⌘C，
        // 两边都注册会双触发（撤销两次）；菜单入口复用同一方法，行为一致。
        actions.register("edit.undo", "撤销  (⌘Z)", "编辑", null, this::editUndo);
        actions.register("edit.redo", "重做  (⇧⌘Z)", "编辑", null, this::editRedo);
        actions.register("edit.copy", "复制  (⌘C)", "编辑", null, () -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid == null) {
                status("复制只在数据页可用");
                return;
            }
            grid.copyFocusedCellValue();
        });

        actions.register("query.run", "执行当前语句", "查询",
                key("Shortcut+R"), () -> {
                    if (!workspace.runQuery()) {
                        status("请先新建一个查询页");
                    }
                });
        actions.register("query.run-all", "执行全部语句", "查询",
                key("Shift+Shortcut+R"), () -> {
                    if (!workspace.runAllQuery()) {
                        status("请先新建一个查询页");
                    }
                });
        actions.register("query.cancel", "取消执行", "查询",
                key("Shortcut+Period"), () -> {
                    if (!workspace.cancelQuery()) {
                        status("当前没有正在执行的查询");
                    }
                });
        actions.register("query.explain", "解释执行计划", "查询",
                key("Shortcut+E"), () -> {
                    if (!workspace.explainQuery()) {
                        status("请先新建一个查询页");
                    }
                });
        actions.register("query.format", "格式化 SQL", "查询",
                key("Alt+Shift+F"), () -> {
                    if (!workspace.formatQuery()) {
                        status("请先新建一个查询页");
                    }
                });
        actions.register("query.save", "保存 SQL", "查询",
                key("Shortcut+S"), () -> {
                    if (!workspace.saveQuery()) {
                        status("请先新建一个查询页");
                    }
                });
        actions.register("query.comment", "注释 / 取消注释", "查询",
                key("Shortcut+Slash"), () -> {
                    if (!workspace.toggleQueryComment()) {
                        status("请先新建一个查询页");
                    }
                });

        actions.register("sidebar.toggle", "折叠 / 展开侧栏", "视图",
                key("Shortcut+Backslash"),
                () -> sideBar.setCollapsed(!sideBar.isCollapsed()));
        actions.register("theme.toggle", "切换主题", "视图",
                null, () -> {
                    themeManager.toggle();
                    status("主题：" + themeManager.mode().label());
                    toast("主题：" + themeManager.mode().label());
                });
        actions.register("density-compact", "紧凑", "视图",
                null, () -> changeDensity("compact"));
        actions.register("density-normal", "标准", "视图",
                null, () -> changeDensity("normal"));
        actions.register("density-comfort", "宽松", "视图",
                null, () -> changeDensity("comfort"));

        actions.register("refresh", "刷新", "数据库",
                key("F5"), this::refresh);
        actions.register("connection.reconnect", "重新连接", "数据库",
                null, this::reconnectConnection);
        actions.register("demo.open", "打开演示表 users", "数据库",
                null, () -> openTab(new TabItem("grid:" + activeProfileId() + ":users",
                        "users", TabKind.GRID, "users", activeProfileId())));

        actions.register("diagnostics", "诊断", "工具",
                null, () -> actions.run("object.diagnostics"));
        // P6 的「导入 / 导出」向导还没做：入口先从侧栏撤掉，
        // 不留「点了只有一句提示」的死 UI（TODO §12.1「要么接要么删」）。
        actions.register("import-export", "导入 / 导出", "工具",
                null, () -> status("导入 / 导出向导在 P6 接入，现在还没有可用的向导"));
        actions.register("settings", "设置…", "工具",
                key("Shortcut+Comma"),
                () -> openTab(new TabItem("settings", "设置", TabKind.SETTINGS)));

        actions.register("window.minimize", "最小化", "窗口",
                key("Shortcut+M"), () -> {
                    if (stage != null) {
                        stage.setIconified(true);
                    }
                });
        actions.register("window.zoom", "最大化 / 还原", "窗口",
                null, () -> {
                    if (stage != null) {
                        stage.setMaximized(!stage.isMaximized());
                    }
                });

        actions.register("shortcuts", "快捷键…", "帮助",
                key("F1"), this::showShortcuts);
        actions.register("about", "关于 AgenticDB", "帮助",
                null, () -> showInfo("关于 AgenticDB",
                        "AgenticDB v0.1.0\nJavaFX 25 · P3 查询页\n\n"
                                + "SQLite 演示库 · 真实内省 · 分页数据页 · SQL 编辑器\n"
                                + "执行清单：NAVICAT-TODO.md\n"
                                + "不内嵌浏览器，不启动 HTTP 端口。"));
        updateQueryActions();
    }

    private void buildUi() {
        MenuBar menuBar = new MenuBarFactory(actions).build();
        mainToolBar = new MainToolBar(actions, this::status, connections,
                activeProfile == null ? "demo" : activeProfile.id(), this::selectConnection);

        VBox top = new VBox(menuBar, mainToolBar);
        top.getStyleClass().add("adb-top");

        resizer = buildResizer();
        VBox main = new VBox(tabBar, workspace);
        main.getStyleClass().add("adb-main");
        VBox.setVgrow(workspace, Priority.ALWAYS);
        HBox.setHgrow(main, Priority.ALWAYS);

        HBox body = new HBox(sideBar, resizer, main);
        body.getStyleClass().add("adb-body");

        root.getStyleClass().add("adb-root");
        root.setTop(top);
        root.setCenter(body);
        root.setBottom(statusBar);

        toast.getStyleClass().add("adb-toast");
        toastLabel.getStyleClass().add("adb-toast__text");
        toastAction.getStyleClass().addAll("adb-toast__action");
        toastAction.setVisible(false);
        toastAction.setManaged(false);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.setVisible(false);
        toast.setManaged(false);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 40, 0));

        getChildren().addAll(root, palette, toast);
    }

    private Region buildResizer() {
        Region handle = new Region();
        handle.getStyleClass().add("adb-resizer");
        handle.setCursor(Cursor.H_RESIZE);
        handle.setMinWidth(4);
        handle.setPrefWidth(4);
        handle.setMaxWidth(4);

        final double[] start = new double[2];
        handle.setOnMousePressed(event -> {
            start[0] = event.getScreenX();
            start[1] = sideBar.getWidth();
        });
        handle.setOnMouseDragged(event -> {
            if (sidebarCollapsed) {
                return;
            }
            int width = clamp((int) (start[1] + event.getScreenX() - start[0]),
                    SIDEBAR_MIN, SIDEBAR_MAX);
            sidebarWidth = width;
            applySidebarWidth();
        });
        handle.setOnMouseReleased(event -> settings.setInt("ui.sidebar.width", sidebarWidth));
        handle.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                sidebarWidth = SIDEBAR_DEFAULT;
                applySidebarWidth();
                settings.setInt("ui.sidebar.width", sidebarWidth);
            }
        });
        return handle;
    }

    // ─────────────────────────── 侧栏 / 标签 / 状态 ───────────────────────────

    private void sidebarCollapsedChanged(boolean collapsed) {
        this.sidebarCollapsed = collapsed;
        settings.setBoolean("ui.sidebar.collapsed", collapsed);
        applySidebarWidth();
    }

    private void applySidebarWidth() {
        double width = sidebarCollapsed ? SIDEBAR_COLLAPSED : sidebarWidth;
        sideBar.setMinWidth(width);
        sideBar.setPrefWidth(width);
        sideBar.setMaxWidth(width);
        if (resizer != null) {
            resizer.setVisible(!sidebarCollapsed);
            resizer.setManaged(!sidebarCollapsed);
        }
    }

    private void showTab(TabItem tab) {
        if (tab.connectionId() != null && !tab.connectionId().equals(activeProfileId())) {
            ConnectionProfile target = profileById(tab.connectionId());
            if (target != null) {
                selectConnection(target);
            }
        }
        workspace.showTab(tab);
        status("已激活：" + tab.title());
        if (tab.kind() != TabKind.GRID && tab.kind() != TabKind.QUERY) {
            statusBar.setRange("—");
            statusBar.setDuration(-1);
            statusBar.setDirty(0);
            statusBar.setReadonly(true);
        }
        updateTabCloseEnabled();
        updateQueryActions();
    }

    private boolean confirmClose(TabItem tab) {
        int count = workspace.dirtyCount(tab.id());
        if (count == 0) {
            return true;
        }
        DestructiveConfirm confirm = DestructiveConfirm.of(
                "未保存的修改",
                tab.title() + " 有 " + count + " 处未保存修改",
                "关闭标签会丢弃这些修改。取消后编辑缓冲原样保留。",
                "放弃修改");
        styleDialog(confirm.alert());
        return confirm.confirmed();
    }

    private boolean confirmDiscardAll() {
        if (!tabBar.hasDirtyTabs()) {
            return true;
        }
        DestructiveConfirm confirm = DestructiveConfirm.of(
                "未保存的修改",
                "仍有标签包含未保存修改",
                "退出会丢弃所有编辑缓冲。",
                "放弃并退出");
        styleDialog(confirm.alert());
        return confirm.confirmed();
    }

    private void styleDialog(Alert alert) {
        if (stage != null) {
            alert.initOwner(stage);
        }
        if (getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            alert.getDialogPane().getStyleClass().add("adb-dialog");
        }
    }

    private void tabClosed(TabItem tab) {
        workspace.closeTab(tab.id());
        status("已关闭：" + tab.title());
        if (tabBar.count() == 0) {
            workspace.showWelcome();
            statusBar.setRange("—");
            statusBar.setDuration(-1);
            statusBar.setDirty(0);
        }
        updateTabCloseEnabled();
        updateQueryActions();
    }

    private void updateTabCloseEnabled() {
        actions.find("tab.close").ifPresent(action ->
                action.setEnabled(tabBar.count() > 0));
    }

    private void openTab(TabItem tab) {
        tabBar.open(tab);
    }

    private void openStructure(String name) {
        openStructure(activeProfileId(), name);
    }

    private void openStructure(String connectionId, String name) {
        openTab(new TabItem("structure:" + connectionId + ":" + name, name,
                TabKind.STRUCTURE, name, connectionId));
        status("打开结构：" + name);
    }

    private void openTool(String title) {
        openTab(new TabItem("tool:" + title, title, TabKind.TOOL));
        status("打开工具：" + title);
    }

    // ─────────────────────────── 数据页 ───────────────────────────

    private Node createGridPage(TabItem tab) {
        ConnectionProfile profile = connectionFor(tab);
        DbSession pageSession = profile == null ? null : sessions.find(profile.id()).orElse(null);
        if (pageSession == null) {
            return startupErrorPage(tab);
        }
        DataGridPage page = new DataGridPage(pageSession, schemaService, tab.target(),
                profile.name(), pageFeedback(), name -> openStructure(profile.id(), name),
                dbExecutor, settings);
        // 新开的数据页要继承当前密度档位（A19）。
        page.setDensity(currentDensity());
        page.setDirtyListener(count -> {
            tab.markDirty(count);
            updateEditActions();
        });
        return page;
    }

    private QueryPage createQueryPage(TabItem tab) {
        ConnectionProfile profile = connectionFor(tab);
        DbSession pageSession = profile == null ? null : sessions.find(profile.id()).orElse(null);
        if (pageSession == null) {
            return null;
        }
        String initial = initialQuerySql.remove(tab.id());
        File file = initialQueryFile.remove(tab.id());
        QueryPage page = new QueryPage(pageSession, profile.name(), initial, dbExecutor, settings,
                pageFeedback());
        if (file != null) {
            page.setCurrentFile(file);
            page.markClean();
        }
        page.setDirtyListener(tab::markDirty);
        page.setStateListener(this::updateQueryActions);
        return page;
    }

    private StructurePage createStructurePage(TabItem tab) {
        ConnectionProfile profile = connectionFor(tab);
        DbSession pageSession = profile == null ? null : sessions.find(profile.id()).orElse(null);
        if (pageSession == null) {
            return null;
        }
        return new StructurePage(pageSession, schemaService, tab.target(), profile.name(),
                pageFeedback(), dbExecutor, () -> schemaChanged(profile.id()));
    }

    private void schemaChanged(String connectionId) {
        reloadObjects(connectionId);
        workspace.refreshAll();
    }

    private ConnectionProfile connectionFor(TabItem tab) {
        String id = tab == null ? null : tab.connectionId();
        ConnectionProfile profile = id == null ? activeProfile : profilesById.get(id);
        return profile == null ? activeProfile : profile;
    }

    private String activeProfileId() {
        return activeProfile == null ? "demo" : activeProfile.id();
    }

    // ───────────────────── 门禁用的只读访问器 ─────────────────────

    ActionRegistry actionRegistry() {
        return actions;
    }

    StatusBar statusBar() {
        return statusBar;
    }

    WorkspaceView workspace() {
        return workspace;
    }

    TabBar tabBar() {
        return tabBar;
    }

    SideBar sideBar() {
        return sideBar;
    }

    /** 供门禁切换密度档位（A19）。 */
    void applyDensityForTest(String density) {
        applyDensity(density);
    }

    DataGridPage.Feedback pageFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
                statusBar.setMessage(message);
            }

            @Override
            public void range(String text) {
                statusBar.setRange(text);
            }

            @Override
            public void duration(long millis) {
                statusBar.setDuration(millis);
            }

            @Override
            public void dirty(int count) {
                statusBar.setDirty(count);
            }

            @Override
            public void readonly(boolean readonly) {
                statusBar.setReadonly(readonly);
            }

            @Override
            public void selection(int count) {
                statusBar.setSelection(count);
            }

            @Override
            public void transaction(boolean inTransaction) {
                statusBar.setTransaction(inTransaction);
            }

            @Override
            public void toast(String message) {
                AppShell.this.toast(message);
            }

            @Override
            public void toast(String message, Runnable action) {
                AppShell.this.toast(message, action);
            }

            @Override
            public void toastUnreversible(String message) {
                AppShell.this.toastUnreversible(message);
            }
        };
    }

    private void openNewQuery() {
        queryCounter++;
        String title = "未命名查询 " + queryCounter;
        openTab(new TabItem("query:" + activeProfileId() + ":" + System.nanoTime(), title,
                TabKind.QUERY, null, activeProfileId()));
        status("新建查询：" + title);
    }

    /**
     * 新建一个查询页并预填 SQL（查询历史的「载入到新查询」走这条）。
     */
    private void openQueryWith(String sql) {
        queryCounter++;
        String title = "未命名查询 " + queryCounter;
        String id = "query:" + activeProfileId() + ":" + System.nanoTime();
        initialQuerySql.put(id, sql);
        openTab(new TabItem(id, title, TabKind.QUERY, null, activeProfileId()));
        status("已载入历史查询：" + title);
    }

    private void openSqlFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("打开 SQL 文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SQL", "*.sql", "*.txt"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        String sql;
        try {
            sql = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            status("打开失败：" + readable(e));
            toast("打开失败：" + readable(e));
            return;
        }
        String id = "query:" + activeProfileId() + ":" + System.nanoTime();
        initialQuerySql.put(id, sql);
        initialQueryFile.put(id, file);
        openTab(new TabItem(id, file.getName(), TabKind.QUERY, null, activeProfileId()));
        status("已打开：" + file.getName());
    }

    private void updateQueryActions() {
        QueryPage page = workspace.activeQuery();
        boolean hasPage = page != null;
        boolean running = hasPage && page.isRunning();
        actions.find("query.run").ifPresent(action -> action.setEnabled(hasPage && !running));
        actions.find("query.run-all").ifPresent(action -> action.setEnabled(hasPage && !running));
        actions.find("query.explain").ifPresent(action -> action.setEnabled(hasPage && !running));
        actions.find("query.format").ifPresent(action -> action.setEnabled(hasPage && !running));
        actions.find("query.save").ifPresent(action -> action.setEnabled(hasPage && !running));
        actions.find("query.comment").ifPresent(action -> action.setEnabled(hasPage && !running));
        actions.find("query.cancel").ifPresent(action -> action.setEnabled(running));
        updateEditActions();
    }

    /**
     * 编辑菜单三件套的启用态：撤销 = 缓冲可撤销 **或** 上次保存留有反向 SQL
     * （保存后仍能撤销，与 Toast 上的「撤销」等价）。
     */
    private void updateEditActions() {
        DataGridPage grid = workspace.activeGrid();
        boolean canUndo = grid != null && (grid.canUndo() || grid.hasPendingSaveUndo());
        boolean canRedo = grid != null && grid.canRedo();
        boolean canCopy = grid != null;
        actions.find("edit.undo").ifPresent(action -> action.setEnabled(canUndo));
        actions.find("edit.redo").ifPresent(action -> action.setEnabled(canRedo));
        actions.find("edit.copy").ifPresent(action -> action.setEnabled(canCopy));
    }

    private void editUndo() {
        DataGridPage grid = workspace.activeGrid();
        if (grid == null) {
            status("撤销只在数据页可用");
            return;
        }
        if (grid.canUndo()) {
            grid.undo();
        } else if (grid.hasPendingSaveUndo()) {
            grid.undoLastSave();
        }
        updateEditActions();
    }

    private void editRedo() {
        DataGridPage grid = workspace.activeGrid();
        if (grid == null) {
            status("重做只在数据页可用");
            return;
        }
        grid.redo();
        updateEditActions();
    }

    private void reloadObjects() {
        reloadObjects(activeProfileId());
    }

    private void reloadObjects(String connectionId) {
        ConnectionProfile profile = profileById(connectionId);
        DbSession target = profile == null ? null : sessions.find(profile.id()).orElse(null);
        if (target == null) {
            status("连接未打开，无法刷新对象");
            return;
        }
        status("正在刷新对象…");
        dbExecutor.submit(() -> {
            try {
                List<SchemaObject> objects = schemaService.listObjects(target);
                Platform.runLater(() -> {
                    objectsByConnection.put(profile.id(), objects);
                    if (activeProfile != null && activeProfile.id().equals(profile.id())) {
                        sideBar.setObjects(objects);
                    }
                    status("对象已刷新 · " + objects.size() + " 个");
                });
            } catch (SQLException e) {
                String message = "刷新对象失败：" + readable(e);
                Platform.runLater(() -> status(message));
            }
        });
    }

    private VBox startupErrorPage(TabItem tab) {
        VBox page = new VBox(10);
        page.getStyleClass().add("adb-page");
        page.setPadding(new Insets(24));

        Label title = new Label(tab.title());
        title.getStyleClass().add("adb-page__title");

        Label error = new Label(statusBar.message());
        error.getStyleClass().add("adb-grid__state-text--error");
        error.setWrapText(true);

        Button retry = new Button("重试");
        retry.getStyleClass().addAll("adb-button", "adb-button--ghost");
        retry.setOnAction(event -> status("请重启应用以重新连接演示库"));
        page.getChildren().addAll(title, error, retry);
        return page;
    }

    // ─────────────────────────── 反馈 ───────────────────────────

    private void refresh() {
        if (workspace.refreshActive()) {
            status("刷新当前页 · " + LocalTime.now().withNano(0));
            toast("已刷新");
        } else {
            status("当前没有可刷新的数据页");
            toast("当前页没有数据");
        }
    }

    private String currentDensity() {
        String value = settings.get("ui.density", "normal");
        return switch (value) {
            case "compact", "comfort" -> value;
            default -> "normal";
        };
    }

    private void changeDensity(String density) {
        String value = applyDensity(density);
        status("密度：" + switch (value) {
            case "compact" -> "紧凑";
            case "comfort" -> "宽松";
            default -> "标准";
        });
    }

    private String applyDensity(String density) {
        String value = switch (density) {
            case "compact", "comfort" -> density;
            default -> "normal";
        };
        getStyleClass().removeAll("density-compact", "density-normal", "density-comfort");
        getStyleClass().add("density-" + value);
        settings.set("ui.density", value);
        // A19：密度以前只影响对象树，数据网格行高不变。现在一起作用。
        if (workspace != null) {
            workspace.applyDensity(value);
        }
        // 勾选态跟着动作走：菜单 / 命令面板读的都是同一份 selected。
        for (String id : new String[]{"density-compact", "density-normal", "density-comfort"}) {
            actions.find(id).ifPresent(action ->
                    action.setSelected(id.endsWith(value)));
        }
        return value;
    }

    private void status(String message) {
        statusBar.setMessage(message);
    }

    private void toast(String message) {
        toast(message, null);
    }

    private void toast(String message, Runnable action) {
        toastLabel.setText(message);
        toastAction.setVisible(action != null);
        toastAction.setManaged(action != null);
        toastAction.setOnAction(event -> {
            hideToast();
            if (action != null) {
                action.run();
            }
        });
        toast.setVisible(true);
        toast.setManaged(true);
        startToast(action == null ? 1.8 : 6.0);
    }

    /** 「结果不可撤销」类提示：没有撤销按钮，但用户需要更长时间反应过来（4.5-21 第三档）。 */
    private void toastUnreversible(String message) {
        toastLabel.setText(message);
        toastAction.setVisible(false);
        toastAction.setManaged(false);
        toast.setVisible(true);
        toast.setManaged(true);
        startToast(4.0);
    }

    private void startToast(double seconds) {
        if (toastTimer != null) {
            toastTimer.stop();
        }
        toastTimer = new PauseTransition(Duration.seconds(seconds));
        toastTimer.setOnFinished(event -> hideToast());
        toastTimer.play();
    }

    /** 当前 Toast 的停留时长（毫秒）——供 4.5-21 的门禁断言三档分级。 */
    long toastDelayMillis() {
        return toastTimer == null ? 0 : (long) toastTimer.getDuration().toMillis();
    }

    private void hideToast() {
        toast.setVisible(false);
        toast.setManaged(false);
        toastAction.setVisible(false);
        toastAction.setManaged(false);
    }

    private void showInfo(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(content);
        if (stage != null) {
            alert.initOwner(stage);
        }
        if (getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            alert.getDialogPane().getStyleClass().add("adb-dialog");
        }
        alert.showAndWait();
    }

    private void showShortcuts() {
        showInfo("快捷键", """
                ⌘N        新建连接
                ⇧⌘N       新建查询
                ⌘O        打开 SQL 文件
                ⌘K        命令面板
                ⌘F        搜索对象 / 查询页查找
                ⌘R        执行当前语句
                ⇧⌘R       执行全部语句
                ⌘.        取消执行
                ⌘E        解释执行计划
                ⌘/        注释 / 取消注释
                ⌥⇧F       格式化 SQL
                ⌘S        保存 SQL
                ⌘\\        折叠 / 展开侧栏
                ⌘,        设置
                F5        刷新 / 重跑当前语句
                ⌘W        关闭标签
                ⇧⌘W       关闭窗口
                F1        本页
                """);
    }

    private static String readable(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable cursor = error;
        String message = null;
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().isBlank()) {
                message = cursor.getMessage();
            }
            cursor = cursor.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static KeyCombination key(String value) {
        return KeyCombination.keyCombination(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
