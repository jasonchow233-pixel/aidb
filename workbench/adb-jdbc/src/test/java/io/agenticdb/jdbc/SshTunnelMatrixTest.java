package io.agenticdb.jdbc;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.assertj.core.api.Assertions;

import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * ⭐ G5a · 真库契约第 13 条：<b>五家 Docker 各经一台跳板机连一次</b>。
 *
 * <p>{@link SshTunnelTest} 已经把「隧道会不会送字节」在假服务器上钉住了，但那条路
 * 证不了<b>驱动</b>这一侧：MySQL 的 Connector/J 会自己解析主机名、PostgreSQL 的驱动
 * 会做 SSL 协商、Oracle 的 ojdbc 认服务名 —— 把它们指向 {@code 127.0.0.1:<临时端口>}
 * 之后是否还能连上<b>原来那台库</b>，只有真库能回答。D-38 那笔账的形状就是这样：
 * 通道自己绿了，对面却是空的。
 *
 * <p>跳板机是<b>进程内</b>的（MINA 同一个 jar 里有服务端），所以这条契约不需要第二台机器，
 * 也不需要往 Docker 里塞 sshd —— 但真库还是得在：没配 {@code ADB_TEST_*} 的引擎记
 * <b>跳过</b>，不算通过（凭据只从环境变量读，仓库里零口令）。
 *
 * <h2>矩阵规模（每加一条契约都要回量这一行）</h2>
 * <p>5 引擎 × 1 契约 = <b>5 例</b>。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SshTunnelMatrixTest {

    private record Engine(String label, Supplier<ExternalDatabase> source) {
    }

    private static final List<Engine> ENGINES = List.of(
            new Engine("MySQL", ExternalDatabase::mysql),
            new Engine("PostgreSQL", ExternalDatabase::postgres),
            new Engine("MariaDB", ExternalDatabase::mariadb),
            new Engine("SQLServer", ExternalDatabase::sqlServer),
            new Engine("Oracle", ExternalDatabase::oracle));

    static Stream<Arguments> matrix() {
        return ENGINES.stream().map(engine -> Arguments.of(engine.label(), engine.source()));
    }

    @TempDir
    Path tempDir;

    private String previousKnownHosts;
    private SshServer bastion;

    @BeforeEach
    void isolateKnownHosts() {
        previousKnownHosts = System.getProperty("adb.ssh.known_hosts");
        System.setProperty("adb.ssh.known_hosts", tempDir.resolve("known_hosts").toString());
    }

    @AfterEach
    void releaseBastion() throws Exception {
        if (previousKnownHosts == null) {
            System.clearProperty("adb.ssh.known_hosts");
        } else {
            System.setProperty("adb.ssh.known_hosts", previousKnownHosts);
        }
        if (bastion != null) {
            bastion.close(true);
        }
    }

    @ParameterizedTest(name = "{0} · 经隧道连真库")
    @MethodSource("matrix")
    void databaseIsReachableThroughTunnel(String engineLabel,
                                          Supplier<ExternalDatabase> source)
            throws Exception {
        ExternalDatabase target = source.get();
        assumeTrue(target != null,
                "未配置 " + engineLabel + " 的 ADB_TEST_* —— 跳过隧道契约");

        bastion = SshServer.setUpDefaultServer();
        bastion.setHost("127.0.0.1");
        bastion.setPort(0);
        bastion.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(
                tempDir.resolve("bastion-" + engineLabel + ".key")));
        bastion.setPasswordAuthenticator((username, password, session) ->
                "tunnel".equals(username) && "bastion-secret".equals(password));
        bastion.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        bastion.start();

        ConnectionSpec throughTunnel = target.spec().withSsh(new SshEndpoint(
                "127.0.0.1", bastion.getPort(), "tunnel", "bastion-secret",
                "", null, true));

        try (JdbcSession session = JdbcSession.open(throughTunnel)) {
            // 量的不是「SQL 跑通了」，是「握手穿过了隧道打到那台库」：
            // 产品名由驱动从真服务器取，任何一家都答得出，且不依赖我们生成的 SQL 形状。
            DatabaseMetaData meta = session.connection().getMetaData();
            String product = meta.getDatabaseProductName();

            Assertions.assertThat(product)
                    .as("经隧道连到的是真服务器（" + target.label() + "）")
                    .isNotBlank();
            Assertions.assertThat(session.label())
                    .as("回报里要看得见这条连接是经跳板机的")
                    .contains(" via tunnel@127.0.0.1:" + bastion.getPort());
            Assertions.assertThat(session.sshVia())
                    .isEqualTo("tunnel@127.0.0.1:" + bastion.getPort());
        } catch (java.sql.SQLException e) {
            if (ExternalUnreachable.isCommunications(e)) {
                abort("外部数据库暂时不可达（" + engineLabel + " 经隧道）：" + e.getMessage()
                        + " —— 跳过，不计入门禁");
            }
            throw e;
        }
    }
}
