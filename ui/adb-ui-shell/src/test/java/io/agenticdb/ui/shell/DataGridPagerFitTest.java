package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ D-1 的门禁：分页器<b>窄宽降级</b>。
 *
 * <p>1280 宽 + 右侧详情面板打开时分页器只剩约 700px，实拍 p2-details 里
 * 右端的「跳至页」被裁成半截、「每页」整个消失 —— 看起来像界面坏了。
 * 正确做法是按优先级<b>整项</b>收起：
 * 行号跳转 → 页码跳转 → 行范围 → 记录导航 →「每页」标签
 * （「每页」留到最后：只剩一个孤零零的「200」会被读成页码）。
 *
 * <p>这里钉住三件事：
 * <ol>
 *   <li>只差 1px 时只收最不值钱的那一项，不许连锁砍掉一串；</li>
 *   <li>再窄也不清掉页码导航与每页框 —— 没了它们就翻不了页；</li>
 *   <li>宽度回来时全部还原 —— 不能一窄就永久丢控件。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class DataGridPagerFitTest {

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-pager-fit");
            thread.setDaemon(true);
            return thread;
        });
        page = new DataGridPage(session, new SchemaService(), "t", "test.db",
                noOpFeedback(), name -> { }, executor);
        // ⭐ 页面必须包一层：Scene 把「场景根」直接 resize 成窗口尺寸，
        //     maxSize 对根无效（实测 setMaxSize(950) 后根宽还是 1280）。
        //     包一层之后这条门禁才真能喂到 600px —— 见下面那条的注释。
        Scene scene = new Scene(new javafx.scene.layout.StackPane(page), 1280, 700);
        // ⭐ 必须挂样式表：App 里由 ThemeManager 挂。不挂的话 `.adb-grid__jump`
        //     的 11px 字号不生效，这条门禁量到的是默认 13px 的宽度 —— 数字看着像，
        //     其实量的不是同一个界面（AppBarTest 里记过同一课）。
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        page.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
        // @Start 跑在 FX 线程上，不能 waitFor（会自己等自己）—— 等待放 @BeforeEach。
    }

    @BeforeEach
    void awaitLoaded() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null
                        && ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                        .getItems().size() == 3);
        // ⭐ 排空 FX 队列再量。两处都要：
        //   ① 挂上场景后 `fitJumpFieldWidths` 在 runLater 里把两个跳转框从 64px
        //      抬到 75/69px，不等它跑完就测 `prefWidth(-1)`，量到的是「还没变宽的
        //      满配」，阶梯比出的档位整体偏一档；
        //   ② 上一条门禁改容器宽度会攒下一串 `fitPagerWidth(旧宽度)` 的 runLater，
        //      它们会在本条门禁喂完合成宽度<b>之后</b>才跑，把刚收起的项又涨回来
        //      （全量跑时实测：喂 1000、pref 960，行号跳转却是不可见的）。
        //   按「排空队列」等而不是等某个具体宽度：中文档提示更短、根本不会抬宽。
        WaitForAsyncUtils.waitForFxEvents(30);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            WaitForAsyncUtils.asyncFx(page::close).get();
        }
        executor.shutdownNow();
        session.close();
    }

    @Test
    void narrowPagerDropsWholeControlsInPriorityOrderAndRestoresThem() throws Exception {
        PagerState wide = feedAndRead(1e9);
        double full = wide.pref();
        assertThat(full)
                .as("分页器满配宽度得是个像样的数（否则下面的阈值断言没有意义）")
                .isGreaterThan(400);
        assertThat(wide.jumpRow()).as("够宽时行号跳转必须在").isTrue();
        assertThat(wide.jumpRowManaged()).as("够宽时行号跳转必须占位").isTrue();

        // 只差 1px：先收最不值钱的一项（行号跳转），不许连锁。
        PagerState oneShort = feedAndRead(full - 1);
        assertThat(oneShort.jumpRow())
                .as("塞不下 1px 时先收起行号跳转（此刻 pref=%.0f）", oneShort.pref()).isFalse();
        assertThat(oneShort.jumpRowManaged())
                .as("藏起来还不够 —— 必须 unmanaged，否则仍占着宽度").isFalse();
        assertThat(oneShort.jump())
                .as("只差 1px 就把跳页也砍掉，属于过度降级").isTrue();
        assertThat(oneShort.recordNav())
                .as("记录导航更不该被顺手砍掉").isTrue();

        // 再窄一档：第二项（页码跳转）收起，行范围必须还在。
        PagerState stage2 = feedAndRead(oneShort.pref() - 1);
        assertThat(stage2.jump()).as("第二档收跳页").isFalse();
        assertThat(stage2.range())
                .as("行范围是第三档，此刻不该跟着消失").isTrue();

        // 第三档：先撤掉与状态栏重复的行范围，也要保住「每页」标签与记录导航 ——
        // 一个孤零零的「200」读起来像页码，比少一行范围更糟。
        PagerState stage3 = feedAndRead(stage2.pref() - 1);
        assertThat(stage3.range()).as("第三档收行范围（状态栏还有一份）").isFalse();
        assertThat(stage3.perPage())
                .as("「每页」标签在行范围之后才收").isTrue();
        assertThat(stage3.recordNav())
                .as("记录导航优先级最低，此刻仍在").isTrue();

        // 第四档：记录导航收起，但「每页」标签必须活到最后一档 ——
        // 实测 p2-details 里先收标签再收导航，曾出现孤零零的「200」。
        PagerState stage4 = feedAndRead(stage3.pref() - 1);
        assertThat(stage4.recordNav()).as("第四档收记录导航").isFalse();
        assertThat(stage4.perPage())
                .as("「每页」标签是倒数第二档，此刻不能被一起收走").isTrue();

        // 第五档：「每页」标签收起，但每页框与页码导航永不清除。
        PagerState stage5 = feedAndRead(stage4.pref() - 1);
        assertThat(stage5.perPage()).as("第五档收「每页」标签").isFalse();

        // 极窄：全收，但翻页与每页框永不清除。
        PagerState tiny = feedAndRead(200);
        assertThat(tiny.jump()).as("极窄时跳页也收起").isFalse();
        assertThat(tiny.perPage()).as("极窄时「每页」标签收起").isFalse();
        assertThat(tiny.recordNav()).as("极窄时记录导航收起").isFalse();
        assertThat(tiny.range()).as("极窄时行范围收起（状态栏还有一份）").isFalse();
        assertThat(tiny.pageLabelOnly()).as("「页面」标签是最后一档，极窄时也该收起").isFalse();
        assertThat(tiny.first()).as("页码导航永不清除 —— 否则翻不了页").isTrue();
        assertThat(tiny.pageText())
                .as("「第 x / y 页」是唯一能确认当前位置的文字，不能藏").isTrue();
        assertThat(tiny.pageSize()).as("每页框永不清除 —— 否则改不了页大小").isTrue();
        assertThat(tiny.pageSizeManaged()).isTrue();

        // 宽度回来：全部还原。
        PagerState restored = feedAndRead(full + 40);
        assertThat(restored.jumpRow()).as("变宽后必须还原，不能永久丢失").isTrue();
        assertThat(restored.jump()).isTrue();
        assertThat(restored.perPage()).isTrue();
        assertThat(restored.recordNav()).isTrue();
        assertThat(restored.range()).isTrue();
    }

    /**
     * 阶梯某一档的<b>一次原子读数</b>：可见性 + 当前 pref。
     *
     * <p>为什么要打包成一条 FX 任务：宽度监听器把 {@code fitPagerWidth(旧宽度)}
     * 排在 {@code runLater} 里，而「喂宽度 → 读可见性」如果分两次跨线程调用，
     * 中间就允许插入一条旧宽度任务 —— 全量跑时实测到过自相矛盾的结果：
     * 读出来 {@code pref=960}（像是全展开）而 {@code jumpRow.isVisible()=false}
     * （像是已经收起），单跑永远复现不了。同一条 FX 任务里喂完立刻读，
     * 中间不可能再插别的事件。
     */
    private record PagerState(boolean jumpRow, boolean jumpRowManaged, boolean jump, boolean range,
                              boolean recordNav, boolean perPage, boolean pageLabelOnly,
                              boolean first, boolean pageText, boolean pageSize,
                              boolean pageSizeManaged, double pref) {
    }

    private PagerState feedAndRead(double width) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> {
            page.fitPagerWidth(width);
            return new PagerState(
                    shown("#data-jump-row"), managed("#data-jump-row"),
                    shown("#data-jump"), shown("#data-range"), shown("#data-record-nav"),
                    shown("#data-per-page"), shown("#data-page-label"),
                    shown("#data-first"), shown("#data-page"),
                    shown("#data-page-size"), managed("#data-page-size"),
                    pager().prefWidth(-1));
        }).get();
    }

    private boolean shown(String id) {
        Node node = page.lookup(id);
        return node != null && node.isVisible();
    }

    private boolean managed(String id) {
        Node node = page.lookup(id);
        return node != null && node.isManaged();
    }

    /**
     * ⭐ R34 N1-b：这一排的<b>文字不许被压扁</b> —— 走真实布局量，不喂合成宽度。
     *
     * <p>上面那条门禁喂的是合成宽度，只回答「该收哪几项」；它全绿的同时，
     * p2-find 实拍里标签仍被压成「Rec…」「P…」—— 因为 Label 默认的计算 min
     * 只有几 px，HBox 宽度不够时<b>优先压它</b>，而阶梯是按整项撤的、撤完之前
     * 这一段还挂着，就先被压扁了。修法是把每个文字标签的 min 钉成自己的偏好宽
     * （压不了，只能整项撤），<b>判据仍用 {@code prefWidth}</b>。
     *
     * <p>⚠️ 中途把判据换成过 {@code minWidth}，两次独立实测都判它是错的：
     * ① 布局恒保证「盒子实际宽 ≥ 自己的 min」，所以 {@code min <= width} 恒真，
     * 阶梯一项都不撤（900 档 pref=953 &gt; w=900，六个可收项全挂着）；
     * ② 钉死的标签把 min 顶到 891px 并往上传，外壳里侧栏 + 抽屉一占位就把整页
     * 顶出窗口，工具条整排溢出（三条门禁红）。这条门禁与下面那条阶梯门禁一起
     * 把两个方向都按住：<b>文字不许压扁，整页也不许被撑出去</b>。
     *
     * <p>两次变异验证（各破坏一次，还原即绿）：
     * 撤掉三个标签的 {@code USE_PREF_SIZE} → <b>本条</b>红，报
     * 「Record 实得 17px &lt; 需要 41px」「3 row(s) 实得 23px &lt; 需要 46px」（760 档）；
     * 判据退回 {@code minWidth} → <b>上面那条阶梯门禁</b>加外壳里三条溢出门禁
     * （{@code ToolbarDiscoverabilityTest} 两条 + {@code DataGridFormViewTest} 一条）共 4 红。
     * 顺带说明为什么钉死而不是只靠阶梯：阶梯在 {@code runLater} 里跑，
     * 宽度突变后总有几个脉冲是「实际宽 &lt; 需要宽」的中间态，
     * 钉死后中间态顶多溢出，<b>不会把字切成省略号</b>。
     */
    @Test
    void noPagerTextIsEverSqueezedIntoEllipsis() throws Exception {
        try {
          for (double width : new double[]{1280, 1100, 900, 760, 600}) {
            final double w = width;
            // ⚠️ 不能用 stage.setWidth 收窄：窗口有系统下限，实测请求 900 会被顶回 960，
            //    而这条门禁要量的正是 900 以下那一档。改喂容器尺寸，走的仍是真实布局。
            WaitForAsyncUtils.asyncFx(() -> {
                page.setMaxSize(w, Double.MAX_VALUE);
                page.getParent().requestLayout();
            }).get();
            WaitForAsyncUtils.waitForFxEvents(3);
            List<String> squeezed = WaitForAsyncUtils.asyncFx(() -> {
                List<String> bad = new ArrayList<>();
                for (Node node : pager().lookupAll(".label")) {
                    if (!(node instanceof javafx.scene.control.Labeled labeled)
                            || !labeled.isVisible() || !labeled.isManaged()) {
                        continue;
                    }
                    double need = labeled.prefWidth(-1);
                    if (labeled.getWidth() + 0.5 < need) {
                        bad.add("「" + labeled.getText() + "」实得 "
                                + String.format("%.0f", labeled.getWidth())
                                + "px < 需要 " + String.format("%.0f", need) + "px");
                    }
                }
                return bad;
            }).get();
            assertThat(squeezed)
                    .as("分页器在 %.0fpx 档把文字压成了省略号（宁可整项收起）：\n  %s",
                            width, String.join("\n  ", squeezed))
                    .isEmpty();
          }
        } finally {
            // ⚠️ 必须还原：maxSize 是页面级状态，留着 600 出去，
            //    下一条门禁量到的就是「已经降级过」的分页器，前提直接失效。
            WaitForAsyncUtils.asyncFx(() -> {
                page.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                page.getParent().requestLayout();
            }).get();
            // 这一串宽度变化会攒下同样多的 `fitPagerWidth(旧宽度)` runLater；
            // 不排空就带进下一条门禁（@BeforeEach 的排空发生在下一条开始<b>之前</b>，
            // 但那时这些任务还没被宽度事件触发出来，所以两头都要排）。
            WaitForAsyncUtils.waitForFxEvents(30);
        }
    }


    private HBox pager() {
        return (HBox) page.lookup("#data-pager");
    }

    /**
     * ⭐ R34 N1-b：跳转框的<b>提示文字</b>也不许被裁（p2-find 实拍「Go to pa」）。
     *
     * <p>上面那条只查 {@code .label}，而 TextField 的 promptText 不是 Label ——
     * 它被框宽切掉时同样读成半截词。64px 是照中文「跳至页」定的，英文要 75px。
     */
    @Test
    void jumpFieldPromptsFitTheirOwnText() throws Exception {
        WaitForAsyncUtils.waitForFxEvents(5);
        List<String> clipped = WaitForAsyncUtils.asyncFx(() -> {
            List<String> bad = new ArrayList<>();
            for (String id : List.of("#data-jump", "#data-jump-row")) {
                if (!(page.lookup(id) instanceof javafx.scene.control.TextField field)
                        || !field.isVisible() || !field.isManaged()) {
                    continue;
                }
                javafx.scene.text.Text probe =
                        new javafx.scene.text.Text(field.getPromptText());
                probe.setFont(field.getFont());
                double need = probe.getLayoutBounds().getWidth() + 16;
                if (field.getWidth() + 0.5 < need) {
                    bad.add(id + "「" + field.getPromptText() + "」框宽 "
                            + String.format("%.0f", field.getWidth())
                            + "px < 提示需要 " + String.format("%.0f", need) + "px");
                }
            }
            return bad;
        }).get();
        assertThat(clipped)
                .as("跳转框把提示文字裁成了半截词：\n  %s", String.join("\n  ", clipped))
                .isEmpty();
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }
}
