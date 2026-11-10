package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ⭐ P3 轻量 Data Profiling（按 P0 默认走向落地 Q3 的轻量版，不做完整分析引擎）。
 *
 * <p>做法刻意保持「轻」：只跑<b>一条</b> {@code SELECT * FROM t LIMIT 200}，
 * 在内存里对每列算出 null 数 / null 率 / 去重数 / 最小·最大·均值（数值列）/
 * 样例值。对宽表也只是一条查询，代价可控；代价是数字基于采样而非全表 ——
 * 这正是「轻量版」的定位，页面文案也如实说明是基于样本。
 */
public final class DataProfilingPage extends VBox {

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    /** ⭐ 2026-11-10：网格那边指定要画像的表（可为空 = 让用户自己挑）。 */
    private final String preselectedTable;

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final ComboBox<String> tablePicker = new ComboBox<>();
    private final Button run = new Button();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label summary = new Label();
    private final VBox body = new VBox(8);

    public DataProfilingPage(DbSession session, SchemaService schemaService, Executor dbExecutor) {
        this(session, schemaService, dbExecutor, null);
    }

    /**
     * ⭐ 2026-11-10：带「预选表」的构造 —— 数据网格工具条那颗「数据画像」按钮
     * 走这条：它知道当前网格正开着哪张表，不该让用户再在下拉框里挑一次。
     *
     * <p>预选的表若不在清单里（例如网格开的是视图），就退回「让用户自己挑」，
     * 而不是把一个查不存在的表当成有效输入。
     */
    public DataProfilingPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                             String preselectedTable) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.preselectedTable = preselectedTable;
        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("adb-page__body");

        title.setId("profiling-title");
        title.getStyleClass().add("adb-page__title");
        title.setText(I18n.t("tool.profiling.title"));

        subtitle.setId("profiling-subtitle");
        subtitle.getStyleClass().add("adb-muted");
        subtitle.setText(I18n.t("tool.profiling.subtitle",
                session == null ? "—" : session.dialect().displayName()));

        tablePicker.setId("profiling-table");
        tablePicker.setPromptText(I18n.t("tool.profiling.pickTable"));
        tablePicker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tablePicker, Priority.ALWAYS);

        run.setId("profiling-run");
        run.setText(I18n.t("tool.profiling.run"));
        run.setDisable(true);
        run.setOnAction(event -> profile(tablePicker.getValue()));

        spinner.setMaxSize(18, 18);
        spinner.setVisible(false);

        summary.setId("profiling-summary");
        summary.getStyleClass().add("adb-muted");
        summary.setVisible(false);

        HBox controls = new HBox(8, tablePicker, run);
        controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        body.setId("profiling-body");
        getChildren().addAll(title, subtitle, controls, spinner, summary, body);

        if (session == null) {
            message(I18n.t("tool.profiling.noSession"));
            return;
        }
        loadTables();
    }

    private void loadTables() {
        dbExecutor.execute(() -> {
            try {
                List<String> tables = new ArrayList<>();
                for (SchemaObject o : schemaService.listObjects(session)) {
                    if (o.kind() == ObjectKind.TABLE) {
                        tables.add(o.name());
                    }
                }
                javafx.application.Platform.runLater(() -> {
                    tablePicker.getItems().setAll(tables);
                    run.setDisable(tables.isEmpty());
                    if (tables.isEmpty()) {
                        message(I18n.t("tool.profiling.noTables"));
                        return;
                    }
                    // ⭐ 2026-11-10：网格指定了表就替他选好并直接跑 ——
                    //    用户点的是「画像这张表」，再让他挑一次是白绕一圈。
                    //    表不在清单里（网格开的是视图等）→ 退回「自己挑」，不硬塞一个查不存在的表。
                    if (preselectedTable != null && tables.contains(preselectedTable)) {
                        tablePicker.setValue(preselectedTable);
                        profile(preselectedTable);
                    }
                });
            } catch (SQLException e) {
                javafx.application.Platform.runLater(() ->
                        message(I18n.t("tool.profiling.failed", messageOf(e))));
            }
        });
    }

    private void profile(String table) {
        if (table == null || table.isBlank()) {
            return;
        }
        spinner.setVisible(true);
        summary.setVisible(false);
        body.getChildren().clear();
        String sql = session.dialect().plainPageSql(table, ProfilingStats.SAMPLE_LIMIT, 0);
        dbExecutor.execute(() -> {
            try (var statement = session.connection().createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                List<String> headers = new ArrayList<>();
                List<String> typeNames = new ArrayList<>();
                boolean[] numericFlags = new boolean[columns];
                for (int c = 1; c <= columns; c++) {
                    headers.add(meta.getColumnLabel(c));
                    typeNames.add(meta.getColumnTypeName(c));
                    numericFlags[c - 1] = isNumeric(meta.getColumnType(c));
                }
                // ⭐ 统计内核在 ProfilingStats（纯函数，可独立门禁）——
                //    这里只负责把 JDBC 读出来的东西整成它要的形状。
                List<List<String>> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>(columns);
                    for (int c = 1; c <= columns; c++) {
                        row.add(rs.getString(c));
                    }
                    rows.add(row);
                }
                final int sampledRows = rows.size();
                List<ProfilingStats.Column> stats =
                        ProfilingStats.summarize(headers, typeNames, numericFlags, rows);
                javafx.application.Platform.runLater(() -> renderGrid(stats, sampledRows));
            } catch (SQLException e) {
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    message(I18n.t("tool.profiling.failed", messageOf(e)));
                });
            }
        });
    }

    private void renderGrid(List<ProfilingStats.Column> stats, int sampledRows) {
        spinner.setVisible(false);
        body.getChildren().clear();
        if (stats.isEmpty()) {
            message(I18n.t("tool.profiling.noColumns"));
            return;
        }
        summary.setVisible(true);
        summary.setText(I18n.t("tool.profiling.rows", sampledRows));

        List<ObservableList<String>> rows = new ArrayList<>();
        for (ProfilingStats.Column column : stats) {
            ObservableList<String> row = FXCollections.observableArrayList();
            row.add(column.name());
            row.add(column.typeName());
            row.add(String.valueOf(column.total()));
            row.add(String.valueOf(column.nulls()));
            row.add(ProfilingStats.nullPercentText(column.nullPercent()));
            row.add(String.valueOf(column.distinct()));
            row.add(ProfilingStats.cell(column.min()));
            row.add(ProfilingStats.cell(column.max()));
            row.add(ProfilingStats.cell(column.average()));
            row.add(String.join(", ", column.samples()));
            rows.add(row);
        }

        TableView<ObservableList<String>> grid = new TableView<>();
        grid.getStyleClass().add("adb-grid");
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        // 固定两列头：列名 + 类型
        String[] fixed = {I18n.t("tool.profiling.column"), I18n.t("tool.profiling.type")};
        for (int c = 0; c < 2; c++) {
            final int index = c;
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(fixed[c]);
            column.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().get(index)));
            grid.getColumns().add(column);
        }
        String[] metrics = {
                I18n.t("tool.profiling.sampleRows"), I18n.t("tool.profiling.nulls"),
                I18n.t("tool.profiling.nullPct"), I18n.t("tool.profiling.distinct"),
                I18n.t("tool.profiling.min"), I18n.t("tool.profiling.max"),
                I18n.t("tool.profiling.avg"), I18n.t("tool.profiling.samples")
        };
        for (int c = 0; c < metrics.length; c++) {
            final int index = c + 2;
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(metrics[c]);
            column.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().get(index)));
            grid.getColumns().add(column);
        }
        grid.getItems().setAll(rows);
        body.getChildren().add(grid);
    }

    private void message(String text) {
        spinner.setVisible(false);
        summary.setVisible(false);
        body.getChildren().clear();
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("adb-muted");
        body.getChildren().add(label);
    }

    private static boolean isNumeric(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL, java.sql.Types.INTEGER,
                 java.sql.Types.SMALLINT, java.sql.Types.BIGINT, java.sql.Types.TINYINT,
                 java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE,
                 java.sql.Types.BIT, java.sql.Types.BOOLEAN -> true;
            default -> false;
        };
    }

    private static String messageOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
