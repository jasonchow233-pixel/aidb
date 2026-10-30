package io.agenticdb.ui.shell;

import io.agenticdb.introspect.DataQuery;
import io.agenticdb.ui.I18n;
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
import io.agenticdb.ui.Icons;
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

    /**
     * 一次候选值查询的结果。
     *
     * @param values   取到的候选值
     * @param truncated 结果被硬上限截断（还有更多候选值没取到）
     */
    record Candidates(List<ValueCount> values, boolean truncated) {
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
    private final Button apply = new Button(I18n.t("details.action.apply"));
    private final Button clear = new Button(I18n.t("filter.clear"));
    private final Button retry = new Button(I18n.t("grid.toolbar.retry"));
    private final PopOver popover = new PopOver();

    private boolean highCardinalityMode;
    private Task<Candidates> inFlight;

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
        // ⭐ R34 收官：320/340 是按中文量的（「全选 / 取消 / 清空 / 应用」四个词很短）。
        // 默认英文后底部四颗加spacer 要 ~410px，p2-filter 实拍抓到全被截成
        // 「Select …  Deselect…  Cl…  Ap…」，浮窗标题也被裁成「…ter · name」。
        setPrefWidth(420);
        setMaxWidth(460);
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
        popover.setTitle(I18n.t("filter.title", column.name()));
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
        Label hint = new Label(I18n.t("filter.hint"));
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);

        search.setPromptText(I18n.t("filter.searchPrompt"));
        search.getStyleClass().add("adb-filter-panel__search");
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            String needle = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            visibleValues.setPredicate(item -> needle.isEmpty()
                    || CellValueParser.display(item.value()).toLowerCase(Locale.ROOT).contains(needle));
        });

        valueList.getStyleClass().add("adb-filter-panel__list");
        valueList.setPrefHeight(260);
        valueList.setCellFactory(view -> new ValueCell());
        // U24：候选值读空是有原因的（整列 NULL / 还没加载完），一句话说清，别让人猜。
        valueList.setPlaceholder(EmptyState.of(Icons.Icon.FILTER, I18n.t("filter.empty.title"),
                I18n.t("filter.empty.body")));

        spinner.setPrefSize(22, 22);
        spinner.getStyleClass().add("adb-filter-panel__spinner");

        status.getStyleClass().add("adb-muted");
        status.setWrapText(true);

        Button all = new Button(I18n.t("action.table-list.select-all"));
        all.getStyleClass().addAll("adb-button", "adb-button--ghost");
        all.setOnAction(event -> {
            selected.clear();
            for (ValueCount value : visibleValues) {
                selected.add(value.value());
            }
            valueList.refresh();
        });

        Button none = new Button(I18n.t("filter.selectNone"));
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

        Label highTitle = new Label(I18n.t("filter.highCardinality", MAX_CANDIDATES));
        highTitle.getStyleClass().add("adb-section-title");
        Label highHint = new Label(I18n.t("filter.highCardinalityHint"));
        highHint.getStyleClass().add("adb-muted");
        highHint.setWrapText(true);
        exact.setPromptText(I18n.t("filter.exactPrompt"));
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
        status.setText(I18n.t("filter.loading"));
        DataQuery cascading = query.withoutFiltersFor(column.name());

        Task<Candidates> task = new Task<>() {
            @Override
            protected Candidates call() throws Exception {
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
                return new Candidates(values, page.rowsTruncated());
            }
        };

        task.setOnSucceeded(event -> {
            if (task != inFlight) {
                return;
            }
            inFlight = null;
            setLoading(false);
            Candidates candidates = task.getValue();
            List<ValueCount> values = candidates.values();
            // ⭐ 高基数判定必须看<b>截断标记</b>，不能只看「取回来的条数」。
            //    以前请求 MAX_CANDIDATES+1 = 1001 行，但 JdbcSession 会把它夹到
            //    单页硬上限 1000 —— 于是 values.size() 永远 ≤ 1000，
            //    「> 1000」这个条件永远不成立，超过 1000 个候选值的列
            //    会被当成普通列，悄悄只显示前 1000 个值。
            highCardinalityMode = candidates.truncated() || values.size() > MAX_CANDIDATES;
            allValues.setAll(highCardinalityMode ? List.of() : values);
            highCardinality.setVisible(highCardinalityMode);
            highCardinality.setManaged(highCardinalityMode);
            valueList.setVisible(!highCardinalityMode);
            valueList.setManaged(!highCardinalityMode);
            search.setVisible(!highCardinalityMode);
            search.setManaged(!highCardinalityMode);
            status.setText(highCardinalityMode
                    ? I18n.t("filter.switchedExact") : I18n.t("filter.count", values.size()));
        });

        task.setOnFailed(event -> {
            if (task != inFlight) {
                return;
            }
            inFlight = null;
            setLoading(false);
            Throwable error = task.getException();
            status.setText(I18n.t("filter.readFailed",
                    error == null ? I18n.t("sections.unknownError") : error.getMessage()));
            retry.setVisible(true);
            retry.setManaged(true);
        });

        inFlight = task;
        try {
            dbExecutor.execute(task);
        } catch (RuntimeException e) {
            inFlight = null;
            setLoading(false);
            status.setText(I18n.t("filter.readFailed", e.getMessage()));
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
