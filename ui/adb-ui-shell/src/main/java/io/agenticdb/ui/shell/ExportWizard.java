package io.agenticdb.ui.shell;

import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.DataSqlBuilder;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.TableExporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
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

    /**
     * 行数来源：给定导出范围，返回「将读取多少行」。
     *
     * <p>抽出来是为了门禁能注入一个超阈值的大数 —— 否则要造一百万行才能测到这条路径。
     */
    @FunctionalInterface
    interface RowCounter {
        long count(Scope scope) throws Exception;
    }

    /**
     * 超限确认：拿到提示文案，返回 {@code true} 才继续导出。
     *
     * <p>默认实现在 FX 线程上弹模态框阻塞等用户 —— 这是 JavaFX 的正常用法，
     * 且调用点本来就在 FX 线程上（见 {@link #start()}）。
     */
    @FunctionalInterface
    interface Confirmation {
        boolean confirm(String message);
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

    private final RowCounter counter;
    private final Confirmation confirmation;

    private volatile boolean running;
    /** 打开时记住拥有者窗口 —— 大表确认那颗 Alert 要用它取主题。 */
    private javafx.stage.Window owner;

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status) {
        this(session, tableName, columns, defaultScope, defaultTarget, dbExecutor, status,
                null, null);
    }

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status,
                 RowCounter counter, Confirmation confirmation) {
        this.session = session;
        this.tableName = tableName;
        this.columns = columns;
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;
        this.counter = counter == null ? this::countRows : counter;
        this.confirmation = confirmation == null ? this::askUser : confirmation;

        scopeBox.getItems().setAll(Scope.values());
        scopeBox.getSelectionModel().select(defaultScope);
        scopeBox.setId("export-scope");

        formatBox.getItems().setAll(ExportFormat.values());
        formatBox.getSelectionModel().select(ExportFormat.CSV);
        formatBox.setId("export-format");
        // 换格式时同步目标文件后缀，避免把 xlsx 字节写进 .csv。
        formatBox.valueProperty().addListener((observable, old, format) ->
                targetField.setText(withExtension(targetField.getText(), format)));

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

        // Navicat 口径（§6.7）：关闭在左、主动作在最右，进度指示留在最左。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spinner, spacer, closeButton, exportButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

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

        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。

        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);

        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        dialog.setHeaderText("选择范围与格式");
        // ⛔ 不放任何按钮到按钮栏 —— 放了会被 setResultAndClose 一键关掉。
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
    }

    /** 打开向导（模态）。 */
    void show(javafx.stage.Window owner) {
        this.owner = owner;
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogStyles.apply(dialog, owner, "export-wizard");
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
        summary.setText("正在统计 " + tableName + " 的行数 …");

        ExportOptions options = new ExportOptions(format, true, 500, 0, true);
        // ⭐ 行数统计在后台线程跑：整表 COUNT(*) 是一次全表扫描，
        //    大表上可能要几十秒 —— 不能因为它把界面卡住。
        dbExecutor.execute(() -> {
            long rows;
            try {
                rows = counter.count(scope);
            } catch (Exception e) {
                // 数不出来就按「不知道」处理：不因此拦住用户，但也不假装知道行数。
                rows = -1;
            }
            if (rows > 0 && QueryLimits.exportRequiresConfirmation(rows)) {
                String message = "将读取 " + NumberFormat.getInstance().format(rows) + " 行"
                        + "（超过 " + NumberFormat.getInstance().format(QueryLimits.MAX_EXPORT_ROWS)
                        + " 行的确认阈值）。确定继续吗？";
                Platform.runLater(() -> {
                    if (confirmation.confirm(message)) {
                        runExport(target, options);
                    } else {
                        finish("已取消导出：" + message);
                    }
                });
                return;
            }
            if (rows < 0) {
                Platform.runLater(() ->
                        summary.setText("无法统计行数，仍继续导出 " + tableName + " …"));
            }
            runExport(target, options);
        });
    }

    private void runExport(Path target, ExportOptions options) {
        dbExecutor.execute(() -> {
            String message;
            try {
                long rows = run(target, options);
                message = "已导出 " + rows + " 行 → " + target;
            } catch (Exception e) {
                message = "导出失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String finalMessage = message;
            Platform.runLater(() -> finish(finalMessage));
        });
    }

    /** 收尾：解锁按钮、写日志。必须在 FX 线程上调用。 */
    private void finish(String message) {
        running = false;
        exportButton.setDisable(false);
        spinner.setVisible(false);
        summary.setText(message);
        log.appendText(message + "\n");
        status.accept(message);
    }

    /**
     * 默认的行数来源。
     *
     * <p>⭐ 只有<b>整表</b>可能过百万 —— 「当前页」和「选中行」都受页大小约束
     * （{@code QueryLimits.MAX_PAGE_SIZE}），永远不该触发确认。
     * 所以这两档直接返回 0，表示「不用问」。
     */
    long countRows(Scope scope) throws Exception {
        if (scope != Scope.WHOLE_TABLE) {
            return 0;
        }
        DataSqlBuilder.Sql sql = DataSqlBuilder.count(session, tableName, DataQuery.empty());
        RowPage page = session.query(sql.text(), 1, sql.params());
        if (page.rows().isEmpty()) {
            return 0;
        }
        Object value = page.rows().get(0).get("adb_count");
        return value instanceof Number number ? number.longValue() : 0;
    }

    /**
     * 默认的确认方式：模态框，且把真实行数写出来（不抽象的「确定吗」）。
     *
     * <p>⭐ 必须走 {@link DialogStyles}：这是一颗 {@link Alert}，自带自己的 Scene，
     * 主题类不在 DialogPane 上 → {@code -adb-*} 全部落空 → 弹出的是一个 Modena 灰皮肤，
     * 和同一个软件里的其它弹窗不是一套东西（任务书 §七）。
     */
    private boolean askUser(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message,
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("导出确认");
        alert.setHeaderText("这张表很大");
        DialogStyles.apply(alert, owner);
        return alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }

    private long run(Path target, ExportOptions options) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (options.format().binary()) {
            try (OutputStream out = Files.newOutputStream(target)) {
                return TableExporter.exportXlsx(session, tableName, columns, options, out);
            }
        }
        try (Writer writer = new BufferedWriter(
                Files.newBufferedWriter(target, StandardCharsets.UTF_8))) {
            return TableExporter.export(session, tableName, columns, options, writer);
        }
    }

    /** 只替换已知的导出后缀；用户自定义文件名保持原样。 */
    static String withExtension(String target, ExportFormat format) {
        if (target == null || target.isBlank() || format == null) {
            return target;
        }
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        for (String known : new String[]{".csv", ".tsv", ".json", ".sql", ".xlsx"}) {
            if (lower.endsWith(known)) {
                return target.substring(0, target.length() - known.length())
                        + "." + format.name().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return target;
    }

    /** 超过导出上限时要不要确认。 */
    static boolean requiresConfirmation(long rowCount) {
        return QueryLimits.exportRequiresConfirmation(rowCount);
    }
}
