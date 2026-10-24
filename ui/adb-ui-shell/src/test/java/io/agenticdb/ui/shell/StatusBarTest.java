package io.agenticdb.ui.shell;

import io.agenticdb.ui.AppVersion;
import io.agenticdb.ui.Tokens;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
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
 *
 * <p>⭐ UX-5-D 再加两条 Navicat 有而我们没有的信息：左段「<b>当前语句</b>」
 * （显示压平的一行、点击复制的是<b>原文</b>），右段「<b>最后刷新</b>」的相对时间。
 * 这两段的断言读的是<b>计算值</b>（文字颜色、可见性、分隔符个数），
 * 不是 CSS 文件里写了什么 —— 状态栏那批单类分段规则早就被
 * {@code .adb-statusbar .label} 顶掉了，写进去不等于生效。
 */
@ExtendWith(ApplicationExtension.class)
class StatusBarTest {

    /** 挂在**带样式表的场景**里的状态栏：颜色与截断只有真渲染了才算数。 */
    private StatusBar styledBar;

    @Start
    void start(Stage stage) {
        styledBar = new StatusBar();
        Scene scene = new Scene(styledBar, 1280, 200);
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        styledBar.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

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

    // ───────────────── UX-5-D：当前语句 + 最后刷新 ─────────────────

    /**
     * ⭐ UX-5-D：左段的「当前语句」——显示是一行，内容仍是整条。
     *
     * <p>两件事缺一个这栏就是错的：
     * <ul>
     *   <li><b>显示</b>压成一行（状态栏只有 24px，换行会把整条撑破），
     *       截断只发生在<b>渲染</b>层：宽度封顶 + {@code ELLIPSIS}，文本本身没被动刀；</li>
     *   <li><b>原文</b>还在（{@code sqlText()} 与 tooltip 拿到的是带换行的完整语句），
     *       没有语句的页面<b>整段消失</b>，而不是留个占位。</li>
     * </ul>
     */
    @Test
    void theSqlSegmentShowsOneLineButKeepsTheWholeStatement() throws Exception {
        String raw = """
                SELECT id, name
                FROM users
                WHERE  active = 1""";
        onFx(() -> {
            styledBar.setSql(raw);
            styledBar.applyCss();
        });

        Label segment = sqlSegment();
        assertThat(styledBar.sqlText()).as("复制走的必须是原文").contains("\n");
        assertThat(segment.getText())
                .as("显示层压成一行：连续空白折叠成一个空格")
                .isEqualTo("SELECT id, name FROM users WHERE active = 1");
        assertThat(segment.isManaged()).as("有语句时这段要占位").isTrue();
        assertThat(segment.getMaxWidth())
                .as("封顶的是渲染宽度，不是内容长度")
                .isEqualTo(240.0);
        assertThat(segment.getTextOverrun()).isEqualTo(OverrunStyle.ELLIPSIS);
        assertThat(segment.getWidth())
                .as("布局跑过后长语句确实被封顶（>0 保证不是「没布局所以宽度为 0」的假绿）")
                .isGreaterThan(0.0)
                .isLessThanOrEqualTo(240.0);
        assertThat(segment.getTextFill())
                .as("规则必须真的落到计算值上 —— 状态栏那批单类分段规则是被基线顶掉的")
                .isEqualTo(Color.web(Tokens.TEXT_TERTIARY_LIGHT));
        assertThat(styledBar.sqlTooltipText())
                .as("tooltip 给完整原文，外加「点一下能干什么」")
                .contains("WHERE  active = 1")
                .contains("点击复制");
        assertThat(managedSeparatorsIn(styledBar, "adb-statusbar__left")).isEqualTo(4);

        // 表清单 / 对象首页这类页面没有语句 → 整段连同分隔符收掉（与 setDuration 同一套纪律）。
        onFx(() -> styledBar.setSql("   "));
        assertThat(segment.isManaged())
                .as("没有语句就整段隐藏，不留悬空占位")
                .isFalse();
        assertThat(managedSeparatorsIn(styledBar, "adb-statusbar__left"))
                .as("段隐藏后不许剩一个孤零零的「·」")
                .isEqualTo(3);
    }

    /**
     * ⭐ UX-5-D：点语句段 = 复制<b>原文</b>。
     *
     * <p>走真实点击（TestFX 打到场景图），不是直接调复制方法：
     * 光标暗示、事件消费与消息位的回执都在这条路径上。
     */
    @Test
    void clickingTheSqlSegmentCopiesTheRawStatement(FxRobot robot) throws Exception {
        String raw = "SELECT *\nFROM   users\nWHERE  id = 1";
        onFx(() -> {
            styledBar.setSql(raw);
            styledBar.applyCss();
        });

        Label segment = sqlSegment();
        assertThat(segment.getCursor())
                .as("HAND 光标是「这段点得动」的暗示之一")
                .isEqualTo(Cursor.HAND);

        robot.clickOn(segment);

        assertThat(WaitForAsyncUtils.asyncFx(() -> Clipboard.getSystemClipboard().getString()).get())
                .as("剪贴板里必须是完整原文 —— 复制半条 SQL 出去执行是正确性事故")
                .isEqualTo(raw);
        assertThat(styledBar.message())
                .as("剪贴板看不见，界面得回执一句")
                .isEqualTo("已复制当前语句");
    }

    /**
     * ⭐ UX-5-D：右段的「最后刷新」说的是<b>新旧</b>，不是耗时。
     *
     * <p>时钟由门禁注入：相对时间若拿真实时钟断言，用例跑多久都不固定，
     * 今天绿明天红。「还没有取过数」（未连接 / 表清单页）必须整段隐藏，
     * 不许出现「刷新于 —」这种看着像坏了的占位。
     */
    @Test
    void theRefreshedSegmentCountsRelativeTimeAgainstItsOwnClock() throws Exception {
        long now = 1_767_225_600_000L;
        onFx(() -> styledBar.setClockForTest(() -> now));

        onFx(() -> {
            styledBar.setRefreshedAt(now);
            styledBar.applyCss();
        });
        assertThat(visibleText(styledBar, "adb-status__refreshed"))
                .as("刚取完数说「刚刚」，不是时间戳")
                .isEqualTo("刷新于 刚刚");
        assertThat(fillOf(styledBar, "adb-status__refreshed"))
                .as("与耗时同一档参考信息色，规则要真的生效")
                .isEqualTo(Color.web(Tokens.TEXT_TERTIARY_LIGHT));
        assertThat(managedSeparatorsIn(styledBar, "adb-statusbar__right")).isEqualTo(5);

        onFx(() -> styledBar.setRefreshedAt(now - 5 * 60_000L));
        assertThat(visibleText(styledBar, "adb-status__refreshed")).isEqualTo("刷新于 5 分钟前");

        onFx(() -> styledBar.setRefreshedAt(now - 3 * 60 * 60_000L));
        assertThat(visibleText(styledBar, "adb-status__refreshed")).isEqualTo("刷新于 3 小时前");

        onFx(() -> styledBar.setRefreshedAt(now - 2 * 24 * 60 * 60_000L));
        assertThat(visibleText(styledBar, "adb-status__refreshed"))
                .as("超过一天要说「几天前」——数据放了一周还写「刚刚」是在骗人")
                .isEqualTo("刷新于 2 天前");

        onFx(() -> styledBar.setRefreshedAt(-1));
        assertThat(visibleText(styledBar, "adb-status__refreshed"))
                .as("还没取过数 → 整段消失")
                .isEmpty();
        assertThat(managedSeparatorsIn(styledBar, "adb-statusbar__right"))
                .as("段隐藏后不留孤零零的「·」")
                .isEqualTo(4);
    }

    /** 供门禁：场景图里那条语句的 Label 节点。 */
    private Label sqlSegment() {
        return (Label) styledBar.lookup(".adb-status__sql");
    }

    /** 供门禁：某段真正算进布局的文字；整段隐藏时返回空串。 */
    private static String visibleText(StatusBar bar, String styleClass) {
        for (javafx.scene.Node node : bar.lookupAll("." + styleClass)) {
            if (node instanceof Label label) {
                return label.isVisible() && label.isManaged() ? orEmpty(label.getText()) : "";
            }
        }
        return "";
    }

    /** 供门禁：某段真正生效的文字颜色（CSS 规则没层叠过来时读到的就是基线色）。 */
    private static javafx.scene.paint.Paint fillOf(StatusBar bar, String styleClass) {
        for (javafx.scene.Node node : bar.lookupAll("." + styleClass)) {
            if (node instanceof Label label) {
                return label.getTextFill();
            }
        }
        return null;
    }

    /** 某一段（左 / 中 / 右）里真正参与布局的分隔符数量 —— 段隐藏时必须跟着少一个。 */
    private static int managedSeparatorsIn(StatusBar bar, String groupClass) {
        int n = 0;
        for (javafx.scene.Node box : bar.lookupAll("." + groupClass)) {
            if (box instanceof HBox group) {
                for (javafx.scene.Node child : group.getChildren()) {
                    if (child.getStyleClass().contains("adb-status__separator")
                            && child.isManaged()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** 中间那格里真正参与布局的分隔符数量 —— 隐藏耗时段时必须跟着清零。 */
    private static int managedSeparatorsInCenter(StatusBar bar) {
        return managedSeparatorsIn(bar, "adb-statusbar__center");
    }

    /** 在 FX 线程上执行并等事件队列排空（CSS 与布局都要真的跑过）。 */
    private static void onFx(Runnable action) {
        javafx.application.Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 用户此刻真正看得见的耗时文本；隐藏时返回空串。 */
    private static String visibleDurationText(StatusBar bar) {
        return visibleText(bar, "adb-status__duration");
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
