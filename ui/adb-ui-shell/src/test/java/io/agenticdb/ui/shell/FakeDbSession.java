package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 只关心「发了什么 SQL」的会话替身。
 *
 * <p>UI 这一层要守的不是数据库真跑通没有（那是集成测试的事），
 * 而是<b>用户的操作最终变成哪条 SQL</b>。把执行过的语句记下来就够了。
 */
final class FakeDbSession implements DbSession {

    private final Dialect dialect;
    private final List<String> executed = new ArrayList<>();
    private final List<String> failingFragments = new ArrayList<>();
    private boolean inTransaction;
    /** 文件型会话的 label 带整条路径 —— 页头截断逻辑要用同型替身测。 */
    private String labelOverride;

    private FakeDbSession(Dialect dialect) {
        this.dialect = dialect;
    }

    FakeDbSession withLabel(String label) {
        this.labelOverride = label;
        return this;
    }

    static FakeDbSession of(DialectKind kind) {
        return new FakeDbSession(kind.dialect());
    }

    /** 执行过的语句（顺序保留）。 */
    List<String> executed() {
        return List.copyOf(executed);
    }

    /** 让包含指定片段的语句抛错（模拟某张表删不掉）。 */
    void failOn(String sqlFragment) {
        failingFragments.add(sqlFragment);
    }

    @Override
    public Connection connection() {
        return null;
    }

    /**
     * 与真实会话同型：{@code JdbcSession} 的标签是「方言 · 库」。
     * 界面把它当一整块上下文显示，替身也得带上方言 —— 否则弹窗标题栏的
     * 断言会去查一个真实界面上根本不存在的位置。
     */
    @Override
    public String label() {
        return labelOverride != null ? labelOverride : dialect.displayName() + " · fake-db";
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
        executed.add(sql);
        return 0;
    }

    @Override
    public ExecutionResult execute(String sql, Danger declared) throws SQLException {
        executed.add(sql);
        for (String fragment : failingFragments) {
            if (sql.contains(fragment)) {
                throw new SQLException("模拟失败：" + fragment);
            }
        }
        return new ExecutionResult(sql, List.of(), List.of(), 0, 0L);
    }

    @Override
    public void close() {
    }

    @Override
    public void begin() {
        inTransaction = true;
    }

    @Override
    public void commit() {
        inTransaction = false;
    }

    @Override
    public void rollback() {
        inTransaction = false;
    }

    @Override
    public boolean inTransaction() {
        return inTransaction;
    }
}
