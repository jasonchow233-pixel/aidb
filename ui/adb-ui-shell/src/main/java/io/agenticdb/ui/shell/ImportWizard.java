package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.transfer.CsvCodec;
import io.agenticdb.ui.I18n;
import io.agenticdb.transfer.ImportFormat;
import io.agenticdb.transfer.ImportMode;
import io.agenticdb.transfer.ImportOptions;
import io.agenticdb.transfer.JsonCodec;
import io.agenticdb.transfer.SqlCodec;
import io.agenticdb.transfer.TableImporter;
import io.agenticdb.transfer.TransferFiles;
import io.agenticdb.transfer.XlsxCodec;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 导入向导：文件 → 编码与选项 → 目标表 → 字段映射 → 预览 → 执行 → 日志。
 *
 * <p>⛔ 按钮放在内容区（同 {@link ExportWizard}，理由见架构文档 §7.1）。
 *
 * <p>预览读前若干行，**不把整个文件读进内存**。
 *
 * <p>⭐ R33-G5：补上 Navicat 导入向导缺的四件事 —— <b>编码</b>（GBK 的 CSV 是中文
 * Windows 的常态）、<b>表头行</b>（真实文件常有标题/说明行）、<b>导入模式</b>
 * （追加 / 覆盖）、<b>配置文件的保存与载入</b>；再加一份可导出的<b>执行日志</b>。
 */
final class ImportWizard {

    /** 预览的行数。 */
    private static final int PREVIEW_ROWS = 20;

    /** 可选编码。xlsx 是二进制，编码对它无意义。 */
    private static final List<Charset> CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            StandardCharsets.UTF_16LE,
            StandardCharsets.UTF_16BE);

    private final DbSession session;
    private final String tableName;
    private final List<ColumnDef> columns;
    private final Executor dbExecutor;
    private final Consumer<String> status;
    private final TransferProfiles profiles;
    private final Consumer<TableImporter.ImportReport> reportSink;

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(8);
    private final TextField fileField = new TextField();
    private final ComboBox<ImportFormat> formatBox = new ComboBox<>();
    private final ComboBox<Charset> charsetBox = new ComboBox<>();
    private final Spinner<Integer> headerRowSpinner = new Spinner<>(0, 999, 1);
    private final ComboBox<ImportMode> modeBox = new ComboBox<>();
    private final ComboBox<TransferProfiles.ImportProfile> profileBox = new ComboBox<>();
    private final ComboBox<String> tableBox = new ComboBox<>();
    private final ListView<String> mappingView = new ListView<>();
    private final TextArea preview = new TextArea();
    private final TextArea logArea = new TextArea();
    private final Label summary = new Label();
    private final Label formatHint = new Label();
    private final Label optionNote = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button importButton = new Button(I18n.t("import.start"));
    private final Button closeButton = new Button(I18n.t("tab.close"));

    private volatile boolean running;

    /**
     * ⭐ D-73：文件选择通道（与 {@code ExportWizard} 同式）。默认走系统打开面板，
     * 门禁换替身 —— 原生面板在 Monocle 下按不住，真弹窗永远等不到返回值。
     */
    private java.util.function.Function<javafx.stage.Window, java.io.File> fileChooser = window -> {
        ImportFormat format = formatBox.getValue();
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.t("import.file.chooserTitle"));
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(format.label(),
                        "*." + format.name().toLowerCase(java.util.Locale.ROOT)),
                new javafx.stage.FileChooser.ExtensionFilter(
                        I18n.t("wizard.file.filter.all"), "*.*"));
        return chooser.showOpenDialog(window);
    };

    /** 测试替身：见 {@link #fileChooser}。 */
    void setFileChooserForTest(java.util.function.Function<javafx.stage.Window, java.io.File> chooser) {
        this.fileChooser = chooser;
    }

    ImportWizard(DbSession session, String tableName, List<ColumnDef> columns,
                 List<String> availableTables, Path initialFile, Executor dbExecutor,
                 Consumer<String> status) {
        this(session, tableName, columns, availableTables, initialFile, dbExecutor, status,
                new TransferProfiles(), null);
    }

    ImportWizard(DbSession session, String tableName, List<ColumnDef> columns,
                 List<String> availableTables, Path initialFile, Executor dbExecutor,
                 Consumer<String> status, TransferProfiles profiles) {
        this(session, tableName, columns, availableTables, initialFile, dbExecutor, status,
                profiles, null);
    }

    /**
     * @param reportSink 有坏行时的汇总去向；{@code null} = 弹 {@link BatchSummaryDialog}。
     *                   门禁必须换成无操作 —— 那颗弹窗是模态的，在测试里会把 FX 线程挂死。
     */
    ImportWizard(DbSession session, String tableName, List<ColumnDef> columns,
                 List<String> availableTables, Path initialFile, Executor dbExecutor,
                 Consumer<String> status, TransferProfiles profiles,
                 Consumer<TableImporter.ImportReport> reportSink) {
        this.session = session;
        this.tableName = tableName;
        this.columns = columns;
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;
        this.profiles = profiles == null ? new TransferProfiles() : profiles;
        this.reportSink = reportSink == null ? this::showReport : reportSink;

        fileField.setId("import-file");
        fileField.setText(initialFile == null ? "" : initialFile.toString());
        fileField.setPromptText(I18n.t("import.file.prompt"));

        // ⭐ D-73：与导出侧同批补的浏览按钮。回填走 textProperty 的监听（D-67 那条真实联动），
        //    所以这里**不**手动调 refreshPreview —— 门禁钉的就是「选完文件预览自己出来」。
        Button fileBrowse = new Button(I18n.t("wizard.browse"));
        fileBrowse.setId("import-file-browse");
        fileBrowse.getStyleClass().addAll("adb-button", "adb-button--ghost");
        fileBrowse.setOnAction(event -> {
            java.io.File picked = fileChooser.apply(ownerWindow());
            if (picked != null) {
                fileField.setText(picked.getAbsolutePath());
            }
        });
        HBox fileRow = new HBox(8, fileField, fileBrowse);
        HBox.setHgrow(fileField, Priority.ALWAYS);

        formatBox.setId("import-format");
        formatBox.getItems().setAll(ImportFormat.values());
        formatBox.getSelectionModel().select(ImportFormat.CSV);
        formatBox.valueProperty().addListener((observable, old, format) ->
                applyFormatEffects(format));

        // ⭐ D-59/D-83：格式说明行 + 禁用原因行（与 ExportWizard 同式）。
        formatHint.setId("import-format-hint");
        formatHint.getStyleClass().add("adb-muted");
        formatHint.setWrapText(true);
        optionNote.setId("import-option-note");
        optionNote.getStyleClass().add("adb-muted");
        optionNote.setWrapText(true);

        charsetBox.setId("import-charset");
        charsetBox.getItems().setAll(CHARSETS);
        charsetBox.getSelectionModel().select(StandardCharsets.UTF_8);
        charsetBox.setMaxWidth(Double.MAX_VALUE);

        headerRowSpinner.setId("import-header-row");
        headerRowSpinner.setEditable(true);
        headerRowSpinner.setMaxWidth(Double.MAX_VALUE);
        headerRowSpinner.setTooltip(new javafx.scene.control.Tooltip(
                I18n.t("import.headerRow.tooltip")));

        modeBox.setId("import-mode");
        modeBox.getItems().setAll(ImportMode.values());
        modeBox.getSelectionModel().select(ImportMode.APPEND);
        modeBox.setMaxWidth(Double.MAX_VALUE);
        // ⭐ D-59：枚举只存消息键，语言在这里落地。
        modeBox.setConverter(new javafx.util.StringConverter<ImportMode>() {
            @Override
            public String toString(ImportMode mode) {
                return mode == null ? "" : I18n.t(mode.labelKey());
            }

            @Override
            public ImportMode fromString(String text) {
                for (ImportMode mode : ImportMode.values()) {
                    if (I18n.t(mode.labelKey()).equals(text)) {
                        return mode;
                    }
                }
                return null;
            }
        });

        profileBox.setId("import-profile");
        profileBox.setMaxWidth(Double.MAX_VALUE);
        profileBox.setPromptText(I18n.t("import.profile.prompt"));
        profileBox.getItems().setAll(profiles.list());
        profileBox.valueProperty().addListener((observable, old, profile) -> {
            if (profile != null) {
                applyProfile(profile);
            }
        });

        Button saveProfile = new Button(I18n.t("import.profile.save"));
        saveProfile.setId("import-profile-save");
        saveProfile.getStyleClass().addAll("adb-button", "adb-button--ghost");
        saveProfile.setOnAction(event -> saveProfile());
        Button deleteProfile = new Button(I18n.t("common.delete"));
        deleteProfile.setId("import-profile-delete");
        deleteProfile.getStyleClass().addAll("adb-button", "adb-button--ghost");
        deleteProfile.setOnAction(event -> deleteProfile());
        HBox profileRow = new HBox(6, profileBox, saveProfile, deleteProfile);
        HBox.setHgrow(profileBox, Priority.ALWAYS);

        tableBox.setId("import-table");
        tableBox.getItems().setAll(availableTables == null ? List.of() : availableTables);
        if (tableName != null) {
            tableBox.getSelectionModel().select(tableName);
        }
        tableBox.setMaxWidth(Double.MAX_VALUE);

        mappingView.setId("import-mapping");
        mappingView.setPrefHeight(88);

        preview.setId("import-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(6);
        preview.setMinHeight(72);
        VBox.setVgrow(preview, Priority.ALWAYS);

        logArea.setId("import-log");
        logArea.setEditable(false);
        logArea.setPrefRowCount(4);
        logArea.setMinHeight(56);
        logArea.getStyleClass().add("adb-import-log");

        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        summary.setId("import-summary");
        summary.setText(I18n.t("import.summary.initial"));
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

        Button saveLog = new Button(I18n.t("import.exportLog"));
        saveLog.setId("import-log-save");
        saveLog.getStyleClass().addAll("adb-button", "adb-button--ghost");
        saveLog.setOnAction(event -> saveLog());

        // Navicat 口径（§6.7）：关闭在左、主动作在最右，进度指示留在最左。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spinner, saveLog, spacer, closeButton, importButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        content.getChildren().addAll(
                field(I18n.t("menu.file"), fileRow),
                field(I18n.t("menu.format"), formatBox),
                formatHint,
                field(I18n.t("import.field.encoding"), charsetBox),
                optionNote,
                field(I18n.t("import.field.headerRow"), headerRowSpinner),
                field(I18n.t("import.field.mode"), modeBox),
                field(I18n.t("import.field.profile"), profileRow),
                field(I18n.t("import.field.table"), tableBox),
                new Label(I18n.t("import.mapping")),
                mappingView,
                new Label(I18n.t("import.preview")),
                preview,
                new Label(I18n.t("import.log")),
                logArea,
                summary,
                actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle(I18n.t("import.title"));

        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。

        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);

        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        dialog.setHeaderText(I18n.t("import.steps"));
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);

        // ⭐ D-67：预览接上真实触发链。以前 refreshPreview 的唯一调用点在 poseForShots，
        // 生产路径（用户手输文件路径、换编码/格式/表头行）永远刷不出预览。
        // 监听在构造尾部注册，前面的初始 select 不会误触发。
        fileField.textProperty().addListener((observable, old, text) -> refreshPreview());
        formatBox.valueProperty().addListener((observable, old, format) -> refreshPreview());
        charsetBox.valueProperty().addListener((observable, old, charset) -> refreshPreview());
        headerRowSpinner.valueProperty().addListener((observable, old, row) -> refreshPreview());
        applyFormatEffects(formatBox.getValue());
        if (initialFile != null) {
            refreshPreview();
        }
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

    /**
     * 验收帧（G5）：摆出「GBK 文件 + 表头第 1 行 + 预览已解码」那一屏，不执行导入。
     *
     * <p>空窗证明不了编码/表头行/模式这三条新能力真的生效 —— 预览里的中文得是
     * 按所选编码解出来的，字段映射得真有匹配结果。
     *
     * <p>⭐ D-84：这里以前最后一行手搓一句执行日志（{@code import.log.pose}，
     * 参数写死 1 和 5）。演示文件只有 3 行数据，于是同一张帧里 Preview 三行、
     * Execution Log 声称「5 preview rows read」—— 自己跟自己打脸。
     * pose 没有真跑过导入，执行日志就该是空的；真实行数由 summary 那句承担。
     */
    void poseForShots(Path file) {
        fileField.setText(file == null ? "" : file.toString());
        charsetBox.getSelectionModel().select(java.nio.charset.Charset.forName("GBK"));
        headerRowSpinner.getValueFactory().setValue(1);
        refreshPreview();
    }

    VBox content() {
        return content;
    }

    /** D-58/D-59/D-83：格式变化一次算完 —— 编码是否禁用、说明行、禁用原因。 */
    private void applyFormatEffects(ImportFormat format) {
        boolean binary = format != null && format.binary();
        boolean implicitHeader = format != null && format.implicitHeader();
        charsetBox.setDisable(binary);
        // ⭐ H4-a：JSON/SQL 的表头内嵌于格式，表头行选择器拧了也不起作用 —— 直接禁掉。
        headerRowSpinner.setDisable(implicitHeader);
        formatHint.setText(format == null ? "" : I18n.t(format.hintKey()));
        formatHint.setVisible(format != null);
        formatHint.setManaged(format != null);
        optionNote.setText(I18n.t("transfer.option.note.binary"));
        optionNote.setVisible(binary);
        optionNote.setManaged(binary);
    }

    private HBox field(String label, javafx.scene.Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("adb-muted");
        caption.setMinWidth(64);
        HBox row = new HBox(8, caption, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    // ─────────────────────────── 选项 ───────────────────────────

    /** 当前界面上的选项。 */
    ImportOptions options() {
        ImportFormat format = formatBox.getValue();
        ImportOptions base = ImportOptions.of(format)
                .withCharset(charsetBox.getValue())
                .withHeaderRow(headerRowOf())
                .withMode(modeBox.getValue());
        return base;
    }

    private int headerRowOf() {
        Integer value = headerRowSpinner.getValue();
        return value == null ? 0 : Math.max(0, value);
    }

    private void applyProfile(TransferProfiles.ImportProfile profile) {
        formatBox.getSelectionModel().select(profile.format());
        charsetBox.getSelectionModel().select(TransferProfiles.charsetOf(profile.charset()));
        headerRowSpinner.getValueFactory().setValue(profile.headerRow());
        modeBox.getSelectionModel().select(profile.mode());
        log(I18n.t("import.log.profileLoaded", profile.name()));
    }

    private void saveProfile() {
        ImportOptions options = options();
        TextInputDialog prompt = new TextInputDialog(
                I18n.t("import.profile.defaultName", profileBox.getItems().size() + 1));
        prompt.setTitle(I18n.t("import.profile.saveTitle"));
        prompt.setHeaderText(I18n.t("import.profile.saveHeader"));
        prompt.setContentText(I18n.t("import.profile.nameLabel"));
        DialogStyles.apply(prompt, ownerWindow());
        Optional<String> name = prompt.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        TransferProfiles.ImportProfile profile = new TransferProfiles.ImportProfile(
                name.get(), options.format(), options.charset().name(),
                options.headerRow(), options.mode());
        profiles.save(profile);
        profileBox.getItems().setAll(profiles.list());
        profileBox.getSelectionModel().select(profile);
        log(I18n.t("import.log.profileSaved", profile.name()));
    }

    private void deleteProfile() {
        TransferProfiles.ImportProfile selected = profileBox.getValue();
        if (selected == null) {
            return;
        }
        profiles.remove(selected.name());
        profileBox.getItems().setAll(profiles.list());
        profileBox.getSelectionModel().clearSelection();
        log(I18n.t("import.log.profileDeleted", selected.name()));
    }

    // ─────────────────────────── 预览 ───────────────────────────

    /** 读文件的前若干行做预览，并推断字段映射。 */
    void refreshPreview() {
        String text = fileField.getText() == null ? "" : fileField.getText().trim();
        if (text.isBlank()) {
            summary.setText(I18n.t("import.summary.pickFile"));
            return;
        }
        Path file = Path.of(text);
        if (!Files.isRegularFile(file)) {
            summary.setText(I18n.t("import.summary.fileMissing", file));
            return;
        }
        ImportFormat format = formatBox.getValue();
        int headerRow = headerRowOf();
        try {
            List<String> header;
            List<String> previewLines = new ArrayList<>();
            if (format.binary()) {
                // ⭐ P6-3：xlsx 走 SAX 预览，同样只读前若干行。
                List<List<String>> rows = XlsxCodec.preview(file, PREVIEW_ROWS + headerRow);
                header = headerRow > 0 && rows.size() >= headerRow
                        ? rows.get(headerRow - 1) : null;
                int from = Math.min(headerRow, rows.size());
                for (List<String> row : rows.subList(from, rows.size())) {
                    previewLines.add(String.join(" | ", row));
                }
            } else if (format.implicitHeader()) {
                // ⭐ H4-a：JSON/SQL 预览 —— 首行是表头（键 / 列清单），本身也是数据，
                //    与 TableImporterJsonSqlTest 钉的执行语义同一条。
                List<List<String>> rows = format == ImportFormat.JSON
                        ? JsonCodec.preview(file, charsetBox.getValue(), PREVIEW_ROWS + 1)
                        : SqlCodec.preview(file, charsetBox.getValue(), PREVIEW_ROWS + 1);
                header = rows.isEmpty() ? null : rows.getFirst();
                for (List<String> row : rows.subList(1, rows.size())) {
                    previewLines.add(String.join(" | ", row));
                }
            } else {
                try (Reader reader = TransferFiles.openReader(file, charsetBox.getValue());
                     PushbackReader pushback = new PushbackReader(reader, 8)) {
                    for (int i = 1; i < headerRow; i++) {
                        if (CsvCodec.readRow(pushback, format.delimiter()) == null) {
                            break;
                        }
                    }
                    header = headerRow > 0
                            ? CsvCodec.readRow(pushback, format.delimiter()) : null;
                    List<String> row;
                    int count = 0;
                    while (count < PREVIEW_ROWS && (row = CsvCodec.readRow(pushback,
                            format.delimiter())) != null) {
                        previewLines.add(String.join(" | ", row));
                        count++;
                    }
                }
            }

            preview.setText(String.join("\n", previewLines));
            List<String> mapping = resolveMappingText(header);
            mappingView.getItems().setAll(mapping);
            summary.setText(I18n.t("import.summary.preview", previewLines.size(),
                    header == null ? I18n.t("import.summary.noHeader")
                            : I18n.tPlural("import.summary.mapped", mapping.size(), mapping.size()),
                    charsetLabel()));
        } catch (IOException e) {
            summary.setText(I18n.t("import.summary.readFailed", e.getMessage()));
        }
    }

    private String charsetLabel() {
        Charset charset = charsetBox.getValue();
        return charset == null ? StandardCharsets.UTF_8.name() : charset.name();
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
            lines.add(column.name() + "  ←  "
                    + (matched == null ? I18n.t("import.mapping.unmatched") : matched));
        }
        return lines;
    }

    // ─────────────────────────── 执行 ───────────────────────────

    /** 开始导入（后台线程）。 */
    void start() {
        if (running) {
            return;
        }
        String text = fileField.getText() == null ? "" : fileField.getText().trim();
        String table = tableBox.getValue();
        if (text.isBlank() || table == null) {
            summary.setText(I18n.t("import.summary.pickFileAndTable"));
            return;
        }
        Path file = Path.of(text);
        ImportOptions options = options();

        running = true;
        importButton.setDisable(true);
        spinner.setVisible(true);
        summary.setText(I18n.t("import.summary.running"));
        log(I18n.t("import.log.start", table, options.format().label(),
                options.charset().name(),
                options.headerRow() == 0 ? I18n.t("import.log.headerNone")
                        : String.valueOf(options.headerRow()),
                I18n.t(options.mode().labelKey())));

        dbExecutor.execute(() -> {
            String message;
            TableImporter.ImportReport report = null;
            try {
                // ⭐ P6-1：带上目标列类型 —— 数值 / 布尔 / 日期先转换，坏值进报告。
                // ⭐ P6-3：xlsx 走 SAX 流式路径；⭐ H4-a：JSON / SQL 走各自的编解码器，
                //    其余格式按文本行读取（编码与 BOM 由引擎处理）。
                report = switch (options.format()) {
                    case XLSX -> TableImporter.importXlsx(session, table, columns, options, file);
                    case JSON -> TableImporter.importJson(session, table, columns, options, file);
                    case SQL -> TableImporter.importSql(session, table, columns, options, file);
                    default -> TableImporter.importTypedRows(session, table, columns, options, file);
                };
                message = I18n.t("import.done", report.inserted())
                        + (report.deleted() > 0
                                ? I18n.t("import.done.cleared", report.deleted()) : "")
                        + (report.skipped() > 0
                                ? I18n.t("import.done.skipped", report.skipped()) : "")
                        + (report.hasErrors()
                                ? I18n.t("import.done.firstError", report.errors().getFirst())
                                : "");
            } catch (Exception e) {
                message = I18n.t("import.failed",
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String finalMessage = message;
            TableImporter.ImportReport finalReport = report;
            Platform.runLater(() -> {
                running = false;
                importButton.setDisable(false);
                spinner.setVisible(false);
                summary.setText(finalMessage);
                status.accept(finalMessage);
                log(finalMessage);
                if (finalReport != null) {
                    for (String error : finalReport.errors()) {
                        log("  " + error);
                    }
                }
                // ⭐ P2-2：有坏行就给一份可复制的汇总；全部成功时只用状态行，不弹窗打扰。
                if (finalReport != null
                        && (finalReport.hasErrors() || finalReport.skipped() > 0)) {
                    reportSink.accept(finalReport);
                }
            });
        });
    }

    private void log(String line) {
        logArea.appendText(line + "\n");
    }

    private void saveLog() {
        if (logArea.getText() == null || logArea.getText().isBlank()) {
            summary.setText(I18n.t("import.summary.noLog"));
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.t("import.log.exportTitle"));
        chooser.setInitialFileName("import-" + (tableName == null ? "log" : tableName) + ".log");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter(
                        I18n.t("import.log.fileFilter"), "*.log", "*.txt"));
        java.io.File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), logArea.getText(), StandardCharsets.UTF_8);
            summary.setText(I18n.t("import.log.exported", file.toPath()));
        } catch (IOException e) {
            summary.setText(I18n.t("import.log.exportFailed", e.getMessage()));
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
            notes.add(I18n.t("import.note.truncated",
                    report.skipped(), report.errors().size()));
        }
        return BatchSummary.of(I18n.t("import.title"), I18n.t("import.summary.action"),
                I18n.t("import.summary.unit"),
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
