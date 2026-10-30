package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
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
        // ⚠️ ListView 的默认首选高度很大 —— 弹窗会被撑成一张巨大的空框。
        list.setFixedCellSize(30);
        list.setPrefHeight(240);
        list.setMinHeight(120);
        // 与历史页同一套列表外观（否则弹窗里是一块默认皮肤的白框）。
        list.getStyleClass().add("adb-history__list");
        list.setPlaceholder(new javafx.scene.control.Label(I18n.t("saved.placeholder")));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(SavedQueries.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                // ⭐ Q6：显示类型（查询 / 片段）—— 用户要知道自己点开的是哪一种。
                setText(item.name() + "  · " + item.kind().label());
                setTooltip(new javafx.scene.control.Tooltip(item.sql()));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        nameField.setId("saved-name");
        nameField.setPromptText(I18n.t("saved.namePrompt"));

        Button rename = new Button(I18n.t("structure.field.rename"));
        rename.setId("saved-rename");
        rename.getStyleClass().addAll("adb-button");
        rename.setOnAction(event -> renameSelected());

        Button delete = new Button(I18n.t("common.delete"));
        delete.setId("saved-delete");
        delete.getStyleClass().addAll("adb-button", "adb-button--danger");
        delete.setOnAction(event -> deleteSelected());

        Button open = new Button(I18n.t("sidebar.menuOpen"));
        open.setId("saved-open");
        open.getStyleClass().addAll("adb-button", "adb-button--primary");
        open.setOnAction(event -> openSelected());

        Button close = new Button(I18n.t("tab.close"));
        close.setId("saved-close");
        close.getStyleClass().addAll("adb-button", "adb-button--ghost");
        close.setOnAction(event -> close());

        // Navicat 口径（§6.7）：关闭在左、主动作在最右，中间命令保持相对次序。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, close, rename, delete, open);
        actions.setAlignment(Pos.CENTER_RIGHT);

        summary.setId("saved-summary");
        summary.getStyleClass().add("adb-muted");

        content.getChildren().addAll(summary, list, nameField, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle(I18n.t("action.saved-queries"));
        dialog.setHeaderText(I18n.t("saved.header"));
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
        refresh();
    }

    void show(javafx.stage.Window owner) {
        refresh();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogStyles.apply(dialog, owner);
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
        summary.setText(I18n.t("saved.summary", list.getItems().size()));
    }

    private void openSelected() {
        SavedQueries.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept(I18n.t("saved.pickFirst"));
            return;
        }
        onOpen.accept(entry.sql());
        close();
    }

    private void renameSelected() {
        SavedQueries.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept(I18n.t("saved.pickFirst"));
            return;
        }
        String name = nameField.getText();
        if (name == null || name.isBlank()) {
            status.accept(I18n.t("saved.needName"));
            return;
        }
        saved.rename(entry.name(), name);
        nameField.clear();
        refresh();
        status.accept(I18n.t("saved.renamed", name.trim()));
    }

    private void deleteSelected() {
        SavedQueries.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) {
            status.accept(I18n.t("saved.pickFirst"));
            return;
        }
        saved.delete(entry.name());
        refresh();
        status.accept(I18n.t("saved.deleted", entry.name()));
    }
}
