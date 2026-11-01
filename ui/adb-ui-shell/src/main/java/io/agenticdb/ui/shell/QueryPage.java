package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.RowHeights;
import io.agenticdb.ui.Icons;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.introspect.QueryPaginator;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
import io.agenticdb.model.PageSize;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;
import io.agenticdb.safety.DangerClassifier;
import io.agenticdb.safety.ErrorLocator;
import io.agenticdb.sql.SqlFormatter;
import io.agenticdb.sql.SqlSplitter;
import io.agenticdb.sql.SqlSplitter.SqlStatement;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.TableExporter;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import org.fxmisc.richtext.CodeArea;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * 查询页：SQL 编辑器 + 多语句结果 + 取消 + 错误定位 + 执行计划 + 历史。
 *
 * <p>编辑内容不被数据页的编辑缓冲管理；查询页只负责「执行」与「展示」。
 * 所有 JDBC 调用都提交到共享单线程执行器，取消通过 {@link DbSession#cancelCurrent()}。
 */
public final class QueryPage extends VBox implements AutoCloseable {

    private record RunResult(List<ExecutionResult> results, java.sql.SQLException error,
                             SqlStatement failed, int failedIndex, long durationMs,
                             String executedText, List<String> texts,
                             List<List<Object>> bound) {
    }

    private final DbSession session;
    private final String connectionName;
    private final Executor dbExecutor;
    private final DataGridPage.Feedback feedback;
    private final QueryHistory history;

    private final SqlEditor editor = new SqlEditor();
    private final TabPane results = new TabPane();
    /** ⭐ 结果网格也要跟密度 —— 否则「紧凑」在查询页看起来没生效。 */
    private final java.util.List<TableView<java.util.Map<String, Object>>> resultTables =
            new java.util.ArrayList<>();
    /** ⭐ P6-2：可分页结果的导出器（顺序与结果页签一致）。 */
    private final List<PagedLoader> pagers = new ArrayList<>();
    /** 密度档位 → 行高（结果网格按 D1 比档位低一档，初值 = {@code RowHeights.grid("normal")}）。 */
    private int rowHeight = RowHeights.grid("normal");
    private final Label status = new Label(I18n.t("query.ready"));
    private final Label risk = new Label(I18n.t("status.readOnly"));
    private final Label range = new Label("—");
    private final Label duration = new Label("— ms");
    private final MenuButton historyButton = new MenuButton(I18n.t("grid.toolbar.history"));
    /** ⭐ N21：▶Run 是分裂按钮 —— 点主体直接跑当前语句，箭头展开 Run All / 带参数执行。 */
    private final javafx.scene.control.SplitMenuButton run = new javafx.scene.control.SplitMenuButton();
    private final Button cancel = IconButtons.of(Icons.Icon.STOP, I18n.t("query.stop"));
    private final Button explain = IconButtons.of(Icons.Icon.EXPLAIN, I18n.t("query.explain"));
    private final Button format = new Button(I18n.t("query.beautify"));
    private final Button save = new Button(I18n.t("query.save"));
    private final MenuButton askAi = new MenuButton(I18n.t("query.askAi"));
    private MenuButton nameMenu;
    private MenuButton connectionMenu;
    private String queryName;
    private final HBox findBar = new HBox(6);
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();

    private Predicate<Danger> confirmation = this::confirmDanger;
    private Runnable stateListener = () -> { };
    private IntConsumer dirtyListener = count -> { };
    /** 「存为命名查询」的回调（名字, SQL）。 */
    private java.util.function.BiConsumer<String, String> onSaveNamed = (name, sql) -> { };
    /** ⭐ N21：打开已保存查询清单（名字菜单里的入口）。 */
    private Runnable onOpenSavedQueries = () -> { };
    /** ⭐ N21：插入代码片段（Code Snippet 按钮）。 */
    private Runnable onInsertSnippet = () -> { };
    /** ⭐ N21：在另一条连接上开新查询（连接菜单里的入口）。 */
    private Runnable onNewQuery = () -> { };
    /** ⭐ Q6：存为代码片段（存的是**选中的文本**，插入时用）。 */
    private java.util.function.BiConsumer<String, String> onSaveSnippet = (name, sql) -> { };
    /** ⭐ Q6：片段库（插入用）。 */
    private java.util.function.Supplier<List<SavedQueries.Entry>> snippetSource = List::of;
    /** 偏好存储（列宽持久化）。 */
    private final SettingsStore settings;
    private long requestSequence;
    private Future<?> inFlight;
    private boolean running;
    private boolean cancelled;
    private boolean dirty;
    private boolean activated;
    private File currentFile;
    private int saveCounter;

    /** 接上「存为命名查询」的落点。 */
    public void setOnSaveNamed(java.util.function.BiConsumer<String, String> onSaveNamed) {
        this.onSaveNamed = onSaveNamed == null ? (name, sql) -> { } : onSaveNamed;
    }

    /** ⭐ N21：名字菜单 →「打开已保存查询」。 */
    public void setOnOpenSavedQueries(Runnable action) {
        this.onOpenSavedQueries = action == null ? () -> { } : action;
    }

    /** ⭐ N21：Code Snippet 按钮的落点（插入到光标处）。 */
    public void setOnInsertSnippet(Runnable action) {
        this.onInsertSnippet = action == null ? () -> { } : action;
    }

    /** ⭐ N21：连接菜单 →「New Query」。 */
    public void setOnNewQuery(Runnable action) {
        this.onNewQuery = action == null ? () -> { } : action;
    }

    /** ⭐ N21：名字菜单显示的查询名（新查询为 Untitled）。 */
    public void setQueryName(String name) {
        queryName = name == null || name.isBlank() ? null : name.trim();
        if (nameMenu != null) {
            nameMenu.setText(queryName == null ? I18n.t("query.name.untitled") : queryName);
        }
    }

    /** 供门禁断言：名字菜单当前显示的名字。 */
    String queryNameText() {
        return queryName == null ? I18n.t("query.name.untitled") : queryName;
    }

    /** 接上「存为代码片段」的落点（Q6）。 */
    public void setOnSaveSnippet(java.util.function.BiConsumer<String, String> onSaveSnippet) {
        this.onSaveSnippet = onSaveSnippet == null ? (name, sql) -> { } : onSaveSnippet;
    }

    /** 接上片段库来源（Q6）。 */
    public void setSnippetSource(java.util.function.Supplier<List<SavedQueries.Entry>> source) {
        this.snippetSource = source == null ? List::of : source;
    }

    /**
     * ⭐ Q6：把**选中的文本**存成代码片段。
     *
     * <p>没有选区就存整段（用户可能就是想存整条）。
     */
    public void saveSnippet() {
        String selected = editor.area().getSelectedText();
        String text = selected == null || selected.isBlank() ? editor.text() : selected;
        if (text == null || text.isBlank()) {
            feedback.status(I18n.t("query.nothingToSave"));
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(I18n.t("query.snippetDefault", ++saveCounter));
        dialog.setTitle(I18n.t("action.query.save-snippet"));
        dialog.setHeaderText(I18n.t("query.snippetNamePrompt"));
        dialog.setContentText(text.length() > 120 ? text.substring(0, 120) + "…" : text);
        java.util.Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        onSaveSnippet.accept(name.get().trim(), text);
        feedback.status(I18n.t("query.snippetSaved", name.get().trim()));
    }

    /**
     * ⭐ Q6：把一个片段**插入到光标处**。
     *
     * <p>插入而不是覆盖 —— 片段的价值就在「拼装」。
     */
    public void insertSnippet(SavedQueries.Entry entry) {
        if (entry == null) {
            return;
        }
        CodeArea area = editor.area();
        int caret = area.getCaretPosition();
        String text = entry.sql();
        area.replaceSelection(text);
        area.moveTo(Math.min(caret + text.length(), area.getText().length()));
        editor.requestEditorFocus();
        feedback.status(I18n.t("query.snippetInserted", entry.name()));
    }

    /** ⭐ Q6：片段列表（供弹窗与命令面板用）。 */
    public List<SavedQueries.Entry> snippets() {
        List<SavedQueries.Entry> items = snippetSource.get();
        return items == null ? List.of() : items;
    }

    /** 给当前 SQL 起个名字存起来（已保存查询库）。 */
    private void saveNamed() {
        String sql = editor.text();
        if (sql == null || sql.isBlank()) {
            feedback.status(I18n.t("query.nothingToSave"));
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(I18n.t("query.namedDefault", ++saveCounter));
        dialog.setTitle(I18n.t("query.saveNamed"));
        dialog.setHeaderText(I18n.t("query.queryNamePrompt"));
        dialog.setContentText(sql.length() > 120 ? sql.substring(0, 120) + "…" : sql);
        java.util.Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        onSaveNamed.accept(name.get().trim(), sql);
        setQueryName(name.get().trim());
        feedback.status(I18n.t("query.namedSaved", name.get().trim()));
    }

    public QueryPage(DbSession session, String connectionName, String initialSql,
                     Executor dbExecutor, SettingsStore settings,
                     DataGridPage.Feedback feedback) {
        this.session = session;
        this.connectionName = connectionName;
        this.dbExecutor = dbExecutor;
        this.feedback = feedback;
        this.settings = settings;
        this.history = new QueryHistory(settings);

        getStyleClass().addAll("adb-page", "adb-query-page");
        buildUi();
        editor.addTextListener(text -> {
            setDirty(true);
            updateRisk();
            updateButtons();
        });
        if (initialSql != null && !initialSql.isBlank()) {
            editor.setText(initialSql);
        }
        updateRisk();
        updateButtons();
        refreshHistory();
    }

    // ─────────────────────────── 构建 ───────────────────────────

    private void buildUi() {
        historyButton.setId("query-history");
        historyButton.getStyleClass().add("adb-button");
        historyButton.getItems().add(new MenuItem(I18n.t("query.noHistory")));

        // ── 行 1：低频 / 编辑类动作（Navicat 17 查询工具带上排） ──
        save.setId("query-save");
        save.getStyleClass().add("adb-button");
        save.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled(I18n.t("query.tip.save"), "Mod+S")));
        save.setOnAction(event -> save());

        Button builder = new Button(I18n.t("query.builder"));
        builder.setId("query-builder");
        builder.getStyleClass().add("adb-button");
        builder.setTooltip(new Tooltip(I18n.t("query.tip.builder")));
        builder.setOnAction(event -> showCapabilityNotice(I18n.t("query.builder"), "grid.capability.queryBuilder"));

        format.setId("query-format");
        format.getStyleClass().add("adb-button");
        format.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled(I18n.t("query.tip.beautify"), "Alt+Shift+F")));
        format.setOnAction(event -> formatSql());

        Button snippet = new Button(I18n.t("query.snippet"));
        snippet.setId("query-snippet");
        snippet.getStyleClass().add("adb-button");
        snippet.setTooltip(new Tooltip(I18n.t("query.tip.snippet")));
        snippet.setOnAction(event -> onInsertSnippet.run());

        askAi.setId("query-ask-ai");
        askAi.getStyleClass().add("adb-button");
        askAi.setTooltip(new Tooltip(I18n.t("query.tip.askAi")));
        MenuItem generate = new MenuItem(I18n.t("query.askAi.generate"));
        generate.setId("query-ask-ai-generate");
        generate.setOnAction(event ->
                showCapabilityNotice(I18n.t("query.askAi.generate"), "grid.capability.askAi"));
        MenuItem explainWithAi = new MenuItem(I18n.t("query.askAi.explain"));
        explainWithAi.setId("query-ask-ai-explain");
        explainWithAi.setOnAction(event ->
                showCapabilityNotice(I18n.t("query.askAi.explain"), "grid.capability.askAi"));
        askAi.getItems().addAll(generate, explainWithAi);

        risk.setId("query-risk");
        risk.getStyleClass().addAll("adb-badge", "adb-query__risk");
        risk.setTooltip(new Tooltip(I18n.t("query.tip.risk")));

        Region row1Spacer = new Region();
        HBox.setHgrow(row1Spacer, Priority.ALWAYS);

        HBox row1 = new HBox(6, save, builder, format, snippet, askAi,
                row1Spacer, risk, historyButton);
        row1.getStyleClass().add("adb-query__toolbar-row");
        row1.setAlignment(Pos.CENTER_LEFT);

        // ── 行 2：查询名 / 连接 / 执行（Navicat 17 查询工具带下排） ──
        nameMenu = new MenuButton(I18n.t("query.name.untitled"));
        nameMenu.setId("query-name");
        nameMenu.getStyleClass().add("adb-button");
        nameMenu.setTooltip(new Tooltip(I18n.t("query.tip.name")));
        MenuItem saveNamed = new MenuItem(I18n.t("query.saveNamed"));
        saveNamed.setId("query-save-named");
        saveNamed.setOnAction(event -> saveNamed());
        MenuItem openSaved = new MenuItem(I18n.t("query.name.open"));
        openSaved.setId("query-open-saved");
        openSaved.setOnAction(event -> onOpenSavedQueries.run());
        nameMenu.getItems().addAll(saveNamed, openSaved);

        connectionMenu = new MenuButton(connectionName);
        connectionMenu.setId("query-connection");
        connectionMenu.getStyleClass().add("adb-button");
        connectionMenu.setTooltip(new Tooltip(I18n.t("query.tip.connection")));
        MenuItem newQuery = new MenuItem(I18n.t("action.new-query"));
        newQuery.setId("query-new");
        newQuery.setOnAction(event -> onNewQuery.run());
        MenuItem bound = new MenuItem(I18n.t("query.connection.bound"));
        bound.setDisable(true);
        connectionMenu.getItems().addAll(newQuery, new SeparatorMenuItem(), bound);

        run.setText(I18n.t("query.run"));
        run.setId("query-run");
        run.setGraphic(Icons.of(Icons.Icon.RUN, Icons.SIZE_LABEL));
        run.getStyleClass().addAll("adb-button", "adb-button--primary");
        run.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled(I18n.t("query.tip.run"), "Mod+Enter")));
        run.setOnAction(event -> runCurrent());
        MenuItem runItem = new MenuItem(I18n.t("query.run"));
        runItem.setId("query-run-current");
        runItem.setGraphic(Icons.of(Icons.Icon.RUN, Icons.SIZE_LABEL));
        runItem.setOnAction(event -> runCurrent());
        MenuItem runAllItem = new MenuItem(I18n.t("query.runAll"));
        runAllItem.setId("query-run-all");
        runAllItem.setGraphic(Icons.of(Icons.Icon.RUN_ALL, Icons.SIZE_LABEL));
        runAllItem.setOnAction(event -> runAll());
        MenuItem paramsItem = new MenuItem(I18n.t("query.runWithParams"));
        paramsItem.setId("query-variables");
        paramsItem.setOnAction(event -> runWithVariables());
        run.getItems().addAll(runItem, runAllItem, new SeparatorMenuItem(), paramsItem);

        cancel.setId("query-cancel");
        cancel.getStyleClass().add("adb-button");
        cancel.setTooltip(new Tooltip(I18n.t("query.tip.stop")));
        cancel.setOnAction(event -> cancel());

        explain.setId("query-explain");
        explain.getStyleClass().add("adb-button");
        explain.setTooltip(new Tooltip(I18n.t("query.tip.explain")));
        explain.setOnAction(event -> explainCurrent());

        Region row2Spacer = new Region();
        HBox.setHgrow(row2Spacer, Priority.ALWAYS);

        HBox row2 = new HBox(6, nameMenu, connectionMenu, run, cancel, explain, row2Spacer);
        row2.getStyleClass().add("adb-query__toolbar-row");
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox toolbar = new VBox(row1, row2);
        toolbar.setId("query-toolbar");
        toolbar.getStyleClass().add("adb-query__toolbar");

        // ⭐ N21：Clear 从工具带收敛到编辑器右键菜单 —— 它是一次性动作，
        //    不值得和 Run/Save 抢常驻位置；右键菜单本来就是「编辑这段文本」的入口。
        ContextMenu editorMenu = editor.area().getContextMenu();
        if (editorMenu == null) {
            editorMenu = new ContextMenu();
            editor.area().setContextMenu(editorMenu);
        }
        MenuItem clear = new MenuItem(I18n.t("query.clear"));
        clear.setId("query-clear");
        clear.setOnAction(event -> editor.setText(""));
        editorMenu.getItems().addAll(new SeparatorMenuItem(), clear);

        buildFindBar();

        editor.area().addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                if (event.isShiftDown()) {
                    runAll();
                } else {
                    runCurrent();
                }
                event.consume();
            } else if (event.getCode() == KeyCode.F && event.isAltDown()
                    && event.isShiftDown()) {
                formatSql();
                event.consume();
            } else if (event.getCode() == KeyCode.F && event.isShortcutDown()
                    && !event.isAltDown()) {
                focusSearch();
                event.consume();
            } else if (event.getCode() == KeyCode.G && event.isShortcutDown()) {
                findNext();
                event.consume();
            } else if (event.getCode() == KeyCode.H && event.isControlDown()) {
                focusReplace();
                event.consume();
            }
        });

        results.setId("query-results");
        results.getStyleClass().add("adb-query__results");
        results.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        results.setTabMinWidth(96);
        results.setTabMinHeight(28);
        results.setTabMaxHeight(32);

        SplitPane split = new SplitPane(editor, results);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.42);
        split.getStyleClass().add("adb-query__split");
        VBox.setVgrow(split, Priority.ALWAYS);

        status.setId("query-status");
        status.getStyleClass().add("adb-query__status-text");
        range.getStyleClass().add("adb-query__status-meta");
        duration.getStyleClass().add("adb-query__status-meta");

        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, status, statusSpacer, range, duration);
        statusBar.getStyleClass().add("adb-query__statusbar");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(toolbar, findBar, split, statusBar);
    }

    private void buildFindBar() {
        findBar.setId("query-find-bar");
        findBar.getStyleClass().add("adb-query__find");
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setVisible(false);
        findBar.setManaged(false);

        findField.setId("query-find");
        findField.setPromptText(I18n.t("grid.find.prompt"));
        findField.setPrefWidth(180);
        findField.getStyleClass().add("adb-query__find-field");

        replaceField.setId("query-replace");
        replaceField.setPromptText(I18n.t("grid.find.replacePrompt"));
        replaceField.setPrefWidth(180);
        replaceField.getStyleClass().add("adb-query__find-field");

        Button next = new Button(I18n.t("grid.find.next"));
        next.setId("query-find-next");
        next.getStyleClass().add("adb-button");
        next.setOnAction(event -> findNext());

        Button replaceOne = new Button(I18n.t("grid.find.replace"));
        replaceOne.setId("query-replace-one");
        replaceOne.getStyleClass().add("adb-button");
        replaceOne.setOnAction(event -> replaceCurrent());

        Button replaceAll = new Button(I18n.t("grid.find.replaceAll"));
        replaceAll.setId("query-replace-all");
        replaceAll.getStyleClass().add("adb-button");
        replaceAll.setOnAction(event -> replaceAll());

        Button close = IconButtons.icon(Icons.Icon.CLOSE, I18n.t("tab.close"));
        close.getStyleClass().add("adb-icon-button");
        close.setOnAction(event -> hideFind());

        findField.setOnAction(event -> findNext());
        findField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hideFind();
                event.consume();
            }
        });

        findBar.getChildren().addAll(findField, next, replaceField, replaceOne, replaceAll, close);
    }

    // ─────────────────────────── 状态 ───────────────────────────

    public void activate() {
        activated = true;
        // U8：「查询页 · demo · 就绪」三个信息各有更权威的家（标签名 / 连接灯 / 待提交段），
        //      消息位只在真有话说时占位。
        feedback.status("");
        feedback.range(range.getText());
        feedback.duration(-1);
        feedback.dirty(dirty ? 1 : 0);
        updateButtons();
    }

    public int dirtyCount() {
        return dirty ? 1 : 0;
    }

    /** 保存/打开文件后由外壳调用；执行 SQL 本身不清除未保存标记。 */
    public void markClean() {
        setDirty(false);
    }

    private void setDirty(boolean value) {
        if (dirty == value) {
            return;
        }
        dirty = value;
        dirtyListener.accept(value ? 1 : 0);
        if (activated) {
            feedback.dirty(value ? 1 : 0);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int resultTabCount() {
        return results.getTabs().size();
    }

    /** P6-2：把一个可分页结果的全部行导出到给定 writer（测试与批处理用）。 */
    long exportAllResultsTo(int resultIndex, Writer out) throws Exception {
        return pagers.get(resultIndex).exportAllTo(out);
    }

    /** P6-2：把当前页导出到给定 writer（测试用，绕过文件选择框）。 */
    void exportCurrentPageTo(int resultIndex, Writer out) throws Exception {
        pagers.get(resultIndex).exportCurrentTo(out);
    }

    public SqlEditor editor() {
        return editor;
    }

    public void setSql(String sql) {
        editor.setText(sql);
        editor.requestEditorFocus();
    }

    public String sql() {
        return editor.text();
    }

    public String statusText() {
        return status.getText();
    }

    /** 测试与外部调用可替换危险确认逻辑；生产环境保持默认 Alert。 */
    void setConfirmationHandler(Predicate<Danger> handler) {
        confirmation = handler == null ? danger -> true : handler;
    }

    /** 外壳据此同步菜单/工具栏的启用状态。 */
    void setStateListener(Runnable listener) {
        stateListener = listener == null ? () -> { } : listener;
        updateButtons();
    }

    /** 外壳据此在标签上显示未保存圆点。 */
    void setDirtyListener(IntConsumer listener) {
        dirtyListener = listener == null ? count -> { } : listener;
        dirtyListener.accept(dirty ? 1 : 0);
    }

    void focusSearch() {
        showFindBar();
        if (findField.getText().isBlank()) {
            String selected = editor.area().getSelectedText();
            if (selected != null && !selected.isBlank()) {
                findField.setText(selected);
            }
        }
        Platform.runLater(() -> {
            findField.requestFocus();
            findField.selectAll();
        });
    }

    private void focusReplace() {
        showFindBar();
        Platform.runLater(() -> {
            replaceField.requestFocus();
            replaceField.selectAll();
        });
    }

    private void showFindBar() {
        findBar.setVisible(true);
        findBar.setManaged(true);
    }

    private void hideFind() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        editor.requestEditorFocus();
    }

    private void findNext() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String text = editor.text();
        int from = editor.area().getSelection().getEnd();
        int index = text.indexOf(needle, from);
        if (index < 0) {
            index = text.indexOf(needle);
        }
        if (index < 0) {
            status.setText(I18n.t("query.find.notFound", needle));
            return;
        }
        editor.select(index, index + needle.length());
        status.setText(I18n.t("query.find.found", needle, index + 1));
    }

    private void replaceCurrent() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String selected = editor.area().getSelectedText();
        if (needle.equals(selected)) {
            editor.area().replaceSelection(replaceField.getText());
            status.setText(I18n.t("query.find.replaced"));
        }
        findNext();
    }

    private void replaceAll() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String text = editor.text();
        int count = countOccurrences(text, needle);
        if (count == 0) {
            status.setText(I18n.t("query.find.notFound", needle));
            return;
        }
        editor.setText(text.replace(needle, replaceField.getText()));
        status.setText(I18n.t("query.find.replacedCount", count));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private void updateRisk() {
        Danger danger = DangerClassifier.classify(editor.text());
        risk.setText(DangerLabels.t(danger));
        risk.getStyleClass().removeAll("adb-risk--read", "adb-risk--write",
                "adb-risk--structure", "adb-risk--admin");
        risk.getStyleClass().add(switch (danger) {
            case READ_ONLY -> "adb-risk--read";
            case MUTATING -> "adb-risk--write";
            case DESTRUCTIVE -> "adb-risk--structure";
            case ADMIN -> "adb-risk--admin";
        });
    }

    private void updateButtons() {
        run.setDisable(running);
        explain.setDisable(running || !explainsPlans());
        explain.setTooltip(new Tooltip(explainsPlans()
                ? I18n.t("query.tip.explain") : I18n.t("query.explain.unsupported")));
        format.setDisable(running);
        save.setDisable(running);
        cancel.setDisable(!running);
        stateListener.run();
    }

    // ─────────────────────────── 执行 ───────────────────────────

    public void runCurrent() {
        List<SqlStatement> statements = SqlSplitter.statementAt(editor.text(), editor.caret())
                .map(statement -> List.of(statement)).orElseGet(List::of);
        if (statements.isEmpty()) {
            status.setText(I18n.t("query.noStatement"));
            feedback.status(I18n.t("query.noStatement"));
            return;
        }
        execute(statements, false);
    }

    public void runAll() {
        List<SqlStatement> statements = SqlSplitter.split(editor.text());
        if (statements.isEmpty()) {
            status.setText(I18n.t("query.noStatement"));
            feedback.status(I18n.t("query.noStatement"));
            return;
        }
        execute(statements, false);
    }

    public void explainCurrent() {
        if (!explainsPlans()) {
            // ⛔ 死按钮红线：这一家做不到，就不假装去做。
            status.setText(I18n.t("query.explain.unsupported"));
            feedback.status(I18n.t("query.explain.unsupported"));
            return;
        }
        List<SqlStatement> statements = SqlSplitter.statementAt(editor.text(), editor.caret())
                .map(statement -> List.of(statement)).orElseGet(List::of);
        if (statements.isEmpty()) {
            status.setText(I18n.t("query.noExplainStatement"));
            return;
        }
        execute(statements, true);
    }

    /**
     * 当前连接的方言能不能出执行计划。
     *
     * <p>SQL Server 的 {@code SET SHOWPLAN_ALL ON} 是<b>另一条语句</b>，开了它当前语句
     * 就不再返回行；Oracle 要 {@code EXPLAIN PLAN FOR} + 再查 {@code TABLE(DBMS_XPLAN)} ——
     * 一次点击只送一条语句的界面套不进这种两步协议。没有真库验证前，
     * 与其摆一个必然给错答案的按钮，不如收起并说清楚。
     */
    private boolean explainsPlans() {
        return session != null && session.dialect().supportsExplainPlan();
    }

    public void formatSql() {
        String formatted = SqlFormatter.format(editor.text());
        if (formatted.isBlank()) {
            return;
        }
        editor.setText(formatted);
        setDirty(true);
        status.setText(I18n.t("query.formatted"));
        feedback.status(I18n.t("query.formattedFeedback"));
    }

    /** ⌘/：给选中行（或当前行）加/去 `-- ` 注释。 */
    public void toggleComment() {
        String text = editor.text();
        if (text.isEmpty()) {
            return;
        }
        int start = editor.area().getSelection().getStart();
        int end = editor.area().getSelection().getEnd();
        if (start == end) {
            int caret = editor.caret();
            start = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
            end = text.indexOf('\n', caret);
            if (end < 0) {
                end = text.length();
            }
        }
        int blockStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        int blockEnd = text.indexOf('\n', end);
        if (blockEnd < 0) {
            blockEnd = text.length();
        }
        String block = text.substring(blockStart, blockEnd);
        List<String> lines = block.lines().toList();
        boolean comment = lines.stream()
                .filter(line -> !line.isBlank())
                .allMatch(line -> line.stripLeading().startsWith("--"));
        StringBuilder replacement = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (comment) {
                int offset = line.indexOf("--");
                int prefix = line.startsWith("-- ", offset) ? 3 : 2;
                replacement.append(line, 0, offset);
                replacement.append(line.substring(Math.min(line.length(), offset + prefix)));
            } else {
                int indent = 0;
                while (indent < line.length()
                        && Character.isWhitespace(line.charAt(indent))) {
                    indent++;
                }
                replacement.append(line, 0, indent).append("-- ")
                        .append(line.substring(indent));
            }
            if (i < lines.size() - 1) {
                replacement.append('\n');
            }
        }
        String next = text.substring(0, blockStart) + replacement
                + text.substring(blockEnd);
        editor.setText(next);
        editor.select(blockStart, blockStart + replacement.length());
        setDirty(true);
        status.setText(comment ? I18n.t("query.uncommented") : I18n.t("query.commented"));
    }

    public void cancel() {
        if (!running) {
            return;
        }
        cancelled = true;
        running = false;
        requestSequence++;
        session.cancelCurrent();
        if (inFlight != null) {
            inFlight.cancel(true);
        }
        status.setText(I18n.t("query.cancelRequested"));
        feedback.status(I18n.t("query.cancelRequestedFeedback"));
        updateButtons();
    }

    /** 当前页所属的窗口（弹窗要继承它的主题）。 */
    private javafx.stage.Window ownerWindow() {
        return getScene() == null ? null : getScene().getWindow();
    }

    /**
     * ⭐ Q2：带变量执行。
     *
     * <p>先问用户要参数的值，再**参数化绑定**执行 —— 值不进 SQL 文本。
     */
    private void runWithVariables() {        if (running) {
            return;
        }
        List<SqlStatement> statements = SqlSplitter.statementAt(editor.text(), editor.caret())
                .map(statement -> List.of(statement)).orElseGet(List::of);
        if (statements.isEmpty()) {
            status.setText(I18n.t("query.noStatement"));
            return;
        }
        String sql = statements.getFirst().text();
        VariableDialog.Bound bound = VariableDialog.prompt(sql, ownerWindow());
        if (bound == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        io.agenticdb.sql.SqlParameters.Prepared prepared =
                io.agenticdb.sql.SqlParameters.toPositional(sql, bound.named(),
                        bound.positional(), missing);
        if (!missing.isEmpty()) {
            status.setText(I18n.t("query.missingParams", String.join(", ", missing)));
            return;
        }
        execute(prepared.sql(), prepared.values(), statements, false);
    }

    private void execute(List<SqlStatement> statements, boolean explainPlan) {
        execute(statements.getFirst().text(), List.of(), statements, explainPlan);
    }

    private void execute(String sql, List<String> params, List<SqlStatement> statements,
                         boolean explainPlan) {
        if (running) {
            return;
        }
        String joined = statements.stream().map(SqlStatement::text)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        Danger danger = DangerClassifier.classify(joined);
        if (!explainPlan && danger.requiresConfirm() && !confirmation.test(danger)) {
            status.setText(I18n.t("query.confirmDeclined"));
            return;
        }

        long request = ++requestSequence;
        cancelled = false;
        running = true;
        updateButtons();
        results.getTabs().clear();
        status.setText((explainPlan ? I18n.t("query.explaining") : I18n.t("query.running")));
        feedback.status(status.getText());
        feedback.duration(-1);

        List<SqlStatement> snapshot = List.copyOf(statements);
        Task<RunResult> task = new Task<>() {
            @Override
            protected RunResult call() {
                long started = System.nanoTime();
                List<ExecutionResult> completed = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                List<List<Object>> bound = new ArrayList<>();
                for (int i = 0; i < snapshot.size(); i++) {
                    SqlStatement statement = snapshot.get(i);
                    String sql = explainPlan ? explainSql(statement.text()) : statement.text();
                    try {
                        // ⭐ 第三层门禁：把刚才分类出来的危险等级**声明**给驱动层，
                        //    驱动层会自己再判一次 —— 声明比实际低就会被拒绝。
                        Danger statementDanger = DangerClassifier.classify(statement.text());
                        // ⭐ Q2：有参数时走参数化绑定（值不进 SQL 文本）。
                        List<Object> boundParams = i == 0 ? new ArrayList<>(params) : List.of();
                        completed.add(session.execute(sql, boundParams, statementDanger));
                        texts.add(statement.text());
                        bound.add(boundParams);
                    } catch (java.sql.SQLException e) {
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        return new RunResult(completed, e, statement, i, elapsed, joined,
                                texts, bound);
                    }
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                return new RunResult(completed, null, null, -1, elapsed, joined, texts, bound);
            }
        };
        task.setOnSucceeded(event -> {
            if (request != requestSequence) {
                return;
            }
            running = false;
            updateButtons();
            if (cancelled) {
                cancelled = false;
                status.setText(I18n.t("query.cancelled"));
                feedback.status(I18n.t("query.cancelledFeedback"));
                return;
            }
            apply(task.getValue(), explainPlan);
        });
        task.setOnFailed(event -> {
            if (request != requestSequence) {
                return;
            }
            running = false;
            updateButtons();
            Throwable error = task.getException();
            String message = readable(error);
            status.setText(I18n.t("query.runFailed", message));
            feedback.status(I18n.t("query.queryFailed", message));
            feedback.duration(-1);
            showErrorTab(I18n.t("query.runFailedTitle"), error, null, -1);
        });
        task.setOnCancelled(event -> {
            if (request == requestSequence) {
                running = false;
                updateButtons();
                status.setText(I18n.t("query.cancelled"));
                feedback.status(I18n.t("query.cancelledFeedback"));
            }
        });
        inFlight = submit(task);
    }

    private Future<?> submit(Task<?> task) {
        if (dbExecutor instanceof ExecutorService service) {
            return service.submit(task);
        }
        dbExecutor.execute(task);
        return null;
    }

    private void apply(RunResult result, boolean explainPlan) {
        for (int i = 0; i < result.results().size(); i++) {
            ExecutionResult execution = result.results().get(i);
            String sql = result.texts().get(i);
            List<Object> bound = result.bound().get(i);
            results.getTabs().add(execution.hasRows()
                    ? resultTab(execution, i + 1, sql, bound)
                    : messageTab(execution, i + 1));
        }
        if (result.error() != null) {
            showErrorTab(I18n.t("query.statementFailed", result.failedIndex() + 1),
                    result.error(), result.failed(),
                    result.failedIndex() + 1);
            status.setText(I18n.t("query.failedAfter", result.results().size()));
            range.setText("—");
            duration.setText(result.durationMs() + " ms");
            feedback.status(I18n.t("query.queryFailed", readable(result.error())));
            feedback.range("—");
            feedback.duration(result.durationMs());
            // ⭐ UX-5-D：失败时更要给出语句（拿去改正是用户下一步要做的事），
            //    但**不推进刷新时刻** —— 这一轮没有取到任何新数据。
            feedback.sql(result.executedText());
        } else {
            long rows = result.results().stream().mapToLong(ExecutionResult::rowCount).sum();
            range.setText(I18n.t("status.rows", rows));
            duration.setText(result.durationMs() + " ms");
            // 抬头当参数递进去而不是拼两遍模板：门禁要断言「状态说完成了」时
            // 只需比 `I18n.t("query.done")`，不必知道后面跟了什么、也不必跟着语言改。
            status.setText(I18n.t("query.doneStatus",
                    I18n.t(explainPlan ? "query.explain" : "query.done"),
                    result.results().size(), result.durationMs()));
            // U8（p3-query 实拍第二拍）：行数与耗时各有自己的段，
            // 「执行完成 · N 条语句」把它们再念一遍就是消息段该沉默的时刻。
            feedback.status("");
            feedback.range(I18n.t("status.rows", rows));
            feedback.duration(result.durationMs());
            // ⭐ UX-5-D：状态栏给出「跑的是哪条、这屏结果是什么时候落地的」。
            feedback.sql(result.executedText());
            feedback.refreshedAt(System.currentTimeMillis());
            if (!explainPlan) {
                history.add(result.executedText(), connectionName);
                refreshHistory();
            }
        }
        if (!results.getTabs().isEmpty()) {
            if (result.error() != null) {
                results.getSelectionModel().selectLast();
            } else {
                results.getSelectionModel().selectFirst();
            }
            // TabPane 打开新页签时自带动画；在已显示的外壳里，动画结束后
            // header 不一定再收到一次布局，标签会被裁成一条竖线。补一次收尾布局。
            results.applyCss();
            results.layout();
            PauseTransition settle = new PauseTransition(Duration.millis(350));
            settle.setOnFinished(event -> {
                results.applyCss();
                results.layout();
            });
            settle.play();
        }
        feedback.dirty(dirty ? 1 : 0);
    }

    /**
     * ⭐ Q4：结果列宽的持久化。
     *
     * <p>键带连接名，避免两个连接的结果表互相覆盖。
     */
    private double savedColumnWidth(String key, double fallback) {
        if (settings == null || connectionName == null) {
            return fallback;
        }
        return settings.getInt("ui.query.colwidth." + connectionName + "." + key,
                (int) fallback);
    }

    private void saveColumnWidth(String key, double width) {
        if (settings == null || connectionName == null || width <= 0) {
            return;
        }
        settings.setInt("ui.query.colwidth." + connectionName + "." + key, (int) width);
    }

    private String explainSql(String sql) {
        // ⭐ 执行计划语句由方言给 —— 以前这里是 switch，加一种库要改这里。
        return session.dialect().explainSql(sql);
    }

    // ─────────────────────────── 结果页 ───────────────────────────

    /**
     * 密度档位。
     *
     * <p>⭐ 结果网格以前**完全不看密度** —— 用户在数据页调到「紧凑」，
     * 切到查询结果页行高没变，密度设置看起来时灵时不灵。
     */
    public void setDensity(String density) {
        // ⭐ D1（2026-10-26）：结果网格与数据页网格同一档口径（比档位低一档）。
        rowHeight = RowHeights.grid(density);
        resultTables.forEach(table -> table.setFixedCellSize(rowHeight));
    }

    /** 结果网格当前的行高（供门禁断言密度作用到了它）。 */
    int resultRowHeight() {
        return rowHeight;
    }

    private Tab resultTab(ExecutionResult result, int index, String sql, List<Object> params) {
        TableView<java.util.Map<String, Object>> table = new TableView<>();
        resultTables.add(table);
        table.setFixedCellSize(rowHeight);
        table.setId("query-result-table");
        table.getStyleClass().addAll("adb-grid", "adb-query__result-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // ⭐ E5：0 行结果以前只有一行字。空态要说清「这是正常结果」+ 下一步怎么办。
        table.setPlaceholder(EmptyState.of(Icons.Icon.TABLE, I18n.t("query.emptyTitle"),
                I18n.t("query.emptyBody")));
        // ⭐ U25：结果网格的右键菜单（复制单元格 / 行 / 列名 / 全部）——
        // 分页与非分页两条路径共用同一张表，挂一次就都覆盖。
        attachResultContextMenu(table);

        // ⭐ Q5：绝对行号 = 当前页偏移 + 行内序号 + 1（非分页时偏移恒为 0）。
        LongProperty rowOffset = new SimpleLongProperty(0);

        TableColumn<java.util.Map<String, Object>, Void> number = new TableColumn<>("#");
        number.setSortable(false);
        number.setPrefWidth(52);
        number.setMinWidth(52);
        number.setMaxWidth(52);
        number.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : Long.toString(rowOffset.get() + getIndex() + 1));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        table.getColumns().add(number);

        List<String> keys = result.columnKeys();
        for (int i = 0; i < result.columns().size(); i++) {
            ColumnDef definition = result.columns().get(i);
            String key = keys.get(i);
            TableColumn<java.util.Map<String, Object>, Object> column = new TableColumn<>();
            column.setText(definition.name());
            column.setUserData(key);
            // ⭐ Q3：结果集可以排序（以前写死 setSortable(false) —— 点表头没反应）。
            column.setSortable(true);
            column.setPrefWidth(140);
            column.setCellValueFactory(data ->
                    new ReadOnlyObjectWrapper<>(data.getValue().get(key)));
            column.setCellFactory(cell -> new ResultCell(definition));
            // ⭐ Q4：列宽持久化（以前每次都固定 140px，改完就没了）。
            column.setPrefWidth(savedColumnWidth(key, 140));
            column.widthProperty().addListener((obs, old, width) ->
                    saveColumnWidth(key, width.doubleValue()));
            table.getColumns().add(column);
        }

        Tab tab = new Tab(I18n.t("query.resultTab", index));
        tab.setClosable(false);

        // ⭐ Q5：可分页的 SELECT 走服务端分页；其余保留「整段取回 + 截断提示」。
        if (QueryPaginator.isPaginable(sql) && result.hasRows()) {
            PagedLoader loader = new PagedLoader(tab, index, table, rowOffset,
                    sql, params, result.columns());
            pagers.add(loader);
            VBox dataPane = new VBox(0, loader.node(), table);
            VBox.setVgrow(table, Priority.ALWAYS);
            tab.setContent(resultViews(dataPane, result.columns(), sql, null, null));
            loader.start();
            return tab;
        }

        table.setItems(FXCollections.observableArrayList(result.rows()));
        Label meta = new Label(I18n.t("query.rowsAndDuration", result.rowCount(), result.durationMs()));
        meta.getStyleClass().add("adb-query__result-meta");

        Button copy = new Button(I18n.t("query.copyTsv"));
        copy.getStyleClass().add("adb-button");
        copy.setOnAction(event -> copyResultTsv(result));

        Button export = new Button(I18n.t("query.exportCsv"));
        export.getStyleClass().add("adb-button");
        export.setOnAction(event -> exportCsv(result));

        Label warning = null;
        if (result.isTruncated()) {
            // ⭐ 静默截断比不分页更糟：用户会以为看到的就是全部。
            meta.setText(meta.getText() + I18n.t("query.truncatedSuffix"));
            // U10：警示标记是**矢量图标**，不是文本「⚠」——
            // 文本符号不吃主题色、不吃字号，暗色下几乎看不见。
            warning = new Label(I18n.t("query.truncated", io.agenticdb.model.QueryLimits.MAX_PAGE_SIZE));
            warning.setGraphic(Icons.of(Icons.Icon.ALERT, Icons.SIZE_BADGE));
            warning.setId("query-result-truncated");
            warning.getStyleClass().add("adb-query__result-truncated");
        }

        // ⭐ N23：Data / Info 第二段 32px —— Data 是网格，Info 是列类型与语句；
        //    Copy / Export 跟着这一段走，不再单独占一条工具条（结果区从三段收成两段）。
        VBox dataPane = new VBox(0, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        tab.setContent(resultViews(dataPane, result.columns(), sql, meta, warning, copy, export));
        return tab;
    }

    /**
     * ⭐ N23：结果区的「Data / Info」32px 切换带 + 内容栈。
     *
     * <p>Navicat 17 的结果区是「27px 页签 → 32px Data/Info → 网格」。
     * 页签高度由 CSS 钉在 27px；这一条只负责第二段与两页内容。
     * Info 页承接以前独占一条的类型 chips 与语句预览 —— 它们是「关于这份结果」
     * 的信息，不该常驻在数据上方。
     */
    private VBox resultViews(javafx.scene.Node dataPane, List<ColumnDef> columns, String sql,
                             Label meta, Label warning, Button... actions) {
        javafx.scene.control.ToggleButton data = new javafx.scene.control.ToggleButton(
                I18n.t("query.result.data"));
        data.setId("result-view-data");
        data.getStyleClass().add("adb-query__result-view");
        javafx.scene.control.ToggleButton info = new javafx.scene.control.ToggleButton(
                I18n.t("query.result.info"));
        info.setId("result-view-info");
        info.getStyleClass().add("adb-query__result-view");
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        data.setToggleGroup(group);
        info.setToggleGroup(group);
        data.setSelected(true);

        VBox infoPane = new VBox(8);
        infoPane.setId("query-result-info-pane");
        infoPane.getStyleClass().add("adb-query__result-info");
        infoPane.getChildren().add(typeStrip(columns));
        if (meta != null) {
            infoPane.getChildren().add(meta);
        }
        Label statement = new Label(sql);
        statement.setId("query-result-sql");
        statement.setWrapText(true);
        statement.getStyleClass().add("adb-query__info-sql");
        infoPane.getChildren().add(statement);
        infoPane.setVisible(false);
        infoPane.setManaged(false);

        StackPane views = new StackPane(dataPane, infoPane);
        VBox.setVgrow(views, Priority.ALWAYS);

        data.setOnAction(event -> {
            if (group.getSelectedToggle() == null) {
                data.setSelected(true);
            }
            showPane(infoPane, false);
        });
        info.setOnAction(event -> {
            if (group.getSelectedToggle() == null) {
                info.setSelected(true);
            }
            showPane(infoPane, true);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox band = new HBox(4, data, info);
        if (warning != null) {
            band.getChildren().add(warning);
        }
        band.getChildren().add(spacer);
        band.getChildren().addAll(actions);
        band.getStyleClass().add("adb-query__result-views");
        band.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(band, views);
        box.getStyleClass().add("adb-query__result-box");
        return box;
    }

    private static void showPane(javafx.scene.Node pane, boolean visible) {
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    /**
     * ⭐ U25：结果网格的右键菜单 —— DBA 的高频动作是「抄一个值 / 抄一行 / 抄列名」，
     * 以前这三件事都要进单元格全选 Ctrl+C，或者回头改 SQL。列头右键同样给
     * 「复制列名」（列头是皮肤里的 TableCell，只是 getTableRow() 为空，
     * 于是行相关项自然落到禁用态，不需要两套菜单）。
     *
     * <p>「全部」取的是 {@code table.getItems()} 而不是首页的 {@code result} ——
     * 分页表翻到第 3 页时复制的必须是第 3 页，静默给第 1 页比不给更糟。
     */
    private void attachResultContextMenu(
            TableView<java.util.Map<String, Object>> table) {
        table.setOnContextMenuRequested(event -> {
            ContextMenu menu = new ContextMenu();
            MenuItem copyCell = new MenuItem(I18n.t("query.ctx.copyCell"));
            MenuItem copyRow = new MenuItem(I18n.t("query.ctx.copyRow"));
            MenuItem copyName = new MenuItem(I18n.t("grid.head.copyName"));
            MenuItem copyAll = new MenuItem(I18n.t("query.ctx.copyAll"));
            copyCell.setId("result-copy-cell");
            copyRow.setId("result-copy-row");
            copyName.setId("result-copy-column-name");
            copyAll.setId("result-copy-all");
            menu.getItems().addAll(copyCell, copyRow, copyName,
                    new SeparatorMenuItem(), copyAll);

            TableCell<?, ?> cell = resultCellAt(event.getTarget());
            TableColumn<?, ?> column = cell == null ? null : cell.getTableColumn();
            Object key = column == null ? null : column.getUserData();
            java.util.Map<String, Object> row = cell != null && cell.getTableRow() != null
                    && cell.getIndex() >= 0 && cell.getIndex() < table.getItems().size()
                    ? table.getItems().get(cell.getIndex()) : null;
            String columnName = column == null ? "" : String.valueOf(column.getText());

            copyCell.setDisable(row == null || key == null);
            copyRow.setDisable(row == null);
            copyName.setDisable(key == null);
            copyAll.setDisable(table.getItems().isEmpty());

            copyCell.setOnAction(action -> {
                Object value = row.get(key);
                putOnClipboard(value == null ? "" : String.valueOf(value));
                status.setText(I18n.t("query.copiedCell", columnName));
            });
            copyRow.setOnAction(action -> {
                StringBuilder line = new StringBuilder();
                for (String columnKey : dataColumnKeys(table)) {
                    if (line.length() > 0) {
                        line.append('\t');
                    }
                    Object value = row.get(columnKey);
                    line.append(value == null ? "" : String.valueOf(value));
                }
                putOnClipboard(line.toString());
                status.setText(I18n.t("query.copiedRow", cell.getIndex() + 1));
            });
            copyName.setOnAction(action -> {
                putOnClipboard(columnName);
                status.setText(I18n.t("query.copiedColumnName", columnName));
            });
            copyAll.setOnAction(action -> {
                StringBuilder text = new StringBuilder();
                List<String> keys = dataColumnKeys(table);
                text.append(String.join("\t", keys)).append('\n');
                for (java.util.Map<String, Object> item : table.getItems()) {
                    for (int i = 0; i < keys.size(); i++) {
                        if (i > 0) {
                            text.append('\t');
                        }
                        Object value = item.get(keys.get(i));
                        text.append(value == null ? "" : String.valueOf(value));
                    }
                    text.append('\n');
                }
                putOnClipboard(text.toString());
                status.setText(I18n.t("query.copiedRowsTsv", table.getItems().size()));
                feedback.status(I18n.t("query.copiedResultsTsv"));
            });

            if (row != null) {
                // 右键先把选择挪到被点的行 —— 菜单说的是「这一行」，
                // 高亮却停在别处就是两张嘴（Navicat 也是这个行为）。
                table.getSelectionModel().clearSelection();
                table.getSelectionModel().select(cell.getIndex());
            }
            menu.show(table, event.getScreenX(), event.getScreenY());
            event.consume();
        });
    }

    /** 事件目标往上找最近的 TableCell（数据格与列头都是 TableCell）。 */
    private static TableCell<?, ?> resultCellAt(Object target) {
        if (!(target instanceof javafx.scene.Node node)) {
            return null;
        }
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n instanceof TableCell<?, ?> cell) {
                return cell;
            }
        }
        return null;
    }

    /**
     * ⭐ U25 验收帧的摆位：给第一个数据格投递**真的** CONTEXT_MENU_REQUESTED ——
     * 拍到的是同一处理器开出来的真菜单，而不是手搓的「菜单的样子」。
     *
     * <p>两段式，因为实测撞到两件不同的事：
     * ① 结果是异步灌的，要轮询等行就绪（预算 200×100ms；旧的 60 次会在冷启动里放弃）；
     * ② 菜单**打开之后约 8.5s 会自己收起** —— 实测等待 1500/3000/5000/7000/8000ms 都拍得到，
     *   9000ms 就只剩主窗口，而本帧的固定等待是 8000ms，正好压在边界上（同一份代码两次跑，
     *   一次成一次败）。收起的触发者没查到：主源码里没有 5–10s 的 FX 计时器，
     *   最可能是 JavaFX 在窗口失焦时收掉 PopupWindow。
     *   所以这里**持续续摆**：每 1500ms 看一眼，菜单不在了就重新摆一次，
     *   于是快门按下时菜单的年龄最多 1.5s，不再和那 8.5s 赛跑。
     *   只在「没有浮窗」时续摆，避免叠出两层菜单。
     */
    void poseContextMenuWhenReady() {
        awaitResultCell(200);
    }

    private void awaitResultCell(int attemptsLeft) {
        javafx.scene.Node cell = firstResultDataCell();
        if (cell == null) {
            if (attemptsLeft <= 0) {
                System.out.println("[screenshot] menu pose gave up: no result cell");
                return;
            }
            schedule(100, () -> awaitResultCell(attemptsLeft - 1));
            return;
        }
        poseOn(cell);
        keepMenuUp(20);
    }

    /** 菜单收起就重新摆一次；进程在快门按下时退出，所以这里只需要覆盖截图窗口。 */
    private void keepMenuUp(int ticksLeft) {
        if (ticksLeft <= 0) {
            return;
        }
        schedule(1_500, () -> {
            if (!menuUp()) {
                javafx.scene.Node cell = firstResultDataCell();
                if (cell != null) {
                    poseOn(cell);
                }
            }
            keepMenuUp(ticksLeft - 1);
        });
    }

    /** ContextMenu 的浮窗不是 Stage（是 PopupWindow），主窗口的快照拍不到它。 */
    private static boolean menuUp() {
        return javafx.stage.Window.getWindows().stream()
                .anyMatch(w -> !(w instanceof javafx.stage.Stage)
                        && w.isShowing() && w.getScene() != null);
    }

    /** 日志必须 ASCII：主源码里的中文字面量会顶穿 I18nAuditTest 的棘轮。 */
    private void poseOn(javafx.scene.Node cell) {
        double x = cell.getBoundsInLocal().getWidth() / 2;
        double y = cell.getBoundsInLocal().getHeight() / 2;
        javafx.geometry.Point2D screen = cell.localToScreen(x, y);
        if (screen == null) {
            System.out.println("[screenshot] menu pose skipped: cell has no screen coords");
            return;
        }
        cell.fireEvent(new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                x, y, screen.getX(), screen.getY(), false, null));
        System.out.println("[screenshot] menu posed, windows="
                + javafx.stage.Window.getWindows().size());
    }

    private void schedule(long millis, Runnable action) {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(millis));
        pause.setOnFinished(event -> action.run());
        pause.play();
    }

    private javafx.scene.Node firstResultDataCell() {
        javafx.scene.Node found = lookup("#query-result-table");
        if (!(found instanceof TableView<?> table) || table.getItems().isEmpty()) {
            return null;
        }
        return table.lookupAll(".table-cell").stream()
                .filter(TableCell.class::isInstance)
                .map(node -> (TableCell<?, ?>) node)
                .filter(cell -> cell.getTableRow() != null && cell.getIndex() >= 0
                        && cell.getTableColumn() != null
                        && cell.getTableColumn().getUserData() instanceof String)
                .findFirst()
                .orElse(null);
    }

    /** 数据列的键（行号列 userData 为空，不参与复制），顺序 = 显示顺序。 */
    private static List<String> dataColumnKeys(
            TableView<java.util.Map<String, Object>> table) {
        List<String> keys = new ArrayList<>();
        for (TableColumn<java.util.Map<String, Object>, ?> column : table.getColumns()) {
            if (column.getUserData() instanceof String key) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static void putOnClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * ⭐ V-9：列类型条（与数据页「类型行」同一套规格）。
     *
     * <p>JavaFX 的 {@code TableColumn} 不是 {@code Control}，没有 {@code setTooltip}，
     * 所以类型信息不塞进列头悬停，而是单独一行展示，用户一眼能看到这张结果有哪些列、各是什么类型。
     */
    private Label typeStrip(List<ColumnDef> columns) {
        String text = columns.stream()
                .map(column -> column.name() + " " + column.displayType())
                .collect(java.util.stream.Collectors.joining("   ·   "));
        Label strip = new Label(text);
        strip.setId("query-result-types");
        strip.getStyleClass().add("adb-query__result-types");
        strip.setTooltip(new Tooltip(text));
        return strip;
    }

    /**
     * ⭐ Q5：单个可分页结果集的服务端分页器。
     *
     * <p>与数据页共用一套 LIMIT/OFFSET 模型（{@link QueryPaginator} 负责把
     * 任意 SELECT 改写成分页 SQL）。每翻页只取一页 + 一次 COUNT，
     * 不再把整张结果集塞进 {@code ObservableList}。
     */
    private final class PagedLoader {
        private final Tab tab;
        private final int index;
        private final TableView<java.util.Map<String, Object>> table;
        private final LongProperty rowOffset;
        private final String sql;
        private final List<Object> params;
        private final List<ColumnDef> columns;

        private int pageSize = QueryLimits.DEFAULT_PAGE_SIZE;
        private long offset;
        private long total = -1;
        private boolean loading;
        private RowPage lastPage;

        private final HBox bar = new HBox(6);
        private final Label rangeLabel = new Label();
        private final Label pageLabel = new Label();
        private final ComboBox<Integer> pageSizeBox = new ComboBox<>();
        // ⭐ 与数据页 `#data-*` 的翻页键同一形态：**纯图标 + tooltip/accessibleText**。
        //    以前这里是 `IconButtons.of`（图标 + 文字），中文「第一页」三个字装得下，
        //    换成英文 "Previous Page" 就在一排里挤成 "Previous Pa…"（第六批重拍帧目视抓到）。
        //    Navicat 的纪录导航也是纯图标，所以这不是"挤不下才退让"，是本来就该长这样。
        private final Button first = IconButtons.icon(Icons.Icon.FIRST, I18n.t("grid.pager.first"));
        private final Button previous = IconButtons.icon(Icons.Icon.PREVIOUS, I18n.t("grid.toolbar.previousPage"));
        private final Button next = IconButtons.icon(Icons.Icon.NEXT, I18n.t("grid.toolbar.nextPage"));
        private final Button last = IconButtons.icon(Icons.Icon.LAST, I18n.t("grid.pager.last"));
        private final TextField jumpField = new TextField();
        // §2.6：以前输入框旁边还挂一颗「跳转」按钮 —— 输入框本身已支持回车跳转，
        //      按钮只会让这一对读起来像「一个禁用 + 一个可点」的两件事（实拍确认）。
        private final Button copy = new Button(I18n.t("query.copyPage"));
        /** ⭐ P6-2：导出范围 —— 本页 / 选中行 / 全部结果。 */
        private final MenuButton export = new MenuButton(I18n.t("grid.toolbar.export"));
        private final MenuItem exportPage = new MenuItem(I18n.t("query.exportPage"));
        private final MenuItem exportSelected = new MenuItem(I18n.t("query.exportSelected"));
        private final MenuItem exportAll = new MenuItem(I18n.t("query.exportAll"));
        private final Region spacer = new Region();

        PagedLoader(Tab tab, int index, TableView<java.util.Map<String, Object>> table,
                    LongProperty rowOffset, String sql, List<Object> params,
                    List<ColumnDef> columns) {
            this.tab = tab;
            this.index = index;
            this.table = table;
            this.rowOffset = rowOffset;
            this.sql = sql;
            this.params = params == null ? List.of() : List.copyOf(params);
            this.columns = columns;
            buildBar();
        }

        /** 分页条节点（V-9：复用数据页的 {@code adb-record-nav} / {@code adb-page-size} 规格）。 */
        HBox node() {
            return bar;
        }

        void start() {
            load();
        }

        private void buildBar() {
            bar.setId("query-result-pager");
            bar.getStyleClass().addAll("adb-query__result-pager", "adb-record-nav");
            bar.setAlignment(Pos.CENTER_LEFT);

            rangeLabel.getStyleClass().add("adb-muted");
            rangeLabel.setId("query-result-range");
            rangeLabel.setMinWidth(Region.USE_PREF_SIZE);
            pageLabel.getStyleClass().add("adb-muted");
            pageLabel.setId("query-result-page-label");
            pageLabel.setMinWidth(Region.USE_PREF_SIZE);

            HBox recordNav = new HBox(4, first, previous, next, last, pageLabel);
            recordNav.getStyleClass().add("adb-record-nav");
            recordNav.setAlignment(Pos.CENTER_LEFT);
            for (Button button : new Button[]{first, previous, next, last}) {
                button.getStyleClass().add("adb-icon-button");
            }
            first.setId("query-result-first");
            previous.setId("query-result-prev");
            next.setId("query-result-next");
            last.setId("query-result-last");
            first.setOnAction(event -> goTo(0));
            previous.setOnAction(event -> goTo(offset - pageSize));
            next.setOnAction(event -> goTo(offset + pageSize));
            last.setOnAction(event ->
                    goTo(total > 0 ? (total - 1) / pageSize * pageSize : 0));

            pageSizeBox.setId("query-result-page-size");
            pageSizeBox.getStyleClass().add("adb-page-size");
            pageSizeBox.getItems().setAll(Arrays.stream(PageSize.values())
                    .map(PageSize::value).toList());
            // ⚠️ 必须装箱：`select(int)` 是**按下标**选（items 只有 0–3 四项），
            //    传原生 int 会静默选中失败，「每页」框渲染成空白 —— 实测截图就是空的。
            //    `select(Integer)` 才是「按值选」这条重载。
            pageSizeBox.getSelectionModel()
                    .select(Integer.valueOf(PageSize.nearest(pageSize).value()));
            // ⚠️ 宽度要够渲染最宽的档位「1000」—— 实测截图里 78px 会把「200」截成「2…」
            //    （按钮单元格吃掉宽度，框右侧还剩 23px 空白却已经省略号了）。
            pageSizeBox.setPrefWidth(96);
            pageSizeBox.setOnAction(event -> {
                Integer value = pageSizeBox.getValue();
                if (value == null || value == pageSize) {
                    return;
                }
                pageSize = value;
                offset = 0;
                load();
            });

            Label perPage = new Label(I18n.t("grid.toolbar.perPage"));
            perPage.getStyleClass().add("adb-muted");
            // ⭐ 文字项一律钉住下限：HBox 一挤就把它们压成 "Per p…" / "Copy This Pa…" / "Exp…"
            //    （第六批重拍帧目视抓到）。**压不了**才是对的 —— 放不下时该整项收起，
            //    而不是把一句话切成半截让人猜。与数据网格分页器同一条规矩（第四批定的）。
            perPage.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            jumpField.setId("query-result-jump");
            // ⭐ 第十八轮：与数据页 `#data-jump` 同一规格 —— 以前这里只有 id + 宽度，
            //    没有占位文字、没有 tooltip、也没有 accessibleText：截图上就是**一个空白框**，
            //    读屏只念得出「编辑框」。同一种控件在两页必须长得一样、能读的一样。
            jumpField.getStyleClass().add("adb-grid__jump");
            jumpField.setPromptText(I18n.t("grid.pager.jumpPagePrompt"));
            jumpField.setTooltip(new javafx.scene.control.Tooltip(I18n.t("grid.pager.jumpPageTip")));
            jumpField.setAccessibleText(I18n.t("grid.pager.jumpPageAria"));
            jumpField.setPrefWidth(64);
            jumpField.setMinWidth(56);
            // 宽度跟着提示文字量（与数据页同一规则）；要等挂上场景才量得准。
            bar.sceneProperty().addListener((obs, was, now) -> {
                if (now != null) {
                    FieldFits.prompt(jumpField);
                }
            });
            jumpField.setOnAction(event -> jumpToPage(jumpField.getText()));

            copy.getStyleClass().add("adb-button");
            copy.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            copy.setOnAction(event -> copyResultTsv(currentResult()));
            export.setId("query-export");
            export.getStyleClass().add("adb-button");
            export.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            export.setTooltip(new Tooltip(I18n.t("query.exportScopeHint")));
            exportPage.setId("query-export-page");
            exportPage.setOnAction(event -> chooseAndExport(this::exportCurrentTo,
                    "query-result-page.csv"));
            exportSelected.setId("query-export-selected");
            exportSelected.setOnAction(event -> chooseAndExport(this::exportSelectedTo,
                    "query-result-selected.csv"));
            exportAll.setId("query-export-all");
            exportAll.setOnAction(event -> chooseAndExport(this::exportAllTo,
                    "query-result-all.csv"));
            export.getItems().addAll(exportPage, exportSelected,
                    new SeparatorMenuItem(), exportAll);
            // 选中行导出只在真的有选区时可用 —— 否则导出的是一张空表。
            exportSelected.disableProperty().bind(
                    Bindings.isEmpty(table.getSelectionModel().getSelectedItems()));

            HBox.setHgrow(spacer, Priority.ALWAYS);
            bar.getChildren().setAll(rangeLabel, recordNav, spacer, perPage,
                    pageSizeBox, jumpField, copy, export);
        }

        private void load() {
            if (loading) {
                return;
            }
            loading = true;
            long request = requestSequence;
            updateButtons();
            Task<PagedData> task = new Task<>() {
                @Override
                protected PagedData call() throws Exception {
                    var pageQ = QueryPaginator.pageSql(
                            session.dialect(), sql, params, pageSize, offset);
                    RowPage page = session.query(pageQ.sql(), pageSize, pageQ.params());
                    var countQ = QueryPaginator.countSql(session.dialect(), sql, params);
                    RowPage count = session.query(countQ.sql(), 1, countQ.params());
                    return new PagedData(page, extractTotal(count));
                }
            };
            task.setOnSucceeded(event -> {
                if (request != requestSequence) {
                    return;
                }
                PagedData data = task.getValue();
                lastPage = data.page();
                total = data.total();
                rowOffset.set(offset);
                table.setItems(FXCollections.observableArrayList(data.page().rows()));
                loading = false;
                updatePager();
            });
            task.setOnFailed(event -> {
                if (request != requestSequence) {
                    return;
                }
                loading = false;
                String message = I18n.t("query.pagedFailed", readable(task.getException()));
                status.setText(message);
                feedback.status(message);
                updateButtons();
            });
            submit(task);
        }

        private void updateButtons() {
            previous.setDisable(loading || offset == 0);
            first.setDisable(loading || offset == 0);
            boolean atEnd = total >= 0 && offset + pageSize >= total;
            next.setDisable(loading || atEnd
                    || QueryLimits.exceedsOffsetLimit(offset + pageSize));
            long lastOffset = total > 0 ? (total - 1) / pageSize * pageSize : 0;
            last.setDisable(loading || total < 0 || lastOffset == offset
                    || QueryLimits.exceedsOffsetLimit(lastOffset));
            pageSizeBox.setDisable(loading);
            jumpField.setDisable(loading);
        }

        private void updatePager() {
            long pages = QueryLimits.totalPages(total, pageSize);
            long current = QueryLimits.pageNumberOf(offset, pageSize);
            pageLabel.setText(pages < 0 ? I18n.t("query.pageOf", current)
                    : I18n.t("query.pageOfTotal", current, pages));
            rangeLabel.setText(total < 0 ? I18n.t("query.totalUnknown") : I18n.t("status.rows", total));
            tab.setText(I18n.t("query.tabWithRows", I18n.t("query.resultTab", index),
                    total < 0 ? "?" : total));
            updateButtons();
        }

        private void goTo(long target) {
            if (loading) {
                return;
            }
            if (target < 0) {
                target = 0;
            }
            if (QueryLimits.exceedsOffsetLimit(target)) {
                String message = I18n.t("query.offsetCap", QueryLimits.MAX_OFFSET);
                status.setText(message);
                feedback.status(message);
                return;
            }
            if (target == offset) {
                return;
            }
            offset = target;
            load();
        }

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
                feedback.status(I18n.t("query.jumpNotNumber", text));
                return;
            }
            if (page < 1) {
                feedback.status(I18n.t("grid.pager.jumpPageZero"));
                return;
            }
            long pages = QueryLimits.totalPages(total, pageSize);
            if (pages >= 0 && page > pages) {
                feedback.status(I18n.t("query.jumpTooBig", pages));
                return;
            }
            long target = (page - 1) * pageSize;
            if (QueryLimits.exceedsOffsetLimit(target)) {
                String message = I18n.t("query.offsetCap", QueryLimits.MAX_OFFSET);
                status.setText(message);
                feedback.status(message);
                return;
            }
            if (target == offset) {
                jumpField.clear();
                return;
            }
            offset = target;
            jumpField.clear();
            load();
        }

        private ExecutionResult currentResult() {
            RowPage page = lastPage;
            if (page == null) {
                return new ExecutionResult(sql, columns, List.of(), 0, 0);
            }
            return new ExecutionResult(sql, page.columns(), page.rows(), 0, 0);
        }

        // ─────────────────────── P6-2：按范围导出 ───────────────────────

        @FunctionalInterface
        private interface WriterAction {
            void write(Writer out) throws Exception;
        }

        /** 选文件 → 后台写出；失败落状态行，不影响结果集。 */
        private void chooseAndExport(WriterAction action, String initialName) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("query.exportCsvTitle"));
            chooser.setInitialFileName(initialName);
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("CSV", "*.csv"));
            Window window = getScene() == null ? null : getScene().getWindow();
            File file = chooser.showSaveDialog(window);
            if (file == null) {
                return;
            }
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    try (Writer out = Files.newBufferedWriter(file.toPath(),
                            StandardCharsets.UTF_8)) {
                        action.write(out);
                    }
                    return null;
                }
            };
            task.setOnSucceeded(event -> {
                status.setText(I18n.t("query.exported", file.getName()));
                feedback.status(I18n.t("query.exportedFeedback"));
            });
            task.setOnFailed(event -> {
                String message = I18n.t("query.exportFailed", readable(task.getException()));
                status.setText(message);
                feedback.status(message);
            });
            submit(task);
        }

        /** 当前页（屏幕上这几行）。 */
        void exportCurrentTo(Writer out) throws IOException {
            writeCsv(out, List.copyOf(table.getItems()));
        }

        /** 当前选中的行（可能跨多页选不了，选多少导多少）。 */
        void exportSelectedTo(Writer out) throws IOException {
            writeCsv(out, List.copyOf(table.getSelectionModel().getSelectedItems()));
        }

        /**
         * ⭐ 全部结果：重跑原始 SQL 流式写文件，不受 UI 单页上限限制。
         *
         * <p>这是「导出本页」与「导出全部结果」的本质区别 ——
         * 250 行的结果翻到第 1 页时，本页只有 200 行，全部仍是 250 行。
         */
        long exportAllTo(Writer out) throws Exception {
            out.write('\uFEFF');
            return TableExporter.exportSql(session, sql, params,
                    ExportOptions.of(ExportFormat.CSV), out);
        }

        private void writeCsv(Writer out, List<java.util.Map<String, Object>> rows)
                throws IOException {
            List<String> headers = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (TableColumn<java.util.Map<String, Object>, ?> column : table.getColumns()) {
                if (column.getUserData() instanceof String key) {
                    headers.add(column.getText());
                    keys.add(key);
                }
            }
            List<List<String>> texts = new ArrayList<>();
            for (java.util.Map<String, Object> row : rows) {
                List<String> line = new ArrayList<>(keys.size());
                for (String key : keys) {
                    Object value = row.get(key);
                    line.add(value == null ? "" : String.valueOf(value));
                }
                texts.add(line);
            }
            out.write('\uFEFF');
            out.write(CsvExporter.csv(headers, texts));
        }
    }

    private record PagedData(RowPage page, long total) {
    }

    private static long extractTotal(RowPage count) {
        if (count.rows().isEmpty()) {
            return 0;
        }
        Object value = count.rows().get(0).get("adb_count");
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Tab messageTab(ExecutionResult result, int index) {
        TextArea text = new TextArea("""
                语句 %d 执行完成。
                影响行数：%d
                耗时：%d ms

                %s
                """.formatted(index, result.updateCount(), result.durationMs(),
                result.statement()));
        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("adb-query__message");
        Tab tab = new Tab(I18n.t("query.messageTab", index), text);
        tab.setClosable(false);
        return tab;
    }

    /**
     * ⭐ P2-2：结果页里的错误页签 —— 快速视图 + 一个「查看详情」。
     *
     * <p>页签放「用户下一步要改什么」（语句 / 建议 / 位置），
     * 详情弹窗放完整证据（分类 / 驱动原文 / 异常类型 / 可复制的 SQL）。
     */
    private void showErrorTab(String title, Throwable error, SqlStatement statement,
                              int statementIndex) {
        String message = readable(error);
        StringBuilder body = new StringBuilder();
        if (statementIndex > 0) {
            body.append(I18n.t("query.errorStatement", statementIndex)).append("\n\n");
        }
        if (statement != null) {
            body.append(statement.text()).append("\n\n");
        }
        body.append(message);
        String advice = ErrorLocator.explainConstraintViolation(message);
        if (!advice.isBlank()) {
            body.append(I18n.t("query.errorAdvice", advice));
        }

        if (statement != null) {
            Optional<ErrorLocator.Position> position =
                    ErrorLocator.locate(message, statement.text());
            position.ifPresent(pos -> {
                body.append(I18n.t("query.errorPosition", pos.line(), pos.column()));
                int offset = statement.start()
                        + offsetOf(statement.text(), pos.line(), pos.column());
                editor.moveTo(offset);
            });
        }

        TextArea text = new TextArea(body.toString());
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("adb-query__message");

        Button detailsButton = new Button(I18n.t("query.details"));
        detailsButton.setId("query-error-details");
        ErrorDetails details = ErrorDetails.of(title, error,
                statement == null ? "" : statement.text());
        detailsButton.setOnAction(event -> ErrorDetailsDialog.show(
                getScene() == null ? null : getScene().getWindow(), details));

        VBox content = new VBox(8, text, detailsButton);
        VBox.setVgrow(text, Priority.ALWAYS);
        content.setAlignment(Pos.TOP_LEFT);
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        results.getTabs().add(tab);
        results.getSelectionModel().select(tab);
    }

    private static int offsetOf(String text, int line, int column) {
        int targetLine = Math.max(1, line);
        int index = 0;
        for (int current = 1; current < targetLine; current++) {
            int next = text.indexOf('\n', index);
            if (next < 0) {
                return text.length();
            }
            index = next + 1;
        }
        return Math.min(text.length(), index + Math.max(0, column - 1));
    }

    private void copyResultTsv(ExecutionResult result) {
        StringBuilder text = new StringBuilder();
        List<String> keys = result.columnKeys();
        List<String> headers = result.columns().stream().map(ColumnDef::name).toList();
        text.append(String.join("\t", headers)).append('\n');
        for (java.util.Map<String, Object> row : result.rows()) {
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    text.append('\t');
                }
                Object value = row.get(keys.get(i));
                text.append(value == null ? "" : String.valueOf(value));
            }
            text.append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.toString());
        Clipboard.getSystemClipboard().setContent(content);
        status.setText(I18n.t("query.copiedRowsTsv", result.rowCount()));
        feedback.status(I18n.t("query.copiedResultsTsv"));
    }

    private void exportCsv(ExecutionResult result) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("query.exportTitle"));
        chooser.setInitialFileName("query-result.csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV", "*.csv"));
        Window window = getScene() == null ? null : getScene().getWindow();
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        List<String> keys = result.columnKeys();
        List<String> headers = result.columns().stream().map(ColumnDef::name).toList();
        List<List<String>> rows = result.rows().stream()
                .map(row -> keys.stream()
                        .map(key -> row.get(key) == null ? "" : String.valueOf(row.get(key)))
                        .toList())
                .toList();
        try {
            Files.writeString(file.toPath(), "\uFEFF" + CsvExporter.csv(headers, rows),
                    StandardCharsets.UTF_8);
            status.setText(I18n.t("query.exported", file.getName()));
            feedback.status(I18n.t("query.exportedFeedback"));
        } catch (IOException e) {
            status.setText(I18n.t("query.exportFailed", readable(e)));
            feedback.status(I18n.t("query.exportFailed", readable(e)));
        }
    }

    // ─────────────────────────── 保存 / 关闭 ───────────────────────────

    public void save() {
        Window window = getScene() == null ? null : getScene().getWindow();
        if (currentFile == null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("action.query.save"));
            chooser.setInitialFileName("query.sql");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("SQL", "*.sql"));
            currentFile = chooser.showSaveDialog(window);
        }
        if (currentFile == null) {
            return;
        }
        try {
            Files.writeString(currentFile.toPath(), editor.text(), StandardCharsets.UTF_8);
            setDirty(false);
            status.setText(I18n.t("query.saved", currentFile.getName()));
            feedback.status(status.getText());
        } catch (IOException e) {
            status.setText(I18n.t("query.saveFailed", readable(e)));
            feedback.status(status.getText());
        }
    }

    public void setCurrentFile(File file) {
        currentFile = file;
    }

    public File currentFile() {
        return currentFile;
    }

    @Override
    public void close() {
        if (running) {
            cancelled = true;
            session.cancelCurrent();
        }
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
    }

    // ─────────────────────────── 历史 ───────────────────────────

    private void refreshHistory() {
        historyButton.getItems().clear();
        List<String> recent = history.recent();
        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem(I18n.t("query.noHistory"));
            empty.setDisable(true);
            historyButton.getItems().add(empty);
            return;
        }
        for (String sql : recent) {
            String label = sql.lines().findFirst().orElse(sql).strip();
            if (label.length() > 60) {
                label = label.substring(0, 59) + "…";
            }
            MenuItem item = new MenuItem(label);
            item.setOnAction(event -> {
                editor.setText(sql);
                editor.requestEditorFocus();
                status.setText(I18n.t("query.historyLoaded"));
            });
            historyButton.getItems().add(item);
        }
        MenuItem clear = new MenuItem(I18n.t("query.clearHistory"));
        clear.setOnAction(event -> {
            history.clear();
            refreshHistory();
            status.setText(I18n.t("query.historyCleared"));
        });
        historyButton.getItems().add(new SeparatorMenuItem());
        historyButton.getItems().add(clear);
    }

    /**
     * ⭐ N21：未建成的能力给「如实说明页」，不留死按钮。
     *
     * <p>Query Builder / Ask AI 在 Navicat 17 的查询页上各占一席 —— 这一版没有实现，
     * 点开必须说清楚「需要什么、什么都没改」，而不是静默无反应。
     */
    private void showCapabilityNotice(String title, String capabilityKey) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.getDialogPane().setId("capability-notice");
        alert.setTitle(I18n.t("grid.capability.windowTitle"));
        alert.setHeaderText(title + " — " + I18n.t("grid.capability.title"));
        alert.setContentText(I18n.t("grid.capability.body", I18n.t(capabilityKey)));
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        DialogStyles.apply(alert, getScene() == null ? null : getScene().getWindow());
        alert.showAndWait();
    }

    private boolean confirmDanger(Danger danger) {        ButtonType execute = new ButtonType(I18n.t("query.confirmRunButton", DangerLabels.t(danger)),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.t("query.confirmRunTitle"));
        alert.setHeaderText(I18n.t("query.riskLevel", DangerLabels.t(danger)));
        String preview = editor.text().strip();
        if (preview.length() > 1200) {
            preview = preview.substring(0, 1200) + "\n…";
        }
        alert.setContentText(preview);
        alert.getButtonTypes().setAll(execute, cancelType);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        DialogStyles.apply(alert, getScene() == null ? null : getScene().getWindow());
        return alert.showAndWait().orElse(cancelType) == execute;
    }

    private static String readable(Throwable error) {
        // ⭐ V-12：统一走错误归类 —— 给出「发生了什么 + 建议怎么办」，
        //    而不是把驱动的英文原文直接甩给用户。
        return io.agenticdb.safety.ErrorClassifier.classify(error).message();
    }

    private static final class ResultCell
            extends TableCell<java.util.Map<String, Object>, Object> {

        private static final List<String> STYLES = List.of(
                "adb-grid__cell--null", "adb-grid__cell--empty", "adb-grid__cell--number",
                "adb-grid__cell--boolean", "adb-grid__cell--binary", "adb-grid__cell--json",
                "adb-grid__cell--enum", "adb-grid__cell--date", "adb-grid__cell--long-text");

        private final ColumnDef definition;

        private ResultCell(ColumnDef definition) {
            this.definition = definition;
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STYLES);
            setTooltip(null);
            if (empty) {
                setText(null);
                return;
            }
            CellRenderer.Rendered rendered = CellRenderer.render(definition, item);
            // ⭐ U9：与数据网格同一套布尔语法 —— 只读态是图标，不是文本 ✓/✗。
            if (rendered.icon() != null) {
                setText(null);
                setGraphic(CellRenderer.checkGlyph(rendered.icon()));
            } else {
                setText(rendered.text());
                setGraphic(null);
            }
            if (!rendered.styleClass().isEmpty()) {
                getStyleClass().add(rendered.styleClass());
            }
            if (rendered.tooltip() != null && !rendered.tooltip().isBlank()) {
                setTooltip(new Tooltip(rendered.tooltip()));
            }
            setAlignment(definition.type() == io.agenticdb.model.ColumnType.NUMBER
                    ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        }
    }

}
