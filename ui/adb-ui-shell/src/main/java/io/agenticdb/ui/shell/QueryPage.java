package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
import io.agenticdb.safety.DangerClassifier;
import io.agenticdb.safety.ErrorLocator;
import io.agenticdb.sql.SqlFormatter;
import io.agenticdb.sql.SqlSplitter;
import io.agenticdb.sql.SqlSplitter.SqlStatement;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
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
                             String executedText) {
    }

    private final DbSession session;
    private final String connectionName;
    private final Executor dbExecutor;
    private final DataGridPage.Feedback feedback;
    private final QueryHistory history;

    private final SqlEditor editor = new SqlEditor();
    private final TabPane results = new TabPane();
    private final Label status = new Label("就绪");
    private final Label risk = new Label("只读");
    private final Label range = new Label("—");
    private final Label duration = new Label("— ms");
    private final MenuButton historyButton = new MenuButton("历史");
    private final Button run = new Button("▶ 执行");
    private final Button runAll = new Button("▶▶ 全部执行");
    private final Button cancel = new Button("停止");
    private final Button explain = new Button("执行计划");
    private final Button format = new Button("格式化");
    private final Button save = new Button("保存");
    private final HBox findBar = new HBox(6);
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();

    private Predicate<Danger> confirmation = this::confirmDanger;
    private Runnable stateListener = () -> { };
    private IntConsumer dirtyListener = count -> { };
    private long requestSequence;
    private Future<?> inFlight;
    private boolean running;
    private boolean cancelled;
    private boolean dirty;
    private boolean activated;
    private File currentFile;

    public QueryPage(DbSession session, String connectionName, String initialSql,
                     Executor dbExecutor, SettingsStore settings,
                     DataGridPage.Feedback feedback) {
        this.session = session;
        this.connectionName = connectionName;
        this.dbExecutor = dbExecutor;
        this.feedback = feedback;
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

        Button clear = new Button("清空");
        clear.getStyleClass().add("adb-button");
        clear.setOnAction(event -> editor.setText(""));

        risk.setId("query-risk");
        risk.getStyleClass().addAll("adb-badge", "adb-query__risk");
        risk.setTooltip(new Tooltip("危险分级来自 DangerClassifier；写操作执行前会确认"));

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);

        HBox toolbar = new HBox(6, run, runAll, cancel, explain, format, save, clear,
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

        Button close = new Button("✕");
        close.setAccessibleText("关闭");
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

    private void execute(List<SqlStatement> statements, boolean explainPlan) {
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
                for (int i = 0; i < snapshot.size(); i++) {
                    SqlStatement statement = snapshot.get(i);
                    String sql = explainPlan ? explainSql(statement.text()) : statement.text();
                    try {
                        completed.add(session.execute(sql));
                    } catch (java.sql.SQLException e) {
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        return new RunResult(completed, e, statement, i, elapsed, joined);
                    }
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                return new RunResult(completed, null, null, -1, elapsed, joined);
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
            results.getTabs().add(execution.hasRows()
                    ? resultTab(execution, i + 1)
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
        } else {
            long rows = result.results().stream().mapToLong(ExecutionResult::rowCount).sum();
            range.setText(rows + " 行");
            duration.setText(result.durationMs() + " ms");
            status.setText((explainPlan ? "执行计划" : "执行完成")
                    + " · " + result.results().size() + " 条语句 · " + result.durationMs() + " ms");
            feedback.status(status.getText());
            feedback.range(rows + " 行");
            feedback.duration(result.durationMs());
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

    private String explainSql(String sql) {
        // ⭐ 执行计划语句由方言给 —— 以前这里是 switch，加一种库要改这里。
        return session.dialect().explainSql(sql);
    }

    // ─────────────────────────── 结果页 ───────────────────────────

    private Tab resultTab(ExecutionResult result, int index) {
        TableView<java.util.Map<String, Object>> table = new TableView<>();
        table.setId("query-result-table");
        table.getStyleClass().addAll("adb-grid", "adb-query__result-table");
        table.setItems(FXCollections.observableArrayList(result.rows()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("查询成功，但没有返回行"));

        TableColumn<java.util.Map<String, Object>, Void> number = new TableColumn<>("#");
        number.setSortable(false);
        number.setPrefWidth(52);
        number.setMinWidth(52);
        number.setMaxWidth(52);
        number.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : Long.toString(getIndex() + 1));
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
            column.setSortable(false);
            column.setPrefWidth(140);
            column.setCellValueFactory(data ->
                    new ReadOnlyObjectWrapper<>(data.getValue().get(key)));
            column.setCellFactory(cell -> new ResultCell(definition));
            table.getColumns().add(column);
        }

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

        VBox box = new VBox(6, toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.getStyleClass().add("adb-query__result-box");

        Tab tab = new Tab("结果 " + index + " · " + result.rowCount() + " 行", box);
        tab.setClosable(false);
        return tab;
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
            alert.getDialogPane().getStylesheets().addAll(getScene().getStylesheets());
            alert.getDialogPane().getStyleClass().add("adb-dialog");
        }
        return alert.showAndWait().orElse(cancelType) == execute;
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
