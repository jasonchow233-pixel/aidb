package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 只关心方言的会话替身。
 *
 * <p>方言差异最终都落在<b>生成的 SQL 文本</b>上 —— 只要 SQL 对，
 * 就不需要真的连一个 PostgreSQL / MySQL 实例。把替身共享出来，
 * 是为了让「列级变更」和「表级生命周期」两组测试用同一份假会话，
 * 而不是各写一份、然后悄悄长出两个版本。
 */
final class FakeDialectSession implements DbSession {

    private final Dialect dialect;

    private FakeDialectSession(Dialect dialect) {
        this.dialect = dialect;
    }

    static DbSession of(DialectKind kind) {
        return new FakeDialectSession(kind.dialect());
    }

    @Override
    public Connection connection() {
        return null;
    }

    @Override
    public String label() {
        return "fake";
    }

    @Override
    public Dialect dialect() {
        return dialect;
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
    public ExecutionResult execute(String sql, Danger declared) throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
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
}
