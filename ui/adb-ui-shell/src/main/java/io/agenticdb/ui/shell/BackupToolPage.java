package io.agenticdb.ui.shell;

import io.agenticdb.introspect.BackupDumper;
import io.agenticdb.introspect.BackupRestorer;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
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
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ⭐ P2 Backup（UI 入口）：连接当前活动库的「转储 / 恢复」工具页。
 *
 * <p>纯 JDBC 落盘（{@link BackupDumper}）与重放（{@link BackupRestorer}）能力已经齐备，
 * 这一页只负责把能力接到界面上：选文件、跑后台任务、报结果。两类动作都走后台
 * {@link Executor}，不阻塞界面；结果写进底部状态条。
 *
 * <p>「恢复前先删旧表」勾选后，会先从 dump 的 {@code -- table: NAME} 标记里取出表名，
 * 逐张 {@code DROP TABLE IF EXISTS} 再重放 —— 这样同一个 dump 既能灌进空库，也能覆盖已有库。
 */
public final class BackupToolPage extends VBox {

    private final DbSession session;
    private final SchemaService schemaService;
    private final Executor dbExecutor;

    private final Label status = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final CheckBox dropFirst = new CheckBox();

    public BackupToolPage(DbSession session, SchemaService schemaService, Executor dbExecutor) {
        this.session = session;
        this.schemaService = schemaService;
        this.dbExecutor = dbExecutor;
        setSpacing(12);
        setPadding(new Insets(16));
        getStyleClass().add("adb-page__body");

        Label title = new Label(I18n.t("tool.backup.title"));
        title.getStyleClass().add("adb-page__title");
        title.setId("backup-title");

        Label subtitle = new Label(I18n.t("tool.backup.subtitle",
                session == null ? "—" : session.dialect().displayName()));
        subtitle.getStyleClass().add("adb-muted");

        Button dump = IconButtons.of(Icons.Icon.SAVE, I18n.t("tool.backup.dump"));
        dump.setId("backup-dump");
        dump.getStyleClass().add("adb-button");
        dump.setOnAction(event -> dumpToFile());

        Button restore = IconButtons.of(Icons.Icon.OPEN, I18n.t("tool.backup.restore"));
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
        if (file == null) {
            return;
        }
        startWork();
        dbExecutor.execute(() -> {
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                long rows = BackupDumper.dump(session, writer);
                report(I18n.tPlural("tool.backup.dumped", rows, rows) + " — " + file.getName());
            } catch (Exception e) {
                report(I18n.t("tool.backup.failed") + " " + messageOf(e));
            }
        });
    }

    /**
     * 「先删旧表」用的 DROP 语句。
     *
     * <p>包级可见（不是 private）：这条语句会真的落到用户的库上，
     * 门禁要能直接盯住它的形状 —— 尤其是<b>只删 dump 里出现过的表</b>，
     * 以及表名走方言引号（不加引号会被 `order` / `group` 这类保留字打挂）。
     */
    static String dropTableIfExists(String quotedName) {
        return "DROP TABLE IF EXISTS " + quotedName;
    }

    private void restoreFromFile() {
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
        if (file == null) {
            return;
        }
        startWork();
        final boolean drop = dropFirst.isSelected();
        dbExecutor.execute(() -> {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                if (drop) {
                    List<String> names = BackupRestorer.tableNames(new StringReader(content));
                    try (var statement = session.connection().createStatement()) {
                        for (String name : names) {
                            try {
                                statement.executeUpdate(dropTableIfExists(
                                        session.dialect().quote(name)));
                            } catch (Exception ignored) {
                                // 某张表不存在 / 无权删 → 跳过，继续重放其余。
                            }
                        }
                    }
                }
                int statements = BackupRestorer.restore(session.connection(), new StringReader(content));
                report(I18n.tPlural("tool.backup.restored", statements, statements)
                        + " — " + file.getName());
            } catch (Exception e) {
                report(I18n.t("tool.backup.failed") + " " + messageOf(e));
            }
        });
    }

    private void startWork() {
        javafx.application.Platform.runLater(() -> {
            spinner.setVisible(true);
            status.setText(I18n.t("tool.backup.working"));
        });
    }

    private void report(String text) {
        javafx.application.Platform.runLater(() -> {
            spinner.setVisible(false);
            status.setText(text);
        });
    }

    private void setStatus(String text) {
        javafx.application.Platform.runLater(() -> status.setText(text));
    }

    private static String messageOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
