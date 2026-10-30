package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.shape.SVGPath;
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
        // ⭐ E9：收藏为空时给一句「怎么收藏」的引导，而不是空白列表。
        list.setPlaceholder(EmptyState.of(Icons.Icon.STAR, I18n.t("fav.empty.title"),
                I18n.t("fav.empty.body")));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                // ⭐ 收藏项用矢量实心星 + 文字。
                setText(item);
                SVGPath star = Icons.of(Icons.Icon.STAR_FILLED, Icons.SIZE_BADGE);
                star.setAccessibleText(I18n.t("fav.accessible"));
                setGraphic(star);
                setGraphicTextGap(6);
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

        Button open = new Button(I18n.t("sidebar.menuOpen"));
        open.setId("favorites-open");
        open.getStyleClass().addAll("adb-button", "adb-button--primary");
        open.setOnAction(event -> openSelected());

        Button close = new Button(I18n.t("tab.close"));
        close.setId("favorites-close");
        close.getStyleClass().addAll("adb-button", "adb-button--ghost");
        close.setOnAction(event -> close());

        // Navicat 口径（§6.7）：关闭在左、主动作在最右。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, close, open);
        actions.setAlignment(Pos.CENTER_RIGHT);

        content.getChildren().addAll(summary, list, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle(I18n.t("category.favorites"));
        dialog.setHeaderText(I18n.t("fav.header"));
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

    /** 供门禁断言弹窗内容（例如空态是否真的在弹窗里）。 */
    Dialog<Void> dialog() {
        return dialog;
    }

    private void refresh() {
        list.getItems().setAll(names);
        summary.setText(names.isEmpty()
                ? I18n.t("fav.emptyHint")
                : I18n.t("fav.summary", names.size()));
    }

    private void openSelected() {
        String name = list.getSelectionModel().getSelectedItem();
        if (name == null) {
            status.accept(I18n.t("fav.pickFirst"));
            return;
        }
        onOpen.accept(name);
        close();
    }
}
