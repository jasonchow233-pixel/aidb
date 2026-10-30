package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;

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
    /** ⭐ P2-3：列表显示「时间 · SQL」；悬停给完整 SQL + 连接 / 表。 */
    private final ListView<QueryHistory.Entry> list = new ListView<>();
    /** ⭐ E8：空态用统一的 {@link EmptyState}（图标 + 标题 + 说明），不再是一行字。 */
    private final EmptyState empty = EmptyState.of(Icons.Icon.HISTORY, I18n.t("history.emptyTitle"),
            I18n.t("history.emptyBody"));
    private final Button use = new Button(I18n.t("history.loadNew"));
    private final Button clear = new Button(I18n.t("query.clearHistory"));

    public HistoryPage(QueryHistory history, Consumer<String> onUse) {
        this.history = history;
        this.onUse = onUse;
        setSpacing(10);
        setPadding(new Insets(0));
        getStyleClass().add("adb-page__body");

        Label hint = new Label(I18n.t("history.hint"));
        hint.getStyleClass().add("adb-muted");

        list.setId("history-list");
        list.getStyleClass().add("adb-history__list");
        VBox.setVgrow(list, Priority.ALWAYS);

        // ⭐ 空态**不放进 ListView 的 placeholder** ——
        //    placeholder 会被垂直居中在「撑满整页」的列表框里，
        //    渲染出来是一张巨大的白卡片（实测截图），而不是一个空态。
        //    改成：空的时候收起列表，单独显示一张尺寸合理的空态卡片。
        empty.getStyleClass().add("adb-empty");
        empty.setMaxWidth(520);
        empty.setVisible(false);
        empty.setManaged(false);

        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(QueryHistory.Entry item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                if (isEmpty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item.summary());
                setTooltip(new Tooltip(item.detail()));
            }
        });

        use.setId("history-use");
        use.getStyleClass().addAll("adb-button", "adb-button--primary");
        use.setOnAction(event -> {
            QueryHistory.Entry entry = list.getSelectionModel().getSelectedItem();
            if (entry != null && onUse != null) {
                onUse.accept(entry.sql());
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

        getChildren().addAll(hint, list, empty, actions);
        refresh();
    }

    /** 重新读一次历史（每次打开标签页都调）。 */
    public void refresh() {
        List<QueryHistory.Entry> items = history.entries();
        list.getItems().setAll(items);
        boolean none = items.isEmpty();
        list.setVisible(!none);
        list.setManaged(!none);
        empty.setVisible(none);
        empty.setManaged(none);
        use.setDisable(true);
    }
}
