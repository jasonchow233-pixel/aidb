package io.agenticdb.ui.shell;

import io.agenticdb.introspect.BackupDumper;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaDiff;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.Danger;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.sql.SqlSplitter;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.StringWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 单表传输：后台转储及校验 → FX 预览确认 → 后台执行同一份 SQL。
 *
 * <p>只允许相同 DialectKind、不同会话、目标没有同名对象的传输；不做异构类型映射。
 * Compare 可跨引擎，只读。DDL 不保证可回滚，且不得接管用户已有事务。
 */
public final class TransferToolPage extends VBox {

    private final DbSession sourceSession;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final List<ConnectionProfile> otherConnections;
    private final Function<String, DbSession> resolver;
    private final Predicate<StructurePlan> confirmation;
    /** FX 线程拥有此状态；准备、确认、执行和 Compare 共用一把页面级互斥锁。 */
    private boolean busy;

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
        this(sourceSession, schemaService, dbExecutor, otherConnections, resolver, null);
    }

    /** 测试只替换确认回调；public 构造始终使用真实 SQL 确认框。 */
    TransferToolPage(DbSession sourceSession,
                     SchemaService schemaService,
                     Executor dbExecutor,
                     List<ConnectionProfile> otherConnections,
                     Function<String, DbSession> resolver,
                     Predicate<StructurePlan> confirmation) {
        this.sourceSession = sourceSession;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.otherConnections = otherConnections == null ? List.of() : otherConnections;
        this.resolver = resolver == null ? id -> null : resolver;
        this.confirmation = confirmation == null ? plan -> StructurePlanRunner.confirm(
                getScene() == null ? null : getScene().getWindow(),
                getScene() == null ? List.of() : getScene().getStylesheets(), plan) : confirmation;
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
        sourcePicker.valueProperty().addListener((observable, before, after) -> refreshRunState());
        targetPicker.valueProperty().addListener((observable, before, after) -> refreshRunState());

        spinner.setId("transfer-progress");
        spinner.setMaxSize(18, 18);
        spinner.setVisible(false);

        body.setId("transfer-body");
        HBox sourceRow = new HBox(8, new Label(I18n.t("tool.transfer.source")), sourcePicker);
        sourceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox targetRow = new HBox(8, new Label(I18n.t("tool.transfer.target")), targetPicker);
        targetRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox controls = new HBox(8, run, compare);
        getChildren().addAll(title, subtitle, sourceRow, targetRow, controls, spinner, body);

        refreshRunState();
        if (sourceSession == null || sourceSession.isClosed()) {
            message(I18n.t("tool.transfer.noSession"));
            return;
        }
        loadTargets();
        loadSources();
    }

    private void loadSources() {
        setBusy(true);
        submit(() -> {
            try {
                List<String> tables = new ArrayList<>();
                for (SchemaObject o : schemaService.listObjects(sourceSession)) {
                    if (o.kind() == ObjectKind.TABLE) {
                        tables.add(o.name());
                    }
                }
                Platform.runLater(() -> {
                    sourcePicker.getItems().setAll(tables);
                    setBusy(false);
                    if (tables.isEmpty()) {
                        message(I18n.t("tool.transfer.noTables"));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> finishMessage(I18n.t("tool.transfer.failed", messageOf(e))));
            }
        }, "tool.transfer.failed");
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
        boolean unavailable = sourceSession == null || sourceSession.isClosed();
        boolean ready = !busy && !unavailable && sourcePicker.getValue() != null
                && !sourcePicker.getValue().isBlank() && targetPicker.getValue() != null;
        sourcePicker.setDisable(busy || unavailable);
        targetPicker.setDisable(busy || unavailable);
        run.setDisable(!ready);
        compare.setDisable(!ready);
    }

    private void setBusy(boolean value) {
        busy = value;
        spinner.setVisible(value);
        refreshRunState();
    }

    /** 只从 FX 线程入队；执行器拒绝任务也必须解除忙态。 */
    private void submit(Runnable task, String failureKey) {
        try {
            dbExecutor.execute(task);
        } catch (RuntimeException e) {
            finishMessage(I18n.t(failureKey, messageOf(e)));
        }
    }

    private void finishMessage(String text) {
        setBusy(false);
        message(text);
    }

    private void transfer(String sourceTable, ConnectionProfile target) {
        if (busy || sourceTable == null || sourceTable.isBlank() || target == null) {
            return;
        }
        setBusy(true);
        body.getChildren().clear();
        submit(() -> {
            try {
                DbSession targetSession = resolver.apply(target.id());
                checkTransfer(sourceTable, target, targetSession);
                StringWriter dump = new StringWriter();
                BackupDumper.dump(sourceSession, List.of(sourceTable), dump);
                List<String> statements = SqlSplitter.split(dump.toString()).stream()
                        .map(statement -> {
                            String sql = statement.text();
                            return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
                        }).toList();
                if (statements.isEmpty()) {
                    throw new SQLException(I18n.t("tool.transfer.noTables"));
                }
                Danger danger = Danger.DESTRUCTIVE;
                for (String sql : statements) {
                    danger = danger.worse(io.agenticdb.safety.DangerClassifier.classify(sql));
                }
                StructurePlan plan = new StructurePlan("tool.transfer.plan",
                        List.of(sourceTable, target.name(), target.id(), targetSession.label()),
                        statements, danger, danger == Danger.ADMIN, false);
                Platform.runLater(() -> confirmTransfer(sourceTable, target, targetSession, plan));
            } catch (Exception e) {
                Platform.runLater(() -> finishMessage(I18n.t("tool.transfer.failed", messageOf(e))));
            }
        }, "tool.transfer.failed");
    }

    private void confirmTransfer(String sourceTable, ConnectionProfile target,
                                 DbSession targetSession, StructurePlan plan) {
        try {
            if (!confirmation.test(plan)) {
                finishMessage(I18n.t("ddl.cancelled", StructurePlanRunner.title(plan)));
                return;
            }
        } catch (RuntimeException e) {
            finishMessage(I18n.t("tool.transfer.failed", messageOf(e)));
            return;
        }
        submit(() -> {
            try {
                // 确认期间可能断线、换会话或出现同名对象，不能把旧检查当作写入许可。
                checkTransfer(sourceTable, target, targetSession);
            } catch (Exception e) {
                Platform.runLater(() -> finishMessage(I18n.t("tool.transfer.failed", messageOf(e))));
                return;
            }
            try {
                boolean pending = executePlan(targetSession, plan);
                int statements = plan.statements().size();
                Platform.runLater(() -> finishMessage(pending
                        ? I18n.t("tool.transfer.donePending", sourceTable, target.name(), statements)
                        : I18n.tPlural("tool.transfer.done", statements,
                                sourceTable, target.name(), statements)));
            } catch (Exception e) {
                Platform.runLater(() -> finishMessage(
                        I18n.t("tool.transfer.executionFailed", messageOf(e))));
            }
        }, "tool.transfer.failed");
    }

    private void checkSessions(ConnectionProfile target, DbSession targetSession) throws SQLException {
        if (sourceSession == null || sourceSession.isClosed()) {
            throw new SQLException(I18n.t("tool.transfer.noSession"));
        }
        if (targetSession == null || targetSession.isClosed()
                || resolver.apply(target.id()) != targetSession) {
            throw new SQLException(I18n.t("tool.transfer.targetOffline", target.name()));
        }
        if (sourceSession == targetSession
                || sourceSession.connection() == targetSession.connection()) {
            throw new SQLException(I18n.t("tool.transfer.sameSession"));
        }
    }

    private void checkTransfer(String sourceTable, ConnectionProfile target,
                               DbSession targetSession) throws SQLException {
        checkSessions(target, targetSession);
        if (!sourceSession.dialect().canTransferTo(targetSession.dialect())) {
            throw new SQLException(I18n.t("tool.transfer.unsupportedDialect",
                    sourceSession.dialect().displayName(), targetSession.dialect().displayName()));
        }
        if (!targetSession.dialect().supportsTransactionalDdl() && targetSession.inTransaction()) {
            throw new SQLException(I18n.t("tool.transfer.activeTransaction"));
        }
        for (SchemaObject object : schemaService.listObjects(targetSession)) {
            if (object.name().equalsIgnoreCase(sourceTable)) {
                throw new SQLException(I18n.t("tool.transfer.targetExists", sourceTable, target.name()));
            }
        }
    }

    /**
     * 保持 StructurePlanRunner 的事务所有权规则，但显式声明 Danger。
     * 隐式提交 DDL 的引擎不能加入用户事务；失败不声称已完全回滚。
     * @return 是否已加入用户事务，仍需用户决定提交或回滚
     */
    private boolean executePlan(DbSession session, StructurePlan plan) throws Exception {
        for (String sql : plan.statements()) {
            DbSession.requireDeclared(plan.danger(), sql);
        }
        boolean userTransaction = session.inTransaction();
        boolean transactional = session.dialect().supportsTransactionalDdl();
        if (userTransaction && !transactional) {
            throw new SQLException(I18n.t("tool.transfer.activeTransaction"));
        }
        boolean ownTransaction = !userTransaction && transactional;
        if (ownTransaction) {
            session.begin();
        }
        try {
            for (String sql : plan.statements()) {
                session.execute(sql, plan.danger());
            }
            if (ownTransaction) {
                session.commit();
            }
            return userTransaction;
        } catch (Exception e) {
            if (ownTransaction) {
                try {
                    session.rollback();
                } catch (Exception rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            throw e;
        } finally {
            // 非事务 DDL 或用户事务中的失败也可能已经改变结构。
            schemaService.invalidate(session);
        }
    }

    /**
     * ⭐ H4-d：比较两个会话里同名表的结构 + 行数，只出报告、不改目标库。
     *
     * <p>目标库没有同名表时如实说「没有这张表」，不假装成一个空结构去比 ——
     * 那样会报出「目标缺了所有列」这种听起来像同步建议的假差异。
     */
    private void compareStructure(String sourceTable, ConnectionProfile target) {
        if (busy || sourceTable == null || sourceTable.isBlank() || target == null) {
            return;
        }
        setBusy(true);
        body.getChildren().clear();
        submit(() -> {
            try {
                DbSession targetSession = resolver.apply(target.id());
                checkSessions(target, targetSession);
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
                    Platform.runLater(() -> finishMessage(missing));
                    return;
                }
                TableStructure source = schemaService.structure(sourceSession, sourceTable);
                TableStructure destination = schemaService.structure(targetSession, sourceTable);
                long sourceRows = schemaService.count(sourceSession, sourceTable);
                long targetRows = schemaService.count(targetSession, sourceTable);
                String text = renderDiff(SchemaDiff.compare(sourceTable, source, destination,
                        sourceRows, targetRows));
                Platform.runLater(() -> {
                    setBusy(false);
                    showReport(text);
                });
            } catch (Exception e) {
                Platform.runLater(() -> finishMessage(I18n.t("tool.transfer.diff.failed", messageOf(e))));
            }
        }, "tool.transfer.diff.failed");
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
