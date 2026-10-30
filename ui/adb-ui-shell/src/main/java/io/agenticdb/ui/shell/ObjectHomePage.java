package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ⭐ **对象首页** —— 标签栏最左边那个常驻的「对象」标签的内容。
 *
 * <p>Navicat 的标签栏第一个就是常驻的「对象」标签（官方截图实拍）：它是**浏览的起点**，
 * 点它回到对象总览，而不是回到一个空白的欢迎页。
 *
 * <h2>为什么是「清单」而不是「卡片墙」</h2>
 * 早先这里是按类型分组的一排按钮（chip）。4 张表时看着还行，问题在于：
 * <ul>
 *   <li><b>零信息量</b> —— 一张 546 万行的大表和一张空表长得一模一样；</li>
 *   <li><b>不可排序/不可搜索</b> —— 40 张表时要靠眼睛在墙上找；</li>
 *   <li><b>占地方** —— 20 个对象就滚出两屏，而清单 20 行只占半屏。</li>
 * </ul>
 * 现在改成 Navicat 的形态：**一张带元数据的清单**（名称 / 类型 / 行数 /
 * 数据长度 / 引擎 / 创建时间），数据来自 {@link SchemaService#listTableMeta}——
 * 内省层早就有这些，只是以前没呈现（见 {@code docs/UI-VISUAL-SPEC.md} §3.4）。
 *
 * <p>用户明确要求：**这里是清单，不是弹窗、不是标签页**（弹窗/标签的判定见
 * {@code docs/UI-FORMS-AND-STATES.md}）。
 */
final class ObjectHomePage extends VBox {

    /** 分组展示顺序（也是类型过滤器的顺序）。 */
    private static final List<SchemaService.ObjectKind> KINDS = List.of(
            SchemaService.ObjectKind.TABLE,
            SchemaService.ObjectKind.VIEW,
            SchemaService.ObjectKind.PROCEDURE,
            SchemaService.ObjectKind.FUNCTION,
            SchemaService.ObjectKind.TRIGGER,
            // ⭐ O1：事件（只有 MySQL 会有这一组）。
            SchemaService.ObjectKind.EVENT);

    /**
     * 对象级动作的宿主（建表 / 设计 / 删表 / 同步详情面板）。
     *
     * <p>Navicat 的对象清单顶部有一条**对象级工具条**（打开 / 设计 / 新建 /
     * 删除 / 刷新）。AIDB 以前只有双击打开：想设计或删除一张表，必须先开
     * 表清单页或去侧栏右键 —— 默认落地页反而不是完整入口。
     *
     * <p>页面只负责「哪些动作对当前选择合法」，真正执行由外壳决定
     * （它才知道当前连接、危险确认与结构页）。默认空实现 → 老调用方不受影响。
     */
    interface Host {

        /** 新建表（打开建表弹窗）。 */
        default void newTable() {
        }

        /** 设计选中的表 / 视图（打开结构页）。 */
        default void design(SchemaService.SchemaObject object) {
        }

        /** 删除选中的表（带 SQL 预览与确认由宿主负责）。 */
        default void dropTables(List<String> tables) {
        }

        /** 选择变化时同步右侧详情面板；{@code null} = 没有单一选中对象。 */
        default void inspect(SchemaService.SchemaObject object) {
        }
    }

    private final Supplier<DbSession> session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Consumer<SchemaService.SchemaObject> onOpen;
    private final Consumer<String> status;
    private final Runnable onConnect;
    private final Host host;
    private final SettingsStore settings;
    /** 列宽记忆按连接分开存；{@code null} = 不持久化（单测的旧构造器）。 */
    private final Supplier<String> connectionKey;
    private ColumnLayoutMemory columnMemory;

    private final Label summary = new Label();
    /**
     * ⭐ U16：页头不再写死「对象」—— tab 上已经写着「对象」，
     * 页内再来一个 16px 粗体等于同一句话读两遍，还占掉了本该显示「我在哪个库」的位置。
     * 现在这里放的是真实上下文：{@code 连接名 › 方言}。
     */
    private final Label breadcrumb = new Label();
    private final ObservableList<ObjectRow> rows = FXCollections.observableArrayList();
    private final FilteredList<ObjectRow> filtered = new FilteredList<>(rows, row -> true);
    private final TableView<ObjectRow> table = new TableView<>();
    private final FlowPane cards = new FlowPane(10, 10);
    private final ScrollPane gridScroll = new ScrollPane(cards);
    private final StackPane views = new StackPane(table, gridScroll);
    private final Set<String> gridSelection = new LinkedHashSet<>();
    private final BooleanProperty listView = new SimpleBooleanProperty(true);
    private final HBox filters = new HBox(6);
    private final TextField search = new TextField();
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, I18n.t("action.refresh"));
    private final VBox content = new VBox();

    // 对象级工具条：随选择启停（Navicat 的对象清单同样如此）。
    private Button openButton;
    private Button designButton;
    private Button createButton;
    private Button dropButton;
    private Button listButton;
    private Button gridButton;

    /** 切换视图时同步选择，避免把「清空表格选择」误当成用户取消选择。 */
    private boolean syncingSelection;
    /** 网格视图 Shift 连选的锚点（行 id）。 */
    private String cardAnchor;

    // ─────────── 可选列（方言相关）的引用 ───────────
    // ⭐ 第十九轮 S-1：与 TableListPage 同一套规则 —— 这 6 列在数据里全是
    //    占位符时整列隐藏；「名称 / 类型 / 行」是核心列，永远显示。
    private TableColumn<ObjectRow, String> sizeColumn;
    private TableColumn<ObjectRow, String> engineColumn;
    private TableColumn<ObjectRow, String> createdColumn;
    private TableColumn<ObjectRow, String> updatedColumn;
    private TableColumn<ObjectRow, String> collationColumn;
    private TableColumn<ObjectRow, String> commentColumn;

    /** 当前类型过滤；{@code null} = 全部。 */
    private SchemaService.ObjectKind filter;
    /** 当前搜索词（小写）。 */
    private String query = "";
    /** 密度档位 → 行高（Navicat 的对象清单同样是密排行）。 */
    private int rowHeight = io.agenticdb.ui.RowHeights.of("normal");

    /** 密度档位 —— 对象首页也要跟（以前只有数据网格 / 结构页跟）。 */
    public void setDensity(String density) {
        rowHeight = io.agenticdb.ui.RowHeights.of(density);
        table.setFixedCellSize(rowHeight);
    }

    /** 表格（供门禁断言列集合与密度）。 */
    TableView<ObjectRow> table() {
        return table;
    }

    /** 门禁用：立即落盘，不等 400ms 防抖。 */
    void persistColumnWidthsForTest() {
        if (columnMemory != null) {
            columnMemory.persist();
        }
    }

    ObjectHomePage(Supplier<DbSession> session,
                   SchemaService schemaService,
                   Executor dbExecutor,
                   Consumer<SchemaService.SchemaObject> onOpen,
                   Consumer<String> status) {
        this(session, schemaService, dbExecutor, onOpen, status, null);
    }

    /**
     * @param onOpen    ⭐ 传的是<b>整个对象</b>而不只是名字 —— 调用方要按
     *                  {@code kind} 决定开什么页面。只传名字的话，点「函数」
     *                  也会去开数据页，然后必然报错。
     * @param onConnect 「未连接」空态上的引导按钮；{@code null} = 不放按钮。
     */
    ObjectHomePage(Supplier<DbSession> session,
                   SchemaService schemaService,
                   Executor dbExecutor,
                   Consumer<SchemaService.SchemaObject> onOpen,
                   Consumer<String> status,
                   Runnable onConnect) {
        this(session, schemaService, dbExecutor, onOpen, status, onConnect, null);
    }

    ObjectHomePage(Supplier<DbSession> session,
                   SchemaService schemaService,
                   Executor dbExecutor,
                   Consumer<SchemaService.SchemaObject> onOpen,
                   Consumer<String> status,
                   Runnable onConnect,
                   Host host) {
        this(session, schemaService, dbExecutor, onOpen, status, onConnect, host, null, null);
    }

    /**
     * @param settings      列宽记忆的存储；{@code null} = 不持久化
     * @param connectionKey 当前连接 id 的供应器 —— 对象首页是常驻实例，
     *                      切连接时不重建，所以用供应器而不是固定值
     */
    ObjectHomePage(Supplier<DbSession> session,
                   SchemaService schemaService,
                   Executor dbExecutor,
                   Consumer<SchemaService.SchemaObject> onOpen,
                   Consumer<String> status,
                   Runnable onConnect,
                   Host host,
                   SettingsStore settings,
                   Supplier<String> connectionKey) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen == null ? object -> { } : onOpen;
        this.status = status == null ? message -> { } : status;
        this.onConnect = onConnect;
        this.host = host == null ? new Host() { } : host;
        this.settings = settings;
        this.connectionKey = connectionKey;
        getStyleClass().addAll("adb-page", "adb-object-home");
        buildUi();
        showView(initialView(), false);
        applyFilter();
    }

    /**
     * 首次进入时用哪种视图。
     *
     * <p>截图脚本可以用 {@code ADB_OBJECT_VIEW=grid} 强制切换，
     * 与表清单的 {@code ADB_TABLE_VIEW} 保持同一套约定。
     */
    private String initialView() {
        String forced = System.getProperty("adb.object.view",
                System.getenv().getOrDefault("ADB_OBJECT_VIEW", "")).trim();
        if (!forced.isEmpty()) {
            return forced;
        }
        return settings == null ? "list" : settings.get("ui.objectHome.view", "list");
    }

    private void buildUi() {
        breadcrumb.setId("object-home-breadcrumb");
        breadcrumb.getStyleClass().add("adb-page__breadcrumb");
        Label badge = new Label(I18n.t("grid.tab.overview"));
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        summary.setId("object-home-summary");
        summary.getStyleClass().add("adb-muted");
        paintBreadcrumb(session.get());

        refresh.setId("object-home-refresh");
        refresh.getStyleClass().add("adb-button");
        refresh.setOnAction(event -> reload());

        // ⚠️ refresh 必须进场景图 —— 此前它只被 new 出来、绑了动作、设了 id，
        //    却从未加入任何容器，于是「刷新」按钮在界面上根本不存在
        //    （截图审计发现）。
        HBox header = new HBox(10, breadcrumb, badge, spacer, summary);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        buildFilters();
        buildTable();
        buildGrid();
        buildViews();

        content.getChildren().addAll(filters, views);
        VBox.setVgrow(views, Priority.ALWAYS);

        VBox body = new VBox(8, content);
        body.getStyleClass().add("adb-page__body");
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, buildToolbar(), body);
        updateToolbarState();
    }

    /**
     * 对象级工具条（Navicat 形态）：打开 / 设计 / 新建 / 删除 / 刷新。
     *
     * <p>⭐ UX-3：主操作改成「图标 + 文字」—— 这四颗按钮里有一颗会删表，
     * 只有图标就意味着用户必须先认图标、再确认自己没认错。
     *
     * <p>启用态由 {@link #updateToolbarState()} 按「当前选择是什么」决定 ——
     * 选函数时「删除」不该亮（宿主只会删表）。
     */
    private HBox buildToolbar() {
        openButton = toolbarOp(Icons.Icon.OPEN, I18n.t("sidebar.menuOpen"),
                I18n.t("object.hint.open"));
        openButton.setId("object-home-open");
        openButton.setOnAction(event -> openSelected());

        designButton = toolbarOp(Icons.Icon.STRUCTURE, I18n.t("object.design"),
                I18n.t("object.hint.design"));
        designButton.setId("object-home-design");
        designButton.setOnAction(event -> {
            SchemaService.SchemaObject selected = singleSelection();
            if (selected == null) {
                status.accept(I18n.t("object.pickTableOrView"));
                return;
            }
            host.design(selected);
        });

        createButton = toolbarOp(Icons.Icon.ADD, I18n.t("ribbon.newTable"),
                I18n.t("object.hint.newTable"));
        createButton.setId("object-home-new");
        createButton.setOnAction(event -> host.newTable());

        dropButton = toolbarOp(Icons.Icon.DELETE, I18n.t("object.drop"),
                I18n.t("object.hint.drop"), "adb-button--danger");
        dropButton.setId("object-home-drop");
        dropButton.setOnAction(event -> {
            List<String> tables = selectedRows().stream()
                    .map(row -> row.object())
                    .filter(object -> object.kind() == SchemaService.ObjectKind.TABLE)
                    .map(SchemaService.SchemaObject::name)
                    .toList();
            if (tables.isEmpty()) {
                status.accept(I18n.t("tableList.pickOneFirst"));
                return;
            }
            host.dropTables(tables);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ⭐ UX-6：列表 / 网格两种视图。网格复用表清单的卡片样式，
        //    但对象种类更多（表 / 视图 / 函数…），卡片按 kind 取图标。
        listButton = IconButtons.icon(Icons.Icon.LIST, I18n.t("action.table-list.view-list"));
        listButton.setId("object-home-view-list");
        listButton.getStyleClass().add("adb-icon-button");
        listButton.setOnAction(event -> showView("list"));

        gridButton = IconButtons.icon(Icons.Icon.GRID, I18n.t("action.table-list.view-grid"));
        gridButton.setId("object-home-view-grid");
        gridButton.getStyleClass().add("adb-icon-button");
        gridButton.setOnAction(event -> showView("grid"));

        HBox groupLayout = new HBox(4, listButton, gridButton);
        groupLayout.getStyleClass().add("adb-toolbar__group");
        groupLayout.setAlignment(Pos.CENTER_LEFT);

        // ⭐ UX-3：看对象的一组、改对象的一组，中间一条竖线。
        HBox groupView = new HBox(4, openButton, designButton);
        HBox groupEdit = new HBox(4, createButton, dropButton);
        groupView.getStyleClass().add("adb-toolbar__group");
        groupEdit.getStyleClass().add("adb-toolbar__group");
        groupView.setAlignment(Pos.CENTER_LEFT);
        groupEdit.setAlignment(Pos.CENTER_LEFT);
        Region separator = new Region();
        separator.getStyleClass().add("adb-toolbar__separator");

        HBox toolbar = new HBox(8, groupView, separator, groupEdit, spacer,
                refresh, groupLayout);
        toolbar.setId("object-home-toolbar");
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    /**
     * 对象首页工具条的一颗主操作：图标 + 文字 + tooltip + accessibleText。
     *
     * <p>变体类（{@code adb-button--danger} 这种）从这里的参数挂，
     * 不在调用点再补一次 {@code getStyleClass()}：基础类和变体类同一条语句成型，
     * 才不会出现「只有变体、拿不到统一尺寸与五态」的按钮（任务书 §七）。
     */
    private Button toolbarOp(Icons.Icon icon, String text, String hint, String... variants) {
        Button button = IconButtons.labeled(new Button(), icon, text, hint, Icons.SIZE_PRIMARY);
        button.getStyleClass().add("adb-button");
        button.getStyleClass().addAll(variants);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** 当前唯一的选中对象；多选 / 空选为 {@code null}。 */
    private SchemaService.SchemaObject singleSelection() {
        List<ObjectRow> selected = selectedRows();
        return selected.size() == 1 ? selected.getFirst().object() : null;
    }

    /**
     * ⭐ U25：对象右键菜单（表格行与网格卡片共用）。
     *
     * <p>条目与工具条同源、判断条件也同源（打开要连着库、设计只认表/视图、
     * 删除只走表）—— 右键不是第二套权限模型，是同一套的另一个入口。
     *
     * <p>包可见：单测直接拿菜单断言权限，不必模拟右键弹窗。
     */
    ContextMenu objectMenu(ObjectRow row) {
        SchemaService.SchemaObject object = row.object();
        boolean connected = session.get() != null;
        boolean designable = object.kind() == SchemaService.ObjectKind.TABLE
                || object.kind() == SchemaService.ObjectKind.VIEW;

        ContextMenu menu = new ContextMenu();
        MenuItem open = new MenuItem(I18n.t("sidebar.menuOpen"));
        open.setId("object-menu-open");
        open.setDisable(!connected);
        open.setOnAction(event -> onOpen.accept(object));

        MenuItem design = new MenuItem(I18n.t("object.design"));
        design.setId("object-menu-design");
        design.setDisable(!connected || !designable);
        design.setOnAction(event -> host.design(object));

        MenuItem copyName = new MenuItem(I18n.t("action.object.copy-name"));
        copyName.setId("object-menu-copy-name");
        copyName.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(object.name());
            Clipboard.getSystemClipboard().setContent(content);
            status.accept(I18n.t("object.copied", object.name()));
        });

        MenuItem drop = new MenuItem(I18n.t("object.drop"));
        drop.setId("object-menu-drop");
        drop.setDisable(!connected || object.kind() != SchemaService.ObjectKind.TABLE);
        drop.setOnAction(event -> host.dropTables(List.of(object.name())));

        menu.getItems().addAll(open, design, copyName,
                new SeparatorMenuItem(), drop);
        return menu;
    }

    /**
     * 当前选中的对象行 —— 列表视图看表格选择，网格视图看卡片选择。
     *
     * <p>工具条、删除动作、状态栏全部走这一份真相，两种视图的行为才不会分叉。
     */
    private List<ObjectRow> selectedRows() {
        if (isGridView()) {
            List<ObjectRow> selected = new ArrayList<>();
            for (ObjectRow row : filtered) {
                if (gridSelection.contains(row.id())) {
                    selected.add(row);
                }
            }
            return selected;
        }
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    /**
     * 按当前选择刷新工具条启用态。
     *
     * <p>规则（与 Navicat 的对象清单一致、也比「全亮」诚实）：
     * <ul>
     *   <li>打开：单选任意对象；</li>
     *   <li>设计：单选表或视图（结构页对视图是只读的）；</li>
     *   <li>删除：多选，但**每一行都必须是表** —— 删视图走别的路径；</li>
     *   <li>未连接时全部禁用。</li>
     * </ul>
     */
    private void updateToolbarState() {
        if (syncingSelection) {
            return;
        }
        boolean connected = session.get() != null;
        List<ObjectRow> selected = selectedRows();
        boolean single = selected.size() == 1;
        SchemaService.ObjectKind kind = single ? selected.getFirst().object().kind() : null;
        boolean designable = kind == SchemaService.ObjectKind.TABLE
                || kind == SchemaService.ObjectKind.VIEW;
        boolean droppable = !selected.isEmpty() && selected.stream()
                .allMatch(row -> row.object().kind() == SchemaService.ObjectKind.TABLE);

        openButton.setDisable(!connected || !single);
        designButton.setDisable(!connected || !designable);
        dropButton.setDisable(!connected || !droppable);
        createButton.setDisable(!connected);

        // ⭐ U19：禁用不能只变灰 —— 一排灰按钮读起来像界面坏了。
        // 悬停要说清「为什么现在点不动、要做什么才能点」，灰才有解释而不是故障。
        explainDisabled(openButton, !connected || !single,
                !connected ? I18n.t("object.needConnect.open") : I18n.t("object.pickFirst"));
        explainDisabled(designButton, !connected || !designable,
                !connected ? I18n.t("object.needConnect.design") : I18n.t("object.designTablesOnly"));
        explainDisabled(dropButton, !connected || !droppable,
                !connected ? I18n.t("object.needConnect.drop") : I18n.t("object.dropTablesOnly"));
        explainDisabled(createButton, !connected, I18n.t("object.needConnect.create"));

        updateViewButtons();
        host.inspect(single ? selected.getFirst().object() : null);
    }

    /**
     * U19：禁用态的 tooltip 换成「原因」，恢复可用时换回「这个按钮是干什么的」。
     *
     * <p>原始说明取自 {@code accessibleText} —— {@code IconButtons} 建按钮时就写好了，
     * 再抄一份会变成两处真相（改了按钮说明，tooltip 还留着老的）。
     */
    private static void explainDisabled(Button button, boolean disabled, String reason) {
        Tooltip tooltip = button.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip();
            button.setTooltip(tooltip);
        }
        String hint = button.getAccessibleText();
        tooltip.setText(disabled || hint == null || hint.isBlank()
                ? (disabled ? reason : "")
                : hint);
    }

    // ─────────────────────────── 过滤条 ───────────────────────────

    private void buildFilters() {
        filters.getStyleClass().add("adb-object-list__filters");
        filters.setAlignment(Pos.CENTER_LEFT);

        search.setId("object-home-search");
        search.setPromptText(I18n.t("object.searchPrompt"));
        search.setPrefWidth(180);
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            query = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            applyFilter();
            // 过滤可能让网格里选中的卡片消失 —— 工具条要跟着收缩。
            updateToolbarState();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        filters.getChildren().addAll(chip(I18n.t("object.filter.all"), null), spacer, search);
    }

    /**
     * 一个类型筛选 chip。
     *
     * @param kind {@code null} = 全部
     */
    private Button chip(String label, SchemaService.ObjectKind kind) {
        Button chip = new Button(label);
        chip.getStyleClass().add("adb-filter-chip");
        chip.setId("object-filter-" + (kind == null ? "all" : kind.name().toLowerCase(Locale.ROOT)));
        chip.setOnAction(event -> {
            filter = kind;
            applyFilter();
        });
        return chip;
    }

    // ─────────────────────────── 清单 ───────────────────────────

    private void buildTable() {
        table.setId("object-list");
        table.getStyleClass().add("adb-object-list");
        // ⭐ P7：表格没有文字，读屏只会读「表」—— 给一个明确的名称。
        table.setAccessibleText(I18n.t("object.listName"));
        table.setEditable(false);
        // ⭐ S-6：可读下限 + 约束策略 —— 宽屏填满，挤窄卡在 minWidth 并横向滚动
        //    （为什么不是 UNCONSTRAINED：宽屏会留一大片空白，见 §33.5）。
        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // U24：空态给图标 + 出路，不剩一行猜不出原因的灰字。
        table.setPlaceholder(EmptyState.of(Icons.Icon.SEARCH, I18n.t("object.noMatches"),
                I18n.t("object.noMatchesDetail"),
                I18n.t("tableList.clearSearch"), search::clear));
        table.setFixedCellSize(rowHeight);

        // ⭐ 列与 Navicat 的「表清单」对齐：名称 / 类型 / 行 / 数据长度 / 引擎 /
        //    创建时间 / 修改时间 / 排序规则 / 注释。方言拿不到的字段保持「—」。
        // ⭐ 第十九轮 S-1：可选列（数据长度/引擎/创建/修改/排序规则/注释）留引用，
        //    按已加载数据决定显不显示 —— 全是占位符就整列隐藏（见 applyColumnVisibility）。
        sizeColumn = sizeColumn();
        engineColumn = keyed(textColumn(I18n.t("column.engine"), 90, 80, row -> row.meta() == null
                ? "—" : row.meta().engineText()), "engine");
        createdColumn = keyed(textColumn(I18n.t("object.column.created"), 130, 130, row -> row.meta() == null
                ? "—" : row.meta().createdText()), "created");
        updatedColumn = keyed(textColumn(I18n.t("object.column.updated"), 130, 130, row -> row.meta() == null
                ? "—" : row.meta().updatedText()), "updated");
        collationColumn = keyed(textColumn(I18n.t("column.collation"), 110, 110, row -> row.meta() == null
                ? "—" : row.meta().collationText()), "collation");
        commentColumn = keyed(textColumn(I18n.t("column.comment"), 160, 120, row -> row.meta() == null
                ? "—" : row.meta().commentText(), true), "comment");

        table.getColumns().setAll(List.of(
                nameColumn(),
                keyed(textColumn(I18n.t("detail.type"), 64, 72,
                        row -> typeLabel(row.object().kind())), "kind"),
                rowsColumn(),
                sizeColumn,
                engineColumn,
                createdColumn,
                updatedColumn,
                collationColumn,
                commentColumn));

        // ⭐ P2-1：列宽按连接记忆；render() 里再按当前连接恢复一次。
        columnMemory = new ColumnLayoutMemory(table, settings,
                connectionKey, "ui.objecthome");

        // ⭐ 行 id：单测与截图脚本要能按「类型 + 名称」点中某一行，
        //    和以前的 object-chip-* 一一对应（D1 回归门禁要靠它）。
        table.setRowFactory(view -> {
            TableRow<ObjectRow> row = new TableRow<>() {
                @Override
                protected void updateItem(ObjectRow item, boolean empty) {
                    super.updateItem(item, empty);
                    setId(empty || item == null ? "" : item.id());
                }
            };
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onOpen.accept(row.getItem().object());
                }
            });
            // ⭐ U25：行右键 = 对象菜单（打开 / 设计 / 复制名称 / 删除）。
            row.setOnContextMenuRequested(event -> {
                if (row.isEmpty() || row.getItem() == null) {
                    return;
                }
                if (!row.isSelected()) {
                    table.getSelectionModel().clearSelection();
                    table.getSelectionModel().select(row.getIndex());
                }
                objectMenu(row.getItem()).show(row, event.getScreenX(), event.getScreenY());
                event.consume();
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelected();
            }
        });

        SortedList<ObjectRow> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);

        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(
                (ListChangeListener<ObjectRow>) change -> updateToolbarState());
    }

    // ─────────────────────────── 网格视图（UX-6） ───────────────────────────

    /** 列表 / 网格共用同一块内容区；用可见性切换，选择与滚动位置都保留。 */
    private void buildViews() {
        table.visibleProperty().bind(listView);
        table.managedProperty().bind(listView);
        gridScroll.visibleProperty().bind(listView.not());
        gridScroll.managedProperty().bind(listView.not());
        views.getStyleClass().add("adb-table-list__views");
    }

    private void buildGrid() {
        gridScroll.setId("object-home-grid");
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.getStyleClass().add("adb-table-list__scroll");
        cards.setPadding(new Insets(8));
        cards.setAlignment(Pos.TOP_LEFT);
        cards.getStyleClass().add("adb-table-list__cards");
        cards.prefWrapLengthProperty().bind(gridScroll.widthProperty().subtract(24));
    }

    /** 供门禁断言：当前是不是网格视图。 */
    boolean isGridView() {
        return !listView.get();
    }

    /** 切换视图，并记住选择（切回表格时仍选中同样的对象）。 */
    void showView(String view) {
        showView(view, true);
    }

    private void showView(String view, boolean remember) {
        boolean grid = "grid".equalsIgnoreCase(view);
        if (grid == isGridView()) {
            updateViewButtons();
            updateToolbarState();
            return;
        }
        syncingSelection = true;
        try {
            if (grid) {
                gridSelection.clear();
                for (ObjectRow row : table.getSelectionModel().getSelectedItems()) {
                    gridSelection.add(row.id());
                }
                cardAnchor = gridSelection.stream().findFirst().orElse(null);
            } else {
                Set<String> keep = new LinkedHashSet<>(gridSelection);
                table.getSelectionModel().clearSelection();
                for (int i = 0; i < table.getItems().size(); i++) {
                    if (keep.contains(table.getItems().get(i).id())) {
                        table.getSelectionModel().select(i);
                    }
                }
            }
            listView.set(!grid);
        } finally {
            syncingSelection = false;
        }
        if (grid) {
            rebuildCards();
        }
        updateViewButtons();
        updateToolbarState();
        if (remember && settings != null) {
            settings.set("ui.objectHome.view", grid ? "grid" : "list");
        }
    }

    private void updateViewButtons() {
        if (listButton == null || gridButton == null) {
            return;
        }
        listButton.getStyleClass().remove("adb-icon-button--active");
        gridButton.getStyleClass().remove("adb-icon-button--active");
        (isGridView() ? gridButton : listButton)
                .getStyleClass().add("adb-icon-button--active");
    }

    /** 把过滤后的对象重建成卡片；空结果给一句明确的空态。 */
    private void rebuildCards() {
        Set<String> visible = new LinkedHashSet<>();
        for (ObjectRow row : filtered) {
            visible.add(row.id());
        }
        gridSelection.retainAll(visible);
        cards.getChildren().clear();
        if (filtered.isEmpty()) {
            Label empty = new Label(rows.isEmpty() ? I18n.t("object.grid.empty") : I18n.t("object.noMatches"));
            empty.getStyleClass().add("adb-muted");
            empty.setPadding(new Insets(24));
            cards.getChildren().add(empty);
            return;
        }
        for (ObjectRow row : filtered) {
            cards.getChildren().add(buildCard(row));
        }
    }

    private VBox buildCard(ObjectRow row) {
        Node icon = typeIcon(row.object().kind());
        Label name = new Label(row.object().name());
        name.getStyleClass().add("adb-table-card__name");
        HBox title = new HBox(6, icon, name);
        title.setAlignment(Pos.CENTER_LEFT);

        // 与表清单卡片同一规则：不摆「—」，整行都是占位符就不渲染这一行。
        String rowsFact = row.meta() == null || MetadataColumns.isPlaceholder(row.meta().rowsText())
                ? null : I18n.t("object.rowsUnit", row.meta().rowsText().trim());
        Label facts = cardLine("adb-table-card__facts", rowsFact,
                row.meta() == null ? null : row.meta().sizeText());
        Label detail = cardLine("adb-table-card__detail", typeLabel(row.object().kind()),
                row.meta() == null ? null : row.meta().engineText(),
                row.meta() == null ? null : row.meta().updatedText());
        Label comment = cardLine("adb-table-card__comment",
                row.meta() == null ? null : row.meta().commentText());

        List<Node> lines = new ArrayList<>();
        lines.add(title);
        if (facts != null) {
            lines.add(facts);
        }
        if (detail != null) {
            lines.add(detail);
        }
        if (comment != null) {
            lines.add(comment);
        }

        VBox card = new VBox(4, lines.toArray(new Node[0]));
        card.setId(row.cardId());
        card.setUserData(row.id());
        card.getStyleClass().add("adb-table-card");
        card.setPrefWidth(220);
        card.setMaxWidth(220);
        card.setMinHeight(92);
        card.setFocusTraversable(true);
        // ⭐ P7：卡片是 VBox，读屏默认只会读成「面板」—— 补角色与名称。
        card.setAccessibleRole(javafx.scene.AccessibleRole.LIST_ITEM);
        card.setAccessibleText(row.object().name());
        if (gridSelection.contains(row.id())) {
            card.getStyleClass().add("adb-table-card--selected");
        }
        card.setOnMousePressed(event -> card.requestFocus());
        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                onOpen.accept(row.object());
                return;
            }
            selectCard(row, event);
        });
        // ⭐ U25：卡片右键与表格行同一份菜单 —— 两种视图两套右键就是两套真相。
        card.setOnContextMenuRequested(event -> {
            if (!gridSelection.contains(row.id())) {
                gridSelection.clear();
                gridSelection.add(row.id());
                cardAnchor = row.id();
                refreshCardSelectionStyles();
                updateToolbarState();
            }
            objectMenu(row).show(card, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        card.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                onOpen.accept(row.object());
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                if (!gridSelection.remove(row.id())) {
                    gridSelection.add(row.id());
                }
                cardAnchor = row.id();
                refreshCardSelectionStyles();
                updateToolbarState();
                event.consume();
            }
        });
        return card;
    }

    /** 卡片里的一行：只留真有值的片段；全占位符返回 {@code null}。 */
    private static Label cardLine(String styleClass, String... parts) {
        List<String> real = new ArrayList<>();
        for (String part : parts) {
            if (!MetadataColumns.isPlaceholder(part)) {
                real.add(part.trim());
            }
        }
        if (real.isEmpty()) {
            return null;
        }
        String joined = String.join(" · ", real);
        Label label = new Label(joined);
        label.getStyleClass().add(styleClass);
        if (styleClass.equals("adb-table-card__comment")) {
            label.setMaxWidth(Double.MAX_VALUE);
            label.setTooltip(new Tooltip(joined));
        }
        return label;
    }

    /** 卡片点击：普通点击单选，⌘/Ctrl 切换，Shift 选区间。 */
    private void selectCard(ObjectRow row, MouseEvent event) {
        if (event.isShortcutDown()) {
            if (!gridSelection.remove(row.id())) {
                gridSelection.add(row.id());
            }
        } else if (event.isShiftDown() && cardAnchor != null) {
            int from = indexOfCard(cardAnchor);
            int to = indexOfCard(row.id());
            if (from >= 0 && to >= 0) {
                gridSelection.clear();
                int start = Math.min(from, to);
                int end = Math.max(from, to);
                for (int i = start; i <= end; i++) {
                    gridSelection.add(filtered.get(i).id());
                }
            } else {
                gridSelection.clear();
                gridSelection.add(row.id());
            }
        } else {
            gridSelection.clear();
            gridSelection.add(row.id());
        }
        cardAnchor = row.id();
        refreshCardSelectionStyles();
        updateToolbarState();
    }

    private int indexOfCard(String id) {
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void refreshCardSelectionStyles() {
        for (Node node : cards.getChildren()) {
            node.getStyleClass().remove("adb-table-card--selected");
            if (node.getUserData() instanceof String id && gridSelection.contains(id)) {
                node.getStyleClass().add("adb-table-card--selected");
            }
        }
    }

    /** 列宽记忆的身份：标题会改，这个 key 不会。 */
    private static TableColumn<ObjectRow, String> keyed(
            TableColumn<ObjectRow, String> column, String key) {
        column.setUserData(key);
        return column;
    }

    private TableColumn<ObjectRow, String> nameColumn() {
        TableColumn<ObjectRow, String> column = new TableColumn<>(I18n.t("column.name"));
        column.setId("object-column-name");
        column.setUserData("name");
        column.setPrefWidth(200);
        column.setMinWidth(140);
        column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().object().name()));
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                ObjectRow row = getTableRow() == null ? null : getTableRow().getItem();
                Node icon = typeIcon(row == null
                        ? SchemaService.ObjectKind.TABLE : row.object().kind());
                Label label = new Label(name);
                HBox box = new HBox(6, icon, label);
                box.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(box);
            }
        });
        return column;
    }

    /** 「行数」「大小」右对齐，且**按数值排序**而不是按字符串。 */
    private TableColumn<ObjectRow, String> rowsColumn() {
        TableColumn<ObjectRow, String> column = new TableColumn<>(I18n.t("detail.rows"));
        column.setId("object-column-rows");
        column.setUserData("rows");
        column.setPrefWidth(80);
        column.setMinWidth(72);
        column.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().meta() == null || cell.getValue().meta().rows() == null
                        ? "—"
                        : NumberFormat.getIntegerInstance(Locale.CHINA)
                        .format(cell.getValue().meta().rows())));
        column.setComparator((left, right) -> Long.compare(numberOf(left), numberOf(right)));
        column.setCellFactory(unused -> numberCell());
        return column;
    }

    private TableColumn<ObjectRow, String> sizeColumn() {
        TableColumn<ObjectRow, String> column = new TableColumn<>(I18n.t("column.dataLength"));
        column.setId("object-column-size");
        column.setUserData("size");
        column.setPrefWidth(90);
        column.setMinWidth(88);
        column.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().meta() == null ? "—" : cell.getValue().meta().sizeText()));
        column.setComparator((left, right) -> Long.compare(bytesOf(left), bytesOf(right)));
        column.setCellFactory(unused -> numberCell());
        return column;
    }

    /**
     * 数字列的单元格。
     *
     * <p>⛔ 以前这里写的是 {@code column.setStyle("-fx-alignment: center-right;")} ——
     * **内联样式**既逃过了 CSS 审计，也和 `.adb-object-list__number` 这个已经写好的
     * 样式类重复（那个类当时是死样式）。现在统一由样式类接管：
     * 右对齐 + 次要色，数字列才能竖着比较。
     */
    private static TableCell<ObjectRow, String> numberCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().add("adb-object-list__number");
                setText(empty || value == null ? null : value);
            }
        };
    }

    private static TableColumn<ObjectRow, String> textColumn(
            String title, double width, double minWidth,
            java.util.function.Function<ObjectRow, String> value) {
        return textColumn(title, width, minWidth, value, false);
    }

    /**
     * @param tooltip 悬停给出**完整文本**（注释列用）；占位符「—」不挂 tooltip。
     */
    private static TableColumn<ObjectRow, String> textColumn(
            String title, double width, double minWidth,
            java.util.function.Function<ObjectRow, String> value, boolean tooltip) {
        TableColumn<ObjectRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setMinWidth(ColumnWidths.listColumnMin(title, minWidth));
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        if (tooltip) {
            column.setCellFactory(unused -> new TableCell<>() {
                @Override
                protected void updateItem(String text, boolean empty) {
                    super.updateItem(text, empty);
                    boolean real = !empty && !MetadataColumns.isPlaceholder(text);
                    setText(empty || text == null ? null : text);
                    if (!real) {
                        setTooltip(null);
                    } else if (getTooltip() == null
                            || !text.equals(getTooltip().getText())) {
                        setTooltip(new Tooltip(text));
                    }
                }
            });
        }
        return column;
    }

    private static long numberOf(String text) {
        if (text == null || text.isBlank() || "—".equals(text)) {
            return -1;
        }
        try {
            return Long.parseLong(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 把「3.4 MB」这类展示串折回字节数用于排序。 */
    private static long bytesOf(String text) {
        if (text == null || text.isBlank() || "—".equals(text)) {
            return -1;
        }
        String upper = text.toUpperCase(Locale.ROOT).trim();
        double factor = 1;
        if (upper.endsWith("KB")) {
            factor = 1024;
            upper = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("MB")) {
            factor = 1024 * 1024;
            upper = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("GB")) {
            factor = 1024L * 1024 * 1024;
            upper = upper.substring(0, upper.length() - 2);
        } else if (upper.endsWith("B")) {
            upper = upper.substring(0, upper.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(upper.trim()) * factor);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void openSelected() {
        List<ObjectRow> selected = selectedRows();
        if (selected.isEmpty()) {
            status.accept(I18n.t("object.pickObject"));
            return;
        }
        if (selected.size() > 1) {
            status.accept(I18n.t("object.singleOpen"));
            return;
        }
        onOpen.accept(selected.getFirst().object());
    }

    /**
     * 这个对象<b>能不能当成"表"来浏览数据行</b>。
     *
     * <p>返回 {@code null} = 可以（表 / 视图）；否则返回一句**给用户看的原因**，
     * 调用方应当把它显示出来而<b>不要</b>去开数据页。
     *
     * <p>⚠️ 这段判断必须只存在一份：以前对象页只把对象名传给外壳，外壳无条件开
     * 数据页，于是点「函数」会去 SELECT 一张不存在的表，甩给用户一个看不懂的错
     * （截图审计 D1）。点与不点的知识属于对象，所以放在这里。
     */
    static String unsupportedReason(SchemaService.ObjectKind kind, String name) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case TABLE, VIEW -> null;
            // ⭐ O2：函数 / 过程现在**能看定义体**（只读标签页），不再是「不支持」。
            case FUNCTION, PROCEDURE, EVENT -> null;
            // 触发器在**结构页**的「触发器」页签里看（那里能看到完整语句）。
            case TRIGGER -> I18n.t("object.triggerReason", name);
        };
    }

    // ─────────────────────────── 数据 ───────────────────────────

    /**
     * ⭐ U16：页头显示真实上下文（{@code 方言 · 连接标识}），没连接就说没连接。
     *
     * <p>这一行只读一个已有对象（{@code label()} / {@code dialect()}），不查库，
     * 所以可以在 FX 线程上直接画；「在哪个库」是用户决定要不要点进去之前就要看到的信息。
     *
     * <p>⚠️ 真实会话的 {@code label()} 本身就带方言前缀（「SQLite · 文件路径」）——
     * 再拼一次「› SQLite」就是同一句话读两遍（p0-shell 实拍抓到）。
     * 只有 label 没提方言的会话才补方言后缀。
     *
     * <p>⚠️ 文件型会话的 label 带**整条绝对路径**（p0-shell 第二拍抓到）——
     * 页头不是资源管理器，长路径把摘要挤下行、读起来像调试输出；
     * 这里只留文件名，全路径进 tooltip。
     */
    private void paintBreadcrumb(DbSession current) {
        if (current == null) {
            breadcrumb.setText(I18n.t("sidebar.disconnected"));
            breadcrumb.setTooltip(null);
            return;
        }
        String label = current.label();
        String dialect = current.dialect().displayName();
        int separator = Math.max(label.lastIndexOf('\\'), label.lastIndexOf('/'));
        if (separator > 0) {
            int start = label.lastIndexOf(" · ");
            String prefix = start < 0 ? "" : label.substring(0, start + 3);
            breadcrumb.setText(prefix + label.substring(separator + 1));
            breadcrumb.setTooltip(new javafx.scene.control.Tooltip(label));
            return;
        }
        breadcrumb.setText(label.startsWith(dialect) ? label : label + " › " + dialect);
        breadcrumb.setTooltip(null);
    }

    /** 重新读一遍对象列表。 */
    void reload() {
        DbSession current = session.get();
        paintBreadcrumb(current);
        if (current == null) {
            showEmpty(EmptyState.of(Icons.Icon.CONNECT, I18n.t("object.empty.notConnected"),
                    I18n.t("object.empty.notConnectedDetail"),
                    onConnect == null ? null : I18n.t("object.newConnection"),
                    onConnect));
            summary.setText(I18n.t("sidebar.disconnected"));
            updateToolbarState();
            return;
        }
        dbExecutor.execute(() -> {
            List<SchemaService.SchemaObject> objects;
            try {
                objects = schemaService.listObjects(current);
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                javafx.application.Platform.runLater(() -> {
                    summary.setText(I18n.t("object.readFailed"));
                    showEmpty(EmptyState.of(Icons.Icon.CLEAR, I18n.t("object.readFailedTitle"),
                            message, I18n.t("shell.retry"), this::reload));
                });
                return;
            }
            // ⭐ 元数据是**锦上添花**：取不到（老版本库 / 权限不足 / SQLite
            //    需要逐表 COUNT）时清单照常显示，只是元数据列显示「—」。
            List<TableMeta> metas = List.of();
            try {
                metas = schemaService.listTableMeta(current);
            } catch (Exception ignored) {
                metas = List.of();
            }
            List<TableMeta> finalMetas = metas;
            javafx.application.Platform.runLater(() -> render(objects, finalMetas));
        });
    }

    /** 渲染对象列表（无元数据版）。package-private：单测要直接喂数据。 */
    void render(List<SchemaService.SchemaObject> objects) {
        render(objects, List.of());
    }

    /** 渲染对象列表 + 表元数据。 */
    void render(List<SchemaService.SchemaObject> objects, List<TableMeta> metas) {
        Map<String, TableMeta> byName = new LinkedHashMap<>();
        for (TableMeta meta : metas == null ? List.<TableMeta>of() : metas) {
            byName.put(meta.name().toLowerCase(Locale.ROOT), meta);
        }

        List<ObjectRow> all = new ArrayList<>();
        for (SchemaService.SchemaObject object : objects) {
            all.add(new ObjectRow(object, byName.get(object.name().toLowerCase(Locale.ROOT))));
        }
        all.sort((left, right) -> {
            int byKind = left.object().kind().compareTo(right.object().kind());
            return byKind != 0 ? byKind
                    : left.object().name().compareToIgnoreCase(right.object().name());
        });

        rows.setAll(all);
        // 对象首页切连接时不重建 —— 列宽要跟着当前连接换一套。
        if (columnMemory != null) {
            columnMemory.restoreIfScopeChanged();
        }
        applyColumnVisibility();
        rebuildFilterChips();
        applyFilter();

        if (all.isEmpty()) {
            showEmpty(EmptyState.of(Icons.Icon.DATABASE,
                    I18n.t("object.empty.noObjects"),
                    I18n.t("object.empty.noObjectsDetail"),
                    I18n.t("object.openQueryPage"),
                    () -> status.accept(I18n.t("object.empty.createQueryHint"))));
            summary.setText(I18n.t("object.summary.count", 0));
            updateToolbarState();
            return;
        }
        showContent();
        summary.setText(summaryText(all));
        updateToolbarState();
    }

    private String summaryText(List<ObjectRow> all) {
        StringBuilder text = new StringBuilder(I18n.t("object.summary.count", all.size()));
        for (SchemaService.ObjectKind kind : KINDS) {
            long count = all.stream().filter(row -> row.object().kind() == kind).count();
            if (count > 0) {
                text.append(" · ").append(typeLabel(kind)).append(' ').append(count);
            }
        }
        return text.toString();
    }

    /**
     * ⭐ 第十九轮 S-1：按**已加载的数据**决定 6 个可选列显不显示。
     *
     * <p>规则与 {@code TableListPage} 完全一致：只在「每一行都是占位符」时整列隐藏，
     * 有一行有真值就留着（混合场景：MySQL 的表有引擎、视图没有 → 引擎列保留）。
     * 判定依据是 {@link #rows}（全部已加载），不是过滤后的子集。
     * 核心列「名称 / 类型 / 行」不参与判定。
     */
    private void applyColumnVisibility() {
        List<ObjectRow> all = List.copyOf(rows);
        sizeColumn.setVisible(MetadataColumns.hasAnyValue(all,
                row -> row.meta() == null ? "—" : row.meta().sizeText()));
        engineColumn.setVisible(MetadataColumns.hasAnyValue(all,
                row -> row.meta() == null ? "—" : row.meta().engineText()));
        createdColumn.setVisible(MetadataColumns.hasAnyValue(all,
                row -> row.meta() == null ? "—" : row.meta().createdText()));
        updatedColumn.setVisible(MetadataColumns.hasAnyValue(all,
                row -> row.meta() == null ? "—" : row.meta().updatedText()));
        collationColumn.setVisible(MetadataColumns.hasAnyValue(all,
                row -> row.meta() == null ? "—" : row.meta().collationText()));
        commentColumn.setVisible(MetadataColumns.hasAnyValue(all,
                row -> row.meta() == null ? "—" : row.meta().commentText()));
    }

    /** 过滤 chip 的标签带上数量 —— 「视图 1」比「视图」有用得多。 */
    private void rebuildFilterChips() {
        List<javafx.scene.Node> children = new ArrayList<>();
        children.add(chip(I18n.t("object.filter.allCount", rows.size()), null));
        for (SchemaService.ObjectKind kind : KINDS) {
            long count = rows.stream().filter(row -> row.object().kind() == kind).count();
            if (count > 0) {
                children.add(chip(typeLabel(kind) + " " + count, kind));
            }
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        children.add(spacer);
        // ⚠️ 搜索框必须重新挂回去 —— 这里整体重建过滤条，
        //    早先只 setAll(chips) 会把搜索框从场景里摘掉。
        children.add(search);

        for (javafx.scene.Node node : children) {
            if (node instanceof Button button) {
                boolean active = button.getId().equals("object-filter-"
                        + (filter == null ? "all" : filter.name().toLowerCase(Locale.ROOT)));
                if (active) {
                    button.getStyleClass().add("adb-filter-chip--active");
                }
                button.setTooltip(new Tooltip(I18n.t("object.filter.tooltip", button.getText())));
            }
        }
        filters.getChildren().setAll(children);
    }

    /** 把「类型 + 搜索词」两个条件一起落到 FilteredList 上。 */
    private void applyFilter() {
        filtered.setPredicate(row -> {
            if (filter != null && row.object().kind() != filter) {
                return false;
            }
            return query.isEmpty()
                    || row.object().name().toLowerCase(Locale.ROOT).contains(query);
        });
        // 过滤后列表可能变空 —— 空清单要说话，还要说清是哪一层收窄了范围（U24）。
        if (!rows.isEmpty() && filtered.isEmpty()) {
            table.setPlaceholder(query.isEmpty()
                    ? EmptyState.of(Icons.Icon.FILTER, I18n.t("object.filter.kindEmpty"),
                            I18n.t("object.filter.kindEmptyDetail"))
                    : EmptyState.of(Icons.Icon.SEARCH, I18n.t("object.noMatches"),
                            I18n.t("object.noNameContains", search.getText()),
                            I18n.t("tableList.clearSearch"), search::clear));
        }
        rebuildCards();
    }

    private void showEmpty(Node node) {
        content.getChildren().setAll(node);
        VBox.setVgrow(node, Priority.ALWAYS);
    }

    private void showContent() {
        if (content.getChildren().size() == 2 && content.getChildren().get(0) == filters) {
            return;
        }
        content.getChildren().setAll(filters, views);
        VBox.setVgrow(views, Priority.ALWAYS);
    }

    // ─────────────────────────── 展示映射 ───────────────────────────

    /** 清单「类型」列的文案（比侧栏分组名更完整）。 */
    private static String typeLabel(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> I18n.t("detail.kind.table");
            case VIEW -> I18n.t("detail.kind.view");
            case PROCEDURE -> I18n.t("object.kind.procedure");
            case FUNCTION -> I18n.t("object.kind.function");
            case TRIGGER -> I18n.t("object.kind.trigger");
            case EVENT -> I18n.t("object.kind.event");
        };
    }

    private static Icons.Icon iconOf(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> Icons.Icon.TABLE;
            case VIEW -> Icons.Icon.VIEW_OBJECT;
            case PROCEDURE, FUNCTION -> Icons.Icon.FUNCTION;
            case TRIGGER -> Icons.Icon.TRIGGER;
            case EVENT -> Icons.Icon.HISTORY;
        };
    }

    private static String iconStyleClass(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> "adb-tree__icon--table";
            case VIEW -> "adb-tree__icon--view";
            case PROCEDURE -> "adb-tree__icon--procedure";
            case FUNCTION -> "adb-tree__icon--function";
            case TRIGGER -> "adb-tree__icon--trigger";
            case EVENT -> "adb-tree__icon--event";
        };
    }

    /**
     * 对象类型图标（14px 标签档）。
     *
     * <p>R35 N3：有彩色资产就分层上色，没有就回退单色。
     * N4-b：两档都挂类型色类 —— 单色时描边自己吃 {@code -adb-kind-*}，
     * 彩色时容器上的类经 {@code .adb-tree__icon--table .adb-icon-edge}
     * 只染轮廓层（见 app.css 的「彩色图标的类型色」块）。
     * 与 {@code SideBar} 的导航树同一口径，否则同一个图标在树里是一种颜色、
     * 在对象首页是另一种，读者会以为是两种对象。
     */
    private static Node typeIcon(SchemaService.ObjectKind kind) {
        Node node = Icons.colored(iconOf(kind), Icons.SIZE_LABEL);
        node.getStyleClass().add(iconStyleClass(kind));
        return node;
    }

    /**
     * 清单的一行。
     *
     * @param meta 表的元数据；视图/过程/函数/触发器为 {@code null}
     *             （JDBC 元数据里没有它们的行数与大小）
     */
    record ObjectRow(SchemaService.SchemaObject object, TableMeta meta) {

        /** 行 id —— 测试与截图脚本靠它定位，格式与旧的 object-chip-* 对齐。 */
        String id() {
            return "object-row-" + object.kind().name().toLowerCase(Locale.ROOT)
                    + "-" + object.name();
        }

        /** 网格视图里卡片的 id（与行 id 分开，便于截图脚本分别定位）。 */
        String cardId() {
            return "object-card-" + object.kind().name().toLowerCase(Locale.ROOT)
                    + "-" + object.name();
        }
    }
}
