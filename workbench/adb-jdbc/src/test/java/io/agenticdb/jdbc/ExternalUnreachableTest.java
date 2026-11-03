package io.agenticdb.jdbc;

import io.agenticdb.safety.SshTunnelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ {@link ExternalUnreachable} 的门禁 —— 「环境抽风判定」只有一份，这份的行为逐形状钉死。
 *
 * <p>D-43（2026-11-03，R56 带环境回量实测）：Oracle 容器刚重启时
 * {@code DBMS_METADATA.GET_DDL} 冷启动超过 socket 读超时，抛的是
 * {@code SQLRecoverableException: ORA-18730: IO 中断错误。: Socket read timed out} ——
 * 旧判定是大小写敏感的 {@code "Read timed out"}，小写 r 对不上，环境抽风被端成红色缺陷。
 * 本类第一例用的就是那条异常的原样形状。
 */
class ExternalUnreachableTest {

    @Test
    @DisplayName("D-43 实测形状：ORA-18730 中文本地化 + 小写 socket read timed out → 算通信失败")
    void oracleColdStartIsCommunications() {
        SQLRecoverableException e = new SQLRecoverableException(
                "ORA-18730: IO 中断错误。: Socket read timed out\nhttps://docs.oracle.com/error-help/db/ora-18730/",
                "60000", 18730);
        assertThat(ExternalUnreachable.isCommunications(e)).isTrue();
    }

    @Test
    @DisplayName("整句被本地化翻译、只剩 ORA 码时也接得住（不靠文案碰运气）")
    void oraCodeAloneIsEnough() {
        assertThat(ExternalUnreachable.isCommunications(
                new SQLRecoverableException("ORA-18730: 完全另一种语言的翻译", null, 18730))).isTrue();
    }

    @Test
    @DisplayName("判定与大小写无关：整句换大写也要接住（D-43 的直接根因）")
    void matchingIsCaseInsensitive() {
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("COMMUNICATIONS LINK FAILURE"))).isTrue();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("Socket READ TIMED OUT"))).isTrue();
    }

    @Test
    @DisplayName("SQLState 08* 是 JDBC 规范的连接类异常：没文案也接得住")
    void sqlStateConnectionClassIsCommunications() {
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("服务端只给了状态码", "08S01", 0))).isTrue();
    }

    @Test
    @DisplayName("MySQL 原始形状与包装链（原 6 份实现的 token 并集不丢）")
    void legacyTokensStillCoveredThroughCauseChain() {
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("Communications link failure"))).isTrue();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("驱动包装一层", new SQLException("CommunicationsException")))).isTrue();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("连接断了", new InterruptedIOException("Read timed out")))).isTrue();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("socketTimeout exceeded"))).isTrue();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("No operations allowed after connection closed"))).isTrue();
    }

    @Test
    @DisplayName("SshTunnelException 永远是真缺陷：单独出现或被包装都不降级成跳过（R55-c 的规矩）")
    void tunnelFailureIsNeverEnvironment() {
        assertThat(ExternalUnreachable.isCommunications(
                new SshTunnelException("认证失败", false))).isFalse();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("Read timed out",
                        new SshTunnelException("隧道里超时", false)))).isFalse();
    }

    @Test
    @DisplayName("真缺陷不许被降级：SQL 语义错、对象不存在、连不上监听器 → 一律红")
    void realDefectsStayRed() {
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("ORA-00942: table or view does not exist", "42000", 942))).isFalse();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("ORA-12541: TNS:no listener", null, 12541))).isFalse();
        assertThat(ExternalUnreachable.isCommunications(
                new SQLException("access denied", "28000", 1045))).isFalse();
    }
}
