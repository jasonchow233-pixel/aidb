package io.agenticdb.ui.shell;

import io.agenticdb.introspect.BackupDumper;
import io.agenticdb.introspect.BackupRestorer;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.Danger;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.FileWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/** 备份与恢复入口：恢复先准备不可变 SQL 计划，确认后才执行。 */
public final class BackupToolPage extends VBox {

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;
    private final Predicate<StructurePlan> confirmation;

    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final CheckBox dropFirst = new CheckBox();
    private final Button dump;
    private final Button restore;
    private boolean busy;

    public BackupToolPage(DbSession session, SchemaService schemaService, Executor dbExecutor) {
        this(session, schemaService, dbExecutor, null);
    }

    BackupToolPage(DbSession session, SchemaService schemaService, Executor dbExecutor,
                   Predicate<StructurePlan> confirmation) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        this.confirmation = confirmation == null ? this::confirmRestore : confirmation;
        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("adb-page__body");

        Label title = new Label(I18n.t("tool.backup.title"));
        title.getStyleClass().add("adb-page__title");
        title.setId("backup-title");

        Label subtitle = new Label(I18n.t("tool.backup.subtitle",
                session == null ? "—" : session.label()));
        subtitle.getStyleClass().add("adb-muted");

        dump = IconButtons.of(Icons.Icon.SAVE, I18n.t("tool.backup.dump"));
        dump.setId("backup-dump");
        dump.getStyleClass().add("adb-button");
        dump.setOnAction(event -> dumpToFile());

        restore = IconButtons.of(Icons.Icon.OPEN, I18n.t("tool.backup.restore"));
        restore.setId("backup-restore");
        restore.getStyleClass().add("adb-button");
        restore.setOnAction(event -> restoreFromFile());

        dropFirst.setId("backup-drop-first");
        dropFirst.setText(I18n.t("tool.backup.dropFirst"));

        HBox actions = new HBox(8, dump, restore, dropFirst);
        actions.setAlignment(Pos.CENTER_LEFT);

        spinner.setMaxSize(18, 18);
        spinner.setVisible(false);

        status.setId("backup-status");
        status.getStyleClass().add("adb-muted");
        status.setWrapText(true);
        status.setText(I18n.t("tool.backup.hint"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(title, subtitle, actions, spinner, status, spacer);
    }

    private void dumpToFile() {
        if (busy) {
            return;
        }
        if (session == null) {
            setStatus(I18n.t("tool.backup.noSession"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("tool.backup.dump"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.t("tool.backup.filter.sql"), "*.sql"),
                new FileChooser.ExtensionFilter(I18n.t("tool.backup.filter.all"), "*.*"));
        File file = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null || !startWork()) {
            return;
        }
        submit(() -> {
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                long rows = BackupDumper.dump(session, writer);
                report(I18n.tPlural("tool.backup.dumped", rows, rows) + " — " + file.getName());
            } catch (Exception e) {
                report(I18n.t("tool.backup.failed") + " " + messageOf(e));
            }
        });
    }

    private void restoreFromFile() {
        if (busy) {
            return;
        }
        if (session == null) {
            setStatus(I18n.t("tool.backup.noSession"));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("tool.backup.restore"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.t("tool.backup.filter.sql"), "*.sql"),
                new FileChooser.ExtensionFilter(I18n.t("tool.backup.filter.all"), "*.*"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            restoreFromFile(file.toPath());
        }
    }

    /** FX 线程入口；与文件选择器共用，测试不必操作本机文件对话框。 */
    void restoreFromFile(Path file) {
        if (busy) {
            return;
        }
        if (session == null) {
            setStatus(I18n.t("tool.backup.noSession"));
            return;
        }
        if (file == null || !startWork()) {
            return;
        }
        final boolean drop = dropFirst.isSelected();
        submit(() -> {
            try {
                Path source = file.toAbsolutePath().normalize();
                String content = Files.readString(source, StandardCharsets.UTF_8);
                StructurePlan plan = prepareRestore(content, source, drop);
                Platform.runLater(() -> previewRestore(plan, source));
            } catch (Exception e) {
                report(I18n.t("tool.backup.failed") + " " + messageOf(e));
            }
        });
    }

    private StructurePlan prepareRestore(String content, Path file, boolean drop) throws Exception {
        List<String> script = BackupRestorer.splitStatements(content);
        List<String> statements = new ArrayList<>();
        // 只有标记、没有 SQL 的文件不能变成一次删库操作。
        if (!script.isEmpty()) {
            if (drop) {
                Set<String> names = new LinkedHashSet<>(
                        BackupRestorer.tableNames(new StringReader(content)));
                if (!names.isEmpty()) {
                    Set<String> existing = new HashSet<>();
                    var connection = session.connection();
                    String catalog = session.dialect().currentCatalog(connection);
                    String schema = session.dialect().currentSchema(connection);
                    try (var tables = connection.getMetaData().getTables(
                            catalog, schema, "%", new String[]{"TABLE", "BASE TABLE"})) {
                        while (tables.next()) {
                            String type = tables.getString("TABLE_TYPE");
                            if ("TABLE".equalsIgnoreCase(type) || "BASE TABLE".equalsIgnoreCase(type)) {
                                existing.add(tables.getString("TABLE_NAME"));
                            }
                        }
                    }
                    for (String name : names) {
                        if (existing.contains(name)) {
                            statements.add(session.dialect().dropTableSql(name));
                        }
                    }
                }
            }
            statements.addAll(script);
        }
        Danger danger = Danger.DESTRUCTIVE;
        for (String sql : statements) {
            danger = danger.worse(io.agenticdb.safety.DangerClassifier.classify(sql));
        }
        return new StructurePlan("tool.backup.restorePlan", List.of(session.label(), file.toString()),
                statements, danger, true, false);
    }

    private boolean confirmRestore(StructurePlan plan) {
        return StructurePlanRunner.confirm(getScene() == null ? null : getScene().getWindow(),
                getScene() == null ? List.of() : getScene().getStylesheets(), plan);
    }

    private void previewRestore(StructurePlan plan, Path file) {
        try {
            if (plan.empty()) {
                report(I18n.t("tool.backup.restoreEmpty"));
                return;
            }
            status.setText(I18n.t("tool.backup.restoreWarning"));
            if (!confirmation.test(plan)) {
                report(I18n.t("tool.backup.restoreCancelled"));
                return;
            }
            status.setText(I18n.t("tool.backup.working"));
            submit(() -> executeRestore(plan, file));
        } catch (Exception e) {
            report(I18n.t("tool.backup.failed") + " " + messageOf(e));
        }
    }

    private void executeRestore(StructurePlan plan, Path file) {
        int completed = 0;
        String result;
        try {
            for (String sql : plan.statements()) {
                DbSession.requireDeclared(plan.danger(), sql);
            }
            // 不接管用户事务；自动提交和 DDL 隐式提交行为取决于数据库。
            for (String sql : plan.statements()) {
                session.execute(sql, plan.danger());
                completed++;
            }
            result = I18n.tPlural("tool.backup.restored", completed, completed)
                    + " — " + file.getFileName() + " " + I18n.t("tool.backup.restoreWarning");
        } catch (Exception e) {
            result = I18n.t("tool.backup.restoreFailed", completed, plan.statements().size(), messageOf(e));
        } finally {
            // 失败也可能已经改变结构，不能保留旧缓存。
            if (schemaService != null) {
                schemaService.invalidate(session);
            }
        }
        report(result);
    }

    private boolean startWork() {
        if (busy) {
            return false;
        }
        busy = true;
        dump.setDisable(true);
        restore.setDisable(true);
        dropFirst.setDisable(true);
        spinner.setVisible(true);
        status.setText(I18n.t("tool.backup.working"));
        return true;
    }

    private void submit(Runnable work) {
        try {
            dbExecutor.execute(work);
        } catch (RuntimeException e) {
            report(I18n.t("tool.backup.failed") + " " + messageOf(e));
        }
    }

    private void report(String text) {
        Platform.runLater(() -> {
            busy = false;
            dump.setDisable(false);
            restore.setDisable(false);
            dropFirst.setDisable(false);
            spinner.setVisible(false);
            status.setText(text);
        });
    }

    private void setStatus(String text) {
        Platform.runLater(() -> status.setText(text));
    }

    private static String messageOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
