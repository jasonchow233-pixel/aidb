package io.agenticdb.ui.shell;

import io.agenticdb.editing.EditBuffer;
import io.agenticdb.editing.EditSqlBuilder;
import io.agenticdb.editing.RowIdentity;
import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.DataSqlBuilder;
import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.introspect.SortSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.Danger;
import io.agenticdb.model.PageSize;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;
import io.agenticdb.safety.DangerClassifier;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 只读数据页：列头 + 类型行 + 服务端分页。
 *
 * <p>三条不变量：
 * <ul>
 *   <li>任何时刻内存里最多一页；绝不把整表塞进 {@code ObservableList}。</li>
 *   <li>SQL 只由 {@link SchemaService#pageSql} 生成，翻页必定改变 LIMIT/OFFSET。</li>
 *   <li>连接只在共享的单线程执行器上访问，UI 线程不做 JDBC。</li>
 * </ul>
 */
public final class DataGridPage extends VBox implements AutoCloseable {

    public interface Feedback {
        void status(String message);

        void range(String text);

        void duration(long millis);

        default void dirty(int count) {
        }

        default void readonly(boolean readonly) {
        }

        /**
         * 当前选中的行数（4.5-11）。
         *
         * <p>状态栏的「选择」段以前永远是「未选择」——这个 setter 零调用。
         */
        default void selection(int count) {
        }

        /**
         * 会话是否处于事务中（4.5-11）。
         *
         * <p>状态栏的「事务」段以前永远是「自动提交」。
         */
        default void transaction(boolean inTransaction) {
        }

        default void toast(String message) {
        }

        default void toast(String message, Runnable action) {
            toast(message);
        }

        /**
         * 「结果不可撤销」类提示：没有撤销按钮，但用户需要更长时间反应过来
         * （如新增行无主键、保存后无法反向撤销）。默认退化为普通 toast。
         */
        default void toastUnreversible(String message) {
            toast(message);
        }
    }

    private record LoadResult(TableSchema schema, long total, RowPage page,
                              long elapsedMs, boolean metadataFresh) {
    }

    private final DbSession session;
    private final SchemaService schemaService;
    private final String tableName;
    private final String connectionName;
    private final Feedback feedback;
    private final Consumer<String> openStructure;
    private final Executor dbExecutor;

    private final TableView<Map<String, Object>> grid = new TableView<>();
    private final StackPane body = new StackPane();
    private final VBox stateBox = new VBox(10);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label stateText = new Label();
    private final Button retry = new Button("重试");
    private final Label rangeLabel = new Label("—");
    private final Label pageLabel = new Label("—");
    private final Button previous = new Button("‹ 上一页");
    private final Button next = new Button("下一页 ›");
    private final Button firstPageButton = new Button("⇤");
    private final Button lastPageButton = new Button("⇥");
    private final TextField jumpField = new TextField();
    private final ComboBox<Integer> pageSizeBox = new ComboBox<>();
    private final Button filterButton = new Button("筛选");
    private final Button clearQueryButton = new Button("清除条件");
    private final Button undoButton = new Button("↶ 撤销");
    private final Button redoButton = new Button("↷ 重做");
    private final Button saveButton = new Button("保存");
    private final Button addRowButton = new Button("＋ 新增");
    private final MenuButton columnsButton = new MenuButton("列");
    private final MenuButton viewButton = new MenuButton("视图");
    private final MenuButton exportButton = new MenuButton("导出");
    private final TextField searchField = new TextField();
    private final CheckMenuItem hideNullItem = new CheckMenuItem("隐藏含 NULL 行");
    private final CheckMenuItem hideEmptyItem = new CheckMenuItem("隐藏含空串行");
    private final CheckMenuItem transposeItem = new CheckMenuItem("转置（只读）");
    private final TableView<Map<String, Object>> transposeTable = new TableView<>();
    private final CellDetailsDrawer detailsDrawer = new CellDetailsDrawer();
    private final Label contextLabel = new Label();
    private final FlowPane chipBar = new FlowPane(6, 6);
    private final HBox batchBar = new HBox(8);
    private final Label batchLabel = new Label();
    private final Button batchNull = new Button("设 NULL");
    private final Button batchEmpty = new Button("设空串");
    private final Button batchDelete = new Button("删除");
    private final Button batchClone = new Button("克隆");
    private final CheckBox selectAllBox = new CheckBox();
    private final Map<String, HeaderView> headers = new LinkedHashMap<>();
    private final Map<String, TableColumn<Map<String, Object>, Object>> columnByDefinition =
            new LinkedHashMap<>();
    private final EditBuffer editBuffer;
    private final SettingsStore settings;
    private IntConsumer dirtyListener = count -> { };
    private javafx.animation.PauseTransition layoutSaveTimer;

    private record HeaderView(Label name, Label sortBadge, Button filter) {
    }

    private TableSchema tableSchema;
    private DataQuery query = DataQuery.empty();
    private int pageSize = QueryLimits.DEFAULT_PAGE_SIZE;
    private long offset;
    private long total = -1;
    private int lastRowCount;
    private long lastElapsedMs = -1;
    private long requestSequence;
    private Future<?> inFlight;
    private boolean loading;
    private boolean saving;
    private boolean undoingSave;
    private boolean activated;
    private boolean hideNullRows;
    private boolean hideEmptyRows;
    private boolean transpose;
    private EditSqlBuilder.Plan saveUndoPlan;
    private String lastSql = "";
    private List<Map<String, Object>> pageRows = List.of();

    public DataGridPage(DbSession session,
                        SchemaService schemaService,
                        String tableName,
                        String connectionName,
                        Feedback feedback,
                        Consumer<String> openStructure,
                        Executor dbExecutor) {
        this(session, schemaService, tableName, connectionName, feedback,
                openStructure, dbExecutor, null);
    }

    public DataGridPage(DbSession session,
                        SchemaService schemaService,
                        String tableName,
                        String connectionName,
                        Feedback feedback,
                        Consumer<String> openStructure,
                        Executor dbExecutor,
                        SettingsStore settings) {
        this.session = Objects.requireNonNull(session, "session");
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.connectionName = connectionName;
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.openStructure = Objects.requireNonNull(openStructure, "openStructure");
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor");
        this.settings = settings;
        this.editBuffer = new EditBuffer(tableName);

        getStyleClass().addAll("adb-page", "adb-grid-page");
        buildUi();
        installShortcuts();
        updateEditControls();
        load(true);
    }

    public void setDirtyListener(IntConsumer listener) {
        dirtyListener = listener == null ? count -> { } : listener;
        dirtyListener.accept(editBuffer.dirtyCount());
    }

    public int dirtyCount() {
        return editBuffer.dirtyCount();
    }

    private void installShortcuts() {
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && detailsDrawer.isOpen()) {
                detailsDrawer.close();
                event.consume();
                return;
            }
            if (event.getCode() == KeyCode.ESCAPE && searchField.isFocused()
                    && !searchField.getText().isEmpty()) {
                searchField.clear();
                event.consume();
                return;
            }
            if (event.getTarget() instanceof javafx.scene.control.TextInputControl) {
                return;
            }
            if (event.isShortcutDown() && event.getCode() == KeyCode.Z) {
                if (event.isShiftDown()) {
                    redo();
                } else {
                    undo();
                }
                event.consume();
            } else if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
                saveChanges();
                event.consume();
            } else if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
                searchField.requestFocus();
                searchField.selectAll();
                event.consume();
            } else if (event.isShortcutDown() && !event.isShiftDown() && !event.isAltDown()
                    && event.getCode() == KeyCode.C) {
                // ⌘C 复制当前单元格的值
                copyFocusedCellValue();
                event.consume();
            } else if (event.isShortcutDown() && event.isAltDown()
                    && event.getCode() == KeyCode.C) {
                // ⌥⌘C 复制选中行（无选中则复制当前行）为 TSV
                copyRowsAsTsv(rowsForCopy(), "选中行");
                event.consume();
            } else if (event.isShortcutDown() && event.isShiftDown()
                    && event.getCode() == KeyCode.C) {
                // ⇧⌘C 复制为 INSERT
                copyRowsAsSql(rowsForCopy(), "选中行");
                event.consume();
            } else if (event.isShortcutDown() && !event.isShiftDown() && !event.isAltDown()
                    && event.getCode() == KeyCode.A) {
                // ⌘A 全选当前页
                selectAllCurrentPage();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE
                    && searchField.isFocused() && !searchField.getText().isEmpty()) {
                searchField.clear();
                event.consume();
            }
        });
    }

    /** 撤销上一处编辑（编辑菜单 / 快捷键共用）。 */
    public void undo() {
        if (saving || loading) {
            return;
        }
        int draftsBefore = editBuffer.drafts().size();
        if (editBuffer.undo()) {
            if (draftsBefore != editBuffer.drafts().size()) {
                rebuildItems();
            } else {
                grid.refresh();
            }
            updateDirty();
            feedback.status("已撤销 · " + editBuffer.dirtyCount() + " 处待保存");
        }
    }

    /** 重做（编辑菜单 / 快捷键共用）。 */
    public void redo() {
        if (saving || loading) {
            return;
        }
        int draftsBefore = editBuffer.drafts().size();
        if (editBuffer.redo()) {
            if (draftsBefore != editBuffer.drafts().size()) {
                rebuildItems();
            } else {
                grid.refresh();
            }
            updateDirty();
            feedback.status("已重做 · " + editBuffer.dirtyCount() + " 处待保存");
        }
    }

    /** 编辑缓冲里是否还能撤销（决定菜单项 enabled）。 */
    public boolean canUndo() {
        return tableSchema != null && tableSchema.editable()
                && !loading && !saving && editBuffer.canUndo();
    }

    /** 编辑缓冲里是否还能重做（决定菜单项 enabled）。 */
    public boolean canRedo() {
        return tableSchema != null && tableSchema.editable()
                && !loading && !saving && editBuffer.canRedo();
    }

    /** 上一次保存是否留有可撤销的反向 SQL。 */
    public boolean hasPendingSaveUndo() {
        return saveUndoPlan != null && !saveUndoPlan.isEmpty() && !undoingSave && !saving;
    }

    private void updateDirty() {
        int count = editBuffer.dirtyCount();
        if (count > 0) {
            // 新编辑一旦发生，上一次保存的“反向撤销”就不再安全。
            saveUndoPlan = null;
        }
        updateEditControls();
        updateBatchBar();
        feedback.dirty(count);
        dirtyListener.accept(count);
    }

    private void updateEditControls() {
        boolean editable = tableSchema != null && tableSchema.editable();
        undoButton.setDisable(!editable || loading || saving || !editBuffer.canUndo());
        redoButton.setDisable(!editable || loading || saving || !editBuffer.canRedo());
        saveButton.setDisable(!editable || loading || saving || !editBuffer.isDirty());
        saveButton.setText(editBuffer.isDirty()
                ? "保存 " + editBuffer.dirtyCount() : "保存");
        addRowButton.setDisable(!editable || loading || saving);
        updateSelectAllState();
        contextLabel.setText(connectionName + (editable ? " · 可编辑" : " · 只读"));
        if (tableSchema != null) {
            feedback.readonly(!editable);
        }
    }

    private void saveChanges() {
        if (tableSchema == null || !tableSchema.editable() || !editBuffer.isDirty() || loading || saving) {
            return;
        }
        EditSqlBuilder.Plan plan;
        try {
            plan = EditSqlBuilder.plan(session, tableSchema, editBuffer);
        } catch (RuntimeException e) {
            feedback.status("无法生成保存 SQL：" + readable(e));
            return;
        }
        if (plan.isEmpty() || !confirmSqlPreview(plan, planDanger(plan))) {
            return;
        }
        EditSqlBuilder.Plan undoPlan;
        try {
            undoPlan = EditSqlBuilder.inversePlan(session, tableSchema, editBuffer);
        } catch (RuntimeException e) {
            undoPlan = new EditSqlBuilder.Plan(List.of(), false);
        }

        saveButton.setDisable(true);
        saveButton.setText("保存中…");
        saving = true;
        grid.setDisable(true);
        // 4.5-11：状态栏的「事务」段接真实数据 —— 保存跑在单事务里。
        feedback.transaction(true);
        EditSqlBuilder.Plan inverse = undoPlan;
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return EditSqlBuilder.execute(session, plan);
            }
        };
        task.setOnSucceeded(event -> {
            int affected = task.getValue();
            saving = false;
            grid.setDisable(false);
            feedback.transaction(false);
            saveUndoPlan = inverse;
            editBuffer.clear();
            updateDirty();
            feedback.status("已保存 " + plan.size() + " 条变更 · 影响 " + affected + " 行");
            if (!inverse.isEmpty() && inverse.complete()) {
                feedback.toast("已保存 " + plan.size() + " 条变更 · 可撤销", this::undoLastSave);
            } else if (!inverse.isEmpty()) {
                feedback.toastUnreversible("已保存 " + plan.size()
                        + " 条变更（新增行无主键，不能反向撤销）");
            } else {
                feedback.toast("已保存 " + plan.size() + " 条变更");
            }
            load(true);
        });
        task.setOnFailed(event -> {
            saving = false;
            grid.setDisable(false);
            feedback.transaction(false);
            updateEditControls();
            feedback.status("保存失败：" + readable(task.getException()));
            showErrorDialog("保存失败", readable(task.getException()));
        });
        dbExecutor.execute(task);
    }

    /** Toast 上的“撤销保存”（编辑菜单共用）：执行保存前生成的反向 SQL，再刷新当前页。 */
    public void undoLastSave() {
        EditSqlBuilder.Plan inverse = saveUndoPlan;
        if (inverse == null || inverse.isEmpty() || undoingSave || saving) {
            return;
        }
        undoingSave = true;
        feedback.status("正在撤销上次保存…");
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return EditSqlBuilder.execute(session, inverse);
            }
        };
        task.setOnSucceeded(event -> {
            undoingSave = false;
            saveUndoPlan = null;
            feedback.status("已撤销上次保存 · 影响 " + task.getValue() + " 行");
            feedback.toast("已撤销上次保存");
            load(true);
        });
        task.setOnFailed(event -> {
            undoingSave = false;
            feedback.status("撤销保存失败：" + readable(task.getException()));
            showErrorDialog("撤销保存失败", readable(task.getException()));
        });
        dbExecutor.execute(task);
    }

    /**
     * 保存计划的风险等级 —— 取所有语句里最危险的那条。
     *
     * <p>⭐ 数据页保存路径以前<b>完全不分类</b>：危险 SQL 在查询页被拦住了，
     * 在保存路径却没拦，等于门禁是空的。现在接上 {@link DangerClassifier}，
     * 与查询页共用同一套分级语义。
     */
    Danger planDanger(EditSqlBuilder.Plan plan) {
        Danger worst = Danger.READ_ONLY;
        for (EditSqlBuilder.Statement statement : plan.statements()) {
            worst = worst.worse(DangerClassifier.classify(statement.sql()));
        }
        return worst;
    }

    /**
     * 保存前确认：展示将要执行的 SQL，并按风险等级决定确认强度。
     *
     * <p>⭐ <b>默认按钮必须是「取消」</b>。以前这里用的是
     * {@code ButtonType("执行", ButtonData.OK_DONE)} —— {@code OK_DONE}
     * 语义上就是「默认且确认」，于是<b>回车 = 直接执行破坏性 SQL</b>。
     * 现在「执行」用 {@code OTHER}，并在弹窗显示后把默认按钮钉在「取消」上
     * （{@link DestructiveConfirm#pinCancelAsDefault}）。
     *
     * <p>高风险（改结构 / 管理操作）额外走一级 {@link DestructiveConfirm}。
     */
    private boolean confirmSqlPreview(EditSqlBuilder.Plan plan, Danger danger) {
        if (danger.requiresApproval()) {
            DestructiveConfirm confirm = DestructiveConfirm.of(
                    "保存修改",
                    plan.size() + " 条变更 · 风险等级：" + danger.label(),
                    "以下 SQL 会在单个事务里执行，任一条失败整体回滚。\n\n"
                            + plan.preview(),
                    "继续");
            if (!confirm.confirmed()) {
                return false;
            }
        }
        Dialog<ButtonType> dialog = buildSavePreviewDialog(plan, danger);
        // ⚠️ 必须用弹窗上**同一个** ButtonType 实例来比对：
        //    ButtonType 没有覆写 equals，新建的实例比不上。
        ButtonType execute = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> "执行".equals(type.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("保存预览弹窗缺少「执行」按钮"));
        return dialog.showAndWait().filter(execute::equals).isPresent();
    }

    /** 保存预览弹窗的「执行」按钮类型。抽出来是为了测试能断言它不是默认按钮。 */
    static ButtonType executeButtonType() {
        // ⭐ OTHER：不是默认按钮，回车不会落在「执行」上。
        return new ButtonType("执行", ButtonBar.ButtonData.OTHER);
    }

    /** 保存预览弹窗的「取消」按钮类型。 */
    static ButtonType cancelButtonType() {
        return new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    /**
     * 构造保存预览弹窗（不显示）。
     *
     * <p>抽成独立方法是为了让门禁能直接断言「默认按钮不是执行」，
     * 而不用真的把模态弹窗弹出来。
     */
    Dialog<ButtonType> buildSavePreviewDialog(EditSqlBuilder.Plan plan, Danger danger) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("保存修改");
        dialog.setHeaderText(plan.size() + " 条变更 · 单事务执行 · 风险：" + danger.label());
        ButtonType execute = executeButtonType();
        ButtonType cancel = cancelButtonType();
        dialog.getDialogPane().getButtonTypes().addAll(execute, cancel);
        dialog.setResultConverter(type -> type);

        TextArea sql = new TextArea(plan.preview());
        sql.setEditable(false);
        sql.setWrapText(false);
        sql.getStyleClass().add("adb-sql-preview");
        sql.setPrefColumnCount(72);
        sql.setPrefRowCount(Math.min(18, Math.max(4, plan.size() + 2)));

        Label badge = new Label(danger.label());
        badge.getStyleClass().add("adb-query__risk");
        badge.getStyleClass().add(riskStyleClass(danger));

        VBox content = new VBox(8, badge, sql);
        content.setPadding(new Insets(0, 0, 0, 0));
        VBox.setVgrow(sql, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("adb-dialog");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
            dialog.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
        }
        // ⭐ 默认按钮钉在「取消」上。必须在 skin 创建之后（shown 之后）才生效。
        dialog.setOnShown(event -> DestructiveConfirm.pinCancelAsDefault(
                dialog.getDialogPane(), cancel, execute));
        return dialog;
    }

    /** 风险等级对应的样式类。 */
    private static String riskStyleClass(Danger danger) {
        return switch (danger) {
            case READ_ONLY -> "adb-risk--read";
            case MUTATING -> "adb-risk--write";
            case DESTRUCTIVE, ADMIN -> "adb-risk--structure";
        };
    }

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            alert.getDialogPane().getStyleClass().add("adb-dialog");
        }
        alert.showAndWait();
    }

    // ─────────────────────────── 结构 ───────────────────────────

    private void buildUi() {
        HBox header = new HBox(10);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(tableName);
        title.getStyleClass().add("adb-page__title");

        Label badge = new Label("数据");
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        contextLabel.setText(connectionName + " · 只读");
        contextLabel.getStyleClass().add("adb-muted");
        header.getChildren().addAll(title, badge, spacer, contextLabel);

        HBox toolbar = new HBox(6);
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refresh = new Button("↻ 刷新");
        refresh.setId("data-refresh");
        refresh.getStyleClass().addAll("adb-button", "adb-button--ghost");
        refresh.setTooltip(new Tooltip("重新统计总数并拉取当前页 (F5)"));
        refresh.setOnAction(event -> refresh());

        Button structure = new Button("☰ 结构");
        structure.setId("data-structure");
        structure.getStyleClass().addAll("adb-button", "adb-button--ghost");
        structure.setOnAction(event -> openStructure.accept(tableName));

        addRowButton.setId("data-add");
        addRowButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        addRowButton.setTooltip(new Tooltip("在缓冲中新增一行；整行淡绿，保存后写库"));
        addRowButton.setDisable(true);
        addRowButton.setOnAction(event -> addRow());

        undoButton.setId("data-undo");
        undoButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        undoButton.setTooltip(new Tooltip("撤销上一处编辑 (⌘Z)"));
        undoButton.setOnAction(event -> undo());

        redoButton.setId("data-redo");
        redoButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        redoButton.setTooltip(new Tooltip("重做 (⇧⌘Z)"));
        redoButton.setOnAction(event -> redo());

        saveButton.setId("data-save");
        saveButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        saveButton.setTooltip(new Tooltip("预览 SQL 并保存全部修改 (⌘S)"));
        saveButton.setOnAction(event -> saveChanges());

        filterButton.setId("data-filter");
        filterButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        filterButton.setDisable(true);
        filterButton.setTooltip(new Tooltip("打开条件构造器；列头漏斗可按候选值筛选"));
        filterButton.setOnAction(event -> showConditionDialog());

        clearQueryButton.setId("data-clear-query");
        clearQueryButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        clearQueryButton.setDisable(true);
        clearQueryButton.setOnAction(event -> clearQuery());

        columnsButton.setId("data-columns");
        columnsButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        columnsButton.setDisable(true);
        columnsButton.setTooltip(new Tooltip("显示/隐藏列；列宽、顺序、显隐会持久化"));

        viewButton.setId("data-view");
        viewButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        viewButton.setDisable(true);
        viewButton.setTooltip(new Tooltip("本页视图：隐藏 NULL / 空串行、转置"));
        hideNullItem.setId("data-hide-null");
        hideNullItem.setOnAction(event -> setHideNullRows(hideNullItem.isSelected()));
        hideEmptyItem.setId("data-hide-empty");
        hideEmptyItem.setOnAction(event -> setHideEmptyRows(hideEmptyItem.isSelected()));
        transposeItem.setId("data-transpose-toggle");
        transposeItem.setOnAction(event -> setTranspose(transposeItem.isSelected()));
        viewButton.getItems().addAll(hideNullItem, hideEmptyItem,
                new SeparatorMenuItem(), transposeItem);

        exportButton.setId("data-export");
        exportButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        exportButton.setDisable(true);
        exportButton.setTooltip(new Tooltip("导出当前页或复制到剪贴板"));
        MenuItem exportCsv = new MenuItem("导出当前页为 CSV…");
        exportCsv.setId("data-export-csv");
        exportCsv.setOnAction(event -> exportCsv());
        MenuItem copyPage = new MenuItem("复制当前页 TSV");
        copyPage.setId("data-export-tsv");
        copyPage.setOnAction(event -> copyRowsAsTsv(List.copyOf(grid.getItems()), "当前页"));
        MenuItem copyPageSql = new MenuItem("复制当前页为 SQL");
        copyPageSql.setId("data-export-sql");
        copyPageSql.setOnAction(event -> copyRowsAsSql(List.copyOf(grid.getItems()), "当前页"));
        exportButton.getItems().addAll(exportCsv, copyPage, copyPageSql);

        searchField.setId("data-search");
        searchField.getStyleClass().add("adb-grid__search");
        searchField.setPromptText("⌕ 本页搜索");
        searchField.setPrefWidth(168);
        searchField.setTooltip(new Tooltip("只在当前页内过滤 (⌘F)"));
        searchField.textProperty().addListener((observable, previous, value) -> rebuildItems());

        toolbar.getChildren().addAll(refresh, structure, addRowButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                undoButton, redoButton, saveButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                filterButton, clearQueryButton, columnsButton, viewButton, exportButton,
                new Separator(javafx.geometry.Orientation.VERTICAL), searchField);

        chipBar.setId("data-query-chips");
        chipBar.getStyleClass().add("adb-query-chips");
        chipBar.setAlignment(Pos.CENTER_LEFT);
        chipBar.setVisible(false);
        chipBar.setManaged(false);

        grid.setId("data-table");
        grid.getStyleClass().add("adb-grid");
        grid.setPlaceholder(new Label("没有数据"));
        grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        grid.setFocusTraversable(true);
        grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        selectAllBox.setId("data-select-all");
        selectAllBox.setTooltip(new Tooltip("全选当前页"));
        selectAllBox.setOnAction(event -> {
            if (selectAllBox.isSelected()) {
                grid.getSelectionModel().selectAll();
            } else {
                grid.getSelectionModel().clearSelection();
            }
        });
        grid.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Map<String, Object>>) change -> {
                    updateBatchBar();
                    updateSelectAllState();
                    // 4.5-11：把选中行数报给状态栏。
                    feedback.selection(grid.getSelectionModel().getSelectedItems().size());
                    grid.refresh();
                });
        grid.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Map<String, Object> item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("adb-grid__row--deleted", "adb-grid__row--new");
                if (!empty && item != null) {
                    if (draftId(item) != null) {
                        getStyleClass().add("adb-grid__row--new");
                    } else {
                        RowIdentity identity = identity(item);
                        if (identity != null && editBuffer.isDeleted(identity)) {
                            getStyleClass().add("adb-grid__row--deleted");
                        }
                    }
                }
            }
        });
        grid.getColumns().addListener(
                (javafx.collections.ListChangeListener<TableColumn<Map<String, Object>, ?>>) change ->
                        scheduleLayoutSave());

        spinner.setPrefSize(26, 26);
        spinner.getStyleClass().add("adb-grid__spinner");

        stateText.getStyleClass().add("adb-grid__state-text");

        retry.getStyleClass().addAll("adb-button", "adb-button--ghost");
        retry.setOnAction(event -> load(true));
        retry.setVisible(false);
        retry.setManaged(false);

        stateBox.getStyleClass().add("adb-grid__state");
        stateBox.setAlignment(Pos.CENTER);
        stateBox.setMaxWidth(420);
        stateBox.setMaxHeight(180);
        stateBox.getChildren().addAll(spinner, stateText, retry);

        transposeTable.setId("data-transpose");
        transposeTable.getStyleClass().addAll("adb-grid", "adb-grid--transpose");
        transposeTable.setPlaceholder(new Label("没有可转置的数据"));
        transposeTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        transposeTable.setVisible(false);
        transposeTable.setManaged(false);
        transposeTable.setFocusTraversable(true);

        detailsDrawer.setOnClose(() -> { });
        StackPane.setAlignment(detailsDrawer, Pos.CENTER_RIGHT);
        StackPane.setMargin(detailsDrawer, new javafx.geometry.Insets(8));

        body.getStyleClass().add("adb-grid__body");
        body.getChildren().addAll(grid, transposeTable, stateBox, detailsDrawer);
        VBox.setVgrow(body, Priority.ALWAYS);

        HBox pager = buildPager();

        buildBatchBar();
        getChildren().addAll(header, new Separator(), toolbar, chipBar, body, batchBar, pager);
        VBox.setVgrow(body, Priority.ALWAYS);
    }

    private void buildBatchBar() {
        batchBar.setId("data-batch-bar");
        batchBar.getStyleClass().add("adb-batch-bar");
        batchBar.setAlignment(Pos.CENTER_LEFT);
        batchBar.setVisible(false);
        batchBar.setManaged(false);

        batchLabel.setId("data-batch-label");
        batchLabel.getStyleClass().add("adb-batch-bar__count");

        Button copy = new Button("复制");
        copy.setId("data-batch-copy");
        copy.getStyleClass().addAll("adb-button", "adb-button--ghost");
        copy.setOnAction(event -> copySelectedAsTsv());

        Button copySql = new Button("复制为 SQL");
        copySql.setId("data-batch-sql");
        copySql.getStyleClass().addAll("adb-button", "adb-button--ghost");
        copySql.setOnAction(event -> copySelectedAsSql());

        batchClone.setId("data-batch-clone");
        batchClone.getStyleClass().addAll("adb-button", "adb-button--ghost");
        batchClone.setTooltip(new Tooltip("复制选中行（不含主键）到新增缓冲"));
        batchClone.setOnAction(event -> cloneSelectedRows());

        batchNull.setId("data-batch-null");
        batchNull.getStyleClass().addAll("adb-button", "adb-button--ghost");
        batchNull.setTooltip(new Tooltip("把焦点列设为 NULL（整批一次撤销）"));
        batchNull.setOnAction(event -> setFocusedColumnValue(null));

        batchEmpty.setId("data-batch-empty");
        batchEmpty.getStyleClass().addAll("adb-button", "adb-button--ghost");
        batchEmpty.setTooltip(new Tooltip("把焦点列设为空串（整批一次撤销）"));
        batchEmpty.setOnAction(event -> setFocusedColumnValue(""));

        batchDelete.setId("data-batch-delete");
        batchDelete.getStyleClass().addAll("adb-button", "adb-button--danger");
        batchDelete.setOnAction(event -> deleteSelectedRows());

        Button clear = new Button("清除选择");
        clear.setId("data-batch-clear");
        clear.getStyleClass().addAll("adb-button", "adb-button--ghost");
        clear.setOnAction(event -> grid.getSelectionModel().clearSelection());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        batchBar.getChildren().addAll(batchLabel, copy, copySql, batchClone,
                batchNull, batchEmpty, batchDelete, spacer, clear);
    }

    private HBox buildPager() {
        HBox pager = new HBox(8);
        pager.getStyleClass().add("adb-grid__pager");
        pager.setAlignment(Pos.CENTER_LEFT);

        rangeLabel.setId("data-range");
        rangeLabel.getStyleClass().add("adb-grid__range");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        previous.setId("data-prev");
        previous.getStyleClass().addAll("adb-button", "adb-button--ghost");
        previous.setOnAction(event -> previousPage());

        firstPageButton.setId("data-first");
        firstPageButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        firstPageButton.setTooltip(new Tooltip("第一页"));
        firstPageButton.setAccessibleText("第一页");
        firstPageButton.setOnAction(event -> firstPage());

        pageLabel.setId("data-page");
        pageLabel.getStyleClass().add("adb-grid__page");

        next.setId("data-next");
        next.getStyleClass().addAll("adb-button", "adb-button--ghost");
        next.setOnAction(event -> nextPage());

        lastPageButton.setId("data-last");
        lastPageButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        lastPageButton.setTooltip(new Tooltip("最后一页"));
        lastPageButton.setAccessibleText("最后一页");
        lastPageButton.setOnAction(event -> lastPage());

        jumpField.setId("data-jump");
        jumpField.getStyleClass().add("adb-grid__jump");
        jumpField.setPromptText("跳至页");
        jumpField.setTooltip(new Tooltip("输入页码后回车跳转"));
        jumpField.setAccessibleText("跳至指定页");
        jumpField.setPrefWidth(72);
        jumpField.setOnAction(event -> jumpToPage(jumpField.getText()));

        Label perPage = new Label("每页");
        perPage.getStyleClass().add("adb-muted");

        pageSizeBox.setId("data-page-size");
        pageSizeBox.getStyleClass().add("adb-page-size");
        // ⭐ 档位来自 PageSize 枚举（唯一真相），不再在 UI 里写死一份数组。
        pageSizeBox.getItems().setAll(Arrays.stream(PageSize.values())
                .map(PageSize::value).toList());
        pageSizeBox.getSelectionModel().select(PageSize.nearest(pageSize).value());
        pageSizeBox.setPrefWidth(78);
        pageSizeBox.setOnAction(event -> {
            Integer value = pageSizeBox.getValue();
            if (value == null || value == pageSize) {
                return;
            }
            pageSize = value;
            offset = 0;
            load(false);
        });

        pager.getChildren().addAll(rangeLabel, spacer, firstPageButton, previous, pageLabel,
                next, lastPageButton, jumpField,
                new Separator(javafx.geometry.Orientation.VERTICAL), perPage, pageSizeBox);
        return pager;
    }

    // ─────────────────────────── 加载 ───────────────────────────

    public void refresh() {
        load(true);
    }

    private void previousPage() {
        if (loading || offset == 0) {
            return;
        }
        offset = Math.max(0, offset - pageSize);
        load(false);
    }

    private void firstPage() {
        if (loading || offset == 0) {
            return;
        }
        offset = 0;
        load(false);
    }

    private void lastPage() {
        if (loading || total <= 0) {
            return;
        }
        long lastOffset = (total - 1) / pageSize * pageSize;
        if (lastOffset == offset) {
            return;
        }
        if (QueryLimits.exceedsOffsetLimit(lastOffset)) {
            showError("已到达 OFFSET 上限 " + QueryLimits.MAX_OFFSET
                    + "，请改用筛选条件而不是继续翻页。");
            return;
        }
        offset = lastOffset;
        load(false);
    }

    /** 跳页：页码从 1 开始；越界或非法输入不动作并给出行内提示。 */
    private void jumpToPage(String raw) {
        if (loading) {
            return;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        long page;
        try {
            page = Long.parseLong(text);
        } catch (NumberFormatException e) {
            feedback.status("跳页失败：「" + text + "」不是页码");
            return;
        }
        if (page < 1) {
            feedback.status("跳页失败：页码从 1 开始");
            return;
        }
        long pages = QueryLimits.totalPages(total, pageSize);
        if (pages >= 0 && page > pages) {
            feedback.status("跳页失败：最大页码 " + pages);
            return;
        }
        long target = (page - 1) * pageSize;
        if (QueryLimits.exceedsOffsetLimit(target)) {
            showError("已到达 OFFSET 上限 " + QueryLimits.MAX_OFFSET
                    + "，请改用筛选条件而不是继续翻页。");
            return;
        }
        if (target == offset) {
            jumpField.clear();
            return;
        }
        offset = target;
        jumpField.clear();
        load(false);
    }

    private void nextPage() {
        if (loading) {
            return;
        }
        long nextOffset = offset + pageSize;
        if (total >= 0 && nextOffset >= total) {
            return;
        }
        if (QueryLimits.exceedsOffsetLimit(nextOffset)) {
            showError("已到达 OFFSET 上限 " + QueryLimits.MAX_OFFSET
                    + "，请改用筛选条件而不是继续翻页。");
            return;
        }
        offset = nextOffset;
        load(false);
    }

    private void load(boolean metadataFresh) {
        if (loading) {
            return;
        }
        loading = true;
        long request = ++requestSequence;
        int size = pageSize;
        long currentOffset = offset;
        DataQuery snapshot = query;
        DataSqlBuilder.Sql pageSql;
        try {
            pageSql = DataSqlBuilder.page(session, tableName, snapshot, size, currentOffset);
        } catch (IllegalArgumentException e) {
            loading = false;
            showError(e.getMessage());
            feedback.status(e.getMessage());
            feedback.duration(-1);
            return;
        }
        lastSql = pageSql.text();

        showLoading();
        updateEditControls();
        feedback.status("加载 " + tableName + " · OFFSET " + currentOffset + " …");

        Task<LoadResult> task = new Task<>() {
            @Override
            protected LoadResult call() throws Exception {
                long started = System.nanoTime();
                boolean needMetadata = metadataFresh || tableSchema == null || total < 0;
                TableSchema schema = needMetadata
                        ? schemaService.describe(session, tableName) : tableSchema;
                long count = needMetadata
                        ? schemaService.count(session, tableName, snapshot) : total;
                RowPage page = session.query(pageSql.text(), size, pageSql.params());
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                return new LoadResult(schema, count, page, elapsed, needMetadata);
            }
        };

        task.setOnSucceeded(event -> {
            // 切换标签/连续刷新时旧请求可能晚到；只接受最后一次。
            if (request != requestSequence) {
                return;
            }
            loading = false;
            apply(task.getValue());
        });
        task.setOnFailed(event -> {
            if (request != requestSequence) {
                return;
            }
            loading = false;
            Throwable error = task.getException();
            showError("加载失败：" + readable(error));
            feedback.status("加载失败：" + tableName + " · " + readable(error));
            feedback.duration(-1);
        });
        task.setOnCancelled(event -> {
            if (request == requestSequence) {
                loading = false;
            }
        });

        inFlight = start(task);
    }

    private Future<?> start(Task<LoadResult> task) {
        if (dbExecutor instanceof java.util.concurrent.ExecutorService service) {
            return service.submit(task);
        }
        dbExecutor.execute(task);
        return null;
    }

    private void apply(LoadResult result) {
        if (result.metadataFresh()) {
            tableSchema = result.schema();
            renderColumns(result.schema());
            filterButton.setDisable(false);
        }
        total = result.total();
        renderQueryState();
        updateEditControls();

        // 刷新时表可能被删到比当前页更少：回到最后一个有效页，而不是显示空页 + 第 8/3 页。
        if (total >= 0 && total > 0 && offset >= total) {
            offset = ((total - 1) / pageSize) * pageSize;
            load(false);
            return;
        }
        if (total == 0) {
            offset = 0;
        }

        lastRowCount = result.page.rowCount();
        lastElapsedMs = result.elapsedMs;

        pageRows = List.copyOf(result.page.rows());
        fitInitialWidths(result.schema());
        rebuildItems();
        hideState();
        updatePager();
        updateBatchBar();
        updateViewControls();
        feedback.range(rangeText());
        feedback.duration(result.elapsedMs);
        // ⭐ 结果被硬上限截断时必须说出来。数据页平时不会截断（分页 + COUNT 已知总数），
        //    但只要发生了，用户看到的就不是完整数据 —— 静默丢数据是正确性问题。
        boolean truncated = result.page.rowsTruncated();
        feedback.status(truncated
                ? "已加载 " + tableName + " · " + lastRowCount + " 行（⚠ 结果已截断）· "
                        + result.elapsedMs() + " ms"
                : "已加载 " + tableName + " · " + lastRowCount + " 行 · "
                        + result.elapsedMs() + " ms");
        activated = true;
    }

    /**
     * 应用密度档位（A19）。
     *
     * <p>行高来自设计令牌 {@code row-compact} / {@code row-normal} / {@code row-comfort}，
     * 不写死在 CSS 里 —— 令牌是唯一真相。
     * 以前密度只影响对象树，数据网格行高不变，等于「紧凑 / 宽松」这个设置
     * 对最费屏的地方不起作用。
     */
    public void setDensity(String density) {
        int rowHeight = switch (density == null ? "normal" : density) {
            case "compact" -> io.agenticdb.ui.Tokens.ROW_COMPACT_LIGHT;
            case "comfort" -> io.agenticdb.ui.Tokens.ROW_COMFORT_LIGHT;
            default -> io.agenticdb.ui.Tokens.ROW_NORMAL_LIGHT;
        };
        grid.setFixedCellSize(rowHeight);
        transposeTable.setFixedCellSize(rowHeight);
    }

    /** 当前行高（供测试断言密度真的作用到了网格）。 */
    public double rowHeight() {
        return grid.getFixedCellSize();
    }

    /** 切回该标签时把状态栏恢复成这一页的真实状态，而不是上一页的残留。 */
    public void activate() {
        if (!activated) {
            return;
        }
        feedback.range(rangeText());
        feedback.duration(lastElapsedMs);
        feedback.dirty(editBuffer.dirtyCount());
        updateEditControls();
        feedback.status("已激活：" + tableName + " · " + lastRowCount + " 行");
    }

    // ─────────────────────────── 表格 ───────────────────────────

    private void renderColumns(TableSchema schema) {
        grid.getColumns().clear();
        headers.clear();
        columnByDefinition.clear();
        grid.setEditable(schema.editable());

        TableColumn<Map<String, Object>, Void> select = new TableColumn<>();
        select.getStyleClass().add("adb-grid__select-column");
        select.setSortable(false);
        select.setResizable(false);
        select.setPrefWidth(36);
        select.setMinWidth(36);
        select.setMaxWidth(36);
        select.setGraphic(selectAllBox);
        select.setCellFactory(column -> new TableCell<>() {
            private final CheckBox box = new CheckBox();

            {
                setAlignment(Pos.CENTER);
                box.setOnAction(event -> {
                    if (isEmpty() || getIndex() < 0) {
                        return;
                    }
                    if (box.isSelected()) {
                        grid.getSelectionModel().select(getIndex());
                    } else {
                        grid.getSelectionModel().clearSelection(getIndex());
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                box.setSelected(grid.getSelectionModel().isSelected(getIndex()));
                setGraphic(box);
            }
        });
        grid.getColumns().add(select);

        TableColumn<Map<String, Object>, Void> number = new TableColumn<>("#");
        number.getStyleClass().add("adb-grid__number-column");
        number.setSortable(false);
        number.setResizable(false);
        number.setPrefWidth(52);
        number.setMinWidth(52);
        number.setMaxWidth(52);
        number.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("adb-grid__row-number",
                        "adb-grid__row-number--dirty",
                        "adb-grid__row-number--new",
                        "adb-grid__row-number--deleted");
                getStyleClass().add("adb-grid__row-number");
                setAlignment(Pos.CENTER_RIGHT);
                if (empty) {
                    setText(null);
                    return;
                }
                Map<String, Object> row = getTableRow() == null
                        ? null : getTableRow().getItem();
                Long draftId = draftId(row);
                if (draftId != null) {
                    setText("＋");
                    getStyleClass().add("adb-grid__row-number--new");
                    return;
                }
                long number = offset + Math.max(0, getIndex()
                        - editBuffer.drafts().size()) + 1;
                RowIdentity identity = identity(row);
                if (identity != null && editBuffer.isDeleted(identity)) {
                    setText("× " + number);
                    getStyleClass().add("adb-grid__row-number--deleted");
                } else if (identity != null && editBuffer.hasRowEdits(identity)) {
                    setText("● " + number);
                    getStyleClass().add("adb-grid__row-number--dirty");
                } else {
                    setText(Long.toString(number));
                }
            }
        });
        grid.getColumns().add(number);

        Set<String> hidden = savedHiddenColumns();
        for (ColumnDef definition : orderedColumns(schema)) {
            TableColumn<Map<String, Object>, Object> column = new TableColumn<>();
            column.setText(null);
            column.setUserData(definition.name());
            column.setGraphic(columnHeader(definition));
            column.setSortable(false);
            column.setEditable(schema.editable());
            column.setPrefWidth(savedWidth(definition));
            column.setMinWidth(72);
            column.setVisible(!hidden.contains(definition.name()));
            column.setCellValueFactory(data ->
                    new ReadOnlyObjectWrapper<>(data.getValue().get(definition.name())));
            column.setCellFactory(cell -> new ValueCell(definition));
            column.widthProperty().addListener((observable, oldWidth, newWidth) ->
                    scheduleLayoutSave());
            column.visibleProperty().addListener((observable, wasVisible, visible) ->
                    scheduleLayoutSave());
            columnByDefinition.put(definition.name(), column);
            grid.getColumns().add(column);
        }
        columnsButton.setDisable(false);
        refreshColumnMenu();
    }

    private List<ColumnDef> orderedColumns(TableSchema schema) {
        if (settings == null) {
            return schema.columns();
        }
        String raw = settings.get(layoutKey("order"), "");
        Map<String, ColumnDef> byName = new LinkedHashMap<>();
        schema.columns().forEach(column -> byName.put(column.name(), column));
        List<ColumnDef> ordered = new ArrayList<>(schema.columns().size());
        for (String name : raw.split(",")) {
            ColumnDef column = byName.remove(name);
            if (column != null) {
                ordered.add(column);
            }
        }
        ordered.addAll(byName.values());
        return ordered;
    }

    private Set<String> savedHiddenColumns() {
        if (settings == null) {
            return Set.of();
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (String name : settings.get(layoutKey("hidden"), "").split(",")) {
            if (!name.isBlank()) {
                hidden.add(name);
            }
        }
        return hidden;
    }

    private double savedWidth(ColumnDef definition) {
        double fallback = preferredWidth(definition);
        if (settings == null) {
            return fallback;
        }
        int width = settings.getInt(layoutKey("width." + definition.name()),
                (int) Math.round(fallback));
        return Math.max(72, Math.min(width, 1200));
    }

    /**
     * 首次打开某张表、或列数发生变化时，按当前页内容估算一次列宽。
     *
     * <p>Navicat 首次打开会尽量让 email / 时间等完整可见；之后再尊重用户拖动后的持久化宽度。
     */
    private void fitInitialWidths(TableSchema schema) {
        if (settings == null || schema == null) {
            return;
        }
        int knownColumns = settings.getInt(layoutKey("columns"), -1);
        if (knownColumns == schema.columns().size()) {
            return;
        }
        for (ColumnDef definition : schema.columns()) {
            TableColumn<Map<String, Object>, ?> column =
                    columnByDefinition.get(definition.name());
            if (column == null) {
                continue;
            }
            int units = visualLength(definition.name()) + 3;
            for (Map<String, Object> row : pageRows.stream().limit(50).toList()) {
                units = Math.max(units, visualLength(CellValueParser.display(
                        row.get(definition.name()))));
            }
            units = Math.min(units, 40);
            double width = switch (definition.type()) {
                case BOOLEAN -> 76;
                case NUMBER -> Math.max(88, Math.min(130, units * 7.2 + 30));
                case TIMESTAMP, DATE -> Math.max(158, Math.min(190, units * 7.2 + 30));
                default -> Math.max(120, Math.min(280, units * 7.2 + 30));
            };
            column.setPrefWidth(Math.round(width));
        }
        settings.setInt(layoutKey("columns"), schema.columns().size());
    }

    private static int visualLength(String text) {
        if (text == null) {
            return 0;
        }
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            units += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return units;
    }

    private String layoutKey(String suffix) {
        return "ui.grid." + connectionName + "." + tableName + "." + suffix;
    }

    private void scheduleLayoutSave() {
        if (settings == null || tableSchema == null) {
            return;
        }
        if (layoutSaveTimer == null) {
            layoutSaveTimer = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(400));
            layoutSaveTimer.setOnFinished(event -> persistLayout());
        }
        layoutSaveTimer.playFromStart();
    }

    private void persistLayout() {
        if (settings == null || tableSchema == null || columnByDefinition.isEmpty()) {
            return;
        }
        StringBuilder order = new StringBuilder();
        StringBuilder hidden = new StringBuilder();
        for (TableColumn<Map<String, Object>, ?> item : grid.getColumns()) {
            if (!(item.getUserData() instanceof String name) || name.isBlank()) {
                continue;
            }
            if (!order.isEmpty()) {
                order.append(',');
            }
            order.append(name);
            if (!item.isVisible()) {
                if (!hidden.isEmpty()) {
                    hidden.append(',');
                }
                hidden.append(name);
            }
            settings.setInt(layoutKey("width." + name), (int) Math.round(item.getWidth()));
        }
        settings.set(layoutKey("order"), order.toString());
        settings.set(layoutKey("hidden"), hidden.toString());
    }

    private void refreshColumnMenu() {
        columnsButton.getItems().clear();
        if (tableSchema == null) {
            return;
        }
        for (ColumnDef definition : tableSchema.columns()) {
            TableColumn<Map<String, Object>, Object> column =
                    columnByDefinition.get(definition.name());
            if (column == null) {
                continue;
            }
            CheckMenuItem item = new CheckMenuItem(definition.name());
            item.setSelected(column.isVisible());
            item.setOnAction(event -> column.setVisible(item.isSelected()));
            columnsButton.getItems().add(item);
        }
        columnsButton.getItems().add(new SeparatorMenuItem());
        MenuItem showAll = new MenuItem("全部显示");
        showAll.setOnAction(event -> {
            columnByDefinition.values().forEach(column -> column.setVisible(true));
            refreshColumnMenu();
        });
        MenuItem reset = new MenuItem("重置列布局");
        reset.setOnAction(event -> resetColumnLayout());
        columnsButton.getItems().addAll(showAll, reset);
    }

    private void resetColumnLayout() {
        if (settings != null) {
            settings.set(layoutKey("order"), "");
            settings.set(layoutKey("hidden"), "");
        }
        if (tableSchema != null) {
            for (ColumnDef definition : tableSchema.columns()) {
                TableColumn<Map<String, Object>, Object> column =
                        columnByDefinition.get(definition.name());
                if (column != null) {
                    column.setVisible(true);
                    column.setPrefWidth(preferredWidth(definition));
                    if (settings != null) {
                        settings.setInt(layoutKey("width." + definition.name()),
                                (int) Math.round(preferredWidth(definition)));
                    }
                }
            }
        }
        refreshColumnMenu();
        scheduleLayoutSave();
    }

    private javafx.scene.Node columnHeader(ColumnDef definition) {
        Label name = new Label(definition.name()
                + (definition.primaryKey() ? "  [PK]" : definition.nullable() ? "" : "  •"));
        name.setId("data-header-" + definition.name());
        name.getStyleClass().add("adb-grid__column-name");
        if (definition.primaryKey()) {
            name.setTooltip(new Tooltip("主键" + (definition.comment() == null
                    ? "" : " · " + definition.comment())));
        } else if (!definition.nullable()) {
            name.setTooltip(new Tooltip("NOT NULL" + (definition.comment() == null
                    ? "" : " · " + definition.comment())));
        } else if (definition.comment() != null && !definition.comment().isBlank()) {
            name.setTooltip(new Tooltip(definition.comment()));
        }

        Label type = new Label(typeLabel(definition));
        type.getStyleClass().add("adb-grid__column-type");

        Label sortBadge = new Label();
        sortBadge.getStyleClass().add("adb-grid__sort-badge");
        sortBadge.setVisible(false);
        sortBadge.setManaged(false);

        Button funnel = new Button("▽");
        funnel.setAccessibleText("筛选");
        funnel.setId("data-filter-" + definition.name());
        funnel.setFocusTraversable(false);
        funnel.getStyleClass().add("adb-grid__filter-button");
        funnel.setTooltip(new Tooltip("按候选值筛选 " + definition.name()));
        funnel.setOnAction(event -> showFilterPanel(definition, funnel));
        funnel.setOnMouseClicked(javafx.event.Event::consume);

        VBox labels = new VBox(1, name, type);
        labels.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(4, labels, spacer, sortBadge, funnel);
        box.getStyleClass().add("adb-grid__header");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setOnMouseClicked(event -> {
            if (event.isSecondaryButtonDown()) {
                return;
            }
            if (event.getClickCount() == 2) {
                autoFitColumn(definition.name());
                event.consume();
                return;
            }
            applySort(definition.name(), event.isShiftDown());
            event.consume();
        });
        box.setOnContextMenuRequested(event -> {
            ContextMenu menu = new ContextMenu();
            MenuItem ascending = new MenuItem("按此列升序");
            ascending.setOnAction(action -> {
                query = query.withSorts(List.of(new SortSpec(definition.name(), true)));
                offset = 0;
                renderQueryState();
                load(false);
            });
            MenuItem descending = new MenuItem("按此列降序");
            descending.setOnAction(action -> {
                query = query.withSorts(List.of(new SortSpec(definition.name(), false)));
                offset = 0;
                renderQueryState();
                load(false);
            });
            MenuItem clearSort = new MenuItem("清除此列排序");
            clearSort.setDisable(query.indexOfSort(definition.name()) < 0);
            clearSort.setOnAction(action -> removeSort(definition.name()));
            MenuItem filter = new MenuItem("筛选此列…");
            filter.setOnAction(action -> showFilterPanel(definition, funnel));
            MenuItem hide = new MenuItem("隐藏此列");
            hide.setOnAction(action -> hideColumn(definition.name()));
            MenuItem fit = new MenuItem("自适应列宽");
            fit.setOnAction(action -> autoFitColumn(definition.name()));
            MenuItem copyName = new MenuItem("复制列名");
            copyName.setOnAction(action -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(definition.name());
                Clipboard.getSystemClipboard().setContent(content);
                feedback.status("已复制列名 " + definition.name());
            });
            menu.getItems().addAll(ascending, descending, clearSort,
                    new SeparatorMenuItem(), filter, hide, fit, copyName);
            menu.show(box, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        headers.put(definition.name(), new HeaderView(name, sortBadge, funnel));
        return box;
    }

    private void hideColumn(String name) {
        TableColumn<Map<String, Object>, Object> column = columnByDefinition.get(name);
        if (column == null) {
            return;
        }
        if (columnByDefinition.values().stream().filter(TableColumn::isVisible).count() <= 1
                && column.isVisible()) {
            feedback.status("至少保留一列可见");
            return;
        }
        column.setVisible(false);
        refreshColumnMenu();
    }

    private void autoFitColumn(String name) {
        TableColumn<Map<String, Object>, Object> column = columnByDefinition.get(name);
        if (column == null) {
            return;
        }
        int max = name.length();
        for (Map<String, Object> row : grid.getItems()) {
            Object value = row.get(name);
            String text = value == null ? "NULL" : String.valueOf(value);
            max = Math.max(max, text.length());
        }
        column.setPrefWidth(Math.max(120, Math.min(420, max * 8.0 + 44)));
        scheduleLayoutSave();
    }

    private void applySort(String column, boolean append) {
        DataQuery next = query.cycleSort(column, append);
        if (next.sorts().equals(query.sorts())) {
            return;
        }
        query = next;
        offset = 0;
        renderQueryState();
        feedback.status("排序：" + sortSummary());
        load(false);
    }

    private void removeSort(String column) {
        query = query.withoutSort(column);
        offset = 0;
        renderQueryState();
        load(false);
    }

    private void clearSorts() {
        query = query.clearSorts();
        offset = 0;
        renderQueryState();
        load(false);
    }

    private String sortSummary() {
        if (!query.hasSorts()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < query.sorts().size(); i++) {
            if (i > 0) {
                text.append(" · ");
            }
            SortSpec sort = query.sorts().get(i);
            text.append(sort.column()).append(sort.ascending() ? " ↑" : " ↓");
        }
        return text.toString();
    }

    private void showFilterPanel(ColumnDef definition, Button anchor) {
        if (tableSchema == null) {
            return;
        }
        ColumnFilterPanel panel = new ColumnFilterPanel(session, tableName, definition, query,
                dbExecutor,
                condition -> {
                    applyFilter(condition);
                    feedback.status("筛选：" + condition.summary());
                },
                () -> clearFilter(definition.name()));
        panel.show(anchor);
    }

    private void showConditionDialog() {
        if (tableSchema == null || getScene() == null) {
            return;
        }
        ConditionDialog.show(getScene().getWindow(), tableSchema, getScene().getStylesheets())
                .ifPresent(condition -> {
                    applyFilter(condition);
                    feedback.status("筛选：" + condition.summary());
                });
    }

    private void applyFilter(FilterCondition condition) {
        query = query.withFilter(condition);
        invalidateFilters();
    }

    private void removeFilter(FilterCondition condition) {
        query = query.withoutFilter(condition);
        invalidateFilters();
    }

    private void clearFilter(String column) {
        query = query.withoutFiltersFor(column);
        invalidateFilters();
    }

    private void clearQuery() {
        if (query.isDefault()) {
            return;
        }
        query = DataQuery.empty();
        total = -1;
        offset = 0;
        renderQueryState();
        feedback.status("已清除筛选与排序");
        load(false);
    }

    private void invalidateFilters() {
        total = -1;
        offset = 0;
        renderQueryState();
        load(false);
    }

    private void renderQueryState() {
        chipBar.getChildren().clear();
        for (FilterCondition filter : query.filters()) {
            Button chip = chip(filter.summary(), () -> removeFilter(filter));
            chip.getStyleClass().add("adb-chip--filter");
            chipBar.getChildren().add(chip);
        }
        for (SortSpec sort : query.sorts()) {
            Button chip = chip("排序 " + sort.column() + (sort.ascending() ? " ↑" : " ↓"),
                    () -> removeSort(sort.column()));
            chip.getStyleClass().add("adb-chip--sort");
            chipBar.getChildren().add(chip);
        }
        if (query.sorts().size() > 1) {
            Button clearSorts = new Button("清除排序");
            clearSorts.setMnemonicParsing(false);
            clearSorts.getStyleClass().addAll("adb-chip", "adb-chip--muted");
            clearSorts.setOnAction(event -> clearSorts());
            chipBar.getChildren().add(clearSorts);
        }
        boolean visible = !query.isDefault();
        chipBar.setVisible(visible);
        chipBar.setManaged(visible);
        clearQueryButton.setDisable(query.isDefault());
        updateHeaderSortBadges();
    }

    private Button chip(String text, Runnable action) {
        Button button = new Button(text + "  ✕");
        button.setMnemonicParsing(false);
        button.getStyleClass().add("adb-chip");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void updateHeaderSortBadges() {
        headers.forEach((column, header) -> {
            int index = query.indexOfSort(column);
            if (index < 0) {
                header.sortBadge().setText("");
                header.sortBadge().setVisible(false);
                header.sortBadge().setManaged(false);
                return;
            }
            SortSpec sort = query.sorts().get(index);
            String arrow = sort.ascending() ? "↑" : "↓";
            String text = query.sorts().size() > 1 ? (index + 1) + arrow : arrow;
            header.sortBadge().setText(text);
            header.sortBadge().setVisible(true);
            header.sortBadge().setManaged(true);
        });
    }

    private static double preferredWidth(ColumnDef definition) {
        if (definition.type() == ColumnType.BOOLEAN) {
            return 88;
        }
        if (definition.type() == ColumnType.TIMESTAMP || definition.type() == ColumnType.DATE) {
            return 168;
        }
        return switch (definition.type()) {
            case NUMBER -> 110;
            case TEXT, JSON, UUID, ENUM, SET, ARRAY, GEO, OTHER -> 180;
            case BINARY -> 120;
            case TIME -> 110;
            default -> 150;
        };
    }

    private static String typeLabel(ColumnDef definition) {
        String base = definition.type().name().toLowerCase(java.util.Locale.ROOT);
        if (definition.type() == ColumnType.NUMBER && definition.numericPrecision() != null) {
            return definition.numericPrecision()
                    + (definition.numericScale() == null ? "" : "," + definition.numericScale());
        }
        if (definition.type() == ColumnType.TEXT && definition.charMaxLength() != null) {
            return base + "(" + definition.charMaxLength() + ")";
        }
        return base;
    }

    private RowIdentity identity(Map<String, Object> row) {
        if (row == null || tableSchema == null || !tableSchema.editable()
                || draftId(row) != null) {
            return null;
        }
        try {
            return RowIdentity.from(tableSchema.primaryKeys(), row);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Long draftId(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object marker = row.get(EditBuffer.DRAFT_KEY);
        return marker instanceof Number number ? number.longValue() : null;
    }

    private void rebuildItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        editBuffer.drafts().forEach((id, values) -> {
            Map<String, Object> row = new LinkedHashMap<>(values);
            row.put(EditBuffer.DRAFT_KEY, id);
            items.add(row);
        });
        items.addAll(pageRows);
        List<Map<String, Object>> visible = applyViewFilters(items);
        grid.setItems(FXCollections.observableArrayList(visible));
        grid.setVisible(!transpose);
        grid.setManaged(!transpose);
        transposeTable.setVisible(transpose);
        transposeTable.setManaged(transpose);
        if (transpose) {
            rebuildTranspose(visible);
        }
        updateBatchBar();
        updateSelectAllState();
        updateViewControls();
    }

    /** 本页搜索与“隐藏 NULL / 空串”只在客户端过滤，不改变服务端分页。 */
    private List<Map<String, Object>> applyViewFilters(List<Map<String, Object>> rows) {
        String needle = searchField.getText() == null ? ""
                : searchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        List<String> columns = visibleColumnNames();
        List<Map<String, Object>> visible = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long draft = draftId(row);
            boolean hasNull = false;
            boolean hasEmpty = false;
            boolean matches = needle.isEmpty();
            for (String name : columns) {
                ColumnDef definition = columnDef(name);
                Object value = definition == null ? row.get(name) : cellValue(row, definition);
                if (value == null) {
                    hasNull = true;
                } else if (value instanceof String text && text.isEmpty()) {
                    hasEmpty = true;
                }
                if (!matches) {
                    String text = value == null ? "null" : String.valueOf(value);
                    if (text.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        matches = true;
                    }
                }
            }
            // 新增行大量字段尚未填写，不参与“隐藏 NULL/空串”，否则刚新增就消失。
            if (draft == null) {
                if (hideNullRows && hasNull) {
                    continue;
                }
                if (hideEmptyRows && hasEmpty) {
                    continue;
                }
            }
            if (!matches) {
                continue;
            }
            visible.add(row);
        }
        return visible;
    }

    private void rebuildTranspose(List<Map<String, Object>> rows) {
        transposeTable.getColumns().clear();
        List<String> columns = visibleColumnNames();
        TableColumn<Map<String, Object>, String> field = new TableColumn<>("字段");
        field.setSortable(false);
        field.setPrefWidth(168);
        field.setCellValueFactory(data ->
                new ReadOnlyStringWrapper(String.valueOf(data.getValue().get("__field"))));
        field.setCellFactory(cell -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().remove("adb-grid__transpose-field");
                getStyleClass().add("adb-grid__transpose-field");
            }
        });
        transposeTable.getColumns().add(field);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> source = rows.get(i);
            int rowIndex = i;
            String key = "__r" + i;
            Long draft = draftId(source);
            String title = draft != null ? "＋" : Long.toString(offset + i + 1);
            TableColumn<Map<String, Object>, String> column = new TableColumn<>(title);
            column.setSortable(false);
            column.setPrefWidth(112);
            column.setCellValueFactory(data ->
                    new ReadOnlyStringWrapper(String.valueOf(data.getValue().get(key))));
            column.setCellFactory(cell -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
            });
            transposeTable.getColumns().add(column);
        }
        List<Map<String, Object>> transposed = new ArrayList<>();
        for (String name : columns) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("__field", name);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> source = rows.get(i);
                ColumnDef definition = columnDef(name);
                Object value = definition == null ? source.get(name)
                        : cellValue(source, definition);
                String text = definition == null
                        ? CellRenderer.compact(String.valueOf(value))
                        : CellRenderer.render(definition, value).text();
                line.put("__r" + i, text);
            }
            transposed.add(line);
        }
        transposeTable.setItems(FXCollections.observableArrayList(transposed));
    }

    void setHideNullRows(boolean value) {
        hideNullRows = value;
        hideNullItem.setSelected(value);
        rebuildItems();
    }

    void setHideEmptyRows(boolean value) {
        hideEmptyRows = value;
        hideEmptyItem.setSelected(value);
        rebuildItems();
    }

    void setTranspose(boolean value) {
        transpose = value;
        transposeItem.setSelected(value);
        rebuildItems();
        feedback.status(value ? "转置视图 · 只读" : "已恢复表格视图");
    }

    private void updateViewControls() {
        boolean hasData = !grid.getItems().isEmpty() || !transposeTable.getItems().isEmpty();
        viewButton.setDisable(tableSchema == null);
        exportButton.setDisable(!hasData);
        hideNullItem.setSelected(hideNullRows);
        hideEmptyItem.setSelected(hideEmptyRows);
        transposeItem.setSelected(transpose);
    }

    private void updateSelectAllState() {
        int total = grid.getItems().size();
        int selected = grid.getSelectionModel().getSelectedItems().size();
        selectAllBox.setSelected(total > 0 && selected == total);
        selectAllBox.setIndeterminate(selected > 0 && selected < total);
        // 4.5-11：状态栏的「选择」段接真实数据。
        feedback.selection(selected);
    }

    private void addRow() {
        if (tableSchema == null || !tableSchema.editable() || loading || saving) {
            return;
        }
        long id = editBuffer.addDraft(Map.of());
        rebuildItems();
        selectDraft(id);
        updateDirty();
        feedback.status("已新增行 · 填写后保存");
    }

    private void cloneRow(Map<String, Object> row) {
        if (tableSchema == null || !tableSchema.editable() || loading || saving) {
            return;
        }
        long id = editBuffer.addDraft(cloneValues(row));
        rebuildItems();
        selectDraft(id);
        updateDirty();
        feedback.status("已克隆行 · 请检查唯一约束后保存");
    }

    private void cloneSelectedRows() {
        if (tableSchema == null || !tableSchema.editable() || loading || saving) {
            return;
        }
        List<Map<String, Object>> selected = List.copyOf(
                grid.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        List<Map<String, Object>> values = selected.stream()
                .map(this::cloneValues)
                .toList();
        List<Long> ids = editBuffer.addDrafts(values);
        rebuildItems();
        if (!ids.isEmpty()) {
            selectDraft(ids.getFirst());
        }
        updateDirty();
        feedback.status("已克隆 " + ids.size() + " 行 · 请检查唯一约束后保存");
    }

    private Map<String, Object> cloneValues(Map<String, Object> row) {
        Map<String, Object> values = new LinkedHashMap<>();
        Long draftId = draftId(row);
        for (ColumnDef column : tableSchema.columns()) {
            if (column.primaryKey()) {
                continue;
            }
            if (draftId != null) {
                if (editBuffer.hasDraftValue(draftId, column.name())) {
                    values.put(column.name(),
                            editBuffer.draftValue(draftId, column.name(), null));
                }
            } else if (row.get(column.name()) != null) {
                values.put(column.name(), row.get(column.name()));
            }
        }
        return values;
    }

    private void selectDraft(long id) {
        for (int i = 0; i < grid.getItems().size(); i++) {
            Long marker = draftId(grid.getItems().get(i));
            if (marker != null && marker == id) {
                grid.getSelectionModel().select(i);
                grid.scrollTo(i);
                return;
            }
        }
    }

    private void removeDraftRow(Map<String, Object> row) {
        Long id = draftId(row);
        if (id == null) {
            return;
        }
        if (editBuffer.removeDraft(id)) {
            rebuildItems();
            updateDirty();
            feedback.status("已移除新增行 · ⌘Z 可撤销");
        }
    }

    private ColumnDef columnDef(String name) {
        if (tableSchema == null) {
            return null;
        }
        for (ColumnDef column : tableSchema.columns()) {
            if (column.name().equals(name)) {
                return column;
            }
        }
        return null;
    }

    private Object cellValue(Map<String, Object> row, ColumnDef definition) {
        Long draftId = draftId(row);
        if (draftId != null) {
            return editBuffer.draftValue(draftId, definition.name(), null);
        }
        RowIdentity identity = identity(row);
        Object original = row.get(definition.name());
        return identity == null ? original
                : editBuffer.valueOr(identity, definition.name(), original);
    }

    private void setCellValue(RowIdentity identity, ColumnDef definition,
                              Map<String, Object> row, Object next) {
        Object original = row.get(definition.name());
        if (editBuffer.setCell(identity, definition.name(), original, next)) {
            updateDirty();
            grid.refresh();
            feedback.status("已修改 " + definition.name() + " · "
                    + CellValueParser.display(next) + " · 待保存");
        }
    }

    private void setRowValue(Map<String, Object> row, ColumnDef definition, Object next) {
        Long draftId = draftId(row);
        if (draftId != null) {
            if (editBuffer.setDraftCell(draftId, definition.name(), next)) {
                updateDirty();
                grid.refresh();
                feedback.status("已修改新增行 · " + definition.name() + " · "
                        + CellValueParser.display(next));
            }
            return;
        }
        RowIdentity identity = identity(row);
        if (identity != null) {
            setCellValue(identity, definition, row, next);
        }
    }

    private void deleteRow(Map<String, Object> row) {
        Long draftId = draftId(row);
        if (draftId != null) {
            removeDraftRow(row);
            return;
        }
        RowIdentity identity = identity(row);
        if (identity == null || tableSchema == null || !tableSchema.editable()) {
            return;
        }
        if (editBuffer.deleteRow(identity, row)) {
            grid.refresh();
            updateDirty();
            feedback.status("已标记删除 " + identity.display() + " · ⌘Z 可撤销");
        }
    }

    private void deleteSelectedRows() {
        if (tableSchema == null || !tableSchema.editable()) {
            return;
        }
        List<Map<String, Object>> selected = List.copyOf(
                grid.getSelectionModel().getSelectedItems());
        Map<RowIdentity, Map<String, Object>> identities = new LinkedHashMap<>();
        List<Long> drafts = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            Long draftId = draftId(row);
            if (draftId != null) {
                drafts.add(draftId);
            } else {
                RowIdentity identity = identity(row);
                if (identity != null) {
                    identities.put(identity, row);
                }
            }
        }
        int marked = editBuffer.removeRows(identities, drafts);
        if (marked > 0) {
            grid.getSelectionModel().clearSelection();
            rebuildItems();
            updateDirty();
            feedback.status("已标记删除 " + marked + " 行 · ⌘Z 可撤销");
        }
    }

    private void updateBatchBar() {
        int count = transpose ? 0 : grid.getSelectionModel().getSelectedItems().size();
        boolean visible = count > 0;
        batchBar.setVisible(visible);
        batchBar.setManaged(visible);
        if (!visible) {
            return;
        }
        batchLabel.setText("已选 " + count + " 行");
        boolean editable = tableSchema != null && tableSchema.editable();
        ColumnDef focused = focusedColumn();
        batchDelete.setDisable(!editable);
        batchClone.setDisable(!editable);
        // ⛔ 主键列不能批量改（见 applyColumnValue）——按钮也要跟着禁用，
        //    不能让用户点下去才发现什么也没发生。
        boolean pkColumn = focused != null && focused.primaryKey();
        batchNull.setDisable(!editable || focused == null || !focused.nullable() || pkColumn);
        batchEmpty.setDisable(!editable || focused == null || !textual(focused.type()) || pkColumn);
    }

    private ColumnDef focusedColumn() {
        javafx.scene.control.TablePosition<?, ?> position =
                grid.getFocusModel().getFocusedCell();
        if (position == null || position.getTableColumn() == null) {
            return null;
        }
        Object name = position.getTableColumn().getUserData();
        return name instanceof String text ? columnDef(text) : null;
    }

    String focusedColumnName() {
        ColumnDef column = focusedColumn();
        return column == null ? null : column.name();
    }

    void setFocusedColumnValue(Object next) {
        ColumnDef definition = focusedColumn();
        if (definition == null) {
            feedback.status("先把焦点放到一个单元格，再批量设置");
            return;
        }
        applyColumnValue(definition, next);
    }

    void setColumnValue(String columnName, Object next) {
        ColumnDef definition = columnDef(columnName);
        if (definition == null) {
            feedback.status("未知列：" + columnName);
            return;
        }
        applyColumnValue(definition, next);
    }

    private void applyColumnValue(ColumnDef definition, Object next) {
        if (next == null && !definition.nullable()) {
            feedback.status(definition.name() + " 不允许 NULL");
            return;
        }
        if ("".equals(next) && !textual(definition.type())) {
            feedback.status(definition.name() + " 不是文本列，不能设为空串");
            return;
        }
        // ⛔ 主键列是保护区。单元格编辑路径有 cellEditable 的主键检查，
        //    批量路径以前漏了，导致 VARCHAR/TEXT 主键能被批量「设空串」，
        //    生成 UPDATE t SET pk='' WHERE pk=? —— 主键损坏、唯一约束冲突。
        if (definition.primaryKey()) {
            feedback.status(definition.name() + " 是主键列，不能批量修改");
            return;
        }
        List<Map<String, Object>> selected = List.copyOf(
                grid.getSelectionModel().getSelectedItems());
        List<EditBuffer.CellWrite> cells = new ArrayList<>();
        List<EditBuffer.DraftWrite> drafts = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            Long draft = draftId(row);
            if (draft != null) {
                if (next == null && !definition.nullable()) {
                    continue;
                }
                drafts.add(new EditBuffer.DraftWrite(draft, definition.name(), next));
                continue;
            }
            RowIdentity identity = identity(row);
            if (identity != null && !editBuffer.isDeleted(identity)) {
                cells.add(new EditBuffer.CellWrite(identity, definition.name(),
                        row.get(definition.name()), next));
            }
        }
        int changed = editBuffer.setValues(cells, drafts);
        if (changed > 0) {
            updateDirty();
            grid.refresh();
            feedback.status("已把 " + changed + " 行的 " + definition.name()
                    + " 设为 " + (next == null ? "NULL" : "空串") + " · ⌘Z 可撤销");
        }
    }

    private List<String> visibleColumnNames() {
        List<String> names = new ArrayList<>();
        for (TableColumn<Map<String, Object>, ?> column : grid.getColumns()) {
            if (column.isVisible() && column.getUserData() instanceof String name) {
                names.add(name);
            }
        }
        return names;
    }

    /** ⌘A：全选当前页（含勾选列同步）。 */
    private void selectAllCurrentPage() {
        if (grid.getItems().isEmpty()) {
            feedback.status("当前页没有可选的行");
            return;
        }
        grid.getSelectionModel().selectAll();
        selectAllBox.setSelected(true);
        feedback.status("已选中当前页 " + grid.getItems().size() + " 行");
    }

    /** ⌥⌘C / ⇧⌘C 的作用范围：优先选中行，其次光标所在行。 */
    private List<Map<String, Object>> rowsForCopy() {
        List<Map<String, Object>> selected =
                List.copyOf(grid.getSelectionModel().getSelectedItems());
        if (!selected.isEmpty()) {
            return selected;
        }
        Map<String, Object> focused = grid.getFocusModel().getFocusedItem();
        return focused == null ? List.of() : List.of(focused);
    }

    /** ⌘C：复制光标所在单元格的值（编辑菜单 / 快捷键共用）。 */
    public void copyFocusedCellValue() {
        TablePosition<Map<String, Object>, ?> focused =
                (TablePosition<Map<String, Object>, ?>) grid.getFocusModel().getFocusedCell();
        if (focused == null || focused.getRow() < 0
                || focused.getRow() >= grid.getItems().size()) {
            feedback.status("请先选中一个单元格");
            return;
        }
        TableColumn<Map<String, Object>, ?> column =
                (TableColumn<Map<String, Object>, ?>) focused.getTableColumn();
        if (column == null || column.getUserData() == null) {
            feedback.status("请先选中一个数据单元格");
            return;
        }
        Map<String, Object> row = grid.getItems().get(focused.getRow());
        Object value = column.getCellObservableValue(row) == null
                ? null
                : column.getCellObservableValue(row).getValue();
        ClipboardContent content = new ClipboardContent();
        content.putString(value == null ? "" : String.valueOf(value));
        Clipboard.getSystemClipboard().setContent(content);
        feedback.status("已复制单元格值");
    }

    private void copySelectedAsTsv() {
        copyRowsAsTsv(List.copyOf(grid.getSelectionModel().getSelectedItems()), "选中行");
    }

    private void copyRowsAsTsv(List<Map<String, Object>> rows, String scope) {
        if (rows.isEmpty()) {
            feedback.status("没有可复制的行");
            return;
        }
        List<String> columns = visibleColumnNames();
        StringBuilder text = new StringBuilder(String.join("\t", columns)).append('\n');
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    text.append('\t');
                }
                ColumnDef definition = columnDef(columns.get(i));
                Object value = definition == null ? row.get(columns.get(i))
                        : cellValue(row, definition);
                text.append(value == null ? "" : String.valueOf(value));
            }
            text.append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.toString());
        Clipboard.getSystemClipboard().setContent(content);
        feedback.status("已复制 " + scope + " " + rows.size() + " 行为 TSV");
    }

    private void copySelectedAsSql() {
        copyRowsAsSql(List.copyOf(grid.getSelectionModel().getSelectedItems()), "选中行");
    }

    private void copyRowsAsSql(List<Map<String, Object>> rows, String scope) {
        if (rows.isEmpty()) {
            feedback.status("没有可复制的行");
            return;
        }
        List<String> columns = visibleColumnNames();
        if (columns.isEmpty()) {
            feedback.status("没有可见列");
            return;
        }
        String table = session.dialect().quote(tableName);
        StringBuilder sql = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (!sql.isEmpty()) {
                sql.append(";\n");
            }
            sql.append("INSERT INTO ").append(table).append(" (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(session.dialect().quote(columns.get(i)));
            }
            sql.append(") VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                ColumnDef definition = columnDef(columns.get(i));
                Object value = definition == null ? row.get(columns.get(i))
                        : cellValue(row, definition);
                sql.append(sqlLiteral(value));
            }
            sql.append(')');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sql.toString());
        Clipboard.getSystemClipboard().setContent(content);
        feedback.status("已复制 " + scope + " " + rows.size() + " 行 INSERT");
    }

    private void exportCsv() {
        List<Map<String, Object>> rows = List.copyOf(grid.getItems());
        if (rows.isEmpty()) {
            feedback.status("没有可导出的行");
            return;
        }
        List<String> columns = visibleColumnNames();
        if (columns.isEmpty()) {
            feedback.status("没有可见列");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出当前页为 CSV");
        chooser.setInitialFileName(tableName + ".csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV 文件", "*.csv"));
        if (getScene() != null) {
            chooser.setInitialDirectory(Path.of(System.getProperty("user.home")).toFile());
        }
        java.io.File file = chooser.showSaveDialog(
                getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        List<List<String>> values = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            List<String> line = new ArrayList<>(columns.size());
            for (String name : columns) {
                ColumnDef definition = columnDef(name);
                Object value = definition == null ? row.get(name)
                        : cellValue(row, definition);
                line.add(csvValue(value));
            }
            values.add(line);
        }
        try {
            Path path = file.toPath();
            Files.writeString(path, "\uFEFF" + CsvExporter.csv(columns, values),
                    StandardCharsets.UTF_8);
            feedback.status("已导出 " + rows.size() + " 行 → " + path);
            feedback.toast("已导出 " + rows.size() + " 行 CSV");
        } catch (IOException e) {
            showErrorDialog("导出失败", readable(e));
            feedback.status("导出失败：" + readable(e));
        }
    }

    private static String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return "BLOB(" + bytes.length + "B)";
        }
        return String.valueOf(value);
    }

    private static Object parseCellValue(ColumnDef definition, String text) {
        if (text.isEmpty() && !textual(definition.type())) {
            return null;
        }
        return CellValueParser.parse(definition, text);
    }

    private static boolean textual(ColumnType type) {
        return type == ColumnType.TEXT || type == ColumnType.JSON
                || type == ColumnType.UUID || type == ColumnType.OTHER;
    }

    private void applyValueFilter(ColumnDef definition, Object value, boolean exclude) {
        FilterCondition condition = value == null
                ? (exclude ? FilterCondition.isNotNull(definition.name())
                        : FilterCondition.isNull(definition.name()))
                : FilterCondition.of(definition.name(),
                        exclude ? io.agenticdb.introspect.FilterOperator.NOT_EQ
                                : io.agenticdb.introspect.FilterOperator.EQ,
                        value);
        applyFilter(condition);
        feedback.status((exclude ? "排除 " : "筛选 ") + definition.name() + " = "
                + CellValueParser.display(value));
    }

    /** 打开详情抽屉；测试与右键菜单共用。 */
    void showDetails(Map<String, Object> row, ColumnDef definition) {
        if (definition == null) {
            return;
        }
        detailsDrawer.showDetails(definition, cellValue(row, definition), row, tableName);
        feedback.status("详情：" + definition.name());
    }

    private final class ValueCell extends TableCell<Map<String, Object>, Object> {
        private static final List<String> STYLES = List.of(
                "adb-grid__cell--null", "adb-grid__cell--empty", "adb-grid__cell--number",
                "adb-grid__cell--boolean", "adb-grid__cell--binary", "adb-grid__cell--dirty",
                "adb-grid__cell--json", "adb-grid__cell--enum", "adb-grid__cell--date",
                "adb-grid__cell--long-text");

        private final ColumnDef definition;
        private TextField editor;

        private ValueCell(ColumnDef definition) {
            this.definition = definition;
            setOnContextMenuRequested(event -> {
                if (isEmpty() || getTableRow() == null
                        || getTableRow().getItem() == null) {
                    return;
                }
                buildCellMenu(getTableRow().getItem()).show(
                        this, event.getScreenX(), event.getScreenY());
                event.consume();
            });
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STYLES);
            setTooltip(null);
            setAlignment(Pos.CENTER_LEFT);
            setGraphic(isEditing() ? editor : null);

            if (empty) {
                setText(null);
                return;
            }
            Map<String, Object> row = getTableRow() == null ? null : getTableRow().getItem();
            Object value = row == null ? item : cellValue(row, definition);
            Long draftId = draftId(row);
            RowIdentity identity = identity(row);
            boolean dirty = draftId == null && identity != null
                    && editBuffer.hasCell(identity, definition.name());
            if (dirty) {
                getStyleClass().add("adb-grid__cell--dirty");
            }

            if (draftId != null && !editBuffer.hasDraftValue(draftId, definition.name())) {
                setText("");
                getStyleClass().add("adb-grid__cell--empty");
                return;
            }
            boolean deleted = identity != null && editBuffer.isDeleted(identity);
            boolean rowEditable = tableSchema != null && tableSchema.editable()
                    && (draftId != null || identity != null);
            boolean cellEditable = rowEditable && (draftId != null || !definition.primaryKey());
            if (definition.type() == ColumnType.BOOLEAN && !isEditing()
                    && value != null && !(value instanceof String text && text.isEmpty())) {
                Boolean bool = CellRenderer.booleanValue(value);
                if (bool != null) {
                    CheckBox toggle = new CheckBox();
                    toggle.getStyleClass().add("adb-grid__boolean");
                    toggle.setSelected(bool);
                    toggle.setDisable(!cellEditable || deleted);
                    toggle.setTooltip(new Tooltip(bool ? "true · 点击切换" : "false · 点击切换"));
                    toggle.setOnAction(event -> {
                        Object next = value instanceof Number ? (bool ? 0 : 1)
                                : value instanceof Boolean ? !bool : (bool ? "0" : "1");
                        toggle.setSelected(bool);
                        setRowValue(row, definition, next);
                    });
                    setText(null);
                    setGraphic(toggle);
                    setAlignment(Pos.CENTER);
                    return;
                }
            }

            CellRenderer.Rendered rendered = CellRenderer.render(definition, value);
            setText(rendered.text());
            if (!rendered.styleClass().isEmpty()) {
                getStyleClass().add(rendered.styleClass());
            }
            if (rendered.tooltip() != null && !rendered.tooltip().isBlank()) {
                setTooltip(new Tooltip(rendered.tooltip()));
            }
            if (definition.type() == ColumnType.NUMBER) {
                setAlignment(Pos.CENTER_RIGHT);
            }
        }

        @Override
        public void startEdit() {
            if (!isEditable() || !getTableColumn().isEditable() || !grid.isEditable()
                    || isEmpty() || getTableRow() == null || getTableRow().getItem() == null) {
                return;
            }
            Map<String, Object> row = getTableRow().getItem();
            Long draftId = draftId(row);
            RowIdentity identity = identity(row);
            if (draftId == null && (identity == null || definition.primaryKey())) {
                return;
            }
            Object value = cellValue(row, definition);
            super.startEdit();
            editor = new TextField(value == null ? "" : String.valueOf(value));
            editor.getStyleClass().add("adb-grid__editor");
            editor.setOnAction(event -> commitEditor());
            editor.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    event.consume();
                }
            });
            editor.focusedProperty().addListener((observable, wasFocused, focused) -> {
                if (wasFocused && !focused && isEditing()) {
                    commitEditor();
                }
            });
            setText(null);
            setGraphic(editor);
            Platform.runLater(() -> {
                editor.selectAll();
                editor.requestFocus();
            });
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            editor = null;
            setGraphic(null);
            updateItem(getItem(), isEmpty());
        }

        private void commitEditor() {
            if (!isEditing() || editor == null || getTableRow() == null
                    || getTableRow().getItem() == null) {
                return;
            }
            Map<String, Object> row = getTableRow().getItem();
            Long draftId = draftId(row);
            RowIdentity identity = identity(row);
            if (draftId == null && identity == null) {
                cancelEdit();
                return;
            }
            try {
                Object next = parseCellValue(definition, editor.getText());
                if (next == null && !definition.nullable()) {
                    throw new IllegalArgumentException(definition.name() + " 不允许 NULL");
                }
                if (draftId != null) {
                    if (editBuffer.setDraftCell(draftId, definition.name(), next)) {
                        updateDirty();
                        feedback.status("已修改新增行 · " + definition.name() + " · "
                                + CellValueParser.display(next));
                    }
                } else {
                    Object original = row.get(definition.name());
                    if (editBuffer.setCell(identity, definition.name(), original, next)) {
                        updateDirty();
                        feedback.status("已修改 " + definition.name() + " · "
                                + CellValueParser.display(next) + " · 待保存");
                    }
                }
                cancelEdit();
                grid.refresh();
            } catch (IllegalArgumentException e) {
                if (!editor.getStyleClass().contains("adb-grid__editor--error")) {
                    editor.getStyleClass().add("adb-grid__editor--error");
                }
                editor.setTooltip(new Tooltip(e.getMessage()));
                feedback.status("无效值：" + e.getMessage());
            }
        }

        private ContextMenu buildCellMenu(Map<String, Object> row) {
            Object value = cellValue(row, definition);
            RowIdentity identity = identity(row);
            Long draftId = draftId(row);
            boolean draft = draftId != null;
            boolean deleted = identity != null && editBuffer.isDeleted(identity);
            boolean rowEditable = tableSchema != null && tableSchema.editable()
                    && (draft || identity != null);
            boolean cellEditable = rowEditable && (draft || !definition.primaryKey());

            ContextMenu menu = new ContextMenu();
            MenuItem edit = new MenuItem("编辑单元格");
            edit.setDisable(!cellEditable || deleted);
            edit.setOnAction(event -> startEdit());

            MenuItem copy = new MenuItem("复制值");
            copy.setOnAction(event -> copyValue(value));

            MenuItem copySql = new MenuItem("复制为 SQL");
            copySql.setOnAction(event -> copySql(row, value));

            MenuItem copyRow = new MenuItem("复制行");
            copyRow.setOnAction(event -> copyRowTsv(row));

            MenuItem setNull = new MenuItem("设为 NULL");
            setNull.setDisable(!cellEditable || deleted || !definition.nullable());
            setNull.setOnAction(event -> setRowValue(row, definition, null));

            MenuItem setEmpty = new MenuItem("设为空串");
            setEmpty.setDisable(!cellEditable || deleted || !textual(definition.type()));
            setEmpty.setOnAction(event -> setRowValue(row, definition, ""));

            MenuItem filter = new MenuItem("筛选此值");
            filter.setDisable(draft);
            filter.setOnAction(event -> applyValueFilter(definition, value, false));

            MenuItem exclude = new MenuItem("排除此值");
            exclude.setDisable(draft);
            exclude.setOnAction(event -> applyValueFilter(definition, value, true));

            MenuItem details = new MenuItem("查看详情…");
            details.setId("data-cell-details");
            details.setOnAction(event -> showDetails(row, definition));

            MenuItem clone = new MenuItem("克隆行");
            clone.setDisable(tableSchema == null || !tableSchema.editable());
            clone.setOnAction(event -> cloneRow(row));

            MenuItem delete = new MenuItem(draft ? "移除新增行"
                    : deleted ? "已标记删除（⌘Z 撤销）" : "删除行");
            delete.setDisable(!rowEditable || deleted);
            delete.setOnAction(event -> deleteRow(row));

            menu.getItems().addAll(edit, new SeparatorMenuItem(), setNull, setEmpty,
                    new SeparatorMenuItem(), copy, copySql, copyRow,
                    new SeparatorMenuItem(), filter, exclude, details,
                    new SeparatorMenuItem(), clone, delete);
            return menu;
        }

        private void copyRowTsv(Map<String, Object> row) {
            StringBuilder text = new StringBuilder();
            List<String> columns = visibleColumnNames();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    text.append('\t');
                }
                ColumnDef definition = columnDef(columns.get(i));
                Object value = definition == null ? row.get(columns.get(i))
                        : cellValue(row, definition);
                text.append(value == null ? "" : String.valueOf(value));
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(text.toString());
            Clipboard.getSystemClipboard().setContent(content);
            feedback.status("已复制整行");
        }

        private void copyValue(Object value) {
            ClipboardContent content = new ClipboardContent();
            content.putString(value == null ? "" : String.valueOf(value));
            Clipboard.getSystemClipboard().setContent(content);
            feedback.status("已复制 " + definition.name());
        }

        private void copySql(Map<String, Object> row, Object value) {
            RowIdentity identity = identity(row);
            if (identity == null) {
                return;
            }
            StringBuilder sql = new StringBuilder("UPDATE ")
                    .append(session.dialect().quote(tableName))
                    .append(" SET ")
                    .append(session.dialect().quote(definition.name()))
                    .append(" = ")
                    .append(sqlLiteral(value))
                    .append(" WHERE ");
            int index = 0;
            for (Map.Entry<String, Object> key : identity.values().entrySet()) {
                if (index++ > 0) {
                    sql.append(" AND ");
                }
                sql.append(session.dialect().quote(key.getKey()))
                        .append(" = ").append(sqlLiteral(key.getValue()));
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(sql.toString());
            Clipboard.getSystemClipboard().setContent(content);
            feedback.status("已复制 SQL");
        }

    }

    private static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof byte[] bytes) {
            return "<BLOB " + bytes.length + " B>";
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    // ─────────────────────────── 状态 ───────────────────────────

    private void showLoading() {
        spinner.setVisible(true);
        spinner.setManaged(true);
        retry.setVisible(false);
        retry.setManaged(false);
        stateText.getStyleClass().remove("adb-grid__state-text--error");
        stateText.setText("加载中…");
        stateBox.setVisible(true);
        stateBox.setManaged(true);
        grid.setDisable(true);
        updatePager();
    }

    private void showError(String message) {
        spinner.setVisible(false);
        spinner.setManaged(false);
        retry.setVisible(true);
        retry.setManaged(true);
        if (!stateText.getStyleClass().contains("adb-grid__state-text--error")) {
            stateText.getStyleClass().add("adb-grid__state-text--error");
        }
        stateText.setText(message);
        stateText.setWrapText(true);
        stateText.setMaxWidth(380);
        stateBox.setVisible(true);
        stateBox.setManaged(true);
        grid.setDisable(true);
        updatePager();
        updateEditControls();
    }

    private void hideState() {
        stateBox.setVisible(false);
        stateBox.setManaged(false);
        grid.setDisable(false);
    }

    private void updatePager() {
        long pages = QueryLimits.totalPages(total, pageSize);
        pageLabel.setText(pages < 0 ? "第 — / — 页"
                : pages == 0 ? "第 0 / 0 页"
                : "第 " + QueryLimits.pageNumberOf(offset, pageSize) + " / " + pages + " 页");
        rangeLabel.setText(rangeText());
        previous.setDisable(loading || offset <= 0);
        next.setDisable(loading || (total >= 0 && offset + pageSize >= total));
        firstPageButton.setDisable(loading || offset <= 0);
        lastPageButton.setDisable(loading || total <= 0
                || (total - 1) / pageSize * pageSize == offset);
        jumpField.setDisable(loading || total < 0);
        pageSizeBox.setDisable(loading);
    }

    private String rangeText() {
        if (total < 0) {
            return lastRowCount == 0 ? "0 行" : "已加载 " + lastRowCount + " 行 · 总数未统计";
        }
        if (total == 0) {
            return "0 行";
        }
        long first = Math.min(offset + 1, total);
        long last = Math.min(offset + lastRowCount, total);
        return String.format("%,d–%,d / %,d", first, last, total);
    }

    /** 最近一次下发的分页 SQL；用于状态复盘与 TestFX 的 ORDER BY / WHERE 断言。 */
    public String lastSql() {
        return lastSql;
    }

    public DataQuery query() {
        return query;
    }

    /** 供查询恢复/测试注入完整条件；正式 UI 通过列头与条件构造器产生同样状态。 */
    void applyQuery(DataQuery next) {
        query = Objects.requireNonNull(next, "next");
        total = -1;
        offset = 0;
        renderQueryState();
        load(false);
    }

    private static String readable(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        Throwable cursor = error;
        String message = null;
        while (cursor != null) {
            if (cursor.getMessage() != null && !cursor.getMessage().isBlank()) {
                message = cursor.getMessage();
            }
            cursor = cursor.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    /**
     * 取消当前正在跑的加载 / 保存（4.5-13）。
     *
     * <p>⭐ 以前数据页的加载与保存<b>无法真正取消</b>：{@code JdbcSession.activeStatement}
     * 只在 {@code execute()} 里赋值，{@code query()} / {@code update()} 不赋值，
     * 所以 {@code ⌘.} 只能逻辑放弃 —— JDBC 语句还在数据库里跑。
     * 现在 {@code query()} / {@code update()} 也记录语句，
     * 这里调 {@code Statement.cancel()} 能真的把它打断。
     */
    public void cancel() {
        requestSequence++;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        session.cancelCurrent();
        loading = false;
        saving = false;
        grid.setDisable(false);
        hideState();
        updateEditControls();
        feedback.status("已取消 · " + tableName);
    }

    /** 是否正在加载或保存。 */
    public boolean isBusy() {
        return loading || saving;
    }

    @Override
    public void close() {
        requestSequence++;
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        detailsDrawer.close();
    }
}
