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

        // Navicat 口径（§6.7）：关闭在左、主动作在最右，进度指示留在最左。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spinner, spacer, closeButton, importButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

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

        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。

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
        DialogStyles.apply(dialog, owner, "import-wizard");
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
        try {
            List<String> header;
            List<String> previewLines = new ArrayList<>();
            if (format.binary()) {
                // ⭐ P6-3：xlsx 走 SAX 预览，同样只读前若干行。
                List<List<String>> rows = io.agenticdb.transfer.XlsxCodec.preview(
                        file, PREVIEW_ROWS + 1);
                header = rows.isEmpty() ? null : rows.getFirst();
                for (List<String> row : rows.subList(Math.min(1, rows.size()), rows.size())) {
                    previewLines.add(String.join(" | ", row));
                }
            } else {
                try (Reader reader = new InputStreamReader(Files.newInputStream(file),
                        StandardCharsets.UTF_8);
                     PushbackReader pushback = new PushbackReader(reader, 8)) {
                    header = io.agenticdb.transfer.CsvCodec.readRow(pushback,
                            format.delimiter());
                    List<String> row;
                    int count = 0;
                    while (count < PREVIEW_ROWS && (row = io.agenticdb.transfer.CsvCodec
                            .readRow(pushback, format.delimiter())) != null) {
                        previewLines.add(String.join(" | ", row));
                        count++;
                    }
                }
            }

            preview.setText(String.join("\n", previewLines));
            List<String> mapping = resolveMappingText(header);
            mappingView.getItems().setAll(mapping);
            summary.setText("预览 " + previewLines.size() + " 行 · "
                    + mapping.size() + " 个字段已映射");
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

        running = true;
        importButton.setDisable(true);
        spinner.setVisible(true);
        summary.setText("正在导入 …");

        dbExecutor.execute(() -> {
            String message;
            TableImporter.ImportReport report = null;
            try {
                // ⭐ P6-1：带上目标列类型 —— 数值 / 布尔 / 日期先转换，坏值进报告。
                // ⭐ P6-3：xlsx 走 SAX 流式路径，其余格式按文本行读取。
                report = format.binary()
                        ? TableImporter.importXlsx(session, table, columns,
                                ImportOptions.of(format), file)
                        : importText(file, table, columns, format);
                message = "已导入 " + report.inserted() + " 行"
                        + (report.skipped() > 0 ? "，跳过 " + report.skipped() + " 行" : "")
                        + (report.hasErrors() ? "（首条错误：" + report.errors().getFirst() + "）" : "");
            } catch (Exception e) {
                message = "导入失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String finalMessage = message;
            TableImporter.ImportReport finalReport = report;
            Platform.runLater(() -> {
                running = false;
                importButton.setDisable(false);
                spinner.setVisible(false);
                summary.setText(finalMessage);
                status.accept(finalMessage);
                // ⭐ P2-2：有坏行就给一份可复制的汇总；全部成功时只用状态行，不弹窗打扰。
                if (finalReport != null
                        && (finalReport.hasErrors() || finalReport.skipped() > 0)) {
                    showReport(finalReport);
                }
            });
        });
    }

    /** 文本格式（CSV / TSV）的导入：Reader → 类型转换 → 批量写入。 */
    private TableImporter.ImportReport importText(Path file, String table,
                                                  List<ColumnDef> columns, ImportFormat format)
            throws Exception {
        try (Reader reader = new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8)) {
            return TableImporter.importTypedRows(session, table, columns,
                    ImportOptions.of(format), reader);
        }
    }

    /** 导入结果汇总 —— 失败数可能大于明细行数（坏行只保留前 50 条）。 */
    private void showReport(TableImporter.ImportReport report) {
        BatchSummaryDialog.show(ownerWindow(), summaryOf(report));
    }

    /** 抽成静态纯函数：坏行截断的口径要能被门禁直接断言。 */
    static BatchSummary summaryOf(TableImporter.ImportReport report) {
        int attempted = report.inserted() + report.skipped();
        List<String> notes = new ArrayList<>();
        if (report.skipped() > report.errors().size()) {
            notes.add("坏行共 " + report.skipped() + " 行，仅列出前 "
                    + report.errors().size() + " 条。");
        }
        return BatchSummary.of("导入数据", "导入", "行",
                attempted, report.inserted(), report.skipped(), report.errors(), notes);
    }

    /** 汇总弹窗要盖在向导上面 —— 优先用向导自己的窗口，其次才是它的 owner。 */
    private javafx.stage.Window ownerWindow() {
        if (dialog.getDialogPane().getScene() != null) {
            return dialog.getDialogPane().getScene().getWindow();
        }
        return dialog.getOwner();
    }
}
