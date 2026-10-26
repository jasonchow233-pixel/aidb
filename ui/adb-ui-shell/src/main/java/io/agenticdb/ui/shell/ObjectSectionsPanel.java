package io.agenticdb.ui.shell;

import io.agenticdb.introspect.ForeignKeyDef;
import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.introspect.TriggerDef;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
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
        columnsTable.setPlaceholder(EmptyState.of(Icons.Icon.COLUMNS, "读不到这张表的列",
                "列是表的结构本体，正常不会读空 —— 多半是表刚被删掉，或当前账号没有读结构的权限。"
                        + "切到「数据」再切回来会重读一次。"));
        columnsTable.getColumns().add(column("列名", ColumnDef::name, 180));
        columnsTable.getColumns().add(column("类型", value -> value.displayType(), 140));
        columnsTable.getColumns().add(column("可空", value -> value.nullable() ? "是" : "否", 60));
        columnsTable.getColumns().add(column("主键", value -> value.primaryKey() ? "是" : "", 60));
        columnsTable.getColumns().add(column("默认值", ColumnDef::defaultValue, 120));
        columnsTable.getColumns().add(column("注释", ColumnDef::comment, 160));

        indexesTable.setId("object-indexes-table");
        indexesTable.getStyleClass().add("adb-structure__table");
        indexesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        indexesTable.setPlaceholder(EmptyState.of(Icons.Icon.INDEX, "这张表没有索引",
                "没有索引时，按任何列过滤都要整表扫 —— 查询慢先看这里。"));
        indexesTable.getColumns().add(column("索引名", IndexDef::name, 200));
        indexesTable.getColumns().add(column("唯一", value -> value.unique() ? "是" : "否", 60));
        indexesTable.getColumns().add(column("列", IndexDef::columnsLabel, 220));
        indexesTable.getColumns().add(column("来源", IndexDef::origin, 100));

        foreignKeysTable.setId("object-foreign-keys-table");
        foreignKeysTable.getStyleClass().add("adb-structure__table");
        foreignKeysTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        foreignKeysTable.setPlaceholder(EmptyState.of(Icons.Icon.TRANSFER, "这张表没有外键",
                "没有外键不代表设计有问题 —— 很多库把引用完整性放在应用层。"
                        + "但少了外键，库里就可能留下指向已删除行的孤儿值。"));
        foreignKeysTable.getColumns().add(column("约束名", ForeignKeyDef::displayName, 160));
        foreignKeysTable.getColumns().add(column("列", value -> String.join(", ", value.columns()), 160));
        foreignKeysTable.getColumns().add(column("引用", ForeignKeyDef::referencedTable, 140));
        foreignKeysTable.getColumns().add(column("引用列",
                value -> String.join(", ", value.referencedColumns()), 160));
        foreignKeysTable.getColumns().add(column("删除时", ForeignKeyDef::onDelete, 100));

        triggersTable.setId("object-triggers-table");
        triggersTable.getStyleClass().add("adb-structure__table");
        triggersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        triggersTable.setPlaceholder(EmptyState.of(Icons.Icon.TRIGGER, "这张表没有触发器",
                "触发器是库在写入时自动执行的 SQL。这里为空，说明这张表的写入没有任何库侧的附带逻辑。"));
        triggersTable.getColumns().add(column("名称", TriggerDef::name, 180));
        triggersTable.getColumns().add(column("时机", TriggerDef::timingLabel, 80));
        triggersTable.getColumns().add(column("事件", TriggerDef::eventLabel, 80));
    }

    private void buildSql() {
        sqlView.setId("object-sql-view");
        sqlView.setEditable(false);
        sqlView.setWrapText(false);
        sqlView.getStyleClass().add("adb-sql-preview");
        sqlNote.setId("object-sql-note");
        sqlNote.getStyleClass().add("adb-muted");
        sqlNote.setText("数据库返回的建表语句 + 数据页实际使用的查询");
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
        showLoading("正在读取 " + tableName + " 的结构…");

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
                    showError("读取结构失败：" + finalFailure);
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
            text.append("-- 该数据库未返回 DDL\n\n");
        }
        String quoted = session.dialect().quote(tableName);
        text.append("-- 数据页使用的查询\nSELECT * FROM ").append(quoted).append(" LIMIT 200;");
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
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
