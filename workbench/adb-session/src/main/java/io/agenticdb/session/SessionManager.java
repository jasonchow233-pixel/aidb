package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话注册表：一个连接 id 对应一个 {@link DbSession}。
 *
 * <p>P1 只有 demo 一个连接，但按 id 管理，P5 多连接时不需要改调用方。
 * 关闭是幂等的；重复打开同一 id 会先关旧会话，避免文件句柄泄漏。
 */
public final class SessionManager implements AutoCloseable {

    private final Map<String, DbSession> sessions = new LinkedHashMap<>();

    /**
     * 打开（或**复用**）一个会话。
     *
     * <p>⭐ U2：以前这里是「<b>先关掉再重开</b>」——
     * 于是切换到 B 连接时，**A 连接的会话被关掉了**，
     * A 的标签页当场失效（用户会发现「切回来就报错」）。
     * 两个连接**不能并存**，这正是 U2「多连接并行」一直做不了的原因。
     *
     * <p>现在：已开着就直接复用；确实要重开（用户点「重新连接」）用 {@link #reopen}。
     */
    public synchronized DbSession open(String id, ConnectionSpec spec) throws SQLException {
        DbSession existing = sessions.get(id);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        // ⭐ 是否池化由方言决定（SQLite 单文件直连，PG/MySQL 走池）。
        DbSession session = spec.kind().dialect().pooled()
                ? ConnectionPool.open(spec) : JdbcSession.open(spec);
        sessions.put(id, session);
        return session;
    }

    /**
     * ⭐ 强制重开（用户点「重新连接」时用）。
     *
     * <p>与 {@link #open} 的区别就在这里：open 复用已开的会话，reopen 一定换新的。
     */
    public synchronized DbSession reopen(String id, ConnectionSpec spec) throws SQLException {
        closeQuietly(id);
        return open(id, spec);
    }

    public synchronized Optional<DbSession> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public synchronized DbSession require(String id) {
        DbSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("没有已打开的连接: " + id);
        }
        return session;
    }

    public synchronized boolean isOpen(String id) {
        return sessions.containsKey(id);
    }

    /** 当前打开的会话 id。用于清理「没有标签页引用」的孤儿会话。 */
    public synchronized List<String> openIds() {
        return List.copyOf(sessions.keySet());
    }

    public synchronized void close(String id) {
        closeQuietly(id);
    }

    @Override
    public synchronized void close() {
        for (String id : Map.copyOf(sessions).keySet()) {
            closeQuietly(id);
        }
        sessions.clear();
    }

    private void closeQuietly(String id) {
        DbSession session = sessions.remove(id);
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (SQLException ignored) {
            // 退出/重连路径：关不掉也不阻断后续会话，连接对象交给进程回收。
        }
    }
}
