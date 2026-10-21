package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.introspect.TriggerDef;
import io.agenticdb.introspect.ForeignKeyDef;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.event.ActionEvent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 结构页：列/索引/外键/触发器/DDL 查看，以及带预览与事务的列编辑。
 *
 * <p>编辑只在主线程构造计划；实际 JDBC 执行全部提交到共享单线程执行器，
 * 与数据页/查询页保持同一连接串行语义。
 */
public final class StructurePage extends VBox implements AutoCloseable {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private final DbSession session;
    private final SchemaService schemaService;
    private final String tableName;
    private final String connectionName;
    private final DataGridPage.Feedback feedback;
    private final Executor dbExecutor;
    private final Runnable onChanged;

    private final TabPane tabs = new TabPane();
    private final TableView<ColumnDef> columns = new TableView<>();
    private final TableView<IndexDef> indexes = new TableView<>();
    private final TableView<ForeignKeyDef> foreignKeys = new TableView<>();
    private final TableView<TriggerDef> triggers = new TableView<>();
    private final TextArea ddl = new TextArea();
    private final Label kind = new Label("—");
    private final Label summary = new Label("—");
    private final Label ddlNote = new Label("");
    private final Button add = new Button("＋ 新增列");
    private final Button edit = new Button("修改列");
    private final Button rename = new Button("重命名");
    private final Button drop = new Button("删除列");
    private final Button refresh = new Button("↻ 刷新");
    private final Button copyDdl = new Button("复制 DDL");
    private final Button copySelect = new Button("复制 SELECT");

    private TableStructure structure;
    private ProgressIndicator loading;
    private Predicate<StructurePlan> confirmation = this::defaultConfirm;
    private Future<?> inFlight;
    private boolean running;

    public StructurePage(DbSession session, SchemaService schemaService, String tableName,
                         String connectionName, DataGridPage.Feedback feedback,
                         Executor dbExecutor, Runnable onChanged) {
        this.session = session;
        this.schemaService = schemaService;
        this.tableName = tableName;
        this.connectionName = connectionName;
        this.feedback = feedback;
        this.dbExecutor = dbExecutor;
        this.onChanged = onChanged == null ? () -> { } : onChanged;

        getStyleClass().addAll("adb-page", "adb-structure");
        buildUi();
        updateButtons();
        reload();
    }

    private void buildUi() {
        Label title = new Label(tableName);
        title.getStyleClass().add("adb-page__title");
        Label badge = new Label("结构");
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Label context = new Label(connectionName);
        context.getStyleClass().add("adb-muted");

        HBox header = new HBox(10, title, badge, headerSpacer, context);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        add.getStyleClass().add("adb-button");
        add.setId("structure-add");
        add.setOnAction(event -> showColumnDialog("新增列", null));
        edit.getStyleClass().add("adb-button");
        edit.setId("structure-edit");
        edit.setOnAction(event -> selectedColumn().ifPresent(column ->
                showColumnDialog("修改列 " + column.name(), StructureSqlBuilder.ColumnSpec.from(column))));
        rename.getStyleClass().add("adb-button");
        rename.setId("structure-rename");
        rename.setOnAction(event -> selectedColumn().ifPresent(this::renameColumn));
        drop.getStyleClass().add("adb-button");
        drop.setId("structure-drop");
        drop.setOnAction(event -> selectedColumn().ifPresent(this::dropColumn));
        refresh.getStyleClass().add("adb-button");
        refresh.setId("structure-refresh");
        refresh.setOnAction(event -> reload());
        copyDdl.getStyleClass().add("adb-button");
        copyDdl.setId("structure-copy-ddl");
        copyDdl.setOnAction(event -> copy(structure == null ? "" : structure.ddl()));
        copySelect.getStyleClass().add("adb-button");
        copySelect.setId("structure-copy-select");
        copySelect.setOnAction(event -> copy("SELECT * FROM "
                + session.dialect().quote(tableName) + " LIMIT 200;"));

        HBox toolbar = new HBox(6, add, edit, rename, drop, new Region(), refresh,
                copyDdl, copySelect);
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        ddl.setEditable(false);
        ddl.setWrapText(false);
        ddl.getStyleClass().add("adb-structure__ddl");
        ddl.setPrefRowCount(18);
        ddlNote.getStyleClass().add("adb-muted");
        VBox ddlBox = new VBox(4, ddl, ddlNote);
        VBox.setVgrow(ddl, Priority.ALWAYS);

        tabs.getStyleClass().add("adb-structure__tabs");
        tabs.setId("structure-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("列", buildColumnsTab()),
                tab("索引", indexes),
                tab("外键", foreignKeys),
                tab("触发器", triggers),
                tab("DDL", ddlBox));

        VBox body = new VBox(8, toolbar, tabs);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        loading = new ProgressIndicator();
        loading.getStyleClass().add("adb-structure__loading");
        loading.setVisible(false);
        loading.setManaged(false);

        getChildren().addAll(header, body, loading);
    }

    private VBox buildColumnsTab() {
        columns.getStyleClass().add("adb-structure__table");
        columns.setId("structure-columns");
        columns.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        addColumn(columns, "名称", value -> new ReadOnlyStringWrapper(value.name()));
        addColumn(columns, "类型", value -> new ReadOnlyStringWrapper(value.displayType()));
        addColumn(columns, "可空", value -> new ReadOnlyStringWrapper(value.nullable() ? "YES" : "NO"));
        addColumn(columns, "默认值", value -> new ReadOnlyStringWrapper(
                value.defaultValue() == null ? "" : value.defaultValue()));
        addColumn(columns, "主键", value -> new ReadOnlyStringWrapper(value.primaryKey() ? "PK" : ""));
        addColumn(columns, "唯一", value -> new ReadOnlyStringWrapper(value.unique() ? "✓" : ""));
        // 注释列只在方言真支持时出现：SQLite 没有列注释，恒为空列就是死 UI（4.5-22）。
        if (session.dialect().supportsColumnComment()) {
            addColumn(columns, "注释", value -> new ReadOnlyStringWrapper(
                    value.comment() == null ? "" : value.comment()));
        }
        columns.setRowFactory(view -> {
            TableRow<ColumnDef> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    columns.getSelectionModel().select(row.getIndex());
                }
            });
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(columnMenu(row.getItem())));
            return row;
        });
        columns.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && selectedColumn().isPresent()) {
                showColumnDialog("修改列", StructureSqlBuilder.ColumnSpec.from(
                        selectedColumn().orElseThrow()));
            }
        });

        VBox overview = new VBox(6, kind, summary);
        overview.getStyleClass().add("adb-structure__overview");

        VBox box = new VBox(8, overview, columns);
        VBox.setVgrow(columns, Priority.ALWAYS);
        return box;
    }

    private ContextMenu columnMenu(ColumnDef column) {
        if (column == null || structure == null || !structure.editable()) {
            return new ContextMenu();
        }
        MenuItem editItem = new MenuItem("修改列");
        editItem.setOnAction(event -> showColumnDialog("修改列 " + column.name(),
                StructureSqlBuilder.ColumnSpec.from(column)));
        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(event -> renameColumn(column));
        MenuItem dropItem = new MenuItem("删除列");
        dropItem.setOnAction(event -> dropColumn(column));
        MenuItem copyName = new MenuItem("复制列名");
        copyName.setOnAction(event -> copy(column.name()));
        MenuItem copySelect = new MenuItem("复制 SELECT");
        copySelect.setOnAction(event -> copy("SELECT "
                + session.dialect().quote(column.name()) + " FROM "
                + session.dialect().quote(tableName) + " LIMIT 200;"));
        return new ContextMenu(editItem, renameItem, dropItem, new SeparatorMenuItem(),
                copyName, copySelect);
    }

    private static <T> Tab tab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private <T> void addColumn(TableView<T> table, String title,
                               Function<T, javafx.beans.value.ObservableValue<String>> factory) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> factory.apply(data.getValue()));
        table.getColumns().add(column);
    }

    private void setIndexes(List<IndexDef> values) {
        indexes.getColumns().clear();
        addColumn(indexes, "名称", value -> new ReadOnlyStringWrapper(value.name()));
        addColumn(indexes, "唯一", value -> new ReadOnlyStringWrapper(value.unique() ? "✓" : ""));
        addColumn(indexes, "列", value -> new ReadOnlyStringWrapper(value.columnsLabel()));
        indexes.setItems(FXCollections.observableArrayList(values));
    }

    private void setForeignKeys(List<ForeignKeyDef> values) {
        foreignKeys.getColumns().clear();
        addColumn(foreignKeys, "名称", value -> new ReadOnlyStringWrapper(value.displayName()));
        addColumn(foreignKeys, "列", value -> new ReadOnlyStringWrapper(
                String.join(", ", value.columns())));
        addColumn(foreignKeys, "引用表", value -> new ReadOnlyStringWrapper(value.referencedTable()));
        addColumn(foreignKeys, "引用列", value -> new ReadOnlyStringWrapper(
                String.join(", ", value.referencedColumns())));
        addColumn(foreignKeys, "更新", value -> new ReadOnlyStringWrapper(value.onUpdate()));
        addColumn(foreignKeys, "删除", value -> new ReadOnlyStringWrapper(value.onDelete()));
        foreignKeys.setItems(FXCollections.observableArrayList(values));
    }

    private void setTriggers(List<TriggerDef> values) {
        triggers.getColumns().clear();
        addColumn(triggers, "名称", value -> new ReadOnlyStringWrapper(value.name()));
        addColumn(triggers, "时机", value -> new ReadOnlyStringWrapper(value.timingLabel()));
        addColumn(triggers, "事件", value -> new ReadOnlyStringWrapper(value.eventLabel()));
        addColumn(triggers, "语句", value -> new ReadOnlyStringWrapper(value.statement()));
        triggers.setItems(FXCollections.observableArrayList(values));
    }

    public void reload() {
        if (running) {
            return;
        }
        setLoading(true);
        feedback.status("正在读取结构：" + tableName);
        Task<TableStructure> task = new Task<>() {
            @Override
            protected TableStructure call() throws Exception {
                return schemaService.structure(session, tableName);
            }
        };
        task.setOnSucceeded(event -> {
            structure = task.getValue();
            render();
            setLoading(false);
            updateButtons();
            feedback.status("结构已加载：" + tableName);
        });
        task.setOnFailed(event -> {
            setLoading(false);
            updateButtons();
            Throwable error = task.getException();
            feedback.status("读取结构失败：" + readable(error));
            feedback.toast("读取结构失败：" + readable(error));
        });
        submit(task);
    }

    private void render() {
        if (structure == null) {
            return;
        }
        kind.setText(structure.kindLabel());
        summary.setText("列 " + structure.columns().size()
                + " · 主键 " + structure.primaryKeys().size()
                + " · 索引 " + structure.indexes().size()
                + " · 外键 " + structure.foreignKeys().size()
                + " · 触发器 " + structure.triggers().size());
        ddl.setText(structure.ddl());
        ddlNote.setText(session.dialect() == io.agenticdb.jdbc.DialectKind.POSTGRESQL
                && !structure.view() ? "PostgreSQL 无 SHOW CREATE TABLE；以下为由内省生成的等价 DDL" : "");
        columns.setItems(FXCollections.observableArrayList(structure.columns()));
        setIndexes(structure.indexes());
        setForeignKeys(structure.foreignKeys());
        setTriggers(structure.triggers());
        updateButtons();
    }

    private Optional<ColumnDef> selectedColumn() {
        ColumnDef selected = columns.getSelectionModel().getSelectedItem();
        return selected == null ? Optional.empty() : Optional.of(selected);
    }

    private void showColumnDialog(String title, StructureSqlBuilder.ColumnSpec initial) {
        if (structure == null || !structure.editable()) {
            return;
        }
        Dialog<StructureSqlBuilder.ColumnSpec> dialog = new Dialog<>();
        dialog.setTitle("结构变更");
        dialog.setHeaderText(title);
        dialog.initOwner(sceneWindow());

        TextField name = new TextField(initial == null ? "" : initial.name());
        name.setId("structure-dialog-name");
        name.setPromptText("列名");

        ComboBox<String> type = new ComboBox<>();
        type.setId("structure-dialog-type");
        type.setEditable(true);
        type.getItems().setAll("TEXT", "VARCHAR(255)", "INTEGER", "REAL", "NUMERIC",
                "BOOLEAN", "DATE", "TIME", "TIMESTAMP", "BLOB", "JSON", "UUID");
        type.setValue(initial == null || initial.rawType().isBlank()
                ? "TEXT" : initial.rawType());

        CheckBox nullable = new CheckBox("允许 NULL");
        nullable.setId("structure-dialog-nullable");
        nullable.setSelected(initial == null || initial.nullable());

        TextField defaultValue = new TextField(
                initial == null ? "" : (initial.defaultValue() == null ? "" : initial.defaultValue()));
        defaultValue.setId("structure-dialog-default");
        defaultValue.setPromptText("默认值表达式（可空）");

        TextField comment = new TextField(
                initial == null ? "" : (initial.comment() == null ? "" : initial.comment()));
        comment.setId("structure-dialog-comment");
        comment.setPromptText("注释（可空）");
        // SQLite 没有列注释：输入框可打字但存不住、读不回，是「数据丢失幻觉」。
        // 按能力禁用并说明，而不是留一个骗人的输入框（4.5-22）。
        boolean commentSupported = session.dialect().supportsColumnComment();
        comment.setDisable(!commentSupported);
        if (!commentSupported) {
            comment.setPromptText(session.dialect().displayName() + " 不支持列注释");
        }

        CheckBox unique = new CheckBox("唯一");
        unique.setId("structure-dialog-unique");
        unique.setSelected(initial != null && initial.unique());

        Label error = new Label();
        error.setId("structure-dialog-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setMaxWidth(360);
        error.setVisible(false);
        error.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("列名"), name);
        grid.addRow(1, new Label("类型"), type);
        grid.addRow(2, new Label("默认值"), defaultValue);
        grid.addRow(3, new Label("注释"), comment);
        grid.addRow(4, nullable, unique);
        grid.add(error, 0, 5, 2, 1);
        dialog.getDialogPane().setContent(grid);

        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        // ⭐ 校验失败必须把对话框留在原地：用户改一个字符就能继续，
        //    关掉再重开等于把刚才填的四五个字段全丢了（4.5-12 / 4.5-16）。
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String problem = columnSpecProblem(name.getText(), type.getValue(),
                    initial == null ? null : initial.name(),
                    structure == null ? List.of() : structure.columns());
            if (problem != null) {
                error.setText(problem);
                error.setVisible(true);
                error.setManaged(true);
                feedback.status(problem);
                event.consume();
            }
        });
        dialog.setResultConverter(button -> {
            if (button != ok) {
                return null;
            }
            String rawType = type.getValue() == null ? "TEXT" : type.getValue().trim();
            return new StructureSqlBuilder.ColumnSpec(
                    name.getText().trim(), rawType, nullable.isSelected(),
                    defaultValue.getText().trim(), comment.getText().trim(),
                    unique.isSelected(), initial != null && initial.primaryKey());
        });

        dialog.showAndWait().ifPresent(spec -> {
            if (spec.name().equalsIgnoreCase(initial == null ? "" : initial.name())
                    && spec.rawType().equalsIgnoreCase(initial == null ? "" : initial.rawType())
                    && spec.nullable() == (initial == null || initial.nullable())
                    && java.util.Objects.equals(spec.defaultValue(), initial == null ? null : initial.defaultValue())
                    && java.util.Objects.equals(spec.comment(), initial == null ? null : initial.comment())) {
                feedback.status("结构未变化");
                return;
            }
            StructurePlan plan = initial == null
                    ? StructureSqlBuilder.addColumn(session, structure, spec)
                    : StructureSqlBuilder.alterColumn(session, structure, initial.name(), spec);
            applyPlan(plan);
        });
    }

    /**
     * 列名 / 类型的校验。返回 {@code null} 表示通过，否则返回给人看的原因。
     *
     * <p>包级可见是为了让单测直接断言规则，不必真的弹出对话框。
     */
    static String columnSpecProblem(String rawName, String rawType,
                                    String selfName, List<ColumnDef> existing) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return "列名不能为空";
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            return "列名只能以字母或下划线开头，且只能包含字母、数字、下划线和 $";
        }
        String type = rawType == null ? "" : rawType.trim();
        if (type.isEmpty()) {
            return "列类型不能为空";
        }
        for (ColumnDef column : existing) {
            // 改名时自己那个旧名字不算冲突。
            if (selfName != null && column.name().equalsIgnoreCase(selfName)) {
                continue;
            }
            if (column.name().equalsIgnoreCase(name)) {
                return "列名 " + name + " 已存在";
            }
        }
        return null;
    }

    /** 列名合法性：与 {@code StructureSqlBuilder} 的生成规则保持一致。 */
    private static final java.util.regex.Pattern VALID_COLUMN_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private void renameColumn(ColumnDef column) {
        TextInputDialog dialog = new TextInputDialog(column.name());
        dialog.setTitle("重命名列");
        dialog.setHeaderText("重命名 " + column.name());
        dialog.initOwner(sceneWindow());
        dialog.getEditor().setId("structure-rename-field");
        // ⭐ 同上：空名字或非法的名字不能静默放过，更不能把对话框关掉。
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            String value = dialog.getEditor().getText();
            boolean unchanged = value != null && value.trim().equals(column.name());
            String problem = unchanged
                    ? null
                    : columnSpecProblem(value, "TEXT", column.name(),
                            structure == null ? List.of() : structure.columns());
            if (problem != null) {
                feedback.status(problem);
                event.consume();
            }
        });
        dialog.showAndWait().ifPresent(newName -> {
            if (newName.isBlank() || newName.equals(column.name())) {
                return;
            }
            applyPlan(StructureSqlBuilder.renameColumn(session, structure, column.name(), newName.trim()));
        });
    }

    private void dropColumn(ColumnDef column) {
        applyPlan(StructureSqlBuilder.dropColumn(session, structure, column.name()));
    }

    public void setConfirmationHandler(Predicate<StructurePlan> confirmation) {
        this.confirmation = confirmation == null ? this::defaultConfirm : confirmation;
    }

    public boolean isRunning() {
        return running;
    }

    public TableStructure structure() {
        return structure;
    }

    private void applyPlan(StructurePlan plan) {
        if (running) {
            feedback.status("已有结构变更正在执行");
            return;
        }
        if (plan.empty()) {
            return;
        }
        if (!confirmation.test(plan)) {
            feedback.status("已取消结构变更");
            return;
        }
        running = true;
        updateButtons();
        feedback.status("正在执行结构变更…");
        feedback.duration(-1);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                execute(plan);
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            running = false;
            updateButtons();
            feedback.toast(plan.title() + " 已完成");
            feedback.status(plan.title() + " 已完成");
            onChanged.run();
            reload();
        });
        task.setOnFailed(event -> {
            running = false;
            updateButtons();
            Throwable error = task.getException();
            feedback.status("结构变更失败：" + readable(error));
            feedback.toast("结构变更失败：" + readable(error));
        });
        submit(task);
    }

    private void execute(StructurePlan plan) throws Exception {
        boolean foreignKeysOff = plan.foreignKeysOff();
        if (foreignKeysOff) {
            session.update("PRAGMA foreign_keys = OFF");
        }
        session.begin();
        try {
            for (String sql : plan.statements()) {
                session.execute(sql);
            }
            session.commit();
        } catch (Exception e) {
            try {
                session.rollback();
            } catch (Exception ignored) {
                // 回滚失败也要把原始 SQL 错误交给用户。
            }
            throw e;
        } finally {
            if (foreignKeysOff) {
                session.update("PRAGMA foreign_keys = ON");
            }
        }
    }

    private boolean defaultConfirm(StructurePlan plan) {
        if (!showPlanAlert(plan, plan.title(), "继续")) {
            return false;
        }
        if (!plan.approvalRequired()) {
            return true;
        }
        return showPlanAlert(plan, "高风险结构变更：" + plan.title(), "确认执行");
    }

    private boolean showPlanAlert(StructurePlan plan, String title, String okLabel) {
        ButtonType ok = new ButtonType(okLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("结构变更确认");
        alert.setHeaderText(title);
        alert.setContentText("将按顺序执行以下 SQL（单事务，失败回滚）：");
        TextArea area = new TextArea(plan.sql());
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(Math.min(18, Math.max(4, plan.statements().size() + 2)));
        alert.getDialogPane().setContent(area);
        alert.getDialogPane().getButtonTypes().setAll(ok, cancel);
        styleAlert(alert);
        return alert.showAndWait().orElse(cancel) == ok;
    }

    private void styleAlert(Alert alert) {
        if (getScene() != null) {
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            alert.getDialogPane().getStyleClass().add("adb-dialog");
        }
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
    }

    private void setLoading(boolean value) {
        loading.setVisible(value);
        loading.setManaged(value);
        updateButtons();
    }

    private void updateButtons() {
        boolean editable = structure != null && structure.editable();
        add.setDisable(running || !editable);
        edit.setDisable(running || !editable || selectedColumn().isEmpty());
        rename.setDisable(running || !editable || selectedColumn().isEmpty());
        drop.setDisable(running || !editable || selectedColumn().isEmpty());
        refresh.setDisable(running);
        copyDdl.setDisable(structure == null);
        copySelect.setDisable(structure == null);
    }

    private void copy(String text) {
        if (text == null || text.isBlank()) {
            feedback.status("没有可复制的内容");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        feedback.status("已复制到剪贴板");
        feedback.toast("已复制到剪贴板");
    }

    private javafx.stage.Window sceneWindow() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static String readable(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private Future<?> submit(Task<?> task) {
        if (dbExecutor instanceof ExecutorService service) {
            return service.submit(task);
        }
        dbExecutor.execute(task);
        return null;
    }

    @Override
    public void close() {
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
    }
}
