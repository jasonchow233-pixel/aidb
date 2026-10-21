package io.agenticdb.jdbc;

import io.agenticdb.model.RowPage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 单个数据库会话。
 *
 * <p>这是 UI 与数据库之间唯一的数据通路。所有查询都必须经过
 * {@link JdbcSession#query(String, int)}，由实现强制 {@code setMaxRows}，
 * 调用方无法拿到「整表」。
 *
 * <p>⚠️ 方法抛 {@link SQLException} 而不是 RuntimeException：
 * 错误要带可读信息交给 UI，不能吞成一句 "Query failed"。
 */
public interface DbSession extends AutoCloseable {

    Connection connection();

    /** 连接标识，用于状态栏与错误信息。 */
    String label();

    /** 方言。SQL 构造（引用标识符、分页）只允许从这里取，不在 UI 里猜。 */
    Dialect dialect();

    /** 方言的枚举项（持久化的判别式）。 */
    default DialectKind dialectKind() {
        return DialectKind.of(dialect());
    }

    /**
     * 执行查询。实现必须调用 {@code Statement.setMaxRows}，
     * 且传入值再按 {@code QueryLimits} 夹紧一次。
     */
    default RowPage query(String sql, int maxRows) throws SQLException {
        return query(sql, maxRows, List.of());
    }

    /**
     * 参数化查询。筛选值、主键值必须走这里，不能拼进 SQL。
     *
     * @param params 按 {@code ?} 出现顺序绑定；null 绑定为 SQL NULL
     */
    RowPage query(String sql, int maxRows, List<Object> params) throws SQLException;

    /** 执行写语句，返回影响行数。 */
    default int update(String sql) throws SQLException {
        return update(sql, List.of());
    }

    /** 参数化写语句。编辑保存必须使用这个重载。 */
    int update(String sql, List<Object> params) throws SQLException;

    /**
     * 执行一条任意 SQL（查询 / DML / DDL），用于查询页。
     * 实现必须限制返回行数，不能把整表塞进内存。
     */
    ExecutionResult execute(String sql) throws SQLException;

    /**
     * 取消当前正在执行的语句。查询页的「取消」按钮从 UI 线程调用，
     * 实现必须允许跨线程调用 JDBC {@code Statement.cancel()}。
     */
    default void cancelCurrent() {
    }

    void begin() throws SQLException;

    void commit() throws SQLException;

    void rollback() throws SQLException;

    boolean inTransaction() throws SQLException;

    @Override
    void close() throws SQLException;
}
