package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ImportFormat;
import io.agenticdb.transfer.TableImporter;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入导出向导的门禁。
 *
 * <p>侧栏的「导入 / 导出」以前是空壳页（只显示一句「该工具只保留入口」）。
 * 现在它真的能干活 —— 这条门禁守住「不是空壳」。
 */
@ExtendWith(ApplicationExtension.class)
class TransferWizardTest {

    @TempDir
    Path temp;

    private ExecutorService executor;

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void exportWizardWritesTheWholeTable() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("out.csv");
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, target,
                    executor, message -> { }));

            onFxRun(() -> {
                TextField field = (TextField) wizard.content().lookup("#export-target");
                field.setText(target.toString());
            });
            onFxRun(() -> clickButton(wizard, "export-start"));

            // 导出前多了一次 COUNT(*)，等文件的时间放宽（原来 200ms 已经不够）。
            assertThat(waitForFile(target, 5000)).as("导出文件应当被写出来").isTrue();
            String csv = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(csv).startsWith("id,name");
            assertThat(csv).contains("User 001");

            Label summary = onFx(() -> (Label) wizard.content().lookup("#export-summary"));
            assertThat(summary.getText()).contains(I18n.t("export.done", 200));
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportWizardAsksBeforeDumpingMoreThanTheLimit() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo5.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("huge.csv");
            AtomicReference<String> asked = new AtomicReference<>();
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, target,
                    executor, message -> { },
                    scope -> 2_000_000L,
                    message -> {
                        asked.set(message);
                        return false;              // 用户点了「取消」
                    }));

            onFxRun(() -> {
                TextField field = (TextField) wizard.content().lookup("#export-target");
                field.setText(target.toString());
                clickButton(wizard, "export-start");
            });

            assertThat(waitForSummary(wizard, I18n.t("export.cancelled", ""), 5000))
                    .as("超过阈值必须先问，用户取消就不该写文件").isTrue();
            assertThat(asked.get())
                    .as("提示里必须写出真实行数，不能只问一个抽象的「确定吗」")
                    .contains("2,000,000");
            assertThat(Files.exists(target)).as("取消了就不该有文件").isFalse();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportWizardProceedsWhenTheUserConfirms() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo6.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("confirmed.csv");
            AtomicReference<String> asked = new AtomicReference<>();
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, target,
                    executor, message -> { },
                    scope -> 2_000_000L,
                    message -> {
                        asked.set(message);
                        return true;               // 用户点了「确定」
                    }));

            onFxRun(() -> {
                TextField field = (TextField) wizard.content().lookup("#export-target");
                field.setText(target.toString());
                clickButton(wizard, "export-start");
            });

            assertThat(waitForFile(target, 5000)).as("确认之后应当真的导出").isTrue();
            assertThat(asked.get()).isNotNull();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportWizardDoesNotAskWhenTheRowCountIsSmall() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo7.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("small.csv");
            AtomicReference<String> asked = new AtomicReference<>();
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id"), ExportWizard.Scope.WHOLE_TABLE, target,
                    executor, message -> { },
                    scope -> 10L,
                    message -> {
                        asked.set(message);
                        return false;
                    }));

            onFxRun(() -> {
                TextField field = (TextField) wizard.content().lookup("#export-target");
                field.setText(target.toString());
                clickButton(wizard, "export-start");
            });

            assertThat(waitForFile(target, 5000)).as("没超阈值就不该拦").isTrue();
            assertThat(asked.get()).as("没超阈值不该弹确认").isNull();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportWizardCountsOnlyTheWholeTable() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo8.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id"), ExportWizard.Scope.WHOLE_TABLE, null,
                    executor, message -> { }));

            long whole = wizard.countRows(ExportWizard.Scope.WHOLE_TABLE);
            Object direct = session.query("SELECT COUNT(*) AS c FROM users", 1)
                    .rows().get(0).get("c");
            assertThat(whole).isEqualTo(((Number) direct).longValue()).isPositive();

            // 当前页 / 选中行都受页大小约束（≤1000），永远不该触发确认。
            assertThat(wizard.countRows(ExportWizard.Scope.CURRENT_PAGE)).isZero();
            assertThat(wizard.countRows(ExportWizard.Scope.SELECTED)).isZero();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportWizardRefusesToStartWithoutATarget() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo2.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id"), ExportWizard.Scope.WHOLE_TABLE, null,
                    executor, message -> { }));
            onFxRun(() -> {
                TextField field = (TextField) wizard.content().lookup("#export-target");
                field.setText("");
                clickButton(wizard, "export-start");
            });
            Label summary = onFx(() -> (Label) wizard.content().lookup("#export-summary"));
            assertThat(summary.getText()).contains(I18n.t("export.summary.pickTarget"));
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void importWizardPreviewsTheFileAndMapsColumns() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo3.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("in.csv");
            Files.writeString(file, "name,id\nNew Person,999\n", StandardCharsets.UTF_8);

            List<ColumnDef> columns = List.of(
                    new ColumnDef("id", ColumnType.NUMBER, false, true, null, null,
                            null, null, null, null, false, "INTEGER"),
                    new ColumnDef("name", ColumnType.TEXT, false, false, null, null,
                            null, null, null, null, false, "TEXT"));

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "users", columns,
                    List.of("users"), file, executor, message -> { }));
            onFxRun(() -> wizard.refreshPreview());

            TextArea preview = onFx(() -> (TextArea) wizard.content().lookup("#import-preview"));
            assertThat(preview.getText()).contains("New Person");

            Label summary = onFx(() -> (Label) wizard.content().lookup("#import-summary"));
            assertThat(summary.getText()).contains(I18n.tPlural("import.summary.mapped", 2, 2));
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    // ───────────────────── R64-b D-67：预览必须有真实可达的触发链 ─────────────────────

    /**
     * ⭐ D-67：生产路径（{@code TransferHub.importSingle} → 用户手输文件路径）以前
     * 永远不会刷预览 —— {@code refreshPreview()} 全仓唯一调用点在 {@code poseForShots}，
     * 「预览 + 字段映射」真实使用时永远空白。上面那批旧测试全都**手动调**
     * {@code refreshPreview()}，恰好把这条断链守漏了；这四条钉的是
     * **只操作界面控件、一次都不手动刷** 的口径。
     * 变异：逐个摘掉 文件/编码/格式/表头行 四个监听 → 各自只红自己这条。
     */
    @Test
    void importWizardPreviewsWhenTheUserTypesAFilePath() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d67-file.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("typed.csv");
            Files.writeString(file, "id,name\n910,Typed Row\n", StandardCharsets.UTF_8);

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "users", userColumns(),
                    List.of("users"), null, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> ((TextField) wizard.content().lookup("#import-file"))
                    .setText(file.toString()));

            TextArea preview = onFx(() ->
                    (TextArea) wizard.content().lookup("#import-preview"));
            assertThat(preview.getText()).as("D-67：敲完路径预览就该有内容")
                    .contains("910 | Typed Row");
            Label summary = onFx(() -> (Label) wizard.content().lookup("#import-summary"));
            assertThat(summary.getText()).contains(I18n.tPlural("import.summary.mapped", 2, 2));
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void importPreviewRedecodesWhenTheCharsetChanges() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d67-charset.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("gbk-typed.csv");
            Files.write(file, "id,name\n911,张三\n".getBytes(
                    java.nio.charset.Charset.forName("GBK")));

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "people", userColumns(),
                    List.of("people"), null, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> {
                ((TextField) wizard.content().lookup("#import-file")).setText(file.toString());
                ((ComboBox<java.nio.charset.Charset>) wizard.content().lookup("#import-charset"))
                        .getSelectionModel().select(java.nio.charset.Charset.forName("GBK"));
            });

            TextArea preview = onFx(() ->
                    (TextArea) wizard.content().lookup("#import-preview"));
            assertThat(preview.getText()).as("D-67：换编码必须按新编码重刷，不能停在乱码首刷")
                    .contains("张三");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void importPreviewResplitsWhenTheFormatChanges() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d67-format.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("typed.tsv");
            Files.writeString(file, "id\tname\n912\tZhao\n", StandardCharsets.UTF_8);

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "users", userColumns(),
                    List.of("users"), null, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> {
                ((TextField) wizard.content().lookup("#import-file")).setText(file.toString());
                ((ComboBox<ImportFormat>) wizard.content().lookup("#import-format"))
                        .getSelectionModel().select(ImportFormat.TSV);
            });

            TextArea preview = onFx(() ->
                    (TextArea) wizard.content().lookup("#import-preview"));
            assertThat(preview.getText()).as("D-67：CSV→TSV 必须按制表符重新切分")
                    .contains("912 | Zhao");
            Label summary = onFx(() -> (Label) wizard.content().lookup("#import-summary"));
            assertThat(summary.getText()).contains(I18n.tPlural("import.summary.mapped", 2, 2));
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void importPreviewRemapsWhenTheHeaderRowChanges() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d67-header.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("titled.csv");
            Files.writeString(file, "导出时间 2026-11-05\nid,name\n913,Li\n",
                    StandardCharsets.UTF_8);

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "people", userColumns(),
                    List.of("people"), null, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> {
                ((TextField) wizard.content().lookup("#import-file")).setText(file.toString());
                ((javafx.scene.control.Spinner<Integer>)
                        wizard.content().lookup("#import-header-row"))
                        .getValueFactory().setValue(2);
            });

            TextArea preview = onFx(() ->
                    (TextArea) wizard.content().lookup("#import-preview"));
            assertThat(preview.getText()).contains("913 | Li")
                    .as("D-67：表头行 1→2 后表头行本身要退出预览数据区")
                    .doesNotContain("id | name");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void transferHubOffersRealImportAndExport() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo4.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            TransferHub hub = onFx(() -> new TransferHub(session, List.of("users", "orders"),
                    executor, message -> { }));

            ComboBox<String> tables = onFx(() ->
                    (ComboBox<String>) hub.content().lookup("#transfer-table"));
            assertThat(tables.getItems()).containsExactly("users", "orders");
            assertThat(onFx(() -> hub.content().lookup("#transfer-export"))).isNotNull();
            assertThat(onFx(() -> hub.content().lookup("#transfer-import"))).isNotNull();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportAndImportXlsxThroughTheWizards() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo9.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("users.xlsx");
            ExportWizard export = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, file,
                    executor, message -> { }));
            onFxRun(() -> {
                ComboBox<ExportFormat> formats = (ComboBox<ExportFormat>)
                        export.content().lookup("#export-format");
                formats.getSelectionModel().select(ExportFormat.XLSX);
                ((TextField) export.content().lookup("#export-target"))
                        .setText(file.toString());
                clickButton(export, "export-start");
            });

            assertThat(waitForFile(file, 5000)).as("xlsx 文件应当被写出来").isTrue();
            assertThat(Files.readAllBytes(file)[0])
                    .as("xlsx 是 zip 容器，首字节应该是 P")
                    .isEqualTo((byte) 'P');

            session.execute("CREATE TABLE users_copy (id INTEGER PRIMARY KEY, name TEXT)");
            List<ColumnDef> columns = List.of(
                    new ColumnDef("id", ColumnType.NUMBER, false, true, null, null,
                            null, null, null, null, false, "INTEGER"),
                    new ColumnDef("name", ColumnType.TEXT, false, false, null, null,
                            null, null, null, null, false, "TEXT"));
            ImportWizard importWizard = onFx(() -> new ImportWizard(session, "users_copy",
                    columns, List.of("users_copy"), file, executor, message -> { }));
            onFxRun(() -> {
                ComboBox<ImportFormat> formats = (ComboBox<ImportFormat>)
                        importWizard.content().lookup("#import-format");
                formats.getSelectionModel().select(ImportFormat.XLSX);
                importWizard.refreshPreview();
            });

            TextArea preview = onFx(() ->
                    (TextArea) importWizard.content().lookup("#import-preview"));
            assertThat(preview.getText()).as("预览应走 xlsx 读取路径").contains("User 001");

            onFxRun(() -> ((javafx.scene.control.Button)
                    importWizard.content().lookup("#import-start")).fire());
            assertThat(waitForLabel(importWizard.content(), "#import-summary",
                    I18n.t("import.done", 200), 5000)).isTrue();
            assertThat(session.query("SELECT COUNT(*) AS c FROM users_copy", 1)
                    .rows().getFirst().get("c")).isEqualTo(200);
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    // ───────────────────── R33-G6：范围 / 字段 / 编码 / 格式 ─────────────────────

    @Test
    void selectedScopeWritesOnlyTheRowsHandedToTheWizard() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("scope1.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("selected.csv");
            // 网格里的「选中行」就是这两行 —— 向导必须原样写出，而不是回头去查整表
            List<List<Object>> rows = List.of(List.of(7, "Picked Seven"), List.of(8, "Picked Eight"));
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.SELECTED, target, executor,
                    message -> { }, (scope, columns) -> rows));

            onFxRun(() -> {
                ((ComboBox<ExportWizard.Scope>) wizard.content().lookup("#export-scope"))
                        .getSelectionModel().select(ExportWizard.Scope.SELECTED);
                ((TextField) wizard.content().lookup("#export-target")).setText(target.toString());
                clickButton(wizard, "export-start");
            });

            assertThat(waitForFile(target, 5000)).isTrue();
            String csv = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(csv).contains("Picked Seven").contains("Picked Eight")
                    .as("⭐ 选「选中行」却导出整表，是这一版要根除的谎")
                    .doesNotContain("User 001");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void currentPageScopeUsesTheSnapshotTakenWhenTheUserClicked() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("scope2.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("page.csv");
            List<List<Object>> rows = List.of(List.of(1, "Page One"));
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.CURRENT_PAGE, target, executor,
                    message -> { }, (scope, columns) -> rows));

            onFxRun(() -> {
                ((TextField) wizard.content().lookup("#export-target")).setText(target.toString());
                clickButton(wizard, "export-start");
            });

            assertThat(waitForFile(target, 5000)).isTrue();
            String csv = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(csv).contains("Page One").doesNotContain("User 002");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void withoutAGridOnlyTheWholeTableScopeIsOffered() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("scope3.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id"), ExportWizard.Scope.SELECTED, null, executor, message -> { }));
            ComboBox<ExportWizard.Scope> scopes = onFx(() ->
                    (ComboBox<ExportWizard.Scope>) wizard.content().lookup("#export-scope"));

            assertThat(scopes.getItems()).containsExactly(ExportWizard.Scope.WHOLE_TABLE);
            assertThat(scopes.getValue()).isEqualTo(ExportWizard.Scope.WHOLE_TABLE);
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void columnCheckboxesDecideWhichFieldsAreWritten() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("cols.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("cols.csv");
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, target,
                    executor, message -> { }));

            onFxRun(() -> {
                ScrollPane columns = (ScrollPane) wizard.content().lookup("#export-columns");
                VBox box = (VBox) columns.getContent();
                ((CheckBox) box.getChildren().get(1)).setSelected(false);   // 取消 name
                ((TextField) wizard.content().lookup("#export-target")).setText(target.toString());
                clickButton(wizard, "export-start");
            });

            assertThat(waitForFile(target, 5000)).isTrue();
            String csv = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(csv.lines().findFirst().orElse("")).isEqualTo("id");
            assertThat(csv).doesNotContain("User 001");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void uncheckingEveryColumnRefusesBeforeWritingAnything() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("cols0.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("none.csv");
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, target,
                    executor, message -> { }));

            onFxRun(() -> {
                ((javafx.scene.control.Button)
                        wizard.content().lookup("#export-columns-none")).fire();
                ((TextField) wizard.content().lookup("#export-target")).setText(target.toString());
                clickButton(wizard, "export-start");
            });

            Label summary = onFx(() -> (Label) wizard.content().lookup("#export-summary"));
            assertThat(summary.getText()).contains(I18n.t("export.summary.pickField"));
            assertThat(Files.exists(target)).isFalse();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void gbkIsWrittenWithTheChosenEncoding() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("gbk.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path target = temp.resolve("gbk.csv");
            List<List<Object>> rows = List.of(List.of(1, "张三"));
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.SELECTED, target, executor,
                    message -> { }, (scope, columns) -> rows));

            onFxRun(() -> {
                ((ComboBox<java.nio.charset.Charset>) wizard.content().lookup("#export-charset"))
                        .getSelectionModel().select(java.nio.charset.Charset.forName("GBK"));
                ((TextField) wizard.content().lookup("#export-target")).setText(target.toString());
                ((CheckBox) wizard.content().lookup("#export-header")).setSelected(false);
                clickButton(wizard, "export-start");
            });

            assertThat(waitForFile(target, 5000)).isTrue();
            byte[] bytes = Files.readAllBytes(target);
            String gbk = new String(bytes, java.nio.charset.Charset.forName("GBK"));
            // UTF-8 解码用「替换非法字节」的方式，不抛异常 —— 这里要看的正是它解不出来
            String utf8Guess = new String(bytes, StandardCharsets.UTF_8);
            assertThat(gbk).contains("张三");
            assertThat(utf8Guess).as("按 GBK 写出的文件用 UTF-8 读会乱码").doesNotContain("张三");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    /**
     * ⭐ R33：选项格必须只在自己真生效的格式下可勾。
     *
     * <p>XML/HTML 的表头由元素结构自带、xlsx 写死表头、SQL 的列名在 INSERT 里 ——
     * 「写表头行」对这些格式是**勾了不生效的谎**（p8-export 实拍：XML 下那格还打着勾）。
     */
    @Test
    void headerOptionIsOnlyAvailableForDelimitedText() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("options.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE,
                    temp.resolve("options.xml"), executor, message -> { }));
            ComboBox<ExportFormat> formats = onFx(() ->
                    (ComboBox<ExportFormat>) wizard.content().lookup("#export-format"));
            CheckBox header = onFx(() ->
                    (CheckBox) wizard.content().lookup("#export-header"));

            onFxRun(() -> formats.getSelectionModel().select(ExportFormat.CSV));
            assertThat(header.isDisabled()).as("CSV 的「写表头行」是真选项").isFalse();

            for (ExportFormat format : List.of(ExportFormat.XML, ExportFormat.HTML,
                    ExportFormat.XLSX, ExportFormat.SQL)) {
                onFxRun(() -> formats.getSelectionModel().select(format));
                assertThat(header.isDisabled())
                        .as("%s 的表头不是这个开关决定的，格子必须置灰", format)
                        .isTrue();
            }
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void xmlAndHtmlAreOfferedAndActuallyWritten() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("xml.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path xmlTarget = temp.resolve("out.xml");
            ExportWizard export = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, xmlTarget,
                    executor, message -> { }));
            ComboBox<ExportFormat> formats = onFx(() ->
                    (ComboBox<ExportFormat>) export.content().lookup("#export-format"));
            assertThat(formats.getItems()).contains(ExportFormat.XML, ExportFormat.HTML);

            onFxRun(() -> {
                formats.getSelectionModel().select(ExportFormat.XML);
                ((TextField) export.content().lookup("#export-target")).setText(xmlTarget.toString());
                clickButton(export, "export-start");
            });

            assertThat(waitForFile(xmlTarget, 5000)).isTrue();
            assertThat(Files.readString(xmlTarget, StandardCharsets.UTF_8))
                    .contains("<rows>").contains("<c name=\"name\">");

            Path htmlTarget = temp.resolve("out.html");
            ExportWizard second = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, htmlTarget,
                    executor, message -> { }));
            onFxRun(() -> {
                ((ComboBox<ExportFormat>) second.content().lookup("#export-format"))
                        .getSelectionModel().select(ExportFormat.HTML);
                ((TextField) second.content().lookup("#export-target"))
                        .setText(htmlTarget.toString());
                clickButton(second, "export-start");
            });

            assertThat(waitForFile(htmlTarget, 5000)).isTrue();
            assertThat(Files.readString(htmlTarget, StandardCharsets.UTF_8))
                    .contains("<thead><tr><th>id</th><th>name</th></tr></thead>");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    // ───────────────────── R33-G5：编码 / 表头行 / 模式 / 配置 / 日志 ─────────────────────

    @Test
    void gbkFileImportsWithoutManglingChinese() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("imp-gbk.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("in-gbk.csv");
            Files.write(file, "id,name\n901,张三\n".getBytes(java.nio.charset.Charset.forName("GBK")));
            session.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "people", userColumns(),
                    List.of("people"), file, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> {
                ((ComboBox<java.nio.charset.Charset>) wizard.content().lookup("#import-charset"))
                        .getSelectionModel().select(java.nio.charset.Charset.forName("GBK"));
                ((javafx.scene.control.Button) wizard.content().lookup("#import-start")).fire();
            });

            assertThat(waitForLabel(wizard.content(), "#import-summary",
                    I18n.t("import.done", 1), 5000)).isTrue();
            assertThat(session.query("SELECT name FROM people WHERE id = 901", 1)
                    .rows().getFirst().get("name")).isEqualTo("张三");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void headerRowSkipsTheTitleLineAndStillMapsFields() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("imp-header.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("titled.csv");
            Files.writeString(file, "导出时间 2026-10-28\nid,name\n902,李四\n",
                    StandardCharsets.UTF_8);
            session.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "people", userColumns(),
                    List.of("people"), file, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> {
                ((javafx.scene.control.Spinner<Integer>)
                        wizard.content().lookup("#import-header-row")).getValueFactory().setValue(2);
                wizard.refreshPreview();
            });

            Label summary = onFx(() -> (Label) wizard.content().lookup("#import-summary"));
            assertThat(summary.getText()).contains(I18n.tPlural("import.summary.mapped", 2, 2));

            onFxRun(() -> ((javafx.scene.control.Button)
                    wizard.content().lookup("#import-start")).fire());
            assertThat(waitForLabel(wizard.content(), "#import-summary",
                    I18n.t("import.done", 1), 5000)).isTrue();
            assertThat(session.query("SELECT name FROM people WHERE id = 902", 1)
                    .rows().getFirst().get("name")).isEqualTo("李四");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void replaceModeEmptiesTheTargetTableThroughTheWizard() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("imp-replace.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("one.csv");
            Files.writeString(file, "id,name\n903,王五\n", StandardCharsets.UTF_8);
            session.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");
            session.execute("INSERT INTO people(id, name) VALUES (1, '旧一')");
            session.execute("INSERT INTO people(id, name) VALUES (2, '旧二')");

            ImportWizard wizard = onFx(() -> new ImportWizard(session, "people", userColumns(),
                    List.of("people"), file, executor, message -> { },
                    new TransferProfiles(), report -> { }));
            onFxRun(() -> {
                ((ComboBox<io.agenticdb.transfer.ImportMode>) wizard.content()
                        .lookup("#import-mode")).getSelectionModel()
                        .select(io.agenticdb.transfer.ImportMode.REPLACE);
                ((javafx.scene.control.Button) wizard.content().lookup("#import-start")).fire();
            });

            assertThat(waitForLabel(wizard.content(), "#import-summary",
                    I18n.t("import.done", 1), 5000)).isTrue();
            assertThat(session.query("SELECT COUNT(*) AS c FROM people", 1)
                    .rows().getFirst().get("c")).as("覆盖模式先清空旧数据").isEqualTo(1);
            assertThat(session.query("SELECT name FROM people", 1)
                    .rows().getFirst().get("name")).isEqualTo("王五");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void importLogRecordsTheOutcomeAndTheBadRows() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("imp-log.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path file = temp.resolve("mixed.csv");
            Files.writeString(file, "id,name\n904,ok\nbad,oops\n", StandardCharsets.UTF_8);
            session.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");

            AtomicReference<TableImporter.ImportReport> reported = new AtomicReference<>();
            ImportWizard wizard = onFx(() -> new ImportWizard(session, "people", userColumns(),
                    List.of("people"), file, executor, message -> { },
                    new TransferProfiles(), reported::set));
            onFxRun(() -> ((javafx.scene.control.Button)
                    wizard.content().lookup("#import-start")).fire());

            assertThat(waitForLabel(wizard.content(), "#import-summary",
                    I18n.t("import.done.skipped", 1), 5000)).isTrue();
            assertThat(reported.get()).as("坏行汇总要走注入的去向，不能直接弹模态框")
                    .isNotNull();
            assertThat(reported.get().skipped()).isEqualTo(1);
            TextArea logArea = onFx(() ->
                    (TextArea) wizard.content().lookup("#import-log"));
            assertThat(logArea.getText())
                    .contains(I18n.t("import.log.start", "people",
                            ImportFormat.CSV.label(), "UTF-8", "1",
                            I18n.t(io.agenticdb.transfer.ImportMode.APPEND.labelKey())))
                    .contains(I18n.t("import.done", 1))
                    .as("坏行明细要进日志，用户能把它导出给同事看").contains("第 2 行");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void profilesSurviveAReloadAndBadLinesAreDropped() {
        String previous = System.getProperty("adb.prefs.node");
        System.setProperty("adb.prefs.node", "adb-import-profile-gate-" + System.nanoTime());
        try {
            TransferProfiles profiles = new TransferProfiles(new SettingsStore());
            assertThat(profiles.list()).isEmpty();

            TransferProfiles.ImportProfile gbk = new TransferProfiles.ImportProfile(
                    "GBK 导出文件", ImportFormat.CSV, "GBK", 2,
                    io.agenticdb.transfer.ImportMode.REPLACE);
            profiles.save(gbk);
            profiles.save(new TransferProfiles.ImportProfile(
                    "UTF-8 无表头", ImportFormat.TSV, "UTF-8", 0,
                    io.agenticdb.transfer.ImportMode.APPEND));

            TransferProfiles reloaded = new TransferProfiles(new SettingsStore());
            assertThat(reloaded.list()).hasSize(2);
            TransferProfiles.ImportProfile first = reloaded.list().getFirst();
            assertThat(first.name()).isEqualTo("GBK 导出文件");
            assertThat(first.charset()).isEqualTo("GBK");
            assertThat(first.headerRow()).isEqualTo(2);
            assertThat(first.mode()).isEqualTo(io.agenticdb.transfer.ImportMode.REPLACE);

            // 同名保存 = 覆盖，不是再加一条
            reloaded.save(new TransferProfiles.ImportProfile("GBK 导出文件",
                    ImportFormat.CSV, "UTF-8", 1, io.agenticdb.transfer.ImportMode.APPEND));
            assertThat(reloaded.list()).hasSize(2);
            assertThat(reloaded.list().getFirst().charset()).isEqualTo("UTF-8");

            assertThat(TransferProfiles.decode("坏行\t没有格式名")).isNull();
            assertThat(TransferProfiles.decode("")).isNull();

            reloaded.remove("GBK 导出文件");
            assertThat(new TransferProfiles(new SettingsStore()).list()).hasSize(1);
        } finally {
            if (previous == null) {
                System.clearProperty("adb.prefs.node");
            } else {
                System.setProperty("adb.prefs.node", previous);
            }
        }
    }

    @Test
    void wizardHeadingsStopPretendingThereAreSteps() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d58a.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard export = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE,
                    temp.resolve("d58a.csv"), executor, message -> { }));
            ImportWizard importWizard = onFx(() -> new ImportWizard(session, "users",
                    userColumns(), List.of("users"), null, executor, message -> { }));

            String exportHeader = onFx(() -> ((javafx.scene.control.DialogPane)
                    export.content().getParent()).getHeaderText());
            String importHeader = onFx(() -> ((javafx.scene.control.DialogPane)
                    importWizard.content().getParent()).getHeaderText());

            // ⭐ D-58：两个向导都是**一页到底**，标题却写着「A → B → C」的步骤条 ——
            // 用户找不到「下一步」，这行字就是谎言。箭头不许再出现在标题里。
            assertThat(exportHeader).as("导出向导标题").doesNotContain("→");
            assertThat(importHeader).as("导入向导标题").doesNotContain("→");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void disabledWizardOptionsSayWhyTheyAreDisabled() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d58b.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE,
                    temp.resolve("d58b.csv"), executor, message -> { }));

            // 只动 #export-format 这一颗真实下拉，不手动调 fitOptionAvailability。
            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<ExportFormat> format =
                        (ComboBox<ExportFormat>) wizard.content().lookup("#export-format");
                format.setValue(ExportFormat.XML);
            });

            onFx(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<ExportFormat> format =
                        (ComboBox<ExportFormat>) wizard.content().lookup("#export-format");
                javafx.scene.control.CheckBox header =
                        (javafx.scene.control.CheckBox) wizard.content().lookup("#export-header");
                javafx.scene.control.CheckBox quote =
                        (javafx.scene.control.CheckBox) wizard.content().lookup("#export-quote");
                assertThat(format.getValue()).isEqualTo(ExportFormat.XML);
                assertThat(header.isDisabled()).as("XML 下「写表头行」应禁用").isTrue();
                assertThat(quote.isDisabled()).as("XML 下「全加引号」应禁用").isTrue();
                return null;
            });

            // 禁用不是静默的：行内要有一行说明为什么（与 NewTableDialog 的 prompt 族同式）。
            // ⭐ 这里用 lookup 是安全的：断言的前提就是「XML 下这行说明必须可见」，
            //   而 lookup 会跳过不可见节点 —— 一条都不许溜号。
            javafx.scene.control.Label note = onFx(() ->
                    (javafx.scene.control.Label) wizard.content().lookup("#export-option-note"));
            assertThat(note).as("禁用项必须带行内说明").isNotNull();
            assertThat(note.isVisible()).as("禁用说明要可见").isTrue();
            assertThat(note.isManaged()).isTrue();
            assertThat(note.getText()).contains(I18n.t("export.option.note.textonly"));

            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<ExportFormat> format =
                        (ComboBox<ExportFormat>) wizard.content().lookup("#export-format");
                format.setValue(ExportFormat.CSV);
            });
            javafx.scene.Node back = onFx(() -> wizard.content().lookup("#export-option-note"));
            assertThat(back == null || !back.isVisible())
                    .as("回到 CSV：没有禁用项了，说明要收起来").isTrue();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void formatHintsReachTheScreenInBothWizards() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d59a.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard export = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE,
                    temp.resolve("d59a.csv"), executor, message -> { }));
            ImportWizard importWizard = onFx(() -> new ImportWizard(session, "users",
                    userColumns(), List.of("users"), null, executor, message -> { }));

            // ⭐ D-59/D-83：ExportFormat/ImportFormat 的 hint 以前只躺在枚举里（零消费者）。
            // 换格式要**真的**把那句话打到屏上，而且走的是消息键，不是枚举里的中文字面量。
            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<ExportFormat> format =
                        (ComboBox<ExportFormat>) export.content().lookup("#export-format");
                format.setValue(ExportFormat.XML);
            });
            Label exportHint = onFx(() ->
                    (Label) export.content().lookup("#export-format-hint"));
            assertThat(exportHint).as("导出向导要有格式说明行").isNotNull();
            assertThat(exportHint.getText())
                    .isEqualTo(I18n.t("export.format.xml.hint"))
                    .as("提示必须真的解析成文案，不是键名漏网")
                    .isNotEqualTo("export.format.xml.hint");

            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<ImportFormat> format =
                        (ComboBox<ImportFormat>) importWizard.content().lookup("#import-format");
                format.setValue(ImportFormat.XLSX);
            });
            Label importHint = onFx(() ->
                    (Label) importWizard.content().lookup("#import-format-hint"));
            assertThat(importHint).as("导入向导也要有格式说明行").isNotNull();
            assertThat(importHint.getText())
                    .isEqualTo(I18n.t("import.format.xlsx.hint"))
                    .isNotEqualTo("import.format.xlsx.hint");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    /**
     * ⭐ D-73（2026-11-05）：两个向导的路径格不许只有手打。
     *
     * <p>帧证 `p8-export` / `p8-import`：Target File / File 两行行尾**没有浏览按钮**，
     * 路径只能一个字符一个字符敲 —— 而同仓 `ConnectionWizard` 早就有 `#wizard-browse`
     * 的正确先例（HBox + ghost 按钮 + FileChooser 回填）。向导族里只有连接向导能选文件，
     * 这就是「同一件事两套做法」的形状，不是缺功能。
     */
    @Test
    void bothWizardsOfferAFileChooserForTheirPathField() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d73a.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard export = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE,
                    temp.resolve("d73a.csv"), executor, message -> { }));
            ImportWizard importWizard = onFx(() -> new ImportWizard(session, "users",
                    userColumns(), List.of("users"), null, executor, message -> { }));

            // 按钮必须**和路径格在同一行**（不是页面上随便一处），否则等于没在补这条。
            // 不写死层数：改成「从路径格往上走，能不能在某一层碰到那颗按钮」——
            // 钉死 parent.parent 的话，实现换个包法门禁就红在版式上而不是行为上。
            for (Object[] pair : List.of(
                    new Object[] {export.content(), "#export-target", "#export-target-browse"},
                    new Object[] {importWizard.content(), "#import-file", "#import-file-browse"})) {
                VBox content = (VBox) pair[0];
                javafx.scene.Node field = onFx(() -> content.lookup((String) pair[1]));
                javafx.scene.Node button = onFx(() -> content.lookup((String) pair[2]));
                assertThat(button)
                        .as(pair[2] + " 必须存在（帧上实拍路径行行尾是空的）")
                        .isInstanceOf(javafx.scene.control.Button.class);
                assertThat(sharesARowWith(field, button))
                        .as(pair[2] + " 必须排在 " + pair[1] + " 同一行右侧")
                        .isTrue();
            }
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    /** 从 field 往上走最多三层，看这一支里是否也装着 button。 */
    private static boolean sharesARowWith(javafx.scene.Node field, javafx.scene.Node button) {
        javafx.scene.Node cursor = field.getParent();
        for (int depth = 0; cursor != null && depth < 3; depth++, cursor = cursor.getParent()) {
            if (cursor instanceof javafx.scene.layout.Pane pane
                    && pane.getChildren().contains(button)) {
                return true;
            }
        }
        return false;
    }

    /**
     * D-73：按下浏览 → 选中的文件真的回填进路径格。
     *
     * <p>选择器走**注入替身**（`setFileChooserForTest`）：Monocle 按不住原生面板，
     * 真弹窗在这台机器上永远等不到结果 —— 但替身必须被调用到，
     * 否则「按钮存在但点了什么也不做」照样能绿（所以断言里数了调用次数）。
     */
    @Test
    void browseButtonsFillThePathFromTheChosenFile() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d73b.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path chosen = temp.resolve("chosen.csv");
            Files.writeString(chosen, "id,name\n911,Picked Row\n", StandardCharsets.UTF_8);

            ExportWizard export = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE, null,
                    executor, message -> { }));
            ImportWizard importWizard = onFx(() -> new ImportWizard(session, "users",
                    userColumns(), List.of("users"), null, executor, message -> { }));

            AtomicReference<Integer> exportCalls = new AtomicReference<>(0);
            AtomicReference<Integer> importCalls = new AtomicReference<>(0);
            onFxRun(() -> {
                export.setFileChooserForTest(window -> {
                    exportCalls.set(exportCalls.get() + 1);
                    return chosen.toFile();
                });
                importWizard.setFileChooserForTest(window -> {
                    importCalls.set(importCalls.get() + 1);
                    return chosen.toFile();
                });
            });

            onFxRun(() -> ((javafx.scene.control.Button)
                    export.content().lookup("#export-target-browse")).fire());
            TextField exportField = onFx(() -> (TextField) export.content().lookup("#export-target"));
            assertThat(exportCalls.get()).as("浏览必须真的走选择器，不是硬塞一个路径").isEqualTo(1);
            assertThat(exportField.getText())
                    .as("选中的文件要回填进导出目标格（含当前格式的扩展名）")
                    .startsWith(chosen.toAbsolutePath().toString());

            // ⭐ 浏览这条路也要过后缀闸：选一个**已知导出后缀**与当前格式不符的文件，
            //    回填的要折成当前格式的 .csv（`withExtension` 的既有规则：只动已知后缀，
            //    用户自定义名如 `report.dat` 原样保留）。少了这句，
            //    「浏览直接 setText(picked.getAbsolutePath())」也能全绿 ——
            //    界面标着 CSV、落盘文件叫 .json，导出完用户自己找不到东西。
            Path knownWrong = temp.resolve("picked-report.json");
            onFxRun(() -> export.setFileChooserForTest(window -> knownWrong.toFile()));
            onFxRun(() -> ((javafx.scene.control.Button)
                    export.content().lookup("#export-target-browse")).fire());
            assertThat(exportField.getText())
                    .as("选中已知后缀与格式不符的文件时，要按当前格式改写后缀（默认 CSV）")
                    .isEqualTo(temp.resolve("picked-report.csv").toAbsolutePath().toString());

            onFxRun(() -> ((javafx.scene.control.Button)
                    importWizard.content().lookup("#import-file-browse")).fire());
            TextField importField = onFx(() -> (TextField) importWizard.content().lookup("#import-file"));
            assertThat(importCalls.get()).isEqualTo(1);
            assertThat(importField.getText()).isEqualTo(chosen.toAbsolutePath().toString());

            // ⭐ 回填必须走 D-67 那条真实联动（监听器），不是测试自己手动刷预览。
            TextArea preview = onFx(() -> (TextArea) importWizard.content().lookup("#import-preview"));
            assertThat(preview.getText())
                    .as("浏览选完文件，预览就该自己出来（点了按钮还要手敲才算数 = 假按钮）")
                    .contains("911 | Picked Row");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void importModeComboSpeaksTheUsersLanguage() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d59b.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ImportWizard wizard = onFx(() -> new ImportWizard(session, "users",
                    userColumns(), List.of("users"), null, executor, message -> { }));

            // ⭐ D-59：ImportMode 的中文名以前硬编码在模型层，英文界面直接露汉字。
            // 枚举只许存键，渲染由界面解析 —— 下拉显示的必须是当前语言的文案。
            @SuppressWarnings("unchecked")
            ComboBox<io.agenticdb.transfer.ImportMode> mode = onFx(() ->
                    (ComboBox<io.agenticdb.transfer.ImportMode>)
                            wizard.content().lookup("#import-mode"));
            assertThat(mode.getConverter().toString(io.agenticdb.transfer.ImportMode.REPLACE))
                    .isEqualTo(I18n.t("import.mode.replace"))
                    .isNotEqualTo("import.mode.replace");
            assertThat(mode.getConverter().toString(io.agenticdb.transfer.ImportMode.APPEND))
                    .isEqualTo(I18n.t("import.mode.append"));

            // 禁用要给原因：XLSX 把编码框灰掉时，行内必须说一句为什么（D-58 导入侧）。
            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                ComboBox<ImportFormat> format =
                        (ComboBox<ImportFormat>) wizard.content().lookup("#import-format");
                format.setValue(ImportFormat.XLSX);
            });
            javafx.scene.control.ComboBox<?> charset = onFx(() ->
                    (javafx.scene.control.ComboBox<?>) wizard.content().lookup("#import-charset"));
            assertThat(charset.isDisabled()).as("XLSX 下编码应禁用").isTrue();
            Label note = onFx(() -> (Label) wizard.content().lookup("#import-option-note"));
            assertThat(note).as("禁用编码要带行内说明").isNotNull();
            assertThat(note.getText()).contains(I18n.t("transfer.option.note.binary"));
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void exportWizardPoseDoesNotFabricateLog() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d83.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            ExportWizard wizard = onFx(() -> new ExportWizard(session, "users",
                    List.of("id", "name"), ExportWizard.Scope.WHOLE_TABLE,
                    temp.resolve("d83.xml"), executor, message -> { }));

            // ⭐ D-83：pose 通道以前往日志框塞一句**手搓的**假日志（export.pose.log）。
            // 帧上看着有说明，其实是编的 —— 撤掉它，说明改由真实的格式提示行承担。
            onFxRun(() -> wizard.poseForShots("xml"));
            TextArea log = onFx(() -> (TextArea) wizard.content().lookup("#export-log"));
            assertThat(log.getText()).as("pose 不许再手搓日志").isEmpty();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    /**
     * ⭐ D-84：D-83 那只手搓假日志在导出侧，导入侧还藏着同一族的另一只 ——
     * {@code poseForShots} 往执行日志里塞 {@code import.log.pose}，参数是<b>写死的 1 和 5</b>。
     *
     * <p>演示 CSV 只有 3 行数据，于是同一张帧里 Preview 显示 3 行、
     * Execution Log 声称「5 preview rows read」—— 两张口互相打脸，
     * 而这条帧是导入向导唯一的画面证据。撤掉它：pose 没有真跑过导入，
     * 执行日志本来就该是空的，真实行数由下面那句 summary 承担。
     */
    @Test
    void importWizardPoseDoesNotFabricateLog() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("d84.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            Path csv = temp.resolve("d84.csv");
            java.nio.file.Files.writeString(csv, "id,name\n1,ann\n2,bob\n3,cid\n");
            ImportWizard wizard = onFx(() -> new ImportWizard(session, "users",
                    userColumns(), List.of("users"), csv, executor, message -> { }));

            onFxRun(() -> wizard.poseForShots(csv));
            TextArea log = onFx(() -> (TextArea) wizard.content().lookup("#import-log"));
            assertThat(log.getText()).as("pose 不许手搓执行日志").isEmpty();

            // 但真实预览数字必须在场 —— 撤假日志不能顺手把真信息也撤没。
            javafx.scene.control.Label summary = onFx(() ->
                    (javafx.scene.control.Label) wizard.content().lookup("#import-summary"));
            assertThat(summary.getText()).contains("3");
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    /** demo 库 users 表的 id / name 两列（带类型，导入才会做类型转换）。 */
    private static List<ColumnDef> userColumns() {
        return List.of(
                new ColumnDef("id", ColumnType.NUMBER, false, true, null, null,
                        null, null, null, null, false, "INTEGER"),
                new ColumnDef("name", ColumnType.TEXT, false, false, null, null,
                        null, null, null, null, false, "TEXT"));
    }

    private static void clickButton(ExportWizard wizard, String id) {
        javafx.scene.control.Button button =
                (javafx.scene.control.Button) wizard.content().lookup("#" + id);
        button.fire();
    }

    private static boolean waitForSummary(ExportWizard wizard, String fragment, long millis)
            throws InterruptedException {
        return waitForLabel(wizard.content(), "#export-summary", fragment, millis);
    }

    private static boolean waitForLabel(javafx.scene.layout.VBox content, String id,
                                        String fragment, long millis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<String> text = new AtomicReference<>();
            Platform.runLater(() -> text.set(
                    ((Label) content.lookup(id)).getText()));
            WaitForAsyncUtils.waitForFxEvents();
            if (text.get() != null && text.get().contains(fragment)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitForFile(Path path, long millis)
            throws InterruptedException, java.io.IOException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path) && Files.size(path) > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
