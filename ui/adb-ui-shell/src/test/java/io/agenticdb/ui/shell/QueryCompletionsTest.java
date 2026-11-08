package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ⭐ R57-a：补全的**界面接缝**门禁 —— 真 SQLite 会话 + 真 QueryPage + 真弹层。
 *
 * <p>规则本体由 {@link SqlCompletionsTest} 钉死，这里只钉四件只有界面才会破的事：
 * 触发键真的接上、异步读回来的模型真的灌进弹层、执行过 DDL 后缓存真的重读、
 * 认不出的限定符真的**不**开窗。
 */
@ExtendWith(ApplicationExtension.class)
class QueryCompletionsTest {

    private JdbcSession session;
    private ExecutorService executor;
    private QueryPage page;
    private QueryCompletions completions;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("CREATE TABLE products (sku TEXT, qty INTEGER)");
        // ⭐ D-55 门禁的封顶那一侧要一条超过 240px 的大名单 —— 再铺 12 张表把
        //    「SELECT * FROM 」的全清单顶过封顶线（z 前缀不撞 'use' 小名单那格）。
        for (int i = 1; i <= 12; i++) {
            session.execute("CREATE TABLE zdata_" + String.format("%02d", i) + " (v TEXT)");
        }
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-completion");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "test", "", executor, new io.agenticdb.introspect.SchemaService(), null, new DataGridPage.Feedback() {
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
        page.setConfirmationHandler(danger -> true);
        completions = page.completionsForTest();
        stage.setScene(new Scene(page, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            // 弹层是节点：关掉必须落在 FX 线程上。
            interact(page::close);
        }
        executor.shutdownNow();
        session.close();
    }

    @Test
    void shortcutSpaceInFromContextShowsTablesOnly() {
        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            page.editor().area().fireEvent(shortcut(KeyCode.SPACE));
        });
        waitUntil("⌘Space 在 FROM 后列出表", () -> completions.isShowingForTest()
                && completions.candidateNamesForTest().contains("users"));

        assertThat(completions.candidateNamesForTest()).contains("products");
        // 表位不端关键字 —— FROM 后面能写的只有表与视图。
        assertThat(completions.candidateNamesForTest()).doesNotContain("SELECT");
    }

    @Test
    void emptyEditorStillCompletesKeywordsWhileObjectsLoad() {
        interact(() -> {
            page.setSql("");
            page.editor().moveTo(0);
            page.editor().area().fireEvent(shortcut(KeyCode.SPACE));
        });
        // 冷弹层：对象还没读回来时关键字先顶上（不是空窗骗用户按错了）。
        waitUntil("空编辑器 ⌘Space 先出关键字", () -> completions.isShowingForTest()
                && completions.candidateNamesForTest().contains("SELECT"));
        waitUntil("对象读回来后补进表", () -> completions.candidateNamesForTest().contains("users"));
    }

    @Test
    void dotAfterTableNameOpensColumns() {
        interact(() -> {
            page.setSql("SELECT * FROM users");
            page.editor().moveTo(19);
            page.editor().area().replaceText(19, 19, ".");
            page.editor().moveTo(20);
        });
        waitUntil("打点自动弹列", () -> completions.isShowingForTest()
                && completions.candidateNamesForTest().contains("id"));
        assertThat(completions.candidateNamesForTest()).contains("name");
    }

    @Test
    void enterAcceptsTheHighlightedColumn() {
        interact(() -> {
            page.setSql("SELECT * FROM users.n");
            page.editor().moveTo(21);
            completions.evaluateForTest(true);
        });
        waitUntil("前缀 n 只剩 name", () -> completions.candidateNamesForTest().contains("name"));

        interact(() -> page.editor().area().fireEvent(key(KeyCode.ENTER)));
        assertThat(page.sql()).isEqualTo("SELECT * FROM users.name");
        assertThat(completions.isShowingForTest()).isFalse();
    }

    @Test
    void escapeClosesThePopup() {
        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            page.editor().area().fireEvent(shortcut(KeyCode.SPACE));
        });
        waitUntil("弹层开起来", () -> completions.isShowingForTest());

        interact(() -> page.editor().area().fireEvent(key(KeyCode.ESCAPE)));
        assertThat(completions.isShowingForTest()).isFalse();
    }

    @Test
    void caretJumpOutClosesThePopup() {
        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            page.editor().area().fireEvent(shortcut(KeyCode.SPACE));
        });
        waitUntil("弹层开起来", () -> completions.isShowingForTest());

        // 用户点了别处 / 按了方向键：补全跟着光标走，不该留在原地。
        interact(() -> page.editor().moveTo(0));
        assertThat(completions.isShowingForTest()).isFalse();
    }

    @Test
    void newTableAfterDdlAppearsWithoutReopeningThePage() {
        // 先把冷缓存读到第一屏（此时没有 orders）。
        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            completions.evaluateForTest(true);
        });
        waitUntil("首轮补全含 users", () -> completions.candidateNamesForTest().contains("users"));
        assertThat(completions.candidateNamesForTest()).doesNotContain("orders");

        interact(() -> {
            page.setSql("CREATE TABLE orders (id INTEGER PRIMARY KEY);");
            page.editor().moveTo(0);
            page.runAll();
        });
        waitUntil("建表跑完", () -> page.resultTabCount() >= 1);

        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            completions.evaluateForTest(true);
        });
        // 缓存作废 + 重读是后台完成的，这里等它自己变绿。
        waitUntil("DDL 后补全含新建的 orders",
                () -> completions.candidateNamesForTest().contains("orders"));
    }

    @Test
    void unknownQualifierKeepsTheWindowShut() {
        // 先热身（对象读回来），否则「不弹」可能只是「还没读到」的假象。
        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            completions.evaluateForTest(true);
        });
        waitUntil("热身：弹层列出 users", () -> completions.candidateNamesForTest().contains("users"));

        interact(() -> {
            page.setSql("SELECT * FROM nope.");
            page.editor().moveTo(19);
            completions.evaluateForTest(false);
        });
        // 认不出的限定符：不猜、不端全部 —— 窗口收起。
        assertThat(completions.isShowingForTest()).isFalse();
        assertThat(completions.candidateNamesForTest()).isEmpty();
    }

    /**
     * ⭐ R57 画面债：那一帧的两条前提 —— 清单是真的、弹层登记了。
     *
     * <p>弹层在 PopupWindow 里，主场景快照拍不到它。少了登记这一步，脚本照样产出一张
     * 1280×800、「看着像补全那格」的查询页，返回码 0 —— 而这一格唯一的画面证据就是它。
     * 变异：删掉 {@code posePopupForShots()} 里那行登记 → 这条红（showing 仍然真，
     * 因为 evaluate(true) 自己会弹 —— 只有 overlay 这一侧露馅）。
     */
    @Test
    void theShotPoseOpensThePopupAndRegistersTheOverlay() throws Exception {
        // 只喂文本、**不动光标**：摆态走的必须是用户那条路。
        // 实测（变异 M6）：`setText` 之后光标本来就在句尾，所以 pose 里不需要再 moveTo ——
        // 当初补的那一行是**多余的**，量出来红不了任何门禁，已删；这条等的是「候选里真的出现了表」，
        // 光标一旦不在句尾（setText 行为改了）它自己会红。
        interact(() -> page.setSql("SELECT * FROM "));
        interact(() -> page.poseCompletionsWhenReady());
        waitUntil("摆态弹出的是表清单（不是停在句首的关键字列）", () -> completions.isShowingForTest()
                && completions.candidateNamesForTest().contains("users"));
        // ⭐ D-85 第一族：逻辑上的 showing 与 PopOver skin 的场景挂载**不是同一拍** ——
        //    满负载下隔一拍去问 overlayForShots() 会拿到 null（探针量过：隔离 50 轮
        //    0 次 null，这个窗口只在 FX 争用时张开）。等到真的登记得上再断言，
        //    别把时序竞争当红；真实快门那侧由 ScreenshotSession 的弃帧守门兜底。
        waitUntil("摆态把浮层登记给截图会话",
                () -> interactReturning(ScreenshotSession::overlayForShots) != null);
        ScreenshotSession.Overlay overlay = interactReturning(ScreenshotSession::overlayForShots);
        assertThat(overlay)
                .as("摆态必须把浮层登记给截图会话")
                .isNotNull();
        assertThat(overlay.node()).isNotNull();
        assertThat(overlay.node().getScene())
                .as("浮层那颗节点要在它自己的弹层场景里（快照要从那里取）")
                .isNotNull();
        assertThat(Double.isFinite(overlay.x()) && Double.isFinite(overlay.y()))
                .as("坐标要是快门时刻在主场景里算出来的有限值，不是猜的默认值")
                .isTrue();

        // ⭐ 有限值不等于**对**：补画面债的第一版实拍把浮层合成到了 (146,-19) ——
        //   尺寸、字节、去重全过，画面里弹层压在窗口左上角、编辑器一行字都看不见。
        //   根因不在合成，在弹层本身：{@code PopupControl.show(owner, x, y)} 要的是**屏幕**坐标，
        //   当时传的是编辑器局部坐标，于是弹层开在屏幕左上角（光标在屏幕 (328,102)，窗开在 (132,-2)）。
        //   行为门禁只读候选内容、量不到坐标，所以这个错位从 R57-a 交付起一直没被发现。
        //   钉法：拿**光标**当基准，坐标全部现场算，不写死像素。
        javafx.geometry.Point2D caretInScene = interactReturning(() -> {
            javafx.geometry.Bounds caret = page.editor().area().getCaretBoundsOnScreen(
                    page.editor().area().getCaretSelectionBind().getUnderlyingCaret()).orElseThrow();
            javafx.geometry.Point2D anchorLocal = page.editor().area()
                    .screenToLocal(caret.getMinX(), caret.getMaxY() + 2);
            return page.editor().area().localToScene(anchorLocal.getX(), anchorLocal.getY());
        });
        assertThat(overlay.x())
                .as("弹层要贴在光标下面：光标在主场景 x=" + caretInScene.getX()
                        + "，浮层左上角 x=" + overlay.x())
                .isBetween(caretInScene.getX() - 30, caretInScene.getX() + 45);
        assertThat(overlay.y())
                .as("弹层要贴在光标下面：光标在主场景 y=" + caretInScene.getY()
                        + "，浮层左上角 y=" + overlay.y())
                .isBetween(caretInScene.getY() - 30, caretInScene.getY() + 45);
    }

    /**
     * ⭐ D-55：弹层按候选条数贴高，不再固定 ~240px 让下面拖一片空白。
     *
     * <p>立案（p3-query-completions 帧）：三条候选的弹层高 ~240px，行只占 ~60px。
     * 根因是 ListView 默认 pref 高 ~400 只被 maxHeight 压到 240 —— 与候选数无关。
     * 修复后 fixedCellSize = 清单档 21px 令牌，pref 高 = 行数 × 21，超过 240 才封顶滚动。
     * 两面都钉：小名单贴高（min 的左侧）、大名单封顶（min 的右侧）。
     */
    @Test
    void theCompletionPopupHugsTheCandidateCountInsteadOfStretchingBlank() throws Exception {
        // 小名单：'use' 前缀只有个位数候选 —— 贴高这一侧。
        interact(() -> page.setSql("SELECT use"));
        interact(() -> page.poseCompletionsWhenReady());
        // 等到 users 出现（对象清单灌回）再读条数 —— 读早了候选只有关键字，条数会漂。
        waitUntil("小名单弹层出现且表清单到位", () -> completions.isShowingForTest()
                && completions.candidateNamesForTest().contains("users"));
        int small = interactReturning(completions::popupCountForTest);
        assertThat(small).as("'use' 前缀就是个位数候选，否则这个摆态测不了贴高这一侧")
                .isLessThanOrEqualTo(10);
        waitUntil("贴高后的列表高度落定", () ->
                interactReturning(completions::listHeightForTest) > 0);
        double smallHeight = interactReturning(completions::listHeightForTest);
        assertThat(smallHeight)
                .as("候选少的时候弹层贴着候选长（%d 行 × %.0fpx），不再固定 ~240px", small,
                        QueryCompletions.CELL_HEIGHT)
                .isCloseTo(QueryCompletions.CELL_HEIGHT * small, within(2.0));

        // 大名单：'SELECT * FROM ' 全清单 —— 封顶那一侧。
        interact(() -> page.setSql("SELECT * FROM "));
        waitUntil("全清单弹层出现", () -> completions.isShowingForTest()
                && completions.candidateNamesForTest().contains("users"));
        // 「正在读」行占位时弹层比列表高一行 —— 等清单读回、行撤掉再量。
        waitUntil("列清单读回、『正在读』行撤掉", () ->
                !completions.loadingRowVisibleForTest());
        int big = interactReturning(completions::popupCountForTest);
        double bigHeight = interactReturning(completions::listHeightForTest);
        assertThat(bigHeight)
                .as("候选多的时候按 %.0f 上限封顶滚动，不无限撑高（%d 行）",
                        QueryCompletions.MAX_LIST_HEIGHT, big)
                .isCloseTo(Math.min(QueryCompletions.MAX_LIST_HEIGHT,
                        QueryCompletions.CELL_HEIGHT * big), within(2.0));
        assertThat(big)
                .as("大名单摆态得真的超过封顶线，否则封顶那一侧没被量到")
                .isGreaterThan((int) (QueryCompletions.MAX_LIST_HEIGHT / QueryCompletions.CELL_HEIGHT));
    }

    /** 认不出的前缀 → 候选为空：这时摆态必须拒绝，否则帧拍的是一列空弹层。 */
    @Test
    void theShotPoseRefusesToFrameAnEmptyColumn() {
        interact(() -> {
            page.setSql("SELECT * FROM ");
            page.editor().moveTo(14);
            completions.evaluateForTest(true);
        });
        waitUntil("热身：清单读回来", () -> completions.isPoseableForShots());

        interact(() -> {
            page.setSql("SELECT * FROM nope.");
            page.editor().moveTo(19);
            completions.evaluateForTest(false);
        });
        assertThat(completions.isPoseableForShots())
                .as("宁可没图不可有假图 —— 没有真候选就不许摆这一帧")
                .isFalse();
    }

    // ─────────────────────────── 助手 ───────────────────────────

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, false, false, false);
    }

    private static KeyEvent shortcut(KeyCode code) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, !mac, false, mac);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 在 FX 线程上取一个值（读浮层坐标必须落在弹层真正显示的那个线程上）。 */
    private static <T> T interactReturning(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get(5, TimeUnit.SECONDS);
    }

    private void waitUntil(String what, Callable<Boolean> condition) {
        try {
            WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, condition);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("等待「" + what + "」超时；当前候选："
                    + completions.candidateNamesForTest(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }
}
