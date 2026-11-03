package io.agenticdb.jdbc;

import io.agenticdb.safety.SshTunnelException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.KnownHostEntry;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.sql.SQLException;
import java.time.Duration;

/**
 * ⭐ G5a：本机开一个端口，把它经 SSH 跳板机转发到数据库端口。
 *
 * <p>驱动那边只知道 {@code 127.0.0.1:<临时端口>}，剩下的（谁加密、怎么走）都是这条隧道的事。
 * 选型是纯 Java（Apache MINA SSHD），不是包一层系统 {@code ssh -L}：
 * Windows 不保证带 ssh 可执行文件，Linux 上也得看装没装 openssh-client ——
 * 「连不上」不该取决于用户机器上装了什么第三方二进制（见 {@code docs/COMPONENT-SELECTION.md}）。
 *
 * <p>目标地址有一个<b>必须记住</b>的语义：{@code remoteHost} 是<b>跳板机眼里</b>的数据库地址。
 * 界面填的 host 会原样递过去（和 Navicat 一样），所以在跳板机之外解析不了的内部主机名，
 * 到了那一侧才会报错 —— 那时报错的是隧道，不是网络。
 *
 * <p>这里的<b>异常文本全是英文</b>，不是偷懒：{@code ErrorClassifier} 把异常原文当「驱动原文」
 * 放进 {@code detail}，那一栏两种语言下都保持原文；给用户看的翻译走
 * {@code error.summary.sshTunnel} / {@code error.advice.sshTunnel} 这类键，由界面渲染。
 * 在这里再写一份中文，等于往 {@code MODEL_CJK_CEILING} 那笔债上加钱（棘轮只许降）。
 */
public final class SshTunnel implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
    /** 转发只绑环回：本机别的进程不该借这条隧道出去。 */
    private static final String LOCAL_BIND = "127.0.0.1";

    private final SshClient client;
    private final ClientSession session;
    private final SshdSocketAddress forward;
    private final SshEndpoint endpoint;

    private SshTunnel(SshClient client, ClientSession session, SshdSocketAddress forward,
                      SshEndpoint endpoint) {
        this.client = client;
        this.session = session;
        this.forward = forward;
        this.endpoint = endpoint;
    }

    /**
     * 起隧道。
     *
     * @param endpoint 跳板机与凭据
     * @param remoteHost 数据库地址（按跳板机的解析）
     * @param remotePort 数据库端口
     * @throws SshTunnelException 隧道自己的失败（认证 / 主机密钥 / 连不上跳板机）
     */
    public static SshTunnel open(SshEndpoint endpoint, String remoteHost, int remotePort)
            throws SQLException {
        if (!endpoint.usable()) {
            throw new SshTunnelException("SSH tunnel: bastion host or username is missing - "
                    + endpoint, false);
        }
        if (!endpoint.hasCredential()) {
            throw new SshTunnelException("SSH tunnel: no credential (a private key or a "
                    + "password is required) - " + endpoint.describe(), false);
        }
        Path knownHosts = knownHostsFile();
        HostKeys hostKeys = new HostKeys(knownHosts, endpoint.trustOnFirstUse());
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(hostKeys);
        ClientSession session = null;
        try {
            ensureKnownHostsFile(knownHosts);
            client.start();
            session = client.connect(endpoint.username(), endpoint.host(), endpoint.port(),
                    null, null).verify(CONNECT_TIMEOUT).getSession();
            applyCredentials(endpoint, session);
            session.auth().verify(AUTH_TIMEOUT);
            if (!session.isAuthenticated()) {
                throw new SshTunnelException("SSH authentication failed: "
                        + endpoint.describe(), false);
            }
            SshdSocketAddress bound = session.startLocalPortForwarding(0,
                    new SshdSocketAddress(remoteHost, remotePort));
            int localPort = bound == null ? 0 : bound.getPort();
            if (localPort <= 0) {
                throw new SshTunnelException("SSH tunnel: no local port could be opened - "
                        + endpoint.describe(), false);
            }
            return new SshTunnel(client, session, bound, endpoint);
        } catch (SQLException e) {
            closeQuietly(session, client);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(session, client);
            // 密钥被拒时，报错该说「密钥」而不是「连不上」：D-32 那笔账的同一条教训。
            throw new SshTunnelException("SSH tunnel failed (" + endpoint.describe() + "): "
                    + describe(hostKeys.refusal(), e), hostKeys.refusal() != null, e);
        }
    }

    private static void applyCredentials(SshEndpoint endpoint, ClientSession session)
            throws SQLException {
        if (endpoint.usesPrivateKey()) {
            Path keyFile = Path.of(endpoint.privateKeyPath());
            if (!Files.isReadable(keyFile)) {
                throw new SshTunnelException("SSH private key file is not readable: "
                        + keyFile, false);
            }
            FileKeyPairProvider provider = new FileKeyPairProvider(keyFile);
            if (endpoint.passphrase() != null) {
                provider.setPasswordFinder(FilePasswordProvider.of(endpoint.passphrase()));
            }
            int loaded = 0;
            // loadKeys 把「读不出 / 口令短语不对」包成运行时异常往外抛（它自己那层不声明受检异常）。
            // 不接住它，界面只会看到一句「隧道建立失败」，用户不知道是私钥的问题。
            try {
                for (KeyPair pair : provider.loadKeys(session)) {
                    session.addPublicKeyIdentity(pair);
                    loaded++;
                }
            } catch (RuntimeException e) {
                throw new SshTunnelException("SSH private key could not be loaded ("
                        + keyFile + "): " + e.getMessage(), false, e);
            }
            if (loaded == 0) {
                throw new SshTunnelException("SSH private key holds no usable key pair: "
                        + keyFile, false);
            }
        }
        if (endpoint.password() != null) {
            session.addPasswordIdentity(endpoint.password());
        }
    }

    /** 隧道这头绑的环回地址 —— 驱动只该看到这个。 */
    public String localHost() {
        return LOCAL_BIND;
    }

    public int localPort() {
        return forward.getPort();
    }

    /** 跳板机身份串（不含秘密），给状态栏与测试连接回报用。 */
    public String via() {
        return endpoint.describe();
    }

    @Override
    public void close() {
        closeQuietly(session, client);
    }

    /**
     * known_hosts 落点。⛔ 必须可重定向：验收/测试不该往用户真实的 {@code ~/.agenticdb}
     * 里写密钥（同 R47-b 对 {@code ADB_CONNECTIONS_FILE} 那条红线）。
     */
    public static Path knownHostsFile() {
        String override = System.getProperty("adb.ssh.known_hosts", "");
        if (override.isBlank()) {
            override = System.getenv().getOrDefault("ADB_SSH_KNOWN_HOSTS", "");
        }
        return override.isBlank()
                ? Path.of(System.getProperty("user.home"), ".agenticdb", "known_hosts")
                : Path.of(override);
    }

    /** 0600：MINA 见到「组/其他可写」的 known_hosts 会直接拒读，与其报错不如先修好权限。 */
    private static void ensureKnownHostsFile(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (!Files.exists(file)) {
                Files.createFile(file);
                try {
                    Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                    // Windows / 非 POSIX 文件系统由 ACL 控制。
                }
            }
        } catch (IOException e) {
            // 建不出来也要继续：MINA 自己会给出「读不到 known_hosts」的原文。
        }
    }

    private static String describe(String refusal, Exception cause) {
        String reason = refusal != null ? refusal : String.valueOf(cause.getMessage());
        return cause.getClass().getSimpleName() + ": " + reason;
    }

    private static void closeQuietly(ClientSession session, SshClient client) {
        try {
            if (session != null) {
                session.close(true);
            }
        } catch (RuntimeException ignored) {
            // 关不成也不能盖住真正的失败原因。
        }
        try {
            if (client != null) {
                client.close(true);
            }
        } catch (RuntimeException ignored) {
            // 同上。
        }
    }

    /**
     * 主机密钥策略：认 known_hosts，剩下的看「首次是否记录」。
     *
     * <p>两件事分开：<b>没见过</b>可以按 TOFU 记下；<b>见过但变了</b>一律拒。
     * 后者不拒就等于把「机房换了密钥」和「有人在中间」合并成同一件事，
     * 而这两件事里只有第二件会要数据。
     */
    private static final class HostKeys extends KnownHostsServerKeyVerifier {

        private final boolean trustOnFirstUse;
        private volatile String refusal;

        HostKeys(Path knownHosts, boolean trustOnFirstUse) {
            super(trustOnFirstUse ? AcceptAllServerKeyVerifier.INSTANCE
                    : RejectAllServerKeyVerifier.INSTANCE, knownHosts);
            this.trustOnFirstUse = trustOnFirstUse;
        }

        String refusal() {
            return refusal;
        }

        @Override
        protected boolean acceptUnknownHostKey(ClientSession sshClientSession,
                                                SocketAddress targetServerAddress,
                                                PublicKey serverKey) {
            if (!trustOnFirstUse) {
                refusal = "the bastion host key is not in known_hosts and "
                        + "\"trust on first use\" is off: " + address(targetServerAddress);
                return false;
            }
            return super.acceptUnknownHostKey(sshClientSession, targetServerAddress, serverKey);
        }

        @Override
        public boolean acceptModifiedServerKey(ClientSession sshClientSession,
                                                SocketAddress targetServerAddress,
                                                KnownHostEntry knownHostEntry,
                                                PublicKey existingServerKey,
                                                PublicKey newServerKey) {
            refusal = "the bastion host key does not match the one recorded in known_hosts ("
                    + address(targetServerAddress) + ") - confirm with the operator that the "
                    + "key genuinely changed before updating the record";
            return false;
        }

        private static String address(SocketAddress address) {
            return address instanceof InetSocketAddress socket
                    ? socket.getHostString() + ":" + socket.getPort()
                    : String.valueOf(address);
        }
    }
}
