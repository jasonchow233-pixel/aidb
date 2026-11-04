package io.agenticdb.ui.shell;

import io.agenticdb.editing.EditBuffer;
import io.agenticdb.editing.EditSqlBuilder;
import io.agenticdb.editing.EditValues;
import io.agenticdb.editing.RowIdentity;
import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.DataSqlBuilder;
import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.introspect.SortSpec;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.ui.I18n;
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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
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

        /**
         * 当前选中的行数（4.5-11）。
         *
         * <p>状态栏的「选择」段以前永远是「未选择」——这个 setter 零调用。
         */
        default void selection(int count) {
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
    /**
     * ⭐ N19：工具条分六段，顺序照 Navicat 帧 3：
     * 表概览｜事务｜单元格编辑 + 筛选｜列 + 历史｜AI + 数据剖析｜导入导出 + 三个能力面。
     */
    private final HBox toolGroupProfile = new HBox(4);
    private final HBox toolGroupTx = new HBox(4);
    private final HBox toolGroupQuery = new HBox(4);
    private final HBox toolGroupView = new HBox(4);
    private final HBox toolGroupInsight = new HBox(4);
    private final HBox toolGroupTransfer = new HBox(4);
    /** ⭐ N18：13 个具名键（视觉顺序）—— 放不下时从右端折进 {@code >>}。 */
    private final List<ButtonBase> namedToolbarKeys = new ArrayList<>();
    /** 当前折进溢出菜单的键（菜单每次展开时按这份名单重建）。 */
    private final List<ButtonBase> foldedToolbarKeys = new ArrayList<>();
    private final Button toolbarOverflowButton = new Button();
    private final ContextMenu toolbarOverflowMenu = new ContextMenu();
    /** 工具条本体要参与溢出判断，不能只当局部变量。 */
    private final HBox toolbar = new HBox(8);
    /** 工具条宽度一变就重判「有没有键要被折进 >>」。 */
    private final ChangeListener<Number> toolbarWidthListener =
            (observable, oldValue, newValue) -> updateToolbarOverflow();
    /** 删除选中行（工具栏左组的危险操作）。 */
    private final Button deleteRowsButton = new Button();
    private final VBox stateBox = new VBox(10);
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label stateText = new Label();
    private final Button retry = IconButtons.of(Icons.Icon.REFRESH, I18n.t("grid.toolbar.retry"));
    /** ⭐ N20：底栏唯一的页码输入框（Navicat 的 `[页码]`，回车跳页）。 */
    private final TextField pageField = new TextField();
    private final Button previous = IconButtons.iconMono(Icons.Icon.PREVIOUS, I18n.t("grid.toolbar.previousPage"));
    private final Button next = IconButtons.iconMono(Icons.Icon.NEXT, I18n.t("grid.toolbar.nextPage"));
    private final Button firstPageButton = IconButtons.iconMono(Icons.Icon.FIRST, I18n.t("grid.toolbar.firstPage"));
    private final Button lastPageButton = IconButtons.iconMono(Icons.Icon.LAST, I18n.t("grid.toolbar.lastPage"));
    /** ⭐ N20：齿轮 —— 页大小与「跳至记录」都收进这里，底栏不再摆文字控件。 */
    private final Button pageOptionsButton = IconButtons.iconMono(Icons.Icon.SETTINGS, I18n.t("grid.pager.options"));
    private final ContextMenu pageOptionsMenu = new ContextMenu();
    /** ⭐ N20：放大镜 —— 开合本页查找条。 */
    private final ToggleButton findToggle = new ToggleButton();
    /** ⭐ N20：右下两枚视图切换（网格 / 表单），与 ≡▾ 菜单同一状态。 */
    private final ToggleButton gridToggle = new ToggleButton();
    private final ToggleButton formToggle = new ToggleButton();
    /** ⭐ D-1：分页器本体 —— 窄宽降级按真实可用宽度决定收起哪几项。 */
    private HBox pagerBox;
    /** ⭐ N31：Filter & Sort 内嵌面板（按下态开合），草稿经 Apply 才成查询。 */
    private final ToggleButton filterButton = new ToggleButton();
    private final VBox filterPanelHost = new VBox();
    private FilterSortPanel filterPanel;
    private final Button clearQueryButton = IconButtons.icon(Icons.Icon.CLEAR, I18n.t("grid.toolbar.clearConditions"));
    private final Button undoButton = IconButtons.icon(Icons.Icon.UNDO, I18n.t("grid.toolbar.undo"));
    private final Button redoButton = IconButtons.icon(Icons.Icon.REDO, I18n.t("grid.toolbar.redo"));
    private final Button saveButton = new Button();
    private final Button addRowButton = IconButtons.iconMono(Icons.Icon.ADD, I18n.t("grid.toolbar.addRow"));
    /**
     * ⭐ G2：事务三键。「开始事务」常驻，「提交 / 回滚」只在事务中现身 ——
     * 这两颗按钮<b>在不在</b>本身就是事务状态的显示器（跟本页「删除记录」同一套可见性口径）。
     */
    private final Button beginTxButton = IconButtons.icon(Icons.Icon.LOCK, I18n.t("grid.tx.begin"));
    private final Button commitTxButton = IconButtons.icon(Icons.Icon.CHECK,
            I18n.t("grid.toolbar.commitTooltip"));
    private final Button rollbackTxButton = IconButtons.icon(Icons.Icon.CLOSE,
            I18n.t("grid.toolbar.rollbackTooltip"));
    private final Button columnsButton = IconButtons.icon(Icons.Icon.COLUMNS, I18n.t("grid.toolbar.columns"));
    /** ⭐ 列设置弹层（带列名搜索）。 */
    private ColumnSettingsPanel columnPanel;
    /** ⭐ N19：表概览（真功能：切到 overview 段 / 刷新概览）。 */
    private final MenuButton tableProfileButton =
            IconButtons.iconMenu(new MenuButton(), Icons.Icon.TABLE, I18n.t("grid.toolbar.tableProfile"));
    /** ⭐ N19：单元格编辑器 —— 用现有的详情抽屉，光标在哪一格就开哪一格。 */
    private final Button cellEditorButton = IconButtons.icon(Icons.Icon.EDIT, I18n.t("grid.toolbar.cellEditor"));
    /** ⭐ N19：AI 与三个能力面——本版本没有对应后端，按诚实条款给如实说明页。 */
    private final Button fillWithAiButton = IconButtons.icon(Icons.Icon.AI,
            I18n.t("grid.toolbar.fillWithAi"));
    private final Button profilingButton = IconButtons.icon(Icons.Icon.DIAGNOSTICS,
            I18n.t("grid.toolbar.dataProfiling"));
    private final Button generationButton = IconButtons.icon(Icons.Icon.GRID,
            I18n.t("grid.toolbar.dataGeneration"));
    private final Button maskingButton = IconButtons.icon(Icons.Icon.LOCK,
            I18n.t("grid.toolbar.dataMasking"));
    private final Button biWorkspaceButton = IconButtons.icon(Icons.Icon.VIEW,
            I18n.t("grid.toolbar.biWorkspace"));
    private final MenuButton viewButton = IconButtons.iconMenu(new MenuButton(), Icons.Icon.LIST, I18n.t("grid.toolbar.view"));
    private final MenuButton exportButton = IconButtons.iconMenu(new MenuButton(), Icons.Icon.EXPORT, I18n.t("grid.toolbar.export"));
    /** ⭐ UX-3：数据页直接导入（目标表预选本表），不用再绕侧栏的「导入 / 导出」。 */
    private final Button importButton = new Button();
    /** ⭐ P2-3：按本表筛过的执行历史，点一条载入查询页。 */
    private final MenuButton historyButton =
            IconButtons.iconMenu(new MenuButton(), Icons.Icon.HISTORY, I18n.t("grid.toolbar.history"));
    private java.util.function.Supplier<List<QueryHistory.Entry>> historySource = List::of;
    private Consumer<String> onHistoryPick = sql -> { };
    private final TextField searchField = new TextField();
    // ── G3：网格查找 / 替换条（Navicat 数据编辑器的 Ctrl+F 形态）──
    //    与「本页搜索」分工：那个是行过滤（输入即筛行），这个是逐格查找 + 替换。
    private final HBox findBar = new HBox(6);
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    /** 区分大小写：默认关 —— 与 Navicat 及主流编辑器一致（要精确匹配再勾上）。 */
    private final CheckBox findCaseBox = new CheckBox(I18n.t("grid.find.caseSensitive"));
    private final Button findNextButton = new Button(I18n.t("grid.find.next"));
    private final Button replaceOneButton = new Button(I18n.t("grid.find.replace"));
    private final Button replaceAllButton = new Button(I18n.t("grid.find.replaceAll"));
    private final CheckMenuItem hideNullItem = new CheckMenuItem(I18n.t("grid.view.hideNull"));
    private final CheckMenuItem hideEmptyItem = new CheckMenuItem(I18n.t("grid.view.hideEmpty"));
    private final CheckMenuItem transposeItem = new CheckMenuItem(I18n.t("grid.view.transpose"));
    /** ⭐ G4：表单视图 —— 单记录纵向表单，与转置同一层、互斥。 */
    private final CheckMenuItem formItem = new CheckMenuItem(I18n.t("grid.view.form"));
    /** ⭐ G3：本页统计（行数与数值列的汇总）。 */
    private final CheckMenuItem summaryItem = new CheckMenuItem(I18n.t("grid.view.summary"));
    private final Label summaryLabel = new Label();
    private final TableView<Map<String, Object>> transposeTable = new TableView<>();
    /** ⭐ G4：表单视图容器（ScrollPane 包一列字段行）—— 与网格/转置同一层整块切换。 */
    private final ScrollPane formPane = new ScrollPane();
    private final VBox formRows = new VBox(10);
    /** 表单头：「记录 N / M」。 */
    private final Label formPosition = new Label();
    /** 表单字段按哪份 schema 建的 —— schema 换了才重建，筛选/翻页不拍掉正在打字的控件。 */
    private TableSchema formSchema;
    /** ⭐ G4：当前是否表单视图。 */
    private boolean formView;
    /**
     * 程序化回填期间置位 —— 复选框的 {@code setSelected} 会触发提交监听，
     * 而「切行 / 撤销 / 保存后重载」把值写回控件不是用户编辑。
     */
    private boolean formRefreshing;
    private final CellDetailsDrawer detailsDrawer = new CellDetailsDrawer();
    /** 抽屉当前展示／编辑的是哪一格（应用编辑时写回这里）。 */
    private Map<String, Object> detailsRow;
    private ColumnDef detailsDefinition;
    private final FlowPane chipBar = new FlowPane(6, 6);
    /** ⭐ 记录导航条（Navicat 形态）：首 / 上 / 下 / 末 + 当前行号。 */
    private final HBox recordNav = new HBox(4);
    /**
     * ⭐ N20：表单视图 = 记录导航 + 字段表单。
     *
     * <p>网格里点一行就算导航，不需要再摆一套方向键；表单视图没有网格，
     * 行导航只有从这里走 —— 所以它跟着表单块一起显隐，不再占底栏。
     */
    private final VBox formSection = new VBox(recordNav, formPane);
    /**
     * ⭐ UX-4：贴在网格左下的**行操作条**（+ / − / 应用 / 取消）。
     *
     * <p>Navicat 改数据时手一直在表格和这一排之间来回，工具条在最上面要抬头；
     * 这一排贴着光标走。四个按钮的可用态**绑定**到工具条同一批按钮上，
     * 不在这里重写一遍判断 —— 两处状态不一致比少一排按钮更糟。
     */
    private final HBox rowActions = new HBox(4);
    private final Button rowCancelButton = IconButtons.iconMono(Icons.Icon.CLOSE, I18n.t("grid.batch.discard"));
    /** ⭐ N19：第 5 颗展开菜单（撤销 / 重做 / 复制）。 */
    private final MenuButton moreButton = IconButtons.iconMenu(new MenuButton(),
            Icons.Icon.MORE, I18n.t("grid.toolbar.rowMore"));
    private final Label recordPosition = new Label("—");
    private final Map<String, HeaderView> headers = new LinkedHashMap<>();
    /** ⭐ G2：正在被拖动的列。 */
    private TableColumn<Map<String, Object>, ?> draggedColumn;
    /** 拖动起点（场景坐标）与累计位移。 */
    private double dragStartSceneX;
    private double dragLastSceneX;
    /** 拖动超过这个位移就算「拖」而不是「点」—— 点列头是排序。 */
    private static final double DRAG_THRESHOLD = 6;
    // ─────────────── G4：单元格区域选择 ───────────────
    /**
     * ⭐ G4：矩形选区。列下标是「可见数据列」的下标（行首槽不算），
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
    /**
     * ⭐ N17：光标所在列的 {@link TableColumn} —— 表头整格着蓝 tint 靠它带样式类。
     *
     * <p>样式类必须挂在 {@code TableColumn} 上（{@code TableColumnHeader} 会把列上的
     * 样式类复制到表头单元格），挂在列头 graphic 上只会染到文字那块，盖不满整格。
     */
    private TableColumn<?, ?> currentHeaderColumn;

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
     * ⭐ G1：冻结在左侧的**数据列**个数（不含行首槽，它天然跟着冻结）。
     * 0 = 不冻结 —— 此时左表整体隐藏，布局和以前完全一样。
     */
    private int frozenColumnCount;
    /** 左半张表：行首槽 + 前 N 个数据列。 */
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
    /**
     * ⭐ R61-c：保存那条任务的票据。原先它只是 {@code dbExecutor.execute(task)} 就没人管了，
     * 于是 {@link #cancel()} 拿不到它 —— 队列里那条（排在慢加载后面的）取消不掉，
     * 用户按了 ⌘. 之后**写库照样发生**。加载路径一直有票据（{@code inFlight}），保存没有。
     */
    private Future<?> saveInFlight;
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
                copyRowsAsTsv(rowsForCopy(), I18n.t("grid.scope.selected"));
                event.consume();
            } else if (event.isShortcutDown() && event.isShiftDown()
                    && event.getCode() == KeyCode.C) {
                // ⇧⌘C 复制为 INSERT
                copyRowsAsSql(rowsForCopy(), I18n.t("grid.scope.selected"));
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
            feedback.status(I18n.t("grid.msg.undoDone", editBuffer.dirtyCount()));
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
            feedback.status(I18n.t("grid.msg.redoDone", editBuffer.dirtyCount()));
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
        feedback.dirty(count);
        dirtyListener.accept(count);
        // ⭐ 缓冲一变，表单里显示的值就变了（编辑 / 撤销 / 重做 / 清空都走这里）。
        //    之前只在 rebuildItems 时刷表单，于是「撤销」后表单还挂着被撤掉的值。
        refreshForm();
    }

    private void updateEditControls() {
        boolean editable = tableSchema != null && tableSchema.editable();
        undoButton.setDisable(!editable || loading || saving || !editBuffer.canUndo());
        redoButton.setDisable(!editable || loading || saving || !editBuffer.canRedo());
        saveButton.setDisable(!editable || loading || saving || !editBuffer.isDirty());
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
            objectHeader.setContext(connectionName + " · "
                    + (editable ? I18n.t("grid.context.editable") : I18n.t("grid.context.readOnly")));
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
        // ⭐ 这一排的宽度**会随状态变**（选中行多一颗删除、事务中换三颗键）。
        //    收字判据原先只在工具条宽度变化时跑，于是「多一颗按钮」把需求顶到
        //    1093px 也没人重判（1280 下可用只有 1026px），最右的搜索框被裁掉
        //    （p2-cell 实拍踩到）。按钮进出必须跟着重判。
        updateToolbarOverflow();
    }

    private void saveChanges() {
        if (tableSchema == null || !tableSchema.editable() || !editBuffer.isDirty() || loading || saving) {
            return;
        }
        EditSqlBuilder.Plan plan;
        try {
            plan = EditSqlBuilder.plan(session, tableSchema, editBuffer);
        } catch (RuntimeException e) {
            feedback.status(I18n.t("grid.msg.saveNoSql", readable(e)));
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

        submitSave(plan, undoPlan);
    }

    /**
     * ⭐ R61-c：把「提交这条保存」单独抽出来 —— 上面那段要经过确认弹窗，
     * 而取消通道（{@link #cancel()} 取走 {@code saveInFlight}）必须在**不弹窗**的情况下
     * 也能被门禁跑到。同 {@code buildSavePreviewDialog} 一个道理：抽出来不是为了复用，
     * 是为了让最关键的那一步可测。
     *
     * <p>必须在 FX 线程调用（它动按钮与网格）。
     */
    void submitSave(EditSqlBuilder.Plan plan, EditSqlBuilder.Plan inverse) {
        saveButton.setDisable(true);
        // ⭐ V-4：保存期间图标换成转圈，且**按钮宽度不变**（否则整排工具栏会往左抽）。
        IconButtons.loading(saveButton, true);
        saving = true;
        grid.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return EditSqlBuilder.execute(session, plan);
            }
        };
        task.setOnSucceeded(event -> {
            saveInFlight = null;
            int affected = task.getValue();
            saving = false;
            IconButtons.loading(saveButton, false);
            grid.setDisable(false);
            // ⚠️ 不能无条件报「自动提交」：用户开了事务时保存只是写进事务，事务还开着。
            // 事务中不留反向 SQL 撤销计划（撤销靠回滚，见 undoLastSave）。
            saveUndoPlan = transactionOpen ? null : inverse;
            editBuffer.clear();
            updateDirty();
            feedback.status(transactionOpen
                    ? I18n.t("grid.msg.savedInTx", plan.size(), affected)
                    : I18n.t("grid.msg.savedCount", plan.size(), affected));
            if (transactionOpen) {
                feedback.toast(I18n.t("grid.tx.writtenToast"));
            } else if (!inverse.isEmpty() && inverse.complete()) {
                feedback.toast(I18n.t("grid.msg.savedUndoable", plan.size()), this::undoLastSave);
            } else if (!inverse.isEmpty()) {
                feedback.toastUnreversible(I18n.t("grid.msg.savedNoUndo", plan.size()));
            } else {
                feedback.toast(I18n.t("grid.msg.savedPlain", plan.size()));
            }
            load(true);
        });
        task.setOnFailed(event -> {
            saveInFlight = null;
            saving = false;
            IconButtons.loading(saveButton, false);
            grid.setDisable(false);
            updateEditControls();
            // ⭐ V-12 + R45 G3-b：错误归类走唯一渲染口 —— 说明/建议的措辞由界面层按语言出。
            String reason = ErrorText.message(
                    io.agenticdb.safety.ErrorClassifier.classify(task.getException()));
            feedback.status(I18n.t("grid.msg.saveFailed", reason));
            showErrorDetails(I18n.t("grid.msg.saveFailedTitle"), task.getException(), plan.preview());
        });
        // ⭐ R61-c：保存也要有票据（见 saveInFlight 的注释）—— 排队中的保存能被取消，
        //   正在写的保存能被中断。
        //   被中断的 Task 走 onCancelled 而不是 onFailed（JavaFX Task 把 InterruptedException
        //   归到 CANCELLED），所以用户主动按 ⌘. 不会看到「保存失败」的错误框。
        task.setOnCancelled(event -> {
            saveInFlight = null;
            if (saving) {
                // 只有不是从 cancel() 进来的（那里已经复位过）才补一次复位。
                saving = false;
                IconButtons.loading(saveButton, false);
                grid.setDisable(false);
                updateEditControls();
            }
        });
        saveInFlight = start(task);
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
            feedback.status(I18n.t("grid.tx.undoBlocked"));
            return;
        }
        undoingSave = true;
        feedback.status(I18n.t("grid.msg.undoingSave"));
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return EditSqlBuilder.execute(session, inverse);
            }
        };
        task.setOnSucceeded(event -> {
            undoingSave = false;
            saveUndoPlan = null;
            feedback.status(I18n.t("grid.msg.undoSaveDone", task.getValue()));
            feedback.toast(I18n.t("grid.msg.undoSaveOk"));
            load(true);
        });
        task.setOnFailed(event -> {
            undoingSave = false;
            feedback.status(I18n.t("grid.msg.undoSaveFailed", readable(task.getException())));
            showErrorDetails(I18n.t("grid.msg.undoSaveFailedTitle"), task.getException(), inverse.preview());
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
        runTransactionCommand(I18n.t("grid.tx.begin"), () -> {
            session.begin();
            return session.inTransaction();
        }, inTx -> {
            transactionOpen = Boolean.TRUE.equals(inTx);
            applyTransactionControls();
            feedback.status(transactionOpen
                    ? I18n.t("grid.tx.started")
                    : I18n.t("grid.tx.unsupported"));
        });
    }

    /** 提交：事务内的写入落库，回到自动提交。 */
    private void commitTransaction() {
        runTransactionCommand(I18n.t("grid.tx.commit"), () -> {
            session.commit();
            return session.inTransaction();
        }, inTx -> {
            transactionOpen = Boolean.TRUE.equals(inTx);
            saveUndoPlan = null;
            applyTransactionControls();
            feedback.status(I18n.t("grid.tx.committedDetail"));
            feedback.toast(I18n.t("grid.tx.committed"));
        });
    }

    /** 回滚前的确认弹窗（不显示；抽出来是为了让门禁断言文案与按钮序，不用真弹模态）。 */
    Dialog<ButtonType> buildRollbackDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("grid.tx.rollback"));
        ButtonType rollback = new ButtonType(I18n.t("grid.tx.rollbackButton"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(rollback, cancel);
        dialog.getDialogPane().setHeader(new Label(I18n.t("grid.tx.rollbackHeader")));
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
                .filter(type -> I18n.t("grid.tx.rollbackButton").equals(type.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("rollback button missing from the rollback confirm dialog"));
        return dialog.showAndWait().filter(rollback::equals).isPresent();
    }

    /**
     * 回滚：丢弃本事务里全部未提交的写入。
     *
     * <p>⚠️ 缓冲必须一起清 —— 已经保存进事务的那些改动，回滚后库里根本没有；
     * 留着它们，网格就会显示一批「数据库里不存在」的编辑，再保存等于凭空造数据。
     */
    void rollbackTransaction() {
        runTransactionCommand(I18n.t("grid.tx.rollback"), () -> {
            session.rollback();
            return session.inTransaction();
        }, inTx -> {
            transactionOpen = Boolean.TRUE.equals(inTx);
            editBuffer.clear();
            saveUndoPlan = null;
            applyTransactionControls();
            updateDirty();
            feedback.status(I18n.t("grid.tx.rolledBackDetail"));
            feedback.toast(I18n.t("grid.tx.rolledBack"));
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
        feedback.status(I18n.t("grid.msg.inProgress", action));
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
            feedback.status(I18n.t("grid.msg.actionFailed", action, readable(task.getException())));
            showErrorDetails(I18n.t("grid.msg.actionFailedShort", action), task.getException(), null);
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
        // 事务开关换掉的是三颗按钮 —— 与「删除记录」同理，宽度要重判一次。
        updateToolbarOverflow();
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
     * {@code ButtonType(I18n.t("grid.save.execute"), ButtonData.OK_DONE)} —— {@code OK_DONE}
     * 语义上就是「默认且确认」，于是<b>回车 = 直接执行破坏性 SQL</b>。
     * 现在「执行」用 {@code OTHER}，并在弹窗显示后把默认按钮钉在「取消」上
     * （{@link DestructiveConfirm#pinCancelAsDefault}）。
     *
     * <p>高风险（改结构 / 管理操作）额外走一级 {@link DestructiveConfirm}。
     */
    private boolean confirmSqlPreview(EditSqlBuilder.Plan plan, Danger danger) {
        if (danger.requiresApproval()) {
            DestructiveConfirm confirm = DestructiveConfirm.of(
                    I18n.t("grid.save.title"),
                    I18n.t("grid.save.changesRisk", plan.size(), DangerLabels.t(danger)),
                    transactionOpen
                            ? I18n.t("grid.save.sqlTxIntro") + plan.preview()
                            : I18n.t("grid.save.sqlPlainIntro") + plan.preview(),
                    I18n.t("grid.save.proceed"));
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
                .filter(type -> I18n.t("grid.save.execute").equals(type.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("execute button missing from the save preview dialog"));
        return dialog.showAndWait().filter(execute::equals).isPresent();
    }

    /** 保存预览弹窗的「执行」按钮类型。抽出来是为了测试能断言它不是默认按钮。 */
    static ButtonType executeButtonType() {
        // ⭐ OTHER：不是默认按钮，回车不会落在「执行」上。
        return new ButtonType(I18n.t("grid.save.execute"), ButtonBar.ButtonData.OTHER);
    }

    /** 保存预览弹窗的「取消」按钮类型。 */
    static ButtonType cancelButtonType() {
        return new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    /**
     * 构造保存预览弹窗（不显示）。
     *
     * <p>抽成独立方法是为了让门禁能直接断言「默认按钮不是执行」，
     * 而不用真的把模态弹窗弹出来。
     */
    Dialog<ButtonType> buildSavePreviewDialog(EditSqlBuilder.Plan plan, Danger danger) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("grid.save.title"));
        dialog.setHeaderText(transactionOpen
                        ? I18n.t("grid.save.headTx", plan.size(), DangerLabels.t(danger))
                        : I18n.t("grid.save.headPlain", plan.size(), DangerLabels.t(danger)));
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

        Label badge = new Label(DangerLabels.t(danger));
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
        objectHeader = new ObjectHeader(Icons.Icon.TABLE, tableName, I18n.t("grid.header.type"));
        objectHeader.setContext(connectionName);
        objectHeader.setCount(null);

        Button refresh = IconButtons.icon(Icons.Icon.REFRESH, I18n.t("grid.header.refreshTooltip"));
        refresh.setId("data-refresh");
        refresh.getStyleClass().addAll("adb-button", "adb-button--icon");
        refresh.setTooltip(new Tooltip(I18n.t("grid.header.refreshTooltip")));
        refresh.setOnAction(event -> refresh());

        Button structure = IconButtons.icon(Icons.Icon.STRUCTURE, I18n.t("grid.header.structure"));
        structure.setId("data-structure");
        structure.getStyleClass().addAll("adb-button", "adb-button--icon");
        structure.setAccessibleText(I18n.t("grid.header.structure"));
        structure.setTooltip(new Tooltip(I18n.t("grid.header.structureTooltip")));
        structure.setOnAction(event -> openStructure.accept(tableName));

        objectHeader.addActions(refresh, structure);

        // ── ② 对象功能切面（§6.2）──────────────────────────────────────
        objectTabs = new ObjectTabBar();
        objectTabs.addTab("data", I18n.t("grid.tab.data"));
        objectTabs.addTab("overview", I18n.t("grid.tab.overview"));
        objectTabs.addTab(ObjectSectionsPanel.SECTION_COLUMNS, I18n.t("grid.tab.structure"));
        objectTabs.addTab(ObjectSectionsPanel.SECTION_INDEXES, I18n.t("grid.tab.indexes"));
        objectTabs.addTab(ObjectSectionsPanel.SECTION_FOREIGN_KEYS, I18n.t("grid.tab.foreignKeys"));
        objectTabs.addTab(ObjectSectionsPanel.SECTION_TRIGGERS, I18n.t("grid.tab.triggers"));
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
        // ⭐ 这一排必须肯认输：按钮的 minWidth 钉着 USE_PREF_SIZE（文字不许压扁），
        //    HBox 的最小宽就等于「带文字的一整排」。不给它 0 的话，父容器只能
        //    把超宽的一条**居中溢出并裁掉**，收字判据也永远看不到真实可用宽度
        //    （p2-details / p2-cell 实拍就是这么被裁的：1026 的框，需求 994 或 1093）。
        toolbar.setMinWidth(0);

        for (HBox group : List.of(toolGroupProfile, toolGroupTx, toolGroupQuery,
                toolGroupView, toolGroupInsight, toolGroupTransfer)) {
            group.setAlignment(Pos.CENTER_LEFT);
            group.getStyleClass().add("adb-toolbar__group");
        }

        Region toolbarSpacer = new Region();
        HBox.setHgrow(toolbarSpacer, Priority.ALWAYS);

        // ── 行操作三键（新增 / 删除 / 保存）──────────────
        //   ⭐ N19：这三颗不再放在顶部工具条 —— Navicat 把它们放在网格左下
        //   的记录操作条里（+ − ✓ ✗），贴着光标。这里的节点本体就是
        //   rowActions 里的那四颗（见 buildRowActions），id 与可用态判断都在。
        addRowButton.setId("data-add");
        addRowButton.getStyleClass().setAll("adb-icon-button");
        addRowButton.setTooltip(new Tooltip(I18n.t("grid.tip.addRow")));
        addRowButton.setDisable(true);
        addRowButton.setOnAction(event -> addRow());

        saveButton.setId("data-save");
        saveButton.getStyleClass().setAll("adb-icon-button", "adb-button--primary");
        // ⭐ N19：这四颗已经搬进网格左下的行操作条 —— 那一排按 Navicat 是灰的单线，
        //    彩色分层会抢过数据本身（R35 N4-c 的两区口径，见 IconButtons#iconMono）。
        saveButton.setGraphic(Icons.of(Icons.Icon.SAVE, Icons.SIZE_TOOLBAR));
        String saveHint = io.agenticdb.ui.Shortcuts.labelled(I18n.t("grid.tip.save"), "Mod+S");
        saveButton.setTooltip(new Tooltip(saveHint));
        saveButton.setAccessibleText(saveHint);
        saveButton.setOnAction(event -> saveChanges());

        deleteRowsButton.setId("data-delete-rows");
        // ⭐ 删除是这一排里唯一会丢数据的动作 —— 用 danger 色，别和「保存」穿同一件衣服。
        deleteRowsButton.getStyleClass().setAll("adb-icon-button", "adb-button--danger");
        deleteRowsButton.setGraphic(Icons.of(Icons.Icon.DELETE, Icons.SIZE_TOOLBAR));
        deleteRowsButton.setTooltip(new Tooltip(I18n.t("grid.tip.deleteRows")));
        deleteRowsButton.setAccessibleText(I18n.t("grid.tip.deleteRows"));
        deleteRowsButton.setDisable(true);
        // ⚠️ 没有选中行时**整颗按钮消失**，而不是摆在那里灰着 ——
        //    「删除」在不可用状态占着位置，只会让人反复去点它。
        deleteRowsButton.setVisible(false);
        deleteRowsButton.setManaged(false);
        deleteRowsButton.setOnAction(event -> deleteSelectedRows());

        // ── ① 表概览 ──────────────────────────────────────────────────
        tableProfileButton.setId("data-table-profile");
        tableProfileButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(tableProfileButton, Icons.Icon.TABLE, I18n.t("grid.toolbar.tableProfile"),
                I18n.t("grid.tip.tableProfile"));
        MenuItem profileOverview = new MenuItem(I18n.t("grid.profile.overview"));
        profileOverview.setId("data-table-profile-overview");
        profileOverview.setOnAction(event -> objectTabs.setSelected("overview"));
        MenuItem profileRefresh = new MenuItem(I18n.t("grid.profile.refresh"));
        profileRefresh.setId("data-table-profile-refresh");
        profileRefresh.setOnAction(event -> {
            overviewLoaded = false;
            objectTabs.setSelected("overview");
            loadOverview(true);
        });
        tableProfileButton.getItems().setAll(profileOverview, profileRefresh);

        // ── ② 事务三键 ────────────────────────────────────────────────
        //    Navicat 的数据页工具栏常驻「开始事务」，事务打开后才有「提交 / 回滚」。
        //    作用域是<b>连接</b>：一个连接一个会话，同一连接上的查询页、结构页共用这个事务。
        beginTxButton.setId("data-begin-tx");
        beginTxButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        beginTxButton.setDisable(true);
        toolbarOp(beginTxButton, Icons.Icon.LOCK, I18n.t("grid.tx.begin"),
                I18n.t("grid.tip.beginTx"));
        beginTxButton.setOnAction(event -> beginTransaction());

        // 提交 / 回滚只在事务里出现 —— 出现本身就是状态，图标 + 提示足够
        //（事务状态由状态栏那一段常显）。
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

        // ── ③ 单元格编辑 + 筛选 ──────────────────────────────────────
        cellEditorButton.setId("data-cell-editor");
        cellEditorButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        cellEditorButton.setDisable(true);
        toolbarOp(cellEditorButton, Icons.Icon.EDIT, I18n.t("grid.toolbar.cellEditor"),
                I18n.t("grid.tip.cellEditor"));
        cellEditorButton.setOnAction(event -> openCellEditor());

        filterButton.setId("data-filter");
        filterButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        filterButton.setDisable(true);
        toolbarOp(filterButton, Icons.Icon.FILTER, I18n.t("grid.toolbar.filter"),
                I18n.t("grid.tip.filter"));
        filterButton.setOnAction(event -> setFilterPanelOpen(filterButton.isSelected()));

        // ── ④ 列 + 历史 ──────────────────────────────────────────────
        columnsButton.setId("data-columns");
        columnsButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        columnsButton.setDisable(true);
        toolbarOp(columnsButton, Icons.Icon.COLUMNS, I18n.t("grid.toolbar.columnsShort"),
                I18n.t("grid.tip.columns"));
        columnsButton.setOnAction(event -> openColumnPanel());

        // ── ⑤ AI + 数据剖析（能力面未建 → 如实说明页）───────────────
        fillWithAiButton.setId("data-fill-ai");
        fillWithAiButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(fillWithAiButton, Icons.Icon.AI, I18n.t("grid.toolbar.fillWithAi"),
                I18n.t("grid.tip.fillWithAi"));
        // R43-b：原来是「用 AI 填充此列 / 解释这张表」两项下拉，两项开的是同一份说明。
        // 一颗按钮就够 —— 一次点击 = 一份如实说明，不给用户两个都点了个空。
        fillWithAiButton.setOnAction(event ->
                showCapabilityNotice(I18n.t("grid.toolbar.fillWithAi"), "grid.capability.fillAi"));

        profilingButton.setId("data-profiling");
        profilingButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(profilingButton, Icons.Icon.DIAGNOSTICS, I18n.t("grid.toolbar.dataProfiling"),
                I18n.t("grid.tip.dataProfiling"));
        profilingButton.setOnAction(event ->
                showCapabilityNotice(I18n.t("grid.toolbar.dataProfiling"), "grid.capability.profiling"));

        // 视图菜单（≡▾）：冻结、隐藏空值、表单视图等 AIDB 已有的显示开关。
        // ⭐ R43-c：这颗挂 adb-button--icon-menu，不是 adb-button--icon ——
        //   后者把宽度钉死 30px，而它是 MenuButton：内容要「内边距 8+8 + 图标 14.5
        //   + 箭头 16」≈46px。30px 装不下时 ButtonSkin 把箭头挤到图标**左边**并压住它，
        //   1280px 帧上工具条右端就是一颗孤零零的 ▾（图标被盖在旁边，看不出是谁的箭头）。
        viewButton.setId("data-view");
        viewButton.getStyleClass().addAll("adb-button", "adb-button--ghost", "adb-button--icon-menu");
        viewButton.setMinWidth(Region.USE_PREF_SIZE);
        viewButton.setTooltip(new Tooltip(I18n.t("grid.tip.view")));
        viewButton.setAccessibleText(I18n.t("grid.tip.view"));
        viewButton.setDisable(true);
        hideNullItem.setId("data-hide-null");
        hideNullItem.setOnAction(event -> setHideNullRows(hideNullItem.isSelected()));
        hideEmptyItem.setId("data-hide-empty");
        hideEmptyItem.setOnAction(event -> setHideEmptyRows(hideEmptyItem.isSelected()));
        transposeItem.setId("data-transpose-toggle");
        transposeItem.setOnAction(event -> setTranspose(transposeItem.isSelected()));
        formItem.setId("data-form-toggle");
        formItem.setOnAction(event -> setFormView(formItem.isSelected()));
        summaryItem.setId("data-summary-toggle");
        summaryItem.setOnAction(event -> setSummaryVisible(summaryItem.isSelected()));

        // ⭐ G1：冻结列入口。做成子菜单而不是一个循环按钮 ——
        //   「现在冻结了几列」必须一眼看得到，循环点会让用户数不清自己在第几档。
        javafx.scene.control.Menu freezeMenu = new javafx.scene.control.Menu(I18n.t("grid.view.freezeLeft"));
        freezeMenu.setId("data-freeze-menu");
        for (int count = 1; count <= 3; count++) {
            MenuItem item = new MenuItem(I18n.t("grid.view.freezeColumns", count));
            item.setId("data-freeze-" + count);
            int frozen = count;
            item.setOnAction(event -> setFrozenColumnCount(frozen));
            freezeMenu.getItems().add(item);
        }
        MenuItem unfreeze = new MenuItem(I18n.t("grid.view.unfreeze"));
        unfreeze.setId("data-freeze-0");
        unfreeze.setOnAction(event -> setFrozenColumnCount(0));
        freezeMenu.getItems().add(unfreeze);

        viewButton.getItems().addAll(hideNullItem, hideEmptyItem,
                new SeparatorMenuItem(), summaryItem, transposeItem, formItem,
                new SeparatorMenuItem(), freezeMenu);

        // ── ⑥ 导入 / 导出 + 三个能力面 ──────────────────────────────
        exportButton.setId("data-export");
        exportButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        exportButton.setDisable(true);
        toolbarOp(exportButton, Icons.Icon.EXPORT, I18n.t("grid.toolbar.export"),
                I18n.t("grid.tip.export"));
        MenuItem exportCsv = new MenuItem(I18n.t("grid.export.csvPage"));
        exportCsv.setId("data-export-csv");
        exportCsv.setOnAction(event -> exportCsv());
        // ⭐ 导出向导：能导**整表**（不是只有当前页），格式可选 CSV/TSV/JSON/SQL。
        MenuItem exportWizard = new MenuItem(I18n.t("grid.export.wizard"));
        exportWizard.setId("data-export-wizard");
        exportWizard.setOnAction(event -> openExportWizard());
        MenuItem copyPage = new MenuItem(I18n.t("grid.export.copyTsvPage"));
        copyPage.setId("data-export-tsv");
        copyPage.setOnAction(event -> copyRowsAsTsv(List.copyOf(grid.getItems()), I18n.t("grid.scope.page")));
        MenuItem copyPageSql = new MenuItem(I18n.t("grid.export.copySqlPage"));
        copyPageSql.setId("data-export-sql");
        copyPageSql.setOnAction(event -> copyRowsAsSql(List.copyOf(grid.getItems()), I18n.t("grid.scope.page")));
        exportButton.getItems().addAll(exportWizard, new SeparatorMenuItem(),
                exportCsv, copyPage, copyPageSql);

        importButton.setId("data-import");
        importButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        importButton.setDisable(true);
        toolbarOp(importButton, Icons.Icon.IMPORT, I18n.t("grid.toolbar.import"),
                I18n.t("grid.tip.import"));
        importButton.setOnAction(event -> openImportWizard());

        generationButton.setId("data-generation");
        generationButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(generationButton, Icons.Icon.GRID, I18n.t("grid.toolbar.dataGeneration"),
                I18n.t("grid.tip.dataGeneration"));
        generationButton.setOnAction(event ->
                showCapabilityNotice(I18n.t("grid.toolbar.dataGeneration"),
                        "grid.capability.generation"));

        maskingButton.setId("data-masking");
        maskingButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(maskingButton, Icons.Icon.LOCK, I18n.t("grid.toolbar.dataMasking"),
                I18n.t("grid.tip.dataMasking"));
        maskingButton.setOnAction(event ->
                showCapabilityNotice(I18n.t("grid.toolbar.dataMasking"),
                        "grid.capability.masking"));

        biWorkspaceButton.setId("data-bi-workspace");
        biWorkspaceButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(biWorkspaceButton, Icons.Icon.VIEW, I18n.t("grid.toolbar.biWorkspace"),
                I18n.t("grid.tip.biWorkspace"));
        biWorkspaceButton.setOnAction(event ->
                showCapabilityNotice(I18n.t("grid.toolbar.biWorkspace"),
                        "grid.capability.bi"));

        // ── ④ 列 + 历史（继续）────────────────────────────────────────
        historyButton.setId("data-history");
        historyButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        toolbarOp(historyButton, Icons.Icon.HISTORY, I18n.t("grid.toolbar.history"),
                I18n.t("grid.tip.history"));
        // 每次展开时重建 —— 历史可能在别的查询页里刚增长，缓存一份只会给出旧列表。
        historyButton.setOnShowing(event -> rebuildHistoryMenu());

        // ⭐ N19：这一排的 13 个具名键（视觉顺序与折叠顺序同一份名单）。
        namedToolbarKeys.addAll(List.of(
                tableProfileButton, beginTxButton, cellEditorButton, filterButton,
                columnsButton, historyButton, fillWithAiButton, profilingButton,
                importButton, exportButton, generationButton, maskingButton, biWorkspaceButton));

        toolGroupProfile.getChildren().add(tableProfileButton);
        toolGroupTx.getChildren().addAll(beginTxButton, commitTxButton, rollbackTxButton);
        toolGroupQuery.getChildren().addAll(cellEditorButton, filterButton);
        toolGroupView.getChildren().addAll(columnsButton, historyButton);
        toolGroupInsight.getChildren().addAll(fillWithAiButton, profilingButton);
        toolGroupTransfer.getChildren().addAll(importButton, exportButton,
                generationButton, maskingButton, biWorkspaceButton);

        // ⭐ N18：右端两颗小键 —— 视图菜单（≡▾）+ 溢出（»）。
        //    放不下的具名键从右往左折进 » 的菜单里，不再把整排文字收掉。
        toolbarOverflowButton.setId("data-toolbar-overflow");
        toolbarOverflowButton.setGraphic(Icons.colored(Icons.Icon.CHEVRON_RIGHT, Icons.SIZE_TOOLBAR));
        toolbarOverflowButton.getStyleClass().addAll("adb-button", "adb-button--ghost", "adb-button--icon");
        toolbarOverflowButton.setTooltip(new Tooltip(I18n.t("grid.toolbar.overflow")));
        toolbarOverflowButton.setAccessibleText(I18n.t("grid.toolbar.overflow"));
        toolbarOverflowButton.setFocusTraversable(true);
        toolbarOverflowButton.setVisible(false);
        toolbarOverflowButton.setManaged(false);
        toolbarOverflowMenu.setId("data-toolbar-overflow-menu");
        toolbarOverflowButton.setOnAction(event -> showToolbarOverflow());
        // Button 默认只认空格，回车要自己接 —— 键盘用户同样要够得到折叠键。
        toolbarOverflowButton.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                showToolbarOverflow();
                event.consume();
            }
        });

        toolbar.getChildren().addAll(
                toolGroupProfile, toolbarSeparator(),
                toolGroupTx, toolbarSeparator(),
                toolGroupQuery, toolbarSeparator(),
                toolGroupView, toolbarSeparator(),
                toolGroupInsight, toolbarSeparator(),
                toolGroupTransfer,
                toolbarSpacer, viewButton, toolbarOverflowButton);
        bindToolbarOverflow();

        chipBar.setId("data-query-chips");
        chipBar.getStyleClass().add("adb-query-chips");
        chipBar.setAlignment(Pos.CENTER_LEFT);
        chipBar.setVisible(false);
        chipBar.setManaged(false);
        // ⭐ N19：清除条件跟着 chip 条走 —— 它只在「有条件下」才有意义，
        //    占据顶部那一排会给 13 个具名键多挤一格。
        clearQueryButton.setId("data-clear-query");
        clearQueryButton.getStyleClass().setAll("adb-chip", "adb-chip--muted");
        clearQueryButton.setTooltip(new Tooltip(I18n.t("grid.tip.clearConditions")));
        clearQueryButton.setAccessibleText(I18n.t("grid.tip.clearConditions"));
        clearQueryButton.setDisable(true);
        clearQueryButton.setOnAction(event -> clearQuery());

        grid.setId("data-table");
        grid.getStyleClass().add("adb-grid");
        // ⭐ P7：表格本身没有文字，读屏只会读「表」—— 给一个明确的名称。
        grid.setAccessibleText(I18n.t("grid.aria.grid"));

        // ⭐ G1：左表（冻结列）。两张表共享同一份 items，靠索引对齐。
        //   列宽用 UNCONSTRAINED —— 冻结区宽度就是各列 prefWidth 之和，
        //   不能让最后一列被拉伸，否则右表起点会随窗口宽度抖动。
        frozenGrid.setId("data-frozen-table");
        frozenGrid.getStyleClass().addAll("adb-grid", "adb-grid--frozen");
        frozenGrid.setAccessibleText(I18n.t("grid.aria.gridFrozen"));
        frozenGrid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        frozenGrid.setFocusTraversable(true);
        frozenGrid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        frozenGrid.setVisible(false);
        frozenGrid.setManaged(false);

        // ⭐ 空表不是"没有数据"四个字 —— 用户看到空白不知道下一步能干什么。
        //   给图标 + 说明 + 一个真能点的出口。
        grid.setPlaceholder(EmptyState.of(Icons.Icon.TABLE, I18n.t("grid.empty.title"),
                I18n.t("grid.empty.detail"),
                I18n.t("grid.empty.action"), this::addRow)
                .hint(I18n.t("grid.empty.hint")));
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
        grid.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Map<String, Object>>) change -> {
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
        transposeTable.setPlaceholder(EmptyState.of(Icons.Icon.STRUCTURE, I18n.t("grid.transpose.empty"),
                I18n.t("grid.transpose.emptyDetail")));
        transposeTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        transposeTable.setVisible(false);
        transposeTable.setManaged(false);
        transposeTable.setFocusTraversable(true);

        // ⭐ G4：表单视图 —— 与转置同一层（body 里的整块切换），字段行按 schema 懒建。
        formPane.setId("data-form");
        formPane.getStyleClass().add("adb-grid__form");
        formPane.setFitToWidth(true);
        formPosition.setId("data-form-position");
        formRows.getStyleClass().add("adb-grid__form-rows");
        formRows.setFillWidth(true);
        formPane.setContent(formRows);
        VBox.setVgrow(formPane, Priority.ALWAYS);
        formSection.setId("data-form-section");
        formSection.setVisible(false);
        formSection.setManaged(false);
        formPane.setVisible(false);
        formPane.setManaged(false);

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
        body.getChildren().addAll(gridRow, transposeTable, formSection, stateBox, summaryLabel, detailsDrawer);
        VBox.setVgrow(body, Priority.ALWAYS);
        bindFrozenSelection();
        bindFrozenScroll();
        // ⭐ G4：表单永远跟着「网格当前选中行」—— 记录导航（selectRow）正是通过
        //    改选中行让表单翻记录的，这里就是唯一的联动点。
        grid.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldValue, newValue) -> refreshForm());

        // 记录导航先建（分页器里要用它），再成分页器。
        buildRecordNav();
        HBox pager = buildPager();

        buildFindBar();

        // ── ④ 组装 ────────────────────────────────────────────────────
        //    数据切面 = 工具栏 + 条件 chips + 查找条 + 网格 + 分页器。
        //    ⭐ N16：不再有「N 行已选 · 复制 · …」批量条 —— 它把网格
        //    往上顶一行，而且每选一行就闪一次。批量动作改走右键菜单
        //    （网格内任何一格）与贴网格左下的行操作条。
        //    其它切面（结构 / 索引 / 外键 / 触发器 / SQL）共用同一个 StackPane，
        //    切换时不丢数据页的任何状态（编辑缓冲、分页、滚动位置都在）。
        //
        //    ⭐ 记录导航条**并进分页器**，不再单独占一行 ——
        //    它原来是网格上方一条 28px 的独立横条，把「第一条数据」推到了
        //    窗口的 1/3 处（实测：284px 的 chrome 里它占了 28px）。
        //    两套导航（翻行 / 翻页）放在一起，语义更清楚，还省一行。
        //    ⭐ G3 查找条默认 unmanaged（不占高度），打开时才把网格往下推一格。
        //    ⭐ N31：Filter & Sort 面板同一条「插在工具带与网格之间」的规则 ——
        //    按钮按下才出现，把网格往下推，不做浮层遮挡。
        filterPanelHost.setId("data-filter-panel-host");
        filterPanelHost.setVisible(false);
        filterPanelHost.setManaged(false);
        dataSection = new VBox(toolbar, chipBar, filterPanelHost, findBar, body, pager);
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
        addOverviewFact(facts, I18n.t("grid.overview.rows"), overviewRows, "overview-rows");
        addOverviewFact(facts, I18n.t("grid.overview.columns"), overviewColumns, "overview-columns");
        addOverviewFact(facts, I18n.t("grid.overview.pk"), overviewPrimary, "overview-primary");
        addOverviewFact(facts, I18n.t("grid.overview.autoIncrement"), overviewAutoIncrement, "overview-auto-increment");
        addOverviewFact(facts, I18n.t("grid.overview.indexes"), overviewIndexes, "overview-indexes");
        addOverviewFact(facts, I18n.t("grid.overview.size"), overviewSize, "overview-size");
        addOverviewFact(facts, I18n.t("grid.overview.engine"), overviewEngine, "overview-engine");

        overviewHint.setId("overview-hint");
        overviewHint.getStyleClass().add("adb-overview__hint");
        overviewHint.setWrapText(true);

        Label recentTitle = new Label(I18n.t("grid.overview.recent"));
        recentTitle.getStyleClass().add("adb-overview__section-title");
        Label recentHint = new Label(I18n.t("grid.overview.recentHint"));
        recentHint.getStyleClass().add("adb-overview__hint");
        overviewRecent.setId("overview-recent");

        Button openStructureButton = IconButtons.of(Icons.Icon.STRUCTURE, I18n.t("grid.overview.openStructure"));
        openStructureButton.setId("overview-open-structure");
        openStructureButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        openStructureButton.setOnAction(event -> openStructure.accept(tableName));

        Button refreshOverviewButton = IconButtons.of(Icons.Icon.REFRESH, I18n.t("grid.overview.refresh"));
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
                ? I18n.t("grid.overview.none")
                : String.join(I18n.t("grid.overview.pkSeparator"), tableSchema.primaryKeys()));
        overviewAutoIncrement.setText(tableSchema.autoIncrementColumn() == null
                ? I18n.t("grid.overview.none") : tableSchema.autoIncrementColumn());
    }

    private void applyOverview(OverviewFacts facts) {
        TableMeta meta = facts.meta();
        overviewSize.setText(meta == null ? "—" : meta.sizeText());
        overviewEngine.setText(meta == null ? "—" : meta.engineText());
        overviewIndexes.setText(facts.indexes() == null ? "—" : String.valueOf(facts.indexes()));
        if (meta == null && session.dialect().tableMetaSql() == null) {
            overviewHint.setText(I18n.t("grid.overview.metaAbsent", session.dialect().displayName()));
        } else if (meta == null) {
            overviewHint.setText(I18n.t("grid.overview.metaMissing"));
        } else {
            overviewHint.setText(I18n.t("grid.overview.metaOk"));
        }
    }

    private void updateOverviewRows() {
        overviewRows.setText(total < 0 ? "—" : String.valueOf(total));
    }

    private void renderOverviewRecent() {
        overviewRecent.getChildren().clear();
        List<QueryHistory.Entry> entries = historySource.get();
        if (entries.isEmpty()) {
            Label empty = new Label(I18n.t("grid.history.empty"));
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

        Button first = IconButtons.icon(Icons.Icon.FIRST, I18n.t("grid.form.firstRow"));
        first.setId("data-record-first");
        first.getStyleClass().add("adb-icon-button");
        first.setOnAction(event -> selectRow(0));

        Button previous = IconButtons.icon(Icons.Icon.PREVIOUS, I18n.t("grid.form.previousRow"));
        previous.setId("data-record-prev");
        previous.getStyleClass().add("adb-icon-button");
        previous.setOnAction(event -> selectRow(currentRow() - 1));

        Button next = IconButtons.icon(Icons.Icon.NEXT, I18n.t("grid.form.nextRow"));
        next.setId("data-record-next");
        next.getStyleClass().add("adb-icon-button");
        next.setOnAction(event -> selectRow(currentRow() + 1));

        Button last = IconButtons.icon(Icons.Icon.LAST, I18n.t("grid.form.lastRow"));
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
        Label recordLabel = new Label(I18n.t("grid.form.record"));
        recordLabel.getStyleClass().add("adb-record-nav__label");
        // ⭐ R34 N1-b：这一排的文字标签一律钉成「不许压缩」。
        //    英文比中文宽（记录 24px → Record 45px），没钉的话 HBox 会把它们
        //    压成「Rec…」「P…」—— 正是 U4 立誓要禁掉的那种压扁文字。
        recordLabel.setMinWidth(Region.USE_PREF_SIZE);
        recordPosition.setMinWidth(Region.USE_PREF_SIZE);

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
                        ? I18n.t("grid.batch.rows", size)
                        : I18n.t("grid.record.position", index + 1, size));
    }

    /**
     * ⭐ N16：批量动作菜单 —— 行多选后在网格里任意一格（或行首槽）右键打开。
     *
     * <p>旧的「N 行已选 · 复制 · …」批量条每选一行就多占一行高度、还会闪一下；
     * Navicat 的批量入口就是右键菜单 + 贴网格左下的行操作条（帧 3/4/6 复核）。
     * 逐项可用态与旧批量条**同一套判断**（这里改一遍，行操作条仍绑定工具条）。
     */
    private ContextMenu buildBatchMenu(List<Map<String, Object>> rows) {
        ContextMenu menu = new ContextMenu();
        menu.setId("data-batch-menu");

        MenuItem count = new MenuItem(I18n.t("grid.batch.selected", rows.size()));
        count.setId("data-batch-label");
        count.setDisable(true);

        boolean editable = tableSchema != null && tableSchema.editable();
        ColumnDef focused = focusedColumn();
        // ⛔ 主键列不能批量改（同 applyColumnValue）。
        boolean pkColumn = focused != null && focused.primaryKey();

        MenuItem copy = new MenuItem(I18n.t("grid.batch.copy"));
        copy.setId("data-batch-copy");
        copy.setOnAction(event -> copySelectedAsTsv());

        MenuItem copySql = new MenuItem(I18n.t("grid.copy.asSql"));
        copySql.setId("data-batch-sql");
        copySql.setOnAction(event -> copySelectedAsSql());

        MenuItem export = new MenuItem(I18n.t("grid.batch.export"));
        export.setId("data-batch-export");
        export.setOnAction(event -> exportSelectedCsv());

        MenuItem clone = new MenuItem(I18n.t("grid.batch.clone"));
        clone.setId("data-batch-clone");
        clone.setDisable(!editable);
        clone.setOnAction(event -> cloneSelectedRows());

        MenuItem setNull = new MenuItem(I18n.t("grid.batch.setNull"));
        setNull.setId("data-batch-null");
        setNull.setDisable(!editable || focused == null || !focused.nullable() || pkColumn);
        setNull.setOnAction(event -> setFocusedColumnValue(null));

        MenuItem setEmpty = new MenuItem(I18n.t("grid.batch.setEmpty"));
        setEmpty.setId("data-batch-empty");
        setEmpty.setDisable(!editable || focused == null || !focused.type().textual() || pkColumn);
        setEmpty.setOnAction(event -> setFocusedColumnValue(""));

        MenuItem delete = new MenuItem(I18n.t("grid.batch.delete"));
        delete.setId("data-batch-delete");
        delete.setDisable(!editable);
        delete.setOnAction(event -> deleteSelectedRows());

        MenuItem clear = new MenuItem(I18n.t("grid.batch.clearSelection"));
        clear.setId("data-batch-clear");
        clear.setOnAction(event -> grid.getSelectionModel().clearSelection());

        menu.getItems().addAll(count, new SeparatorMenuItem(), copy, copySql, export,
                clone, setNull, setEmpty, new SeparatorMenuItem(), delete, clear);
        return menu;
    }

    /** 测试用：按当前选中行构造批量菜单（不弹出）。 */
    ContextMenu batchMenuForTest() {
        return buildBatchMenu(List.copyOf(grid.getSelectionModel().getSelectedItems()));
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
     * ⭐ N19：网格左下的记录操作条 —— {@code + − ✓ ✗} 四颗 + 第 5 颗展开菜单。
     *
     * <p>Navicat 帧 3 的底栏左边就是这一簇纯图标；它们<b>不再占顶部工具条</b>，
     * 顶部只留 13 个具名键。按钮本体（{@code data-add / data-delete-rows /
     * data-save}）直接挪到这里，可用态判断仍走 {@link #updateEditControls()}，
     * 不做第二份 if。
     *
     * <p>撤销 / 重做放进第 5 颗展开菜单：它们仍是完整动作（菜单项可用态
     * 绑在原按钮上），只是不像 Navicat 那样常驻顶栏。
     */
    private HBox buildRowActions() {
        rowActions.setId("data-row-actions");
        rowActions.getStyleClass().add("adb-grid__row-actions");
        rowActions.setAlignment(Pos.CENTER_LEFT);

        rowCancelButton.setId("data-row-cancel");
        rowCancelButton.getStyleClass().setAll("adb-icon-button");
        rowCancelButton.setOnAction(event -> discardChanges());
        rowCancelButton.disableProperty().bind(saveButton.disableProperty());

        moreButton.setId("data-row-more");
        moreButton.getStyleClass().setAll("adb-icon-button");
        // 整条行操作条同一画法：灰的单线（彩色的编辑开关会盖过数据本身）。
        moreButton.setGraphic(Icons.of(Icons.Icon.MORE, Icons.SIZE_TOOLBAR));
        moreButton.setTooltip(new Tooltip(I18n.t("grid.toolbar.rowMore")));
        moreButton.setAccessibleText(I18n.t("grid.toolbar.rowMore"));
        MenuItem undoItem = new MenuItem(I18n.t("grid.toolbar.undo"));
        undoItem.setId("data-row-undo");
        undoItem.disableProperty().bind(undoButton.disableProperty());
        undoItem.setOnAction(event -> undo());
        MenuItem redoItem = new MenuItem(I18n.t("grid.toolbar.redo"));
        redoItem.setId("data-row-redo");
        redoItem.disableProperty().bind(redoButton.disableProperty());
        redoItem.setOnAction(event -> redo());
        MenuItem copyItem = new MenuItem(I18n.t("grid.batch.copy"));
        copyItem.setId("data-row-copy");
        copyItem.setOnAction(event -> copyRowsAsTsv(rowsForCopy(),
                I18n.t("grid.scope.selected")));
        MenuItem copySqlItem = new MenuItem(I18n.t("grid.copy.asSql"));
        copySqlItem.setId("data-row-copy-sql");
        copySqlItem.setOnAction(event -> copyRowsAsSql(rowsForCopy(),
                I18n.t("grid.scope.selected")));
        moreButton.getItems().addAll(undoItem, redoItem, new SeparatorMenuItem(),
                copyItem, copySqlItem);

        rowActions.getChildren().addAll(addRowButton, deleteRowsButton, saveButton,
                rowCancelButton, moreButton);
        return rowActions;
    }

    /**
     * ⭐ N19：Cell Editor —— 光标所在格的详情抽屉。
     *
     * <p>长文本 / JSON / CLOB 的多行编辑本来就在抽屉里完成；这颗键只是把它
     * 从双击与右键之外的第三条路摆到台面上（Navicat 的工具条上也有它）。
     * 没有光标时按钮是灰的 —— 不摆一颗点了没反应的键。
     */
    private void openCellEditor() {
        if (tableSchema == null || cursorRow < 0 || cursorColumnName == null
                || cursorRow >= grid.getItems().size()) {
            feedback.status(I18n.t("grid.msg.cellEditorNoCell"));
            return;
        }
        ColumnDef definition = tableSchema.columns().stream()
                .filter(column -> column.name().equals(cursorColumnName))
                .findFirst().orElse(null);
        if (definition != null) {
            showDetails(grid.getItems().get(cursorRow), definition);
        }
    }

    /**
     * ⭐ N19：能力面还没建的三颗键 —— 真按钮 + 如实说明页。
     *
     * <p>{@code Fill with AI / Data Profiling / Data Generation / Data Masking /
     * Create BI Workspace} 依赖 R42 的能力面；这一版没有后端，点开必须看到
     * 「本版本未提供」，而不是什么也不发生（死按钮）或假装在执行。
     */
    void showCapabilityNotice(String title, String capabilityKey) {
        Alert alert = capabilityAlert(title, capabilityKey);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.showAndWait();
    }

    /** 门禁接缝：说明页本体（不显示），断言标题与原因都可以在这里做。 */
    Alert capabilityAlert(String title, String capabilityKey) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogStyles.apply(alert, getScene() == null ? null : getScene().getWindow());
        alert.getDialogPane().setId("capability-notice");
        alert.setTitle(I18n.t("grid.capability.windowTitle"));
        alert.setHeaderText(title + " — " + I18n.t("grid.capability.title"));
        alert.setContentText(I18n.t("grid.capability.body", I18n.t(capabilityKey)));
        return alert;
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
        feedback.status(I18n.t("grid.msg.discarded", count));
    }

    /**
     * ⭐ N20：底栏 = 左下记录操作条 + 右下「五键翻页 · 页码框 · 齿轮 · 放大镜 · 两枚视图切换」。
     *
     * <p>Navicat 底栏没有任何行数 / 页大小 / 「跳至页」文字控件（§1.4）：记录计数在状态栏
     * （仍由 {@code feedback.range(...)} 送过去），页大小与「跳至记录」收进齿轮，
     * 页码框本身兼作跳页输入。
     *
     * <p>⭐ D-1：可用宽度按场景坐标量，并把详情浮层盖住的那一截让出来 ——
     * 浮层在 body 里向下溢出时 JavaFX 默认不裁边，正好压在底栏右端。
     */
    private HBox buildPager() {
        HBox pager = new HBox(6);
        pagerBox = pager;
        pager.setId("data-pager");
        pager.getStyleClass().add("adb-grid__pager");
        pager.setAlignment(Pos.CENTER_LEFT);
        // ⚠️ 不能在布局过程中直接改 managed —— 实测会把同一场景里对象标题栏
        //    的标签也挤成「u…」（多一轮布局把宽度传成了极窄值）。推迟到本轮
        //    布局结束再收，宽度是稳定的。
        pager.widthProperty().addListener((observable, oldValue, newValue) ->
                javafx.application.Platform.runLater(this::fitPagerToAvailableWidth));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        previous.setId("data-prev");
        previous.getStyleClass().add("adb-icon-button");
        previous.setOnAction(event -> previousPage());

        firstPageButton.setId("data-first");
        firstPageButton.getStyleClass().add("adb-icon-button");
        firstPageButton.setTooltip(new Tooltip(I18n.t("grid.pager.first")));
        firstPageButton.setAccessibleText(I18n.t("grid.pager.first"));
        firstPageButton.setOnAction(event -> firstPage());

        next.setId("data-next");
        next.getStyleClass().add("adb-icon-button");
        next.setOnAction(event -> nextPage());

        lastPageButton.setId("data-last");
        lastPageButton.getStyleClass().add("adb-icon-button");
        lastPageButton.setTooltip(new Tooltip(I18n.t("grid.pager.last")));
        lastPageButton.setAccessibleText(I18n.t("grid.pager.last"));
        lastPageButton.setOnAction(event -> lastPage());

        pageField.setId("data-page");
        pageField.getStyleClass().add("adb-grid__page");
        pageField.setPromptText(I18n.t("grid.pager.jumpPagePrompt"));
        pageField.setTooltip(new Tooltip(I18n.t("grid.pager.jumpPageTip")));
        pageField.setAccessibleText(I18n.t("grid.pager.jumpPageAria"));
        pageField.setPrefWidth(58);
        pageField.setMinWidth(48);
        pageField.setMaxWidth(72);
        pageField.setAlignment(Pos.CENTER);
        pageField.setOnAction(event -> {
            jumpToPage(pageField.getText());
            pageField.selectAll();
        });
        pageField.focusedProperty().addListener((observable, was, focused) -> {
            if (!focused) {
                resetPageFieldText();
            }
        });

        pageOptionsButton.setId("data-page-options");
        pageOptionsButton.getStyleClass().add("adb-icon-button");
        pageOptionsButton.setOnAction(event -> showPageOptions());
        pageOptionsButton.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                showPageOptions();
                event.consume();
            }
        });
        pageOptionsMenu.setId("data-page-options-menu");

        findToggle.setId("data-find-toggle");
        findToggle.getStyleClass().add("adb-icon-button");
        findToggle.setGraphic(Icons.of(Icons.Icon.SEARCH, Icons.SIZE_TOOLBAR));
        findToggle.setTooltip(new Tooltip(I18n.t("grid.find.toggle")));
        findToggle.setAccessibleText(I18n.t("grid.find.toggle"));
        findToggle.setOnAction(event -> {
            if (findToggle.isSelected()) {
                showFindBar();
            } else {
                hideFindBar();
            }
            findToggle.setSelected(findBar.isVisible());
        });

        styleViewToggle(gridToggle, Icons.Icon.GRID, "data-view-grid", I18n.t("grid.view.gridMode"));
        gridToggle.setSelected(true);
        gridToggle.setOnAction(event -> {
            if (formView) {
                setFormView(false);
            }
            syncViewToggles();
        });
        styleViewToggle(formToggle, Icons.Icon.LIST, "data-view-form", I18n.t("grid.view.form"));
        formToggle.setOnAction(event -> {
            setFormView(formToggle.isSelected());
            syncViewToggles();
        });

        // ⭐ D-1：浮层开合 / 改变尺寸都会改右侧可用宽度，实时重算。
        detailsDrawer.visibleProperty().addListener((observable, was, visible) ->
                javafx.application.Platform.runLater(this::fitPagerToAvailableWidth));
        detailsDrawer.layoutBoundsProperty().addListener(
                (observable, oldBounds, newBounds) -> fitPagerToAvailableWidth());

        pager.getChildren().addAll(buildRowActions(),
                new Separator(javafx.geometry.Orientation.VERTICAL), spacer,
                firstPageButton, previous, pageField, next, lastPageButton,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                pageOptionsButton, findToggle, gridToggle, formToggle);
        return pager;
    }

    private static void styleViewToggle(ToggleButton toggle, Icons.Icon icon, String id, String hint) {
        toggle.setId(id);
        toggle.getStyleClass().add("adb-icon-button");
        toggle.setGraphic(Icons.of(icon, Icons.SIZE_TOOLBAR));
        toggle.setTooltip(new Tooltip(hint));
        toggle.setAccessibleText(hint);
    }

    /** ⭐ N20：齿轮菜单 —— 每页行数 + 跳至记录（Navicat 把页大小收进齿轮）。 */
    private void showPageOptions() {
        pageOptionsMenu.getItems().clear();
        javafx.scene.control.Label header = new javafx.scene.control.Label(
                I18n.t("grid.pager.pageSizeAria"));
        header.setDisable(true);
        pageOptionsMenu.getItems().add(
                new javafx.scene.control.CustomMenuItem(header, false));
        for (PageSize option : PageSize.values()) {
            javafx.scene.control.RadioMenuItem item = new javafx.scene.control.RadioMenuItem(
                    I18n.t("grid.pager.pageSizeOption", option.value()));
            item.setId("data-page-size-" + option.value());
            item.setSelected(option.value() == pageSize);
            item.setDisable(loading);
            item.setOnAction(event -> {
                pageSize = option.value();
                offset = 0;
                load(false);
            });
            pageOptionsMenu.getItems().add(item);
        }
        pageOptionsMenu.getItems().add(new SeparatorMenuItem());
        MenuItem jump = new MenuItem(I18n.t("grid.pager.jumpRowMenu"));
        jump.setId("data-page-jump-row");
        jump.setDisable(loading);
        jump.setOnAction(event -> promptJumpToRow());
        pageOptionsMenu.getItems().add(jump);
        // 没挂进场景时没有可用的弹窗宿主（ContextMenu.show 会 NPE），菜单项照建不弹。
        if (pageOptionsButton.getScene() != null) {
            pageOptionsMenu.show(pageOptionsButton, javafx.geometry.Side.BOTTOM, 0, 0);
        }
    }

    private void promptJumpToRow() {
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog();
        dialog.setTitle(I18n.t("grid.pager.jumpRowAria"));
        dialog.setHeaderText(null);
        dialog.setContentText(I18n.t("grid.pager.jumpRowPrompt"));
        DialogStyles.apply(dialog, getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(this::jumpToRow);
    }

    /** 页码框 → 当前页（`total` 未知时也照实显示算出来的页号）。 */
    private void resetPageFieldText() {
        pageField.setText(total == 0
                ? "" : String.valueOf(QueryLimits.pageNumberOf(offset, pageSize)));
    }

    private void syncViewToggles() {
        boolean form = formView && !transpose;
        formToggle.setSelected(form);
        gridToggle.setSelected(!form);
    }

    /**
     * ⭐ D-1（N20 收尾）：底栏窄宽降级 —— 只撤「视图切换 + 放大镜」，五键翻页 / 页码框 /
     * 齿轮永远留着（撤了它们就翻不了页、改不了页大小、开不了查找）。
     *
     * <p>判据用 {@code prefWidth} 而不是 {@code minWidth}：{@code minWidth} 会被布局
     * 恒等地满足（{@code width ≥ minWidth}），降级循环第一项就 break，一项都不收 ——
     * R34 N1-b 实测踩过，记在这里防再犯。
     *
     * <p>可用宽度按场景坐标量（{@link #availablePagerWidth()}），不是盒子自己的宽度：
     * 详情浮层向下溢出时 JavaFX 不裁边，会直接压在底栏右端约 90px 上；
     * 按盒子宽度判「放得下」，那 90px 就永远露不出来。
     */
    private void fitPagerToAvailableWidth() {
        if (pagerBox == null || pagerBox.getScene() == null) {
            return;
        }
        double available = availablePagerWidth();
        if (available > 0) {
            foldPagerTo(available);
        }
    }

    /** 从右往左整项收起：表单视图 → 网格视图 → 放大镜；五键 / 页码框 / 齿轮永不收。 */
    private void foldPagerTo(double available) {
        javafx.scene.Node[] droppable = {formToggle, gridToggle, findToggle};
        for (javafx.scene.Node node : droppable) {
            showPagerPart(node, true);
        }
        for (javafx.scene.Node node : droppable) {
            if (pagerBox.prefWidth(-1) <= available) {
                break;
            }
            showPagerPart(node, false);
        }
    }

    /** 底栏真正**能看见**的宽度：场景右缘 / 父容器右缘 / 详情浮层左缘，三者取小。 */
    private double availablePagerWidth() {
        if (pagerBox == null || pagerBox.getScene() == null) {
            return 0;
        }
        javafx.geometry.Bounds self = pagerBox.localToScene(pagerBox.getBoundsInLocal());
        double left = self.getMinX();
        double right = Math.min(self.getMaxX(), pagerBox.getScene().getWidth());
        if (pagerBox.getParent() instanceof Region parent) {
            right = Math.min(right,
                    parent.localToScene(parent.getBoundsInLocal()).getMaxX()
                            - parent.getInsets().getRight());
        }
        if (detailsDrawer.isVisible() && detailsDrawer.getScene() != null) {
            javafx.geometry.Bounds drawer =
                    detailsDrawer.localToScene(detailsDrawer.getBoundsInLocal());
            boolean overlapsRow = drawer.getMaxY() > self.getMinY()
                    && drawer.getMinY() < self.getMaxY();
            if (overlapsRow) {
                right = Math.min(right, drawer.getMinX() - 8);
            }
        }
        return Math.max(0, right - left);
    }

    /** 门禁接缝：齿轮菜单（每页行数 + 跳至记录）。 */
    ContextMenu pageOptionsMenuForTest() {
        return pageOptionsMenu;
    }

    /** 门禁接缝：底栏此刻真正能看见的宽度（D-1 要看浮层压掉多少）。 */
    double availablePagerWidthForTest() {
        return availablePagerWidth();
    }

    /** 门禁接缝：底栏窄宽降级按合成宽度跑一次。 */
    void fitPagerWidthForTest(double width) {
        if (pagerBox != null) {
            foldPagerTo(width);
        }
    }

    private static void showPagerPart(javafx.scene.Node node, boolean present) {
        node.setVisible(present);
        node.setManaged(present);
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
            showError(I18n.t("grid.pager.offsetLimit", QueryLimits.MAX_OFFSET));
            return;
        }
        offset = lastOffset;
        load(false);
    }

    /** 跳页：页码从 1 开始；越界或非法输入不动作并给出行内提示。 */
    void jumpToPage(String raw) {
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
            feedback.status(I18n.t("grid.pager.jumpPageNotNumber", text));
            return;
        }
        if (page < 1) {
            feedback.status(I18n.t("grid.pager.jumpPageZero"));
            return;
        }
        long pages = QueryLimits.totalPages(total, pageSize);
        if (pages >= 0 && page > pages) {
            feedback.status(I18n.t("grid.pager.jumpPageMax", pages));
            return;
        }
        long target = (page - 1) * pageSize;
        if (QueryLimits.exceedsOffsetLimit(target)) {
            showError(I18n.t("grid.pager.offsetLimit", QueryLimits.MAX_OFFSET));
            return;
        }
        if (target == offset) {
            resetPageFieldText();
            return;
        }
        offset = target;
        resetPageFieldText();
        load(false);
    }

    /**
     * ⭐ G7：跳到第 N 行（跨页也能跳）。
     *
     * <p>记录号是**绝对记录号**（与底栏「Record N of M」同一口径），所以先算出目标行所在的页，
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
            feedback.status(I18n.t("grid.pager.jumpRowNotNumber", text));
            return;
        }
        if (row < 1) {
            feedback.status(I18n.t("grid.pager.jumpRowZero"));
            return;
        }
        if (total >= 0 && row > total) {
            feedback.status(I18n.t("grid.pager.jumpRowMax", total));
            return;
        }
        long targetOffset = ((row - 1) / pageSize) * pageSize;
        if (QueryLimits.exceedsOffsetLimit(targetOffset)) {
            showError(I18n.t("grid.pager.offsetLimit", QueryLimits.MAX_OFFSET));
            return;
        }
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
        feedback.status(I18n.t("grid.pager.jumpedToRow", offset + index + 1));
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
            showError(I18n.t("grid.pager.offsetLimit", QueryLimits.MAX_OFFSET));
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
        feedback.status(I18n.t("grid.msg.loading", tableName, currentOffset));

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
            showError(I18n.t("grid.msg.loadFailed", readable(error)));
            feedback.status(I18n.t("grid.msg.loadFailedTable", tableName, readable(error)));
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
            java.util.List<String> before = tableSchema == null ? java.util.List.of()
                    : tableSchema.columns().stream().map(ColumnDef::name).toList();
            java.util.List<String> after = result.schema().columns().stream()
                    .map(ColumnDef::name).toList();
            boolean columnsChanged = !before.equals(after);
            tableSchema = result.schema();
            renderColumns(result.schema());
            filterButton.setDisable(false);
            // 列集变了，旧面板的行引用的是旧 schema —— 直接重建，别让草稿指向不存在的列。
            if (columnsChanged && filterPanel != null) {
                filterPanelHost.getChildren().clear();
                filterPanel = null;
                filterPanelHost.setVisible(false);
                filterPanelHost.setManaged(false);
                filterButton.setSelected(false);
            }
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
                ? I18n.t("grid.msg.truncated", tableName, lastRowCount)
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
            objectHeader.setCount(I18n.t("grid.batch.rows", total));
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
        // ⭐ G2：向会话核对一次真实事务状态（见 refreshTransactionState）。
        refreshTransactionState();
        updateEditControls();
        // U8：切回来只恢复事实段（范围/语句/耗时/待提交），不再播一句
        // 「已激活：users · 200 行」—— 表名在标签上、行数在范围段里，这句话没有新信息。
        feedback.status("");
    }

    // ─────────────────────────── 表格 ───────────────────────────

    // ─────────────── G1：冻结列 ───────────────

    /** 冻结前 N 个数据列（不含行首槽，它天然跟着冻结）。0 = 解冻。 */
    public void setFrozenColumnCount(int count) {
        int next = Math.max(0, count);
        if (next == frozenColumnCount) {
            return;
        }
        frozenColumnCount = next;
        applyFrozenColumns();
        feedback.status(next == 0 ? I18n.t("grid.msg.unfrozen") : I18n.t("grid.msg.frozen", next));
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
        // 第 1 列是行首槽（N16），它永远跟着冻结区走；
        // 至少给右表留一列，否则「冻结」就变成把整张表搬走了。
        int move = Math.min(1 + frozenColumnCount, all.size() - 1);
        if (move <= 1) {
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
        // 列实例重建了 —— 旧的光标列表头已经不在场景图里，别留着引用。
        currentHeaderColumn = null;
        grid.setEditable(schema.editable());

        // ⭐ N16：行首槽 —— 帧 3/4/6 8–12× 复核（2026-10-31）实测是一条
        //    ~12px 的空白窄槽：没有行号、没有复选框，也没有铅笔；铅笔只在
        //    「这一行有未保存修改」时出现。所以这里不给表头文字，只在脏行 /
        //    草稿行 / 待删行放状态图标；批量入口改走右键菜单（N16）。
        TableColumn<Map<String, Object>, Void> gutter = new TableColumn<>();
        gutter.getStyleClass().add("adb-grid__gutter-column");
        gutter.setSortable(false);
        gutter.setResizable(false);
        // 16px = 实测 12px 槽 + 12px 图标（SIZE_BADGE）居中所需的余量。
        gutter.setPrefWidth(16);
        gutter.setMinWidth(16);
        gutter.setMaxWidth(16);
        gutter.setCellFactory(column -> new TableCell<>() {
            {
                getStyleClass().add("adb-grid__gutter");
                setAlignment(Pos.CENTER);
                setOnContextMenuRequested(event -> {
                    if (isEmpty() || getIndex() < 0 || getIndex() >= grid.getItems().size()) {
                        return;
                    }
                    Map<String, Object> row = getTableRow() == null
                            ? null : getTableRow().getItem();
                    if (row == null) {
                        return;
                    }
                    if (!grid.getSelectionModel().isSelected(getIndex())) {
                        grid.getSelectionModel().clearAndSelect(getIndex());
                    }
                    buildBatchMenu(List.copyOf(grid.getSelectionModel().getSelectedItems()))
                            .show(this, event.getScreenX(), event.getScreenY());
                    event.consume();
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("adb-grid__gutter--dirty",
                        "adb-grid__gutter--new", "adb-grid__gutter--deleted");
                setText(null);
                setGraphic(null);
                if (empty) {
                    return;
                }
                Map<String, Object> row = getTableRow() == null
                        ? null : getTableRow().getItem();
                if (row == null) {
                    return;
                }
                if (draftId(row) != null) {
                    setGraphic(rowStateGlyph(Icons.Icon.ADD));
                    getStyleClass().add("adb-grid__gutter--new");
                    return;
                }
                RowIdentity identity = identity(row);
                if (identity == null) {
                    return;
                }
                if (editBuffer.isDeleted(identity)) {
                    setGraphic(rowStateGlyph(Icons.Icon.CLOSE));
                    getStyleClass().add("adb-grid__gutter--deleted");
                } else if (editBuffer.hasRowEdits(identity)) {
                    setGraphic(rowStateGlyph(Icons.Icon.EDIT));
                    getStyleClass().add("adb-grid__gutter--dirty");
                }
            }
        });
        grid.getColumns().add(gutter);

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
     * <p>列头是两行：列名 + 类型（{@link #columnTypeLabel}），
     * 右侧还有排序徽标与筛选漏斗。只按列名算会漏掉
     * 这些固定装饰，于是又回到 72px 的老问题。
     */
    /**
     * 行首槽的状态标记（U10）：新增 / 待保存 / 标记删除。
     *
     * <p>以前是文本符号「＋ × ●」拼在行号前面 —— 符号随字体走、颜色只能整格继承，
     * 在中文行字体下三颗标记大小不一。现在是矢量图标（N16 后行号本身取消），
     * 颜色由 {@code .adb-grid__gutter--*} 的 {@code -fx-stroke} 规则给。
     */
    private static javafx.scene.shape.SVGPath rowStateGlyph(Icons.Icon icon) {
        // Icons.of 已经挂 .adb-icon / .adb-icon--filled，这里不再叠一个只有 Java 侧认识的类。
        return Icons.of(icon, Icons.SIZE_DOT);
    }

    private double readableMinWidth(ColumnDef definition) {
        return ColumnWidths.dataColumnMin(definition.name(), columnTypeLabel(definition));
    }

    /**
     * ⭐ N19：列头第二行**只放类型**（Navicat 帧 3/6 实测）。
     *
     * <p>数值列带 {@code #} 前缀（帧上写作 {@code # int(11)} / {@code # bigint(20)}），
     * 其余类型原样。约束（主键 / 非空 / 自增）不再挤进这一行 —— 它们改由
     * 类型标签的 tooltip 说明，信息不丢，第二行回到「一眼认出类型」这一件事。
     */
    private static String columnTypeLabel(ColumnDef definition) {
        String label = typeLabel(definition);
        return definition.type() == ColumnType.NUMBER ? "# " + label : label;
    }

    /**
     * 类型 + 约束摘要（{@code number · 主键 · 自增} / {@code text · 非空}）。
     *
     * <p>⭐ U5 的信息保留在这里：约束仍然写得明白，只是从列头第二行挪到
     * 类型标签的 tooltip（N19 之后第二行只放类型）。
     */
    private String columnConstraintLabel(ColumnDef definition) {
        boolean autoIncrement = tableSchema != null
                && tableSchema.autoIncrement(definition.name());
        StringBuilder label = new StringBuilder(typeLabel(definition));
        if (definition.primaryKey()) {
            label.append(" · ").append(I18n.t("grid.head.pk"));
        } else if (!definition.nullable()) {
            label.append(" · ").append(I18n.t("grid.head.notNull"));
        }
        if (autoIncrement) {
            label.append(" · ").append(I18n.t("grid.head.autoInc"));
        }
        return label.toString();
    }

    private static int visualLength(String text) {
        return ColumnWidths.visualLength(text);
    }

    private String layoutKey(String suffix) {
        // ⭐ D-18：连接名与表名都是自由文本，键必须走带长度上限的组装。
        return SettingsStore.key("ui.grid", connectionName, tableName, suffix);
    }

    /**
     * 列宽防抖是否还在跑（只给门禁看，同包）。
     *
     * <p>开这个口子的理由和 {@code StartupTrace} 那条一样：要断言的是
     * 「关页签之后不再有待写的盘」，从外部只能靠等 400ms 再看有没有异常，
     * 那种断言既慢又只在特定调度下才红。
     */
    boolean layoutSavePending() {
        return layoutSaveTimer != null
                && layoutSaveTimer.getStatus() == javafx.animation.Animation.Status.RUNNING;
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

    /** 可见的**数据列**（排除行首槽 —— 它固定在左边）。 */
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
     * @return 数据列的序号；落在行首槽上或不在网格里时返回 -1
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
     * <p>行首槽**固定在左边**，不参与重排 —— 和 Navicat 一致。
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
        // ⭐ G6：自增列必须标出来 —— 新增行时这一列**留空**就行，数据库自动赋值。
        //    不标出来，用户会以为得自己填，然后撞主键。
        // ⭐ N19：第一行只有列名，第二行只有类型；约束进类型标签的 tooltip。
        Label name = new Label(definition.name());
        name.setId("data-header-" + definition.name());
        name.getStyleClass().add("adb-grid__column-name");
        if (definition.comment() != null && !definition.comment().isBlank()) {
            // 第二行只放类型，列注释仍需要一个出口。
            name.setTooltip(new Tooltip(definition.comment()));
        }

        Label type = new Label(columnTypeLabel(definition));
        type.getStyleClass().add("adb-grid__column-type");
        type.setTooltip(new Tooltip(columnConstraintLabel(definition)));
        type.setAccessibleText(definition.name() + " " + columnConstraintLabel(definition));

        Label sortBadge = new Label();
        sortBadge.getStyleClass().add("adb-grid__sort-badge");
        sortBadge.setVisible(false);
        sortBadge.setManaged(false);

        // ⭐ 只有图标的按钮：Tooltip（鼠标）+ accessibleText（读屏）都要有。
        Button funnel = IconButtons.icon(Icons.Icon.FILTER, I18n.t("grid.head.filterByValues", definition.name()));
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
                        + I18n.t("grid.head.dragHint")));
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
            MenuItem ascending = new MenuItem(I18n.t("grid.head.sortAsc"));
            ascending.setOnAction(action -> {
                query = query.withSorts(List.of(new SortSpec(definition.name(), true)));
                offset = 0;
                renderQueryState();
                load(false);
            });
            // ⭐ G2：右键也能改顺序 —— 拖不动的场景（触控板 / 窄列）有精确入口。
            MenuItem moveLeft = new MenuItem(I18n.t("grid.head.moveLeft"));
            moveLeft.setId("data-move-left");
            moveLeft.setOnAction(action -> nudgeColumn(definition.name(), -1));
            MenuItem moveRight = new MenuItem(I18n.t("grid.head.moveRight"));
            moveRight.setId("data-move-right");
            moveRight.setOnAction(action -> nudgeColumn(definition.name(), 1));
            MenuItem moveFirst = new MenuItem(I18n.t("grid.head.moveFirst"));
            moveFirst.setId("data-move-first");
            moveFirst.setOnAction(action -> moveColumnTo(definition.name(), 0));
            MenuItem moveLast = new MenuItem(I18n.t("grid.head.moveLast"));
            moveLast.setId("data-move-last");
            moveLast.setOnAction(action -> moveColumnTo(definition.name(),
                    dataColumns().size() - 1));

            MenuItem descending = new MenuItem(I18n.t("grid.head.sortDesc"));
            descending.setOnAction(action -> {
                query = query.withSorts(List.of(new SortSpec(definition.name(), false)));
                offset = 0;
                renderQueryState();
                load(false);
            });
            MenuItem clearSort = new MenuItem(I18n.t("grid.head.clearSort"));
            clearSort.setDisable(query.indexOfSort(definition.name()) < 0);
            clearSort.setOnAction(action -> removeSort(definition.name()));
            MenuItem filter = new MenuItem(I18n.t("grid.head.filterColumn"));
            filter.setOnAction(action -> showFilterPanel(definition, funnel));
            MenuItem hide = new MenuItem(I18n.t("grid.head.hideColumn"));
            hide.setOnAction(action -> hideColumn(definition.name()));
            MenuItem fit = new MenuItem(I18n.t("grid.head.fit"));
            fit.setOnAction(action -> autoFitColumn(definition.name()));
            // ⭐ UX-4：冻结入口收敛到列头右键。以前只能拖分隔条，
            //    分隔条看不见就等于功能不存在。
            MenuItem freezeHere = new MenuItem(I18n.t("grid.head.freezeHere"));
            freezeHere.setId("data-freeze-here");
            boolean alreadyFrozen = isColumnFrozen(definition.name());
            freezeHere.setDisable(alreadyFrozen
                    || dataColumnsOrdered().size() - indexOfDataColumn(definition.name()) < 2);
            freezeHere.setOnAction(action -> setFrozenColumnCount(
                    indexOfDataColumn(definition.name()) + 1));
            MenuItem unfreeze = new MenuItem(I18n.t("grid.head.unfreezeColumns"));
            unfreeze.setId("data-unfreeze");
            unfreeze.setDisable(frozenColumnCount == 0);
            unfreeze.setOnAction(action -> setFrozenColumnCount(0));
            MenuItem copyName = new MenuItem(I18n.t("grid.head.copyName"));
            copyName.setOnAction(action -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(definition.name());
                Clipboard.getSystemClipboard().setContent(content);
                feedback.status(I18n.t("grid.msg.copiedColumnName", definition.name()));
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
            feedback.status(I18n.t("grid.head.keepOne"));
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
        feedback.status(I18n.t("grid.msg.sorted", sortSummary()));
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
            return I18n.t("grid.overview.none");
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
                    feedback.status(I18n.t("grid.msg.filtered", condition.summary()));
                },
                () -> clearFilter(definition.name()));
        panel.show(anchor);
    }

    /**
     * ⭐ N31：Filter & Sort 面板的开合与工具条按钮按下态是同一个状态。
     * 打开时用**已应用的**查询播种草稿；关掉不应用任何未提交的改动。
     */
    private void setFilterPanelOpen(boolean open) {
        if (tableSchema == null || (open && !ensureFilterPanel())) {
            filterButton.setSelected(false);
            return;
        }
        filterButton.setSelected(open);
        filterPanelHost.setVisible(open);
        filterPanelHost.setManaged(open);
        if (open) {
            filterPanel.seed(query);
            filterPanel.requestFocus();
        }
    }

    private boolean ensureFilterPanel() {
        if (filterPanel != null) {
            return true;
        }
        if (tableSchema == null) {
            return false;
        }
        filterPanel = new FilterSortPanel(tableSchema, this::applyCriteria,
                () -> showCapabilityNotice(I18n.t("filter.panel.addGroup"),
                        "grid.capability.filterGroup"));
        filterPanelHost.getChildren().setAll(filterPanel);
        return true;
    }

    /** 面板里按下 Apply 才走到这里 —— 生成新查询、重查一次，并刷新 chip / 表头徽标。 */
    private void applyCriteria(DataQuery next) {
        query = next;
        invalidateFilters();
        feedback.status(I18n.t("grid.msg.criteriaApplied"));
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
        feedback.status(I18n.t("grid.msg.cleared"));
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
            Button chip = chip(I18n.t("grid.chip.sort", sort.column()) + (sort.ascending() ? " ↑" : " ↓"),
                    () -> removeSort(sort.column()));
            chip.getStyleClass().add("adb-chip--sort");
            chipBar.getChildren().add(chip);
        }
        if (query.sorts().size() > 1) {
            Button clearSorts = new Button(I18n.t("grid.chip.clearSorts"));
            clearSorts.setMnemonicParsing(false);
            clearSorts.getStyleClass().addAll("adb-chip", "adb-chip--muted");
            clearSorts.setOnAction(event -> clearSorts());
            chipBar.getChildren().add(clearSorts);
        }
        boolean visible = !query.isDefault();
        if (visible) {
            clearQueryButton.setDisable(false);
            chipBar.getChildren().add(clearQueryButton);
        }
        chipBar.setVisible(visible);
        chipBar.setManaged(visible);
        clearQueryButton.setDisable(query.isDefault());
        updateHeaderSortBadges();
        // ⭐ N31：外部改查询（列头排序、漏斗、清除 chip）时，面板在**没有脏草稿**的
        //    前提下跟着重播种；用户正在编辑的草稿不被悄悄覆盖。
        if (filterPanel != null && filterPanelHost.isVisible()) {
            filterPanel.adopt(query);
        }
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
        // ⭐ G4：表单视图 —— 整块换渲染（与转置同一层），网格/冻结区/转置表一起让位。
        boolean form = formView && !transpose;
        grid.setVisible(!transpose && !form);
        grid.setManaged(!transpose && !form);
        // ⭐ G1：转置视图下不该露出冻结区（那是另一套列，冻了也没意义）。
        frozenGrid.setVisible(!transpose && !form && frozenColumnCount > 0);
        frozenGrid.setManaged(!transpose && !form && frozenColumnCount > 0);
        transposeTable.setVisible(transpose);
        transposeTable.setManaged(transpose);
        formSection.setVisible(form);
        formSection.setManaged(form);
        formPane.setVisible(form);
        formPane.setManaged(form);
        // ⭐ 行导航是表单的内嵌子块：可见性必须跟父块一致，别只靠「父隐藏」。
        recordNav.setVisible(form);
        recordNav.setManaged(form);
        if (transpose) {
            rebuildTranspose(visible);
        }
        // ⭐ G4：行 / 页 / 筛选变了，表单按「当前选中行」重刷（没开表单是空操作）。
        refreshForm();
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
        TableColumn<Map<String, Object>, String> field = new TableColumn<>(I18n.t("grid.transpose.field"));
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
            String title = draft != null ? I18n.t("grid.transpose.newDraft") : Long.toString(offset + i + 1);
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
        // ⭐ G4 互斥：表单和转置都是「整块换渲染」，后开的赢 ——
        //    转置只读、表单可写，同时开着只会让人分不清改的值落在哪个视图。
        if (value && formView) {
            formView = false;
            formItem.setSelected(false);
        }
        rebuildItems();
        syncViewToggles();
        feedback.status(value ? I18n.t("grid.view.transposed") : I18n.t("grid.view.backToGrid"));
    }

    /**
     * ⭐ G4：表单视图 —— 整页换成单记录纵向表单（网格 ↔ 表单双向切换的第一半）。
     *
     * <p>与转置<b>互斥</b>：转置是只读的整块渲染，压掉表单是同一规则的后开的赢。
     * 切换不丢任何状态 —— 编辑缓冲、选中行、分页都在，表单只是网格的另一种画法。
     */
    void setFormView(boolean value) {
        formView = value;
        formItem.setSelected(value);
        if (value && transpose) {
            setTranspose(false);
        }
        rebuildItems();
        syncViewToggles();
        feedback.status(value ? I18n.t("grid.view.formMode") : I18n.t("grid.view.backToGrid"));
    }

    // ───────────────────── G4 表单视图 ─────────────────────

    /** 表单控件能否写 —— 与格子内联编辑同一口径（可编辑表 + 非主键 + 不在载入/保存中）。 */
    private boolean formFieldEditable(ColumnDef definition) {
        return tableSchema != null && tableSchema.editable()
                && !definition.primaryKey() && !loading && !saving;
    }

    /** 按 schema 建一遍字段行（只在 schema 换了的时候发生，见 refreshForm 的懒重建）。 */
    private void rebuildForm() {
        formRows.getChildren().clear();
        formPosition.getStyleClass().add("adb-grid__form-header");
        formRows.getChildren().add(formPosition);
        if (tableSchema == null) {
            return;
        }
        for (ColumnDef definition : tableSchema.columns()) {
            formRows.getChildren().add(buildFormField(definition));
        }
    }

    /** 字段行 = 列名 + 类型（可空列不标）+ 控件；行上挂 definition 供 refresh 定位。 */
    private HBox buildFormField(ColumnDef definition) {
        Label name = new Label(definition.name());
        name.getStyleClass().add("adb-grid__form-label");
        Label type = new Label(definition.displayType()
                + (definition.nullable() ? "" : " · NOT NULL"));
        type.getStyleClass().add("adb-grid__form-type");
        Node control = definition.type() == ColumnType.BOOLEAN
                ? buildFormBoolean(definition)
                : buildFormText(definition);
        HBox row = new HBox(8);
        row.getStyleClass().add("adb-grid__form-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setUserData(definition);
        name.setPrefWidth(130);
        name.setMinWidth(96);
        // ⭐ 150px：`BOOLEAN · NOT NULL` / `TIMESTAMP · NOT NULL` 在 110px 下会被截成
        //    `BOOLEAN · NOT …`（p2-form 帧实拍）—— 类型后面那半句「不许为空」是
        //    这一行最有用的信息，宁可让输入框少 40px。
        type.setPrefWidth(150);
        type.setMinWidth(110);
        HBox.setHgrow(control, Priority.ALWAYS);
        if (control instanceof javafx.scene.control.Control styled) {
            styled.setMaxWidth(Double.MAX_VALUE);
        }
        row.getChildren().addAll(name, type, control);
        return row;
    }

    private TextField buildFormText(ColumnDef definition) {
        TextField field = new TextField();
        field.setId("data-form-" + definition.name());
        field.getStyleClass().add("adb-grid__form-field");
        field.setDisable(!formFieldEditable(definition));
        // 提交走两条真实路径：Enter、失焦 —— 与格子内联编辑同一套语义。
        field.setOnAction(event -> commitFormField(definition, field.getText()));
        field.focusedProperty().addListener((observable, was, focused) -> {
            if (was && !focused) {
                commitFormField(definition, field.getText());
            }
        });
        return field;
    }

    private CheckBox buildFormBoolean(ColumnDef definition) {
        CheckBox box = new CheckBox();
        box.setId("data-form-" + definition.name());
        box.getStyleClass().add("adb-grid__form-check");
        box.setDisable(!formFieldEditable(definition));
        box.selectedProperty().addListener((observable, was, selected) ->
                commitFormField(definition, selected));
        return box;
    }

    /**
     * 表单提交 —— 与格子内联编辑走<b>同一个</b> {@link #setRowValue}（→ 编辑缓冲）。
     * 解析失败 / 违反 NOT NULL 时打回原值并播报，不静默吞输入。
     */
    private void commitFormField(ColumnDef definition, Object raw) {
        if (formRefreshing || !formFieldEditable(definition)) {
            return;
        }
        int index = currentRow();
        if (index < 0 || index >= grid.getItems().size()) {
            return;
        }
        Map<String, Object> row = grid.getItems().get(index);
        try {
            Object next = raw instanceof Boolean value ? value
                    : parseCellValue(definition, String.valueOf(raw == null ? "" : raw));
            if (next == null && !definition.nullable()) {
                feedback.status(I18n.t("grid.msg.invalidNotNull", definition.name()));
                refreshForm();
                return;
            }
            setRowValue(row, definition, next);
        } catch (IllegalArgumentException e) {
            feedback.status(I18n.t("grid.msg.invalidValue", e.getMessage()));
            refreshForm();
        }
    }

    /**
     * 表单的值永远跟「网格当前选中行」走，读的是<b>显示口径</b>（{@link #cellValue}，
     * 缓冲已合并）—— 网格里改、查找替换、撤销、保存后的重载，最终都经过
     * updateDirty / rebuildItems / 选中监听汇到这里。正在打字的控件跳过刷新，
     * 不让程序刷新把输入踩掉。
     */
    private void refreshForm() {
        if (!formView) {
            return;
        }
        if (formSchema != tableSchema) {
            formSchema = tableSchema;
            rebuildForm();
        }
        int size = grid.getItems().size();
        int index = currentRow();
        formPosition.setText(index < 0 || size == 0
                        ? I18n.t("grid.form.noRecord")
                        : I18n.t("grid.form.position", index + 1, size));
        if (tableSchema == null) {
            return;
        }
        Map<String, Object> row = index < 0 ? null : grid.getItems().get(index);
        formRefreshing = true;
        try {
            for (Node node : formRows.getChildren()) {
                if (!(node instanceof HBox formRow)
                        || !(formRow.getUserData() instanceof ColumnDef definition)) {
                    continue;
                }
                Node control = formRow.getChildren().get(formRow.getChildren().size() - 1);
                if (control.isFocused()) {
                    continue;
                }
                Object value = row == null ? null : cellValue(row, definition);
                if (control instanceof CheckBox box) {
                    // ⭐ 与网格同一口径：SQLite 的 BOOLEAN 列取回来是 0/1，
                    //   不能只认 Boolean.TRUE（否则库里是 1，表单却显示未勾选）。
                    box.setSelected(Boolean.TRUE.equals(CellRenderer.booleanValue(value)));
                } else if (control instanceof TextField field) {
                    field.setText(value == null ? "" : String.valueOf(value));
                    field.setPromptText(value == null ? I18n.t("grid.form.nullPrompt") : "");
                }
            }
        } finally {
            formRefreshing = false;
        }
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
            summaryLabel.setText(I18n.t("grid.summary.empty"));
            return;
        }
        StringBuilder text = new StringBuilder(I18n.t("grid.summary.rows", rows.size()));
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
                text.append(" · ").append(definition.name()).append(" ")
                        .append(I18n.t("grid.summary.stats", formatNumber(sum), formatNumber(sum / count), formatNumber(min), formatNumber(max)));
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
        formItem.setSelected(formView);
    }

    private void updateSelectAllState() {
        int selected = grid.getSelectionModel().getSelectedItems().size();
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
                        ? I18n.t("grid.msg.rowAddedAuto", tableSchema.autoIncrementColumn())
                        : I18n.t("grid.msg.rowAdded"));
    }

    private void cloneRow(Map<String, Object> row) {
        if (tableSchema == null || !tableSchema.editable() || loading || saving) {
            return;
        }
        long id = editBuffer.addDraft(cloneValues(row));
        rebuildItems();
        selectDraft(id);
        updateDirty();
        feedback.status(I18n.t("grid.msg.clonedOne"));
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
        feedback.status(I18n.t("grid.msg.cloned", ids.size()));
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
            feedback.status(I18n.t("grid.msg.draftRemoved", io.agenticdb.ui.Shortcuts.of("Mod+Z")));
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
            feedback.status(I18n.t("grid.msg.cellModified", definition.name(), CellValueParser.display(next)));
        }
    }

    private void setRowValue(Map<String, Object> row, ColumnDef definition, Object next) {
        Long draftId = draftId(row);
        if (draftId != null) {
            if (editBuffer.setDraftCell(draftId, definition.name(), next)) {
                updateDirty();
                grid.refresh();
                feedback.status(I18n.t("grid.msg.draftModified", definition.name(), CellValueParser.display(next)));
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
            feedback.status(I18n.t("grid.msg.markedDeleteOne", identity.display(), io.agenticdb.ui.Shortcuts.of("Mod+Z")));
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
            feedback.status(I18n.t("grid.msg.markedDeleteRows", marked, io.agenticdb.ui.Shortcuts.of("Mod+Z")));
        }
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
            feedback.status(I18n.t("grid.batch.needFocus"));
            return;
        }
        applyColumnValue(definition, next);
    }

    void setColumnValue(String columnName, Object next) {
        ColumnDef definition = columnDef(columnName);
        if (definition == null) {
            feedback.status(I18n.t("grid.batch.unknownColumn", columnName));
            return;
        }
        applyColumnValue(definition, next);
    }

    private void applyColumnValue(ColumnDef definition, Object next) {
        if (next == null && !definition.nullable()) {
            feedback.status(I18n.t("grid.batch.notNull", definition.name()));
            return;
        }
        if ("".equals(next) && !definition.type().textual()) {
            feedback.status(I18n.t("grid.batch.notText", definition.name()));
            return;
        }
        // ⛔ 主键列是保护区。单元格编辑路径有 cellEditable 的主键检查，
        //    批量路径以前漏了，导致 VARCHAR/TEXT 主键能被批量「设空串」，
        //    生成 UPDATE t SET pk='' WHERE pk=? —— 主键损坏、唯一约束冲突。
        if (definition.primaryKey()) {
            feedback.status(I18n.t("grid.batch.primaryKey", definition.name()));
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
            feedback.status(I18n.t("grid.batch.applied", changed, definition.name(),
                    (next == null ? "NULL" : I18n.t("grid.batch.emptyString")),
                    io.agenticdb.ui.Shortcuts.of("Mod+Z")));
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

    /** ⌘A：全选当前页。 */
    private void selectAllCurrentPage() {
        if (grid.getItems().isEmpty()) {
            feedback.status(I18n.t("grid.batch.noneToSelect"));
            return;
        }
        grid.getSelectionModel().selectAll();
        feedback.status(I18n.t("grid.batch.pageSelected", grid.getItems().size()));
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
            feedback.status(I18n.t("grid.msg.pickCell"));
            return;
        }
        TableColumn<Map<String, Object>, ?> column =
                (TableColumn<Map<String, Object>, ?>) focused.getTableColumn();
        if (column == null || column.getUserData() == null) {
            feedback.status(I18n.t("grid.msg.pickDataCell"));
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
        feedback.status(I18n.t("grid.msg.copiedCellValue"));
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
        findField.setPromptText(I18n.t("grid.find.prompt"));
        findField.setPrefWidth(180);
        findField.setMinWidth(110);
        findField.getStyleClass().add("adb-grid__find-field");
        findField.setOnAction(event -> findNext());

        replaceField.setId("data-replace");
        replaceField.setPromptText(I18n.t("grid.find.replacePrompt"));
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

        Button close = IconButtons.icon(Icons.Icon.CLOSE, I18n.t("grid.find.close"));
        close.setId("data-find-close");
        close.getStyleClass().add("adb-icon-button");
        close.setOnAction(event -> hideFindBar());

        // ⭐ N19：本页筛选从工具条挪进查找条 —— 顶部那一排只放 Navicat 的
        //    13 个具名键与右端两颗小键。⌘F 打开查找条后两个输入框都在：
        //    前方是逐格查找，末尾是本页行过滤。
        searchField.setId("data-search");
        searchField.getStyleClass().add("adb-grid__find-field");
        searchField.setPromptText(I18n.t("grid.search.prompt"));
        searchField.setPrefWidth(180);
        searchField.setMinWidth(110);
        searchField.setTooltip(new Tooltip(I18n.t("grid.search.tooltip")));
        searchField.textProperty().addListener((observable, previous, value) -> rebuildItems());

        findBar.getChildren().addAll(findField, findNextButton, replaceField,
                replaceOneButton, replaceAllButton, findCaseBox,
                new Separator(javafx.geometry.Orientation.VERTICAL), searchField, close);
    }

    /**
     * 打开查找条：预填当前焦点格的值 —— 有选中就从选中开始找（Navicat 同款）。
     *
     * <p>转置视图不开：它是只读渲染，选中落在隐藏的网格里用户看不见，
     * 「找到了」就成了没有落点的谎话。
     */
    private void showFindBar() {
        if (transpose) {
            feedback.status(I18n.t("grid.find.transposeReadOnly"));
            return;
        }
        findBar.setVisible(true);
        findBar.setManaged(true);
        findToggle.setSelected(true);
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
        findToggle.setSelected(false);
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
     * 截图 / 验收（R34 收官）：把某一列的「按值筛选」浮窗摆出来。
     *
     * <p>锚点那颗漏斗的 id 是动态拼的（{@code data-filter-<列名>}），而
     * {@code ScreenshotScriptAuditTest} 只认源码里字面存在的 {@code setId("...")} ——
     * 脚本点不到它。所以这里程序化点：查到那颗按钮就 {@code fire()}，
     * 走的仍是它自己那条 {@code showFilterPanel} 路径，浮窗内容与真点一致。
     * 返回 false = 列头还没建出来（表结构是异步读的），调用方继续轮询。
     */
    boolean poseFilterPanel(String column) {
        if (tableSchema == null) {
            return false;
        }
        if (lookup("#data-filter-" + column) instanceof Button button) {
            button.fire();
            return true;
        }
        return false;
    }

    /**
     * 截图 / 验收（R38 N31）：打开内嵌 Filter & Sort 面板并摆出一条完整草稿。
     *
     * <p>只摆不应用 —— 面板打开与草稿内容由这一帧看，Apply 的生效路径由
     * {@code FilterPanelAuditTest} 真点按钮验收。
     * 返回 false = 表结构 / 行还没就绪，调用方继续轮询。
     */
    boolean poseFilterSortPanel() {
        if (tableSchema == null || grid.getItems().isEmpty()) {
            return false;
        }
        setFilterPanelOpen(true);
        if (filterPanel == null || !filterPanelHost.isVisible()) {
            return false;
        }
        filterPanel.poseForShots();
        return true;
    }

    /**
     * 截图 / 验收（R32 G4）：切到表单视图并选中第一条记录。
     * 返回 false = 行还没灌进来，调用方继续轮询（与 {@link #poseFindBar} 同一契约）。
     * 「翻到下一条」不在这摆：截图脚本真点 {@code #data-record-next}，
     * 让那一帧同时证明「表单跟着记录导航走」。
     */
    boolean poseFormView() {
        if (grid.getItems().isEmpty()) {
            return false;
        }
        setFormView(true);
        selectRow(0);
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
            feedback.status(I18n.t("grid.find.transposeOff"));
            return;
        }
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            feedback.status(I18n.t("grid.find.needText"));
            return;
        }
        List<int[]> matches = collectMatches(needle);
        if (matches.isEmpty()) {
            feedback.status(I18n.t("grid.find.notFound", needle));
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
        feedback.status(I18n.t("grid.find.found", needle, from + 1, matches.size()));
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
        feedback.status(I18n.t("grid.find.countOnPage", needle, collectMatches(needle).size()));
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
            feedback.status(I18n.t("grid.find.needText"));
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
            feedback.status(I18n.t("grid.find.replacedOne", io.agenticdb.ui.Shortcuts.of("Mod+Z")));
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
            feedback.status(I18n.t("grid.find.needText"));
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
                        || !definition.type().textual()) {
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
                                ? I18n.t("grid.find.noneSkipped", skipped)
                                : I18n.t("grid.find.none"));
            return;
        }
        updateDirty();
        grid.refresh();
        feedback.status(I18n.t("grid.find.replaced", changed,
                (skipped > 0 ? I18n.t("grid.find.skipped", skipped) : ""),
                io.agenticdb.ui.Shortcuts.of("Mod+Z")));
    }

    /** 替换的前置守卫：只读 / 转置 / 载入保存中都直接说清楚，不让点了没反应。 */
    private boolean replaceAllowed() {
        if (transpose) {
            feedback.status(I18n.t("grid.find.replaceTransposeReadOnly"));
            return false;
        }
        if (tableSchema == null || !tableSchema.editable()) {
            feedback.status(I18n.t("grid.find.readOnlyTable"));
            return false;
        }
        if (loading || saving) {
            feedback.status(I18n.t("grid.find.busy"));
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
            feedback.status(I18n.t("grid.find.pkSkipped", columnName));
            return false;
        }
        if (!definition.type().textual()) {
            feedback.status(I18n.t("grid.find.notTextSkipped", columnName));
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
     * <p>行首槽没有 {@code userData}，天然被排除 —— 框选时用户框的是数据，
     * 不是行首槽。用「可见」而不是「全部」是因为隐藏的列用户根本看不到，
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
            // ⭐ N19：Cell Editor 跟随光标 —— 没有落点就没有可编辑的那一格。
            cellEditorButton.setDisable(row < 0 || column == null);
            // ⭐ N17：当前列的表头整格着蓝 tint（帧 6 实测 #AED4F2）。
            markCurrentHeaderColumn(now == null ? null : now.getTableColumn());
            // TableView 不会自己因为 focusModel 变了重画格子。
            grid.refresh();
            frozenGrid.refresh();
        });
    }

    /** 光标所在列的表头着色；换列时把上一列的样式类摘掉。 */
    private void markCurrentHeaderColumn(TableColumn<?, ?> column) {
        if (currentHeaderColumn == column) {
            return;
        }
        if (currentHeaderColumn != null) {
            currentHeaderColumn.getStyleClass().remove("adb-grid__column--current");
        }
        currentHeaderColumn = column;
        if (currentHeaderColumn != null
                && !currentHeaderColumn.getStyleClass().contains("adb-grid__column--current")) {
            currentHeaderColumn.getStyleClass().add("adb-grid__column--current");
        }
    }

    /**
     * 这一格是不是当前光标格。
     *
     * <p>行首槽没有 {@code userData}，拿不到列名 → 不参与，
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
        feedback.status(I18n.t("grid.msg.rectCopied", rectangle.rowCount(), rectangle.columnCount()));
    }

    /** 矩形变了要重算 cell 样式（{@code TableView} 不会自己重画）。 */
    private void refreshRectangle() {
        grid.refresh();
        // ⭐ G1：冻结区里的格子也要重画，否则框选高亮只出现在右半张表。
        frozenGrid.refresh();
        if (rectangle != null) {
            feedback.status(I18n.t("grid.msg.rectSelected", rectangle.rowCount(), rectangle.columnCount(), io.agenticdb.ui.Shortcuts.of("Mod+C")));
        }
    }

    private void copySelectedAsTsv() {
        copyRowsAsTsv(List.copyOf(grid.getSelectionModel().getSelectedItems()), I18n.t("grid.scope.selected"));
    }

    private void copyRowsAsTsv(List<Map<String, Object>> rows, String scope) {
        if (rows.isEmpty()) {
            feedback.status(I18n.t("grid.copy.noRows"));
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
        feedback.status(I18n.t("grid.copy.tsv", scope, rows.size()));
    }

    private void copySelectedAsSql() {
        copyRowsAsSql(List.copyOf(grid.getSelectionModel().getSelectedItems()), I18n.t("grid.scope.selected"));
    }

    private void copyRowsAsSql(List<Map<String, Object>> rows, String scope) {
        if (rows.isEmpty()) {
            feedback.status(I18n.t("grid.copy.noRows"));
            return;
        }
        List<String> columns = visibleColumnNames();
        if (columns.isEmpty()) {
            feedback.status(I18n.t("grid.copy.noVisibleColumns"));
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
        feedback.status(I18n.t("grid.copy.insert", scope, rows.size()));
    }

    /** 打开导出向导：整表 / 当前页 / 选中行，CSV/TSV/JSON/SQL/XLSX/XML/HTML。 */
    private void openExportWizard() {
        if (tableSchema == null) {
            return;
        }
        List<String> names = tableSchema.columns().stream().map(ColumnDef::name).toList();
        ExportWizard.Scope scope = grid.getSelectionModel().getSelectedItems().isEmpty()
                ? ExportWizard.Scope.WHOLE_TABLE : ExportWizard.Scope.SELECTED;
        Path target = Path.of(System.getProperty("user.home"), tableName + ".csv");
        ExportWizard wizard = new ExportWizard(session, tableName, names, scope, target,
                dbExecutor, message -> feedback.status(message), this::exportScopeRows);
        wizard.show(getScene() == null ? null : getScene().getWindow());
    }

    /**
     * 导出向导的「当前页 / 选中行」数据来源：网格里那一屏的行，按请求的列摊平。
     *
     * <p>只在 FX 线程上被调用（向导在点「开始导出」时取快照），所以这里可以直接读网格。
     */
    private List<List<Object>> exportScopeRows(ExportWizard.Scope scope, List<String> columns) {
        List<Map<String, Object>> source = switch (scope) {
            case CURRENT_PAGE -> List.copyOf(grid.getItems());
            case SELECTED -> List.copyOf(grid.getSelectionModel().getSelectedItems());
            case WHOLE_TABLE -> List.of();
        };
        List<List<Object>> rows = new ArrayList<>(source.size());
        for (Map<String, Object> row : source) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String name : columns) {
                ColumnDef definition = columnDef(name);
                values.add(definition == null ? row.get(name) : cellValue(row, definition));
            }
            rows.add(values);
        }
        return rows;
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
            feedback.status(I18n.t("grid.import.tablesReadFailed"));
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

    // ── 截图 / 验收（R33 G5/G6）：直接弹向导，跳过文件选择框 ──

    /** 截图用：弹导入向导并摆好预览；表结构还没读到时返回 false（外壳继续轮询）。 */
    boolean poseImportWizardForShots(Path file) {
        if (tableSchema == null) {
            return false;
        }
        ImportWizard wizard = createImportWizard(List.of());
        wizard.poseForShots(file);
        wizard.show(getScene() == null ? null : getScene().getWindow());
        return true;
    }

    /** 截图用：弹导出向导并摆好「字段子集 + XML/HTML」（不执行导出）。 */
    boolean poseExportWizardForShots(String pose) {
        if (tableSchema == null) {
            return false;
        }
        List<String> names = tableSchema.columns().stream().map(ColumnDef::name).toList();
        Path target = Path.of(System.getProperty("user.home"), tableName + ".xml");
        ExportWizard wizard = new ExportWizard(session, tableName, names,
                ExportWizard.Scope.WHOLE_TABLE, target, dbExecutor,
                message -> feedback.status(message), this::exportScopeRows);
        wizard.poseForShots(pose);
        wizard.show(getScene() == null ? null : getScene().getWindow());
        return true;
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
            MenuItem none = new MenuItem(I18n.t("grid.history.empty"));
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
     * ⭐ N19：具名键 = 图标 + 文字 + tooltip + accessibleText。
     *
     * <p>文字按钮一律不许被 HBox 压扁（{@code minWidth = USE_PREF_SIZE}）。
     * 放不下时不再把文字收掉 —— 整颗键从右往左折进 » 菜单（N18），
     * 文字与提示原样保留在菜单项里。
     */
    private <T extends ButtonBase> T toolbarOp(T button, Icons.Icon icon, String text, String hint) {
        IconButtons.labeled(button, icon, text, hint);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** 组与组之间的竖分隔线（样式在 {@code .adb-toolbar__separator}）。 */
    private Region toolbarSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("adb-toolbar__separator");
        return separator;
    }

    /** 把「工具条宽度 → 溢出折叠」接上；还没进场景时先全展开，进场景后再判一次。 */
    private void bindToolbarOverflow() {
        toolbar.widthProperty().addListener(toolbarWidthListener);
        sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene != null) {
                javafx.application.Platform.runLater(this::updateToolbarOverflow);
            }
        });
        updateToolbarOverflow();
    }

    /**
     * ⭐ N18：放不下的具名键从右往左折进右端的 » 菜单。
     *
     * <p>与 R32-G19b 的「整排退成图标」互斥：那条会把 Begin Transaction 这类
     * 本来就该带文字的具名键也收成图标，菜单里反而要重新认一遍。Navicat 的做法是
     * 文字保持完整，只把最右端放不下的几颗折叠起来。
     *
     * <p>可用宽度按场景坐标量（{@link #availableToolbarWidth()}），不用自身的
     * {@code widthProperty}：这一排被自身 minWidth 撑宽时，后者会永远「放得下」。
     * 折叠后空出来的分隔线一并收起，避免连着两条竖线。
     */
    private void updateToolbarOverflow() {
        if (toolbar.getScene() == null && toolbar.getWidth() <= 0) {
            return;
        }
        // 先全部展开，再按宽度从右往左折 —— 顺序与视觉顺序相反才有意义。
        for (ButtonBase key : foldedToolbarKeys) {
            showPagerPart(key, true);
        }
        foldedToolbarKeys.clear();
        // 先让 » 退场再量：它还在场时 prefWidth 已经把它算进去了，
        // 再加 reserve 就会多折一颗。
        showPagerPart(toolbarOverflowButton, false);
        double available = availableToolbarWidth();
        if (available > 0) {
            double reserve = toolbarOverflowButton.prefWidth(-1) + toolbar.getSpacing();
            for (int i = namedToolbarKeys.size() - 1; i >= 0; i--) {
                if (toolbar.prefWidth(-1) + reserve <= available + 0.5) {
                    break;
                }
                ButtonBase key = namedToolbarKeys.get(i);
                showPagerPart(key, false);
                foldedToolbarKeys.add(0, key);
            }
        }
        showPagerPart(toolbarOverflowButton, !foldedToolbarKeys.isEmpty());
        hideOrphanedSeparators();
    }

    /** 组里一颗可见键都不剩时，那条竖分隔线也一起收起。 */
    private void hideOrphanedSeparators() {
        List<javafx.scene.Node> children = toolbar.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (!isToolbarSeparator(children.get(i))) {
                continue;
            }
            boolean before = false;
            for (int j = i - 1; j >= 0 && !isToolbarSeparator(children.get(j)); j--) {
                if (hasVisibleContent(children.get(j))) {
                    before = true;
                    break;
                }
            }
            boolean after = false;
            for (int j = i + 1; j < children.size() && !isToolbarSeparator(children.get(j)); j++) {
                if (hasVisibleContent(children.get(j))) {
                    after = true;
                    break;
                }
            }
            showPagerPart(children.get(i), before && after);
        }
    }

    private static boolean isToolbarSeparator(javafx.scene.Node node) {
        return node.getStyleClass().contains("adb-toolbar__separator");
    }

    private static boolean hasVisibleContent(javafx.scene.Node node) {
        if (!node.isVisible() || !node.isManaged()) {
            return false;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                if (child.isVisible() && child.isManaged()) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** 键盘与鼠标共用的溢出入口：菜单项按折叠顺序，点击等于点原按钮。 */
    private void showToolbarOverflow() {
        toolbarOverflowMenu.getItems().clear();
        for (ButtonBase key : foldedToolbarKeys) {
            String text = key instanceof Labeled labeled && !labeled.getText().isBlank()
                    ? labeled.getText() : key.getAccessibleText();
            MenuItem item = new MenuItem(text);
            item.setDisable(key.isDisabled());
            item.setOnAction(event -> key.fire());
            toolbarOverflowMenu.getItems().add(item);
        }
        if (toolbarOverflowMenu.getItems().isEmpty()) {
            return;
        }
        toolbarOverflowMenu.show(toolbarOverflowButton,
                javafx.geometry.Side.BOTTOM, 0, 0);
    }

    /** 门禁接缝：13 个具名键（按视觉顺序，含当前被折进 » 的）。 */
    List<ButtonBase> namedToolbarKeysForTest() {
        return List.copyOf(namedToolbarKeys);
    }

    /** 门禁接缝：当前被折进 » 的键（按视觉顺序）。 */
    List<ButtonBase> foldedToolbarKeysForTest() {
        return List.copyOf(foldedToolbarKeys);
    }

    /** 门禁接缝：溢出菜单本体。 */
    ContextMenu toolbarOverflowMenuForTest() {
        return toolbarOverflowMenu;
    }

    /**
     * 工具条真正**分到**的宽度 —— 按场景坐标量，不用 {@code toolbar.getWidth()}。
     *
     * <p>用自身宽度会成死结：这一排放不下时 HBox 会被自己的 {@code minWidth}
     * 顶到「带文字的需求宽」（实测 1093px），判据再拿 1093 去比 1093 就永远通过，
     * 于是宁可溢出窗外也不肯收字（p2-form 实拍：1280 窗口、表单视图选中一行，
     * 最右的搜索框被裁到只剩一个「本」字）。可用宽度由场景与左边缘算出，
     * 与这一排有没有被撑宽无关。
     */
    private double availableToolbarWidth() {
        if (toolbar.getScene() == null) {
            return toolbar.getWidth();
        }
        double left = toolbar.localToScene(0, 0).getX();
        double right = toolbar.getScene().getWidth();
        if (toolbar.getParent() instanceof Region parent) {
            // 父容器自己被撑宽（G12 分页器）时，这一排也拿不到那部分 —— 两边取小。
            right = Math.min(right,
                    parent.localToScene(parent.getBoundsInLocal()).getMaxX()
                            - parent.getInsets().getRight());
        }
        return right > left ? right - left : toolbar.getWidth();
    }

    private void exportCsv() {
        writeCsv(List.copyOf(grid.getItems()), I18n.t("grid.scope.page"));
    }

    /** ⭐ G8：导出选中行。 */
    private void exportSelectedCsv() {
        List<Map<String, Object>> selected =
                List.copyOf(grid.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            feedback.status(I18n.t("grid.export.noSelection"));
            return;
        }
        writeCsv(selected, I18n.t("grid.scope.selected"));
    }

    private void writeCsv(List<Map<String, Object>> rows, String scope) {
        if (rows.isEmpty()) {
            feedback.status(I18n.t("grid.export.noRows"));
            return;
        }
        List<String> columns = visibleColumnNames();
        if (columns.isEmpty()) {
            feedback.status(I18n.t("grid.copy.noVisibleColumns"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("grid.export.title", scope));
        chooser.setInitialFileName(tableName + ".csv");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(I18n.t("grid.export.csvFilter"), "*.csv"));
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
            feedback.status(I18n.t("grid.export.done", scope, rows.size(), path));
            feedback.toast(I18n.t("grid.export.toast", scope, rows.size()));
        } catch (IOException e) {
            showErrorDetails(I18n.t("grid.export.failedTitle"), e, null);
            feedback.status(I18n.t("grid.export.failed", readable(e)));
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

    /**
     * 把编辑器里的文本翻译成要绑进 SQL 的值。
     *
     * <p>「这一格被清空了」交给 {@link EditValues#emptiedCell} —— 规则不在界面这边，
     * 真库契约走的是同一个方法，界面单方面改掉它时契约跟着红（R53）。
     * ⭐ R55：这个判断本身收进了 {@link CellValueParser#parse}，这里不再自己写一遍
     * 「空串先拦一道」—— 两条分支就是两处出处，筛选面板当初正是绕过了这一条才漏出空串。
     * 包级可见与 {@link #editorText(Object)} 同因：给门禁直接量这一层。
     */
    static Object parseCellValue(ColumnDef definition, String text) {
        return CellValueParser.parse(definition, text);
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
        feedback.status(exclude
                        ? I18n.t("grid.filter.excluded", definition.name(), CellValueParser.display(value))
                        : I18n.t("grid.filter.applied", definition.name(), CellValueParser.display(value)));
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
        feedback.status(I18n.t("grid.msg.details", definition.name()));
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
                && definition.type().textual();
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
                feedback.status(I18n.t("grid.msg.invalidNotNull", definition.name()));
                return;
            }
            setRowValue(row, definition, next);
            // 用新值重开抽屉：编辑态自动退出，看到的也按渲染规则收敛过。
            showDetails(row, definition);
            grid.refresh();
            frozenGrid.refresh();
        } catch (IllegalArgumentException e) {
            feedback.status(I18n.t("grid.msg.invalidValue", e.getMessage()));
        }
    }

    private final class ValueCell extends TableCell<Map<String, Object>, Object> {
        private static final List<String> STYLES = List.of(
                "adb-grid__cell--null", "adb-grid__cell--empty", "adb-grid__cell--number",
                "adb-grid__cell--boolean", "adb-grid__cell--binary", "adb-grid__cell--dirty",
                "adb-grid__cell--json", "adb-grid__cell--enum", "adb-grid__cell--date",
                "adb-grid__cell--long-text", "adb-grid__cell--rect", "adb-grid__cell--cursor",
                "adb-grid__cell--editing");

        private final ColumnDef definition;
        private TextField editor;

        private ValueCell(ColumnDef definition) {
            this.definition = definition;
            setOnContextMenuRequested(event -> {
                if (isEmpty() || getTableRow() == null
                        || getTableRow().getItem() == null) {
                    return;
                }
                Map<String, Object> row = getTableRow().getItem();
                // ⭐ N16：多选后右键选中行里的任一格 = 批量菜单；否则还是这一格的动作。
                //    先把光标挪到被点的格，批量「设 NULL / 设空串」才有目标列。
                if (grid.getSelectionModel().getSelectedItems().size() > 1
                        && grid.getSelectionModel().getSelectedItems().contains(row)) {
                    if (getIndex() >= 0 && getTableColumn() != null) {
                        grid.getFocusModel().focus(getIndex(), getTableColumn());
                    }
                    buildBatchMenu(List.copyOf(grid.getSelectionModel().getSelectedItems()))
                            .show(this, event.getScreenX(), event.getScreenY());
                } else {
                    buildCellMenu(row).show(this, event.getScreenX(), event.getScreenY());
                }
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
            // ⭐ N17：行内编辑盒 = 白底 + 2px 蓝框（帧 6 实测 #0078D7），方角贴满整格。
            if (isEditing()) {
                getStyleClass().add("adb-grid__cell--editing");
            }

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
                    toggle.setAccessibleText(I18n.t("grid.cell.toggleBoolean"));
                    toggle.setSelected(bool);
                    toggle.setDisable(!cellEditable || deleted);
                    toggle.setTooltip(new Tooltip(bool ? I18n.t("grid.cell.trueToggle") : I18n.t("grid.cell.falseToggle")));
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
            editor.getStyleClass().add("adb-grid__editor");            editor.setOnAction(event -> commitEditor());
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
            getStyleClass().add("adb-grid__cell--editing");
            Platform.runLater(() -> {
                // 编辑可能在这次 runLater 之前就撤掉了（双击后立刻 Esc / 焦点被抢），
                // 那时 editor 已经是 null —— 全选没有对象，跳过。
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
                    throw new IllegalArgumentException(I18n.t("grid.batch.notNull", definition.name()));
                }
                if (draftId != null) {
                    if (editBuffer.setDraftCell(draftId, definition.name(), next)) {
                        updateDirty();
                        feedback.status(I18n.t("grid.msg.draftModified", definition.name(), CellValueParser.display(next)));
                    }
                } else {
                    Object original = row.get(definition.name());
                    if (editBuffer.setCell(identity, definition.name(), original, next)) {
                        updateDirty();
                        feedback.status(I18n.t("grid.msg.cellModified", definition.name(), CellValueParser.display(next)));
                    }
                }
                cancelEdit();
                grid.refresh();
            } catch (IllegalArgumentException e) {
                if (!editor.getStyleClass().contains("adb-grid__editor--error")) {
                    editor.getStyleClass().add("adb-grid__editor--error");
                }
                editor.setTooltip(new Tooltip(e.getMessage()));
                feedback.status(I18n.t("grid.msg.invalidValue", e.getMessage()));
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
            MenuItem edit = new MenuItem(I18n.t("grid.cell.edit"));
            edit.setDisable(!cellEditable || deleted);
            edit.setOnAction(event -> startEdit());

            MenuItem copy = new MenuItem(I18n.t("grid.cell.copyValue"));
            copy.setOnAction(event -> copyValue(value));

            MenuItem copySql = new MenuItem(I18n.t("grid.copy.asSql"));
            copySql.setOnAction(event -> copySql(row, value));

            MenuItem copyRow = new MenuItem(I18n.t("grid.cell.copyRow"));
            copyRow.setOnAction(event -> copyRowTsv(row));

            MenuItem setNull = new MenuItem(I18n.t("grid.cell.setNull"));
            setNull.setDisable(!cellEditable || deleted || !definition.nullable());
            setNull.setOnAction(event -> setRowValue(row, definition, null));

            MenuItem setEmpty = new MenuItem(I18n.t("grid.cell.setEmpty"));
            setEmpty.setDisable(!cellEditable || deleted || !definition.type().textual());
            setEmpty.setOnAction(event -> setRowValue(row, definition, ""));

            MenuItem filter = new MenuItem(I18n.t("grid.cell.filterThis"));
            filter.setDisable(draft);
            filter.setOnAction(event -> applyValueFilter(definition, value, false));

            MenuItem exclude = new MenuItem(I18n.t("grid.cell.excludeThis"));
            exclude.setDisable(draft);
            exclude.setOnAction(event -> applyValueFilter(definition, value, true));

            MenuItem details = new MenuItem(I18n.t("grid.cell.details"));
            details.setId("data-cell-details");
            details.setOnAction(event -> showDetails(row, definition));

            MenuItem clone = new MenuItem(I18n.t("grid.cell.cloneRow"));
            clone.setDisable(tableSchema == null || !tableSchema.editable());
            clone.setOnAction(event -> cloneRow(row));

            MenuItem delete = new MenuItem(draft ? I18n.t("grid.cell.removeDraft")
                    : deleted ? I18n.t("grid.cell.markedDelete", io.agenticdb.ui.Shortcuts.of("Mod+Z")) : I18n.t("grid.cell.deleteRow"));
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
            feedback.status(I18n.t("grid.msg.copiedWholeRow"));
        }

        private void copyValue(Object value) {
            ClipboardContent content = new ClipboardContent();
            content.putString(value == null ? "" : String.valueOf(value));
            Clipboard.getSystemClipboard().setContent(content);
            feedback.status(I18n.t("grid.msg.copiedColumn", definition.name()));
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
            feedback.status(I18n.t("grid.msg.copiedSql"));
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
        stateText.setText(I18n.t("grid.state.loading"));
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
        String pageText = pages < 0 ? I18n.t("grid.pager.pageUnknown")
                : pages == 0 ? I18n.t("grid.pager.pageZero")
                : I18n.t("grid.pager.pageOf", QueryLimits.pageNumberOf(offset, pageSize), pages);
        pageField.setTooltip(new Tooltip(pageText));
        pageField.setAccessibleText(I18n.t("grid.pager.jumpPageAria") + " · " + pageText);
        if (!pageField.isFocused()) {
            resetPageFieldText();
        }
        // ⭐ N20：记录计数只在状态栏（feedback.range），底栏不再重复一份。
        previous.setDisable(loading || offset <= 0);
        next.setDisable(loading || (total >= 0 && offset + pageSize >= total));
        firstPageButton.setDisable(loading || offset <= 0);
        lastPageButton.setDisable(loading || total <= 0
                || (total - 1) / pageSize * pageSize == offset);
        pageField.setDisable(loading || total < 0);
        pageOptionsButton.setDisable(loading);
        boolean noSchema = tableSchema == null;
        gridToggle.setDisable(noSchema);
        formToggle.setDisable(noSchema);
        syncViewToggles();
    }

    private String rangeText() {
        if (total < 0) {
            return lastRowCount == 0 ? I18n.t("grid.pager.zeroRows") : I18n.t("grid.pager.loadedNoTotal", lastRowCount);
        }
        if (total == 0) {
            return I18n.t("grid.pager.zeroRows");
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
        return ErrorText.readable(error);
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
        // ⭐ R61-c：保存也在这一条通道里。排在慢加载**后面**的那条保存，
        //   以前既取不掉也停不了 —— 用户按了 ⌘.，写库还是会发生。
        if (saveInFlight != null) {
            saveInFlight.cancel(true);
            saveInFlight = null;
        }
        session.cancelCurrent();
        loading = false;
        saving = false;
        // ⭐ R61-c：取消保存时图标必须复位 —— 转圈是 submitSave 换上去的，
        //   而 onSucceeded/onFailed/onCancelled 里那三次复位在「排队中被摘掉」时**一次都不会跑**
        //   （任务从没被执行，JavaFX 把它当成永远没开始）。
        IconButtons.loading(saveButton, false);
        grid.setDisable(false);
        hideState();
        updateEditControls();
        feedback.status(I18n.t("grid.msg.cancelled", tableName));
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
        // ⭐ R61-c：关页签同样要摘掉在途的保存 —— 与上面 inFlight 对称。
        //   否则页签已经没了，那条 UPDATE 还是会落库（用户以为关掉就中止了）。
        if (saveInFlight != null) {
            saveInFlight.cancel(true);
            saveInFlight = null;
        }
        // ⭐ 关页签必须**摘掉还在防抖里的列宽落盘**：400ms 的 PauseTransition 由 FX 时钟驱动，
        //   页签已经没了它还会照点一次 persistLayout() —— 往已拆卸的场景里读列、
        //   再往可能已被回收的 prefs 节点写值（实测抛 IllegalStateException: Node has been removed，
        //   在测试里表现为「跟在本页之后跑的别的用例突然红一条」）。
        //   这里只 stop，不补写：最多丢一次「拖完 400ms 内就关页签」的宽度，
        //   换来的是关页之后不再有任何写。
        if (layoutSaveTimer != null) {
            layoutSaveTimer.stop();
        }
        detailsDrawer.close();
    }
}
