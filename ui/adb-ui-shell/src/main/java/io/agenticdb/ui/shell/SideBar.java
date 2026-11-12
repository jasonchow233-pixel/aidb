package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.RowHeights;
import io.agenticdb.ui.Tokens;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import io.agenticdb.jdbc.Dialect;
import io.agenticdb.asset.AssetKind;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.session.ConnectionProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 左侧对象树。节点来自 {@link SchemaService} 的真实内省结果，不再硬编码表名。
 *
 * <p>侧栏两种形态：展开 {@code w-sidebar} 令牌宽 / 折叠 48px。折叠不是隐藏，
 * 高频入口仍保留在图标栏里 —— 这是与「一键隐藏整栏」的关键差别。
 */
public final class SideBar extends VBox {

    private final ActionRegistry actions;
    private String connectionName;
    private String connectionId;
    private final Consumer<TabItem> openTab;
    private final Consumer<String> status;
    private Consumer<ConnectionProfile> onSelectConnection = profile -> { };
    /**
     * ⭐ §6：树里被选中的对象要报给右侧详情面板。
     *
     * <p>分组节点（「表」「视图」这类容器）不上报 —— 它们不是对象，
     * 报上去只会让详情栏闪一下空态。
     */
    private Consumer<InspectTarget> onInspect = target -> { };
    private List<ConnectionProfile> connections = List.of();
    /**
     * ⭐ UX-5：连接「连上了没有」的判据，由外壳注入。
     *
     * <p>{@code null} = 没人告诉我 —— 这时**不画状态点**。画一个灰点等于
     * 宣称「这个连接是离线的」，而侧栏并不知道；界面不许编造状态。
     */
    private Predicate<String> connectionAlive;
    /** ⭐ D-2：按连接 id 取方言（侧栏不造 SQL 文本，见 {@link #setDialectResolver}）。 */
    private Function<String, Dialect> dialectFor = connectionId -> null;
    private List<SchemaObject> tables = List.of();
    private List<SchemaObject> views = List.of();
    /**
     * ⭐ 每个连接各自的对象（多连接对象树）。
     *
     * <p>以前侧栏只显示「当前连接」的对象 —— 切到另一个连接，整棵树就换掉了，
     * 没法同时浏览两个库。Navicat 的对象树是**所有连接都在同一棵树里**。
     */
    private final Map<String, List<SchemaObject>> objectsByConnection = new LinkedHashMap<>();
    /**
     * ⭐ U1：每个连接的 catalog（数据库）清单。
     *
     * <p>只有「切 catalog 要重连」的方言有（MySQL / MariaDB、PG、SQL Server）：
     * 连接节点下先出库一层，库节点下才是表 / 视图分组。没有 catalog 的连接
     * （SQLite、Oracle）这个 map 里没有条目 → 分组直接挂在连接节点下（老形态）。
     *
     * <p>{@link CatalogEntry#connectionId()} 是**Serve 这个库的连接 id**：
     * 父连接自己的库就是父 id，别的库是虚拟连接 id（{@code 父id@库名}）——
     * 于是「当前库高亮」「懒加载对象」「切库」全都按连接 id 一把搞定。
     */
    private final Map<String, List<CatalogEntry>> catalogsByConnection = new LinkedHashMap<>();
    /** 需要某个连接的对象时调用（AppShell 负责连上去读）。 */
    private Consumer<String> onLoadObjects = connectionId -> { };
    /** 需要某个连接的 catalog 清单时调用（AppShell 负责读）。 */
    private Consumer<String> onLoadCatalogs = connectionId -> { };
    /** ⭐ EXT（P1-4）：需要某个 Redis 连接的库清单时调用（AppShell 负责连上去读）。 */
    private Consumer<String> onLoadRedisDbs = connectionId -> { };
    /** ⭐ EXT（P1-4）：每个 Redis 连接「有数据的库」清单（{@code INFO keyspace} 过滤空库）。 */
    private final Map<String, List<Integer>> redisDbsByConnection = new LinkedHashMap<>();
    /** ⭐ EXT（P1-4）：库清单读失败的连接（展开可重试）。 */
    private final Set<String> failedRedis = new LinkedHashSet<>();
    /** 已经尝试读过 catalog 的连接（读失败也不反复试）。 */
    private final Set<String> attemptedCatalogs = new LinkedHashSet<>();
    /** 双击 / 右键「切换到此数据库」（AppShell 负责重连）。 */
    private Consumer<CatalogSwitch> onSwitchCatalog = request -> { };
    /** 已经尝试过读取的对象（读失败时摘掉标记 → 展开重试，见 {@link #setObjectsReadFailed}）。 */
    private final Set<String> attempted = new LinkedHashSet<>();
    /**
     * ⭐ R51 T2：最近一次**读失败**的连接 / 库清单。
     *
     * <p>以前读失败只在状态栏闪一行，树照旧画成「这个库什么都没有」——
     * 用户分不清「空库」和「没读到」，而且 {@link #attempted} 已经记了「试过」，
     * 于是除非重启，那条懒加载链**永远不会再跑**。同一棵树里列读取是有重试的
     * （{@link #setColumns} 的空清单分支），对象读取却没享受同样的待遇，
     * 这两条路径的差别只在「有没有人记得它失败过」。
     */
    private final Set<String> failedObjects = new LinkedHashSet<>();
    private final Set<String> failedCatalogs = new LinkedHashSet<>();
    /**
     * ⭐ S-3：展开表节点时才读它的列（懒加载）。
     *
     * <p>为 null = 没接回调 —— 表节点保持叶子，不摆一个点不开的箭头骗人。
     */
    private BiConsumer<String, String> onLoadColumns;
    /** ⭐ H6-e：刷新此表时先作废底层 describe 缓存（可空 —— 裸侧栏只清自己的缓存）。 */
    private BiConsumer<String, String> onInvalidateColumns;
    /** ⭐ S-3：连接 + 表 → 列名（展开读到后缓存，刷新树不丢）。 */
    private final Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
    /** ⭐ S-3：已发起过列读取的表（读失败时摘掉标记 → 再展开一次会重试）。 */
    private final Set<String> attemptedColumns = new LinkedHashSet<>();
    /**
     * 「刷新对象」的回调。
     *
     * <p>⭐ 带连接 id：多连接对象树 + catalog 树之后，用户右键的节点可能属于
     * 另一个连接 / 另一个库 —— 刷新必须刷**那个**连接，不是当前活跃的。
     */
    private Consumer<String> onRefreshObjects = id -> { };
    private Consumer<TableRequest> onTableDdl = request -> { };
    private Consumer<RoutineRequest> onOpenRoutine = request -> { };
    /** ⭐ H6-d：树内把连接移进分组（档案与落盘归外壳 —— 侧栏只问名字）。 */
    private BiConsumer<ConnectionProfile, String> onMoveToGroup;
    /** ⭐ H6-d：树内重命名分组（外壳逐成员搬家 —— 分组名存在每个连接档案上）。 */
    private BiConsumer<String, String> onRenameGroup;
    /** ⭐ H6-d：门禁注入选答（Monocle 按不住模态弹窗 —— D-69/D-73 同一先例）。 */
    private Function<String, String> groupPromptForTest;
    /** ⭐ UX-6：连接节点下的「已保存查询」来源（外壳给 savedQueries::all）。 */
    private Supplier<List<SavedQueries.Entry>> savedQuerySource = List::of;
    /** ⭐ UX-6：双击 / 回车一条已保存查询 → 外壳建查询页并预填 SQL。 */
    private Consumer<SavedQueryRequest> onOpenSavedQuery = request -> { };
    /** ⭐ N24：双击 / 回车「Queries」分组 → 打开已保存查询的对象清单页。 */
    private Runnable onOpenSavedQueries = () -> { };

    /**
     * ⭐ R51 T1：收藏 = **连接 + 对象名** 的键集合（见 {@link #starKey}）。
     *
     * <p>以前存的是裸对象名：同一个 MySQL 服务上 {@code orders.users} 和
     * {@code audit.users} 是两张表，收藏其中一张，另一张也跟着亮星、
     * 也一起进「只看收藏」的过滤 —— 而且从收藏夹点开永远开到**当前连接**上。
     */
    private final Set<String> starred = new LinkedHashSet<>();
    /**
     * 旧版存下来的**只写了名字**的收藏（限定了连接的键都带分隔符）。
     *
     * <p>不猜它属于哪个库：它按老语义在所有库里都算收藏，直到用户在某一张表上
     * 收藏 / 取消一次 —— 那一次把它展开成精确的键（{@link #toggleStar}）。
     */
    private final Set<String> legacyStarred = new LinkedHashSet<>();
    /** 收藏的持久化位置（以前只在内存里 —— 退出即丢）。 */
    private final SettingsStore settings;
    private static final String STARRED_KEY = "sidebar.starred";
    /** 落盘时每行 {@code 连接id<TAB>对象名}；没有 TAB 的行就是旧版裸名。 */
    private static final char STAR_FIELD = '\t';
    private DemoNode selectedNode;
    private String dialectName = "—";
    /** ⭐ R54：折叠态那颗「当前连接」按钮 —— 换连接 / 换方言时要重画它的厂商图形。 */
    private Button railConnection;
    private final VBox expanded = new VBox(8);
    private final VBox rail = new VBox(6);
    private final TextField search = new TextField();
    private final TreeView<DemoNode> tree = new TreeView<>();
    private final ComboBox<String> schemaPicker = new ComboBox<>();
    /** 切换 schema 的回调（AppShell 负责改会话并重新内省）。 */
    private Consumer<String> onSelectSchema = schema -> { };
    private String activeSchema;

    private Consumer<Boolean> onCollapsed = value -> { };
    private boolean collapsed;

    public SideBar(ActionRegistry actions,
                   List<SchemaObject> objects,
                   String connectionName,
                   Consumer<TabItem> openTab,
                   Consumer<String> status) {
        this(actions, objects, connectionName, openTab, status, null, List.of(), profile -> { });
    }

    public SideBar(ActionRegistry actions,
                   List<SchemaObject> objects,
                   String connectionName,
                   Consumer<TabItem> openTab,
                   Consumer<String> status,
                   String connectionId,
                   List<ConnectionProfile> connections,
                   Consumer<ConnectionProfile> onSelectConnection) {
        this(actions, objects, connectionName, openTab, status, connectionId, connections,
                onSelectConnection, null);
    }

    public SideBar(ActionRegistry actions,
                   List<SchemaObject> objects,
                   String connectionName,
                   Consumer<TabItem> openTab,
                   Consumer<String> status,
                   String connectionId,
                   List<ConnectionProfile> connections,
                   Consumer<ConnectionProfile> onSelectConnection,
                   SettingsStore settings) {
        this.actions = actions;
        this.settings = settings;
        loadStarred();
        this.connectionName = connectionName;
        this.connectionId = connectionId;
        this.openTab = openTab;
        this.status = status;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.onSelectConnection = onSelectConnection == null ? profile -> { } : onSelectConnection;
        setObjects(objects == null ? List.of() : objects, false);

        getStyleClass().add("adb-sidebar");
        // ⭐ UX-5：宽度走 w-sidebar 令牌，不再写死 240 ——
        //    装配后 AppShell 会用「持久化过的实际宽度」覆盖这里，
        //    但单独构造（测试 / 预览）必须落到同一个默认值上。
        double width = io.agenticdb.ui.Tokens.W_SIDEBAR_LIGHT;
        setPrefWidth(width);
        setMinWidth(width);
        setMaxWidth(width);

        registerObjectActions();
        buildExpanded();
        buildRail();
        getChildren().add(expanded);
        // ⭐ 让展开的侧栏占满 SideBar 全高：不然后面的搜索底栏会按自然高度
        //    停在窗口中上部（悬浮感），而不是贴住窗口底部。
        VBox.setVgrow(expanded, Priority.ALWAYS);
    }

    /**
     * 注册对象树的动作（4.5-8）。
     *
     * <p>⭐ 以前侧栏树的右键菜单是<b>手搓 MenuItem</b>：7 个菜单项既不在命令面板里，
     * 也没有统一的 enabled 判断。现在它们和其它动作一样进 {@link ActionRegistry}，
     * 于是能在 ⌘K 里搜到（「复制 SELECT」），禁用时也会变灰并给出解释。
     *
     * <p>动作行为依赖「当前选中的树节点」，所以这里登记的是<b>节点无关</b>的命令，
     * 具体作用对象由 {@link #selectedNode} 决定。
     */
    private void registerObjectActions() {
        actions.register("object.open-data", I18n.t("action.object.open-data"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.OPEN_DATA));
        actions.register("object.open-structure", I18n.t("action.object.open-structure"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.OPEN_STRUCTURE));
        actions.register("object.new-query", I18n.t("action.object.new-query"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.NEW_QUERY));
        actions.register("object.favorite", I18n.t("action.object.favorite"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.FAVORITE));
        actions.register("object.copy-name", I18n.t("action.object.copy-name"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.COPY_NAME));
        actions.register("object.copy-select", I18n.t("action.object.copy-select"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.COPY_SELECT));
        actions.register("object.diagnostics", I18n.t("action.object.diagnostics"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.DIAGNOSTICS));
        actions.register("object.refresh-objects", I18n.t("action.object.refresh-objects"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.REFRESH_OBJECTS));
        // ⭐ H6-e：「刷新此表」—— 不整树重建，缓存只炸这一份。
        actions.register("object.refresh-table", I18n.t("action.object.refresh-table"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.REFRESH_TABLE));
        // ⭐ H6-d：分组在树上就能管。直接挂 handler 不走 onSelected ——
        //    那条路对 CONNECTION_GROUP 有「分组只是容器」的闸门，而这两件事
        //    恰恰只对分组 / 连接节点有意义。
        actions.register("object.move-to-group", I18n.t("action.object.move-to-group"), I18n.t("category.object"), null,
                this::moveSelectedConnectionToGroup);
        actions.register("object.rename-group", I18n.t("action.object.rename-group"), I18n.t("category.object"), null,
                this::renameSelectedGroup);
        // ⭐ U1：切换数据库（catalog 节点专用；其它节点上这个动作是禁用的）。
        actions.register("object.switch-catalog", I18n.t("action.object.switch-catalog"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.SWITCH_CATALOG));
        // ⭐ X6：收藏夹菜单只有一项 → 顶级菜单资源被浪费。补「清空收藏」。
        actions.register("favorites.clear", I18n.t("action.favorites.clear"), I18n.t("category.favorites"), null, this::clearStarred);
        // ── T1–T4 表级生命周期 ──
        actions.register("object.new-table", I18n.t("action.object.new-table"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.NEW_TABLE));
        actions.register("object.truncate-table", I18n.t("action.object.truncate-table"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.TRUNCATE_TABLE));
        actions.register("object.rename-table", I18n.t("action.object.rename-table"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.RENAME_TABLE));
        actions.register("object.drop-table", I18n.t("action.object.drop-table"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.DROP_TABLE));
        // ── V1–V3 视图级生命周期（R42 N28-a）──
        //    与表级同一套路：菜单项来自注册表，所以命令面板里也搜得到，
        //    禁用时同样变灰 + 给解释，不是「藏在菜单里」。
        actions.register("object.new-view", I18n.t("action.object.new-view"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.NEW_VIEW));
        actions.register("object.design-view", I18n.t("action.object.design-view"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.DESIGN_VIEW));
        actions.register("object.drop-view", I18n.t("action.object.drop-view"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.DROP_VIEW));
        // ⭐ B3：重命名视图 —— 与重命名表同格，但只挂在视图节点上（表节点上灰掉）。
        actions.register("object.rename-view", I18n.t("action.object.rename-view"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.RENAME_VIEW));
        // ⭐ B4：查看依赖图 —— 表 / 视图节点都能开（表看谁依赖它，视图看它依赖谁）。
        actions.register("object.dependency-graph", I18n.t("action.object.dependency-graph"), I18n.t("category.object"), null,
                () -> onSelected(SideBarObject.SHOW_DEPENDENCY_GRAPH));
        // ⭐ UX-6：侧栏「已保存查询」分组里的条目（打开 = 新查询页 + 预填 SQL）。
        actions.register("saved-query.open", I18n.t("action.saved-query.open"), I18n.t("category.query"), null,
                () -> onSelected(SideBarObject.OPEN_SAVED_QUERY));
    }

    /** 对象树动作的种类（包级可见 —— 外壳要按它分派）。 */
    enum SideBarObject {
        OPEN_DATA,
        OPEN_STRUCTURE,
        NEW_QUERY,
        FAVORITE,
        COPY_NAME,
        COPY_SELECT,
        DIAGNOSTICS,
        REFRESH_OBJECTS,
        /** ⭐ H6-e：只重读这张表 / 视图的列（不重建整棵树）。 */
        REFRESH_TABLE,
        NEW_TABLE,
        TRUNCATE_TABLE,
        RENAME_TABLE,
        DROP_TABLE,
        /** ⭐ R42 N28-a：建视图 / 设计视图 / 删视图（执行同样在 {@link TableDdl}）。 */
        NEW_VIEW,
        DESIGN_VIEW,
        DROP_VIEW,
        /** ⭐ B3：重命名视图（执行同样在 {@link TableDdl}）。 */
        RENAME_VIEW,
        /** ⭐ B4：查看依赖图（表 / 视图都能开）。 */
        SHOW_DEPENDENCY_GRAPH,
        /** ⭐ U1：把当前连接切到这个库（重连）。 */
        SWITCH_CATALOG,
        /** ⭐ UX-6：打开一条已保存查询（新查询页 + 预填 SQL）。 */
        OPEN_SAVED_QUERY
    }

    /**
     * 转发给外壳的一次表级请求。
     *
     * <p>侧栏不知道怎么执行 DDL（那是 {@link TableDdl} 的事），
     * 只知道「用户在哪个连接上的哪个节点点了什么」。
     */
    record TableRequest(SideBarObject action, String tableName, String connectionId) {
    }

    /**
     * ⭐ UX-6：打开一条已保存查询。
     *
     * @param connectionId 用户点的是哪个连接下的查询（查询页跟着连接走）
     * @param name         保存时的名字（作为新标签标题）
     * @param sql          预填到查询页的 SQL
     */
    public record SavedQueryRequest(String connectionId, String name, String sql) {
    }

    /**
     * ⭐ U1：一个 catalog（数据库）节点。
     *
     * @param catalog     库名（节点上显示的文字）
     * @param connectionId **Serve 这个库的连接 id** —— 父连接自己的库用父 id，
     *                     其它库用虚拟连接 id（{@code 父id@库名}，见
     *                     {@link ConnectionProfile#catalogVariantId}）
     */
    public record CatalogEntry(String catalog, String connectionId) {
    }

    /**
     * ⭐ U1：一次「切换到这个数据库」的请求。
     *
     * @param connectionId 用户点的库当前由哪个连接 Serve（父 id 或虚拟 id）
     * @param catalog      目标库名
     */
    public record CatalogSwitch(String connectionId, String catalog) {
    }

    /**
     * ⭐ R37 N33：双击过程 / 函数 —— 打开只读定义体。
     *
     * <p>以前这条路挂在对象首页的行点击上；对象首页撤掉后，树成为唯一入口。
     * 树只有 Tables / Views / Functions / Queries 四组（N12），事件不在树上，
     * 所以这里只有过程 / 函数两种。
     *
     * @param connectionId 对象所属连接（可能是 catalog 虚拟连接）
     * @param name         例程名
     * @param procedure    {@code true} = 存储过程，{@code false} = 函数
     * @param event        {@code true} = 调度事件（MySQL EVENT），交给外壳开<b>只读</b>定义体页
     */
    public record RoutineRequest(String connectionId, String name, boolean procedure,
                                 boolean event) {
        /** 函数 / 过程的旧调用点不带事件位 —— 保持 false，不波及外壳接线。 */
        public RoutineRequest(String connectionId, String name, boolean procedure) {
            this(connectionId, name, procedure, false);
        }
    }

    /** 设置某个连接的 catalog 清单（U1；空清单 = 这个连接没有库一层）。 */
    public void setCatalogs(String connectionId, List<CatalogEntry> entries) {
        if (connectionId == null) {
            return;
        }
        catalogsByConnection.put(connectionId,
                entries == null ? List.of() : List.copyOf(entries));
        attemptedCatalogs.add(connectionId);
        failedCatalogs.remove(connectionId);
        refreshTree();
    }

    /** 需要某个连接的 catalog 清单时调用（AppShell 负责读）。 */
    public void setOnLoadCatalogs(Consumer<String> onLoadCatalogs) {
        this.onLoadCatalogs = onLoadCatalogs == null ? id -> { } : onLoadCatalogs;
    }

    /** ⭐ EXT（P1-4）：需要某个 Redis 连接的库清单时调用（AppShell 负责读）。 */
    public void setOnLoadRedisDbs(Consumer<String> onLoadRedisDbs) {
        this.onLoadRedisDbs = onLoadRedisDbs == null ? id -> { } : onLoadRedisDbs;
    }

    /**
     * ⭐ EXT（P1-4）：AppShell 读完某 Redis 连接的库清单后回填。
     *
     * <p>就地重建整棵树（与 SQL 对象回填同形）—— 展开态会由
     * {@link #connectionNode} 对当前连接 / 已加载库重新展开，不丢上下文。
     */
    public void setRedisDbs(String connectionId, List<Integer> dbs) {
        failedRedis.remove(connectionId);
        redisDbsByConnection.put(connectionId,
                dbs == null ? List.of() : List.copyOf(dbs));
        refreshTree();
    }

    /** ⭐ EXT（P1-4）：Redis 库清单读失败 → 标记，树里给「展开重试」。 */
    public void setRedisFailed(String connectionId) {
        failedRedis.add(connectionId);
        refreshTree();
    }

    /** 双击 / 右键「切换到此数据库」（AppShell 负责重连）。 */
    public void setOnSwitchCatalog(Consumer<CatalogSwitch> onSwitchCatalog) {
        this.onSwitchCatalog = onSwitchCatalog == null ? request -> { } : onSwitchCatalog;
    }

    /** 这个连接的 catalog 读过了吗（供外壳判断要不要再去读）。 */
    boolean catalogsAttempted(String connectionId) {
        return attemptedCatalogs.contains(connectionId);
    }

    public void setOnCollapsed(Consumer<Boolean> onCollapsed) {
        this.onCollapsed = onCollapsed;
    }

    public void setOnRefreshObjects(Consumer<String> onRefreshObjects) {
        this.onRefreshObjects = onRefreshObjects == null ? id -> { } : onRefreshObjects;
    }

    /** 表级生命周期（T1–T4）交给外壳执行。 */
    public void setOnTableDdl(Consumer<TableRequest> onTableDdl) {
        this.onTableDdl = onTableDdl == null ? request -> { } : onTableDdl;
    }

    /** ⭐ R37 N33：双击 / Enter 例程 → 打开定义体（外壳负责建只读页）。 */
    public void setOnOpenRoutine(Consumer<RoutineRequest> handler) {
        this.onOpenRoutine = handler == null ? request -> { } : handler;
    }

    /** ⭐ H6-d：树内「移动到分组」—— 名字问完交外壳落档案 + connectionStore。 */
    public void setOnMoveToGroup(BiConsumer<ConnectionProfile, String> handler) {
        this.onMoveToGroup = handler;
    }

    /** ⭐ H6-d：树内「重命名分组」—— 外壳逐成员搬家。 */
    public void setOnRenameGroup(BiConsumer<String, String> handler) {
        this.onRenameGroup = handler;
    }

    /** ⭐ H6-d：门禁注入分组弹窗的选答（header → 选答；返回 null = 取消）。 */
    void setGroupPromptForTest(Function<String, String> answer) {
        this.groupPromptForTest = answer;
    }

    /**
     * ⭐ UX-6：已保存查询的来源。
     *
     * <p>用 Supplier 而不是一次性 List —— 存 / 改名 / 删以后外壳只需
     * {@link #refreshSavedQueries()}，不会拿到一份过期快照。
     */
    public void setSavedQuerySource(Supplier<List<SavedQueries.Entry>> source) {
        this.savedQuerySource = source == null ? List::of : source;
        refreshTree();
    }

    /** ⭐ UX-6：已保存查询变了（存 / 改名 / 删）→ 重建树。 */
    public void refreshSavedQueries() {
        refreshTree();
    }

    /** ⭐ UX-6：打开一条已保存查询（外壳负责建查询页并预填 SQL）。 */
    public void setOnOpenSavedQuery(Consumer<SavedQueryRequest> handler) {
        this.onOpenSavedQuery = handler == null ? request -> { } : handler;
    }

    /** ⭐ N24：打开 Queries 对象清单页（外壳负责建标签）。 */
    public void setOnOpenSavedQueries(Runnable handler) {
        this.onOpenSavedQueries = handler == null ? () -> { } : handler;
    }

    /** 多连接切换：替换可选连接列表并保持当前选中项。 */
    public void setConnections(List<ConnectionProfile> connections) {
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        refreshTree();
        // ⭐ R54：档案清单可能**晚于**构造到达（外壳后注入）—— 那时折叠态那颗钮还顶着兜底插头。
        paintRailConnectionGlyph();
    }

    /**
     * ⭐ UX-5：注入连接存活判据（{@code sessions::isOpen} —— 只有 SessionManager 知道答案）。
     *
     * <p>侧栏自己**不判断**存活：它没有会话，任何自建判断都会变成第二套真相。
     */
    public void setConnectionLiveness(Predicate<String> connectionAlive) {
        this.connectionAlive = connectionAlive;
        refreshTree();
    }

    /**
     * ⭐ D-2：注入「按连接 id 取方言」。树里每个节点属于**它自己的连接**，
     * 「复制成 SELECT」必须用那个连接的方言 —— 引号（{@code ` `} / {@code " "} /
     * {@code [ ]}）和分页写法（{@code LIMIT} / {@code OFFSET…FETCH}）都跟库走。
     *
     * <p>侧栏自己**不拼** SQL：和存活判据一样，任何自建判断都会变成第二套真相。
     */
    public void setDialectResolver(Function<String, Dialect> dialectFor) {
        this.dialectFor = dialectFor == null ? connectionId -> null : dialectFor;
    }

    /**
     * ⭐ UX-5：连接开/关之后刷新状态点。
     *
     * <p>点是**渲染时**取的，所以状态变了必须有人重画一次树；
     * 否则「已断开」的连接会一直顶着绿点 —— 比没有点更糟。
     */
    public void refreshConnectionLiveness() {
        refreshTree();
    }

    public void setConnection(String id, String name) {
        this.connectionId = id;
        this.connectionName = name;
        refreshTree();
        loadObjectsIfNeeded(id);
        // ⭐ R54：换连接 = 可能换了一家，折叠态那颗钮的图形得跟着重画。
        paintRailConnectionGlyph();
    }

    /** 用新的内省结果替换对象树；对象在数据库里被增删后调用。 */
    public void setObjects(List<SchemaObject> objects) {
        setObjects(objects, true);
    }

    /**
     * ⭐ 设置某个连接的对象（多连接对象树）。
     *
     * @param connectionId 哪个连接
     */
    public void setObjects(String connectionId, List<SchemaObject> objects) {
        if (connectionId == null) {
            return;
        }
        objectsByConnection.put(connectionId,
                objects == null ? List.of() : List.copyOf(objects));
        attempted.add(connectionId);
        failedObjects.remove(connectionId);
        // ⭐ S-3：对象重新内省过了 —— 该连接缓存的列清单一起作废。
        //    表可能刚被 ALTER 过（加列 / 删列），留着旧列比没有列更糟。
        dropColumnsOf(connectionId);
        if (connectionId.equals(this.connectionId)) {
            setObjects(objects, true);
        } else {
            refreshTree();
        }
    }

    /**
     * ⭐ R51 T2：某个连接的对象**读失败**了（外壳调用）。
     *
     * <p>三件事一起做：摘掉「已尝试」→ 懒加载这条路还能再跑；记下失败 →
     * 树里出现一个说明性的占位节点；重建树 → 这件事不再只靠状态栏那一行会滚走的字。
     */
    public void setObjectsReadFailed(String connectionId) {
        if (connectionId == null) {
            return;
        }
        attempted.remove(connectionId);
        failedObjects.add(connectionId);
        refreshTree();
    }

    /**
     * ⭐ R51 T2：库清单读失败 —— 同上，而且**不许**退化成
     * 「这个方言没有库一层」的形态（那会让 MySQL 看起来像 SQLite）。
     */
    public void setCatalogsReadFailed(String connectionId) {
        if (connectionId == null) {
            return;
        }
        attemptedCatalogs.remove(connectionId);
        failedCatalogs.add(connectionId);
        refreshTree();
    }

    /** ⭐ S-3：作废某个连接下所有表的列缓存（对象重新内省后调用）。 */
    private void dropColumnsOf(String connectionId) {
        String prefix = (connectionId == null ? "" : connectionId) + '\u0000';
        columnsByTable.keySet().removeIf(key -> key.startsWith(prefix));
        attemptedColumns.removeIf(key -> key.startsWith(prefix));
    }

    /** 需要某个连接的对象时调用（AppShell 连上去读）。 */
    public void setOnLoadObjects(Consumer<String> onLoadObjects) {
        this.onLoadObjects = onLoadObjects == null ? id -> { } : onLoadObjects;
    }

    /**
     * ⭐ S-3：需要某个表的列时调用（AppShell 连上去读）。
     *
     * <p>回调只在**展开表节点时**触发一次（懒加载）—— 连上就查每张表的列，
     * 在几千张表的库上会把界面卡死；Navicat 也是展开才读。
     *
     * <p>{@code onLoadColumns} 为 null 时表节点保持叶子（见 {@link #objectNode}），
     * 所以接上回调后要重建一次树，箭头才长得出来。
     */
    public void setOnLoadColumns(BiConsumer<String, String> onLoadColumns) {
        this.onLoadColumns = onLoadColumns;
        if (!objectsByConnection.isEmpty()) {
            refreshTree();
        }
    }

    /**
     * ⭐ H6-e：「刷新此表」在重读之前要先作废底层 describe 缓存（AppShell 接
     * {@code SchemaService.invalidateTable}）—— 不然重读读回的还是旧列。
     * 没接（演示 / 裸侧栏）就只清树自己的缓存，行为照旧。
     */
    public void setOnInvalidateColumns(BiConsumer<String, String> onInvalidateColumns) {
        this.onInvalidateColumns = onInvalidateColumns;
    }

    /** 供门禁触发对象树动作。 */
    void actionForTest(String actionId) {
        actions.run(actionId);
    }

    /** schema 切换的回调（AppShell 改会话的 search_path 并重新内省）。 */
    public void setOnSelectSchema(Consumer<String> onSelectSchema) {
        this.onSelectSchema = onSelectSchema == null ? schema -> { } : onSelectSchema;
    }

    /**
     * 设置「选中对象」的上报回调（右侧详情面板 §6）。
     *
     * <p>只在**对象节点**（表 / 视图 / 触发器 / 函数 / 过程 / 事件）
     * 与**连接 / 库节点**上触发，分组容器不报。
     */
    public void setOnInspect(Consumer<InspectTarget> onInspect) {
        this.onInspect = onInspect == null ? target -> { } : onInspect;
    }

    /**
     * 树上被选中的目标 —— 详情面板的输入。
     *
     * @param topic 节点类别；{@link Topic#GROUP} / {@link Topic#ROOT} 不会出现
     *              （容器节点不上报）
     */
    public record InspectTarget(String connectionId, String name, String schema, Topic topic) {

        /** 与 {@code NodeKind} 对应的公开子集。 */
        public enum Topic {
            CONNECTION, CATALOG, TABLE, VIEW, TRIGGER, FUNCTION, PROCEDURE, EVENT
        }
    }

    /**
     * 设置可选的 schema 列表。
     *
     * <p>只有<b>多于一个</b> schema 时才显示切换器；单 schema 的库不添乱。
     */
    public void setSchemas(List<String> schemas) {
        List<String> safe = schemas == null ? List.of() : List.copyOf(schemas);
        schemaPicker.getItems().setAll(safe);
        boolean visible = safe.size() > 1;
        schemaPicker.setVisible(visible);
        schemaPicker.setManaged(visible);
        if (!safe.isEmpty()) {
            activeSchema = safe.getFirst();
            schemaPicker.getSelectionModel().select(activeSchema);
        }
    }

    /** 只看收藏（底部 ★ 按钮切换）。 */
    private boolean showOnlyFavorites;
    /**
     * ⭐ E7：侧栏搜索**没结果**时的说明。
     *
     * <p>以前搜不到就是「一棵树消失了」—— 用户不知道是搜索没命中，还是连接真的没东西。
     */
    private final Label searchEmpty = new Label();

    /** 当前选中的 schema。 */
    public String activeSchema() {
        return activeSchema;
    }

    private void setObjects(List<SchemaObject> objects, boolean refresh) {
        List<SchemaObject> safe = objects == null ? List.of() : List.copyOf(objects);
        tables = safe.stream()
                .filter(object -> object.kind() == ObjectKind.TABLE)
                .toList();
        views = safe.stream()
                .filter(object -> object.kind() == ObjectKind.VIEW)
                .toList();
        // ⭐ R51 T7：这里原本还算并缓存了 procedures / functions / triggers 和
        //    schemaCount —— 四个字段只写不读。函数/过程在树上走
        //    {@link #groupsOf} 的 profileRoutines（按连接算），触发器只在数据页的
        //    只读切面里出现，schema 分组层在 R37 N12 之后就没了。
        //    留着它们会让人以为「树里有触发器分组」，而树上永远点不到。
        if (refresh && !getChildren().isEmpty()) {
            refreshTree();
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean value) {
        if (collapsed == value && !getChildren().isEmpty()) {
            return;
        }
        collapsed = value;
        getChildren().setAll(value ? rail : expanded);
        // ⭐ 切换后的子节点也要占满 SideBar 全高（折叠态同理），否则底栏 / 末尾项
        //    会停在窗口中上部。
        VBox.setVgrow(value ? rail : expanded, Priority.ALWAYS);
        getStyleClass().remove("adb-sidebar--collapsed");
        if (value) {
            getStyleClass().add("adb-sidebar--collapsed");
        }
        onCollapsed.accept(value);
    }

    /**
     * ⭐ UX-2：对象树也要跟密度档位走。
     *
     * <p>第二十一轮默认档改成 normal：树和网格共用 {@link RowHeights}，
     * 不再自己写一份档位表 —— 以前树用 {@code row-tree}、网格用 {@code row-*}，
     * 同一档密度在两个页面算出两个行高（「密度设置时灵时不灵」的根因）。
     */
    public void setDensity(String density) {
        tree.setFixedCellSize(RowHeights.of(density));
    }

    public void focusSearch() {
        if (collapsed) {
            setCollapsed(false);
        }
        Platform.runLater(search::requestFocus);
    }

    // ─────────────────────────── 展开态 ───────────────────────────

    private void buildExpanded() {
        expanded.getStyleClass().add("adb-sidebar__expanded");

        // ⭐ Navicat 的侧栏只有**一棵树**：标题「我的连接」下面直接是连接，
        //    没有「收藏 / 连接 / 工作台 / 工具」这些分段标题，也没有连接下拉。
        //    连接切换在顶部应用栏与树里都能做；查询入口跟着连接走（右键 / ⌘K）。
        Label title = new Label(I18n.t("sidebar.myConnections"));
        title.setId("sidebar-title");
        title.getStyleClass().add("adb-sidebar__title");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button newQuery = IconButtons.icon(Icons.Icon.QUERY, I18n.t("action.object.new-query"));
        newQuery.setId("sidebar-new-query");
        newQuery.setAccessibleText(I18n.t("action.object.new-query"));
        newQuery.getStyleClass().add("adb-icon-button");
        newQuery.setTooltip(new Tooltip(I18n.t("sidebar.newQueryHint")));
        newQuery.setOnAction(event -> openQuery(queryTargetConnectionId(), null));

        HBox header = new HBox(6, title, headerSpacer, newQuery);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("adb-sidebar__header");

        // ⭐ schema 切换器（Navicat / DBeaver 都有）：
        //    只在真的有多个 schema 时才出现 —— SQLite 没有 schema 概念，
        //    单 schema 的库也没必要多一次点击。
        schemaPicker.setId("sidebar-schema-picker");
        schemaPicker.getStyleClass().add("adb-schema-picker");
        schemaPicker.setMaxWidth(Double.MAX_VALUE);
        schemaPicker.setMinWidth(90);
        schemaPicker.setVisible(false);
        schemaPicker.setManaged(false);
        schemaPicker.setOnAction(event -> {
            String selected = schemaPicker.getValue();
            if (selected != null && !selected.equals(activeSchema)) {
                onSelectSchema.accept(selected);
            }
        });

        search.setPromptText(I18n.t("sidebar.searchPrompt"));
        search.getStyleClass().add("adb-sidebar__search");
        search.setPrefHeight(io.agenticdb.ui.Tokens.H_INPUT_LIGHT);
        search.setMinWidth(120);
        search.textProperty().addListener((observable, oldValue, newValue) -> refreshTree());

        // ⭐ S4：搜索框在**底部**（Navicat 就是这个位置），
        //    和 ★ 收藏、« 折叠按钮同一行 —— 不是在顶上单独占一行。
        Button onlyFavorites = IconButtons.icon(Icons.Icon.STAR, I18n.t("sidebar.onlyFavorites"));
        onlyFavorites.setId("sidebar-favorites-filter");
        onlyFavorites.setAccessibleText(I18n.t("sidebar.onlyFavorites"));
        onlyFavorites.getStyleClass().add("adb-icon-button");
        onlyFavorites.setTooltip(new Tooltip(I18n.t("sidebar.onlyFavorites")));
        onlyFavorites.setOnAction(event -> {
            showOnlyFavorites = !showOnlyFavorites;
            if (showOnlyFavorites) {
                onlyFavorites.getStyleClass().add("adb-icon-button--active");
            } else {
                onlyFavorites.getStyleClass().remove("adb-icon-button--active");
            }
            refreshTree();
            status.accept(showOnlyFavorites ? I18n.t("sidebar.onlyFavorites") : I18n.t("sidebar.showAllObjects"));
        });

        Button collapse = IconButtons.icon(Icons.Icon.CHEVRON_LEFT, I18n.t("sidebar.collapse"));
        collapse.setAccessibleText(I18n.t("sidebar.collapse"));
        collapse.setId("sidebar-collapse");
        collapse.getStyleClass().add("adb-icon-button");
        collapse.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled(I18n.t("sidebar.collapse"), "Mod+Backslash")));
        collapse.setOnAction(event -> actions.run("sidebar.toggle"));

        // ⭐ R44 G2-a：清除一键（Navicat 的搜索框右侧也有这颗）。
        //    没有它，改一个长搜索词要退格退到底 —— 而退格过程中每按一次都重建整棵树。
        //    只在**有字**时出现：空框里摆一颗按了没反应的 ✕ 是噪声（也是诚实条款的反面）。
        Button clearSearch = IconButtons.icon(Icons.Icon.CLOSE, I18n.t("sidebar.clearSearch"));
        clearSearch.setId("sidebar-clear-search");
        clearSearch.setAccessibleText(I18n.t("sidebar.clearSearch"));
        clearSearch.getStyleClass().add("adb-icon-button");
        clearSearch.setTooltip(new Tooltip(I18n.t("sidebar.clearSearch")));
        clearSearch.setOnAction(event -> {
            search.clear();
            search.requestFocus();
        });
        clearSearch.managedProperty().bind(clearSearch.visibleProperty());
        clearSearch.visibleProperty().bind(search.textProperty().isNotEmpty());

        HBox bottomBar = new HBox(6, search, clearSearch, onlyFavorites, collapse);
        HBox.setHgrow(search, Priority.ALWAYS);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.getStyleClass().add("adb-sidebar__bottom");

        // ⭐ R37 N13：侧栏底部的三个导航项（查询历史 / 诊断 / 导入导出）撤掉 ——
        //    Navicat 的侧栏只有树和底栏，工具入口统一在菜单与功能区。
        //    查询历史此前只在这里有入口，所以同时补了「工具」菜单项
        //    （AppShell 的 query-history 动作）；诊断与导入导出菜单里本来就有。

        searchEmpty.setId("sidebar-search-empty");
        searchEmpty.getStyleClass().add("adb-muted");
        searchEmpty.setPadding(new Insets(4, 4, 4, 4));
        searchEmpty.setWrapText(true);
        searchEmpty.setVisible(false);

        tree.getStyleClass().add("adb-object-tree");
        // ⭐ P7：树本身没有文字，读屏只会读「树」—— 给一个明确的名称。
        tree.setAccessibleText(I18n.t("sidebar.treeAccessible"));
        // ⭐ 树根不显示：标题已经是「我的连接」，再显示一行同名根节点
        //    就是重复占位（Navicat 的树直接从连接开始）。
        tree.setShowRoot(false);
        // ⭐ 行高固定（Navicat 密度）。默认行高由字体 + 内边距撑到 26px 以上，
        //    同样是 480px 高的侧栏，能看见的对象少 4 行 —— 而侧栏存在的意义
        //    就是「一屏扫完这个库有什么」。
        //    R37 N11：对象树行距取 20px（Navicat 实测），来自 `row-tree` 令牌
        //    （唯一真相），不写死 —— 改密度只改令牌。
        tree.setFixedCellSize(Tokens.ROW_TREE_LIGHT);
        tree.setCellFactory(view -> new DemoCell());
        tree.setRoot(buildTreeModel(""));
        // ⭐ 记住当前选中的节点：对象树的动作（打开数据 / 复制 SELECT …）
        //    都是节点无关的命令，具体作用对象由它决定。
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            selectedNode = item == null ? null : item.getValue();
            updateObjectActionStates();
            reportInspect();
            // ⭐ 选中一个还没读过的连接 → 去读它的对象（懒加载）。
            //    不这么做的话，多连接树里非当前连接的节点永远是空的。
            if (item != null) {
                DemoNode node = item.getValue();
                if (node.kind() == NodeKind.CONNECTION && !node.connectionId().isBlank()
                        && !isRedisConnection(node.connectionId())) {
                    loadObjectsIfNeeded(node.connectionId());
                    // ⭐ U1：顺便读这个连接的库清单（没有库的连接什么都不发生）。
                    loadCatalogsIfNeeded(node.connectionId());
                } else if (node.kind() == NodeKind.CATALOG
                        && !node.connectionId().isBlank()) {
                    // ⭐ U1：选中一个库 → 读这个库的对象（Serve 它的可能是虚拟连接）。
                    loadObjectsIfNeeded(node.connectionId());
                }
            }
        });
        tree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    return;
                }
                DemoNode node = selected.getValue();
                if (node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW) {
                    openGrid(node.connectionId(), node.name());
                } else if (node.kind() == NodeKind.PROCEDURE
                        || node.kind() == NodeKind.FUNCTION) {
                    // ⭐ R37 N33：例程的定义体从这里打开（对象首页撤掉后的唯一入口）。
                    onOpenRoutine.accept(new RoutineRequest(node.connectionId(), node.name(),
                            node.kind() == NodeKind.PROCEDURE));
                } else if (node.kind() == NodeKind.EVENT) {
                    // ⭐ T12：调度事件也上树了 —— 双击开定义体，但带事件位，
                    //   外壳据此建「只读」页（不可执行 / 不可重建，见 RoutinePage）。
                    onOpenRoutine.accept(new RoutineRequest(node.connectionId(), node.name(),
                            false, true));
                } else if (node.kind() == NodeKind.SAVED_QUERY) {
                    // ⭐ UX-6：双击已保存查询 = 新开查询页并载入它。
                    openSavedQuery(node.connectionId(), node.name());
                } else if (node.kind() == NodeKind.SAVED_GROUP) {
                    // ⭐ N24：Queries 分组 = 对象清单页（Navicat 同形）。
                    onOpenSavedQueries.run();
                } else if (node.kind() == NodeKind.REDIS_KEY) {
                    // ⭐ EXT（P1-4）：双击 Redis 库节点 → 开键浏览器标签。
                    openRedisKeys(node.connectionId(), Integer.parseInt(node.schema()));
                } else if (node.kind() == NodeKind.CATALOG) {
                    // ⭐ U1：双击一个库 = 把当前连接切到它（MySQL 要重连）。
                    onSwitchCatalog.accept(new CatalogSwitch(node.connectionId(), node.name()));
                } else if (node.kind() == NodeKind.CONNECTION) {
                    selected.setExpanded(!selected.isExpanded());
                }
            }
        });
        tree.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    return;
                }
                DemoNode node = selected.getValue();
                if (node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW) {
                    openGrid(node.connectionId(), node.name());
                    event.consume();
                } else if (node.kind() == NodeKind.PROCEDURE
                        || node.kind() == NodeKind.FUNCTION) {
                    onOpenRoutine.accept(new RoutineRequest(node.connectionId(), node.name(),
                            node.kind() == NodeKind.PROCEDURE));
                    event.consume();
                } else if (node.kind() == NodeKind.EVENT) {
                    // ⭐ T12：与双击同一条路 —— 事件位交给外壳开只读定义体页。
                    onOpenRoutine.accept(new RoutineRequest(node.connectionId(), node.name(),
                            false, true));
                    event.consume();
                } else if (node.kind() == NodeKind.SAVED_QUERY) {
                    openSavedQuery(node.connectionId(), node.name());
                    event.consume();
                } else if (node.kind() == NodeKind.SAVED_GROUP) {
                    onOpenSavedQueries.run();
                    event.consume();
                } else if (node.kind() == NodeKind.REDIS_KEY) {
                    openRedisKeys(node.connectionId(), Integer.parseInt(node.schema()));
                    event.consume();
                } else if (node.kind() == NodeKind.CATALOG) {
                    onSwitchCatalog.accept(new CatalogSwitch(node.connectionId(), node.name()));
                    event.consume();
                }
            }
            // ⭐ H6-b/P-9（2026-11-08 拍板「F2 + Delete 都加」）：键盘对口右键菜单。
            //    不进 ActionRegistry 的 accelerator —— 那是场景全局的，F2 会漏到
            //    别的控件上；树内按键只在树有焦点时生效。动作本体仍走注册表，
            //    enabled / 危险确认 / 分派与右键菜单是同一条路。
            else if (event.getCode() == javafx.scene.input.KeyCode.F2) {
                TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue().kind() == NodeKind.TABLE) {
                    actions.run("object.rename-table");
                    event.consume();
                }
            } else if (event.getCode() == javafx.scene.input.KeyCode.DELETE
                    || event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
                TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    // macOS 的退格是 BACK_SPACE，前向删除才是 DELETE —— 两个都认。
                    NodeKind kind = selected.getValue().kind();
                    if (kind == NodeKind.TABLE) {
                        actions.run("object.drop-table");
                        event.consume();
                    } else if (kind == NodeKind.VIEW) {
                        actions.run("object.drop-view");
                        event.consume();
                    }
                }
            }
        });
        tree.setOnContextMenuRequested(event -> {
            // 右键先选中鼠标下的行，否则菜单会作用在上一次选中的对象上。
            javafx.scene.Node picked = event.getPickResult().getIntersectedNode();
            while (picked != null && !(picked instanceof TreeCell<?>)) {
                picked = picked.getParent();
            }
            if (picked instanceof TreeCell<?> cell && cell.getTreeItem() != null) {
                @SuppressWarnings("unchecked")
                TreeItem<DemoNode> item = (TreeItem<DemoNode>) cell.getTreeItem();
                tree.getSelectionModel().select(item);
            }

            TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            ContextMenu menu = menuFor(selected.getValue());
            menu.show(tree, event.getScreenX(), event.getScreenY());
        });

        expanded.getChildren().addAll(header, schemaPicker, tree, searchEmpty, bottomBar);
        VBox.setVgrow(tree, Priority.ALWAYS);
    }

    /**
     * 把当前选中的节点报给详情面板（§6）。
     *
     * <p>容器节点（根 / 「表」「视图」分组）不上报 —— 它们不是对象，
     * 报上去只会让右侧栏反复闪空态。
     */
    private void reportInspect() {
        DemoNode node = selectedNode;
        if (node == null) {
            return;
        }
        InspectTarget.Topic topic = switch (node.kind()) {
            case CONNECTION -> InspectTarget.Topic.CONNECTION;
            case CATALOG -> InspectTarget.Topic.CATALOG;
            case TABLE -> InspectTarget.Topic.TABLE;
            case VIEW -> InspectTarget.Topic.VIEW;
            case TRIGGER -> InspectTarget.Topic.TRIGGER;
            case FUNCTION -> InspectTarget.Topic.FUNCTION;
            case PROCEDURE -> InspectTarget.Topic.PROCEDURE;
            case EVENT -> InspectTarget.Topic.EVENT;
            case ROOT, GROUP, COLUMN, CONNECTION_GROUP, SAVED_GROUP, SAVED_QUERY, RETRY,
                    REDIS_DB, REDIS_KEY -> null;
        };
        if (topic == null) {
            return;
        }
        onInspect.accept(new InspectTarget(node.connectionId(), node.name(), node.schema(), topic));
    }

    private VBox section(String title, javafx.scene.Node... content) {
        Label label = new Label(title);
        label.getStyleClass().add("adb-sidebar__section-title");
        VBox box = new VBox(6, label);
        box.getStyleClass().add("adb-sidebar__section");
        box.getChildren().addAll(content);
        return box;
    }

    // ─────────────────────────── 折叠态 ───────────────────────────

    private void buildRail() {
        rail.getStyleClass().add("adb-rail");
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setPadding(new Insets(8, 0, 8, 0));

        Button expand = railButton(Icons.Icon.CHEVRON_RIGHT, io.agenticdb.ui.Shortcuts.labelled(I18n.t("sidebar.expand"), "Mod+Backslash"), () -> actions.run("sidebar.toggle"));
        expand.setId("sidebar-expand");
        Button connection = railButton(Icons.Icon.CONNECT, connectionName,
                () -> status.accept(I18n.t("sidebar.connectionPrefix", connectionName)));
        connection.setId("sidebar-rail-connection");
        this.railConnection = connection;
        // ⭐ R54：折叠侧栏时这是「我在哪个连接上」唯一的常驻入口，以前六家都顶同一颗插头。
        paintRailConnectionGlyph();
        Button favorites = railButton(Icons.Icon.STAR, I18n.t("sidebar.favorites"), () -> {
            setCollapsed(false);
            refreshTree();
        });
        String firstTable = tables.isEmpty() ? I18n.t("detail.kind.table") : tables.getFirst().name();
        Button users = railButton(Icons.Icon.TABLE, firstTable, () -> {
            if (!tables.isEmpty()) {
                openGrid(tables.getFirst().name());
            }
        });
        Button find = railButton(Icons.Icon.SEARCH, io.agenticdb.ui.Shortcuts.labelled(I18n.t("sidebar.search"), "Mod+F"), this::focusSearch);
        // ⚠️ 这里原来直接 openTool("诊断")，但 WorkspaceView 解析诊断页时会做
        //    title.substring("诊断 · ".length()) —— 标题只有「诊断」两个字会越界崩溃。
        //    统一走 object.diagnostics 动作，由它按「当前选中的对象」拼出
        //    「诊断 · <表名>」，没有选中对象时只给提示。
        Button tools = railButton(Icons.Icon.DIAGNOSTICS, I18n.t("action.object.diagnostics"), () -> actions.run("object.diagnostics"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // 4.5-11：这里以前是写死的「DEV」。现在显示当前连接的方言 —— 真信息。
        Label dialect = new Label(dialectName);
        dialect.getStyleClass().add("adb-rail__dialect");
        dialect.setAccessibleText(I18n.t("sidebar.dialectAccessible", dialectName));

        rail.getChildren().addAll(expand, connection, favorites, users, find, tools, spacer, dialect);
    }

    /** 设置当前连接的方言（状态栏与折叠侧栏共用同一个信息）。 */
    public void setDialect(String name) {
        this.dialectName = name == null || name.isBlank() ? "—" : name;
        if (rail.getChildren().size() > 0) {
            Label last = (Label) rail.getChildren().get(rail.getChildren().size() - 1);
            last.setText(dialectName);
            last.setAccessibleText(I18n.t("sidebar.dialectAccessible", dialectName));
        }
        // ⭐ R54：方言变了 → 折叠态那颗连接钮也得跟着换图形（切到临时会话时它得退回插头）。
        paintRailConnectionGlyph();
    }

    /**
     * 折叠侧栏的连接钮顶上放**当前连接那一家**的图形；认不出方言就退回通用插头。
     *
     * <p>图形从 {@link ConnectionCatalog#graphic} 取 —— 与树、连接管理器、状态栏同一行登记，
     * 所以同一个 MySQL 不会在两个地方长得不一样。
     */
    private void paintRailConnectionGlyph() {
        if (railConnection == null) {
            return;
        }
        Node vendor = ConnectionCatalog.graphic(railDialectKind(), Icons.SIZE_TOOLBAR);
        // ⚠️ 认不出方言时要**退回**插头，不是「保持原样」：从 MySQL 切到临时会话后还顶着
        //    MySQL 的图形，就是一条会说谎的常驻信息。
        railConnection.setGraphic(vendor != null ? vendor
                : Icons.colored(Icons.Icon.CONNECT, Icons.SIZE_TOOLBAR));
    }

    /**
     * 折叠态那颗钮代表「当前在哪个连接上」—— 当前活跃的可能是一条 catalog 虚拟连接
     * （{@code 父id@库名}），它没有档案，所以回父连接取方言。
     */
    private AssetKind railDialectKind() {
        AssetKind kind = dialectKindOf(connectionId);
        if (kind == null && ConnectionProfile.isCatalogVariantId(connectionId)) {
            kind = dialectKindOf(ConnectionProfile.parentOfCatalogVariant(connectionId));
        }
        return kind;
    }

    /** 折叠态的竖排按钮：只有图标。 */
    private Button railButton(Icons.Icon icon, String tooltip, Runnable handler) {
        Button button = IconButtons.icon(icon, tooltip);
        button.getStyleClass().add("adb-rail__button");
        button.setOnAction(event -> handler.run());
        return button;
    }

    // ─────────────────────────── 对象树 ───────────────────────────

    /**
     * 对象树模型。
     *
     * <p>⭐ <b>所有连接都在同一棵树里</b>（Navicat 的「我的连接」模型）：
     * 以前树根就是当前连接，切连接整棵树就换掉了 —— 用户没法同时浏览两个库。
     * 现在每个连接一个节点，展开即读该连接的对象（懒加载）。
     */
    private TreeItem<DemoNode> buildTreeModel(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        // 连接清单：优先用已保存的连接档案；没有档案时用当前连接兜底。
        // ⭐ 这里**不能**造一个带方言的 ConnectionProfile —— 那是方言分支，
        //    会被 DialectBranchAuditTest 拦下（而且本来就该避免）。
        List<ConnectionProfile> entries = new ArrayList<>(connections);
        TreeItem<DemoNode> root = new TreeItem<>(
                new DemoNode(I18n.t("sidebar.myConnections"), NodeKind.ROOT, Icons.Icon.DATABASE));
        root.setExpanded(true);

        if (entries.isEmpty()) {
            if (connectionId != null) {
                connectionNode(root, connectionId, connectionName, needle);
            }
            return root;
        }

        // ⭐ P5-1：分组是**显式**的（空串 = 未分组）。没有分组的连接直接挂在根下，
        //    所以只用「连接」的用户看不到多出来的一层；一旦有分组，同组的连接
        //    收进一个文件夹节点。分组顺序按名称，组内保持传入顺序（displayOrder）。
        Map<String, List<ConnectionProfile>> grouped = new LinkedHashMap<>();
        for (ConnectionProfile profile : entries) {
            if (profile.group().isBlank()) {
                connectionNode(root, profile.id(), profile.name(), needle);
            } else {
                grouped.computeIfAbsent(profile.group(), key -> new ArrayList<>()).add(profile);
            }
        }
        grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> {
                    TreeItem<DemoNode> groupNode = new TreeItem<>(new DemoNode(
                            // ⭐ H6-g：连接分组用「叠着的两只文件夹」，不是单只 folder-open ——
                            //    后者同时是功能区「其他」那一格，一个图形说两件事。
                            entry.getKey(), NodeKind.CONNECTION_GROUP, Icons.Icon.GROUP_CONNECTIONS));
                    groupNode.setExpanded(true);
                    for (ConnectionProfile profile : entry.getValue()) {
                        connectionNode(groupNode, profile.id(), profile.name(), needle);
                    }
                    root.getChildren().add(groupNode);
                });
        return root;
    }

    /**
     * 这个连接是哪家数据库。
     *
     * <p>两条来源按可信度排：已保存的连接档案（没连上也答得出）优先，
     * 其次当前会话的方言解析器（临时连接、还没建档时走这条）。
     */
    private AssetKind dialectKindOf(String id) {
        for (ConnectionProfile profile : connections) {
            if (profile.id().equals(id)) {
                return profile.kind();
            }
        }
        Dialect dialect = dialectFor.apply(id);
        return dialect == null ? null : AssetKind.from(dialect.kind());
    }

    /** 一个连接节点：有 catalog 的连接先出库一层，否则分组直接挂连接下。 */
    private void connectionNode(TreeItem<DemoNode> root, String id, String name, String needle) {
        boolean active = isActiveConnection(id);
        // ⭐ R43-a / R63-3：连接节点顶的是**厂商官方标志**，由单元格走统一出口
        //    {@link ConnectionCatalog#graphic} 取（与向导磁贴同一枚）。这里登记的
        //    {@code Icons.Icon.CONNECT} 只是「认不出方言时」的兜底形状。
        ConnectionCatalog.Entry vendor = ConnectionCatalog.forKind(dialectKindOf(id));
        // ⭐ A-2：连接节点内联「N 张表」计数在 DemoCell 显示层画，绝不拼进 name。
        //    name 是树内部的**定位键**（展开 / 搜索命中 / 右键 / 门禁 findExact 全按纯名字找）；
        //    把 " · N" 拼进去会让这些路径整体失效 —— 真机门禁实测 3 条 SideBarTreeTest 直接
        //    NPE / 展开断言失败。懒加载前 objectsByConnection 为空就不画，load 后 refreshTree 补上。
        TreeItem<DemoNode> connectionNode = new TreeItem<>(new DemoNode(
                name, NodeKind.CONNECTION, Icons.Icon.CONNECT,
                "", id, vendor == null ? "" : vendor.styleClass()));
        connectionNode.setExpanded(active);

        List<CatalogEntry> catalogs = catalogsByConnection.getOrDefault(id, List.of());
        if (!catalogs.isEmpty()) {
            // ⭐ U1：MySQL 这类「切库要重连」的方言 —— 每个库一个节点，
            //    表 / 视图分组挂在**库**下面（库才是对象的归属）。
            for (CatalogEntry catalog : catalogs) {
                // ⭐ R54：库节点形状仍是圆柱（它说的是「这是个库」），但**品牌色跟着父连接走** ——
                //    多连接同屏时 MySQL 的库和 PG 的库不该是同一个青。
                TreeItem<DemoNode> catalogNode = new TreeItem<>(new DemoNode(
                        catalog.catalog(), NodeKind.CATALOG, Icons.Icon.DATABASE,
                        "", catalog.connectionId(),
                        vendor == null ? "" : vendor.styleClass()));
                // 当前正在用的库默认展开，一眼能看到自己在哪个库里。
                catalogNode.setExpanded(catalog.connectionId().equals(connectionId));
                // ⚠️ 库节点**永远保留** —— 它的对象可能还没读（懒加载）。
                //    以前在下面统一 removeIf(isLeaf)，对象没读到的库会被
                //    当成「空分组」摘掉：连接下看起来什么都没有，用户也就
                //    永远点不到那个库去触发加载 —— 懒加载链就此断掉。
                catalogNode.getChildren().addAll(nonEmptyGroups(catalog.connectionId(), needle));
                // ⭐ R51 T2：这个库的对象读失败 → 挂一个「展开重试」的说明节点，
                //    而不是留一个看起来像空库的折叠节点。
                if (failedObjects.contains(catalog.connectionId())) {
                    catalogNode.getChildren().add(retryNode("sidebar.objectsFailed",
                            catalog.connectionId(),
                            () -> loadObjectsIfNeeded(catalog.connectionId())));
                }
                connectionNode.getChildren().add(catalogNode);
            }
        } else {
            connectionNode.getChildren().addAll(nonEmptyGroups(id, needle));
            if (failedObjects.contains(id)) {
                connectionNode.getChildren().add(retryNode("sidebar.objectsFailed", id,
                        () -> loadObjectsIfNeeded(id)));
            }
        }
        if (failedCatalogs.contains(id)) {
            // 放在最前面：库清单没读到是「这棵树少了一层」的原因，得先说清楚。
            connectionNode.getChildren().add(0, retryNode("sidebar.databasesFailed", id,
                    () -> loadCatalogsIfNeeded(id)));
        }

        // ⭐ UX-6：已保存查询直接挂在**连接**下（不是库下）——
        //    它是跟着连接走的全局清单，没必要在每个库下面重复一遍。
        TreeItem<DemoNode> saved = savedQueriesGroup(id, needle);
        if (saved != null) {
            connectionNode.getChildren().add(saved);
        }
        // ⚠️ 连接下的 Backups 分组已撤：当前它只列 Dump / Restore 两个工具入口，
        //    不是 Navicat 那种备份文件历史。等 C01 实现真实历史后再上树，
        //    避免用分组名冒充不存在的能力。备份工具仍可从功能区 / 菜单进入。

        // ⭐ EXT（P1-4）：Redis 连接 → 挂一个「键」分组，展开即拉有数据的库清单。
        if (isRedisConnection(id)) {
            connectionNode.getChildren().add(redisGroupNode(id, needle));
        }

        root.getChildren().add(connectionNode);
    }

    /**
     * ⭐ EXT（P1-4）：Redis 连接的「键」分组节点。
     *
     * <p>库清单没读过时放一个占位子节点当箭头由头，展开即触发 {@link #onLoadRedisDbs}；
     * 读到了就把每个「有数据的库」挂成 {@code REDIS_KEY} 叶子（双击开键浏览器）。
     * 读失败给「展开重试」（复用 SQL 库清单失败那套文案）。
     */
    private TreeItem<DemoNode> redisGroupNode(String id, String needle) {
        TreeItem<DemoNode> group = new TreeItem<>(new DemoNode(
                I18n.t("sidebar.group.redisKeys"), NodeKind.REDIS_DB, Icons.Icon.DATABASE, "", id));
        List<Integer> dbs = redisDbsByConnection.getOrDefault(id, List.of());
        if (dbs.isEmpty()) {
            if (failedRedis.contains(id)) {
                group.getChildren().add(retryNode("sidebar.databasesFailed", id,
                        () -> onLoadRedisDbs.accept(id)));
            } else {
                // 占位子节点 + 展开即加载（与对象 / 列懒加载同形）。
                group.getChildren().add(new TreeItem<>(new DemoNode(
                        I18n.t("sidebar.redisLoading"), NodeKind.RETRY, Icons.Icon.REFRESH, "", id)));
                group.expandedProperty().addListener((obs, was, is) -> {
                    if (is) {
                        onLoadRedisDbs.accept(id);
                    }
                });
            }
        } else {
            for (Integer db : dbs) {
                if (matches("db " + db, needle)) {
                    // ⭐ schema 字段借给「库下标」用（Redis 没有 schema；
                    //    双击时从 schema 还原 db，避免 DemoNode 再加一个字段）。
                    group.getChildren().add(new TreeItem<>(new DemoNode(
                            "db " + db, NodeKind.REDIS_KEY, Icons.Icon.LIST,
                            String.valueOf(db), id)));
                }
            }
            group.setExpanded(true);
        }
        return group;
    }

    /** ⭐ EXT（P1-4）：这个连接是不是 Redis（按档案 kind 判，不靠 URL / 文件名）。 */
    private boolean isRedisConnection(String id) {
        AssetKind kind = dialectKindOf(id);
        return kind != null && kind.isRedis();
    }

    /**
     * ⭐ UX-6：连接节点下的「已保存查询」分组。
     *
     * <p>只列完整查询 —— 代码片段是「插进当前编辑器」用的，
     * 放进这个「双击载入新查询」的列表里，用户点开的行为就说不清了。
     *
     * <p>搜索词同样过滤：搜表名时不该被一堆查询名占满。
     */
    /**
     * 「读失败了」的树内占位节点（⭐ R51 T2，展开即重试）。
     *
     * <p>挂一个占位子节点是**为了让箭头出现** —— JavaFX 只给有子项的节点画展开箭头，
     * 而展开这个动作就是重试的触发点。做法与列读取失败完全一致
     * （{@link #setColumns} 的空清单分支 + {@link #attemptedColumns}）。
     *
     * <p>为什么不用 GROUP：GROUP 是容器，搜索态会被 {@link #expandHitBranches}
     * 自动摊开 —— 那等于「搜一次表名，把每个失败的连接都重连一遍」。
     */
    private TreeItem<DemoNode> retryNode(String labelKey, String servingConnectionId,
                                         Runnable retry) {
        TreeItem<DemoNode> node = new TreeItem<>(new DemoNode(
                I18n.t(labelKey), NodeKind.RETRY, Icons.Icon.ALERT, "", servingConnectionId));
        node.getChildren().add(new TreeItem<>(new DemoNode(
                I18n.t("sidebar.retryLoading"), NodeKind.RETRY, Icons.Icon.REFRESH, "",
                servingConnectionId)));
        node.expandedProperty().addListener((obs, was, is) -> {
            if (is) {
                retry.run();
            }
        });
        return node;
    }

    private TreeItem<DemoNode> savedQueriesGroup(String servingConnectionId, String needle) {
        List<SavedQueries.Entry> visible = savedQueryEntries().stream()
                .filter(entry -> matches(entry.name(), needle))
                .toList();
        if (visible.isEmpty()) {
            return null;
        }
        TreeItem<DemoNode> group = new TreeItem<>(new DemoNode(
                // ⭐ H6-g：这一行是「一摞已保存的查询」，不是「保存」这个动作 ——
                //    以前挂的是软盘（与功能区 Backup 同一枚），子节点才是查询图标。
                I18n.t("sidebar.group.queries"), NodeKind.SAVED_GROUP,
                Icons.Icon.GROUP_QUERIES, "",
                servingConnectionId));
        group.setExpanded(true);
        for (SavedQueries.Entry entry : visible) {
            group.getChildren().add(new TreeItem<>(new DemoNode(
                    entry.name(), NodeKind.SAVED_QUERY, Icons.Icon.QUERY, "",
                    servingConnectionId)));
        }
        return group;
    }

    private List<SavedQueries.Entry> savedQueryEntries() {
        try {
            List<SavedQueries.Entry> entries = savedQuerySource.get();
            return entries == null ? List.of() : entries.stream()
                    .filter(entry -> entry != null && !entry.snippet())
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private SavedQueries.Entry savedQueryByName(String name) {
        if (name == null) {
            return null;
        }
        for (SavedQueries.Entry entry : savedQueryEntries()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    /** ⭐ UX-6：双击 / 回车 / 右键「打开查询」共用这一条路径。 */
    private void openSavedQuery(String targetConnection, String name) {
        SavedQueries.Entry entry = savedQueryByName(name);
        if (entry == null) {
            status.accept(I18n.t("sidebar.savedQueryGone", name));
            return;
        }
        onOpenSavedQuery.accept(new SavedQueryRequest(targetConnection, entry.name(), entry.sql()));
    }

    /**
     * ⭐ EXT（P1-4）：双击 / 回车 Redis 库节点 → 开键浏览器标签。
     *
     * <p>标签 id 编码成 {@code redis-keys:<connId>:<db>}，{@link WorkspaceView} 据此
     * 同一 (连接, 库) 只建一个页面实例并缓存；{@code target} 存库下标，页面据此 SCAN。
     */
    private void openRedisKeys(String connectionId, int db) {
        String id = "redis-keys:" + connectionId + ":" + db;
        String title = I18n.t("redis.keys.header", connectionNameFor(connectionId), db);
        openTab.accept(new TabItem(id, title, TabKind.REDIS_KEYS, String.valueOf(db), connectionId));
    }

    /** ⭐ EXT（P1-4）：按连接 id 取档案名（找不到时退化为 id 本身）。 */
    private String connectionNameFor(String id) {
        for (ConnectionProfile profile : connections) {
            if (profile.id().equals(id)) {
                return profile.name();
            }
        }
        return id;
    }

    /** 空分组不占位（没读到对象的连接只留一个可展开的节点）。 */
    private List<TreeItem<DemoNode>> nonEmptyGroups(String servingConnectionId, String needle) {
        List<TreeItem<DemoNode>> groups = new ArrayList<>(groupsOf(servingConnectionId, needle));
        groups.removeIf(TreeItem::isLeaf);
        return groups;
    }

    /**
     * 这个连接是不是「当前活跃」的。
     *
     * <p>⭐ U1：当前活跃的可能是一条 catalog 虚拟连接（{@code 父id@库名}）——
     * 它爹（父连接）也要跟着展开，否则用户看不到自己切到哪个库了。
     */
    private boolean isActiveConnection(String id) {
        if (id == null) {
            return false;
        }
        return id.equals(connectionId)
                || (connectionId != null
                && connectionId.startsWith(id + ConnectionProfile.CATALOG_VARIANT_SEPARATOR));
    }

    /** 某个连接（或 catalog 虚拟连接）下的对象分组。 */
    private List<TreeItem<DemoNode>> groupsOf(String servingConnectionId, String needle) {
        List<SchemaObject> objects = objectsByConnection.getOrDefault(servingConnectionId, List.of());
        if (showOnlyFavorites) {
            // ⭐ 底部 ★ 按钮：只看收藏的对象（R51 T1：按**这个连接**的收藏筛，
            //    否则 orders 里收藏过 users，audit 的 users 也会一起冒出来）
            objects = objects.stream()
                    .filter(object -> isStarred(servingConnectionId, object.name()))
                    .toList();
        }
        List<SchemaObject> profileTables = objects.stream()
                .filter(object -> object.kind() == ObjectKind.TABLE).toList();
        List<SchemaObject> profileViews = objects.stream()
                .filter(object -> object.kind() == ObjectKind.VIEW).toList();
        // ⭐ R37 N12：过程并入「函数」一组 —— Navicat 的树只有 Tables / Views /
        //    Functions / Queries 四组；过程单独一组会多出一个日常几乎不展开的层级。
        //    触发器仍按表归属（数据页的只读切面），事件在树上没有家 —— 两者都不再
        //    占一个空分组，避免用户点开一个永远为空的「触发器」。
        List<SchemaObject> profileRoutines = objects.stream()
                .filter(object -> object.kind() == ObjectKind.FUNCTION
                        || object.kind() == ObjectKind.PROCEDURE).toList();
        List<SchemaObject> profileEvents = objects.stream()
                .filter(object -> object.kind() == ObjectKind.EVENT).toList();

        return List.of(
                kindGroup(I18n.t("sidebar.group.tables"), NodeKind.TABLE, profileTables, needle, servingConnectionId),
                kindGroup(I18n.t("sidebar.group.views"), NodeKind.VIEW, profileViews, needle, servingConnectionId),
                kindGroup(I18n.t("sidebar.group.functions"), NodeKind.FUNCTION, profileRoutines, needle, servingConnectionId),
                kindGroup(I18n.t("sidebar.group.events"), NodeKind.EVENT, profileEvents, needle, servingConnectionId));
    }


    /** 一个分组节点（表 / 视图 / 函数）。 */
    private TreeItem<DemoNode> kindGroup(String label, NodeKind kind,
                                        List<SchemaObject> objects, String needle,
                                        String connectionId) {
        // ⭐ 先按搜索词过滤，再建节点。
        List<SchemaObject> visible = objects.stream()
                .filter(object -> matches(object.name(), needle))
                .toList();
        // ⭐ R37 N12：分组标题不再挂计数徽标 —— Navicat 的树没有数字，
        //    展开一眼能数完；数字还会随搜索词变化，读起来像一个会撒谎的统计。
        TreeItem<DemoNode> group = new TreeItem<>(
                new DemoNode(label, NodeKind.GROUP, groupIcon(kind), "", connectionId));
        group.setExpanded(true);
        for (SchemaObject object : visible) {
            group.getChildren().add(objectNode(object, kindOf(object.kind()), connectionId));
        }
        return group;
    }

    /**
     * 一个对象节点（表 / 视图 / 过程…）。
     *
     * <p>⭐ S-3：表 / 视图节点在**没读到列之前**也得能展开 —— JavaFX 的
     * 展开箭头只在「有子节点」时才画出来（{@link TreeItem#isLeaf()} 就是
     * 有没有 children），所以这里先挂一个占位子节点当箭头的由头，
     * 展开时才真正去读列（读到就换成真的列节点）。
     *
     * <p>没接读列回调的侧栏（{@code onLoadColumns == null}）保持旧行为：
     * 表节点是叶子 —— 不摆一个点开没反应的箭头骗人。
     */
    private TreeItem<DemoNode> objectNode(SchemaObject object, NodeKind kind,
                                          String connectionId) {
        TreeItem<DemoNode> item = new TreeItem<>(new DemoNode(
                object.name(), kind, iconOf(object.kind()), object.schema(), connectionId));
        if ((kind == NodeKind.TABLE || kind == NodeKind.VIEW) && onLoadColumns != null) {
            List<String> cached = columnsByTable.get(columnKey(connectionId, object.name()));
            if (cached != null) {
                cached.forEach(name -> item.getChildren().add(columnNode(name, connectionId)));
            } else {
                // ⭐ 占位子节点：既是箭头的由头，也是诚实的「正在读」状态 ——
                //    展开时加载会立刻开始，读到列就换掉它。
                item.getChildren().add(new TreeItem<>(
                        new DemoNode(I18n.t("sidebar.columnsLoading"), NodeKind.COLUMN, Icons.Icon.COLUMNS,
                                "", connectionId)));
                item.expandedProperty().addListener((obs, was, is) -> {
                    if (is) {
                        loadColumnsIfNeeded(connectionId, object.name());
                    }
                });
            }
        }
        return item;
    }

    /** 列节点（展开表 / 视图后出现）。 */
    private static TreeItem<DemoNode> columnNode(String name, String connectionId) {
        return new TreeItem<>(new DemoNode(
                name, NodeKind.COLUMN, Icons.Icon.COLUMNS, "", connectionId));
    }

    /** 连接 + 表 → 缓存键（表名在同一连接内唯一；schema 见 {@link #describeColumns}）。 */
    private static String columnKey(String connectionId, String table) {
        return (connectionId == null ? "" : connectionId) + '\u0000' + table;
    }

    /** 某个表的列还没读过就去读（只在展开节点时触发 —— 懒加载）。 */
    private void loadColumnsIfNeeded(String connectionId, String table) {
        String key = columnKey(connectionId, table);
        if (columnsByTable.containsKey(key) || !attemptedColumns.add(key)) {
            return;
        }
        onLoadColumns.accept(connectionId, table);
    }

    /**
     * ⭐ S-3：AppShell 读完列后回填。
     *
     * <p>就地换掉占位子节点，**不重建整棵树** —— 重建会把用户刚展开的
     * 节点合上，还会丢掉选中态（读一次列就把树重置一遍，体验很差）。
     *
     * <p>读到空清单：表不可能没有列，这说明读失败了 —— 保留一个说明性的
     * 占位节点并摘掉「已尝试」标记，再展开一次会重试，不静默降级成叶子。
     */
    public void setColumns(String connectionId, String table, List<String> columns) {
        List<String> safe = columns == null ? List.of() : List.copyOf(columns);
        String key = columnKey(connectionId, table);
        TreeItem<DemoNode> node = findTableNode(tree.getRoot(), connectionId, table);
        if (safe.isEmpty()) {
            attemptedColumns.remove(key);
            if (node != null) {
                node.getChildren().setAll(new TreeItem<>(new DemoNode(
                        I18n.t("sidebar.columnsFailed"), NodeKind.COLUMN, Icons.Icon.COLUMNS,
                        "", connectionId)));
            }
            return;
        }
        columnsByTable.put(key, safe);
        if (node != null) {
            List<TreeItem<DemoNode>> children = new ArrayList<>();
            safe.forEach(name -> children.add(columnNode(name, connectionId)));
            node.getChildren().setAll(children);
        }
    }

    /** 深度优先找到某个表 / 视图节点（当前树里没有就返回 null —— 缓存仍生效）。 */
    private static TreeItem<DemoNode> findTableNode(TreeItem<DemoNode> item, String connectionId,
                                                    String table) {
        if (item == null) {
            return null;
        }
        DemoNode value = item.getValue();
        if (value != null
                && (value.kind() == NodeKind.TABLE || value.kind() == NodeKind.VIEW)
                && String.valueOf(connectionId).equals(String.valueOf(value.connectionId()))
                && value.name().equals(table)) {
            return item;
        }
        for (TreeItem<DemoNode> child : item.getChildren()) {
            TreeItem<DemoNode> found = findTableNode(child, connectionId, table);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 分组节点的图标。
     *
     * <p>⭐ 图标是 UI 的事 —— 领域层（{@code ObjectKind}）不该知道图标长什么样，
     * 所以映射放在这里。
     *
     * <p>⭐ R62-a H6-g：**分组字形与叶子字形必须是两枚**。以前这三臂直接返回
     * {@code TABLE / VIEW_OBJECT / FUNCTION} —— 「Tables」这一行画的就是一个表，
     * 它下面每一行也画一个表，父子同形，层级只能靠缩进读。
     * 现在三臂各走一枚「集合」字形（{@code GROUP_*}），叶子那三枚一个像素都没动。
     *
     * <p>⚠️ 触发器 / 事件在树上没有分组（R37 N12 把它们收进表与函数，不再各占一个
     * 永远为空的组），所以这两臂今天走不到；真建组时它们落到 {@code FOLDER}（通用容器），
     * <b>不许</b>退回叶子那枚 —— 那等于把 H6-g 这条账重新欠一遍。
     */
    private static Icons.Icon groupIcon(NodeKind kind) {
        return switch (kind) {
            case TABLE -> Icons.Icon.GROUP_TABLES;
            case VIEW -> Icons.Icon.GROUP_VIEWS;
            case PROCEDURE, FUNCTION -> Icons.Icon.GROUP_FUNCTIONS;
            default -> Icons.Icon.FOLDER;
        };
    }

    /** 领域对象类型 → 树节点类型（节点的展开 / 图标 / 着色都按对象自身类型走）。 */
    private static NodeKind kindOf(ObjectKind kind) {
        return switch (kind) {
            case TABLE -> NodeKind.TABLE;
            case VIEW -> NodeKind.VIEW;
            case PROCEDURE -> NodeKind.PROCEDURE;
            case FUNCTION -> NodeKind.FUNCTION;
            case TRIGGER -> NodeKind.TRIGGER;
            case EVENT -> NodeKind.EVENT;
        };
    }

    /** 对象的图标（按领域类型映射）。 */
    private static Icons.Icon iconOf(ObjectKind kind) {
        return switch (kind) {
            case TABLE -> Icons.Icon.TABLE;
            case VIEW -> Icons.Icon.VIEW_OBJECT;
            case PROCEDURE, FUNCTION -> Icons.Icon.FUNCTION;
            case TRIGGER -> Icons.Icon.TRIGGER;
            case EVENT -> Icons.Icon.HISTORY;
        };
    }

    /**
     * 图标着色用的样式类。
     *
     * <p>⭐ 颜色只在**图标**上，不在文字上：整棵树都用彩色文字会变成调色盘，
     * 而用户扫视时靠的是「形状 + 颜色」这一对线索，文字保持正文色最易读。
     */
    private static String iconStyleClass(NodeKind kind) {
        return switch (kind) {
            case TABLE -> "adb-tree__icon--table";
            case VIEW -> "adb-tree__icon--view";
            case TRIGGER -> "adb-tree__icon--trigger";
            case PROCEDURE -> "adb-tree__icon--procedure";
            case FUNCTION -> "adb-tree__icon--function";
            case EVENT -> "adb-tree__icon--event";
            case CATALOG -> "adb-tree__icon--catalog";
            // ⭐ S-3：列是「细节叶子」，与表 / 视图的颜色区分开 ——
            //    用正文次色（已有令牌），不新增一种语义色（列不属于对象类型）。
            case COLUMN -> "adb-tree__icon--column";
            default -> "adb-tree__icon--connection";
        };
    }

    /** 该连接的对象还没读过就去读（懒加载）。 */
    private void loadObjectsIfNeeded(String id) {
        if (id == null || attempted.contains(id)) {
            return;
        }
        attempted.add(id);
        onLoadObjects.accept(id);
    }

    /** ⭐ U1：该连接的库清单还没读过就去读（懒加载；没有库的连接读不到就不显示）。 */
    private void loadCatalogsIfNeeded(String id) {
        if (id == null || attemptedCatalogs.contains(id)) {
            return;
        }
        attemptedCatalogs.add(id);
        onLoadCatalogs.accept(id);
    }

    private static boolean matches(String value, String needle) {
        return needle.isEmpty() || value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void refreshTree() {
        String filter = search.getText();
        tree.setRoot(buildTreeModel(filter));
        if (filter != null && !filter.isBlank()) {
            // ⭐ R51 T3：搜索时展开**装得下命中**的那几支，收起空的那几支。
            expandHitBranches(tree.getRoot());
        }
        updateSearchEmpty(filter);
        // ⚠️ 重建模型后视口要回到顶部 —— 否则上一次留下的滚动偏移会让
        //    第一行（连接组）被切掉一半，看起来像「上面少了一行」。
        tree.scrollTo(0);
    }

    /**
     * ⭐ R51 T3：搜索态下的展开规则 —— 「这一支里有东西，就把它摊开」。
     *
     * <p>旧做法只展开根和根的第一层子节点。多库树上线以后这就不成立了：
     * 命中在**另一个库**里时，它头顶是「分组 → 连接 → 库」三层折叠节点，
     * 用户搜到的是「树里好像有，但一行都看不见」，只能挨个库点开碰运气；
     * 反过来，没命中的库仍然摊着一堆空折叠节点。
     *
     * <p>树模型在建的时候**已经按 needle 过滤过**（{@link #kindGroup} 只放命中，
     * {@link #nonEmptyGroups} 摘掉空分组），所以「有没有子节点」就是
     * 「这一支有没有命中」，不需要再比一遍名字。
     *
     * <p>只碰容器节点：表 / 视图节点**不展开** —— 它们一展开就走懒加载列的
     * 回调（{@link #loadColumnsIfNeeded}），搜一次「user」把 40 张表的列全读一遍，
     * 是把「找不到」换成了「卡住」。
     */
    private static void expandHitBranches(TreeItem<DemoNode> node) {
        for (TreeItem<DemoNode> child : node.getChildren()) {
            if (isContainer(child.getValue().kind())) {
                child.setExpanded(!child.getChildren().isEmpty());
                expandHitBranches(child);
            }
        }
        node.setExpanded(!node.getChildren().isEmpty());
    }

    /** 容器节点：连接 / 分组 / 库 / 对象分组 —— 搜索态下按「有没有命中」决定展开。 */
    private static boolean isContainer(NodeKind kind) {
        return switch (kind) {
            case ROOT, CONNECTION, CONNECTION_GROUP, CATALOG, GROUP, SAVED_GROUP, REDIS_DB -> true;
            case TABLE, VIEW, PROCEDURE, FUNCTION, TRIGGER, EVENT, COLUMN, SAVED_QUERY,
                    RETRY, REDIS_KEY -> false;
        };
    }

    /**
     * ⭐ E7：搜索没命中时给一句说明。
     *
     * <p>「搜不到」和「库里没有」是两回事，界面必须分开说。
     */
    private void updateSearchEmpty(String filter) {
        boolean searching = filter != null && !filter.isBlank();
        boolean nothing = searching && countObjects(tree.getRoot()) == 0;
        searchEmpty.setVisible(nothing);
        if (nothing) {
            searchEmpty.setText(I18n.t("sidebar.searchEmpty", filter.trim()));
        }
    }

    /** 树里真正的对象节点数（分组 / 连接节点不算）。 */
    private static int countObjects(TreeItem<DemoNode> item) {
        if (item == null) {
            return 0;
        }
        int count = isObjectNode(item.getValue()) ? 1 : 0;
        for (TreeItem<DemoNode> child : item.getChildren()) {
            count += countObjects(child);
        }
        return count;
    }

    private static boolean isObjectNode(DemoNode node) {
        if (node == null) {
            return false;
        }
        return switch (node.kind()) {
            case TABLE, VIEW, PROCEDURE, FUNCTION, TRIGGER -> true;
            // ⭐ S-3：列不是对象 —— 不进「有多少个对象」的计数，
            //    否则一张 50 列的表会让搜索空态判断失真。
            case COLUMN, ROOT, CONNECTION, CONNECTION_GROUP, GROUP, CATALOG, EVENT,
                    SAVED_GROUP, SAVED_QUERY, RETRY, REDIS_DB, REDIS_KEY -> false;
        };
    }

    /**
     * ⭐ X6：清空收藏。
     *
     * <p>以前收藏只能一个一个取消（「收藏 / 取消收藏」是切换动作）——
     * 收藏多了以后没法一键清空。
     */
    public void clearStarred() {
        if (starred.isEmpty() && legacyStarred.isEmpty()) {
            status.accept(I18n.t("sidebar.noFavorites"));
            return;
        }
        int count = starred.size() + legacyStarred.size();
        starred.clear();
        legacyStarred.clear();
        settings.set(STARRED_KEY, "");
        refreshTree();
        status.accept(I18n.t("sidebar.favoritesCleared", count));
    }

    /** 收藏的对象（供「收藏夹」菜单用），按标签排序。 */
    public List<Favorite> favorites() {
        List<Favorite> out = new ArrayList<>();
        for (String key : starred) {
            int field = key.indexOf('\u0000');
            String connection = key.substring(0, field);
            String table = key.substring(field + 1);
            String scope = scopeOf(connection);
            out.add(new Favorite(connection, table, scope.isEmpty()
                    ? table
                    : I18n.t("fav.entry.scoped", table, scope)));
        }
        for (String table : legacyStarred) {
            // 旧版只存了名字 —— 不猜它属于哪个库，标签里也不编一个库名。
            out.add(new Favorite("", table, table));
        }
        return List.copyOf(out);
    }

    /** 打开一个收藏的对象（走正常的开表路径，开到**它自己的**连接上）。 */
    public void openFavorite(Favorite favorite) {
        if (favorite == null) {
            return;
        }
        // 旧版裸名不知道该开去哪条连接 —— 保持老行为，开在当前连接上。
        openGrid(favorite.connectionId().isBlank() ? connectionId : favorite.connectionId(),
                favorite.table());
    }

    /**
     * 这个收藏属于哪儿（给收藏夹的标签用）。
     *
     * <p>能认出**库**就报库（{@code users（audit）} 比 {@code users（生产库）}
     * 更能说明这是哪张表）；认不出库（SQLite、Oracle 这些没有库一层的方言）
     * 才退回连接名。两个都认不出来时返回空串 —— 标签里不编一个归属。
     */
    private String scopeOf(String connection) {
        if (connection == null || connection.isEmpty()) {
            return "";
        }
        for (List<CatalogEntry> catalogs : catalogsByConnection.values()) {
            for (CatalogEntry catalog : catalogs) {
                if (catalog.connectionId().equals(connection)) {
                    return catalog.catalog();
                }
            }
        }
        // 库清单还没读回来（比如从收藏夹进的，没展开过那个连接）——
        // 虚拟连接的 id 本身就带库名，可以直接解出来；父连接解不出就退回连接名。
        if (ConnectionProfile.isCatalogVariantId(connection)) {
            return ConnectionProfile.catalogOfCatalogVariant(connection);
        }
        for (ConnectionProfile profile : connections) {
            if (profile.id().equals(connection)) {
                return profile.name();
            }
        }
        return "";
    }

    /** 读回上次收藏（以前退出就没了）。 */
    private void loadStarred() {
        if (settings == null) {
            return;
        }
        String raw = settings.get(STARRED_KEY, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            for (String line : raw.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int field = line.indexOf(STAR_FIELD);
                if (field < 0) {
                    // ⭐ R51 T1：旧格式的一行 = 只有名字。留着它按老语义用，
                    //    直到用户在这一张表上动一次收藏（见 toggleStar）。
                    legacyStarred.add(line.trim());
                } else {
                    // 名字**不**trim：限定键的两段是原样存的，
                    // 而首尾有空白的表名 trim 一次就打不开那张表了。
                    starred.add(starKey(line.substring(0, field), line.substring(field + 1)));
                }
            }
        } catch (RuntimeException ignored) {
            // 存坏了就当没有收藏。
        }
    }

    /** 收藏落盘（一行一条：{@code 连接id<TAB>对象名}；旧版裸名原样留一行）。 */
    private void saveStarred() {
        if (settings == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String key : starred) {
            int field = key.indexOf('\u0000');
            lines.add(key.substring(0, field) + STAR_FIELD + key.substring(field + 1));
        }
        // 没被动过的旧条目继续留着 —— 写盘顺手清掉它们等于把用户的收藏删了。
        lines.addAll(legacyStarred);
        settings.set(STARRED_KEY, String.join("\n", lines));
    }

    /** 连接 + 对象名 → 收藏键（NUL 分隔：表名里不会出现，落盘时换成 TAB）。 */
    private static String starKey(String connectionId, String name) {
        return (connectionId == null ? "" : connectionId) + '\u0000' + name;
    }

    /** 这一张表在**这个连接**里收藏了没有。 */
    private boolean isStarred(String connectionId, String name) {
        return starred.contains(starKey(connectionId, name)) || legacyStarred.contains(name);
    }

    private void toggleStar(String connectionId, String name) {
        String key = starKey(connectionId, name);
        if (starred.remove(key)) {
            // 精确取消这一张，别的一个都不动。
        } else if (legacyStarred.remove(name)) {
            // ⭐ R51 T1：旧版一颗覆盖所有库的星，被用户动了一次就**展开成精确的**：
            //    除当前这张外，其它**已经读到对象**的同名表各自保留一颗。
            //    不展开的话，「在 audit 取消收藏」会把 orders 里那颗也带走。
            //    ⚠️ 已知边界：还没展开过的库里那张同名表的星会丢 —— 裸名根本
            //    认不出归属，猜一个库比丢一颗更糟（宁可让用户重新收藏一次）。
            for (Map.Entry<String, List<SchemaObject>> loaded : objectsByConnection.entrySet()) {
                if (loaded.getKey().equals(connectionId)) {
                    continue;
                }
                boolean sameName = loaded.getValue().stream()
                        .anyMatch(object -> object.name().equals(name)
                                && (object.kind() == ObjectKind.TABLE
                                || object.kind() == ObjectKind.VIEW));
                if (sameName) {
                    starred.add(starKey(loaded.getKey(), name));
                }
            }
        } else {
            starred.add(key);
        }
        saveStarred();
        refreshTree();
        boolean starredNow = starred.contains(key);
        status.accept(starredNow
                ? I18n.t("sidebar.starred", name)
                : I18n.t("sidebar.unstarred", name));
    }

    /**
     * 截图用（R34 收官）：往收藏里摆一颗，收藏夹管理器才有内容可拍。
     *
     * <p>验收脚本每次都换新 prefs 节点（{@code ADB_PREFS_NODE} + RESET），
     * starred 于是恒为空 —— 拍出来是一张空表，证不了分组与「打开」这一列。
     */
    void poseStarForShots(String name) {
        if (!isStarred(connectionId, name)) {
            toggleStar(connectionId, name);
        }
    }

    /**
     * 截图用（R34 收官）：按路由直接开一个工具页签。
     *
     * <p>工具页原本只在「树里选中 → 动作」这条路上（{@code onSelected} 读
     * selectedNode），而树节点没有公开的选中入口，摆态只能从路由这一层进去；
     * 后面开的仍是同一个 {@code openTool}，页签内容一模一样。
     */
    void poseToolForShots(String route, String title) {
        openTool(route, title);
    }

    /** 供门禁检查右键菜单是否来自注册表（4.5-8）。 */
    ContextMenu contextMenuForTest() {
        return selectedNode == null ? null : menuFor(selectedNode);
    }

    /** 当前选中的树节点（门禁用）。 */
    String selectedNodeName() {
        return selectedNode == null ? null : selectedNode.name();
    }

    private ContextMenu menuFor(DemoNode node) {
        ContextMenu menu = new ContextMenu();
        switch (node.kind()) {
            // ⭐ R42 N28-a：表与视图的菜单分家。
            //    以前两者共用一整串，于是视图节点上挂着「清空表 / 重命名表」——
            //    点了只会得到一句「只有表能重命名」。能看见却不能做，比看不见更糟。
            //    视图这一串只留视图真能做的：看数据、看结构、设计视图、删视图。
            case TABLE -> menu.getItems().addAll(
                    actions.menuItem("object.open-data"),
                    actions.menuItem("object.open-structure"),
                    actions.menuItem("object.new-query"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.favorite",
                            isStarred(node.connectionId(), node.name())
                                    ? I18n.t("sidebar.favoriteRemove")
                                    : I18n.t("sidebar.favoriteAdd")),
                    actions.menuItem("object.copy-name"),
                    actions.menuItem("object.copy-select"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.diagnostics"),
                    actions.menuItem("object.refresh-table"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.truncate-table"),
                    actions.menuItem("object.rename-table"),
                    actions.menuItem("object.dependency-graph"),
                    actions.menuItem("object.drop-table"));
            case VIEW -> menu.getItems().addAll(
                    actions.menuItem("object.open-data"),
                    actions.menuItem("object.open-structure"),
                    actions.menuItem("object.new-query"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.favorite",
                            isStarred(node.connectionId(), node.name())
                                    ? I18n.t("sidebar.favoriteRemove")
                                    : I18n.t("sidebar.favoriteAdd")),
                    actions.menuItem("object.copy-name"),
                    actions.menuItem("object.copy-select"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.refresh-table"),
                    actions.menuItem("object.design-view"),
                    actions.menuItem("object.rename-view"),
                    actions.menuItem("object.dependency-graph"),
                    actions.menuItem("object.drop-view"));
            case CONNECTION -> menu.getItems().addAll(
                    actions.menuItem("new-connection", I18n.t("sidebar.editConnection")),
                    actions.menuItem("object.refresh-objects"),
                    // ⭐ H6-d：分组在树上就能管 —— 不必去连接管理页。
                    actions.menuItem("object.move-to-group"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-table"),
                    // ⭐ N28-a：建视图与建表同格 —— 容器节点（连接 / 库 / 分组）都能建。
                    actions.menuItem("object.new-view"),
                    actions.menuItem("object.new-query"));
            // ⭐ U1：库节点 —— 切过去 / 在里面建表 / 查数据。
            case CATALOG -> menu.getItems().addAll(
                    actions.menuItem("object.switch-catalog"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-query"),
                    actions.menuItem("object.new-table"),
                    actions.menuItem("object.new-view"),
                    actions.menuItem("object.refresh-objects"));
            // ⭐ P5-1：分组节点只装连接 —— 菜单只留「新建连接」。
            case CONNECTION_GROUP -> menu.getItems().addAll(
                    actions.menuItem("new-connection"),
                    // ⭐ H6-d：分组改名也在树上做（逐成员搬家，外壳执行）。
                    actions.menuItem("object.rename-group"));
            case GROUP -> menu.getItems().addAll(
                    actions.menuItem("object.refresh-objects"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-table"),
                    actions.menuItem("object.new-view"));
            // ⭐ S-3：列只有一件事可做 —— 复制列名。它没有数据页 / 结构页，
            //    也不能收藏（收藏的是表），所以不复用对象那一整串。
            case COLUMN -> menu.getItems().add(
                    actions.menuItem("object.copy-name"));
            // ⭐ S3：过程 / 函数 / 触发器没有数据页，也没有结构页 ——
            //    只给「复制名称 / 新建查询」（调用它）/ 收藏。
            case PROCEDURE, FUNCTION, TRIGGER -> menu.getItems().addAll(
                    actions.menuItem("object.new-query"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.favorite",
                            isStarred(node.connectionId(), node.name())
                                    ? I18n.t("sidebar.favoriteRemove")
                                    : I18n.t("sidebar.favoriteAdd")),
                    actions.menuItem("object.copy-name"));
            // ⭐ UX-6：已保存查询 —— 只有「打开」一件事。
            case SAVED_QUERY -> menu.getItems().add(
                    actions.menuItem("saved-query.open", I18n.t("sidebar.menuOpenSavedQuery")));
            case SAVED_GROUP -> menu.getItems().add(
                    actions.menuItem("object.refresh-objects", I18n.t("action.refresh")));
            default -> menu.getItems().add(
                    actions.menuItem("object.open-data", I18n.t("sidebar.menuOpen")));
        }
        return menu;
    }

    /** 执行一个对象树动作。动作本身不知道作用对象，由当前选中节点决定。 */
    private void onSelected(SideBarObject action) {
        DemoNode node = selectedNode;
        if (node == null) {
            status.accept(I18n.t("sidebar.needObject"));
            return;
        }
        // ⭐ P5-1：分组只是归类容器 —— 它不属于任何一个连接，不能拿它执行对象动作。
        if (node.kind() == NodeKind.CONNECTION_GROUP) {
            status.accept(I18n.t("sidebar.groupNodeOnly"));
            return;
        }
        boolean isObject = node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW;
        // ⭐ S-3：列节点只放行「复制名称」（见 updateObjectActionStates）。
        boolean isColumn = node.kind() == NodeKind.COLUMN;
        // ⭐ 多连接对象树：动作作用在**节点所属的连接**上，不是当前激活的连接。
        String owner = node.connectionId() == null || node.connectionId().isBlank()
                ? connectionId : node.connectionId();
        switch (action) {
            case OPEN_DATA -> {
                if (isObject) {
                    openGrid(owner, node.name());
                } else {
                    status.accept(I18n.t("sidebar.onlyTableOpensData"));
                }
            }
            case OPEN_STRUCTURE -> {
                if (isObject) {
                    openStructure(owner, node.name());
                } else {
                    status.accept(I18n.t("sidebar.onlyTableHasStructure"));
                }
            }
            case NEW_QUERY -> openQuery(owner, isObject ? node.name() : null);
            case FAVORITE -> {
                if (isObject) {
                    toggleStar(node.connectionId(), node.name());
                } else {
                    status.accept(I18n.t("sidebar.onlyTableFavoritable"));
                }
            }
            case COPY_NAME -> {
                if (isObject || isColumn) {
                    copy(node.name());
                }
            }
            case COPY_SELECT -> {
                if (isObject) {
                    Dialect dialect = dialectFor.apply(owner);
                    if (dialect == null) {
                        status.accept(I18n.t("shell.needConnection"));
                    } else {
                        copy(dialect.plainPageSql(node.name(), 200, 0) + ";");
                    }
                }
            }
            case DIAGNOSTICS -> {
                if (isObject) {
                    openTool(TOOL_DIAGNOSTICS + node.name(),
                            I18n.t("sidebar.tool.diagnostics", node.name()));
                }
            }
            case REFRESH_OBJECTS -> {
                // ⭐ 刷节点所属的连接（可能是虚拟连接），不是当前活跃连接。
                onRefreshObjects.accept(owner);
                status.accept(I18n.t("sidebar.refreshed", node.name()));
            }
            case REFRESH_TABLE -> {
                if (isObject) {
                    refreshTable(owner, node.name());
                } else {
                    status.accept(I18n.t("sidebar.onlyObject.refreshTable"));
                }
            }
            // ⭐ 表级生命周期：侧栏只负责把「点的是谁」说清楚，执行在 TableDdl。
            case NEW_TABLE ->
                    onTableDdl.accept(new TableRequest(SideBarObject.NEW_TABLE, null, owner));
            case TRUNCATE_TABLE, RENAME_TABLE, DROP_TABLE -> {
                if (isObject) {
                    onTableDdl.accept(new TableRequest(action, node.name(), owner));
                } else {
                    status.accept(I18n.t(onlyTableProblemKey(action)));
                }
            }
            // ⭐ R42 N28-a：视图级生命周期。建视图落在<b>容器</b>上（连接 / 库 / 分组都行，
            //    和建表一样），设计与删除只落在视图节点上 —— 表节点上这两项是灰的。
            case NEW_VIEW ->
                    onTableDdl.accept(new TableRequest(SideBarObject.NEW_VIEW, null, owner));
            case DESIGN_VIEW, DROP_VIEW, RENAME_VIEW -> {
                if (node.kind() == NodeKind.VIEW) {
                    onTableDdl.accept(new TableRequest(action, node.name(), owner));
                } else {
                    status.accept(I18n.t(onlyViewProblemKey(action)));
                }
            }
            // ⭐ B4：依赖图 —— 表（看谁依赖它）和视图（看它依赖谁）都能开，不限类型。
            case SHOW_DEPENDENCY_GRAPH ->
                    onTableDdl.accept(new TableRequest(action, node.name(), owner));
            // ⭐ U1：切库（重连由外壳做）。
            case SWITCH_CATALOG -> {
                if (node.kind() == NodeKind.CATALOG) {
                    onSwitchCatalog.accept(new CatalogSwitch(owner, node.name()));
                } else {
                    status.accept(I18n.t("sidebar.onlyCatalogSwitchable"));
                }
            }
            // ⭐ UX-6：已保存查询 → 新查询页 + 预填 SQL。
            case OPEN_SAVED_QUERY -> {
                if (node.kind() == NodeKind.SAVED_QUERY) {
                    openSavedQuery(owner, node.name());
                } else {
                    status.accept(I18n.t("sidebar.needSavedQuery"));
                }
            }
        }
    }

    /**
     * 「只有表能做这件事」的按动作整句键。
     *
     * <p>不拼「只有表能 + 动词」：英文里 "Only tables can clear" 说不通（清什么？），
     * 而中文原文正好能这么省。整句一个键，两侧各自成句。
     */
    private static String onlyTableProblemKey(SideBarObject action) {
        return switch (action) {
            case TRUNCATE_TABLE -> "sidebar.onlyTable.truncate";
            case RENAME_TABLE -> "sidebar.onlyTable.rename";
            case DROP_TABLE -> "sidebar.onlyTable.drop";
            default -> "sidebar.onlyTable.generic";
        };
    }

    /** ⭐ N28-a：视图专属动作落在表 / 库节点上的解释（同上一条的整句键规则）。 */
    private static String onlyViewProblemKey(SideBarObject action) {
        return switch (action) {
            case DESIGN_VIEW -> "sidebar.onlyView.design";
            case DROP_VIEW -> "sidebar.onlyView.drop";
            case RENAME_VIEW -> "sidebar.onlyView.rename";
            default -> "sidebar.onlyView.generic";
        };
    }

    /** 按当前选中节点刷新对象动作的 enabled 状态。 */
    private void updateObjectActionStates() {
        DemoNode node = selectedNode;
        boolean isObject = node != null
                && (node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW);
        boolean isCallable = node != null
                && (node.kind() == NodeKind.PROCEDURE || node.kind() == NodeKind.FUNCTION
                || node.kind() == NodeKind.TRIGGER);
        boolean isConnection = node != null && node.kind() == NodeKind.CONNECTION;
        // ⭐ P5-1：分组容器不属于任何连接 —— 除了「新建连接」什么都不能做。
        boolean isConnectionGroup = node != null && node.kind() == NodeKind.CONNECTION_GROUP;
        // ⭐ U1：库节点 —— 能切库、能建表、能新建查询，但没有数据页 / 结构页。
        boolean isCatalog = node != null && node.kind() == NodeKind.CATALOG;
        boolean isTable = node != null && node.kind() == NodeKind.TABLE;
        // ⭐ S-3：列节点只能复制名称 —— 打开数据 / 结构 / 收藏都是表级动作。
        boolean isColumn = node != null && node.kind() == NodeKind.COLUMN;
        // ⭐ UX-6：已保存查询既不是数据库对象，也不是容器 —— 只放行「打开查询」。
        boolean isSavedQuery = node != null && node.kind() == NodeKind.SAVED_QUERY;
        boolean isSavedNode = node != null
                && (node.kind() == NodeKind.SAVED_QUERY || node.kind() == NodeKind.SAVED_GROUP);
        setObjectActionEnabled("object.open-data", isObject);
        setObjectActionEnabled("object.open-structure", isObject);
        // ⚠️ 这条以前写了两遍（先 isObject||isConnection 再 isObject||isCallable），
        //     后一条覆盖前一条 → 连接节点上的「新建查询」其实是灰的，
        //     而连接节点的右键菜单里又有这一项。合成一条，四种节点都放行。
        setObjectActionEnabled("object.new-query", isObject || isCallable || isConnection || isCatalog);
        setObjectActionEnabled("object.favorite", isObject);
        setObjectActionEnabled("object.copy-name", isObject || isColumn);
        setObjectActionEnabled("object.copy-select", isObject);
        setObjectActionEnabled("object.diagnostics", isObject);
        // 过程 / 函数 / 触发器没有数据页 —— 相应动作要变灰并解释为什么。
        setObjectActionEnabled("object.open-data", isObject);
        setObjectActionEnabled("object.open-structure", isObject);
        setObjectActionEnabled("object.copy-select", isObject);
        setObjectActionEnabled("object.refresh-objects",
                node != null && !isConnectionGroup && !isSavedNode);
        setObjectActionEnabled("object.refresh-table", isObject);
        // ⭐ H6-d：分组管理 —— 移动只挂在连接节点上，改名只挂在分组节点上。
        setObjectActionEnabled("object.move-to-group", isConnection);
        setObjectActionEnabled("object.rename-group", isConnectionGroup);
        // ⭐ 表级生命周期：建表挂在连接 / 分组 / 库上，改表只挂在「表」上 ——
        //    视图没有 TRUNCATE，过程函数更没有，所以不能一律放行。
        setObjectActionEnabled("object.new-table",
                node != null && !isConnectionGroup && !isSavedNode);
        setObjectActionEnabled("object.truncate-table", isTable);
        setObjectActionEnabled("object.rename-table", isTable);
        setObjectActionEnabled("object.drop-table", isTable);
        // ⭐ N28-a：视图级三项 —— 建视图跟建表一样挂在容器上，
        //    「设计 / 删视图」只挂在视图节点上（表节点上灰掉，理由见右键菜单分家那段）。
        boolean isView = node != null && node.kind() == NodeKind.VIEW;
        setObjectActionEnabled("object.new-view",
                node != null && !isConnectionGroup && !isSavedNode);
        setObjectActionEnabled("object.design-view", isView);
        setObjectActionEnabled("object.rename-view", isView);
        setObjectActionEnabled("object.drop-view", isView);
        // ⭐ U1：切库只挂在库节点上。
        setObjectActionEnabled("object.switch-catalog", isCatalog);
        // ⭐ UX-6：已保存查询只有「打开」可用。
        setObjectActionEnabled("saved-query.open", isSavedQuery);
    }

    private void setObjectActionEnabled(String id, boolean enabled) {
        actions.find(id).ifPresent(action -> action.setEnabled(enabled));
    }

    /**
     * ⭐ H6-e：「刷新此表」—— 只炸这一个表的列缓存再重读，不重建整棵树。
     *
     * <p>缓存与「已尝试」标记都清掉（后者保证读失败后这一项仍可重试），
     * 底层 describe 缓存由 {@link #onInvalidateColumns} 作废（接了才有），
     * 节点上先摆回「读取列…」占位，读回后 {@link #setColumns} 就地替换 ——
     * 其余节点的展开态、选中态一概不动。视图同待遇（列同样会过期）。
     */
    private void refreshTable(String connectionId, String table) {
        String key = columnKey(connectionId, table);
        columnsByTable.remove(key);
        attemptedColumns.remove(key);
        if (onInvalidateColumns != null) {
            onInvalidateColumns.accept(connectionId, table);
        }
        TreeItem<DemoNode> node = findTableNode(tree.getRoot(), connectionId, table);
        if (node != null) {
            node.getChildren().setAll(new TreeItem<>(new DemoNode(
                    I18n.t("sidebar.columnsLoading"), NodeKind.COLUMN, Icons.Icon.COLUMNS,
                    "", connectionId)));
        }
        onLoadColumns.accept(connectionId, table);
        status.accept(I18n.t("sidebar.refreshed", table));
    }

    /** ⭐ H6-d：树内「移动到分组」—— 空串 = 移出分组（弹窗提示写明），取消 = 不动。 */
    private void moveSelectedConnectionToGroup() {
        DemoNode node = selectedNode;
        if (node == null || node.kind() != NodeKind.CONNECTION || onMoveToGroup == null) {
            return;
        }
        ConnectionProfile profile = profileOf(node.connectionId());
        if (profile == null) {
            return;
        }
        String group = askGroupName(I18n.t("conn.group.title"),
                I18n.t("conn.group.header", profile.name()), profile.group());
        if (group != null) {
            onMoveToGroup.accept(profile, group);
        }
    }

    /**
     * ⭐ H6-d：树内「重命名分组」。
     *
     * <p>分组没有独立实体（名字存在每个成员的档案上），改名 = 外壳逐成员搬家；
     * 空名不是合法分组（空分组的节点根本不渲染），同名直接不动。
     */
    private void renameSelectedGroup() {
        DemoNode node = selectedNode;
        if (node == null || node.kind() != NodeKind.CONNECTION_GROUP || onRenameGroup == null) {
            return;
        }
        String from = node.name();
        String to = askGroupName(I18n.t("conn.group.renameTitle"),
                I18n.t("conn.group.renameHeader", from), from);
        if (to != null && !to.isBlank() && !to.equals(from)) {
            onRenameGroup.accept(from, to);
        }
    }

    /**
     * ⭐ H6-d：分组名询问弹窗（与连接管理页同一个形状）。返回 null = 取消。
     * 门禁经 {@link #setGroupPromptForTest} 注入选答 —— Monocle 按不住模态弹窗。
     */
    private String askGroupName(String title, String header, String current) {
        if (groupPromptForTest != null) {
            return groupPromptForTest.apply(header);
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        ComboBox<String> input = new ComboBox<>();
        input.setId("connection-group-name");
        input.setEditable(true);
        input.getItems().setAll(existingGroupNames());
        input.setValue(current);
        input.setPrefWidth(240);
        Label hint = new Label(I18n.t("conn.group.hint"));
        hint.getStyleClass().add("adb-muted");
        VBox content = new VBox(8, new Label(I18n.t("conn.col.group")), input, hint);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK
                ? (input.getEditor().getText() == null ? "" : input.getEditor().getText().trim())
                : null);
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
        return dialog.showAndWait().orElse(null);
    }

    /** 已有分组名（去重 + 大小写不敏感排序）—— 给下拉做候选，口径同连接管理页。 */
    private List<String> existingGroupNames() {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        connections.stream()
                .map(ConnectionProfile::group)
                .filter(value -> !value.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(groups::add);
        return List.copyOf(groups);
    }

    private ConnectionProfile profileOf(String id) {
        for (ConnectionProfile profile : connections) {
            if (profile.id().equals(id)) {
                return profile;
            }
        }
        return null;
    }

    /** 打开表。{@code targetConnection} 为空表示当前连接。 */
    private void openGrid(String name) {
        openGrid(connectionId, name);
    }

    private void openGrid(String targetConnection, String name) {
        String id = targetConnection == null ? connectionId : targetConnection;
        openTab.accept(new TabItem("grid:" + id + ":" + name, name, TabKind.GRID,
                name, id));
        status.accept(I18n.t("sidebar.openTable", name));
    }

    private void openStructure(String name) {
        openStructure(connectionId, name);
    }

    private void openStructure(String targetConnection, String name) {
        String id = targetConnection == null ? connectionId : targetConnection;
        openTab.accept(new TabItem("structure:" + id + ":" + name, name,
                TabKind.STRUCTURE, name, id));
        status.accept(I18n.t("shell.openStructure", name));
    }

    private void openQuery(String name) {
        openQuery(connectionId, name);
    }

    private void openQuery(String targetConnection, String name) {
        String id = targetConnection == null ? connectionId : targetConnection;
        String title = name == null ? I18n.t("sidebar.queryUntitled") : I18n.t("sidebar.queryTitle", name);
        openTab.accept(new TabItem("query:" + id + ":" + System.nanoTime(), title,
                TabKind.QUERY, null, id));
        status.accept(I18n.t("action.object.new-query"));
    }

    /**
     * 侧栏「新建查询」按钮的目标连接。
     *
     * <p>⭐ 侧栏现在是一棵「所有连接」的树（Navicat 模型）：新建查询应当落在
     * <b>当前选中的连接节点</b>上；没选中具体连接时落在当前激活的连接。
     * 查询跟着连接走，而不是一个全局「工作台」。
     */
    private String queryTargetConnectionId() {
        DemoNode node = selectedNode;
        if (node != null && node.connectionId() != null && !node.connectionId().isBlank()) {
            return node.connectionId();
        }
        return connectionId;
    }

    /**
     * 打开一个工具页签。
     *
     * <p>⚠️ {@code route} 是**路由键**，{@code title} 只是给人看的标题。
     * 以前这里只有 title，{@code WorkspaceView} 靠 {@code title.startsWith("诊断")}
     * 反解析要开哪一页、表名也从标题里切出来 —— 于是文案一换语言，
     * 诊断页与查询历史页就双双退回「该工具只保留入口」的空壳（{@link TabItem} 的
     * {@code target} 参数注释早就写明「不能从标题反解析」，这一处是漏网的）。
     * id 里带上表名，保持"每张表一个诊断页签"的原行为。
     */
    private void openTool(String route, String title) {
        openTab.accept(new TabItem("tool:" + route, title, TabKind.TOOL, route));
        status.accept(I18n.t("shell.openTool", title));
    }

    /**
     * 工具页签的路由键，写在 {@link TabItem#target()} 里。
     *
     * <p>公开是因为 {@code WorkspaceView} 要按它开页 —— 两边各写一遍字面量，
     * 拼错的那一边不会编译失败，只会静默退回「该工具只保留入口」的空壳。
     */
    public static final String TOOL_HISTORY = "history";

    /** 诊断页的路由前缀，后面跟表名（每张表一个页签，与抽取前的 id 行为一致）。 */
    public static final String TOOL_DIAGNOSTICS = "diagnostics:";

    /**
     * ⭐ R36：Ribbon 上「能力面还没建」的路由键（N28 → R42）。
     *
     * <p>走 {@code WorkspaceView} 的 TOOL 分支 —— 这些路由没有真页面，
     * 由 {@link WorkspaceView} 给**如实说明页**（「本版本未提供」），不是死磁贴。
     * 键公开的理由同 {@link #TOOL_HISTORY}：两边各写一遍字面量，拼错的那边只会静默退回空壳。
     */
    public static final String TOOL_USERS = "users";
    public static final String TOOL_BACKUP = "backup";
    /** ⭐ P3 轻量 Data Profiling：单表列画像（样本）。 */
    public static final String TOOL_PROFILING = "profiling";
    /** ⭐ P3 跨库传输最小切片：单表 DB→DB。 */
    public static final String TOOL_TRANSFER = "transfer";

    /**
     * ⭐ 2026-11-10：例程清单页的路由键 —— 功能区「函数」格的**真实落点**。
     *
     * <p>此前它落的是「object-functions」那条说明页路由（{@code TOOL_OBJECT_FUNCTIONS}，
     * 已随之删除），而例程定义体（{@code RoutinePage}）早已交付、入口只在左侧树双击 ——
     * 两条路自相矛盾。现在这一格开真实的例程清单页（列函数 / 过程 / 事件，
     * 双击打开定义体）。
     */
    public static final String TOOL_ROUTINES = "routines";

    /** 对象类说明页路由（这一类对象本版本还没有编辑器）——
     *  ⭐ 2026-11-10：Function 已接真实例程清单页，集合清空（角标随之摘掉）。
     *  ⭐ 2026-11-11：AI 新建表入口按「不做假能力」撤掉，说明页集合保持为空。
     *  机制留着：将来哪类对象又落回说明页，把路由常量加回这里即可。 */
    public static final java.util.Set<String> TOOL_OBJECT_ROUTES = java.util.Set.of();

    /** 落「本版本未提供」说明页的路由集合。
     *  ⭐ P2：User / Backup、P3：Profile / Transfer 四格已接真实工具页，移出此集合
     *  （仍是诚实条款，只是从「没这功能」升级为「有这功能」）。
     *  ⭐ 2026-11-09：BI 按用户裁定整体移除（连说明页入口一起），TOOL_BI 不再存在。
     *  ⭐ 2026-11-10：Automation / Model 因「明确不做」删除入口，移出此集合。
     *  ⭐ 2026-11-11：AI 新建表入口撤掉，集合清空。
     *  ⚠️ 这份集合同时是 Ribbon 角标的判据来源 ——
     *  {@code RibbonCapabilityAuditTest#thePendingBadgesMatchTheHonestLandingPoints}
     *  读它反推该挂哪几格，改动请与 {@code AppBar.PENDING_TILES} 同批。 */
    public static final java.util.Set<String> TOOL_UNAVAILABLE_ROUTES = java.util.Set.of();

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private ConnectionProfile findActiveProfile() {
        return connections.stream()
                .filter(profile -> profile.id().equals(connectionId))
                .findFirst()
                .orElse(null);
    }

    private enum NodeKind {
        ROOT, CONNECTION,
        /** ⭐ P5-1：连接分组（文件夹）—— 只装连接，不是数据库对象。 */
        CONNECTION_GROUP,
        GROUP, TABLE, VIEW,
        /** ⭐ S3：过程 / 函数 / 触发器。 */
        PROCEDURE, FUNCTION, TRIGGER,
        /** ⭐ O1：调度事件（只有 MySQL 有）。 */
        EVENT,
        /** ⭐ U1：数据库（catalog）—— 只有「切库要重连」的方言有这一层。 */
        CATALOG,
        /** ⭐ S-3：列（展开表 / 视图节点后出现的叶子）。 */
        COLUMN,
        /** ⭐ EXT（P1-4）：Redis 连接下的「键」分组（展开即拉库清单）。 */
        REDIS_DB,
        /** ⭐ EXT（P1-4）：某个 Redis 库节点（双击打开键浏览器标签）。 */
        REDIS_KEY,
        /** ⭐ UX-6：已保存查询分组 / 条目（连接节点下）。 */
        SAVED_GROUP, SAVED_QUERY,
        /**
         * ⭐ R51 T2：读取失败的占位节点（展开即重试）。
         *
         * <p>单独一个 kind，而不是借用 GROUP：GROUP 是容器，搜索态会被
         * {@link #expandHitBranches} 自动摊开 —— 那等于「搜一次表名，
         * 把所有失败的连接都重连一遍」。
         */
        RETRY
    }

    /**
     * 树节点。
     *
     * @param schema       所属 schema（表/视图节点用；其它节点为空串）
     * @param connectionId 所属连接（连接节点与其下的对象用；其它节点为空串）
     * @param vendorClass  厂商品牌色的作用域类（连接节点与<b>库</b>节点用同一枚 —— R54：
     *                     库的形状是圆柱，但颜色跟着它父连接那一家走；其它节点为空串）
     */
    private record DemoNode(String name, NodeKind kind, Icons.Icon icon, String schema,
                             String connectionId, String vendorClass) {

        private DemoNode(String name, NodeKind kind, Icons.Icon icon) {
            this(name, kind, icon, "", "", "");
        }

        private DemoNode(String name, NodeKind kind, Icons.Icon icon, String connectionId) {
            this(name, kind, icon, "", connectionId, "");
        }

        private DemoNode(String name, NodeKind kind, Icons.Icon icon, String schema,
                         String connectionId) {
            this(name, kind, icon, schema, connectionId, "");
        }
    }

    private final class DemoCell extends TreeCell<DemoNode> {
        @Override
        protected void updateItem(DemoNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(
                    "adb-tree-cell--group", "adb-tree-cell--connection", "adb-tree-cell--object",
                    "adb-tree-cell--catalog", "adb-tree-cell--current");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            // ⭐ 矢量图标（不是 Unicode 符号）。
            //    R35 N3：有彩色资产就分层上色，没有就回退单色（回退在 colored() 内部，
            //    调用方不分叉）。
            //    R35 N4-b 定档：两档**都**挂类型色类 —— 差别只在吃法的层级：
            //    单色时描边自己吃 -adb-kind-*；彩色时容器上的类经
            //    `.adb-tree__icon--table .adb-icon-edge` 把轮廓层染成类型色，
            //    三层 tone 保持中性浅蓝（形状 + 颜色是一对线索，彩色档没资格删掉一条）。
            //    ⭐ R63-3：连接节点走**统一出口** {@link ConnectionCatalog#graphic} ——
            //    顶的是那一家自己的官方标志，与磁贴、状态栏、页签、库选择器同一枚。
            //    认不出方言时 graphic() 交回 null，这里才落回插头（插头说的是
            //    「这是一条连接，但不知道是哪家」，画成某家的标志就是编造）。
            Node icon = item.kind() == NodeKind.CONNECTION
                    ? ConnectionCatalog.graphic(dialectKindOf(item.connectionId()),
                            Icons.SIZE_LABEL)
                    : null;
            if (icon == null) {
                icon = Icons.colored(item.icon(), Icons.SIZE_LABEL);
                icon.getStyleClass().addAll("adb-tree__icon", iconStyleClass(item.kind()));
                // ⭐ R43-a：库节点挂**厂商作用域类**，品牌色经
                //    `.adb-db--mysql .adb-icon-tone-c` 传到填充层 —— 形状是自己的（圆柱），
                //    颜色跟着那一家走，MySQL 的库和 PG 的库不会撞成同一个青。
                if (!item.vendorClass().isBlank()) {
                    icon.getStyleClass().add(item.vendorClass());
                }
            } else {
                icon.getStyleClass().add("adb-tree__icon");
            }

            Label name = new Label(item.name());
            name.getStyleClass().add("adb-tree__name");

            // ⭐ UX-5 / R62-a H5-l：连接存活点（与应用栏那颗点同一套令牌：success / text-muted）。
            //    位置改到**图标的右下角**，不再是「图标与名字之间」——
            //    夹在中间时它是一枚谁都不属于的独立点，还让连接名比同深度的别的行
            //    多缩 8+6=14px（点的 8px 盒子 + HBox 那 6px 间距），扫视时读成不同层级。
            //    挂到图标上，状态就贴在它所描述的那个图形上；StackPane 的 pref
            //    取最大子节点 = 图标本身，所以**缩进一格都没动**（H5-k 刚为这条付过学费）。
            //    ⚠️ 点只能挪不能删：它是树上唯一的在线/离线通道（见 GAP-TODOLIST 约束②）。
            Node lead = icon;
            if (item.kind() == NodeKind.CONNECTION && connectionAlive != null) {
                boolean alive = connectionAlive.test(item.connectionId());
                SVGPath dot = Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT);
                dot.getStyleClass().addAll("adb-tree__dot", alive
                        ? "adb-tree__dot--online" : "adb-tree__dot--offline");
                dot.setAccessibleText(alive ? I18n.t("sidebar.connected") : I18n.t("sidebar.disconnected"));
                StackPane badge = new StackPane(icon, dot);
                StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
                // 往右下角压 2px：DOT 的实心圆只占 8px 盒子中间那 2.4px，
                // 不加这条负外边距，圆心就落在盒子正中 —— 贴在图形身上而不是它的角上，
                // 读成「图形缺了一块」，而不是「这个图形挂了一个状态」。
                StackPane.setMargin(dot, new Insets(0, -2, -2, 0));
                lead = badge;
            }

            HBox box = new HBox(6, lead, name);
            box.setAlignment(Pos.CENTER_LEFT);

            // ⭐ A-2：连接名后内联表数（对齐 Navicat 树的信息密度）。这是**展示层**的
            //    独立 Label：DemoNode.name 保持纯连接名，树内部与门禁都能按名字定位节点。
            if (item.kind() == NodeKind.CONNECTION) {
                long tableCount = objectsByConnection
                        .getOrDefault(item.connectionId(), List.of()).stream()
                        .filter(object -> object.kind() == ObjectKind.TABLE).count();
                if (tableCount > 0) {
                    Label count = new Label("\u00B7 " + tableCount);
                    count.getStyleClass().add("adb-tree__count");
                    box.getChildren().add(count);
                }
            }

            // ⭐ U1：当前正在用的库加粗标出（Navicat 也是这样标当前数据库的）。
            if (item.kind() == NodeKind.CATALOG
                    && item.connectionId().equals(connectionId)) {
                getStyleClass().add("adb-tree-cell--current");
                name.getStyleClass().add("adb-tree__name--current");
            }

            if ((item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW)
                    && isStarred(item.connectionId(), item.name())) {
                SVGPath star = Icons.of(Icons.Icon.STAR, Icons.SIZE_BADGE);
                star.setAccessibleText(I18n.t("sidebar.favorites"));
                star.getStyleClass().add("adb-tree__star");
                box.getChildren().add(star);
            }

            switch (item.kind()) {
                case ROOT -> getStyleClass().add("adb-tree-cell--connection");
                case GROUP, CONNECTION_GROUP, SAVED_GROUP ->
                        getStyleClass().add("adb-tree-cell--group");
                case CONNECTION -> getStyleClass().add("adb-tree-cell--connection");
                case CATALOG -> getStyleClass().add("adb-tree-cell--catalog");
                default -> getStyleClass().add("adb-tree-cell--object");
            }

            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    if (item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW) {
                        // ⭐ 按节点所属连接打开（多连接对象树）
                        openGrid(item.connectionId(), item.name());
                        event.consume();
                    } else if (item.kind() == NodeKind.CATALOG) {
                        // ⭐ U1：双击一个库 = 切到它（外壳负责重连）。
                        onSwitchCatalog.accept(
                                new CatalogSwitch(item.connectionId(), item.name()));
                        event.consume();
                    } else if (item.kind() == NodeKind.SAVED_QUERY) {
                        // ⭐ UX-6：双击已保存查询 = 新查询页 + 预填 SQL。
                        openSavedQuery(item.connectionId(), item.name());
                        event.consume();
                    }
                }
            });

            setText(null);
            setGraphic(box);
        }
    }
}
