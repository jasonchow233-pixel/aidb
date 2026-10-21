package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 已保存查询页：列出命名查询，可打开 / 重命名 / 删除。
 *
 * <p>侧栏的「已保存查询」以前是空壳页 —— 现在它真的能用。
 *
 * <p>⛔ 按钮在内容区（架构文档 §7.1 的 Dialog 按钮陷阱）。
 */
final class SavedQueriesPage extends VBox {

    private final SavedQueries saved;
    private final Consumer<String> onOpen;
    private final Consumer<String> status;

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(10);
    private final ListView<SavedQueries.Entry> list = new ListView<>();
    private final TextField nameField = new TextField();
    private final Label summary = new Label();

    SavedQueriesPage(SavedQueries saved, Consumer<String> onOpen, Consumer<String> status) {
        this.saved = saved;
        this.onOpen = onOpen == null ? sql -> { } : onOpen;
        this.status = status == null ? message -> { } : status;

        list.setId("saved-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(SavedQueries.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item.name());
                setTooltip(new javafx.scene.control.Tooltip(item.sql()));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        nameField.setId("saved-name");
        nameField.setPromptText("新名字");

        Button rename = new Button("重命名");
        rename.setId("saved-rename");
        rename.getStyleClass().addAll("adb-button");
        rename.setOnAction(event -> renameSelected());

        Button delete = new Button("删除");
        delete.setId("saved-delete");
        delete.getStyleClass().addAll("adb-button", "adb-button--danger");
        delete.setOnAction(event -> deleteSelected());

        Button open = new Button("打开");
        open.setId("saved-open");
        open.getStyleClass().addAll("adb-button", "adb-button--primary");
        open.setOnAction(event -> openSelected());

        Button close = new Button("关闭");
        close.setId("saved-close");
        close.getStyleClass().addAll("adb-button", "adb-button--ghost");
        close.setOnAction(event -> close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, open, rename, delete, close, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        summary.setId("saved-summary");
        summary.getStyleClass().add("adb-muted");

        content.getChildren().addAll(summary, list, nameField, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle("已保存查询");
        dialog.setHeaderText("命名查询，随时打开");
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
        refresh();
    }

    void show(javafx.stage.Window owner) {
        refresh();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showAndWait();
    }

    void close() {
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }

    VBox content() {
        return content;
    }

    /** 重新读一遍（保存 / 删除后调用）。 */
    void refresh() {
        list.getItems().setAll(saved.all());
        summary.setText("共 " + list.getItems().size() + " 条已保存查询");
    }

    private void openSelected() {
        SavedQueries.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept("先选一条查询");
            return;
        }
        onOpen.accept(entry.sql());
        close();
    }

    private void renameSelected() {
        SavedQueries.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept("先选一条查询");
            return;
        }
        String name = nameField.getText();
        if (name == null || name.isBlank()) {
            status.accept("请先填新名字");
            return;
        }
        saved.rename(entry.name(), name);
        nameField.clear();
        refresh();
        status.accept("已重命名为 " + name.trim());
    }

    private void deleteSelected() {
        SavedQueries.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept("先选一条查询");
            return;
        }
        saved.delete(entry.name());
        refresh();
        status.accept("已删除 " + entry.name());
    }
}
