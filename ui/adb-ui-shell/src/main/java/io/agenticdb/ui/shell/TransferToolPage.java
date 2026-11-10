package io.agenticdb.ui.shell;

import io.agenticdb.introspect.BackupDumper;
import io.agenticdb.introspect.BackupRestorer;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaDiff;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * ⭐ P3 跨库传输最小切片（按 P0 默认走向 Q11：单表 DB→DB，不做整库同步引擎）。
 *
 * <p>复用 Backup 的两条原语：源表先经 {@link BackupDumper} 落成一个可重放的
 * {@code .sql} 片段（内存里，不落盘），再经 {@link BackupRestorer} 灌进目标连接。
 * 这样传输走的就是和备份/恢复完全一致、互逆的通道，零新协议。
 *
 * <p>诚实边界：跨方言的类型映射交给各自方言的 DDL/值转义，少数类型可能需要在
 * 目标端微调；这是「最小切片」而非「全量同步」，页面文案也如实说明。
 */
public final class TransferToolPage extends VBox {

    private final DbSession sourceSession;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final List<ConnectionProfile> otherConnections;
    private final Function<String, DbSession> resolver;

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final ComboBox<String> sourcePicker = new ComboBox<>();
    private final ComboBox<ConnectionProfile> targetPicker = new ComboBox<>();
    private final Button run = new Button();
    /** ⭐ H4-d：只读结构差异报告。和「传输」并列，但绝不自动改目标库。 */
    private final Button compare = new Button();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final VBox body = new VBox(8);

    public TransferToolPage(DbSession sourceSession,
                            SchemaService schemaService,
                            Executor dbExecutor,
                            List<ConnectionProfile> otherConnections,
                            Function<String, DbSession> resolver) {
        this.sourceSession = sourceSession;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.otherConnections = otherConnections == null ? List.of() : otherConnections;
        this.resolver = resolver;
        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("adb-page__body");

        title.setId("transfer-title");
        title.getStyleClass().add("adb-page__title");
        title.setText(I18n.t("tool.transfer.title"));

        subtitle.setId("transfer-subtitle");
        subtitle.getStyleClass().add("adb-muted");
        subtitle.setText(I18n.t("tool.transfer.subtitle",
                sourceSession == null ? "—" : sourceSession.dialect().displayName()));

        sourcePicker.setId("transfer-source");
        sourcePicker.setPromptText(I18n.t("tool.transfer.pickSource"));
        sourcePicker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sourcePicker, Priority.ALWAYS);

        targetPicker.setId("transfer-target");
        targetPicker.setPromptText(I18n.t("tool.transfer.pickTarget"));
        targetPicker.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(targetPicker, Priority.ALWAYS);
        targetPicker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ConnectionProfile p) {
                return p == null ? "" : p.name() + " (" + p.kind() + ")";
            }
            @Override
            public ConnectionProfile fromString(String s) {
                return null;
            }
        });

        run.setId("transfer-run");
        run.setText(I18n.t("tool.transfer.run"));
        run.setDisable(true);
        run.setOnAction(event -> transfer(sourcePicker.getValue(),
                targetPicker.getValue()));

        compare.setId("transfer-compare");
        compare.setText(I18n.t("tool.transfer.compare"));
        compare.setDisable(true);
        compare.setOnAction(event -> compareStructure(sourcePicker.getValue(),
                targetPicker.getValue()));

        spinner.setMaxSize(18, 18);
        spinner.setVisible(false);

        body.setId("transfer-body");
        HBox sourceRow = new HBox(8, new Label(I18n.t("tool.transfer.source")), sourcePicker);
        sourceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox targetRow = new HBox(8, new Label(I18n.t("tool.transfer.target")), targetPicker);
        targetRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox controls = new HBox(8, run, compare);
        getChildren().addAll(title, subtitle, sourceRow, targetRow, controls, spinner, body);

        if (sourceSession == null) {
            message(I18n.t("tool.transfer.noSession"));
            return;
        }
        loadSources();
        loadTargets();
    }

    private void loadSources() {
        dbExecutor.execute(() -> {
            try {
                List<String> tables = new ArrayList<>();
                for (SchemaObject o : schemaService.listObjects(sourceSession)) {
                    if (o.kind() == ObjectKind.TABLE) {
                        tables.add(o.name());
                    }
                }
                javafx.application.Platform.runLater(() -> {
                    sourcePicker.getItems().setAll(tables);
                    refreshRunState();
                    if (tables.isEmpty()) {
                        message(I18n.t("tool.transfer.noTables"));
                    }
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() ->
                        message(I18n.t("tool.transfer.failed", messageOf(e))));
            }
        });
    }

    private void loadTargets() {
        targetPicker.getItems().setAll(otherConnections);
        refreshRunState();
        if (otherConnections.isEmpty()) {
            Label hint = new Label(I18n.t("tool.transfer.noTargets"));
            hint.setWrapText(true);
            hint.getStyleClass().add("adb-muted");
            body.getChildren().add(hint);
        }
    }

    private void refreshRunState() {
        boolean ready = sourcePicker.getValue() != null && targetPicker.getValue() != null;
        run.setDisable(!ready);
        compare.setDisable(!ready);
    }

    private void transfer(String sourceTable, ConnectionProfile target) {
        if (sourceTable == null || target == null) {
            return;
        }
        DbSession targetSession = resolver.apply(target.id());
        if (targetSession == null) {
            message(I18n.t("tool.transfer.targetOffline", target.name()));
            return;
        }
        spinner.setVisible(true);
        body.getChildren().clear();
        String targetName = target.name();
        dbExecutor.execute(() -> {
            try {
                StringWriter dump = new StringWriter();
                BackupDumper.dump(sourceSession, List.of(sourceTable), dump);
                int statements = BackupRestorer.restore(targetSession.connection(),
                        new StringReader(dump.toString()));
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    Label done = new Label(I18n.tPlural("tool.transfer.done", statements,
                            sourceTable, targetName, statements));
                    done.setWrapText(true);
                    body.getChildren().add(done);
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    message(I18n.t("tool.transfer.failed", messageOf(e)));
                });
            }
        });
    }

    /**
     * ⭐ H4-d：比较两个会话里同名表的结构 + 行数，只出报告、不改目标库。
     *
     * <p>目标库没有同名表时如实说「没有这张表」，不假装成一个空结构去比 ——
     * 那样会报出「目标缺了所有列」这种听起来像同步建议的假差异。
     */
    private void compareStructure(String sourceTable, ConnectionProfile target) {
        if (sourceTable == null || target == null) {
            return;
        }
        DbSession targetSession = resolver.apply(target.id());
        if (targetSession == null) {
            message(I18n.t("tool.transfer.targetOffline", target.name()));
            return;
        }
        spinner.setVisible(true);
        dbExecutor.execute(() -> {
            try {
                boolean exists = false;
                for (SchemaObject object : schemaService.listObjects(targetSession)) {
                    if (object.kind() == ObjectKind.TABLE
                            && object.name().equalsIgnoreCase(sourceTable)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    String missing = I18n.t("tool.transfer.diff.missing", sourceTable);
                    javafx.application.Platform.runLater(() -> {
                        spinner.setVisible(false);
                        message(missing);
                    });
                    return;
                }
                TableStructure source = schemaService.structure(sourceSession, sourceTable);
                TableStructure destination = schemaService.structure(targetSession, sourceTable);
                long sourceRows = schemaService.count(sourceSession, sourceTable);
                long targetRows = schemaService.count(targetSession, sourceTable);
                String text = renderDiff(SchemaDiff.compare(sourceTable, source, destination,
                        sourceRows, targetRows));
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    showReport(text);
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    spinner.setVisible(false);
                    message(I18n.t("tool.transfer.diff.failed", messageOf(e)));
                });
            }
        });
    }

    /** 包级静态：报告是纯字符串，门禁不必起 FX 场景图就能验「差异说全了没有」。 */
    static String renderDiff(SchemaDiff.Report report) {
        StringBuilder text = new StringBuilder();
        text.append(I18n.t("tool.transfer.diff.title", report.table())).append('\n');
        text.append(I18n.t("tool.transfer.diff.rows",
                report.sourceRows(), report.targetRows())).append('\n');
        if (!report.onlyInSource().isEmpty()) {
            text.append(I18n.t("tool.transfer.diff.onlySource",
                    String.join(", ", report.onlyInSource()))).append('\n');
        }
        if (!report.onlyInTarget().isEmpty()) {
            text.append(I18n.t("tool.transfer.diff.onlyTarget",
                    String.join(", ", report.onlyInTarget()))).append('\n');
        }
        for (SchemaDiff.TypeMismatch mismatch : report.typeMismatches()) {
            text.append(I18n.t("tool.transfer.diff.type", mismatch.column(),
                    mismatch.sourceType(), mismatch.targetType())).append('\n');
        }
        if (!report.primaryKeySame()) {
            text.append(I18n.t("tool.transfer.diff.pk")).append('\n');
        }
        if (report.differenceCount() == 0) {
            text.append(I18n.t("tool.transfer.diff.none")).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private void showReport(String text) {
        body.getChildren().clear();
        TextArea area = new TextArea(text);
        area.setId("transfer-diff-report");
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(Math.min(12, text.split("\n", -1).length + 1));
        body.getChildren().add(area);
    }

    private void message(String text) {
        spinner.setVisible(false);
        body.getChildren().clear();
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("adb-muted");
        body.getChildren().add(label);
    }

    private static String messageOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
