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
 * 丢了用户就只能看到一句「执行失败」，和 V-12 之前没区别。
 *
 * <p>⚠️ 第二十一批把这 12 条文案抽进资源包之后，断言一律走 {@code I18n.t(...)} 组装，
 * 不写死中文。但「分类名与说明重复 → 原文顶到标题栏」这条判重比的是<b>文本</b>，而
 * {@link ErrorClassifier.Diagnosis#summary()} 仍是模型层的中文（D-9）：英文界面下英文
 * 分类名对不上那句中文，判重就不成立。钉这条行为的两个用例因此显式切到中文跑，
 * {@link #restoreDefaultLocale()} 负责复位 —— 不是把门禁钉死在一种语言上，
 * 而是**只有那种语言下这条判重是真的在生效**，等 D-9 抽掉模型层中文后撤掉。
 */
class ErrorDetailsTest {

    @AfterEach
    void restoreDefaultLocale() {
        I18n.setLocale(I18n.DEFAULT_LOCALE);
    }

    @Test
    void missingTableIsClassifiedAndExplained() {
        I18n.setLocale(I18n.CHINESE);
        ErrorDetails details = ErrorDetails.of("保存失败",
                new SQLException("no such table: user_profiles"),
                "UPDATE user_profiles SET name = 'x' WHERE id = 1");

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
        assertThat(details.kindLabel()).isEqualTo(I18n.t("error.kind.notFound"));
        assertThat(details.headline())
                .as("标题栏直接给出是哪张表，而不是只写「对象不存在」")
                .isEqualTo(I18n.t("error.join.colon",
                        I18n.t("error.kind.notFound"), "no such table: user_profiles"));
        assertThat(details.body())
                .contains(I18n.t("error.section.advice") + "检查表名拼写")
                .contains(I18n.t("error.section.exception") + "java.sql.SQLException")
                .contains(I18n.t("error.section.sql") + "UPDATE user_profiles")
                .doesNotContain(I18n.t("error.section.driver") + "no such table: user_profiles")
                .as("驱动原文已经顶到标题栏，正文不许重复一遍");
    }

    @Test
    void syntaxErrorsAreTheirOwnKind() {
        ErrorDetails details = ErrorDetails.of("执行失败",
                new SQLException("near \"SELEC\": syntax error"), "SELEC 1");

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.SYNTAX);
        assertThat(details.kindLabel()).isEqualTo(I18n.t("error.kind.syntax"));
        assertThat(details.headline()).contains(I18n.t("error.kind.syntax"));
        assertThat(details.body())
                .contains(I18n.t("error.section.driver"))
                .contains("near \"SELEC\"");
    }

    @Test
    void wrappedCausesKeepTheRealDriverMessage() {
        I18n.setLocale(I18n.CHINESE);
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
                .contains(I18n.t("error.section.driver") + "执行保存失败\nno such table: audit_log")
                .contains("java.lang.RuntimeException ← java.sql.SQLException");
    }

    @Test
    void anUnknownErrorStillProducesSomethingCopyable() {
        ErrorDetails details = ErrorDetails.of("保存失败", null, null);

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.OTHER);
        assertThat(details.headline()).isEqualTo(I18n.t("error.kind.other") + " · 未知错误");
        assertThat(details.body()).isEmpty();
        assertThat(details.copyText())
                .isEqualTo(I18n.t("error.join.colon", "保存失败",
                        I18n.t("error.kind.other") + " · 未知错误"));
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
                "执行失败", "  ", "磁盘已满", "java.io.IOException", " ");

        assertThat(details.body())
                .isEqualTo(I18n.t("error.section.driver") + "磁盘已满\n\n"
                        + I18n.t("error.section.exception") + "java.io.IOException");
    }
}
