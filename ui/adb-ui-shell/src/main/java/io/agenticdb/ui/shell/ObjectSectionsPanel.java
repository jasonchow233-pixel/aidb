package io.agenticdb.ui.shell;

import io.agenticdb.introspect.ForeignKeyDef;
import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.introspect.TriggerDef;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * ⭐ 对象的功能切面（§6.2）：结构 / 索引 / 外键 / 触发器 / SQL。
 *
 * <p>这些切面读的是**真实的内省结果**（{@link SchemaService#structure}），
 * 不是写死的示例。三个原则：
 * <ol>
 *   <li><b>不重复造编辑器</b> —— 这里是**只读**视图；增删改索引 / 外键 / 触发器
 *       仍然在结构页里做（那里有完整的对话框与方言分支）。</li>
 *   <li><b>拿不到就说拿不到</b> —— 加载中 / 空 / 失败 / 权限不足各有明确状态，
 *       绝不显示空白表格让用户猜。</li>
 *   <li><b>懒加载</b> —— 只有用户切到某个切面才去读结构，不拖慢打开数据页。</li>
 * </ol>
 */
final class ObjectSectionsPanel extends VBox {

    /** 切面 id。 */
    static final String SECTION_COLUMNS = "columns";
    static final String SECTION_INDEXES = "indexes";
    static final String SECTION_FOREIGN_KEYS = "foreign-keys";
    static final String SECTION_TRIGGERS = "triggers";
    static final String SECTION_SQL = "sql";

    private final DbSession session;
    private final SchemaService schemaService;
    private final String tableName;
    private final Executor dbExecutor;

    private final TableView<ColumnDef> columnsTable = new TableView<>();
    private final TableView<IndexDef> indexesTable = new TableView<>();
    private final TableView<ForeignKeyDef> foreignKeysTable = new TableView<>();
    private final TableView<TriggerDef> triggersTable = new TableView<>();
    private final TextArea sqlView = new TextArea();
    private final Label sqlNote = new Label();
    private final VBox sqlBox = new VBox(4, sqlView, sqlNote);
    private final StackPane stack = new StackPane();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label stateText = new Label();
    private final VBox stateBox = new VBox(10);
    private final Runnable onOpenStructure;

    private boolean loaded;
    private boolean loading;
    private TableStructure structure;
    private String currentSectionId = SECTION_COLUMNS;
    /** 最近一次读取失败的原因（给状态文案与门禁用）。 */
    private String lastError;

    ObjectSectionsPanel(DbSession session, SchemaService schemaService, String tableName,
                        Executor dbExecutor, Runnable onOpenStructure) {
        this.session = session;
        this.schemaService = schemaService;
        this.tableName = tableName;
        this.dbExecutor = dbExecutor;
        this.onOpenStructure = onOpenStructure == null ? () -> { } : onOpenStructure;

        getStyleClass().add("adb-object-sections");
        setPadding(new Insets(0));
        buildTables();
        buildSql();
        buildState();

        stack.getChildren().addAll(columnsTable, indexesTable, foreignKeysTable,
                triggersTable, sqlBox, stateBox);
        getChildren().add(stack);
        VBox.setVgrow(stack, javafx.scene.layout.Priority.ALWAYS);
    }

    // ─────────────────────────── 构建 ───────────────────────────

    private void buildTables() {
        columnsTable.setId("object-columns-table");
        columnsTable.getStyleClass().add("adb-structure__table");
        columnsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // U24：四类空态各说一句「为什么是空的」——「没有索引」和「读不到索引」不是一回事。
        columnsTable.setPlaceholder(EmptyState.of(Icons.Icon.COLUMNS,
                I18n.t("sections.empty.columns.title"),
                I18n.t("sections.empty.columns.note")));
        columnsTable.getColumns().add(column(I18n.t("sections.col.name"), ColumnDef::name, 180));
        columnsTable.getColumns().add(column(I18n.t("detail.type"),
                value -> value.displayType(), 140));
        columnsTable.getColumns().add(column(I18n.t("structure.col.nullable"),
                value -> value.nullable() ? I18n.t("details.meta.yes")
                        : I18n.t("details.meta.no"), 60));
        columnsTable.getColumns().add(column(I18n.t("structure.col.key"),
                value -> value.primaryKey() ? I18n.t("details.meta.yes") : "", 60));
        columnsTable.getColumns().add(column(I18n.t("structure.col.default"),
                ColumnDef::defaultValue, 120));
        columnsTable.getColumns().add(column(I18n.t("column.comment"),
                ColumnDef::comment, 160));

        indexesTable.setId("object-indexes-table");
        indexesTable.getStyleClass().add("adb-structure__table");
        indexesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        indexesTable.setPlaceholder(EmptyState.of(Icons.Icon.INDEX,
                I18n.t("sections.empty.indexes.title"),
                I18n.t("sections.empty.indexes.note")));
        indexesTable.getColumns().add(column(I18n.t("idx.name.prompt"), IndexDef::name, 200));
        indexesTable.getColumns().add(column(I18n.t("structure.col.unique"),
                value -> value.unique() ? I18n.t("details.meta.yes")
                        : I18n.t("details.meta.no"), 60));
        indexesTable.getColumns().add(column(I18n.t("structure.col.columns"),
                IndexDef::columnsLabel, 220));
        indexesTable.getColumns().add(column(I18n.t("sections.col.origin"),
                IndexDef::origin, 100));

        foreignKeysTable.setId("object-foreign-keys-table");
        foreignKeysTable.getStyleClass().add("adb-structure__table");
        foreignKeysTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        foreignKeysTable.setPlaceholder(EmptyState.of(Icons.Icon.TRANSFER,
                I18n.t("sections.empty.fks.title"),
                I18n.t("sections.empty.fks.note")));
        foreignKeysTable.getColumns().add(column(I18n.t("sections.fk.constraint"),
                ForeignKeyDef::displayName, 160));
        foreignKeysTable.getColumns().add(column(I18n.t("structure.col.columns"),
                value -> String.join(", ", value.columns()), 160));
        foreignKeysTable.getColumns().add(column(I18n.t("sections.fk.references"),
                ForeignKeyDef::referencedTable, 140));
        foreignKeysTable.getColumns().add(column(I18n.t("structure.fk.referencedColumns"),
                value -> String.join(", ", value.referencedColumns()), 160));
        foreignKeysTable.getColumns().add(column(I18n.t("sections.fk.onDelete"),
                ForeignKeyDef::onDelete, 100));

        triggersTable.setId("object-triggers-table");
        triggersTable.getStyleClass().add("adb-structure__table");
        triggersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        triggersTable.setPlaceholder(EmptyState.of(Icons.Icon.TRIGGER,
                I18n.t("sections.empty.triggers.title"),
                I18n.t("sections.empty.triggers.note")));
        triggersTable.getColumns().add(column(I18n.t("column.name"), TriggerDef::name, 180));
        triggersTable.getColumns().add(column(I18n.t("structure.trigger.timing"),
                TriggerDef::timingLabel, 80));
        triggersTable.getColumns().add(column(I18n.t("structure.trigger.event"),
                TriggerDef::eventLabel, 80));
    }

    private void buildSql() {
        sqlView.setId("object-sql-view");
        sqlView.setEditable(false);
        sqlView.setWrapText(false);
        sqlView.getStyleClass().add("adb-sql-preview");
        sqlNote.setId("object-sql-note");
        sqlNote.getStyleClass().add("adb-muted");
        sqlNote.setText(I18n.t("sections.sql.note"));
        // ⭐ 文本区要吃满剩余高度 —— 上面不加 grow 的话它只有默认的 5 行高，
        //    剩下的空间是一片灰，看着像「没加载出来」。
        VBox.setVgrow(sqlView, javafx.scene.layout.Priority.ALWAYS);
    }

    private void buildState() {
        spinner.setPrefSize(24, 24);
        spinner.getStyleClass().add("adb-grid__spinner");
        stateText.getStyleClass().add("adb-grid__state-text");
        stateBox.getStyleClass().add("adb-grid__state");
        stateBox.setAlignment(Pos.CENTER);
        stateBox.setMaxWidth(420);
        stateBox.getChildren().addAll(spinner, stateText);
    }

    private static <T> TableColumn<T, String> column(String title,
                                                      java.util.function.Function<T, String> getter,
                                                      int width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                nullSafe(getter.apply(cell.getValue()))));
        column.setPrefWidth(width);
        column.setSortable(false);
        return column;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ─────────────────────────── 切面切换 ───────────────────────────

    /** 切到某个切面（必要时懒加载结构）。 */
    void show(String sectionId) {
        currentSectionId = sectionId;
        switch (sectionId) {
            case SECTION_INDEXES -> visible(indexesTable);
            case SECTION_FOREIGN_KEYS -> visible(foreignKeysTable);
            case SECTION_TRIGGERS -> visible(triggersTable);
            case SECTION_SQL -> visible(sqlBox);
            default -> visible(columnsTable);
        }
        if (!loaded && !loading) {
            reload();
        }
    }

    private void visible(javafx.scene.Node node) {
        for (javafx.scene.Node child : stack.getChildren()) {
            child.setVisible(child == node);
            child.setManaged(child == node);
        }
    }

    // ─────────────────────────── 加载 ───────────────────────────

    /**
     * 重新读取结构。
     *
     * <p>在**该连接的执行器**上跑 —— UI 线程不做 JDBC。
     */
    void reload() {
        if (loading) {
            return;
        }
        loading = true;
        loaded = false;
        showLoading(I18n.t("sections.loading", tableName));

        dbExecutor.execute(() -> {
            TableStructure result;
            String failure = null;
            try {
                result = schemaService.structure(session, tableName);
            } catch (Exception e) {
                result = null;
                failure = readable(e);
            }
            TableStructure finalResult = result;
            String finalFailure = failure;
            Platform.runLater(() -> {
                loading = false;
                if (finalResult == null) {
                    lastError = finalFailure;
                    showError(I18n.t("sections.readFailed", finalFailure));
                    return;
                }
                loaded = true;
                lastError = null;
                structure = finalResult;
                apply(finalResult);
                restoreSection();
            });
        });
    }

    private void apply(TableStructure value) {
        columnsTable.setItems(FXCollections.observableArrayList(value.columns()));
        indexesTable.setItems(FXCollections.observableArrayList(value.indexes()));
        foreignKeysTable.setItems(FXCollections.observableArrayList(value.foreignKeys()));
        triggersTable.setItems(FXCollections.observableArrayList(value.triggers()));
        sqlView.setText(buildSqlText(value));
    }

    /**
     * 生成这个对象的 SQL 文本。
     *
     * <p>真实内容：库返回的 DDL + 按当前方言生成的 SELECT。
     */
    private String buildSqlText(TableStructure value) {
        StringBuilder text = new StringBuilder();
        String ddl = value.ddl();
        if (ddl != null && !ddl.isBlank()) {
            text.append(ddl.strip()).append("\n\n");
        } else {
            text.append(I18n.t("sections.sql.noDdl")).append("\n\n");
        }
        text.append(I18n.t("sections.sql.dataQuery"))
                .append("\n").append(session.dialect().plainPageSql(tableName, 200, 0)).append(';');
        return text.toString();
    }

    // ─────────────────────────── 状态 ───────────────────────────

    private void showLoading(String message) {
        stateText.setText(message);
        stateText.getStyleClass().remove("adb-grid__state-text--error");
        spinner.setVisible(true);
        visible(stateBox);
    }

    private void showError(String message) {
        stateText.setText(message);
        if (!stateText.getStyleClass().contains("adb-grid__state-text--error")) {
            stateText.getStyleClass().add("adb-grid__state-text--error");
        }
        spinner.setVisible(false);
        visible(stateBox);
    }

    private void restoreSection() {
        // 结构读回来了：切回当前切面。
        show(currentSectionId);
    }

    private javafx.scene.Node currentSection() {
        for (javafx.scene.Node child : stack.getChildren()) {
            if (child.isVisible() && child != stateBox) {
                return child;
            }
        }
        return columnsTable;
    }

    /** 供「打开结构编辑器」用：当前读到的结构（没读则 null）。 */
    TableStructure structure() {
        return structure;
    }

    /** 最近一次读取失败的原因（null = 没失败过）。 */
    String lastError() {
        return lastError;
    }

    private static String readable(Throwable error) {
        if (error == null) {
            return I18n.t("sections.unknownError");
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
