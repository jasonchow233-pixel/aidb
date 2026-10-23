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

    // ───────────────── 第十八轮：没有计时就不许出现「— ms」 ─────────────────

    @Test
    void durationSegmentHidesEntirelyWhenThereIsNoTiming() throws Exception {
        StatusBar bar = new StatusBar();

        // 起步没有任何计时 → 用户什么都看不见，而不是看见一个悬空的「— ms」。
        assertThat(visibleDurationText(bar))
                .as("还没跑过语句时，耗时段不该占位（表清单页实测显示「未选择 — ms」）")
                .isEmpty();
        assertThat(managedSeparatorsInCenter(bar))
                .as("段隐藏后不许剩下孤零零的分隔符「·」")
                .isZero();

        WaitForAsyncUtils.asyncFx(() -> bar.setDuration(12)).get();
        assertThat(visibleDurationText(bar)).isEqualTo("12 ms");
        assertThat(managedSeparatorsInCenter(bar)).isEqualTo(1);

        // AppShell 在「未连接 / 非查询页」会调 setDuration(-1) —— 意图是清掉计时。
        WaitForAsyncUtils.asyncFx(() -> bar.setDuration(-1)).get();
        assertThat(visibleDurationText(bar))
                .as("清计时后整段必须消失，不能退回占位文字")
                .isEmpty();
        assertThat(managedSeparatorsInCenter(bar)).isZero();

        // 再次有计时时要能回来（隐藏不能变成永久消失）。
        WaitForAsyncUtils.asyncFx(() -> bar.setDuration(3)).get();
        assertThat(visibleDurationText(bar)).isEqualTo("3 ms");
        assertThat(managedSeparatorsInCenter(bar)).isEqualTo(1);
    }

    /** 中间那格里真正参与布局的分隔符数量 —— 隐藏耗时段时必须跟着清零。 */
    private static int managedSeparatorsInCenter(StatusBar bar) {
        int n = 0;
        for (javafx.scene.Node box : bar.lookupAll(".adb-statusbar__center")) {
            if (box instanceof javafx.scene.layout.HBox center) {
                for (javafx.scene.Node child : center.getChildren()) {
                    if (child.getStyleClass().contains("adb-status__separator")
                            && child.isManaged()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** 用户此刻真正看得见的耗时文本；隐藏时返回空串。 */
    private static String visibleDurationText(StatusBar bar) {
        for (javafx.scene.Node node : bar.lookupAll(".adb-status__duration")) {
            if (node instanceof Label label) {
                return label.isVisible() && label.isManaged() ? orEmpty(label.getText()) : "";
            }
        }
        return "";
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
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
