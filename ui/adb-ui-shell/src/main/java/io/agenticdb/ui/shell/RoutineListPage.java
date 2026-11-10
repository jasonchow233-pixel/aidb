package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ⭐ 2026-11-10：例程清单页 —— 功能区「函数」格的**真实落点**。
 *
 * <p>此前那格落的是一句「本版本还没有存储函数的编辑器」的说明页，而例程定义体
 * （{@link RoutinePage}）其实早已交付、入口只在左侧树的双击上 —— 用户从功能区点
 * 「函数」读到的是「没有」，从树里点却是好的，两条路自相矛盾。
 * 按「要么接要么删」的原则，这里给它接上真页面：列出当前连接里的函数 / 存储过程 /
 * 调度事件，双击任一行打开该例程的定义体标签页。
 *
 * <p>形态与 {@link TableListPage} 同形（表格 + 搜索框 + 空态），因为它就是同一类东西：
 * 一张「这张库里有哪些例程」的清单表。
 */
final class RoutineListPage extends VBox {

    /** 一行例程。kind 决定它是函数、过程还是调度事件。 */
    record Row(String name, ObjectKind kind, String schema) {
    }

    /** 例程清单的数据来源 —— 抽成接口是为了让「哪些对象算例程」这条规则能被独立门禁覆盖。 */
    @FunctionalInterface
    interface Source {
        List<SchemaObject> load() throws SQLException;
    }

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    /** 点开某一行 → 外壳据此打开定义体标签页（例程名 + 类型）。 */
    private final java.util.function.BiConsumer<String, ObjectKind> onOpen;
    /** 例程清单的数据来源。 */
    private final Source source;

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final TextField search = new TextField();
    private final Label error = new Label();
    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> filtered = new FilteredList<>(rows, row -> true);

    RoutineListPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                    java.util.function.BiConsumer<String, ObjectKind> onOpen) {
        this(session, schemaService, dbExecutor, onOpen, null);
    }

    /**
     * ⭐ 2026-11-10：可注入数据来源的构造 —— 门禁用它把「哪些对象算例程」
     * 这条规则单独覆盖，不必依赖某个驱动恰好支持 {@code getFunctions}
     * （SQLite 的驱动直接抛 {@code SQLFeatureNotSupportedException}）。
     */
    RoutineListPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                    java.util.function.BiConsumer<String, ObjectKind> onOpen, Source source) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen;
        // ⚠️ 注入了数据源就以它为准 —— 会话只用来显示副标题里的方言名，
        //    「能不能列清单」这件事由数据源决定，不由会话决定。
        boolean injected = source != null;
        this.source = injected ? source : () -> schemaService.listObjects(session);

        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("adb-page__body");

        title.setId("routine-list-title");
        title.getStyleClass().add("adb-page__title");
        title.setText(I18n.t("tool.routines.title"));

        subtitle.setId("routine-list-subtitle");
        subtitle.getStyleClass().add("adb-muted");
        subtitle.setText(I18n.t("tool.routines.subtitle",
                session == null ? "—" : session.dialect().displayName()));

        search.setId("routine-list-search");
        search.setPromptText(I18n.t("tool.routines.search"));
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(search, Priority.ALWAYS);
        // 搜索只作用本页（与表清单页同口径）。
        search.textProperty().addListener((obs, oldValue, newValue) ->
                filtered.setPredicate(row -> newValue == null || newValue.isBlank()
                        || row.name().toLowerCase(java.util.Locale.ROOT)
                        .contains(newValue.toLowerCase(java.util.Locale.ROOT))));

        error.setId("routine-list-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);

        table.setId("routine-list-table");
        // ⚠️ 表格的视觉全在 .adb-grid 上（列头 / 行高 / 斑马纹 / 选中态）——
        //    自造一个 .adb-table 会退回 JavaFX 默认外观，与旁边那些表不是一套东西。
        table.getStyleClass().add("adb-grid");
        table.setItems(filtered);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        // U24：空态不是一行灰字 —— 图标说清「这是空，不是坏了」，说明给出路。
        //    这里给「清空筛选」那个出口：搜不到时最常见的成因就是筛选词太窄。
        table.setPlaceholder(EmptyState.of(Icons.Icon.GROUP_FUNCTIONS,
                I18n.t("tool.routines.empty"),
                I18n.t("tool.routines.emptyDetail"),
                I18n.t("tool.routines.clearSearch"), search::clear));
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().add(nameColumn());
        table.getColumns().add(kindColumn());
        table.getColumns().add(schemaColumn());

        // 双击或回车打开定义体 —— 与左侧树里双击例程节点同一条路径。
        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelected();
            }
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelected();
                event.consume();
            }
        });

        HBox controls = new HBox(8, search);
        controls.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(title, subtitle, controls, error, table);

        // ⚠️ 只有「靠会话现取清单」这条路才需要会话；注入了数据源时照常列
        //    （门禁就是靠注入来覆盖筛选与点开这两段逻辑的）。
        if (session == null && !injected) {
            fail(I18n.t("routine.noSession"));
            return;
        }
        load();
    }

    private TableColumn<Row, String> nameColumn() {
        TableColumn<Row, String> column = new TableColumn<>(I18n.t("column.name"));
        column.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().name()));
        column.setId("routine-list-column-name");
        column.setSortable(true);
        return column;
    }

    private TableColumn<Row, String> kindColumn() {
        TableColumn<Row, String> column = new TableColumn<>(I18n.t("tool.routines.kind"));
        column.setCellValueFactory(cell ->
                new SimpleStringProperty(kindLabel(cell.getValue().kind())));
        column.setId("routine-list-column-kind");
        column.setSortable(true);
        return column;
    }

    private TableColumn<Row, String> schemaColumn() {
        TableColumn<Row, String> column = new TableColumn<>(I18n.t("tool.routines.schema"));
        column.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().schema()));
        column.setId("routine-list-column-schema");
        column.setSortable(true);
        return column;
    }

    /** 类型列的中文/英文标签。 */
    private static String kindLabel(ObjectKind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case FUNCTION -> I18n.t("object.kind.function");
            case PROCEDURE -> I18n.t("object.kind.procedure");
            case EVENT -> I18n.t("routine.kind.event");
            case TRIGGER -> I18n.t("object.kind.trigger");
            default -> kind.name();
        };
    }

    private void openSelected() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null || onOpen == null) {
            return;
        }
        onOpen.accept(row.name(), row.kind());
    }

    /**
     * ⭐ 哪些对象算「例程」—— 纯函数，门禁直接覆盖这条规则。
     *
     * <p>函数 / 存储过程 / 调度事件三类进清单；表、视图、触发器不进
     * （触发器的定义体归结构页看，不是例程页的事）。
     * 按名字排序（不分大小写），与左侧对象树里的分组顺序一致。
     */
    static List<Row> rowsOf(List<SchemaObject> objects) {
        List<Row> found = new ArrayList<>();
        for (SchemaObject object : objects == null ? List.<SchemaObject>of() : objects) {
            ObjectKind kind = object.kind();
            if (kind == ObjectKind.FUNCTION || kind == ObjectKind.PROCEDURE
                    || kind == ObjectKind.EVENT) {
                found.add(new Row(object.name(), kind, object.schema()));
            }
        }
        found.sort(java.util.Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    private void load() {
        if (dbExecutor == null) {
            fail(I18n.t("routine.noExecutor"));
            return;
        }
        dbExecutor.execute(() -> {
            try {
                List<Row> found = rowsOf(source.load());
                javafx.application.Platform.runLater(() -> {
                    rows.setAll(found);
                    error.setVisible(false);
                });
            } catch (SQLException ex) {
                String detail = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName() : ex.getMessage();
                javafx.application.Platform.runLater(() -> fail(I18n.t("tool.routines.failed", detail)));
            }
        });
    }

    private void fail(String reason) {
        error.setText(reason);
        error.setVisible(true);
    }

    /** 供测试读回当前过滤后的行数（只能在 FX 线程上调）。 */
    int visibleRowCount() {
        return filtered.size();
    }

    /** 供测试直接打开某一行（选行 + 触发打开，只在 FX 线程上调）。 */
    void openRow(int index) {
        if (index >= 0 && index < rows.size()) {
            table.getSelectionModel().select(rows.get(index));
            openSelected();
        }
    }
}
