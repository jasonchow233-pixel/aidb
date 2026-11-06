package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * 已保存查询的对象清单页（N24）。
 *
 * <p>Navicat 的 Queries 不是弹窗，而是一张和表清单同形的四列清单：
 * 名称 / 类型 / 修改日期 / SQL 预览，工具条三键「设计 / 新建 / 删除查询」。
 * 双击一条 = 打开查询页并载入（与左侧 Queries 分组的双击同一动作）。
 *
 * <p>⛔ 删除是破坏性动作：必须确认，且默认键是「取消」。
 */
final class SavedQueriesPage extends VBox {

    private static final DateTimeFormatter UPDATED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final SavedQueries saved;
    private final Consumer<SavedQueries.Entry> onDesign;
    private final Runnable onNew;
    private final Runnable onChanged;
    private final Consumer<String> status;

    private final TableView<SavedQueries.Entry> table = new TableView<>();
    private final ObservableList<SavedQueries.Entry> rows = FXCollections.observableArrayList();
    private final Label summary = new Label();
    private final Button design;
    private final Button delete;

    SavedQueriesPage(SavedQueries saved, Consumer<SavedQueries.Entry> onDesign,
                     Runnable onNew, Runnable onChanged, Consumer<String> status) {
        this.saved = saved;
        this.onDesign = onDesign == null ? entry -> { } : onDesign;
        this.onNew = onNew == null ? () -> { } : onNew;
        this.onChanged = onChanged == null ? () -> { } : onChanged;
        this.status = status == null ? message -> { } : status;

        getStyleClass().add("adb-page");
        setId("saved-queries-page");

        design = IconButtons.labeled(new Button(I18n.t("saved.design")),
                Icons.Icon.QUERY, I18n.t("saved.design"), I18n.t("saved.design"));
        design.setId("saved-design");
        design.getStyleClass().addAll("adb-button", "adb-button--ghost");
        design.setOnAction(event -> designSelected());

        Button create = IconButtons.labeled(new Button(I18n.t("saved.new")),
                Icons.Icon.ADD, I18n.t("saved.new"), I18n.t("saved.new"));
        create.setId("saved-new");
        create.getStyleClass().addAll("adb-button", "adb-button--ghost");
        create.setOnAction(event -> onNew.run());

        delete = IconButtons.labeled(new Button(I18n.t("saved.delete")),
                Icons.Icon.DELETE, I18n.t("saved.delete"), I18n.t("saved.delete"));
        delete.setId("saved-delete");
        delete.getStyleClass().addAll("adb-button", "adb-button--danger");
        delete.setOnAction(event -> deleteSelected());

        HBox group = new HBox(3, design, create, delete);
        group.setAlignment(Pos.CENTER_LEFT);
        group.getStyleClass().add("adb-toolbar__group");

        summary.setId("saved-summary");
        summary.getStyleClass().add("adb-muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(6, group, spacer, summary);
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        buildTable();
        getChildren().addAll(toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        refresh();
    }

    private void buildTable() {
        table.setId("saved-queries-list");
        table.getStyleClass().add("adb-table-list");
        table.setEditable(false);
        table.setFixedCellSize(30);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(EmptyState.of(Icons.Icon.QUERY,
                I18n.t("saved.placeholder"), I18n.t("saved.placeholderDetail"),
                I18n.t("saved.new"), onNew));

        TableColumn<SavedQueries.Entry, String> name =
                new TableColumn<>(I18n.t("column.name"));
        name.setId("saved-column-name");
        name.setPrefWidth(240);
        name.setMinWidth(140);
        name.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
        name.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label label = new Label(value);
                HBox box = new HBox(6, Icons.of(Icons.Icon.QUERY, Icons.SIZE_LABEL), label);
                box.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(box);
            }
        });

        TableColumn<SavedQueries.Entry, String> type =
                new TableColumn<>(I18n.t("saved.column.type"));
        type.setId("saved-column-type");
        type.setPrefWidth(110);
        type.setMinWidth(90);
        type.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().kind().label()));

        TableColumn<SavedQueries.Entry, String> updated =
                new TableColumn<>(I18n.t("column.updated"));
        updated.setId("saved-column-updated");
        updated.setPrefWidth(160);
        updated.setMinWidth(140);
        updated.setCellValueFactory(cell -> new SimpleStringProperty(
                UPDATED.format(Instant.ofEpochMilli(cell.getValue().savedAt()))));

        TableColumn<SavedQueries.Entry, String> sql =
                new TableColumn<>(I18n.t("saved.column.sql"));
        sql.setId("saved-column-sql");
        sql.setPrefWidth(420);
        sql.setMinWidth(160);
        sql.setCellValueFactory(cell ->
                new SimpleStringProperty(oneLine(cell.getValue().sql())));
        sql.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(value);
                SavedQueries.Entry entry = getTableRow() == null ? null : getTableRow().getItem();
                setTooltip(entry == null ? null : new Tooltip(entry.sql()));
            }
        });

        table.getColumns().addAll(name, type, updated, sql);
        table.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, current) -> updateActions());

        table.setRowFactory(view -> {
            TableRow<SavedQueries.Entry> row = new TableRow<>() {
                @Override
                protected void updateItem(SavedQueries.Entry item, boolean empty) {
                    super.updateItem(item, empty);
                    setId(empty || item == null ? "" : "saved-row-" + item.name());
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onDesign.accept(row.getItem());
                }
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                designSelected();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE
                    || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelected();
                event.consume();
            }
        });
        table.setItems(rows);
    }

    /** 重新读一遍（保存 / 删除后调用，也用于打开标签时刷新）。 */
    void refresh() {
        String selected = table.getSelectionModel().getSelectedItem() == null
                ? null : table.getSelectionModel().getSelectedItem().name();
        rows.setAll(saved.all());
        if (selected != null) {
            rows.stream()
                    .filter(entry -> entry.name().equalsIgnoreCase(selected))
                    .findFirst()
                    .ifPresent(entry -> table.getSelectionModel().select(entry));
        }
        summary.setText(I18n.tPlural("saved.summary", rows.size(), rows.size()));
        updateActions();
    }

    /** 截图 / 验收摆态：选中第一行，让「设计 / 删除查询」处于可用态。 */
    void selectFirst() {
        if (!rows.isEmpty()) {
            table.getSelectionModel().selectFirst();
        }
    }

    private void updateActions() {
        boolean selected = table.getSelectionModel().getSelectedItem() != null;
        design.setDisable(!selected);
        delete.setDisable(!selected);
    }

    private void designSelected() {
        SavedQueries.Entry entry = table.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept(I18n.t("saved.pickFirst"));
            return;
        }
        onDesign.accept(entry);
    }

    private void deleteSelected() {
        SavedQueries.Entry entry = table.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept(I18n.t("saved.pickFirst"));
            return;
        }
        if (!confirmDelete(entry)) {
            return;
        }
        saved.delete(entry.name());
        onChanged.run();
        refresh();
        status.accept(I18n.t("saved.deleted", entry.name()));
    }

    /** 删除确认：默认键必须是「取消」—— 误按回车不能删掉一条查询。 */
    Dialog<ButtonType> buildDeleteDialog(SavedQueries.Entry entry) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("saved.delete"));
        dialog.setHeaderText(I18n.t("saved.deleteHeader", entry.name()));
        ButtonType remove = new ButtonType(I18n.t("saved.delete"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(remove, cancel);
        dialog.getDialogPane().setContent(new Label(I18n.t("saved.deleteDetail")));
        dialog.setResultConverter(type -> type);
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
        dialog.setOnShown(event ->
                DestructiveConfirm.pinCancelAsDefault(dialog.getDialogPane(), cancel, remove));
        return dialog;
    }

    private boolean confirmDelete(SavedQueries.Entry entry) {
        Dialog<ButtonType> dialog = buildDeleteDialog(entry);
        ButtonType remove = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> I18n.t("saved.delete").equals(type.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "delete button missing from the saved-query delete dialog"));
        return dialog.showAndWait().filter(remove::equals).isPresent();
    }

    private static String oneLine(String sql) {
        String value = sql == null ? "" : sql.strip();
        return value.replaceAll("\\s+", " ");
    }
}
