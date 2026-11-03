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
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-completion");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "test", "", executor, null, new DataGridPage.Feedback() {
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
