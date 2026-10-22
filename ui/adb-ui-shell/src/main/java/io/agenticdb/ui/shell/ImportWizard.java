package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.transfer.ImportFormat;
import io.agenticdb.transfer.ImportOptions;
import io.agenticdb.transfer.TableImporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 导入向导：文件 → 目标表 → 字段映射 → 预览 → 执行。
 *
 * <p>⛔ 按钮放在内容区（同 {@link ExportWizard}，理由见架构文档 §7.1）。
 *
 * <p>预览读前若干行，**不把整个文件读进内存**。
 */
final class ImportWizard {

    /** 预览的行数。 */
    private static final int PREVIEW_ROWS = 20;

    private final DbSession session;
    private final String tableName;
    private final List<ColumnDef> columns;
    private final Executor dbExecutor;
    private final Consumer<String> status;

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(10);
    private final TextField fileField = new TextField();
    private final ComboBox<ImportFormat> formatBox = new ComboBox<>();
    private final ComboBox<String> tableBox = new ComboBox<>();
    private final ListView<String> mappingView = new ListView<>();
    private final TextArea preview = new TextArea();
    private final Label summary = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button importButton = new Button("开始导入");
    private final Button closeButton = new Button("关闭");

    private volatile boolean running;

    ImportWizard(DbSession session, String tableName, List<ColumnDef> columns,
                 List<String> availableTables, Path initialFile, Executor dbExecutor,
                 Consumer<String> status) {
        this.session = session;
        this.tableName = tableName;
        this.columns = columns;
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;

        fileField.setId("import-file");
        fileField.setText(initialFile == null ? "" : initialFile.toString());
        fileField.setPromptText("要导入的文件");

        formatBox.setId("import-format");
        formatBox.getItems().setAll(ImportFormat.values());
        formatBox.getSelectionModel().select(ImportFormat.CSV);

        tableBox.setId("import-table");
        tableBox.getItems().setAll(availableTables == null ? List.of() : availableTables);
        if (tableName != null) {
            tableBox.getSelectionModel().select(tableName);
        }

        mappingView.setId("import-mapping");
        mappingView.setPrefHeight(120);

        preview.setId("import-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(8);
        VBox.setVgrow(preview, Priority.ALWAYS);

        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        summary.setId("import-summary");
        summary.setText("选好文件与目标表后点「开始导入」");
        summary.getStyleClass().add("adb-muted");

        importButton.setId("import-start");
        importButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        importButton.setOnAction(event -> start());

        closeButton.setId("import-close");
        closeButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        closeButton.setOnAction(event -> {
            if (!running) {
                close();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, importButton, closeButton, spacer, spinner);
        actions.setAlignment(Pos.CENTER_LEFT);

        content.getChildren().addAll(
                field("文件", fileField),
                field("格式", formatBox),
                field("目标表", tableBox),
                new Label("字段映射"),
                mappingView,
                new Label("预览"),
                preview,
                summary,
                actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle("导入数据");

        // ⭐ V-11：弹窗宽度走三档规范（DialogSizes）。

        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);

        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        dialog.setHeaderText("文件 → 目标表 → 字段映射 → 预览 → 执行");
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
    }

    void show(javafx.stage.Window owner) {
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogStyles.apply(dialog, owner);
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

    private HBox field(String label, javafx.scene.Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("adb-muted");
        caption.setMinWidth(64);
        HBox row = new HBox(8, caption, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    /** 读文件的前若干行做预览，并推断字段映射。 */
    void refreshPreview() {
        String text = fileField.getText() == null ? "" : fileField.getText().trim();
        if (text.isBlank()) {
            summary.setText("请先选一个文件");
            return;
        }
        Path file = Path.of(text);
        if (!Files.isRegularFile(file)) {
            summary.setText("文件不存在：" + file);
            return;
        }
        ImportFormat format = formatBox.getValue();
        try (Reader reader = new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8);
             PushbackReader pushback = new PushbackReader(reader, 8)) {

            List<String> header = io.agenticdb.transfer.CsvCodec.readRow(pushback,
                    format.delimiter());
            List<String> previewLines = new ArrayList<>();
            List<String> row;
            int count = 0;
            while (count < PREVIEW_ROWS && (row = io.agenticdb.transfer.CsvCodec.readRow(pushback,
                    format.delimiter())) != null) {
                previewLines.add(String.join(" | ", row));
                count++;
            }

            preview.setText(String.join("\n", previewLines));
            List<String> mapping = resolveMappingText(header);
            mappingView.getItems().setAll(mapping);
            summary.setText("预览 " + count + " 行 · " + mapping.size() + " 个字段已映射");
        } catch (IOException e) {
            summary.setText("读取失败：" + e.getMessage());
        }
    }

    private List<String> resolveMappingText(List<String> header) {
        List<String> lines = new ArrayList<>();
        if (header == null) {
            return lines;
        }
        for (ColumnDef column : columns) {
            String matched = null;
            for (String name : header) {
                if (column.name().equalsIgnoreCase(name.trim())) {
                    matched = name.trim();
                    break;
                }
            }
            lines.add(column.name() + "  ←  " + (matched == null ? "（未匹配）" : matched));
        }
        return lines;
    }

    /** 开始导入（后台线程）。 */
    void start() {
        if (running) {
            return;
        }
        String text = fileField.getText() == null ? "" : fileField.getText().trim();
        String table = tableBox.getValue();
        if (text.isBlank() || table == null) {
            summary.setText("请先选好文件与目标表");
            return;
        }
        Path file = Path.of(text);
        ImportFormat format = formatBox.getValue();
        List<String> targetColumns = columns.stream().map(ColumnDef::name).toList();

        running = true;
        importButton.setDisable(true);
        spinner.setVisible(true);
        summary.setText("正在导入 …");

        dbExecutor.execute(() -> {
            String message;
            try (Reader reader = new InputStreamReader(Files.newInputStream(file),
                    StandardCharsets.UTF_8)) {
                TableImporter.ImportReport report = TableImporter.importRows(session, table,
                        targetColumns, ImportOptions.of(format), reader);
                message = "已导入 " + report.inserted() + " 行"
                        + (report.skipped() > 0 ? "，跳过 " + report.skipped() + " 行" : "")
                        + (report.hasErrors() ? "（首条错误：" + report.errors().getFirst() + "）" : "");
            } catch (Exception e) {
                message = "导入失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String finalMessage = message;
            Platform.runLater(() -> {
                running = false;
                importButton.setDisable(false);
                spinner.setVisible(false);
                summary.setText(finalMessage);
                status.accept(finalMessage);
            });
        });
    }
}
