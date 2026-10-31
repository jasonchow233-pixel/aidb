package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.introspect.SchemaService;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7 行级导航 + G8 批量导出的门禁。
 */
@ExtendWith(ApplicationExtension.class)
class DataGridNavigationTest {

    @TempDir
    Path temp;

    private ExecutorService executor;

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void jumpToRowMovesAcrossPages() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "nav-test", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null
                            && !((javafx.scene.control.TableView<?>)
                            page.lookup("#data-table")).getItems().isEmpty());

            assertThat(page.lookup("#data-page-jump-row"))
                    .as("⭐ N20：跳至记录收进齿轮菜单，底栏不再常驻行号输入框")
                    .isNull();

            // 用 1 行的页大小更容易验证跨页。
            page.setPageSizeForTest(1);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                            .getItems().size() == 1);

            // 非法输入不崩（状态栏给出解释）
            WaitForAsyncUtils.asyncFx(() -> {
                page.jumpToRow("abc");
                return null;
            }).get();
            WaitForAsyncUtils.waitForFxEvents();

            // 越界也不崩
            WaitForAsyncUtils.asyncFx(() -> {
                page.jumpToRow("999999");
                return null;
            }).get();
            WaitForAsyncUtils.waitForFxEvents();

            // 合法输入：跳到第 3 行 —— 跨页落到第 3 页
            WaitForAsyncUtils.asyncFx(() -> {
                page.jumpToRow("3");
                return null;
            }).get();
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
                TextField box = (TextField) page.lookup("#data-page");
                javafx.scene.control.TableView<?> table =
                        (javafx.scene.control.TableView<?>) page.lookup("#data-table");
                return box != null && "3".equals(box.getText())
                        && table.getItems().size() == 1;
            });
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void batchMenuHasExportSelected() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo2.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "nav-test-2", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null);

            assertThat(page.batchMenuForTest().getItems().stream()
                    .anyMatch(item -> "data-batch-export".equals(item.getId())))
                    .as("N16：批量菜单要有「导出选中」")
                    .isTrue();
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String message) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }
}
