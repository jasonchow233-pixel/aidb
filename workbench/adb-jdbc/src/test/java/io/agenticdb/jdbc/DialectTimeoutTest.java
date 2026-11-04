package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 连接 URL **必须带超时**。
 *
 * <p>实测踩过：真库联调时服务端的 TLS 握手停顿，客户端<b>无限期</b>阻塞在
 * socket 读上 —— 界面直接冻住，测试挂死，连报错都没有。
 * 线程栈停在 {@code com.mysql.cj.protocol.ExportControlled.performTlsHandshake}。
 *
 * <p>加上 {@code connectTimeout} / {@code socketTimeout} 之后，
 * 卡住的握手会**快速失败**（而不是永远挂着），错误信息也能看到了。
 */
class DialectTimeoutTest {

    @Test
    void mysqlUrlCarriesConnectAndSocketTimeouts() {
        String url = new MySqlDialect().jdbcUrl("db.local", 3306, "shop");

        assertThat(url)
                .as("MySQL 连接超时（毫秒）")
                .contains("connectTimeout=" + MySqlDialect.CONNECT_TIMEOUT_MS);
        assertThat(url)
                .as("MySQL 读超时（毫秒）")
                .contains("socketTimeout=" + MySqlDialect.SOCKET_TIMEOUT_MS);
        assertThat(url).startsWith("jdbc:mysql://db.local:3306/shop?");
    }

    @Test
    void postgresUrlCarriesConnectAndSocketTimeouts() {
        String url = new PostgresDialect().jdbcUrl("db.local", 5432, "shop");

        assertThat(url)
                .as("PostgreSQL 连接超时（秒）")
                .contains("connectTimeout=" + PostgresDialect.CONNECT_TIMEOUT_SECONDS);
        assertThat(url)
                .as("PostgreSQL 读超时（秒）")
                .contains("socketTimeout=" + PostgresDialect.SOCKET_TIMEOUT_SECONDS);
        assertThat(url).startsWith("jdbc:postgresql://db.local:5432/shop?");
    }

    @Test
    void mariadbUrlCarriesTheSameTimeoutsOnItsOwnScheme() {
        String url = new MariaDbDialect().jdbcUrl("db.local", 3306, "shop");

        assertThat(url).startsWith("jdbc:mariadb://db.local:3306/shop?");
        assertThat(url)
                .as("mariadb-java-client 的超时参数与 mysql-connector-j 同名（毫秒）")
                .contains("connectTimeout=" + MySqlDialect.CONNECT_TIMEOUT_MS)
                .contains("socketTimeout=" + MySqlDialect.SOCKET_TIMEOUT_MS);
        assertThat(url)
                .as("serverTimezone / useUnicode 是 mysql-connector-j 的私有参数，MariaDB 驱动不认")
                .doesNotContain("serverTimezone")
                .doesNotContain("useUnicode");
    }

    @Test
    void theTimeoutsAreReasonable() {
        assertThat(MySqlDialect.CONNECT_TIMEOUT_MS)
                .as("连接超时不能太短（跨机房网络要留余量），也不能太长（界面等不起）")
                .isBetween(3_000, 30_000);
        assertThat(MySqlDialect.SOCKET_TIMEOUT_MS)
                .as("读超时要明显大于连接超时")
                .isGreaterThan(MySqlDialect.CONNECT_TIMEOUT_MS);
        assertThat(PostgresDialect.CONNECT_TIMEOUT_SECONDS).isBetween(3, 30);
        assertThat(PostgresDialect.SOCKET_TIMEOUT_SECONDS)
                .isGreaterThan(PostgresDialect.CONNECT_TIMEOUT_SECONDS);
    }

    /**
     * ⭐ **契约用的读超时不许短过产品自己用的那个**（D-49 的成因，2026-11-04 量到）。
     *
     * <p>三轮全量在这里红过、单跑必绿，原因一直查不到 —— 因为红的是断言
     * 「Oracle 说能读 DDL 就得读回刚建的那张表」，而真异常在更下面一层被吞了：
     * 全量负载下 {@code DBMS_METADATA.GET_DDL} 实测要 **>8s**（同一个类单跑 22.00s、
     * 全量 43.26s，失败那一条用例本身 13.41s），驱动抛
     * {@code ORA-18730: Socket read timed out}，{@code OracleDialect.getDdl} 的
     * {@code catch (SQLException) → return null} 把它变成「读不到」。
     * 也就是说：**契约测的是一份比产品更严的超时，产品永远不会有的失败被算成了产品的错**。
     *
     * <p>⚠️ 只钉 Oracle 一格，而且只钉「不小于产品」这一条方向：
     * 连接超时**必须**保持 5s 短值（连不上的主机在 connect 阶段快速失败，
     * 这正是这套短超时存在的理由，放宽它等于把挂死风险请回来）。
     * 其余四家的读超时没在三轮全量里红过 —— 没量就不改。
     */
    @Test
    void theContractHarnessDoesNotReadShorterThanTheProduct() {
        var oracle = ExternalDatabase.timeoutOptions(DialectKind.ORACLE);
        assertThat(Integer.parseInt(oracle.get("oracle.jdbc.ReadTimeout")))
                .as("契约的读超时短过产品时，「服务端慢」会被读成「这个库读不出 DDL」")
                .isGreaterThanOrEqualTo(OracleDialect.READ_TIMEOUT_MS);
        assertThat(oracle.get("oracle.net.CONNECT_TIMEOUT"))
                .as("连接超时保持短值 —— 不可达的主机必须快速失败")
                .isEqualTo("5000");

        // 另外三家（走 connectTimeout/socketTimeout 的那几家）本轮没量到同类失败，
        // 这里只把「没改」这件事钉住，改的人必须带着新的实测理由来。
        var mysql = ExternalDatabase.timeoutOptions(DialectKind.MYSQL);
        assertThat(mysql.get("socketTimeout"))
                .as("没量到就不动 —— 动它的人要给出这一家自己的超时证据")
                .isEqualTo("8000");
        assertThat(mysql.get("connectTimeout")).isEqualTo("5000");
    }
}
