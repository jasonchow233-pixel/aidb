package io.agenticdb.safety;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ V-12 门禁：错误归类。
 *
 * <p>断言的是「归到哪一类 + 交回的是<b>哪个键</b>」。
 * 「那句建议对用户有没有用」不在这儿判 —— 从 R45 G3-b 起，模型层只交键，
 * <b>文字</b>在 {@code adb-ui-shell} 才长出来，所以那句话的门禁是
 * {@code ErrorTextTest}（中英两侧各判一次）。
 * 键名写错一个字母这件事由 {@code I18nAuditTest} 兜（它扫 {@code new Diagnosis(}）。
 */
class ErrorClassifierTest {

    @Test
    void classifiesMissingTable() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("SQLITE_ERROR: no such table: users"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.notFound");
        assertThat(diagnosis.adviceKey())
                .as("必须交回一个「可执行下一步」的键，而不是空串")
                .isEqualTo("error.advice.notFound");
    }

    @Test
    void classifiesPermissionDenied() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("Access denied for user 'root'@'10.0.0.3'"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.PERMISSION_DENIED);
        assertThat(diagnosis.adviceKey()).isEqualTo("error.advice.permissionDenied");
    }

    /**
     * ⭐ 服务器用<b>中文 locale</b> 报错时也要归得出来 —— 原文语言跟界面语言无关。
     * 这就是归类器里那句中文<b>探针</b>存在的理由（它是匹配输入，不是文案）。
     */
    @Test
    void classifiesAChineseLocaleServerMessage() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("用户 'app' 的访问被拒绝：该字段是只读的"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.PERMISSION_DENIED);
    }

    @Test
    void classifiesConnectionLost() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("Communications link failure"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.CONNECTION_LOST);
        assertThat(diagnosis.adviceKey()).isEqualTo("error.advice.connectionLost");
    }

    @Test
    void classifiesTimeout() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLTimeoutException("query timeout"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TIMEOUT);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.queryTimeout");
    }

    /** 「查询超时」与「操作超时」是两句话 —— 一个是 SQL，一个是别的慢操作。 */
    @Test
    void distinguishesQueryTimeoutFromOperationTimeout() {
        ErrorClassifier.Diagnosis query = ErrorClassifier.classify(
                new SQLTimeoutException("query timeout"));
        ErrorClassifier.Diagnosis operation = ErrorClassifier.classify(
                new SQLException("socket timeout while reading"));

        assertThat(query.summaryKey()).isEqualTo("error.summary.queryTimeout");
        assertThat(operation.summaryKey()).isEqualTo("error.summary.timeout");
    }

    @Test
    void classifiesLockConflict() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("Lock wait timeout exceeded; try restarting transaction"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.LOCK_CONFLICT);
        // 锁等待超时也是「timeout」字样 —— 它必须归成锁冲突而不是超时，
        // 否则建议会告诉用户「缩小查询范围」，而真正该做的是分批提交。
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.lockConflict");
    }

    @Test
    void classifiesConstraintViolation() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("UNIQUE constraint failed: users.email"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.CONSTRAINT_VIOLATION);
        assertThat(diagnosis.adviceKey()).isEqualTo("error.advice.constraint");
    }

    @Test
    void classifiesSyntaxError() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("SQLITE_ERROR: near \"SELCT\": syntax error"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.SYNTAX);
    }

    @Test
    void unknownErrorsFallBackButKeepTheOriginalText() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("某个没见过的怪错误"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.OTHER);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.failed");
        assertThat(diagnosis.detail())
                .as("即使归不出来，**原文也要留着**")
                .contains("某个没见过的怪错误");
    }

    /** 没有异常可归时不能交回一个空得没法渲染的键。 */
    @Test
    void nullErrorStillHasARenderableKey() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(null);

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.OTHER);
        assertThat(diagnosis.summaryKey()).startsWith("error.summary.");
        assertThat(diagnosis.adviceKey()).isEmpty();
    }

    /** ⭐ G3-b：模型层<b>不许再交中文文案</b> —— 交出去的东西会被五个页面直接上屏。 */
    @Test
    void classifiesWithoutCarryingAnyDisplayText() {
        for (String driverMessage : List.of("no such table: users",
                "Access denied for user 'root'@'h'",
                "Communications link failure",
                "UNIQUE constraint failed: a.b",
                "near \"SELCT\": syntax error",
                "Lock wait timeout exceeded",
                "socket timeout",
                "完全没见过的原文")) {
            ErrorClassifier.Diagnosis diagnosis =
                    ErrorClassifier.classify(new SQLException(driverMessage));

            assertThat(diagnosis.summaryKey() + diagnosis.adviceKey())
                    .as("%s 归出来的键必须是资源键，不是文案", driverMessage)
                    .doesNotMatch(".*[\\u4e00-\\u9fa5].*");
            assertThat(diagnosis.summaryKey())
                    .as("说明键要有命名空间，渲染口才认得")
                    .startsWith("error.summary.");
            assertThat(diagnosis.detail())
                    .as("驱动原文原样留着（排查靠它）")
                    .contains(driverMessage);
        }
    }

    /** 一个异常被包装时，归类读的是<b>整条链</b>，不是最外层那句「执行失败」。 */
    @Test
    void classifiesThroughTheCauseChain() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new IllegalStateException("执行失败",
                        new SQLException("no such table: orders")));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
    }

    // ─────────────── D-32：证书校验失败（三家真 TLS 对端上的原文链） ───────────────
    //
    // 下面每一段的原文都不是编的：本机 Docker 起三个只走 TLS 的对端
    // （{@code scripts/dbmatrix.sh tls-up}），拿冒名 CA / 证书外的地址各连一次，
    // 把驱动吐出来的整条 cause 链原样抄回来。同一件事在
    // {@code ExternalTlsDatabaseTest} 契约④⑥里也各跑一次真库 —— 这里钉的是<b>归类</b>，
    // 那边钉的是<b>连接行为</b>；CI 没有 Docker，所以这条必须能离线跑。

    /**
     * MySQL 把 JSSE 的证书异常包进 {@code CommunicationsException}：最外层只有
     * 「Communications link failure」那句，证书原文在第 3 层。
     * 归成「连接断了」的代价不是难看，是建议把人支去查网络。
     */
    @Test
    void classifiesMySqlUntrustedChainWrappedInCommunicationsFailure() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(chain(
                "Communications link failure\n\nThe last packet sent successfully to the "
                        + "server was 0 milliseconds ago. The driver has not received any packets "
                        + "from the server.",
                "Communications link failure\n\nThe last packet sent successfully to the server "
                        + "was 0 milliseconds ago.",
                "(certificate_unknown) java.security.cert.CertPathValidatorException: "
                        + "Path does not chain with any of the trust anchors",
                "java.security.cert.CertPathValidatorException: Path does not chain with any "
                        + "of the trust anchors",
                "Path does not chain with any of the trust anchors"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.tlsUntrusted");
        assertThat(diagnosis.adviceKey())
                .as("这句建议里必须写着「把 CA 交给驱动」，不是「检查网络」")
                .isEqualTo("error.advice.tlsUntrusted");
    }

    /** MySQL 主机名对不上：原文在第 3 层，最外层还是那句「Communications link failure」。 */
    @Test
    void classifiesMySqlHostnameMismatch() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(chain(
                "Communications link failure\n\nThe last packet sent successfully to the server "
                        + "was 0 milliseconds ago.",
                "Communications link failure",
                "java.security.cert.CertificateException: Server identity verification failed. "
                        + "None of the certificate's DNS or IP Subject Alternative Name entries "
                        + "matched the server hostname/IP '127.0.0.1'.",
                "Server identity verification failed. None of the certificate's DNS or IP "
                        + "Subject Alternative Name entries matched the server hostname/IP "
                        + "'127.0.0.1'."));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.tlsHostname");
        assertThat(diagnosis.adviceKey()).isEqualTo("error.advice.tlsHostname");
    }

    /**
     * MariaDB 那句<b>同时</b>写着「Could not connect」和证书原文 —— 两支探针都命中，
     * 于是<b>判的顺序</b>就是结论：证书分支必须在连接分支之前，否则这条永远归成「连接断了」。
     */
    @Test
    void certificateAttributionWinsOverCouldNotConnect() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(chain(
                "(conn=99) Could not connect to 127.0.0.1:33072 : (certificate_unknown) PKIX "
                        + "path building failed: sun.security.provider.certpath."
                        + "SunCertPathBuilderException: unable to find valid certification path "
                        + "to requested target",
                "(certificate_unknown) PKIX path building failed: sun.security.provider.certpath."
                        + "SunCertPathBuilderException: unable to find valid certification path "
                        + "to requested target",
                "PKIX path building failed: sun.security.provider.certpath."
                        + "SunCertPathBuilderException: unable to find valid certification path "
                        + "to requested target",
                "unable to find valid certification path to requested target"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.tlsUntrusted");
    }

    /** MariaDB 主机名：{@code sslMode=VERIFY_FULL} 对环回 IP 的原文。 */
    @Test
    void classifiesMariaDbHostnameMismatch() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(chain(
                "(conn=100) SSL hostname verification failed : IPv4 host \"127.0.0.1\" doesn't "
                        + "correspond to certificate CN \"localhost\" and SAN[{DNS:\"localhost\"}] "
                        + "This verification can be disabled using the sslMode to VERIFY_CA but "
                        + "won't prevent man-in-the-middle attacks anymore"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.tlsHostname");
    }

    /**
     * pgjdbc 的证书失败原文<b>跟着 JVM 默认语言走</b>：中文环境下是「SSL错误: …」，
     * 前面那句翻译挡不住探针，因为它抓的是括号里的英文。
     */
    @Test
    void classifiesPostgresChainFailureInLocalizedMessage() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(chain(
                "SSL错误: (certificate_unknown) PKIX path building failed: sun.security.provider."
                        + "certpath.SunCertPathBuilderException: unable to find valid "
                        + "certification path to requested target",
                "(certificate_unknown) PKIX path building failed"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(diagnosis.summaryKey()).isEqualTo("error.summary.tlsUntrusted");
    }

    /**
     * 同上，主机名这一句整条都被翻译了 —— 「HostnameVerifier」那个类名是唯一不会被翻的token。
     * 探针抓它，中文环境和英文环境（"could not be verified with HostnameVerifier"）都归得出来。
     */
    @Test
    void classifiesPostgresHostnameMismatchByTheOneTokenThatIsNeverTranslated() {
        ErrorClassifier.Diagnosis chinese = ErrorClassifier.classify(
                new SQLException("主机名127.0.0.1无法通过HostnameVerifier PgjdbcHostnameVerifier认证。"));
        ErrorClassifier.Diagnosis english = ErrorClassifier.classify(
                new SQLException("The hostname 127.0.0.1 could not be verified with "
                        + "HostnameVerifier PgjdbcHostnameVerifier."));

        assertThat(chinese.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(chinese.summaryKey()).isEqualTo("error.summary.tlsHostname");
        assertThat(english.summaryKey())
                .as("同一件事换一种 JVM 语言不许换成另一个结论")
                .isEqualTo("error.summary.tlsHostname");
    }

    /** 反向对照：链里<b>没有</b>证书原文的网络失败，不许被这几支探针抢走。 */
    @Test
    void plainNetworkFailureStaysConnectionLost() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(chain(
                "Communications link failure",
                "connect timed out"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.CONNECTION_LOST);
        assertThat(diagnosis.adviceKey()).isEqualTo("error.advice.connectionLost");
    }

    /**
     * 链的读取深度是个<b>已知上限</b>（5 层）：证书原文再往上一层就退回「连接断了」。
     * 钉住这个边界，免得以后有人把探针判不成当成探针的错去乱加字符串。
     * 中间的包装层刻意不含任何探针串 —— 有的话这条就在测别的东西了。
     */
    @Test
    void attributionStopsAtTheChainDepthLimit() {
        ErrorClassifier.Diagnosis within = ErrorClassifier.classify(chain(
                "Communications link failure", "驱动包装一层", "驱动包装两层", "驱动包装三层",
                "Path does not chain with any of the trust anchors"));
        ErrorClassifier.Diagnosis beyond = ErrorClassifier.classify(chain(
                "Communications link failure", "驱动包装一层", "驱动包装两层", "驱动包装三层",
                "驱动包装四层", "Path does not chain with any of the trust anchors"));

        assertThat(within.kind()).isEqualTo(ErrorClassifier.Kind.TLS_VERIFICATION_FAILED);
        assertThat(beyond.kind())
                .as("第 6 层读不到 —— 这是当前的上限，行为要写出来而不是飘着")
                .isEqualTo(ErrorClassifier.Kind.CONNECTION_LOST);
    }

    /** 按抄回来的顺序拼成一条 cause 链（最外层是驱动那句，越往里越接近 JSSE）。 */
    private static SQLException chain(String... messages) {
        Throwable cause = null;
        for (int i = messages.length - 1; i >= 0; i--) {
            cause = i == 0 ? new SQLException(messages[i], "08S01", cause)
                    : new RuntimeException(messages[i], cause);
        }
        return (SQLException) cause;
    }
}
