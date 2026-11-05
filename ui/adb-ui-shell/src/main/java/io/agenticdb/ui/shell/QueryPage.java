package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.RowHeights;
import io.agenticdb.ui.Icons;
import io.agenticdb.editing.EditBuffer;
import io.agenticdb.editing.EditSqlBuilder;
import io.agenticdb.editing.ResultEditPolicy;
import io.agenticdb.editing.RowIdentity;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.introspect.QueryPaginator;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
import io.agenticdb.model.PageSize;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;
import io.agenticdb.safety.DangerClassifier;
import io.agenticdb.safety.ErrorLocator;
import io.agenticdb.sql.SqlFormatter;
import io.agenticdb.sql.SqlQueryShape;
import io.agenticdb.sql.SqlSplitter;
import io.agenticdb.sql.SqlSplitter.SqlStatement;
import io.agenticdb.transfer.ExportFormat;
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
import javafx.scene.Node;
import javafx.scene.control.Alert;
import org.fxmisc.richtext.CodeArea;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.SelectionMode;
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
import java.io.OutputStream;
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
 * <p>⭐ R58-c 起结果网格能按主键写回：判定在 {@code ResultEditPolicy}（模型层），
 * 缓冲与生成的 SQL 与数据页共用同一套（{@code EditBuffer} / {@code EditSqlBuilder}），
 * 这里只负责「哪一格能点」与「保存前给用户看一眼 SQL」，界面自己不写 UPDATE。
 *
 * <p>所有 JDBC 调用都提交到共享单线程执行器，取消通过 {@link DbSession#cancelCurrent()}。
 */
public final class QueryPage extends VBox implements AutoCloseable {

    private record RunResult(List<ExecutionResult> results, java.sql.SQLException error,
                             SqlStatement failed, int failedIndex, long durationMs,
                             String executedText, List<String> texts,
                             List<List<Object>> bound) {
    }

    private final DbSession session;
    /** ⭐ G1：行 2 菜单的显示名 —— 有库层时换成当前库，不再 final 锁死连接名。 */
    private String connectionName;
    private final Executor dbExecutor;
    /** ⭐ R58-b：结果回写的判定要读真表的列与主键 —— 与数据页共用同一个内省服务。 */
    private final SchemaService schemaService;
    private final DataGridPage.Feedback feedback;
    private final QueryHistory history;

    private final SqlEditor editor = new SqlEditor();
    /** ⭐ R57-a：编辑器补全（弹层 + 模式缓存 + ⌘Space / 点号触发）。 */
    private final QueryCompletions completions;
    private final TabPane results = new TabPane();
    /**
     * ⭐ D-53：结果带**整块空着**的那两态（从没执行 / 已取消）各有一句解释。
     *
     * <p>TabPane 没有 placeholder 这个口子，所以只能在外面套一层 {@link StackPane}：
     * 页签条空着的时候盖上去，一旦有了页签就收起来（{@link #updateResultsEmptyState()}）。
     *
     * <p>「跑完了、零行」不在这里 —— 那一态早有实现（E5 的网格占位，
     * {@code query.emptyTitle}），这一格把它盖成「这个页签没跑过」就是骗人。
     *
     * <p>字形各让开一步（帧检出的第四条）：原先「没执行」用 {@code QUERY} —— 那是功能区
     * Query 磁贴与侧栏 New Query 的字形，一帧里同一符号说三件事；「已取消」用 {@code STOP} ——
     * 和<b>同一屏</b>工具条那颗 Stop 一模一样。现在说的是「结果会出现在这里」和「这一轮作废」，
     * 用 {@code GRID} 与 {@code CLEAR}。
     */
    private final StackPane resultsArea = new StackPane();
    private final EmptyState resultsNeverRun = EmptyState.of(Icons.Icon.GRID,
            I18n.t("query.empty.none.title"), I18n.t("query.empty.none.body"));
    private final EmptyState resultsCancelledRun = EmptyState.of(Icons.Icon.CLEAR,
            I18n.t("query.empty.cancelled.title"), I18n.t("query.empty.cancelled.body"));
    /** 结果带此刻为什么是空的 —— 在途（RUNNING）时空着是对的，两句都不许出声。 */
    private ResultsBand resultsBand = ResultsBand.NEVER_RUN;

    private enum ResultsBand {
        NEVER_RUN, RUNNING, CANCELLED
    }
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
    /** ⭐ D-54：光标在第几行第几列 + 框了多少个字 —— 编辑器知道，屏幕上原本一个字都没说。 */
    private final Label caretReadout = new Label();
    private final MenuButton historyButton = new MenuButton(I18n.t("grid.toolbar.history"));
    /** ⭐ N21：▶Run 是分裂按钮 —— 点主体直接跑当前语句，箭头展开 Run All / 带参数执行。 */
    private final javafx.scene.control.SplitMenuButton run = new javafx.scene.control.SplitMenuButton();
    private final Button cancel = IconButtons.of(Icons.Icon.STOP, I18n.t("query.stop"));
    private final Button explain = IconButtons.of(Icons.Icon.EXPLAIN, I18n.t("query.explain"));
    private final Button format = new Button(I18n.t("query.beautify"));
    private final Button save = new Button(I18n.t("query.save"));
    private final Button askAi = new Button(I18n.t("query.askAi"));
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
    /** ⭐ G1：当前连接可切的库（catalog）；空 = 该方言没有库层，菜单保持只展示。 */
    private List<String> catalogs = List.of();
    /** ⭐ G1：本页当前绑定的库名（勾标准用；null = 无库层概念或未读到）。 */
    private String currentCatalog;
    /** ⭐ G1：选中库的落点（外壳换绑会话并带 SQL 开新查询页）。 */
    private java.util.function.Consumer<String> onSelectCatalog = catalog -> { };
    /** 偏好存储（列宽持久化）。 */
    private final SettingsStore settings;
    private long requestSequence;
    private Future<?> inFlight;
    /** ⭐ R58-b：每个结果页的回写判定（顺序与结果页签一致）。 */
    private final List<ResultEditState> editStates = new ArrayList<>();
    /** ⭐ R58-b：后台判定回来时页面可能已经关了 —— 关掉之后不再动已经离场的节点。 */
    private boolean disposed;
    /** ⭐ R58-c：结果区正在写库的那一次（期间禁用保存/放弃，页面关闭时取走任务）。 */
    private boolean savingResultEdits;
    private Future<?> saveInFlight;
    private boolean running;
    private boolean cancelled;
    private boolean dirty;
    private boolean activated;
    private File currentFile;
    private int saveCounter;
    /** ⭐ R57-g：鼠标正按在分隔条上 / 已经拖动了分隔条 —— 记忆只认这两个证据。 */
    private boolean dividerPressed;
    private boolean dividerDragging;

    // ─────────────────────── R57-g：查询页分栏 ───────────────────────

    /**
     * 编辑器占分栏的默认比例。
     *
     * <p>取自参照帧逐像素量的数：{@code navicat17-query-editor} 里编辑器白带 377px、
     * 分栏总高 814px = <b>46.3%</b>。原先写死的 0.42 是 P3 第一版顺手定的，没有出处。
     */
    private static final double SPLIT_DEFAULT = 0.463;

    /** 编辑器最小高：实测行距 16px（{@code adb-sql-editor} 12px 字号），8 行 + 余量。 */
    private static final double SPLIT_EDITOR_MIN = 140;

    /**
     * 结果带最小高：帧 {@code p3-query} 量到结果带里「结果页签 31 + Data/Info 30 +
     * 分页条 41 + 列头 26」= 136px 固定开销，再加 3 行 × 23px（{@code RowHeights.grid}）。
     * 低于这个高，网格一行都看不见，只剩三条页签条。
     */
    private static final double SPLIT_RESULTS_MIN = 205;

    /** 偏好里存的是千分比整数（{@code java.util.prefs} 没有 double）。 */
    private static final String SPLIT_RATIO_KEY = "ui.query.split";

    /** 落盘比例的可用区间：越界的值只可能来自被手改坏的偏好文件，钳回来而不是照用。 */
    private static final double SPLIT_RATIO_FLOOR = 0.15;
    private static final double SPLIT_RATIO_CEILING = 0.85;

    /**
     * 摆给截图那一格的预算：每跳 150ms，共 {@value #POSE_READY_TICKS} 跳 ≈ 12s。
     *
     * <p>必须<b>短于</b>快门等待（{@code shots.sh} 那一行的 wait 列），否则「放弃」这条
     * 出口永远不会在落盘前生效 —— 实测就撞上过：14s 的快门、30s 的预算，
     * 于是摆不成的那一帧照样写文件，帧名写着 completions 画面却没有弹层。
     */
    private static final int POSE_READY_TICKS = 80;

    /**
     * 摆「待保存」那一帧写进格子里的值：只进缓冲，不进库。
     *
     * <p>刻意用一个是 seed 里不存在、又一眼看得出「是改出来的」的名字 —— 帧里要能分辨
     * 「这一格是待保存的改动」和「库里本来就这么写的」。</p>
     */
    private static final String POSE_EDITED_TEXT = "Carol";

    /**
     * 结果格里可能出现的全部状态类：先整体摘掉再按这一格的实际状态重贴，
     * 否则单元格被复用（滚动、refresh）时会留着上一格的样式。
     *
     * <p>⚠️ 声明在外层而不是 {@link ResultCell} 里 —— 内部类的静态成员只允许常量变量。
     */
    private static final List<String> RESULT_CELL_STYLES = List.of(
            "adb-grid__cell--null", "adb-grid__cell--empty", "adb-grid__cell--number",
            "adb-grid__cell--boolean", "adb-grid__cell--binary", "adb-grid__cell--json",
            "adb-grid__cell--enum", "adb-grid__cell--date", "adb-grid__cell--long-text",
            "adb-grid__cell--dirty", "adb-grid__cell--editing");

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
     * ⭐ G1：接上库选择器（Navicat 查询工具带行 2 的库下拉）。
     *
     * <p>空列表 = 该方言没有库层（SQLite）—— 菜单保持只展示连接名 + 禁用项，
     * 不摆一个点了没反应的假开关（诚实条款）。
     */
    public void setOnSelectCatalog(java.util.function.Consumer<String> handler) {
        this.onSelectCatalog = handler == null ? catalog -> { } : handler;
    }

    /** ⭐ G1：灌入当前连接可切的库，并标记当前绑定的库（条目上打勾）；
     *  非空时「绑定」禁用项换成真能点的库条目。 */
    public void setCatalogs(List<String> catalogs, String currentCatalog) {
        this.catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
        this.currentCatalog = currentCatalog;
        if (connectionMenu != null) {
            rebuildConnectionMenu();
        }
    }

    /** 供门禁断言：当前可切的库列表。 */
    List<String> catalogsForTest() {
        return catalogs;
    }

    /** ⭐ G1：行 2 那颗菜单的显示名 —— 有库层的连接显示**当前库**（Navicat 同款），无库层保持连接名。 */
    public void setConnectionLabel(String label) {
        if (label != null && !label.isBlank()) {
            this.connectionName = label;
            if (connectionMenu != null) {
                connectionMenu.setText(label);
            }
        }
    }

    /** 供门禁断言：行 2 连接/库菜单的显示名。 */
    String connectionLabelForTest() {
        return connectionMenu == null ? connectionName : connectionMenu.getText();
    }

    /** 供门禁：按库名触发菜单条目（走同一条 onAction 链）。 */
    void selectCatalogForTest(String catalog) {
        for (MenuItem item : connectionMenu.getItems()) {
            if (catalog.equals(item.getText())) {
                item.fire();
                return;
            }
        }
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
                     Executor dbExecutor, SchemaService schemaService, SettingsStore settings,
                     DataGridPage.Feedback feedback) {
        this.session = session;
        this.connectionName = connectionName;
        this.dbExecutor = dbExecutor;
        this.schemaService = schemaService;
        this.feedback = feedback;
        this.settings = settings;
        this.history = new QueryHistory(settings);
        // ⭐ R57-a：补全控制器只认「编辑器 + 会话 + 执行器」，模型读取全部走后台。
        this.completions = new QueryCompletions(editor.area(), session, dbExecutor);

        getStyleClass().addAll("adb-page", "adb-query-page");
        buildUi();
        completions.install();
        editor.addTextListener(text -> {
            setDirty(true);
            updateRisk();
            updateButtons();
            updateCaretReadout();
        });
        // ⭐ D-54：读数跟着光标走，不是跟着执行走 —— 挪一下光标屏幕就该说新位置。
        //    只挂 selectionProperty 一条：实测把 caretPositionProperty 那条单独摘掉，六条门禁全绿 ——
        //    RichTextFX 的光标本来就是选区的一端，挪光标一定动选区，挂两条等于同一件事说两遍。
        editor.area().selectionProperty().addListener(
                (observable, oldSelection, newSelection) -> updateCaretReadout());
        if (initialSql != null && !initialSql.isBlank()) {
            editor.setText(initialSql);
        }
        updateRisk();
        updateButtons();
        updateCaretReadout();
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
        // R43-b：原来这里挂两项（生成 SQL / 解释这条查询），两项打开的是同一段说明。
        // 菜单留着只是让一次点击多两步 —— 收成一颗按钮，一击 = 一份如实说明。
        askAi.setOnAction(event ->
                showCapabilityNotice(I18n.t("query.askAi"), "grid.capability.askAi"));

        risk.setId("query-risk");
        risk.getStyleClass().addAll("adb-badge", "adb-query__risk");
        risk.setTooltip(new Tooltip(I18n.t("query.tip.risk")));

        Region row1Spacer = new Region();
        HBox.setHgrow(row1Spacer, Priority.ALWAYS);

        HBox row1 = new HBox(6, save, builder, format, snippet, askAi,
                row1Spacer, risk, historyButton);
        row1.getStyleClass().add("adb-query__toolbar-row");
        row1.setAlignment(Pos.CENTER_LEFT);
        // ⭐ D-61：这两颗的落点是如实说明页（判据 = showCapabilityNotice），
        //    入口必须提前说实话：环挂 row1、tooltip 与 accessibleText 带「本版本未提供」。
        PendingMark.mark(row1, builder);
        PendingMark.mark(row1, askAi);

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
        // ⭐ R54：这颗按钮（库▾）今天说的是「哪个库」，但「哪家库」只有读字才知道。
        //    顶上放那一家自己的图形 —— 与树、磁贴、状态栏同一枚、同一个品牌色。
        if (session != null) {
            connectionMenu.setGraphic(ConnectionCatalog.graphic(
                    session.dialect().kind(), Icons.SIZE_LABEL));
        }
        rebuildConnectionMenu();

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

        // ⭐ D-53：空态盖在结果带上面，收起时连位置都不占。
        //    adb-empty--inset：浅色主题里 -adb-bg-surface 与 -adb-bg-canvas 同为 #FFFFFF，
        //    .adb-empty 那层「面板」盖在白 canvas 上等于隐形（帧检出的第二条）——
        //    只有这两块贴在带上的卡片换 inset 令牌，其余 23 处空态的底色不动。
        resultsNeverRun.setId("query-results-empty-never-run");
        resultsNeverRun.getStyleClass().add("adb-empty--inset");
        resultsCancelledRun.setId("query-results-empty-cancelled");
        resultsCancelledRun.getStyleClass().add("adb-empty--inset");
        resultsNeverRun.managedProperty().bind(resultsNeverRun.visibleProperty());
        resultsCancelledRun.managedProperty().bind(resultsCancelledRun.visibleProperty());
        resultsArea.getChildren().addAll(results, resultsNeverRun, resultsCancelledRun);
        // 页签列表一动就重算（clear / add 都从这里过）。
        javafx.collections.ListChangeListener<Tab> tabsChanged =
                change -> updateResultsEmptyState();
        results.getTabs().addListener(tabsChanged);
        updateResultsEmptyState();

        SplitPane split = new SplitPane(editor, resultsArea);
        split.setOrientation(Orientation.VERTICAL);
        // ⭐ R57-g：两条最小高是给「拖到底」准备的 —— 没有它们，分隔条能一路把编辑器
        //    拖成 0 高（或者把结果带拖成只剩页签条），而 SplitPane 上没有第二个入口能拖回来。
        editor.setMinHeight(SPLIT_EDITOR_MIN);
        results.setMinHeight(SPLIT_RESULTS_MIN);
        split.setDividerPositions(restoreSplitRatio());
        split.getStyleClass().add("adb-query__split");
        VBox.setVgrow(split, Priority.ALWAYS);
        rememberSplitDrag(split);

        status.setId("query-status");
        status.getStyleClass().add("adb-query__status-text");
        // ⭐ D-54：Navicat 底部那条带实测次序是 SQL 回显 | Read Only | Record 1 of 20473 |
        //    Ln 11, Col 5 | Elapsed Time —— 行列夹在「行数」与「耗时」中间，这一条带照它排。
        //    （pill 那颗 Read Only 在工具带右上角，搬家不在这格。）
        caretReadout.setId("query-caret");
        caretReadout.getStyleClass().add("adb-query__status-meta");
        range.getStyleClass().add("adb-query__status-meta");
        duration.getStyleClass().add("adb-query__status-meta");

        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(12, status, statusSpacer, range, caretReadout, duration);
        statusBar.getStyleClass().add("adb-query__statusbar");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(toolbar, findBar, split, statusBar);
    }

    /**
     * ⭐ G1：连接菜单的两种形态。
     *
     * <p>没有库层（SQLite / 还没读到库列表）= 连接名 + 「新建查询」+ 禁用项，
     * 与 R39 交付的形态一致；读到库列表后换成**真能点**的库条目 ——
     * Navicat 的下拉能换库，我们的禁用项只是它缺席时的如实展示。
     */
    private void rebuildConnectionMenu() {
        List<MenuItem> items = new java.util.ArrayList<>();
        MenuItem newQuery = new MenuItem(I18n.t("action.new-query"));
        newQuery.setId("query-new");
        newQuery.setOnAction(event -> onNewQuery.run());
        items.add(newQuery);
        if (catalogs.isEmpty()) {
            MenuItem bound = new MenuItem(I18n.t("query.connection.bound"));
            bound.setId("query-connection-bound");
            bound.setDisable(true);
            items.add(new SeparatorMenuItem());
            items.add(bound);
        } else {
            items.add(new SeparatorMenuItem());
            items.add(new SeparatorMenuItem());
            MenuItem header = new MenuItem(I18n.t("query.catalog.switchTo"));
            header.setDisable(true);
            items.add(header);
            for (String catalog : catalogs) {
                MenuItem entry = new MenuItem(catalog);
                entry.setId("query-catalog-" + catalog);
                if (catalog.equalsIgnoreCase(currentCatalog)) {
                    entry.setGraphic(Icons.of(Icons.Icon.CHECK, Icons.SIZE_LABEL));
                }
                entry.setOnAction(event -> onSelectCatalog.accept(catalog));
                items.add(entry);
            }
        }
        connectionMenu.getItems().setAll(items);
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

    /**
     * ⭐ D-53：结果带空着的时候，让当前那一句出声——或者一句都不出。
     *
     * <p>「空」有两处真相要看：页签列表空不空，以及**为什么**空。
     * 在途的那一次不算「从没执行过」（页签是它自己清的），也不算「已取消」
     * （还没取消成）—— 所以 RUNNING 一态两句都收着，界面继续靠状态栏说话。
     */
    private void updateResultsEmptyState() {
        boolean hasResults = !results.getTabs().isEmpty();
        resultsNeverRun.setVisible(!hasResults && resultsBand == ResultsBand.NEVER_RUN);
        resultsCancelledRun.setVisible(!hasResults && resultsBand == ResultsBand.CANCELLED);
    }

    /**
     * ⭐ D-53 的门禁后门：把结果带说成某一句，然后按生产同一套规则重算可见性。
     *
     * <p>生产路径拿不出「带着页签却被说成已取消」这一种（清页签与改措辞总在同一个
     * {@code execute()} 里成对发生），所以「有内容就不许出声」那半句只能从这儿验。
     */
    void forceResultsBandForTest(boolean cancelled) {
        resultsBand = cancelled ? ResultsBand.CANCELLED : ResultsBand.NEVER_RUN;
        updateResultsEmptyState();
    }

    /**
     * ⭐ R57-d：导出的行范围。
     *
     * <p>{@code PAGE} 与 {@code SELECTED} 导的是屏幕上那些行（原样、带类型），
     * {@code ALL} 重跑原始 SQL —— 两者的差别就是「本页 200 行 / 全部 250 行」那条门禁钉的东西。
     */
    enum Range {
        PAGE, SELECTED, ALL
    }

    /** P6-2：把一个可分页结果的全部行导出为 CSV（测试与批处理用）。 */
    long exportAllResultsTo(int resultIndex, Writer out) throws Exception {
        return pagers.get(resultIndex).export(Range.ALL, ExportFormat.CSV, out);
    }

    /** P6-2：把当前页导出为 CSV（测试用，绕过文件选择框）。 */
    long exportCurrentPageTo(int resultIndex, Writer out) throws Exception {
        return pagers.get(resultIndex).export(Range.PAGE, ExportFormat.CSV, out);
    }

    /** ⭐ R57-d：范围 × 格式同一入口（测试绕过文件对话框）。 */
    long exportResultTo(int resultIndex, Range range, ExportFormat format, Writer out)
            throws Exception {
        return pagers.get(resultIndex).export(range, format, out);
    }

    /** ⭐ R57-d：xlsx 那一档的同一入口（二进制，目标流不是 Writer）。 */
    long exportResultXlsxTo(int resultIndex, Range range, OutputStream out) throws Exception {
        return pagers.get(resultIndex).exportXlsx(range, out);
    }

    public SqlEditor editor() {
        return editor;
    }

    /** ⭐ R57-a：供门禁断言补全状态（显示 / 候选 / 触发）。 */
    QueryCompletions completionsForTest() {
        return completions;
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

    /**
     * ⭐ D-54：底部带上的行列读数。
     *
     * <p>行与列<b>不</b>问 RichTextFX 的段落索引，直接数光标之前的换行 ——
     * 编辑器 {@code setWrapText(false)}，软换行不存在，原始偏移与屏幕行列是一回事。
     * 选区长度为 0 时那一段整段消失：「已选 0 字」不是信息。
     */
    private void updateCaretReadout() {
        String text = editor.text();
        int caret = Math.max(0, Math.min(editor.caret(), text.length()));
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < caret; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        String readout = I18n.t("query.caret.lnCol", line, caret - lineStart + 1);
        int selected = editor.area().getSelection().getLength();
        if (selected > 0) {
            readout = readout + " " + I18n.t("query.caret.selected", selected);
        }
        caretReadout.setText(readout);
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

    /**
     * ⭐ R57-b：这一次 Run 到底跑哪些语句 —— 有选区只跑选区，没选区跑光标所在语句。
     *
     * <p>选区按字面切句执行（选中的是两段就产两个结果页签），<b>不</b>往两边扩到语句边界：
     * 用户圈了哪一段，落进数据库的就是那一段 —— 与 Navicat 同形，也是「所见即所跑」。
     * 反过来说，圈半句就会拿到服务端的语法错误，这不是缺陷：把半句悄悄补全成整句才更难发现。
     *
     * <p>危险分级、状态栏那句 SQL 回显、查询历史都读这个方法的结果，
     * 所以「确认框问你的」与「真正执行的」是同一份文本，不会出现两套口径。
     */
    private List<SqlStatement> statementsToRun() {
        String text = editor.text();
        int from = editor.area().getSelection().getStart();
        int to = editor.area().getSelection().getEnd();
        if (from == to) {
            return SqlSplitter.statementAt(text, editor.caret())
                    .map(statement -> List.of(statement)).orElseGet(List::of);
        }
        int start = Math.max(0, Math.min(from, text.length()));
        int end = Math.max(start, Math.min(to, text.length()));
        String selected = text.substring(start, end);
        if (selected.isBlank()) {
            // 圈的是一段空白或纯注释：没有可执行的东西，也不假装跑了整条语句。
            return List.of();
        }
        return SqlSplitter.split(selected);
    }

    /**
     * 「这一次没东西可跑」该说什么 —— 圈住空白与整页空是两件事。
     *
     * <p>两个键各写成一次直接调用：{@code I18n.t(cond ? "a" : "b")} 那种写法
     * {@code I18nAuditTest} 的引用正则扫不到键名，等于把这条引用从审计里摘出去。
     */
    private String nothingToRunMessage() {
        return editor.area().getSelection().getStart() != editor.area().getSelection().getEnd()
                ? I18n.t("query.nothingInSelection")
                : I18n.t("query.noStatement");
    }

    public void runCurrent() {
        List<SqlStatement> statements = statementsToRun();
        if (statements.isEmpty()) {
            String message = nothingToRunMessage();
            status.setText(message);
            feedback.status(message);
            return;
        }
        execute(statements, false);
    }

    public void runAll() {
        List<SqlStatement> statements = SqlSplitter.split(editor.text());
        if (statements.isEmpty()) {
            String message = I18n.t("query.noStatement");
            status.setText(message);
            feedback.status(message);
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
        List<SqlStatement> statements = statementsToRun();
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
        // ⭐ R58-c：⌘. 也要停得下一条正在写的保存。它和查询共用这条连接，
        // 但有自己的在途标志 —— 查询没在跑、光靠在下面的 guard 会直接放过它。
        if (savingResultEdits && saveInFlight != null) {
            saveInFlight.cancel(true);
        }
        if (!running) {
            return;
        }
        cancelled = true;
        running = false;
        requestSequence++;
        // ⭐ D-53：这一句落在 cancel() 里，不落在任务的 onCancelled 回调里 ——
        //    回调那三处都被 requestSequence++ 这道闸拦下了（这就是取消后状态栏
        //    停在「Cancel requested」的原因），在这儿改才真的有人看得到。
        resultsBand = ResultsBand.CANCELLED;
        updateResultsEmptyState();
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
    private void runWithVariables() {
        if (running) {
            return;
        }
        List<SqlStatement> statements = statementsToRun();
        if (statements.isEmpty()) {
            status.setText(nothingToRunMessage());
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
        // ⭐ D-53：先把「这一轮在途」写在结果带脸上，再清页签 —— 反过来的话，
        //    清空的这一次变化会先让上一轮的措辞闪一下。
        resultsBand = ResultsBand.RUNNING;
        // ⭐ R58-c：三条按「第几个结果」取用的列表必须跟着页签一起清 —— 留着上一轮的条目，
        // 索引就指到已经不显示的页面上（导出取的是 pagers.get(i)，回写取的是 editStates.get(i)）。
        results.getTabs().clear();
        resultTables.clear();
        pagers.clear();
        editStates.clear();
        updateResultsEmptyState();
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
        // ⭐ R57-a：跑成功过 DDL 就把补全的模式缓存作废（侧栏 S-3 同一口径）——
        //    刚建的表要能立刻补出来，比省一次后台内省值钱。
        completions.onStatementsExecuted(result.texts());
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
     * <p>键带连接名，避免两个连接的结果表互相覆盖；带名字就必须过
     * {@link SettingsStore#key} —— 连接名是自由文本（⭐ D-18）。
     */
    private double savedColumnWidth(String columnId, double fallback) {
        if (settings == null || connectionName == null) {
            return fallback;
        }
        return settings.getInt(
                SettingsStore.key("ui.query.colwidth", connectionName, columnId),
                (int) fallback);
    }

    private void saveColumnWidth(String columnId, double width) {
        if (settings == null || connectionName == null || width <= 0) {
            return;
        }
        settings.setInt(SettingsStore.key("ui.query.colwidth", connectionName, columnId),
                (int) width);
    }

    // ─────────────────────── R57-g：分栏比例的记忆 ───────────────────────

    /** 建页时读回的比例；没有记忆（或没有存储可用）就用参照帧量出来的默认值。 */
    private double restoreSplitRatio() {
        if (settings == null) {
            return SPLIT_DEFAULT;
        }
        int perMille = settings.getInt(SPLIT_RATIO_KEY, -1);
        if (perMille <= 0) {
            return SPLIT_DEFAULT;
        }
        return Math.min(Math.max(perMille / 1000.0, SPLIT_RATIO_FLOOR), SPLIT_RATIO_CEILING);
    }

    /**
     * 用户拖过分栏就把比例记下 —— 只认「在分隔条上按下、移动过、再松手」这一串证据。
     *
     * <p>不监听 {@code positionProperty} 的通用写法在这里会记错东西：分栏被窗口高度
     * 夹住时（编辑器与结果带各自的最小高顶住了），布局自己就会改 position，
     * 于是**第一次在小窗口里打开查询页，就把布局夹出来的比例当成用户的选择存下去**，
     * 之后窗口放大也回不到默认。一次真实拖拽只在松手时落盘一次，也不用防抖。
     *
     * <p>移动那一步不挑事件目标：往下拖 120px，鼠标早就离开那条 6px 的带子了，
     * 此时命中的是结果带 —— 只认「拖拽事件落在分隔条上」会一次都记不上（本轮实测踩过）。
     *
     * <p>用 event **filter**（捕获阶段）而不是 handler：分隔条的处理器是 SplitPaneSkin
     * 自己装的，{@code setOnMouseReleased} 会把它整个顶掉，分隔条就再也拖不动了。
     */
    private void rememberSplitDrag(SplitPane split) {
        split.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event ->
                dividerPressed = isOnSplitDivider(event.getTarget(), split));
        split.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, event -> {
            if (dividerPressed) {
                dividerDragging = true;
            }
        });
        split.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
            if (dividerDragging) {
                persistSplitRatio(split.getDividerPositions()[0]);
            }
            dividerPressed = false;
            dividerDragging = false;
        });
    }

    /**
     * 事件是不是落在分隔条上。
     *
     * <p>要往上走一层：JavaFX 25 的分隔条是 {@code ContentDivider[split-pane-divider]}
     * 里再套一个匿名 {@code [vertical-grabber]} 当把手，鼠标命中的是**里面那颗** ——
     * 只比 {@code split-pane-divider} 这一个类名，按下事件一次都认不出来，
     * 记忆静默失效（界面上拖得动、比例也变了，就是下次开还是默认）。
     */
    private static boolean isOnSplitDivider(Object target, SplitPane split) {
        for (Node node = target instanceof Node n ? n : null;
                node != null && node != split; node = node.getParent()) {
            if (node.getStyleClass().contains("split-pane-divider")) {
                return true;
            }
        }
        return false;
    }

    private void persistSplitRatio(double position) {
        if (settings == null) {
            return;
        }
        settings.setInt(SPLIT_RATIO_KEY, (int) Math.round(position * 1000));
    }

    /** 供门禁断言：当前分栏比例（也是「记忆有没有落盘」的读回口）。 */
    double splitRatio() {
        SplitPane split = (SplitPane) lookup(".adb-query__split");
        return split == null ? -1 : split.getDividerPositions()[0];
    }

    /** 供门禁断言：偏好里存没存过这个比例（-1 = 从没拖过）。 */
    int storedSplitPerMille() {
        return settings == null ? -1 : settings.getInt(SPLIT_RATIO_KEY, -1);
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
        // ⭐ R57-d：默认是 SINGLE —— 实测「导出选中行」最多只能导 1 行（selectRange 也只留一行），
        // 菜单写着"选中行"（复数）却给不出复数。打开 MULTIPLE，这一档的名字才兑现。
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
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
        // ⭐ R58-b：这一屏能不能按主键改回去，判定要跟着结果一起给 ——
        // 网格以前一直是「闷着只读」，用户只能靠试；判不出来的一种就说一句原因。
        // ⭐ R58-c：判成可改的一支真的能点了，所以这个状态对象要先于列建好，
        // 每格的工厂拿的是它本身（判定是后台回来的，建列时还没有结论）。
        ResultEditState edit = new ResultEditState(table);
        editStates.add(edit);
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
            column.setCellFactory(cell -> new ResultCell(definition, edit, key));
            // ⭐ Q4：列宽持久化（以前每次都固定 140px，改完就没了）。
            column.setPrefWidth(savedColumnWidth(key, 140));
            column.widthProperty().addListener((obs, old, width) ->
                    saveColumnWidth(key, width.doubleValue()));
            table.getColumns().add(column);
        }
        edit.changes = resultChangesLabel();
        edit.discard = resultEditButton("query.edit.discard", "query-result-discard",
                event -> discardResultEdits(edit));
        edit.save = resultEditButton("query.edit.save", "query-result-save",
                event -> saveResultEdits(edit));
        judgeEditability(edit, sql, result.columns(), keys);

        Tab tab = new Tab(I18n.t("query.resultTab", index));
        tab.setClosable(false);

        // ⭐ Q5：可分页的 SELECT 走服务端分页；其余保留「整段取回 + 截断提示」。
        if (QueryPaginator.isPaginable(sql) && result.hasRows()) {
            PagedLoader loader = new PagedLoader(tab, index, table, rowOffset,
                    sql, params, result.columns());
            pagers.add(loader);
            // ⭐ R58-c：保存成功后重取当前页 —— 屏幕上留下的必须是库里现在的值，
            // 不是界面缓冲里那份。
            // ⚠️ 走不到这一支的语句没有重取钩子，症状是「保存之后格子跳回查询时读到的旧值，
            //    而状态那句『已保存』仍然是真的」。今天**走不到**：判定层比分页层严 ——
            //    WITH / INTO / FOR UPDATE / 多条语句在 SqlQueryShape 就先判不可改，
            //    而 QueryPaginator.isPaginable 拒的恰好是这几样（两边各自读过同一份语句文本）。
            //    所以这是**潜在**边界、不是现役缺陷；哪天放宽判定（放行 CTE 之类），
            //    这一支必须同时补上重取，不许留第二套重取规则。
            edit.reload = loader::load;
            VBox dataPane = new VBox(0, loader.node(), table);
            VBox.setVgrow(table, Priority.ALWAYS);
            tab.setContent(resultViews(dataPane, result.columns(), sql, null, null, edit.note,
                    edit.changes, edit.discard, edit.save));
            loader.start();
            return tab;
        }

        table.setItems(FXCollections.observableArrayList(result.rows()));
        Label meta = new Label(I18n.t("query.rowsAndDuration", result.rowCount(), result.durationMs()));
        meta.getStyleClass().add("adb-query__result-meta");

        Button copy = new Button(I18n.t("query.copyTsv"));
        copy.getStyleClass().add("adb-button");
        copy.setOnAction(event -> copyResultTsv(result));

        Button export = new Button(I18n.t("grid.toolbar.export"));
        export.getStyleClass().add("adb-button");
        export.setOnAction(event -> exportResultRows(result));

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
        tab.setContent(resultViews(dataPane, result.columns(), sql, meta, warning, edit.note,
                edit.changes, edit.discard, edit.save, copy, export));
        return tab;
    }

    /**
     * ⭐ R58-b：判定这一屏结果能不能按主键改回去，结论落成 Info 带的一行话。
     *
     * <p>分两段走：形状层（{@link SqlQueryShape}，纯函数、不碰数据库）当场就能拒的立刻出句子；
     * 认成单表 SELECT 的才去后台读表结构 —— 内省是 JDBC 调用，不能在 FX 线程上跑。
     *
     * <p>⚠️ 判定<b>不改写 SQL、也不替用户把主键补进投影</b>：界面上显示的是哪条语句，
     * 底下跑的就是哪条。读不到表 / 报错都归成「读不到这张表那一句」，堆栈只进日志。
     *
     * <p>表结构跟着结论一起带回来：写 UPDATE 要用的是<b>整张表</b>的列序与主键名，
     * 光有投影里那几格不够（{@link EditSqlBuilder#plan} 按 {@code schema.columns()} 排 SET）。
     */
    private void judgeEditability(ResultEditState edit, String sql,
                                  List<ColumnDef> columns, List<String> keys) {
        SqlQueryShape.Shape shape = SqlQueryShape.parse(sql);
        if (shape instanceof SqlQueryShape.Refused refused) {
            applyEditDecision(edit, new EditJudgement(new ResultEditPolicy.Blocked(
                    refused.reason().messageKey(), refused.detail()), null));
            return;
        }
        String target = ((SqlQueryShape.Readable) shape).select().table();
        List<String> labels = columns.stream().map(ColumnDef::name).toList();
        Task<EditJudgement> task = new Task<>() {
            @Override
            protected EditJudgement call() {
                try {
                    TableSchema schema = schemaService.describe(session, target);
                    return new EditJudgement(
                            ResultEditPolicy.judge(shape, schema, labels, keys), schema);
                } catch (Exception error) {
                    System.err.println("[query] cannot describe " + target + ": " + error);
                    return new EditJudgement(new ResultEditPolicy.Blocked(
                            ResultEditPolicy.Block.TABLE_UNREADABLE.messageKey(), target), null);
                }
            }
        };
        task.setOnSucceeded(event -> applyEditDecision(edit, task.getValue()));
        submit(task);
    }

    /** 判定结论 + 它依据的表结构（只有判成可改时才带得上结构）。 */
    private record EditJudgement(ResultEditPolicy.Decision decision, TableSchema schema) {
    }

    /**
     * 判定落地：不能改的一支只说一句为什么；能改的一支把这一屏真的打开成可编辑。
     *
     * <p>⭐ R58-c：缓冲用数据页那个 {@link EditBuffer}，SQL 用 {@link EditSqlBuilder}，
     * 界面这边只决定「哪一格能进编辑」—— 回写规则不许在这儿再写一遍。
     */
    private void applyEditDecision(ResultEditState edit, EditJudgement judgement) {
        if (disposed) {
            return;
        }
        edit.decision = judgement.decision();
        Label note = edit.note;
        if (judgement.decision() instanceof ResultEditPolicy.Blocked blocked) {
            edit.schema = null;
            edit.buffer = null;
            note.setText(I18n.t(blocked.messageKey()));
            note.getStyleClass().add("adb-query__edit-state--blocked");
            note.setVisible(true);
            note.setManaged(true);
            updateResultEdits(edit);
            return;
        }
        ResultEditPolicy.Editable editable = (ResultEditPolicy.Editable) judgement.decision();
        edit.schema = judgement.schema();
        edit.buffer = new EditBuffer(editable.table());
        // 只有对上表列的格子能编辑，主键格除外 —— 改主键等于把定位这一行的条件也挪走。
        for (TableColumn<java.util.Map<String, Object>, ?> column : edit.table.getColumns()) {
            Object key = column.getUserData();
            ResultEditPolicy.Cell cell = key == null ? null : editable.cell(String.valueOf(key));
            column.setEditable(cell != null && !cell.primaryKey());
        }
        edit.table.setEditable(true);
        // R58-b 那批「判成可改也不许宣布」的前提是点了没反应；现在能反应了，就说该怎么用。
        note.setText(I18n.t("query.edit.ready"));
        note.getStyleClass().remove("adb-query__edit-state--blocked");
        note.setVisible(true);
        note.setManaged(true);
        updateResultEdits(edit);
    }

    /** 「N 处改动」的计数（贴着保存键，改动一眼数得清）。 */
    private Label resultChangesLabel() {
        Label changes = new Label();
        changes.setId("query-result-changes");
        changes.getStyleClass().add("adb-query__result-meta");
        changes.setMinWidth(Region.USE_PREF_SIZE);
        changes.setVisible(false);
        changes.setManaged(false);
        return changes;
    }

    /** 回写动作键（保存 / 放弃）：初始不占位，判定落地后才进出。 */
    private Button resultEditButton(String key, String id,
                                    javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(I18n.t(key));
        button.setId(id);
        button.getStyleClass().add("adb-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setVisible(false);
        button.setManaged(false);
        button.setOnAction(action);
        return button;
    }

    /** 有改动才让保存/放弃进出（与数据页删除键同一条规矩：点不动的键不留位）。 */
    private void updateResultEdits(ResultEditState edit) {
        if (edit.save == null) {
            return;
        }
        boolean ready = edit.buffer != null
                && edit.decision instanceof ResultEditPolicy.Editable
                && edit.buffer.isDirty();
        int count = edit.buffer == null ? 0 : edit.buffer.dirtyCount();
        edit.changes.setText(I18n.t("query.edit.changes", count));
        for (Node node : new Node[]{edit.changes, edit.discard, edit.save}) {
            node.setVisible(ready);
            node.setManaged(ready);
        }
        edit.save.setDisable(savingResultEdits);
        edit.discard.setDisable(savingResultEdits);
    }

    /** 放弃这一屏的改动：只清界面缓冲，库里本来就没动过。 */
    private void discardResultEdits(ResultEditState edit) {
        if (edit.buffer == null || !edit.buffer.isDirty()) {
            return;
        }
        edit.buffer.clear();
        updateResultEdits(edit);
        edit.table.refresh();
    }

    /**
     * ⭐ R58-c：把这一屏的改动写回表里 —— SQL 由 {@link EditSqlBuilder#plan} 生成，
     * 经 {@link SavePreviewDialog} 让用户先看一眼再执行。
     */
    private void saveResultEdits(ResultEditState edit) {
        if (edit.buffer == null || edit.schema == null || !edit.buffer.isDirty()
                || savingResultEdits) {
            return;
        }
        EditSqlBuilder.Plan plan;
        try {
            plan = EditSqlBuilder.plan(session, edit.schema, edit.buffer);
        } catch (RuntimeException error) {
            reportSaveFailure(error, null);
            return;
        }
        if (plan.isEmpty()) {
            return;
        }
        boolean inTransaction;
        try {
            inTransaction = session.inTransaction();
        } catch (java.sql.SQLException error) {
            reportSaveFailure(error, null);
            return;
        }
        if (!SavePreviewDialog.confirm(ownerWindow(), plan, Danger.MUTATING, inTransaction)) {
            return;
        }
        runResultSave(edit, plan, inTransaction);
    }

    /**
     * 供门禁：跳过预览弹窗，直接提交第 {@code index} 个结果页缓冲里的改动。
     *
     * <p>与数据页 {@code submitSave} 同一个理由 —— 真正写库那一步必须能不弹窗地被跑到，
     * 否则「保存之后库里真的变了」这条只能靠人肉点。它走的是同一条 {@link #runResultSave}。
     */
    void submitResultSave(int index) {
        ResultEditState edit = index < editStates.size() ? editStates.get(index) : null;
        if (edit == null || edit.buffer == null || edit.schema == null
                || !edit.buffer.isDirty()) {
            return;
        }
        boolean inTransaction;
        try {
            inTransaction = session.inTransaction();
        } catch (java.sql.SQLException error) {
            reportSaveFailure(error, null);
            return;
        }
        runResultSave(edit, EditSqlBuilder.plan(session, edit.schema, edit.buffer), inTransaction);
    }

    /**
     * ⭐ R58-c：真正写库的那一段（{@link EditSqlBuilder#execute}：一个事务，任一条失败整体回滚）。
     *
     * <p>成功后<b>清缓冲并重取当前页</b> —— 屏幕上留下的必须是库里现在的值，不是缓冲里那份。
     */
    private void runResultSave(ResultEditState edit, EditSqlBuilder.Plan plan,
                               boolean inTransaction) {
        savingResultEdits = true;
        updateResultEdits(edit);
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return EditSqlBuilder.execute(session, plan);
            }
        };
        task.setOnSucceeded(event -> {
            savingResultEdits = false;
            if (disposed) {
                return;
            }
            int matched = task.getValue();
            edit.buffer.clear();
            updateResultEdits(edit);
            // ⚠️ 命中行数不等于改动数时不许报「保存成功」：主键在别处被改过就是这种情况。
            // ⚠️ 事务里也不能报「已落库」—— 那句得说「写进事务、尚未提交」。
            String message;
            if (matched != plan.size()) {
                message = I18n.t("query.edit.rowMissed", matched, plan.size(),
                        edit.schema.name());
            } else if (inTransaction) {
                message = I18n.t("grid.msg.savedInTx", plan.size(), matched);
            } else {
                message = I18n.t("query.edit.saved", plan.size(), edit.schema.name());
            }
            status.setText(message);
            feedback.status(message);
            if (edit.reload != null) {
                edit.reload.run();
            } else {
                edit.table.refresh();
            }
        });
        task.setOnFailed(event -> {
            savingResultEdits = false;
            updateResultEdits(edit);
            reportSaveFailure(task.getException(), plan.preview());
        });
        task.setOnCancelled(event -> {
            // 中断的 Task 走 onCancelled（JavaFX 把 InterruptedException 归到 CANCELLED）：
            // 用户按 ⌘. 不该看到「保存失败」，而且整批已经被回滚，缓冲原样留着。
            savingResultEdits = false;
            updateResultEdits(edit);
            status.setText(I18n.t("query.cancelled"));
            feedback.status(I18n.t("query.cancelledFeedback"));
        });
        saveInFlight = submit(task);
    }

    /** 保存这条路的失败只出一句状态，不弹模态 —— 长文本进日志，界面给人能读的那句。 */
    private void reportSaveFailure(Throwable error, String sql) {
        String reason = readable(error);
        status.setText(I18n.t("query.edit.saveFailed", reason));
        feedback.status(I18n.t("query.edit.saveFailed", reason));
        if (sql != null) {
            System.err.println("[query] save failed, SQL was:\n" + sql + "\n" + error);
        }
    }

    /**
     * ⭐ R58-b/c：一个结果页的回写状态 —— 判定、缓冲、动作键与刷新钩子成对出现。
     *
     * <p>判定是后台回来的，所以这些字段在建树之后才逐个补齐；{@code decision == null}
     * 表示还没回来（此时网格保持只读）。
     */
    private final class ResultEditState {
        final Label note;
        final TableView<java.util.Map<String, Object>> table;
        ResultEditPolicy.Decision decision;
        TableSchema schema;
        EditBuffer buffer;
        Runnable reload;
        Label changes;
        Button save;
        Button discard;

        ResultEditState(TableView<java.util.Map<String, Object>> table) {
            this.table = table;
            note = new Label();
            note.setId("query-result-edit-state");
            note.getStyleClass().add("adb-query__edit-state");
            note.setWrapText(true);
            // 判定还没回来之前不占位（同步执行器下判定可能先于建树完成，所以初始态在这里钉）。
            note.setVisible(false);
            note.setManaged(false);
        }

        /** 判成可改才给结论，其余一律 {@code null}（调用方据此保持只读，不猜）。 */
        ResultEditPolicy.Editable editable() {
            return decision instanceof ResultEditPolicy.Editable editable ? editable : null;
        }

        /** 行 Map 的键 → 表里的目标格；不可改或键对不上给 {@code null}。 */
        ResultEditPolicy.Cell cell(String resultKey) {
            ResultEditPolicy.Editable editable = editable();
            return editable == null ? null : editable.cell(resultKey);
        }

        /**
         * 这一行的身份：只装主键，键名用<b>表里的</b>名字。
         *
         * <p>规则本身在 {@link ResultEditPolicy#identity} —— 界面和真库契约第 14 条
         * 调同一个方法（同 D-38：一个规则不许有两个出处）。
         */
        RowIdentity identity(java.util.Map<String, Object> row) {
            return ResultEditPolicy.identity(editable(), row);
        }
    }

    /** 供门禁：第 index 个结果页的回写判定；{@code null} = 后台内省还没回来。 */
    ResultEditPolicy.Decision editDecisionForTest(int index) {
        return index < editStates.size() ? editStates.get(index).decision : null;
    }

    /** 供门禁：第 index 个结果页 Info 带上那行判定文案。 */
    String editNoteForTest(int index) {
        return index < editStates.size() ? editStates.get(index).note.getText() : null;
    }

    /** 供门禁：第 index 个结果页当前的待保存改动数。 */
    int resultDirtyCountForTest(int index) {
        if (index >= editStates.size()) {
            return -1;
        }
        EditBuffer buffer = editStates.get(index).buffer;
        return buffer == null ? 0 : buffer.dirtyCount();
    }

    /** 供门禁：第 index 个结果页的网格有没有被打开成可编辑。 */
    boolean resultTableEditableForTest(int index) {
        return index < editStates.size() && editStates.get(index).table.isEditable();
    }

    /** 供门禁：某结果页某一列能不能进编辑（键是行 Map 的列键）。 */
    boolean resultColumnEditableForTest(int index, String resultKey) {
        if (index >= editStates.size()) {
            return false;
        }
        for (TableColumn<java.util.Map<String, Object>, ?> column
                : editStates.get(index).table.getColumns()) {
            if (resultKey.equals(column.getUserData())) {
                return column.isEditable();
            }
        }
        return false;
    }

    /**
     * ⭐ N23：结果区的「Data / Info」32px 切换带 + 内容栈。
     *
     * <p>Navicat 17 的结果区是「27px 页签 → 32px Data/Info → 网格」。
     * 页签高度由 CSS 钉在 27px；这一条只负责第二段与两页内容。
     * Info 页承接以前独占一条的类型 chips 与语句预览 —— 它们是「关于这份结果」
     * 的信息，不该常驻在数据上方。
     *
     * <p>⭐ R58-c：这一条带上的「待改几格 + 丢弃 + 保存」和 Copy / Export 同一段，
     * 所以形参从 {@code Button...} 放宽成 {@code Node...} —— 计数那条是 Label。
     * 判定为不可改时它们本来就不 managed，不会把 32px 那条撑开。
     */
    private VBox resultViews(javafx.scene.Node dataPane, List<ColumnDef> columns, String sql,
                             Label meta, Label warning, Label editState, javafx.scene.Node... actions) {
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
        if (editState != null) {
            infoPane.getChildren().add(editState);
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

    /**
     * ⭐ R47-b：把「库▾」摆开 —— 这颗控件交付至今没有一张验收帧。
     *
     * <p>等的是<b>库清单</b>而不是结果格：清单是后台读的（`loadQueryCatalogs`），
     * 空清单 = SQLite 或读失败，那两种情况下这一格没有证据价值，
     * 于是明确放弃，<b>不</b>拍一张「看着像库选择器、里面什么都没有」的图。
     */
    void poseCatalogMenuWhenReady() {
        awaitCatalogs(100);
    }

    /**
     * ⭐ R57 画面债：把补全弹层摆到快门前 —— 这一格交付时只有行为门禁，一张帧都没有。
     *
     * <p>等的是<b>候选真的算出来了</b>：对象清单走后台读（{@code SchemaService}），
     * 读回来之前那一列只有关键字。用「只有关键字」的弹层当「候选跟着库上下文走」的证据
     * 是假图，所以读不到就明确放弃，不写文件。
     *
     * <p>求值入口用的是手动触发那一条（与 ⌘Space 同一个 {@code evaluate(true)}），
     * 不另造一套摆态逻辑 —— 摆态走的必须是用户走的那条路，否则帧证的是 pose 代码、不是功能。
     */
    void poseCompletionsWhenReady() {
        awaitCompletionPose(POSE_READY_TICKS);
    }

    private void awaitCompletionPose(int attemptsLeft) {
        if (!isAttachedToWindow()) {
            // 页刚建好时编辑器还不在场景图上（标签内容要下一个布局脉冲才挂上去），
            // 这一跳不算失败也不算放弃 —— 继续等，等到挂上为止。
            // ⚠️ 这里曾经写成「没挂上就直接 return」，实测那条路径一声不响地把
            //    一张没有弹层的查询页交了出去，正是要防的假图。
            if (attemptsLeft <= 0) {
                ScreenshotSession.abortMatrixFrame("completion pose gave up: the editor never"
                        + " attached to a showing window, so there is nothing to pose on");
                return;
            }
            schedule(150, () -> awaitCompletionPose(attemptsLeft - 1));
            return;
        }
        completions.evaluateForTest(true);
        if (!completions.isPoseableForShots()) {
            if (attemptsLeft <= 0) {
                // ⚠️ 不是「打一行日志然后照常拍」：那样会产出一张 1280×800 的、
                // 没有弹层的查询页，文件名却写着 completions —— 正是 D-37/D-39 那族假图。
                ScreenshotSession.abortMatrixFrame("completion pose gave up: no table candidates"
                        + " (needs a server with objects; demo SQLite has none of this shape)");
                return;
            }
            schedule(150, () -> awaitCompletionPose(attemptsLeft - 1));
            return;
        }
        completions.posePopupForShots();
        System.out.println("[screenshot] completion popup posed, candidates="
                + completions.candidateNamesForTest().size());
        keepCompletionPose(20);
    }

    /** 弹层会自己收（失焦即收，与菜单帧同一族），所以每 1500ms 续摆一次到快门为止。 */
    private void keepCompletionPose(int ticksLeft) {
        if (ticksLeft <= 0 || !isAttachedToWindow()) {
            return;
        }
        schedule(1_500, () -> {
            // 再查一次：这一跳之间页可能已经关了（关页 / 换测试类都会）。
            if (!isAttachedToWindow()) {
                return;
            }
            if (!completions.isShowingForTest()) {
                completions.posePopupForShots();
            }
            keepCompletionPose(ticksLeft - 1);
        });
    }

    /**
     * 编辑器还挂在一扇显示中的窗口上吗。
     *
     * <p>摆态的计时器活得比快门久：页一关（或测试类一换），
     * {@code PopupWindow.show} 就抛 {@code The owner node needs to be associated with a window} ——
     * 异常落在 FX 动画线程上，会端给**下一条**测试。实测踩过。
     * 注意「节点还有 scene」不等于「那扇窗还在」，所以问的是 window。
     */
    private boolean isAttachedToWindow() {
        return completions.isHostWindowUp();
    }

    /**
     * ⭐ R57 画面债：把「圈住哪段跑哪段」摆到快门前。
     *
     * <p>选区是**真选区**（不是画上去的高亮），跑的是真执行路径
     * {@code statementsToRun()}，所以状态栏的 SQL 回显、结果页签标题、危险分级
     * 三者都会跟着变成「只跑了这一段」—— 这一帧证的正是那条共享出口。
     */
    void poseSelectionForShots(String statement) {
        String text = sql();
        int start = text.indexOf(statement);
        if (start < 0) {
            // 同上：找不到就不拍，不拍一张「选区圈着别的东西」的帧冒充选中执行的证据。
            ScreenshotSession.abortMatrixFrame("selection pose gave up: the statement is not in"
                    + " the editor, so the frame would show a selection that was never run");
            return;
        }
        editor().area().selectRange(start, start + statement.length());
        editor().area().requestFollowCaret();
        System.out.println("[screenshot] selection posed, chars=" + statement.length()
                + " at=" + start);
    }

    /**
     * ⭐ R58-d 画面债：把结果区回写的某一态摆到快门前（R58 整批交付时一帧都没有）。
     *
     * <p>摆态全部走用户那条路：{@code table.edit(row, column)} 就是双击那一格（落到
     * {@code ResultCell.startEdit}），改文本后 fire 的是编辑框自己的 ActionEvent（就是回车），
     * 预览那一帧点的是保存那颗键。没有一帧靠「画上去的高亮」—— 那样帧证的是 pose 代码，不是功能。
     *
     * <p>⚠️ 前提不成立就**弃帧**（D-37 / D-39 那一族）：判定还没落地、判成只读却要摆「待保存」、
     * 那一格开不出编辑框 —— 任何一种都拍出一张帧名写着回写、画面是普通结果页的假图，
     * 而它比缺帧更贵：它会替这笔欠账「还掉」它其实没还的那一半。
     */
    void poseResultEditWhenReady(String pose) {
        awaitResultEditPose(pose, POSE_READY_TICKS);
    }

    private void awaitResultEditPose(String pose, int attemptsLeft) {
        ResultEditState edit = editStates.isEmpty() ? null : editStates.getFirst();
        ResultEditPolicy.Decision decision = edit == null ? null : edit.decision;
        if (decision == null) {
            if (attemptsLeft <= 0) {
                ScreenshotSession.abortMatrixFrame("result edit pose gave up: the editability"
                        + " judgement never landed (no result tab, or the background introspect"
                        + " never returned) - the frame would show a plain result grid"
                        + " and claim it is a write-back state");
                return;
            }
            schedule(150, () -> awaitResultEditPose(pose, attemptsLeft - 1));
            return;
        }
        switch (pose) {
            case "blocked" -> poseBlockedBand(edit, decision);
            case "dirty" -> poseDirtyCell(edit, decision, false);
            case "preview" -> poseDirtyCell(edit, decision, true);
            default -> System.out.println("[screenshot] unknown result edit pose: " + pose
                    + " (expected: blocked / dirty / preview)");
        }
    }

    /** 「判成只读」那一帧等的就是 Info 带那句为什么 —— 结论已经落地，文案由判定自己写。 */
    private void poseBlockedBand(ResultEditState edit, ResultEditPolicy.Decision decision) {
        if (!(decision instanceof ResultEditPolicy.Blocked blocked)) {
            ScreenshotSession.abortMatrixFrame("result edit pose gave up: the frame is named"
                    + " 'blocked' but this screen judged EDITABLE, so the Info band would say"
                    + " the opposite of what the file name claims");
            return;
        }
        if (!(lookup("#result-view-info") instanceof javafx.scene.control.ToggleButton info)) {
            // 那句「为什么不能改」住在 Info 切面里，Data 切面看不见它 —— 切不过去就等于
            // 拍一张「帧名写着报因、画面里一个字都没有」的假图。
            ScreenshotSession.abortMatrixFrame("result edit pose gave up: the Info view toggle"
                    + " is not on this page, so the refusal sentence would stay off-screen");
            return;
        }
        // 切过去走的是用户那颗键本身（fire 它的 onAction），不是自己把 pane 摆显。
        info.fire();
        System.out.println("[screenshot] blocked sentence posed on the Info view, reason="
                + blocked.messageKey() + " detail=" + blocked.detail());
    }

    /**
     * 「改了一格、还没保存」，可带保存前预览。
     *
     * <p>改的值 {@value #POSE_EDITED_TEXT} 只进缓冲：预览弹窗的默认键是「取消」，
     * 快门不会点它 —— 这一帧不落任何写。
     */
    private void poseDirtyCell(ResultEditState edit, ResultEditPolicy.Decision decision,
                               boolean openPreview) {
        ResultEditPolicy.Editable editable =
                decision instanceof ResultEditPolicy.Editable e ? e : null;
        if (editable == null || edit.buffer == null || edit.table.getItems().isEmpty()) {
            ScreenshotSession.abortMatrixFrame("result edit pose gave up: this screen judged"
                    + " " + (decision instanceof ResultEditPolicy.Blocked blocked
                    ? "BLOCKED (" + blocked.messageKey() + ")" : "not editable yet")
                    + ", so there is no dirty cell to frame - a read-only grid named 'dirty'"
                    + " is exactly the fake shot this channel exists to refuse");
            return;
        }
        TableColumn<java.util.Map<String, Object>, ?> column = edit.table.getColumns().stream()
                .filter(each -> {
                    Object key = each.getUserData();
                    ResultEditPolicy.Cell cell = key == null
                            ? null : editable.cell(String.valueOf(key));
                    // 主键格不开编辑框（改它等于把定位这一行的条件挪走），所以跳过它。
                    return cell != null && !cell.primaryKey();
                })
                .findFirst().orElse(null);
        if (column == null) {
            ScreenshotSession.abortMatrixFrame("result edit pose gave up: no column in this"
                    + " projection is writable (only the primary key is present)");
            return;
        }
        edit.table.edit(0, column);
        javafx.scene.Node node = edit.table.lookup(".adb-grid__editor");
        if (!(node instanceof TextField editor)) {
            ScreenshotSession.abortMatrixFrame("result edit pose gave up: double-click on that"
                    + " cell never opened an editor, so the shot would show a grid with no"
                    + " pending change");
            return;
        }
        editor.setText(POSE_EDITED_TEXT);
        editor.fireEvent(new javafx.event.ActionEvent(editor, null));
        System.out.println("[screenshot] result cell edited, column=" + column.getUserData()
                + " dirty=" + edit.buffer.dirtyCount());
        if (openPreview) {
            if (edit.save == null || !edit.save.isVisible()) {
                ScreenshotSession.abortMatrixFrame("result edit pose gave up: the save button"
                        + " is not on screen even though a change is buffered");
                return;
            }
            // 保存那颗键自己会开预览弹窗（模态、独立 Stage）—— 快门拍的就是那个弹窗。
            // ⭐ 但这一发必须**跳出动画脉冲**再点：预览弹窗走 {@code showAndWait}，而
            //   JavaFX 禁止在动画/布局处理中调它。实测在 {@code PauseTransition} 的
            //   onFinished 里直接 fire —— 抛 {@code IllegalStateException: showAndWait is
            //   not allowed during animation or layout processing}，弹窗压根没出现，快门
            //   拍到一张「帧名写着 preview、画面里只有脏格子」的假图。真用户点键落在输入
            //   事件里，本来就不在脉冲里，所以 runLater 才是同一条路。
            Platform.runLater(edit.save::fire);
            awaitPreviewDialog(40);
        }
    }

    /**
     * 弹窗到底在不在屏幕上：模态预览弹窗是**独立 Stage**，主窗口那棵场景树里查不到它，
     * 所以只能遍历窗口清单，认带 {@code .dialog-pane} 的那一颗。
     */
    private boolean previewDialogIsUp() {
        javafx.stage.Window owner = ownerWindow();
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window instanceof javafx.stage.Stage candidate && candidate != owner
                    && candidate.isShowing() && candidate.getScene() != null
                    && candidate.getScene().getRoot().lookup(".dialog-pane") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点完保存之后、快门之前，验一次预览弹窗真的上屏了。
     *
     * <p>验不了就弃帧：这一帧要证的是「写库之前用户先看得到那几条 SQL」，
     * 弹窗没开就等于什么也没证 —— 拍一张脏格子冒充 preview 比没图更贵。
     */
    private void awaitPreviewDialog(int ticksLeft) {
        if (previewDialogIsUp()) {
            System.out.println("[screenshot] save preview dialog is on screen, the shutter"
                    + " captures that window");
            return;
        }
        if (ticksLeft <= 0) {
            ScreenshotSession.abortMatrixFrame("result edit pose gave up: the save button was"
                    + " fired but no preview dialog is on screen, so the frame would show a"
                    + " dirty grid and claim it is the pre-save preview");
            return;
        }
        schedule(150, () -> awaitPreviewDialog(ticksLeft - 1));
    }

    private void awaitCatalogs(int attemptsLeft) {
        if (catalogs.isEmpty()) {
            if (attemptsLeft <= 0) {
                System.out.println("[screenshot] catalog pose gave up: no catalogs read"
                        + " (needs a server with a database layer; SQLite has none)");
                return;
            }
            schedule(100, () -> awaitCatalogs(attemptsLeft - 1));
            return;
        }
        connectionMenu.show();
        System.out.println("[screenshot] catalog menu posed, entries=" + catalogs.size());
        keepCatalogMenuUp(20);
    }

    /** 与 {@link #keepMenuUp} 同型：菜单会自己收，续摆到快门为止。 */
    private void keepCatalogMenuUp(int ticksLeft) {
        if (ticksLeft <= 0) {
            return;
        }
        schedule(1_500, () -> {
            if (!connectionMenu.isShowing()) {
                connectionMenu.show();
            }
            keepCatalogMenuUp(ticksLeft - 1);
        });
    }

    /**
     * ⭐ R47-b：摆出「切到另一把库之后」的页面态，与「下拉展开」那张成对。
     *
     * <p>判据是<b>至少两把库</b>：这一帧要证的是「下拉里有第二把库、点得过去、
     * 状态栏跟着换」，只读到一把就摆等于把结论拍成运气。等不到就打印放弃，
     * <b>不</b>交一张「看着像切过」的图。
     */
    void poseCatalogSwitchWhenReady() {
        awaitSecondCatalog(100);
    }

    private void awaitSecondCatalog(int attemptsLeft) {
        if (catalogs.size() < 2) {
            if (attemptsLeft <= 0) {
                System.out.println("[screenshot] catalog switch gave up: got "
                        + catalogs.size() + " database(s), needs at least 2");
                return;
            }
            schedule(100, () -> awaitSecondCatalog(attemptsLeft - 1));
            return;
        }
        String away = catalogs.stream()
                .filter(name -> currentCatalog == null || !name.equalsIgnoreCase(currentCatalog))
                .findFirst().orElse(null);
        if (away == null) {
            System.out.println("[screenshot] catalog switch gave up:"
                    + " no catalog differs from the current one");
            return;
        }
        // 走真菜单条目的 onAction —— 与用户点那一条是同一条链，不是绕过控件直接调外壳。
        selectCatalogForTest(away);
        System.out.println("[screenshot] catalog switched to " + away);
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
            exportPage.setOnAction(event -> chooseAndExport("query-result-page.csv",
                    exportTarget(Range.PAGE)));
            exportSelected.setId("query-export-selected");
            exportSelected.setOnAction(event -> chooseAndExport("query-result-selected.csv",
                    exportTarget(Range.SELECTED)));
            exportAll.setId("query-export-all");
            exportAll.setOnAction(event -> chooseAndExport("query-result-all.csv",
                    exportTarget(Range.ALL)));
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

        // ─────────────────── P6-2 / R57-d：范围 × 格式导出 ───────────────────

        /** 一个范围的落盘动作（格式与目标流由 QueryPage 的 {@code chooseAndExport} 决定）。 */
        private ExportTarget exportTarget(Range range) {
            return new ExportTarget() {
                @Override
                public long write(ExportFormat format, Writer out) throws Exception {
                    return export(range, format, out);
                }

                @Override
                public long writeXlsx(OutputStream out) throws Exception {
                    return exportXlsx(range, out);
                }
            };
        }

        /**
         * 文本档（CSV / TSV / JSON / XML / HTML）导出一个范围。
         *
         * <p>「全部」重跑原始 SQL 流式写出 —— 250 行的结果翻到第 1 页时，本页 200 行、
         * 全部仍是 250 行；「本页」「选中行」导网格里这些行。
         * SQL（{@code INSERT}）那一档在 {@link ResultExport} 那里被拒：查询结果没有目标表名，
         * 硬写会产出一个不能执行的文件，那比不给这一档更坏。
         */
        long export(Range range, ExportFormat format, Writer out) throws Exception {
            if (range == Range.ALL) {
                return ResultExport.writeQuery(session, sql, params, format, out);
            }
            GridColumns grid = gridColumns();
            return ResultExport.writeRows(grid.headers(),
                    values(grid.keys(), rowsOf(range)), format, out);
        }

        /** xlsx 那一档（六格式里唯一的二进制，要走 {@link OutputStream}）。 */
        long exportXlsx(Range range, OutputStream out) throws Exception {
            if (range == Range.ALL) {
                return ResultExport.writeQueryXlsx(session, sql, params, out);
            }
            GridColumns grid = gridColumns();
            return ResultExport.writeRowsXlsx(grid.headers(),
                    values(grid.keys(), rowsOf(range)), ExportFormat.XLSX, out);
        }

        /** 屏幕上这几行 / 选中的这几行。 */
        private List<java.util.Map<String, Object>> rowsOf(Range range) {
            return range == Range.SELECTED
                    ? List.copyOf(table.getSelectionModel().getSelectedItems())
                    : List.copyOf(table.getItems());
        }

        /** 网格列 →（表头文字 · 行 map 的键）：导出与屏幕上看到的那几列一致。 */
        private GridColumns gridColumns() {
            List<String> headers = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (TableColumn<java.util.Map<String, Object>, ?> column : table.getColumns()) {
                if (column.getUserData() instanceof String key) {
                    headers.add(column.getText());
                    keys.add(key);
                }
            }
            return new GridColumns(List.copyOf(headers), List.copyOf(keys));
        }

        /** 行 map 按列键取值 —— 交原始值，不再 {@code String.valueOf}（口径见 {@link ResultExport}）。 */
        private static List<List<Object>> values(List<String> keys,
                                                 List<java.util.Map<String, Object>> rows) {
            List<List<Object>> out = new ArrayList<>(rows.size());
            for (java.util.Map<String, Object> row : rows) {
                out.add(keys.stream().map(row::get).toList());
            }
            return out;
        }

        /** 网格列的表头与取值键（顺序一一对应）。 */
        private record GridColumns(List<String> headers, List<String> keys) {
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
        String advice = ErrorText.constraintAdvice(message);
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

    /**
     * ⭐ R57-d：不可分页结果（{@code QueryPaginator.isPaginable} 之外那一支）的导出。
     *
     * <p>这一支没有「本页 / 全部」之分 —— 行本来就在内存里，导的就是屏上这些行。
     * 走 {@link ResultExport} 同一台机器，所以两种结果、六种格式是一套规则，不是两份。
     */
    private void exportResultRows(ExecutionResult result) {
        List<String> headers = result.columns().stream().map(ColumnDef::name).toList();
        List<String> keys = result.columnKeys();
        List<List<Object>> rows = result.rows().stream()
                .map(row -> keys.stream().map(row::get).toList())
                .toList();
        chooseAndExport("query-result.csv", new ExportTarget() {
            @Override
            public long write(ExportFormat format, Writer out) throws IOException {
                return ResultExport.writeRows(headers, rows, format, out);
            }

            @Override
            public long writeXlsx(OutputStream out) throws IOException {
                return ResultExport.writeRowsXlsx(headers, rows, ExportFormat.XLSX, out);
            }
        });
    }

    // ─────────────────── R57-d：保存对话框选格式 → 后台写盘 ───────────────────

    /** 一次导出的落盘动作：文本档给 {@link Writer}，xlsx 给 {@link OutputStream}。 */
    private interface ExportTarget {
        long write(ExportFormat format, Writer out) throws Exception;

        long writeXlsx(OutputStream out) throws Exception;
    }

    /**
     * 保存对话框的<b>扩展名档</b>就是格式选择器（CSV / TSV / JSON / XLSX / XML / HTML），
     * 文件名后缀跟着同一档换 —— 不在界面上再搭一层下拉，让同一个决定有两个入口、
     * 两处各记一个默认值。
     *
     * <p>写盘落在后台线程：旧的非分页导出是在 FX 线程上 {@code Files.writeString}，
     * 结果集大时手感直接冻住；现在与分页导出同一条 {@link #submit} 通道。
     */
    private void chooseAndExport(String baseName, ExportTarget target) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("query.exportTitle"));
        chooser.setInitialFileName(baseName);
        List<FileChooser.ExtensionFilter> filters = ResultExport.filters();
        chooser.getExtensionFilters().setAll(filters);
        chooser.setSelectedExtensionFilter(filters.getFirst());
        Window window = getScene() == null ? null : getScene().getWindow();
        File picked = chooser.showSaveDialog(window);
        if (picked == null) {
            return;
        }
        ExportFormat format = ResultExport.formatOf(chooser.getSelectedExtensionFilter());
        java.nio.file.Path destination = ResultExport.withExtension(picked.toPath(), format);
        Task<Long> task = new Task<>() {
            @Override
            protected Long call() throws Exception {
                if (format.binary()) {
                    try (OutputStream out = Files.newOutputStream(destination)) {
                        return target.writeXlsx(out);
                    }
                }
                try (Writer out = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
                    return target.write(format, out);
                }
            }
        };
        String fileName = destination.getFileName().toString();
        task.setOnSucceeded(event -> {
            String message = I18n.t("query.exportedRows", fileName,
                    String.valueOf(task.getValue()), format.label());
            status.setText(message);
            feedback.status(I18n.t("query.exportedFeedback"));
        });
        task.setOnFailed(event -> {
            String message = I18n.t("query.exportFailed", readable(task.getException()));
            status.setText(message);
            feedback.status(message);
        });
        submit(task);
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
        disposed = true;
        completions.close();
        if (running) {
            cancelled = true;
            session.cancelCurrent();
        }
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
        // ⭐ R58-c：结果区那条保存也是后台任务 —— 页签关了不能让它继续往库里写半批。
        if (saveInFlight != null) {
            saveInFlight.cancel(true);
            saveInFlight = null;
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
        return ErrorText.readable(error);
    }

    /**
     * ⭐ R58-c：判定说这一屏可改之后，非主键的格子能双击进编辑 —— 但
     * <b>值只进缓冲，SQL 由模型层生成</b>：这里只认「这一格对表里的哪一列」，
     * 缓冲是 {@link EditBuffer}，写回语句交给 {@link EditSqlBuilder#plan}，
     * 连「这一行的主键值怎么取」都用 {@link ResultEditState#identity} 那一份。
     *
     * <p>为什么不是静态内部类：一次改动落地要刷新<b>这一屏自己</b>的计数与保存键，
     * 而状态条挂在页上 —— 它们都在 {@link ResultEditState} 和外层类里。
     */
    private final class ResultCell
            extends TableCell<java.util.Map<String, Object>, Object> {

        private final ColumnDef definition;
        private final ResultEditState edit;
        private final String key;

        private TextField editor;

        private ResultCell(ColumnDef definition, ResultEditState edit, String key) {
            this.definition = definition;
            this.edit = edit;
            this.key = key;
        }

        /** 这一格在判定结论里的目标；判定还没回来或键对不上给 {@code null}。 */
        private ResultEditPolicy.Cell target() {
            return edit.cell(key);
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(RESULT_CELL_STYLES);
            setTooltip(null);
            if (empty) {
                setText(null);
                setGraphic(null);
                return;
            }
            java.util.Map<String, Object> row =
                    getTableRow() == null ? null : getTableRow().getItem();
            Object value = item;
            ResultEditPolicy.Cell cell = target();
            RowIdentity identity = edit.identity(row);
            // 缓冲里有这一格的改动 → 屏幕上显示的是那一份，并钉上「待保存」样式。
            // 库里现在的值要等保存后重取才回来，这里不许拿缓冲冒充落库结果。
            boolean pending = cell != null && identity != null && edit.buffer != null
                    && edit.buffer.hasCell(identity, cell.column());
            if (pending) {
                value = edit.buffer.valueOr(identity, cell.column(), item);
                getStyleClass().add("adb-grid__cell--dirty");
            }
            CellRenderer.Rendered rendered = CellRenderer.render(definition, value);
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

        @Override
        public void startEdit() {
            // ⭐ 三道闸，缺一不可：网格与列都说能编辑（列的可编辑位由判定落地时才置上）、
            // 这一格对得上表里的列、且不是主键 —— 改主键等于把定位这一行的条件挪走。
            ResultEditPolicy.Cell cell = target();
            if (cell == null || !isEditable() || !getTableColumn().isEditable()
                    || isEmpty() || getTableRow() == null || getTableRow().getItem() == null
                    || cell.primaryKey() || edit.buffer == null) {
                return;
            }
            java.util.Map<String, Object> row = getTableRow().getItem();
            RowIdentity identity = edit.identity(row);
            // 编辑框里给的是「这一格现在屏幕上显示的值」——缓冲改过就用改过的，
            // 不然用户看到的和点进去的会是两个数。
            Object current = identity == null ? row.get(key)
                    : edit.buffer.valueOr(identity, cell.column(), row.get(key));
            super.startEdit();
            editor = new TextField(DataGridPage.editorText(current));
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
            getStyleClass().add("adb-grid__cell--editing");
            Platform.runLater(() -> {
                // 编辑可能在这次 runLater 之前就撤掉了（双击后立刻 Esc / 焦点被抢）。
                if (editor != null) {
                    editor.selectAll();
                    editor.requestFocus();
                }
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
            ResultEditPolicy.Cell cell = target();
            java.util.Map<String, Object> row = getTableRow().getItem();
            RowIdentity identity = edit.identity(row);
            if (cell == null || identity == null || edit.buffer == null) {
                cancelEdit();
                return;
            }
            try {
                // 解析与非空判定用的是**表侧**的列定义（{@code Cell.definition()}）：
                // 结果集那份是驱动给的，别名、表达式与类型宽度都可能和表里的不一样，
                // 而写回时吃这个值的正是表里那一列。
                Object next = DataGridPage.parseCellValue(cell.definition(), editor.getText());
                if (next == null && !cell.definition().nullable()) {
                    throw new IllegalArgumentException(I18n.t("grid.batch.notNull", definition.name()));
                }
                cancelEdit();
                // 原值取行 Map 里的那一份：缓冲按「改了才算」计数，改回原值等于没改。
                if (edit.buffer.setCell(identity, cell.column(), row.get(key), next)) {
                    feedback.status(I18n.t("grid.msg.cellModified", definition.name(),
                            CellValueParser.display(next)));
                }
                updateResultEdits(edit);
                edit.table.refresh();
            } catch (IllegalArgumentException error) {
                // 解析不过就把值留在编辑框里并标红 —— 静默丢掉用户刚打的字比报错更糟。
                if (!editor.getStyleClass().contains("adb-grid__editor--error")) {
                    editor.getStyleClass().add("adb-grid__editor--error");
                }
                editor.setTooltip(new Tooltip(error.getMessage()));
                feedback.status(I18n.t("grid.msg.invalidValue", error.getMessage()));
            }
        }
    }

}
