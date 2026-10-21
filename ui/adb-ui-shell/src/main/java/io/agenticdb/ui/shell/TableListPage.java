package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DbSession;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 表清单页 —— Navicat 的「表清单」那一层。
 *
 * <p>以前对象树里只有表名，用户想知道「这张表有多大、有多少行」只能自己去查。
 * 现在有一张表：名称 / 行数 / 数据长度 / 引擎 / 创建日期。
 *
 * <p>⛔ 统计行数可能要全表扫描，所以**在后台线程**做，界面上先出表名。
 */
final class TableListPage extends VBox {

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Consumer<String> onOpen;
    private final Consumer<String> status;

    private final TableView<TableMeta> table = new TableView<>();
    /** 密度档位 → 行高（不写死）。 */
    private int rowHeight = io.agenticdb.ui.RowHeights.of("normal");

    /** 密度档位 —— 表清单也要跟。 */
    public void setDensity(String density) {
        rowHeight = io.agenticdb.ui.RowHeights.of(density);
        table.setFixedCellSize(rowHeight);
    }

    /** 表格（供门禁断言密度作用到了它）。 */
    TableView<TableMeta> table() {
        return table;
    }
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label summary = new Label();

    TableListPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                  Consumer<String> onOpen, Consumer<String> status) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.onOpen = onOpen == null ? name -> { } : onOpen;
        this.status = status == null ? message -> { } : status;

        table.setId("table-list");
        table.getStyleClass().add("adb-table-list");
        table.setEditable(false);
        VBox.setVgrow(table, Priority.ALWAYS);

        table.getColumns().add(column("名称", "name", 240));
        table.getColumns().add(column("行数", "rowsText", 100));
        table.getColumns().add(column("数据长度", "sizeText", 110));
        table.getColumns().add(column("引擎", "engineText", 120));
        table.getColumns().add(column("创建日期", "createdText", 180));

        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelected();
            }
        });

        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        summary.setId("table-list-summary");
        summary.getStyleClass().add("adb-muted");

        Button refresh = new Button("刷新");
        refresh.setId("table-list-refresh");
        refresh.getStyleClass().addAll("adb-button");
        refresh.setOnAction(event -> reload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, refresh, spacer, spinner);
        actions.setAlignment(Pos.CENTER_LEFT);

        table.setFixedCellSize(rowHeight);
        getChildren().addAll(summary, table, actions);
        setPadding(new Insets(12));
        getStyleClass().add("adb-page__body");
        reload();
    }

    private TableColumn<TableMeta, String> column(String title, String property, double width) {
        TableColumn<TableMeta, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                text(cell.getValue(), property)));
        column.setPrefWidth(width);
        return column;
    }

    private static String text(TableMeta meta, String property) {
        return switch (property) {
            case "name" -> meta.name();
            case "rowsText" -> meta.rowsText();
            case "sizeText" -> meta.sizeText();
            case "engineText" -> meta.engineText();
            case "createdText" -> meta.createdText();
            default -> "";
        };
    }

    /** 重新统计（后台线程）。 */
    void reload() {
        spinner.setVisible(true);
        summary.setText("正在统计…");
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
                spinner.setVisible(false);
                table.getItems().setAll(finalMetas);
                if (finalError != null) {
                    summary.setText("读取失败：" + finalError);
                    status.accept("读取表清单失败：" + finalError);
                } else {
                    summary.setText("共 " + finalMetas.size() + " 张表");
                }
            });
        });
    }

    private void openSelected() {
        TableMeta meta = table.getSelectionModel().getSelectedItem();
        if (meta == null) {
            status.accept("先选一张表");
            return;
        }
        onOpen.accept(meta.name());
    }
}
