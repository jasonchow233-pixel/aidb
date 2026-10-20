package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 唯一的 JDBC 会话实现。
 *
 * <p>刻意不按数据库拆三个类：SQLite/PostgreSQL/MySQL 的差异在
 * {@link DialectKind} 与 SQL 构造层，执行/映射/事务是同一套代码。
 * 拆成三份只会让「修一处漏两处」。
 */
public final class JdbcSession implements DbSession {

    private final Connection connection;
    private final String label;
    private final DialectKind dialect;

    private JdbcSession(Connection connection, String label, DialectKind dialect) {
        this.connection = connection;
        this.label = label;
        this.dialect = dialect;
    }

    public static JdbcSession open(ConnectionSpec spec) throws SQLException {
        try {
            // JDBC 4 会自动加载，但显式加载能给出更清楚的错误
            Class.forName(switch (spec.kind()) {
                case SQLITE -> "org.sqlite.JDBC";
                case POSTGRESQL -> "org.postgresql.Driver";
                case MYSQL -> "com.mysql.cj.jdbc.Driver";
            });
        } catch (ClassNotFoundException e) {
            throw new SQLException("缺少 " + spec.kind().displayName() + " JDBC 驱动", e);
        }

        Properties props = new Properties();
        if (spec.username() != null) {
            props.setProperty("user", spec.username());
        }
        if (spec.password() != null) {
            props.setProperty("password", spec.password());
        }
        spec.options().forEach(props::setProperty);

        Connection connection = DriverManager.getConnection(spec.jdbcUrl(), props);
        connection.setAutoCommit(true);
        return new JdbcSession(connection, spec.kind().displayName() + " · " + spec.database(),
                spec.kind());
    }

    public static JdbcSession wrap(Connection connection, String label) {
        return new JdbcSession(connection, label, inferDialect(connection));
    }

    private static DialectKind inferDialect(Connection connection) {
        try {
            String url = connection.getMetaData().getURL();
            if (url != null) {
                String normalized = url.toLowerCase(java.util.Locale.ROOT);
                if (normalized.startsWith("jdbc:postgresql:")) {
                    return DialectKind.POSTGRESQL;
                }
                if (normalized.startsWith("jdbc:mysql:")) {
                    return DialectKind.MYSQL;
                }
            }
        } catch (SQLException ignored) {
            // 取不到 URL 时按 SQLite 处理：当前首版只有 SQLite 会走 wrap。
        }
        return DialectKind.SQLITE;
    }

    @Override
    public Connection connection() {
        return connection;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public DialectKind dialect() {
        return dialect;
    }

    @Override
    public RowPage query(String sql, int maxRows, List<Object> params) throws SQLException {
        int limit = QueryLimits.clampPageSize(maxRows);
        long started = System.nanoTime();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(limit);
            // 大结果集不要一次性从驱动拉全量
            statement.setFetchSize(Math.min(limit, 500));
            bind(statement, params);

            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData meta = resultSet.getMetaData();
                int columnCount = meta.getColumnCount();

                List<ColumnDef> columns = new ArrayList<>(columnCount);
                List<String> keys = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    ColumnDef column = TypeMapper.toColumnDef(meta, i);
                    columns.add(column);
                    keys.add(uniqueKey(column.name(), keys));
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(keys.get(i - 1), resultSet.getObject(i));
                    }
                    rows.add(row);
                }

                long durationMs = (System.nanoTime() - started) / 1_000_000;
                return new RowPage(columns, rows, null, null, durationMs, List.of(), false);
            }
        }
    }

    @Override
    public int update(String sql, List<Object> params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            // setObject(i, null) 在各驱动上会发送无类型 NULL，正好对应 SQL NULL。
            statement.setObject(i + 1, params.get(i));
        }
    }

    @Override
    public void begin() throws SQLException {
        connection.setAutoCommit(false);
    }

    @Override
    public void commit() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    @Override
    public void rollback() throws SQLException {
        connection.rollback();
        connection.setAutoCommit(true);
    }

    @Override
    public boolean inTransaction() throws SQLException {
        return !connection.getAutoCommit();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    /** 结果集允许重名列（JOIN 很常见），但 Map 的 key 不能重复。 */
    private static String uniqueKey(String base, List<String> existing) {
        if (!existing.contains(base)) {
            return base;
        }
        int suffix = 2;
        while (existing.contains(base + "_" + suffix)) {
            suffix++;
        }
        return base + "_" + suffix;
    }
}
