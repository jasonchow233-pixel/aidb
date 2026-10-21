package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
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

            assertThat(waitForFile(target, 200)).as("导出文件应当被写出来").isTrue();
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

    // ───────────────────────────── 工具 ─────────────────────────────

    private static void clickButton(ExportWizard wizard, String id) {
        javafx.scene.control.Button button =
                (javafx.scene.control.Button) wizard.content().lookup("#" + id);
        button.fire();
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
