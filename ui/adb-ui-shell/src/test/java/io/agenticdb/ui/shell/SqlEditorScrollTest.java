package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R72 D-97：共用件 {@link SqlEditor} 的溢出可达性门禁。
 *
 * <p>D-95 只在视图设计器那两格开了折行，其余四处消费者（查询页 / 结构页 DDL /
 * 新建表预览 / 新建索引预览）仍然是「一行超出盒子宽度就看不见、界面上也没有线索」。
 * 这一轮补的是共用件本身：<b>一条看得见的横条</b>，不是把所有人的折行翻开。
 *
 * <p>所以这里钉的全是<b>几何结果</b>，没有一条去读 {@code setWrapText} 之类的开关自证：
 * <ol>
 *   <li>超长单行 → 盒子底边真有一枚<b>可见</b>的横条，且它表示的范围大于可视宽度；</li>
 *   <li>把这枚条拖到底 → 行尾那几个字符真的搬进可视区（用 {@code hit()} 从像素反查字符下标）；</li>
 *   <li>把光标移到行尾（{@link SqlEditor#moveTo} —— 查询页跳错误行列走的就是这条路）→ 条自己走过去；</li>
 *   <li>短文本 / 折行盒 → <b>不许</b>长出横条（钉住 D-95 与 D-97 不打架、条不是装饰）；</li>
 *   <li>四十行 → 竖条可见且拖到底能渲染末行；</li>
 *   <li>重挂父件后 {@code #query-editor} / {@code .adb-sql-editor} 仍命中同一个 CodeArea，
 *       且编辑器的 pref 宽度不随内容宽度爆炸（弹窗宽度靠这个）；</li>
 *   <li>{@code p3-query-overflow} 那一帧的姿态 SQL 在<b>帧面实得的盒子宽度</b>下仍然溢出
 *       （帧证的前提：句子被改短或改成多行，那一帧就退化成假帧 —— D-98 同型病）。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class SqlEditorScrollTest {

    /** 约 320 字符的单行，690 宽的盒子里放不下的量与 MySQL 实拍那条一致。 */
    private static final String LONG_LINE = "SELECT `" + "abcdefghij".repeat(30) + "` FROM `users`";

    private static final String SHORT_LINE = "SELECT 1";

    private static final String MANY_LINES =
            "SELECT 1;\n".repeat(40) + "SELECT 2 AS tail_line_marker";

    /** 与查询页 SplitPane 给编辑器的量级相当的盒子（宽 690、高 298）。 */
    private static final double BOX_W = 690;
    private static final double BOX_H = 298;

    /** {@code p3-query} 帧面上编辑器实得的宽度（1280 窗口 − 侧栏 − 分隔条）。 */
    private static final double SHOT_EDITOR_W = 1010;

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    // ─────────────────────── 横条：看得见 ───────────────────────

    @Test
    void aSingleLineWiderThanTheBoxGrowsAVisibleHorizontalBar() {
        SqlEditor editor = shown(LONG_LINE, false);
        CodeArea area = onFx(editor::area);
        ScrollBar hbar = bar(editor, Orientation.HORIZONTAL);
        assertThat(hbar)
                .as("一行超出盒子宽度时，编辑器底边必须有一枚看得见的横条 —— "
                        + "D-97 的原始现象是「右边凭空没了，界面上零线索」")
                .isNotNull();
        assertThat(hbar.getMax())
                .as("横条的范围要真的表示整行宽度，不是占位")
                .isGreaterThan(hbar.getVisibleAmount());
        assertThat(hbar.getHeight())
                .as("横条得有自己的厚度（没被挤出盒子就等于没画）")
                .isGreaterThan(0);
        assertThat(area.getWidth())
                .as("有了横条也不许把内容挤出盒子：编辑区宽度不得超过宿主")
                .isLessThanOrEqualTo(BOX_W);
    }

    @Test
    void draggingTheBarToTheEndBringsTheTailOfTheLineIntoView() {
        SqlEditor editor = shown(LONG_LINE, false);
        CodeArea area = onFx(editor::area);
        int length = onFx(area::getLength);
        double width = onFx(area::getWidth);

        // 拖之前：可视区右缘还够不到行尾，左缘还是行首。
        int rightBefore = onFx(() -> area.hit(width - 8, 6).getInsertionIndex());
        int leftBefore = onFx(() -> area.hit(6, 6).getInsertionIndex());
        assertThat(leftBefore)
                .as("横条还没动时，可视区左缘命中的应该是行首附近")
                .isLessThan(8);
        assertThat(rightBefore)
                .as("横条还没动时，行尾那几个字符应该还在可视区外面")
                .isLessThan(length - 40);

        ScrollBar hbar = bar(editor, Orientation.HORIZONTAL);
        onFx(() -> {
            hbar.setValue(hbar.getMax());
            return null;
        });
        settle();

        int rightAfter = onFx(() -> area.hit(width - 8, 6).getInsertionIndex());
        int leftAfter = onFx(() -> area.hit(6, 6).getInsertionIndex());
        assertThat(rightAfter)
                .as("把横条拖到底后，可视区右缘应该已经贴到行尾（实测 %d / 全长 %d）",
                        rightAfter, length)
                .isGreaterThan(length - 20);
        assertThat(leftAfter)
                .as("整行内容要真的横移过来越过左缘，左缘不能再停在行首（实测 %d）", leftAfter)
                .isGreaterThan(length / 2);
    }

    @Test
    void movingTheCaretToTheEndOfALongLineWalksTheBarWithIt() {
        SqlEditor editor = shown(LONG_LINE, false);
        int length = onFx(editor::text).length();
        onFx(() -> {
            editor.moveTo(length);
            return null;
        });
        settle();

        ScrollBar hbar = bar(editor, Orientation.HORIZONTAL);
        assertThat(hbar.getValue())
                .as("查询页「跳到出错行列」走的正是 SqlEditor.moveTo：光标走到行尾时，"
                        + "横条要跟着走过去，否则跳了等于没跳")
                .isGreaterThan(0);
    }

    // ─────────────────────── 横条：不该出现时别出现 ───────────────────────

    /**
     * ⭐ D-97 帧证的前提门。{@code p3-query-overflow} 那一帧声称画的是「一条看得见的横条」，
     * 而帧面本身不会自证：姿态 SQL 一旦被改短（或改成多行），那一帧照常拍出默认查询页、
     * 返回码照样是 0 —— 与 D-98 同一类安静失败。这条把姿态那句按帧面实得的盒子宽度量一遍，
     * 量不到横条就是帧先坏。
     */
    @Test
    void theOverflowPoseFrameSqlOverflowsTheEditorBoxTheFrameUses() {
        SqlEditor editor = shown(AppShell.POSE_OVERFLOW_SQL, false, SHOT_EDITOR_W);
        CodeArea area = onFx(editor::area);
        assertThat(onFx(() -> area.getParagraphs().size()))
                .as("姿态那句必须是**一整行**（帧要证的就是单行放不下）；换行 = 没有溢出可拍")
                .isEqualTo(1);
        assertThat(visibleBar(editor, Orientation.HORIZONTAL))
                .as("姿态那句在帧面那个宽度的盒子里必须长出横条 —— 长不出来这一帧就是假帧")
                .isNotNull();
    }

    @Test
    void shortTextShowsNoHorizontalBarAtAll() {
        SqlEditor editor = shown(SHORT_LINE, false);
        assertThat(visibleBar(editor, Orientation.HORIZONTAL))
                .as("内容没溢出就不许长出横条：常显的条会把「这里有多宽」的读数污染掉")
                .isNull();
    }

    @Test
    void aWrappedBoxFoldsInsteadOfGrowingABar() {
        SqlEditor editor = shown(LONG_LINE, true);
        assertThat(visibleBar(editor, Orientation.HORIZONTAL))
                .as("D-95 在设计器那两格开的是折行：折行盒不该再有横条，"
                        + "否则同一轮里两个方案在同一个共用件上打架")
                .isNull();
        assertThat(onFx(editor::area).getParagraphLinesCount(0))
                .as("折行盒仍然按折行读（>1 视觉行）")
                .isGreaterThan(1);
    }

    // ─────────────────────── 竖条：别把原来的能力弄丢 ───────────────────────

    @Test
    void aTallScriptGrowsAVerticalBarThatReachesTheLastLine() {
        SqlEditor editor = shown(MANY_LINES, false);
        CodeArea area = onFx(editor::area);
        ScrollBar vbar = bar(editor, Orientation.VERTICAL);

        int last = onFx(() -> area.getParagraphs().size() - 1);
        onFx(() -> {
            vbar.setValue(vbar.getMax());
            return null;
        });
        settle();

        assertThat(onFx(() -> area.allParToVisibleParIndex(last).isPresent()))
                .as("把竖条拖到底必须真把末行搬进渲染窗口 —— 横条这轮新加，"
                        + "竖式滚动原本靠 CodeArea 内部自己走，重挂外层件时最容易把它弄丢")
                .isTrue();
    }

    @Test
    void theEditorStaysFindableAndDoesNotGrowWithItsContent() {
        SqlEditor editor = shown(LONG_LINE, false);
        CodeArea area = onFx(editor::area);

        Node byId = onFx(() -> editor.lookup("#query-editor"));
        Node byClass = onFx(() -> editor.lookup(".adb-sql-editor"));
        assertThat(byId)
                .as("重挂滚动件之后 #query-editor 必须仍指向同一个 CodeArea —— "
                        + "外壳层的多条门禁靠这个 id 找编辑器")
                .isSameAs(area);
        assertThat(byClass)
                .as(".adb-sql-editor 类也要还在 CodeArea 本尊上，语法配色规则挂在它下面")
                .isSameAs(area);
        assertThat(onFx(() -> editor.prefWidth(-1)))
                .as("一行 2300 像素宽，编辑器的 pref 宽度不许跟着涨 —— 弹窗宽度按它算，涨了就把弹窗撑破")
                .isLessThan(300.0);
    }

    // ─────────────────────────── 装配通道 ───────────────────────────

    /**
     * 把编辑器挂到真舞台上（横条的几何只有走完布局脉冲才落定），返回编辑器本体。
     *
     * <p>⚠️ 宿主盒子**不能当场景根**：根由 {@code Scene} 用 {@code resizeRelocate} 直接
     * 摆成窗口大小，那条路不夹 min/max（实测 {@code Region} 不覆写它），而这套 harness 的
     * 舞台尺寸会漏前一个测试类留下的几何（全量跑到过 1278 宽）。所以盒子钉成 690x298
     * 之后**再套一层当根** —— 父件的布局走 {@code resize(x, y, true)}，验算过的那条会夹，
     * 漏进来的宽度到不了这一层。钉死宽度是前提：一被拉长就没有「溢出」可谈，
     * 门禁会绿得毫无意义，所以钉不住时必须当场红。
     */
    private SqlEditor shown(String text, boolean wrap) {
        return shown(text, wrap, BOX_W);
    }

    private SqlEditor shown(String text, boolean wrap, double boxWidth) {
        SqlEditor editor = onFx(() -> {
            SqlEditor created = new SqlEditor();
            created.area().setWrapText(wrap);
            created.setText(text);
            StackPane host = new StackPane(created);
            host.setMinSize(boxWidth, BOX_H);
            host.setPrefSize(boxWidth, BOX_H);
            host.setMaxSize(boxWidth, BOX_H);
            StackPane root = new StackPane(host);
            Scene scene = new Scene(root, boxWidth, BOX_H);
            scene.getStylesheets().add(style("/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            stage.setScene(scene);
            return created;
        });
        settle();
        assertThat(onFx(() -> editor.getParent().getBoundsInLocal().getWidth()))
                .as("前提：宿主盒子必须正好 %d 宽 —— 超宽的行放不下才谈得上横条，"
                        + "舞台几何漏进来把盒子拉长时这条门禁就是空转", (int) boxWidth)
                .isEqualTo(boxWidth);
        return editor;
    }

    private static void settle() {
        WaitForAsyncUtils.waitForFxEvents(8);
    }

    private static String style(String resource) {
        java.net.URL url = SqlEditorScrollTest.class.getResource(resource);
        assertThat(url).as("样式表缺席时量到的字号与厚度都是假的：" + resource).isNotNull();
        return url.toExternalForm();
    }

    /** 可见的那一枚条（横或竖）；没有就返回 {@code null}。 */
    private static ScrollBar visibleBar(SqlEditor editor, Orientation orientation) {
        return onFx(() -> {
            for (Node node : editor.lookupAll(".scroll-bar")) {
                if (node instanceof ScrollBar bar
                        && bar.getOrientation() == orientation
                        && bar.isVisible()
                        && size(bar, orientation) > 0) {
                    return bar;
                }
            }
            return null;
        });
    }

    /** 可见性之外还要量到真实厚度：没走完布局的条是「可见但还没被摆好」的中间态。 */
    private static double size(ScrollBar bar, Orientation orientation) {
        return orientation == Orientation.HORIZONTAL ? bar.getHeight() : bar.getWidth();
    }

    private static ScrollBar bar(SqlEditor editor, Orientation orientation) {
        ScrollBar found = visibleBar(editor, orientation);
        assertThat(found)
                .as("这条门禁要求这里存在一枚可见的 %s 条", orientation)
                .isNotNull();
        return found;
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> callable) {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                err.set(t);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (err.get() != null) {
            throw new AssertionError(err.get());
        }
        return ref.get();
    }
}
