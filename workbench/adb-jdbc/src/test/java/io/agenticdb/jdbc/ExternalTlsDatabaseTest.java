package io.agenticdb.jdbc;

import io.agenticdb.safety.ErrorClassifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ R47：TLS 契约矩阵 —— 连接向导上那几档加密<b>在真库对端上到底做了什么</b>。
 *
 * <h2>为什么这一层要单独立一张矩阵</h2>
 * <p>主矩阵（{@link ExternalRealDatabaseTest}）验的是「我们生成的 SQL 跑不跑」，
 * 它连的是<b>明文</b>对端，TLS 参数一律为空。而 TLS 这条产品路径的失败方式很特别：
 * <b>填了不生效</b>。实测（Connector/J 8.2 的 {@code getPropertyInfo}）驱动参数表里
 * 根本没有 {@code sslCA} / {@code sslCapath}，而驱动对<b>不认识的属性静默忽略</b> ——
 * 带着 {@code sslCA=/does/not/exist.pem} 连 {@code sslMode=REQUIRED} 照样成功。
 * 也就是说界面上「校验 CA」那一档只要参数名拼错，连接照样建起来，用户以为在验证书，
 * 校验其实一步没做。这类事只有真对端能验：单元测试里问的是我们自己写的字符串。
 *
 * <h2>六条契约各自的来处</h2>
 * <ol>
 *   <li>{@code offMeansPlaintext}：UI 上那格写着「不加密」，会话就得真的是明文。
 *       <b>这条首跑就红了</b>：三家原来都把 OFF 翻成<b>不写任何参数</b>，
 *       而 Connector/J 的默认是 PREFERRED、pgjdbc 的默认是 prefer，两个都是
 *       「服务端支持就加密」（只有 mariadb-java-client 的默认真是 DISABLE，
 *       所以三家红了两个）—— 选了「不加密」拿到的是加密会话，标签骗人（D-33）。</li>
 *   <li>{@code requireEncryptsSession}：「必须加密」要真加密。判据是<b>服务端侧</b>
 *       读回来的会话密码套件，不是「连接成功」。</li>
 *   <li>{@code verifyCaAcceptsTrustedAnchor}：把签发方交给驱动之后，链校验要<b>过</b>。</li>
 *   <li>{@code verifyCaRejectsRogueAnchor}：换一把没签过服务器证书的 CA 必须<b>失败</b>，
 *       而且归类别不能是「连接断了」（D-32：MySQL 把 JSSE 的证书异常包进
 *       {@code CommunicationsException}，归错类的代价不是难看，是建议把用户支去查机房，
 *       而真正要做的是把 CA 交给驱动）。</li>
 *   <li>{@code verifyIdentityAcceptsNameInCert}：用证书 SAN 里那个名字连，主机名校验要<b>过</b>。</li>
 *   <li>{@code verifyIdentityRejectsOtherName}：同一台服务器换个不在证书里的地址
 *       （SAN 只有 {@code DNS:localhost}，没有 IP SAN），必须失败并归到「主机名对不上」。</li>
 * </ol>
 *
 * <p>矩阵规模（加一条契约都要回量这一行）：<b>3 家加密对端 × 6 条 = 18 例</b>。
 * SQL Server / Oracle 不在这一层：它们的 TLS 不是一个档位能表达的
 * （见 {@code SqlServerDialect#supportsSslOptionsUi()} 与 {@code OracleDialect}），
 * 界面本来就把这组控件收成了说明页，没有可验的映射。
 *
 * <p>没起 TLS 对端（缺 {@code ADB_TEST_*_TLS_HOST}）时对应例<b>记为跳过</b>，不是通过。
 */
class ExternalTlsDatabaseTest {

    /** 「证书里没这个名字」用的地址：本机脚本签的证书只有 {@code DNS:localhost}，没有 IP SAN。 */
    private static final String HOST_NOT_IN_CERT = "127.0.0.1";

    /** 临时信任库的口令：是<b>本地当场生成的临时文件</b>的口令，不是任何数据库的凭据。 */
    private static final char[] TRUSTSTORE_PASSWORD = "aidb-tls-contract".toCharArray();

    /** 一把 CA PEM → 一个 PKCS12 信任库路径（同一路径复用，别每条契约都新建临时文件）。 */
    private static final Map<String, String> TRUST_STORES = new ConcurrentHashMap<>();

    private record Engine(String label, Supplier<ExternalTlsDatabase> source) {
    }

    private record Contract(String name, ContractBody body) {
    }

    @FunctionalInterface
    private interface ContractBody {
        void accept(ExternalTlsDatabase target) throws Exception;
    }

    private static final List<Engine> ENGINES = List.of(
            new Engine("MySQL", ExternalTlsDatabase::mysql),
            new Engine("MariaDB", ExternalTlsDatabase::mariadb),
            new Engine("PostgreSQL", ExternalTlsDatabase::postgres));

    private static final List<Contract> CONTRACTS = List.of(
            new Contract("offMeansPlaintext", ExternalTlsDatabaseTest::offMeansPlaintext),
            new Contract("requireEncryptsSession", ExternalTlsDatabaseTest::requireEncryptsSession),
            new Contract("verifyCaAcceptsTrustedAnchor",
                    ExternalTlsDatabaseTest::verifyCaAcceptsTrustedAnchor),
            new Contract("verifyCaRejectsRogueAnchor",
                    ExternalTlsDatabaseTest::verifyCaRejectsRogueAnchor),
            new Contract("verifyIdentityAcceptsNameInCert",
                    ExternalTlsDatabaseTest::verifyIdentityAcceptsNameInCert),
            new Contract("verifyIdentityRejectsOtherName",
                    ExternalTlsDatabaseTest::verifyIdentityRejectsOtherName));

    /** 矩阵的展开：加密对端 × 契约。 */
    static Stream<Arguments> matrix() {
        Stream<Arguments> flat = Stream.empty();
        for (Engine engine : ENGINES) {
            for (Contract contract : CONTRACTS) {
                flat = Stream.concat(flat,
                        Stream.of(Arguments.of(engine.label(), contract.name(),
                                engine.source(), contract.body())));
            }
        }
        return flat;
    }

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("matrix")
    void tlsContract(String engineLabel, String contractName,
                     Supplier<ExternalTlsDatabase> source, ContractBody body) throws Exception {
        ExternalTlsDatabase target = source.get();
        assumeTrue(target != null, "未配置 " + engineLabel + " 的 ADB_TEST_*_TLS_* —— 跳过 TLS 契约"
                + "（先跑 scripts/dbmatrix.sh tls-up，再 eval \"$(scripts/dbmatrix.sh env)\"）");
        assumeTrue(!target.caPem().isBlank() && Files.isReadable(Path.of(target.caPem())),
                "CA 文件不可读：" + target.caPem() + " —— 跳过，不算验过");
        assumeTrue(!target.rogueCaPem().isBlank()
                        && Files.isReadable(Path.of(target.rogueCaPem())),
                "冒名 CA 文件不可读：" + target.rogueCaPem() + " —— 跳过，不算验过");
        try {
            body.accept(target);
        } catch (SQLException e) {
            // 对端抽风（实测远端 MySQL 的 TLS 握手会间歇卡住）不是我们参数拼错：
            // 记跳过，看得见，但不计入门禁。带证书原文的失败<b>不</b>算抽风 ——
            // 那正是这几条契约要抓的东西，吞掉等于把结论丢掉。
            if (!isCertificateFailure(e) && isTransientComm(e)) {
                Assumptions.abort("TLS 对端暂时不可达（" + engineLabel + " · " + contractName
                        + "）：" + e.getMessage() + " —— 跳过，不计入门禁");
            }
            throw e;
        }
    }

    // ─────────────────────────── 六条契约 ───────────────────────────

    /** ① 界面写「不加密」，会话就必须是明文。 */
    private static void offMeansPlaintext(ExternalTlsDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec(Dialect.SslLevel.OFF, Map.of()))) {
            assertThat(sessionCipher(session, target.kind()))
                    .as("「不加密」必须真的不加密：驱动默认是「能加密就加密」，"
                            + "不写参数等于让标签骗人（D-33）")
                    .isEmpty();
        }
    }

    /** ② 「必须加密」→ 服务端侧读回密码套件。 */
    private static void requireEncryptsSession(ExternalTlsDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(
                target.spec(Dialect.SslLevel.REQUIRE, Map.of()))) {
            assertThat(sessionCipher(session, target.kind()))
                    .as("选了「必须加密」却没建立加密会话：这一档没落到驱动参数上")
                    .isNotBlank();
        }
    }

    /** ③ 把签发方交给驱动之后，链校验要过得去。 */
    private static void verifyCaAcceptsTrustedAnchor(ExternalTlsDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec(Dialect.SslLevel.VERIFY_CA,
                trustAnchor(target, target.caPem())))) {
            assertThat(sessionCipher(session, target.kind()))
                    .as("信任材料给了还读不到密码套件，说明这一档根本没走 TLS")
                    .isNotBlank();
        }
    }

    /** ④ 换一把没签过服务器证书的 CA：必须失败，而且要归到「链不受信」而不是「连接断了」。 */
    private static void verifyCaRejectsRogueAnchor(ExternalTlsDatabase target) throws Exception {
        Throwable error = catchThrowable(() -> {
            try (JdbcSession ignored = JdbcSession.open(target.spec(Dialect.SslLevel.VERIFY_CA,
                    trustAnchor(target, target.rogueCaPem())))) {
                throw new AssertionError("用冒名 CA 也连上了 —— 这一档其实根本没校验证书链");
            }
        });
        assertThat(error).isInstanceOf(SQLException.class);
        assertThat(ErrorClassifier.classify(error))
                .as("证书链失败必须归成证书类，否则建议把用户支去查网络（D-32）")
                .extracting(ErrorClassifier.Diagnosis::kind,
                        ErrorClassifier.Diagnosis::summaryKey)
                .containsExactly(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED,
                        "error.summary.tlsUntrusted");
    }

    /** ⑤ 用证书里写的那个名字连，主机名校验要过得去。 */
    private static void verifyIdentityAcceptsNameInCert(ExternalTlsDatabase target)
            throws Exception {
        ExternalTlsDatabase named = target.withHost(target.hostInCert());
        try (JdbcSession session = JdbcSession.open(named.spec(Dialect.SslLevel.VERIFY_IDENTITY,
                trustAnchor(named, named.caPem())))) {
            assertThat(sessionCipher(session, named.kind()))
                    .as("主机名对上了却没加密")
                    .isNotBlank();
        }
    }

    /** ⑥ 同一台服务器、换个不在证书里的地址：必须失败，且归到「主机名对不上」。 */
    private static void verifyIdentityRejectsOtherName(ExternalTlsDatabase target) throws Exception {
        // 固定用环回 IP，不用 target.host()：脚本签的证书只有 DNS:localhost，
        // 地址换成 IP 就<b>必然</b>对不上 —— 用 target.host() 会让这条契约的前提随环境变形。
        ExternalTlsDatabase offName = target.withHost(HOST_NOT_IN_CERT);
        assumeTrue(!offName.hostInCert().equalsIgnoreCase(HOST_NOT_IN_CERT),
                "证书 SAN 里写了 " + HOST_NOT_IN_CERT + " —— 这条契约的前提没了");
        Throwable error = catchThrowable(() -> {
            try (JdbcSession ignored = JdbcSession.open(offName.spec(
                    Dialect.SslLevel.VERIFY_IDENTITY, trustAnchor(offName, offName.caPem())))) {
                throw new AssertionError("地址不在证书里也连上了 —— 主机名校验没生效");
            }
        });
        assertThat(error).isInstanceOf(SQLException.class);
        assertThat(ErrorClassifier.classify(error))
                .as("主机名不匹配要归成「证书主机名对不上」，不是「连接断了」")
                .extracting(ErrorClassifier.Diagnosis::kind,
                        ErrorClassifier.Diagnosis::summaryKey)
                .containsExactly(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED,
                        "error.summary.tlsHostname");
    }

    // ─────────────────────── 信任材料：三家三套写法 ───────────────────────

    /**
     * 「把 CA 交给驱动」这一件事在三家的写法 —— 键名来自方言，不在测试里另猜一套。
     *
     * <p>PostgreSQL 认 {@code sslrootcert}、MariaDB 认 {@code serverSslCert}（两个都收 PEM）；
     * Connector/J 8.2 <b>没有</b>任何 CA 文件参数（{@code getPropertyInfo} 实测没有
     * {@code sslCA} / {@code sslCapath}），链校验只走 {@code trustCertificateKeyStore*}
     * 三件套，所以这里把同一把 CA 装进一个临时 PKCS12 交给它。
     * 这正是要写进界面提示的那句实话：MySQL 这一档要的是信任库，不是 PEM 路径。
     */
    private static Map<String, String> trustAnchor(ExternalTlsDatabase target, String caPem)
            throws Exception {
        String rootKey = target.kind().dialect().sslRootCertOption();
        if (rootKey != null) {
            return Map.of(rootKey, caPem);
        }
        return Map.of(
                "trustCertificateKeyStoreUrl", Path.of(pkcs12TrustStore(caPem)).toUri().toString(),
                "trustCertificateKeyStoreType", "PKCS12",
                "trustCertificateKeyStorePassword", new String(TRUSTSTORE_PASSWORD));
    }

    /** CA PEM → PKCS12 信任库（纯 JDK，不调 keytool）；同一把 CA 只生成一次。 */
    private static String pkcs12TrustStore(String caPem) {
        return TRUST_STORES.computeIfAbsent(caPem, key -> {
            try {
                Collection<? extends Certificate> certs;
                try (InputStream in = Files.newInputStream(Path.of(key))) {
                    certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
                }
                assertThat(certs).as("CA 文件里一个证书都没有：" + key).isNotEmpty();
                KeyStore store = KeyStore.getInstance("PKCS12");
                store.load(null, null);
                int index = 0;
                for (Certificate cert : certs) {
                    store.setCertificateEntry("ca-" + index++, cert);
                }
                Path out = Files.createTempFile("aidb-tls-trust-", ".p12");
                out.toFile().deleteOnExit();
                try (OutputStream stream = Files.newOutputStream(out)) {
                    store.store(stream, TRUSTSTORE_PASSWORD);
                }
                return out.toAbsolutePath().toString();
            } catch (Exception e) {
                throw new IllegalStateException("生成临时信任库失败：" + key, e);
            }
        });
    }

    // ─────────────────────── 服务端侧读回「到底加密了没」 ───────────────────────

    /**
     * 这一条会话用的密码套件；<b>空 = 明文</b>。
     *
     * <p>⚠️ 为什么不看「连接成功」：证书校验失败之前连接可能已经建起来了，
     * 而 {@code require} 这类档位连上了也不代表加密过 —— 断言只认服务端报回来的套件名。
     * MySQL / MariaDB 读 {@code SHOW SESSION STATUS}（会话级，不是全局级），
     * PostgreSQL 读 {@code pg_stat_ssl} 配 {@code pg_backend_pid()}（同理，只看自己这条后端）。
     *
     * <p>⚠️ 列号<b>不能一套通吃</b>，这里踩过：首版两处都取第一列，而 {@code SHOW}
     * 返回的是 Variable_name + Value <b>两列</b> —— 第一列是变量名本身、永远非空，
     * 于是 ②③⑤ 在 MySQL / MariaDB 上把「Ssl_cipher」当成了密码套件，<b>绿灯是假的</b>。
     */
    private static String sessionCipher(JdbcSession session, DialectKind kind) throws SQLException {
        String sql = switch (kind) {
            case MYSQL, MARIADB -> "SHOW SESSION STATUS LIKE 'Ssl_cipher'";
            case POSTGRESQL -> "SELECT coalesce(cipher, '') FROM pg_stat_ssl"
                    + " WHERE pid = pg_backend_pid()";
            default -> throw new IllegalStateException("TLS 契约没有这一家：" + kind);
        };
        int valueColumn = switch (kind) {
            case MYSQL, MARIADB -> 2;
            case POSTGRESQL -> 1;
            default -> throw new IllegalStateException("TLS 契约没有这一家：" + kind);
        };
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return "";
            }
            return rs.getString(valueColumn) == null ? "" : rs.getString(valueColumn).trim();
        }
    }

    /** 证书类失败（下面这些串全是三家在真 TLS 对端上的实测原文）。 */
    private static boolean isCertificateFailure(Throwable error) {
        return mentionsAny(error, "path does not chain", "pkix path building failed",
                "certificate_unknown", "hostname verification failed",
                "server identity verification failed", "hostnameverifier");
    }

    /** 通信类抽风（服务端 / 网络问题，不是我们的参数问题）。 */
    private static boolean isTransientComm(Throwable error) {
        return mentionsAny(error, "communications link failure", "communicationsexception",
                "connection refused", "read timed out", "sockettimeout");
    }

    /** 沿 cause 链（最多 8 层，防自引用）找任意一个探针。 */
    private static boolean mentionsAny(Throwable error, String... needles) {
        Map<Throwable, Boolean> seen = new LinkedHashMap<>();
        for (Throwable current = error; current != null && seen.size() < 8;
             current = current.getCause()) {
            if (seen.put(current, Boolean.TRUE) != null) {
                break;
            }
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (message.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
