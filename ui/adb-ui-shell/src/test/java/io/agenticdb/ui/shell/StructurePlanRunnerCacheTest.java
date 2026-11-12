package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.ColumnProblem;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ H3-d 布线门：{@link StructurePlanRunner#execute(DbSession, StructurePlan, SchemaService)}
 * 计划至少成功执行一条语句就作废 describe 缓存，首条失败则保持缓存原样。
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
                Danger.DESTRUCTIVE, false, false);
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

    @Test
    void underDeclaredPlanIsRejectedBeforeAnyStatementOrTransactionChange() throws Exception {
        var original = session.query("SELECT id FROM users ORDER BY id", 100).rows();
        session.begin();
        StructurePlan plan = new StructurePlan("plan.title.default",
                List.of("UPDATE users SET id = id", "DELETE FROM users"),
                Danger.MUTATING, false, false);

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, plan, schemas))
                .isInstanceOf(SQLException.class);
        assertThat(counting.statements).isZero();
        assertThat(session.inTransaction()).isTrue();
        assertThat(session.query("SELECT id FROM users ORDER BY id", 100).rows()).isEqualTo(original);
        session.rollback();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rebuildSuccessPreservesOriginalForeignKeysAndReferencedRows(int originalForeignKeys)
            throws Exception {
        createRebuildTables(originalForeignKeys);
        var parents = session.query("SELECT id, name FROM rebuild_parent ORDER BY id", 100).rows();
        var children = session.query("SELECT * FROM rebuild_child ORDER BY id", 100).rows();

        StructurePlanRunner.execute(counting, rebuildPlan(false), schemas);

        assertThat(foreignKeys()).isEqualTo(originalForeignKeys);
        assertThat(session.inTransaction()).isFalse();
        assertThat(session.query("SELECT id, name FROM rebuild_parent ORDER BY id", 100).rows())
                .isEqualTo(parents);
        assertThat(session.query("SELECT * FROM rebuild_child ORDER BY id", 100).rows())
                .as("重建父表不能触发 ON DELETE CASCADE 删除原有子表数据")
                .isEqualTo(children);
        assertThat(schemas.describe(counting, "rebuild_parent").columns())
                .extracting(ColumnDef::name).contains("remark");
        assertThat(counting.beginCalls).isEqualTo(1);
        assertThat(counting.commitCalls).isEqualTo(1);
        assertThat(counting.rollbackCalls).isZero();
        assertForeignKeyUpdates(originalForeignKeys);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rebuildFailureRestoresOriginalForeignKeysAndRollsBackSqliteDdl(int originalForeignKeys)
            throws Exception {
        createRebuildTables(originalForeignKeys);
        var parents = session.query("SELECT * FROM rebuild_parent ORDER BY id", 100).rows();
        var children = session.query("SELECT * FROM rebuild_child ORDER BY id", 100).rows();
        var cached = schemas.describe(counting, "rebuild_parent");
        assertThat(schemas.describe(counting, "rebuild_parent")).isSameAs(cached);

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, rebuildPlan(true), schemas))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> {
                    assertThat(error).isSameAs(counting.lastExecutionFailure);
                    assertThat(error.getSuppressed()).isEmpty();
                });

        assertThat(foreignKeys()).isEqualTo(originalForeignKeys);
        assertThat(session.inTransaction()).isFalse();
        assertThat(session.query("SELECT * FROM rebuild_parent ORDER BY id", 100).rows())
                .isEqualTo(parents);
        assertThat(session.query("SELECT * FROM rebuild_child ORDER BY id", 100).rows())
                .isEqualTo(children);
        int afterFailure = counting.statements;
        assertThat(schemas.describe(counting, "rebuild_parent").columns())
                .extracting(ColumnDef::name).doesNotContain("remark");
        assertThat(counting.statements)
                .as("即使 SQLite 已回滚，执行成功过 DDL 仍须保守作废缓存")
                .isGreaterThan(afterFailure);
        assertThat(counting.beginCalls).isEqualTo(1);
        assertThat(counting.commitCalls).isZero();
        assertThat(counting.rollbackCalls).isEqualTo(1);
        assertForeignKeyUpdates(originalForeignKeys);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void firstRebuildStatementFailureRestoresForeignKeysWithoutInvalidatingCache(int originalForeignKeys)
            throws Exception {
        setForeignKeys(originalForeignKeys);
        var cached = schemas.describe(counting, "users");
        StructurePlan plan = new StructurePlan("plan.title.default",
                List.of("ALTER TABLE nosuchtable ADD COLUMN q TEXT"),
                Danger.DESTRUCTIVE, false, true);

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, plan, schemas))
                .isInstanceOf(SQLException.class);

        int afterFailure = counting.statements;
        assertThat(schemas.describe(counting, "users")).isSameAs(cached);
        assertThat(counting.statements).isEqualTo(afterFailure);
        assertThat(foreignKeys()).isEqualTo(originalForeignKeys);
        assertThat(session.inTransaction()).isFalse();
        assertThat(counting.commitCalls).isZero();
        assertThat(counting.rollbackCalls).isEqualTo(1);
        assertForeignKeyUpdates(originalForeignKeys);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void rebuildInUserTransactionIsRejectedBeforeAnySql(int originalForeignKeys) throws Exception {
        createRebuildTables(originalForeignKeys);
        session.begin();
        try {
            session.update("INSERT INTO rebuild_parent VALUES (2, 'pending')", List.of(),
                    Danger.MUTATING);
            var parents = session.query("SELECT * FROM rebuild_parent ORDER BY id", 100).rows();
            var children = session.query("SELECT * FROM rebuild_child ORDER BY id", 100).rows();

            assertThatThrownBy(() -> StructurePlanRunner.execute(counting, rebuildPlan(false), schemas))
                    .isInstanceOfSatisfying(ColumnProblem.class, problem -> {
                        assertThat(problem.key()).isEqualTo("structure.error.rebuildInTransaction");
                        assertThat(problem.args()).isEmpty();
                    });

            assertThat(counting.statements).as("连 PRAGMA 查询也不能先发出").isZero();
            assertThat(counting.foreignKeyReads).isZero();
            assertThat(counting.beginCalls).isZero();
            assertThat(counting.commitCalls).isZero();
            assertThat(counting.rollbackCalls).isZero();
            assertThat(session.inTransaction()).isTrue();
            assertThat(foreignKeys()).isEqualTo(originalForeignKeys);
            assertThat(session.query("SELECT * FROM rebuild_parent ORDER BY id", 100).rows())
                    .isEqualTo(parents);
            assertThat(session.query("SELECT * FROM rebuild_child ORDER BY id", 100).rows())
                    .isEqualTo(children);
        } finally {
            session.rollback();
        }
        assertThat(session.query("SELECT id FROM rebuild_parent WHERE id = 2", 1).rows())
                .as("用户的未提交写入没有被 runner 提交").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulDdlInvalidatesCacheInUserTransactionEvenIfLaterSqlFails(boolean failLater)
            throws Exception {
        session.execute("CREATE TABLE user_work (id INTEGER PRIMARY KEY)", Danger.DESTRUCTIVE);
        var cached = schemas.describe(counting, "users");
        assertThat(schemas.describe(counting, "users")).isSameAs(cached);
        session.begin();
        try {
            session.update("INSERT INTO user_work VALUES (1)", List.of(), Danger.MUTATING);
            List<String> statements = new ArrayList<>(addColumnPlan("pending_remark").statements());
            if (failLater) {
                statements.add("ALTER TABLE nosuchtable ADD COLUMN q TEXT");
            }
            StructurePlan plan = new StructurePlan("plan.title.default", statements,
                    Danger.DESTRUCTIVE, false, false);

            if (failLater) {
                assertThatThrownBy(() -> StructurePlanRunner.execute(counting, plan, schemas))
                        .isInstanceOf(SQLException.class);
            } else {
                StructurePlanRunner.execute(counting, plan, schemas);
            }

            int afterExecution = counting.statements;
            assertThat(schemas.describe(counting, "users").columns())
                    .extracting(ColumnDef::name).contains("pending_remark");
            assertThat(counting.statements).isGreaterThan(afterExecution);
            assertThat(session.inTransaction()).isTrue();
            assertThat(session.query("SELECT id FROM user_work", 1).rows()).hasSize(1);
            assertThat(counting.beginCalls).isZero();
            assertThat(counting.commitCalls).isZero();
            assertThat(counting.rollbackCalls).isZero();
            assertThat(counting.foreignKeyReads).isZero();
            assertThat(counting.updateSql).isEmpty();
        } finally {
            session.rollback();
        }
        assertThat(session.query("SELECT id FROM user_work", 1).rows()).isEmpty();
        assertThat(new SchemaService().describe(session, "users").columns())
                .extracting(ColumnDef::name).doesNotContain("pending_remark");
    }

    @Test
    void everyStatementReceivesPlanDangerInsteadOfItsOwnClassification() throws Exception {
        StructurePlan plan = new StructurePlan("plan.title.default",
                List.of("SELECT id FROM users", "UPDATE users SET id = id",
                        "ALTER TABLE users ADD COLUMN declared_danger TEXT"),
                Danger.DESTRUCTIVE, false, false);

        StructurePlanRunner.execute(counting, plan, schemas);

        assertThat(counting.executeSql).containsExactlyElementsOf(plan.statements());
        assertThat(counting.executeDangers)
                .containsExactly(plan.danger(), plan.danger(), plan.danger());
        assertThat(counting.foreignKeyReads).isZero();
        assertThat(counting.updateSql).isEmpty();
    }

    @Test
    void underDeclaredRebuildIsRejectedBeforeForeignKeyReadOrTransactionChange() throws Exception {
        setForeignKeys(1);
        var original = session.query("SELECT id FROM users ORDER BY id", 100).rows();
        StructurePlan plan = new StructurePlan("plan.title.default",
                List.of("UPDATE users SET id = id", "DELETE FROM users"),
                Danger.MUTATING, false, true);

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, plan, schemas))
                .isInstanceOf(SQLException.class);

        assertThat(counting.statements).isZero();
        assertThat(counting.beginCalls).isZero();
        assertThat(counting.commitCalls).isZero();
        assertThat(counting.rollbackCalls).isZero();
        assertThat(session.inTransaction()).isFalse();
        assertThat(foreignKeys()).isEqualTo(1);
        assertThat(session.query("SELECT id FROM users ORDER BY id", 100).rows()).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void beginFailureRestoresForeignKeysWithoutMaskingOriginalError(boolean startedBeforeFailure)
            throws Exception {
        setForeignKeys(1);
        schemas.describe(counting, "users");
        counting.beginFailure = new SQLException("injected begin failure");
        counting.startBeforeBeginFailure = startedBeforeFailure;

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, rebuildPlan(false), schemas))
                .isSameAs(counting.beginFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());

        assertThat(foreignKeys()).isEqualTo(1);
        assertThat(session.inTransaction()).isFalse();
        assertThat(counting.executeSql).isEmpty();
        assertThat(counting.beginCalls).isEqualTo(1);
        assertThat(counting.commitCalls).isZero();
        assertThat(counting.rollbackCalls).isEqualTo(startedBeforeFailure ? 1 : 0);
        assertForeignKeyUpdates(1);
        int afterFailure = counting.statements;
        schemas.describe(counting, "users");
        assertThat(counting.statements).isEqualTo(afterFailure);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollbackAndRestoreErrorsAreSuppressedOnOriginalBeginOrSqlFailure(boolean failAtBegin)
            throws Exception {
        createRebuildTables(1);
        if (failAtBegin) {
            counting.beginFailure = new SQLException("injected begin failure");
            counting.startBeforeBeginFailure = true;
        }
        counting.rollbackFailure = new SQLException("injected rollback failure");
        counting.restoreFailure = new SQLException("injected restore failure");

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, rebuildPlan(true), schemas))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> {
                    assertThat(error).isSameAs(failAtBegin
                            ? counting.beginFailure : counting.lastExecutionFailure);
                    assertThat(error.getSuppressed())
                            .containsExactly(counting.rollbackFailure, counting.restoreFailure);
                });

        assertThat(counting.rollbackCalls).isEqualTo(1);
        assertThat(counting.commitCalls).isZero();
        assertThat(session.inTransaction()).isFalse();
        assertThat(foreignKeys()).as("恢复注入失败时不能假装已恢复").isZero();
        assertForeignKeyUpdates(1);
    }

    @Test
    void restoreFailureAfterSuccessIsReportedAndCacheStillInvalidates() throws Exception {
        createRebuildTables(1);
        schemas.describe(counting, "rebuild_parent");
        counting.restoreFailure = new SQLException("injected restore failure");

        assertThatThrownBy(() -> StructurePlanRunner.execute(counting, rebuildPlan(false), schemas))
                .isSameAs(counting.restoreFailure)
                .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());

        assertThat(session.inTransaction()).isFalse();
        assertThat(counting.commitCalls).isEqualTo(1);
        assertThat(counting.rollbackCalls).isZero();
        assertThat(foreignKeys()).isZero();
        int afterFailure = counting.statements;
        assertThat(schemas.describe(counting, "rebuild_parent").columns())
                .extracting(ColumnDef::name).contains("remark");
        assertThat(counting.statements).isGreaterThan(afterFailure);
        assertForeignKeyUpdates(1);
    }

    private int foreignKeys() throws SQLException {
        return ((Number) session.query("PRAGMA foreign_keys", 1).rows().getFirst()
                .get("foreign_keys")).intValue();
    }

    private void setForeignKeys(int value) throws SQLException {
        session.update("PRAGMA foreign_keys = " + value, List.of(), Danger.MUTATING);
        assertThat(foreignKeys()).isEqualTo(value);
    }

    private void createRebuildTables(int originalForeignKeys) throws SQLException {
        session.execute("CREATE TABLE rebuild_parent (id INTEGER PRIMARY KEY, name TEXT)",
                Danger.DESTRUCTIVE);
        session.execute("CREATE TABLE rebuild_child (id INTEGER PRIMARY KEY, parent_id INTEGER "
                + "REFERENCES rebuild_parent(id) ON DELETE CASCADE)", Danger.DESTRUCTIVE);
        session.update("INSERT INTO rebuild_parent VALUES (1, 'original')", List.of(), Danger.MUTATING);
        session.update("INSERT INTO rebuild_child VALUES (1, 1)", List.of(), Danger.MUTATING);
        setForeignKeys(originalForeignKeys);
    }

    private void assertForeignKeyUpdates(int originalForeignKeys) {
        assertThat(counting.foreignKeyReads).isEqualTo(1);
        if (originalForeignKeys == 1) {
            assertThat(counting.updateSql)
                    .containsExactly("PRAGMA foreign_keys = OFF", "PRAGMA foreign_keys = ON");
            assertThat(counting.updateDangers).containsExactly(Danger.MUTATING, Danger.MUTATING);
        } else {
            assertThat(counting.updateSql).isEmpty();
            assertThat(counting.updateDangers).isEmpty();
        }
    }

    private static StructurePlan rebuildPlan(boolean failLater) {
        List<String> statements = new ArrayList<>(List.of(
                "CREATE TABLE rebuild_copy (id INTEGER PRIMARY KEY, name TEXT, remark TEXT)",
                "INSERT INTO rebuild_copy (id, name) SELECT id, name FROM rebuild_parent",
                "DROP TABLE rebuild_parent",
                "ALTER TABLE rebuild_copy RENAME TO rebuild_parent"));
        if (failLater) {
            statements.add("ALTER TABLE nosuchtable ADD COLUMN q TEXT");
        }
        return new StructurePlan("plan.title.default", statements, Danger.DESTRUCTIVE, true, true);
    }

    private static StructurePlan addColumnPlan(String column) {
        return new StructurePlan(
                "加一列", List.of("ALTER TABLE \"users\" ADD COLUMN \"" + column + "\" TEXT"),
                Danger.DESTRUCTIVE, false, false);
    }

    /** 会话级语句计数器（同 {@code SchemaServiceDescribeCacheTest} 的形状）。 */
    private static final class CountingSession implements DbSession {

        private final DbSession delegate;
        final List<String> executeSql = new ArrayList<>();
        final List<Danger> executeDangers = new ArrayList<>();
        final List<String> updateSql = new ArrayList<>();
        final List<Danger> updateDangers = new ArrayList<>();
        int statements;
        int foreignKeyReads;
        int beginCalls;
        int commitCalls;
        int rollbackCalls;
        SQLException beginFailure;
        boolean startBeforeBeginFailure;
        SQLException rollbackFailure;
        SQLException restoreFailure;
        SQLException lastExecutionFailure;

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
            if (sql.equals("PRAGMA foreign_keys")) {
                foreignKeyReads++;
            }
            return delegate.query(sql, maxRows, params);
        }

        @Override
        public int update(String sql, List<Object> params) {
            throw new AssertionError("runner must declare update danger");
        }

        @Override
        public int update(String sql, List<Object> params, Danger declared) throws SQLException {
            statements++;
            updateSql.add(sql);
            updateDangers.add(declared);
            if (sql.startsWith("PRAGMA foreign_keys = ")) {
                assertThat(delegate.inTransaction()).as("外键开关必须在事务外执行").isFalse();
            }
            if (sql.equals("PRAGMA foreign_keys = ON") && restoreFailure != null) {
                throw restoreFailure;
            }
            return delegate.update(sql, params, declared);
        }

        @Override
        public ExecutionResult execute(String sql) {
            throw new AssertionError("runner must pass plan.danger() explicitly");
        }

        @Override
        public ExecutionResult execute(String sql, Danger declared) throws SQLException {
            statements++;
            executeSql.add(sql);
            executeDangers.add(declared);
            try {
                return delegate.execute(sql, declared);
            } catch (SQLException e) {
                lastExecutionFailure = e;
                throw e;
            }
        }

        @Override
        public void begin() throws SQLException {
            beginCalls++;
            if (beginFailure != null) {
                if (startBeforeBeginFailure) {
                    delegate.begin();
                }
                throw beginFailure;
            }
            delegate.begin();
        }

        @Override
        public void commit() throws SQLException {
            commitCalls++;
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            rollbackCalls++;
            delegate.rollback();
            // 真实回滚后注入报告错误，不破坏连接，以便独立验证后续恢复与异常链。
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
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
