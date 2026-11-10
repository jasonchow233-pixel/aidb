package io.agenticdb.jdbc;

import io.agenticdb.safety.ErrorClassifier;
import io.agenticdb.safety.SshTunnelException;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ G5a：SSH 隧道的<b>进程内</b>门禁。
 *
 * <p>为什么不用真跳板机：Apache MINA sshd 一个 jar 里<b>客户端和服务端都有</b>，
 * 所以「转发真的把字节送到了对面」「主机密钥变了要拒」「口令错了报的是隧道不是断线」
 * 这三件事都能在 CI 里确定性地跑，不需要第二台机器、也不看谁的机器上装了 ssh。
 *
 * <p>{@code known_hosts} 一律重定向到临时目录（{@code -Dadb.ssh.known_hosts}）——
 * 测试不该往用户真实的 {@code ~/.agenticdb} 里写密钥，那条红线和 R47-b 对
 * {@code ADB_CONNECTIONS_FILE} 的规定同源。
 */
class SshTunnelTest {

    private static final String USER = "tunnel";
    private static final String PASSWORD = "let-me-in";

    @TempDir
    Path tempDir;

    private String previousKnownHosts;
    private Path knownHosts;
    private SshServer bastion;
    private ServerSocket database;

    @BeforeEach
    void redirectKnownHosts() {
        knownHosts = tempDir.resolve("known_hosts");
        previousKnownHosts = System.getProperty("adb.ssh.known_hosts");
        System.setProperty("adb.ssh.known_hosts", knownHosts.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (previousKnownHosts == null) {
            System.clearProperty("adb.ssh.known_hosts");
        } else {
            System.setProperty("adb.ssh.known_hosts", previousKnownHosts);
        }
        if (bastion != null) {
            bastion.close(true);
        }
        if (database != null) {
            database.close();
        }
    }

    /** 一台只认口令、允许任意端口转发的进程内跳板机。 */
    private SshServer startBastion(String keyFileName) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(
                new SimpleGeneratorHostKeyProvider(tempDir.resolve(keyFileName)));
        server.setPasswordAuthenticator((username, password, session) ->
                USER.equals(username) && PASSWORD.equals(password));
        // 默认所有转发都被拒 —— 忘了设这个，症状是「认证成功但隧道起不来」，
        // 报出来像服务器的错，其实是我们没允许这台跳板机转发。
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.start();
        return server;
    }

    /** 一行一进一出的假「数据库」：证的是字节真的到了对面，不是端口开着就算数。 */
    private ServerSocket startFakeDatabase() throws IOException {
        ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread worker = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread responder = new Thread(() -> answer(socket));
                    responder.setDaemon(true);
                    responder.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-db-accept");
        worker.setDaemon(true);
        worker.start();
        return server;
    }

    private static void answer(Socket socket) {
        try (Socket closeMe = socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(closeMe.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(closeMe.getOutputStream(), true,
                    StandardCharsets.UTF_8);
            String line;
            while ((line = in.readLine()) != null) {
                out.println("echo:" + line);
            }
        } catch (IOException ignored) {
            // 对端关掉隧道时这里必然抛 —— 不是失败。
        }
    }

    private SshEndpoint endpoint(int port) {
        return new SshEndpoint("127.0.0.1", port, USER, PASSWORD, "", null, true);
    }

    @Test
    void bytesCrossTheTunnelAndReachTheOtherSide() throws Exception {
        bastion = startBastion("bastion_a.key");
        database = startFakeDatabase();

        try (SshTunnel tunnel = SshTunnel.open(endpoint(bastion.getPort()),
                "127.0.0.1", database.getLocalPort())) {
            assertThat(tunnel.localHost()).isEqualTo("127.0.0.1");
            assertThat(tunnel.localPort()).isPositive();
            assertThat(tunnel.via()).isEqualTo(USER + "@127.0.0.1:" + bastion.getPort());

            try (Socket socket = new Socket(tunnel.localHost(), tunnel.localPort())) {
                socket.setSoTimeout(10_000);
                PrintWriter out = new PrintWriter(
                        socket.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out.println("SELECT 1");
                assertThat(in.readLine()).isEqualTo("echo:SELECT 1");
            }
        }

        // TOFU 的意义是「记下」：没写进 known_hosts 等于每次重连都要再信一次。
        String recorded = Files.readString(knownHosts);
        assertThat(recorded).contains("[127.0.0.1]:" + bastion.getPort());
    }

    @Test
    void wrongBastionPasswordIsReportedAsTunnelFailureNotConnectionLoss() throws Exception {
        bastion = startBastion("bastion_pw.key");
        database = startFakeDatabase();

        assertThatThrownBy(() -> SshTunnel.open(
                new SshEndpoint("127.0.0.1", bastion.getPort(), USER, "wrong-password",
                        "", null, true), "127.0.0.1", database.getLocalPort()))
                .isInstanceOf(SshTunnelException.class)
                .as("隧道自己的失败不该被归类成「连接断了」（D-32 同一类账）")
                .satisfies(error -> assertThat(
                        ErrorClassifier.classify(error).kind())
                        .isEqualTo(ErrorClassifier.Kind.SSH_TUNNEL_FAILED));
    }

    @Test
    void unseenHostKeyIsRefusedWhenTrustOnFirstUseIsOff() throws Exception {
        bastion = startBastion("bastion_strict.key");
        database = startFakeDatabase();

        assertThatThrownBy(() -> SshTunnel.open(
                new SshEndpoint("127.0.0.1", bastion.getPort(), USER, PASSWORD, "", null,
                        false), "127.0.0.1", database.getLocalPort()))
                .isInstanceOf(SshTunnelException.class)
                .satisfies(error -> assertThat(((SshTunnelException) error)
                        .untrustedHostKey()).isTrue())
                .satisfies(error -> assertThat(ErrorClassifier.classify(error).kind())
                        .as("密钥没记过 → 该给「主机密钥」这一类，不是「隧道失败」兜底")
                        .isEqualTo(ErrorClassifier.Kind.SSH_HOST_KEY_UNTRUSTED));
    }

    /**
     * ⭐ 交互式信任决策：用户接受 → 隧道开通且密钥写进 known_hosts（与 TOFU 自动记录同源）。
     *
     * <p>这条把「决策接受」和「密钥落盘」绑在一起验 —— 接受却不记录等于下次又问，
     * TOFU 的意义就丢了（见 {@code bytesCrossTheTunnelAndReachTheOtherSide} 的同一条账）。
     */
    @Test
    void unknownHostKeyIsAcceptedWhenTheInteractiveDecisionAccepts() throws Exception {
        bastion = startBastion("bastion_prompt_accept.key");
        database = startFakeDatabase();

        HostKeyDecision accept = info -> HostKeyDecision.Choice.ACCEPT_AND_REMEMBER;
        try (SshTunnel tunnel = SshTunnel.open(endpoint(bastion.getPort()), "127.0.0.1",
                database.getLocalPort(), accept)) {
            assertThat(tunnel.localPort()).as("接受后隧道必须正常开通").isPositive();
        }
        assertThat(Files.readString(knownHosts))
                .as("用户接受后密钥必须写进 known_hosts")
                .contains("[127.0.0.1]:" + bastion.getPort());
    }

    /**
     * ⭐ 交互式信任决策：用户拒绝 → 连接失败、归类为「主机密钥不可信」且<b>不</b>记录。
     *
     * <p>拒绝和「严格模式拒未知密钥」走的是同一处失败出口（{@code untrustedHostKey()==true}），
     * 区别只在原因文案：这里是 "user declined"，那里是 "trust on first use is off"。
     */
    @Test
    void unknownHostKeyIsRejectedWhenTheInteractiveDecisionRejects() throws Exception {
        bastion = startBastion("bastion_prompt_reject.key");
        database = startFakeDatabase();

        HostKeyDecision reject = info -> HostKeyDecision.Choice.REJECT;
        assertThatThrownBy(() -> SshTunnel.open(endpoint(bastion.getPort()), "127.0.0.1",
                database.getLocalPort(), reject))
                .isInstanceOf(SshTunnelException.class)
                .satisfies(error -> assertThat(((SshTunnelException) error).untrustedHostKey())
                        .isTrue())
                .satisfies(error -> assertThat(String.valueOf(error.getMessage()).toLowerCase())
                        .as("拒绝要说得清是「用户拒绝」，不是「连不上」")
                        .contains("user declined"));
        assertThat(Files.readString(knownHosts))
                .as("用户拒绝后密钥绝不该写进 known_hosts")
                .doesNotContain("[127.0.0.1]:" + bastion.getPort());
    }

    /**
     * ⭐ 这条是本类里最要紧的一条：TOFU 打开后，<b>换过的</b>密钥照样要拒。
     *
     * <p>如果 TOFU 被写成「以后都信」，机房换密钥和有人在中间截取在界面上就变成同一件事，
     * 而这两件事里只有第二件会丢数据。做法：先用 A 的密钥连一次（记进 known_hosts），
     * 再把那行记录的端口改成 B 的（B 用自己生成的一把新密钥），于是
     * 「同一个地址、不同的密钥」。
     */
    @Test
    void changedHostKeyIsRefusedEvenWithTrustOnFirstUseOn() throws Exception {
        bastion = startBastion("bastion_first.key");
        database = startFakeDatabase();
        try (SshTunnel ignored = SshTunnel.open(endpoint(bastion.getPort()),
                "127.0.0.1", database.getLocalPort())) {
            assertThat(Files.readString(knownHosts))
                    .contains("[127.0.0.1]:" + bastion.getPort());
        }
        int firstPort = bastion.getPort();
        bastion.close(true);

        SshServer swapped = startBastion("bastion_second.key");
        bastion = swapped;
        Files.writeString(knownHosts, Files.readString(knownHosts)
                .replace("]:" + firstPort + " ", "]:" + swapped.getPort() + " "));

        assertThatThrownBy(() -> SshTunnel.open(endpoint(swapped.getPort()),
                "127.0.0.1", database.getLocalPort()))
                .isInstanceOf(SshTunnelException.class)
                .satisfies(error -> assertThat(((SshTunnelException) error)
                        .untrustedHostKey()).isTrue())
                .satisfies(error -> assertThat(String.valueOf(error.getMessage()).toLowerCase())
                        .as("要说清是「和记录不符」，不是「连不上」")
                        .contains("does not match"));
    }

    @Test
    void privateKeyAuthenticationOpensTheSameTunnel() throws Exception {
        // RSA 而不是 Ed25519：MINA 的 OpenSSH writer 要 eddsa 库才认 JDK 那把
        // Ed 密钥（本工程没有那个依赖），换成 RSA 是这条门禁自己的取舍。
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        Path privateKey = tempDir.resolve("id_test");
        try (java.io.OutputStream out = Files.newOutputStream(privateKey)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "aidb-test", null, out);
        }

        bastion = SshServer.setUpDefaultServer();
        bastion.setHost("127.0.0.1");
        bastion.setPort(0);
        bastion.setKeyPairProvider(
                new SimpleGeneratorHostKeyProvider(tempDir.resolve("bastion_pk.key")));
        bastion.setPublickeyAuthenticator(
                new KeySetPublickeyAuthenticator(this, List.of(pair.getPublic())));
        bastion.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        bastion.start();

        database = startFakeDatabase();
        // 认证方式从「只认私钥」改成「私钥 + 口令都给」时，私钥要先试（见 SshEndpoint 的口径）。
        try (SshTunnel tunnel = SshTunnel.open(new SshEndpoint("127.0.0.1",
                bastion.getPort(), USER, PASSWORD, privateKey.toString(), null, true),
                "127.0.0.1", database.getLocalPort())) {
            try (Socket socket = new Socket(tunnel.localHost(), tunnel.localPort())) {
                socket.setSoTimeout(10_000);
                PrintWriter out = new PrintWriter(
                        socket.getOutputStream(), true, StandardCharsets.UTF_8);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out.println("ping");
                assertThat(in.readLine()).isEqualTo("echo:ping");
            }
        }
    }

    /**
     * 提示词写「id_ed25519」也救不了这一把：MINA 没有 eddsa 那个依赖时，
     * {@code loadKeys} 对 OpenSSH 的 ed25519 私钥是<b>安静地返回 0 把</b>。
     * 所以这句报错必须自己把「支持哪些格式」说完，否则用户只看到「没有可用密钥」，
     * 会去怀疑文件权限而不是密钥类型。
     */
    @Test
    void ed25519KeyNamesTheFormatsTheEngineActuallyLoads() throws Exception {
        Path keygen = Path.of("/usr/bin/ssh-keygen");
        assumeTrue(Files.isExecutable(keygen),
                "这台机器没有 /usr/bin/ssh-keygen —— 造不出真 ed25519 私钥，跳过，不算验过");
        Path keyFile = tempDir.resolve("id_ed25519");
        Process process = new ProcessBuilder(keygen.toString(), "-t", "ed25519", "-N", "",
                "-q", "-f", keyFile.toString()).redirectErrorStream(true).start();
        assumeTrue(process.waitFor() == 0, "ssh-keygen 没造出密钥 —— 跳过，不算验过");

        bastion = startBastion("bastion_ed.key");
        assertThatThrownBy(() -> SshTunnel.open(new SshEndpoint("127.0.0.1", bastion.getPort(),
                USER, "", keyFile.toString(), null, true), "127.0.0.1", 1))
                .isInstanceOf(SshTunnelException.class)
                .satisfies(error -> assertThat(String.valueOf(error.getMessage()))
                        .as("报错要点名能用的格式，而不是只说「读不出密钥」")
                        .contains("ed25519")
                        .contains("RSA")
                        .contains("ECDSA"));
    }

    @Test
    void missingCredentialIsRefusedBeforeTouchingTheNetwork() {
        assertThatThrownBy(() -> SshTunnel.open(
                new SshEndpoint("127.0.0.1", 22, USER, null, "", null, true),
                "127.0.0.1", 3306))
                .isInstanceOf(SshTunnelException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .contains("no credential"));
    }

    @Test
    void halfFilledBastionIsNotATunnel() {
        assertThatThrownBy(() -> SshTunnel.open(
                new SshEndpoint("127.0.0.1", 22, "", "x", "", null, true), "127.0.0.1", 3306))
                .isInstanceOf(SshTunnelException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .contains("username is missing"));
    }

    /**
     * 驱动连不上时隧道也要收干净。
     *
     * <p>量的是<b>跳板机上还剩几个会话</b>，不是本机端口是否关闭：端口是临时分配的，
     * 外面拿不到；而「重试十次留十个 SSH 会话」这个症状在会话数上是确定的。
     * 对面端口在隧道建好后立刻关掉，驱动那一侧因此是<b>快速失败</b>而不是等登录超时。
     */
    @Test
    void failedDriverHandshakeDoesNotLeaveTheBastionSessionOpen() throws Exception {
        bastion = startBastion("bastion_leak.key");
        database = startFakeDatabase();
        int deadPort = database.getLocalPort();
        database.close();
        database = null;

        ConnectionSpec spec = new ConnectionSpec(DialectKind.MYSQL, "127.0.0.1", deadPort,
                "shop", USER, PASSWORD, java.util.Map.of(), endpoint(bastion.getPort()));

        assertThatThrownBy(() -> JdbcSession.open(spec)).isInstanceOf(SQLException.class);
        waitUntilSessionsClose();
        assertThat(bastion.getActiveSessions())
                .as("驱动失败后跳板机上不该留着会话（隧道没关 = 本机端口 + 远端会话双泄漏）")
                .isEmpty();
    }

    /** 关闭是异步的：给两秒轮询，不等它就把断言写死会在慢机器上随机红。 */
    private void waitUntilSessionsClose() throws InterruptedException {
        for (int attempt = 0; attempt < 40 && !bastion.getActiveSessions().isEmpty(); attempt++) {
            Thread.sleep(50);
        }
    }
}
