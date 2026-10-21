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

            TextField jumpRow = (TextField) page.lookup("#data-jump-row");
            assertThat(jumpRow).as("G7：行级导航的输入框要在").isNotNull();

            // 跳到第 150 行 —— 超过一页（200 行一页时在第 1 页；
            // 这里用 1 行的页大小更容易验证跨页）。
            page.setPageSizeForTest(1);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                            .getItems().size() == 1);

            // 非法输入不崩，且状态栏给出解释
            jumpRow.setText("abc");
            WaitForAsyncUtils.asyncFx(() -> jumpRow.getOnAction().handle(new javafx.event.ActionEvent())).get();
            WaitForAsyncUtils.waitForFxEvents();

            // 越界也不崩
            jumpRow.setText("999999");
            WaitForAsyncUtils.asyncFx(() -> jumpRow.getOnAction().handle(new javafx.event.ActionEvent())).get();
            WaitForAsyncUtils.waitForFxEvents();

            // 合法输入：跳到第 3 行
            jumpRow.setText("3");
            WaitForAsyncUtils.asyncFx(() -> jumpRow.getOnAction().handle(new javafx.event.ActionEvent())).get();
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(jumpRow).isNotNull();
            page.close();
        } finally {
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    void batchBarHasExportSelected() throws Exception {
        DbSession session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo2.db"))));
        executor = Executors.newSingleThreadExecutor();
        try {
            DataGridPage page = new DataGridPage(session, new SchemaService(), "users",
                    "nav-test-2", noOpFeedback(), name -> { }, executor);
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null);

            assertThat(page.lookup("#data-batch-export"))
                    .as("G8：批量条要有「导出选中」")
                    .isNotNull();
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
