package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 导入 / 导出的入口对话框。
 *
 * <p>侧栏的「导入 / 导出」以前是空壳页；现在它真的能干活了：
 * 选一张表，然后导入或导出。
 *
 * <p>⛔ 按钮在内容区（架构文档 §7.1 的 Dialog 按钮陷阱）。
 */
final class TransferHub {

    private final DbSession session;
    private final List<String> tables;
    private final Executor dbExecutor;
    private final Consumer<String> status;

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(10);
    private final ComboBox<String> tableBox = new ComboBox<>();
    private final Button exportButton = IconButtons.of(Icons.Icon.EXPORT, "导出…");
    private final Button importButton = IconButtons.of(Icons.Icon.IMPORT, "导入…");
    private final Button closeButton = new Button("关闭");

    TransferHub(DbSession session, List<String> tables, Executor dbExecutor,
                Consumer<String> status) {
        this.session = session;
        this.tables = tables == null ? List.of() : tables;
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;

        tableBox.setId("transfer-table");
        tableBox.getItems().setAll(this.tables);
        if (!this.tables.isEmpty()) {
            tableBox.getSelectionModel().selectFirst();
        }

        exportButton.setId("transfer-export");
        exportButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        exportButton.setDisable(this.tables.isEmpty());
        exportButton.setOnAction(event -> {
            close();
            openExport();
        });

        importButton.setId("transfer-import");
        importButton.getStyleClass().addAll("adb-button");
        importButton.setDisable(this.tables.isEmpty());
        importButton.setOnAction(event -> {
            close();
            openImport();
        });

        closeButton.setId("transfer-close");
        closeButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        closeButton.setOnAction(event -> close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, exportButton, importButton, closeButton, spacer);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(this.tables.isEmpty()
                ? "这个连接里还没有可用的表"
                : "选一张表，然后导入或导出");
        hint.getStyleClass().add("adb-muted");

        content.getChildren().addAll(hint, tableBox, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle("导入 / 导出");
        dialog.setHeaderText("选一张表");
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
    }

    void show(javafx.stage.Window owner) {
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showAndWait();
    }

    void close() {
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }

    VBox content() {
        return content;
    }

    private void openExport() {
        String table = tableBox.getValue();
        if (table == null) {
            return;
        }
        List<ColumnDef> columns = describe(table);
        ExportWizard wizard = new ExportWizard(session, table,
                columns.stream().map(ColumnDef::name).toList(),
                ExportWizard.Scope.WHOLE_TABLE, defaultTarget(table), dbExecutor, status);
        wizard.show(owner());
    }

    private void openImport() {
        String table = tableBox.getValue();
        if (table == null) {
            return;
        }
        List<ColumnDef> columns = describe(table);
        ImportWizard wizard = new ImportWizard(session, table, columns, tables, null,
                dbExecutor, status);
        wizard.show(owner());
    }

    private List<ColumnDef> describe(String table) {
        try {
            return new io.agenticdb.introspect.SchemaService()
                    .describe(session, table).columns();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Path defaultTarget(String table) {
        return Path.of(System.getProperty("user.home"), table + ".csv");
    }

    private javafx.stage.Window owner() {
        return dialog.getOwner();
    }
}
