package io.agenticdb.safety;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/**
 * ⭐ V-12：数据库错误的**归类**与可读说明。
 *
 * <h2>为什么要有</h2>
 * 驱动抛出来的错误原文对普通用户没有意义：
 * <pre>
 *   SQLITE_ERROR: no such table: users
 *   Access denied for user 'root'@'10.0.0.3'
 *   Lock wait timeout exceeded; try restarting transaction
 * </pre>
 * 用户真正想知道的是三件事：<b>发生了什么 / 是不是我的问题 / 下一步怎么办</b>。
 *
 * <p>这个类把驱动原文归成 9 类，每类给出<b>一句说明</b> + <b>一个建议动作</b>。
 * 界面拿到的不是 SQLException，而是「能照着做」的信息。
 *
 * <h2>⭐ R45 G3-b：这里交的是<b>键</b>，不是文案</h2>
 * 归类是模型层的事（读驱动原文），<b>怎么说</b>是界面层的事。
 * 以前这里直接把中文写进 {@code summary}/{@code advice}，于是英文界面上
 * 状态栏会长出一句「对象不存在：no such table: users —— 检查表名拼写…」，
 * 而且连接符「：」「——」也一起漏出去（D-9 家族的最后一大块）。
 * 现在 {@link Diagnosis} 带的是 {@code summaryKey}/{@code adviceKey}，
 * 渲染口只有 {@code ErrorText}（状态栏一行）和 {@code ErrorDetails}（详情弹窗）两处，
 * 两侧都在 {@code adb-ui-shell} 里 —— 切语言不会切出半句中文。
 *
 * <p>⚠️ {@code classify()} 里那些<b>中文探针</b>（如「只读」）不是文案，是<b>匹配用的输入</b>：
 * 服务器用什么 locale 就用什么语言报错，跟界面语言无关。删掉它们等于把一类真错误归不出来。
 */
public final class ErrorClassifier {

    /** 错误类别。 */
    public enum Kind {
        /** 连接断了（网络 / 服务重启）。 */
        CONNECTION_LOST,
        /** 权限不足。 */
        PERMISSION_DENIED,
        /** 对象不存在。 */
        NOT_FOUND,
        /** 超时（语句或锁等待）。 */
        TIMEOUT,
        /** 死锁 / 锁冲突。 */
        LOCK_CONFLICT,
        /** 约束冲突（唯一键 / 外键 / 非空 / CHECK）。 */
        CONSTRAINT_VIOLATION,
        /** 语法错误。 */
        SYNTAX,
        /**
         * ⭐ D-32：连上了、TLS 握手也开始了，但<b>证书没通过校验</b>（链认不出 / 主机名对不上）。
         *
         * <p>这一类以前会被归进 {@link #CONNECTION_LOST}：MySQL 的驱动把 JSSE 的
         * {@code CertPathValidatorException} 包进 {@code CommunicationsException}，
         * 整条消息里就有「communications link failure」那句 —— 探针按包含匹配，先到先判。
         * 归进「连接断了」的代价是<b>建议把人支开</b>：让人去查网络和服务器进程，
         * 而真正要做的是把 CA 交给驱动、或者把主机名填成证书里写的那个。
         */
        TLS_VERIFICATION_FAILED,
        /** 其它。 */
        OTHER
    }

    /**
     * 归类结果。
     *
     * @param kind      类别
     * @param summaryKey 一句话说明的<b>资源键</b>（界面渲染时解析）
     * @param adviceKey 建议动作的资源键；空串 = 这类没有可建议的动作
     * @param detail    驱动原文（给排查用，本来就不该翻译）
     */
    public record Diagnosis(Kind kind, String summaryKey, String adviceKey, String detail) {

        public Diagnosis {
            kind = kind == null ? Kind.OTHER : kind;
            summaryKey = summaryKey == null || summaryKey.isBlank()
                    ? "error.summary.unknown" : summaryKey;
            adviceKey = adviceKey == null ? "" : adviceKey;
            detail = detail == null ? "" : detail;
        }
    }

    private ErrorClassifier() {
    }

    /**
     * 归类一个异常。
     *
     * @param error 异常（{@link SQLException} 或任何）
     */
    public static Diagnosis classify(Throwable error) {
        if (error == null) {
            return new Diagnosis(Kind.OTHER, "error.summary.unknown", "", "");
        }
        if (error instanceof SQLTimeoutException) {
            return new Diagnosis(Kind.TIMEOUT, "error.summary.queryTimeout",
                    "error.advice.queryTimeout", detail(error));
        }
        String text = messageOf(error);
        String lower = text.toLowerCase(Locale.ROOT);

        if (contains(lower, "lock wait timeout", "deadlock", "lock timeout")) {
            return new Diagnosis(Kind.LOCK_CONFLICT, "error.summary.lockConflict",
                    "error.advice.lockConflict", detail(error));
        }
        if (contains(lower, "access denied", "permission denied", "not authorized",
                "insufficient privilege", "只读")) {
            return new Diagnosis(Kind.PERMISSION_DENIED, "error.summary.permissionDenied",
                    "error.advice.permissionDenied", detail(error));
        }
        if (contains(lower, "no such table", "doesn't exist", "does not exist",
                "unknown table", "undefined table", "doesn't exist")) {
            return new Diagnosis(Kind.NOT_FOUND, "error.summary.notFound",
                    "error.advice.notFound", detail(error));
        }
        if (contains(lower, "syntax error", "near \"", "sql syntax")) {
            return new Diagnosis(Kind.SYNTAX, "error.summary.syntax",
                    "error.advice.syntax", detail(error));
        }
        if (contains(lower, "unique constraint", "duplicate entry", "unique failed",
                "primary key", "foreign key", "check constraint", "not null",
                "constraint failed")) {
            return new Diagnosis(Kind.CONSTRAINT_VIOLATION, "error.summary.constraint",
                    "error.advice.constraint", detail(error));
        }
        // ⭐ D-32：证书类失败必须在「连接断了」<b>之前</b>判。
        //    下面那支的探针里有 "communications link failure" 和 "could not connect"，
        //    而 MySQL 的驱动把 JSSE 的证书异常<b>就包在这两句里</b>、MariaDB 那句
        //    「Could not connect to …: (certificate_unknown) PKIX path building failed」同理 ——
        //    放后面等于这一类永远不会被归出来。这些串全是三家驱动在真 TLS 对端上
        //    实测吐出来的原文（见 ExternalTlsDatabaseTest 那条契约），不是照文档猜的。
        if (contains(lower, "path does not chain with any of the trust anchors",
                "pkix path building failed", "unable to find valid certification path",
                "certificate_unknown")) {
            return new Diagnosis(Kind.TLS_VERIFICATION_FAILED, "error.summary.tlsUntrusted",
                    "error.advice.tlsUntrusted", detail(error));
        }
        if (contains(lower, "server identity verification failed",
                "hostname verification failed", "doesn't correspond to certificate",
                "hostnameverifier")) {
            // pgjdbc 这句会跟着 JVM 默认语言本地化（中文环境下是「主机名…无法通过
            // HostnameVerifier …认证」），所以探针抓的是那个不会被翻译的类名。
            return new Diagnosis(Kind.TLS_VERIFICATION_FAILED, "error.summary.tlsHostname",
                    "error.advice.tlsHostname", detail(error));
        }
        if (contains(lower, "connection closed", "connection reset", "broken pipe",
                "communications link failure", "connection refused", "could not connect",
                "server has gone away", "connection is closed", "closed")) {
            return new Diagnosis(Kind.CONNECTION_LOST, "error.summary.connectionLost",
                    "error.advice.connectionLost", detail(error));
        }
        // ⭐ D-34：这一支原来在「连接断了」<b>前面</b>，现在挪到后面。
        //    实测 MySQL 连不上时最外层就是 "Communications link failure"，而它的 cause
        //    经常正好是 "connect timed out"（驱动原文链：CommunicationsException →
        //    CJCommunicationsException → …→ SocketTimeoutException）—— 超时探针先到，
        //    建议就成了「缩小查询范围再试」，可用户面对的是一条<b>根本没建起来</b>的连接，
        //    跟 SQL 范围毫无关系。锁等待超时仍在最前面（那是 SQL 层的事，建议不同），
        //    这里只把「连不上」与「跑太久」的先后摆正。
        if (contains(lower, "timed out", "timeout", "socket timeout")) {
            return new Diagnosis(Kind.TIMEOUT, "error.summary.timeout",
                    "error.advice.timeout", detail(error));
        }
        return new Diagnosis(Kind.OTHER, "error.summary.failed", "", detail(error));
    }

    private static boolean contains(String lower, String... needles) {
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String messageOf(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append(" | ");
            }
            current = current.getCause();
            depth++;
        }
        return text.toString();
    }

    private static String detail(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
