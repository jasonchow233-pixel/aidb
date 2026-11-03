package io.agenticdb.jdbc;

import io.agenticdb.safety.SshTunnelException;

import java.sql.SQLException;
import java.util.Locale;

/**
 * ⭐ 真库测试侧「环境抽风 ≠ 产品缺陷」判定的<b>唯一出处</b>。
 *
 * <p>外部真库是第三方依赖：TLS 握手间歇卡住、容器刚重启时 {@code DBMS_METADATA.GET_DDL}
 * 冷启动超过读超时 —— 这些不该让门禁变红；但也不能<b>悄悄算通过</b>，
 * 所以调用方把它记成<b>可见的跳过</b>（或重试）。
 *
 * <p>此前这条规则在 6 个测试类里各有一份字面相同的实现，且<b>全部是大小写敏感的文案匹配</b> ——
 * R56 带环境回量时被现实打穿：Oracle 容器刚重启，契约 ①–⑨ 的 Oracle 读回落到
 * {@code SQLRecoverableException: ORA-18730: IO 中断错误。: Socket read timed out}，
 * 小写 {@code read} 对不上大写的 {@code "Read timed out"}，异常类与 ORA 码又没人看 ——
 * 环境抽风被端成了红色缺陷（D-43）。现在：
 * <ul>
 *   <li><b>先看类型与错误码</b>（与本地化文案无关）：ORA-18730 是 Oracle 的
 *       「socket 读超时」；SQLState 以 {@code 08} 开头是 JDBC 规范的连接类异常；</li>
 *   <li>再看文案，一律<b>大小写不敏感</b>（历史 token 全部保留）；</li>
 *   <li>{@link SshTunnelException} 永远<b>不算</b>环境抽风 —— 隧道自身的失败是真缺陷，
 *       不许降级成跳过（R55-c 立的规矩）。</li>
 * </ul>
 *
 * <p>⚠️ {@code ExternalTlsDatabaseTest} 故意<b>不</b>走这里：TLS 负例的证书失败原文里就混着
 * 「communications link failure」（D-32），那一圈必须先排除证书失败、再判抽风，
 * 且把「connection refused」当对端没起 —— 语义与这里不同，合并会稀释它的门。
 *
 * <p>和 {@link ExternalDatabase} 一样住在 {@code adb-jdbc} 的测试源码里，经 test-jar
 * 共享给 {@code adb-editing} / {@code adb-introspect} 的矩阵测试 —— 判定只有一份，
 * 界面或驱动单方面改掉文案时，各矩阵跟着一起红，不会有的类接得住、有的类接不住。
 */
public final class ExternalUnreachable {

    /** Oracle「IO 中断错误 / Socket read timed out」的 ORA 码（D-43 实测形状）。 */
    static final int ORA_SOCKET_READ_TIMEOUT = 18730;

    /** 历史 token（原 6 份实现的并集），匹配时一律转小写。 */
    private static final String[] LEGACY_TOKENS = {
            "communications link failure",
            "communicationsexception",
            "sockettimeout",
            "read timed out",
            "no operations allowed after connection closed",
            "connection is closed",
    };

    private ExternalUnreachable() {
    }

    /**
     * 是通信 / 环境类失败吗（而不是 SQL / 断言 / 隧道问题）？
     *
     * <p>两遍扫描：链上<b>任何一层</b>出现 {@link SshTunnelException} 即 {@code false}
     * —— 隧道自身的失败是真缺陷，就算被驱动包了层「communications」外衣也不许降级成跳过；
     * 没有隧道异常时，ORA-18730、SQLState {@code 08*}、大小写不敏感的历史文案 token
     * 任一命中即 {@code true}。
     */
    public static boolean isCommunications(SQLException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SshTunnelException) {
                return false;
            }
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getErrorCode() == ORA_SOCKET_READ_TIMEOUT) {
                    return true;
                }
                String state = sql.getSQLState();
                if (state != null && state.startsWith("08")) {
                    return true;
                }
            }
            String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            for (String token : LEGACY_TOKENS) {
                if (message.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }
}
