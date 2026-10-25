package io.agenticdb.ui.shell;

import io.agenticdb.safety.ErrorClassifier;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2-2：错误详情的**文本口径**门禁。
 *
 * <p>弹窗可以改版，但「分类 + 原因 + 建议 + 驱动原文 + SQL」这一组信息不能丢 ——
 * 丢了用户就只能看到一句「执行失败」，和 V-12 之前没区别。
 */
class ErrorDetailsTest {

    @Test
    void missingTableIsClassifiedAndExplained() {
        ErrorDetails details = ErrorDetails.of("保存失败",
                new SQLException("no such table: user_profiles"),
                "UPDATE user_profiles SET name = 'x' WHERE id = 1");

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.NOT_FOUND);
        assertThat(details.kindLabel()).isEqualTo("对象不存在");
        assertThat(details.headline())
                .as("标题栏直接给出是哪张表，而不是只写「对象不存在」")
                .isEqualTo("对象不存在：no such table: user_profiles");
        assertThat(details.body())
                .contains("建议：\n检查表名拼写")
                .contains("异常类型：\njava.sql.SQLException")
                .contains("SQL：\nUPDATE user_profiles")
                .doesNotContain("驱动原文：\nno such table: user_profiles")
                .as("驱动原文已经顶到标题栏，正文不许重复一遍");
    }

    @Test
    void syntaxErrorsAreTheirOwnKind() {
        ErrorDetails details = ErrorDetails.of("执行失败",
                new SQLException("near \"SELEC\": syntax error"), "SELEC 1");

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.SYNTAX);
        assertThat(details.kindLabel()).isEqualTo("语法错误");
        assertThat(details.headline()).contains("语法错误");
        assertThat(details.body())
                .contains("驱动原文：")
                .contains("near \"SELEC\"");
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
                .isEqualTo("对象不存在：no such table: audit_log");
        assertThat(details.body())
                .contains("驱动原文：\n执行保存失败\nno such table: audit_log")
                .contains("java.lang.RuntimeException ← java.sql.SQLException");
    }

    @Test
    void anUnknownErrorStillProducesSomethingCopyable() {
        ErrorDetails details = ErrorDetails.of("保存失败", null, null);

        assertThat(details.kind()).isEqualTo(ErrorClassifier.Kind.OTHER);
        assertThat(details.headline()).isEqualTo("其它 · 未知错误");
        assertThat(details.body()).isEmpty();
        assertThat(details.copyText())
                .isEqualTo("保存失败：其它 · 未知错误");
    }

    @Test
    void copyingIncludesTitleAndEverySection() {
        ErrorDetails details = ErrorDetails.of("查询失败",
                new SQLException("Access denied for user 'app'@'localhost'"),
                "SELECT * FROM users");

        String copy = details.copyText();
        assertThat(copy)
                .startsWith("查询失败：权限不足")
                .contains("建议：")
                .contains("Access denied")
                .contains("java.sql.SQLException")
                .contains("SQL：\nSELECT * FROM users");
    }

    @Test
    void blankSqlAndAdviceDoNotLeaveEmptySections() {
        ErrorDetails details = new ErrorDetails("导出失败", ErrorClassifier.Kind.OTHER,
                "执行失败", "  ", "磁盘已满", "java.io.IOException", " ");

        assertThat(details.body())
                .isEqualTo("驱动原文：\n磁盘已满\n\n异常类型：\njava.io.IOException");
    }
}
