package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ⭐ **对象首页** —— 标签栏最左边那个常驻的「对象」标签的内容。
 *
 * <p>Navicat 的标签栏第一个就是常驻的「对象」标签（实拍见
 * {@code docs/NAVICAT-COMPARE.md} §2.2 C1）：它是**浏览的起点**，
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

    /** 列宽记忆的偏好根；没有它就不记（单测 / 无外壳照样能用）。 */
    private SettingsStore preferences;
    /** 对象首页的列宽存在这个前缀下（与表清单页分开，两页列的集合不同）。 */
    private static final String COLUMN_WIDTH_PREFIX = "ui.list.objects";

    private final Label summary = new Label();
    private final ObservableList<ObjectRow> rows = FXCollections.observableArrayList();
    private final FilteredList<ObjectRow> filtered = new FilteredList<>(rows, row -> true);
    private final TableView<ObjectRow> table = new TableView<>();
    private final HBox filters = new HBox(6);
    private final TextField search = new TextField();
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, "刷新");
    private final VBox content = new VBox();

    // 对象级工具条：随选择启停（Navicat 的对象清单同样如此）。
    private Button openButton;
    private Button designButton;
    private Button createButton;
    private Button dropButton;

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

    /**
     * ⭐ P2-1：列宽记住 —— 拖过一次，下次打开还是那个宽度。
     *
     * <p>没有偏好存储时（单测 / 无外壳）就不记，功能不缺，只是不记。
     */
    void rememberColumnsIn(SettingsStore store) {
        this.preferences = store;
        ListColumns.installWidthMemory(table, COLUMN_WIDTH_PREFIX, store);
    }

    /** 测试用：跳过防抖直接落盘。 */
    void persistColumnWidthsForTest() {
        if (preferences != null) {
            ListColumns.save(table, COLUMN_WIDTH_PREFIX, preferences);
        }
    }

    /** 测试用：按列标题取列。 */
    TableColumn<ObjectRow, ?> columnTitled(String title) {
        for (TableColumn<ObjectRow, ?> column : table.getColumns()) {
            if (title.equals(column.getText())) {
                return column;
            }
        }
        return null;
    }

    /** 表格（供门禁断言列集合与密度）。 */
    TableView<ObjectRow> table() {
        return table;
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
     *                  也会去开数据页，然后必然报错（UI-AUDIT D1）。
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
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen == null ? object -> { } : onOpen;
        this.status = status == null ? message -> { } : status;
        this.onConnect = onConnect;
        this.host = host == null ? new Host() { } : host;
        getStyleClass().addAll("adb-page", "adb-object-home");
        buildUi();
        applyFilter();
    }

    private void buildUi() {
        Label title = new Label("对象");
        title.getStyleClass().add("adb-page__title");
        Label badge = new Label("总览");
        badge.getStyleClass().addAll("adb-badge", "adb-badge--muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        summary.setId("object-home-summary");
        summary.getStyleClass().add("adb-muted");

        refresh.setId("object-home-refresh");
        refresh.getStyleClass().add("adb-button");
        refresh.setOnAction(event -> reload());

        // ⚠️ refresh 必须进场景图 —— 此前它只被 new 出来、绑了动作、设了 id，
        //    却从未加入任何容器，于是「刷新」按钮在界面上根本不存在
        //    （截图审计 S2，docs/UI-AUDIT.md §2.1）。
        HBox header = new HBox(10, title, badge, spacer, summary);
        header.getStyleClass().add("adb-page__header");
        header.setAlignment(Pos.CENTER_LEFT);

        buildFilters();
        buildTable();

        content.getChildren().addAll(filters, table);
        VBox.setVgrow(table, Priority.ALWAYS);

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
        openButton = toolbarOp(Icons.Icon.TABLE, "打开", "打开选中对象的数据页 / 预览页");
        openButton.setId("object-home-open");
        openButton.setOnAction(event -> openSelected());

        designButton = toolbarOp(Icons.Icon.STRUCTURE, "设计表",
                "在结构编辑器里打开选中的表或视图");
        designButton.setId("object-home-design");
        designButton.setOnAction(event -> {
            SchemaService.SchemaObject selected = singleSelection();
            if (selected == null) {
                status.accept("先选一个表或视图");
                return;
            }
            host.design(selected);
        });

        createButton = toolbarOp(Icons.Icon.ADD, "新建表", "新建一张表（弹窗填字段）");
        createButton.setId("object-home-new");
        createButton.setOnAction(event -> host.newTable());

        dropButton = toolbarOp(Icons.Icon.DELETE, "删除表",
                "删除选中的表（会二次确认，可多选批量删）");
        dropButton.setId("object-home-drop");
        dropButton.getStyleClass().add("adb-button--danger");
        dropButton.setOnAction(event -> {
            List<String> tables = table.getSelectionModel().getSelectedItems().stream()
                    .map(row -> row.object())
                    .filter(object -> object.kind() == SchemaService.ObjectKind.TABLE)
                    .map(SchemaService.SchemaObject::name)
                    .toList();
            if (tables.isEmpty()) {
                status.accept("先选一张表");
                return;
            }
            host.dropTables(tables);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ⭐ UX-3：看对象的一组、改对象的一组，中间一条竖线。
        HBox groupView = new HBox(4, openButton, designButton);
        HBox groupEdit = new HBox(4, createButton, dropButton);
        groupView.getStyleClass().add("adb-toolbar__group");
        groupEdit.getStyleClass().add("adb-toolbar__group");
        groupView.setAlignment(Pos.CENTER_LEFT);
        groupEdit.setAlignment(Pos.CENTER_LEFT);
        Region separator = new Region();
        separator.getStyleClass().add("adb-toolbar__separator");

        HBox toolbar = new HBox(8, groupView, separator, groupEdit, spacer, refresh);
        toolbar.setId("object-home-toolbar");
        toolbar.getStyleClass().add("adb-inline-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    /** 对象首页工具条的一颗主操作：图标 + 文字 + tooltip + accessibleText。 */
    private Button toolbarOp(Icons.Icon icon, String text, String hint) {
        Button button = IconButtons.labeled(new Button(), icon, text, hint, Icons.SIZE_PRIMARY);
        button.getStyleClass().add("adb-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** 当前唯一的选中对象；多选 / 空选为 {@code null}。 */
    private SchemaService.SchemaObject singleSelection() {
        List<ObjectRow> selected = table.getSelectionModel().getSelectedItems();
        return selected.size() == 1 ? selected.getFirst().object() : null;
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
        boolean connected = session.get() != null;
        List<ObjectRow> selected = List.copyOf(table.getSelectionModel().getSelectedItems());
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

        host.inspect(single ? selected.getFirst().object() : null);
    }

    // ─────────────────────────── 过滤条 ───────────────────────────

    private void buildFilters() {
        filters.getStyleClass().add("adb-object-list__filters");
        filters.setAlignment(Pos.CENTER_LEFT);

        search.setId("object-home-search");
        search.setPromptText("搜索对象名");
        search.setPrefWidth(180);
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            query = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            applyFilter();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        filters.getChildren().addAll(chip("全部", null), spacer, search);
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
        table.setEditable(false);
        // ⭐ S-6：可读下限 + 约束策略 —— 宽屏填满，挤窄卡在 minWidth 并横向滚动
        //    （为什么不是 UNCONSTRAINED：宽屏会留一大片空白，见 §33.5）。
        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("没有匹配的对象"));
        table.setFixedCellSize(rowHeight);

        // ⭐ 列与 Navicat 的「表清单」对齐：名称 / 类型 / 行 / 数据长度 / 引擎 /
        //    创建时间 / 修改时间 / 排序规则 / 注释。方言拿不到的字段保持「—」。
        // ⭐ 第十九轮 S-1：可选列（数据长度/引擎/创建/修改/排序规则/注释）留引用，
        //    按已加载数据决定显不显示 —— 全是占位符就整列隐藏（见 applyColumnVisibility）。
        sizeColumn = sizeColumn();
        engineColumn = textColumn("引擎", 90, 80, row -> row.meta() == null
                ? "—" : row.meta().engineText());
        createdColumn = textColumn("创建时间", 130, 130, row -> row.meta() == null
                ? "—" : row.meta().createdText());
        updatedColumn = textColumn("修改时间", 130, 130, row -> row.meta() == null
                ? "—" : row.meta().updatedText());
        collationColumn = textColumn("排序规则", 110, 110, row -> row.meta() == null
                ? "—" : row.meta().collationText());
        commentColumn = textColumn("注释", 160, 120, row -> row.meta() == null
                ? "—" : row.meta().commentText());

        table.getColumns().setAll(List.of(
                nameColumn(),
                textColumn("类型", 64, 72, row -> typeLabel(row.object().kind())),
                rowsColumn(),
                sizeColumn,
                engineColumn,
                createdColumn,
                updatedColumn,
                collationColumn,
                commentColumn));

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

    private TableColumn<ObjectRow, String> nameColumn() {
        TableColumn<ObjectRow, String> column = new TableColumn<>("名称");
        column.setId("object-column-name");
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
                SVGPath icon = Icons.of(iconOf(row == null
                        ? SchemaService.ObjectKind.TABLE : row.object().kind()),
                        Icons.SIZE_LABEL);
                icon.getStyleClass().addAll("adb-icon", iconStyleClass(row == null
                        ? SchemaService.ObjectKind.TABLE : row.object().kind()));
                Label label = new Label(name);
                HBox box = new HBox(6, icon, label);
                // ⭐ P2-1：长对象名截断后，Tooltip 是拿到全名的唯一出口。
                ListColumns.applyTooltip(this, name);
                box.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(box);
            }
        });
        return column;
    }

    /** 「行数」「大小」右对齐，且**按数值排序**而不是按字符串。 */
    private TableColumn<ObjectRow, String> rowsColumn() {
        TableColumn<ObjectRow, String> column = new TableColumn<>("行数");
        column.setId("object-column-rows");
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
        TableColumn<ObjectRow, String> column = new TableColumn<>("数据长度");
        column.setId("object-column-size");
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
        TableColumn<ObjectRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setMinWidth(ColumnWidths.listColumnMin(title, minWidth));
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        // ⭐ P2-1：长注释 / 长排序规则被截断时给全文（短值与「—」不给）。
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty || text == null ? null : text);
                ListColumns.applyTooltip(this, text);
            }
        });
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
        ObjectRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            status.accept("先选一个对象");
            return;
        }
        onOpen.accept(row.object());
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
            case TRIGGER -> "「" + name + "」是触发器 —— 请在结构页的「触发器」页签里查看";
        };
    }

    // ─────────────────────────── 数据 ───────────────────────────

    /** 重新读一遍对象列表。 */
    void reload() {
        DbSession current = session.get();
        if (current == null) {
            showEmpty(EmptyState.of(Icons.Icon.CONNECT, "还没有连接数据库",
                    "连上一个数据库后，这里会列出它的表、视图、函数和触发器。",
                    onConnect == null ? null : "新建连接",
                    onConnect));
            summary.setText("未连接");
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
                    summary.setText("读取失败");
                    showEmpty(EmptyState.of(Icons.Icon.CLEAR, "读不到对象列表",
                            message, "重试", this::reload));
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
        applyColumnVisibility();
        rebuildFilterChips();
        applyFilter();

        if (all.isEmpty()) {
            showEmpty(EmptyState.of(Icons.Icon.DATABASE,
                    "这个连接里还没有对象",
                    "数据库是空的。可以先在查询页执行建表语句，"
                            + "或从侧栏新建一个表。",
                    "打开查询页", () -> status.accept("用查询页执行 CREATE TABLE 建第一张表")));
            summary.setText("共 0 个对象");
            updateToolbarState();
            return;
        }
        showContent();
        summary.setText(summaryText(all));
        updateToolbarState();
    }

    private String summaryText(List<ObjectRow> all) {
        StringBuilder text = new StringBuilder("共 " + all.size() + " 个对象");
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
        children.add(chip("全部 " + rows.size(), null));
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
                button.setTooltip(new Tooltip("只看" + button.getText()));
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
        // 过滤后列表可能变空 —— 空清单要说话，不能是一片空白。
        if (!rows.isEmpty() && filtered.isEmpty()) {
            table.setPlaceholder(new Label(query.isEmpty()
                    ? "这个类型下没有对象" : "没有名称包含「" + query + "」的对象"));
        }
    }

    private void showEmpty(javafx.scene.Node node) {
        content.getChildren().setAll(node);
        VBox.setVgrow(node, Priority.ALWAYS);
    }

    private void showContent() {
        if (content.getChildren().size() == 2 && content.getChildren().get(0) == filters) {
            return;
        }
        content.getChildren().setAll(filters, table);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    // ─────────────────────────── 展示映射 ───────────────────────────

    private static String labelOf(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> "表";
            case VIEW -> "视图";
            case PROCEDURE -> "过程";
            case FUNCTION -> "函数";
            case TRIGGER -> "触发器";
            case EVENT -> "事件";
        };
    }

    /** 清单「类型」列的文案（比侧栏分组名更完整）。 */
    private static String typeLabel(SchemaService.ObjectKind kind) {
        return switch (kind) {
            case TABLE -> "表";
            case VIEW -> "视图";
            case PROCEDURE -> "存储过程";
            case FUNCTION -> "函数";
            case TRIGGER -> "触发器";
            case EVENT -> "事件";
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
    }
}
