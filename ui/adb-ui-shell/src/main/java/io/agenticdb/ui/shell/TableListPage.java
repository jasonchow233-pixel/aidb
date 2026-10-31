package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.RowHeights;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 表清单页 —— Navicat 的「表对象清单」那一层，也是本项目**唯一的对象清单页**。
 *
 * <p>⭐ R37 N33：以前另有一张「对象首页」（药丸总览 + 类型筛选），Navicat 没有这个概念；
 * 现在常驻的「对象」标签直接托管本页，表以外的对象在左侧树里浏览。
 *
 * <p>列与 Navicat 的「表」对象清单一致（N14，官方截图实拍）：
 * **名称 / 行 / 数据长度 / 引擎 / 修改日期 / 注释**。方言拿不到的格显示「—」——
 * 显示「—」比编一个看起来像真的值诚实；列本身不再按数据整列隐藏，
 * 六列在任何方言下都会出现（恒空的列按「—」读，位置不随数据跳动）。
 *
 * <p>顶部工具条是 N15 的七键：**打开表 / 设计表 / 新建表 / 用 AI 新建表 /
 * 删除表 / 导入向导 / 导出向导**：
 * <ul>
 *   <li>危险动作（删除）选中后才会亮，并且一定先展示 SQL 再执行；</li>
 *   <li>多选后删除会先给一张汇总确认页，执行完再报告失败行；</li>
 *   <li>导入 / 导出在单选时直接进向导；没选或多选时先进「选一张表」对话框；</li>
 *   <li>「用 AI 新建表」的能力面还没建，按诚实条款打开如实说明页；</li>
 *   <li>双击行或按 Enter 打开数据页（与对象树的操作一致）。</li>
 * </ul>
 *
 * <p>⛔ 统计行数可能要全表扫描，所以元数据**在后台线程**读，界面上先出空表 + 转圈。
 */
final class TableListPage extends VBox {

    /** 页面真正干活时回调外壳（建表 / 打开 / 设计 / 删表）。 */
    interface Host {

        default void newTable() {
        }

        /**
         * 「用 AI 新建表」—— 能力面还没建，外壳打开如实说明页。
         *
         * <p>默认空实现只为老的测试假 Host 不炸；生产外壳必须覆盖。
         */
        default void newTableWithAi() {
        }

        default void openTable(String name) {
        }

        default void designTable(String name) {
        }

        default void renameTable(String name) {
        }

        default void truncateTable(String name) {
        }

        default void dropTables(List<String> names) {
        }

        default void selectionChanged(String text) {
        }

        /**
         * 选择变化时同步右侧「对象详情面板」（规格 §6.3）。
         *
         * <p>{@code name} = 唯一选中的表名；多选 / 空选传 {@code null}
         * （没有单一对象可看，外壳会把面板切回空态）。
         * 默认空实现，老的门禁假 Host 不受影响。
         */
        default void inspectTable(String name) {
        }

        default void activated() {
        }
    }

    /**
     * 工具条 / 右键菜单共用的动作。
     *
     * <p>生产环境传的是 {@link ActionRegistry} 里的实例；单测可以传本地实例。
     * 这样按钮与菜单项的启用态来自同一份真相，不会出现「按钮灰了、右键还能点」。
     */
    record Actions(Action create, Action open, Action design, Action rename,
                   Action truncate, Action drop, Action refresh,
                   Action selectAll, Action viewList, Action viewGrid,
                   Action createAi, Action importWizard, Action exportWizard) {
    }

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Consumer<String> onOpen;
    private final Consumer<String> status;
    private final Actions actions;
    private final Host host;
    private final Consumer<String> viewSaver;
    private final SettingsStore settings;
    /** 列宽记忆按连接分开存；{@code null} = 不持久化（单测的旧构造器）。 */
    private final String connectionKey;
    private ColumnLayoutMemory columnMemory;

    private final TableView<TableMeta> table = new TableView<>();
    private final ObservableList<TableMeta> rows = FXCollections.observableArrayList();
    private final FilteredList<TableMeta> filtered = new FilteredList<>(rows, meta -> true);
    private final TextField search = new TextField();
    private final Label summary = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    /** 网格视图：卡片集合；选择状态单独存一份，切回表格时再同步。 */
    private final FlowPane cards = new FlowPane(10, 10);
    private final ScrollPane gridScroll = new ScrollPane(cards);
    private final Set<String> gridSelection = new LinkedHashSet<>();
    private final BooleanProperty listView = new SimpleBooleanProperty(true);

    private Button listButton;
    private Button gridButton;
    private String anchorName;
    /** 防重入：统计没跑完时再点刷新只会排一次队。 */
    private boolean loading;
    /** 同步表格与网格选择时不要来回报告状态。 */
    private boolean syncingSelection;
    /** 上一次报给外壳「详情面板该看谁」的表名；{@code null} = 没有单一对象。 */
    private String inspectReported;

    /** 密度档位 → 行高（不写死）。 */
    private int rowHeight = RowHeights.of("normal");

    TableListPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                  Consumer<String> onOpen, Consumer<String> status) {
        this(session, schemaService, dbExecutor, onOpen, status, null, null, "list", null);
    }

    TableListPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                  Consumer<String> onOpen, Consumer<String> status,
                  Actions actions, Host host, String initialView, Consumer<String> viewSaver) {
        this(session, schemaService, dbExecutor, onOpen, status, actions, host,
                initialView, viewSaver, null, null);
    }

    TableListPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                  Consumer<String> onOpen, Consumer<String> status,
                  Actions actions, Host host, String initialView, Consumer<String> viewSaver,
                  SettingsStore settings, String connectionKey) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen == null ? name -> { } : onOpen;
        this.status = status == null ? message -> { } : status;
        this.actions = actions == null ? localActions() : actions;
        this.host = host;
        this.viewSaver = viewSaver;
        this.settings = settings;
        this.connectionKey = connectionKey;

        getStyleClass().addAll("adb-page", "adb-table-list-page");
        buildTable();
        buildGrid();
        getChildren().addAll(buildToolbar(), buildViews());
        VBox.setVgrow(getChildren().getLast(), Priority.ALWAYS);
        showView(initialView, false);
        reload();
    }

    // ─────────────────────────── 工具条 ───────────────────────────

    private HBox buildToolbar() {
        search.setId("table-list-search");
        search.setPromptText(I18n.t("tableList.searchPrompt"));
        // 120 是这条工具条的上限：七颗键 + 列表/网格切换已经排到窗口右缘，
        // 放宽到 150 会把第二颗切换键挤出可视区（2026-10-31 帧核对）。
        // 占位符因此用短文案（"Search tables" / "搜索表名"），不靠省略号收尾。
        search.setPrefWidth(120);
        search.setMinWidth(90);
        search.getStyleClass().add("adb-table-list__search");
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter(newValue));
        search.focusedProperty().addListener((observable, was, now) -> {
            if (now) {
                activate();
            }
        });

        summary.setId("table-list-summary");
        summary.getStyleClass().add("adb-muted");
        // 空文案不占位：常态下工具条只有 Navicat 的七颗键（数量不摆在这一行）。
        summary.managedProperty().bind(summary.textProperty().isNotEmpty());

        spinner.setId("table-list-spinner");
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        spinner.getStyleClass().add("adb-grid__spinner");

        // ⭐ N15：七颗带文字的键 —— 光看图标猜不出「导入」和「打开」的区别。
        //    Navicat 的对象工具条没有「刷新」键：刷新留在右键菜单 / 菜单栏。
        Button open = labeledGhost(actions.open(), Icons.Icon.OPEN, "table-list-open");
        Button design = labeledGhost(actions.design(), Icons.Icon.STRUCTURE, "table-list-design");
        Button create = labeledGhost(actions.create(), Icons.Icon.ADD, "table-list-new");
        Button createAi = labeledGhost(actions.createAi(), Icons.Icon.AI, "table-list-new-ai");
        Button drop = labeledGhost(actions.drop(), Icons.Icon.DELETE, "table-list-drop");
        Button importWizard = labeledGhost(actions.importWizard(), Icons.Icon.IMPORT,
                "table-list-import");
        Button exportWizard = labeledGhost(actions.exportWizard(), Icons.Icon.EXPORT,
                "table-list-export");

        listButton = ActionRegistry.iconButtonFor(actions.viewList(), Icons.Icon.LIST,
                I18n.t("action.table-list.view-list"), I18n.t("action.table-list.view-list"));
        gridButton = ActionRegistry.iconButtonFor(actions.viewGrid(), Icons.Icon.GRID,
                I18n.t("action.table-list.view-grid"), I18n.t("action.table-list.view-grid"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 按语义分三组，组间一条竖线 —— 一排按钮不分堆等于没有顺序。
        HBox groupObject = grouped(open, design);
        HBox groupEdit = grouped(create, createAi, drop);
        HBox groupTransfer = grouped(importWizard, exportWizard);
        HBox groupView = grouped(listButton, gridButton);

        HBox toolbar = new HBox(6, groupObject, toolbarSeparator(), groupEdit,
                toolbarSeparator(), groupTransfer, spacer, search, summary, spinner,
                toolbarSeparator(), groupView);
        toolbar.getStyleClass().addAll("adb-inline-toolbar", "adb-table-list__toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    /**
     * 带文字的透明底按钮（N15 的七键）。
     *
     * <p>{@code USE_PREF_SIZE} 防止 HBox 把动作名压成省略号 ——
     * 挤不下时该收缩的是搜索框，不是「导入向导」这四个字。
     */
    private static Button labeledGhost(Action action, Icons.Icon icon, String id) {
        Button button = IconButtons.labeled(ActionRegistry.buttonFor(action), icon,
                action.label(), action.label());
        button.setId(id);
        button.getStyleClass().addAll("adb-button", "adb-button--ghost");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** 工具条的一组（同一语义的动作）。 */
    private static HBox grouped(Node... nodes) {
        HBox group = new HBox(3, nodes);
        group.setAlignment(Pos.CENTER_LEFT);
        group.getStyleClass().add("adb-toolbar__group");
        return group;
    }

    /** 组与组之间的竖分隔线。 */
    private static Region toolbarSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("adb-toolbar__separator");
        return separator;
    }

    /**
     * 导入 / 导出的候选表：**当前选中的那张排第一**。
     *
     * <p>{@link TransferHub} 会预选列表第一项，所以「把选中表挪到第一位」
     * 就等于「向导里预选了这张表」—— 不用给弹窗再加一个预选参数。
     */
    List<String> transferCandidates() {
        List<String> names = new ArrayList<>();
        List<String> selected = selectedNames();
        if (selected.size() == 1) {
            names.add(selected.getFirst());
        }
        for (TableMeta meta : rows) {
            if (!names.contains(meta.name())) {
                names.add(meta.name());
            }
        }
        return List.copyOf(names);
    }

    /**
     * 导入 / 导出向导。
     *
     * <p>N15：单选一张表时直接进向导（省一步选表）；没选或多选时先给
     * {@link TransferHub} 的选表对话框 —— 两边的候选来自同一份清单。
     */
    void openImport() {
        openTransfer(false);
    }

    void openExport() {
        openTransfer(true);
    }

    private void openTransfer(boolean export) {
        if (rows.isEmpty()) {
            status.accept(I18n.t("tableList.noTablesForTransfer"));
            return;
        }
        javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
        List<String> selected = selectedNames();
        if (selected.size() == 1) {
            if (export) {
                TransferHub.exportSingle(session, selected.getFirst(), dbExecutor, status, owner);
            } else {
                TransferHub.importSingle(session, selected.getFirst(), dbExecutor, status, owner);
            }
            return;
        }
        TransferHub hub = new TransferHub(session, transferCandidates(), dbExecutor, status);
        hub.show(owner);
    }

    // ─────────────────────────── 清单 ───────────────────────────

    private void buildTable() {
        table.setId("table-list");
        table.getStyleClass().add("adb-table-list");
        table.setAccessibleText(I18n.t("tableList.aria.list"));
        table.setEditable(false);
        // U24：空态不是一行灰字 —— 图标说清「这是空，不是坏了」，说明给出路。
        table.setPlaceholder(EmptyState.of(Icons.Icon.SEARCH, I18n.t("tableList.noMatches"),
                I18n.t("tableList.noMatchesDetail"),
                I18n.t("tableList.clearSearch"), search::clear));
        // ⭐ S-6：可读下限 + 约束策略 —— 宽屏填满，挤窄卡在 minWidth 并横向滚动
        //    （为什么不是 UNCONSTRAINED：宽屏会留一大片空白，见 §33.5）。
        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(rowHeight);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // ⭐ N14：Navicat 的「表」清单是**六列**，且六列恒在 ——
        //    方言拿不到的格显示「—」，列本身不随数据整列消失（位置不跳动）。
        table.getColumns().setAll(List.of(
                nameColumn(),
                rowsColumn(),
                sizeColumn(),
                keyed(textColumn(I18n.t("column.engine"), 90, 80, TableMeta::engineText), "engine"),
                keyed(textColumn(I18n.t("column.updated"), 150, 120, TableMeta::updatedText), "updated"),
                keyed(textColumn(I18n.t("column.comment"), 170, 110,
                        TableMeta::commentText, true, true), "comment")));

        // ⭐ P2-1：列宽按连接记忆（与数据网格同一套「拖完就记住」的预期）。
        columnMemory = new ColumnLayoutMemory(table, settings,
                () -> connectionKey, "ui.tablelist");

        table.getSelectionModel().getSelectedIndices().addListener(
                (ListChangeListener<Integer>) change -> updateSelectionState());

        // 行 id：单测与截图脚本按表名点行。
        table.setRowFactory(view -> {
            TableRow<TableMeta> row = new TableRow<>() {
                @Override
                protected void updateItem(TableMeta item, boolean empty) {
                    super.updateItem(item, empty);
                    setId(empty || item == null ? "" : "table-row-" + item.name());
                }
            };
            row.setOnMousePressed(event -> activate());
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onOpen.accept(row.getItem().name());
                }
            });
            row.setOnContextMenuRequested(event -> {
                if (row.isEmpty()) {
                    return;
                }
                if (!row.isSelected()) {
                    table.getSelectionModel().clearSelection();
                    table.getSelectionModel().select(row.getItem());
                }
                activate();
                showContextMenu(row, event);
                event.consume();
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelected();
                event.consume();
            } else if (event.isShortcutDown() && event.getCode() == KeyCode.A) {
                selectAll();
                event.consume();
            }
        });
        table.focusedProperty().addListener((observable, was, now) -> {
            if (now) {
                activate();
            }
        });

        SortedList<TableMeta> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);

        filtered.addListener((ListChangeListener<TableMeta>) change -> {
            rebuildCards();
            updateSummary();
            updateSelectionState();
        });
    }

    /** 网格 / 列表共用的内容区；用可见性切换，选择与滚动位置都保留。 */
    private StackPane buildViews() {
        table.visibleProperty().bind(listView);
        table.managedProperty().bind(listView);
        gridScroll.visibleProperty().bind(listView.not());
        gridScroll.managedProperty().bind(listView.not());
        StackPane stack = new StackPane(table, gridScroll);
        stack.getStyleClass().add("adb-table-list__views");
        return stack;
    }

    private void buildGrid() {
        gridScroll.setId("table-list-grid");
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.getStyleClass().add("adb-table-list__scroll");
        cards.setPadding(new Insets(8));
        cards.setAlignment(Pos.TOP_LEFT);
        cards.getStyleClass().add("adb-table-list__cards");
        cards.prefWrapLengthProperty().bind(gridScroll.widthProperty().subtract(24));
    }

    /** 列宽记忆的身份：标题会改，这个 key 不会。 */
    private static TableColumn<TableMeta, String> keyed(
            TableColumn<TableMeta, String> column, String key) {
        column.setUserData(key);
        return column;
    }

    /** 表名列：类型图标 + 名字（与对象树、对象首页同一套图形语言）。 */
    private TableColumn<TableMeta, String> nameColumn() {
        TableColumn<TableMeta, String> column = new TableColumn<>(I18n.t("column.name"));
        column.setId("table-column-name");
        column.setUserData("name");
        column.setPrefWidth(220);
        column.setMinWidth(140);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().name()));
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Node icon = Icons.colored(Icons.Icon.TABLE, Icons.SIZE_LABEL);
                icon.getStyleClass().add("adb-tree__icon--table");
                Label label = new Label(name);
                HBox box = new HBox(6, icon, label);
                box.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(box);
            }
        });
        return column;
    }

    /** 行数列：右对齐 + 按数值排序。 */
    private TableColumn<TableMeta, String> rowsColumn() {
        TableColumn<TableMeta, String> column = new TableColumn<>(I18n.t("column.rows"));
        column.setId("table-column-rows");
        column.setUserData("rows");
        column.setPrefWidth(90);
        column.setMinWidth(72);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().rowsText()));
        column.setComparator((left, right) -> Long.compare(numberOf(left), numberOf(right)));
        column.setCellFactory(unused -> numberCell());
        return column;
    }

    /** 数据长度列：右对齐 + 折回字节排序。 */
    private TableColumn<TableMeta, String> sizeColumn() {
        TableColumn<TableMeta, String> column = new TableColumn<>(I18n.t("column.dataLength"));
        column.setId("table-column-size");
        column.setUserData("size");
        column.setPrefWidth(110);
        column.setMinWidth(88);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().sizeText()));
        column.setComparator((left, right) -> Long.compare(bytesOf(left), bytesOf(right)));
        column.setCellFactory(unused -> numberCell());
        return column;
    }

    private TableColumn<TableMeta, String> textColumn(
            String title, double width, double minWidth,
            Function<TableMeta, String> value) {
        return textColumn(title, width, minWidth, value, false);
    }

    /**
     * 文本列。{@code muted} 用于排序规则 / 注释这类「辅助信息」，
     * 避免它们和表名抢视觉重心（Navicat 的注释列也是浅一档）。
     */
    private TableColumn<TableMeta, String> textColumn(
            String title, double width, double minWidth,
            Function<TableMeta, String> value, boolean muted) {
        return textColumn(title, width, minWidth, value, muted, false);
    }

    /**
     * @param tooltip 悬停时给出**完整文本**（注释列用）。单元格被列宽截断时，
     *                tooltip 是唯一能读到全文的出口；占位符「—」不挂 tooltip，
     *                别把「没有注释」也解释一遍。
     */
    private TableColumn<TableMeta, String> textColumn(
            String title, double width, double minWidth,
            Function<TableMeta, String> value, boolean muted, boolean tooltip) {
        TableColumn<TableMeta, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setMinWidth(ColumnWidths.listColumnMin(title, minWidth));
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                value.apply(cell.getValue())));
        column.setCellFactory(unused -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                // cell 会被复用，样式类只加一次。
                if (muted && !getStyleClass().contains("adb-table-list__muted")) {
                    getStyleClass().add("adb-table-list__muted");
                }
                boolean real = !empty && !MetadataColumns.isPlaceholder(text);
                setText(empty || text == null ? null : text);
                if (!tooltip || !real) {
                    setTooltip(null);
                } else if (getTooltip() == null || !text.equals(getTooltip().getText())) {
                    setTooltip(new Tooltip(text));
                }
            }
        });
        return column;
    }

    private static TableCell<TableMeta, String> numberCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (!getStyleClass().contains("adb-table-list__number")) {
                    getStyleClass().add("adb-table-list__number");
                }
                setText(empty || value == null ? null : value);
            }
        };
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

    // ─────────────────────────── 网格视图 ───────────────────────────

    /** 把过滤后的表重建成卡片；空结果给一句明确的空态。 */
    private void rebuildCards() {
        Set<String> visible = new LinkedHashSet<>();
        for (TableMeta meta : filtered) {
            visible.add(meta.name());
        }
        gridSelection.retainAll(visible);
        cards.getChildren().clear();
        if (filtered.isEmpty()) {
            // U24：卡片视图的空态与列表视图同一套语言（图标 + 说明 + 出口），
            // 不再是一行飘在白底上的灰字。
            cards.getChildren().add(rows.isEmpty()
                    ? EmptyState.of(Icons.Icon.TABLE, I18n.t("tableList.empty"),
                            I18n.t("tableList.emptyDetail"))
                    : EmptyState.of(Icons.Icon.SEARCH, I18n.t("tableList.noMatches"),
                            I18n.t("tableList.noMatchesDetail"),
                            I18n.t("tableList.clearSearch"), search::clear));
            return;
        }
        for (TableMeta meta : filtered) {
            cards.getChildren().add(buildCard(meta));
        }
    }

    private VBox buildCard(TableMeta meta) {
        Node icon = Icons.colored(Icons.Icon.TABLE, Icons.SIZE_LABEL);
        icon.getStyleClass().add("adb-tree__icon--table");
        Label name = new Label(meta.name());
        name.getStyleClass().add("adb-table-card__name");
        HBox title = new HBox(6, icon, name);
        title.setAlignment(Pos.CENTER_LEFT);

        // ⭐ 第十九轮 S-2：卡片不摆「—」。每一行只留真有值的片段；
        //    整行都是占位符就不渲染这一行（也不留空占位）。
        //    实测 SQLite 演示库上一张卡片 3 行有 2 行是纯破折号（§10.3 C2）。
        //    注意 rowsText() 为「—」时不能拼成「— 行」——那不是占位符了。
        String rowsFact = MetadataColumns.isPlaceholder(meta.rowsText())
                ? null : I18n.t("status.rows", meta.rowsText().trim());
        Label facts = cardLine("adb-table-card__facts", rowsFact, meta.sizeText());
        Label engine = cardLine("adb-table-card__detail",
                meta.engineText(), meta.updatedText());
        Label comment = cardLine("adb-table-card__comment", meta.commentText());

        List<Node> lines = new ArrayList<>();
        lines.add(title);
        if (facts != null) {
            lines.add(facts);
        }
        if (engine != null) {
            lines.add(engine);
        }
        if (comment != null) {
            lines.add(comment);
        }

        VBox card = new VBox(4, lines.toArray(new Node[0]));
        card.setId("table-card-" + meta.name());
        card.setUserData(meta.name());
        card.getStyleClass().add("adb-table-card");
        card.setPrefWidth(220);
        card.setMaxWidth(220);
        card.setMinHeight(92);
        card.setFocusTraversable(true);
        // ⭐ P7：卡片是 VBox，读屏默认只会读成「面板」—— 补角色与名称。
        card.setAccessibleRole(javafx.scene.AccessibleRole.LIST_ITEM);
        card.setAccessibleText(meta.name());
        if (gridSelection.contains(meta.name())) {
            card.getStyleClass().add("adb-table-card--selected");
        }
        card.setOnMousePressed(event -> {
            card.requestFocus();
            activate();
        });
        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                onOpen.accept(meta.name());
                return;
            }
            selectCard(meta, event);
        });
        card.setOnContextMenuRequested(event -> {
            if (!gridSelection.contains(meta.name())) {
                gridSelection.clear();
                gridSelection.add(meta.name());
                anchorName = meta.name();
                refreshCardSelectionStyles();
                updateSelectionState();
            }
            activate();
            showContextMenu(card, event);
            event.consume();
        });
        card.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                onOpen.accept(meta.name());
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                if (!gridSelection.remove(meta.name())) {
                    gridSelection.add(meta.name());
                }
                anchorName = meta.name();
                refreshCardSelectionStyles();
                updateSelectionState();
                event.consume();
            }
        });
        return card;
    }

    /**
     * ⭐ 第十九轮 S-2：拼卡片里的一行，只留真有值的片段。
     *
     * <p>Navicat 的卡片只显示这个库**真提供**的字段；我们上一版把拿不到的字段
     * 渲染成「—」照样拼进这一行，于是在 SQLite 上出现「— · —」「—」这种纯破折号行。
     *
     * @return 这一行的标签；如果所有片段都是占位符则返回 {@code null}（调用方
     *         直接跳过，<b>不</b>渲染空行、也不留占位）
     */
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
            // 卡片只有 220px 宽，长注释一定被截断 —— 悬停要给得了全文。
            label.setTooltip(new Tooltip(joined));
        }
        return label;
    }

    /** 卡片点击：普通点击单选，⌘/Ctrl 切换，Shift 选区间。 */
    private void selectCard(TableMeta meta, MouseEvent event) {
        activate();
        if (event.isShortcutDown()) {
            if (!gridSelection.remove(meta.name())) {
                gridSelection.add(meta.name());
            }
        } else if (event.isShiftDown() && anchorName != null) {
            int from = indexOfCardName(anchorName);
            int to = indexOfCardName(meta.name());
            if (from >= 0 && to >= 0) {
                gridSelection.clear();
                int start = Math.min(from, to);
                int end = Math.max(from, to);
                for (int i = start; i <= end; i++) {
                    gridSelection.add(filtered.get(i).name());
                }
            } else {
                gridSelection.clear();
                gridSelection.add(meta.name());
            }
        } else {
            gridSelection.clear();
            gridSelection.add(meta.name());
        }
        anchorName = meta.name();
        refreshCardSelectionStyles();
        updateSelectionState();
    }

    private int indexOfCardName(String name) {
        for (int i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private void refreshCardSelectionStyles() {
        for (Node node : cards.getChildren()) {
            node.getStyleClass().remove("adb-table-card--selected");
            if (node.getUserData() instanceof String name && gridSelection.contains(name)) {
                node.getStyleClass().add("adb-table-card--selected");
            }
        }
    }

    /** 供门禁断言：当前是不是网格视图。 */
    boolean isGridView() {
        return !listView.get();
    }

    /** 切换视图，并记住选择（切回表格时仍选中同样的表）。 */
    void showView(String view) {
        showView(view, true);
    }

    private void showView(String view, boolean remember) {
        boolean grid = "grid".equalsIgnoreCase(view);
        if (grid == isGridView()) {
            updateViewActionStates();
            updateSelectionState();
            return;
        }
        syncingSelection = true;
        try {
            if (grid) {
                gridSelection.clear();
                for (TableMeta meta : table.getSelectionModel().getSelectedItems()) {
                    gridSelection.add(meta.name());
                }
            } else {
                table.getSelectionModel().clearSelection();
                for (int i = 0; i < table.getItems().size(); i++) {
                    if (gridSelection.contains(table.getItems().get(i).name())) {
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
        updateViewActionStates();
        updateSelectionState();
        if (remember && viewSaver != null) {
            viewSaver.accept(grid ? "grid" : "list");
        }
    }

    private void updateViewActionStates() {
        // 切到别的标签时外壳会把这些动作整体禁用；切回来必须由页面重新点亮。
        actions.viewList.setEnabled(true);
        actions.viewGrid.setEnabled(true);
        actions.viewList.setSelected(!isGridView());
        actions.viewGrid.setSelected(isGridView());
        if (listButton != null) {
            listButton.getStyleClass().remove("adb-icon-button--active");
            if (!isGridView()) {
                listButton.getStyleClass().add("adb-icon-button--active");
            }
        }
        if (gridButton != null) {
            gridButton.getStyleClass().remove("adb-icon-button--active");
            if (isGridView()) {
                gridButton.getStyleClass().add("adb-icon-button--active");
            }
        }
    }

    // ─────────────────────────── 选择与动作 ───────────────────────────

    /** 当前选中的表（列表视图看表格选择，网格视图看卡片选择）。 */
    List<TableMeta> selectedMetas() {
        if (isGridView()) {
            List<TableMeta> selected = new java.util.ArrayList<>();
            for (TableMeta meta : filtered) {
                if (gridSelection.contains(meta.name())) {
                    selected.add(meta);
                }
            }
            return selected;
        }
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    List<String> selectedNames() {
        return selectedMetas().stream().map(TableMeta::name).toList();
    }

    /** 状态栏的选中项文案 —— 单表带行数，多表说「项目」。 */
    void reportSelection() {
        if (host == null) {
            return;
        }
        List<TableMeta> selected = selectedMetas();
        if (selected.isEmpty()) {
            host.selectionChanged(I18n.t("status.noneSelected"));
        } else if (selected.size() == 1) {
            TableMeta meta = selected.getFirst();
            String rowsText = meta.rows() == null ? "" : " · " + I18n.t("status.rows", meta.rowsText());
            host.selectionChanged(I18n.t("tableList.selectedOne", meta.name() + rowsText));
        } else {
            host.selectionChanged(I18n.t("tableList.selectedCount", selected.size()));
        }
        reportInspect(selected.size() == 1 ? selected.getFirst().name() : null);
    }

    /**
     * 把当前选中项同步给右侧详情面板，并按值去重。
     *
     * <p>刷新、切视图、标签切回来都会重跑 {@link #updateSelectionState()} →
     * {@link #reportSelection()}；不去重就会拿同一个表名反复打扰外壳
     * （每次都触发一次元数据查询 + 一次面板重绘）。
     */
    private void reportInspect(String name) {
        if (java.util.Objects.equals(inspectReported, name)) {
            return;
        }
        inspectReported = name;
        host.inspectTable(name);
    }

    /** 供外壳在标签切回来时刷新状态栏与动作启用态。 */
    void refreshSelectionStatus() {
        updateSelectionState();
    }

    void createTable() {
        if (host == null) {
            status.accept(I18n.t("tableList.connectFirst"));
            return;
        }
        host.newTable();
    }

    /** 「用 AI 新建表」—— 能力面还没建，外壳会打开如实说明页。 */
    void createTableAi() {
        if (host == null) {
            status.accept(I18n.t("tableList.connectFirst"));
            return;
        }
        host.newTableWithAi();
    }

    void openSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? I18n.t("tableList.pickOneFirst") : I18n.t("tableList.singleOpen"));
            return;
        }
        if (host != null) {
            host.openTable(selected.getFirst().name());
        }
    }

    void designSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? I18n.t("tableList.pickOneFirst") : I18n.t("tableList.singleDesign"));
            return;
        }
        if (host != null) {
            host.designTable(selected.getFirst().name());
        }
    }

    void renameSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? I18n.t("tableList.pickOneFirst") : I18n.t("tableList.singleRename"));
            return;
        }
        if (host != null) {
            host.renameTable(selected.getFirst().name());
        }
    }

    void truncateSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? I18n.t("tableList.pickOneFirst") : I18n.t("tableList.singleTruncate"));
            return;
        }
        if (host != null) {
            host.truncateTable(selected.getFirst().name());
        }
    }

    void dropSelected() {
        List<String> names = selectedNames();
        if (names.isEmpty()) {
            status.accept(I18n.t("tableList.pickToDelete"));
            return;
        }
        if (host != null) {
            host.dropTables(names);
        }
    }

    void selectAll() {
        if (isGridView()) {
            gridSelection.clear();
            for (TableMeta meta : filtered) {
                gridSelection.add(meta.name());
            }
            refreshCardSelectionStyles();
            updateSelectionState();
        } else {
            table.getSelectionModel().selectAll();
        }
    }

    /** 选中项 / 加载状态 → 按钮与右键菜单的 enabled。 */
    private void updateSelectionState() {
        if (syncingSelection) {
            return;
        }
        int count = selectedMetas().size();
        boolean single = count == 1;
        actions.open.setEnabled(single);
        actions.design.setEnabled(single);
        actions.rename.setEnabled(single);
        actions.truncate.setEnabled(single);
        actions.drop.setEnabled(count >= 1);
        actions.create.setEnabled(host != null);
        actions.createAi.setEnabled(host != null);
        // 导入 / 导出要有一张表可选；单选时直接进向导，否则先选表。
        actions.importWizard.setEnabled(!rows.isEmpty());
        actions.exportWizard.setEnabled(!rows.isEmpty());
        actions.refresh.setEnabled(!loading);
        actions.selectAll.setEnabled(!filtered.isEmpty());
        updateViewActionStates();
        reportSelection();
    }

    /** 页面被点击 / 聚焦时告诉外壳「现在操作的是我」。 */
    private void activate() {
        if (host != null) {
            host.activated();
        }
    }

    /** 右键菜单：打开 / 设计 / 新建 / 重命名 / 清空 / 删除 / 刷新。 */
    private ContextMenu buildContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(
                ActionRegistry.menuItemFor(actions.open),
                ActionRegistry.menuItemFor(actions.design),
                new SeparatorMenuItem(),
                ActionRegistry.menuItemFor(actions.create),
                ActionRegistry.menuItemFor(actions.rename),
                ActionRegistry.menuItemFor(actions.truncate),
                ActionRegistry.menuItemFor(actions.drop),
                new SeparatorMenuItem(),
                ActionRegistry.menuItemFor(actions.refresh),
                ActionRegistry.menuItemFor(actions.selectAll));
        return menu;
    }

    private void showContextMenu(Node anchor, javafx.scene.input.ContextMenuEvent event) {
        ContextMenu menu = buildContextMenu();
        menu.show(anchor, event.getScreenX(), event.getScreenY());
    }

    /** 供门禁检查右键菜单是否来自同一组动作。 */
    ContextMenu contextMenuForTest() {
        return buildContextMenu();
    }

    // ─────────────────────────── 数据 ───────────────────────────

    /** 直接喂数据（单测用；真实路径走 {@link #reload()} 的后台读取）。 */
    void render(List<TableMeta> metas) {
        rows.setAll(metas == null ? List.of() : metas);
        updateSelectionState();
    }

    /** 搜索框（供门禁验证过滤真的生效）。 */
    TextField searchField() {
        return search;
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filtered.setPredicate(meta -> needle.isEmpty()
                || meta.name().toLowerCase(Locale.ROOT).contains(needle));
        if (!rows.isEmpty() && filtered.isEmpty()) {
            // U24：把用户真正输入的词回显给他看 —— 「没有匹配的表」不说是哪几个字没匹配上。
            table.setPlaceholder(EmptyState.of(Icons.Icon.SEARCH,
                    I18n.t("tableList.noNameContains", query),
                    I18n.t("tableList.substringHelp"),
                    I18n.t("tableList.clearSearch"), search::clear));
        }
    }

    private void updateSummary() {
        if (loading) {
            return;
        }
        if (search.getText() != null && !search.getText().isBlank()
                && filtered.size() != rows.size()) {
            summary.setText(I18n.t("tableList.showingOf", filtered.size(), rows.size()));
        } else {
            // ⭐ N15：Navicat 的对象工具条没有「N 张表」这一项 —— 常态留空，
            //    只有搜索过滤时回一句「显示 X / Y」，否则七颗键会被挤出窗口。
            summary.setText("");
        }
    }

    /** 重新统计（后台线程）。 */
    void reload() {
        if (loading || schemaService == null || session == null) {
            return;
        }
        loading = true;
        spinner.setVisible(true);
        summary.setText("");
        updateSelectionState();
        dbExecutor.execute(() -> {
            List<TableMeta> metas;
            String error = null;
            try {
                metas = schemaService.listTableMeta(session);
            } catch (Exception e) {
                metas = List.of();
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            List<TableMeta> finalMetas = metas;
            String finalError = error;
            Platform.runLater(() -> {
                loading = false;
                spinner.setVisible(false);
                rows.setAll(finalMetas);
                updateSummary();
                updateSelectionState();
                if (finalError != null) {
                    summary.setText(I18n.t("tableList.readFailed", finalError));
                    status.accept(I18n.t("tableList.readListFailed", finalError));
                }
            });
        });
    }

    /** 表格（供门禁断言密度作用到了它）。 */
    TableView<TableMeta> table() {
        return table;
    }

    /** 门禁用：立即落盘，不等 400ms 防抖。 */
    void persistColumnWidthsForTest() {
        if (columnMemory != null) {
            columnMemory.persist();
        }
    }

    Actions actions() {
        return actions;
    }

    /** 密度档位 —— 表清单也要跟。 */
    public void setDensity(String density) {
        rowHeight = RowHeights.of(density);
        table.setFixedCellSize(rowHeight);
    }

    // ─────────────────────────── 默认动作（单测 / 无外壳） ───────────────────────────

    /**
     * 无外壳（单测 / 独立使用）时用的本地动作。
     *
     * <p>⚠️ 走的是 {@code new Action(...)} 而不是 {@code actions.register(...)} ——
     * 所以「每个动作两侧都有名字」那条门禁原先**看不见这里**（它只匹配 register）。
     * 键仍然用 {@code action.<id>} / {@code category.<slug>}，与注册表那一路同名同值，
     * 两处共用一份资源；门禁的模式已扩到同时匹配两种构造。
     */
    private Actions localActions() {
        String category = io.agenticdb.ui.I18n.t("category.tableList");
        return new Actions(
                new Action("table-list.new", io.agenticdb.ui.I18n.t("action.table-list.new"),
                        category, null, this::createTable),
                new Action("table-list.open", io.agenticdb.ui.I18n.t("action.table-list.open"),
                        category, null, this::openSelected),
                new Action("table-list.design", io.agenticdb.ui.I18n.t("action.table-list.design"),
                        category, null, this::designSelected),
                new Action("table-list.rename", io.agenticdb.ui.I18n.t("action.table-list.rename"),
                        category, null, this::renameSelected),
                new Action("table-list.truncate",
                        io.agenticdb.ui.I18n.t("action.table-list.truncate"),
                        category, null, this::truncateSelected),
                new Action("table-list.drop", io.agenticdb.ui.I18n.t("action.table-list.drop"),
                        category, null, this::dropSelected),
                new Action("table-list.refresh", io.agenticdb.ui.I18n.t("action.table-list.refresh"),
                        category, null, this::reload),
                new Action("table-list.select-all",
                        io.agenticdb.ui.I18n.t("action.table-list.select-all"),
                        category, null, this::selectAll),
                new Action("table-list.view-list",
                        io.agenticdb.ui.I18n.t("action.table-list.view-list"),
                        category, null, () -> showView("list")),
                new Action("table-list.view-grid",
                        io.agenticdb.ui.I18n.t("action.table-list.view-grid"),
                        category, null, () -> showView("grid")),
                new Action("table-list.new-ai",
                        io.agenticdb.ui.I18n.t("action.table-list.new-ai"),
                        category, null, this::createTableAi),
                new Action("table-list.import",
                        io.agenticdb.ui.I18n.t("action.table-list.import"),
                        category, null, this::openImport),
                new Action("table-list.export",
                        io.agenticdb.ui.I18n.t("action.table-list.export"),
                        category, null, this::openExport));
    }
}
