package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.Tokens;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
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
import java.util.function.Consumer;

/**
 * 左侧对象树。节点来自 {@link SchemaService} 的真实内省结果，不再硬编码表名。
 *
 * <p>侧栏两种形态：展开 240px / 折叠 48px。折叠不是隐藏，
 * 高频入口仍保留在图标栏里 —— 这是与「一键隐藏整栏」的关键差别。
 */
public final class SideBar extends VBox {

    private final ActionRegistry actions;
    private String connectionName;
    private String connectionId;
    private final Consumer<TabItem> openTab;
    private final Consumer<String> status;
    private Consumer<ConnectionProfile> onSelectConnection = profile -> { };
    private List<ConnectionProfile> connections = List.of();
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
    /** 需要某个连接的对象时调用（AppShell 负责连上去读）。 */
    private Consumer<String> onLoadObjects = connectionId -> { };
    /** 已经尝试过读取的对象（读失败也不反复试）。 */
    private final Set<String> attempted = new LinkedHashSet<>();
    /** 出现的 schema 个数；> 1 时对象树按 schema 分组。 */
    private int schemaCount;
    private Runnable onRefreshObjects = () -> { };
    private Consumer<TableRequest> onTableDdl = request -> { };

    private final Set<String> starred = new LinkedHashSet<>();
    /** 收藏的持久化位置（以前只在内存里 —— 退出即丢）。 */
    private final SettingsStore settings;
    private static final String STARRED_KEY = "sidebar.starred";
    private DemoNode selectedNode;
    private String dialectName = "—";
    private final VBox expanded = new VBox(8);
    private final VBox rail = new VBox(6);
    private final VBox favoriteBox = new VBox(4);
    /** 「收藏」整段 —— 没有收藏时整段隐藏（Navicat 不会留一块空标题占地方）。 */
    private VBox favoritesSection;
    private final TextField search = new TextField();
    private final TreeView<DemoNode> tree = new TreeView<>();
    private final ComboBox<ConnectionProfile> connectionPicker = new ComboBox<>();
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
        setPrefWidth(240);
        setMinWidth(240);
        setMaxWidth(240);

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
        actions.register("object.open-data", "打开数据", "对象", null,
                () -> onSelected(SideBarObject.OPEN_DATA));
        actions.register("object.open-structure", "打开结构", "对象", null,
                () -> onSelected(SideBarObject.OPEN_STRUCTURE));
        actions.register("object.new-query", "新建查询", "对象", null,
                () -> onSelected(SideBarObject.NEW_QUERY));
        actions.register("object.favorite", "收藏 / 取消收藏", "对象", null,
                () -> onSelected(SideBarObject.FAVORITE));
        actions.register("object.copy-name", "复制名称", "对象", null,
                () -> onSelected(SideBarObject.COPY_NAME));
        actions.register("object.copy-select", "复制 SELECT", "对象", null,
                () -> onSelected(SideBarObject.COPY_SELECT));
        actions.register("object.diagnostics", "诊断", "对象", null,
                () -> onSelected(SideBarObject.DIAGNOSTICS));
        actions.register("object.refresh-objects", "刷新对象", "对象", null,
                () -> onSelected(SideBarObject.REFRESH_OBJECTS));
        // ⭐ X6：收藏夹菜单只有一项 → 顶级菜单资源被浪费。补「清空收藏」。
        actions.register("favorites.clear", "清空收藏", "收藏夹", null, this::clearStarred);
        // ── T1–T4 表级生命周期 ──
        actions.register("object.new-table", "新建表…", "对象", null,
                () -> onSelected(SideBarObject.NEW_TABLE));
        actions.register("object.truncate-table", "清空表…", "对象", null,
                () -> onSelected(SideBarObject.TRUNCATE_TABLE));
        actions.register("object.rename-table", "重命名表…", "对象", null,
                () -> onSelected(SideBarObject.RENAME_TABLE));
        actions.register("object.drop-table", "删除表…", "对象", null,
                () -> onSelected(SideBarObject.DROP_TABLE));
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
        DROP_TABLE
    }

    /**
     * 转发给外壳的一次表级请求。
     *
     * <p>侧栏不知道怎么执行 DDL（那是 {@link TableDdl} 的事），
     * 只知道「用户在哪个连接上的哪个节点点了什么」。
     */
    record TableRequest(SideBarObject action, String tableName, String connectionId) {
    }

    public void setOnCollapsed(Consumer<Boolean> onCollapsed) {
        this.onCollapsed = onCollapsed;
    }

    public void setOnRefreshObjects(Runnable onRefreshObjects) {
        this.onRefreshObjects = onRefreshObjects == null ? () -> { } : onRefreshObjects;
    }

    /** 表级生命周期（T1–T4）交给外壳执行。 */
    public void setOnTableDdl(Consumer<TableRequest> onTableDdl) {
        this.onTableDdl = onTableDdl == null ? request -> { } : onTableDdl;
    }

    /** 多连接切换：替换可选连接列表并保持当前选中项。 */
    public void setConnections(List<ConnectionProfile> connections) {
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        connectionPicker.setItems(javafx.collections.FXCollections.observableArrayList(this.connections));
        if (connectionId != null) {
            connectionPicker.getSelectionModel().select(findActiveProfile());
        }
    }

    public void setConnection(String id, String name) {
        this.connectionId = id;
        this.connectionName = name;
        refreshTree();
        refreshFavorites();
        loadObjectsIfNeeded(id);
        if (id != null) {
            connectionPicker.getSelectionModel().select(findActiveProfile());
        }
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
        if (connectionId.equals(this.connectionId)) {
            setObjects(objects, true);
        } else {
            refreshTree();
        }
    }

    /** 需要某个连接的对象时调用（AppShell 连上去读）。 */
    public void setOnLoadObjects(Consumer<String> onLoadObjects) {
        this.onLoadObjects = onLoadObjects == null ? id -> { } : onLoadObjects;
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
            refreshFavorites();
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
        } else {
            refreshFavorites();
        }
        onCollapsed.accept(value);
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

        Label title = new Label("对象");
        title.getStyleClass().add("adb-sidebar__title");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button collapse = IconButtons.icon(Icons.Icon.CHEVRON_LEFT, "折叠侧栏");
        collapse.setAccessibleText("折叠侧栏");
        collapse.setId("sidebar-collapse");
        collapse.getStyleClass().add("adb-icon-button");
        collapse.setTooltip(new Tooltip("折叠侧栏 (⌘\\)"));
        collapse.setOnAction(event -> actions.run("sidebar.toggle"));

        HBox header = new HBox(6, title, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("搜索表、视图");
        search.getStyleClass().add("adb-sidebar__search");
        search.setPrefHeight(io.agenticdb.ui.Tokens.H_INPUT_LIGHT);
        search.setMinWidth(120);
        search.textProperty().addListener((observable, oldValue, newValue) -> refreshTree());

        // ⭐ S4：搜索框在**底部**（Navicat 就是这个位置），
        //    和 ★ 收藏、« 折叠按钮同一行 —— 不是在顶上单独占一行。
        Button onlyFavorites = IconButtons.icon(Icons.Icon.STAR, "只看收藏");
        onlyFavorites.setId("sidebar-favorites-filter");
        onlyFavorites.setAccessibleText("只看收藏");
        onlyFavorites.getStyleClass().add("adb-icon-button");
        onlyFavorites.setTooltip(new Tooltip("只看收藏"));
        onlyFavorites.setOnAction(event -> {
            showOnlyFavorites = !showOnlyFavorites;
            if (showOnlyFavorites) {
                onlyFavorites.getStyleClass().add("adb-icon-button--active");
            } else {
                onlyFavorites.getStyleClass().remove("adb-icon-button--active");
            }
            refreshTree();
            status.accept(showOnlyFavorites ? "只看收藏" : "显示全部对象");
        });

        HBox bottomBar = new HBox(6, search, onlyFavorites, collapse);
        HBox.setHgrow(search, Priority.ALWAYS);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.getStyleClass().add("adb-sidebar__bottom");

        // ⭐ 紧凑成图标按钮：侧栏只有 240px，文字按钮会被连接下拉挤没
        //    （以前整颗按钮被压成「..」两个字，截图里能看见）。
        Button manageConnections = IconButtons.icon(Icons.Icon.CONNECT, "连接管理");
        manageConnections.setId("sidebar-connections");
        manageConnections.setAccessibleText("连接管理");
        manageConnections.getStyleClass().addAll("adb-icon-button", "adb-sidebar__nav");
        manageConnections.setTooltip(new Tooltip("连接管理"));
        manageConnections.setMinWidth(Region.USE_PREF_SIZE);
        manageConnections.setOnAction(event -> openConnections());

        connectionPicker.setId("sidebar-connection-picker");
        connectionPicker.getStyleClass().add("adb-connection-picker");
        connectionPicker.setMaxWidth(Double.MAX_VALUE);
        connectionPicker.setMinWidth(90);
        connectionPicker.setCellFactory(view -> new ConnectionCell());
        connectionPicker.setButtonCell(new ConnectionCell());
        connectionPicker.setItems(javafx.collections.FXCollections.observableArrayList(connections));
        if (connectionId != null) {
            connectionPicker.getSelectionModel().select(findActiveProfile());
        }
        connectionPicker.setOnAction(event -> {
            ConnectionProfile selected = connectionPicker.getValue();
            if (selected != null) {
                onSelectConnection.accept(selected);
            }
        });

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

        HBox connectionBar = new HBox(6, manageConnections, connectionPicker, schemaPicker);
        HBox.setHgrow(connectionPicker, Priority.ALWAYS);
        HBox.setHgrow(schemaPicker, Priority.ALWAYS);
        connectionBar.getStyleClass().add("adb-sidebar__connection");

        searchEmpty.setId("sidebar-search-empty");
        searchEmpty.getStyleClass().add("adb-muted");
        searchEmpty.setPadding(new Insets(4, 4, 4, 4));
        searchEmpty.setWrapText(true);
        searchEmpty.setVisible(false);

        favoritesSection = section("收藏", favoriteBox);
        VBox favorites = favoritesSection;
        VBox connections = section("连接", tree, searchEmpty);
        VBox.setVgrow(tree, Priority.ALWAYS);

        tree.getStyleClass().add("adb-object-tree");
        tree.setShowRoot(true);
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
            // ⭐ 选中一个还没读过的连接 → 去读它的对象（懒加载）。
            //    不这么做的话，多连接树里非当前连接的节点永远是空的。
            if (item != null) {
                DemoNode node = item.getValue();
                if (node.kind() == NodeKind.CONNECTION && !node.connectionId().isBlank()) {
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
                } else if (node.kind() == NodeKind.CONNECTION) {
                    selected.setExpanded(!selected.isExpanded());
                }
            }
        });
        tree.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected != null && (selected.getValue().kind() == NodeKind.TABLE
                        || selected.getValue().kind() == NodeKind.VIEW)) {
                    openGrid(selected.getValue().name());
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

        VBox workbench = section("工作台",
                navButton("sidebar-nav-history", Icons.Icon.HISTORY, "查询历史",
                        () -> openTool("查询历史")),
                // 已保存查询接真实功能（命名 + 落盘 + 一键打开）。
                navButton("sidebar-nav-saved", Icons.Icon.STAR, "已保存查询",
                        () -> actions.run("saved-queries")));

        VBox tools = section("工具",
                // 诊断接真实功能：诊断对象树里当前选中的表。
                navButton("sidebar-nav-diagnostics", Icons.Icon.DIAGNOSTICS, "诊断",
                        () -> actions.run("object.diagnostics")),
                // 导入 / 导出接真实功能（P6）。
                navButton("sidebar-nav-transfer", Icons.Icon.TRANSFER, "导入 / 导出",
                        () -> actions.run("import-export")),
                navButton("sidebar-nav-settings", Icons.Icon.SETTINGS, "设置",
                        () -> openTab.accept(new TabItem("settings", "设置", TabKind.SETTINGS))));

        expanded.getChildren().addAll(header, connectionBar, favorites, connections,
                workbench, tools, bottomBar);
        VBox.setVgrow(connections, Priority.ALWAYS);

        refreshFavorites();
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

        Button expand = railButton(Icons.Icon.CHEVRON_RIGHT, "展开侧栏 (⌘\\)", () -> actions.run("sidebar.toggle"));
        expand.setId("sidebar-expand");
        Button connection = railButton(Icons.Icon.CONNECT, connectionName,
                () -> status.accept("连接：" + connectionName));
        Button favorites = railButton(Icons.Icon.STAR, "收藏", () -> {
            setCollapsed(false);
            refreshFavorites();
        });
        String firstTable = tables.isEmpty() ? "表" : tables.getFirst().name();
        Button users = railButton(Icons.Icon.TABLE, firstTable, () -> {
            if (!tables.isEmpty()) {
                openGrid(tables.getFirst().name());
            }
        });
        Button find = railButton(Icons.Icon.SEARCH, "搜索 (⌘F)", this::focusSearch);
        Button tools = railButton(Icons.Icon.DIAGNOSTICS, "工具", () -> openTool("诊断"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // 4.5-11：这里以前是写死的「DEV」。现在显示当前连接的方言 —— 真信息。
        Label dialect = new Label(dialectName);
        dialect.getStyleClass().add("adb-rail__dialect");
        dialect.setAccessibleText("当前数据库方言：" + dialectName);

        rail.getChildren().addAll(expand, connection, favorites, users, find, tools, spacer, dialect);
    }

    /** 设置当前连接的方言（状态栏与折叠侧栏共用同一个信息）。 */
    public void setDialect(String name) {
        this.dialectName = name == null || name.isBlank() ? "—" : name;
        if (rail.getChildren().size() > 0) {
            Label last = (Label) rail.getChildren().get(rail.getChildren().size() - 1);
            last.setText(dialectName);
            last.setAccessibleText("当前数据库方言：" + dialectName);
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
        List<String[]> entries = new ArrayList<>();
        for (ConnectionProfile profile : connections) {
            entries.add(new String[]{profile.id(), profile.name()});
        }
        if (entries.isEmpty() && connectionId != null) {
            entries.add(new String[]{connectionId, connectionName});
        }

        TreeItem<DemoNode> root = new TreeItem<>(
                new DemoNode("我的连接", NodeKind.ROOT, Icons.Icon.DATABASE));
        root.setExpanded(true);

        for (String[] entry : entries) {
            String id = entry[0];
            boolean active = id.equals(connectionId);
            TreeItem<DemoNode> connectionNode = new TreeItem<>(
                    new DemoNode(entry[1], NodeKind.CONNECTION, Icons.Icon.CONNECT, id));
            connectionNode.setExpanded(active);

            List<SchemaObject> objects = objectsByConnection.getOrDefault(id, List.of());
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

            connectionNode.getChildren().addAll(
                    kindGroup("表", NodeKind.TABLE, profileTables, needle, id),
                    kindGroup("视图", NodeKind.VIEW, profileViews, needle, id),
                    // ⭐ S3：过程 / 函数 / 触发器（Navicat 的对象浏览层）
                    kindGroup("过程", NodeKind.PROCEDURE, profileProcedures, needle, id),
                    kindGroup("函数", NodeKind.FUNCTION, profileFunctions, needle, id),
                    kindGroup("触发器", NodeKind.TRIGGER, profileTriggers, needle, id),
                    // ⭐ O1：事件分组（空分组会被摘掉，所以非 MySQL 看不到它）。
                    kindGroup("事件", NodeKind.EVENT, profileEvents, needle, id));

            // 空分组不显示（没读到对象的连接只留一个可展开的连接节点）。
            connectionNode.getChildren().removeIf(TreeItem::isLeaf);
            root.getChildren().add(connectionNode);
        }
        return root;
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
            group.getChildren().add(new TreeItem<>(
                    new DemoNode(object.name(), kind, iconOf(object.kind()), object.schema(),
                            connectionId)));
        }
        return group;
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
            searchEmpty.setText("没有匹配「" + filter.trim() + "」的对象");
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
            default -> false;
        };
    }

    private void refreshFavorites() {
        favoriteBox.getChildren().clear();
        // 没有收藏时整段隐藏：空标题 + 「右键对象 → 收藏」占掉侧栏最宝贵的前两行，
        // 而用户第一次看到它时并没有任何可操作的收藏项（Navicat 同位置不占位）。
        if (favoritesSection != null) {
            favoritesSection.setVisible(!starred.isEmpty());
            favoritesSection.setManaged(!starred.isEmpty());
        }
        if (starred.isEmpty()) {
            return;
        }
        for (String name : starred) {
            // ⭐ 收藏项：矢量实心星 + 名字。
            Button button = new Button(name);
            button.setGraphic(Icons.of(Icons.Icon.STAR_FILLED, Icons.SIZE_BADGE));
            button.setGraphicTextGap(6);
            button.getStyleClass().add("adb-favorite");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setOnAction(event -> openGrid(name));
            favoriteBox.getChildren().add(button);
        }
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
            status.accept("没有收藏");
            return;
        }
        int count = starred.size();
        starred.clear();
        settings.set(STARRED_KEY, "");
        refreshFavorites();
        status.accept("已清空 " + count + " 个收藏");
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
        refreshFavorites();
        refreshTree();
        status.accept(starred.contains(name) ? "已收藏 " + name : "已取消收藏 " + name);
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
                    actions.menuItem("object.open-data", "打开数据"),
                    actions.menuItem("object.open-structure", "打开结构"),
                    actions.menuItem("object.new-query", "新建查询"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.favorite",
                            starred.contains(node.name()) ? "取消收藏" : "收藏"),
                    actions.menuItem("object.copy-name", "复制名称"),
                    actions.menuItem("object.copy-select", "复制 SELECT"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.diagnostics", "诊断"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.truncate-table", "清空表…"),
                    actions.menuItem("object.rename-table", "重命名表…"),
                    actions.menuItem("object.drop-table", "删除表…"));
            case CONNECTION -> menu.getItems().addAll(
                    actions.menuItem("new-connection", "编辑连接"),
                    actions.menuItem("object.refresh-objects", "刷新对象"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-table", "新建表…"),
                    actions.menuItem("object.new-query", "新建查询"));
            case GROUP -> menu.getItems().addAll(
                    actions.menuItem("object.refresh-objects", "刷新对象"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-table", "新建表…"));
            // ⭐ S3：过程 / 函数 / 触发器没有数据页，也没有结构页 ——
            //    只给「复制名称 / 新建查询」（调用它）/ 收藏。
            case PROCEDURE, FUNCTION, TRIGGER -> menu.getItems().addAll(
                    actions.menuItem("object.new-query", "新建查询"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.favorite",
                            starred.contains(node.name()) ? "取消收藏" : "收藏"),
                    actions.menuItem("object.copy-name", "复制名称"));
            default -> menu.getItems().add(
                    actions.menuItem("object.open-data", "打开"));
        }
        return menu;
    }

    /** 执行一个对象树动作。动作本身不知道作用对象，由当前选中节点决定。 */
    private void onSelected(SideBarObject action) {
        DemoNode node = selectedNode;
        if (node == null) {
            status.accept("请先在对象树里选中一个对象");
            return;
        }
        boolean isObject = node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW;
        // ⭐ 多连接对象树：动作作用在**节点所属的连接**上，不是当前激活的连接。
        String owner = node.connectionId() == null || node.connectionId().isBlank()
                ? connectionId : node.connectionId();
        switch (action) {
            case OPEN_DATA -> {
                if (isObject) {
                    openGrid(owner, node.name());
                } else {
                    status.accept("只有表和视图能打开数据");
                }
            }
            case OPEN_STRUCTURE -> {
                if (isObject) {
                    openStructure(owner, node.name());
                } else {
                    status.accept("只有表和视图有结构页");
                }
            }
            case NEW_QUERY -> openQuery(owner, isObject ? node.name() : null);
            case FAVORITE -> {
                if (isObject) {
                    toggleStar(node.name());
                } else {
                    status.accept("只有表和视图可以收藏");
                }
            }
            case COPY_NAME -> {
                if (isObject) {
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
                    openTool("诊断 · " + node.name());
                }
            }
            case REFRESH_OBJECTS -> {
                onRefreshObjects.run();
                status.accept("已刷新 " + connectionName);
            }
            // ⭐ 表级生命周期：侧栏只负责把「点的是谁」说清楚，执行在 TableDdl。
            case NEW_TABLE ->
                    onTableDdl.accept(new TableRequest(SideBarObject.NEW_TABLE, null, owner));
            case TRUNCATE_TABLE, RENAME_TABLE, DROP_TABLE -> {
                if (isObject) {
                    onTableDdl.accept(new TableRequest(action, node.name(), owner));
                } else {
                    status.accept("只有表能" + labelOf(action));
                }
            }
        }
    }

    private static String labelOf(SideBarObject action) {
        return switch (action) {
            case TRUNCATE_TABLE -> "清空";
            case RENAME_TABLE -> "重命名";
            case DROP_TABLE -> "删除";
            default -> "操作";
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
        boolean isTable = node != null && node.kind() == NodeKind.TABLE;
        setObjectActionEnabled("object.open-data", isObject);
        setObjectActionEnabled("object.open-structure", isObject);
        setObjectActionEnabled("object.new-query", isObject || isConnection);
        setObjectActionEnabled("object.favorite", isObject);
        setObjectActionEnabled("object.copy-name", isObject);
        setObjectActionEnabled("object.copy-select", isObject);
        setObjectActionEnabled("object.diagnostics", isObject);
        // 过程 / 函数 / 触发器没有数据页 —— 相应动作要变灰并解释为什么。
        setObjectActionEnabled("object.open-data", isObject);
        setObjectActionEnabled("object.open-structure", isObject);
        setObjectActionEnabled("object.copy-select", isObject);
        setObjectActionEnabled("object.new-query", isObject || isCallable);
        setObjectActionEnabled("object.refresh-objects", node != null);
        // ⭐ 表级生命周期：建表挂在连接 / 分组上，改表只挂在「表」上 ——
        //    视图没有 TRUNCATE，过程函数更没有，所以不能一律放行。
        setObjectActionEnabled("object.new-table", node != null);
        setObjectActionEnabled("object.truncate-table", isTable);
        setObjectActionEnabled("object.rename-table", isTable);
        setObjectActionEnabled("object.drop-table", isTable);
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
        status.accept("打开表：" + name);
    }

    private void openStructure(String name) {
        openStructure(connectionId, name);
    }

    private void openStructure(String targetConnection, String name) {
        String id = targetConnection == null ? connectionId : targetConnection;
        openTab.accept(new TabItem("structure:" + id + ":" + name, name,
                TabKind.STRUCTURE, name, id));
        status.accept("打开结构：" + name);
    }

    private void openQuery(String name) {
        openQuery(connectionId, name);
    }

    private void openQuery(String targetConnection, String name) {
        String id = targetConnection == null ? connectionId : targetConnection;
        String title = name == null ? "未命名查询" : "查询 · " + name;
        openTab.accept(new TabItem("query:" + id + ":" + System.nanoTime(), title,
                TabKind.QUERY, null, id));
        status.accept("新建查询");
    }

    private void openConnections() {
        openTab.accept(new TabItem("connections", "连接管理", TabKind.CONNECTIONS));
        status.accept("打开连接管理");
    }

    private void openTool(String title) {
        openTab.accept(new TabItem("tool:" + title, title, TabKind.TOOL));
        status.accept("打开工具：" + title);
    }

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
        ROOT, CONNECTION, GROUP, TABLE, VIEW,
        /** ⭐ S3：过程 / 函数 / 触发器。 */
        PROCEDURE, FUNCTION, TRIGGER,
        /** ⭐ O1：调度事件（只有 MySQL 有）。 */
        EVENT
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

    private final class ConnectionCell extends ListCell<ConnectionProfile> {
        @Override
        protected void updateItem(ConnectionProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.name() + "  ·  " + item.kindLabel() + "  ·  " + item.displayTarget());
        }
    }

    private final class DemoCell extends TreeCell<DemoNode> {
        @Override
        protected void updateItem(DemoNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(
                    "adb-tree-cell--group", "adb-tree-cell--connection", "adb-tree-cell--object");
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

            // ⭐ 分组标题右侧的对象计数 —— 让用户不用展开就知道这个库里
            //    有多少张表（Navicat 也是这个形态）。
            if (item.kind() == NodeKind.GROUP && item.count() > 0) {
                Label count = new Label(String.valueOf(item.count()));
                count.getStyleClass().add("adb-tree__count");
                box.getChildren().add(count);
            }

            if ((item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW)
                    && starred.contains(item.name())) {
                SVGPath star = Icons.of(Icons.Icon.STAR, Icons.SIZE_BADGE);
                star.setAccessibleText("收藏");
                star.getStyleClass().add("adb-tree__star");
                box.getChildren().add(star);
            }

            switch (item.kind()) {
                case ROOT -> getStyleClass().add("adb-tree-cell--connection");
                case GROUP -> getStyleClass().add("adb-tree-cell--group");
                case CONNECTION -> getStyleClass().add("adb-tree-cell--connection");
                default -> getStyleClass().add("adb-tree-cell--object");
            }

            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2
                        && (item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW)) {
                    // ⭐ 按节点所属连接打开（多连接对象树）
                    openGrid(item.connectionId(), item.name());
                    event.consume();
                }
            });

            setText(null);
            setGraphic(box);
        }
    }
}
