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
}
