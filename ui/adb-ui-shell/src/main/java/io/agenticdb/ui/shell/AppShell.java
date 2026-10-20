package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.session.SessionManager;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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
import java.util.List;
import java.util.Map;
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
    private final BorderPane root = new BorderPane();
    private final Label toastLabel = new Label();
    private final Button toastAction = new Button("撤销");
    private final HBox toast = new HBox(10, toastLabel, toastAction);
    private final String connectionName;
    private final DbSession session;
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
        this.connectionName = demoPath.getFileName() == null
                ? DemoDatabase.DEFAULT_FILE_NAME : demoPath.getFileName().toString();

        DbSession opened = null;
        List<SchemaObject> objects = List.of();
        String error = null;
        try {
            DemoDatabase.ensure(demoPath);
            opened = sessions.open("demo", ConnectionSpec.sqlite(demoPath));
            objects = schemaService.listObjects(opened);
        } catch (Exception e) {
            error = readable(e);
        }
        this.session = opened;

        this.sideBar = new SideBar(actions, objects, connectionName,
                this::openTab, statusBar::setMessage);
        this.sideBar.setOnRefreshObjects(this::reloadObjects);
        this.workspace = new WorkspaceView(actions, connectionName,
                this::createGridPage, this::createQueryPage);
        this.palette = new CommandPalette(actions);

        registerActions();
        actions.validateUniqueAccelerators();

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

        statusBar.setConnection(connectionName);
        statusBar.setReadonly(true);
        statusBar.setRange("—");
        if (error != null) {
            status("演示库不可用：" + error);
        } else {
            status("已连接 " + connectionName + " · " + objects.size() + " 个对象");
        }
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
        String initial = System.getProperty("adb.open",
                System.getenv().getOrDefault("ADB_OPEN", "")).trim();
        if (!initial.isEmpty()) {
            openTab(new TabItem("grid:" + initial, initial, TabKind.GRID, initial));
        }
        String initialQuery = System.getProperty("adb.query",
                System.getenv().getOrDefault("ADB_QUERY", "")).trim();
        if (!initialQuery.isEmpty()) {
            String sql = initialQuery.equals("1") || initialQuery.equalsIgnoreCase("true")
                    ? "SELECT id, name, email, active FROM users ORDER BY id LIMIT 100;"
                    : initialQuery;
            String id = "query:" + System.nanoTime();
            initialQuerySql.put(id, sql);
            openTab(new TabItem(id, "查询示例", TabKind.QUERY));
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
                key("Shortcut+N"), () -> showInfo("新建连接",
                        "连接向导将在 P5 接入。\n\n"
                                + "P0 只验证外壳；P1 会先用固定的 SQLite 演示库跑通数据页。"));
        actions.register("new-query", "新建查询", "文件",
                key("Shift+Shortcut+N"), this::openNewQuery);
        actions.register("open-sql", "打开 SQL 文件…", "文件",
                key("Shortcut+O"), this::openSqlFile);
        actions.register("tab.close", "关闭标签", "文件",
                key("Shortcut+W"), () -> tabBar.closeActive());
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
        actions.register("demo.open", "打开演示表 users", "数据库",
                null, () -> openTab(new TabItem("grid:users", "users", TabKind.GRID, "users")));

        actions.register("diagnostics", "诊断", "工具",
                null, () -> openTool("诊断"));
        actions.register("import-export", "导入 / 导出", "工具",
                null, () -> openTool("导入 / 导出"));
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
        MainToolBar toolBar = new MainToolBar(actions, connectionName, this::status);

        VBox top = new VBox(menuBar, toolBar);
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
        ButtonType discard = new ButtonType("放弃修改", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("未保存的修改");
        alert.setHeaderText(tab.title() + " 有 " + count + " 处未保存修改");
        alert.setContentText("关闭标签会丢弃这些修改。取消后编辑缓冲原样保留。");
        alert.getButtonTypes().setAll(discard, cancel);
        styleDialog(alert);
        return alert.showAndWait().orElse(cancel) == discard;
    }

    private boolean confirmDiscardAll() {
        if (!tabBar.hasDirtyTabs()) {
            return true;
        }
        ButtonType discard = new ButtonType("放弃并退出", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("未保存的修改");
        alert.setHeaderText("仍有标签包含未保存修改");
        alert.setContentText("退出会丢弃所有编辑缓冲。");
        alert.getButtonTypes().setAll(discard, cancel);
        styleDialog(alert);
        return alert.showAndWait().orElse(cancel) == discard;
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
        openTab(new TabItem("structure:" + name, name, TabKind.STRUCTURE, name));
        status("打开结构：" + name);
    }

    private void openTool(String title) {
        openTab(new TabItem("tool:" + title, title, TabKind.TOOL));
        status("打开工具：" + title);
    }

    // ─────────────────────────── 数据页 ───────────────────────────

    private Node createGridPage(TabItem tab) {
        if (session == null) {
            return startupErrorPage(tab);
        }
        DataGridPage page = new DataGridPage(session, schemaService, tab.target(), connectionName,
                pageFeedback(), this::openStructure, dbExecutor, settings);
        page.setDirtyListener(tab::markDirty);
        return page;
    }

    private QueryPage createQueryPage(TabItem tab) {
        if (session == null) {
            return null;
        }
        String initial = initialQuerySql.remove(tab.id());
        File file = initialQueryFile.remove(tab.id());
        QueryPage page = new QueryPage(session, connectionName, initial, dbExecutor, settings,
                pageFeedback());
        if (file != null) {
            page.setCurrentFile(file);
            page.markClean();
        }
        page.setDirtyListener(tab::markDirty);
        page.setStateListener(this::updateQueryActions);
        return page;
    }

    private DataGridPage.Feedback pageFeedback() {
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
            public void toast(String message) {
                AppShell.this.toast(message);
            }

            @Override
            public void toast(String message, Runnable action) {
                AppShell.this.toast(message, action);
            }
        };
    }

    private void openNewQuery() {
        queryCounter++;
        String title = "未命名查询 " + queryCounter;
        openTab(new TabItem("query:" + System.nanoTime(), title, TabKind.QUERY));
        status("新建查询：" + title);
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
        String id = "query:" + System.nanoTime();
        initialQuerySql.put(id, sql);
        initialQueryFile.put(id, file);
        openTab(new TabItem(id, file.getName(), TabKind.QUERY));
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
    }

    private void reloadObjects() {
        if (session == null) {
            status("演示库未连接，无法刷新对象");
            return;
        }
        status("正在刷新对象…");
        dbExecutor.submit(() -> {
            try {
                List<SchemaObject> objects = schemaService.listObjects(session);
                Platform.runLater(() -> {
                    sideBar.setObjects(objects);
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
        if (toastTimer != null) {
            toastTimer.stop();
        }
        toastTimer = new PauseTransition(Duration.seconds(action == null ? 1.8 : 6));
        toastTimer.setOnFinished(event -> hideToast());
        toastTimer.play();
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
