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
    private Runnable onRefreshObjects = () -> { };

    private final Set<String> starred = new LinkedHashSet<>();
    private final VBox expanded = new VBox(8);
    private final VBox rail = new VBox(6);
    private final VBox favoriteBox = new VBox(4);
    private final TextField search = new TextField();
    private final TreeView<DemoNode> tree = new TreeView<>();
    private final ComboBox<ConnectionProfile> connectionPicker = new ComboBox<>();

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

        buildExpanded();
        buildRail();
        getChildren().add(expanded);
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

    private void setObjects(List<SchemaObject> objects, boolean refresh) {
        List<SchemaObject> safe = objects == null ? List.of() : List.copyOf(objects);
        tables = safe.stream()
                .filter(object -> object.kind() == ObjectKind.TABLE)
                .toList();
        views = safe.stream()
                .filter(object -> object.kind() == ObjectKind.VIEW)
                .toList();
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

        HBox connectionBar = new HBox(6, manageConnections, connectionPicker);
        HBox.setHgrow(connectionPicker, Priority.ALWAYS);
        connectionBar.getStyleClass().add("adb-sidebar__connection");

        VBox favorites = section("收藏", favoriteBox);
        VBox connections = section("连接", tree);
        VBox.setVgrow(tree, Priority.ALWAYS);

        tree.getStyleClass().add("adb-object-tree");
        tree.setShowRoot(true);
        tree.setCellFactory(view -> new DemoCell());
        tree.setRoot(buildTreeModel(""));
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
                navButton("◷  查询历史", () -> openTool("查询历史")),
                navButton("★  已保存查询", () -> openTool("已保存查询")));

        VBox tools = section("工具",
                navButton("◆  诊断", () -> openTool("诊断")),
                navButton("⇅  导入 / 导出", () -> openTool("导入 / 导出")),
                navButton("⚙  设置", () -> openTab.accept(new TabItem("settings", "设置", TabKind.SETTINGS))));

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

    private Button navButton(String text, Runnable handler) {
        Button button = new Button(text);
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

        Button expand = railButton("☰", "展开侧栏 (⌘\\)", () -> actions.run("sidebar.toggle"));
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

        Label env = new Label("DEV");
        env.getStyleClass().add("adb-rail__env");

        rail.getChildren().addAll(expand, connection, favorites, users, find, tools, spacer, env);
    }

    private Button railButton(String text, String tooltip, Runnable handler) {
        Button button = new Button(text);
        button.getStyleClass().add("adb-rail__button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> handler.run());
        return button;
    }

    // ─────────────────────────── 对象树 ───────────────────────────

    private TreeItem<DemoNode> buildTreeModel(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        TreeItem<DemoNode> root = new TreeItem<>(
                new DemoNode(connectionName, NodeKind.CONNECTION, "◈"));
        root.setExpanded(true);

        TreeItem<DemoNode> tableGroup = new TreeItem<>(
                new DemoNode("表", NodeKind.GROUP, "▤"));
        tableGroup.setExpanded(true);
        for (SchemaObject table : tables) {
            if (matches(table.name(), needle)) {
                tableGroup.getChildren().add(new TreeItem<>(
                        new DemoNode(table.name(), NodeKind.TABLE, table.kind().icon())));
            }
        }

        TreeItem<DemoNode> viewGroup = new TreeItem<>(
                new DemoNode("视图", NodeKind.GROUP, "◇"));
        viewGroup.setExpanded(true);
        for (SchemaObject view : views) {
            if (matches(view.name(), needle)) {
                viewGroup.getChildren().add(new TreeItem<>(
                        new DemoNode(view.name(), NodeKind.VIEW, view.kind().icon())));
            }
        }

        if (!tableGroup.getChildren().isEmpty()) {
            root.getChildren().add(tableGroup);
        }
        if (!viewGroup.getChildren().isEmpty()) {
            root.getChildren().add(viewGroup);
        }
        return root;
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

    private ContextMenu menuFor(DemoNode node) {
        ContextMenu menu = new ContextMenu();
        switch (node.kind()) {
            case TABLE, VIEW -> {
                MenuItem open = new MenuItem("打开数据");
                open.setOnAction(event -> openGrid(node.name()));
                MenuItem structure = new MenuItem("打开结构");
                structure.setOnAction(event -> openStructure(node.name()));
                MenuItem query = new MenuItem("新建查询");
                query.setOnAction(event -> openQuery(node.name()));

                MenuItem star = new MenuItem(
                        starred.contains(node.name()) ? "取消收藏" : "收藏");
                star.setOnAction(event -> toggleStar(node.name()));

                MenuItem copyName = new MenuItem("复制名称");
                copyName.setOnAction(event -> copy(node.name()));
                MenuItem copySelect = new MenuItem("复制 SELECT");
                copySelect.setOnAction(event -> copy(
                        "SELECT * FROM \"" + node.name() + "\" LIMIT 200;"));

                MenuItem diagnose = new MenuItem("诊断");
                diagnose.setOnAction(event -> openTool("诊断 · " + node.name()));

                menu.getItems().addAll(open, structure, query,
                        new SeparatorMenuItem(), star, copyName, copySelect,
                        new SeparatorMenuItem(), diagnose);
            }
            case CONNECTION -> {
                MenuItem edit = new MenuItem("编辑连接");
                edit.setOnAction(event -> actions.run("new-connection"));
                MenuItem refresh = new MenuItem("刷新对象");
                refresh.setOnAction(event -> {
                    onRefreshObjects.run();
                    status.accept("已刷新 " + connectionName);
                });
                MenuItem query = new MenuItem("新建查询");
                query.setOnAction(event -> openQuery(null));
                menu.getItems().addAll(edit, refresh, new SeparatorMenuItem(), query);
            }
            case GROUP -> {
                MenuItem refresh = new MenuItem("刷新对象");
                refresh.setOnAction(event -> {
                    onRefreshObjects.run();
                    status.accept("已刷新 " + connectionName);
                });
                menu.getItems().add(refresh);
            }
            default -> {
                MenuItem open = new MenuItem("打开");
                open.setOnAction(event -> openGrid(node.name()));
                menu.getItems().add(open);
            }
        }
        return menu;
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

    private record DemoNode(String name, NodeKind kind, String icon) {
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
