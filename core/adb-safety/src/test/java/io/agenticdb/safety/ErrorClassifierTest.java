package io.agenticdb.safety;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ V-12 门禁：错误归类。
 *
 * <p>断言的不只是「归到了哪一类」，还有「**给用户的建议是不是有用**」——
 * 一个只会说「执行失败」的归类器等于没做。
 */
class ErrorClassifierTest {

    @Test
    void classifiesMissingTable() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("SQLITE_ERROR: no such table: users"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
        assertThat(diagnosis.advice())
                .as("必须给出可执行的下一步")
                .contains("表名");
    }

    @Test
    void classifiesPermissionDenied() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("Access denied for user 'root'@'10.0.0.3'"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.PERMISSION_DENIED);
        assertThat(diagnosis.advice()).contains("权限");
    }

    @Test
    void classifiesConnectionLost() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("Communications link failure"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.CONNECTION_LOST);
        assertThat(diagnosis.advice()).contains("重新连接");
    }

    @Test
    void classifiesTimeout() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLTimeoutException("query timeout"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.TIMEOUT);
        assertThat(diagnosis.advice()).contains("超时");
    }

    @Test
    void classifiesLockConflict() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("Lock wait timeout exceeded; try restarting transaction"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.LOCK_CONFLICT);
    }

    @Test
    void classifiesConstraintViolation() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("UNIQUE constraint failed: users.email"));

        assertThat(diagnosis.kind()).isEqualTo(ErrorClassifier.Kind.CONSTRAINT_VIOLATION);
        assertThat(diagnosis.advice()).contains("约束");
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
        assertThat(diagnosis.message())
                .as("即使归不出来，**原文也要留着**")
                .contains("某个没见过的怪错误");
    }

    @Test
    void messageKeepsBothTheSummaryAndTheOriginalText() {
        ErrorClassifier.Diagnosis diagnosis = ErrorClassifier.classify(
                new SQLException("no such table: users"));

        assertThat(diagnosis.message())
                .contains("对象不存在")
                .contains("no such table: users");
    }
}
