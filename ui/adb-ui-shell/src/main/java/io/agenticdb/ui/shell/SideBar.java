package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.session.ConnectionProfile;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.List;
import java.util.Locale;
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
    /** 出现的 schema 个数；> 1 时对象树按 schema 分组。 */
    private int schemaCount;
    private Runnable onRefreshObjects = () -> { };

    private final Set<String> starred = new LinkedHashSet<>();
    private DemoNode selectedNode;
    private String dialectName = "—";
    private final VBox expanded = new VBox(8);
    private final VBox rail = new VBox(6);
    private final VBox favoriteBox = new VBox(4);
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
        this.actions = actions;
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
    }

    /** 对象树动作的种类。 */
    private enum SideBarObject {
        OPEN_DATA,
        OPEN_STRUCTURE,
        NEW_QUERY,
        FAVORITE,
        COPY_NAME,
        COPY_SELECT,
        DIAGNOSTICS,
        REFRESH_OBJECTS
    }

    public void setOnCollapsed(Consumer<Boolean> onCollapsed) {
        this.onCollapsed = onCollapsed;
    }

    public void setOnRefreshObjects(Runnable onRefreshObjects) {
        this.onRefreshObjects = onRefreshObjects == null ? () -> { } : onRefreshObjects;
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
        if (id != null) {
            connectionPicker.getSelectionModel().select(findActiveProfile());
        }
    }

    /** 用新的内省结果替换对象树；对象在数据库里被增删后调用。 */
    public void setObjects(List<SchemaObject> objects) {
        setObjects(objects, true);
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

        Button collapse = new Button("«");
        collapse.setAccessibleText("折叠侧栏");
        collapse.setId("sidebar-collapse");
        collapse.getStyleClass().add("adb-icon-button");
        collapse.setTooltip(new Tooltip("折叠侧栏 (⌘\\)"));
        collapse.setOnAction(event -> actions.run("sidebar.toggle"));

        HBox header = new HBox(6, title, headerSpacer, collapse);
        header.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("搜索表、视图");
        search.getStyleClass().add("adb-sidebar__search");
        search.setPrefHeight(28);
        search.textProperty().addListener((observable, oldValue, newValue) -> refreshTree());

        Button manageConnections = new Button("◈  连接管理");
        manageConnections.setId("sidebar-connections");
        manageConnections.getStyleClass().add("adb-sidebar__nav");
        manageConnections.setMaxWidth(Double.MAX_VALUE);
        manageConnections.setAlignment(Pos.CENTER_LEFT);
        manageConnections.setOnAction(event -> openConnections());

        connectionPicker.setId("sidebar-connection-picker");
        connectionPicker.getStyleClass().add("adb-connection-picker");
        connectionPicker.setMaxWidth(Double.MAX_VALUE);
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

        VBox favorites = section("收藏", favoriteBox);
        VBox connections = section("连接", tree);
        VBox.setVgrow(tree, Priority.ALWAYS);

        tree.getStyleClass().add("adb-object-tree");
        tree.setShowRoot(true);
        tree.setCellFactory(view -> new DemoCell());
        tree.setRoot(buildTreeModel(""));
        // ⭐ 记住当前选中的节点：对象树的动作（打开数据 / 复制 SELECT …）
        //    都是节点无关的命令，具体作用对象由它决定。
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            selectedNode = item == null ? null : item.getValue();
            updateObjectActionStates();
        });
        tree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<DemoNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    return;
                }
                DemoNode node = selected.getValue();
                if (node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW) {
                    openGrid(node.name());
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
                navButton("◷  查询历史", "查询历史", () -> openTool("查询历史")));

        VBox tools = section("工具",
                // 诊断接真实功能：诊断对象树里当前选中的表。
                navButton("◆  诊断", "诊断", () -> actions.run("object.diagnostics")),
                navButton("⚙  设置", "设置", () -> openTab.accept(new TabItem("settings", "设置", TabKind.SETTINGS))));

        expanded.getChildren().addAll(header, search, connectionBar, favorites, connections,
                workbench, tools);
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

    private Button navButton(String text, String accessible, Runnable handler) {
        Button button = new Button(text);
        button.setAccessibleText(accessible);
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

        Button expand = railButton("»", "展开侧栏 (⌘\\)", () -> actions.run("sidebar.toggle"));
        expand.setId("sidebar-expand");
        Button connection = railButton("◈", connectionName,
                () -> status.accept("连接：" + connectionName));
        Button favorites = railButton("★", "收藏", () -> {
            setCollapsed(false);
            refreshFavorites();
        });
        String firstTable = tables.isEmpty() ? "表" : tables.getFirst().name();
        Button users = railButton("▦", firstTable, () -> {
            if (!tables.isEmpty()) {
                openGrid(tables.getFirst().name());
            }
        });
        Button find = railButton("⌕", "搜索 (⌘F)", this::focusSearch);
        Button tools = railButton("◆", "工具", () -> openTool("诊断"));

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

    private Button railButton(String text, String tooltip, Runnable handler) {
        Button button = new Button(text);
        button.getStyleClass().add("adb-rail__button");
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        button.setOnAction(event -> handler.run());
        return button;
    }

    // ─────────────────────────── 对象树 ───────────────────────────

    private TreeItem<DemoNode> buildTreeModel(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        TreeItem<DemoNode> root = new TreeItem<>(
                new DemoNode(connectionName, NodeKind.CONNECTION, "◈"));
        root.setExpanded(true);

        root.getChildren().addAll(
                kindGroup("表", NodeKind.TABLE, tables, needle),
                kindGroup("视图", NodeKind.VIEW, views, needle));
        return root;
    }


    /** 一个分组节点（表 / 视图）。 */
    private TreeItem<DemoNode> kindGroup(String label, NodeKind kind,
                                        List<SchemaObject> objects, String needle) {
        TreeItem<DemoNode> group = new TreeItem<>(
                new DemoNode(label, NodeKind.GROUP, kind == NodeKind.TABLE ? "▤" : "◇"));
        group.setExpanded(true);
        for (SchemaObject object : objects) {
            if (matches(object.name(), needle)) {
                group.getChildren().add(new TreeItem<>(
                        new DemoNode(object.name(), kind, object.kind().icon(), object.schema())));
            }
        }
        return group;
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
    }

    private void refreshFavorites() {
        favoriteBox.getChildren().clear();
        if (starred.isEmpty()) {
            Label empty = new Label("右键对象 → 收藏");
            empty.getStyleClass().add("adb-muted");
            empty.setPadding(new Insets(2, 4, 2, 4));
            favoriteBox.getChildren().add(empty);
            return;
        }
        for (String name : starred) {
            Button button = new Button("★  " + name);
            button.getStyleClass().add("adb-favorite");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setOnAction(event -> openGrid(name));
            favoriteBox.getChildren().add(button);
        }
    }

    private void toggleStar(String name) {
        if (!starred.remove(name)) {
            starred.add(name);
        }
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
                    actions.menuItem("object.diagnostics", "诊断"));
            case CONNECTION -> menu.getItems().addAll(
                    actions.menuItem("new-connection", "编辑连接"),
                    actions.menuItem("object.refresh-objects", "刷新对象"),
                    new SeparatorMenuItem(),
                    actions.menuItem("object.new-query", "新建查询"));
            case GROUP -> menu.getItems().add(
                    actions.menuItem("object.refresh-objects", "刷新对象"));
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
        switch (action) {
            case OPEN_DATA -> {
                if (isObject) {
                    openGrid(node.name());
                } else {
                    status.accept("只有表和视图能打开数据");
                }
            }
            case OPEN_STRUCTURE -> {
                if (isObject) {
                    openStructure(node.name());
                } else {
                    status.accept("只有表和视图有结构页");
                }
            }
            case NEW_QUERY -> openQuery(isObject ? node.name() : null);
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
        }
    }

    /** 按当前选中节点刷新对象动作的 enabled 状态。 */
    private void updateObjectActionStates() {
        DemoNode node = selectedNode;
        boolean isObject = node != null
                && (node.kind() == NodeKind.TABLE || node.kind() == NodeKind.VIEW);
        boolean isConnection = node != null && node.kind() == NodeKind.CONNECTION;
        setObjectActionEnabled("object.open-data", isObject);
        setObjectActionEnabled("object.open-structure", isObject);
        setObjectActionEnabled("object.new-query", isObject || isConnection);
        setObjectActionEnabled("object.favorite", isObject);
        setObjectActionEnabled("object.copy-name", isObject);
        setObjectActionEnabled("object.copy-select", isObject);
        setObjectActionEnabled("object.diagnostics", isObject);
        setObjectActionEnabled("object.refresh-objects", node != null);
    }

    private void setObjectActionEnabled(String id, boolean enabled) {
        actions.find(id).ifPresent(action -> action.setEnabled(enabled));
    }

    private void openGrid(String name) {
        openTab.accept(new TabItem("grid:" + connectionId + ":" + name, name, TabKind.GRID,
                name, connectionId));
        status.accept("打开表：" + name);
    }

    private void openStructure(String name) {
        openTab.accept(new TabItem("structure:" + connectionId + ":" + name, name,
                TabKind.STRUCTURE, name, connectionId));
        status.accept("打开结构：" + name);
    }

    private void openQuery(String name) {
        String title = name == null ? "未命名查询" : "查询 · " + name;
        openTab.accept(new TabItem("query:" + connectionId + ":" + System.nanoTime(), title,
                TabKind.QUERY, null, connectionId));
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
        CONNECTION, GROUP, TABLE, VIEW
    }

    /**
     * 树节点。
     *
     * @param schema 所属 schema（表/视图节点用；其它节点为空串）
     */
    private record DemoNode(String name, NodeKind kind, String icon, String schema) {

        private DemoNode(String name, NodeKind kind, String icon) {
            this(name, kind, icon, "");
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

            Label icon = new Label(item.icon());
            icon.getStyleClass().add("adb-tree__icon");

            Label name = new Label(item.name());
            name.getStyleClass().add("adb-tree__name");

            HBox box = new HBox(7, icon, name);
            box.setAlignment(Pos.CENTER_LEFT);

            if ((item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW)
                    && starred.contains(item.name())) {
                Label star = new Label("★");
                star.setAccessibleText("收藏");
                star.getStyleClass().add("adb-tree__star");
                box.getChildren().add(star);
            }

            switch (item.kind()) {
                case GROUP -> getStyleClass().add("adb-tree-cell--group");
                case CONNECTION -> getStyleClass().add("adb-tree-cell--connection");
                default -> getStyleClass().add("adb-tree-cell--object");
            }

            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2
                        && (item.kind() == NodeKind.TABLE || item.kind() == NodeKind.VIEW)) {
                    openGrid(item.name());
                    event.consume();
                }
            });

            setText(null);
            setGraphic(box);
        }
    }
}
