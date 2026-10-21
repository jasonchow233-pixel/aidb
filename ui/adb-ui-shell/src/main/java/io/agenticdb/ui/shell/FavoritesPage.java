package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 收藏夹面板：列出收藏的对象，双击打开。
 *
 * <p>侧栏的「收藏」以前只在内存里，退出即丢；现在收藏**落盘**，
 * 菜单栏也有一个顶级的「收藏夹」入口（Navicat 就是这个结构）。
 */
final class FavoritesPage extends VBox {

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(10);
    private final ListView<String> list = new ListView<>();
    private final Label summary = new Label();

    private final List<String> names;
    private final Consumer<String> onOpen;
    private final Consumer<String> status;

    FavoritesPage(List<String> names, Consumer<String> onOpen, Consumer<String> status) {
        this.names = names == null ? List.of() : List.copyOf(names);
        this.onOpen = onOpen == null ? name -> { } : onOpen;
        this.status = status == null ? message -> { } : status;

        list.setId("favorites-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText("★  " + item);
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelected();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        summary.setId("favorites-summary");
        summary.getStyleClass().add("adb-muted");

        Button open = new Button("打开");
        open.setId("favorites-open");
        open.getStyleClass().addAll("adb-button", "adb-button--primary");
        open.setOnAction(event -> openSelected());

        Button close = new Button("关闭");
        close.setId("favorites-close");
        close.getStyleClass().addAll("adb-button", "adb-button--ghost");
        close.setOnAction(event -> close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, open, close, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(summary, list, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle("收藏夹");
        dialog.setHeaderText("收藏的对象");
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

    private void refresh() {
        list.getItems().setAll(names);
        summary.setText(names.isEmpty()
                ? "还没有收藏 —— 在对象树里右键 → 收藏"
                : "共 " + names.size() + " 个收藏");
    }

    private void openSelected() {
        String name = list.getSelectionModel().getSelectedItem();
        if (name == null) {
            status.accept("先选一个收藏");
            return;
        }
        onOpen.accept(name);
        close();
    }
}
