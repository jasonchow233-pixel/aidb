package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.Danger;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
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
    private final Dialect dialect;
    /** 查询页的「取消」从 JavaFX 线程调用，所以必须是 volatile。 */
    private volatile Statement activeStatement;
    /** ⭐ A10：单条语句执行超时（秒）；0 = 不限制。 */
    private final int queryTimeoutSeconds;

    private JdbcSession(Connection connection, String label, Dialect dialect,
                        int queryTimeoutSeconds) {
        this.connection = connection;
        this.label = label;
        this.dialect = dialect;
        this.queryTimeoutSeconds = QueryLimits.clampQueryTimeout(queryTimeoutSeconds);
    }

    /** 语句超时（秒）；0 = 不限制。 */
    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    /**
     * 给语句挂上超时。
     *
     * <p>驱动不支持 {@code setQueryTimeout} 时**静默忽略** ——
     * 那属于「这个库不支持」，不该让整条查询失败。
     */
    private void applyTimeout(Statement statement) {
        if (queryTimeoutSeconds <= 0) {
            return;
        }
        try {
            statement.setQueryTimeout(queryTimeoutSeconds);
        } catch (SQLException | RuntimeException ignored) {
            // 驱动不支持 → 不限时（而不是报错）。
        }
    }

    public static JdbcSession open(ConnectionSpec spec) throws SQLException {
        try {
            // JDBC 4 会自动加载，但显式加载能给出更清楚的错误
            // ⭐ 驱动类名由方言自己给 —— 以前这里是 switch，加一种库要改两处。
            Class.forName(spec.kind().dialect().driverClassName());
        } catch (ClassNotFoundException e) {
            throw new SQLException("缺少 " + spec.kind().displayName() + " JDBC 驱动", e);
        }

        Properties props = new Properties();
        // ⭐ D-3：方言级超时先落地，options 还能覆盖它（真库联调要能改）。
        spec.kind().dialect().connectionProperties().forEach(props::setProperty);
        // ⭐ R4：先应用 options，再设 user / password。
        //    以前顺序相反 —— options 里的 password 会**覆盖**钥匙串里的口令，
        //    落盘的明文 options 反而赢了安全存储的口令。
        spec.options().forEach(props::setProperty);
        if (spec.username() != null) {
            props.setProperty("user", spec.username());
        }
        if (spec.password() != null) {
            props.setProperty("password", spec.password());
        }

        Connection connection = DriverManager.getConnection(spec.jdbcUrl(), props);
        connection.setAutoCommit(true);
        // ⭐ A10：语句超时。默认 30 秒；可用 options 里的 queryTimeout 覆盖（秒）。
        int timeout = QueryLimits.clampQueryTimeout(
                integerOption(spec.options(), "queryTimeout",
                        QueryLimits.DEFAULT_QUERY_TIMEOUT_SECONDS));
        return new JdbcSession(connection, spec.kind().displayName() + " · " + spec.database(),
                spec.kind().dialect(), timeout);
    }

    public static JdbcSession wrap(Connection connection, String label) {
        return new JdbcSession(connection, label, inferDialect(connection),
                QueryLimits.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    /** 读一个整数型附加参数；没有或不是数字就用默认值。 */
    private static int integerOption(Map<String, String> options, String key, int fallback) {
        if (options == null) {
            return fallback;
        }
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 由已建好的连接的 URL 反查方言。
     *
     * <p>⭐ D-3：改成<b>遍历方言的 {@code uriScheme()}</b> —— 以前这里是
     * {@code startsWith("jdbc:mysql:")} 之类的一串硬分支，加一种库就得回来改它，
     * 漏了就退化成 SQLite（等于连对了库却用错方言）。
     */
    private static Dialect inferDialect(Connection connection) {
        try {
            String url = connection.getMetaData().getURL();
            if (url != null) {
                String normalized = url.toLowerCase(java.util.Locale.ROOT);
                for (DialectKind kind : DialectKind.values()) {
                    if (normalized.startsWith("jdbc:" + kind.dialect().uriScheme() + ":")) {
                        return kind.dialect();
                    }
                }
            }
        } catch (SQLException ignored) {
            // 取不到 URL 时按 SQLite 处理：当前首版只有 SQLite 会走 wrap。
        }
        return DialectKind.SQLITE.dialect();
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
    public Dialect dialect() {
        return dialect;
    }

    @Override
    public RowPage query(String sql, int maxRows, List<Object> params) throws SQLException {
        int limit = QueryLimits.clampPageSize(maxRows);
        long started = System.nanoTime();

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // ⭐ 多要一行用来探测「是不是还有更多」。读到 limit+1 行就说明被截断了，
            //    那一行不进结果集，只用来把 truncated 置上（A11 / 4.5-2）。
            statement.setMaxRows(limit + 1);
            // 大结果集不要一次性从驱动拉全量
            statement.setFetchSize(Math.min(limit, 500));
            applyTimeout(statement);
            bind(statement, params);

            // ⭐ 4.5-13：query() 也要记录 activeStatement ——
            //    否则数据页的加载无法被 ⌘. 真正中断，只能逻辑放弃。
            activeStatement = statement;
            try (ResultSet resultSet = statement.executeQuery()) {
                ExecutionResult result = readResultSet(resultSet, sql, started, limit);
                // ⭐ 不要丢截断标记：readResultSet 已经探测到了「还有更多行」，
                //    这里丢掉的话，调用方永远不知道自己拿到的是残缺结果。
                return new RowPage(result.columns(), result.rows(), null, null,
                        result.durationMs(), List.of(), false, result.isTruncated());
            }
        } catch (SQLTimeoutException e) {
            // ⭐ A10：给用户一句能看懂的话 —— 驱动原文通常是干巴巴的
            //    "query timeout" 或 "statement timeout"。
            throw new SQLException("查询超时（" + queryTimeoutSeconds + " 秒）—— "
                    + "试试加条件筛选，或改用「按主键跳转」", e);
        } finally {
            activeStatement = null;
        }
    }

    @Override
    public int update(String sql, List<Object> params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            bind(statement, params);
            // ⭐ 4.5-13：update() 也要能被取消 —— 数据页的保存跑在 update() 里。
            activeStatement = statement;
            return statement.executeUpdate();
        } catch (SQLTimeoutException e) {
            // ⭐ A10：给用户一句能看懂的话 —— 驱动原文通常是干巴巴的
            //    "query timeout" 或 "statement timeout"。
            throw new SQLException("查询超时（" + queryTimeoutSeconds + " 秒）—— "
                    + "试试加条件筛选，或改用「按主键跳转」", e);
        } finally {
            activeStatement = null;
        }
    }

    /**
     * ⭐ 第三层门禁：驱动侧复查（见 {@link DbSession#execute(String, Danger)}）。
     *
     * <p>实际等级比声明的高 → 拒绝执行。这条拦的是「绕过确认」：
     * 调用方把一条 DELETE 说成只读，驱动层不认。
     */
    @Override
    public ExecutionResult execute(String sql, Danger declared) throws SQLException {
        DbSession.requireDeclared(declared, sql);
        return execute(sql);
    }

    /**
     * ⭐ Q2：带参数执行。
     *
     * <p>用 {@link PreparedStatement} 绑定参数 —— 值不进 SQL 文本。
     */
    @Override
    public ExecutionResult execute(String sql, List<Object> params, Danger declared)
            throws SQLException {
        DbSession.requireDeclared(declared, sql);
        if (params == null || params.isEmpty()) {
            return execute(sql);
        }
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            applyTimeout(statement);
            bind(statement, params);
            activeStatement = statement;
            statement.setMaxRows(QueryLimits.MAX_PAGE_SIZE + 1);
            statement.setFetchSize(Math.min(QueryLimits.MAX_PAGE_SIZE, 500));
            if (statement.execute()) {
                try (ResultSet rs = statement.getResultSet()) {
                    return readResultSet(rs, sql, started, QueryLimits.MAX_PAGE_SIZE);
                }
            }
            return new ExecutionResult(sql, List.of(), List.of(), statement.getUpdateCount(),
                    (System.nanoTime() - started) / 1_000_000);
        } catch (SQLTimeoutException e) {
            // ⭐ A10：给用户一句能看懂的话 —— 驱动原文通常是干巴巴的
            //    "query timeout" 或 "statement timeout"。
            throw new SQLException("查询超时（" + queryTimeoutSeconds + " 秒）—— "
                    + "试试加条件筛选，或改用「按主键跳转」", e);
        } finally {
            activeStatement = null;
        }
    }

    @Override
    public ExecutionResult execute(String sql) throws SQLException {
        long started = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            applyTimeout(statement);
            activeStatement = statement;
            statement.setMaxRows(QueryLimits.MAX_PAGE_SIZE + 1);
            statement.setFetchSize(Math.min(QueryLimits.MAX_PAGE_SIZE, 500));
            boolean hasResult = statement.execute(sql);
            if (hasResult) {
                ResultSet resultSet = statement.getResultSet();
                if (resultSet != null) {
                    try (resultSet) {
                        return readResultSet(resultSet, sql, started, QueryLimits.MAX_PAGE_SIZE);
                    }
                }
            }
            int updateCount = statement.getUpdateCount();
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            return new ExecutionResult(sql, List.of(), List.of(), updateCount, durationMs);
        } catch (SQLTimeoutException e) {
            // ⭐ A10：给用户一句能看懂的话 —— 驱动原文通常是干巴巴的
            //    "query timeout" 或 "statement timeout"。
            throw new SQLException("查询超时（" + queryTimeoutSeconds + " 秒）—— "
                    + "试试加条件筛选，或改用「按主键跳转」", e);
        } finally {
            activeStatement = null;
        }
    }

    @Override
    public boolean isClosed() {
        try {
            return connection == null || connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    @Override
    public void cancelCurrent() {
        Statement statement = activeStatement;
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // 取消是尽力而为；驱动不支持时由执行线程的异常路径收尾。
        }
    }

    private static ExecutionResult readResultSet(ResultSet resultSet, String sql,
                                                long started, int limit) throws SQLException {
        int rowLimit = limit <= 0 ? QueryLimits.MAX_PAGE_SIZE : limit;
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
        while (rows.size() < rowLimit && resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
            for (int i = 1; i <= columnCount; i++) {
                row.put(keys.get(i - 1), resultSet.getObject(i));
            }
            rows.add(row);
        }
        // ⭐ 再多读一行：读得到就说明库里还有，本次结果是被截断的。
        //   这行不进 rows，只用来置标记 —— UI 必须把这话说给用户听。
        boolean truncated = rows.size() == rowLimit && resultSet.next();

        long durationMs = (System.nanoTime() - started) / 1_000_000;
        return new ExecutionResult(sql, columns, rows, -1, durationMs, truncated);
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
