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
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.ui.Icons;
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
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
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
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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

        /**
         * ⭐ UX-5-D：这一页「当前正在看的那条语句」。
         *
         * <p>数据页给的是本页的分页 SQL，查询页给的是刚执行的那段文本。
         * null / 空白 = 这一页没有语句（表清单、对象首页），状态栏整段隐藏。
         */
        default void sql(String statement) {
        }

        /**
         * ⭐ UX-5-D：最后一次取数成功的时刻（epoch 毫秒）。
         *
         * <p>耗时回答的是「这次跑了多久」，回答不了「这屏数据多旧」。
         * ≤0 = 还没取过数 → 状态栏整段隐藏。
         */
        default void refreshedAt(long epochMillis) {
        }

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
    /** ⭐ §6.1：对象标题栏（类型图标 · 名字 · 类型 · 行数 · 动作）。 */
    private ObjectHeader objectHeader;
    /** ⭐ §6.2：对象功能切面（数据 / 结构 / 索引 / 外键 / 触发器 / SQL）。 */
    private ObjectTabBar objectTabs;
    /** 对象切面内容（结构 / 索引 / 外键 / 触发器 / SQL 的真实内省结果）。 */
    private ObjectSectionsPanel sectionsPanel;
    /** 数据切面与其它切面共用的容器。 */
    private final StackPane sectionStack = new StackPane();
    /** ⭐ UX-6：数据切面（工具栏 + 网格 + 分页器）。 */
    private VBox dataSection;
    /** ⭐ UX-6：概览切面（事实 + 最近操作 + 快捷动作）。 */
    private VBox overviewPane;
    private final Label overviewRows = new Label("—");
    private final Label overviewSize = new Label("—");
    private final Label overviewEngine = new Label("—");
    private final Label overviewIndexes = new Label("—");
    /** ⭐ U17：表档案补齐的三项 —— 全部来自已内省到的表结构，不额外查库。 */
    private final Label overviewColumns = new Label("—");
    private final Label overviewPrimary = new Label("—");
    private final Label overviewAutoIncrement = new Label("—");
    private final Label overviewHint = new Label();
    private final VBox overviewRecent = new VBox(4);
    /** 概览的元信息只读一次；表大小会变，由用户点「刷新概览」或数据刷新来更新。 */
    private boolean overviewLoaded;
    private boolean overviewLoading;
    /** 工具栏左组（数据操作）。 */
    private final HBox toolGroupData = new HBox(4);
    /** 工具栏中组（查询与显示）。 */
    private final HBox toolGroupQuery = new HBox(4);
    /** ⭐ UX-3：工具栏数据交换组（导入 / 导出）。 */
    private final HBox toolGroupTransfer = new HBox(4);
    /**
     * ⭐ UX-3：工具条上的「图标 + 文字」主操作 —— 按钮 -&gt; 完整文字。
     *
     * <p>收起来时要清空文字、展开时要复原，靠这张表记住原文，
     * 不在按钮上塞自定义属性。
     */
    private final Map<ButtonBase, String> toolbarLabels = new LinkedHashMap<>();
    /** ⭐ U4：工具条本体要参与「放不放得下文字」的判断，不能只当局部变量。 */
    private final HBox toolbar = new HBox(8);
    /** 工具条当前是否显示文字；null = 还没量过窗口宽度。 */
    private Boolean toolbarTextVisible;
    /** U4：窗口宽度这一档（侧栏收起、边距吃掉的宽度由工具条实测宽度负责）。 */
    private boolean windowAllowsLabels = true;
    /** 正在监听的窗口宽度（换窗口时必须摘掉旧的监听）。 */
    private ObservableValue<? extends Number> boundWindowWidth;
    private final ChangeListener<Number> windowWidthListener =
            (observable, oldValue, newValue) -> {
                windowAllowsLabels = newValue.doubleValue() >= TOOLBAR_LABEL_MIN_WIDTH;
                applyToolbarLabels();
            };
    /** 工具条宽度一变就重判「这一排文字到底放不放得下」。 */
    private final ChangeListener<Number> toolbarWidthListener =
            (observable, oldValue, newValue) -> applyToolbarLabels();
    /** 删除选中行（工具栏左组的危险操作）。 */
    private final Button deleteRowsButton = IconButtons.of(Icons.Icon.DELETE, "删除记录");
    private final VBox stateBox = new VBox(10);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label stateText = new Label();
    private final Button retry = IconButtons.of(Icons.Icon.REFRESH, "重试");
    private final Label rangeLabel = new Label("—");
    private final Label pageLabel = new Label("—");
    private final Button previous = IconButtons.icon(Icons.Icon.PREVIOUS, "上一页");
    private final Button next = IconButtons.icon(Icons.Icon.NEXT, "下一页");
    private final Button firstPageButton = IconButtons.icon(Icons.Icon.FIRST, "首页");
    private final Button lastPageButton = IconButtons.icon(Icons.Icon.LAST, "末页");
    private final TextField jumpField = new TextField();
    /** ⭐ G7：行级导航（跳到第 N 行）。 */
    private final TextField jumpRowField = new TextField();
    private final ComboBox<Integer> pageSizeBox = new ComboBox<>();
    private final Button filterButton = IconButtons.icon(Icons.Icon.FILTER, "筛选");
    private final Button clearQueryButton = IconButtons.icon(Icons.Icon.CLEAR, "清除条件");
    private final Button undoButton = IconButtons.icon(Icons.Icon.UNDO, "撤销");
    private final Button redoButton = IconButtons.icon(Icons.Icon.REDO, "重做");
    private final Button saveButton = IconButtons.of(Icons.Icon.SAVE, "保存");
    private final Button addRowButton = IconButtons.icon(Icons.Icon.ADD, "新增行");
    /**
     * ⭐ G2：事务三键。「开始事务」常驻，「提交 / 回滚」只在事务中现身 ——
     * 这两颗按钮<b>在不在</b>本身就是事务状态的显示器（跟本页「删除记录」同一套可见性口径）。
     */
    private final Button beginTxButton = IconButtons.icon(Icons.Icon.LOCK, "开始事务");
    private final Button commitTxButton = IconButtons.icon(Icons.Icon.CHECK,
            "提交事务：本事务内的全部写入落库，之后回到自动提交");
    private final Button rollbackTxButton = IconButtons.icon(Icons.Icon.CLOSE,
            "回滚事务：丢弃本事务内尚未提交的全部写入（含网格里未保存的编辑）");
    private final Button columnsButton = IconButtons.icon(Icons.Icon.COLUMNS, "列设置");
    /** ⭐ 列设置弹层（带列名搜索）。 */
    private ColumnSettingsPanel columnPanel;
    private final MenuButton viewButton = IconButtons.iconMenu(new MenuButton(), Icons.Icon.VIEW, "视图");
    private final MenuButton exportButton = IconButtons.iconMenu(new MenuButton(), Icons.Icon.EXPORT, "导出");
    /** ⭐ UX-3：数据页直接导入（目标表预选本表），不用再绕侧栏的「导入 / 导出」。 */
    private final Button importButton = new Button();
    /** ⭐ P2-3：按本表筛过的执行历史，点一条载入查询页。 */
    private final MenuButton historyButton =
            IconButtons.iconMenu(new MenuButton(), Icons.Icon.HISTORY, "历史");
    private java.util.function.Supplier<List<QueryHistory.Entry>> historySource = List::of;
    private Consumer<String> onHistoryPick = sql -> { };
    private final TextField searchField = new TextField();
    // ── G3：网格查找 / 替换条（Navicat 数据编辑器的 Ctrl+F 形态）──
    //    与「本页搜索」分工：那个是行过滤（输入即筛行），这个是逐格查找 + 替换。
    private final HBox findBar = new HBox(6);
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    /** 区分大小写：默认关 —— 与 Navicat 及主流编辑器一致（要精确匹配再勾上）。 */
    private final CheckBox findCaseBox = new CheckBox("区分大小写");
    private final Button findNextButton = new Button("下一个");
    private final Button replaceOneButton = new Button("替换");
    private final Button replaceAllButton = new Button("全部替换");
    private final CheckMenuItem hideNullItem = new CheckMenuItem("隐藏含 NULL 行");
    private final CheckMenuItem hideEmptyItem = new CheckMenuItem("隐藏含空串行");
    private final CheckMenuItem transposeItem = new CheckMenuItem("转置（只读）");
    /** ⭐ G3：本页统计（行数与数值列的汇总）。 */
    private final CheckMenuItem summaryItem = new CheckMenuItem("本页统计");
    private final Label summaryLabel = new Label();
    private final TableView<Map<String, Object>> transposeTable = new TableView<>();
    private final CellDetailsDrawer detailsDrawer = new CellDetailsDrawer();
    /** 抽屉当前展示／编辑的是哪一格（应用编辑时写回这里）。 */
    private Map<String, Object> detailsRow;
    private ColumnDef detailsDefinition;
    private final FlowPane chipBar = new FlowPane(6, 6);
    private final HBox batchBar = new HBox(8);
    /** ⭐ 记录导航条（Navicat 形态）：首 / 上 / 下 / 末 + 当前行号。 */
    private final HBox recordNav = new HBox(4);
    /**
     * ⭐ UX-4：贴在网格左下的**行操作条**（+ / − / 应用 / 取消）。
     *
     * <p>Navicat 改数据时手一直在表格和这一排之间来回，工具条在最上面要抬头；
     * 这一排贴着光标走。四个按钮的可用态**绑定**到工具条同一批按钮上，
     * 不在这里重写一遍判断 —— 两处状态不一致比少一排按钮更糟。
     */
    private final HBox rowActions = new HBox(4);
    private final Button rowAddButton = IconButtons.icon(Icons.Icon.ADD, "新增行");
    private final Button rowDeleteButton = IconButtons.icon(Icons.Icon.DELETE, "删除选中行");
    private final Button rowApplyButton = IconButtons.icon(Icons.Icon.SAVE, "应用修改");
    private final Button rowCancelButton = IconButtons.icon(Icons.Icon.CLOSE, "放弃全部修改");
    private final Label recordPosition = new Label("—");
    private final Label batchLabel = new Label();
    private final Button batchNull = new Button("设 NULL");
    private final Button batchEmpty = new Button("设空串");
    private final Button batchDelete = IconButtons.of(Icons.Icon.DELETE, "删除");
    private final Button batchClone = IconButtons.of(Icons.Icon.COPY, "克隆");
    private final CheckBox selectAllBox = new CheckBox();
    private final Map<String, HeaderView> headers = new LinkedHashMap<>();
    /** ⭐ G2：正在被拖动的列。 */
    private TableColumn<Map<String, Object>, ?> draggedColumn;
    /** 拖动起点（场景坐标）与累计位移。 */
    private double dragStartSceneX;
    private double dragLastSceneX;
    /** 拖动超过这个位移就算「拖」而不是「点」—— 点列头是排序。 */
    private static final double DRAG_THRESHOLD = 6;
    /**
     * ⭐ UX-3：窗口窄于这个宽度时，工具条主操作退回「图标 + tooltip」。
     *
     * <p>量的是**窗口**而不是页面：侧栏收起会让页面变宽，但用户能一眼扫完的
     * 工具条宽度取决于整窗给多少地方 —— 1100px 以下文字会开始互相挤。
     */
    static final double TOOLBAR_LABEL_MIN_WIDTH = 1100;

    // ─────────────── G4：单元格区域选择 ───────────────
    /**
     * ⭐ G4：矩形选区。列下标是「可见数据列」的下标（行号列 / 选择列不算），
     * 行下标是当前页 {@code items} 的下标 —— 都取用户眼睛看到的坐标，
     * 不被「列被隐藏」「列被拖到别处」这些内部顺序干扰。
     */
    private GridRectangle rectangle;
    /** 拖选的锚点（矩形的起点）。 */
    private GridCell rectangleAnchor;
    /** 是否正在拖选（决定鼠标拖动要不要扩展矩形）。 */
    private boolean rectangleDragging;

    /**
     * ⭐ UX-4：当前格（键盘光标）= 行下标 + **列名**。
     *
     * <p>列按名字记，不按 {@code TableColumn} 实例记：冻结区的列是从右表**搬过去**的
     * 同一批实例（见 {@code applyFrozenColumns}），搬走之后
     * {@code grid.getVisibleLeafColumns()} 里就没有它了 —— 按下标比会让冻结区的光标标不上。
     *
     * <p>{@code cursorRow < 0} = 还没有光标。
     */
    private int cursorRow = -1;
    private String cursorColumnName;

    /** 一个单元格坐标（行 + 可见数据列下标）。 */
    private record GridCell(int row, int column) {
    }

    /** 一个矩形选区（两个对角即可，取 min/max 消掉方向）。 */
    private record GridRectangle(int fromRow, int fromColumn, int toRow, int toColumn) {
        private int minRow() {
            return Math.min(fromRow, toRow);
        }

        private int maxRow() {
            return Math.max(fromRow, toRow);
        }

        private int minColumn() {
            return Math.min(fromColumn, toColumn);
        }

        private int maxColumn() {
            return Math.max(fromColumn, toColumn);
        }

        private int rowCount() {
            return maxRow() - minRow() + 1;
        }

        private int columnCount() {
            return maxColumn() - minColumn() + 1;
        }

        private boolean isSingleCell() {
            return rowCount() == 1 && columnCount() == 1;
        }

        private boolean contains(int row, int column) {
            return row >= minRow() && row <= maxRow()
                    && column >= minColumn() && column <= maxColumn();
        }
    }

    private final Map<String, TableColumn<Map<String, Object>, Object>> columnByDefinition =
            new LinkedHashMap<>();
    private final EditBuffer editBuffer;
    private final SettingsStore settings;
    private IntConsumer dirtyListener = count -> { };
    private javafx.animation.PauseTransition layoutSaveTimer;

    private record HeaderView(Label name, Label sortBadge, Button filter) {
    }

    // ─────────────── G1：冻结列 ───────────────
    /**
     * ⭐ G1：冻结在左侧的**数据列**个数（不含选择列与行号列，那两列天然跟着冻结）。
     * 0 = 不冻结 —— 此时左表整体隐藏，布局和以前完全一样。
     */
    private int frozenColumnCount;
    /** 左半张表：选择列 + 行号列 + 前 N 个数据列。 */
    private final TableView<Map<String, Object>> frozenGrid = new TableView<>();
    /** 两张表并排的容器（左表不冻结时宽度为 0）。 */
    private final HBox gridRow = new HBox();
    /** 垂直滚动是否已绑定 —— skin 就绪后才拿得到滚动条，只能绑一次。 */
    private boolean scrollBound;
    /** 选择同步中的防循环标志（两表互相监听，不加会无限来回）。 */
    private boolean syncingSelection;
    /** 最近一次由列头右键处理器建出来的菜单（测试用它检查菜单内容）。 */
    private javafx.scene.control.ContextMenu lastHeaderMenu;

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
    /** ⭐ G2：本连接是否处于用户显式事务中（提交或回滚之前一直为真）。 */
    private boolean transactionOpen;
    /** 事务指令（开始 / 提交 / 回滚）在途 —— 防连点，也与保存、刷新互斥。 */
    private boolean txCommandInFlight;
    private boolean activated;
    private boolean hideNullRows;
    private boolean hideEmptyRows;
    /** ⭐ G3：是否显示本页统计。 */
    private boolean summaryVisible;
    private boolean transpose;
    private EditSqlBuilder.Plan saveUndoPlan;
    private String lastSql = "";
    /** ⭐ UX-5-D：最后一次取数成功的时刻（epoch 毫秒）；-1 = 还没取过。 */
    private long lastRefreshedAt = -1;
    /** ⭐ 最近一次复制出去的文本。headless 下单测读不回系统剪贴板，门禁断言这个。 */
    private String lastCopiedText = "";
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
            // ⭐ 编辑长文本时按 Esc = 放弃这次编辑，不是把整个抽屉关掉。
            if (event.getCode() == KeyCode.ESCAPE && detailsDrawer.isEditing()) {
                detailsDrawer.cancelEditing();
                event.consume();
                return;
            }
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
            // ⭐ G3：Esc 关闭查找条 —— 必须拦在「目标是输入框就早退」之前：
            //    查找/替换框本身就是输入框，不在这之前拦，Esc 永远落不到关闭上。
            if (event.getCode() == KeyCode.ESCAPE && findBar.isVisible()
                    && (findField.isFocused() || replaceField.isFocused())) {
                hideFindBar();
                event.consume();
                return;
            }
            // ⭐ G3：F3 = 查找下一处（查找条开着时；焦点在哪个输入框里都生效）。
            if (event.getCode() == KeyCode.F3 && findBar.isVisible()) {
                findNext();
                event.consume();
                return;
            }
            // ⭐ G4：Esc 取消矩形选区（在输入框里按 Esc 不该动选区，所以放在上面那条之后）
            if (event.getCode() == KeyCode.ESCAPE && hasRectangleSelection()) {
                clearRectangleSelection();
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
                // ⭐ G3：⌘F 打开网格查找条（Navicat 数据编辑器口径）。
                //    原来聚焦的是「本页搜索」过滤框 —— 那个框常驻工具条、不缺入口，
                //    而查找/替换此前在网格里根本没有入口。
                showFindBar();
                event.consume();
            } else if (event.isShortcutDown() && !event.isShiftDown() && !event.isAltDown()
                    && event.getCode() == KeyCode.C) {
                // ⌘C：框了区域就复制这块区域（Excel 的直觉），没框才复制当前单元格
                if (hasRectangleSelection()) {
                    copyRectangleAsTsv();
                } else {
                    copyFocusedCellValue();
                }
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
            } else if (event.isShortcutDown() && !event.isShiftDown() && !event.isAltDown()
                    && event.getCode() == KeyCode.DOWN) {
                // ⌘↓ 跳到当前页最后一行（G7：行级导航）
                selectRowIndex(grid.getItems().size() - 1);
                event.consume();
            } else if (event.isShortcutDown() && !event.isShiftDown() && !event.isAltDown()
                    && event.getCode() == KeyCode.UP) {
                // ⌘↑ 跳到当前页第一行
                selectRowIndex(0);
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
        setToolbarLabel(saveButton, editBuffer.isDirty()
                ? "保存 " + editBuffer.dirtyCount() : "保存");
        addRowButton.setDisable(!editable || loading || saving);
        // ⭐ G3：替换两键跟「能否写」走 —— 只读表/视图/载入中都点不动（查找不受限）。
        replaceOneButton.setDisable(!editable || loading || saving);
        replaceAllButton.setDisable(!editable || loading || saving);
        // ⭐ 只读表**整排行操作条消失** —— 一排永远点不动的按钮
        //    只会让人反复去试，而这一排贴着光标，碍事程度高于工具条。
        rowActions.setVisible(editable);
        rowActions.setManaged(editable);
        updateSelectAllState();
        updateDeleteButton();
        applyTransactionControls();
        if (objectHeader != null) {
            objectHeader.setContext(connectionName + (editable ? " · 可编辑" : " · 只读"));
        }
        if (tableSchema != null) {
            feedback.readonly(!editable);
        }
    }

    /** 删除按钮只有在「有选中行 + 表可编辑 + 不在加载/保存中」时才可用。 */
    private void updateDeleteButton() {
        boolean editable = tableSchema != null && tableSchema.editable();
        boolean hasSelection = !grid.getSelectionModel().getSelectedItems().isEmpty();
        deleteRowsButton.setDisable(!editable || loading || saving || !hasSelection);
        // 没有可删的行时按钮**整颗消失**（而不是灰着占位）。
        deleteRowsButton.setVisible(hasSelection);
        deleteRowsButton.setManaged(hasSelection);
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
        setToolbarLabel(saveButton, "保存中…");
        // ⭐ V-4：保存期间图标换成转圈，且**按钮宽度不变**（否则整排工具栏会往左抽）。
        IconButtons.loading(saveButton, true);
        saving = true;
        grid.setDisable(true);
        // 4.5-11：状态栏的「事务」段接真实数据 —— 保存跑在事务里（用户开的，或这条保存自己的）。
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
            IconButtons.loading(saveButton, false);
            grid.setDisable(false);
            // ⚠️ 不能无条件报「自动提交」：用户开了事务时保存只是写进事务，事务还开着。
            feedback.transaction(transactionOpen);
            // 事务中不留反向 SQL 撤销计划（撤销靠回滚，见 undoLastSave）。
            saveUndoPlan = transactionOpen ? null : inverse;
            editBuffer.clear();
            updateDirty();
            feedback.status((transactionOpen ? "已写入事务 " : "已保存 ")
                    + plan.size() + " 条变更 · 影响 " + affected + " 行"
                    + (transactionOpen ? " · 未提交" : ""));
            if (transactionOpen) {
                feedback.toast("已写入事务 · 点「提交」才落库，点「回滚」整体撤回");
            } else if (!inverse.isEmpty() && inverse.complete()) {
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
            IconButtons.loading(saveButton, false);
            grid.setDisable(false);
            feedback.transaction(transactionOpen);
            updateEditControls();
            // ⭐ V-12：错误归类 —— 给「能照着做」的说明，而不是驱动的原始英文。
            String reason = io.agenticdb.safety.ErrorClassifier
                    .classify(task.getException()).message();
            feedback.status("保存失败：" + reason);
            showErrorDetails("保存失败", task.getException(), plan.preview());
        });
        dbExecutor.execute(task);
    }

    /**
     * Toast 上的“撤销保存”（编辑菜单共用）：执行保存前生成的反向 SQL，再刷新当前页。
     *
     * <p>⚠️ 反向 SQL 只在<b>自动提交</b>模式下有意义：它靠再发一批 UPDATE/DELETE 把数据
     * 改回去。事务打开时「撤销」的正确手段是回滚整个事务（{@link #rollbackTransaction()}），
     * 所以在事务中直接拒绝这条路径 —— 否则会出现「反向 SQL 和未提交写入混在一个事务里、
     * 用户以为撤销了、其实一提交全落库」。
     */
    public void undoLastSave() {
        EditSqlBuilder.Plan inverse = saveUndoPlan;
        if (inverse == null || inverse.isEmpty() || undoingSave || saving) {
            return;
        }
        if (transactionOpen) {
            feedback.status("事务中不能用「撤销上次保存」—— 请点「回滚」整体撤回本事务");
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
            showErrorDetails("撤销保存失败", task.getException(), inverse.preview());
        });
        dbExecutor.execute(task);
    }

    // ─────────────────────────── 事务（G2）───────────────────────────

    /** 本连接当前是否处于用户显式事务中（供门禁与外壳读取）。 */
    public boolean transactionOpen() {
        return transactionOpen;
    }

    /** 开始事务：之后的保存只写进事务，点「提交」才落库。 */
    private void beginTransaction() {
        runTransactionCommand("开始事务", () -> {
            session.begin();
            return session.inTransaction();
        }, inTx -> {
            transactionOpen = Boolean.TRUE.equals(inTx);
            applyTransactionControls();
            feedback.transaction(transactionOpen);
            feedback.status(transactionOpen
                    ? "事务已开始 · 之后的保存只写入不提交，点「提交」才落库"
                    : "该数据库不支持显式事务");
        });
    }

    /** 提交：事务内的写入落库，回到自动提交。 */
    private void commitTransaction() {
        runTransactionCommand("提交事务", () -> {
            session.commit();
            return session.inTransaction();
        }, inTx -> {
            transactionOpen = Boolean.TRUE.equals(inTx);
            saveUndoPlan = null;
            applyTransactionControls();
            feedback.transaction(transactionOpen);
            feedback.status("事务已提交 · 本事务内的写入已落库");
            feedback.toast("事务已提交");
        });
    }

    /** 回滚前的确认弹窗（不显示；抽出来是为了让门禁断言文案与按钮序，不用真弹模态）。 */
    Dialog<ButtonType> buildRollbackDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("回滚事务");
        ButtonType rollback = new ButtonType("回滚", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(rollback, cancel);
        dialog.getDialogPane().setHeader(new Label("本事务里尚未提交的写入会全部撤销，"
                + "网格里未保存的编辑也会一起清空 —— 这一步不能恢复。"));
        dialog.setResultConverter(type -> type);
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
        dialog.setOnShown(event -> DestructiveConfirm.pinCancelAsDefault(
                dialog.getDialogPane(), cancel, rollback));
        return dialog;
    }

    private boolean confirmRollbackTransaction() {
        Dialog<ButtonType> dialog = buildRollbackDialog();
        ButtonType rollback = dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> "回滚".equals(type.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("回滚确认弹窗缺少「回滚」按钮"));
        return dialog.showAndWait().filter(rollback::equals).isPresent();
    }

    /**
     * 回滚：丢弃本事务里全部未提交的写入。
     *
     * <p>⚠️ 缓冲必须一起清 —— 已经保存进事务的那些改动，回滚后库里根本没有；
     * 留着它们，网格就会显示一批「数据库里不存在」的编辑，再保存等于凭空造数据。
     */
    void rollbackTransaction() {
        runTransactionCommand("回滚事务", () -> {
            session.rollback();
            return session.inTransaction();
        }, inTx -> {
            transactionOpen = Boolean.TRUE.equals(inTx);
            editBuffer.clear();
            saveUndoPlan = null;
            applyTransactionControls();
            feedback.transaction(transactionOpen);
            updateDirty();
            feedback.status("事务已回滚 · 未提交的写入全部撤销");
            feedback.toast("事务已回滚");
            load(true);
        });
    }

    /**
     * 三条事务指令的统一跑法：串行在 {@code dbExecutor} 上（同一连接的事务必须串行），
     * 与加载 / 保存 / 彼此互斥，跑完回 FX 线程更新状态。
     *
     * @param jdbcCall 真正动会话的那一步，返回指令执行<b>之后</b>会话是否仍在事务中
     */
    private void runTransactionCommand(String action,
                                       java.util.concurrent.Callable<Boolean> jdbcCall,
                                       java.util.function.Consumer<Boolean> onDone) {
        if (txCommandInFlight || loading || saving) {
            return;
        }
        txCommandInFlight = true;
        applyTransactionControls();
        feedback.status(action + "中…");
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                return jdbcCall.call();
            }
        };
        task.setOnSucceeded(event -> {
            txCommandInFlight = false;
            onDone.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            txCommandInFlight = false;
            applyTransactionControls();
            feedback.status(action + "失败：" + readable(task.getException()));
            showErrorDetails(action + "失败", task.getException(), null);
        });
        dbExecutor.execute(task);
    }

    /**
     * 事务三键的可见性 / 可用性 —— 由 {@link #updateEditControls()} 统一驱动，
     * 这样「表变只读」「正在加载」「指令在途」这些状态不需要各自再记一份账。
     *
     * <p>「开始事务」在事务中<b>整颗消失</b>而不是灰着摆那儿：它在事务里已经无事可做，
     * 而「提交 / 回滚」现身就是状态本身 —— 与本页「删除记录」的可见性口径一致。
     */
    private void applyTransactionControls() {
        boolean editable = tableSchema != null && tableSchema.editable();
        boolean busy = loading || saving || txCommandInFlight;
        beginTxButton.setDisable(!editable || transactionOpen || busy);
        beginTxButton.setVisible(!transactionOpen);
        beginTxButton.setManaged(!transactionOpen);
        commitTxButton.setDisable(busy);
        rollbackTxButton.setDisable(busy);
        commitTxButton.setVisible(transactionOpen);
        commitTxButton.setManaged(transactionOpen);
        rollbackTxButton.setVisible(transactionOpen);
        rollbackTxButton.setManaged(transactionOpen);
    }

    /**
     * 向会话核对真实的事务状态（切回本页标签时调用）。
     *
     * <p>事务的作用域是<b>连接</b>：同一连接上的查询页、结构页、另一个数据页标签
     * 都能把本页没看见的事务开起来或提交掉。本页那个布尔只是缓存，不核对就会出现
     * 「事务中」三个字挂着、按钮全点不动的死局。
     */
    private void refreshTransactionState() {
        if (session == null || txCommandInFlight || loading || saving) {
            return;
        }
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                try {
                    return session.inTransaction();
                } catch (Exception e) {
                    // 读不到就维持原状 —— 把界面跳到「未知」比停在旧值更糟。
                    return transactionOpen;
                }
            }
        };
        task.setOnSucceeded(event -> {
            boolean open = Boolean.TRUE.equals(task.getValue());
            if (open == transactionOpen) {
                return;
            }
            transactionOpen = open;
            applyTransactionControls();
            feedback.transaction(open);
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
                    transactionOpen
                            ? "以下 SQL 写进「当前事务」：执行后仍不落库，点「提交」才生效，点「回滚」整体撤回。\n\n"
                            + plan.preview()
                            : "以下 SQL 会在单个事务里执行，任一条失败整体回滚。\n\n"
                            + plan.preview(),
                    "继续");
            // ⭐ 与本页保存预览同一套皮肤：确认框漏掉主题类时，它是满屏 Modena 灰。
            DialogStyles.apply(confirm.alert(), getScene() == null ? null : getScene().getWindow());
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
        dialog.setHeaderText(plan.size() + " 条变更 · "
                + (transactionOpen ? "写入当前事务 · 提交后才落库" : "单事务执行 · 失败回滚")
                + " · 风险：" + danger.label());
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
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空。
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
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

    /**
     * ⭐ P2-2：失败不再只弹一句 message —— 分类徽标 + 驱动原文 + 建议 + SQL，
     * 全部可一键复制。{@code sql} 可为 null（导出这类与语句无关的失败）。
     */
    private void showErrorDetails(String title, Throwable error, String sql) {
        ErrorDetailsDialog.show(getScene() == null ? null : getScene().getWindow(),
                ErrorDetails.of(title, error, sql));
    }

    // ─────────────────────────── 结构 ───────────────────────────

    private void buildUi() {
        // ── ① 对象标题栏（§6.1）────────────────────────────────────────
        //    类型图标 + 对象名 + 类型 + 真实行数 + 与本对象直接相关的动作。
        //    行数在第一次 COUNT 之前显示「行数未知」—— 绝不编数字。
        objectHeader = new ObjectHeader(Icons.Icon.TABLE, tableName, "表");
        objectHeader.setContext(connectionName);
        objectHeader.setCount(null);

        Button refresh = IconButtons.icon(Icons.Icon.REFRESH, "重新统计总数并拉取当前页 (F5)");
        refresh.setId("data-refresh");
        refresh.getStyleClass().addAll("adb-button", "adb-button--icon");
        refresh.setTooltip(new Tooltip("重新统计总数并拉取当前页 (F5)"));
        refresh.setOnAction(event -> refresh());

        Button structure = IconButtons.icon(Icons.Icon.STRUCTURE, "打开表结构");
        structure.setId("data-structure");
        structure.getStyleClass().addAll("adb-button", "adb-button--icon");
        structure.setAccessibleText("打开表结构");
        structure.setTooltip(new Tooltip("在结构编辑器中打开这张表"));
        structure.setOnAction(event -> openStructure.accept(tableName));

        objectHeader.addActions(refresh, structure);

        // ── ② 对象功能切面（§6.2）──────────────────────────────────────
        objectTabs = new ObjectTabBar();
        objectTabs.addTab("data", "数据");
        objectTabs.addTab("overview", "概览");
        objectTabs.addTab(ObjectSectionsPanel.SECTION_COLUMNS, "结构");
        objectTabs.addTab(ObjectSectionsPanel.SECTION_INDEXES, "索引");
        objectTabs.addTab(ObjectSectionsPanel.SECTION_FOREIGN_KEYS, "外键");
        objectTabs.addTab(ObjectSectionsPanel.SECTION_TRIGGERS, "触发器");
        objectTabs.addTab(ObjectSectionsPanel.SECTION_SQL, "SQL");
        objectTabs.setOnSelect(this::sectionSelected);
        objectTabs.setSelected("data");
        // ⭐ 切面条并进标题栏同一行 —— 单独占一行会把「第一条数据」
        //    推到窗口的 1/3 处（实测：284px chrome 里它占了 29px）。
        objectHeader.setTabs(objectTabs);

        sectionsPanel = new ObjectSectionsPanel(session, schemaService, tableName,
                dbExecutor, () -> openStructure.accept(tableName));
        overviewPane = buildOverview();

        // ── ③ 数据工具栏（§6.3）────────────────────────────────────────
        //    左组 = 数据操作；右组 = 查询与显示。两组之间留白分隔，
        //    搜索框贴着右边缘 —— 视线从左到右是「改数据 → 看数据」。
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setId("data-toolbar");

        toolGroupData.setAlignment(Pos.CENTER_LEFT);
        toolGroupData.getStyleClass().add("adb-toolbar__group");
        toolGroupQuery.setAlignment(Pos.CENTER_LEFT);
        toolGroupQuery.getStyleClass().add("adb-toolbar__group");
        toolGroupTransfer.setAlignment(Pos.CENTER_LEFT);
        toolGroupTransfer.getStyleClass().add("adb-toolbar__group");

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);

        addRowButton.setId("data-add");
        addRowButton.getStyleClass().addAll("adb-button", "adb-button--icon");
        addRowButton.setTooltip(new Tooltip("在缓冲中新增一行；整行淡绿，保存后写库"));
        addRowButton.setDisable(true);
        addRowButton.setOnAction(event -> addRow());

        undoButton.setId("data-undo");
        undoButton.getStyleClass().addAll("adb-button", "adb-button--ghost", "adb-button--icon");
        undoButton.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled("撤销上一处编辑", "Mod+Z")));
        undoButton.setOnAction(event -> undo());

        redoButton.setId("data-redo");
        redoButton.getStyleClass().addAll("adb-button", "adb-button--ghost", "adb-button--icon");
        redoButton.setTooltip(new Tooltip(io.agenticdb.ui.Shortcuts.labelled("重做", "Shift+Mod+Z")));
        redoButton.setOnAction(event -> redo());

        saveButton.setId("data-save");
        saveButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        toolbarOp(saveButton, Icons.Icon.SAVE, "保存", io.agenticdb.ui.Shortcuts.labelled("预览 SQL 并保存全部修改", "Mod+S"));
        saveButton.setOnAction(event -> saveChanges());

        deleteRowsButton.setId("data-delete-rows");
        // ⭐ 删除是这一排里唯一会丢数据的动作 —— 用 danger 色，别和「保存」穿同一件衣服。
        deleteRowsButton.getStyleClass().addAll("adb-button", "adb-button--danger");
        toolbarOp(deleteRowsButton, Icons.Icon.DELETE, "删除记录",
                "删除选中的行（写进编辑缓冲，保存后生效）");
        deleteRowsButton.setDisable(true);
        // ⚠️ 没有选中行时**整颗按钮消失**，而不是摆在那里灰着 ——
        //    「删除」在不可用状态占着位置，只会让人反复去点它。
        deleteRowsButton.setVisible(false);
        deleteRowsButton.setManaged(false);
        deleteRowsButton.setOnAction(event -> deleteSelectedRows());

        // ── ⭐ G2：事务三键 ────────────────────────────────────────────
        //    Navicat 的数据页工具栏常驻「开始事务」，事务打开后才有「提交 / 回滚」。
        //    作用域是<b>连接</b>：一个连接一个会话，同一连接上的查询页、结构页共用这个事务。
        beginTxButton.setId("data-begin-tx");
        beginTxButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        beginTxButton.setDisable(true);
        toolbarOp(beginTxButton, Icons.Icon.LOCK, "开始事务",
                "在本连接上开一个显式事务：之后的保存只写入不提交，点「提交」才真正落库");
        beginTxButton.setOnAction(event -> beginTransaction());

        // ⚠️ 提交 / 回滚<b>只有图标</b>，不带文字，也不进 toolbarLabels。
        //    这一排 1280 窗口实测只有 1026px，文字全开已经要 1026px ——
        //    再给两颗文字键，事务一打开整排文字就被判「放不下」而集体退成图标，
        //    「保存」这种主操作反而丢了文字。这两颗只在事务里出现，
        //    出现本身就是状态，图标 + 提示足够；事务状态由状态栏那一段常显。
        commitTxButton.setId("data-commit-tx");
        commitTxButton.getStyleClass()
                .addAll("adb-button", "adb-button--ghost", "adb-button--icon");
        commitTxButton.setMinWidth(Region.USE_PREF_SIZE);
        commitTxButton.setOnAction(event -> commitTransaction());

        rollbackTxButton.setId("data-rollback-tx");
        rollbackTxButton.getStyleClass()
                .addAll("adb-button", "adb-button--danger", "adb-button--icon");
        rollbackTxButton.setMinWidth(Region.USE_PREF_SIZE);
        rollbackTxButton.setOnAction(event -> {
            if (confirmRollbackTransaction()) {
                rollbackTransaction();
            }
        });
        // 非事务态：提交/回滚不占位。
        for (Button button : List.of(commitTxButton, rollbackTxButton)) {
            button.setVisible(false);
            button.setManaged(false);
        }

        toolGroupData.getChildren().addAll(addRowButton, undoButton, redoButton, saveButton,
                deleteRowsButton, beginTxButton, commitTxButton, rollbackTxButton);

        filterButton.setId("data-filter");
        filterButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        filterButton.setDisable(true);
        toolbarOp(filterButton, Icons.Icon.FILTER, "筛选",
                "打开条件构造器；列头漏斗可按候选值筛选");
        filterButton.setOnAction(event -> showConditionDialog());

        clearQueryButton.setId("data-clear-query");
        clearQueryButton.getStyleClass().addAll("adb-button", "adb-button--ghost", "adb-button--icon");
        clearQueryButton.setDisable(true);
        clearQueryButton.setTooltip(new Tooltip("清除全部筛选与排序条件"));
        clearQueryButton.setOnAction(event -> clearQuery());

        columnsButton.setId("data-columns");
        columnsButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        columnsButton.setDisable(true);
        toolbarOp(columnsButton, Icons.Icon.COLUMNS, "列",
                "显示 / 隐藏列、恢复默认布局；列宽可拖列头边缘调整");
        columnsButton.setOnAction(event -> openColumnPanel());

        viewButton.setId("data-view");
        viewButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        viewButton.setDisable(true);
        toolbarOp(viewButton, Icons.Icon.VIEW, "视图",
                "本页视图：隐藏 NULL / 空串行、转置、冻结列");
        hideNullItem.setId("data-hide-null");
        hideNullItem.setOnAction(event -> setHideNullRows(hideNullItem.isSelected()));
        hideEmptyItem.setId("data-hide-empty");
        hideEmptyItem.setOnAction(event -> setHideEmptyRows(hideEmptyItem.isSelected()));
        transposeItem.setId("data-transpose-toggle");
        transposeItem.setOnAction(event -> setTranspose(transposeItem.isSelected()));
        summaryItem.setId("data-summary-toggle");
        summaryItem.setOnAction(event -> setSummaryVisible(summaryItem.isSelected()));

        // ⭐ G1：冻结列入口。做成子菜单而不是一个循环按钮 ——
        //   「现在冻结了几列」必须一眼看得到，循环点会让用户数不清自己在第几档。
        javafx.scene.control.Menu freezeMenu = new javafx.scene.control.Menu("冻结左侧列");
        freezeMenu.setId("data-freeze-menu");
        for (int count = 1; count <= 3; count++) {
            MenuItem item = new MenuItem("冻结 " + count + " 列");
            item.setId("data-freeze-" + count);
            int frozen = count;
            item.setOnAction(event -> setFrozenColumnCount(frozen));
            freezeMenu.getItems().add(item);
        }
        MenuItem unfreeze = new MenuItem("取消冻结");
        unfreeze.setId("data-freeze-0");
        unfreeze.setOnAction(event -> setFrozenColumnCount(0));
        freezeMenu.getItems().add(unfreeze);

        viewButton.getItems().addAll(hideNullItem, hideEmptyItem,
                new SeparatorMenuItem(), summaryItem, transposeItem,
                new SeparatorMenuItem(), freezeMenu);

        exportButton.setId("data-export");
        exportButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        exportButton.setDisable(true);
        toolbarOp(exportButton, Icons.Icon.EXPORT, "导出",
                "导出当前页或复制到剪贴板；导出向导可导整表、多种格式");
        MenuItem exportCsv = new MenuItem("导出当前页为 CSV…");
        exportCsv.setId("data-export-csv");
        exportCsv.setOnAction(event -> exportCsv());
        // ⭐ 导出向导：能导**整表**（不是只有当前页），格式可选 CSV/TSV/JSON/SQL。
        MenuItem exportWizard = new MenuItem("导出向导（整表 / 多种格式）…");
        exportWizard.setId("data-export-wizard");
        exportWizard.setOnAction(event -> openExportWizard());
        MenuItem copyPage = new MenuItem("复制当前页 TSV");
        copyPage.setId("data-export-tsv");
        copyPage.setOnAction(event -> copyRowsAsTsv(List.copyOf(grid.getItems()), "当前页"));
        MenuItem copyPageSql = new MenuItem("复制当前页为 SQL");
        copyPageSql.setId("data-export-sql");
        copyPageSql.setOnAction(event -> copyRowsAsSql(List.copyOf(grid.getItems()), "当前页"));
        exportButton.getItems().addAll(exportWizard, new SeparatorMenuItem(),
                exportCsv, copyPage, copyPageSql);

        importButton.setId("data-import");
        importButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        importButton.setDisable(true);
        toolbarOp(importButton, Icons.Icon.IMPORT, "导入",
                "打开导入向导；目标表已预选成本表");
        importButton.setOnAction(event -> openImportWizard());

        historyButton.setId("data-history");
        historyButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(historyButton, Icons.Icon.HISTORY, "历史",
                "本表最近执行过的 SQL（按表筛选）；点一条载入到查询页");
        // 每次展开时重建 —— 历史可能在别的查询页里刚增长，缓存一份只会给出旧列表。
        historyButton.setOnShowing(event -> rebuildHistoryMenu());

        searchField.setId("data-search");
        searchField.getStyleClass().add("adb-grid__search");
        searchField.setPromptText("本页搜索");
        searchField.setPrefWidth(180);
        searchField.setMinWidth(110);
        // ⭐ G3 之后 ⌘F 归网格查找条：这里不再挂快捷键，只说它做什么。
        searchField.setTooltip(new Tooltip("只在当前页内过滤（不改服务端分页）"));
        searchField.textProperty().addListener((observable, previous, value) -> rebuildItems());

        // ⭐ UX-3：按语义分三组 —— 编辑 / 查询与显示 / 数据交换，组间一条竖线。
        //    以前「导出」混在查询组里、「新增行」和「撤销」挨着，
        //    眼睛得把一排按钮重新分类一遍才知道哪组管什么。
        toolGroupQuery.getChildren().addAll(filterButton, clearQueryButton, historyButton,
                columnsButton, viewButton);
        toolGroupTransfer.getChildren().addAll(importButton, exportButton);

        toolbar.getChildren().addAll(toolGroupData, toolbarSeparator(), toolGroupQuery,
                toolbarSeparator(), toolGroupTransfer, toolbarSpacer, searchField);
        bindToolbarLabels();

        chipBar.setId("data-query-chips");
        chipBar.getStyleClass().add("adb-query-chips");
        chipBar.setAlignment(Pos.CENTER_LEFT);
        chipBar.setVisible(false);
        chipBar.setManaged(false);

        grid.setId("data-table");
        grid.getStyleClass().add("adb-grid");
        // ⭐ P7：表格本身没有文字，读屏只会读「表」—— 给一个明确的名称。
        grid.setAccessibleText("数据网格");

        // ⭐ G1：左表（冻结列）。两张表共享同一份 items，靠索引对齐。
        //   列宽用 UNCONSTRAINED —— 冻结区宽度就是各列 prefWidth 之和，
        //   不能让最后一列被拉伸，否则右表起点会随窗口宽度抖动。
        frozenGrid.setId("data-frozen-table");
        frozenGrid.getStyleClass().addAll("adb-grid", "adb-grid--frozen");
        frozenGrid.setAccessibleText("数据网格（冻结列）");
        frozenGrid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        frozenGrid.setFocusTraversable(true);
        frozenGrid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        frozenGrid.setVisible(false);
        frozenGrid.setManaged(false);

        // ⭐ 空表不是"没有数据"四个字 —— 用户看到空白不知道下一步能干什么。
        //   给图标 + 说明 + 一个真能点的出口。
        grid.setPlaceholder(EmptyState.of(Icons.Icon.TABLE, "这张表还是空的",
                "表里没有数据行。可以新增一行，或到查询页执行 INSERT。",
                "新增一行", this::addRow)
                .hint("新增的行是草稿，要按「保存」才会真正写进数据库。"));
        // ⭐ S-6：CONSTRAINED 策略 + 每列可读下限（见 ColumnWidths）。
        //    实测（第二十轮 WidthProbe）：宽屏时列会**填满**（按 pref 比例分），
        //    挤窄时宽度**卡在 minWidth 不往下走**，总和超出视口就自动出横向滚动条。
        //    所以既不会留一大片空白，也不会把列头压成 `na… em… acti…`。
        grid.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        grid.setFocusTraversable(true);
        grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        bindFocusCursor(grid);
        bindFocusCursor(frozenGrid);
        selectAllBox.setId("data-select-all");
        selectAllBox.setAccessibleText("全选本页");
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
                    updateDeleteButton();
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
        // U24：转置视图空着的时候，说清是「这张表没行」还是「筛选太严」。
        transposeTable.setPlaceholder(EmptyState.of(Icons.Icon.STRUCTURE, "没有可转置的数据",
                "当前页一行都没有 —— 表可能是空的，也可能是筛选条件把所有行都滤掉了。"));
        transposeTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        transposeTable.setVisible(false);
        transposeTable.setManaged(false);
        transposeTable.setFocusTraversable(true);

        detailsDrawer.setOnClose(() -> { });
        // ⭐ UX-4：抽屉里的多行编辑器 —— 应用走的是和格子里同一套写缓冲逻辑。
        detailsDrawer.setOnApply(this::applyDetailsEdit);
        detailsDrawer.setEditGate(this::canEditTextCell);
        StackPane.setAlignment(detailsDrawer, Pos.CENTER_RIGHT);
        StackPane.setMargin(detailsDrawer, new javafx.geometry.Insets(8));

        body.getStyleClass().add("adb-grid__body");
        summaryLabel.setId("data-summary");
        summaryLabel.getStyleClass().add("adb-grid__summary");
        summaryLabel.setVisible(false);
        summaryLabel.setManaged(false);

        // ⭐ G1：左表 + 右表并排。不冻结时左表 managed=false，宽度归零 ——
        //   右表 Hgrow 占满，和「只有一张表」的旧布局完全等价。
        gridRow.getStyleClass().add("adb-grid__row");
        HBox.setHgrow(grid, Priority.ALWAYS);
        grid.setMaxWidth(Double.MAX_VALUE);
        gridRow.getChildren().addAll(frozenGrid, grid);
        body.getChildren().addAll(gridRow, transposeTable, stateBox, summaryLabel, detailsDrawer);
        VBox.setVgrow(body, Priority.ALWAYS);
        bindFrozenSelection();
        bindFrozenScroll();

        // 记录导航先建（分页器里要用它），再成分页器。
        buildRecordNav();
        HBox pager = buildPager();

        buildBatchBar();
        buildFindBar();

        // ── ④ 组装 ────────────────────────────────────────────────────
        //    数据切面 = 工具栏 + 条件 chips + 查找条 + 网格 + 批量条 + 分页器。
        //    其它切面（结构 / 索引 / 外键 / 触发器 / SQL）共用同一个 StackPane，
        //    切换时不丢数据页的任何状态（编辑缓冲、分页、滚动位置都在）。
        //
        //    ⭐ 记录导航条**并进分页器**，不再单独占一行 ——
        //    它原来是网格上方一条 28px 的独立横条，把「第一条数据」推到了
        //    窗口的 1/3 处（实测：284px 的 chrome 里它占了 28px）。
        //    两套导航（翻行 / 翻页）放在一起，语义更清楚，还省一行。
        //    ⭐ G3 查找条默认 unmanaged（不占高度），打开时才把网格往下推一格。
        dataSection = new VBox(toolbar, chipBar, findBar, body, batchBar, pager);
        dataSection.getStyleClass().add("adb-grid-page__data");
        VBox.setVgrow(body, Priority.ALWAYS);

        sectionStack.getChildren().addAll(dataSection, overviewPane, sectionsPanel);
        sectionStack.getStyleClass().add("adb-grid-page__sections");
        VBox.setVgrow(sectionStack, Priority.ALWAYS);
        // ⚠️ 初始必须只显示数据切面 —— 多个切面都 visible 时会**叠在一起**，
        //    结构表压在网格上，鼠标点到的根本不是网格。
        overviewPane.setVisible(false);
        overviewPane.setManaged(false);
        sectionsPanel.setVisible(false);
        sectionsPanel.setManaged(false);

        getChildren().addAll(objectHeader, sectionStack);
        VBox.setVgrow(sectionStack, Priority.ALWAYS);
    }

    /**
     * 切换对象切面。
     *
     * <p>切到「数据」以外的切面时，隐藏数据区、显示对应的只读视图；
     * 切回「数据」时原样恢复 —— 编辑缓冲与分页位置都不丢。
     */
    private void sectionSelected(String sectionId) {
        boolean data = "data".equals(sectionId);
        boolean overview = "overview".equals(sectionId);
        dataSection.setVisible(data);
        dataSection.setManaged(data);
        overviewPane.setVisible(overview);
        overviewPane.setManaged(overview);
        boolean sections = !data && !overview;
        sectionsPanel.setVisible(sections);
        sectionsPanel.setManaged(sections);
        if (overview) {
            loadOverview(false);
        } else if (sections) {
            sectionsPanel.show(sectionId);
        }
    }

    /** UX-6：程序化切换对象切面（等同用户点标签；测试与外壳共用）。 */
    void selectSection(String sectionId) {
        objectTabs.select(sectionId);
    }

    /**
     * ⭐ UX-6 + U17：概览切面 —— 一张<b>表档案</b>（左标签 / 右值）
     * + 这张表的最近操作（P2-3 查询历史）+ 两个快捷动作。
     *
     * <p>为什么从「四个指标卡」改成属性表（U17）：卡片版把 4 个数摊成 2×2，
     * 每个数各占一格视觉重量，而真正想知道的「主键是谁、有几列、哪列自增」
     * 一格都没有；剩下的 60% 高度是空白。Navicat 的 Table Profile 就是一列
     * 一行一句话的属性表 —— 读它不需要先理解哪格更重要。
     *
     * <p>行数直接复用数据页刚算出来的 {@code total}（同一时刻、同一套筛选条件）；
     * 大小 / 引擎来自数据库元信息，方言不提供时显示「—」并说明原因；
     * 索引数走实时内省；列数 / 主键 / 自增列来自已经内省到的表结构。
     * **没有哪一项是猜的。**
     */
    private VBox buildOverview() {
        VBox pane = new VBox(12);
        pane.setId("overview-pane");
        pane.getStyleClass().add("adb-overview");
        pane.setPadding(new Insets(16, 18, 16, 18));

        // ⭐ U16：这里原来还有一个 16px 的「概览」标题 —— 它和上面的切面 tab
        // 是同一个词，同一句话读两遍，还挤掉了真正的内容。删。
        GridPane facts = new GridPane();
        facts.setId("overview-facts");
        facts.setHgap(24);
        facts.setVgap(6);
        addOverviewFact(facts, "行数", overviewRows, "overview-rows");
        addOverviewFact(facts, "列数", overviewColumns, "overview-columns");
        addOverviewFact(facts, "主键", overviewPrimary, "overview-primary");
        addOverviewFact(facts, "自增列", overviewAutoIncrement, "overview-auto-increment");
        addOverviewFact(facts, "索引数", overviewIndexes, "overview-indexes");
        addOverviewFact(facts, "数据大小", overviewSize, "overview-size");
        addOverviewFact(facts, "引擎", overviewEngine, "overview-engine");

        overviewHint.setId("overview-hint");
        overviewHint.getStyleClass().add("adb-overview__hint");
        overviewHint.setWrapText(true);

        Label recentTitle = new Label("最近操作");
        recentTitle.getStyleClass().add("adb-overview__section-title");
        Label recentHint = new Label("来自查询历史 —— 点一条在查询页打开");
        recentHint.getStyleClass().add("adb-overview__hint");
        overviewRecent.setId("overview-recent");

        Button openStructureButton = IconButtons.of(Icons.Icon.STRUCTURE, "打开结构");
        openStructureButton.setId("overview-open-structure");
        openStructureButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        openStructureButton.setOnAction(event -> openStructure.accept(tableName));

        Button refreshOverviewButton = IconButtons.of(Icons.Icon.REFRESH, "刷新概览");
        refreshOverviewButton.setId("overview-refresh");
        refreshOverviewButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        refreshOverviewButton.setOnAction(event -> {
            refresh();
            loadOverview(true);
        });

        HBox actions = new HBox(8, openStructureButton, refreshOverviewButton);
        actions.getStyleClass().add("adb-overview__actions");

        pane.getChildren().addAll(facts, overviewHint,
                new Separator(), recentTitle, recentHint, overviewRecent, actions);
        return pane;
    }

    /**
     * 属性表的一行：左标签 + 右值。
     *
     * <p>标签列给固定宽度（{@code 76px}）而不是靠内容撑开：值必须对齐在同一条竖线上，
     * 否则「行数 / 索引数 / 表注释」各自缩进，读起来像三张不同的表 ——
     * 这正是 U17 要修的「同一件事多种长相」。
     */
    private static void addOverviewFact(GridPane grid, String key, Label value, String id) {
        if (grid.getColumnConstraints().isEmpty()) {
            ColumnConstraints keys = new ColumnConstraints();
            keys.setMinWidth(76);
            grid.getColumnConstraints().add(keys);
        }
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("adb-overview__fact-key");
        value.setId(id);
        value.getStyleClass().add("adb-overview__fact-value");
        value.setWrapText(true);
        grid.addRow(grid.getRowCount(), keyLabel, value);
    }

    /** UX-6：切到概览时按需加载；已有结果且不强制刷新时直接复用。 */
    private void loadOverview(boolean fresh) {
        updateOverviewRows();
        updateOverviewSchemaFacts();
        renderOverviewRecent();
        if (overviewLoading || (overviewLoaded && !fresh)) {
            return;
        }
        overviewLoading = true;
        overviewLoaded = false;
        overviewSize.setText("…");
        overviewEngine.setText("…");
        overviewIndexes.setText("…");

        Task<OverviewFacts> task = new Task<>() {
            @Override
            protected OverviewFacts call() {
                return new OverviewFacts(readTableMeta(), countIndexes());
            }
        };
        task.setOnSucceeded(event -> {
            overviewLoading = false;
            overviewLoaded = true;
            applyOverview(task.getValue());
        });
        task.setOnFailed(event -> {
            overviewLoading = false;
            overviewLoaded = true;
            applyOverview(new OverviewFacts(null, null));
        });
        start(task);
    }

    private TableMeta readTableMeta() {
        if (session.dialect().tableMetaSql() == null) {
            return null;
        }
        try {
            for (TableMeta meta : schemaService.listTableMeta(session)) {
                if (meta.name().equalsIgnoreCase(tableName)) {
                    return meta;
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            // 元信息读不到就显示「—」，不让概览页整页失败。
        }
        return null;
    }

    private Integer countIndexes() {
        try {
            return schemaService.indexCount(session, tableName);
        } catch (SQLException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * U17：列数 / 主键 / 自增列来自<b>已经内省到的表结构</b>，不额外查库。
     *
     * <p>「还没加载」与「真的没有」是两件事，必须两种写法：结构没到 → 「…」；
     * 结构到了但没有主键 / 自增 → 「无」。两者都写成「—」，用户就分不出
     * 是该等还是这张表确实如此 —— 那是假状态。
     */
    private void updateOverviewSchemaFacts() {
        if (tableSchema == null) {
            overviewColumns.setText("…");
            overviewPrimary.setText("…");
            overviewAutoIncrement.setText("…");
            return;
        }
        overviewColumns.setText(String.valueOf(tableSchema.columns().size()));
        overviewPrimary.setText(tableSchema.primaryKeys().isEmpty()
                ? "无" : String.join("、", tableSchema.primaryKeys()));
        overviewAutoIncrement.setText(tableSchema.autoIncrementColumn() == null
                ? "无" : tableSchema.autoIncrementColumn());
    }

    private void applyOverview(OverviewFacts facts) {
        TableMeta meta = facts.meta();
        overviewSize.setText(meta == null ? "—" : meta.sizeText());
        overviewEngine.setText(meta == null ? "—" : meta.engineText());
        overviewIndexes.setText(facts.indexes() == null ? "—" : String.valueOf(facts.indexes()));
        if (meta == null && session.dialect().tableMetaSql() == null) {
            overviewHint.setText(session.dialect().displayName()
                    + " 不提供表大小与引擎 —— 显示「—」；索引数来自实时内省");
        } else if (meta == null) {
            overviewHint.setText("没有读到表元信息（权限不足或旧版本库）—— 显示「—」");
        } else {
            overviewHint.setText("大小 / 引擎来自数据库元信息；索引数来自实时内省");
        }
    }

    private void updateOverviewRows() {
        overviewRows.setText(total < 0 ? "—" : String.valueOf(total));
    }

    private void renderOverviewRecent() {
        overviewRecent.getChildren().clear();
        List<QueryHistory.Entry> entries = historySource.get();
        if (entries.isEmpty()) {
            Label empty = new Label("还没有执行过针对本表的 SQL");
            empty.setId("overview-recent-empty");
            empty.getStyleClass().add("adb-overview__empty");
            overviewRecent.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < Math.min(5, entries.size()); i++) {
            QueryHistory.Entry entry = entries.get(i);
            Button item = new Button(entry.summary());
            item.setId("overview-history-" + i);
            item.getStyleClass().addAll("adb-button", "adb-button--ghost", "adb-overview__history");
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setTooltip(new Tooltip(entry.sql()));
            item.setOnAction(event -> onHistoryPick.accept(entry.sql()));
            overviewRecent.getChildren().add(item);
        }
    }

    private record OverviewFacts(TableMeta meta, Integer indexes) {
    }

    /**
     * ⭐ 记录导航条（Navicat 形态）。
     *
     * <p>首行 / 上一行 / 下一行 / 末行 + 当前行号。
     *
     * <p><b>位置</b>：并进底部的**分页器**（不是网格上方单独一行）——
     * 单独占一行会把「第一条数据」推到窗口的 1/3 处，而且两套方向键
     * （翻行 / 翻页）分开放在两个地方，用户很容易点错。
     */
    private HBox buildRecordNav() {
        recordNav.setId("data-record-nav");
        recordNav.getStyleClass().add("adb-record-nav");
        recordNav.setAlignment(Pos.CENTER_LEFT);

        Button first = IconButtons.icon(Icons.Icon.FIRST, "第一行");
        first.setId("data-record-first");
        first.getStyleClass().add("adb-icon-button");
        first.setOnAction(event -> selectRow(0));

        Button previous = IconButtons.icon(Icons.Icon.PREVIOUS, "上一行");
        previous.setId("data-record-prev");
        previous.getStyleClass().add("adb-icon-button");
        previous.setOnAction(event -> selectRow(currentRow() - 1));

        Button next = IconButtons.icon(Icons.Icon.NEXT, "下一行");
        next.setId("data-record-next");
        next.getStyleClass().add("adb-icon-button");
        next.setOnAction(event -> selectRow(currentRow() + 1));

        Button last = IconButtons.icon(Icons.Icon.LAST, "最后一行");
        last.setId("data-record-last");
        last.getStyleClass().add("adb-icon-button");
        last.setOnAction(event -> selectRow(grid.getItems().size() - 1));

        recordPosition.setId("data-record-position");
        recordPosition.getStyleClass().add("adb-record-nav__position");

        // ⭐ 记录导航必须带「记录」两个字。
        //     实测（2026-10-23 截图复查）：底部同时有两组 ◀◀ ◀ ▶ ▶▶，
        //     一组翻**行**一组翻**页**，图标完全一样 —— 用户根本分不清
        //     点哪一组会跳到哪。工具提示只在悬停时出现，而这两组按钮
        //     是连在一起看的，不会逐个悬停。加一个常驻标签是最省的解法。
        Label recordLabel = new Label("记录");
        recordLabel.getStyleClass().add("adb-record-nav__label");

        recordNav.getChildren().addAll(recordLabel, first, previous, next, last, recordPosition);

        grid.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldValue, newValue) -> updateRecordPosition());
        updateRecordPosition();
        return recordNav;
    }

    /** 当前选中的行号（-1 表示没选）。 */
    private int currentRow() {
        return grid.getSelectionModel().getSelectedIndex();
    }

    /** 选中指定行并滚动到可见。 */
    private void selectRow(int index) {
        int size = grid.getItems().size();
        if (size == 0) {
            return;
        }
        int target = Math.max(0, Math.min(index, size - 1));
        grid.getSelectionModel().clearAndSelect(target);
        grid.scrollTo(target);
        updateRecordPosition();
    }

    /** 刷新「第 N 行 / 共 M 行」。 */
    private void updateRecordPosition() {
        int size = grid.getItems().size();
        int index = currentRow();
        // ⭐ 文案要短 —— 它和分页器挤在同一行，窄窗口下会被截成「共 20…」。
        recordPosition.setText(index < 0
                ? size + " 行"
                : (index + 1) + " / " + size + " 行");
    }

    private void buildBatchBar() {
        batchBar.setId("data-batch-bar");
        batchBar.getStyleClass().add("adb-batch-bar");
        batchBar.setAlignment(Pos.CENTER_LEFT);
        batchBar.setVisible(false);
        batchBar.setManaged(false);

        batchLabel.setId("data-batch-label");
        batchLabel.getStyleClass().add("adb-batch-bar__count");

        Button copy = IconButtons.of(Icons.Icon.COPY, "复制");
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

        // ⭐ G8：导出选中行（以前批量条只有复制，没有导出）
        Button export = IconButtons.of(Icons.Icon.EXPORT, "导出选中");
        export.setId("data-batch-export");
        export.getStyleClass().addAll("adb-button", "adb-button--ghost");
        export.setTooltip(new Tooltip("把选中的行导出为 CSV 文件"));
        export.setOnAction(event -> exportSelectedCsv());

        Button clear = new Button("清除选择");
        clear.setId("data-batch-clear");
        clear.getStyleClass().addAll("adb-button", "adb-button--ghost");
        clear.setOnAction(event -> grid.getSelectionModel().clearSelection());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        batchBar.getChildren().addAll(batchLabel, copy, copySql, export, batchClone,
                batchNull, batchEmpty, batchDelete, spacer, clear);
    }

    /**
     * 工具栏分组之间的 1px 竖直分隔线。
     *
     * <p>用 {@code .adb-toolbar__separator} 样式类控制（1px 宽、18px 高），
     * 不用 JavaFX 默认分隔线 —— 默认那条比设计令牌粗，一排下来工具栏显得毛糙。
     */
    private static Separator groupSeparator() {
        Separator separator = new Separator(javafx.geometry.Orientation.VERTICAL);
        separator.getStyleClass().add("adb-toolbar__separator");
        return separator;
    }

    /**
     * ⭐ UX-4：网格左下的行操作条。
     *
     * <p>四个动作与工具条**同一批**：点击直接调同一组方法，可用态用
     * {@code bind} 挂在工具条按钮上 —— 这里再写一遍 if 迟早和上面不一致。
     */
    private HBox buildRowActions() {
        rowActions.setId("data-row-actions");
        rowActions.getStyleClass().add("adb-grid__row-actions");
        rowActions.setAlignment(Pos.CENTER_LEFT);

        rowAddButton.setId("data-row-add");
        rowAddButton.getStyleClass().add("adb-icon-button");
        rowAddButton.setOnAction(event -> addRow());

        rowDeleteButton.setId("data-row-delete");
        rowDeleteButton.getStyleClass().addAll("adb-icon-button", "adb-button--danger");
        rowDeleteButton.setOnAction(event -> deleteSelectedRows());

        rowApplyButton.setId("data-row-apply");
        rowApplyButton.getStyleClass().addAll("adb-icon-button", "adb-button--primary");
        rowApplyButton.setOnAction(event -> saveChanges());

        rowCancelButton.setId("data-row-cancel");
        rowCancelButton.getStyleClass().add("adb-icon-button");
        rowCancelButton.setOnAction(event -> discardChanges());

        rowAddButton.disableProperty().bind(addRowButton.disableProperty());
        rowDeleteButton.disableProperty().bind(deleteRowsButton.disableProperty());
        // 没有选中行时工具条那颗「删除」整颗消失，这里跟着一起消失 ——
        // 摆一颗灰掉的删除按钮在光标旁边，比没有更危险。
        rowDeleteButton.visibleProperty().bind(deleteRowsButton.visibleProperty());
        rowDeleteButton.managedProperty().bind(deleteRowsButton.visibleProperty());
        rowApplyButton.disableProperty().bind(saveButton.disableProperty());
        rowCancelButton.disableProperty().bind(saveButton.disableProperty());

        rowActions.getChildren().addAll(rowAddButton, rowDeleteButton,
                rowApplyButton, rowCancelButton);
        return rowActions;
    }

    /**
     * 放弃缓冲里全部未保存的修改（行操作条的「取消」）。
     *
     * <p>只清缓冲、不重新查询 —— 草稿行本来就不在库里，
     * 被改的单元格也靠缓冲覆盖显示，清掉就恢复成数据库里的样子。
     */
    private void discardChanges() {
        if (!editBuffer.isDirty() || loading || saving) {
            return;
        }
        int count = editBuffer.dirtyCount();
        editBuffer.clear();
        saveUndoPlan = null;
        rebuildItems();
        updateDirty();
        feedback.status("已放弃 " + count + " 处未保存的修改");
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
        jumpField.setPrefWidth(64);
        jumpField.setMinWidth(56);
        jumpField.setOnAction(event -> jumpToPage(jumpField.getText()));

        // ⭐ G7：行级导航 —— 页码之上还有行号。
        jumpRowField.setId("data-jump-row");
        jumpRowField.getStyleClass().add("adb-grid__jump");
        jumpRowField.setPromptText("跳至行");
        jumpRowField.setTooltip(new Tooltip("输入行号后回车跳到该行（跨页也能跳）"));
        jumpRowField.setAccessibleText("跳至指定行");
        jumpRowField.setPrefWidth(64);
        jumpRowField.setMinWidth(56);
        jumpRowField.setOnAction(event -> jumpToRow(jumpRowField.getText()));

        Label perPage = new Label("每页");
        perPage.getStyleClass().add("adb-muted");
        // ⭐ 这些文字标签**不许被压扁** —— 「第 1 / 1 页」被截成「第 1/…」，
        //    用户就不知道当前在第几页了。宁可让别的东西收缩。
        pageLabel.setMinWidth(Region.USE_PREF_SIZE);
        perPage.setMinWidth(Region.USE_PREF_SIZE);
        rangeLabel.setMinWidth(Region.USE_PREF_SIZE);

        pageSizeBox.setId("data-page-size");
        pageSizeBox.setAccessibleText("每页行数");
        pageSizeBox.getStyleClass().add("adb-page-size");
        // ⭐ 档位来自 PageSize 枚举（唯一真相），不再在 UI 里写死一份数组。
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
            load(false);
        });

        // ⭐ 与「记录」对称：翻页那一组也要有常驻标签。
        //     没有它，两组图标相同的方向键并排放着，用户只能靠猜。
        Label pageLabelText = new Label("页面");
        pageLabelText.getStyleClass().add("adb-record-nav__label");

        pager.getChildren().addAll(buildRowActions(),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                rangeLabel, recordNav, spacer, pageLabelText,
                firstPageButton, previous, pageLabel, next, lastPageButton,
                jumpField, jumpRowField,
                new Separator(javafx.geometry.Orientation.VERTICAL), perPage, pageSizeBox);
        return pager;
    }

    // ─────────────────────────── 加载 ───────────────────────────

    public void refresh() {
        // 表大小 / 引擎会随写入变化：刷新数据时让概览的元信息也重新读一次。
        overviewLoaded = false;
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

    /**
     * ⭐ G7：跳到第 N 行（跨页也能跳）。
     *
     * <p>行号是**绝对行号**（跟网格左侧的 # 一致），所以先算出目标行所在的页，
     * 必要时翻页，再把焦点落到那一行。
     */
    void jumpToRow(String raw) {
        if (loading) {
            return;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        long row;
        try {
            row = Long.parseLong(text);
        } catch (NumberFormatException e) {
            feedback.status("跳行失败：「" + text + "」不是行号");
            return;
        }
        if (row < 1) {
            feedback.status("跳行失败：行号从 1 开始");
            return;
        }
        if (total >= 0 && row > total) {
            feedback.status("跳行失败：最大行号 " + total);
            return;
        }
        long targetOffset = ((row - 1) / pageSize) * pageSize;
        if (QueryLimits.exceedsOffsetLimit(targetOffset)) {
            showError("已到达 OFFSET 上限 " + QueryLimits.MAX_OFFSET
                    + "，请改用筛选条件而不是继续翻页。");
            return;
        }
        jumpRowField.clear();
        if (targetOffset != offset) {
            offset = targetOffset;
            load(false);
            return;
        }
        selectRowIndex((int) ((row - 1) % pageSize));
    }

    /** 门禁用：设置每页行数并重新加载。 */
    void setPageSizeForTest(int size) {
        this.pageSize = Math.max(1, size);
        offset = 0;
        load(false);
    }

    /** 选中当前页里的第 index 行（0 起）并滚到它。 */
    private void selectRowIndex(int index) {
        List<Map<String, Object>> items = grid.getItems();
        if (index < 0 || index >= items.size()) {
            return;
        }
        grid.getSelectionModel().clearAndSelect(index);
        grid.scrollTo(index);
        grid.requestFocus();
        feedback.status("已跳到第 " + (offset + index + 1) + " 行");
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

    private <T> Future<?> start(Task<T> task) {
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
        updateHeaderCount();
        updateOverviewRows();
        updateOverviewSchemaFacts();

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
        // ⭐ UX-5-D：状态栏要说清「这屏数据是哪条语句取来的、什么时候取的」。
        //    时刻取结果落到界面的这一刻 —— 用户感知的「刷新完成」就是这一瞬，
        //    不是后台线程里查询返回的那一毫秒。
        lastRefreshedAt = System.currentTimeMillis();
        feedback.sql(lastSql);
        feedback.refreshedAt(lastRefreshedAt);
        // ⭐ 结果被硬上限截断时必须说出来。数据页平时不会截断（分页 + COUNT 已知总数），
        //    但只要发生了，用户看到的就不是完整数据 —— 静默丢数据是正确性问题。
        boolean truncated = result.page.rowsTruncated();
        // ⭐ U8：加载成功不再往消息位写「已加载 users · 200 行」—— 行范围段
        //    （1–200 / 200）说的就是这件事，重复一遍等于多占一格。
        //    但**截断必须说**：静默丢数据是正确性问题，这一句是消息位存在的意义。
        feedback.status(truncated
                ? "⚠ 结果已截断 · " + tableName + " 只取回 " + lastRowCount + " 行"
                : "");
        activated = true;
    }

    /**
     * 更新标题栏里的记录数。
     *
     * <p>只有拿到**真实的 COUNT** 才显示数字；总数未知时显示「行数未知」——
     * 编一个数字比不显示更糟（用户会拿它做判断）。
     */
    private void updateHeaderCount() {
        if (objectHeader == null) {
            return;
        }
        if (total < 0) {
            objectHeader.setCount(null);
        } else {
            objectHeader.setCount(total + " 行");
        }
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
        // ⭐ D1（2026-10-26）：网格比档位低一档（默认 normal → 24px），清单/树仍按原档。
        int rowHeight = io.agenticdb.ui.RowHeights.grid(density);
        grid.setFixedCellSize(rowHeight);
        // ⭐ G1：两表行高必须一致，否则同一行在左右两半会错开半格。
        frozenGrid.setFixedCellSize(rowHeight);
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
        // ⭐ UX-5-D：语句与刷新时刻也要跟着回来 ——
        //    在表清单那一类「没有语句」的标签上停留时状态栏会清空，切回来不能还是空的。
        feedback.sql(lastSql);
        feedback.refreshedAt(lastRefreshedAt);
        feedback.dirty(editBuffer.dirtyCount());
        // ⭐ G2：事务状态跟着回来 —— 并且向会话核对一次真实值（见 refreshTransactionState）。
        feedback.transaction(transactionOpen);
        refreshTransactionState();
        updateEditControls();
        // U8：切回来只恢复事实段（范围/语句/耗时/待提交），不再播一句
        // 「已激活：users · 200 行」—— 表名在标签上、行数在范围段里，这句话没有新信息。
        feedback.status("");
    }

    // ─────────────────────────── 表格 ───────────────────────────

    // ─────────────── G1：冻结列 ───────────────

    /** 冻结前 N 个数据列（不含选择列 / 行号列，那两列天然跟着冻结）。0 = 解冻。 */
    public void setFrozenColumnCount(int count) {
        int next = Math.max(0, count);
        if (next == frozenColumnCount) {
            return;
        }
        frozenColumnCount = next;
        applyFrozenColumns();
        feedback.status(next == 0 ? "已取消冻结列" : "已冻结左侧 " + next + " 列");
    }

    public int frozenColumnCount() {
        return frozenColumnCount;
    }

    /**
     * 按当前冻结列数重新分配两张表的列。
     *
     * <p>移动的是<b>同一批 {@code TableColumn} 实例</b>（不是复制一份），
     * 所以列宽、可见性、cellFactory、userData 全都跟着走 ——
     * 不用在两处各维护一份状态，也就不会出现「左表改了右表没改」。
     */
    private void applyFrozenColumns() {
        // ⭐ 列可能已经分散在两表里（上一次冻结留下的），先合并再重分，保证幂等。
        List<TableColumn<Map<String, Object>, ?>> all = new ArrayList<>();
        all.addAll(frozenGrid.getColumns());
        all.addAll(grid.getColumns());
        frozenGrid.setItems(grid.getItems());

        if (frozenColumnCount <= 0) {
            frozenGrid.getColumns().clear();
            grid.getColumns().clear();
            grid.getColumns().addAll(all);
            frozenGrid.setVisible(false);
            frozenGrid.setManaged(false);
            return;
        }
        // 前 2 列是选择列 + 行号列，它们永远跟着冻结区走；
        // 至少给右表留一列，否则「冻结」就变成把整张表搬走了。
        int move = Math.min(2 + frozenColumnCount, all.size() - 1);
        if (move <= 2) {
            frozenColumnCount = 0;
            frozenGrid.getColumns().clear();
            grid.getColumns().clear();
            grid.getColumns().addAll(all);
            frozenGrid.setVisible(false);
            frozenGrid.setManaged(false);
            return;
        }
        frozenGrid.getColumns().clear();
        grid.getColumns().clear();
        frozenGrid.getColumns().addAll(all.subList(0, move));
        grid.getColumns().addAll(all.subList(move, all.size()));
        frozenGrid.setVisible(true);
        frozenGrid.setManaged(true);
        frozenGrid.setPrefWidth(visibleColumnsWidth(frozenGrid));
    }

    /** 可见列宽度之和 —— 冻结区就按这个宽度占住，右表起点才不会抖。 */
    private static double visibleColumnsWidth(TableView<Map<String, Object>> table) {
        double width = 0;
        for (TableColumn<Map<String, Object>, ?> column : table.getColumns()) {
            if (column.isVisible()) {
                width += column.getPrefWidth();
            }
        }
        return width;
    }

    /** 两表选择互相跟随（共享同一份 items，按索引对齐即可）。 */
    private void bindFrozenSelection() {
        grid.getSelectionModel().getSelectedIndices().addListener(
                (javafx.collections.ListChangeListener<Number>) change ->
                        syncSelection(frozenGrid, grid));
        frozenGrid.getSelectionModel().getSelectedIndices().addListener(
                (javafx.collections.ListChangeListener<Number>) change ->
                        syncSelection(grid, frozenGrid));
    }

    private void syncSelection(TableView<Map<String, Object>> target,
                               TableView<Map<String, Object>> source) {
        if (syncingSelection || frozenColumnCount <= 0) {
            return;
        }
        syncingSelection = true;
        try {
            List<Integer> indices = new ArrayList<>();
            for (Integer index : source.getSelectionModel().getSelectedIndices()) {
                indices.add(index);
            }
            target.getSelectionModel().clearSelection();
            for (Integer index : indices) {
                if (index >= 0 && index < target.getItems().size()) {
                    target.getSelectionModel().select(index);
                }
            }
        } finally {
            syncingSelection = false;
        }
    }

    /**
     * 垂直滚动同步。
     *
     * <p>滚动条在 skin 里，skin 就绪前 lookup 不到 —— 所以挂在 skinProperty 上，
     * 两边都就绪再绑。headless 下单测拿不到滚动条，这里会安静跳过：
     * <b>没有滚动条不等于功能坏了</b>，只是这个环境渲染不了。
     */
    private void bindFrozenScroll() {
        Runnable attempt = () -> {
            if (scrollBound || frozenColumnCount <= 0) {
                return;
            }
            javafx.scene.control.ScrollBar main = verticalBar(grid);
            javafx.scene.control.ScrollBar frozen = verticalBar(frozenGrid);
            if (main == null || frozen == null) {
                return;
            }
            main.valueProperty().bindBidirectional(frozen.valueProperty());
            scrollBound = true;
        };
        grid.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                attempt.run();
            }
        });
        frozenGrid.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                attempt.run();
            }
        });
    }

    private static javafx.scene.control.ScrollBar verticalBar(TableView<?> table) {
        for (javafx.scene.Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                return bar;
            }
        }
        return null;
    }

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
                box.setAccessibleText("选择本行");
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
        // ⛔ 这里原来加的是 `adb-grid__number-column` —— TableColumn 不在场景图里，
        //    样式类对它**完全无效**（真正生效的是单元格上的 `adb-grid__row-number`）。
        //    留着它只会让人以为「行号列已经上过样式」。
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
                    setGraphic(null);
                    return;
                }
                Map<String, Object> row = getTableRow() == null
                        ? null : getTableRow().getItem();
                Long draftId = draftId(row);
                if (draftId != null) {
                    setText(null);
                    setGraphic(rowStateGlyph(Icons.Icon.ADD));
                    getStyleClass().add("adb-grid__row-number--new");
                    return;
                }
                long number = offset + Math.max(0, getIndex()
                        - editBuffer.drafts().size()) + 1;
                RowIdentity identity = identity(row);
                if (identity != null && editBuffer.isDeleted(identity)) {
                    setText(Long.toString(number));
                    setGraphic(rowStateGlyph(Icons.Icon.CLOSE));
                    getStyleClass().add("adb-grid__row-number--deleted");
                } else if (identity != null && editBuffer.hasRowEdits(identity)) {
                    setText(Long.toString(number));
                    setGraphic(rowStateGlyph(Icons.Icon.DOT));
                    getStyleClass().add("adb-grid__row-number--dirty");
                } else {
                    setText(Long.toString(number));
                    setGraphic(null);
                }
            }
        });
        grid.getColumns().add(number);

        Set<String> hidden = savedHiddenColumns();
        for (ColumnDef definition : orderedColumns(schema)) {
            TableColumn<Map<String, Object>, Object> column = new TableColumn<>();
            column.setText(null);
            column.setUserData(definition.name());
            column.setGraphic(columnHeader(column, definition));
            column.setSortable(false);
            column.setEditable(schema.editable());
            double minWidth = readableMinWidth(definition);
            column.setPrefWidth(Math.max(minWidth, savedWidth(definition)));
            column.setMinWidth(minWidth);
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
        // ⭐ G1：列重建后按当前冻结数重新分配两表（解冻时是空操作）。
        applyFrozenColumns();
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
        // ⭐ 自愈：保存的顺序必须**恰好覆盖**这张表的全部列。
        //    否则就是一次不完整的保存（比如列渲染到一半时落了盘），
        //    会把列顺序搅乱 —— 宁可退回表结构顺序，也不能显示一个错乱的网格。
        if (ordered.size() != schema.columns().size()) {
            return schema.columns();
        }
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
        return Math.max(readableMinWidth(definition), Math.min(width, 1200));
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
            column.setPrefWidth(Math.max(readableMinWidth(definition), Math.round(width)));
        }
        settings.setInt(layoutKey("columns"), schema.columns().size());
    }

    /**
     * 这一列的表头至少要占多宽才能读出来。
     *
     * <p>列头是两行：列名 + 类型与约束（{@link #columnConstraintLabel}），
     * 右侧还有排序徽标与筛选漏斗。只按列名算会漏掉
     * 这些固定装饰，于是又回到 72px 的老问题。
     */
    /**
     * 行号列的状态标记（U10）：新增 / 待保存 / 标记删除。
     *
     * <p>以前是文本符号「＋ × ●」拼在行号前面 —— 符号随字体走、颜色只能整格继承，
     * 在中文行字体下三颗标记大小不一。现在是矢量图标，颜色由
     * {@code .adb-grid__row-number--*} 的 {@code -fx-stroke} 规则给。
     */
    private static javafx.scene.shape.SVGPath rowStateGlyph(Icons.Icon icon) {
        // Icons.of 已经挂 .adb-icon / .adb-icon--filled，这里不再叠一个只有 Java 侧认识的类。
        return Icons.of(icon, Icons.SIZE_DOT);
    }

    private double readableMinWidth(ColumnDef definition) {
        return ColumnWidths.dataColumnMin(definition.name(), columnConstraintLabel(definition));
    }

    /**
     * 列头第二行：类型 + 约束摘要（{@code number · 主键 · 自增} / {@code text · 非空}）。
     *
     * <p>⭐ U5（2026-10-26）：主键与 NOT NULL 以前挤在第一行，写成
     * {@code id  [PK]} 和 {@code name  •} 两套互不相干的语法 —— 那个圆点没有任何
     * 地方解释它是 NOT NULL，而且主键列本身也非空，却被 {@code [PK]} 顶掉，
     * 于是「非空」这件事在表头时有时无。约束统一并入第二行，
     * 第一行只放列名，和 {@code · 自增} 走同一种读法。
     */
    private String columnConstraintLabel(ColumnDef definition) {
        boolean autoIncrement = tableSchema != null
                && tableSchema.autoIncrement(definition.name());
        StringBuilder label = new StringBuilder(typeLabel(definition));
        if (definition.primaryKey()) {
            label.append(" · 主键");
        } else if (!definition.nullable()) {
            label.append(" · 非空");
        }
        if (autoIncrement) {
            label.append(" · 自增");
        }
        return label.toString();
    }

    private static int visualLength(String text) {
        return ColumnWidths.visualLength(text);
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
        int expected = tableSchema.columns().size();
        int seen = 0;
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
            seen++;
        }
        // ⭐ 只在**列齐了**的时候落盘 —— 半截的顺序会搅乱网格（见 orderedColumns）。
        if (seen != expected) {
            return;
        }
        settings.set(layoutKey("order"), order.toString());
        settings.set(layoutKey("hidden"), hidden.toString());
    }

    /**
     * ⭐ 打开列设置弹层（带列名搜索）。
     *
     * <p>表有几十列时，「一串勾选项的菜单」根本没法用 ——
     * 弹层里有搜索框，勾选控制显隐，底部两个动作恢复默认。
     */
    private void openColumnPanel() {
        if (tableSchema == null) {
            return;
        }
        if (columnPanel == null) {
            columnPanel = new ColumnSettingsPanel(
                    tableSchema.columns(),
                    (definition, visible) -> {
                        TableColumn<Map<String, Object>, Object> column =
                                columnByDefinition.get(definition.name());
                        if (column != null) {
                            column.setVisible(visible);
                            scheduleLayoutSave();
                        }
                    },
                    definition -> {
                        TableColumn<Map<String, Object>, Object> column =
                                columnByDefinition.get(definition.name());
                        return column != null && column.isVisible();
                    },
                    () -> {
                        columnByDefinition.values().forEach(column -> column.setVisible(true));
                        scheduleLayoutSave();
                        columnPanel.refresh();
                    },
                    () -> {
                        resetColumnLayout();
                        columnPanel.refresh();
                    });
        }
        columnPanel.resetSearch();
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        org.controlsfx.control.PopOver popOver = new org.controlsfx.control.PopOver();
        popOver.setDetachable(false);
        popOver.setArrowLocation(org.controlsfx.control.PopOver.ArrowLocation.TOP_LEFT);
        popOver.setContentNode(columnPanel);
        popOver.show(columnsButton, -8);    }

    private void refreshColumnMenu() {
        if (tableSchema == null) {
            return;
        }
        if (columnPanel != null) {
            columnPanel.refresh();
        }
    }

    // ─────────────────────────── G2：列拖拽重排 ───────────────────────────

    /** 可见的**数据列**（排除选择列与行号列 —— 那两列固定在左边）。 */
    private List<TableColumn<Map<String, Object>, ?>> dataColumns() {
        List<TableColumn<Map<String, Object>, ?>> result = new ArrayList<>();
        for (TableColumn<Map<String, Object>, ?> column : grid.getColumns()) {
            if (column.getUserData() instanceof String) {
                result.add(column);
            }
        }
        return result;
    }

    /**
     * 按**用户看到的顺序**列出全部数据列。
     *
     * <p>冻结后数据列分散在两张表里（左 = frozenGrid，右 = grid），
     * 所以只数 {@code grid} 会漏掉冻结区 —— 冻结序号必须跨两表计算。
     */
    private List<TableColumn<Map<String, Object>, ?>> dataColumnsOrdered() {
        List<TableColumn<Map<String, Object>, ?>> result = new ArrayList<>();
        for (TableColumn<Map<String, Object>, ?> column : frozenGrid.getColumns()) {
            if (column.getUserData() instanceof String) {
                result.add(column);
            }
        }
        for (TableColumn<Map<String, Object>, ?> column : grid.getColumns()) {
            if (column.getUserData() instanceof String) {
                result.add(column);
            }
        }
        return result;
    }

    /** 数据列在整体顺序里的下标；找不到返回 -1。 */
    private int indexOfDataColumn(String name) {
        List<TableColumn<Map<String, Object>, ?>> columns = dataColumnsOrdered();
        for (int i = 0; i < columns.size(); i++) {
            if (name.equals(columns.get(i).getUserData())) {
                return i;
            }
        }
        return -1;
    }

    /** 这一列当前是否在冻结区里。 */
    private boolean isColumnFrozen(String name) {
        return frozenColumnCount > 0 && indexOfDataColumn(name) < frozenColumnCount;
    }

    /**
     * 场景坐标落在哪个数据列上。
     *
     * @return 数据列的序号；落在固定列（选择列 / 行号列）上或不在网格里时返回 -1
     */
    private int dataColumnIndexAtSceneX(double sceneX) {
        double x = grid.localToScene(0, 0).getX();
        int index = -1;
        for (TableColumn<Map<String, Object>, ?> column : grid.getColumns()) {
            if (!column.isVisible()) {
                continue;
            }
            double width = Math.max(1, column.getWidth());
            if (sceneX < x + width) {
                // 落在固定列上 → 不参与重排
                return column.getUserData() instanceof String ? index : -1;
            }
            x += width;
            if (column.getUserData() instanceof String) {
                index++;
            }
        }
        return index;
    }

    /**
     * 把某列挪到数据列的第 target 位。
     *
     * <p>选择列与行号列**固定在左边**，不参与重排 —— 和 Navicat 一致。
     */
    private void moveColumnTo(TableColumn<Map<String, Object>, ?> column, int target) {
        List<TableColumn<Map<String, Object>, ?>> all = new ArrayList<>(grid.getColumns());
        int from = all.indexOf(column);
        if (from < 0) {
            return;
        }
        all.remove(from);

        // 目标在「数据列」序列里的位置 → 换算回 all 里的插入点
        int insertAt = all.size();
        int seen = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getUserData() instanceof String) {
                if (seen == target) {
                    insertAt = i;
                    break;
                }
                seen++;
            }
        }
        all.add(insertAt, column);
        grid.getColumns().setAll(all);
    }

    /** 把某列挪到数据列的第 target 位（按列名）。 */
    private void moveColumnTo(String name, int target) {
        TableColumn<Map<String, Object>, Object> column = columnByDefinition.get(name);
        if (column == null) {
            return;
        }
        moveColumnTo(column, target);
        scheduleLayoutSave();
    }

    /**
     * 把某列挪到数据列的第 target 位。
     *
     * <p>右键菜单与拖拽走的是**同一条**路；这个方法是给门禁用的精确入口
     * （真实鼠标轨迹在无头环境里不稳）。
     */
    void reorderColumn(String name, int target) {
        moveColumnTo(name, target);
    }

    /** 当前落盘的列顺序（逗号分隔；供门禁断言）。 */
    String savedColumnOrder() {
        return settings == null ? "" : settings.get(layoutKey("order"), "");
    }

    /** 把某列往左 / 往右挪一格（右键菜单用；拖不动的场景也能用）。 */
    private void nudgeColumn(String name, int delta) {
        TableColumn<Map<String, Object>, Object> column = columnByDefinition.get(name);
        if (column == null) {
            return;
        }
        List<TableColumn<Map<String, Object>, ?>> data = dataColumns();
        int index = data.indexOf(column);
        if (index < 0) {
            return;
        }
        int target = Math.max(0, Math.min(data.size() - 1, index + delta));
        if (target == index) {
            return;
        }
        moveColumnTo(column, target);
        scheduleLayoutSave();
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

    private javafx.scene.Node columnHeader(TableColumn<Map<String, Object>, Object> column,
                                       ColumnDef definition) {
        // ⭐ G6：标出自增列 —— 新增行时这一列**留空**就行，数据库自动赋值。
        //    不标出来，用户会以为得自己填，然后撞主键。
        // ⭐ U5：约束（主键 / 非空 / 自增）统一落在第二行，第一行只有列名。
        Label name = new Label(definition.name());
        name.setId("data-header-" + definition.name());
        name.getStyleClass().add("adb-grid__column-name");
        if (definition.comment() != null && !definition.comment().isBlank()) {
            // 约束已经在第二行写明白了，tooltip 只补「列注释」这一件第二行装不下的事。
            name.setTooltip(new Tooltip(definition.comment()));
        }

        Label type = new Label(columnConstraintLabel(definition));
        type.getStyleClass().add("adb-grid__column-type");

        Label sortBadge = new Label();
        sortBadge.getStyleClass().add("adb-grid__sort-badge");
        sortBadge.setVisible(false);
        sortBadge.setManaged(false);

        // ⭐ 只有图标的按钮：Tooltip（鼠标）+ accessibleText（读屏）都要有。
        Button funnel = IconButtons.icon(Icons.Icon.FILTER, "按候选值筛选 " + definition.name());
        funnel.setId("data-filter-" + definition.name());
        funnel.setFocusTraversable(false);
        funnel.getStyleClass().add("adb-grid__filter-button");
        funnel.setOnAction(event -> showFilterPanel(definition, funnel));
        funnel.setOnMouseClicked(javafx.event.Event::consume);

        VBox labels = new VBox(1, name, type);
        labels.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(4, labels, spacer, sortBadge, funnel);
        box.getStyleClass().add("adb-grid__header");
        box.setAlignment(Pos.CENTER_LEFT);
        // ⭐ G2 的可发现性：告诉用户列头能拖。
        name.setTooltip(new Tooltip(
                (name.getTooltip() == null || name.getTooltip().getText() == null
                        ? "" : name.getTooltip().getText() + " · ")
                        + "拖动列头可调整顺序；双击可自适应宽度"));
        box.setOnMouseClicked(event -> {
            if (event.isSecondaryButtonDown() || event.isConsumed()) {
                return;
            }
            if (event.getClickCount() == 2) {
                autoFitColumn(definition.name());
                event.consume();
                return;
            }
            // ⭐ 拖过就不算「点」—— 点列头是排序，拖列头是换顺序。
            if (Math.abs(event.getSceneX() - dragStartSceneX) < DRAG_THRESHOLD) {
                applySort(definition.name(), event.isShiftDown());
            }
            event.consume();
        });
        // ⭐ G2：列头**拖拽重排**。顺序本来就能持久化，只是没有运行时入口。
        box.setOnMousePressed(event -> {
            if (event.isSecondaryButtonDown()) {
                return;
            }
            draggedColumn = column;
            dragStartSceneX = event.getSceneX();
            dragLastSceneX = event.getSceneX();
        });
        box.setOnMouseDragged(event -> {
            if (draggedColumn == null) {
                return;
            }
            dragLastSceneX = event.getSceneX();
            int target = dataColumnIndexAtSceneX(dragLastSceneX);
            if (target >= 0) {
                moveColumnTo(draggedColumn, target);
            }
            event.consume();
        });
        box.setOnMouseReleased(event -> {
            if (draggedColumn == null) {
                return;
            }
            draggedColumn = null;
            // 落盘：新顺序要记住。
            scheduleLayoutSave();
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
            // ⭐ G2：右键也能改顺序 —— 拖不动的场景（触控板 / 窄列）有精确入口。
            MenuItem moveLeft = new MenuItem("向左移动一列");
            moveLeft.setId("data-move-left");
            moveLeft.setOnAction(action -> nudgeColumn(definition.name(), -1));
            MenuItem moveRight = new MenuItem("向右移动一列");
            moveRight.setId("data-move-right");
            moveRight.setOnAction(action -> nudgeColumn(definition.name(), 1));
            MenuItem moveFirst = new MenuItem("移到最前");
            moveFirst.setId("data-move-first");
            moveFirst.setOnAction(action -> moveColumnTo(definition.name(), 0));
            MenuItem moveLast = new MenuItem("移到最后");
            moveLast.setId("data-move-last");
            moveLast.setOnAction(action -> moveColumnTo(definition.name(),
                    dataColumns().size() - 1));

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
            // ⭐ UX-4：冻结入口收敛到列头右键。以前只能拖分隔条，
            //    分隔条看不见就等于功能不存在。
            MenuItem freezeHere = new MenuItem("冻结到此列");
            freezeHere.setId("data-freeze-here");
            boolean alreadyFrozen = isColumnFrozen(definition.name());
            freezeHere.setDisable(alreadyFrozen
                    || dataColumnsOrdered().size() - indexOfDataColumn(definition.name()) < 2);
            freezeHere.setOnAction(action -> setFrozenColumnCount(
                    indexOfDataColumn(definition.name()) + 1));
            MenuItem unfreeze = new MenuItem("取消冻结列");
            unfreeze.setId("data-unfreeze");
            unfreeze.setDisable(frozenColumnCount == 0);
            unfreeze.setOnAction(action -> setFrozenColumnCount(0));
            MenuItem copyName = new MenuItem("复制列名");
            copyName.setOnAction(action -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(definition.name());
                Clipboard.getSystemClipboard().setContent(content);
                feedback.status("已复制列名 " + definition.name());
            });
            menu.getItems().addAll(ascending, descending, clearSort,
                    new SeparatorMenuItem(),
                    moveLeft, moveRight, moveFirst, moveLast,
                    new SeparatorMenuItem(), filter, hide, fit,
                    freezeHere, unfreeze, copyName);
            // ⭐ 只有处理器真的跑起来才会有这个实例 ——
            //    测试拿它检查菜单内容，同时也就验了「handler 挂在列头上」。
            lastHeaderMenu = menu;
            menu.show(box, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        headers.put(definition.name(), new HeaderView(name, sortBadge, funnel));
        return box;
    }

    /** 最近一次列头右键**处理器实际建出来**的菜单；没弹过就是 null。 */
    ContextMenu headerMenuForTest() {
        return lastHeaderMenu;
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
        ColumnDef definition = columnDef(name);
        int max = visualLength(name);
        for (Map<String, Object> row : grid.getItems()) {
            Object value = row.get(name);
            // ⭐ UX-4：按**渲染出来的文本**量宽度，不按原始值。
            //   JSON 列显示的是摘要、BLOB 显示的是大小、NULL 显示的是 (NULL) ——
            //   量原始值会给出一格根本看不见的宽度（一个 2KB 的 JSON 摘要只有几十字符，
            //   原始值能把列撑到 420px 上限）。
            String text = definition == null
                    ? (value == null ? "(NULL)" : String.valueOf(value))
                    : CellRenderer.render(definition, value).text();
            max = Math.max(max, visualLength(text));
        }
        double minWidth = definition == null ? ColumnWidths.DATA_MIN : readableMinWidth(definition);
        column.setPrefWidth(Math.max(minWidth,
                ColumnWidths.contentWidth(max, 44, minWidth, 420)));
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
        // ⭐ 有筛选条件的列，筛选钮要**一直**看得见（那是状态，不是装饰）。
        java.util.Set<String> filtered = query.filters().stream()
                .map(FilterCondition::column)
                .collect(java.util.stream.Collectors.toSet());
        headers.forEach((column, header) -> header.filter().getStyleClass()
                .remove("adb-grid__filter-button--active"));
        headers.keySet().stream()
                .filter(filtered::contains)
                .forEach(column -> headers.get(column).filter().getStyleClass()
                        .add("adb-grid__filter-button--active"));
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
        // ⭐ 条件 chip：文字 + 一个「×」图标（矢量，不是字符）。
        Button button = new Button(text);
        button.setMnemonicParsing(false);
        button.setGraphic(Icons.of(Icons.Icon.CLOSE, Icons.SIZE_BADGE));
        button.setGraphicTextGap(4);
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
        // ⭐ G1：翻页换的是新的 items 实例，左表必须跟着换 ——
        //   否则左表还指着上一页那批行，两表就错位了。
        frozenGrid.setItems(grid.getItems());
        // ⭐ G4：换了一页数据，旧矩形的行下标就指向另一批行了 —— 必须清掉，
        //   否则用户会复制到一个「看着像选区、其实是新页数据」的矩形。
        if (rectangle != null) {
            rectangle = null;
            rectangleAnchor = null;
            rectangleDragging = false;
        }
        // ⭐ 行数变了，记录导航条上的「第 N / 共 M 行」要跟着更新。
        updateRecordPosition();
        // ⭐ G3：行变了，统计要跟着重算。
        updateSummary();
        grid.setVisible(!transpose);
        grid.setManaged(!transpose);
        // ⭐ G1：转置视图下不该露出冻结区（那是另一套列，冻了也没意义）。
        frozenGrid.setVisible(!transpose && frozenColumnCount > 0);
        frozenGrid.setManaged(!transpose && frozenColumnCount > 0);
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
            String title = draft != null ? "新增" : Long.toString(offset + i + 1);
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

    /** ⭐ G3：显示 / 隐藏「本页统计」。 */
    void setSummaryVisible(boolean value) {
        summaryVisible = value;
        summaryItem.setSelected(value);
        summaryLabel.setVisible(value);
        summaryLabel.setManaged(value);
        updateSummary();
    }

    /**
     * ⭐ G3：算本页的汇总 —— 行数 + 数值列的合计 / 平均 / 最小 / 最大。
     *
     * <p>算的是<b>当前页</b>（不是整表），所以标题里写明「本页」——
     * 整表统计要走 SQL 聚合，是另一件事。
     */
    private void updateSummary() {
        if (!summaryVisible) {
            summaryLabel.setText("");
            return;
        }
        List<Map<String, Object>> rows = grid.getItems();
        if (rows.isEmpty()) {
            summaryLabel.setText("本页 0 行");
            return;
        }
        StringBuilder text = new StringBuilder("本页 ").append(rows.size()).append(" 行");
        if (tableSchema != null) {
            for (ColumnDef definition : tableSchema.columns()) {
                if (definition.type() != ColumnType.NUMBER) {
                    continue;
                }
                double sum = 0;
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                int count = 0;
                for (Map<String, Object> row : rows) {
                    Object value = row.get(definition.name());
                    if (!(value instanceof Number number)) {
                        continue;
                    }
                    double d = number.doubleValue();
                    sum += d;
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                    count++;
                }
                if (count == 0) {
                    continue;
                }
                text.append(" · ").append(definition.name())
                        .append(" 合计 ").append(formatNumber(sum))
                        .append(" 平均 ").append(formatNumber(sum / count))
                        .append(" 最小 ").append(formatNumber(min))
                        .append(" 最大 ").append(formatNumber(max));
            }
        }
        summaryLabel.setText(text.toString());
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)
                && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.format("%.2f", value);
    }

    private void updateViewControls() {
        boolean hasData = !grid.getItems().isEmpty() || !transposeTable.getItems().isEmpty();
        viewButton.setDisable(tableSchema == null);
        exportButton.setDisable(!hasData);
        // ⭐ 导入的可用条件和导出**相反**：空表正是最需要导入的时候，
        //    所以只看「表结构读到没有」，不看「现在有没有行」。
        importButton.setDisable(tableSchema == null);
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
        // ⭐ 自增列留空即可 —— 明确告诉用户，别让他去手填主键。
        feedback.status(tableSchema != null && tableSchema.autoIncrementColumn() != null
                ? "已新增行 · 自增列（" + tableSchema.autoIncrementColumn() + "）留空即可"
                : "已新增行 · 填写后保存");
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
            feedback.status("已移除新增行 · " + io.agenticdb.ui.Shortcuts.of("Mod+Z") + " 可撤销");
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
            feedback.status("已标记删除 " + identity.display() + " · "
                    + io.agenticdb.ui.Shortcuts.of("Mod+Z") + " 可撤销");
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
            feedback.status("已标记删除 " + marked + " 行 · "
                    + io.agenticdb.ui.Shortcuts.of("Mod+Z") + " 可撤销");
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
                    + " 设为 " + (next == null ? "NULL" : "空串") + " · "
                    + io.agenticdb.ui.Shortcuts.of("Mod+Z") + " 可撤销");
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
        String text = value == null ? "" : String.valueOf(value);
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        lastCopiedText = text;
        feedback.status("已复制单元格值");
    }

    // ─────────────────────── G3：网格查找 / 替换 ───────────────────────

    /**
     * 建查找条：查找 / 下一个 / 替换为 / 替换 / 全部替换 / 区分大小写 / 关闭。
     *
     * <p>形态照抄查询页的查找条（同一套令牌），语义不同：查询页查的是编辑器里的
     * 字符流，这里查的是<b>网格单元格</b> —— 范围是当前页的<b>可见</b>数据列：
     * 隐藏列用户看不到，替换它等于在暗处动手，保存预览里才会突然冒出一句 UPDATE。
     */
    private void buildFindBar() {
        findBar.setId("data-find-bar");
        findBar.getStyleClass().add("adb-grid__find");
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setVisible(false);
        findBar.setManaged(false);

        findField.setId("data-find");
        findField.setPromptText("查找");
        findField.setPrefWidth(180);
        findField.setMinWidth(110);
        findField.getStyleClass().add("adb-grid__find-field");
        findField.setOnAction(event -> findNext());

        replaceField.setId("data-replace");
        replaceField.setPromptText("替换为");
        replaceField.setPrefWidth(180);
        replaceField.setMinWidth(110);
        replaceField.getStyleClass().add("adb-grid__find-field");

        findNextButton.setId("data-find-next");
        findNextButton.getStyleClass().add("adb-button");
        findNextButton.setOnAction(event -> findNext());

        replaceOneButton.setId("data-replace-one");
        replaceOneButton.getStyleClass().add("adb-button");
        replaceOneButton.setOnAction(event -> replaceCurrent());

        replaceAllButton.setId("data-replace-all");
        replaceAllButton.getStyleClass().add("adb-button");
        replaceAllButton.setOnAction(event -> replaceAllMatches());

        findCaseBox.setId("data-find-case");
        // 切换口径立刻报一次命中数 —— 勾它的人就是想看「命中变了多少」。
        findCaseBox.setOnAction(event -> recountMatches());

        Button close = IconButtons.icon(Icons.Icon.CLOSE, "关闭查找");
        close.setId("data-find-close");
        close.getStyleClass().add("adb-icon-button");
        close.setOnAction(event -> hideFindBar());

        findBar.getChildren().addAll(findField, findNextButton, replaceField,
                replaceOneButton, replaceAllButton, findCaseBox, close);
    }

    /**
     * 打开查找条：预填当前焦点格的值 —— 有选中就从选中开始找（Navicat 同款）。
     *
     * <p>转置视图不开：它是只读渲染，选中落在隐藏的网格里用户看不见，
     * 「找到了」就成了没有落点的谎话。
     */
    private void showFindBar() {
        if (transpose) {
            feedback.status("转置视图是只读的 —— 切回表格视图再查找");
            return;
        }
        findBar.setVisible(true);
        findBar.setManaged(true);
        if (findField.getText() == null || findField.getText().isEmpty()) {
            String focused = focusedCellText();
            if (focused != null && !focused.isEmpty()) {
                findField.setText(focused);
            }
        }
        Platform.runLater(() -> {
            findField.requestFocus();
            findField.selectAll();
        });
    }

    private void hideFindBar() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        grid.requestFocus();
    }

    /** 查找条当前是否展开（门禁读）。 */
    boolean findBarVisible() {
        return findBar.isVisible();
    }

    /**
     * 截图 / 验收（R32 G3）：打开查找条并填入 needle。
     *
     * <p>返回 false = 行还没灌进来（数据页异步加载），调用方继续轮询 ——
     * 与 {@code poseInteractionStates} 同一契约。查找的<b>推进</b>不在这摆：
     * 截图脚本真点 {@code #data-find-next}，那一帧才同时证「按钮能点、计数会走」。
     */
    boolean poseFindBar(String needle) {
        if (grid.getItems().isEmpty()) {
            return false;
        }
        showFindBar();
        findField.setText(needle);
        return true;
    }

    /**
     * 当前页某行某列<b>显示口径</b>的文本 —— 与 {@link ValueCell} 同源：
     * 编辑缓冲已合并（脏值看的是新值，不是库里的旧值）。门禁读。
     *
     * <p>不能读 items 原始 map：替换落的是缓冲，原始 map 到保存落库前一直不动。
     */
    String displayedCellText(int row, String column) {
        List<Map<String, Object>> rows = grid.getItems();
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        ColumnDef definition = columnDef(column);
        if (definition == null) {
            return null;
        }
        Object value = cellValue(rows.get(row), definition);
        return value == null ? null : String.valueOf(value);
    }

    /** 查找下一处：从当前焦点格<b>之后</b>找，到底回绕；状态栏报「第 x / y 个」。 */
    private void findNext() {
        if (transpose) {
            feedback.status("转置视图下不查找 —— 切回表格视图");
            return;
        }
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            feedback.status("输入要查找的内容");
            return;
        }
        List<int[]> matches = collectMatches(needle);
        if (matches.isEmpty()) {
            feedback.status("未找到：" + needle);
            return;
        }
        int[] cursor = focusedCellPosition();
        int from = 0;
        if (cursor != null) {
            // 从焦点格之后开始（焦点格本身已看过）；没有更靠后的就回绕。
            from = matches.size();
            for (int i = 0; i < matches.size(); i++) {
                int[] hit = matches.get(i);
                if (hit[0] > cursor[0]
                        || (hit[0] == cursor[0] && hit[1] > cursor[1])) {
                    from = i;
                    break;
                }
            }
            if (from >= matches.size()) {
                from = 0;
            }
        }
        int[] hit = matches.get(from);
        selectCell(hit[0], hit[1]);
        feedback.status("找到 " + needle + " · 第 " + (from + 1) + " / "
                + matches.size() + " 个匹配");
    }

    /**
     * 当前页里所有命中的格子（行下标, 可见列下标）—— 行主序，与眼睛扫表同序。
     *
     * <p>每次<b>现算</b>不缓存：翻页 / 替换 / 撤销都会改数据，缓存的下标会指到
     * 别的格子上（报「第 2/3 个」，点出来是另一条记录）。
     */
    private List<int[]> collectMatches(String needle) {
        boolean caseSensitive = findCaseBox.isSelected();
        List<int[]> hits = new ArrayList<>();
        List<TableColumn<Map<String, Object>, ?>> columns = rectangleColumns();
        List<Map<String, Object>> rows = grid.getItems();
        for (int row = 0; row < rows.size(); row++) {
            Map<String, Object> values = rows.get(row);
            for (int col = 0; col < columns.size(); col++) {
                Object value = cellValue(columns.get(col), values);
                // NULL 不参与：空 needle 已被拒，非空 needle 对 NULL 无从谈起。
                if (value != null && matches(String.valueOf(value), needle, caseSensitive)) {
                    hits.add(new int[] {row, col});
                }
            }
        }
        return hits;
    }

    /** 勾 / 去「区分大小写」后报一次命中数 —— 不动选择（动了反而看不出差别在哪）。 */
    private void recountMatches() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            return;
        }
        feedback.status(needle + " · 当前页共 " + collectMatches(needle).size() + " 个匹配");
    }

    /**
     * 替换当前格并前进到下一处（G3）。
     *
     * <p>焦点格不匹配时退化成「查找」—— 与主流编辑器一致：先点「替换」看它要动
     * 哪一格，而不是必须先手动命中一次。
     */
    private void replaceCurrent() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            feedback.status("输入要查找的内容");
            return;
        }
        if (!replaceAllowed()) {
            return;
        }
        boolean replaced = false;
        int[] cursor = focusedCellPosition();
        if (cursor != null && cellMatchesAt(cursor[0], cursor[1], needle)) {
            replaced = replaceOneAt(cursor[0], cursor[1], needle);
            if (replaced) {
                updateDirty();
                grid.refresh();
            }
        }
        findNext();
        if (replaced) {
            feedback.status("已替换 1 处 · " + io.agenticdb.ui.Shortcuts.of("Mod+Z")
                    + " 可撤销 · 已跳到下一处");
        }
    }

    /**
     * 全部替换：当前页所有命中的文本格一次写进缓冲 —— <b>一个撤销步</b>。
     *
     * <p>主键列与非文本列跳过并计数：主键是保护区（与批量改值同一口径）；
     * 非文本列做文本替换会产出 `2abc` 这类转不回去的值，宁可不碰。
     */
    private void replaceAllMatches() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            feedback.status("输入要查找的内容");
            return;
        }
        if (!replaceAllowed()) {
            return;
        }
        boolean caseSensitive = findCaseBox.isSelected();
        List<TableColumn<Map<String, Object>, ?>> columns = rectangleColumns();
        List<Map<String, Object>> rows = grid.getItems();
        List<EditBuffer.CellWrite> cells = new ArrayList<>();
        List<EditBuffer.DraftWrite> drafts = new ArrayList<>();
        int skipped = 0;
        for (int row = 0; row < rows.size(); row++) {
            Map<String, Object> values = rows.get(row);
            for (int col = 0; col < columns.size(); col++) {
                TableColumn<Map<String, Object>, ?> column = columns.get(col);
                Object original = cellValue(column, values);
                if (original == null
                        || !matches(String.valueOf(original), needle, caseSensitive)) {
                    continue;
                }
                String columnName = column.getUserData() instanceof String name ? name : null;
                ColumnDef definition = columnName == null ? null : columnDef(columnName);
                if (definition == null || definition.primaryKey()
                        || !textual(definition.type())) {
                    skipped++;
                    continue;
                }
                String text = String.valueOf(original);
                String next = replaceInText(text, needle);
                if (next.equals(text)) {
                    continue;
                }
                Long draft = draftId(values);
                if (draft != null) {
                    drafts.add(new EditBuffer.DraftWrite(draft, columnName, next));
                    continue;
                }
                RowIdentity identity = identity(values);
                if (identity != null && !editBuffer.isDeleted(identity)) {
                    cells.add(new EditBuffer.CellWrite(identity, columnName, original, next));
                }
            }
        }
        int changed = editBuffer.setValues(cells, drafts);
        if (changed == 0) {
            feedback.status(skipped > 0
                    ? "没有可替换的格子（跳过 " + skipped + " 处主键 / 非文本列）"
                    : "没有可替换的匹配");
            return;
        }
        updateDirty();
        grid.refresh();
        feedback.status("已替换 " + changed + " 处"
                + (skipped > 0 ? " · 跳过 " + skipped + " 处主键 / 非文本列" : "")
                + " · " + io.agenticdb.ui.Shortcuts.of("Mod+Z") + " 可撤销");
    }

    /** 替换的前置守卫：只读 / 转置 / 载入保存中都直接说清楚，不让点了没反应。 */
    private boolean replaceAllowed() {
        if (transpose) {
            feedback.status("转置视图是只读的 —— 切回表格视图再替换");
            return false;
        }
        if (tableSchema == null || !tableSchema.editable()) {
            feedback.status("这张表是只读的，不能替换");
            return false;
        }
        if (loading || saving) {
            feedback.status("正在载入 / 保存，稍后再试");
            return false;
        }
        return true;
    }

    /** 把一格写进编辑缓冲（不是直接落库）—— 与手动编辑同一条路：脏标记、撤销、保存预览自动成立。 */
    private boolean replaceOneAt(int row, int columnIndex, String needle) {
        Map<String, Object> values = grid.getItems().get(row);
        TableColumn<Map<String, Object>, ?> column = rectangleColumns().get(columnIndex);
        String columnName = column.getUserData() instanceof String name ? name : null;
        if (columnName == null) {
            return false;
        }
        ColumnDef definition = columnDef(columnName);
        if (definition == null || definition.primaryKey()) {
            feedback.status(columnName + " 是主键列，不参与替换");
            return false;
        }
        if (!textual(definition.type())) {
            feedback.status(columnName + " 不是文本列，不参与替换");
            return false;
        }
        Object original = cellValue(column, values);
        if (original == null) {
            return false;
        }
        String text = String.valueOf(original);
        String next = replaceInText(text, needle);
        if (next.equals(text)) {
            return false;
        }
        Long draft = draftId(values);
        if (draft != null) {
            return editBuffer.setDraftCell(draft, columnName, next);
        }
        RowIdentity identity = identity(values);
        return identity != null && !editBuffer.isDeleted(identity)
                && editBuffer.setCell(identity, columnName, original, next);
    }

    /** 替换文本里所有命中；替换串按<b>字面量</b>处理（`$1` 不当反引用）。 */
    private String replaceInText(String text, String needle) {
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        int flags = findCaseBox.isSelected() ? 0
                : java.util.regex.Pattern.CASE_INSENSITIVE
                        | java.util.regex.Pattern.UNICODE_CASE;
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle), flags)
                .matcher(text)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    private static boolean matches(String text, String needle, boolean caseSensitive) {
        return caseSensitive ? text.contains(needle)
                : text.toLowerCase(java.util.Locale.ROOT)
                        .contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private static Object cellValue(TableColumn<Map<String, Object>, ?> column,
                                    Map<String, Object> row) {
        javafx.beans.value.ObservableValue<?> observable = column.getCellObservableValue(row);
        return observable == null ? null : observable.getValue();
    }

    /** 当前焦点格在「可见数据列」坐标系里的位置 (行, 列下标)；焦点不在数据格 → null。 */
    private int[] focusedCellPosition() {
        TablePosition<Map<String, Object>, ?> focused =
                (TablePosition<Map<String, Object>, ?>) grid.getFocusModel().getFocusedCell();
        if (focused == null || focused.getRow() < 0
                || focused.getRow() >= grid.getItems().size()) {
            return null;
        }
        int col = rectangleColumns().indexOf(focused.getTableColumn());
        return col < 0 ? null : new int[] {focused.getRow(), col};
    }

    /** 焦点格的显示文本；没有焦点格 → null（打开查找条时用来预填）。 */
    private String focusedCellText() {
        TablePosition<Map<String, Object>, ?> focused =
                (TablePosition<Map<String, Object>, ?>) grid.getFocusModel().getFocusedCell();
        if (focused == null || focused.getRow() < 0 || focused.getTableColumn() == null) {
            return null;
        }
        javafx.beans.value.ObservableValue<?> observable =
                focused.getTableColumn().getCellObservableValue(focused.getRow());
        Object value = observable == null ? null : observable.getValue();
        return value == null ? null : String.valueOf(value);
    }

    /** 选中并滚动到指定格 —— 查找的落点（行与列都滚到位，宽表不迷路）。 */
    private void selectCell(int row, int columnIndex) {
        List<TableColumn<Map<String, Object>, ?>> columns = rectangleColumns();
        if (row < 0 || row >= grid.getItems().size() || columnIndex < 0
                || columnIndex >= columns.size()) {
            return;
        }
        TableColumn<Map<String, Object>, ?> column = columns.get(columnIndex);
        grid.getSelectionModel().clearAndSelect(row, column);
        grid.getFocusModel().focus(row, column);
        grid.scrollTo(row);
        grid.scrollToColumn(column);
    }

    private boolean cellMatchesAt(int row, int columnIndex, String needle) {
        List<TableColumn<Map<String, Object>, ?>> columns = rectangleColumns();
        if (row < 0 || row >= grid.getItems().size() || columnIndex < 0
                || columnIndex >= columns.size()) {
            return false;
        }
        Object value = cellValue(columns.get(columnIndex), grid.getItems().get(row));
        return value != null
                && matches(String.valueOf(value), needle, findCaseBox.isSelected());
    }

    // ─────────────── G4：单元格区域选择 ───────────────

    /**
     * 参与矩形选区的列 = 可见的**数据列**。
     *
     * <p>行号列与勾选列没有 {@code userData}，天然被排除 —— 框选时用户框的是数据，
     * 不是行号。用「可见」而不是「全部」是因为隐藏的列用户根本看不到，
     * 若按全部列算下标，隐藏一列就会让矩形整体错位。
     */
    private List<TableColumn<Map<String, Object>, ?>> rectangleColumns() {
        List<TableColumn<Map<String, Object>, ?>> columns = new ArrayList<>();
        for (TableColumn<Map<String, Object>, ?> column : grid.getVisibleLeafColumns()) {
            if (column.getUserData() != null) {
                columns.add(column);
            }
        }
        return columns;
    }

    /** 这一列在「可见数据列」里的下标；不是数据列、或不可见 → -1（不参与矩形）。 */
    private int rectangleColumnIndex(TableColumn<?, ?> column) {
        if (column == null || column.getUserData() == null || !column.isVisible()) {
            return -1;
        }
        List<TableColumn<Map<String, Object>, ?>> columns = rectangleColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i) == column) {
                return i;
            }
        }
        return -1;
    }

    /** 这个坐标是否落在矩形选区内。 */
    private boolean inRectangle(int row, int column) {
        return rectangle != null && row >= 0 && column >= 0 && rectangle.contains(row, column);
    }

    /**
     * ⭐ UX-4：把「当前格」盯下来，用样式类标出来。
     *
     * <p>为什么不能用 CSS 的 {@code .table-cell:focused}：
     * {@code TableCell.updateFocus()}（JavaFX 25 源码）在非 cell-selection 模式下
     * 第一件事就是 {@code setFocused(false)} 然后 return ——
     * 本表用 MULTIPLE（行选），所以**任何** {@code .table-cell:focused} 选择器
     * 在这张表上永远不匹配。实测：鼠标点进单元格，
     * {@code table.isFocused()=true}、{@code focusModel.getFocusedCell()} 有值、
     * 单元格自己 {@code isFocused()=false}。
     * 结论：焦点必须自己标，否则键盘用户完全看不见光标。
     */
    private void bindFocusCursor(TableView<Map<String, Object>> view) {
        view.getFocusModel().focusedCellProperty().addListener((observable, was, now) -> {
            int row = now == null ? -1 : now.getRow();
            String column = null;
            if (now != null && now.getTableColumn() != null
                    && now.getTableColumn().getUserData() instanceof String name) {
                column = name;
            }
            if (row == cursorRow && Objects.equals(column, cursorColumnName)) {
                return;
            }
            cursorRow = row;
            cursorColumnName = column;
            // TableView 不会自己因为 focusModel 变了重画格子。
            grid.refresh();
            frozenGrid.refresh();
        });
    }

    /**
     * 这一格是不是当前光标格。
     *
     * <p>行号列 / 勾选列没有 {@code userData}，拿不到列名 → 不参与，
     * 光标只在数据列之间走。
     */
    private boolean isCursor(int row, String column) {
        return cursorRow >= 0 && column != null
                && row == cursorRow && column.equals(cursorColumnName);
    }

    /**
     * 框选一个矩形（两个对角，行列自动取 min/max，越界夹到表格内）。
     *
     * <p>公开是为了两件事：Shift 扩展选区，以及门禁能**不依赖像素坐标**地构造矩形
     * （headless 下模拟鼠标拖选既慢又脆，而这里要验的是「复制的内容对不对」）。
     */
    public void selectRectangle(int fromRow, int fromColumn, int toRow, int toColumn) {
        int rows = grid.getItems().size();
        int columns = rectangleColumns().size();
        if (rows == 0 || columns == 0) {
            clearRectangleSelection();
            return;
        }
        rectangle = new GridRectangle(
                Math.max(0, Math.min(rows - 1, fromRow)),
                Math.max(0, Math.min(columns - 1, fromColumn)),
                Math.max(0, Math.min(rows - 1, toRow)),
                Math.max(0, Math.min(columns - 1, toColumn)));
        refreshRectangle();
    }

    /** 取消矩形选区（翻页、重载、Esc 都会走这里）。 */
    public void clearRectangleSelection() {
        boolean had = rectangle != null;
        rectangle = null;
        rectangleAnchor = null;
        rectangleDragging = false;
        if (had) {
            refreshRectangle();
        }
    }

    public boolean hasRectangleSelection() {
        return rectangle != null;
    }

    /** 矩形尺寸（行 × 列）；没有选区返回 0。 */
    public String rectangleSize() {
        return rectangle == null ? "" : rectangle.rowCount() + " × " + rectangle.columnCount();
    }

    /**
     * 矩形里的文本（TSV），**不含表头**。
     *
     * <p>不含表头是刻意的：用户框住的就是那块数据，粘到 Excel 里要的就是那块数据；
     * 复制整行的那条路径（⌥⌘C）才带表头，两者用途不同。
     */
    public String rectangleText() {
        if (rectangle == null) {
            return "";
        }
        List<TableColumn<Map<String, Object>, ?>> columns = rectangleColumns();
        StringBuilder text = new StringBuilder();
        for (int row = rectangle.minRow(); row <= rectangle.maxRow(); row++) {
            Map<String, Object> data = grid.getItems().get(row);
            for (int column = rectangle.minColumn();
                 column <= rectangle.maxColumn(); column++) {
                if (column > rectangle.minColumn()) {
                    text.append('\t');
                }
                text.append(cellText(data, columns.get(column)));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** 单元格的显示文本 —— 走 {@link #cellValue}，草稿值 / 渲染规则与表格里看到的保持一致。 */
    private String cellText(Map<String, Object> row,
                            TableColumn<Map<String, Object>, ?> column) {
        String name = String.valueOf(column.getUserData());
        ColumnDef definition = columnDef(name);
        Object value = definition == null ? row.get(name) : cellValue(row, definition);
        return value == null ? "" : String.valueOf(value);
    }

    /** ⭐ G4：把矩形复制成 TSV。⌘C 在存在矩形选区时走这里。 */
    private void copyRectangleAsTsv() {
        if (rectangle == null) {
            return;
        }
        String text = rectangleText();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        lastCopiedText = text;
        feedback.status("已复制 " + rectangle.rowCount() + " × "
                + rectangle.columnCount() + " 区域");
    }

    /** 矩形变了要重算 cell 样式（{@code TableView} 不会自己重画）。 */
    private void refreshRectangle() {
        grid.refresh();
        // ⭐ G1：冻结区里的格子也要重画，否则框选高亮只出现在右半张表。
        frozenGrid.refresh();
        if (rectangle != null) {
            feedback.status("已选 " + rectangle.rowCount() + " × "
                    + rectangle.columnCount() + " 区域 · " + io.agenticdb.ui.Shortcuts.of("Mod+C") + " 复制");
        }
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
        lastCopiedText = text.toString();
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
        lastCopiedText = sql.toString();
        feedback.status("已复制 " + scope + " " + rows.size() + " 行 INSERT");
    }

    /** 打开导出向导：整表 / 当前页 / 选中行，CSV/TSV/JSON/SQL。 */
    private void openExportWizard() {
        if (tableSchema == null) {
            return;
        }
        List<String> names = tableSchema.columns().stream().map(ColumnDef::name).toList();
        ExportWizard.Scope scope = grid.getSelectionModel().getSelectedItems().isEmpty()
                ? ExportWizard.Scope.WHOLE_TABLE : ExportWizard.Scope.SELECTED;
        Path target = Path.of(System.getProperty("user.home"), tableName + ".csv");
        ExportWizard wizard = new ExportWizard(session, tableName, names, scope, target,
                dbExecutor, message -> feedback.status(message));
        wizard.show(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * ⭐ UX-3：数据页「导入…」—— 直接开导入向导，目标表预选本表。
     *
     * <p>以前导数据只能绕侧栏「导入 / 导出」→ 再在表清单里重新找一遍这张表。
     * 用户正盯着 users 表想补数据，入口就该在这张表上。
     *
     * <p>候选表要查库，所以走 {@code dbExecutor}；拉不到清单也照样能往本表导 ——
     * 那样只是下拉框里少几个别的表，不该挡住主用途。
     */
    private void openImportWizard() {
        if (tableSchema == null) {
            return;
        }
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return schemaService.listObjects(session).stream()
                        .filter(object -> object.kind() == SchemaService.ObjectKind.TABLE)
                        .map(SchemaService.SchemaObject::name)
                        .toList();
            }
        };
        task.setOnSucceeded(event -> showImportWizard(task.getValue()));
        task.setOnFailed(event -> {
            feedback.status("表清单读取失败，导入向导只列出本表");
            showImportWizard(List.of());
        });
        dbExecutor.execute(task);
    }

    private void showImportWizard(List<String> listedTables) {
        createImportWizard(listedTables).show(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * 组装导入向导（<b>不弹出</b>）：当前表排到候选第一位并预选。
     *
     * <p>单独拆出来是因为 {@code ImportWizard#show} 是模态的，
     * 一点就阻塞 FX 线程 —— 门禁要断言「预选对了」只能走这个接缝。
     */
    ImportWizard createImportWizard(List<String> listedTables) {
        List<String> candidates = new ArrayList<>();
        candidates.add(tableName);
        for (String table : listedTables) {
            if (!table.equals(tableName)) {
                candidates.add(table);
            }
        }
        return new ImportWizard(session, tableName, tableSchema.columns(), List.copyOf(candidates),
                null, dbExecutor, message -> feedback.status(message));
    }

    /** P2-3：历史数据来源（外壳按当前表筛选后喂进来）。 */
    void setHistorySource(java.util.function.Supplier<List<QueryHistory.Entry>> source) {
        this.historySource = source == null ? List::of : source;
    }

    /** P2-3：选中一条历史后做什么（外壳负责开查询页）。 */
    void setOnHistoryPick(Consumer<String> onPick) {
        this.onHistoryPick = onPick == null ? sql -> { } : onPick;
    }

    /** 重建「历史」菜单项；弹层展开时调用，测试也走这里。 */
    void rebuildHistoryMenu() {
        // 概览里的「最近操作」与这个菜单是同一份历史，一起重建。
        renderOverviewRecent();
        historyButton.getItems().clear();
        List<QueryHistory.Entry> entries = historySource.get();
        if (entries.isEmpty()) {
            MenuItem none = new MenuItem("还没有执行过针对本表的 SQL");
            none.setId("data-history-empty");
            none.setDisable(true);
            historyButton.getItems().add(none);
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            QueryHistory.Entry entry = entries.get(i);
            MenuItem item = new MenuItem(entry.summary());
            item.setId("data-history-" + i);
            item.setOnAction(event -> onHistoryPick.accept(entry.sql()));
            historyButton.getItems().add(item);
        }
    }

    /**
     * ⭐ UX-3：工具条主操作 = 图标 + 文字 + tooltip + accessibleText，
     * 同时登记进「窄窗口收字」名单。
     */
    private <T extends ButtonBase> T toolbarOp(T button, Icons.Icon icon, String text, String hint) {
        IconButtons.labeled(button, icon, text, hint);
        toolbarLabels.put(button, text);
        // ⭐ U4：文字按钮一律不许被 HBox 压扁（「保存」被挤成图标 = 认不出是保存）。
        // 放不下时走上面那条实测判据：整排退回图标 + tooltip，而不是压某个按钮。
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /**
     * 改工具条主操作的文字（「保存 3」这类脏计数用它）。
     *
     * <p>⚠️ 不许直接 {@code setText}：{@link #toolbarLabels} 是收字/复原的唯一真相，
     * 绕过它改文字，窄窗口里收起的按钮会在下一次刷新时自己长出文字。
     */
    private void setToolbarLabel(ButtonBase button, String text) {
        toolbarLabels.put(button, text);
        boolean showText = toolbarTextVisible == null || toolbarTextVisible;
        button.setText(showText ? text : "");
        if (showText) {
            // 「保存 3」比「保存」宽 —— 改完文字要重判这一排还放不放得下（U4）。
            applyToolbarLabels();
        }
    }

    /** 组与组之间的竖分隔线（样式在 {@code .adb-toolbar__separator}）。 */
    private Region toolbarSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("adb-toolbar__separator");
        return separator;
    }

    /** 把「窗口宽度 → 工具条收字」接上；还没进窗口时先按带文字渲染。 */
    private void bindToolbarLabels() {
        toolbar.widthProperty().addListener(toolbarWidthListener);
        sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene != null && scene.getWindow() != null) {
                attachWindowWidth(scene.getWindow());
            }
        });
        if (getScene() != null && getScene().getWindow() != null) {
            attachWindowWidth(getScene().getWindow());
        } else {
            windowAllowsLabels = true;
            applyToolbarLabels();
        }
    }

    private void attachWindowWidth(Window window) {
        if (boundWindowWidth != null) {
            boundWindowWidth.removeListener(windowWidthListener);
        }
        boundWindowWidth = window.widthProperty();
        boundWindowWidth.addListener(windowWidthListener);
        windowAllowsLabels = boundWindowWidth.getValue().doubleValue() >= TOOLBAR_LABEL_MIN_WIDTH;
        applyToolbarLabels();
    }

    /**
     * 宽窗口给文字、窄窗口退回图标 + tooltip。
     *
     * <p>⚠️ 收字时补上 {@code adb-button--icon}（固定 30px 宽），
     * 展开时必须摘掉 —— 留着的话「保存」会被钉成 30px，文字直接看不见。
     *
     * <p>⭐ U4：光按<b>窗口</b>宽度假装放得下是不够的 —— 窗口宽 ≠ 工具条宽
     * （侧栏、边距、搜索框都要吃宽度）。实测过 1100px 档：工具条给「筛选」60px，
     * 它要 71px，HBox 就把文字压成「筛 …」。所以加一条<b>工具条自己说的算</b>的判据：
     * 只有这一排带文字真的放得下，才给文字。
     */
    private void applyToolbarLabels() {
        boolean showText = windowAllowsLabels
                // 还没布局过时（宽度 0）没有可信的实测值，先按窗口档位走。
                && (toolbar.getWidth() <= 0
                        || toolbar.getWidth() + 0.5 >= labelRequirement());
        if (toolbarTextVisible != null && toolbarTextVisible.booleanValue() == showText) {
            return;
        }
        toolbarTextVisible = showText;
        toolbarLabels.forEach((button, text) -> {
            button.setText(showText ? text : "");
            button.getStyleClass().remove("adb-button--icon");
            if (!showText) {
                button.getStyleClass().add("adb-button--icon");
            }
        });
    }

    /**
     * 标签全开时工具条<b>最少</b>要多宽。
     *
     * <p>收着字也要量得出来：临时把文字装回去算一次再还原。
     * 文字按钮的 {@code minWidth} 被钉成 {@code USE_PREF_SIZE}（U4：不许把「筛选」压成「筛 …」），
     * 所以这一趟量到的是「每个按钮文字完整 + 搜索框退到它自己的下限」。
     *
     * <p>⚠️ 不能用 {@code toolbar.prefWidth(-1)}：那会按搜索框的 <b>180px 偏好宽</b>记账，
     * 凭空多要 70px。1280 窗口实测工具条只有 1026px，用 prefWidth 判就会把
     * 「其实挤得动搜索框」误判成「放不下文字」，整排退成图标。
     * 真的连下限都不够时，HBox 自己会把搜索框压到 110px —— 那才是这一排该付的代价。
     */
    private double labelRequirement() {
        boolean collapsed = toolbarTextVisible != null && !toolbarTextVisible;
        if (collapsed) {
            toolbarLabels.forEach(ButtonBase::setText);
        }
        double needed = rowMinimumWidth(toolbar);
        if (collapsed) {
            toolbarLabels.keySet().forEach(button -> button.setText(""));
        }
        return needed;
    }

    /** 一个横排在其<b>托管</b>子节点都不折叠的前提下最少要多宽（未托管的按钮不占位、不计宽）。 */
    private static double rowMinimumWidth(HBox row) {
        List<javafx.scene.Node> managed = new ArrayList<>();
        for (javafx.scene.Node child : row.getChildren()) {
            if (child.isManaged()) {
                managed.add(child);
            }
        }
        double total = Math.max(0, row.getSpacing()) * Math.max(0, managed.size() - 1);
        for (javafx.scene.Node child : managed) {
            double min = child.minWidth(-1);
            total += Double.isNaN(min) || min < 0 ? child.prefWidth(-1) : min;
        }
        return total;
    }

    private void exportCsv() {
        writeCsv(List.copyOf(grid.getItems()), "当前页");
    }

    /** ⭐ G8：导出选中行。 */
    private void exportSelectedCsv() {
        List<Map<String, Object>> selected =
                List.copyOf(grid.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            feedback.status("没有选中的行");
            return;
        }
        writeCsv(selected, "选中行");
    }

    private void writeCsv(List<Map<String, Object>> rows, String scope) {
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
        chooser.setTitle("导出" + scope + "为 CSV");
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
            feedback.status("已导出" + scope + " " + rows.size() + " 行 → " + path);
            feedback.toast("已导出" + scope + " " + rows.size() + " 行 CSV");
        } catch (IOException e) {
            showErrorDetails("导出失败", e, null);
            feedback.status("导出失败：" + readable(e));
        }
    }

    /**
     * 单元格编辑器打开时的初始文本。
     *
     * <p><b>必须是原值，不能是展示文本。</b>
     * 展示文本为了可读性会做收敛（浮点噪声 {@code 22.990000000000002 → 22.99}，
     * 见 {@link CellRenderer#doubleText(double)}）。如果这里回填收敛后的文本，
     * 用户一进一出就把精度真正弄丢了 —— 从「显示不好看」升级成「数据损坏」。
     * 有 {@code NumberFormattingTest} 守着。
     */
    static String editorText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 导出到 CSV 的取值 —— 同 {@link #editorText(Object)}，走原值不走展示值。
     */
    static String csvValue(Object value) {
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

    /**
     * ⭐ UX-5-F：把「选中这一行 + 光标落在这一格 + 打开这格的详情抽屉」三个交互态一次摆出来。
     *
     * <p>为什么需要它：这三个状态只有真的点下去才出现，而截图脚本只能靠启动开关开页、
     * 靠 {@code ADB_SHOTS_CLICK} 点一个 id —— 没有「点进某一行某一格」这条路径，
     * 于是 {@code shots.sh} 的帧里<b>一帧都没拍到</b>选中态、光标环和抽屉
     * （{@code p2-users} 只拍到竖线和 {@code (NULL)}）。
     * 「全绿的门禁 + 从没被看见的界面」正是 UX-4 那颗灰星漏掉的形状。
     *
     * <p>⚠️ 摆状态必须走**真路径**，不许直接糊样式类：
     * <ul>
     *   <li>光标经 {@code focusModel.focus(..)} → 由 {@link #bindFocusCursor} 的监听自己标格子；
     *       直接 {@code add("adb-grid__cell--cursor")} 的话，截图好看和键盘真有光标
     *       就成了两件互不相干的事；</li>
     *   <li>抽屉经 {@link #showDetails} —— 和右键菜单「查看详情」是同一个方法。</li>
     * </ul>
     *
     * @param columnName 列名（表结构里的名字，不是界面标题）；行越界会夹到边界行
     * @return 是否摆出来了。数据还没灌进来、列不存在或列被隐藏 → false，
     *         调用方自己决定重试还是放弃，绝不静默摆出一个半套状态。
     */
    public boolean poseInteractionStates(int row, String columnName) {
        List<Map<String, Object>> items = grid.getItems();
        if (items.isEmpty() || columnName == null) {
            return false;
        }
        TableColumn<Map<String, Object>, Object> column = columnByDefinition.get(columnName);
        if (column == null || !column.isVisible()) {
            return false;
        }
        ColumnDef definition = tableSchema == null ? null : tableSchema.columns().stream()
                .filter(candidate -> candidate.name().equals(columnName))
                .findFirst().orElse(null);
        if (definition == null) {
            return false;
        }
        int at = Math.max(0, Math.min(items.size() - 1, row));
        grid.getSelectionModel().clearSelection();
        grid.getSelectionModel().select(at);
        grid.scrollTo(at);
        grid.getFocusModel().focus(at, column);
        showDetails(items.get(at), definition);
        return true;
    }

    /** 打开详情抽屉；测试与右键菜单共用。 */
    void showDetails(Map<String, Object> row, ColumnDef definition) {
        if (definition == null) {
            return;
        }
        detailsRow = row;
        detailsDefinition = definition;
        detailsDrawer.showDetails(definition, cellValue(row, definition), row, tableName);
        feedback.status("详情：" + definition.name());
    }

    /**
     * 抽屉里的多行编辑器允许改哪一格：可写表 + 文本类列 + 非主键 + 不在加载／保存中。
     *
     * <p>主键排除的理由和格子里一样 —— 改主键等于改「这行是谁」，
     * UPDATE 的 WHERE 会跟着失效，不该在顺手改长文本时发生。
     */
    private boolean canEditTextCell(ColumnDef definition) {
        return definition != null && tableSchema != null && tableSchema.editable()
                && !loading && !saving && !definition.primaryKey()
                && textual(definition.type());
    }

    /** 应用抽屉编辑器的文本：和单元格内联编辑写进同一个编辑缓冲。 */
    private void applyDetailsEdit(String text) {
        Map<String, Object> row = detailsRow;
        ColumnDef definition = detailsDefinition;
        if (row == null || !canEditTextCell(definition)) {
            return;
        }
        try {
            Object next = parseCellValue(definition, text);
            if (next == null && !definition.nullable()) {
                feedback.status("无效值：" + definition.name() + " 不允许 NULL");
                return;
            }
            setRowValue(row, definition, next);
            // 用新值重开抽屉：编辑态自动退出，看到的也按渲染规则收敛过。
            showDetails(row, definition);
            grid.refresh();
            frozenGrid.refresh();
        } catch (IllegalArgumentException e) {
            feedback.status("无效值：" + e.getMessage());
        }
    }

    private final class ValueCell extends TableCell<Map<String, Object>, Object> {
        private static final List<String> STYLES = List.of(
                "adb-grid__cell--null", "adb-grid__cell--empty", "adb-grid__cell--number",
                "adb-grid__cell--boolean", "adb-grid__cell--binary", "adb-grid__cell--dirty",
                "adb-grid__cell--json", "adb-grid__cell--enum", "adb-grid__cell--date",
                "adb-grid__cell--long-text", "adb-grid__cell--rect", "adb-grid__cell--cursor");

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
            // ⭐ G4：按下定锚点、拖动扩展矩形、松开收尾。
            //   每个 cell 自己知道 (行, 可见数据列下标)，拖动经过时这里就会触发 ——
            //   不需要去算像素坐标，那是 「坐标一变测试就红」 的老路。
            setOnMousePressed(event -> {
                int row = getIndex();
                int column = rectangleColumnIndex(getTableColumn());
                if (isEmpty() || row < 0 || column < 0) {
                    return;
                }
                if (event.isShiftDown() && rectangleAnchor != null) {
                    // Shift + 点击 = 从锚点扩展到这一格（Excel 的选区语义）
                    selectRectangle(rectangleAnchor.row(), rectangleAnchor.column(),
                            row, column);
                    return;
                }
                rectangleAnchor = new GridCell(row, column);
                rectangleDragging = true;
                selectRectangle(row, column, row, column);
            });
            setOnMouseDragged(event -> {
                if (!rectangleDragging || rectangleAnchor == null) {
                    return;
                }
                int row = getIndex();
                int column = rectangleColumnIndex(getTableColumn());
                if (isEmpty() || row < 0 || column < 0) {
                    return;
                }
                selectRectangle(rectangleAnchor.row(), rectangleAnchor.column(),
                        row, column);
            });
            setOnMouseReleased(event -> rectangleDragging = false);
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
            // ⭐ G4：矩形选区高亮。放在所有 return 分支**之前** ——
            //   布尔列会渲染成 CheckBox 并提前 return，晚加就漏掉那一格。
            if (inRectangle(getIndex(), rectangleColumnIndex(getTableColumn()))) {
                getStyleClass().add("adb-grid__cell--rect");
            }
            // ⭐ UX-4：当前格（键盘光标）。同样放在所有 return 分支之前 ——
            //   布尔列渲染成 CheckBox 会提前 return，晚加就漏掉那一格。
            if (isCursor(getIndex(), definition.name())) {
                getStyleClass().add("adb-grid__cell--cursor");
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
                    toggle.setAccessibleText("切换布尔值");
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
            // ⭐ U9：只读布尔用图标（check / x），不再用文本 ✓/✗ —— 文本字形不吃主题、
            //   不吃字号，还会和「内容本身就是 ✓ 的文本」撞车。可编辑态仍是 CheckBox。
            if (rendered.icon() != null) {
                setText(null);
                setGraphic(CellRenderer.checkGlyph(rendered.icon()));
                setAlignment(Pos.CENTER);
            } else {
                setText(rendered.text());
            }
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
            editor = new TextField(editorText(value));
            editor.getStyleClass().add("adb-grid__editor");
            editor.setOnAction(event -> commitEditor());
            editor.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    event.consume();
                    return;
                }
                // ⭐ G5：编辑完直接跳到下一格 —— Tab 右移一格、↓ 下一行同列、
                //    Shift+Tab 左移一格。这是所有数据库客户端的基本操作。
                if (event.getCode() == KeyCode.TAB
                        || event.getCode() == KeyCode.DOWN
                        || event.getCode() == KeyCode.UP) {
                    commitEditor();
                    moveAfterEdit(event.getCode(), event.isShiftDown());
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

        /**
         * ⭐ G5：提交后把焦点移到下一格。
         *
         * <p>Tab → 右边一格；Shift+Tab → 左边一格；↓ → 下一行同一列；↑ → 上一行同一列。
         */
        private void moveAfterEdit(KeyCode code, boolean shift) {
            int columnIndex = -1;
            List<TableColumn<Map<String, Object>, ?>> columns = grid.getColumns();
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i) == getTableColumn()) {
                    columnIndex = i;
                    break;
                }
            }
            if (columnIndex < 0) {
                return;
            }
            int rowIndex = grid.getSelectionModel().getSelectedIndex();
            int targetColumn = columnIndex;
            int targetRow = rowIndex;
            switch (code) {
                case TAB -> targetColumn = shift ? columnIndex - 1 : columnIndex + 1;
                case DOWN -> targetRow = rowIndex + 1;
                case UP -> targetRow = rowIndex - 1;
                default -> {
                    return;
                }
            }
            if (targetColumn < 0 || targetColumn >= columns.size()) {
                // 到头了就停在原格，不做无意义的移动。
                return;
            }
            if (targetRow < 0 || targetRow >= grid.getItems().size()) {
                return;
            }
            TableColumn<Map<String, Object>, ?> column = columns.get(targetColumn);
            grid.getSelectionModel().clearAndSelect(targetRow, column);
            grid.scrollTo(targetRow);
            grid.requestFocus();
            // 直接开始编辑下一格 —— 连续录入不用再点一次。
            grid.edit(targetRow, column);
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
                    : deleted ? "已标记删除（" + io.agenticdb.ui.Shortcuts.of("Mod+Z") + " 撤销）" : "删除行");
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

    /** 最近一次复制出去的文本（矩形复制 / 整行复制都会记）。 */
    public String lastCopiedText() {
        return lastCopiedText;
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
        // ⭐ V-12：统一走错误归类 —— 给出「发生了什么 + 建议怎么办」，
        //    而不是把驱动的英文原文直接甩给用户。
        return io.agenticdb.safety.ErrorClassifier.classify(error).message();
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
