package io.agenticdb.ui.shell;

import io.agenticdb.safety.ErrorClassifier;
import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R45 G3-b：错误<b>文案渲染口</b>的门禁 —— 「一句说明 + 一个建议」到底说了什么。
 *
 * <h2>为什么判据从这里开始才成立</h2>
 * V-12 时代这些句子写死在 {@code ErrorClassifier} 里，所以「建议是不是有用」只能在
 * {@code adb-safety} 的测试中断言中文（{@code contains("表名")}）。键化之后模型层交回的
 * 是 {@code error.advice.notFound}，<b>文字</b>只在这里长出来 —— 判据必须跟着文字搬过来，
 * 否则「有用」这件事就没人判了，而一个只会说「执行失败」的归类器等于没做（V-12 的原话）。
 *
 * <p>于是这里同时判三件事：
 * <ol>
 *   <li><b>有用</b>：中英两侧的建议都得包含一个照着能做的动作；</li>
 *   <li><b>不混语言</b>：英文界面这一整行不许夹中文，也不许露出 {@code ⚠}（缺键的可见标记）；</li>
 *   <li><b>不撒谎</b>：没有建议的类别不许留一个孤零零的连接符。</li>
 * </ol>
 */
class ErrorTextTest {

    @AfterEach
    void restoreDefaultLocale() {
        I18n.setLocale(I18n.DEFAULT_LOCALE);
    }

    /** 八类里每一类都要有<b>可执行</b>的下文（中文界面：这是 D-9 之前那些断言的家）。 */
    @Test
    void everyClassGetsAnActionableAdviceInChinese() {
        I18n.setLocale(Locale.CHINESE);

        assertThat(adviceFor("no such table: users")).contains("表名");
        assertThat(adviceFor("Access denied for user 'root'@'10.0.0.3'")).contains("权限");
        assertThat(adviceFor("Communications link failure")).contains("重新连接");
        assertThat(adviceFor("UNIQUE constraint failed: users.email")).contains("约束");
        assertThat(adviceFor("near \"SELCT\": syntax error")).contains("格式化");
        assertThat(adviceFor("Lock wait timeout exceeded")).contains("分批");
        assertThat(adviceForQueryTimeout()).contains("超时");
    }

    /** 同一批句子在<b>英文</b>界面也得是真话，不是把键名印出来。 */
    @Test
    void everyClassGetsAnActionableAdviceInEnglish() {
        assertThat(adviceFor("no such table: users").toLowerCase(Locale.ROOT))
                .as("英文侧同样要告诉用户去查什么")
                .contains("table name");
        assertThat(adviceFor("Access denied for user 'root'@'10.0.0.3"))
                .contains("privileges");
        assertThat(adviceFor("Communications link failure")).contains("Reconnect");
        assertThat(adviceFor("UNIQUE constraint failed: users.email")).contains("constraint");
        assertThat(adviceFor("near \"SELCT\": syntax error")).contains("Format");
        assertThat(adviceFor("Lock wait timeout exceeded")).contains("batches");
    }

    /**
     * ⭐ D-11 的病灶就在这条：英文界面上状态栏半英半中。
     * 归类说明、连接符、建议三段现在都来自英文资源包，中文那一句一次都不该出现。
     */
    @Test
    void theStatusLineStaysInOneLanguageAndKeepsTheDriverText() {
        String line = ErrorText.readable(new SQLException("no such table: user_profiles"));

        assertThat(line)
                .as("状态栏一行 = 说明 + 驱动原文 + 建议")
                .isEqualTo(I18n.t("error.join.advice",
                        I18n.t("error.join.colon",
                                I18n.t("error.summary.notFound"), "no such table: user_profiles"),
                        I18n.t("error.advice.notFound")));
        assertThat(line)
                .as("英文界面不许夹模型层留下的中文（D-11）")
                .doesNotContain("对象不存在")
                .doesNotContain("检查表名")
                .doesNotContain("：")
                .doesNotContain("——");
        assertThat(line).as("一个键都没缺").doesNotContain("⚠");
    }

    /** 归不出来的类别<b>没有建议</b>：不许拖一个孤零零的连接符。 */
    @Test
    void aClassWithoutAdviceDoesNotLeaveADanglingConnector() {
        String line = ErrorText.readable(new SQLException("某个没见过的怪错误"));

        assertThat(line)
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.summary.failed"), "某个没见过的怪错误"));
        assertThat(line).doesNotContain("——").doesNotContain(" — ");
    }

    /** 中文界面：连接符换成中文的「：」「——」，说明与建议换成中文，且同样不许缺键。 */
    @Test
    void theChineseRenderUsesChineseConnectors() {
        I18n.setLocale(Locale.CHINESE);

        String line = ErrorText.readable(new SQLException("no such table: users"));

        assertThat(line)
                .isEqualTo("对象不存在：no such table: users —— 检查表名拼写与当前连接的库 / schema");
        assertThat(line).doesNotContain("⚠");
    }

    /**
     * ⭐ 每类都渲染一遍，缺一个键就红 —— 这条是「键名打错字母」在<b>界面侧</b>的第二道网
     * （第一道是 {@code I18nAuditTest} 的模型层键门禁，它只能证明资源里有这个键，
     * 证明不了渲染口真的取到了它）。
     */
    @Test
    void everyClassifiedShapeRendersWithoutMissingKeys() {
        List<String> driverMessages = List.of("no such table: users",
                "Access denied for user 'root'@'h'", "Communications link failure",
                "UNIQUE constraint failed: a.b", "near \"SELCT\": syntax error",
                "Lock wait timeout exceeded", "socket timeout", "完全没见过的原文");

        for (String message : driverMessages) {
            ErrorClassifier.Diagnosis diagnosis =
                    ErrorClassifier.classify(new SQLException(message));
            String summary = ErrorText.summary(diagnosis);
            String advice = ErrorText.advice(diagnosis);

            assertThat(summary).as("%s 的说明没渲染出来", message).isNotBlank().doesNotContain("⚠");
            assertThat(advice).as("%s 的建议不许露出键名", message).doesNotContain("⚠");
        }
    }

    /** 约束类建议同样只有一个渲染口。 */
    @Test
    void constraintAdviceRendersFromTheKey() {
        assertThat(ErrorText.constraintAdvice("FOREIGN KEY constraint failed"))
                .isEqualTo(I18n.t("error.rule.foreignKey"))
                .doesNotContain("⚠");
        assertThat(ErrorText.constraintAdvice("no such table: users"))
                .as("认不出来的错误不给建议 —— 也不许渲染成 ⚠")
                .isEmpty();
    }

    private static String adviceFor(String driverMessage) {
        return ErrorText.advice(ErrorClassifier.classify(new SQLException(driverMessage)));
    }

    private static String adviceForQueryTimeout() {
        return ErrorText.advice(ErrorClassifier.classify(
                new java.sql.SQLTimeoutException("query timeout", "SQLTE", 0)));
    }
}
