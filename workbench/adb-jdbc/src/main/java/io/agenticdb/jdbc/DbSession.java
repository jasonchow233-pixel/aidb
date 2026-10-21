package io.agenticdb.jdbc;

import io.agenticdb.model.Danger;
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
     * ⭐ 带危险等级的参数化写 —— 第三层门禁的写路径。
     *
     * <p>语义与 {@link #execute(String, Danger)} 相同：驱动侧自己再判一次，
     * 实际等级比声明的高就拒绝。
     */
    default int update(String sql, List<Object> params, Danger declared) throws SQLException {
        requireDeclared(declared, sql);
        return update(sql, params);
    }

    /**
     * ⭐ 驱动侧复查：声明的危险等级不能低于语句的实际等级。
     *
     * <p>拦的是「绕过确认」—— 把 DELETE 说成只读，驱动层不认。
     * 声明得比实际高（保守）不拦。
     *
     * @throws SQLException 等级不符时抛出，语句不执行
     */
    static void requireDeclared(Danger declared, String sql) throws SQLException {
        Danger actual = io.agenticdb.safety.DangerClassifier.classify(sql);
        if (actual.ordinal() > declared.ordinal()) {
            throw new SQLException("危险等级不符，语句已被拒绝：声明「" + declared.label()
                    + "」，实际「" + actual.label() + "」");
        }
    }

    /**
     * 执行一条任意 SQL（查询 / DML / DDL），用于查询页。
     * 实现必须限制返回行数，不能把整表塞进内存。
     */
    default ExecutionResult execute(String sql) throws SQLException {
        return execute(sql, io.agenticdb.safety.DangerClassifier.classify(sql));
    }

    /**
     * ⭐ 执行一条任意 SQL，<b>并声明这条语句的危险等级</b>。
     *
     * <p>这是三层写门禁的<b>第三层：驱动侧复查</b>。
     * 前两层是「分类 + UI 确认」，都在 UI 侧；这一层在数据通路侧，
     * <b>不依赖 UI 是否走了正确的路径</b>。
     *
     * <p>实现必须做两件事：
     * <ol>
     *   <li><b>自己再判一次</b>，实际等级比声明的高 → <b>拒绝执行</b>；</li>
     *   <li>等级一致或实际更低 → 正常执行。</li>
     * </ol>
     *
     * <p>为什么只拦「实际更危险」：调用方可能保守地声明了高等级
     * （比如把只读查询声明成写数据），那不该被执行层拒绝；
     * 但把 DELETE 声明成只读 —— 那是<b>绕过确认</b>，必须拦。
     */
    ExecutionResult execute(String sql, Danger declared) throws SQLException;

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
