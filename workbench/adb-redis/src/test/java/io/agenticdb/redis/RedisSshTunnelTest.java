package io.agenticdb.redis;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import io.agenticdb.session.SshSecrets;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ Redis over SSH 的<b>进程内</b>集成门禁（P1-1 闭环）。
 *
 * <p>为什么可信：Apache MINA sshd 一个 jar 里<b>客户端和服务端都有</b>，跳板机就在本进程里，
 * Redis 用真 testcontainers 容器 —— 于是「转发真的把字节送到了 Redis」「错误 SSH 口令报的是隧道
 * 不是断线」「会话关闭后本地端口释放」三件事都能在 CI 里确定性地跑，不靠第二台机器。
 *
 * <p>{@code known_hosts} 一律重定向到临时目录：测试不该往用户真实的 {@code ~/.agenticdb} 写密钥，
 * 那条红线和 R47-b 对 {@code ADB_CONNECTIONS_FILE} 的规定同源。
 */
class RedisSshTunnelTest {

    private static final String USER = "tunnel";
    private static final String BASTION_PASSWORD = "let-me-in";
    private static final String REDIS_PASSWORD = "secret";

    private static Path workDir;
    private static Path knownHosts;
    private static String previousKnownHosts;

    private static GenericContainer<?> redis;
    private static String redisHost;
    private static int redisPort;

    private static SshServer bastion;
    private static int bastionPort;

    @BeforeAll
    static void start() throws Exception {
        workDir = Files.createTempDirectory("aidb-redis-ssh");
        knownHosts = workDir.resolve("known_hosts");
        previousKnownHosts = System.setProperty("adb.ssh.known_hosts", knownHosts.toString());

        redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);
        redis.start();
        redisHost = redis.getHost();
        redisPort = redis.getMappedPort(6379);
        seed();

        bastion = SshServer.setUpDefaultServer();
        bastion.setHost("127.0.0.1");
        bastion.setPort(0);
        bastion.setKeyPairProvider(
                new SimpleGeneratorHostKeyProvider(workDir.resolve("hostkey.ser")));
        bastion.setPasswordAuthenticator(
                (username, password, session) -> USER.equals(username) && BASTION_PASSWORD.equals(password));
        // 默认所有转发都被拒：忘了设这个，症状是「认证成功但隧道起不来」。
        bastion.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        bastion.start();
        bastionPort = bastion.getPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (previousKnownHosts == null) {
            System.clearProperty("adb.ssh.known_hosts");
        } else {
            System.setProperty("adb.ssh.known_hosts", previousKnownHosts);
        }
        if (bastion != null) {
            bastion.close(true);
        }
        if (redis != null) {
            redis.stop();
        }
    }

    /** 直接用 Lettuce 往 0 库与 1 库各写几种类型，供会话侧只读校验。 */
    private static void seed() {
        RedisURI uri = RedisURI.Builder.redis(redisHost, redisPort).withPassword(REDIS_PASSWORD).build();
        try (RedisClient client = RedisClient.create(uri);
             var conn = client.connect()) {
            var cmd = conn.sync();
            cmd.select(0);
            cmd.set("user:1", "alice");
            cmd.set("user:2", "bob");
            cmd.rpush("queue:tasks", "a", "b", "c");
            cmd.sadd("tags", "x", "y");
            cmd.select(1);
            cmd.set("cfg:name", "value");
        }
    }

    /** 档案把 Redis 地址填成「跳板机眼里的真实地址」，SSH 段指向本进程里的跳板机。 */
    private static ConnectionProfile sshProfile() {
        SshConfig ssh = new SshConfig("127.0.0.1", bastionPort, USER, "", true);
        return new ConnectionProfile("t", "t", AssetKind.REDIS, redisHost, redisPort, "0", "",
                false, 0, "", Map.of(), ssh);
    }

    private static RedisSession sshSession() {
        return RedisSessions.connect(sshProfile(), REDIS_PASSWORD, new SshSecrets(BASTION_PASSWORD, null));
    }

    @Test
    void scanReturnsKeysThroughTheTunnel() {
        try (RedisSession session = sshSession()) {
            // 经本地转发口连到真 Redis：能列回 seeded 的 4 颗 key，证明字节真的穿过了隧道。
            List<RedisKey> keys = session.scan(0, "", null);
            assertThat(keys).hasSize(4);
            assertThat(keys).allMatch(k -> session.via() != null && !session.via().isBlank());
        }
    }

    @Test
    void viaReportsTheBastionIdentity() {
        try (RedisSession session = sshSession()) {
            String via = session.via();
            assertThat(via).contains(USER).contains("127.0.0.1").contains(String.valueOf(bastionPort));
        }
    }

    @Test
    void wrongSshPasswordIsReportedAsTunnelFailure() {
        ConnectionProfile profile = sshProfile();
        assertThatThrownBy(() -> RedisSessions.connect(profile, REDIS_PASSWORD,
                new SshSecrets("wrong-password", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSH tunnel")
                .hasMessageContaining("authentication");
    }

    @Test
    void closeReleasesTheLocalTunnelPort() throws Exception {
        RedisSession session = sshSession();
        int localPort = ((LettuceRedisSession) session).tunnelLocalPort();
        assertThat(localPort).isGreaterThan(0);
        session.close();
        // 关掉之后，本地转发口应当不再接受连接（否则端口与 SSH 会话泄漏）。
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket("127.0.0.1", localPort)) {
                socket.getOutputStream().write(0);
            }
        }).isInstanceOf(Exception.class);
    }
}
