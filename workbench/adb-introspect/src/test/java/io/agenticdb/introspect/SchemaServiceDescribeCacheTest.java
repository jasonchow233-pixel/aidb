package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService.TableSchema;
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

/**
 * ⭐ H3-d：describe 的表级缓存与失效。
 *
 * <p>立案事实（第六版性能三件之一）：{@code SchemaService.describe} 每次都打一遍
 * 数据库 —— 探测查询 + {@code getPrimaryKeys} + 自增列探测，网络库上是三四个来回。
 * 树展开、查询页可改性判定、数据页刷新都在重复调它；Navicat 的二次展开是瞬时的。
 *
 * <p>门禁三条各咬一件事：
 * <ol>
 *   <li><b>命中</b>：同一会话第二次 describe 不许再发语句（计语句包装会话现形）；</li>
 *   <li><b>失效</b>：{@code invalidate} 之后必须真重读 —— DDL 之后的结构页/数据页
 *       不许看到旧列（这是缓存的正确性半边，比命中半边更要紧）；</li>
 *   <li><b>隔离</b>：缓存按会话分桶 —— 两个库的同名表不许互相污染。</li>
 * </ol>
 *
 * <p>⭐ 量测条款 {@link #measuresTheCostOfRepeatedDescribes} 是立案依据本身：
 * H3-d 开工时先量「未缓存的二次 describe」的语句数与耗时（SQLite 本地），
 * 数字进台账；缓存落地后同一条款量到的是命中路径的成本。
 */
class SchemaServiceDescribeCacheTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schema;

    @BeforeEach
    void open() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        schema = new SchemaService();
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    /**
     * ⭐ 量测条款（立案依据，非门禁）：describe 的语句数与耗时。
     *
     * <p>H3-d 开工时（未缓存）量到的是「每次 describe 都打库」的本相；
     * 缓存落地后这一条继续跑，量到的是命中路径 —— 两次数字都进台账。
     * SQLite 是本地库，绝对值只当量纲用；网络库的成本按语句数推
     * （探测查询 + 元数据主键 + 自增列 ≈ 每次展开两三个网络来回）。
     */
    @Test
    void measuresTheCostOfRepeatedDescribes() throws Exception {
        schema.describe(session, "users");

        CountingSession counting = new CountingSession(session);
        long start = System.nanoTime();
        int rounds = 200;
        for (int i = 0; i < rounds; i++) {
            schema.describe(counting, "users");
        }
        long micros = (System.nanoTime() - start) / 1_000 / rounds;
        System.out.println("[H3-d-measure] describe x" + rounds + " 平均 " + micros
                + " µs/次，语句 " + counting.statements + " 条（"
                + (counting.statements == 0 ? "缓存命中：零语句" : "未缓存/首读") + "）");
    }

    /** 门 1（命中）：第二次 describe 不许再发语句，结果与首读一致。 */
    @Test
    void repeatedDescribeOfTheSameTableReadsTheDatabaseOnce() throws Exception {
        CountingSession counting = new CountingSession(session);
        TableSchema first = schema.describe(counting, "users");
        int firstCost = counting.statements;
        assertThat(firstCost)
                .as("首次 describe 必须真读库（这条防门禁空转）")
                .isGreaterThan(0);

        TableSchema second = schema.describe(counting, "users");

        assertThat(counting.statements)
                .as("H3-d：同一会话第二次 describe 不许再打数据库")
                .isEqualTo(firstCost);
        assertThat(second.columns()).extracting(ColumnDef::name)
                .containsExactlyElementsOf(first.columns().stream()
                        .map(ColumnDef::name).toList());
        assertThat(second.primaryKeys()).containsExactlyElementsOf(first.primaryKeys());
    }

    /** 门 2（失效）：invalidate 之后必须真重读 —— DDL 之后的结构不许停在旧列。 */
    @Test
    void invalidateMakesTheNextDescribeReadTheSchemaAnew() throws Exception {
        assertThat(schema.describe(session, "users").columns()).extracting(ColumnDef::name)
                .doesNotContain("remark");

        session.execute("ALTER TABLE users ADD COLUMN remark TEXT");
        schema.invalidate(session);

        assertThat(schema.describe(session, "users").columns()).extracting(ColumnDef::name)
                .as("H3-d：DDL 之后缓存必须让位 —— 新列要看得见")
                .contains("remark");
    }

    /** 门 3（隔离）：缓存按会话分桶，两个库的同名表不许互相污染。 */
    @Test
    void cachesOfDifferentSessionsStaySeparate() throws Exception {
        Path otherFile = DemoDatabase.create(temp.resolve("other.db"));
        try (DbSession other = JdbcSession.open(ConnectionSpec.sqlite(otherFile))) {
            // 把另一个库的 users 改出不同的形状 —— 缓存若不分会话，这里就会读到第一个库的列。
            other.execute("ALTER TABLE users ADD COLUMN extra INTEGER");

            schema.describe(session, "users");
            TableSchema fromOther = schema.describe(other, "users");

            assertThat(fromOther.columns()).extracting(ColumnDef::name)
                    .as("H3-d：另一个会话的同名表必须读它自己的结构")
                    .contains("extra");
        }
    }

    /** 会话级语句计数器：describe 发了几条 SQL，一翻便知。JDBC 元数据调用不计（驱动内部）。 */
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
