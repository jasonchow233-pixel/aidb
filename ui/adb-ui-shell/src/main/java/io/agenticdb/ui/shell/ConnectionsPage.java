package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.RowHeights;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 连接管理页：搜索、收藏、打开 / 编辑 / 复制 / 删除，全部操作经回调交给 AppShell。
 *
 * <p>⭐ <b>用表格而不是列表</b>（Navicat 形态）：
 * 以前每个连接占一行，名称与目标挤在两行文字里 —— 用户要点开每个连接才知道连的什么。
 * 现在拆成 <b>名称 / 类型 / 主机 / 端口 / 数据库 / 用户 / 状态</b> 七列，
 * 每列独立、**可排序、可拖列宽**，一眼扫完。
 */
public final class ConnectionsPage extends VBox implements AutoCloseable {

    private final ObservableList<ConnectionProfile> profiles;
    private final FilteredList<ConnectionProfile> visible;
    private final TableView<ConnectionProfile> table = new TableView<>();
    /** 密度档位 → 行高（不写死）。 */
    private int rowHeight = RowHeights.of("normal");
    private final TextField search = new TextField();
    private final CheckBox favoritesOnly = new CheckBox("只看收藏");
    private final Label status = new Label("—");
    private final Button open = new Button("打开");
    private final Button edit = new Button("编辑");
    private final Button duplicate = new Button("复制");
    private final Button delete = new Button("删除");
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, "刷新");

    private Predicate<ConnectionProfile> deleteConfirmation = this::confirmDelete;
    private Consumer<ConnectionProfile> onOpen;
    private Consumer<ConnectionProfile> onEdit;
    private Consumer<ConnectionProfile> onDuplicate;
    private Consumer<ConnectionProfile> onDelete;
    private Runnable onNew;
    private Predicate<ConnectionProfile> connected = profile -> false;

    public ConnectionsPage(ObservableList<ConnectionProfile> profiles,
                           Runnable onNew,
                           Consumer<ConnectionProfile> onOpen,
                           Consumer<ConnectionProfile> onEdit,
                           Consumer<ConnectionProfile> onDuplicate,
                           Consumer<ConnectionProfile> onDelete,
                           Predicate<ConnectionProfile> connected,
                           DataGridPage.Feedback feedback) {
        this.profiles = profiles;
        this.visible = new FilteredList<>(profiles, profile -> true);
        this.onNew = onNew == null ? () -> { } : onNew;
        this.onOpen = onOpen == null ? profile -> { } : onOpen;
        this.onEdit = onEdit == null ? profile -> { } : onEdit;
        this.onDuplicate = onDuplicate == null ? profile -> { } : onDuplicate;
        this.onDelete = onDelete == null ? profile -> { } : onDelete;
        this.connected = connected == null ? profile -> false : connected;

        getStyleClass().addAll("adb-page", "adb-connections");
        buildUi();
        bind();
        refresh();
    }

    private void buildUi() {
        Label title = new Label("连接管理");
        title.getStyleClass().add("adb-page__title");
        Label badge = new Label("连接");
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Label context = new Label("口令保存于系统钥匙串 / Secret Service");
        context.getStyleClass().add("adb-muted");

        HBox header = new HBox(10, title, badge, headerSpacer, context);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        Button newConnection = IconButtons.of(Icons.Icon.ADD, "新建连接");
        newConnection.setId("connections-new");
        newConnection.getStyleClass().add("adb-button--primary");
        newConnection.setOnAction(event -> onNew.run());

        open.getStyleClass().add("adb-button");
        open.setId("connections-open");
        open.setOnAction(event -> selected().ifPresent(onOpen));
        edit.getStyleClass().add("adb-button");
        edit.setId("connections-edit");
        edit.setOnAction(event -> selected().ifPresent(onEdit));
        duplicate.getStyleClass().add("adb-button");
        duplicate.setId("connections-duplicate");
        duplicate.setOnAction(event -> selected().ifPresent(onDuplicate));
        delete.getStyleClass().addAll("adb-button", "adb-button--danger");
        delete.setId("connections-delete");
        delete.setOnAction(event -> selected().ifPresent(this::delete));
        refresh.getStyleClass().add("adb-button");
        refresh.setId("connections-refresh");
        refresh.setOnAction(event -> refresh());

        HBox toolbar = new HBox(6, newConnection, open, edit, duplicate, delete, refresh);
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        search.setId("connections-search");
        search.getStyleClass().add("adb-connections__search");
        search.setPromptText("搜索名称、主机、数据库");
        favoritesOnly.getStyleClass().add("adb-connections__favorites");
        HBox filter = new HBox(10, search, favoritesOnly);
        filter.setAlignment(Pos.CENTER_LEFT);

        buildColumns();

        table.setId("connections-table");
        table.getStyleClass().addAll("adb-grid", "adb-connections__table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("没有匹配的连接"));
        table.setFixedCellSize(rowHeight);
        VBox.setVgrow(table, Priority.ALWAYS);

        // 双击一行 = 打开（Navicat 的习惯）。
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                selected().ifPresent(onOpen);
            }
        });

        status.getStyleClass().add("adb-muted");
        VBox body = new VBox(8, toolbar, filter, table, status);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(table, Priority.ALWAYS);

        getChildren().addAll(header, body);
    }

    /** 七列：名称 / 类型 / 主机 / 端口 / 数据库 / 用户 / 状态。 */
    private void buildColumns() {
        TableColumn<ConnectionProfile, String> name = column("名称", "name", 200);
        name.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().name()));
        // 收藏标记放进「名称」列的前面 —— 一列一个信息，不另开一列。
        name.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                if (getTableRow() != null && getTableRow().getItem() != null
                        && getTableRow().getItem().favorite()) {
                    SVGPath star = Icons.of(Icons.Icon.STAR_FILLED, 12);
                    star.setAccessibleText("已收藏");
                    setGraphic(star);
                    setGraphicTextGap(4);
                } else {
                    setGraphic(null);
                }
            }
        });

        TableColumn<ConnectionProfile, String> kind = column("类型", "kind", 90);
        kind.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().kindLabel()));

        TableColumn<ConnectionProfile, String> host = column("主机", "host", 180);
        host.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().host()));

        TableColumn<ConnectionProfile, Number> port = column("端口", "port", 80);
        port.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(
                data.getValue().port()));

        TableColumn<ConnectionProfile, String> database = column("数据库", "database", 180);
        database.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().database()));

        TableColumn<ConnectionProfile, String> user = column("用户", "user", 130);
        user.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().username()));

        TableColumn<ConnectionProfile, String> state = column("状态", "state", 100);
        state.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                connected.test(data.getValue()) ? "已连接" : "—"));

        table.getColumns().addAll(name, kind, host, port, database, user, state);
    }

    private static <T> TableColumn<ConnectionProfile, T> column(String title, String id,
                                                                  double width) {
        TableColumn<ConnectionProfile, T> column = new TableColumn<>(title);
        column.setId("connections-column-" + id);
        column.setPrefWidth(width);
        column.setSortable(true);
        return column;
    }

    private void bind() {
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        favoritesOnly.selectedProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        profiles.addListener((javafx.collections.ListChangeListener<ConnectionProfile>) change -> {
            applyFilter();
            updateButtons();
        });
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons());
        open.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        edit.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        duplicate.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
        delete.disableProperty().bind(Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));
    }

    public void refresh() {
        applyFilter();
        status.setText("共 " + profiles.size() + " 个连接 · 已显示 " + visible.size() + " 个");
        updateButtons();
    }

    private void applyFilter() {
        String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        visible.setPredicate(profile -> {
            if (favoritesOnly.isSelected() && !profile.favorite()) {
                return false;
            }
            if (needle.isEmpty()) {
                return true;
            }
            return (profile.name() + " " + profile.host() + " " + profile.database() + " "
                    + profile.username()).toLowerCase().contains(needle);
        });
        table.setItems(visible);
    }

    private void updateButtons() {
        // 打开/编辑/复制/删除的可用性由 selection binding 控制；
        // 这里不重复刷新，避免与 refresh() 互相调用。
    }

    /** 密度档位。 */
    public void setDensity(String density) {
        rowHeight = RowHeights.of(density);
        table.setFixedCellSize(rowHeight);
    }

    /** 连接表格（供门禁断言列数与排序）。 */
    TableView<ConnectionProfile> table() {
        return table;
    }

    private Optional<ConnectionProfile> selected() {
        return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
    }

    public void setDeleteConfirmation(Predicate<ConnectionProfile> confirmation) {
        this.deleteConfirmation = confirmation == null ? profile -> true : confirmation;
    }

    private void delete(ConnectionProfile profile) {
        if (!deleteConfirmation.test(profile)) {
            return;
        }
        onDelete.accept(profile);
        refresh();
    }

    private boolean confirmDelete(ConnectionProfile profile) {
        // ⛔ 同 AppShell：破坏性按钮不能是默认按钮（回车即删），统一走 DestructiveConfirm。
        DestructiveConfirm confirm = DestructiveConfirm.of(
                "删除连接",
                "删除 " + profile.name() + "？",
                "这会移除连接元数据与钥匙串口令，不会删除数据库中的数据。",
                "删除");
        Alert alert = confirm.alert();
        if (getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            alert.getDialogPane().getStyleClass().add("adb-dialog");
            alert.initOwner(getScene().getWindow());
        }
        return confirm.confirmed();
    }

    public void focusSearch() {
        search.requestFocus();
    }

    @Override
    public void close() {
    }
}
