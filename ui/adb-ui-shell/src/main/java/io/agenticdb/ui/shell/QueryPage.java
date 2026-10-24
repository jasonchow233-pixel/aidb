package io.agenticdb.ui.shell;

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
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
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
    /** 密度档位 → 行高。 */
    private int rowHeight = RowHeights.of("normal");
    private final Label status = new Label("就绪");
    private final Label risk = new Label("只读");
    private final Label range = new Label("—");
    private final Label duration = new Label("— ms");
    private final MenuButton historyButton = new MenuButton("历史");
    private final Button run = IconButtons.of(Icons.Icon.RUN, "执行");
    private final Button runAll = IconButtons.of(Icons.Icon.RUN_ALL, "全部执行");
    private final Button cancel = IconButtons.of(Icons.Icon.STOP, "停止");
    private final Button explain = IconButtons.of(Icons.Icon.EXPLAIN, "执行计划");
    private final Button format = new Button("格式化");
    private final Button save = new Button("保存");
    private final HBox findBar = new HBox(6);
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();

    private Predicate<Danger> confirmation = this::confirmDanger;
    private Runnable stateListener = () -> { };
    private IntConsumer dirtyListener = count -> { };
    /** 「存为命名查询」的回调（名字, SQL）。 */
    private java.util.function.BiConsumer<String, String> onSaveNamed = (name, sql) -> { };
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
            feedback.status("编辑器是空的，没什么可存");
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog("片段 " + (++saveCounter));
        dialog.setTitle("存为代码片段");
        dialog.setHeaderText("给这段 SQL 起个名字（之后可插入到光标处）");
        dialog.setContentText(text.length() > 120 ? text.substring(0, 120) + "…" : text);
        java.util.Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        onSaveSnippet.accept(name.get().trim(), text);
        feedback.status("已保存片段：" + name.get().trim());
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
        feedback.status("已插入片段：" + entry.name());
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
            feedback.status("编辑器是空的，没什么可存");
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog("查询 " + (++saveCounter));
        dialog.setTitle("存为命名查询");
        dialog.setHeaderText("给这条 SQL 起个名字");
        dialog.setContentText(sql.length() > 120 ? sql.substring(0, 120) + "…" : sql);
        java.util.Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        onSaveNamed.accept(name.get().trim(), sql);
        feedback.status("已保存查询：" + name.get().trim());
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
        Label title = new Label("SQL 查询");
        title.getStyleClass().add("adb-page__title");

        Label connection = new Label(connectionName);
        connection.getStyleClass().add("adb-badge");
        connection.getStyleClass().add("adb-badge--muted");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        historyButton.setId("query-history");
        historyButton.getStyleClass().add("adb-button");
        historyButton.getItems().add(new MenuItem("暂无历史"));

        HBox header = new HBox(8, title, connection, spacer, historyButton);
        header.getStyleClass().add("adb-query__header");
        header.setAlignment(Pos.CENTER_LEFT);

        run.setId("query-run");
        run.getStyleClass().addAll("adb-button", "adb-button--primary");
        run.setTooltip(new Tooltip("执行光标所在语句 (⌘↵)"));
        run.setOnAction(event -> runCurrent());

        runAll.setId("query-run-all");
        runAll.getStyleClass().add("adb-button");
        runAll.setTooltip(new Tooltip("按顺序执行全部语句 (⇧⌘↵)"));
        runAll.setOnAction(event -> runAll());

        cancel.setId("query-cancel");
        cancel.getStyleClass().add("adb-button");
        cancel.setOnAction(event -> cancel());

        explain.setId("query-explain");
        explain.getStyleClass().add("adb-button");
        explain.setTooltip(new Tooltip("EXPLAIN / EXPLAIN QUERY PLAN 当前语句"));
        explain.setOnAction(event -> explainCurrent());

        format.setId("query-format");
        format.getStyleClass().add("adb-button");
        format.setTooltip(new Tooltip("格式化当前 SQL (⌥⇧F)"));
        format.setOnAction(event -> formatSql());

        save.setId("query-save");
        save.getStyleClass().add("adb-button");
        save.setTooltip(new Tooltip("保存为 .sql 文件 (⌘S)"));
        save.setOnAction(event -> save());

        Button saveNamed = new Button("存为命名查询");
        saveNamed.setId("query-save-named");
        saveNamed.getStyleClass().add("adb-button");
        saveNamed.setTooltip(new Tooltip("给当前 SQL 起个名字，之后从「已保存查询」一键打开"));
        saveNamed.setOnAction(event -> saveNamed());

        // ⭐ Q2：变量按钮 —— 语句里有 ? / :name 就亮起来
        Button variables = new Button("变量");
        variables.setId("query-variables");
        variables.getStyleClass().add("adb-button");
        variables.setTooltip(new Tooltip("填写语句里的参数（? 与 :name）"));
        variables.setOnAction(event -> runWithVariables());

        Button clear = new Button("清空");
        clear.getStyleClass().add("adb-button");
        clear.setOnAction(event -> editor.setText(""));

        risk.setId("query-risk");
        risk.getStyleClass().addAll("adb-badge", "adb-query__risk");
        risk.setTooltip(new Tooltip("危险分级来自 DangerClassifier；写操作执行前会确认"));

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);

        HBox toolbar = new HBox(6, run, runAll, cancel, explain, format, save, saveNamed,
                variables, clear,
                risk, toolbarSpacer);
        toolbar.getStyleClass().add("adb-query__toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

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

        getChildren().addAll(header, toolbar, findBar, split, statusBar);
    }

    private void buildFindBar() {
        findBar.setId("query-find-bar");
        findBar.getStyleClass().add("adb-query__find");
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setVisible(false);
        findBar.setManaged(false);

        findField.setId("query-find");
        findField.setPromptText("查找");
        findField.setPrefWidth(180);
        findField.getStyleClass().add("adb-query__find-field");

        replaceField.setId("query-replace");
        replaceField.setPromptText("替换为");
        replaceField.setPrefWidth(180);
        replaceField.getStyleClass().add("adb-query__find-field");

        Button next = new Button("下一个");
        next.setId("query-find-next");
        next.getStyleClass().add("adb-button");
        next.setOnAction(event -> findNext());

        Button replaceOne = new Button("替换");
        replaceOne.setId("query-replace-one");
        replaceOne.getStyleClass().add("adb-button");
        replaceOne.setOnAction(event -> replaceCurrent());

        Button replaceAll = new Button("全部替换");
        replaceAll.setId("query-replace-all");
        replaceAll.getStyleClass().add("adb-button");
        replaceAll.setOnAction(event -> replaceAll());

        Button close = IconButtons.icon(Icons.Icon.CLOSE, "关闭");
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
        feedback.status("查询页 · " + connectionName + " · " + (dirty ? "未保存" : "就绪"));
        feedback.range(range.getText());
        feedback.duration(-1);
        feedback.dirty(dirty ? 1 : 0);
        feedback.readonly(false);
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
            status.setText("未找到：" + needle);
            return;
        }
        editor.select(index, index + needle.length());
        status.setText("找到 " + needle + " · 第 " + (index + 1) + " 个字符");
    }

    private void replaceCurrent() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String selected = editor.area().getSelectedText();
        if (needle.equals(selected)) {
            editor.area().replaceSelection(replaceField.getText());
            status.setText("已替换");
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
            status.setText("未找到：" + needle);
            return;
        }
        editor.setText(text.replace(needle, replaceField.getText()));
        status.setText("已替换 " + count + " 处");
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
        risk.setText(danger.label());
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
        runAll.setDisable(running);
        explain.setDisable(running);
        format.setDisable(running);
        save.setDisable(running);
        cancel.setDisable(!running);
        cancel.setVisible(running);
        cancel.setManaged(running);
        stateListener.run();
    }

    // ─────────────────────────── 执行 ───────────────────────────

    public void runCurrent() {
        List<SqlStatement> statements = SqlSplitter.statementAt(editor.text(), editor.caret())
                .map(statement -> List.of(statement)).orElseGet(List::of);
        if (statements.isEmpty()) {
            status.setText("没有可执行语句");
            feedback.status("没有可执行语句");
            return;
        }
        execute(statements, false);
    }

    public void runAll() {
        List<SqlStatement> statements = SqlSplitter.split(editor.text());
        if (statements.isEmpty()) {
            status.setText("没有可执行语句");
            feedback.status("没有可执行语句");
            return;
        }
        execute(statements, false);
    }

    public void explainCurrent() {
        List<SqlStatement> statements = SqlSplitter.statementAt(editor.text(), editor.caret())
                .map(statement -> List.of(statement)).orElseGet(List::of);
        if (statements.isEmpty()) {
            status.setText("没有可解释语句");
            return;
        }
        execute(statements, true);
    }

    public void formatSql() {
        String formatted = SqlFormatter.format(editor.text());
        if (formatted.isBlank()) {
            return;
        }
        editor.setText(formatted);
        setDirty(true);
        status.setText("已格式化");
        feedback.status("SQL 已格式化");
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
        status.setText(comment ? "已取消注释" : "已注释");
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
        status.setText("已请求取消");
        feedback.status("已请求取消查询");
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
            status.setText("没有可执行语句");
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
            status.setText("有参数没填值：" + String.join(", ", missing));
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
            status.setText("已取消执行");
            return;
        }

        long request = ++requestSequence;
        cancelled = false;
        running = true;
        updateButtons();
        results.getTabs().clear();
        status.setText((explainPlan ? "正在生成执行计划…" : "正在执行…"));
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
                status.setText("已取消");
                feedback.status("已取消查询");
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
            status.setText("执行失败：" + message);
            feedback.status("查询失败：" + message);
            feedback.duration(-1);
            showErrorTab("执行失败", message, null, -1);
        });
        task.setOnCancelled(event -> {
            if (request == requestSequence) {
                running = false;
                updateButtons();
                status.setText("已取消");
                feedback.status("已取消查询");
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
            showErrorTab("语句 " + (result.failedIndex() + 1) + " 失败",
                    readable(result.error()), result.failed(),
                    result.failedIndex() + 1);
            status.setText("执行失败 · 已完成 " + result.results().size() + " 条");
            range.setText("—");
            duration.setText(result.durationMs() + " ms");
            feedback.status("查询失败：" + readable(result.error()));
            feedback.range("—");
            feedback.duration(result.durationMs());
            // ⭐ UX-5-D：失败时更要给出语句（拿去改正是用户下一步要做的事），
            //    但**不推进刷新时刻** —— 这一轮没有取到任何新数据。
            feedback.sql(result.executedText());
        } else {
            long rows = result.results().stream().mapToLong(ExecutionResult::rowCount).sum();
            range.setText(rows + " 行");
            duration.setText(result.durationMs() + " ms");
            status.setText((explainPlan ? "执行计划" : "执行完成")
                    + " · " + result.results().size() + " 条语句 · " + result.durationMs() + " ms");
            feedback.status(status.getText());
            feedback.range(rows + " 行");
            feedback.duration(result.durationMs());
            // ⭐ UX-5-D：状态栏给出「跑的是哪条、这屏结果是什么时候落地的」。
            feedback.sql(result.executedText());
            feedback.refreshedAt(System.currentTimeMillis());
            if (!explainPlan) {
                history.add(result.executedText());
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
        feedback.readonly(false);
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
        rowHeight = RowHeights.of(density);
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
        table.setPlaceholder(EmptyState.of(Icons.Icon.TABLE, "查询成功，但没有返回行",
                "这是正常的空结果 —— 检查 WHERE 条件，或确认目标表里确实没有数据。"));

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

        Tab tab = new Tab("结果 " + index);
        tab.setClosable(false);

        // ⭐ Q5：可分页的 SELECT 走服务端分页；其余保留「整段取回 + 截断提示」。
        if (QueryPaginator.isPaginable(sql) && result.hasRows()) {
            PagedLoader loader = new PagedLoader(tab, index, table, rowOffset,
                    sql, params, result.columns());
            VBox box = new VBox(6, loader.node(), table, typeStrip(result.columns()));
            VBox.setVgrow(table, Priority.ALWAYS);
            box.getStyleClass().add("adb-query__result-box");
            tab.setContent(box);
            loader.start();
            return tab;
        }

        table.setItems(FXCollections.observableArrayList(result.rows()));
        Label meta = new Label(result.rowCount() + " 行 · " + result.durationMs() + " ms");
        meta.getStyleClass().add("adb-query__result-meta");

        Button copy = new Button("复制 TSV");
        copy.getStyleClass().add("adb-button");
        copy.setOnAction(event -> copyResultTsv(result));

        Button export = new Button("导出 CSV");
        export.getStyleClass().add("adb-button");
        export.setOnAction(event -> exportCsv(result));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar;
        if (result.isTruncated()) {
            // ⭐ 静默截断比不分页更糟：用户会以为看到的就是全部。
            meta.setText(meta.getText() + " · 已截断");
            Label warning = new Label("\u26A0 结果已截断，上限 "
                    + io.agenticdb.model.QueryLimits.MAX_PAGE_SIZE + " 行");
            warning.setId("query-result-truncated");
            warning.getStyleClass().add("adb-query__result-truncated");
            toolbar = new HBox(6, meta, warning, spacer, copy, export);
        } else {
            toolbar = new HBox(6, meta, spacer, copy, export);
        }
        toolbar.getStyleClass().add("adb-query__result-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, toolbar, table, typeStrip(result.columns()));
        VBox.setVgrow(table, Priority.ALWAYS);
        box.getStyleClass().add("adb-query__result-box");
        tab.setContent(box);
        return tab;
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
        private final Button first = IconButtons.of(Icons.Icon.FIRST, "第一页");
        private final Button previous = IconButtons.of(Icons.Icon.PREVIOUS, "上一页");
        private final Button next = IconButtons.of(Icons.Icon.NEXT, "下一页");
        private final Button last = IconButtons.of(Icons.Icon.LAST, "最后一页");
        private final TextField jumpField = new TextField();
        private final Button jump = new Button("跳转");
        private final Button copy = new Button("复制本页");
        private final Button export = new Button("导出本页");
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

            Label perPage = new Label("每页");
            perPage.getStyleClass().add("adb-muted");
            jumpField.setId("query-result-jump");
            // ⭐ 第十八轮：与数据页 `#data-jump` 同一规格 —— 以前这里只有 id + 宽度，
            //    没有占位文字、没有 tooltip、也没有 accessibleText：截图上就是**一个空白框**，
            //    读屏只念得出「编辑框」。同一种控件在两页必须长得一样、能读的一样。
            jumpField.getStyleClass().add("adb-grid__jump");
            jumpField.setPromptText("跳至页");
            jumpField.setTooltip(new javafx.scene.control.Tooltip("输入页码后回车跳转"));
            jumpField.setAccessibleText("跳至指定页");
            jumpField.setPrefWidth(64);
            jumpField.setMinWidth(56);
            jumpField.setOnAction(event -> jumpToPage(jumpField.getText()));
            jump.getStyleClass().add("adb-button");

            copy.getStyleClass().add("adb-button");
            copy.setOnAction(event -> copyResultTsv(currentResult()));
            export.getStyleClass().add("adb-button");
            export.setOnAction(event -> exportCsv(currentResult()));

            HBox.setHgrow(spacer, Priority.ALWAYS);
            bar.getChildren().setAll(rangeLabel, recordNav, spacer, perPage,
                    pageSizeBox, jumpField, jump, copy, export);
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
                    QueryPaginator.PagedSql pageQ =
                            QueryPaginator.pageSql(sql, params, pageSize, offset);
                    RowPage page = session.query(pageQ.sql(), pageSize, pageQ.params());
                    QueryPaginator.PagedSql countQ = QueryPaginator.countSql(sql, params);
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
                String message = "分页查询失败：" + readable(task.getException());
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
            jump.setDisable(loading);
        }

        private void updatePager() {
            long pages = QueryLimits.totalPages(total, pageSize);
            long current = QueryLimits.pageNumberOf(offset, pageSize);
            pageLabel.setText(pages < 0 ? "第 " + current + " 页"
                    : "第 " + current + " / " + pages + " 页");
            rangeLabel.setText(total < 0 ? "总行数未知" : (total + " 行"));
            tab.setText("结果 " + index + " · " + (total < 0 ? "?" : total) + " 行");
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
                String message = "已到达 OFFSET 上限 " + QueryLimits.MAX_OFFSET
                        + "，请改用筛选条件而不是继续翻页";
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
                String message = "已到达 OFFSET 上限 " + QueryLimits.MAX_OFFSET
                        + "，请改用筛选条件而不是继续翻页";
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
        Tab tab = new Tab("消息 " + index, text);
        tab.setClosable(false);
        return tab;
    }

    private void showErrorTab(String title, String message, SqlStatement statement,
                              int statementIndex) {
        StringBuilder body = new StringBuilder();
        if (statementIndex > 0) {
            body.append("语句：").append(statementIndex).append("\n\n");
        }
        if (statement != null) {
            body.append(statement.text()).append("\n\n");
        }
        body.append(message);
        String advice = ErrorLocator.explainConstraintViolation(message);
        if (!advice.isBlank()) {
            body.append("\n\n建议：").append(advice);
        }

        if (statement != null) {
            Optional<ErrorLocator.Position> position =
                    ErrorLocator.locate(message, statement.text());
            position.ifPresent(pos -> {
                body.append("\n\n位置：第 ").append(pos.line()).append(" 行，第 ")
                        .append(pos.column()).append(" 列");
                int offset = statement.start()
                        + offsetOf(statement.text(), pos.line(), pos.column());
                editor.moveTo(offset);
            });
        }

        TextArea text = new TextArea(body.toString());
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("adb-query__message");
        Tab tab = new Tab(title, text);
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
        status.setText("已复制 " + result.rowCount() + " 行 TSV");
        feedback.status("已复制查询结果 TSV");
    }

    private void exportCsv(ExecutionResult result) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出查询结果");
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
            status.setText("已导出：" + file.getName());
            feedback.status("已导出查询结果");
        } catch (IOException e) {
            status.setText("导出失败：" + readable(e));
            feedback.status("导出失败：" + readable(e));
        }
    }

    // ─────────────────────────── 保存 / 关闭 ───────────────────────────

    public void save() {
        Window window = getScene() == null ? null : getScene().getWindow();
        if (currentFile == null) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存 SQL");
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
            status.setText("已保存：" + currentFile.getName());
            feedback.status(status.getText());
        } catch (IOException e) {
            status.setText("保存失败：" + readable(e));
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
            MenuItem empty = new MenuItem("暂无历史");
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
                status.setText("已载入历史 SQL");
            });
            historyButton.getItems().add(item);
        }
        MenuItem clear = new MenuItem("清空历史");
        clear.setOnAction(event -> {
            history.clear();
            refreshHistory();
            status.setText("历史已清空");
        });
        historyButton.getItems().add(new SeparatorMenuItem());
        historyButton.getItems().add(clear);
    }

    private boolean confirmDanger(Danger danger) {
        ButtonType execute = new ButtonType("执行 " + danger.label(),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认执行");
        alert.setHeaderText("这条 SQL 的风险等级：" + danger.label());
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
            setText(rendered.text());
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
