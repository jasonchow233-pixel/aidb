package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H3-d 布线门：查询页批执行与 describe 缓存的联动。
 *
 * <p>用户在查询页跑的 DDL 不经过 {@link StructurePlanRunner} —— 挂点在
 * {@code QueryPage.apply}（与补全缓存 {@code onStatementsExecuted} 同一句旁边）。
 * 口径与补全 S-3 一致：<b>执行过 DDL 就整份作废</b>，不猜哪张表变了；
 * 纯 DML（INSERT / UPDATE / DELETE）不许动缓存 —— 白作废一次，
 * 下一次树展开 / 数据页刷新就多打一遍库。
 *
 * <p>判定是<b>行为</b>：门 1 断言 DDL 之后 describe 读得到新列
 * （缓存若不作废，命中路径返回的是旧列清单）；门 2 断言 DML 之后
 * describe 仍是缓存命中（语句计数不再增长）。门 2 出生即绿（它断言的是
 * 「没有的行为」），由变异 M5b 证真 —— 摘掉 isDdl 守卫它就红。
 */
@ExtendWith(ApplicationExtension.class)
class QueryPageDdlCacheTest {

    private JdbcSession real;
    private CountingSession session;
    private SchemaService schema;
    private ExecutorService executor;
    private QueryPage page;

    @Start
    void start(Stage stage) throws Exception {
        real = JdbcSession.open(ConnectionSpec.sqliteMemory());
        real.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        real.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Bob')");
        session = new CountingSession(real);
        schema = new SchemaService();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-query-ddl");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "test", "", executor, schema, null,
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

    /** 门 Q1（DDL 作废）：查询页跑 ALTER 之后，describe 必须立刻读得到新列。 */
    @Test
    void ddlExecutedFromTheQueryPageInvalidatesTheDescribeCache() throws Exception {
        schema.describe(session, "users");

        interact(() -> {
            page.setSql("ALTER TABLE users ADD COLUMN remark TEXT");
            page.runAll();
        });
        waitForTabs(1);

        TableSchema after = schema.describe(session, "users");
        assertThat(after.columns()).extracting(ColumnDef::name)
                .as("H3-d 布线：查询页的 DDL 必须作废 describe 缓存 —— 新列要看得见")
                .contains("remark");
    }

    /** 门 Q2（DML 不作废）：INSERT 之后 describe 必须仍是缓存命中。 */
    @Test
    void dmlExecutedFromTheQueryPageLeavesTheDescribeCacheIntact() throws Exception {
        schema.describe(session, "users");

        interact(() -> {
            page.setSql("INSERT INTO users (name) VALUES ('Grace')");
            page.runAll();
        });
        waitForTabs(1);

        int afterInsert = session.statements;
        schema.describe(session, "users");
        assertThat(session.statements)
                .as("H3-d 布线：纯 DML 不许作废缓存 —— describe 必须仍是命中")
                .isEqualTo(afterInsert);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
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

    /** 会话级语句计数器（同 {@code SchemaServiceDescribeCacheTest} 的形状）。 */
    private static final class CountingSession implements DbSession {

        private final DbSession delegate;
        int statements;

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
            statements++;
            return delegate.query(sql, maxRows, params);
        }

        @Override
        public int update(String sql, List<Object> params) throws SQLException {
            statements++;
            return delegate.update(sql, params);
        }

        @Override
        public ExecutionResult execute(String sql, Danger declared) throws SQLException {
            statements++;
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
