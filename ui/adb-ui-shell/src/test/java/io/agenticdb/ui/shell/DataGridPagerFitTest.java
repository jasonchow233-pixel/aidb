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
        stage.setScene(new Scene(page, 1280, 700));
        stage.show();
        // @Start 跑在 FX 线程上，不能 waitFor（会自己等自己）—— 等待放 @BeforeEach。
    }

    @BeforeEach
    void awaitLoaded() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null
                        && ((javafx.scene.control.TableView<?>) page.lookup("#data-table"))
                        .getItems().size() == 3);
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
        double full = WaitForAsyncUtils.asyncFx(() -> pager().prefWidth(-1)).get();
        assertThat(full)
                .as("分页器满配宽度得是个像样的数（否则下面的阈值断言没有意义）")
                .isGreaterThan(400);

        Node jumpRow = page.lookup("#data-jump-row");
        Node jump = page.lookup("#data-jump");
        Node perPage = page.lookup("#data-per-page");
        Node recordNav = page.lookup("#data-record-nav");
        Node range = page.lookup("#data-range");
        Node first = page.lookup("#data-first");
        Node pageLabel = page.lookup("#data-page");
        Node pageSize = page.lookup("#data-page-size");

        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(full + 40)).get();
        assertThat(jumpRow.isVisible())
                .as("够宽时行号跳转必须在").isTrue();
        assertThat(jumpRow.isManaged()).isTrue();

        // 只差 1px：先收最不值钱的一项（行号跳转），不许连锁。
        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(full - 1)).get();
        assertThat(jumpRow.isVisible())
                .as("塞不下 1px 时先收起行号跳转").isFalse();
        assertThat(jumpRow.isManaged())
                .as("藏起来还不够 —— 必须 unmanaged，否则仍占着宽度").isFalse();
        assertThat(jump.isVisible())
                .as("只差 1px 就把跳页也砍掉，属于过度降级").isTrue();
        assertThat(recordNav.isVisible())
                .as("记录导航更不该被顺手砍掉").isTrue();

        // 再窄一档：第二项（页码跳转）收起，行范围必须还在。
        double withoutJumpRow = WaitForAsyncUtils.asyncFx(() ->
                pager().prefWidth(-1)).get();
        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(withoutJumpRow - 1)).get();
        assertThat(jump.isVisible()).as("第二档收跳页").isFalse();
        assertThat(range.isVisible())
                .as("行范围是第三档，此刻不该跟着消失").isTrue();

        // 第三档：先撤掉与状态栏重复的行范围，也要保住「每页」标签与记录导航 ——
        // 一个孤零零的「200」读起来像页码，比少一行范围更糟。
        double withoutJump = WaitForAsyncUtils.asyncFx(() ->
                pager().prefWidth(-1)).get();
        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(withoutJump - 1)).get();
        assertThat(range.isVisible()).as("第三档收行范围（状态栏还有一份）").isFalse();
        assertThat(perPage.isVisible())
                .as("「每页」标签在行范围之后才收").isTrue();
        assertThat(recordNav.isVisible())
                .as("记录导航优先级最低，此刻仍在").isTrue();

        // 第四档：记录导航收起，但「每页」标签必须活到最后一档 ——
        // 实测 p2-details 里先收标签再收导航，曾出现孤零零的「200」。
        double withoutRange = WaitForAsyncUtils.asyncFx(() ->
                pager().prefWidth(-1)).get();
        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(withoutRange - 1)).get();
        assertThat(recordNav.isVisible()).as("第四档收记录导航").isFalse();
        assertThat(perPage.isVisible())
                .as("「每页」标签是最后一档，此刻不能被一起收走").isTrue();

        // 极窄：全收，但翻页与每页框永不清除。
        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(200)).get();
        assertThat(jump.isVisible()).as("极窄时跳页也收起").isFalse();
        assertThat(perPage.isVisible()).as("极窄时「每页」标签收起").isFalse();
        assertThat(recordNav.isVisible()).as("极窄时记录导航收起").isFalse();
        assertThat(range.isVisible()).as("极窄时行范围收起（状态栏还有一份）").isFalse();
        assertThat(first.isVisible()).as("页码导航永不清除 —— 否则翻不了页").isTrue();
        assertThat(pageLabel.isVisible())
                .as("「第 x / y 页」是唯一能确认当前位置的文字，不能藏").isTrue();
        assertThat(pageSize.isVisible())
                .as("每页框永不清除 —— 否则改不了页大小").isTrue();
        assertThat(pageSize.isManaged()).isTrue();

        // 宽度回来：全部还原。
        WaitForAsyncUtils.asyncFx(() -> page.fitPagerWidth(full + 40)).get();
        assertThat(jumpRow.isVisible()).as("变宽后必须还原，不能永久丢失").isTrue();
        assertThat(jump.isVisible()).isTrue();
        assertThat(perPage.isVisible()).isTrue();
        assertThat(recordNav.isVisible()).isTrue();
        assertThat(range.isVisible()).isTrue();
    }

    private HBox pager() {
        return (HBox) page.lookup("#data-pager");
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
