package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.ui.I18n;
import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableOptions;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.introspect.TriggerDef;
import io.agenticdb.introspect.ForeignKeyDef;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

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
    /** ⭐ CHECK 约束：以前既不内省也不展示（用户撞到 CHECK 报错却看不到约束是什么）。 */
    private final TableView<io.agenticdb.introspect.CheckDef> checks = new TableView<>();
    /** 密度档位 → 行高（结构页有四张表，都要跟）。 */
    private int rowHeight = io.agenticdb.ui.RowHeights.of("normal");
    private final SqlEditor ddlEditor = new SqlEditor();
    private final Label kind = new Label("—");
    private final Label summary = new Label("—");
    /** ⭐ B5-S4：表注释（B5-S4 之前**连看都看不了**）。 */
    private final Label tableCommentLabel = new Label();
    private final Label ddlNote = new Label("");
    private final Button add = IconButtons.of(Icons.Icon.ADD, I18n.t("structure.field.add"));
    private final Button edit = IconButtons.of(Icons.Icon.EDIT, I18n.t("structure.field.edit"));
    private final Button rename = new Button(I18n.t("structure.field.rename"));
    private final Button drop = IconButtons.of(Icons.Icon.DELETE, I18n.t("structure.field.delete"));
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, I18n.t("action.refresh"));
    private final Button copyDdl = IconButtons.icon(Icons.Icon.COPY, I18n.t("structure.action.copyDdl"));
    private final Button copySelect = IconButtons.icon(Icons.Icon.COPY, I18n.t("tab.copySelect"));
    /** ⭐ 与数据页共用的对象标题栏。 */
    private ObjectHeader objectHeader;
    /** S1 索引可编辑 —— 按钮挂在索引页签里，不进顶部工具栏（那是列操作的地盘）。 */
    private final Button addIndex = IconButtons.of(Icons.Icon.ADD, I18n.t("structure.index.add"));
    private final Button dropIndex = IconButtons.of(Icons.Icon.DELETE, I18n.t("structure.index.delete"));
    /** ⭐ B5-S2：外键可增删。 */
    private final Button addForeignKey = IconButtons.of(Icons.Icon.ADD, I18n.t("structure.fk.add"));
    private final Button dropForeignKey = IconButtons.of(Icons.Icon.DELETE, I18n.t("structure.fk.delete"));
    /** ⭐ B5-S4：表注释可改（SQLite 不支持 → 按钮禁用）。 */
    private final Button tableComment = IconButtons.of(Icons.Icon.EDIT, I18n.t("structure.tableComment.button"));
    /** ⭐ B5-S3：触发器可增删。 */
    private final Button addTrigger = IconButtons.of(Icons.Icon.ADD, I18n.t("structure.trigger.add"));
    private final Button dropTrigger = IconButtons.of(Icons.Icon.DELETE, I18n.t("structure.trigger.delete"));

    /**
     * 密度档位 —— 结构页有<b>四张</b>表，都要跟。
     */
    /**
     * 直接跳到 DDL 子页签（规格 §11.1：选中表 → 看建表 SQL）。
     *
     * <p>详情面板的「打开 DDL」走这里 —— 与其新开一个只有一段文本的标签页，
     * 不如复用结构页里那个已经会读真 DDL、能复制的页签。
     */
    public void showDdl() {
        if (tabs == null) {
            return;
        }
        tabs.getTabs().stream()
                .filter(tab -> "DDL".equals(tab.getText()))
                .findFirst()
                .ifPresent(tab -> tabs.getSelectionModel().select(tab));
    }

    public void setDensity(String density) {
        rowHeight = io.agenticdb.ui.RowHeights.of(density);
        columns.setFixedCellSize(rowHeight);
        indexes.setFixedCellSize(rowHeight);
        foreignKeys.setFixedCellSize(rowHeight);
        triggers.setFixedCellSize(rowHeight);
    }

    /** 结构页的四张表（供门禁断言密度作用到了**每一张**）。 */
    java.util.List<TableView<?>> tables() {
        return java.util.List.of(columns, indexes, foreignKeys, triggers);
    }

    private TableStructure structure;
    /** ⭐ UX-6：表的物理选项；方言没有这些概念时保持「—」。 */
    private TableOptions tableOptions = TableOptions.unavailable();
    private final Label optionsEngine = new Label("—");
    private final Label optionsCollation = new Label("—");
    private final Label optionsAutoIncrement = new Label("—");
    private final Label optionsRowFormat = new Label("—");
    private final Label optionsHint = new Label();
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
        // ⭐ 标题栏与数据页共用 {@link ObjectHeader} —— 同一类对象在两个页面里
        //    长得不一样，是「不统一」最直接的来源。
        ObjectHeader header = new ObjectHeader(Icons.Icon.TABLE, tableName, I18n.t("detail.kind.table"));
        objectHeader = header;
        header.setContext(connectionName);
        // 结构页没有「行数」这回事，显示「行数未知」像坏了。
        header.hideCount();

        add.getStyleClass().add("adb-button");
        add.setId("structure-add");
        add.setOnAction(event -> showColumnDialog(I18n.t("structure.field.add"), null));
        edit.getStyleClass().add("adb-button");
        edit.setId("structure-edit");
        edit.setOnAction(event -> selectedColumn().ifPresent(column ->
                showColumnDialog(I18n.t("structure.dialog.editHeader", column.name()), ColumnSpec.from(column))));
        rename.getStyleClass().add("adb-button");
        rename.setId("structure-rename");
        rename.setOnAction(event -> selectedColumn().ifPresent(this::renameColumn));
        drop.getStyleClass().addAll("adb-button", "adb-button--danger");
        drop.setId("structure-drop");
        drop.setOnAction(event -> selectedColumn().ifPresent(this::dropColumn));
        refresh.getStyleClass().addAll("adb-button", "adb-button--icon");
        refresh.setId("structure-refresh");
        refresh.setAccessibleText(I18n.t("structure.refresh.accessible"));
        refresh.setTooltip(new javafx.scene.control.Tooltip(I18n.t("structure.refresh.tooltip")));
        refresh.setOnAction(event -> reload());
        copyDdl.getStyleClass().addAll("adb-button", "adb-button--icon");
        copyDdl.setId("structure-copy-ddl");
        copyDdl.setAccessibleText(I18n.t("structure.action.copyDdl"));
        copyDdl.setTooltip(new javafx.scene.control.Tooltip(I18n.t("structure.copyDdl.tooltip")));
        copyDdl.setOnAction(event -> copy(structure == null ? "" : structure.ddl()));
        copySelect.getStyleClass().addAll("adb-button", "adb-button--icon");
        copySelect.setId("structure-copy-select");
        copySelect.setAccessibleText(I18n.t("tab.copySelect"));
        copySelect.setTooltip(new javafx.scene.control.Tooltip(I18n.t("structure.copySelect.tooltip")));
        copySelect.setOnAction(event -> copy("SELECT * FROM "
                + session.dialect().quote(tableName) + " LIMIT 200;"));

        header.addActions(refresh, copyDdl, copySelect);

        HBox toolbar = new HBox(6, add, edit, rename, drop, tableComment);
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // ⭐ 规格 §11.2：DDL 页复用 SqlEditor（等宽 + 行号 + 高亮），
        //    不再是灰底 TextArea —— 和查询页、新建表预览是同一套编辑器。
        //    只读：DDL 是内省/生成出来的，不允许在这里改。
        ddlEditor.area().setEditable(false);
        ddlEditor.getStyleClass().add("adb-structure__ddl");
        ddlEditor.setHint(I18n.t("structure.ddl.empty"));
        ddlEditor.setPrefHeight(340);
        ddlNote.getStyleClass().add("adb-muted");
        VBox ddlBox = new VBox(4, ddlEditor, ddlNote);
        VBox.setVgrow(ddlEditor, Priority.ALWAYS);

        tabs.getStyleClass().add("adb-structure__tabs");
        tabs.setId("structure-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        addForeignKey.getStyleClass().add("adb-button");
        addForeignKey.setId("structure-add-foreign-key");
        addForeignKey.setOnAction(event -> createForeignKey());
        dropForeignKey.getStyleClass().addAll("adb-button", "adb-button--danger");
        dropForeignKey.setId("structure-drop-foreign-key");
        dropForeignKey.setOnAction(event -> {
            ForeignKeyDef selected = foreignKeys.getSelectionModel().getSelectedItem();
            if (selected != null) {
                dropForeignKey(selected);
            }
        });
        addTrigger.getStyleClass().add("adb-button");
        addTrigger.setId("structure-add-trigger");
        addTrigger.setOnAction(event -> createTrigger());
        dropTrigger.getStyleClass().addAll("adb-button", "adb-button--danger");
        dropTrigger.setId("structure-drop-trigger");
        dropTrigger.setOnAction(event -> {
            TriggerDef selected = triggers.getSelectionModel().getSelectedItem();
            if (selected != null) {
                dropTrigger(selected);
            }
        });
        tableComment.getStyleClass().add("adb-button");
        tableComment.setId("structure-table-comment");
        tableComment.setOnAction(event -> showTableCommentDialog());
        addIndex.getStyleClass().add("adb-button");
        addIndex.setId("structure-add-index");
        addIndex.setOnAction(event -> createIndex());
        dropIndex.getStyleClass().addAll("adb-button", "adb-button--danger");
        dropIndex.setId("structure-drop-index");
        dropIndex.setOnAction(event -> selectedIndex().ifPresent(index ->
                dropIndex(index)));

        tabs.getTabs().addAll(
                tab(I18n.t("structure.tab.fields"), buildColumnsTab()),
                tab(I18n.t("structure.tab.options"), buildOptionsTab()),
                tab(I18n.t("structure.tab.indexes"), buildIndexesTab()),
                tab(I18n.t("structure.tab.foreignKeys"), buildForeignKeysTab()),
                tab(I18n.t("structure.tab.checks"), buildChecksTab()),
                tab(I18n.t("structure.tab.triggers"), buildTriggersTab()),
                tab("DDL", ddlBox));

        VBox body = new VBox(8, toolbar, tabs);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        loading = new ProgressIndicator();
        loading.getStyleClass().add("adb-structure__loading");
        loading.setVisible(false);
        loading.setManaged(false);

        // ⭐ 四张表都跟密度。
        columns.setFixedCellSize(rowHeight);
        indexes.setFixedCellSize(rowHeight);
        foreignKeys.setFixedCellSize(rowHeight);
        triggers.setFixedCellSize(rowHeight);
        getChildren().addAll(header, body, loading);
    }

    private VBox buildColumnsTab() {
        columns.getStyleClass().add("adb-structure__table");
        columns.setId("structure-columns");
        columns.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        addColumn(columns, I18n.t("column.name"), value -> new ReadOnlyStringWrapper(value.name()));
        addColumn(columns, I18n.t("detail.type"), value -> new ReadOnlyStringWrapper(value.displayType()));
        // U9 补漏（p4-structure 第三拍）：可空/主键以前是文本 YES/PK ——
        // 「非空」本身就是需要被看见的约束，所以可空列走 check/x 双字形；
        // 「非主键」不是需要被看见的状态（同 唯一 列的裁定），走 check/留空。
        addBoolColumn(columns, I18n.t("structure.col.nullable"), ColumnDef::nullable, true);
        addColumn(columns, I18n.t("structure.col.default"), value -> new ReadOnlyStringWrapper(
                value.defaultValue() == null ? "" : value.defaultValue()));
        addFlagColumn(columns, I18n.t("structure.col.key"), ColumnDef::primaryKey);
        addFlagColumn(columns, I18n.t("structure.col.unique"), ColumnDef::unique);
        // 注释列只在方言真支持时出现：SQLite 没有列注释，恒为空列就是死 UI（4.5-22）。
        if (session.dialect().supportsColumnComment()) {
            addColumn(columns, I18n.t("column.comment"), value -> new ReadOnlyStringWrapper(
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
                showColumnDialog(I18n.t("structure.field.edit"), ColumnSpec.from(
                        selectedColumn().orElseThrow()));
            }
        });

        VBox overview = new VBox(6, kind, summary, tableCommentLabel);
        overview.getStyleClass().add("adb-structure__overview");

        VBox box = new VBox(8, overview, columns);
        VBox.setVgrow(columns, Priority.ALWAYS);
        return box;
    }

    /**
     * ⭐ UX-6：「选项」页签 —— 引擎 / 排序规则 / 自增值 / 行格式。
     *
     * <p>这是 Navicat 表查看器里「选项」这一页的最小形态：只读、一屏看完。
     * 不在这里改物理选项（改引擎要重建表、改排序规则可能重写全表 ——
     * 那是另一个量级的危险动作，不属于「查看」）。
     */
    private VBox buildOptionsTab() {
        optionsHint.setId("structure-options-hint");
        optionsHint.getStyleClass().add("adb-muted");
        optionsHint.setWrapText(true);

        optionsEngine.setId("structure-options-engine");
        optionsCollation.setId("structure-options-collation");
        optionsAutoIncrement.setId("structure-options-auto-increment");
        optionsRowFormat.setId("structure-options-row-format");

        GridPane grid = new GridPane();
        grid.setId("structure-options");
        grid.setHgap(28);
        grid.setVgap(10);
        grid.addRow(0, keyLabel(I18n.t("column.engine")), optionsEngine);
        grid.addRow(1, keyLabel(I18n.t("column.collation")), optionsCollation);
        grid.addRow(2, keyLabel(I18n.t("structure.options.autoIncrement")), optionsAutoIncrement);
        grid.addRow(3, keyLabel(I18n.t("structure.options.rowFormat")), optionsRowFormat);

        VBox box = new VBox(12, optionsHint, grid);
        box.setPadding(new Insets(12));
        return box;
    }

    private static Label keyLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        label.setMinWidth(88);
        return label;
    }

    /** 把选项落到四个标签上；package-private 供门禁直接验证展示映射。 */
    void renderOptions(TableOptions options) {
        tableOptions = options == null ? TableOptions.unavailable() : options;
        optionsEngine.setText(orDash(tableOptions.engine()));
        optionsCollation.setText(orDash(tableOptions.collation()));
        optionsAutoIncrement.setText(tableOptions.autoIncrement() == null
                ? "—" : String.valueOf(tableOptions.autoIncrement()));
        optionsRowFormat.setText(orDash(tableOptions.rowFormat()));

        if (structure != null && structure.view()) {
            optionsHint.setText(I18n.t("structure.options.viewNone"));
        } else if (tableOptions.available()) {
            optionsHint.setText(I18n.t("structure.options.source"));
        } else if (session.dialect().tableOptionsSql() == null) {
            optionsHint.setText(I18n.t("structure.options.dialectNone",
                    session.dialect().displayName()));
        } else {
            optionsHint.setText(I18n.t("structure.options.unreadable"));
        }
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private ContextMenu columnMenu(ColumnDef column) {
        if (column == null || structure == null || !structure.editable()) {
            return new ContextMenu();
        }
        MenuItem editItem = new MenuItem(I18n.t("structure.field.edit"));
        editItem.setOnAction(event -> showColumnDialog(I18n.t("structure.dialog.editHeader", column.name()),
                ColumnSpec.from(column)));
        MenuItem renameItem = new MenuItem(I18n.t("structure.field.rename"));
        renameItem.setOnAction(event -> renameColumn(column));
        MenuItem dropItem = new MenuItem(I18n.t("structure.field.delete"));
        dropItem.setOnAction(event -> dropColumn(column));
        MenuItem copyName = new MenuItem(I18n.t("structure.menu.copyColumnName"));
        copyName.setOnAction(event -> copy(column.name()));
        MenuItem copySelect = new MenuItem(I18n.t("tab.copySelect"));
        copySelect.setOnAction(event -> copy("SELECT "
                + session.dialect().quote(column.name()) + " FROM "
                + session.dialect().quote(tableName) + " LIMIT 200;"));
        return new ContextMenu(editItem, renameItem, dropItem, new SeparatorMenuItem(),
                copyName, copySelect);
    }

    /**
     * 「检查」页签：显示 CHECK 约束（名称 + 表达式）。
     *
     * <p>只读：建表 / 改表的 CHECK 暂不支持在 UI 里编辑，但**看得见**很重要 ——
     * {@code ErrorLocator} 已经在给用户解释「值不满足 CHECK 约束」，
     * 用户却看不到约束到底是什么。
     */
    /**
     * 「外键」页签：外键可**新增 / 删除**（B5-S2）。
     *
     * <p>SQLite 的外键是 {@code CREATE TABLE} 的一部分 → 增删都要整表重建，
     * 由 {@code StructureSqlBuilder} 决定，UI 只负责收集输入。
     */
    private VBox buildForeignKeysTab() {
        foreignKeys.getStyleClass().add("adb-structure__table");
        foreignKeys.setId("structure-foreign-keys");
        foreignKeys.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // U24：空态给图标 + 解释 + 出口，不再是一行「表中无内容」。
        foreignKeys.setPlaceholder(EmptyState.of(Icons.Icon.TRANSFER, I18n.t("structure.empty.foreignKeys"),
                I18n.t("structure.hint.foreignKeys"),
                I18n.t("structure.fk.add"), this::createForeignKey));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, addForeignKey, dropForeignKey, spacer);
        bar.getStyleClass().add("adb-inline-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(I18n.t("structure.note.foreignKeys"));
        hint.getStyleClass().add("adb-muted");

        VBox box = new VBox(8, bar, hint, foreignKeys);
        VBox.setVgrow(foreignKeys, Priority.ALWAYS);
        return box;
    }

    /** 「触发器」页签：触发器可**新增 / 删除**（B5-S3）。 */
    private VBox buildTriggersTab() {
        triggers.getStyleClass().add("adb-structure__table");
        triggers.setId("structure-triggers");
        triggers.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        triggers.setPlaceholder(EmptyState.of(Icons.Icon.TRIGGER, I18n.t("structure.empty.triggers"),
                I18n.t("structure.hint.triggers"),
                I18n.t("structure.trigger.add"), this::createTrigger));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, addTrigger, dropTrigger, spacer);
        bar.getStyleClass().add("adb-inline-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(I18n.t("structure.note.triggers"));
        hint.getStyleClass().add("adb-muted");

        VBox box = new VBox(8, bar, hint, triggers);
        VBox.setVgrow(triggers, Priority.ALWAYS);
        return box;
    }

    private VBox buildChecksTab() {
        checks.getStyleClass().add("adb-structure__table");
        checks.setId("structure-checks");
        checks.setEditable(false);
        checks.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        checks.setPlaceholder(EmptyState.of(Icons.Icon.CHECK, I18n.t("structure.empty.checks"),
                I18n.t("structure.hint.checks")));

        Label hint = new Label(I18n.t("structure.note.checks"));
        hint.getStyleClass().add("adb-muted");

        VBox box = new VBox(8, hint, checks);
        VBox.setVgrow(checks, Priority.ALWAYS);
        return box;
    }

    private VBox buildIndexesTab() {
        indexes.getStyleClass().add("adb-structure__table");
        indexes.setId("structure-indexes");
        indexes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        indexes.setPlaceholder(EmptyState.of(Icons.Icon.INDEX, I18n.t("structure.empty.indexes"),
                I18n.t("structure.hint.indexes"),
                I18n.t("structure.index.add"), this::createIndex));
        indexes.setRowFactory(view -> {
            TableRow<IndexDef> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    indexes.getSelectionModel().select(row.getIndex());
                }
            });
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(indexMenu(row.getItem())));
            return row;
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(6, addIndex, dropIndex, spacer);
        bar.getStyleClass().add("adb-inline-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(I18n.t("structure.note.indexes"));
        hint.getStyleClass().add("adb-muted");

        VBox box = new VBox(8, bar, hint, indexes);
        VBox.setVgrow(indexes, Priority.ALWAYS);
        return box;
    }

    /** 数据库自动建的索引（主键/唯一约束的物理实现）不能删。 */
    private ContextMenu indexMenu(IndexDef index) {
        if (index == null || structure == null || !structure.editable()) {
            return new ContextMenu();
        }
        MenuItem drop = new MenuItem(I18n.t("structure.index.delete"));
        drop.setDisable(index.autoCreated()
                || index.name().equalsIgnoreCase(session.dialect().primaryIndexName()));
        drop.setOnAction(event -> dropIndex(index));
        MenuItem copyName = new MenuItem(I18n.t("structure.menu.copyIndexName"));
        copyName.setOnAction(event -> copy(index.name()));
        return new ContextMenu(drop, new SeparatorMenuItem(), copyName);
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

    /**
     * 标记列（U9，2026-10-26）：为真画一个 ✓ 图标，为假**留空**。
     *
     * <p>以前这两列渲染的是文本「✓」—— 与查询结果 / 数据网格里的布尔不是一套东西，
     * 而且文本字形不吃主题色、不吃字号。现在三处共用 {@link CellRenderer#checkGlyph}。
     * 这里刻意不画 ✗：「非唯一」不是一个需要被看见的状态，满列的 ✗ 只是噪声
     * （数据网格里的 ✗ 不同 —— 那里的 false 本身就是值）。
     */
    private <T> void addFlagColumn(TableView<T> table, String title, Function<T, Boolean> flag) {
        addBoolColumn(table, title, flag, false);
    }

    /**
     * 布尔标记列（U9）：为真画 ✓（成功色）；{@code showOff} 为真时为假画 ✗（禁用灰），
     * 否则留空 —— 假值本身要不要被看见，按列裁定（见 {@link #buildColumnsTab}）。
     */
    private <T> void addBoolColumn(TableView<T> table, String title,
                                   Function<T, Boolean> flag, boolean showOff) {
        TableColumn<T, T> column = new TableColumn<>(title);
        column.setSortable(false);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        column.setCellFactory(view -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setAlignment(Pos.CENTER);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                boolean on = Boolean.TRUE.equals(flag.apply(item));
                if (!on && !showOff) {
                    setGraphic(null);
                    return;
                }
                setGraphic(CellRenderer.checkGlyph(on ? Icons.Icon.CHECK : Icons.Icon.CLOSE));
            }
        });
        table.getColumns().add(column);
    }

    private void setIndexes(List<IndexDef> values) {
        indexes.getColumns().clear();
        addIndexIconColumn();
        addColumn(indexes, I18n.t("column.name"), value -> new ReadOnlyStringWrapper(value.name()));
        addFlagColumn(indexes, I18n.t("structure.col.unique"), IndexDef::unique);
        addColumn(indexes, I18n.t("structure.col.columns"), value -> new ReadOnlyStringWrapper(value.columnsLabel()));
        indexes.setItems(FXCollections.observableArrayList(values));
    }

    /**
     * 索引页签的行首图标列。
     *
     * <p>为什么加：索引表和列表、外键表长得一模一样，用户扫一眼分不出「这是索引」。
     * 一个 14px 的有序清单图标就能区分开 —— 与对象树里「表 / 视图 / 触发器」
     * 各用一种形状是同一套逻辑。
     *
     * <p>R35 N3：有彩色资产就分层上色（与导航树、对象首页同一口径），
     * 没有才回退单色并照旧吃 {@code -adb-kind-index}。
     */
    private void addIndexIconColumn() {
        TableColumn<IndexDef, IndexDef> column = new TableColumn<>("");
        column.setMinWidth(28);
        column.setMaxWidth(28);
        column.setSortable(false);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        column.setCellFactory(view -> new TableCell<>() {
            @Override
            protected void updateItem(IndexDef item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Node graphic;
                if (Icons.hasColoredVariant(Icons.Icon.INDEX)) {
                    graphic = Icons.colored(Icons.Icon.INDEX, Icons.SIZE_LABEL);
                } else {
                    SVGPath icon = Icons.of(Icons.Icon.INDEX, Icons.SIZE_LABEL);
                    icon.getStyleClass().add("adb-structure__index-icon");
                    graphic = icon;
                }
                setGraphic(graphic);
            }
        });
        indexes.getColumns().add(column);
    }

    private void setForeignKeys(List<ForeignKeyDef> values) {
        foreignKeys.getColumns().clear();
        addColumn(foreignKeys, I18n.t("column.name"), value -> new ReadOnlyStringWrapper(value.displayName()));
        addColumn(foreignKeys, I18n.t("structure.col.columns"), value -> new ReadOnlyStringWrapper(
                String.join(", ", value.columns())));
        addColumn(foreignKeys, I18n.t("structure.fk.referencedTable"), value -> new ReadOnlyStringWrapper(value.referencedTable()));
        addColumn(foreignKeys, I18n.t("structure.fk.referencedColumns"), value -> new ReadOnlyStringWrapper(
                String.join(", ", value.referencedColumns())));
        addColumn(foreignKeys, I18n.t("structure.fk.onUpdate"), value -> new ReadOnlyStringWrapper(value.onUpdate()));
        addColumn(foreignKeys, I18n.t("structure.fk.onDelete"), value -> new ReadOnlyStringWrapper(value.onDelete()));
        foreignKeys.setRowFactory(view -> {
            TableRow<ForeignKeyDef> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    foreignKeys.getSelectionModel().select(row.getIndex());
                }
            });
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(foreignKeyMenu(row.getItem())));
            return row;
        });
        foreignKeys.setItems(FXCollections.observableArrayList(values));
    }

    /** 外键右键菜单（B5-S2）。 */
    private ContextMenu foreignKeyMenu(ForeignKeyDef foreignKey) {
        if (foreignKey == null || structure == null || !structure.editable()) {
            return new ContextMenu();
        }
        MenuItem drop = new MenuItem(I18n.t("structure.fk.delete"));
        drop.setOnAction(event -> dropForeignKey(foreignKey));
        MenuItem copyName = new MenuItem(I18n.t("structure.menu.copyForeignKeyName"));
        copyName.setOnAction(event -> copy(foreignKey.name()));
        return new ContextMenu(drop, new SeparatorMenuItem(), copyName);
    }

    private void setChecks(List<io.agenticdb.introspect.CheckDef> values) {
        checks.getColumns().clear();
        addColumn(checks, I18n.t("column.name"), value -> new ReadOnlyStringWrapper(value.name()));
        addColumn(checks, I18n.t("structure.check.expression"), value -> new ReadOnlyStringWrapper(value.expression()));
        checks.setItems(FXCollections.observableArrayList(values));
    }

    private void setTriggers(List<TriggerDef> values) {
        triggers.getColumns().clear();
        addColumn(triggers, I18n.t("column.name"), value -> new ReadOnlyStringWrapper(value.name()));
        addColumn(triggers, I18n.t("structure.trigger.timing"), value -> new ReadOnlyStringWrapper(value.timingLabel()));
        addColumn(triggers, I18n.t("structure.trigger.event"), value -> new ReadOnlyStringWrapper(value.eventLabel()));
        addColumn(triggers, I18n.t("structure.trigger.statement"), value -> new ReadOnlyStringWrapper(value.statement()));
        triggers.setRowFactory(view -> {
            TableRow<TriggerDef> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    triggers.getSelectionModel().select(row.getIndex());
                }
            });
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(triggerMenu(row.getItem())));
            return row;
        });
        triggers.setItems(FXCollections.observableArrayList(values));
    }

    /** 触发器右键菜单（B5-S3）。 */
    private ContextMenu triggerMenu(TriggerDef trigger) {
        if (trigger == null || structure == null || !structure.editable()) {
            return new ContextMenu();
        }
        MenuItem drop = new MenuItem(I18n.t("structure.trigger.delete"));
        drop.setOnAction(event -> dropTrigger(trigger));
        MenuItem copyName = new MenuItem(I18n.t("structure.menu.copyTriggerName"));
        copyName.setOnAction(event -> copy(trigger.name()));
        return new ContextMenu(drop, new SeparatorMenuItem(), copyName);
    }

    public void reload() {
        if (running) {
            return;
        }
        setLoading(true);
        feedback.status(I18n.t("structure.status.loading", tableName));
        Task<Loaded> task = new Task<>() {
            @Override
            protected Loaded call() throws Exception {
                TableStructure loaded = schemaService.structure(session, tableName);
                // 选项是锦上添花：方言不提供时 tableOptions 不执行 SQL。
                return new Loaded(loaded, schemaService.tableOptions(session, tableName));
            }
        };
        task.setOnSucceeded(event -> {
            Loaded loaded = task.getValue();
            structure = loaded.structure();
            tableOptions = loaded.options();
            render();
            setLoading(false);
            updateButtons();
            // 标题栏的对象类型：内省结果出来才知道是表还是视图。
            if (objectHeader != null) {
                objectHeader.setType(I18n.t(structure.view() ? "detail.kind.view" : "detail.kind.table"));
            }
            // U8：页头已经写着是哪张表，「结构已加载」没有新增信息 —— 成功保持沉默。
            feedback.status("");
        });
        task.setOnFailed(event -> {
            setLoading(false);
            updateButtons();
            Throwable error = task.getException();
            feedback.status(I18n.t("structure.status.readFailed", readable(error)));
            feedback.toast(I18n.t("structure.status.readFailed", readable(error)));
        });
        submit(task);
    }

    private void render() {
        if (structure == null) {
            return;
        }
        kind.setText(I18n.t(structure.view() ? "detail.kind.view" : "detail.kind.table"));
        renderOptions(tableOptions);
        // ⭐ 表注释：以前结构页**一个字都看不到**（S4）。
        tableCommentLabel.setId("structure-table-comment-label");
        tableCommentLabel.getStyleClass().add("adb-muted");
        tableCommentLabel.setText(I18n.t("structure.tableComment.prefix",
                structure.comment().isBlank() ? "—" : structure.comment()));
        tableCommentLabel.setWrapText(true);
        summary.setText(I18n.t("structure.summary", structure.columns().size(),
                structure.primaryKeys().size(), structure.indexes().size(),
                structure.foreignKeys().size(), structure.checks().size(),
                structure.triggers().size()));
        ddlEditor.setText(structure.ddl());
        // ⭐ 「没有 SHOW CREATE TABLE」是方言的事实，不是 PostgreSQL 的硬编码。
        ddlNote.setText(!session.dialect().readsDdlFromDatabase() && !structure.view()
                ? I18n.t("structure.ddl.generatedNote", session.dialect().displayName())
                : "");
        columns.setItems(FXCollections.observableArrayList(structure.columns()));
        setIndexes(structure.indexes());
        setForeignKeys(structure.foreignKeys());
        setChecks(structure.checks());
        setTriggers(structure.triggers());
        updateButtons();
    }

    private Optional<ColumnDef> selectedColumn() {
        ColumnDef selected = columns.getSelectionModel().getSelectedItem();
        return selected == null ? Optional.empty() : Optional.of(selected);
    }

    private void showColumnDialog(String title, ColumnSpec initial) {
        if (structure == null || !structure.editable()) {
            return;
        }
        Dialog<ColumnSpec> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("structure.dialog.title"));
        dialog.setHeaderText(title);
        dialog.initOwner(sceneWindow());
        DialogStyles.apply(dialog, sceneWindow());

        TextField name = new TextField(initial == null ? "" : initial.name());
        name.setId("structure-dialog-name");
        name.setPromptText(I18n.t("structure.dialog.name"));

        ComboBox<String> type = new ComboBox<>();
        type.setId("structure-dialog-type");
        type.setEditable(true);
        type.getItems().setAll("TEXT", "VARCHAR(255)", "INTEGER", "REAL", "NUMERIC",
                "BOOLEAN", "DATE", "TIME", "TIMESTAMP", "BLOB", "JSON", "UUID");
        type.setValue(initial == null || initial.rawType().isBlank()
                ? "TEXT" : initial.rawType());

        CheckBox nullable = new CheckBox(I18n.t("structure.dialog.nullable"));
        nullable.setId("structure-dialog-nullable");
        nullable.setSelected(initial == null || initial.nullable());

        TextField defaultValue = new TextField(
                initial == null ? "" : (initial.defaultValue() == null ? "" : initial.defaultValue()));
        defaultValue.setId("structure-dialog-default");
        defaultValue.setPromptText(I18n.t("structure.dialog.defaultPrompt"));

        TextField comment = new TextField(
                initial == null ? "" : (initial.comment() == null ? "" : initial.comment()));
        comment.setId("structure-dialog-comment");
        comment.setPromptText(I18n.t("structure.dialog.commentPrompt"));
        // SQLite 没有列注释：输入框可打字但存不住、读不回，是「数据丢失幻觉」。
        // 按能力禁用并说明，而不是留一个骗人的输入框（4.5-22）。
        boolean commentSupported = session.dialect().supportsColumnComment();
        comment.setDisable(!commentSupported);
        if (!commentSupported) {
            comment.setPromptText(I18n.t("structure.dialog.commentUnsupported", session.dialect().displayName()));
        }

        CheckBox unique = new CheckBox(I18n.t("structure.col.unique"));
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
        grid.addRow(0, new Label(I18n.t("structure.dialog.name")), name);
        grid.addRow(1, new Label(I18n.t("detail.type")), type);
        grid.addRow(2, new Label(I18n.t("structure.col.default")), defaultValue);
        grid.addRow(3, new Label(I18n.t("column.comment")), comment);
        grid.addRow(4, nullable, unique);
        grid.add(error, 0, 5, 2, 1);
        dialog.getDialogPane().setContent(grid);

        ButtonType ok = new ButtonType(I18n.t("common.ok"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
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
            return new ColumnSpec(
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
                feedback.status(I18n.t("structure.status.unchanged"));
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
            return I18n.t("structure.error.emptyName");
        }
        if (!VALID_COLUMN_NAME.matcher(name).matches()) {
            return I18n.t("structure.error.badName");
        }
        String type = rawType == null ? "" : rawType.trim();
        if (type.isEmpty()) {
            return I18n.t("structure.error.emptyType");
        }
        for (ColumnDef column : existing) {
            // 改名时自己那个旧名字不算冲突。
            if (selfName != null && column.name().equalsIgnoreCase(selfName)) {
                continue;
            }
            if (column.name().equalsIgnoreCase(name)) {
                return I18n.t("structure.error.duplicateName", name);
            }
        }
        return null;
    }

    /** 列名合法性：与 {@code StructureSqlBuilder} 的生成规则保持一致。 */
    private static final java.util.regex.Pattern VALID_COLUMN_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private void renameColumn(ColumnDef column) {
        TextInputDialog dialog = new TextInputDialog(column.name());
        dialog.setTitle(I18n.t("structure.dialog.renameTitle"));
        dialog.setHeaderText(I18n.t("structure.dialog.renameHeader", column.name()));
        dialog.initOwner(sceneWindow());
        // ⭐ TextInputDialog 也是 Dialog：不套就是 Modena 灰皮肤（§七 统一弹窗外观）。
        DialogStyles.apply(dialog, sceneWindow());
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
            feedback.status(I18n.t("structure.status.busy"));
            return;
        }
        if (plan.empty()) {
            return;
        }
        if (!confirmation.test(plan)) {
            feedback.status(I18n.t("structure.status.cancelled"));
            return;
        }
        running = true;
        updateButtons();
        feedback.status(I18n.t("structure.status.applying"));
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
            feedback.toast(I18n.t("structure.status.done", plan.title()));
            feedback.status(I18n.t("structure.status.done", plan.title()));
            onChanged.run();
            reload();
        });
        task.setOnFailed(event -> {
            running = false;
            updateButtons();
            Throwable error = task.getException();
            feedback.status(I18n.t("structure.status.failed", readable(error)));
            feedback.toast(I18n.t("structure.status.failed", readable(error)));
        });
        submit(task);
    }

    private void execute(StructurePlan plan) throws Exception {
        // 事务边界与表级生命周期共用 —— 见 StructurePlanRunner。
        StructurePlanRunner.execute(session, plan);
    }

    private boolean defaultConfirm(StructurePlan plan) {
        return StructurePlanRunner.confirm(sceneWindow(), sceneStylesheets(), plan);
    }

    private List<String> sceneStylesheets() {
        return getScene() == null ? List.of() : List.copyOf(getScene().getStylesheets());
    }

    // ─────────────────────────── S1 索引 ───────────────────────────

    private Optional<IndexDef> selectedIndex() {
        IndexDef selected = indexes.getSelectionModel().getSelectedItem();
        return selected == null ? Optional.empty() : Optional.of(selected);
    }

    private void createIndex() {
        if (structure == null || !structure.editable()) {
            feedback.status(I18n.t("structure.blocked.viewIndex"));
            return;
        }
        if (running) {
            feedback.status(I18n.t("structure.status.busy"));
            return;
        }
        IndexDialog dialog = IndexDialog.create(session, structure);
        dialog.show(sceneWindow(), sceneStylesheets())
                .ifPresent(index -> applyPlan(
                        StructureSqlBuilder.createIndex(session, structure, index)));
    }

    private void dropIndex(IndexDef index) {
        if (index == null) {
            return;
        }
        if (index.autoCreated()
                || index.name().equalsIgnoreCase(session.dialect().primaryIndexName())) {
            feedback.status(I18n.t("structure.blocked.autoIndex", index.name()));
            return;
        }
        applyPlan(StructureSqlBuilder.dropIndex(session, structure, index.name()));
    }

    /**
     * ⭐ B5-S4：改表注释。
     *
     * <p>SQLite 没有表注释 → 按钮禁用（而不是点了才报错）。
     */
    private void showTableCommentDialog() {
        if (structure == null || !structure.editable()) {
            feedback.status(I18n.t("structure.blocked.noTable"));
            return;
        }
        if (running) {
            feedback.status(I18n.t("structure.status.busy"));
            return;
        }
        if (!session.dialect().supportsTableComment()) {
            feedback.status(I18n.t("structure.blocked.dialectComment", session.dialect().displayName()));
            return;
        }
        TableCommentDialog dialog = TableCommentDialog.create(session, structure);
        dialog.show(sceneWindow(), sceneStylesheets())
                .ifPresent(comment -> applyPlan(
                        StructureSqlBuilder.setTableComment(session, structure, comment)));
    }

    // ─────────────────────────── B5-S2/S3 外键 / 触发器 ───────────────────────────

    private void createForeignKey() {
        if (structure == null || !structure.editable()) {
            feedback.status(I18n.t("structure.blocked.viewFk"));
            return;
        }
        if (running) {
            feedback.status(I18n.t("structure.status.busy"));
            return;
        }
        ForeignKeyDialog dialog = ForeignKeyDialog.create(session, structure);
        dialog.show(sceneWindow(), sceneStylesheets())
                .ifPresent(foreignKey -> applyPlan(
                        StructureSqlBuilder.addForeignKey(session, structure, foreignKey)));
    }

    private void dropForeignKey(ForeignKeyDef foreignKey) {
        if (foreignKey == null) {
            return;
        }
        applyPlan(StructureSqlBuilder.dropForeignKey(session, structure, foreignKey));
    }

    private void createTrigger() {
        if (structure == null || !structure.editable()) {
            feedback.status(I18n.t("structure.blocked.viewTrigger"));
            return;
        }
        if (running) {
            feedback.status(I18n.t("structure.status.busy"));
            return;
        }
        TriggerDialog dialog = TriggerDialog.create(session, structure);
        dialog.show(sceneWindow(), sceneStylesheets())
                .ifPresent(spec -> applyPlan(StructureSqlBuilder.createTrigger(
                        session, structure, spec.name(), spec.timing(), spec.event(), spec.body())));
    }

    private void dropTrigger(TriggerDef trigger) {
        if (trigger == null) {
            return;
        }
        applyPlan(StructureSqlBuilder.dropTrigger(session, structure, trigger));
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
        // 索引：只有结构可编辑时才能增删，且删除还要先选中一个「可删」的索引。
        addIndex.setDisable(running || !editable);
        Optional<IndexDef> index = selectedIndex();
        dropIndex.setDisable(running || !editable || index.isEmpty()
                || index.get().autoCreated()
                || index.get().name().equalsIgnoreCase(session.dialect().primaryIndexName()));
        // 外键 / 触发器：结构可编辑才能增删；删除还要先选中一个。
        addForeignKey.setDisable(running || !editable);
        dropForeignKey.setDisable(running || !editable
                || foreignKeys.getSelectionModel().getSelectedItem() == null);
        addTrigger.setDisable(running || !editable);
        dropTrigger.setDisable(running || !editable
                || triggers.getSelectionModel().getSelectedItem() == null);
        // 表注释：SQLite 没有这个概念 → 直接禁用（而不是点了才报错）。
        tableComment.setDisable(running || !editable
                || !session.dialect().supportsTableComment());
        // ⭐ U19（p4-structure 实拍）：灰是对的，缺的是「为什么灰、怎么做才亮」。
        paintDisableReasons(running, editable);
    }

    /** 禁用态的「原因」tooltip —— 与 {@link #updateButtons()} 的禁用位同一批条件。 */
    private void paintDisableReasons(boolean running, boolean editable) {
        boolean columnSelected = selectedColumn().isPresent();
        for (Button button : java.util.List.of(edit, rename, drop)) {
            paintDisableReason(button, running, I18n.t("structure.status.busy"),
                    !editable, I18n.t("structure.blocked.viewStructure"),
                    !columnSelected, I18n.t("structure.blocked.noColumn"));
        }
        paintDisableReason(addIndex, running, I18n.t("structure.status.busy"),
                !editable, I18n.t("structure.blocked.viewIndex"), false, "");
        paintDisableReason(addForeignKey, running, I18n.t("structure.status.busy"),
                !editable, I18n.t("structure.blocked.viewFk"), false, "");
        paintDisableReason(addTrigger, running, I18n.t("structure.status.busy"),
                !editable, I18n.t("structure.blocked.viewTrigger"), false, "");
        paintDisableReason(tableComment, running, I18n.t("structure.status.busy"),
                !editable, I18n.t("structure.blocked.viewStructure"),
                !session.dialect().supportsTableComment(), I18n.t("structure.blocked.dialectCommentTip"));
    }

    /** 「条件 → 文案」成对传入，第一个命中的条件就是 tooltip；可用时清掉 tooltip。 */
    private static void paintDisableReason(Button button, Object... conditionThenReason) {
        String reason = null;
        for (int i = 0; i + 1 < conditionThenReason.length; i += 2) {
            if (Boolean.TRUE.equals(conditionThenReason[i])) {
                reason = (String) conditionThenReason[i + 1];
                break;
            }
        }
        button.setTooltip(reason == null ? null : new javafx.scene.control.Tooltip(reason));
    }

    private void copy(String text) {
        if (text == null || text.isBlank()) {
            feedback.status(I18n.t("structure.copy.empty"));
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        feedback.status(I18n.t("structure.copy.done"));
        feedback.toast(I18n.t("structure.copy.done"));
    }

    private javafx.stage.Window sceneWindow() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static String readable(Throwable error) {
        // ⭐ V-12：统一走错误归类 —— 给出「发生了什么 + 建议怎么办」，
        //    而不是把驱动的英文原文直接甩给用户。
        return io.agenticdb.safety.ErrorClassifier.classify(error).message();
    }

    private Future<?> submit(Task<?> task) {
        if (dbExecutor instanceof ExecutorService service) {
            return service.submit(task);
        }
        dbExecutor.execute(task);
        return null;
    }

    /** 一次后台加载的成果：结构 + 物理选项（选项失败不影响结构）。 */
    private record Loaded(TableStructure structure, TableOptions options) {
    }

    @Override
    public void close() {
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
    }
}
