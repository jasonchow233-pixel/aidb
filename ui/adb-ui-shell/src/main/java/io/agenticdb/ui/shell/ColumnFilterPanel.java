package io.agenticdb.ui.shell;

import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.DataSqlBuilder;
import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.introspect.FilterOperator;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.RowPage;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.controlsfx.control.PopOver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 列头值筛选面板。
 *
 * <p>候选值和计数由 SQL 聚合得到；计数只受「其他列」的筛选影响，
 * 因此连续筛选时不会出现「列表里全是 0」的假候选。
 */
final class ColumnFilterPanel extends VBox {

    private static final int MAX_CANDIDATES = 1000;

    record ValueCount(Object value, long count) {
    }

    private final DbSession session;
    private final String tableName;
    private final ColumnDef column;
    private final DataQuery query;
    private final Executor dbExecutor;
    private final Consumer<FilterCondition> onApply;
    private final Runnable onClear;

    private final ObservableList<ValueCount> allValues = FXCollections.observableArrayList();
    private final FilteredList<ValueCount> visibleValues = new FilteredList<>(allValues);
    private final Set<Object> selected = new LinkedHashSet<>();
    private final ListView<ValueCount> valueList = new ListView<>(visibleValues);
    private final TextField search = new TextField();
    private final TextField exact = new TextField();
    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final VBox highCardinality = new VBox(6);
    private final Button apply = new Button("应用");
    private final Button clear = new Button("清除");
    private final Button retry = new Button("重试");
    private final PopOver popover = new PopOver();

    private boolean highCardinalityMode;
    private Task<List<ValueCount>> inFlight;

    ColumnFilterPanel(DbSession session, String tableName, ColumnDef column, DataQuery query,
                      Executor dbExecutor, Consumer<FilterCondition> onApply, Runnable onClear) {
        this.session = Objects.requireNonNull(session, "session");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.column = Objects.requireNonNull(column, "column");
        this.query = Objects.requireNonNull(query, "query");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor");
        this.onApply = Objects.requireNonNull(onApply, "onApply");
        this.onClear = Objects.requireNonNull(onClear, "onClear");

        getStyleClass().add("adb-filter-panel");
        setPrefWidth(320);
        setMaxWidth(340);
        seedSelection();
        buildUi();
        configurePopover();
        load();
    }

    void show(Node anchor) {
        popover.show(anchor);
    }

    void hide() {
        popover.hide();
    }

    private void configurePopover() {
        popover.setContentNode(this);
        popover.setTitle("筛选 · " + column.name());
        popover.setHeaderAlwaysVisible(true);
        popover.setCloseButtonEnabled(true);
        popover.setAnimated(false);
        popover.setArrowLocation(PopOver.ArrowLocation.TOP_RIGHT);
        popover.setAutoHide(true);
        popover.setHideOnEscape(true);
    }

    private void seedSelection() {
        FilterCondition existing = query.filters().stream()
                .filter(filter -> filter.column().equalsIgnoreCase(column.name()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            return;
        }
        if (existing.operator() == FilterOperator.IN
                || existing.operator() == FilterOperator.IS_NULL) {
            selected.addAll(existing.values());
            if (existing.operator() == FilterOperator.IS_NULL) {
                selected.add(null);
            }
        } else if (existing.operator() == FilterOperator.EQ && !existing.values().isEmpty()) {
            Object value = existing.values().getFirst();
            selected.add(value);
            if (value != null) {
                exact.setText(String.valueOf(value));
            }
        }
    }

    private void buildUi() {
        Label hint = new Label("候选值来自当前表；计数已排除本列筛选");
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);

        search.setPromptText("搜索候选值…");
        search.getStyleClass().add("adb-filter-panel__search");
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            String needle = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            visibleValues.setPredicate(item -> needle.isEmpty()
                    || CellValueParser.display(item.value()).toLowerCase(Locale.ROOT).contains(needle));
        });

        valueList.getStyleClass().add("adb-filter-panel__list");
        valueList.setPrefHeight(260);
        valueList.setCellFactory(view -> new ValueCell());
        valueList.setPlaceholder(new Label("没有候选值"));

        spinner.setPrefSize(22, 22);
        spinner.getStyleClass().add("adb-filter-panel__spinner");

        status.getStyleClass().add("adb-muted");
        status.setWrapText(true);

        Button all = new Button("全选");
        all.getStyleClass().addAll("adb-button", "adb-button--ghost");
        all.setOnAction(event -> {
            selected.clear();
            for (ValueCount value : visibleValues) {
                selected.add(value.value());
            }
            valueList.refresh();
        });

        Button none = new Button("取消选择");
        none.getStyleClass().addAll("adb-button", "adb-button--ghost");
        none.setOnAction(event -> {
            selected.clear();
            valueList.refresh();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        apply.getStyleClass().addAll("adb-button", "adb-button--primary");
        apply.setOnAction(event -> applySelection());

        clear.getStyleClass().addAll("adb-button", "adb-button--ghost");
        clear.setOnAction(event -> {
            onClear.run();
            popover.hide();
        });

        HBox actions = new HBox(6, all, none, spacer, clear, apply);
        actions.setAlignment(Pos.CENTER_LEFT);

        retry.getStyleClass().addAll("adb-button", "adb-button--ghost");
        retry.setOnAction(event -> load());
        retry.setVisible(false);
        retry.setManaged(false);

        Label highTitle = new Label("候选值超过 " + MAX_CANDIDATES + " 个");
        highTitle.getStyleClass().add("adb-section-title");
        Label highHint = new Label("该列基数过高，已切换为精确匹配。");
        highHint.getStyleClass().add("adb-muted");
        highHint.setWrapText(true);
        exact.setPromptText("输入精确值");
        highCardinality.getChildren().addAll(highTitle, highHint, exact);
        highCardinality.getStyleClass().add("adb-filter-panel__high-cardinality");
        highCardinality.setVisible(false);
        highCardinality.setManaged(false);

        HBox loading = new HBox(8, spinner, status);
        loading.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(hint, search, loading, valueList, highCardinality, actions, retry);
    }

    private void load() {
        if (inFlight != null) {
            inFlight.cancel(true);
        }
        setLoading(true);
        status.setText("正在读取候选值…");
        DataQuery cascading = query.withoutFiltersFor(column.name());

        Task<List<ValueCount>> task = new Task<>() {
            @Override
            protected List<ValueCount> call() throws Exception {
                DataSqlBuilder.Sql sql = DataSqlBuilder.distinctValues(
                        session, tableName, column.name(), cascading, MAX_CANDIDATES + 1);
                RowPage page = session.query(sql.text(), MAX_CANDIDATES + 1, sql.params());
                List<ValueCount> values = new ArrayList<>(page.rows().size());
                for (var row : page.rows()) {
                    Object value = row.get("adb_value");
                    long count = row.get("adb_count") instanceof Number number
                            ? number.longValue() : 0;
                    values.add(new ValueCount(value, count));
                }
                return values;
            }
        };

        task.setOnSucceeded(event -> {
            if (task != inFlight) {
                return;
            }
            inFlight = null;
            setLoading(false);
            List<ValueCount> values = task.getValue();
            highCardinalityMode = values.size() > MAX_CANDIDATES;
            allValues.setAll(highCardinalityMode ? List.of() : values);
            highCardinality.setVisible(highCardinalityMode);
            highCardinality.setManaged(highCardinalityMode);
            valueList.setVisible(!highCardinalityMode);
            valueList.setManaged(!highCardinalityMode);
            search.setVisible(!highCardinalityMode);
            search.setManaged(!highCardinalityMode);
            status.setText(highCardinalityMode
                    ? "已切换为精确匹配" : "共 " + values.size() + " 个候选值");
        });

        task.setOnFailed(event -> {
            if (task != inFlight) {
                return;
            }
            inFlight = null;
            setLoading(false);
            Throwable error = task.getException();
            status.setText("读取候选值失败：" + (error == null ? "未知错误" : error.getMessage()));
            retry.setVisible(true);
            retry.setManaged(true);
        });

        inFlight = task;
        try {
            dbExecutor.execute(task);
        } catch (RuntimeException e) {
            inFlight = null;
            setLoading(false);
            status.setText("读取候选值失败：" + e.getMessage());
        }
    }

    private void setLoading(boolean loading) {
        spinner.setVisible(loading);
        spinner.setManaged(loading);
        apply.setDisable(loading);
        allValues.clear();
        valueList.setDisable(loading);
        retry.setVisible(false);
        retry.setManaged(false);
    }

    private void applySelection() {
        if (highCardinalityMode) {
            String text = exact.getText();
            if (text == null || text.isEmpty()) {
                onClear.run();
                popover.hide();
                return;
            }
            try {
                Object parsed = CellValueParser.parse(column, text);
                onApply.accept(FilterCondition.of(column.name(), FilterOperator.EQ, parsed));
                popover.hide();
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
            }
            return;
        }

        if (selected.isEmpty()) {
            onClear.run();
        } else {
            onApply.accept(FilterCondition.in(column.name(), new ArrayList<>(selected)));
        }
        popover.hide();
    }

    private final class ValueCell extends ListCell<ValueCount> {
        private final CheckBox check = new CheckBox();
        private final Label count = new Label();
        private final HBox box = new HBox(8, check, count);

        private ValueCell() {
            getStyleClass().add("adb-filter-panel__value-cell");
            box.setAlignment(Pos.CENTER_LEFT);
            check.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(check, Priority.ALWAYS);
            count.getStyleClass().add("adb-muted");
            setGraphic(box);
        }

        @Override
        protected void updateItem(ValueCount item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            check.setText(CellValueParser.display(item.value()));
            check.setSelected(selected.contains(item.value()));
            check.setOnAction(event -> {
                if (check.isSelected()) {
                    selected.add(item.value());
                } else {
                    selected.remove(item.value());
                }
            });
            count.setText(String.format(Locale.ROOT, "%,d", item.count()));
            setGraphic(box);
        }
    }
}
