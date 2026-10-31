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
}
