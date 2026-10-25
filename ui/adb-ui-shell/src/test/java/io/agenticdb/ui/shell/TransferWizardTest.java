package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ImportFormat;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
            assertThat(summary.getText()).contains("字段已映射");
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
                    "已导入", 5000)).isTrue();
            assertThat(session.query("SELECT COUNT(*) AS c FROM users_copy", 1)
                    .rows().getFirst().get("c")).isEqualTo(200);
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

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
