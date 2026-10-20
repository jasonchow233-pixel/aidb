package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;

import java.sql.SQLException;
import java.util.LinkedHashMap;
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

    public synchronized DbSession open(String id, ConnectionSpec spec) throws SQLException {
        closeQuietly(id);
        DbSession session = JdbcSession.open(spec);
        sessions.put(id, session);
        return session;
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
