package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ H3-d 布线门：{@link StructurePlanRunner#execute(DbSession, StructurePlan, SchemaService)}
 * 在计划<b>执行成功后</b>作废 describe 缓存，失败时保持缓存原样。
 *
 * <p>表级 / 列级 DDL 全部汇进 runner（StructurePage、TableDdl 建表/清空/重命名/删表），
 * 所以失效挂点只需这一个；会话级查询页批执行由 QueryPage 自己挂
 * （见 {@code QueryPageDdlCacheTest}）。
 *
 * <p>判定用<b>行为</b>而不是记录器：{@code SchemaService} 是 final，
 * 改不了它 —— 门禁断言「失效后 describe 必须重读到新形状 / 未失效时必须仍是缓存命中」，
 * 语句计数由包装会话现形（JDBC 元数据调用不计，驱动内部）。
 */
class StructurePlanRunnerCacheTest {

    @TempDir
    Path temp;

    private DbSession session;
    private CountingSession counting;
    private SchemaService schemas;

    @BeforeEach
    void open() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        counting = new CountingSession(session);
        schemas = new SchemaService();
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    /** 门 R1（成功即失效）：计划执行成功后，describe 必须重读到加出来的列。 */
    @Test
    void successfulPlanInvalidatesTheDescribeCache() throws Exception {
        schemas.describe(counting, "users");
        int warm = counting.statements;
        schemas.describe(counting, "users");
        assertThat(counting.statements)
                .as("前置：第二次 describe 必须是缓存命中（防门禁空转）")
                .isEqualTo(warm);

        StructurePlanRunner.execute(counting, addColumnPlan("remark"), schemas);

        assertThat(schemas.describe(counting, "users").columns())
                .extracting(ColumnDef::name)
                .as("H3-d 布线：runner 成功后必须作废缓存 —— 新列要立刻看得见")
                .contains("remark");
        assertThat(counting.statements)
                .as("重读必须真发语句（命中路径到不了新形状）")
                .isGreaterThan(warm);
    }

    /** 门 R2（失败不作废）：语句炸掉时缓存保持原样 —— 回滚之后库没变。 */
    @Test
    void failedPlanLeavesTheCacheIntact() throws Exception {
        schemas.describe(counting, "users");
        int warm = counting.statements;
        schemas.describe(counting, "users");
        assertThat(counting.statements).isEqualTo(warm);

        StructurePlan broken = new StructurePlan(
                "加一列", List.of("ALTER TABLE nosuchtable ADD COLUMN q TEXT"),
                Danger.MUTATING, false, false);
        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, broken, schemas))
                .isInstanceOf(Exception.class);
        // 失败的语句本身也计了数 —— 基线取在失败之后，看的只是 describe 有没有重读。
        int afterFailure = counting.statements;

        schemas.describe(counting, "users");
        assertThat(counting.statements)
                .as("H3-d 布线：失败不许作废缓存 —— 回滚后 describe 必须仍是命中")
                .isEqualTo(afterFailure);
    }

    /** 门 R3（旧签名仍通）：两参重载继续可用，null 服务不许炸。 */
    @Test
    void twoArgOverloadStillWorksWithoutASchemaService() throws Exception {
        StructurePlanRunner.execute(counting, addColumnPlan("note"));
        try (var rs = session.connection().createStatement()
                .executeQuery("PRAGMA table_info(users)")) {
            List<String> columns = new java.util.ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
            assertThat(columns).contains("note");
        }
    }

    private static StructurePlan addColumnPlan(String column) {
        return new StructurePlan(
                "加一列", List.of("ALTER TABLE \"users\" ADD COLUMN \"" + column + "\" TEXT"),
                Danger.MUTATING, false, false);
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
