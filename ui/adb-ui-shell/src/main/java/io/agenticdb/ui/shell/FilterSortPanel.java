package io.agenticdb.ui.shell;

import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.introspect.FilterOperator;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.introspect.SortSpec;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * ⭐ N31：网格内嵌的 Filter & Sort 生成器（Navicat §1.9 形态）。
 *
 * <p>与旧 {@code ConditionDialog} 的区别是「不弹窗、不即输即查」：条件先在面板里
 * 编辑成草稿，只有按 {@code Apply Filter & Sort} 才生成新的 {@link DataQuery}。
 * 草稿与已应用条件不一致时右侧亮 {@code Criteria Edited}。
 *
 * <p>模型约束照实表达：{@link DataQuery} 只有**平铺 AND**，所以行间连接词固定
 * {@code and}；{@code [Ω]} 加条件组（OR）没有底层支持，点击给如实说明页，不摆假括号。
 * 列头漏斗的「候选值多选」（IN）仍走 {@code ColumnFilterPanel}；这类条件在面板里
 * 原样保留、不参与行编辑，避免把 IN 伪装成单个等值条件。
 */
final class FilterSortPanel extends VBox {

    private final TableSchema schema;
    private final Consumer<DataQuery> onApply;
    private final Runnable onAddGroup;

    private final VBox builderBox = new VBox(6);
    private final VBox rowsBox = new VBox(6);
    private final HBox sortsBox = new HBox(6);
    private final VBox textBox = new VBox(6);
    private final TextArea textArea = new TextArea();
    private final Label dirtyLabel = new Label(I18n.t("filter.panel.edited"));
    private final Label errorLabel = new Label();
    private final Button addCondition;
    private final Button addSort;
    private final ToggleGroup modes = new ToggleGroup();

    private final List<Row> rows = new ArrayList<>();
    /** 行编辑器表达不了的 IN 条件（来自列头值筛选），Apply 时原样带过去。 */
    private final List<FilterCondition> preserved = new ArrayList<>();
    private final List<SortSpec> sorts = new ArrayList<>();
    private boolean dirty;
    private int rowSeq;

    FilterSortPanel(TableSchema schema, Consumer<DataQuery> onApply, Runnable onAddGroup) {
        this.schema = schema;
        this.onApply = onApply;
        this.onAddGroup = onAddGroup;

        setId("data-filter-panel");
        getStyleClass().add("adb-filter-builder");
        setPadding(new Insets(8, 12, 8, 12));
        setSpacing(8);
        setAccessibleText(I18n.t("filter.panel.aria"));

        Label title = new Label(I18n.t("filter.panel.title"));
        title.getStyleClass().add("adb-filter-builder__title");
        Button sortAsc = iconButton(Icons.Icon.MOVE_UP, I18n.t("filter.tip.sortAsc"));
        sortAsc.setId("data-filter-sort-asc");
        sortAsc.setOnAction(event -> quickSort(true));
        Button sortDesc = iconButton(Icons.Icon.MOVE_DOWN, I18n.t("filter.tip.sortDesc"));
        sortDesc.setId("data-filter-sort-desc");
        sortDesc.setOnAction(event -> quickSort(false));
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        RadioButton builderMode = new RadioButton(I18n.t("filter.panel.builder"));
        builderMode.setId("data-filter-mode-builder");
        builderMode.setToggleGroup(modes);
        builderMode.setSelected(true);
        RadioButton textMode = new RadioButton(I18n.t("filter.panel.text"));
        textMode.setId("data-filter-mode-text");
        textMode.setToggleGroup(modes);
        builderMode.getStyleClass().add("adb-filter-builder__mode");
        textMode.getStyleClass().add("adb-filter-builder__mode");
        HBox modesBox = new HBox(8, builderMode, textMode);
        modesBox.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(8, title, sortAsc, sortDesc, headerSpacer, modesBox);
        header.setAlignment(Pos.CENTER_LEFT);

        rowsBox.setId("data-filter-rows");
        builderBox.setId("data-filter-builder");
        addCondition = new Button("+");
        addCondition.setId("data-filter-add");
        addCondition.setMnemonicParsing(false);
        addCondition.getStyleClass().addAll("adb-chip", "adb-filter-builder__add");
        addCondition.setTooltip(new Tooltip(I18n.t("filter.panel.addCondition")));
        addCondition.setAccessibleText(I18n.t("filter.panel.addCondition"));
        addCondition.setOnAction(event -> {
            if (rows.size() < DataQuery.MAX_FILTERS) {
                rows.add(newRow(null));
                renderRows();
                touch();
            }
        });
        Button addGroup = new Button("Ω");
        addGroup.setId("data-filter-add-group");
        addGroup.setMnemonicParsing(false);
        addGroup.getStyleClass().addAll("adb-chip", "adb-filter-builder__add");
        addGroup.setTooltip(new Tooltip(I18n.t("filter.panel.addGroup")));
        addGroup.setAccessibleText(I18n.t("filter.panel.addGroup"));
        addGroup.setOnAction(event -> this.onAddGroup.run());
        HBox rowActions = new HBox(6, addCondition, addGroup);
        rowActions.getStyleClass().add("adb-filter-builder__row-actions");
        // ⭐ D-61：Ω 的落点是 showCapabilityNotice（判据 = 落点函数）—— 与「添加条件」
        //    同形的假按钮必须点之前就说实话：环挂 rowActions，提示带「本版本未提供」。
        PendingMark.mark(rowActions, addGroup);
        builderBox.getChildren().addAll(rowsBox, rowActions);

        Label sortLabel = new Label(I18n.t("filter.panel.sortBy"));
        sortLabel.getStyleClass().add("adb-filter-builder__label");
        sortsBox.setId("data-filter-sorts");
        sortsBox.setAlignment(Pos.CENTER_LEFT);
        addSort = new Button("+");
        addSort.setId("data-filter-add-sort");
        addSort.setMnemonicParsing(false);
        addSort.getStyleClass().addAll("adb-chip", "adb-filter-builder__add");
        addSort.setTooltip(new Tooltip(I18n.t("filter.panel.addSort")));
        addSort.setAccessibleText(I18n.t("filter.panel.addSort"));
        addSort.setOnAction(event -> {
            SortSpec next = nextSort();
            if (next != null) {
                sorts.add(next);
                renderSorts();
                touch();
            }
        });
        HBox sortRow = new HBox(8, sortLabel, sortsBox, addSort);
        sortRow.setAlignment(Pos.CENTER_LEFT);
        sortRow.getStyleClass().add("adb-filter-builder__sort-row");
        builderBox.getChildren().add(sortRow);

        textArea.setId("data-filter-text");
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(3);
        textArea.getStyleClass().add("adb-filter-builder__text");
        Label textHint = new Label(I18n.t("filter.panel.textHint"));
        textHint.getStyleClass().add("adb-filter-builder__hint");
        textHint.setWrapText(true);
        textBox.getChildren().addAll(textArea, textHint);
        textBox.setVisible(false);
        textBox.setManaged(false);

        Button apply = new Button(I18n.t("filter.panel.apply"));
        apply.setId("data-filter-apply");
        apply.setMnemonicParsing(false);
        apply.getStyleClass().addAll("adb-button", "adb-button--primary");
        apply.setOnAction(event -> applyDraft());
        dirtyLabel.setId("data-filter-dirty");
        dirtyLabel.getStyleClass().add("adb-filter-builder__dirty");
        dirtyLabel.setVisible(false);
        dirtyLabel.setManaged(false);
        errorLabel.setId("data-filter-error");
        errorLabel.getStyleClass().add("adb-grid__state-text--error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, apply, footerSpacer, dirtyLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("adb-filter-builder__footer");

        modes.selectedToggleProperty().addListener((observable, was, now) -> {
            boolean text = textMode.isSelected();
            builderBox.setVisible(!text);
            builderBox.setManaged(!text);
            textBox.setVisible(text);
            textBox.setManaged(text);
            if (text) {
                textArea.setText(expression());
            }
        });

        getChildren().addAll(header, builderBox, textBox, errorLabel, footer);
        seed(DataQuery.empty());
    }

    /** 重新从已应用的查询播种草稿；脏草稿不会被覆盖（返回 false）。 */
    boolean adopt(DataQuery query) {
        if (dirty) {
            return false;
        }
        seed(query);
        return true;
    }

    /**
     * 截图 / 验收（R38 N31）：摆出一条完整草稿 —— 两条条件 + 一条排序 + 「Criteria Edited」。
     * 只摆不应用：Apply 的生效路径由 {@code FilterPanelAuditTest} 真点验收。
     */
    void poseForShots() {
        seed(DataQuery.empty());
        List<ColumnDef> columns = schema.columns();
        ColumnDef text = columns.stream()
                .filter(column -> column.type() == ColumnType.TEXT)
                .findFirst().orElse(columns.get(0));
        ColumnDef number = columns.stream()
                .filter(column -> column.type() == ColumnType.NUMBER)
                .findFirst().orElse(columns.get(columns.size() - 1));

        rows.clear();
        rows.add(rowFor(FilterCondition.of(text.name(), FilterOperator.CONTAINS, "a")));
        if (!number.name().equalsIgnoreCase(text.name())) {
            rows.add(rowFor(FilterCondition.of(number.name(), FilterOperator.GT, 0)));
        }
        sorts.clear();
        sorts.add(new SortSpec(number.name(), false));
        dirty = true;
        renderRows();
        renderSorts();
        showDirty(true);
    }

    void seed(DataQuery query) {
        rows.clear();
        preserved.clear();
        sorts.clear();
        sorts.addAll(query.sorts());
        for (FilterCondition filter : query.filters()) {
            if (filter.operator() == FilterOperator.IN) {
                preserved.add(filter);
            } else {
                Row row = rowFor(filter);
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            rows.add(newRow(firstColumn(null)));
        }
        dirty = false;
        setError(null);
        renderRows();
        renderSorts();
        showDirty(false);
        if (textBox.isVisible()) {
            textArea.setText(expression());
        }
    }

    boolean isDirty() {
        return dirty;
    }

    DataQuery draft() {
        try {
            return new DataQuery(buildFilters(), List.copyOf(sorts));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void applyDraft() {
        List<FilterCondition> filters;
        try {
            filters = buildFilters();
        } catch (IllegalArgumentException e) {
            setError(e.getMessage());
            return;
        }
        setError(null);
        dirty = false;
        showDirty(false);
        onApply.accept(new DataQuery(filters, List.copyOf(sorts)));
    }

    private List<FilterCondition> buildFilters() {
        List<FilterCondition> result = new ArrayList<>(preserved);
        Set<String> columns = new LinkedHashSet<>();
        for (Row row : rows) {
            if (!row.enabled.isSelected() || incomplete(row)) {
                continue;
            }
            FilterCondition condition = conditionOf(row);
            String key = condition.column().toLowerCase(Locale.ROOT);
            if (!columns.add(key)) {
                throw new IllegalArgumentException(
                        I18n.t("filter.panel.error.duplicate", condition.column()));
            }
            result.add(condition);
        }
        // 行条件与保留的 IN 条件同列时，以行的写法为准（与 DataQuery.withFilter 同语义）。
        List<FilterCondition> merged = new ArrayList<>();
        for (FilterCondition filter : result) {
            if (columns.contains(filter.column().toLowerCase(Locale.ROOT))
                    && filter.operator() == FilterOperator.IN) {
                continue;
            }
            merged.add(filter);
        }
        if (merged.size() > DataQuery.MAX_FILTERS) {
            throw new IllegalArgumentException(
                    I18n.t("filter.panel.error.limit", DataQuery.MAX_FILTERS));
        }
        return merged;
    }

    private FilterCondition conditionOf(Row row) {
        FilterOperator operator = row.operator.getValue();
        if (operator == null) {
            throw new IllegalArgumentException(I18n.t("cond.error.noOperator"));
        }
        ColumnDef column = column(row.column);
        if (!operator.acceptsValue()) {
            return operator == FilterOperator.IS_NULL
                    ? FilterCondition.isNull(column.name())
                    : FilterCondition.isNotNull(column.name());
        }
        String raw = row.value.getText() == null ? "" : row.value.getText().trim();
        if (raw.isEmpty() && requiresNonEmpty(column.type())) {
            throw new IllegalArgumentException(
                    I18n.t("cond.error.invalidValue", kindPrompt(column)));
        }
        Object first = CellValueParser.parse(column, raw);
        if (operator.acceptsSecondValue()) {
            String secondRaw = row.second.getText() == null ? "" : row.second.getText().trim();
            if (secondRaw.isEmpty() && requiresNonEmpty(column.type())) {
                throw new IllegalArgumentException(I18n.t("cond.error.noUpperBound"));
            }
            return FilterCondition.between(column.name(), first,
                    CellValueParser.parse(column, secondRaw));
        }
        return FilterCondition.of(column.name(), operator, first);
    }

    private Row rowFor(FilterCondition condition) {
        Row row = newRow(condition.column());
        row.operator.setValue(condition.operator());
        List<Object> values = condition.values();
        row.value.setText(values.isEmpty() ? "" : text(values.get(0)));
        if (values.size() > 1) {
            row.second.setText(text(values.get(1)));
        }
        updateValueFields(row);
        return row;
    }

    private Row newRow(String column) {
        Row row = new Row();
        row.column = column == null ? firstColumn(null) : column;

        row.enabled = new CheckBox();
        row.enabled.setSelected(true);
        row.enabled.setAccessibleText(I18n.t("filter.panel.enableCondition"));

        row.field = new Hyperlink(row.column);
        row.field.setId("data-filter-field-" + rowSeq++);
        row.field.getStyleClass().add("adb-filter-builder__field");
        row.field.setTooltip(new Tooltip(I18n.t("filter.tip.field")));
        row.field.setAccessibleText(I18n.t("filter.tip.field"));
        row.field.setOnAction(event -> showColumnMenu(row));

        row.operator = new ComboBox<>();
        row.operator.getItems().setAll(Arrays.stream(FilterOperator.values())
                .filter(operator -> operator != FilterOperator.IN)
                .toList());
        row.operator.setValue(FilterOperator.EQ);
        row.operator.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(FilterOperator operator) {
                return operator == null ? "" : operatorLabel(operator);
            }

            @Override
            public FilterOperator fromString(String text) {
                return null;
            }
        });
        row.operator.getStyleClass().add("adb-filter-builder__operator");
        row.operator.setMaxWidth(Double.MAX_VALUE);
        row.operator.setOnAction(event -> {
            updateValueFields(row);
            touch();
        });

        row.value = new TextField();
        row.value.setPromptText(I18n.t("filter.panel.valuePrompt"));
        row.value.getStyleClass().add("adb-filter-builder__value");
        row.value.setMinWidth(120);
        row.value.setPrefWidth(160);
        HBox.setHgrow(row.value, Priority.ALWAYS);
        row.value.textProperty().addListener(
                (observable, was, now) -> {
                    if (row.syncing) {
                        return;
                    }
                    touch();
                });

        Label between = new Label(I18n.t("filter.panel.and"));
        between.getStyleClass().add("adb-filter-builder__connector");
        between.visibleProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> row.operator.getValue() != null
                        && row.operator.getValue().acceptsSecondValue(),
                row.operator.valueProperty()));
        between.managedProperty().bind(between.visibleProperty());
        row.second = new TextField();
        row.second.setPromptText(I18n.t("filter.panel.valuePrompt"));
        row.second.getStyleClass().add("adb-filter-builder__value");
        row.second.setMinWidth(100);
        row.second.setPrefWidth(120);
        row.second.visibleProperty().bind(between.visibleProperty());
        row.second.managedProperty().bind(between.visibleProperty());
        row.second.textProperty().addListener(
                (observable, was, now) -> {
                    if (row.syncing) {
                        return;
                    }
                    touch();
                });

        Button remove = iconButton(Icons.Icon.CLOSE, I18n.t("filter.panel.removeCondition"));
        remove.getStyleClass().add("adb-filter-builder__remove");
        remove.setOnAction(event -> {
            rows.remove(row);
            renderRows();
            touch();
        });

        row.box = new HBox(6, row.enabled, row.field, row.operator,
                row.value, between, row.second, remove);
        row.box.setAlignment(Pos.CENTER_LEFT);
        row.box.getStyleClass().add("adb-filter-builder__row");

        row.enabled.selectedProperty().addListener((observable, was, now) -> {
            updateValueFields(row);
            touch();
        });
        for (Node control : List.of(row.enabled, row.field, row.operator,
                row.value, row.second, remove)) {
            control.focusedProperty().addListener((observable, was, now) ->
                    setActive(row, now));
        }
        return row;
    }

    private void showColumnMenu(Row row) {
        ContextMenu menu = new ContextMenu();
        Set<String> used = new LinkedHashSet<>();
        for (Row other : rows) {
            if (other != row && other.enabled.isSelected()) {
                used.add(other.column.toLowerCase(Locale.ROOT));
            }
        }
        for (ColumnDef column : schema.columns()) {
            MenuItem item = new MenuItem(column.name());
            item.setId("data-filter-column-" + column.name());
            item.setDisable(used.contains(column.name().toLowerCase(Locale.ROOT)));
            item.setOnAction(event -> {
                row.column = column.name();
                row.field.setText(column.name());
                updateValueFields(row);
                touch();
            });
            menu.getItems().add(item);
        }
        if (row.field.getScene() != null) {
            menu.show(row.field, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    private void renderRows() {
        rowsBox.getChildren().clear();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (index > 0) {
                Label and = new Label(I18n.t("filter.panel.and"));
                and.getStyleClass().add("adb-filter-builder__connector");
                rowsBox.getChildren().add(and);
            }
            row.box.getStyleClass().remove("adb-filter-builder__row--active");
            rowsBox.getChildren().add(row.box);
        }
        addCondition.setDisable(rows.size() >= DataQuery.MAX_FILTERS);
    }

    private void renderSorts() {
        sortsBox.getChildren().clear();
        for (int index = 0; index < sorts.size(); index++) {
            SortSpec sort = sorts.get(index);
            HBox chip = new HBox(4);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().addAll("adb-chip", "adb-filter-builder__sort-chip");

            MenuButton field = new MenuButton(sort.column());
            field.getStyleClass().add("adb-filter-builder__sort-field");
            field.setAccessibleText(I18n.t("filter.panel.changeColumn"));
            Set<String> usedByOthers = new LinkedHashSet<>();
            for (SortSpec other : sorts) {
                if (other != sort) {
                    usedByOthers.add(other.column().toLowerCase(Locale.ROOT));
                }
            }
            for (ColumnDef column : schema.columns()) {
                MenuItem item = new MenuItem(column.name());
                item.setDisable(usedByOthers.contains(column.name().toLowerCase(Locale.ROOT)));
                item.setOnAction(event -> {
                    int position = sorts.indexOf(sort);
                    if (position >= 0) {
                        sorts.set(position, new SortSpec(column.name(), sort.ascending()));
                        renderSorts();
                        touch();
                    }
                });
                field.getItems().add(item);
            }

            Button direction = iconButton(sort.ascending()
                            ? Icons.Icon.MOVE_UP : Icons.Icon.MOVE_DOWN,
                    sort.ascending() ? I18n.t("grid.head.sortAsc") : I18n.t("grid.head.sortDesc"));
            direction.getStyleClass().add("adb-filter-builder__sort-direction");
            direction.setOnAction(event -> {
                int position = sorts.indexOf(sort);
                if (position >= 0) {
                    sorts.set(position, sort.reversed());
                    renderSorts();
                    touch();
                }
            });

            Button remove = iconButton(Icons.Icon.CLOSE, I18n.t("filter.panel.removeSort"));
            remove.getStyleClass().add("adb-filter-builder__remove");
            remove.setOnAction(event -> {
                sorts.remove(sort);
                renderSorts();
                touch();
            });

            chip.getChildren().addAll(field, direction, remove);
            chip.setId("data-filter-sort-" + index);
            sortsBox.getChildren().add(chip);
        }
        addSort.setDisable(sorts.size() >= DataQuery.MAX_SORTS || nextSort() == null);
        if (sorts.isEmpty()) {
            Label empty = new Label(I18n.t("grid.overview.none"));
            empty.getStyleClass().add("adb-filter-builder__hint");
            sortsBox.getChildren().add(empty);
        }
    }

    private void quickSort(boolean ascending) {
        Row target = rows.stream()
                .filter(row -> row.enabled.isSelected())
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }
        SortSpec next = new SortSpec(target.column, ascending);
        int index = -1;
        for (int i = 0; i < sorts.size(); i++) {
            if (sorts.get(i).column().equalsIgnoreCase(next.column())) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            sorts.set(index, next);
        } else if (sorts.size() < DataQuery.MAX_SORTS) {
            sorts.add(next);
        } else {
            setError(I18n.t("filter.panel.error.sortLimit", DataQuery.MAX_SORTS));
            return;
        }
        setError(null);
        renderSorts();
        touch();
    }

    private SortSpec nextSort() {
        Set<String> used = new LinkedHashSet<>();
        for (SortSpec sort : sorts) {
            used.add(sort.column().toLowerCase(Locale.ROOT));
        }
        for (ColumnDef column : schema.columns()) {
            if (!used.contains(column.name().toLowerCase(Locale.ROOT))) {
                return new SortSpec(column.name(), true);
            }
        }
        return null;
    }

    private void updateValueFields(Row row) {
        FilterOperator operator = row.operator.getValue();
        boolean accepts = operator != null && operator.acceptsValue();
        boolean between = operator != null && operator.acceptsSecondValue();
        boolean enabled = row.enabled.isSelected();
        row.field.setDisable(!enabled);
        row.operator.setDisable(!enabled);
        row.syncing = true;
        try {
            row.value.setDisable(!enabled || !accepts);
            row.second.setDisable(!enabled || !between);
            if (!accepts) {
                row.value.clear();
                row.second.clear();
            } else if (!between) {
                row.second.clear();
            }
        } finally {
            row.syncing = false;
        }
    }

    private void touch() {
        dirty = true;
        showDirty(true);
        if (textBox.isVisible()) {
            textArea.setText(expression());
        }
    }

    private void showDirty(boolean visible) {
        dirtyLabel.setVisible(visible);
        dirtyLabel.setManaged(visible);
    }

    private void setError(String message) {
        errorLabel.setText(message == null ? "" : message);
        errorLabel.setVisible(message != null);
        errorLabel.setManaged(message != null);
    }

    private void setActive(Row row, boolean active) {
        if (active && !row.box.getStyleClass().contains("adb-filter-builder__row--active")) {
            row.box.getStyleClass().add("adb-filter-builder__row--active");
        } else if (!active) {
            row.box.getStyleClass().remove("adb-filter-builder__row--active");
        }
    }

    private String expression() {
        List<String> conditions = new ArrayList<>();
        for (FilterCondition filter : preserved) {
            conditions.add(filter.column() + " ∈ {" + filter.values().size() + "}");
        }
        for (Row row : rows) {
            if (!row.enabled.isSelected() || incomplete(row)) {
                continue;
            }
            try {
                FilterCondition condition = conditionOf(row);
                StringBuilder text = new StringBuilder();
                text.append(condition.column()).append(' ')
                        .append(operatorLabel(condition.operator()));
                if (condition.operator().acceptsValue()) {
                    text.append(' ').append(format(condition, 0));
                }
                if (condition.operator().acceptsSecondValue()) {
                    text.append(" AND ").append(format(condition, 1));
                }
                conditions.add(text.toString());
            } catch (IllegalArgumentException ignored) {
                // 未填完的行不写进表达式，面板里仍然是可编辑草稿。
            }
        }
        StringBuilder text = new StringBuilder(String.join(" AND ", conditions));
        if (!sorts.isEmpty()) {
            text.append(text.isEmpty() ? "" : " · ");
            text.append("ORDER BY ");
            for (int i = 0; i < sorts.size(); i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(sorts.get(i).column())
                        .append(sorts.get(i).ascending() ? " ASC" : " DESC");
            }
        }
        return text.toString();
    }

    private static String format(FilterCondition condition, int index) {
        if (index >= condition.values().size()) {
            return "<?>";
        }
        Object value = condition.values().get(index);
        if (value == null) {
            return "NULL";
        }
        String rendered = String.valueOf(value);
        if (rendered.isEmpty()) {
            return "''";
        }
        return "'" + rendered.replace("'", "''") + "'";
    }

    private ColumnDef column(String name) {
        return schema.columns().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(I18n.t("cond.error.noColumn")));
    }

    private String firstColumn(String exclude) {
        for (ColumnDef column : schema.columns()) {
            if (exclude == null || !column.name().equalsIgnoreCase(exclude)) {
                return column.name();
            }
        }
        return schema.columns().isEmpty() ? "" : schema.columns().get(0).name();
    }

    /**
     * ⭐ R55：「这一档吃不吃空串」不再由这里手写第二张名单 —— 名单和
     * {@link ColumnType#textual()} 就是同一件事的两份真相，漂了没人知道。
     * 网格里「清空一格」绑什么、契约里绑什么、这里允不允许空，全部走 {@code textual()}。
     */
    private static boolean requiresNonEmpty(ColumnType type) {
        return !type.textual();
    }

    /**
     * 值为空 = 这一行还没填完（占位符 {@code <?>}）。Apply 时跳过，
     * 否则「只想加个排序」也会被一行没填的条件拦住。
     */
    private static boolean incomplete(Row row) {
        FilterOperator operator = row.operator.getValue();
        if (operator == null) {
            return true;
        }
        if (!operator.acceptsValue()) {
            return false;
        }
        if (row.value.getText() == null || row.value.getText().trim().isEmpty()) {
            return true;
        }
        return operator.acceptsSecondValue()
                && (row.second.getText() == null || row.second.getText().trim().isEmpty());
    }

    private static String kindPrompt(ColumnDef column) {
        return switch (column.type()) {
            case NUMBER -> I18n.t("cond.kindNumber");
            case BOOLEAN -> "1 / 0 / true / false";
            case DATE -> "YYYY-MM-DD";
            case TIMESTAMP -> "YYYY-MM-DD HH:MM:SS";
            case TIME -> "HH:MM:SS";
            default -> I18n.t("cond.kindText");
        };
    }

    static String operatorLabel(FilterOperator operator) {
        // 逐键写死而不是拼 "filter.op." + name：I18nAuditTest 要求源码里的键是**字面量**，
        // 拼出来的前缀会被判成「引用了资源里没有的键」。
        return switch (operator) {
            case EQ -> I18n.t("filter.op.EQ");
            case NOT_EQ -> I18n.t("filter.op.NOT_EQ");
            case CONTAINS -> I18n.t("filter.op.CONTAINS");
            case STARTS_WITH -> I18n.t("filter.op.STARTS_WITH");
            case ENDS_WITH -> I18n.t("filter.op.ENDS_WITH");
            case GT -> I18n.t("filter.op.GT");
            case GTE -> I18n.t("filter.op.GTE");
            case LT -> I18n.t("filter.op.LT");
            case LTE -> I18n.t("filter.op.LTE");
            case IS_NULL -> I18n.t("filter.op.IS_NULL");
            case IS_NOT_NULL -> I18n.t("filter.op.IS_NOT_NULL");
            case BETWEEN -> I18n.t("filter.op.BETWEEN");
            case IN -> I18n.t("filter.op.EQ");
        };
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Button iconButton(Icons.Icon icon, String hint) {
        Button button = IconButtons.iconMono(icon, hint);
        button.getStyleClass().add("adb-icon-button--small");
        return button;
    }

    private static final class Row {
        private HBox box;
        private CheckBox enabled;
        private Hyperlink field;
        private ComboBox<FilterOperator> operator;
        private TextField value;
        private TextField second;
        private String column;
        private boolean syncing;
    }
}
