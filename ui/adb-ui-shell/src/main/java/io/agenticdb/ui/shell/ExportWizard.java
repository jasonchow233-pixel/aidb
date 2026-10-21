package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.TableExporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 导出向导。
 *
 * <p>⛔ 按钮放在<b>内容区</b>，不放 Dialog 按钮栏 ——
 * {@code DialogPane.createButton} 会给按钮栏里的每个按钮挂
 * {@code setResultAndClose}，点一下就把整个对话框关掉（见架构文档 §7.1）。
 *
 * <p>导出在后台线程跑：整表导出可能是几百万行，不能卡住界面。
 */
final class ExportWizard {

    /** 导出范围。 */
    enum Scope {
        CURRENT_PAGE("当前页"),
        SELECTED("选中行"),
        WHOLE_TABLE("整表");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final DbSession session;
    private final String tableName;
    private final List<String> columns;
    private final Executor dbExecutor;
    private final Consumer<String> status;

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(10);
    private final ComboBox<Scope> scopeBox = new ComboBox<>();
    private final ComboBox<ExportFormat> formatBox = new ComboBox<>();
    private final TextField targetField = new TextField();
    private final TextArea log = new TextArea();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label summary = new Label();
    private final Button exportButton = new Button("开始导出");
    private final Button closeButton = new Button("关闭");

    private volatile boolean running;

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status) {
        this.session = session;
        this.tableName = tableName;
        this.columns = columns;
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;

        scopeBox.getItems().setAll(Scope.values());
        scopeBox.getSelectionModel().select(defaultScope);
        scopeBox.setId("export-scope");

        formatBox.getItems().setAll(ExportFormat.values());
        formatBox.getSelectionModel().select(ExportFormat.CSV);
        formatBox.setId("export-format");

        targetField.setId("export-target");
        targetField.setText(defaultTarget == null ? "" : defaultTarget.toString());
        targetField.setPromptText("导出到的文件");

        log.setId("export-log");
        log.setEditable(false);
        log.setPrefRowCount(6);
        VBox.setVgrow(log, Priority.ALWAYS);

        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        summary.setId("export-summary");
        summary.setText("选好范围与格式，点「开始导出」");
        summary.getStyleClass().add("adb-muted");

        exportButton.setId("export-start");
        exportButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        exportButton.setOnAction(event -> start());

        closeButton.setId("export-close");
        closeButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        closeButton.setOnAction(event -> {
            if (!running) {
                close();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, exportButton, closeButton, spacer, spinner);
        actions.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(
                field("范围", scopeBox),
                field("格式", formatBox),
                field("目标文件", targetField),
                summary,
                log,
                actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle("导出 " + tableName);
        dialog.setHeaderText("选择范围与格式");
        // ⛔ 不放任何按钮到按钮栏 —— 放了会被 setResultAndClose 一键关掉。
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
    }

    /** 打开向导（模态）。 */
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

    /** 供门禁直接驱动。 */
    VBox content() {
        return content;
    }

    private HBox field(String label, javafx.scene.Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("adb-muted");
        caption.setMinWidth(64);
        HBox row = new HBox(8, caption, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    /** 开始导出（在后台线程跑）。 */
    void start() {
        if (running) {
            return;
        }
        String text = targetField.getText() == null ? "" : targetField.getText().trim();
        if (text.isBlank()) {
            summary.setText("请先选一个目标文件");
            return;
        }
        Path target = Path.of(text);
        ExportFormat format = formatBox.getValue();
        Scope scope = scopeBox.getValue();

        running = true;
        exportButton.setDisable(true);
        spinner.setVisible(true);
        summary.setText("正在导出 " + tableName + " …");

        ExportOptions options = new ExportOptions(format, true, 500, 0, true);
        dbExecutor.execute(() -> {
            String message;
            try {
                long rows = run(target, options);
                message = "已导出 " + rows + " 行 → " + target;
            } catch (Exception e) {
                message = "导出失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String finalMessage = message;
            Platform.runLater(() -> {
                running = false;
                exportButton.setDisable(false);
                spinner.setVisible(false);
                summary.setText(finalMessage);
                log.appendText(finalMessage + "\n");
                status.accept(finalMessage);
            });
        });
    }

    private long run(Path target, ExportOptions options) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = new BufferedWriter(
                Files.newBufferedWriter(target, StandardCharsets.UTF_8))) {
            return TableExporter.export(session, tableName, columns, options, writer);
        }
    }

    /** 超过导出上限时要不要确认。 */
    static boolean requiresConfirmation(long rowCount) {
        return QueryLimits.exportRequiresConfirmation(rowCount);
    }
}
