package io.agenticdb.ui.shell;

import io.agenticdb.ui.AppVersion;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态栏门禁（V-3 / M1 / M2）。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>版本号来自构建产物</b>，不再是写死的字符串；</li>
 *   <li><b>不含阶段代号</b>（以前的 {@code v0.1.0 · P3} —— 阶段代号必然再次过时）；</li>
 *   <li><b>耗时只出现一次</b>：{@code range} 只说行范围，耗时由 {@code duration} 独占。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class StatusBarTest {

    @Test
    void versionComesFromTheBuildArtifact() {
        StatusBar bar = new StatusBar();
        String version = versionText(bar);

        assertThat(version)
                .as("状态栏版本号必须来自 AppVersion（构建产物）")
                .isEqualTo(AppVersion.DISPLAY);
        assertThat(version)
                .as("版本号不该再写死：以前是 v0.1.0")
                .doesNotContain("v0.1.0");
    }

    @Test
    void versionCarriesNoStageCode() {
        String version = AppVersion.DISPLAY;

        // 阶段代号（P0…P7 那种）必然再次过时 —— 它写进去的那天就开始过期。
        assertThat(version)
                .as("版本串里不该有阶段代号（· P3 之类）")
                .doesNotContain("· P")
                .doesNotMatch(".*\\sP\\d+.*");
    }

    @Test
    void rangeTextDoesNotRepeatTheDuration() throws Exception {
        StatusBar bar = new StatusBar();
        WaitForAsyncUtils.asyncFx(() -> {
            bar.setRange("1-200 / 共 500");
            bar.setDuration(12);
        }).get();

        String range = segmentText(bar, "adb-status__range");
        String duration = segmentText(bar, "adb-status__duration");

        assertThat(range)
                .as("行范围只说范围，不带耗时（耗时另有字段，否则同一件事说两遍）")
                .doesNotContain("ms");
        assertThat(duration).contains("12");
    }

    @Test
    void selectionTextCanDescribeObjectsInsteadOfRows() throws Exception {
        StatusBar bar = new StatusBar();
        WaitForAsyncUtils.asyncFx(() -> bar.setSelectionText("已选 users · 200 行")).get();

        assertThat(bar.selectionText())
                .as("表清单选中的是「表」不是「行」（N5）")
                .isEqualTo("已选 users · 200 行");

        WaitForAsyncUtils.asyncFx(() -> bar.setSelectionText("已选 3 个项目")).get();
        assertThat(bar.selectionText()).isEqualTo("已选 3 个项目");

        WaitForAsyncUtils.asyncFx(() -> bar.setSelectionText("  ")).get();
        assertThat(bar.selectionText()).isEqualTo("未选择");
    }

    @Test
    void selectionAndTransactionAreDrivenByRealData() throws Exception {
        StatusBar bar = new StatusBar();
        WaitForAsyncUtils.asyncFx(() -> {
            bar.setSelection(0);
            bar.setTransaction(false);
        }).get();

        assertThat(bar.selectionText()).isEqualTo("未选择");
        assertThat(bar.transactionText()).isEqualTo("自动提交");

        WaitForAsyncUtils.asyncFx(() -> {
            bar.setSelection(7);
            bar.setTransaction(true);
        }).get();

        assertThat(bar.selectionText()).isEqualTo("已选 7 行");
        assertThat(bar.transactionText()).isEqualTo("事务中");
    }

    private static String versionText(StatusBar bar) {
        return segmentText(bar, "adb-status__version");
    }

    private static String segmentText(StatusBar bar, String styleClass) {
        for (javafx.scene.Node node : bar.lookupAll("." + styleClass)) {
            if (node instanceof Label label) {
                return label.getText();
            }
        }
        return "";
    }
}
