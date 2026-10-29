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
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

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
    private List<SchemaObject> tables = List.of();
    private List<SchemaObject> views = List.of();
    /** ⭐ S3：过程 / 函数 / 触发器。 */
    private List<SchemaObject> procedures = List.of();
    private List<SchemaObject> functions = List.of();
    private List<SchemaObject> triggers = List.of();
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
     * <p>只有「切 catalog 要重连」的方言有（MySQL）：连接节点下先出库一层，
     * 库节点下才是表 / 视图分组。没有 catalog 的连接（SQLite / PG）
     * 这个 map 里没有条目 → 分组直接挂在连接节点下（老形态）。
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
    /** 已经尝试读过 catalog 的连接（读失败也不反复试）。 */
    private final Set<String> attemptedCatalogs = new LinkedHashSet<>();
    /** 双击 / 右键「切换到此数据库」（AppShell 负责重连）。 */
    private Consumer<CatalogSwitch> onSwitchCatalog = request -> { };
    /** 已经尝试过读取的对象（读失败也不反复试）。 */
    private final Set<String> attempted = new LinkedHashSet<>();
    /**
     * ⭐ S-3：展开表节点时才读它的列（懒加载）。
     *
     * <p>为 null = 没接回调 —— 表节点保持叶子，不摆一个点不开的箭头骗人。
     */
    private BiConsumer<String, String> onLoadColumns;
    /** ⭐ S-3：连接 + 表 → 列名（展开读到后缓存，刷新树不丢）。 */
    private final Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
    /** ⭐ S-3：已发起过列读取的表（读失败时摘掉标记 → 再展开一次会重试）。 */
    private final Set<String> attemptedColumns = new LinkedHashSet<>();
    /** 出现的 schema 个数；> 1 时对象树按 schema 分组。 */
    private int schemaCount;
    /**
     * 「刷新对象」的回调。
     *
     * <p>⭐ 带连接 id：多连接对象树 + catalog 树之后，用户右键的节点可能属于
     * 另一个连接 / 另一个库 —— 刷新必须刷**那个**连接，不是当前活跃的。
     */
    private Consumer<String> onRefreshObjects = id -> { };
    private Consumer<TableRequest> onTableDdl = request -> { };
    /** ⭐ UX-6：连接节点下的「已保存查询」来源（外壳给 savedQueries::all）。 */
    private Supplier<List<SavedQueries.Entry>> savedQuerySource = List::of;
    /** ⭐ UX-6：双击 / 回车一条已保存查询 → 外壳建查询页并预填 SQL。 */
    private Consumer<SavedQueryRequest> onOpenSavedQuery = request -> { };

    private final Set<String> starred = new LinkedHashSet<>();
    /** 收藏的持久化位置（以前只在内存里 —— 退出即丢）。 */
    private final SettingsStore settings;
    private static final String STARRED_KEY = "sidebar.starred";
    private DemoNode selectedNode;
    private String dialectName = "—";
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
        NEW_TABLE,
        TRUNCATE_TABLE,
        RENAME_TABLE,
        DROP_TABLE,
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

    /** 设置某个连接的 catalog 清单（U1；空清单 = 这个连接没有库一层）。 */
    public void setCatalogs(String connectionId, List<CatalogEntry> entries) {
        if (connectionId == null) {
            return;
        }
        catalogsByConnection.put(connectionId,
                entries == null ? List.of() : List.copyOf(entries));
        attemptedCatalogs.add(connectionId);
        refreshTree();
    }

    /** 需要某个连接的 catalog 清单时调用（AppShell 负责读）。 */
    public void setOnLoadCatalogs(Consumer<String> onLoadCatalogs) {
        this.onLoadCatalogs = onLoadCatalogs == null ? id -> { } : onLoadCatalogs;
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

    /** 多连接切换：替换可选连接列表并保持当前选中项。 */
    public void setConnections(List<ConnectionProfile> connections) {
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        refreshTree();
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
        // ⭐ S-3：对象重新内省过了 —— 该连接缓存的列清单一起作废。
        //    表可能刚被 ALTER 过（加列 / 删列），留着旧列比没有列更糟。
        dropColumnsOf(connectionId);
        if (connectionId.equals(this.connectionId)) {
            setObjects(objects, true);
        } else {
            refreshTree();
        }
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
        procedures = safe.stream()
                .filter(object -> object.kind() == ObjectKind.PROCEDURE)
                .toList();
        functions = safe.stream()
                .filter(object -> object.kind() == ObjectKind.FUNCTION)
                .toList();
        triggers = safe.stream()
                .filter(object -> object.kind() == ObjectKind.TRIGGER)
                .toList();
        // ⭐ schema 分组：只有「存在多个 schema」时才加这一层，
        //    否则常见的单 schema 连接会凭空多一次点击。
        schemaCount = (int) safe.stream()
                .map(SchemaObject::schema)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
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

        HBox bottomBar = new HBox(6, search, onlyFavorites, collapse);
        HBox.setHgrow(search, Priority.ALWAYS);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.getStyleClass().add("adb-sidebar__bottom");

        // ⭐ U-B7：去掉「工作台 / 工具」分段**不等于**把入口一起删掉。
        //    查询历史与诊断是**真功能**（HistoryPage / DiagnosticsPage），项目规则是
        //    「占位要么接要么删」—— 这里接的是真功能，就必须留入口，否则功能变成不可达。
        //    Navicat 模型是「一棵树 + 底部工具」，所以这里**不加分段标题**，平铺在底栏上方。
        Button history = navButton("sidebar-history", Icons.Icon.HISTORY,
                I18n.t("sidebar.tool.history"),
                () -> openTool(TOOL_HISTORY, I18n.t("sidebar.tool.history")));
        // 诊断对象树里当前选中的表；没选中时 onSelected 会给提示而不是崩。
        Button diagnostics = navButton("sidebar-diagnostics", Icons.Icon.DIAGNOSTICS, I18n.t("action.object.diagnostics"),
                () -> actions.run("object.diagnostics"));
        // 导入 / 导出（P6）：菜单与工具栏也有入口，这里补回侧栏让三个工具成一行，
        // 同时让 TRANSFER 图标重新被使用（否则 IconSystemAuditTest 判它是死代码）。
        Button transfer = navButton("sidebar-transfer", Icons.Icon.TRANSFER, I18n.t("action.import-export"),
                () -> actions.run("import-export"));
        VBox tools = new VBox(2, history, diagnostics, transfer);
        tools.getStyleClass().add("adb-sidebar__tools");

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
        // ⭐ 行高固定 22px（Navicat 密度）。默认行高由字体 + 内边距撑到 26px 以上，
        //    同样是 480px 高的侧栏，能看见的对象少 4 行 —— 而侧栏存在的意义
        //    就是「一屏扫完这个库有什么」。
        //    22px 来自 `row-tree` 令牌（唯一真相），不写死 —— 改密度只改令牌。
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
                if (node.kind() == NodeKind.CONNECTION && !node.connectionId().isBlank()) {
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
                } else if (node.kind() == NodeKind.SAVED_QUERY) {
                    // ⭐ UX-6：双击已保存查询 = 新开查询页并载入它。
                    openSavedQuery(node.connectionId(), node.name());
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
                } else if (node.kind() == NodeKind.SAVED_QUERY) {
                    openSavedQuery(node.connectionId(), node.name());
                    event.consume();
                } else if (node.kind() == NodeKind.CATALOG) {
                    onSwitchCatalog.accept(new CatalogSwitch(node.connectionId(), node.name()));
                    event.consume();
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

        expanded.getChildren().addAll(header, schemaPicker, tree, searchEmpty, tools, bottomBar);
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
            case ROOT, GROUP, COLUMN, CONNECTION_GROUP, SAVED_GROUP, SAVED_QUERY -> null;
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

    /** 侧栏导航项：图标 + 文字。 */
    private Button navButton(String id, Icons.Icon icon, String text, Runnable handler) {
        Button button = IconButtons.of(icon, text);
        button.setId(id);
        button.setAccessibleText(text);
        button.getStyleClass().add("adb-sidebar__nav");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> handler.run());
        return button;
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
                            entry.getKey(), NodeKind.CONNECTION_GROUP, Icons.Icon.FOLDER));
                    groupNode.setExpanded(true);
                    for (ConnectionProfile profile : entry.getValue()) {
                        connectionNode(groupNode, profile.id(), profile.name(), needle);
                    }
                    root.getChildren().add(groupNode);
                });
        return root;
    }

    /** 一个连接节点：有 catalog 的连接先出库一层，否则分组直接挂连接下。 */
    private void connectionNode(TreeItem<DemoNode> root, String id, String name, String needle) {
        boolean active = isActiveConnection(id);
        TreeItem<DemoNode> connectionNode = new TreeItem<>(
                new DemoNode(name, NodeKind.CONNECTION, Icons.Icon.CONNECT, id));
        connectionNode.setExpanded(active);

        List<CatalogEntry> catalogs = catalogsByConnection.getOrDefault(id, List.of());
        if (!catalogs.isEmpty()) {
            // ⭐ U1：MySQL 这类「切库要重连」的方言 —— 每个库一个节点，
            //    表 / 视图分组挂在**库**下面（库才是对象的归属）。
            for (CatalogEntry catalog : catalogs) {
                TreeItem<DemoNode> catalogNode = new TreeItem<>(new DemoNode(
                        catalog.catalog(), NodeKind.CATALOG, Icons.Icon.DATABASE,
                        "", catalog.connectionId()));
                // 当前正在用的库默认展开，一眼能看到自己在哪个库里。
                catalogNode.setExpanded(catalog.connectionId().equals(connectionId));
                // ⚠️ 库节点**永远保留** —— 它的对象可能还没读（懒加载）。
                //    以前在下面统一 removeIf(isLeaf)，对象没读到的库会被
                //    当成「空分组」摘掉：连接下看起来什么都没有，用户也就
                //    永远点不到那个库去触发加载 —— 懒加载链就此断掉。
                catalogNode.getChildren().addAll(nonEmptyGroups(catalog.connectionId(), needle));
                connectionNode.getChildren().add(catalogNode);
            }
        } else {
            connectionNode.getChildren().addAll(nonEmptyGroups(id, needle));
        }

        // ⭐ UX-6：已保存查询直接挂在**连接**下（不是库下）——
        //    它是跟着连接走的全局清单，没必要在每个库下面重复一遍。
        TreeItem<DemoNode> saved = savedQueriesGroup(id, needle);
        if (saved != null) {
            connectionNode.getChildren().add(saved);
        }

        root.getChildren().add(connectionNode);
    }

    /**
     * ⭐ UX-6：连接节点下的「已保存查询」分组。
     *
     * <p>只列完整查询 —— 代码片段是「插进当前编辑器」用的，
     * 放进这个「双击载入新查询」的列表里，用户点开的行为就说不清了。
     *
     * <p>搜索词同样过滤：搜表名时不该被一堆查询名占满。
     */
    private TreeItem<DemoNode> savedQueriesGroup(String servingConnectionId, String needle) {
        List<SavedQueries.Entry> visible = savedQueryEntries().stream()
                .filter(entry -> matches(entry.name(), needle))
                .toList();
        if (visible.isEmpty()) {
            return null;
        }
        TreeItem<DemoNode> group = new TreeItem<>(new DemoNode(
                I18n.t("sidebar.group.savedQueries"), NodeKind.SAVED_GROUP, Icons.Icon.SAVE, "",
                servingConnectionId, visible.size()));
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
            // ⭐ 底部 ★ 按钮：只看收藏的对象
            objects = objects.stream()
                    .filter(object -> starred.contains(object.name()))
                    .toList();
        }
        List<SchemaObject> profileTables = objects.stream()
                .filter(object -> object.kind() == ObjectKind.TABLE).toList();
        List<SchemaObject> profileViews = objects.stream()
                .filter(object -> object.kind() == ObjectKind.VIEW).toList();
        List<SchemaObject> profileProcedures = objects.stream()
                .filter(object -> object.kind() == ObjectKind.PROCEDURE).toList();
        List<SchemaObject> profileFunctions = objects.stream()
                .filter(object -> object.kind() == ObjectKind.FUNCTION).toList();
        List<SchemaObject> profileTriggers = objects.stream()
                .filter(object -> object.kind() == ObjectKind.TRIGGER).toList();
        // ⭐ O1：调度事件（只有 MySQL 有；其它方言这一组是空的 → 自动摘除）。
        List<SchemaObject> profileEvents = objects.stream()
                .filter(object -> object.kind() == ObjectKind.EVENT).toList();

        return List.of(
                kindGroup(I18n.t("sidebar.group.tables"), NodeKind.TABLE, profileTables, needle, servingConnectionId),
                kindGroup(I18n.t("sidebar.group.views"), NodeKind.VIEW, profileViews, needle, servingConnectionId),
                // ⭐ S3：过程 / 函数 / 触发器（Navicat 的对象浏览层）
                kindGroup(I18n.t("sidebar.group.procedures"), NodeKind.PROCEDURE, profileProcedures, needle, servingConnectionId),
                kindGroup(I18n.t("sidebar.group.functions"), NodeKind.FUNCTION, profileFunctions, needle, servingConnectionId),
                kindGroup(I18n.t("sidebar.group.triggers"), NodeKind.TRIGGER, profileTriggers, needle, servingConnectionId),
                // ⭐ O1：事件分组（空分组会被摘掉，所以非 MySQL 看不到它）。
                kindGroup(I18n.t("sidebar.group.events"), NodeKind.EVENT, profileEvents, needle, servingConnectionId));
    }


    /** 一个分组节点（表 / 视图）。 */
    private TreeItem<DemoNode> kindGroup(String label, NodeKind kind,
                                        List<SchemaObject> objects, String needle,
                                        String connectionId) {
        // ⭐ 先按搜索词过滤，再建节点 —— 这样分组标题上的计数是
        //    「当前可见的数量」，而不是总数（搜 `user` 时显示 3 而不是 12）。
        List<SchemaObject> visible = objects.stream()
                .filter(object -> matches(object.name(), needle))
                .toList();
        TreeItem<DemoNode> group = new TreeItem<>(
                new DemoNode(label, NodeKind.GROUP, groupIcon(kind), "", connectionId,
                        visible.size()));
        group.setExpanded(true);
        for (SchemaObject object : visible) {
            group.getChildren().add(objectNode(object, kind, connectionId));
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
     */
    private static Icons.Icon groupIcon(NodeKind kind) {
        return switch (kind) {
            case TABLE -> Icons.Icon.TABLE;
            case VIEW -> Icons.Icon.VIEW_OBJECT;
            case PROCEDURE, FUNCTION -> Icons.Icon.FUNCTION;
            case TRIGGER -> Icons.Icon.TRIGGER;
            case EVENT -> Icons.Icon.HISTORY;
            default -> Icons.Icon.TABLE;
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
            tree.getRoot().setExpanded(true);
            tree.getRoot().getChildren().forEach(item -> item.setExpanded(true));
        }
        updateSearchEmpty(filter);
        // ⚠️ 重建模型后视口要回到顶部 —— 否则上一次留下的滚动偏移会让
        //    第一行（连接组）被切掉一半，看起来像「上面少了一行」。
        tree.scrollTo(0);
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
                    SAVED_GROUP, SAVED_QUERY -> false;
        };
    }

    /** 收藏的对象名（供「收藏夹」菜单用）。 */
    /**
     * ⭐ X6：清空收藏。
     *
     * <p>以前收藏只能一个一个取消（「收藏 / 取消收藏」是切换动作）——
     * 收藏多了以后没法一键清空。
     */
    public void clearStarred() {
        if (starred.isEmpty()) {
            status.accept(I18n.t("sidebar.noFavorites"));
            return;
        }
        int count = starred.size();
        starred.clear();
        settings.set(STARRED_KEY, "");
        refreshTree();
        status.accept(I18n.t("sidebar.favoritesCleared", count));
    }

    public List<String> starredNames() {
        return List.copyOf(starred);
    }

    /** 打开一个收藏的对象（走正常的开表路径）。 */
    public void openStarred(String name) {
        openGrid(connectionId, name);
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
            for (String name : raw.split("\n")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    starred.add(trimmed);
                }
            }
        } catch (RuntimeException ignored) {
            // 存坏了就当没有收藏。
        }
    }

    /** 收藏落盘。 */
    private void saveStarred() {
        if (settings == null) {
            return;
        }
        settings.set(STARRED_KEY, String.join("\n", starred));
    }

    private void toggleStar(String name) {
        if (!starred.remove(name)) {
            starred.add(name);
        }
        saveStarred();
        refreshTree();
        status.accept(starred.contains(name) ? I18n.t("sidebar.starred", name) : I18n.t("sidebar.unstarred", name));
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
            case TABLE, VIEW -> menu.getItems().addAll(
                    actions.menuItem("object.open-data"),
                    actions.menuItem("object.open-structure"),
                    actions.menuItem("object.new-query"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.favorite",
                            starred.contains(node.name())
                                    ? I18n.t("sidebar.favoriteRemove")
                                    : I18n.t("sidebar.favoriteAdd")),
                    actions.menuItem("object.copy-name"),
                    actions.menuItem("object.copy-select"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.diagnostics"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.truncate-table"),
                    actions.menuItem("object.rename-table"),
                    actions.menuItem("object.drop-table"));
            case CONNECTION -> menu.getItems().addAll(
                    actions.menuItem("new-connection", I18n.t("sidebar.editConnection")),
                    actions.menuItem("object.refresh-objects"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-table"),
                    actions.menuItem("object.new-query"));
            // ⭐ U1：库节点 —— 切过去 / 在里面建表 / 查数据。
            case CATALOG -> menu.getItems().addAll(
                    actions.menuItem("object.switch-catalog"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-query"),
                    actions.menuItem("object.new-table"),
                    actions.menuItem("object.refresh-objects"));
            // ⭐ P5-1：分组节点只装连接 —— 菜单只留「新建连接」。
            case CONNECTION_GROUP -> menu.getItems().add(
                    actions.menuItem("new-connection"));
            case GROUP -> menu.getItems().addAll(
                    actions.menuItem("object.refresh-objects"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-table"));
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
                            starred.contains(node.name())
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
                    toggleStar(node.name());
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
                    copy("SELECT * FROM \"" + node.name() + "\" LIMIT 200;");
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
        // ⭐ 表级生命周期：建表挂在连接 / 分组 / 库上，改表只挂在「表」上 ——
        //    视图没有 TRUNCATE，过程函数更没有，所以不能一律放行。
        setObjectActionEnabled("object.new-table",
                node != null && !isConnectionGroup && !isSavedNode);
        setObjectActionEnabled("object.truncate-table", isTable);
        setObjectActionEnabled("object.rename-table", isTable);
        setObjectActionEnabled("object.drop-table", isTable);
        // ⭐ U1：切库只挂在库节点上。
        setObjectActionEnabled("object.switch-catalog", isCatalog);
        // ⭐ UX-6：已保存查询只有「打开」可用。
        setObjectActionEnabled("saved-query.open", isSavedQuery);
    }

    private void setObjectActionEnabled(String id, boolean enabled) {
        actions.find(id).ifPresent(action -> action.setEnabled(enabled));
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
        /** ⭐ UX-6：已保存查询分组 / 条目（连接节点下）。 */
        SAVED_GROUP, SAVED_QUERY
    }

    /**
     * 树节点。
     *
     * @param schema       所属 schema（表/视图节点用；其它节点为空串）
     * @param connectionId 所属连接（连接节点与其下的对象用；其它节点为空串）
     * @param count        分组节点下的可见对象数（非分组节点为 0）——
     *                     用来在「表」后面显示计数徽标
     */
    private record DemoNode(String name, NodeKind kind, Icons.Icon icon, String schema,
                            String connectionId, int count) {

        private DemoNode(String name, NodeKind kind, Icons.Icon icon) {
            this(name, kind, icon, "", "", 0);
        }

        private DemoNode(String name, NodeKind kind, Icons.Icon icon, String connectionId) {
            this(name, kind, icon, "", connectionId, 0);
        }

        private DemoNode(String name, NodeKind kind, Icons.Icon icon, String schema,
                         String connectionId) {
            this(name, kind, icon, schema, connectionId, 0);
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
            SVGPath icon = Icons.of(item.icon(), Icons.SIZE_LABEL);
            icon.getStyleClass().addAll("adb-tree__icon", iconStyleClass(item.kind()));

            Label name = new Label(item.name());
            name.getStyleClass().add("adb-tree__name");

            HBox box = new HBox(6, icon, name);
            box.setAlignment(Pos.CENTER_LEFT);

            // ⭐ UX-5：连接存活点（与应用栏那颗点同一套令牌：success / text-muted）。
            //    放在图标与名字**之间**，不是名字后面 —— 长名字会把尾部的点挤出格子，
            //    「在线/离线」恰恰是最不该看不见的那条信息。
            if (item.kind() == NodeKind.CONNECTION && connectionAlive != null) {
                boolean alive = connectionAlive.test(item.connectionId());
                SVGPath dot = Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT);
                dot.getStyleClass().addAll("adb-tree__dot", alive
                        ? "adb-tree__dot--online" : "adb-tree__dot--offline");
                dot.setAccessibleText(alive ? I18n.t("sidebar.connected") : I18n.t("sidebar.disconnected"));
                box.getChildren().add(1, dot);
            }

            // ⭐ 分组标题右侧的对象计数 —— 让用户不用展开就知道这个库里
            //    有多少张表（Navicat 也是这个形态）。
            if (item.kind() == NodeKind.GROUP && item.count() > 0) {
                Label count = new Label(String.valueOf(item.count()));
                count.getStyleClass().add("adb-tree__count");
                box.getChildren().add(count);
            }

            // ⭐ U1：当前正在用的库加粗标出（Navicat 也是这样标当前数据库的）。
            if (item.kind() == NodeKind.CATALOG
                    && item.connectionId().equals(connectionId)) {
                getStyleClass().add("adb-tree-cell--current");
                name.getStyleClass().add("adb-tree__name--current");
            }

            if ((item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW)
                    && starred.contains(item.name())) {
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
