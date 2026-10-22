package io.agenticdb.session;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
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
        // ⭐ 是否池化由方言决定 —— 以前这里硬编码 `== SQLITE`。
        if (!spec.kind().dialect().pooled()) {
            return JdbcSession.open(spec);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(spec.jdbcUrl());
        // ⭐ R4：options 先加，user / password 后设 —— 钥匙串里的口令必须赢。
        //    否则 options 里的 password 会覆盖它，落盘的明文反而压过安全存储。
        spec.options().forEach(config::addDataSourceProperty);
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

    /**
     * 池化会话。
     *
     * <p>包级可见（不是 private）：门禁要能直接构造它，
     * 验证「关闭会话时连接真的还回池子」（A12）。
     */
    static final class PooledSession implements DbSession {

        private final JdbcSession delegate;
        private final HikariDataSource dataSource;

        PooledSession(JdbcSession delegate, HikariDataSource dataSource) {
            this.delegate = delegate;
            this.dataSource = dataSource;
        }

        @Override
        public Connection connection() {
            return delegate.connection();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
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
            return delegate.query(sql, maxRows, params);
        }

        @Override
        public int update(String sql, List<Object> params) throws SQLException {
            return delegate.update(sql, params);
        }

        @Override
        public ExecutionResult execute(String sql, io.agenticdb.model.Danger declared)
                throws SQLException {
            return delegate.execute(sql, declared);
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
        /**
         * ⭐ A12：关闭会话 = **把借来的连接还回池子**，再关池子。
         *
         * <p>以前这里只 {@code delegate.close()}（那只关 statement，不关 connection）
         * + {@code dataSource.close()} —— 于是那条借出来的连接**一直挂在池外**，
         * 池子关不掉、连接不释放（长时间使用会打满数据库连接数）。
         *
         * <p>顺序很重要：<b>先还连接，再关池</b>（反过来 Hikari 会等连接归还而卡住）。
         */
        public void close() throws SQLException {
            try {
                // delegate.close() 只关 statement —— 真正要还的是 connection。
                try {
                    delegate.close();
                } finally {
                    Connection borrowed = delegate.connection();
                    if (borrowed != null && !borrowed.isClosed()) {
                        // close() 会把连接归还给 Hikari（不是物理断连）。
                        borrowed.close();
                    }
                }
            } finally {
                dataSource.close();
            }
        }
    }
}
