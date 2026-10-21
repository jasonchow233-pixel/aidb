package io.agenticdb.ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 查询历史页。
 *
 * <p>⭐ 侧栏的「查询历史」入口以前开的是<b>空壳</b>（一句「该工具只保留入口」），
 * 而真正的历史功能其实早就存在（{@link QueryHistory}：落 {@code java.util.prefs}、
 * 去重、最近 50 条），只是侧栏按钮开错了页面。
 * 现在这个页面把那条真实功能接上了：列表、载入到新查询、清空。
 */
public final class HistoryPage extends VBox {

    private final QueryHistory history;
    private final Consumer<String> onUse;
    private final ListView<String> list = new ListView<>();
    private final Label empty = new Label("还没有执行过 SQL");
    private final Button use = new Button("载入到新查询");
    private final Button clear = new Button("清空历史");

    public HistoryPage(QueryHistory history, Consumer<String> onUse) {
        this.history = history;
        this.onUse = onUse;
        setSpacing(10);
        setPadding(new Insets(0));
        getStyleClass().add("adb-page__body");

        Label hint = new Label("最近执行过的 SQL（最多 50 条 · 落本地偏好，重启还在）");
        hint.getStyleClass().add("adb-muted");

        list.setId("history-list");
        list.getStyleClass().add("adb-history__list");
        list.setPlaceholder(empty);
        VBox.setVgrow(list, Priority.ALWAYS);

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                if (isEmpty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item.length() > 160 ? item.substring(0, 160) + "…" : item);
                setTooltip(new Tooltip(item));
            }
        });

        use.setId("history-use");
        use.getStyleClass().addAll("adb-button", "adb-button--primary");
        use.setOnAction(event -> {
            String sql = list.getSelectionModel().getSelectedItem();
            if (sql != null && onUse != null) {
                onUse.accept(sql);
            }
        });

        clear.setId("history-clear");
        clear.getStyleClass().addAll("adb-button", "adb-button--ghost");
        clear.setOnAction(event -> {
            history.clear();
            refresh();
        });

        list.getSelectionModel().selectedItemProperty().addListener((obs, old, item) ->
                use.setDisable(item == null));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, use, clear, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(hint, list, actions);
        refresh();
    }

    /** 重新读一次历史（每次打开标签页都调）。 */
    public void refresh() {
        List<String> items = history.recent();
        list.getItems().setAll(items);
        empty.setVisible(items.isEmpty());
        use.setDisable(true);
    }
}
