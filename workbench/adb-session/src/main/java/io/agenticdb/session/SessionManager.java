package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话注册表：一个连接 id 对应一个 {@link DbSession}。
 *
 * <p>P1 只有 demo 一个连接，但按 id 管理，P5 多连接时不需要改调用方。
 * 关闭是幂等的；重复打开同一 id 会先关旧会话，避免文件句柄泄漏。
 *
 * <p>⭐ R61-b：**锁的粒度 = 连接 id**，不再是一把全局锁。
 * 原因不是理论洁癖：{@code open()} 里做的是**真连接**，而真连接能量到多慢？
 * 本机 D-39 实拍量过 —— Oracle 首次连接 8~12 秒。原先整个注册表是一把
 * {@code synchronized}，于是那 10 秒里：
 * <ul>
 *   <li>另一把库的 {@code open} 排在后面 —— 「多连接并行」名不副实；</li>
 *   <li><b>FX 线程</b>的 {@code find}/{@code isOpen}/{@code require} 也排在后面 ——
 *       侧栏的存活点（{@code isConnectionAlive}）与方言解析（{@code sessionOrCurrent}）
 *       都从这条路上走，症状是「正在连 Oracle，整个界面点不动」。</li>
 * </ul>
 * 现在：读操作无锁（{@link ConcurrentHashMap}），只有**同一个 id** 的打开/重开/关闭互斥。
 */
public final class SessionManager implements AutoCloseable {

    private final Map<String, DbSession> sessions = new ConcurrentHashMap<>();

    /**
     * 每个 id 一把锁：同一把库不许两个人同时打开（会重复建会话、还会把先建的那个丢掉），
     * 不同把库之间不互相排队。
     */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * ⭐ R61-b：只给测试用（同包）—— 在真正打开之前回调一次，跑在**调用方线程**上。
     *
     * <p>为什么要开这个口子：「一条连接打开得很慢」在单测里没法靠真网络造出来
     * （连一个黑洞地址要等多久取决于 NAT / 防火墙 / 内核超时，那种门禁第二天就红）。
     * 同包的注入点是唯一能把「慢」变成常量的办法 ——
     * 与 {@code StartupTrace(Instant)} 同一类做法：把不可控的时间交给测试。
     */
    private volatile java.util.function.Consumer<String> openProbe = id -> { };

    /** 注入「慢连接」（见 {@code openProbe} 的注释）；传 null 表示恢复默认。 */
    void setOpenProbeForTest(java.util.function.Consumer<String> probe) {
        this.openProbe = probe == null ? id -> { } : probe;
    }

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
    public DbSession open(String id, ConnectionSpec spec) throws SQLException {
        DbSession existing = sessions.get(id);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        synchronized (lockFor(id)) {
            return openLocked(id, spec);
        }
    }

    /**
     * ⭐ 强制重开（用户点「重新连接」时用）。
     *
     * <p>与 {@link #open} 的区别就在这里：open 复用已开的会话，reopen 一定换新的。
     */
    public DbSession reopen(String id, ConnectionSpec spec) throws SQLException {
        synchronized (lockFor(id)) {
            closeQuietly(id);
            return openLocked(id, spec);
        }
    }

    public Optional<DbSession> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public DbSession require(String id) {
        DbSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("没有已打开的连接: " + id);
        }
        return session;
    }

    public boolean isOpen(String id) {
        return sessions.containsKey(id);
    }

    /** 当前打开的会话 id。用于清理「没有标签页引用」的孤儿会话。 */
    public List<String> openIds() {
        return List.copyOf(sessions.keySet());
    }

    public void close(String id) {
        synchronized (lockFor(id)) {
            closeQuietly(id);
        }
    }

    @Override
    public void close() {
        // ⚠️ 逐把按自己的锁收口：拿一把全局锁去关所有会话，等于又把所有连接串起来了。
        for (String id : List.copyOf(sessions.keySet())) {
            close(id);
        }
        sessions.clear();
    }

    /** 调用方必须已持有该 id 的锁。 */
    private DbSession openLocked(String id, ConnectionSpec spec) throws SQLException {
        DbSession existing = sessions.get(id);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        openProbe.accept(id);
        // ⭐ H3-f 撤池：所有方言一律直开 JdbcSession。原来的 ConnectionPool
        //    maxPoolSize=1 且连接借出后全程不归还 —— 池语义从未生效过，
        //    只留一个 HikariDataSource 和它的后台线程。真池化要重设计
        //    事务/取消/串行语义，当前没有这样的用户故事（决策记录见 ARCHITECTURE §6）。
        DbSession session = JdbcSession.open(spec);
        sessions.put(id, session);
        return session;
    }

    private Object lockFor(String id) {
        return locks.computeIfAbsent(id, key -> new Object());
    }

    /** 调用方必须已持有该 id 的锁。 */
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
