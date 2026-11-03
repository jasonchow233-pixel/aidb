package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R57-g：查询页编辑器 / 结果带的分栏 —— 默认比例、最小高、拖拽记忆。
 *
 * <p>三个数都有出处，不是拍脑袋：
 * <ul>
 *   <li>默认 0.463 = 参照帧 {@code navicat17-query-editor} 逐像素量的
 *       （编辑器白带 377px / 分栏总高 814px）；原先写死的 0.42 是 P3 第一版顺手定的，没出处。</li>
 *   <li>编辑器最小高按实测行距 16px 算，留得住 8 行。</li>
 *   <li>结果带最小高按帧 {@code p3-query} 量的 136px 固定开销（结果页签 + Data/Info +
 *       分页条 + 列头）再加 3 行 × 23px。</li>
 * </ul>
 *
 * <p>记忆只认「鼠标真的拖着分隔条移动过」：布局自己夹出来的 position（窗口太矮、
 * 两条最小高顶住）不是用户的选择 —— 存了它，等于把第一次在小窗口里的意外永久变成默认。
 */
@ExtendWith(ApplicationExtension.class)
class QuerySplitMemoryTest {

    private JdbcSession session;
    private ExecutorService executor;
    private SettingsStore settings;
    private QueryPage page;
    private Stage stage;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Bob')");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-split");
            thread.setDaemon(true);
            return thread;
        });
        settings = new SettingsStore();
        page = newPage(settings);
        stage.setScene(new Scene(page, 1280, 800));
        stage.show();
        settle();
    }

    /**
     * ⭐ 每条测试前把窗口钉回 1280×800。
     *
     * <p>TestFX 一个测试类里用的是**同一颗 primary stage**：{@code @Start} 会重建场景，
     * 但窗口尺寸留的是上一条测试改过的值。「窗口压到 340」那条把 340 传给了下一条，
     * 分栏就被两条最小高夹到 0.407 —— 上一版读回门禁报的正是这个数，
     * 红的是布局尺寸，不是「记住的比例有没有读回来」。
     */
    @BeforeEach
    void pinTheWindowGeometry() {
        fx(() -> {
            stage.setWidth(1280);
            stage.setHeight(800);
        });
        settle();
    }

    @AfterEach
    void tearDown() throws Exception {        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        session.close();
    }

    private QueryPage newPage(SettingsStore store) {
        return new QueryPage(session, "test", "", executor, store,
                new DataGridPage.Feedback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void range(String text) {
                    }

                    @Override
                    public void duration(long millis) {
                    }
                });
    }

    // ─────────────────────────── 默认比例 ───────────────────────────

    @Test
    void defaultsToTheRatioMeasuredOnTheReferenceFrame() {
        assertThat(page.storedSplitPerMille())
                .as("新偏好节点上不该已经有记忆")
                .isEqualTo(-1);

        assertThat(fx(page::splitRatio))
                .as("默认比例 = 参照帧量出来的 46.3%，不是 P3 顺手写的 0.42")
                .isBetween(0.45, 0.48);

        SplitPane split = split();
        double share = fx(() -> page.editor().getHeight() / split.getHeight());
        assertThat(share)
                .as("比例要真的落到编辑器的高度上，不只是控件里记着的一个数")
                .isBetween(0.45, 0.48);
    }

    @Test
    void theDividerIsAGrabbableBandNotJustAPixelLine() {
        Node divider = divider();
        // CSS 把可见线画成 1px（扁平化），皮肤给的分隔条命中带实测 6px ——
        // 「看着是细线」和「摸得着拖得动」必须同时成立，否则「分隔可拖」是句空话。
        assertThat(fx(() -> divider.getBoundsInLocal().getHeight()))
                .as("分隔条的鼠标命中带高度")
                .isGreaterThanOrEqualTo(5);
        assertThat(divider.getCursor())
                .as("鼠标停在分隔条上要换成上下箭头")
                .isEqualTo(Cursor.V_RESIZE);
    }

    // ─────────────────────────── 拖拽记忆 ───────────────────────────

    @Test
    void draggingTheDividerRemembersTheRatio(FxRobot robot) {
        double before = fx(page::splitRatio);

        dragDividerDown(robot, 120);

        double after = fx(page::splitRatio);
        assertThat(after)
                .as("往下拖，编辑器那一格要变高")
                .isGreaterThan(before + 0.05);
        assertThat(page.storedSplitPerMille())
                .as("拖完要落盘，存的得是眼前这一格")
                .isEqualTo((int) Math.round(after * 1000));
    }

    @Test
    void aNewQueryPageOpensAtTheRememberedRatio() {
        // 直接写偏好，不走「先拖一次」。上一版让这条依赖拖拽落点，
        // 于是变异「读回时忽略存过的值」照样绿 —— 它当时测的其实是那一次拖拽，不是读回。
        // 键名在这里写死（不从 QueryPage 取常量）：改键名 = 老用户存过的比例作废，
        // 这种事要让测试先红一次，不能静默失效。
        fx(() -> settings.setInt("ui.query.split", 620));

        QueryPage second = newPage(settings);
        fx(() -> stage.getScene().setRoot(second));
        settle();
        try {
            assertThat(fx(second::splitRatio))
                    .as("新开的查询页要用记住的比例，不是回到默认")
                    .isCloseTo(0.62, org.assertj.core.data.Offset.offset(0.005));
        } finally {
            second.close();
            fx(() -> stage.getScene().setRoot(page));
            settle();
        }
    }

    @Test
    void aCorruptStoredRatioIsClampedNotObeyed() {
        // 偏好文件是用户能手改的（plist / xml）。存进 5.0 不该把分栏甩出可用区间。
        fx(() -> settings.setInt("ui.query.split", 5000));
        QueryPage second = newPage(settings);
        try {
            assertThat(fx(second::splitRatio))
                .as("越界的记忆要钳回可用区间，而不是照用")
                .isBetween(0.15, 0.85);
        } finally {
            second.close();
        }
    }

    @Test
    void clickingTheDividerWithoutMovingItDoesNotOverwriteTheMemory(FxRobot robot) {
        dragDividerDown(robot, 120);
        int stored = page.storedSplitPerMille();

        robot.clickOn(divider());
        settle();

        assertThat(page.storedSplitPerMille())
                .as("只点不拖不该改记忆")
                .isEqualTo(stored);
    }

    @Test
    void draggingInsideTheEditorDoesNotTouchTheMemory(FxRobot robot) {
        // 选中一段 SQL 也是一次「按下 + 移动 + 松手」。若记忆只要求「分栏里发生过拖拽」，
        // 用户圈一次文本就把当时的布局比例悄悄钉死了 —— 下一次开窗读到的不是他选的比例。
        SplitPane split = split();
        double y = fx(() -> page.editor().getHeight() * 0.6);
        assertThat(fx(page::splitRatio))
                .as("这条场景要先没被拖过")
                .isCloseTo(0.463, org.assertj.core.data.Offset.offset(0.02));

        robot.drag(page.editor()).dropTo(640, y + 60);
        settle();

        assertThat(page.storedSplitPerMille())
                .as("在编辑器里拖拽选字不该写分栏记忆")
                .isEqualTo(-1);
        assertThat(fx(() -> split.getDividerPositions()[0]))
                .as("也不该把分栏拖走")
                .isCloseTo(0.463, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void layoutClampingIsNotRememberedAsAUserChoice() {
        // 窗口矮到两条最小高顶不住默认比例时，SplitPane 会自己把 position 夹偏。
        fx(() -> stage.setHeight(340));
        settle();

        double ratio = fx(page::splitRatio);
        assertThat(Math.abs(ratio - 0.463))
                .as("这条场景要先真的把比例夹偏，否则下面那条断言是空的")
                .isGreaterThan(0.01);
        assertThat(page.storedSplitPerMille())
                .as("⭐ 布局夹出来的比例不能当成用户的选择存下去")
                .isEqualTo(-1);
    }

    @Test
    void draggingTheDividerUpCannotSwallowTheEditor(FxRobot robot) {
        // 落点用另一颗常驻控件，不用裸坐标：上一版按分栏内高度算 y，那一拖其实没到底，
        // 于是「去掉编辑器最小高」这种变异照样绿 —— 门禁得先证明拖到了头，才谈得上守最小高。
        double before = fx(page::splitRatio);
        robot.drag(divider()).dropTo((Node) page.lookup("#query-toolbar"));
        settle();

        assertThat(fx(page::splitRatio))
                .as("先证明这一拖真的把分隔条推上去了")
                .isLessThan(before - 0.1);
        assertThat(fx(() -> page.editor().getHeight()))
                .as("⭐ 拖到最顶，编辑器也不能被压没：实测行距 16px，最小高要留得住 8 行")
                .isGreaterThanOrEqualTo(140);
    }

    @Test
    void draggingTheDividerDownCannotSwallowTheResults(FxRobot robot) {
        double before = fx(page::splitRatio);
        robot.drag(divider()).dropTo((Node) page.lookup("#query-status"));
        settle();

        assertThat(fx(page::splitRatio))
                .as("先证明这一拖真的把分隔条推下去了")
                .isGreaterThan(before + 0.1);
        assertThat(fx(() -> ((Node) page.lookup("#query-results")).getBoundsInParent().getHeight()))
                .as("⭐ 拖到最底，结果带也不能只剩页签条（帧量固定开销 136px + 3 行 23px）")
                .isGreaterThanOrEqualTo(205);
    }

    @Test
    void withoutASettingsStoreThePageStillBuildsAndNothingPersists() {
        QueryPage storeless = newPage(null);
        try {
            assertThat(fx(storeless::splitRatio))
                    .as("没有偏好存储也要按默认比例开出来")
                    .isBetween(0.45, 0.48);
            assertThat(storeless.storedSplitPerMille())
                    .as("没有存储时读回口给 -1，不能抛")
                    .isEqualTo(-1);
        } finally {
            storeless.close();
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private void dragDividerDown(FxRobot robot, double pixels) {
        SplitPane split = split();
        Node divider = divider();
        double y = fx(() -> divider.getBoundsInParent().getMinY()
                + divider.getBoundsInLocal().getHeight() / 2 + pixels);
        assertThat(y).as("拖拽终点要在分栏里").isLessThan(fx(() -> split.getHeight()));
        robot.drag(divider).dropTo(640, y);
        settle();
    }

    private SplitPane split() {
        return fx(() -> (SplitPane) page.lookup(".adb-query__split"));
    }

    private Node divider() {
        List<Node> found = fx(() -> new ArrayList<>(page.lookupAll(".split-pane-divider")));
        assertThat(found)
                .as("查询页的分栏要有分隔条（皮肤没建出来 = 页面没进过场景）")
                .isNotEmpty();
        return found.getFirst();
    }

    /** 在 FX 线程上取一个值。 */
    private static <T> T fx(Supplier<T> action) {
        AtomicReference<T> box = new AtomicReference<>();
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> box.set(action.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return box.get();
    }

    private static void fx(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void settle() {
        for (int attempt = 0; attempt < 20; attempt++) {
            WaitForAsyncUtils.waitForFxEvents();
        }
    }
}
