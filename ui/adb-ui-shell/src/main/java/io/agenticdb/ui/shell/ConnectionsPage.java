package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** 连接管理页：搜索、收藏、打开/编辑/复制/删除，全部操作经回调交给 AppShell。 */
public final class ConnectionsPage extends VBox implements AutoCloseable {

    private final ObservableList<ConnectionProfile> profiles;
    private final FilteredList<ConnectionProfile> visible;
    private final ListView<ConnectionProfile> list = new ListView<>();
    private final TextField search = new TextField();
    private final CheckBox favoritesOnly = new CheckBox("只看收藏");
    private final Label status = new Label("—");
    private final Button open = new Button("打开");
    private final Button edit = new Button("编辑");
    private final Button duplicate = new Button("复制");
    private final Button delete = new Button("删除");
    private final Button refresh = new Button("↻ 刷新");

    private Predicate<ConnectionProfile> deleteConfirmation = this::confirmDelete;
    private Consumer<ConnectionProfile> onOpen;
    private Consumer<ConnectionProfile> onEdit;
    private Consumer<ConnectionProfile> onDuplicate;
    private Consumer<ConnectionProfile> onDelete;
    private Runnable onNew;
    private java.util.function.Predicate<ConnectionProfile> connected = profile -> false;

    public ConnectionsPage(ObservableList<ConnectionProfile> profiles,
                           Runnable onNew,
                           Consumer<ConnectionProfile> onOpen,
                           Consumer<ConnectionProfile> onEdit,
                           Consumer<ConnectionProfile> onDuplicate,
                           Consumer<ConnectionProfile> onDelete,
                           java.util.function.Predicate<ConnectionProfile> connected,
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

        Button newConnection = new Button("＋ 新建连接");
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

        list.setId("connections-list");
        list.setCellFactory(view -> new ConnectionCell());
        list.setPlaceholder(new Label("没有匹配的连接"));
        VBox.setVgrow(list, Priority.ALWAYS);

        status.getStyleClass().add("adb-muted");
        VBox body = new VBox(8, toolbar, filter, list, status);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().addAll(header, body);
        updateButtons();
    }

    private void bind() {
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        favoritesOnly.selectedProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        profiles.addListener((javafx.collections.ListChangeListener<ConnectionProfile>) change -> {
            applyFilter();
            updateButtons();
        });
        list.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> updateButtons());
        open.disableProperty().bind(Bindings.isEmpty(list.getSelectionModel().getSelectedItems()));
        edit.disableProperty().bind(Bindings.isEmpty(list.getSelectionModel().getSelectedItems()));
        duplicate.disableProperty().bind(Bindings.isEmpty(list.getSelectionModel().getSelectedItems()));
        delete.disableProperty().bind(Bindings.isEmpty(list.getSelectionModel().getSelectedItems()));
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
        list.setItems(visible);
    }

    private void updateButtons() {
        // 打开/编辑/复制/删除的可用性由 selection binding 控制；
        // 这里不重复刷新，避免与 refresh() 互相调用。
    }

    private java.util.Optional<ConnectionProfile> selected() {
        return java.util.Optional.ofNullable(list.getSelectionModel().getSelectedItem());
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

    private final class ConnectionCell extends ListCell<ConnectionProfile> {
        @Override
        protected void updateItem(ConnectionProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(item.name() + (item.favorite() ? "   ★" : ""));
            name.getStyleClass().add("adb-connections__name");
            Label target = new Label(item.kindLabel() + " · " + item.displayTarget()
                    + (connected.test(item) ? " · 已连接" : ""));
            target.getStyleClass().add("adb-connections__target");
            VBox box = new VBox(2, name, target);
            setGraphic(box);
            setText(null);
        }
    }
}
