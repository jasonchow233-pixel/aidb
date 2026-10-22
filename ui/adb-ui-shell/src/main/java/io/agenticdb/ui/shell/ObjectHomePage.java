package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    private final Supplier<DbSession> session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Consumer<SchemaService.SchemaObject> onOpen;
    private final Consumer<String> status;
    private final Runnable onConnect;

    private final Label summary = new Label();
    private final ObservableList<ObjectRow> rows = FXCollections.observableArrayList();
    private final FilteredList<ObjectRow> filtered = new FilteredList<>(rows, row -> true);
    private final TableView<ObjectRow> table = new TableView<>();
    private final HBox filters = new HBox(6);
    private final TextField search = new TextField();
    private final Button refresh = IconButtons.of(Icons.Icon.REFRESH, "刷新");
    private final VBox content = new VBox();

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
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen == null ? object -> { } : onOpen;
        this.status = status == null ? message -> { } : status;
        this.onConnect = onConnect;
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

        // ⚠️ refresh 必须进 header —— 此前它只被 new 出来、绑了动作、设了 id，
        //    却从未加入任何容器，于是「刷新」按钮在界面上根本不存在
        //    （截图审计 S2，docs/UI-AUDIT.md §2.1）。
        HBox header = new HBox(10, title, badge, spacer, summary, refresh);
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

        getChildren().addAll(header, body);
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
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("没有匹配的对象"));
        table.setFixedCellSize(rowHeight);

        // ⭐ 列与 Navicat 的「表清单」对齐：名称 / 类型 / 行 / 数据长度 / 引擎 /
        //    创建时间 / 修改时间 / 排序规则 / 注释。方言拿不到的列显示「—」。
        table.getColumns().setAll(List.of(
                nameColumn(),
                textColumn("类型", 64, row -> typeLabel(row.object().kind())),
                rowsColumn(),
                sizeColumn(),
                textColumn("引擎", 90, row -> row.meta() == null
                        ? "—" : row.meta().engineText()),
                textColumn("创建时间", 130, row -> row.meta() == null
                        ? "—" : row.meta().createdText()),
                textColumn("修改时间", 130, row -> row.meta() == null
                        ? "—" : row.meta().updatedText()),
                textColumn("排序规则", 110, row -> row.meta() == null
                        ? "—" : row.meta().collationText()),
                textColumn("注释", 160, row -> row.meta() == null
                        ? "—" : row.meta().commentText())));

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
    }

    private TableColumn<ObjectRow, String> nameColumn() {
        TableColumn<ObjectRow, String> column = new TableColumn<>("名称");
        column.setId("object-column-name");
        column.setPrefWidth(200);
        column.setMinWidth(120);
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
            String title, double width, java.util.function.Function<ObjectRow, String> value) {
        TableColumn<ObjectRow, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
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
        rebuildFilterChips();
        applyFilter();

        if (all.isEmpty()) {
            showEmpty(EmptyState.of(Icons.Icon.DATABASE,
                    "这个连接里还没有对象",
                    "数据库是空的。可以先在查询页执行建表语句，"
                            + "或从侧栏新建一个表。",
                    "打开查询页", () -> status.accept("用查询页执行 CREATE TABLE 建第一张表")));
            summary.setText("共 0 个对象");
            return;
        }
        showContent();
        summary.setText(summaryText(all));
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
