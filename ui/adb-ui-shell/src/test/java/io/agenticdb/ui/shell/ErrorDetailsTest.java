package io.agenticdb.ui.shell;

import io.agenticdb.safety.ErrorClassifier;
import io.agenticdb.ui.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2-2：错误详情的**文本口径**门禁。
 *
 * <p>弹窗可以改版，但「分类 + 原因 + 建议 + 驱动原文 + SQL」这一组信息不能丢 ——
 * 丢了用户就只能看到一句「执行失败」，和 V-12 之前没区别。丢没丢看 {@code copyText()}：
 * 驱动原文在标题栏还是在正文里可以改，只要<b>复制得到</b>就算没丢。
 *
 * <p>⭐ R45 G3-b 撤掉了这条门禁上的一个语言枷锁。以前「分类名与说明重复 → 原文顶到标题栏」
 * 判的是<b>文本</b>，而说明是模型层写死的中文（D-9）：英文界面上英文分类名对不上那句中文，
 * 判重恒不成立，于是钉这条行为的用例只能显式切到中文跑（当时的注释说这是「只有那种语言下
 * 这条判重是真的在生效」）。现在说明走键、判重按<b>类别</b>，两种语言下都成立 ——
 * 枷锁拆了，并且 {@link #thePromotionToTheHeadlineIsLocaleIndependent()} 把「英文下也生效」
 * 本身钉成了门禁，防止以后又退回成文本比对。
 */
class ErrorDetailsTest {

    @AfterEach
    void restoreDefaultLocale() {
        I18n.setLocale(I18n.DEFAULT_LOCALE);
    }

    @Test
    void missingTableIsClassifiedAndExplained() {
        ErrorDetails details = ErrorDetails.of("保存失败",
                new SQLException("no such table: user_profiles"),
                "UPDATE user_profiles SET name = 'x' WHERE id = 1");

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
        assertThat(details.kindLabel()).isEqualTo(I18n.t("error.kind.notFound"));
        assertThat(details.headline())
                .as("标题栏直接给出是哪张表，而不是只重说一遍徽标")
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.kind.notFound"), "no such table: user_profiles"));
        assertThat(details.body())
                .contains(I18n.t("error.section.advice") + I18n.t("error.advice.notFound"))
                .contains(I18n.t("error.section.exception") + "java.sql.SQLException")
                .contains(I18n.t("error.section.sql") + "UPDATE user_profiles")
                .doesNotContain(I18n.t("error.section.driver"))
                .as("驱动原文已经顶到标题栏，正文不许重复一遍");
        assertThat(details.copyText())
                .as("顶上去不等于丢掉：复制文本里必须有这句驱动原文")
                .contains("no such table: user_profiles");
    }

    @Test
    void syntaxErrorsAreTheirOwnKind() {
        ErrorDetails details = ErrorDetails.of("执行失败",
                new SQLException("near \"SELEC\": syntax error"), "SELEC 1");

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.SYNTAX);
        assertThat(details.kindLabel()).isEqualTo(I18n.t("error.kind.syntax"));
        assertThat(details.headline())
                .as("出错在哪个词上是这条错误唯一有用的信息，必须在标题栏")
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.kind.syntax"), "near \"SELEC\": syntax error"));
        assertThat(details.body())
                .contains(I18n.t("error.section.advice") + I18n.t("error.advice.syntax"))
                .doesNotContain(I18n.t("error.section.driver"));
        assertThat(details.copyText()).contains("near \"SELEC\"");
    }

    /**
     * 「原文顶到标题栏、正文不再重复」这条规则<b>不能只在一种语言里成立</b>。
     *
     * <p>这正是 R45 G3-b 修掉的那个死分支：判重比文本时，英文界面上它一次都没跑过。
     * 这里同一份输入跑两种 locale，断两边给出同样的结构。
     */
    @Test
    void thePromotionToTheHeadlineIsLocaleIndependent() {
        String driver = "no such table: users";
        ErrorDetails en = ErrorDetails.of("Query failed", new SQLException(driver),
                "SELECT * FROM users");
        String enHeadline = en.headline();
        String enBody = en.body();
        assertThat(enHeadline)
                .as("英文界面上徽标同样只是重说类别，所以驱动原文同样要顶上来")
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.kind.notFound"), driver));
        assertThat(enBody).doesNotContain(I18n.t("error.section.driver"));
        assertThat(enBody)
                .as("正文里那句建议必须是英文那句")
                .contains(I18n.t("error.section.advice") + I18n.t("error.advice.notFound"));

        I18n.setLocale(I18n.CHINESE);
        ErrorDetails zh = ErrorDetails.of("查询失败", new SQLException(driver),
                "SELECT * FROM users");
        assertThat(zh.headline())
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.kind.notFound"), driver));
        assertThat(zh.body())
                .doesNotContain(I18n.t("error.section.driver"))
                .contains(I18n.t("error.section.advice") + I18n.t("error.advice.notFound"));
        assertThat(zh.body()).isNotEqualTo(enBody)
                .as("结构一致，但措辞必须跟着语言走 —— 不许把另一句留在对面那界面上");
    }

    @Test
    void wrappedCausesKeepTheRealDriverMessage() {
        SQLException root = new SQLException("no such table: audit_log");
        ErrorDetails details = ErrorDetails.of("保存失败",
                new RuntimeException("执行保存失败", root), "DELETE FROM audit_log");

        assertThat(details.kind())
                .as("归类看的是整条链，展示也必须给整条链")
                .isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
        assertThat(details.headline())
                .as("标题栏取链尾的根因")
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.kind.notFound"), "no such table: audit_log"));
        assertThat(details.body())
                .as("链是多行的，标题栏放不下 —— 整条留在正文的驱动原文段里")
                .contains(I18n.t("error.section.driver") + "执行保存失败\nno such table: audit_log")
                .contains("java.lang.RuntimeException ← java.sql.SQLException");
    }

    @Test
    void anUnknownErrorStillProducesSomethingCopyable() {
        ErrorDetails details = ErrorDetails.of("保存失败", null, null);

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.OTHER);
        assertThat(details.headline())
                .as("归不出类别时说明本身带信息（「未知错误」），保留「徽标 · 说明」")
                .isEqualTo(I18n.t("error.join.headline",
                        I18n.t("error.kind.other"), I18n.t("error.summary.unknown")));
        assertThat(details.body()).isEmpty();
        assertThat(details.copyText())
                .isEqualTo(I18n.t("error.join.colon", "保存失败", details.headline()));
    }

    @Test
    void copyingIncludesTitleAndEverySection() {
        ErrorDetails details = ErrorDetails.of("查询失败",
                new SQLException("Access denied for user 'app'@'localhost'"),
                "SELECT * FROM users");

        String copy = details.copyText();
        assertThat(copy)
                .startsWith(I18n.t("error.join.colon", "查询失败",
                        I18n.t("error.kind.permissionDenied")))
                .contains(I18n.t("error.section.advice"))
                .contains("Access denied")
                .contains("java.sql.SQLException")
                .contains(I18n.t("error.section.sql") + "SELECT * FROM users");
    }

    @Test
    void blankSqlAndAdviceDoNotLeaveEmptySections() {
        ErrorDetails details = new ErrorDetails("导出失败", ErrorClassifier.Kind.OTHER,
                I18n.t("error.summary.failed"), "  ", "磁盘已满", "java.io.IOException", " ");

        assertThat(details.body())
                .isEqualTo(I18n.t("error.section.driver") + "磁盘已满\n\n"
                        + I18n.t("error.section.exception") + "java.io.IOException");
    }
}
