package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.model.RowPage;
import javafx.scene.Scene;
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
        page = new QueryPage(session, "blocking", "", executor, null,
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
        assertThat(page.statusText()).contains("取消");
        assertThat(page.resultTabCount()).isZero();
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
