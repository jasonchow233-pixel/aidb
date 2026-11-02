package io.agenticdb.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R45 G3-b：约束类错误的<b>归因</b>门禁。
 *
 * <p>这里判的是「驱动原文 → 哪个建议键」，文字在界面侧（{@code ErrorTextTest}）。
 * 键是死的：写错一个字母会被 {@code I18nAuditTest} 的模型层键门禁拦住，
 * 但「认不出来的错误必须交回空串」只能在这里判 ——
 * 交回一个猜出来的建议比不交更糟（用户会照着做一件错事）。
 */
class ErrorLocatorTest {

    @Test
    void eachConstraintShapeGetsItsOwnAdviceKey() {
        assertThat(ErrorLocator.constraintAdviceKey(
                "FOREIGN KEY constraint failed")).isEqualTo("error.rule.foreignKey");
        assertThat(ErrorLocator.constraintAdviceKey(
                "UNIQUE constraint failed: users.email")).isEqualTo("error.rule.unique");
        assertThat(ErrorLocator.constraintAdviceKey(
                "NOT NULL constraint failed: orders.total")).isEqualTo("error.rule.notNull");
        assertThat(ErrorLocator.constraintAdviceKey(
                "CHECK constraint failed: ck_positive")).isEqualTo("error.rule.check");
        assertThat(ErrorLocator.constraintAdviceKey(
                "datatype mismatch")).isEqualTo("error.rule.typeMismatch");
        assertThat(ErrorLocator.constraintAdviceKey(
                "cannot modify readonly column: seq")).isEqualTo("error.rule.readOnly");
    }

    /** 驱动原文大小写不一（有的写 {@code UNIQUE}、有的写 {@code unique}）也要认得。 */
    @Test
    void matchingIgnoresCase() {
        assertThat(ErrorLocator.constraintAdviceKey("unique Constraint FAILED"))
                .isEqualTo("error.rule.unique");
    }

    @Test
    void anUnrecognizedErrorGetsNoAdvice() {
        assertThat(ErrorLocator.constraintAdviceKey("no such table: users")).isEmpty();
        assertThat(ErrorLocator.constraintAdviceKey("")).isEmpty();
        assertThat(ErrorLocator.constraintAdviceKey(null)).isEmpty();
    }

    /** 顺序也是判据：外键失败里同时带着「constraint failed」字样，不能归成别的。 */
    @Test
    void theMostSpecificRuleWins() {
        assertThat(ErrorLocator.constraintAdviceKey(
                "FOREIGN KEY constraint failed")).isNotEqualTo("error.rule.notNull");
    }
}
