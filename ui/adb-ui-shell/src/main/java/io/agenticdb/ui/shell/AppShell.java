package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.ViewDefinition;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.ConnectionStore;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.session.SessionManager;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    // ⭐ 侧栏宽度来自设计令牌，不再是魔数。
    //    以前是 180/420/240/48，与 tokens.json 的 160/480/240/48 还不一致 ——
    //    令牌是唯一真相，代码向令牌对齐。
    private static final int SIDEBAR_MIN = io.agenticdb.ui.Tokens.W_SIDEBAR_MIN_LIGHT;
    private static final int SIDEBAR_MAX = io.agenticdb.ui.Tokens.W_SIDEBAR_MAX_LIGHT;
    private static final int SIDEBAR_DEFAULT = io.agenticdb.ui.Tokens.W_SIDEBAR_LIGHT;
    private static final int SIDEBAR_COLLAPSED = io.agenticdb.ui.Tokens.W_SIDEBAR_COLLAPSED_LIGHT;

    // ⭐ 右侧详情面板（规格书 §6）：默认 300，夹在 220–420 之间。
    //    与侧栏一样走「令牌给档位、设置记忆实际值」的模式，
    //    不在这里另立一套魔数。
    private static final int DETAILS_MIN = ObjectDetailsPanel.WIDTH_MIN;
    private static final int DETAILS_MAX = ObjectDetailsPanel.WIDTH_MAX;
    private static final int DETAILS_DEFAULT = ObjectDetailsPanel.WIDTH_DEFAULT;

    private final SettingsStore settings;
    /** ⭐ P2-3：查询历史（连接 / 表 / 时间）。数据页「历史」与详情面板共用。 */
    private final QueryHistory history;
    private final ThemeManager themeManager;
    /** ⭐ P7：启动阶段打点（首屏耗时 / 各阶段 / 关于弹窗里回显）。 */
    private final io.agenticdb.ui.StartupTrace startup;
    private final ActionRegistry actions = new ActionRegistry();
    private final SessionManager sessions = new SessionManager();
    private final SchemaService schemaService = new SchemaService();
    /** ⭐ A16：每连接一个执行器（不再全局共用一条线程）。 */
    private final DbExecutors executors = new DbExecutors();

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "adb-db");
        thread.setDaemon(true);
        return thread;
    });

    private final StatusBar statusBar = new StatusBar();
    private final TabBar tabBar = new TabBar(actions);
    private final SideBar sideBar;
    private final WorkspaceView workspace;
    /**
     * ⭐ N33：常驻的「对象」标签托管对象清单页（就是表清单页）。
     *
     * <p>按连接构建 —— {@link #objectPageConnection} 记着它是哪个库的；
     * 切库后 {@link #refreshObjectHome()} 会让 WorkspaceView 重新走工厂。
     */
    private TableListPage objectPage;
    private String objectPageConnection;
    /** ⭐ N1–N5：当前可操作的表清单页（工具栏 / 右键动作按它取选择）。 */
    private TableListPage activeTableListPage;
    /**
     * ⭐ R34 N2：Navicat 的 {@code Table} 顶级菜单是**上下文菜单** —— 只在活动页签是表时出现
     * （查询页那张实拍的菜单栏里就没有它）。由 {@link #showTab} 开合。
     */
    private javafx.scene.control.Menu tableContextMenu;
    /** 表级 DDL 协调者（懒建 —— 构造时 stage 还没有）。 */
    private TableDdl tableDdl;
    private final CommandPalette palette;
    private final ConnectionStore connectionStore;
    private final SavedQueries savedQueries;
    /** ⭐ N24：已保存查询的对象清单页（单实例 —— 同一个标签反复打开不重建）。 */
    private SavedQueriesPage savedQueriesPage;
    private final CredentialStore credentials;
    private final ObservableList<ConnectionProfile> connections = FXCollections.observableArrayList();
    private final Map<String, List<SchemaObject>> objectsByConnection = new HashMap<>();
    private final Map<String, ConnectionProfile> profilesById = new HashMap<>();
    /**
     * ⭐ U1：catalog 虚拟连接（{@code 父id@库名}）。
     *
     * <p>只活在内存里，<b>绝不落盘</b> —— 用户的连接档案保持原样
     * （database 还是他保存时的那个库）。切库 = 激活一个虚拟连接，
     * 它有自己的人会话、自己的标签，父连接一个字节都没改。
     */
    private final Map<String, ConnectionProfile> catalogVariants = new HashMap<>();
    /** 已经读过 catalog 清单的连接（懒加载，读失败不反复试）。 */
    private final Set<String> catalogsLoaded = new HashSet<>();
    /** ⭐ G1：库连上之后要做的事（查询页选库 → 连好再开页，不能开着空页等会话）。 */
    private Runnable pendingAfterCatalogConnect;
    private final BorderPane root = new BorderPane();
    private final ObjectDetailsPanel details = new ObjectDetailsPanel();
    private final Label toastLabel = new Label();
    private final Button toastAction = new Button(I18n.t("action.edit.undo"));
    private final HBox toast = new HBox(10, toastLabel, toastAction);
    private String connectionName;
    private DbSession session;
    private ConnectionProfile activeProfile;
    private AppBar appBar;
    private final Map<String, String> initialQuerySql = new HashMap<>();
    private final Map<String, String> initialQueryName = new HashMap<>();
    private final Map<String, File> initialQueryFile = new HashMap<>();
    private int queryCounter;

    private Stage stage;
    private Region resizer;
    private Region detailsResizer;
    private int sidebarWidth;
    private boolean sidebarCollapsed;
    private int detailsWidth;
    private boolean detailsVisible;
    private PauseTransition toastTimer;
    /** ⭐ 第二十一轮：默认密度从 compact(24) 改判 normal(28)（见 {@link #initialDensity()}）。 */
    private String density = "normal";

    public AppShell(SettingsStore settings, ThemeManager themeManager) {
        this(settings, themeManager, new io.agenticdb.ui.StartupTrace());
    }

    /**
     * ⭐ P7：带启动打点的构造器。测试与 `App` 共用一条路径，
     * 避免「生产环境记、测试环境不记」两套行为。
     */
    public AppShell(SettingsStore settings, ThemeManager themeManager,
                    io.agenticdb.ui.StartupTrace startup) {
        this.startup = startup;
        startup.mark("shell.construct");
        this.settings = settings;
        this.history = new QueryHistory(settings);
        this.themeManager = themeManager;

        Path demoPath = DemoDatabase.defaultPath();
        DemoDatabase.ensure(demoPath);
        startup.mark("shell.demo-db");
        this.connectionStore = ConnectionStore.defaultStore();
        this.savedQueries = new SavedQueries(settings);
        this.credentials = connectionStore.credentials();
        this.connectionStore.ensureDemo(demoPath);
        this.connections.setAll(connectionStore.load());
        this.connectionName = demoPath.getFileName() == null
                ? DemoDatabase.DEFAULT_FILE_NAME : demoPath.getFileName().toString();
        startup.mark("shell.stores");

        this.sideBar = new SideBar(actions, List.of(), connectionName,
                this::openTab, statusBar::setMessage, "demo", connections,
                this::selectConnection, settings);
        this.sideBar.setOnRefreshObjects(this::reloadObjects);
        this.sideBar.setOnSelectSchema(this::switchSchema);
        // ⭐ U1：catalog 树 —— 懒加载库清单 + 双击切库（重连）。
        this.sideBar.setOnLoadCatalogs(this::loadCatalogsFor);
        this.sideBar.setOnSwitchCatalog(this::switchCatalog);
        // ⭐ T1–T4 表级生命周期：右键菜单 / 数据库菜单 / 命令面板三条入口进同一个 TableDdl。
        this.sideBar.setOnTableDdl(this::runTableDdl);
        // ⭐ N33：对象首页撤掉后，例程定义体由树的双击 / Enter 打开。
        this.sideBar.setOnOpenRoutine(request -> openRoutine(request.connectionId(),
                request.name(), request.procedure()));
        // ⭐ UX-6：连接节点下直接列「已保存查询」；点一条 = 新查询页 + 预填 SQL。
        this.sideBar.setSavedQuerySource(savedQueries::all);
        this.sideBar.setOnOpenSavedQuery(this::openSavedQuery);
        // ⭐ N24：双击 / Enter「Queries」分组 = 打开对象清单页（不是弹窗）。
        this.sideBar.setOnOpenSavedQueries(this::openSavedQueries);
        // ⭐ 多连接对象树：展开一个没读过的连接时，去连它、读它的对象。
        this.sideBar.setOnLoadObjects(this::loadObjectsFor);
        // ⭐ S-3：展开表节点时才读它的列（懒加载，见 loadColumnsFor）。
        this.sideBar.setOnLoadColumns(this::loadColumnsFor);
        // ⭐ UX-5：连接节点的存活点。**判据只能来自 SessionManager** ——
        //    侧栏没有会话，它自己判断就是第二套真相；外壳也不许替它编一个。
        this.sideBar.setConnectionLiveness(this::isConnectionAlive);
        // ⭐ D-2：同理，「复制成 SELECT」的语句由**节点所属连接**的方言生成 ——
        //    侧栏只问「这个连接用哪种写法」，自己不拼 SQL（引号/分页都跟库走）。
        this.sideBar.setDialectResolver(connectionId -> {
            DbSession owner = sessionOrCurrent(connectionId);
            return owner == null ? null : owner.dialect();
        });
        // ⭐ §6：右侧详情面板。数据必须由外壳提供 —— 它才认识会话、
        //    执行器和 SchemaService；面板自己一个数据库概念都没有，
        //    于是「不编造数据」这条规则在类型上就成立了。
        this.details.setLoader(this::loadObjectDetails);
        this.details.setActions(new ObjectDetailsPanel.Actions() {
            @Override
            public void openData(ObjectDetailsPanel.Subject subject) {
                if (subject.kind() == SchemaService.ObjectKind.TABLE
                        || subject.kind() == SchemaService.ObjectKind.VIEW) {
                    openTab(new TabItem("grid:" + subject.connectionId() + ":" + subject.name(),
                            subject.name(), TabKind.GRID, subject.name(), subject.connectionId()));
                }
            }

            @Override
            public void openStructure(ObjectDetailsPanel.Subject subject) {
                // ⚠️ 必须写 AppShell.this —— 匿名类自己的 openStructure 会把
                //    外层同名方法遮住，直接调用会递归 / 签名不匹配。
                AppShell.this.openStructure(subject.connectionId(), subject.name());
            }

            @Override
            public void openDdl(ObjectDetailsPanel.Subject subject) {
                // 不新开标签页：结构页里那个 DDL 子页签本来就会读真 DDL、
                // 本来就能复制。再开一个只读文本框等于把同一个东西放两处。
                AppShell.this.openStructure(subject.connectionId(), subject.name());
                StructurePage page = workspace.activeStructure();
                if (page != null) {
                    page.showDdl();
                }
            }

            @Override
            public void refresh(ObjectDetailsPanel.Subject subject) {
                details.reload();
            }
        });
        this.sideBar.setOnInspect(this::inspectTarget);
        startup.mark("shell.sidebar");
        this.workspace = new WorkspaceView(actions, connectionName,
                this::createGridPage, this::createQueryPage, this::createStructurePage,
                this::createConnectionsPage, tab -> objectHomePage());
        this.palette = new CommandPalette(actions);
        // 侧栏「查询历史」接真实功能：QueryHistory 本来就可用，只是侧栏开错了页面。
        this.workspace.setHistory(history, this::openQueryWith);
        startup.mark("shell.views");

        registerActions();
        actions.validateUniqueAccelerators();
        // 4.5-30：动作被禁用时给出解释，不再静默 no-op。
        actions.setFeedback(statusBar::setMessage);
        startup.mark("shell.actions");

        tabBar.setOnActivate(this::showTab);
        tabBar.setOnClose(this::tabClosed);
        tabBar.setCloseGuard(this::confirmClose);
        sideBar.setOnCollapsed(this::sidebarCollapsedChanged);

        this.sidebarWidth = clamp(settings.getInt("ui.sidebar.width", SIDEBAR_DEFAULT),
                SIDEBAR_MIN, SIDEBAR_MAX);
        this.sidebarCollapsed = settings.getBoolean("ui.sidebar.collapsed", false);
        // ⭐ §6：详情面板默认**关闭**（它是有用的补充，不是该常驻的第三栏）。
        this.detailsWidth = clamp(settings.getInt("ui.details.width", DETAILS_DEFAULT),
                DETAILS_MIN, DETAILS_MAX);
        this.detailsVisible = settings.getBoolean("ui.details.visible", false);

        getStyleClass().add("app-shell");
        buildUi();
        startup.mark("shell.ui");
        applyDensity(initialDensity());
        sideBar.setCollapsed(sidebarCollapsed);
        applySidebarWidth();
        applyDetailsVisibility();
        updateTabCloseEnabled();
        startup.mark("shell.chrome");

        refreshProfiles();
        // ⭐ S5：常驻的「对象」标签 —— 永远排第一、不可关闭。
        // 用 window.pageType.object 而不是 category.object：中文两侧都是「对象」，
        // 但 category.object 的英文是单数 "Object"，当页面标题读着不对，
        // pageType 那一档才是 "Objects"（Navicat 的对象页头也是复数）。
        tabBar.openPermanent(new TabItem("object-home",
                I18n.t("window.pageType.object"), TabKind.OBJECT));
        ConnectionProfile demo = profileById("demo");
        if (demo != null) {
            // ⭐ A15：启动时的演示库连接**同步**做 —— 它是本地 SQLite（毫秒级），
            //    而且窗口还没出现：此时阻塞没有任何观感问题。
            //    用户自己连远程库时才走后台（那边可能很慢）。
            connectProfileBlocking(demo, false);
        }
        startup.mark("shell.demo");
        statusBar.setRange("—");
        if (activeProfile == null) {
            status(I18n.t("shell.demoUnusable", connectionName));
        } else {
            status(I18n.t("shell.connectedObjects", connectionName,
                    objectsByConnection.getOrDefault(activeProfile.id(), List.of()).size()));
        }
    }

    // ─────────────────── T1–T4 表级 + V1–V3 视图级生命周期 ───────────────────

    /** 表级 / 视图级 DDL 的协调者。懒建 —— 构造时 stage 还没有。 */
    private TableDdl tableDdl() {
        if (tableDdl == null) {
            tableDdl = new TableDdl(
                    this::sessionFor,
                    this::executorFor,
                    this::status,
                    this::existingTablesFor,
                    this::viewDefinitionFor,
                    this::afterTableChanged,
                    () -> stage,
                    () -> getScene() == null ? List.of()
                            : List.copyOf(getScene().getStylesheets()));
        }
        return tableDdl;
    }

    /** 按连接 id 取会话（catalog 虚拟连接也在内）。 */
    private DbSession sessionFor(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        return sessions.find(connectionId).orElse(null);
    }

    /**
     * ⭐ D-2：按连接 id 取会话，取不到回落到当前会话。
     *
     * <p>「复制成 SELECT」这类语句必须按**对象所属连接**的方言生成；
     * 老标签没带 connectionId 时（单连接时代建的）才用当前会话兜底。
     */
    private DbSession sessionOrCurrent(String connectionId) {
        DbSession found = sessionFor(connectionId);
        return found == null ? session : found;
    }

    /** 按连接 id 取执行器（同一连接的库操作必须串行）。 */
    private java.util.concurrent.Executor executorFor(String connectionId) {
        return executors.forConnection(connectionId);
    }

    /** 表级 DDL 完成后：对象树、对象首页、已打开的表清单都要重新读。 */
    private void afterTableChanged(String connectionId) {
        reloadObjects(connectionId == null ? activeProfileId() : connectionId);
        workspace.refreshTableLists();
        refreshObjectHome();
    }

    // ─────────────────── 截图专用：等连接就绪再弹模态框 ───────────────────

    private static final int POP_READY_MAX_POLLS = 100;   // 100 × 100ms ≈ 10s

    /** 等到 session 就绪再弹「新建表」；超时就放弃（至少还能拍到主窗口）。 */
    private void popNewTableWhenReady(int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> popNewTableWhenReady(attempt + 1));
            return;
        }
        // ⚠️ 必须再包一层 runLater：showAndWait() 直接写在 Timeline 的帧事件里，
        //    对话框会被提前关掉（实测拍到的图里状态栏是「已取消新建表」）。
        //    包 runLater 之后走的是普通 FX 事件，与 IndexDialog 那条路径一致。
        // ⭐ R30-D：设计器是多页弹窗，空列表 / 空预览那一页证不了什么 ——
        //    ADB_NEW_TABLE_POSE=index|foreign-key|trigger|options|sql 让验收帧
        //    落在对应页上，并带一条按当前字段算出来的对象。
        String pose = text("adb.newTablePose", "ADB_NEW_TABLE_POSE");
        javafx.application.Platform.runLater(
                () -> tableDdl().createTable(activeProfileId(), pose));
    }

    /**
     * ⭐ R42 N28-a：等 session 就绪再弹「新建视图」。
     *
     * <p>与 {@link #popNewTableWhenReady} 完全同形 —— 两个坑都一样：
     * 模态框要包 runLater（否则截图调度排不上），连接是异步建的（此刻可能还没就绪）。
     */
    private void popNewViewWhenReady(int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> popNewViewWhenReady(attempt + 1));
            return;
        }
        String pose = text("adb.newViewPose", "ADB_NEW_VIEW_POSE");
        javafx.application.Platform.runLater(
                () -> tableDdl().createView(activeProfileId(), pose));
    }

    /**
     * ⭐ R42 N28-a：等 session 就绪再打开「设计视图」。
     *
     * <p>这一条拍的是<b>读回真定义</b>那一态：正文不是脚本填的，
     * 是从演示库的 {@code v_active_users} 里读出来再切掉 {@code CREATE VIEW} 头的 ——
     * 「读不出形状就不开弹窗」因此也能在帧里证一次（开出来了 = 读到了）。
     */
    private void popDesignViewWhenReady(int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> popDesignViewWhenReady(attempt + 1));
            return;
        }
        tableDdl().designView(activeProfileId(), "v_active_users");
    }

    /** 等到 session 就绪再读结构、弹「新建索引」。 */
    private void popIndexDialogWhenReady(int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> popIndexDialogWhenReady(attempt + 1));
            return;
        }
        DbSession current = session;
        // ⭐ 按连接取执行器（结构内省与数据页的活要串行）。
        executors.forConnection(activeProfileId()).execute(() -> {
            try {
                TableStructure structure = schemaService.structure(current, "users");
                javafx.application.Platform.runLater(() -> {
                    IndexDialog dialog = IndexDialog.create(current, structure);
                    if (flag("adb.indexPose", "ADB_INDEX_POSE")) {
                        dialog.poseForShots();
                    }
                    dialog.show(stage, getScene() == null ? List.of()
                            : List.copyOf(getScene().getStylesheets()));
                });
            } catch (Exception ignored) {
                // 读不到结构就不弹 —— 截图脚本至少还能拍到结构页。
            }
        });
    }

    /** 等到连接就绪再开表清单（截图脚本用；表清单构造时就要 session）。 */
    private void openTableListWhenReady(int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> openTableListWhenReady(attempt + 1));
            return;
        }
        javafx.application.Platform.runLater(this::openTableList);
    }

    private void pollAgain(int attempt, Runnable next) {
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(100), event -> next.run()));
        timeline.setCycleCount(1);
        timeline.play();
    }

    /** 截图用：等连接就绪后把详情面板指向某张表（连接是异步建立的，此刻可能还是 null）。 */
    private void showDetailsWhenReady(String table, int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> showDetailsWhenReady(table, attempt + 1));
            return;
        }
        Platform.runLater(() -> inspectTarget(
                new SideBar.InspectTarget(activeProfileId(), table, null,
                        SideBar.InspectTarget.Topic.TABLE)));
    }

    /**
     * 截图用（R34 收官）：等连接就绪后按路由开一页诊断。
     *
     * <p>诊断页原本只在「树里选中表 → 诊断」这条路上（{@code SideBar.onSelected}
     * 读的是 selectedNode），而树节点没有公开的选中入口 —— 与 {@code ADB_DETAILS}
     * 同一个绕法：直接喂路由，走的仍是 {@code openTool} 那条真路径。
     */
    private void poseDiagnosticsWhenReady(int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> poseDiagnosticsWhenReady(attempt + 1));
            return;
        }
        Platform.runLater(() -> sideBar.poseToolForShots(SideBar.TOOL_DIAGNOSTICS + "users",
                I18n.t("sidebar.tool.diagnostics", "users")));
    }

    /**
     * 截图用（R34 收官）：表清单 → 选中一行 → 「重命名」弹窗。
     *
     * <p>两轮轮询各有各的理由：连接是异步建的（第一轮），行数据也是异步灌的
     * （第二轮 —— 空列表时 {@code renameSelected()} 只会报「先选中一张表」）。
     * {@code openTableList()} 每调一次就新造一个页，所以只在还没有页时开。
     */
    private void poseRenameWhenReady(String table, int attempt) {
        if (session == null) {
            if (attempt >= POP_READY_MAX_POLLS) {
                return;
            }
            pollAgain(attempt, () -> poseRenameWhenReady(table, attempt + 1));
            return;
        }
        Platform.runLater(() -> {
            if (activeTableListPage == null) {
                openTableList();
            }
            TableListPage page = activeTableListPage;
            javafx.collections.ObservableList<io.agenticdb.introspect.TableMeta> items =
                    page == null ? null : page.table().getItems();
            if (items == null || items.isEmpty()) {
                if (attempt < POP_READY_MAX_POLLS) {
                    pollAgain(attempt, () -> poseRenameWhenReady(table, attempt + 1));
                }
                return;
            }
            int index = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).name().equals(table)) {
                    index = i;
                    break;
                }
            }
            page.table().getSelectionModel().select(index);
            page.renameSelected();
        });
    }

    /** 截图用（R34 收官）：收藏夹与已存查询两个管理器在新 prefs 节点里是空的。 */
    private void poseSeedSavedQueriesForShots() {
        savedQueries.save("users-top-100",
                "SELECT id, name, email FROM users ORDER BY id LIMIT 100;");
        savedQueries.saveSnippet("count-rows", "SELECT COUNT(*) FROM users;");
        // 树是在种子之前建的 —— 不重建的话，帧里 Queries 组是空的。
        sideBar.refreshSavedQueries();
    }

    /** 截图用：等结构页真的建出来再跳到 DDL 子页签（页签是异步开的）。 */
    private void showDdlWhenReady(int attempt) {
        Platform.runLater(() -> {
            StructurePage active = workspace.activeStructure();
            if (active == null) {
                if (attempt < POP_READY_MAX_POLLS) {
                    pollAgain(attempt, () -> showDdlWhenReady(attempt + 1));
                }
                return;
            }
            active.showDdl();
        });
    }

    /** 截图用：等数据页建出来再切到「概览」切面（页签与行数都是异步到的）。 */
    private void showOverviewWhenReady(int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid == null) {
                if (attempt < POP_READY_MAX_POLLS) {
                    pollAgain(attempt, () -> showOverviewWhenReady(attempt + 1));
                }
                return;
            }
            grid.selectSection("overview");
        });
    }

    /** ⭐ 截图 / 验收（R34 第十六批）：切到某个只读切面（索引 / 外键 / 触发器 / SQL）。 */
    private void showSectionWhenReady(String sectionId, int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid == null) {
                if (attempt < POP_READY_MAX_POLLS) {
                    pollAgain(attempt, () -> showSectionWhenReady(sectionId, attempt + 1));
                }
                return;
            }
            grid.selectSection(sectionId);
        });
    }

    /** 截图用：等 users 网格真的把行灌进来，再摆出选中 / 光标 / 抽屉三个交互态。 */
    private void poseGridCellWhenReady(int attempt) {
        Platform.runLater(() -> {
            // 摆不出来 = 页签还没建 / 行还没灌进来（数据页是异步加载的），继续等。
            // ⚠️ 列选 name 而不是 bio：抽屉浮在网格右侧约 480px，光标格要是落在
            //    抽屉底下（bio 本来就得横向滚才看得见），这一帧就拍不到它要拍的东西。
            DataGridPage grid = workspace.activeGrid();
            if (grid != null && grid.poseInteractionStates(1, "name")) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseGridCellWhenReady(attempt + 1));
            }
        });
    }

    /** 截图用：等 users 网格灌完行再打开查找条（行没到就摆不出来，继续轮询）。 */
    private void poseGridFindWhenReady(int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            // needle 与 poseInteractionStates 硬编码列同一取舍：帧要的是「条开了、
            // 有 needle、能点下一个」，具体词命中多少条由演示数据决定，不写死。
            if (grid != null && grid.poseFindBar("User 0")) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseGridFindWhenReady(attempt + 1));
            }
        });
    }

    /**
     * 截图用（R34 收官）：等列头建出来再点开那颗漏斗，摆出「按值筛选」浮窗。
     * 与 {@link #poseGridFindWhenReady} 同一契约：页没就位就继续轮询。
     */
    private void poseGridFilterWhenReady(int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid != null && grid.poseFilterPanel("name")) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseGridFilterWhenReady(attempt + 1));
            }
        });
    }

    /** 截图用：等 users 网格灌完行再切表单视图、选中第一条记录。 */
    private void poseGridFormWhenReady(int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid != null && grid.poseFormView()) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseGridFormWhenReady(attempt + 1));
            }
        });
    }

    /** 截图用（R38 N31）：等网格出数据后打开内嵌 Filter & Sort 面板并摆出草稿。 */
    private void poseGridFilterSortWhenReady(int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid != null && grid.poseFilterSortPanel()) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseGridFilterSortWhenReady(attempt + 1));
            }
        });
    }

    /** 截图用：等数据页读完表结构再弹导入向导（showAndWait 只能从事件里开）。 */
    private void poseImportWizardWhenReady(Path file, int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid != null && grid.poseImportWizardForShots(file)) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseImportWizardWhenReady(file, attempt + 1));
            }
        });
    }

    /** 截图用：等数据页读完表结构再弹导出向导。 */
    private void poseExportWizardWhenReady(String pose, int attempt) {
        Platform.runLater(() -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid != null && grid.poseExportWizardForShots(pose)) {
                return;
            }
            if (attempt < POP_READY_MAX_POLLS) {
                pollAgain(attempt, () -> poseExportWizardWhenReady(pose, attempt + 1));
            }
        });
    }

    /**
     * 截图用：写一份 GBK 演示 CSV（中文姓名），让导入向导的「编码」这一项
     * 在帧里真的起作用 —— UTF-8 文件按 GBK 解会乱码，证明不了什么。
     *
     * <p>姓名是<b>数据不是文案</b>，所以不进语言包（翻成英文这张帧就没意义了）；
     * 但也<strong>不留在 Java 字面量里</strong> —— 那样 `CJK_CEILING` 永远降不到 0，
     * 只能靠注释豁免，而豁免口子一旦开就有人拿它躲真文案。
     * 放进资源文件，源文件保持 UTF-8 可读可 diff，<b>写盘时才转 GBK</b>：
     * 转码这一步本来就是这张帧要证明的东西。
     */
    private static Path writeDemoImportFile() {
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "adb-demo-import-gbk.csv");
        try (java.io.InputStream in = AppShell.class.getResourceAsStream(
                "/io/agenticdb/ui/demo-import-utf8.csv")) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(file, text, java.nio.charset.Charset.forName("GBK"));
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /** 表清单动作的统一入口：没有打开的清单页时给一句人话，不静默 no-op。 */
    private void withActiveTableList(java.util.function.Consumer<TableListPage> action) {
        if (activeTableListPage == null) {
            status(I18n.t("shell.needTableList"));
            return;
        }
        action.accept(activeTableListPage);
    }

    /** 切到别的页面时，表清单动作不能再亮着（否则会操作一个看不见的页）。 */
    private void disableTableListActions() {
        for (String id : List.of("table-list.new", "table-list.open", "table-list.design",
                "table-list.rename", "table-list.truncate", "table-list.drop",
                "table-list.refresh", "table-list.select-all", "table-list.view-list",
                "table-list.view-grid", "table-list.new-ai", "table-list.import",
                "table-list.export")) {
            actions.find(id).ifPresent(action -> action.setEnabled(false));
        }
    }

    /** 侧栏转来的一次表级请求。 */
    private void runTableDdl(SideBar.TableRequest request) {
        TableDdl ddl = tableDdl();
        // ⭐ U1：请求自带连接 id（可能是 catalog 虚拟连接）——
        //    DDL 必须落在用户点的那个库上，不是「当前连接」上。
        String connectionId = request.connectionId();
        switch (request.action()) {
            case NEW_TABLE -> ddl.createTable(connectionId);
            case TRUNCATE_TABLE -> ddl.truncateTable(connectionId, request.tableName());
            case RENAME_TABLE -> ddl.renameTable(connectionId, request.tableName());
            case DROP_TABLE -> ddl.dropTable(connectionId, request.tableName());
            case NEW_VIEW -> ddl.createView(connectionId);
            case DESIGN_VIEW -> ddl.designView(connectionId, request.tableName());
            case DROP_VIEW -> ddl.dropView(connectionId, request.tableName());
            default -> status(I18n.t("shell.actionNotWired"));
        }
    }

    /** 某个连接里已经存在的表名 —— 建表 / 重命名要用它拒绝重名。 */
    private List<String> existingTablesFor(String connectionId) {
        List<SchemaObject> objects = objectsByConnection.get(connectionId);
        if (objects == null) {
            DbSession target = sessionFor(connectionId);
            if (target != null) {
                try {
                    objects = schemaService.listObjects(target);
                } catch (Exception ignored) {
                    objects = List.of();
                }
            }
        }
        if (objects == null) {
            return List.of();
        }
        return objects.stream().map(SchemaObject::name).toList();
    }

    /**
     * ⭐ R42 N28-a：读某个视图「已经存在库里的那条查询」。
     *
     * <p>DDL 的来源仍然是方言（{@code readDdl(..., view=true)}：MySQL 走
     * {@code SHOW CREATE VIEW}、SQLite 读 sqlite_master、PG 走 pg_get_viewdef），
     * 这一层只负责把 {@code CREATE VIEW … AS} 的头切掉
     * （{@link ViewDefinition#selectBody}）。
     *
     * <p><b>切不出来就返回 null</b>，让调用方说明原因并<b>不开设计器</b>：
     * 猜一个正文出来，用户点「更新视图」就把原定义覆盖了。
     *
     * <p>这里是同步的 JDBC 调用 —— 只在后台线程走（{@code TableDdl#designView} 负责调度）。
     */
    private String viewDefinitionFor(String connectionId, String view) {
        DbSession target = sessionFor(connectionId);
        if (target == null) {
            return null;
        }
        try {
            return ViewDefinition.selectBody(
                    target.dialect().readDdl(target.connection(), view, true));
        } catch (Exception ignored) {
            return null;
        }
    }

    // ─────────────────────────── 连接管理 ───────────────────────────

    /**
     * ⭐ 界面顺序必须与落盘顺序一致。
     *
     * <p>以前这里是直接 {@code add} 到末尾 —— 于是<b>刚连过的连接反而排到最后</b>，
     * 而磁盘上是「收藏 → 最近使用倒序」，重启之后顺序又变回来。
     * 同一份数据两种顺序，是这条待办（最近使用排序）真正没闭环的地方。
     */
    private void resortConnections() {
        FXCollections.sort(connections, ConnectionStore.displayOrder());
    }

    /** 供门禁核对顺序（与界面同一份列表，不是副本）。 */
    javafx.collections.ObservableList<ConnectionProfile> connectionsList() {
        return connections;
    }

    private void refreshProfiles() {
        profilesById.clear();
        for (ConnectionProfile profile : connections) {
            profilesById.put(profile.id(), profile);
        }
        // ⭐ U1：catalog 虚拟连接也登记进来 —— 标签页按连接 id 找档案时
        //    （connectionFor）不能找不到它，否则切回父连接后，
        //    挂在虚拟连接上的标签会全都解析到「当前连接」上。
        profilesById.putAll(catalogVariants);
        if (appBar != null) {
            // ⭐ U1：当前活跃的可能是 catalog 虚拟连接 —— 它也要进胶囊下拉，
            //    否则顶栏会显示「未连接」（下拉里按 id 找不到它）。
            List<ConnectionProfile> shown = connections;
            if (activeProfile != null && ConnectionProfile.isCatalogVariantId(activeProfile.id())) {
                shown = new ArrayList<>(connections);
                shown.add(activeProfile);
            }
            appBar.setConnections(shown,
                    activeProfile == null ? "demo" : activeProfile.id());
        }
        sideBar.setConnections(connections);
        // ⭐ UX-5：标签重名要补连接名 —— 档案名字只有外壳知道。
        //    放在这里（而不是构造时一次注入）：连接**改名**后标题里的后缀也得跟着变。
        tabBar.setConnectionLabels(id -> {
            ConnectionProfile profile = profileById(id);
            if (profile == null) {
                return id;
            }
            // ⭐ N7-b：catalog 虚拟连接的「连接」是它的父连接 —— 标题里的库名
            //    由下面那条注入，连接名再带一遍库名就重复了。
            if (ConnectionProfile.isCatalogVariantId(id)) {
                ConnectionProfile parent = profileById(
                        ConnectionProfile.parentOfCatalogVariant(id));
                return parent == null ? profile.name() : parent.name();
            }
            return profile.name();
        });
        tabBar.setConnectionCatalogs(id -> {
            ConnectionProfile profile = profileById(id);
            if (profile == null || !profile.kind().dialect().switchesCatalogByReconnect()) {
                return "";
            }
            return profile.database();
        });
    }

    private ConnectionProfile profileById(String id) {
        ConnectionProfile saved = profilesById.get(id);
        return saved != null ? saved : catalogVariants.get(id);
    }

    /**
     * ⭐ UX-5：这个连接现在有没有活的会话（侧栏存活点的判据）。
     *
     * <p>catalog 虚拟连接（{@code 父id@库名}）算在**父连接**头上：切库重连时
     * 父 id 上没有会话，只认父 id 会把「正在用的那个连接」画成灰色。
     */
    private boolean isConnectionAlive(String id) {
        if (id == null) {
            return false;
        }
        if (sessions.isOpen(id)) {
            return true;
        }
        String prefix = id + ConnectionProfile.CATALOG_VARIANT_SEPARATOR;
        return sessions.openIds().stream().anyMatch(open -> open.startsWith(prefix));
    }

    /**
     * ⭐ N33：常驻的「对象」标签 = 当前库的**对象清单页**（表清单页）。
     *
     * <p>Navicat 的标签栏第一个永远是「对象」，它列的就是这个库的对象。
     * 以前这里是一张自制的「对象首页」（药丸总览 + 类型筛选），Navicat 没有这个概念 ——
     * 本轮撤掉，六项表元数据 + 七个操作键全部收进这一页。
     *
     * <p>页面按连接构建：切换 / 重连后 {@link #refreshObjectHome()} 会让
     * {@code WorkspaceView} 重走这个工厂，清单落到新库上。
     */
    private Node objectHomePage() {
        String connectionId = activeProfileId();
        DbSession current = session;
        if (current == null) {
            objectPage = null;
            objectPageConnection = null;
            return EmptyState.of(Icons.Icon.CONNECT,
                    I18n.t("object.empty.notConnected"),
                    I18n.t("object.empty.notConnectedDetail"),
                    I18n.t("object.newConnection"), this::openConnectionWizard);
        }
        if (objectPage == null || !Objects.equals(objectPageConnection, connectionId)) {
            objectPage = new TableListPage(current, schemaService,
                    executors.forConnection(connectionId),
                    name -> openTab(new TabItem("grid:" + connectionId + ":" + name, name,
                            TabKind.GRID, name, connectionId)),
                    this::status,
                    tableListActions(),
                    objectListHost(connectionId),
                    tableListInitialView(),
                    view -> settings.set("ui.tableList.view", view),
                    settings, connectionId);
            objectPageConnection = connectionId;
            objectPage.setDensity(currentDensity());
        }
        // 每次切到对象标签都重读一遍 —— 表可能刚被别处改了。
        objectPage.reload();
        return objectPage;
    }

    /**
     * 对象清单页的行为宿主：打开 / 设计 / 改名 / 清空 / 删除 / 详情联动。
     *
     * <p>全部落在 {@code connectionId} 这个库上（可能是 catalog 虚拟连接），
     * 不是「当前连接」—— 对象清单属于它显示的库。
     */
    private TableListPage.Host objectListHost(String connectionId) {
        return new TableListPage.Host() {
            @Override
            public void newTable() {
                tableDdl().createTable(connectionId);
            }

            @Override
            public void newTableWithAi() {
                openRibbonTool(SideBar.TOOL_NEW_TABLE_AI,
                        I18n.t("tool.title.new-table-ai"));
            }

            @Override
            public void openTable(String name) {
                openTab(new TabItem("grid:" + connectionId + ":" + name, name,
                        TabKind.GRID, name, connectionId));
            }

            @Override
            public void designTable(String name) {
                openStructure(connectionId, name);
            }

            @Override
            public void renameTable(String name) {
                tableDdl().renameTable(connectionId, name);
            }

            @Override
            public void truncateTable(String name) {
                tableDdl().truncateTable(connectionId, name);
            }

            @Override
            public void dropTables(List<String> names) {
                tableDdl().dropTables(connectionId, names);
            }

            @Override
            public void selectionChanged(String text) {
                statusBar.setSelectionText(text);
            }

            @Override
            public void inspectTable(String name) {
                if (!detailsVisible) {
                    return;
                }
                if (name == null || name.isBlank()) {
                    details.showEmpty();
                    return;
                }
                inspectTarget(new SideBar.InspectTarget(connectionId, name, null,
                        SideBar.InspectTarget.Topic.TABLE));
            }
        };
    }

    /**
     * ⭐ U6：读服务器版本与当前用户，放进状态栏。
     *
     * <p>在后台线程读 —— 它只是一行信息，不该让「连上库」这件事变慢。
     */
    private void loadServerInfo(DbSession target) {
        // ⭐ A16：走**该连接自己的执行器** ——
        //    服务器信息查询和数据页的加载如果跑在两个线程上、共用同一个连接，
        //    SQLite 这类「一个连接一个线程」的库会互相卡住（实测：数据页一直转圈）。
        executors.forConnection(activeProfileId()).execute(() -> {
            String[] info = schemaService.serverInfo(target);
            javafx.application.Platform.runLater(() ->
                    statusBar.setServerInfo(info[0], info[1]));
        });
    }

    /**
     * ⭐ N33：对象标签的内容跟着连接走。
     *
     * <p>同一个库上只是重读清单；换了库（或刚连上）就让 WorkspaceView 重走工厂 ——
     * 页面绑的是会话，旧页面上还挂着旧连接。
     */
    private void refreshObjectHome() {
        if (objectPage != null && Objects.equals(objectPageConnection, activeProfileId())) {
            objectPage.reload();
        } else {
            workspace.refreshObjectPage();
        }
    }

    /**
     * O2：打开函数 / 存储过程的定义体（只读标签页）。
     *
     * <p>形态依据 {@code docs/UI-FORMS-AND-STATES.md} §4：定义体要**长期停留读代码**
     * → 标签页（不是弹窗）。N33 之后唯一入口是左侧对象树的双击 / Enter。
     */
    private void openRoutine(String connectionId, String name, boolean procedure) {
        DbSession current = sessionFor(connectionId);
        if (current == null) {
            status(I18n.t("shell.needConnection"));
            return;
        }
        RoutinePage page = new RoutinePage(name, procedure, false, current, new SchemaService(),
                executors.forConnection(connectionId), this::status);
        String id = "routine:" + connectionId + ":" + name;
        openTab(new TabItem(id, name, TabKind.ROUTINE, name, connectionId, page));
        status(I18n.t("shell.openDefinition", name));
    }

    /**
     * ⭐ A15：同步连接（**只在启动时用**）。
     *
     * <p>启动连的是本地演示库 —— 毫秒级，而且窗口还没出现，
     * 此时阻塞不会让用户感到「卡」。
     * 用户自己连远程库时一律走 {@link #connectProfile(ConnectionProfile, boolean)}（后台）。
     */
    private void connectProfileBlocking(ConnectionProfile profile, boolean touch) {
        if (profile == null) {
            return;
        }
        activeProfile = profile;
        connectionName = profile.name();
        session = null;
        try {
            String password = credentials.password(profile.id()).orElse(null);
            session = sessions.open(profile.id(), profile.spec(password));
            List<SchemaObject> objects = schemaService.listObjects(session);
            objectsByConnection.put(profile.id(), objects);
            sideBar.setObjects(profile.id(), objects);
            sideBar.setConnection(profile.id(), profile.name());
            refreshProfiles();
            refreshObjectHome();
            applyConnectedUi(profile, objects);
        } catch (Exception e) {
            session = null;
            status(I18n.t("shell.connectFailed", readable(e)));
            if (appBar != null) {
                appBar.setConnectionState(false, true);
            }
            sideBar.refreshConnectionLiveness();
        }
    }

    /**
     * 连接一个档案。
     *
     * <p>⭐ A15：**建立连接与读对象列表都放到后台线程**。
     * 以前这段跑在 JavaFX 线程上 —— 服务器慢一点 / 网络抖一下，
     * <b>整个窗口就僵住</b>（连菜单都点不动）。现在界面先回话，
     * 结果到了再刷新。
     */
    private void connectProfile(ConnectionProfile profile, boolean touch) {
        if (profile == null) {
            return;
        }
        activeProfile = profile;
        connectionName = profile.name();
        session = null;
        if (touch) {
            connectionStore.touch(profile);
            ConnectionProfile touched = profile.withLastUsed(System.currentTimeMillis());
            connections.removeIf(existing -> existing.id().equals(touched.id()));
            connections.add(touched);
            resortConnections();
            connectionStore.save(touched);
        }
        status(I18n.t("shell.connecting", profile.name()));

        // ⭐ A15/A16：连接与内省在**该连接自己的执行器**上跑，不占 UI 线程，
        //    也不阻塞别的连接。
        executors.forConnection(profile.id()).execute(() -> {
            DbSession opened = null;
            List<SchemaObject> objects = List.of();
            try {
                String password = credentials.password(profile.id()).orElse(null);
                opened = sessions.open(profile.id(), profile.spec(password));
                objects = schemaService.listObjects(opened);
            } catch (Exception e) {
                String reason = readable(e);
                Platform.runLater(() -> {
                    session = null;
                    status(I18n.t("shell.connectFailed", reason));
                    if (appBar != null) {
                        appBar.setConnectionState(false, true);
                    }
                    sideBar.refreshConnectionLiveness();
                });
                return;
            }
            DbSession ready = opened;
            List<SchemaObject> loaded = objects;
            Platform.runLater(() -> {
                session = ready;
                objectsByConnection.put(profile.id(), loaded);
                sideBar.setObjects(profile.id(), loaded);
                sideBar.setConnection(profile.id(), profile.name());
                refreshProfiles();
                refreshObjectHome();
                applyConnectedUi(profile, loaded);
            });
        });
    }

    /** 连接就绪后的界面刷新（必须在 FX 线程调用）。 */
    private void applyConnectedUi(ConnectionProfile profile, List<SchemaObject> objects) {
        if (session != null) {
            var dialect = session.dialect();
            // ⭐ N32：库一层（MySQL / MariaDB、PG、SQL Server）。虚拟连接本身
            //    就是一个库，不再往自己下面套一层 —— 父连接的清单在接入时已读过。
            if (dialect.switchesCatalogByReconnect()
                    && !ConnectionProfile.isCatalogVariantId(profile.id())) {
                loadCatalogsFor(profile.id());
            }
            // ⭐ schema 切换器只给「会话内能切」的方言（PG 的 search_path、
            //    Oracle 的 CURRENT_SCHEMA）。MySQL 的 schema 就是库（入口在树上的
            //    库一层）、SQL Server 没有会话级搜索路径 —— 两处都不摆一个
            //    切了却不影响查询的假开关。
            if (dialect.switchesSchemaInSession()) {
                try {
                    sideBar.setSchemas(schemaService.listSchemas(session));
                } catch (SQLException e) {
                    sideBar.setSchemas(List.of());
                }
            } else {
                sideBar.setSchemas(List.of());
            }
        }
        statusBar.setConnection(connectionTitle(profile));
        if (appBar != null) {
            appBar.setConnectionState(true, false);
        }
        // ⭐ U6：服务器信息（版本 · 当前用户）—— 在后台读，不阻塞连接流程。
        loadServerInfo(session);
        // 侧栏「诊断」接真实功能：表结构 + 行数的健康概览。会话变了要跟着换。
        workspace.setDiagnostics(session, schemaService,
                executors.forConnection(activeProfileId()));
        // 4.5-11：状态栏与折叠侧栏的左端徽标显示真实方言，不再是写死的「DEV」。
        String dialectName = session == null ? "—" : session.dialect().displayName();
        statusBar.setDialect(dialectName);
        sideBar.setDialect(dialectName);
        statusBar.setRange("—");
        status(I18n.t("shell.connectedObjects", profile.name(), objects.size()));
        closeOrphanSessions();
    }

    /**
     * ⭐ N32：状态栏的「连接 · 库」。
     *
     * <p>库名跟着连接走：catalog 虚拟连接的档案名本来就是
     * {@code 父连接 · 库名}，不再重复拼；SQLite 没有库名，只显示连接名。
     */
    private static String connectionTitle(ConnectionProfile profile) {
        String database = profile.database();
        if (database.isBlank()
                || !profile.kind().dialect().switchesCatalogByReconnect()) {
            return profile.name();
        }
        String suffix = " · " + database;
        return profile.name().endsWith(suffix) ? profile.name() : profile.name() + suffix;
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
            status(I18n.t("shell.currentSchema", schema, objects.size()));
        } catch (SQLException e) {
            status(I18n.t("shell.schemaSwitchFailed", readable(e)));
        }
    }

    /**
     * 读取某个连接的对象（多连接对象树的懒加载）。
     *
     * <p>在后台线程做 —— 一个连不上的连接不能把界面卡住。
     *
     * <p>⭐ U1：{@code connectionId} 可能是 catalog 虚拟连接（{@code 父id@库名}）——
     * 它的档案从父连接派生（口令也走父连接的凭据条目）。
     */
    private void loadObjectsFor(String connectionId) {
        ConnectionProfile profile = profileFor(connectionId);
        if (profile == null) {
            return;
        }
        // ⭐ 按连接取执行器：同一连接的事务要串行。
        executors.forConnection(connectionId).execute(() -> {
            try {
                DbSession opened = sessions.open(profile.id(), specOf(profile));
                List<SchemaObject> objects = schemaService.listObjects(opened);
                objectsByConnection.put(profile.id(), objects);
                Platform.runLater(() -> {
                    sideBar.setObjects(profile.id(), objects);
                    status(I18n.t("shell.objectsLoaded", profile.name(), objects.size()));
                    // ⭐ U1：会话开好了 —— 补一次 catalog 清单。选中一个
                    //    没连过的连接时，loadCatalogsFor 会因会话未开而跳过，
                    //    这里补上，库一层才不会永远缺席。
                    loadCatalogsFor(profile.id());
                });
            } catch (Exception e) {
                String reason = readable(e);
                Platform.runLater(() -> {
                    status(I18n.t("shell.objectsLoadFailed", profile.name(), reason));
                    // ⭐ R51 T2：失败要落回侧栏 —— 光靠状态栏那一行，树会一直
                    //    画成「这个库什么都没有」，而「已尝试」标记已经把重试堵死了。
                    sideBar.setObjectsReadFailed(profile.id());
                });
            }
        });
    }

    /**
     * ⭐ S-3：展开表节点时读它的列（懒加载）。
     *
     * <p>为什么懒：连上一个几千张表的库就查每张表的列，会把界面卡死 ——
     * Navicat 同样是展开才读。数据来源是 {@link SchemaService#describe}
     * （{@code SELECT * FROM t LIMIT 0}，表与视图共用），读不到的值一个都不编。
     *
     * <p>失败时侧栏会把占位节点换成「列读取失败（展开重试）」并清掉
     * 「已尝试」标记 —— 用户再展开一次就重试，不静默降级成叶子。
     */
    private void loadColumnsFor(String connectionId, String table) {
        DbSession session = sessionFor(connectionId);
        if (session == null) {
            sideBar.setColumns(connectionId, table, List.of());
            status(I18n.t("shell.noColumns", table));
            return;
        }
        executorFor(connectionId).execute(() -> {
            try {
                List<String> columns = schemaService.describe(session, table).columns().stream()
                        .map(column -> column.name())
                        .toList();
                // ⭐ 必须回 FX 线程再改树 —— 读取跑在数据库执行器上。
                Platform.runLater(() -> sideBar.setColumns(connectionId, table, columns));
            } catch (Exception e) {
                String reason = readable(e);
                Platform.runLater(() -> {
                    sideBar.setColumns(connectionId, table, List.of());
                    status(I18n.t("shell.columnsFailed", table, reason));
                });
            }
        });
    }

    /**
     * ⭐ N32：读某个连接的 catalog（数据库）清单，推进对象树。
     *
     * <p>只有「切 catalog 要重连」的方言读得到东西（MySQL / MariaDB、PG、
     * SQL Server）；SQLite / Oracle 读到空清单 → 侧栏不加库一层，不假装有。
     */
    private void loadCatalogsFor(String connectionId) {
        // ⭐ 虚拟连接本身就是一个库 —— 它下面不再套库一层。
        if (connectionId == null || ConnectionProfile.isCatalogVariantId(connectionId)) {
            return;
        }
        ConnectionProfile parent = profileById(connectionId);
        if (parent == null || catalogsLoaded.contains(connectionId)) {
            return;
        }
        DbSession target = sessionFor(connectionId);
        if (target == null) {
            // ⚠️ 会话还没开（选中一个没连过的连接时，对象正在后台读）——
            //    这时候**不能**标记「读过了」，否则库一层永远不出来。
            //    loadObjectsFor 把会话开好后会再叫一次。
            //    ⭐ R51 T2：但用户是**主动**来要库清单的（展开重试节点）时，
            //    静默返回等于「点开没反应」—— 让侧栏把它当成一次没读到，
            //    说明节点留在树上，下次还能试。
            Platform.runLater(() -> sideBar.setCatalogsReadFailed(connectionId));
            return;
        }
        catalogsLoaded.add(connectionId);
        executors.forConnection(connectionId).execute(() -> {
            List<String> catalogs;
            String readFailed = null;
            try {
                catalogs = new ArrayList<>(schemaService.listCatalogs(target));
            } catch (Exception e) {
                // ⭐ R51 T2：**读失败**和「这个方言真的没有库一层」（SQLite / Oracle）
                //    是两回事，混在一起就会把 MySQL 画成 SQLite 的样子。
                catalogs = new ArrayList<>();
                readFailed = readable(e);
            }
            // ⭐ N32：父连接自己落在的那个库**必须**出现在树里 —— SQL Server 常以
            //    master 接入（系统库被过滤掉了），PG 也可能连到模板库；缺了它，
            //    当前连接的对象在树上没有归属，用户会以为库是空的。
            String own = parent.database();
            if (!own.isBlank() && catalogs.stream().noneMatch(name -> name.equalsIgnoreCase(own))) {
                catalogs.add(own);
                catalogs.sort(String.CASE_INSENSITIVE_ORDER);
            }
            List<SideBar.CatalogEntry> entries = new ArrayList<>();
            for (String catalog : catalogs) {
                // 父连接自己的库直接由父连接 Serve；其它库用虚拟连接 id。
                String serving = catalog.equals(parent.database())
                        ? parent.id()
                        : ConnectionProfile.catalogVariantId(parent.id(), catalog);
                entries.add(new SideBar.CatalogEntry(catalog, serving));
            }
            String failure = readFailed;
            Platform.runLater(() -> {
                if (failure != null) {
                    catalogsLoaded.remove(connectionId);
                    sideBar.setCatalogsReadFailed(connectionId);
                    status(I18n.t("shell.databasesLoadFailed", parent.name(), failure));
                } else if (entries.isEmpty()) {
                    status(I18n.t("shell.noDatabases", parent.name()));
                } else {
                    sideBar.setCatalogs(connectionId, entries);
                }
            });
        });
    }

    /**
     * ⭐ U1：切换当前数据库（catalog）。
     *
     * <p>MySQL 的 schema 就是数据库，{@code USE} 管不住连接池 ——
     * 所以切库 = 激活一个**虚拟连接**（{@code 父id@库名}，见
     * {@link ConnectionProfile#catalogVariant}）。父连接自己的库
     * 直接复用它现成的会话，不另开一条。
     */
    private void switchCatalog(SideBar.CatalogSwitch request) {
        if (request == null || request.catalog() == null || request.catalog().isBlank()) {
            return;
        }
        String parentId = ConnectionProfile.isCatalogVariantId(request.connectionId())
                ? ConnectionProfile.parentOfCatalogVariant(request.connectionId())
                : request.connectionId();
        ConnectionProfile parent = profileById(parentId);
        if (parent == null) {
            status(I18n.t("shell.connectionNotFound", parentId));
            return;
        }
        if (request.catalog().equals(parent.database())) {
            // 点的就是父连接自己的库 —— 复用它的会话（selectConnection 会去重）。
            selectConnection(parent);
            return;
        }
        String variantId = ConnectionProfile.catalogVariantId(parent.id(), request.catalog());
        if (activeProfile != null && activeProfile.id().equals(variantId)
                && sessions.isOpen(variantId)) {
            status(I18n.t("shell.currentDatabase", request.catalog()));
            return;
        }
        connectCatalog(variantProfile(variantId));
    }

    /**
     * ⭐ U1：取（或就地创建）一个 catalog 虚拟连接档案。
     *
     * <p>创建时登记进 {@link #catalogVariants} —— 它只活在内存里，
     * 不进 {@code connections.json}，刷新配置也不会丢。
     */
    private ConnectionProfile variantProfile(String variantId) {
        ConnectionProfile existing = catalogVariants.get(variantId);
        if (existing != null) {
            return existing;
        }
        ConnectionProfile parent = profileById(
                ConnectionProfile.parentOfCatalogVariant(variantId));
        if (parent == null) {
            return null;
        }
        ConnectionProfile variant = parent.catalogVariant(
                ConnectionProfile.catalogOfCatalogVariant(variantId));
        catalogVariants.put(variant.id(), variant);
        return variant;
    }

    /**
     * ⭐ U1：连接一个 catalog 虚拟连接。
     *
     * <p>与 {@link #connectProfile} 的差别只有两个：<b>不 touch、不落盘</b>
     * （虚拟连接不是用户的档案），以及对象树按虚拟连接的 id 收对象。
     */
    private void connectCatalog(ConnectionProfile variant) {
        if (variant == null) {
            status(I18n.t("shell.cannotOpenDatabase"));
            return;
        }
        activeProfile = variant;
        connectionName = variant.name();
        session = null;
        status(I18n.t("shell.connecting", variant.name()));
        executors.forConnection(variant.id()).execute(() -> {
            DbSession opened = null;
            List<SchemaObject> objects = List.of();
            try {
                opened = sessions.open(variant.id(), specOf(variant));
                objects = schemaService.listObjects(opened);
            } catch (Exception e) {
                String reason = readable(e);
                Platform.runLater(() -> {
                    session = null;
                    status(I18n.t("shell.connectFailed", reason));
                    if (appBar != null) {
                        appBar.setConnectionState(false, true);
                    }
                    sideBar.refreshConnectionLiveness();
                });
                return;
            }
            DbSession ready = opened;
            List<SchemaObject> loaded = objects;
            Platform.runLater(() -> {
                session = ready;
                objectsByConnection.put(variant.id(), loaded);
                sideBar.setObjects(variant.id(), loaded);
                sideBar.setConnection(variant.id(), variant.name());
                refreshProfiles();
                refreshObjectHome();
                applyConnectedUi(variant, loaded);
                // ⭐ G1：查询页库选择器等「连上之后」的动作，会话就绪这一拍才执行。
                if (pendingAfterCatalogConnect != null) {
                    Runnable followUp = pendingAfterCatalogConnect;
                    pendingAfterCatalogConnect = null;
                    followUp.run();
                }
            });
        });
    }

    /** 按 id 找档案：先 saved，再 catalog 虚拟连接。 */
    private ConnectionProfile profileFor(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        ConnectionProfile direct = profileById(connectionId);
        if (direct != null) {
            return direct;
        }
        return variantProfile(connectionId);
    }

    /** 侧栏/工具栏切换连接。已打开的会话复用，不重开。 */
    private void selectConnection(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        if (activeProfile != null && activeProfile.id().equals(profile.id())
                && sessions.isOpen(profile.id())) {
            // U8：点「已连着的那颗连接」没有新信息可播报 —— 连接灯本来就写着它。
            status("");
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
            resortConnections();
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
        openTab(new TabItem("connections", I18n.t("shell.tab.connections"), TabKind.CONNECTIONS));
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
        page.setRenameHandler(this::renameConnection);
        page.setGroupHandler(this::moveConnectionToGroup);
        return page;
    }

    /**
     * ⭐ P5-1：连接改名。
     *
     * <p>只改元数据：会话与口令条目都挂在不变的 id 上，所以改名不会断开连接，
     * 也不会让已保存的口令「消失」。
     */
    private void renameConnection(ConnectionProfile profile, String newName) {
        if (profile == null) {
            return;
        }
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty()) {
            status(I18n.t("shell.nameRequired"));
            return;
        }
        if (trimmed.equals(profile.name())) {
            return;
        }
        replaceProfile(profile.withName(trimmed));
        connectionStore.rename(profile.id(), trimmed);
        status(I18n.t("shell.renamed", trimmed));
    }

    /** ⭐ P5-1：把连接移进分组（空串 = 移出分组）；同样不动会话。 */
    private void moveConnectionToGroup(ConnectionProfile profile, String groupName) {
        if (profile == null) {
            return;
        }
        String group = groupName == null ? "" : groupName.trim();
        if (group.equals(profile.group())) {
            return;
        }
        replaceProfile(profile.withGroup(group));
        connectionStore.moveToGroup(profile.id(), group);
        status(group.isEmpty()
                ? I18n.t("shell.ungrouped", profile.name())
                : I18n.t("shell.movedToGroup", group, profile.name()));
    }

    /** 用改好的档案替换内存里的同一 id 项，并同步顺序 / 顶栏 / 侧栏 / 标签标题。 */
    private void replaceProfile(ConnectionProfile updated) {
        for (int index = 0; index < connections.size(); index++) {
            if (connections.get(index).id().equals(updated.id())) {
                connections.set(index, updated);
                break;
            }
        }
        if (activeProfile != null && activeProfile.id().equals(updated.id())) {
            activeProfile = updated;
        }
        resortConnections();
        refreshProfiles();
    }

    private void duplicateConnection(ConnectionProfile profile) {
        ConnectionProfile copy = profile.duplicate();
        credentials.password(profile.id()).ifPresent(password ->
                credentials.save(copy.id(), password));
        connections.add(copy);
        resortConnections();
        connectionStore.save(copy);
        refreshProfiles();
        status(I18n.t("shell.duplicated", copy.name()));
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
                // ⚠️ 以前这里传字面量「未连接」，靠 StatusBar 拿显示文案反推状态。
                //    StatusBar 改成记布尔之后，传字符串就是"有一个名字叫未连接的连接"——
                //    连接灯会在没连上时亮绿。断开的正确表达是 null。
                statusBar.setConnection(null);
                if (appBar != null) {
                    appBar.setConnectionState(false, false);
                }
            }
        }
        refreshProfiles();
        status(I18n.t("shell.deleted", profile.name()));
    }

    // ⛔ 别再手写 ButtonType("删除", OK_DONE) —— OK_DONE 是默认按钮，回车即删。
    //    统一走 DestructiveConfirm：破坏性按钮用 OTHER，默认按钮落在「取消」上。
    private boolean confirmDeleteConnection(ConnectionProfile profile) {
        DestructiveConfirm confirm = DestructiveConfirm.of(
                I18n.t("shell.deleteTitle"),
                I18n.t("shell.deleteQuestion", profile.name()),
                I18n.t("shell.deleteBody"),
                    I18n.t("common.delete"));
        styleDialog(confirm.alert());
        return confirm.confirmed();
    }

    /**
     * ⭐ 断开当前连接。
     *
     * <p>断开前先关掉**引用这个连接的标签** —— 它们握着已关闭的会话，
     * 留着只会在下一次翻页时抛「会话已关闭」。有关闭确认：
     * 未保存的编辑会逐个问过（{@code TabBar} 的 closeGuard）。
     */
    private void disconnectConnection() {
        if (activeProfile == null) {
            status(I18n.t("shell.noConnections"));
            return;
        }
        String id = activeProfile.id();
        List<TabItem> bound = tabBar.openTabs().stream()
                .filter(tab -> id.equals(tab.connectionId()))
                .toList();
        for (TabItem tab : bound) {
            if (!tabBar.close(tab.id())) {
                // 用户在关闭确认里选择了「保留」—— 那就不断开：
                // 会话还开着，页面还能用；强行断开等于偷偷丢掉未保存的编辑。
                status(I18n.t("shell.disconnectCancelled"));
                return;
            }
        }
        sessions.close(id);
        session = null;
        statusBar.setConnection(null);
        statusBar.setDialect("—");
        statusBar.setRange("—");
        statusBar.setDuration(-1);
        // ⭐ UX-5-D：断开后没有会话了，「当前语句 / 最后刷新」这两段必须一起收掉，
        //    留着上一条的连接信息会让人以为还在看那批数据。
        statusBar.setSql(null);
        statusBar.setRefreshedAt(-1);
        if (appBar != null) {
            appBar.setConnectionState(false, false);
        }
        // ⭐ UX-5：断开要让侧栏那颗绿点一起变灰 —— 这条路径不重建连接清单，
        //    不重画的话「已断开」的连接会一直顶着绿点（比没有点更误导人）。
        sideBar.refreshConnectionLiveness();
        status(I18n.t("shell.disconnected", activeProfile.name()));
    }

    private void reconnectConnection() {
        if (activeProfile == null) {
            status(I18n.t("shell.noConnections"));
            return;
        }
        // ⭐ U2：重连 = **强制换一条新会话**（SessionManager.reopen），
        //    与「切换连接时复用已有会话」是两回事。
        try {
            sessions.reopen(activeProfile.id(), specOf(activeProfile));
        } catch (java.sql.SQLException e) {
            status(I18n.t("shell.reconnectFailed", readable(e)));
            return;
        }
        session = null;
        connectProfile(activeProfile, false);
    }

    /**
     * 取连接的 spec（口令从凭据存储取）。
     *
     * <p>⭐ U1：catalog 虚拟连接的口令在<b>父连接</b>的凭据条目里
     * （虚拟连接不是档案，没有自己的凭据条目）。
     */
    private io.agenticdb.jdbc.ConnectionSpec specOf(ConnectionProfile profile) {
        String credentialId = ConnectionProfile.isCatalogVariantId(profile.id())
                ? ConnectionProfile.parentOfCatalogVariant(profile.id())
                : profile.id();
        String password = credentials.password(credentialId).orElse(null);
        return profile.spec(password);
    }

    // ─────────────────────────── 生命周期 ───────────────────────────

    public void restoreWindow(Stage stage) {
        this.stage = stage;
        int width = settings.getInt("ui.window.width", 0);
        int height = settings.getInt("ui.window.height", 0);
        // ⭐ 窗口尺寸要**夹到当前屏幕**里。
        //    以前直接用记住的尺寸：换一台分辨率更小的显示器（或外接屏拔掉），
        //    窗口会比屏幕还大 —— 底部的分页条 / 状态栏被顶到**可视区之外**，
        //    用户点不到「下一页」，也看不到状态栏（实测：分页条落在 y=800..826，
        //    而窗口只有 800 高）。夹一下就能避免「窗口大到内容点不着」。
        javafx.geometry.Rectangle2D bounds = screenBounds(stage);
        double maxWidth = bounds == null ? Double.MAX_VALUE : bounds.getWidth();
        double maxHeight = bounds == null ? Double.MAX_VALUE : bounds.getHeight();
        if (width >= 960) {
            stage.setWidth(Math.min(width, maxWidth));
        }
        if (height >= 600) {
            stage.setHeight(Math.min(height, maxHeight));
        }
        // ⭐ S7：窗口**位置**也记住（以前只记宽高 —— 每次打开都跑到屏幕正中）。
        int x = settings.getInt("ui.window.x", -1);
        int y = settings.getInt("ui.window.y", -1);
        if (x > -2000 && y > -2000) {
            // 位置也要夹：屏幕变小了的话，窗口可能整个跑到屏幕外（用户看不到标题栏）。
            double left = bounds == null ? x : Math.max(bounds.getMinX(),
                    Math.min(x, bounds.getMaxX() - stage.getWidth()));
            double top = bounds == null ? y : Math.max(bounds.getMinY(),
                    Math.min(y, bounds.getMaxY() - stage.getHeight()));
            stage.setX(left);
            stage.setY(top);
        }
        stage.setOnCloseRequest(event -> {
            if (!confirmDiscardAll()) {
                event.consume();
                return;
            }
            saveWindowState();
        });
    }

    /** 当前屏幕的可视范围；取不到时返回 null（不夹）。 */
    private static javafx.geometry.Rectangle2D screenBounds(Stage stage) {
        try {
            javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
            if (screen != null) {
                return screen.getVisualBounds();
            }
        } catch (Throwable ignored) {
            // ⛔ 必须是 Throwable：��头环境（CI / Monocle）里
            //    Screen.getPrimary() 抛的是 ExceptionInInitializerError（Error，不是 RuntimeException）——
            //    只 catch RuntimeException 会让应用在 CI 上**启动即崩**。
        }
        return null;
    }

    public void afterShown(Stage stage) {
        this.stage = stage;
        workspace.requestFocus();
        String requested = System.getProperty("adb.connection",
                System.getenv().getOrDefault("ADB_CONNECTION", "")).trim();
        if (requested.isEmpty()) {
            // ⭐ S7：没指定连接就用上次用的那个
            requested = settings.get("ui.lastConnection", "");
        }
        final String connection = requested;
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
        // ⭐ 只服务截图脚本：启动即弹「新建表」对话框（模态弹窗的图没法靠点击拍，
        //    因为 ScreenshotSession 的 click 只能点页面内的 id）。
        // ⚠️ 两个坑都踩过：
        //   1) showAndWait() 会开嵌套事件循环，直接在 afterShown 里调会让
        //      App.java 的 ScreenshotSession.maybeRun() 永远排不上 → 不截图也不退出；
        //   2) 连接是异步建立的，此刻 session 可能还是 null，弹了也会被「请先连接」挡掉。
        //   所以：包 runLater + 轮询等 session 就绪。
        if (flag("adb.newTable", "ADB_NEW_TABLE")) {
            popNewTableWhenReady(0);
        }
        // ⭐ R42 N28-a：视图设计器。与「新建表」同一个坑、同一个解法
        //    （模态框要包 runLater + 轮询等 session 就绪）。
        //    ADB_NEW_VIEW_POSE=create|invalid 落在「有内容的新建」与「校验拒绝」两态；
        //    ADB_DESIGN_VIEW=1 走的是另一条路：从演示库读回 v_active_users 的定义。
        if (flag("adb.newView", "ADB_NEW_VIEW")) {
            popNewViewWhenReady(0);
        }
        if (flag("adb.designView", "ADB_DESIGN_VIEW")) {
            popDesignViewWhenReady(0);
        }
        // ⭐ 同上：结构页背后 + 「新建索引」弹窗盖在前面。
        if (flag("adb.index", "ADB_INDEX")) {
            openStructure(activeProfileId(), "users");
            popIndexDialogWhenReady(0);
        }
        // ⭐ 截图 / 验收：启动即打开表清单（需要连接就绪，所以轮询）。
        if (flag("adb.tables", "ADB_TABLES")) {
            openTableListWhenReady(0);
        }
        // ⭐ 截图 / 验收：启动即打开「连接管理」。
        //    顶栏的连接管理图标随 2026-10-28 顶区精简一起删了，菜单项又不是场景节点、
        //    截图脚本点不到 —— 于是给定格留一个与 ADB_TABLES 同型的开关。
        if (flag("adb.connections", "ADB_CONNECTIONS")) {
            actions.run("connections.manage");
        }
        // ⭐ 截图 / 验收：启动即打开「对象详情面板」并选中 users 表（规格 §6）。
        //    面板默认是关的（ui.details.visible=false），所以这里显式打开；
        //    树节点没有公开的「选中」入口，于是直接喂一个 InspectTarget ——
        //    走的仍是点击树节点那条 inspectTarget() 路径（只是不画树里的高亮）。
        if (flag("adb.details", "ADB_DETAILS")) {
            detailsVisible = true;
            applyDetailsVisibility();
            showDetailsWhenReady("users", 0);
        }
        // ⭐ 截图 / 验收：结构页直接落在 DDL 子页签（规格 §11.2 换成
        //    只读 SqlEditor 之后，要能一眼看到行号与高亮）。
        if (flag("adb.ddl", "ADB_DDL")) {
            openStructure(activeProfileId(), "users");
            showDdlWhenReady(0);
        }
        // ⭐ 截图 / 验收（UX-5-F）：在数据页摆出「选中行 + 光标格 + 详情抽屉」三个交互态。
        //    这三个状态只在真点下去时才出现，以前的帧里一帧都没覆盖到 ——
        //    于是「网格改了三版」全程只有运行时坐标断言在守，画面没人看过。
        //    数据是异步灌进来的，所以要等行就绪（与 ADB_DETAILS 的轮询同一形态）；
        //    编辑态由脚本的 ADB_SHOTS_CLICK=data-details-edit 真点「编辑」按钮进入。
        if (flag("adb.cell", "ADB_CELL")) {
            poseGridCellWhenReady(0);
        }
        // ⭐ 截图 / 验收（R32 G3）：数据页摆出「查找条展开 + needle 已填」。
        //    「下一个」由截图脚本真点（#data-find-next），命中推进与计数播报
        //    由那一帧自己证明 —— 与 ADB_CELL「状态程序化摆、交互真点进去」同一形态。
        if (flag("adb.find", "ADB_FIND")) {
            poseGridFindWhenReady(0);
        }
        // ⭐ 截图 / 验收（R32 G4）：数据页摆出「表单视图 + 第一条记录」。
        //    翻到下一条由截图脚本真点（#data-record-next），记录导航 ↔ 表单的
        //    联动由那一帧自己证明 —— 与 ADB_FIND 同一形态。
        if (flag("adb.form", "ADB_FORM")) {
            poseGridFormWhenReady(0);
        }
        // ⭐ 截图 / 验收（R33-G5）：导入向导 —— 文件由外壳准备（GBK 中文），
        //    绕过文件选择框直接弹向导并摆好预览。与 ADB_NEW_TABLE 同理必须 runLater：
        //    向导是 showAndWait 的模态框，直接调用会开嵌套事件循环挡住截图调度。
        if (flag("adb.import", "ADB_IMPORT")) {
            Path demo = writeDemoImportFile();
            if (demo == null) {
                status(I18n.t("shell.demoImportFailed"));
            } else {
                poseImportWizardWhenReady(demo, 0);
            }
        }
        // ⭐ 截图 / 验收（R33-G6）：导出向导 —— ADB_EXPORT=xml|html 决定摆哪一档格式。
        String exportPose = text("adb.exportPose", "ADB_EXPORT").trim();
        if (!exportPose.isEmpty()) {
            String pose = exportPose.equals("1") || exportPose.equalsIgnoreCase("true")
                    ? "xml" : exportPose;
            poseExportWizardWhenReady(pose, 0);
        }
        // ⭐ 截图 / 验收（R33-G8/G20）：连接向导 —— 类型页 / 7 页属性各一帧。
        //    不需要数据库连接，所以直接 runLater 弹出；pose 打错会落到默认页，
        //    由 ScreenshotScriptAuditTest 守着「脚本里的 pose 必须被认得」。
        String connectWizardPose = text("adb.connectWizard", "ADB_CONNECT_WIZARD").trim();
        if (!connectWizardPose.isEmpty()) {
            String pose = connectWizardPose.equals("1")
                    || connectWizardPose.equalsIgnoreCase("true")
                    ? "general" : connectWizardPose;
            Platform.runLater(() -> {
                ConnectionWizard wizard = new ConnectionWizard(
                        stage, null, credentials, this::testConnection);
                wizard.poseForShots(pose);
                wizard.show();
            });
        }
        // ⭐ 截图 / 验收（UX-6）：启动直接落在数据页「概览」切面。
        //    切面标签的 id 是动态拼的（object-tab-<id>），截图脚本的静态审计
        //    不允许点它；这里与 ADB_DDL 同型，等页面建好后程序化切换。
        if (flag("adb.overview", "ADB_OVERVIEW")) {
            showOverviewWhenReady(0);
        }
        // ⭐ 截图 / 验收（R34 第十六批）：ObjectSectionsPanel 的四张只读表
        //    以前没有任何帧覆盖（i18n 抽完只能靠单测），这里补一个切面摆态。
        String sectionPose = text("adb.section", "ADB_SECTION").trim();
        if (!sectionPose.isEmpty()) {
            showSectionWhenReady(sectionPose, 0);
        }
        // ⭐ 截图 / 验收（P2-2）：启动即弹「错误详情」。
        //    JavaFX 的 Dialog 是独立 Stage，ScreenshotSession 会优先拍模态窗口。
        //    ⚠️ 走 showForShots 而不是 show：show 里的 showAndWait 开嵌套事件循环，
        //    实测 6 次冷启有 2 次弹窗根本没进 Window.getWindows()（截图拍到主窗口，
        //    被 U7 去重门禁判成假帧）。摆态只需要「拍的那一刻它可见」，不必阻塞。
        if (flag("adb.errorDemo", "ADB_ERROR_DEMO")) {
            Platform.runLater(() -> ErrorDetailsDialog.showForShots(stage,
                    ErrorDetails.of(I18n.t("shell.queryFailed"),
                            new java.sql.SQLException("no such table: user_profiles"),
                            "SELECT id, name, email\nFROM user_profiles\nWHERE active = 1;")));
        }
        // ⭐ 截图 / 验收（R34 收官）：第二十至二十五批共 275 条文案抽进资源包后
        //    一帧都没拍过 —— 只有单测在守。下面六个开关把缺的六个面各摆一帧：
        //    设置页 / 诊断页 / 收藏夹管理器 / 已存查询管理器 / 重命名弹窗 / 列筛选浮窗。
        //    列筛选那颗漏斗的 id 是动态拼的（data-filter-<列>），脚本点不到，
        //    所以它也要一个开关，由数据页自己 fire 那颗按钮。
        if (flag("adb.settings", "ADB_SETTINGS")) {
            Platform.runLater(() -> actions.run("settings"));
        }
        if (flag("adb.diagnostics", "ADB_DIAGNOSTICS")) {
            poseDiagnosticsWhenReady(0);
        }
        if (flag("adb.favorites", "ADB_FAVORITES")) {
            Platform.runLater(() -> {
                sideBar.poseStarForShots("users");
                openFavorites();
            });
        }
        if (flag("adb.saved", "ADB_SAVED")) {
            Platform.runLater(() -> {
                poseSeedSavedQueriesForShots();
                openSavedQueries();
                savedQueriesPage().selectFirst();
            });
        }
        if (flag("adb.filter", "ADB_FILTER")) {
            poseGridFilterWhenReady(0);
        }
        // ⭐ R38 N31：内嵌 Filter & Sort 面板那一帧 —— 工具条按下态 + 条件行 + Sort By
        //    芯片 + Criteria Edited 都在画面里，背面网格仍是真数据。
        if (flag("adb.filterSort", "ADB_FILTER_SORT")) {
            poseGridFilterSortWhenReady(0);
        }
        String renamePose = text("adb.rename", "ADB_RENAME").trim();
        if (!renamePose.isEmpty()) {
            String table = renamePose.equals("1") || renamePose.equalsIgnoreCase("true")
                    ? "users" : renamePose;
            poseRenameWhenReady(table, 0);
        }
        String initialQuery = System.getProperty("adb.query",
                System.getenv().getOrDefault("ADB_QUERY", "")).trim();        if (!initialQuery.isEmpty()) {
            String sql = initialQuery.equals("1") || initialQuery.equalsIgnoreCase("true")
                    ? "SELECT id, name, email, active FROM users ORDER BY id LIMIT 100;"
                    : initialQuery;
            String id = "query:" + activeProfileId() + ":" + System.nanoTime();
            initialQuerySql.put(id, sql);
            openTab(new TabItem(id, I18n.t("shell.sampleQuery"), TabKind.QUERY, null, activeProfileId()));
            QueryPage page = workspace.activeQuery();
            if (page != null) {
                page.markClean();
                // ⭐ 截图 / 验收（U25）：结果渲染后在数据格上摆出真右键菜单。
                //    以前 15 帧里 0 张菜单 —— 四类状态里「交互态」整类缺证。
                if (flag("adb.menuPose", "ADB_MENU_POSE")) {
                    page.poseContextMenuWhenReady();
                }
            }
            Platform.runLater(workspace::runQuery);
        }
        // ⭐ S7：恢复上次打开的对象。命令行显式指定时不让它覆盖。
        if (initial.isEmpty() && structure.isEmpty() && initialQuery.isEmpty()) {
            restoreLastTabs();
        }
        // ⭐ P7：首屏 = 启动参数 / 上次标签都恢复完（后续截图弹窗走 runLater，不计入）。
        startup.mark("shell.after-shown");
    }

    /** ⭐ P7：给门禁 / 关于弹窗读的启动打点。 */
    io.agenticdb.ui.StartupTrace startupTrace() {
        return startup;
    }

    /** 窗口关闭时调用：先停掉在途查询，再关连接。 */
    public void close() {
        executors.close();
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

    /**
     * ⭐ S7：记住「上次是怎么用这个应用的」。
     *
     * <p>以前只记宽高 / 侧栏宽 / 折叠态；现在补上
     * <b>窗口位置</b>、<b>上次用的连接</b>、<b>上次打开的对象</b>（标签页）。
     */
    private void saveWindowState() {
        if (stage == null) {
            return;
        }
        settings.setInt("ui.window.width", (int) Math.round(stage.getWidth()));
        settings.setInt("ui.window.height", (int) Math.round(stage.getHeight()));
        // ⭐ S7：窗口位置
        settings.setInt("ui.window.x", (int) Math.round(stage.getX()));
        settings.setInt("ui.window.y", (int) Math.round(stage.getY()));
        settings.setInt("ui.sidebar.width", sidebarWidth);
        settings.setBoolean("ui.sidebar.collapsed", sidebarCollapsed);
        // ⭐ S7：上次用的连接
        if (activeProfile != null) {
            settings.set("ui.lastConnection", activeProfile.id());
        }
        // ⭐ S7：上次打开的对象（标签页）—— 重开后接着用
        StringBuilder tabs = new StringBuilder();
        for (TabItem tab : tabBar.openTabs()) {
            if (tabs.length() > 0) {
                tabs.append('\n');
            }
            tabs.append(tab.kind().name()).append('\t')
                    .append(tab.connectionId() == null ? "" : tab.connectionId())
                    .append('\t')
                    .append(tab.target() == null ? "" : tab.target().replace('\t', ' '))
                    .append('\t')
                    .append(tab.title().replace('\t', ' '));
        }
        settings.set("ui.lastTabs", tabs.toString());
    }

    /**
     * 恢复上次打开的标签页（S7）。
     *
     * <p>只在「上次退出时没有未保存修改」的情况下恢复 —— 否则会把用户
     * 以为还在的脏数据悄悄换掉。
     */
    private void restoreLastTabs() {
        String raw = settings.get("ui.lastTabs", "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 4) {
                continue;
            }
            TabKind kind;
            try {
                kind = TabKind.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String connectionId = parts[1].isBlank() ? null : parts[1];
            String target = parts[2].isBlank() ? null : parts[2];
            String title = parts[3].isBlank() ? target : parts[3];
            if (kind != TabKind.GRID && kind != TabKind.STRUCTURE && kind != TabKind.QUERY) {
                continue;
            }
            String id = kind.name().toLowerCase(Locale.ROOT) + ":"
                    + (connectionId == null ? activeProfileId() : connectionId) + ":"
                    + (target == null ? System.nanoTime() : target);
            openTab(new TabItem(id, title, kind, target, connectionId));
        }
        // ⭐ X7：恢复完落回**常驻的「对象」标签** ——
        //    否则活动标签是「最后恢复的那个」，用户看到的不是浏览的起点。
        tabBar.activatePermanent();
    }

    // ─────────────────────────── 动作 ───────────────────────────

    private void registerActions() {
        actions.register("new-connection", I18n.t("action.new-connection"), I18n.t("category.file"),
                key("Shortcut+N"), this::openConnectionWizard);
        actions.register("connections.manage", I18n.t("action.connections.manage"), I18n.t("category.file"),
                null, this::openConnections);
        actions.register("new-query", I18n.t("action.new-query"), I18n.t("category.file"),
                key("Shift+Shortcut+N"), this::openNewQuery);
        actions.register("open-sql", I18n.t("action.open-sql"), I18n.t("category.file"),
                key("Shortcut+O"), this::openSqlFile);
        actions.register("tab.close", I18n.t("action.tab.close"), I18n.t("category.file"),
                key("Shortcut+W"), () -> tabBar.closeActive());
        // 4.5-29：标签栏右键菜单以前是手搓 MenuItem，现在也走注册表，
        // 于是「关闭其他 / 全部关闭」能在 ⌘K 里搜到，enabled 也统一。
        actions.register("tab.close-others", I18n.t("action.tab.close-others"), I18n.t("category.file"),
                null, () -> tabBar.closeOthers(tabBar.activeId()));
        actions.register("tab.close-all", I18n.t("action.tab.close-all"), I18n.t("category.file"),
                null, () -> tabBar.closeAll());
        // ⭐ S8：标签栏右键补「复制表名 / 复制 SELECT」
        actions.register("tab.copy-name", I18n.t("action.tab.copy-name"), I18n.t("category.file"),
                null, () -> copyActiveTabTarget(false));
        actions.register("tab.copy-select", I18n.t("action.tab.copy-select"), I18n.t("category.file"),
                null, () -> copyActiveTabTarget(true));
        actions.register("window.close", I18n.t("action.window.close"), I18n.t("category.file"),
                key("Shift+Shortcut+W"), () -> {
                    if (stage != null) {
                        stage.close();
                    } else {
                        Platform.exit();
                    }
                });
        actions.register("app.quit", I18n.t("action.app.quit"), I18n.t("category.file"),
                key("Shortcut+Q"), () -> {
                    if (stage != null) {
                        stage.close();
                    } else {
                        Platform.exit();
                    }
                });

        actions.register("command-palette", I18n.t("action.command-palette"), I18n.t("category.edit"),
                key("Shortcut+K"), palette::showPalette);
        actions.register("focus-search", I18n.t("action.focus-search"), I18n.t("category.edit"),
                key("Shortcut+F"), () -> {
                    if (!workspace.focusSearch()) {
                        sideBar.focusSearch();
                    }
                });

        // 编辑菜单三件套：撤销 / 重做 / 复制，全部委托当前活动数据页。
        // 快捷键不在此注册 —— DataGridPage 已在自己的 scene 上绑了 ⌘Z/⇧⌘Z/⌘C，
        // 两边都注册会双触发（撤销两次）；菜单入口复用同一方法，行为一致。
        actions.register("edit.undo", io.agenticdb.ui.Shortcuts.labelled(
                I18n.t("action.edit.undo"), "Mod+Z"), I18n.t("category.edit"), null, this::editUndo);
        actions.register("edit.redo", io.agenticdb.ui.Shortcuts.labelled(
                I18n.t("action.edit.redo"), "Shift+Mod+Z"), I18n.t("category.edit"), null, this::editRedo);
        actions.register("edit.copy", io.agenticdb.ui.Shortcuts.labelled(
                I18n.t("action.edit.copy"), "Mod+C"), I18n.t("category.edit"), null, () -> {
            DataGridPage grid = workspace.activeGrid();
            if (grid == null) {
                status(I18n.t("shell.copyNeedsGrid"));
                return;
            }
            grid.copyFocusedCellValue();
        });

        actions.register("query.run", I18n.t("action.query.run"), I18n.t("category.query"),
                key("Shortcut+R"), () -> {
                    if (!workspace.runQuery()) {
                        status(I18n.t("shell.needQueryPage"));
                    }
                });
        actions.register("query.run-all", I18n.t("action.query.run-all"), I18n.t("category.query"),
                key("Shift+Shortcut+R"), () -> {
                    if (!workspace.runAllQuery()) {
                        status(I18n.t("shell.needQueryPage"));
                    }
                });
        actions.register("query.cancel", I18n.t("action.query.cancel"), I18n.t("category.query"),
                key("Shortcut+Period"), () -> {
                    if (!workspace.cancelQuery()) {
                        status(I18n.t("shell.noRunningQuery"));
                    }
                });
        actions.register("query.explain", I18n.t("action.query.explain"), I18n.t("category.query"),
                key("Shortcut+E"), () -> {
                    if (!workspace.explainQuery()) {
                        status(I18n.t("shell.needQueryPage"));
                    }
                });
        actions.register("query.format", I18n.t("action.query.format"), I18n.t("category.query"),
                key("Alt+Shift+F"), () -> {
                    if (!workspace.formatQuery()) {
                        status(I18n.t("shell.needQueryPage"));
                    }
                });
        // ⭐ Q6：代码片段库（与「已保存查询」同源，但用法不同：插入而非执行）。
        actions.register("query.save-snippet", I18n.t("action.query.save-snippet"), I18n.t("category.query"), null, () -> {
            QueryPage page = workspace.activeQuery();
            if (page == null) {
                status(I18n.t("shell.needQueryPage"));
                return;
            }
            page.saveSnippet();
        });
        actions.register("query.insert-snippet", I18n.t("action.query.insert-snippet"), I18n.t("category.query"), null, this::insertSnippet);
        actions.register("query.save", I18n.t("action.query.save"), I18n.t("category.query"),
                key("Shortcut+S"), () -> {
                    if (!workspace.saveQuery()) {
                        status(I18n.t("shell.needQueryPage"));
                    }
                });
        actions.register("query.comment", I18n.t("action.query.comment"), I18n.t("category.query"),
                key("Shortcut+Slash"), () -> {
                    if (!workspace.toggleQueryComment()) {
                        status(I18n.t("shell.needQueryPage"));
                    }
                });

        actions.register("sidebar.toggle", I18n.t("action.sidebar.toggle"), I18n.t("category.view"),
                key("Shortcut+Backslash"),
                () -> sideBar.setCollapsed(!sideBar.isCollapsed()));
        // ⭐ §6：右侧对象详情面板。⌥⌘D 与 VS Code 的侧栏一致，
        //    用户不用重新学。
        actions.register("details.toggle", I18n.t("action.details.toggle"), I18n.t("category.view"),
                key("Shortcut+Alt+D"),
                this::toggleDetails);
        actions.register("theme.toggle", I18n.t("action.theme.toggle"), I18n.t("category.view"),
                null, () -> {
                    themeManager.toggle();
                    status(I18n.t("shell.theme", themeManager.mode().label()));
                    toast(I18n.t("shell.theme", themeManager.mode().label()));
                });
        actions.register("density-dense", I18n.t("action.density-dense"), I18n.t("category.view"),
                null, () -> changeDensity("dense"));
        actions.register("density-compact", I18n.t("action.density-compact"), I18n.t("category.view"),
                null, () -> changeDensity("compact"));
        actions.register("density-normal", I18n.t("action.density-normal"), I18n.t("category.view"),
                null, () -> changeDensity("normal"));
        actions.register("density-comfort", I18n.t("action.density-comfort"), I18n.t("category.view"),
                null, () -> changeDensity("comfort"));

        actions.register("refresh", I18n.t("action.refresh"), I18n.t("category.database"),
                key("F5"), this::refresh);
        actions.register("connection.reconnect", I18n.t("action.connection.reconnect"), I18n.t("category.database"),
                null, this::reconnectConnection);
        actions.register("connection.disconnect", I18n.t("action.connection.disconnect"), I18n.t("category.database"),
                null, this::disconnectConnection);
        actions.register("demo.open", I18n.t("action.demo.open"), I18n.t("category.database"),
                null, () -> openTab(new TabItem("grid:" + activeProfileId() + ":users",
                        "users", TabKind.GRID, "users", activeProfileId())));

        // ⭐ S2：表清单（Navicat 的「表清单」那一层）
        actions.register("object.tables", I18n.t("action.object.tables"), I18n.t("category.object"),
                null, this::openTableList);

        // ⭐ R36 N6：Ribbon 12 格补齐（真源 §1.2 顺序）。
        //    User / Backup / Automation / Model / BI 背后是 N28 的五个能力面（本轮裁定：
        //    Automation / Model / 数据生成 <b>不做真功能</b>，其余按 R42-b/c/d 排期）——
        //    按诚实条款先落**如实说明页**（工具页签 + 「本版本未提供」），不留死磁贴。
        //    门禁：RibbonCapabilityAuditTest 逐格钉「有注册动作 + 有真实落点」。
        // ⭐ R42 N28-a：View 那一格现在有真落点了 —— 与「新建表」同形，
        //    直接开视图设计器（Navicat 的「新建视图」也是一个窗口，不是一个页签）。
        actions.register("ribbon.view", I18n.t("action.ribbon.view"), I18n.t("category.object"),
                null, () -> tableDdl().createView(activeProfileId()));
        actions.register("ribbon.function", I18n.t("action.ribbon.function"), I18n.t("category.object"),
                null, () -> openRibbonTool(SideBar.TOOL_OBJECT_FUNCTIONS,
                        I18n.t("tool.title.functions")));
        actions.register("ribbon.others", I18n.t("action.ribbon.others"), I18n.t("category.object"),
                null, this::openAllObjects);
        actions.register("ribbon.user", I18n.t("action.ribbon.user"), I18n.t("category.tools"),
                null, () -> openRibbonTool(SideBar.TOOL_USERS, I18n.t("tool.title.users")));
        actions.register("ribbon.backup", I18n.t("action.ribbon.backup"), I18n.t("category.tools"),
                null, () -> openRibbonTool(SideBar.TOOL_BACKUP, I18n.t("tool.title.backup")));
        actions.register("ribbon.automation", I18n.t("action.ribbon.automation"), I18n.t("category.tools"),
                null, () -> openRibbonTool(SideBar.TOOL_AUTOMATION, I18n.t("tool.title.automation")));
        actions.register("ribbon.model", I18n.t("action.ribbon.model"), I18n.t("category.tools"),
                null, () -> openRibbonTool(SideBar.TOOL_MODEL, I18n.t("tool.title.model")));
        actions.register("ribbon.bi", I18n.t("action.ribbon.bi"), I18n.t("category.tools"),
                null, () -> openRibbonTool(SideBar.TOOL_BI, I18n.t("tool.title.bi")));

        // ⭐ N1–N5：表清单的动作全部进注册表 —— 工具栏、右键菜单、命令面板同一份真相。
        //    选择相关的动作先禁用，等表清单页出现后由页面按选择启用。
        actions.register("table-list.new", I18n.t("action.table-list.new"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::createTable));
        actions.register("table-list.open", I18n.t("action.table-list.open"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::openSelected));
        actions.register("table-list.design", I18n.t("action.table-list.design"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::designSelected));
        actions.register("table-list.rename", I18n.t("action.table-list.rename"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::renameSelected));
        actions.register("table-list.truncate", I18n.t("action.table-list.truncate"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::truncateSelected));
        actions.register("table-list.drop", I18n.t("action.table-list.drop"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::dropSelected));
        actions.register("table-list.refresh", I18n.t("action.table-list.refresh"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::reload));
        actions.register("table-list.select-all", I18n.t("action.table-list.select-all"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::selectAll));
        actions.register("table-list.view-list", I18n.t("action.table-list.view-list"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(page -> page.showView("list")));
        actions.register("table-list.view-grid", I18n.t("action.table-list.view-grid"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(page -> page.showView("grid")));
        actions.register("table-list.new-ai", I18n.t("action.table-list.new-ai"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::createTableAi));
        actions.register("table-list.import", I18n.t("action.table-list.import"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::openImport));
        actions.register("table-list.export", I18n.t("action.table-list.export"), I18n.t("category.tableList"), null,
                () -> withActiveTableList(TableListPage::openExport));
        for (String id : List.of("table-list.open", "table-list.design", "table-list.rename",
                "table-list.truncate", "table-list.drop", "table-list.refresh",
                "table-list.select-all", "table-list.view-list", "table-list.view-grid",
                "table-list.new-ai", "table-list.import", "table-list.export")) {
            actions.find(id).ifPresent(action -> action.setEnabled(false));
        }
        // ⭐ S6：顶级「收藏夹」菜单的入口
        actions.register("favorites.open", I18n.t("action.favorites.open"), I18n.t("category.favorites"),
                null, this::openFavorites);
        actions.register("diagnostics", I18n.t("action.diagnostics"), I18n.t("category.tools"),
                null, () -> actions.run("object.diagnostics"));
        // ⭐ R37 N13：侧栏导航项撤掉后，查询历史必须还有入口（功能不可变成不可达）。
        //    工具页签的路由键与 WorkspaceView 共用 SideBar.TOOL_HISTORY。
        actions.register("query-history", I18n.t("action.query-history"), I18n.t("category.tools"),
                null, () -> openTab(new TabItem("tool:" + SideBar.TOOL_HISTORY,
                        I18n.t("action.query-history"), TabKind.TOOL, SideBar.TOOL_HISTORY)));
        // P6 的「导入 / 导出」向导还没做：入口先从侧栏撤掉，
        // 不留「点了只有一句提示」的死 UI（TODO §12.1「要么接要么删」）。
        actions.register("import-export", I18n.t("action.import-export"), I18n.t("category.tools"),
                null, this::openTransferHub);
        actions.register("saved-queries", I18n.t("action.saved-queries"), I18n.t("category.tools"),
                null, this::openSavedQueries);
        actions.register("settings", I18n.t("action.settings"), I18n.t("category.tools"),
                key("Shortcut+Comma"),
                () -> openTab(new TabItem("settings", I18n.t("window.pageType.settings"), TabKind.SETTINGS)));

        actions.register("window.minimize", I18n.t("action.window.minimize"), I18n.t("category.window"),
                key("Shortcut+M"), () -> {
                    if (stage != null) {
                        stage.setIconified(true);
                    }
                });
        actions.register("window.zoom", I18n.t("action.window.zoom"), I18n.t("category.window"),
                null, () -> {
                    if (stage != null) {
                        stage.setMaximized(!stage.isMaximized());
                    }
                });

        actions.register("shortcuts", I18n.t("action.shortcuts"), I18n.t("category.help"),
                key("F1"), this::showShortcuts);
        actions.register("about", I18n.t("action.about"), I18n.t("category.help"),
                null, () -> showInfo(I18n.t("action.about"),
                        I18n.t("shell.aboutBody", io.agenticdb.ui.AppVersion.DISPLAY,
                                startupText()),
                        Icons.Icon.BRAND));
        updateQueryActions();
    }

    private void buildUi() {
        MenuBarFactory menuFactory = new MenuBarFactory(actions);
        MenuBar menuBar = menuFactory.build();
        tableContextMenu = menuFactory.tableMenu();
        appBar = new AppBar(actions, connections,
                activeProfile == null ? "demo" : activeProfile.id(), this::selectConnection);

        // ⭐ 顶区 = 菜单栏 + 91px Ribbon + 页签行 25 = 140px（R36，Navicat 实测）。
        //    全局工具条（连接/搜索/主题/更多）在 Ribbon 右端（AppBar 内部），
        //    页签行只放页签（N7）。菜单栏在 macOS 上走系统菜单栏，在
        //    Windows / Linux 上由 JavaFX 贴在窗口顶部 —— 两种形态都只占一行。
        VBox top = new VBox(menuBar, appBar);
        top.getStyleClass().add("adb-top");

        resizer = buildResizer();
        detailsResizer = buildDetailsResizer();
        // ⭐ 2026-10-30 R36：工具条从这一行挪走了（它以前挤在页签行右端）。
        //    这一行从此只有页签 —— 宽度按整条 Ribbon 走，紧凑档的判据也跟着变。
        HBox chromeRow = new HBox(tabBar);
        chromeRow.setId("adb-chrome-row");
        chromeRow.getStyleClass().add("adb-chrome-row");
        chromeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(tabBar, Priority.ALWAYS);
        // 侧栏 + 详情面板同时打开时主区可能只剩四百多像素：
        // Ribbon 右端工具条切紧凑形态（搜索降级成图标），而不是让磁贴排被挤没。
        appBar.widthProperty().addListener((observable, oldWidth, newWidth) ->
                appBar.fitChromeWidth(newWidth == null ? 0 : newWidth.doubleValue()));

        VBox main = new VBox(chromeRow, workspace);
        main.getStyleClass().add("adb-main");
        // ⭐ 1280 宽 + 右侧详情面板时，三栏的最小宽度会超过窗口：
        //    StackPane 只能把超宽的内容居中，结果左侧菜单/侧栏被挤出可视区。
        //    主区允许收缩（网格本来就有横向滚动），chrome 与详情面板保持原位。
        main.setMinWidth(0);
        VBox.setVgrow(workspace, Priority.ALWAYS);
        HBox.setHgrow(main, Priority.ALWAYS);

        HBox body = new HBox(sideBar, resizer, main, detailsResizer, details);
        body.getStyleClass().add("adb-body");
        body.setMinWidth(0);

        root.getStyleClass().add("adb-root");
        root.setMinWidth(0);
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

    // ─────────────────────────── 右侧详情面板（§6） ───────────────────────────

    /**
     * 显隐 + 定宽。
     *
     * <p>用 {@code setManaged} 而不只是 {@code setVisible}：HBox 会把
     * 不可见但仍受管理的节点算进布局，隐藏后中间会留一道缝。
     */
    private void applyDetailsVisibility() {
        details.setVisible(detailsVisible);
        details.setManaged(detailsVisible);
        detailsResizer.setVisible(detailsVisible);
        detailsResizer.setManaged(detailsVisible);
        details.setMinWidth(detailsWidth);
        details.setPrefWidth(detailsWidth);
        details.setMaxWidth(detailsWidth);
        if (detailsVisible) {
            details.reload();
        }
    }

    private void toggleDetails() {
        detailsVisible = !detailsVisible;
        settings.setBoolean("ui.details.visible", detailsVisible);
        applyDetailsVisibility();
        status(I18n.t("shell.detailsPanel",
                detailsVisible ? I18n.t("shell.shown") : I18n.t("shell.hidden")));
    }

    /** 树里选中了新对象：面板关着就什么都不做（省掉一次白读的元数据查询）。 */
    private void inspectTarget(SideBar.InspectTarget target) {
        if (!detailsVisible || target == null) {
            return;
        }
        ObjectDetailsPanel.Subject subject = toSubject(target);
        if (subject == null) {
            details.showEmpty();
            return;
        }
        details.showObject(subject);
    }

    /**
     * 把树的节点翻译成详情面板的主题。
     *
     * @return {@code null} = 这个节点没有详情可看（连接没连上之类）
     */
    private ObjectDetailsPanel.Subject toSubject(SideBar.InspectTarget target) {
        String connectionId = target.connectionId();
        String database = databaseNameOf(connectionId);
        SchemaService.ObjectKind kind = switch (target.topic()) {
            case TABLE -> SchemaService.ObjectKind.TABLE;
            case VIEW -> SchemaService.ObjectKind.VIEW;
            case TRIGGER -> SchemaService.ObjectKind.TRIGGER;
            case FUNCTION -> SchemaService.ObjectKind.FUNCTION;
            case PROCEDURE -> SchemaService.ObjectKind.PROCEDURE;
            case EVENT -> SchemaService.ObjectKind.EVENT;
            case CONNECTION, CATALOG -> null;
        };
        if (kind == null) {
            // 连接 / 库节点 → §6.2 的数据库概览。
            return ObjectDetailsPanel.Subject.connection(connectionId, database);
        }
        return new ObjectDetailsPanel.Subject(connectionId, database, target.name(),
                kind, target.schema());
    }

    /** 连接 id 对应的库名（catalog 虚拟连接也在内）。 */
    private String databaseNameOf(String connectionId) {
        ConnectionProfile profile = profileById(connectionId);
        return profile == null ? "" : profile.database();
    }

    /**
     * ⭐ R34 N2：窗口标题改成 Navicat 式 {@code <连接> @<库> (<对象>) - <页型> - <产品>}。
     *
     * <p>实拍样例 {@code tz - mkopo @loanmarket (fetch) - Query - Navicat Premium}。
     * 旧版标题是写死的 "AIDB" —— 多开几个窗口后任务栏里分不清哪个是哪个库，
     * 而"当前连的是生产还是测试库"正是数据库客户端最该一直看得见的一件事。
     *
     * <p>缺哪段就省哪段，不留悬空的 {@code @ } 或 {@code ()}：
     * 没连接时整个标题就是产品名。
     */
    private void updateWindowTitle(TabItem tab) {
        if (stage == null) {
            return;
        }
        String product = I18n.t("window.product");
        if (activeProfile == null) {
            stage.setTitle(product);
            return;
        }
        StringBuilder title = new StringBuilder(activeProfile.name());
        String database = activeProfile.database();
        // 「@库」只在它**带来新信息**时才加。SQLite 的 database() 是整个 .db 文件路径，
        // 拼上去就成了 `demo.db @C:\Users\ADMINI~1\AppData\Local\Temp\adb-app-test-…\demo.db`
        // —— 又长又把临时目录摊在标题栏上，而连接名本身已经说了是哪个文件。
        // 实测被自己新加的门禁抓到一次，所以这条判断写在这里并说明原因。
        if (addsInformation(database, activeProfile.name())) {
            title.append(" @").append(database);
        }
        if (tab != null) {
            String target = tab.target();
            if (target != null && !target.isBlank()) {
                title.append(" (").append(target).append(')');
            }
            title.append(" - ").append(pageTypeLabel(tab.kind()));
        }
        stage.setTitle(title.append(" - ").append(product).toString());
    }

    /** 库名是否值得单独出现在标题里：非空、不等于连接名、且不是一条文件路径。 */
    private static boolean addsInformation(String database, String connectionName) {
        if (database == null || database.isBlank() || database.equals(connectionName)) {
            return false;
        }
        return !database.contains("/") && !database.contains("\\") && !database.endsWith(".db");
    }

    /** 页型走资源，不复用 {@code TabKind} 自带的中文标签 —— 那是标签栏文案。 */
    private static String pageTypeLabel(TabKind kind) {
        return switch (kind) {
            case GRID -> I18n.t("window.pageType.grid");
            case QUERY -> I18n.t("window.pageType.query");
            case STRUCTURE -> I18n.t("window.pageType.structure");
            case OBJECT -> I18n.t("window.pageType.object");
            case CONNECTIONS -> I18n.t("window.pageType.connections");
            case ROUTINE -> I18n.t("window.pageType.routine");
            case SETTINGS -> I18n.t("window.pageType.settings");
            case TOOL -> I18n.t("window.pageType.tool");
        };
    }

    /**
     * ⭐ §6 的数据来源：<b>全部走真实元数据，一个值都不编</b>。
     *
     * <p>读不到的（SQLite 没有引擎 / 排序规则 / 创建时间）就在
     * {@code notes} 里说明原因，或者干脆不进 facts ——
     * 放一个 {@code "InnoDB"} 上去比空着更糟：用户会以为这是真的。
     */
    private void loadObjectDetails(ObjectDetailsPanel.Subject subject,
                                   java.util.function.Consumer<ObjectDetailsPanel.Snapshot> onLoaded,
                                   java.util.function.Consumer<String> onError) {
        DbSession session = sessionFor(subject.connectionId());
        if (session == null) {
            onError.accept(I18n.t("shell.noMetadata", subject.name()));
            return;
        }
        executorFor(subject.connectionId()).execute(() -> {
            try {
                ObjectDetailsPanel.Snapshot snapshot = readDetails(session, subject);
                // ⭐ 必须回 FX 线程再渲染。读取跑在数据库执行器上，
                //    直接调面板方法会在 FX 线程外改场景图 —— 轻则什么都不显示，
                //    重则 IllegalStateException，而且异常还被执行器吞掉，
                //    现场只剩一个「标题对了、内容空着」的面板。
                Platform.runLater(() -> onLoaded.accept(snapshot));
            } catch (Exception failure) {
                String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                Platform.runLater(() -> onError.accept(reason));
            }
        });
    }

    private ObjectDetailsPanel.Snapshot readDetails(DbSession session,
                                                    ObjectDetailsPanel.Subject subject)
            throws Exception {
        if (subject.kind() == null) {
            return connectionSnapshot(subject);
        }
        io.agenticdb.introspect.TableStructure structure =
                schemaService.structure(session, subject.name());
        io.agenticdb.introspect.TableMeta meta = tableMetaOf(session, subject.name());
        Long rows = safeCount(session, subject.name());

        List<ObjectDetailsPanel.Fact> facts = new ArrayList<>();
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("column.name"), subject.name()));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.type"),
                structure.view() ? I18n.t("detail.kind.view") : I18n.t("detail.kind.table")));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.database"),
                blankToNull(subject.database())));
        if (subject.schema() != null && !subject.schema().isBlank()) {
            facts.add(new ObjectDetailsPanel.Fact("Schema", subject.schema()));
        }
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.rows"),
                rows == null ? null : String.valueOf(rows)));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.fields"),
                String.valueOf(structure.columns().size())));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.primaryKey"),
                String.valueOf(structure.primaryKeys().size())));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.indexes"),
                String.valueOf(structure.indexes().size())));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.foreignKeys"),
                String.valueOf(structure.foreignKeys().size())));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.triggers"),
                String.valueOf(structure.triggers().size())));
        if (meta != null) {
            facts.add(new ObjectDetailsPanel.Fact(I18n.t("column.dataLength"), meta.sizeText()));
            facts.add(new ObjectDetailsPanel.Fact(I18n.t("column.engine"),
                    blankToNull(meta.engine())));
            facts.add(new ObjectDetailsPanel.Fact(I18n.t("column.collation"),
                    blankToNull(meta.collation())));
            facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.createdAt"),
                    blankToNull(meta.createdAt())));
            facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.updatedAt"),
                    blankToNull(meta.updatedAt())));
        }
        if (!structure.comment().isBlank()) {
            facts.add(new ObjectDetailsPanel.Fact(I18n.t("column.comment"), structure.comment()));
        }

        List<String> columns = new ArrayList<>();
        for (io.agenticdb.model.ColumnDef column : structure.columns()) {
            columns.add(ObjectDetailsPanel.columnLine(column.name(), column.displayType(),
                    column.primaryKey(), column.nullable()));
        }
        List<String> indexes = new ArrayList<>();
        for (io.agenticdb.introspect.IndexDef index : structure.indexes()) {
            indexes.add(index.name() + " · " + String.join(", ", index.columns()));
        }
        List<String> foreignKeys = new ArrayList<>();
        for (io.agenticdb.introspect.ForeignKeyDef key : structure.foreignKeys()) {
            foreignKeys.add(key.displayName() + I18n.t("detail.listSeparator")
                    + String.join(", ", key.columns()) + " → "
                    + key.referencedTable() + "("
                    + String.join(", ", key.referencedColumns()) + ")");
        }
        List<String> triggers = new ArrayList<>();
        for (io.agenticdb.introspect.TriggerDef trigger : structure.triggers()) {
            triggers.add(trigger.name());
        }
        List<String> notes = new ArrayList<>();
        if (meta == null) {
            notes.add(I18n.t("detail.note.noMeta"));
        }
        if (structure.view()) {
            notes.add(I18n.t("detail.note.viewRows"));
        }
        // ⭐ P2-3：这张表最近被怎么查过（历史按表名筛，最新在前）。
        List<String> recent = new ArrayList<>();
        for (QueryHistory.Entry entry : history.recentForObject(subject.name(), 5)) {
            recent.add(entry.summary());
        }
        return new ObjectDetailsPanel.Snapshot(subject, facts, columns, indexes,
                foreignKeys, triggers, recent, notes);
    }

    /** 连接 / 库一级的概览（§6.2）：只放主工作区没有的真实信息。 */
    private ObjectDetailsPanel.Snapshot connectionSnapshot(
            ObjectDetailsPanel.Subject subject) throws Exception {
        DbSession session = sessionFor(subject.connectionId());
        List<ObjectDetailsPanel.Fact> facts = new ArrayList<>();
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.database"), subject.database()));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.type"),
                session.dialect().displayName()));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.connectionState"),
                I18n.t("detail.connected")));
        List<SchemaObject> objects = schemaService.listObjects(session);
        int tables = 0;
        int views = 0;
        int triggers = 0;
        for (SchemaObject object : objects) {
            switch (object.kind()) {
                case TABLE -> tables++;
                case VIEW -> views++;
                case TRIGGER -> triggers++;
                default -> {
                }
            }
        }
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.objectCount"),
                String.valueOf(objects.size())));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.tables"), String.valueOf(tables)));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.views"), String.valueOf(views)));
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.triggers"),
                String.valueOf(triggers)));
        // ⭐ §29.2：连接档案路径是**真实可复制的信息**（备份 / 迁移 / 排查时
        //    用户第一件事就是找它）。显示的是 ConnectionStore 实际读写的那个文件，
        //    不是硬编码的 `~/.agenticdb` —— 测试和自定义启动会覆盖它。
        facts.add(new ObjectDetailsPanel.Fact(I18n.t("detail.profile"),
                connectionStore.file().toString()));
        return new ObjectDetailsPanel.Snapshot(subject, facts, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private io.agenticdb.introspect.TableMeta tableMetaOf(DbSession session, String table) {
        try {
            return schemaService.listTableMeta(session).stream()
                    .filter(meta -> meta.name().equalsIgnoreCase(table))
                    .findFirst()
                    .orElse(null);
        } catch (Exception notProvided) {
            // 方言不提供这 8 列元数据时返回 null，由 notes 说明，不编值。
            return null;
        }
    }

    private Long safeCount(DbSession session, String table) {
        try {
            return schemaService.count(session, table);
        } catch (Exception notCountable) {
            // 视图没有可靠行数 —— 返回 null 让 UI 显示「—」。
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Region buildDetailsResizer() {
        Region handle = new Region();
        handle.getStyleClass().add("adb-resizer");
        handle.setCursor(Cursor.H_RESIZE);
        handle.setMinWidth(4);
        handle.setPrefWidth(4);
        handle.setMaxWidth(4);

        final double[] start = new double[2];
        handle.setOnMousePressed(event -> {
            start[0] = event.getScreenX();
            start[1] = details.getWidth();
        });
        handle.setOnMouseDragged(event -> {
            if (!detailsVisible) {
                return;
            }
            // ⭐ 往左拖 = 面板变宽：起点在面板左边缘，所以是「减」。
            int width = clamp((int) (start[1] - (event.getScreenX() - start[0])),
                    DETAILS_MIN, DETAILS_MAX);
            detailsWidth = width;
            applyDetailsVisibility();
        });
        handle.setOnMouseReleased(event -> {
            if (detailsVisible) {
                settings.setInt("ui.details.width", detailsWidth);
            }
        });
        handle.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && detailsVisible) {
                detailsWidth = DETAILS_DEFAULT;
                applyDetailsVisibility();
                settings.setInt("ui.details.width", detailsWidth);
            }
        });
        return handle;
    }

    private void showTab(TabItem tab) {
        if (tab.connectionId() != null && !tab.connectionId().equals(activeProfileId())) {
            ConnectionProfile target = profileById(tab.connectionId());
            if (target != null) {
                selectConnection(target);
            }
        }
        workspace.showTab(tab);
        // ⭐ N33：常驻「对象」标签本身没有 view，页面在 WorkspaceView 里托管。
        TableListPage page = tab.view() instanceof TableListPage direct
                ? direct : workspace.tableListPage(tab.id());
        if (page != null) {
            activeTableListPage = page;
            page.refreshSelectionStatus();
        } else {
            activeTableListPage = null;
            disableTableListActions();
        }
        // Table 菜单跟着「活动页签是不是一张表」走 —— 与 Navicat 同形。
        // GRID（数据页）与 STRUCTURE（结构页）才是表上下文；表清单不是"某一张表"，
        // 它的动作在页内工具条上，不占这个菜单。
        // ⚠️ 只能 setVisible：Menu 不是 Node，没有 setManaged —— 菜单栏自己听 visibleProperty 重排。
        if (tableContextMenu != null) {
            tableContextMenu.setVisible(tab.kind() == TabKind.GRID
                    || tab.kind() == TabKind.STRUCTURE);
        }
        updateWindowTitle(tab);
        // U8：切标签不是回执 —— 当前页由标签栏本身表达，「已激活：连接管理」
        //      只会让消息位常驻一句车轱辘话。
        status("");
        // ⭐ N25：底栏按页型换段位 —— 查询页多「语句 / 耗时」，对象页只留「计数 + 连接」。
        statusBar.setMode(switch (tab.kind()) {
            case QUERY -> StatusBar.Mode.QUERY;
            case GRID -> StatusBar.Mode.DATA;
            default -> StatusBar.Mode.OBJECT;
        });
        if (tab.kind() != TabKind.GRID && tab.kind() != TabKind.QUERY) {
            statusBar.setRange("—");
            statusBar.setDuration(-1);
            statusBar.setDirty(0);
            // ⭐ UX-5-D：这一类页面（表清单 / 对象首页 / 结构页）没有「当前语句」，
            //    也没有取数时刻 —— 整段收掉，不能拿上一页的语句冒充这一页在看什么。
            statusBar.setSql(null);
            statusBar.setRefreshedAt(-1);
            if (page == null) {
                statusBar.setSelection(0);
            }
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
                I18n.t("shell.unsavedTitle"),
                I18n.t("shell.unsavedTabBody", tab.title(), count),
                I18n.t("shell.unsavedTabHint"),
                I18n.t("shell.discardChanges"));
        styleDialog(confirm.alert());
        return confirm.confirmed();
    }

    private boolean confirmDiscardAll() {
        if (!tabBar.hasDirtyTabs()) {
            return true;
        }
        DestructiveConfirm confirm = DestructiveConfirm.of(
                I18n.t("shell.unsavedTitle"),
                I18n.t("shell.unsavedAnyBody"),
                I18n.t("shell.unsavedQuitHint"),
                I18n.t("shell.discardAndQuit"));
        styleDialog(confirm.alert());
        return confirm.confirmed();
    }

    private void styleDialog(Alert alert) {
        if (stage != null) {
            alert.initOwner(stage);
        }
        // ⭐ Alert 与 Dialog 是同一个毛病：主题类不在 DialogPane 上，
        //    -adb-* 全部落空 → 退回 JavaFX 默认皮肤。
        DialogStyles.apply(alert, stage);
    }

    private void tabClosed(TabItem tab) {
        if (tab.view() == activeTableListPage) {
            activeTableListPage = null;
            statusBar.setSelection(0);
            disableTableListActions();
        }
        workspace.closeTab(tab.id());
        status(I18n.t("shell.closed", tab.title()));
        if (tabBar.closableCount() == 0) {
            // ⭐ S5：常驻的「对象」标签是落点 —— 不再回��一片空白。
            String permanent = tabBar.permanentId();
            if (permanent != null) {
                tabBar.activate(permanent);
            }
            // ⭐ S-7：原来这里还有个 else 走 workspace.showWelcome()，
            //    但构造器无条件 openPermanent(object-home) ⇒ permanent 永远存在，
            //    那条分支不可达，欢迎页的死代码一并删了。
            statusBar.setRange("—");
            statusBar.setDuration(-1);
            statusBar.setDirty(0);
            // ⭐ UX-5-D：全关光了 → 停在常驻的「对象」标签上，那里没有语句可看。
            statusBar.setSql(null);
            statusBar.setRefreshedAt(-1);
        }
        updateTabCloseEnabled();
        updateQueryActions();
    }

    private void updateTabCloseEnabled() {
        // ⭐ 常驻的「对象」标签不可关 —— 停在它上面时「关闭标签」要禁用，
        //    而不是让用户点了没反应。
        actions.find("tab.close").ifPresent(action ->
                action.setEnabled(!tabBar.isPermanent(tabBar.activeId())));
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
        status(I18n.t("shell.openStructure", name));
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
                executors.forConnection(tab.connectionId()), settings);
        // 新开的数据页要继承当前密度档位（A19）。
        page.setDensity(currentDensity());
        // ⭐ P2-3：数据页「历史」只看本表；选中一条载入新查询页。
        page.setHistorySource(() -> history.recentForObject(tab.target(), 10));
        page.setOnHistoryPick(this::openQueryWith);
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
        String initialName = initialQueryName.remove(tab.id());
        File file = initialQueryFile.remove(tab.id());
        QueryPage page = new QueryPage(pageSession, profile.name(), initial,
                executors.forConnection(tab.connectionId()), settings,
                pageFeedback());
        if (initialName != null) {
            page.setQueryName(initialName);
        }
        page.setOnOpenSavedQueries(this::openSavedQueries);
        page.setOnInsertSnippet(this::insertSnippet);
        page.setOnNewQuery(this::openNewQuery);
        // ⭐ G1：查询页真正跟库走 —— 读到库列表就把「只展示」的连接菜单换成可切的库下拉。
        page.setOnSelectCatalog(catalog -> switchQueryCatalog(tab, catalog));
        String pageCatalog = catalogLabelFor(tab.connectionId());
        if (pageCatalog != null) {
            page.setConnectionLabel(pageCatalog);
        }
        loadQueryCatalogs(pageSession, tab.connectionId(), pageCatalog, page);
        // 「存为命名查询」落到已保存查询库。
        page.setOnSaveNamed((name, sql) -> {
            savedQueries.save(name, sql);
            // ⭐ UX-6：侧栏跟着更新，不必重启才看到新保存的查询。
            sideBar.refreshSavedQueries();
            // ⭐ N24：清单页开着时也要立刻出现新条目。
            refreshSavedQueriesPage();
            // ⭐ Q6：片段库接线（存片段 / 取片段）。
            page.setOnSaveSnippet((snippetName, snippetSql) -> {
                savedQueries.saveSnippet(snippetName, snippetSql);
                refreshSavedQueriesPage();
            });
            page.setSnippetSource(() -> savedQueries.snippets());
            status(I18n.t("shell.savedQuery", name));
        });
        if (file != null) {
            page.setCurrentFile(file);
            page.markClean();
        }
        page.setDirtyListener(tab::markDirty);
        page.setStateListener(this::updateQueryActions);
        return page;
    }

    /**
     * ⭐ G1：这个连接标签当前服务的库名。
     *
     * <p>虚拟连接从 id 里取；父连接用它自己保存的 database；
     * 两者都没有（SQLite 这类无库层方言）返回 null —— 菜单保持只展示连接名。
     */
    private String catalogLabelFor(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        if (ConnectionProfile.isCatalogVariantId(connectionId)) {
            return ConnectionProfile.catalogOfCatalogVariant(connectionId);
        }
        ConnectionProfile parent = profileById(connectionId);
        return parent == null || parent.database().isBlank() ? null : parent.database();
    }

    /**
     * ⭐ G1：后台读当前连接的库清单，灌进查询页的库菜单。
     *
     * <p>与侧栏的 {@link #loadCatalogsFor} 同一条方言通道（{@code listCatalogs}），
     * 读不到或为空就保持 R39 的只展示形态 —— 不给用户摆一个只有禁用项的下拉。
     */
    private void loadQueryCatalogs(DbSession pageSession, String connectionId,
                                   String currentCatalog, QueryPage page) {
        if (pageSession == null || connectionId == null) {
            return;
        }
        executors.forConnection(connectionId).execute(() -> {
            List<String> catalogs;
            try {
                catalogs = new ArrayList<>(schemaService.listCatalogs(pageSession));
            } catch (Exception e) {
                return;
            }
            if (catalogs.isEmpty()) {
                return;
            }
            List<String> names = List.copyOf(catalogs);
            Platform.runLater(() -> page.setCatalogs(names, currentCatalog));
        });
    }

    /**
     * ⭐ G1：在查询页里选中另一个库。
     *
     * <p>换绑 = 走侧栏切库同一条虚拟连接通道，**带着当前 SQL 开一个新查询页**；
     * 原页保持绑定原库（同一条 SQL 在两个库里跑是有写风险的，P-1 裁定不自动重跑）。
     * 目标库的会话还没开时先连、连上再开页（{@link #pendingAfterCatalogConnect}）。
     */
    private void switchQueryCatalog(TabItem tab, String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return;
        }
        String parentId = ConnectionProfile.isCatalogVariantId(tab.connectionId())
                ? ConnectionProfile.parentOfCatalogVariant(tab.connectionId())
                : tab.connectionId();
        ConnectionProfile parent = profileById(parentId);
        if (parent == null) {
            status(I18n.t("shell.connectionNotFound", parentId));
            return;
        }
        String sql = currentQuerySql(tab);
        if (catalog.equalsIgnoreCase(parent.database())) {
            openQueryOn(parent.id(), catalog, sql);
            return;
        }
        String variantId = ConnectionProfile.catalogVariantId(parent.id(), catalog);
        if (sessions.isOpen(variantId)) {
            openQueryOn(variantId, catalog, sql);
            return;
        }
        ConnectionProfile variant = variantProfile(variantId);
        if (variant == null) {
            status(I18n.t("shell.cannotOpenDatabase"));
            return;
        }
        pendingAfterCatalogConnect = () -> openQueryOn(variantId, catalog, sql);
        connectCatalog(variant);
    }

    /** ⭐ G1：在指定库的连接上带 SQL 开新查询页（P-1：不自动执行，状态栏说清落在哪个库）。 */
    private void openQueryOn(String connectionId, String catalog, String sql) {
        queryCounter++;
        String id = "query:" + connectionId + ":" + System.nanoTime();
        initialQuerySql.put(id, sql == null ? "" : sql);
        openTab(new TabItem(id, I18n.t("shell.unnamedQuery", queryCounter),
                TabKind.QUERY, null, connectionId));
        status(I18n.t("shell.querySwitchedDatabase", catalog));
    }

    /** ⭐ G1：取某个查询页标签当前的 SQL（页没建起来/不是查询页时给空串）。 */
    private String currentQuerySql(TabItem tab) {
        Node node = tabBar.contentOf(tab.id());
        return node instanceof QueryPage page ? page.sql() : "";
    }

    private StructurePage createStructurePage(TabItem tab) {
        ConnectionProfile profile = connectionFor(tab);
        DbSession pageSession = profile == null ? null : sessions.find(profile.id()).orElse(null);
        if (pageSession == null) {
            return null;
        }
        return new StructurePage(pageSession, schemaService, tab.target(), profile.name(),
                pageFeedback(), executors.forConnection(tab.connectionId()),
                () -> schemaChanged(profile.id()));
    }

    private void schemaChanged(String connectionId) {
        reloadObjects(connectionId);
        workspace.refreshAll();
    }

    private ConnectionProfile connectionFor(TabItem tab) {
        String id = tab == null ? null : tab.connectionId();
        ConnectionProfile profile = id == null ? activeProfile : profileById(id);
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
            public void sql(String statement) {
                statusBar.setSql(statement);
            }

            @Override
            public void refreshedAt(long epochMillis) {
                statusBar.setRefreshedAt(epochMillis);
            }

            @Override
            public void dirty(int count) {
                statusBar.setDirty(count);
            }

            @Override
            public void selection(int count) {
                statusBar.setSelection(count);
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

    /**
     * 打开对象清单 —— 就是常驻的「对象」标签。
     *
     * <p>⭐ N33：清单不再单独占一个 {@code tables:<conn>} 标签页；
     * Navicat 的第一个标签既是对象浏览的起点，也是表清单。
     * 所以这里只「切到对象标签」并让它重读（{@link #objectHomePage()} 负责构建）。
     */
    private void openTableList() {
        if (session == null) {
            status(I18n.t("shell.needConnection"));
            return;
        }
        String permanent = tabBar.permanentId();
        if (permanent != null) {
            tabBar.activate(permanent);
        } else {
            openTab(new TabItem("object-home", I18n.t("window.pageType.object"),
                    TabKind.OBJECT));
        }
        status(I18n.t("category.tableList"));
    }

    /** 表清单页共用注册表里的动作；页面只负责按选择改 enabled。 */
    private TableListPage.Actions tableListActions() {
        return new TableListPage.Actions(
                actions.require("table-list.new"),
                actions.require("table-list.open"),
                actions.require("table-list.design"),
                actions.require("table-list.rename"),
                actions.require("table-list.truncate"),
                actions.require("table-list.drop"),
                actions.require("table-list.refresh"),
                actions.require("table-list.select-all"),
                actions.require("table-list.view-list"),
                actions.require("table-list.view-grid"),
                actions.require("table-list.new-ai"),
                actions.require("table-list.import"),
                actions.require("table-list.export"));
    }

    /** 截图可强制视图；正常启动读上次选择。 */
    private String tableListInitialView() {
        String forced = System.getProperty("adb.table.view",
                System.getenv().getOrDefault("ADB_TABLE_VIEW", "")).trim();
        if (!forced.isEmpty()) {
            return forced;
        }
        return settings.get("ui.tableList.view", "list");
    }

    /**
     * 打开收藏夹（菜单栏的顶级「收藏夹」入口）。
     */
    /**
     * ⭐ R36 / N33：Others 格 —— 回到完整的对象清单（常驻「对象」标签）。
     *
     * <p>它只做「回到完整清单」这一件真事，不装成一个更大的能力面。
     */
    private void openAllObjects() {
        String home = tabBar.permanentId();
        if (home != null) {
            tabBar.activate(home);
        }
        status(I18n.t("shell.openAllObjects"));
    }

    /**
     * ⭐ R36：Ribbon 上还没建能力面的格子（User/Backup/Automation/Model/BI）
     * 落**如实说明页** —— 走 {@link SideBar#TOOL_USERS} 那族路由键，
     * {@code WorkspaceView} 的 TOOL 分支对未接路由统一给「本版本未提供」的空态。
     */
    private void openRibbonTool(String route, String title) {
        openTab(new TabItem("tool:" + route, title, TabKind.TOOL, route));
        status(I18n.t("shell.openTool", title));
    }

    private void openFavorites() {
        // ⭐ R51 T1：交出去的是整条收藏（连接 + 表名），不是裸表名 ——
        //    同名表在两个库里点开必须是各自那一张。
        FavoritesPage page = new FavoritesPage(sideBar.favorites(),
                sideBar::openFavorite, this::status);
        page.show(stage);
    }

    /**
     * 打开「已保存查询」。
     *
     * <p>侧栏的「已保存查询」以前是空壳页 —— 现在它真的能用。
     */
    /**
     * ⭐ Q6：插入代码片段（弹窗选片段 → 插到光标处）。
     */
    private void insertSnippet() {
        QueryPage page = workspace.activeQuery();
        if (page == null) {
            status(I18n.t("shell.needQueryPage"));
            return;
        }
        List<SavedQueries.Entry> snippets = page.snippets();
        if (snippets.isEmpty()) {
            status(I18n.t("shell.noSnippets"));
            return;
        }
        // ChoiceDialog 的「默认项」只能通过构造器给（没有 setter）。
        SavedQueries.Entry first = snippets.getFirst();
        List<SavedQueries.Entry> rest = snippets.subList(1, snippets.size());
        javafx.scene.control.ChoiceDialog<SavedQueries.Entry> dialog =
                new javafx.scene.control.ChoiceDialog<>(first,
                        rest.toArray(new SavedQueries.Entry[0]));
        dialog.setTitle(I18n.t("shell.insertSnippet"));
        dialog.setHeaderText(I18n.t("shell.insertSnippetHint"));
        dialog.showAndWait().ifPresent(page::insertSnippet);
    }

    /** ⭐ N24：Queries 清单是对象页，不是弹窗 —— 关掉标签再开也不丢位置。 */
    private static final String SAVED_QUERIES_TAB = "tool:saved-queries";

    private void openSavedQueries() {
        SavedQueriesPage page = savedQueriesPage();
        page.refresh();
        if (tabBar.openTabs().stream().anyMatch(tab -> SAVED_QUERIES_TAB.equals(tab.id()))) {
            tabBar.activate(SAVED_QUERIES_TAB);
            return;
        }
        openTab(new TabItem(SAVED_QUERIES_TAB, I18n.t("action.saved-queries"),
                TabKind.TOOL, "saved-queries", null, page));
    }

    private SavedQueriesPage savedQueriesPage() {
        if (savedQueriesPage == null) {
            savedQueriesPage = new SavedQueriesPage(savedQueries,
                    entry -> openSavedQuery(new SideBar.SavedQueryRequest(
                            activeProfileId(), entry.name(), entry.sql())),
                    this::openNewQuery,
                    () -> {
                        sideBar.refreshSavedQueries();
                        refreshSavedQueriesPage();
                    },
                    this::status);
        }
        return savedQueriesPage;
    }

    private void refreshSavedQueriesPage() {
        if (savedQueriesPage != null) {
            savedQueriesPage.refresh();
        }
    }

    /**
     * ⭐ UX-6：打开侧栏里的一条已保存查询。
     *
     * <p>查询页跟着**那条查询所在的连接**走（多连接下不能用当前活跃连接
     * 硬套）；标题沿用保存时的名字，方便在标签栏里认出来。
     */
    private void openSavedQuery(SideBar.SavedQueryRequest request) {
        queryCounter++;
        String connection = request.connectionId() == null || request.connectionId().isBlank()
                ? activeProfileId() : request.connectionId();
        String name = request.name() == null || request.name().isBlank()
                ? I18n.t("shell.unnamedQuery", queryCounter) : request.name();
        String id = "query:" + connection + ":" + System.nanoTime();
        initialQuerySql.put(id, request.sql());
        initialQueryName.put(id, request.name());
        openTab(new TabItem(id, name, TabKind.QUERY, null, connection));
        status(I18n.t("shell.loadedSavedQuery", name));
    }

    /**
     * 打开导入 / 导出入口（P6）。
     *
     * <p>侧栏的「导入 / 导出」以前是空壳页 —— 现在它真的能干活：
     * 选表 → 导出（CSV/TSV/JSON/SQL，流式）或导入（CSV/TSV，批量提交 + 坏行报告）。
     */
    private void openTransferHub() {
        DbSession current = session;
        if (current == null) {
            status(I18n.t("shell.needConnection"));
            return;
        }
        List<String> tables = objectsByConnection.getOrDefault(activeProfileId(), List.of())
                .stream()
                .filter(object -> object.kind()
                        == io.agenticdb.introspect.SchemaService.ObjectKind.TABLE)
                .map(io.agenticdb.introspect.SchemaService.SchemaObject::name)
                .toList();
        TransferHub hub = new TransferHub(current, tables,
                executors.forConnection(activeProfileId()), this::status);
        hub.show(stage);
    }

    /**
     * 复制当前标签的目标对象名（可选复制成 SELECT 语句）。
     *
     * <p>只对有表目标的标签（数据页 / 结构页）有意义。
     */
    private void copyActiveTabTarget(boolean asSelect) {
        TabItem tab = tabBar.active();
        if (tab == null || tab.target() == null || tab.target().isBlank()) {
            status(I18n.t("shell.noTableName"));
            return;
        }
        String text;
        if (!asSelect) {
            text = tab.target();
        } else {
            // ⭐ D-2：语句由标签所属连接的方言生成 —— 引号与分页写法都跟库走，
            // 写死 "SELECT * FROM \"t\" LIMIT 200" 在 MySQL 上是错引号、
            // 在 SQL Server / Oracle 上直接跑不通。
            DbSession tabSession = sessionOrCurrent(tab.connectionId());
            if (tabSession == null) {
                status(I18n.t("shell.needConnection"));
                return;
            }
            text = tabSession.dialect().plainPageSql(tab.target(), 200, 0) + ";";
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        status(I18n.t("shell.copied", text));
    }

    private void openNewQuery() {
        queryCounter++;
        String title = I18n.t("shell.unnamedQuery", queryCounter);
        openTab(new TabItem("query:" + activeProfileId() + ":" + System.nanoTime(), title,
                TabKind.QUERY, null, activeProfileId()));
        status(I18n.t("shell.newQuery", title));
    }

    /**
     * 新建一个查询页并预填 SQL（查询历史的「载入到新查询」走这条）。
     */
    private void openQueryWith(String sql) {
        queryCounter++;
        String title = I18n.t("shell.unnamedQuery", queryCounter);
        String id = "query:" + activeProfileId() + ":" + System.nanoTime();
        initialQuerySql.put(id, sql);
        openTab(new TabItem(id, title, TabKind.QUERY, null, activeProfileId()));
        status(I18n.t("shell.loadedHistoryQuery", title));
    }

    private void openSqlFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("shell.openSqlTitle"));
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
            status(I18n.t("shell.openFailed", readable(e)));
            toast(I18n.t("shell.openFailed", readable(e)));
            return;
        }
        String id = "query:" + activeProfileId() + ":" + System.nanoTime();
        initialQuerySql.put(id, sql);
        initialQueryFile.put(id, file);
        openTab(new TabItem(id, file.getName(), TabKind.QUERY, null, activeProfileId()));
        status(I18n.t("shell.opened", file.getName()));
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
            status(I18n.t("shell.undoNeedsGrid"));
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
            status(I18n.t("shell.redoNeedsGrid"));
            return;
        }
        grid.redo();
        updateEditActions();
    }

    private void reloadObjects(String connectionId) {
        ConnectionProfile profile = profileFor(connectionId);
        DbSession target = profile == null ? null : sessionFor(profile.id());
        if (target == null) {
            status(I18n.t("shell.refreshNeedsConnection"));
            return;
        }
        status(I18n.t("shell.refreshingObjects"));
        executors.forConnection(activeProfileId()).execute(() -> {
            try {
                List<SchemaObject> objects = schemaService.listObjects(target);
                Platform.runLater(() -> {
                    objectsByConnection.put(profile.id(), objects);
                    // ⭐ 侧栏按连接 id 收对象 —— 非当前连接也要更新它的那棵树。
                    sideBar.setObjects(profile.id(), objects);
                    status(I18n.t("shell.objectsRefreshed", objects.size()));
                });
            } catch (SQLException e) {
                String message = I18n.t("shell.refreshObjectsFailed", readable(e));
                Platform.runLater(() -> {
                    status(message);
                    sideBar.setObjectsReadFailed(profile.id());
                });
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

        Button retry = new Button(I18n.t("shell.retry"));
        retry.getStyleClass().addAll("adb-button", "adb-button--ghost");
        retry.setOnAction(event -> status(I18n.t("shell.restartForDemo")));
        page.getChildren().addAll(title, error, retry);
        return page;
    }

    // ─────────────────────────── 反馈 ───────────────────────────

    private void refresh() {
        if (workspace.refreshActive()) {
            status(I18n.t("shell.pageRefreshed", LocalTime.now().withNano(0)));
            toast(I18n.t("shell.refreshed"));
        } else {
            status(I18n.t("shell.noRefreshablePage"));
            toast(I18n.t("shell.pageEmpty"));
        }
    }

    private String currentDensity() {
        return density;
    }

    /**
     * ⭐ UX-0：截图脚本用 ADB_DENSITY 钉住密度；它只影响本次运行，**不写回偏好**。
     * 否则同一份脚本会被上一次运行留下的档位污染（实拍踩过：应为 28，拍成 34）。
     */
    private static String forcedDensity() {
        return System.getProperty("adb.density",
                System.getenv().getOrDefault("ADB_DENSITY", "")).trim();
    }

    private String initialDensity() {
        String forced = forcedDensity();
        return forced.isEmpty() ? settings.get("ui.density", "normal") : forced;
    }

    private void changeDensity(String density) {
        String value = applyDensity(density);
        status(I18n.t("shell.density", switch (value) {
            case "dense" -> I18n.t("action.density-dense");
            case "compact" -> I18n.t("action.density-compact");
            case "comfort" -> I18n.t("action.density-comfort");
            default -> I18n.t("action.density-normal");
        }));
    }

    private String applyDensity(String density) {
        String value = switch (density) {
            case "dense", "compact", "normal", "comfort" -> density;
            default -> "normal";
        };
        this.density = value;
        getStyleClass().removeAll("density-dense", "density-compact", "density-normal",
                "density-comfort");
        getStyleClass().add("density-" + value);
        if (forcedDensity().isEmpty()) {
            settings.set("ui.density", value);
        }
        // A19：密度以前只影响对象树，数据网格行高不变。现在一起作用。
        if (workspace != null) {
            workspace.applyDensity(value);
        }
        if (objectPage != null) {
            objectPage.setDensity(value);
        }
        if (sideBar != null) {
            sideBar.setDensity(value);
        }
        // 勾选态跟着动作走：菜单 / 命令面板读的都是同一份 selected。
        for (String id : new String[]{"density-dense", "density-compact",
                "density-normal", "density-comfort"}) {
            actions.find(id).ifPresent(action ->
                    action.setSelected(id.endsWith(value)));
        }
        return value;
    }

    private void status(String message) {
        statusBar.setMessage(message);
    }

    /** 「1 / true / yes」算开，其余算关。系统属性优先于环境变量。 */
    private static boolean flag(String property, String env) {
        String raw = text(property, env);
        return raw.equals("1") || raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes");
    }

    /** 系统属性优先、环境变量兜底 —— 与 {@link #flag} 同一口径，只是要原值。 */
    private static String text(String property, String env) {
        return System.getProperty(property, System.getenv().getOrDefault(env, "")).trim();
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
        startToast(action == null ? TOAST_MILLIS : TOAST_ACTION_MILLIS);
    }

    /** 「结果不可撤销」类提示：没有撤销按钮，但用户需要更长时间反应过来（4.5-21 第三档）。 */
    private void toastUnreversible(String message) {
        toastLabel.setText(message);
        toastAction.setVisible(false);
        toastAction.setManaged(false);
        toast.setVisible(true);
        toast.setManaged(true);
        startToast(TOAST_RESULT_MILLIS);
    }

    private void startToast(long millis) {
        if (toastTimer != null) {
            toastTimer.stop();
        }
        toastTimer = new PauseTransition(Duration.millis(millis));
        toastTimer.setOnFinished(event -> hideToast());
        toastTimer.play();
    }

    // ⭐ Toast 时长来自设计令牌（4.5-14）：三档分级是产品决策，不该散成魔数。
    private static final long TOAST_MILLIS = io.agenticdb.ui.Tokens.DUR_TOAST_LIGHT;
    private static final long TOAST_ACTION_MILLIS = io.agenticdb.ui.Tokens.DUR_TOAST_ACTION_LIGHT;
    private static final long TOAST_RESULT_MILLIS = io.agenticdb.ui.Tokens.DUR_TOAST_RESULT_LIGHT;

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

    /** 关于弹窗里的启动耗时（P7）：给用户 / 支持人员一个可直接复述的数字。 */
    private String startupText() {
        long millis = startup.firstScreenMillis();
        String line = millis > 0
                ? I18n.t("shell.startupElapsed", millis)
                : I18n.t("shell.startupUnrecorded");
        return line + (startup.slow() ? I18n.t("shell.startupSlow") : "")
                + "\n" + I18n.t("shell.startupLog");
    }

    private void showInfo(String title, String content) {
        showInfo(title, content, null);
    }

    /**
     * ⭐ 2026-10-28 顶区精简：顶栏那块 AIDB logo 删掉之后，品牌 mark 在应用内的
     * 唯一落点是「关于」对话框 —— 低频、但用户找品牌的时候就在那里。
     */
    private void showInfo(String title, String content, Icons.Icon graphic) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(content);
        if (graphic != null) {
            alert.setGraphic(Icons.of(graphic, Icons.SIZE_EMPTY));
        }
        if (stage != null) {
            alert.initOwner(stage);
        }
        // ⭐ 这里原来手写「挂样式表 + adb-dialog」—— 少了主题类，
        //    -adb-* 查找照样落空（半套皮肤）。DialogStyles 是这三件事的唯一入口。
        DialogStyles.apply(alert, stage);
        alert.showAndWait();
    }

    /**
     * 快捷键总表（F1）。
     *
     * <p>U1：这张表原来是一段手写 macOS 符号的纯文本（{@code ⌘N / ⇧⌘N}），
     * 而实拍与主要用户都在 Windows —— 键盘上没有 ⌘ 这个键，且比例字体下
     * 空格对齐会散架。现在两列栅格、按键走 {@link io.agenticdb.ui.Shortcuts}，
     * 与菜单、tooltip、状态栏文案同一份真相。
     */
    private void showShortcuts() {
        // {语义按键, 动作}；按键为 null 的行是分节标题。
        List<String[]> rows = List.of(
                new String[]{null, I18n.t("shortcut.section.session")},
                new String[]{"Mod+N", I18n.t("action.new-connection")},
                new String[]{"Mod+O", I18n.t("action.open-sql")},
                new String[]{"Shift+Mod+N", I18n.t("action.new-query")},
                new String[]{"Mod+W", I18n.t("action.tab.close")},
                new String[]{"Shift+Mod+W", I18n.t("action.window.close")},
                new String[]{"Mod+M", I18n.t("action.window.minimize")},
                new String[]{"Mod+Q", I18n.t("action.app.quit")},
                new String[]{null, I18n.t("shortcut.section.editing")},
                new String[]{"Mod+K", I18n.t("action.command-palette")},
                new String[]{"Mod+F", I18n.t("shortcut.search")},
                new String[]{"Mod+Z", I18n.t("shortcut.undo")},
                new String[]{"Shift+Mod+Z", I18n.t("action.edit.redo")},
                new String[]{"Mod+C", I18n.t("shortcut.copyCell")},
                new String[]{"Alt+Mod+C", I18n.t("shortcut.copyTsv")},
                new String[]{"Shift+Mod+C", I18n.t("shortcut.copyInsert")},
                new String[]{"Mod+A", I18n.t("shortcut.selectAll")},
                new String[]{"Mod+UP", I18n.t("shortcut.firstRow")},
                new String[]{"Mod+DOWN", I18n.t("shortcut.lastRow")},
                new String[]{"ESCAPE", I18n.t("shortcut.escape")},
                new String[]{null, I18n.t("menu.query")},
                new String[]{"Mod+ENTER", I18n.t("action.query.run")},
                new String[]{"Shift+Mod+ENTER", I18n.t("action.query.run-all")},
                new String[]{"Mod+R", I18n.t("shortcut.runMenu")},
                new String[]{"Shift+Mod+R", I18n.t("shortcut.runAllMenu")},
                new String[]{"Mod+PERIOD", I18n.t("action.query.cancel")},
                new String[]{"Mod+E", I18n.t("action.query.explain")},
                new String[]{"Alt+Shift+F", I18n.t("action.query.format")},
                new String[]{"Mod+S", I18n.t("action.query.save")},
                new String[]{"Mod+SLASH", I18n.t("action.query.comment")},
                new String[]{"Mod+G", I18n.t("shortcut.findNext")},
                new String[]{"F5", I18n.t("shortcut.refreshRun")},
                new String[]{null, I18n.t("menu.view")},
                new String[]{"Mod+BACKSLASH", I18n.t("action.sidebar.toggle")},
                new String[]{"Mod+Alt+D", I18n.t("action.details.toggle")},
                new String[]{"Mod+COMMA", I18n.t("action.settings")},
                new String[]{"F1", I18n.t("shortcut.thisTable")});

        GridPane grid = new GridPane();
        grid.getStyleClass().add("adb-shortcuts");
        ColumnConstraints keyColumn = new ColumnConstraints();
        keyColumn.setMinWidth(120);
        keyColumn.setHalignment(javafx.geometry.HPos.RIGHT);
        ColumnConstraints actionColumn = new ColumnConstraints();
        actionColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keyColumn, actionColumn);

        int row = 0;
        for (String[] entry : rows) {
            if (entry[0] == null) {
                Label group = new Label(entry[1]);
                group.getStyleClass().add("adb-shortcut__group");
                GridPane.setColumnSpan(group, 2);
                grid.add(group, 0, row++);
                continue;
            }
            Label key = new Label(io.agenticdb.ui.Shortcuts.of(entry[0]));
            key.getStyleClass().add("adb-shortcut__key");
            Label action = new Label(entry[1]);
            action.getStyleClass().add("adb-shortcut__action");
            grid.add(key, 0, row);
            grid.add(action, 1, row++);
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("shortcut.title"));
        dialog.setHeaderText(I18n.t("shortcut.heading",
                io.agenticdb.ui.Shortcuts.mac() ? "macOS" : "Windows / Linux"));
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        DialogStyles.apply(dialog, stage);
        dialog.showAndWait();
    }

    private static String readable(Throwable error) {
        // ⭐ V-12：统一走错误归类 —— 给出「发生了什么 + 建议怎么办」，
        //    而不是把驱动的英文原文直接甩给用户。
        return ErrorText.readable(error);
    }

    private static KeyCombination key(String value) {
        return KeyCombination.keyCombination(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
