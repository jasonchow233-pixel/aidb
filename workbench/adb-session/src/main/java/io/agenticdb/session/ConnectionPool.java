package io.agenticdb.session;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.RowPage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

/**
 * HikariCP 连接池。桌面端同一时刻只有一个会话，池固定为 1 条连接，
 * 因此保留了 P1 的「共享单线程串行执行」语义；同时把物理连接的建立/校验
 * 交给 HikariCP，避免每次查询都新建 TCP。
 *
 * <p>SQLite 不走池：文件连接池化没有收益，且 {@code :memory:} 会串库。
 */
public final class ConnectionPool {

    private ConnectionPool() {
    }

    public static DbSession open(ConnectionSpec spec) throws SQLException {
        if (spec.kind() == DialectKind.SQLITE) {
            return JdbcSession.open(spec);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(spec.jdbcUrl());
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(3_000);
        config.setIdleTimeout(120_000);
        config.setMaxLifetime(1_800_000);
        config.setPoolName("adb-" + spec.kind().id());
        config.setAutoCommit(true);
        spec.options().forEach(config::addDataSourceProperty);

        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            Connection connection = dataSource.getConnection();
            JdbcSession delegate = JdbcSession.wrap(connection,
                    spec.kind().displayName() + " · " + spec.database());
            return new PooledSession(delegate, dataSource);
        } catch (SQLException e) {
            dataSource.close();
            throw e;
        }
    }

    private static final class PooledSession implements DbSession {

        private final JdbcSession delegate;
        private final HikariDataSource dataSource;

        private PooledSession(JdbcSession delegate, HikariDataSource dataSource) {
            this.delegate = delegate;
            this.dataSource = dataSource;
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
        public DialectKind dialect() {
            return delegate.dialect();
        }

        @Override
        public RowPage query(String sql, int maxRows, List<Object> params) throws SQLException {
            return delegate.query(sql, maxRows, params);
        }

        @Override
        public int update(String sql, List<Object> params) throws SQLException {
            return delegate.update(sql, params);
        }

        @Override
        public ExecutionResult execute(String sql) throws SQLException {
            return delegate.execute(sql);
        }

        @Override
        public void cancelCurrent() {
            delegate.cancelCurrent();
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
        public void close() throws SQLException {
            try {
                delegate.close();
            } finally {
                dataSource.close();
            }
        }
    }
}
