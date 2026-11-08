package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H3-e：分页 COUNT 的退路 —— 每条执行只数一次，炸了不拦行、措辞如实。
 *
 * <p>立案事实：查询页每个结果页签的每次装载（首装 + 每次翻页）都包一层
 * {@code SELECT COUNT(*) FROM (…)}，网络库上翻一页付两次往返。
 * 数据页早已是「一执行只数一次」（{@code DataGridPage} 的 {@code load(false)} 翻页
 * 跳过 COUNT），查询页是漏网的那个。
 *
 * <p>门禁三条各咬一件事：
 * <ol>
 *   <li><b>翻页不重数</b>：同一执行内翻页复用 total —— COUNT 只包一次；</li>
 *   <li><b>重跑重数</b>：重跑是新的执行（新页签新装载），计数重新来 ——
 *       防止「为省 COUNT 连真相都不刷新」的过激实现；</li>
 *   <li><b>退路</b>：COUNT 炸掉行照常上屏、范围措辞落 {@code query.totalUnknown}
 *       （如实说不知道，不写成精确数），且同一次执行内不许每次翻页都重试。</li>
 * </ol>
 *
 * <p>估算档翻案记在台账：没有可移植的估算来源（SQLite 无行数估算、
 * EXPLAIN 形状各家不一），「未知」措辞已如实 —— 不建第二套口径。
 */
@ExtendWith(ApplicationExtension.class)
class QueryPagerCountTest {

    private JdbcSession real;
    private CountingSession session;
    private ExecutorService executor;
    private QueryPage page;

    @Start
    void start(Stage stage) throws Exception {
        real = JdbcSession.open(ConnectionSpec.sqliteMemory());
        try (var statement = real.connection().createStatement()) {
            statement.execute("CREATE TABLE nums (id INTEGER PRIMARY KEY, label TEXT)");
            for (int i = 1; i <= 250; i++) {
                statement.execute("INSERT INTO nums VALUES (" + i + ", 'n" + i + "')");
            }
        }
        session = new CountingSession(real);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-query-count");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "test", "", executor, new SchemaService(), null,
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
        page.setConfirmationHandler(danger -> true);
        stage.setScene(new Scene(page, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        real.close();
    }

    /** 门 E1（翻页不重数）：翻页必须复用同一执行的计数，COUNT 只包一次。 */
    @Test
    void flippingPagesReusesTheCountFromTheSameExecution() throws Exception {
        interact(() -> {
            page.setSql("SELECT * FROM nums ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);
        int afterRun = session.countQueries;
        assertThat(afterRun)
                .as("首装必须真包一次 COUNT（防门禁空转）")
                .isEqualTo(1);

        interact(() -> ((Button) page.lookup("#query-result-next")).fire());
        waitFor(() -> table.getItems().size() == 50);

        assertThat(session.countQueries)
                .as("H3-e：翻页必须复用同一执行的计数 —— 不许再包一层 COUNT")
                .isEqualTo(afterRun);
    }

    /** 门 E2（重跑重数）：重跑是新的执行 —— 计数重新来，新页签的 pager 才显示得出总页数。 */
    @Test
    void rerunningTheQueryCountsAnew() throws Exception {
        interact(() -> {
            page.setSql("SELECT * FROM nums ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);
        int afterRun = session.countQueries;

        interact(() -> page.runAll());
        // runAll 先清页签再装载（同一套结果区，页签数不涨）—— 同步点取新执行的 COUNT 落地。
        waitFor(() -> session.countQueries == afterRun + 1);

        TableView<?> fresh = (TableView<?>) page.lookup("#query-result-table");
        assertThat(fresh.getItems()).hasSize(200);
        assertThat(((Label) page.lookup("#query-result-page-label")).getText())
                .as("H3-e：重跑是新的执行 —— 新装载必须带着自己的 total（250 行 = 2 页）")
                .isEqualTo(I18n.t("query.pageOfTotal", 1, 2));
    }

    /** 门 G（退路）：COUNT 炸掉 —— 行照常上屏、措辞如实「未知」、翻页不重试。 */
    @Test
    void aFailedCountLeavesTheRowsVisibleAndWordingHonest() throws Exception {
        session.failCounts = true;
        interact(() -> {
            page.setSql("SELECT * FROM nums ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);
        Label range = (Label) page.lookup("#query-result-range");
        assertThat(range.getText())
                .as("H3-e：COUNT 退路的措辞必须如实 —— 不知道就是不知道，不写成精确数")
                .isEqualTo(I18n.t("query.totalUnknown"));
        int attempts = session.countQueries;

        interact(() -> ((Button) page.lookup("#query-result-next")).fire());
        waitFor(() -> table.getItems().size() == 50);

        assertThat(session.countQueries)
                .as("H3-e：COUNT 失败后同一次执行内不许每次翻页都重试一遍")
                .isEqualTo(attempts);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void waitFor(java.util.function.BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition::getAsBoolean);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("条件超时；状态：" + page.statusText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void waitForTabs(int count) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> page.resultTabCount() == count);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("等待结果页 " + count + " 超时；当前 "
                    + page.resultTabCount() + "，状态：" + page.statusText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 会话包装：只盯着 {@code COUNT(} 形状的查询计数（页查询不算），
     * {@code failCounts} 打开后 COUNT 一律炸 —— 门 G 用它把「退路」逼出来。
     */
    private static final class CountingSession implements DbSession {

        private final DbSession delegate;
        boolean failCounts;
        int countQueries;

        CountingSession(DbSession delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connection() {
            return delegate.connection();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        @Override
        public Dialect dialect() {
            return delegate.dialect();
        }

        @Override
        public RowPage query(String sql, int maxRows, List<Object> params) throws SQLException {
            if (sql.toUpperCase(Locale.ROOT).contains("COUNT(")) {
                countQueries++;
                if (failCounts) {
                    throw new SQLException("count exploded (test)");
                }
            }
            return delegate.query(sql, maxRows, params);
        }

        @Override
        public int update(String sql, List<Object> params) throws SQLException {
            return delegate.update(sql, params);
        }

        @Override
        public ExecutionResult execute(String sql, Danger declared) throws SQLException {
            return delegate.execute(sql, declared);
        }

        @Override
        public void begin() throws SQLException {
            delegate.begin();
        }

        @Override
        public void commit() throws SQLException {
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            delegate.rollback();
        }

        @Override
        public boolean inTransaction() throws SQLException {
            return delegate.inTransaction();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }
    }
}
