package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.model.RowPage;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class QueryPageCancelTest {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final BlockingSession session = new BlockingSession(started, release);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private QueryPage page;

    @Start
    void start(Stage stage) {
        page = new QueryPage(session, "blocking", "", executor, new io.agenticdb.introspect.SchemaService(), null,
                new DataGridPage.Feedback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void range(String text) {
                    }

                    @Override
                    public void duration(long millis) {
                    }
                });
        stage.setScene(new Scene(page, 800, 600));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        page.close();
        executor.shutdownNow();
    }

    @Test
    void cancelCallsSessionAndDropsLateResult() throws Exception {
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            page.setSql("SELECT 1;");
            page.runAll();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        WaitForAsyncUtils.waitForAsyncFx(5_000, page::cancel);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.cancelCalled).isTrue();
        assertThat(page.isRunning()).isFalse();
        assertThat(page.statusText()).contains(I18n.t("query.cancelRequested"));
        assertThat(page.resultTabCount()).isZero();
    }

    // ─────────────────── D-53 空态不能在跑着的时候骗人 ───────────────────

    @Test
    void theResultsBandSaysNothingWhileTheQueryIsInFlight() throws Exception {
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            page.setSql("SELECT 1;");
            page.runAll();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(band("query-results-empty-never-run").isVisible())
                .as("页签上一轮就清空的、这一轮还在路上 —— 此刻说「从没执行过」是骗人")
                .isFalse();
        assertThat(band("query-results-empty-cancelled").isVisible())
                .as("还没取消，不能说已取消")
                .isFalse();
        release.countDown();
    }

    @Test
    void aCancelledRunExplainsTheClearedResultsBand() throws Exception {
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            page.setSql("SELECT 1;");
            page.runAll();
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        WaitForAsyncUtils.waitForAsyncFx(5_000, page::cancel);
        WaitForAsyncUtils.waitForFxEvents();

        EmptyState cancelled = band("query-results-empty-cancelled");
        assertThat(page.resultTabCount()).isZero();
        assertThat(cancelled.isVisible())
                .as("结果带空着得说一句为什么空着，不能留一片死白")
                .isTrue();
        assertThat(((Label) cancelled.lookup(".adb-empty__title")).getText())
                .isEqualTo(I18n.t("query.empty.cancelled.title"));
        assertThat(band("query-results-empty-never-run").isVisible())
                .as("两条不能同时说话")
                .isFalse();
    }

    private EmptyState band(String id) {
        return (EmptyState) page.lookup("#" + id);
    }

    private static final class BlockingSession implements DbSession {

        private final CountDownLatch started;
        private final CountDownLatch release;
        private volatile boolean cancelCalled;

        private BlockingSession(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public Connection connection() {
            return null;
        }

        @Override
        public String label() {
            return "blocking";
        }

        @Override
        public Dialect dialect() {
            return DialectKind.SQLITE.dialect();
        }

        @Override
        public RowPage query(String sql, int maxRows, List<Object> params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(String sql, List<Object> params) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutionResult execute(String sql, io.agenticdb.model.Danger declared)
                throws SQLException {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new SQLException("cancelled");
        }

        @Override
        public void cancelCurrent() {
            cancelCalled = true;
            release.countDown();
        }

        @Override
        public void begin() {
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }

        @Override
        public boolean inTransaction() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
