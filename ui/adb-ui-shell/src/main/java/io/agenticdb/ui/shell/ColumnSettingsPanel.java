package io.agenticdb.ui.shell;

import io.agenticdb.model.ColumnDef;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * ⭐ 列设置面板（§6.5）。
 *
 * <p>「列」按钮不再是一串勾选项 —— 表有 40 列时那个菜单没法用。
 * 改成一个**带搜索的弹层**：
 * <ul>
 *   <li>顶部一个搜索框，按列名过滤（40 列时唯一可用的办法）；</li>
 *   <li>下面是勾选列表，控制显示 / 隐藏；</li>
 *   <li>底部两个动作：全部显示 / 恢复默认布局。</li>
 * </ul>
 *
 * <p>宽度调整不在这里 —— 拖列头边缘就是最直接的办法（也是 Navicat 的做法）。
 */
final class ColumnSettingsPanel extends VBox {

    private final ObservableList<ColumnDef> all = FXCollections.observableArrayList();
    private final ObservableList<ColumnDef> visible = FXCollections.observableArrayList();
    private final ListView<ColumnDef> list = new ListView<>();
    private final TextField search = new TextField();
    private final Label count = new Label();
    private final CheckBox selection = new CheckBox();

    ColumnSettingsPanel(List<ColumnDef> columns,
                        java.util.function.BiConsumer<ColumnDef, Boolean> onToggle,
                        java.util.function.Function<ColumnDef, Boolean> isVisible,
                        Runnable onShowAll,
                        Runnable onReset) {
        getStyleClass().add("adb-column-panel");
        setPadding(new Insets(8));

        all.setAll(columns == null ? List.of() : columns);

        search.setId("columns-search");
        search.setPromptText("搜索列名");
        search.getStyleClass().add("adb-sidebar__search");
        search.setPrefHeight(io.agenticdb.ui.Tokens.H_INPUT_LIGHT);
        search.textProperty().addListener((observable, previous, value) -> refresh());

        list.setId("columns-list");
        list.getStyleClass().add("adb-column-panel__list");
        list.setItems(visible);
        list.setCellFactory(view -> new ColumnCell(onToggle, isVisible));
        list.setPrefHeight(240);
        list.setMinHeight(120);
        VBox.setVgrow(list, Priority.ALWAYS);

        count.setId("columns-count");
        count.getStyleClass().add("adb-muted");

        Button showAll = new Button("全部显示");
        showAll.setId("columns-show-all");
        showAll.getStyleClass().addAll("adb-button", "adb-button--ghost");
        showAll.setOnAction(event -> onShowAll.run());

        Button reset = new Button("恢复默认");
        reset.setId("columns-reset");
        reset.getStyleClass().addAll("adb-button", "adb-button--ghost");
        reset.setOnAction(event -> onReset.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(8, showAll, reset, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(search, list, count, actions);
        refresh();
    }

    /** 按搜索词刷新列表（并同步计数）。 */
    void refresh() {
        String needle = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (ColumnDef definition : all) {
            if (needle.isEmpty() || definition.name().toLowerCase(Locale.ROOT).contains(needle)) {
                visible.add(definition);
            }
        }
        count.setText(visible.size() + " / " + all.size() + " 列");
    }

    /** 恢复搜索框的初始状态（打开弹层时调用）。 */
    void resetSearch() {
        search.clear();
        refresh();
    }

    /** 一行 = 列名 + 勾选框（勾选态 = 该列当前可见）。 */
    private static final class ColumnCell extends ListCell<ColumnDef> {
        private final CheckBox box = new CheckBox();
        private final java.util.function.BiConsumer<ColumnDef, Boolean> onToggle;
        private final java.util.function.Function<ColumnDef, Boolean> visible;

        ColumnCell(java.util.function.BiConsumer<ColumnDef, Boolean> onToggle,
                   java.util.function.Function<ColumnDef, Boolean> visible) {
            this.onToggle = onToggle;
            this.visible = visible;
            box.setId("columns-toggle");
            box.setOnAction(event -> {
                if (getItem() != null) {
                    onToggle.accept(getItem(), box.isSelected());
                }
            });
            setText(null);
        }

        @Override
        protected void updateItem(ColumnDef item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.name());
            box.setSelected(Boolean.TRUE.equals(visible.apply(item)));
            setGraphic(box);
        }
    }
}
