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
            assertThat(summary.getText()).contains("已导出");
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

            assertThat(waitForSummary(wizard, "已取消", 5000))
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
            assertThat(summary.getText()).contains("目标文件");
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
            assertThat(summary.getText()).contains(I18n.t("import.summary.mapped", 2));
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
            assertThat(summary.getText()).contains("至少勾选一个字段");
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
            assertThat(summary.getText()).contains(I18n.t("import.summary.mapped", 2));

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
                            io.agenticdb.transfer.ImportMode.APPEND.label()))
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
