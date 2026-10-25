package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DbSession;
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
 * 表清单页 —— Navicat 的「表清单」那一层。
 *
 * <p>列与 Navicat 的「表」对象清单一致：**名称 / 行 / 数据长度 / 引擎 /
 * 创建日期 / 修改日期 / 排序规则 / 注释**（实拍见
 * {@code docs/NAVICAT-COMPARE.md}）。方言拿不到的列显示「—」——
 * 显示「—」比编一个看起来像真的值诚实。
 *
 * <p>顶部工具条提供**新建 / 删除 / 设计 / 搜索 / 打开 / 刷新 / 视图切换**：
 * <ul>
 *   <li>危险动作（删除）选中后才会亮，并且一定先展示 SQL 再执行；</li>
 *   <li>多选后删除会先给一张汇总确认页，执行完再报告失败行；</li>
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
                   Action selectAll, Action viewList, Action viewGrid) {
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

    // ─────────── 可选列（方言相关）的引用 ───────────
    // ⭐ 第十九轮 S-1：这 6 列是「方言拿不到就显示 —」的可选列。留引用是为了
    //    在数据加载后按内容决定整列显不显示（见 applyColumnVisibility）。
    //    「名称 / 行」是核心列，不参与判定、永远显示。
    private TableColumn<TableMeta, String> sizeColumn;
    private TableColumn<TableMeta, String> engineColumn;
    private TableColumn<TableMeta, String> createdColumn;
    private TableColumn<TableMeta, String> updatedColumn;
    private TableColumn<TableMeta, String> collationColumn;
    private TableColumn<TableMeta, String> commentColumn;

    private Button listButton;
    private Button gridButton;
    /** ⭐ UX-3：清单页的「导入 / 导出」入口（预选当前选中表）。 */
    private final Button transferButton = new Button();
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
        search.setPromptText("搜索表名");
        search.setPrefWidth(160);
        search.setMinWidth(120);
        search.getStyleClass().add("adb-table-list__search");
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter(newValue));
        search.focusedProperty().addListener((observable, was, now) -> {
            if (now) {
                activate();
            }
        });

        summary.setId("table-list-summary");
        summary.getStyleClass().add("adb-muted");

        spinner.setId("table-list-spinner");
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        spinner.getStyleClass().add("adb-grid__spinner");

        Button create = IconButtons.withIcon(
                ActionRegistry.buttonFor(actions.create()), Icons.Icon.ADD);
        create.setId("table-list-new");
        Button drop = IconButtons.withIcon(
                ActionRegistry.buttonFor(actions.drop()), Icons.Icon.DELETE);
        drop.setId("table-list-drop");
        Button design = IconButtons.withIcon(
                ActionRegistry.buttonFor(actions.design()), Icons.Icon.STRUCTURE);
        design.setId("table-list-design");
        Button open = IconButtons.withIcon(
                ActionRegistry.buttonFor(actions.open()), Icons.Icon.OPEN);
        open.setId("table-list-open");
        Button refresh = IconButtons.withIcon(
                ActionRegistry.buttonFor(actions.refresh()), Icons.Icon.REFRESH);
        refresh.setId("table-list-refresh");

        // 文字按钮不许被 HBox 压到省略号（"打开…" 比只有图标更难懂）：
        // 挤不下时让搜索框变窄，动作名保持完整。
        for (Button button : List.of(create, drop, design, open, refresh)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }

        listButton = ActionRegistry.iconButtonFor(actions.viewList(), Icons.Icon.LIST,
                "列表视图", "列表视图");
        gridButton = ActionRegistry.iconButtonFor(actions.viewGrid(), Icons.Icon.GRID,
                "网格视图", "网格视图");

        // ⭐ UX-3：清单页直接进导入 / 导出 —— 预选当前选中的那张表。
        //    以前只能绕侧栏，选中了 orders 却要在弹窗里再找一遍。
        transferButton.setId("table-list-transfer");
        transferButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        IconButtons.labeled(transferButton, Icons.Icon.TRANSFER, "导入 / 导出",
                "选一张表导入或导出；这里会预选当前选中的表");
        transferButton.setMinWidth(Region.USE_PREF_SIZE);
        transferButton.setOnAction(event -> openTransferHub());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // ⭐ UX-3：按语义分四组，组间一条竖线 —— 一排 11 颗按钮不分堆，
        //    等于没有顺序，眼睛每颗都要读一遍。
        HBox groupObject = grouped(open, design, refresh);
        HBox groupEdit = grouped(create, drop);
        HBox groupTransfer = grouped(transferButton);
        HBox groupView = grouped(listButton, gridButton);

        HBox toolbar = new HBox(8, groupObject, toolbarSeparator(), groupEdit,
                toolbarSeparator(), groupTransfer, spacer, search, summary, spinner,
                toolbarSeparator(), groupView);
        toolbar.getStyleClass().addAll("adb-inline-toolbar", "adb-table-list__toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    /** 工具条的一组（同一语义的动作）。 */
    private static HBox grouped(Node... nodes) {
        HBox group = new HBox(4, nodes);
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

    private void openTransferHub() {
        TransferHub hub = new TransferHub(session, transferCandidates(), dbExecutor, status);
        hub.show(getScene() == null ? null : getScene().getWindow());
    }

    // ─────────────────────────── 清单 ───────────────────────────

    private void buildTable() {
        table.setId("table-list");
        table.getStyleClass().add("adb-table-list");
        table.setEditable(false);
        table.setPlaceholder(new Label("没有匹配的表"));
        // ⭐ S-6：可读下限 + 约束策略 —— 宽屏填满，挤窄卡在 minWidth 并横向滚动
        //    （为什么不是 UNCONSTRAINED：宽屏会留一大片空白，见 §33.5）。
        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(rowHeight);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // ⭐ 第十九轮 S-1：可选列要留引用，才能按数据决定显不显示。
        //    「名称 / 行」是核心列，永远显示；其余 6 列是方言相关的可选列。
        sizeColumn = sizeColumn();
        engineColumn = keyed(textColumn("引擎", 100, 80, TableMeta::engineText), "engine");
        createdColumn = keyed(textColumn("创建日期", 150, 140, TableMeta::createdText), "created");
        // ⭐ 本轮补的三列：Navicat 的表清单有「修改日期 / 排序规则 / 注释」。
        updatedColumn = keyed(textColumn("修改日期", 150, 140, TableMeta::updatedText), "updated");
        collationColumn = keyed(textColumn("排序规则", 120, 112,
                TableMeta::collationText, true), "collation");
        commentColumn = keyed(textColumn("注释", 180, 120,
                TableMeta::commentText, true, true), "comment");

        table.getColumns().setAll(List.of(
                nameColumn(),
                rowsColumn(),
                sizeColumn,
                engineColumn,
                createdColumn,
                updatedColumn,
                collationColumn,
                commentColumn));

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
        TableColumn<TableMeta, String> column = new TableColumn<>("名称");
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
                SVGPath icon = Icons.of(Icons.Icon.TABLE, Icons.SIZE_LABEL);
                icon.getStyleClass().addAll("adb-icon", "adb-tree__icon--table");
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
        TableColumn<TableMeta, String> column = new TableColumn<>("行");
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
        TableColumn<TableMeta, String> column = new TableColumn<>("数据长度");
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
            Label empty = new Label(rows.isEmpty() ? "还没有表" : "没有匹配的表");
            empty.getStyleClass().add("adb-muted");
            empty.setPadding(new Insets(24));
            cards.getChildren().add(empty);
            return;
        }
        for (TableMeta meta : filtered) {
            cards.getChildren().add(buildCard(meta));
        }
    }

    private VBox buildCard(TableMeta meta) {
        SVGPath icon = Icons.of(Icons.Icon.TABLE, Icons.SIZE_LABEL);
        icon.getStyleClass().addAll("adb-icon", "adb-tree__icon--table");
        Label name = new Label(meta.name());
        name.getStyleClass().add("adb-table-card__name");
        HBox title = new HBox(6, icon, name);
        title.setAlignment(Pos.CENTER_LEFT);

        // ⭐ 第十九轮 S-2：卡片不摆「—」。每一行只留真有值的片段；
        //    整行都是占位符就不渲染这一行（也不留空占位）。
        //    实测 SQLite 演示库上一张卡片 3 行有 2 行是纯破折号（§10.3 C2）。
        //    注意 rowsText() 为「—」时不能拼成「— 行」——那不是占位符了。
        String rowsFact = MetadataColumns.isPlaceholder(meta.rowsText())
                ? null : meta.rowsText().trim() + " 行";
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
            host.selectionChanged("未选择");
        } else if (selected.size() == 1) {
            TableMeta meta = selected.getFirst();
            String rowsText = meta.rows() == null ? "" : " · " + meta.rowsText() + " 行";
            host.selectionChanged("已选 " + meta.name() + rowsText);
        } else {
            host.selectionChanged("已选 " + selected.size() + " 个项目");
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
            status.accept("请先连上一个数据库");
            return;
        }
        host.newTable();
    }

    void openSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? "先选一张表" : "一次只能打开一张表");
            return;
        }
        if (host != null) {
            host.openTable(selected.getFirst().name());
        }
    }

    void designSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? "先选一张表" : "一次只能设计一张表");
            return;
        }
        if (host != null) {
            host.designTable(selected.getFirst().name());
        }
    }

    void renameSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? "先选一张表" : "一次只能重命名一张表");
            return;
        }
        if (host != null) {
            host.renameTable(selected.getFirst().name());
        }
    }

    void truncateSelected() {
        List<TableMeta> selected = selectedMetas();
        if (selected.size() != 1) {
            status.accept(selected.isEmpty() ? "先选一张表" : "一次只能清空一张表");
            return;
        }
        if (host != null) {
            host.truncateTable(selected.getFirst().name());
        }
    }

    void dropSelected() {
        List<String> names = selectedNames();
        if (names.isEmpty()) {
            status.accept("先选中要删除的表");
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
        applyColumnVisibility();
        updateSelectionState();
    }

    /**
     * ⭐ 第十九轮 S-1：按**已加载的数据**决定 6 个可选列显不显示。
     *
     * <p>只在「每一行都是占位符」时整列隐藏；有一行有真值就留着。
     * 判定依据是 {@link #rows}（全部已加载），不是 {@link #filtered}，
     * 所以搜索时列不会凭空消失/出现。核心列「名称 / 行」不参与判定。
     */
    private void applyColumnVisibility() {
        List<TableMeta> all = List.copyOf(rows);
        sizeColumn.setVisible(MetadataColumns.hasAnyValue(all, TableMeta::sizeText));
        engineColumn.setVisible(MetadataColumns.hasAnyValue(all, TableMeta::engineText));
        createdColumn.setVisible(MetadataColumns.hasAnyValue(all, TableMeta::createdText));
        updatedColumn.setVisible(MetadataColumns.hasAnyValue(all, TableMeta::updatedText));
        collationColumn.setVisible(MetadataColumns.hasAnyValue(all, TableMeta::collationText));
        commentColumn.setVisible(MetadataColumns.hasAnyValue(all, TableMeta::commentText));
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
            table.setPlaceholder(new Label("没有名称包含「" + query + "」的表"));
        }
    }

    private void updateSummary() {
        if (loading) {
            return;
        }
        if (search.getText() != null && !search.getText().isBlank()
                && filtered.size() != rows.size()) {
            summary.setText("显示 " + filtered.size() + " / " + rows.size() + " 张表");
        } else {
            summary.setText("共 " + rows.size() + " 张表");
        }
    }

    /** 重新统计（后台线程）。 */
    void reload() {
        if (loading || schemaService == null || session == null) {
            return;
        }
        loading = true;
        spinner.setVisible(true);
        summary.setText("正在统计…");
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
                applyColumnVisibility();
                updateSummary();
                updateSelectionState();
                if (finalError != null) {
                    summary.setText("读取失败：" + finalError);
                    status.accept("读取表清单失败：" + finalError);
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

    private Actions localActions() {
        return new Actions(
                new Action("table-list.new", "新建表…", "表清单", null, this::createTable),
                new Action("table-list.open", "打开表", "表清单", null, this::openSelected),
                new Action("table-list.design", "设计表…", "表清单", null, this::designSelected),
                new Action("table-list.rename", "重命名表…", "表清单", null, this::renameSelected),
                new Action("table-list.truncate", "清空表…", "表清单", null, this::truncateSelected),
                new Action("table-list.drop", "删除表…", "表清单", null, this::dropSelected),
                new Action("table-list.refresh", "刷新表清单", "表清单", null, this::reload),
                new Action("table-list.select-all", "全选", "表清单", null, this::selectAll),
                new Action("table-list.view-list", "列表视图", "表清单", null,
                        () -> showView("list")),
                new Action("table-list.view-grid", "网格视图", "表清单", null,
                        () -> showView("grid")));
    }
}
