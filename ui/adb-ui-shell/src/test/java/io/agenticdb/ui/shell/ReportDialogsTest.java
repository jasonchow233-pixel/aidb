package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ⭐ P2-2：两个汇总弹窗的**结构门禁**。
 *
 * <p>纯格式化测试守不住「弹窗里到底有没有那块内容」—— 模型写得再对，
 * 界面漏接就是用户看不到。这里查三件事：分类徽标、正文、复制按钮真的写剪贴板。
 */
@ExtendWith(ApplicationExtension.class)
class ReportDialogsTest {

    @Start
    void start(Stage stage) {
        Scene scene = new Scene(new VBox(), 1000, 700);
        scene.getStylesheets().addAll(
                "io/agenticdb/ui/theme-light.css",
                "io/agenticdb/ui/theme-dark.css",
                "io/agenticdb/ui/app.css");
        scene.getRoot().getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void errorDialogShowsKindDetailAndCopiesEverything(FxRobot robot) throws Exception {
        ErrorDetails details = ErrorDetails.of("保存失败",
                new SQLException("no such table: user_profiles"),
                "UPDATE user_profiles SET name = 'x'");

        Alert alert = WaitForAsyncUtils.asyncFx((Callable<Alert>) () ->
                ErrorDetailsDialog.create(details)).get();
        assertThat(alert.getAlertType()).isEqualTo(Alert.AlertType.ERROR);
        assertThat(alert.getTitle()).isEqualTo("保存失败");
        assertThat(alert.getHeaderText()).contains("对象不存在").contains("no such table");

        VBox content = (VBox) alert.getDialogPane().getContent();
        Label kind = (Label) content.lookup("#error-details-kind");
        assertThat(kind)
                .as("分类徽标必须真的画出来 —— 只剩正文的话用户扫一眼分不清权限还是语法")
                .isNotNull();
        assertThat(kind.getText()).isEqualTo("对象不存在");
        assertThat(kind.getStyleClass()).contains("adb-badge", "adb-error-kind");

        TextArea text = (TextArea) content.lookup("#error-details-text");
        assertThat(text).isNotNull();
        assertThat(text.getText())
                .contains("建议：")
                .contains("SQL：\nUPDATE user_profiles")
                .doesNotContain("驱动原文：\nno such table")
                .as("原文已顶到标题栏，正文不重复");

        Button copy = (Button) content.lookup("#error-details-copy");
        assertThat(copy).isNotNull();
        WaitForAsyncUtils.asyncFx((Runnable) copy::fire).get();
        assertThat(WaitForAsyncUtils.asyncFx(
                (Callable<String>) () -> Clipboard.getSystemClipboard().getString()).get())
                .as("复制按钮必须把标题 + 分类 + 建议 + SQL 一起带走")
                .isEqualTo(details.copyText());
    }

    @Test
    void batchDialogIsAnErrorAndSaysHowManyFailed(FxRobot robot) throws Exception {
        BatchSummary summary = BatchSummary.of("删除表", "删除", "张表", 3, 2,
                List.of("logs：有外键依赖"));

        Alert alert = WaitForAsyncUtils.asyncFx((Callable<Alert>) () ->
                BatchSummaryDialog.create(summary)).get();
        assertThat(alert.getAlertType()).isEqualTo(Alert.AlertType.ERROR);
        assertThat(alert.getHeaderText())
                .isEqualTo(I18n.t("batch.headline.failed", "删除", 2, 3, "张表", 1));

        VBox content = (VBox) alert.getDialogPane().getContent();
        Label status = (Label) content.lookup("#batch-summary-status");
        assertThat(status).isNotNull();
        assertThat(status.getText()).isEqualTo(I18n.t("batch.badge.failed", 1));
        assertThat(status.getStyleClass()).contains("adb-error-kind");

        TextArea text = (TextArea) content.lookup("#batch-summary-text");
        assertThat(text).isNotNull();
        assertThat(text.getText())
                .contains(I18n.t("batch.body.details", "logs：有外键依赖"));

        Button copy = (Button) content.lookup("#batch-summary-copy");
        WaitForAsyncUtils.asyncFx((Runnable) copy::fire).get();
        assertThat(WaitForAsyncUtils.asyncFx(
                (Callable<String>) () -> Clipboard.getSystemClipboard().getString()).get())
                .isEqualTo(summary.copyText());
    }

    @Test
    void batchDialogIsInformationalWhenEverythingWorked(FxRobot robot) throws Exception {
        Alert alert = WaitForAsyncUtils.asyncFx((Callable<Alert>) () ->
                BatchSummaryDialog.create(
                        BatchSummary.of("删除表", "删除", "张表", 2, 2, List.of()))).get();

        assertThat(alert.getAlertType()).isEqualTo(Alert.AlertType.INFORMATION);
        assertThat(alert.getHeaderText())
                .isEqualTo(I18n.t("batch.headline.allDone", "删除", 2, 2, "张表"));
        VBox content = (VBox) alert.getDialogPane().getContent();
        Label status = (Label) content.lookup("#batch-summary-status");
        assertThat(status.getText()).isEqualTo(I18n.t("batch.badge.allDone"));
        assertThat(status.getStyleClass()).contains("adb-badge--muted");
    }

    @Test
    void showingWithoutAWindowIsANoOp() {
        assertThatCode(() -> {
            ErrorDetailsDialog.show(null,
                    ErrorDetails.of("保存失败", new SQLException("boom"), null));
            BatchSummaryDialog.show(null,
                    BatchSummary.of("删除表", "删除", "张表", 1, 0, List.of("logs：失败")));
        }).doesNotThrowAnyException();
    }
}
