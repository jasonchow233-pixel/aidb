package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ G3 的门禁：网格查找 / 替换。
 *
 * <p>这一轮要证的是<b>语义</b>，不是「按钮在不在」：
 * <ol>
 *   <li>查找按行主序逐格推进、到底回绕，状态栏报「第 x / y 个」；</li>
 *   <li>「区分大小写」开关真的改变命中数（默认不区分）；</li>
 *   <li>替换走编辑缓冲 —— 脏标记、撤销、保存路径全部自动成立，不许直接落库；</li>
 *   <li>全部替换是<b>一个撤销步</b>；主键列与非文本列必须跳过并计数；</li>
 *   <li>只读表（视图）的替换两键点不动，查找不受限。</li>
 * </ol>
 *
 * <p>数据是固定四行的小表（非 200 行演示库）：命中数可以逐个数出来，
 * 断言写的是「3 / 2 / 1」这种硬数字，不靠 contains 糊过去。
 */
@ExtendWith(ApplicationExtension.class)
class DataGridFindReplaceTest {

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;
    /** 状态栏最后一次播报 —— 断言的主要落点（超时时也是唯一线索）。 */
    private final AtomicReference<String> lastStatus = new AtomicReference<>("<无>");

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT,"
                + " note TEXT, amount INTEGER)");
        session.execute("INSERT INTO t VALUES"
                + " (1, 'Alpha', 'find me', 10),"
                + " (2, 'Beta', 'find me too', 20),"
                + " (3, 'Gamma', 'nothing', 30),"
                + " (4, 'find me again', 'Alpha again', 40)");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-find");
            thread.setDaemon(true);
            return thread;
        });
        page = new DataGridPage(session, new SchemaService(), "t", "test.db",
                recordingFeedback(), name -> { }, executor);
        stage.setScene(new Scene(page, 1100, 700));
        stage.show();
        // ⚠️ 这里不能等加载：@Start 跑在 FX 线程上，加载完成的回调也要回 FX 线程 ——
        //    在这儿 waitFor 等于自己等自己，必然超时（第一版就是这么红的）。
        //    等待挪到 @BeforeEach（测试线程），见 awaitLoaded()。
    }

    @BeforeEach
    void awaitLoaded() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                table().getItems().size() == 4 && !replaceOne().isDisabled());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            interact(page::close);
        }
        executor.shutdownNow();
        session.close();
    }

    // ─────────────────────── 入口与键盘 ───────────────────────

    @Test
    void commandFOpensTheFindBarAndEscapeClosesIt() throws Exception {
        interact(() -> page.fireEvent(shortcut(KeyCode.F)));
        assertThat(page.findBarVisible())
                .as("⌘F 必须展开查找条（Navicat 数据编辑器口径）")
                .isTrue();
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS,
                () -> page.lookup("#data-find").isFocused());
        assertThat(page.lookup("#data-find").isFocused())
                .as("展开后焦点必须落在查找框，直接能打字")
                .isTrue();

        TextField find = (TextField) page.lookup("#data-find");
        interact(() -> find.fireEvent(key(KeyCode.ESCAPE, false, false, false)));
        assertThat(page.findBarVisible())
                .as("Esc 在查找框里必须关掉查找条（焦点在输入框，不拦就早退了）")
                .isFalse();
    }

    @Test
    void poseForShotsOpensTheFindBarWithTheNeedle() {
        // 截图摆拍钩子的门禁：开关（ADB_FIND）被读由 ScreenshotScriptAuditTest 守，
        // 「摆了真的生效」归这里 —— 否则摆态静默降级，p2-find 会拍成一张普通网格。
        assertThat(page.poseFindBar("find me")).isTrue();
        assertThat(page.findBarVisible()).isTrue();
        assertThat(findField().getText()).isEqualTo("find me");
    }

    @Test
    void findNextWalksMatchesInOrderAndWrapsWithACount() {
        findField().setText("find me");

        interact(() -> fireFindNext());
        assertThat(lastStatus.get()).contains("第 1 / 3 个匹配");
        assertThat(focusedCell()).isEqualTo(new int[] {0, 2});

        interact(() -> fireFindNext());
        assertThat(lastStatus.get()).contains("第 2 / 3 个匹配");
        assertThat(focusedCell()).isEqualTo(new int[] {1, 2});

        interact(() -> fireFindNext());
        assertThat(lastStatus.get()).contains("第 3 / 3 个匹配");
        assertThat(focusedCell()).isEqualTo(new int[] {3, 1});

        interact(() -> fireFindNext());
        assertThat(lastStatus.get())
                .as("到底必须回绕到第一个，不能停在「没有更多」")
                .contains("第 1 / 3 个匹配");
        assertThat(focusedCell()).isEqualTo(new int[] {0, 2});
    }

    @Test
    void caseSensitiveToggleRecountsMatches() {
        findField().setText("ALPHA");

        interact(() -> fireFindNext());
        assertThat(lastStatus.get())
                .as("默认不区分大小写：ALPHA 应命中 Alpha / Alpha again")
                .contains("第 1 / 2 个匹配");

        CheckBox toggle = (CheckBox) page.lookup("#data-find-case");
        interact(toggle::fire);
        assertThat(toggle.isSelected()).isTrue();
        assertThat(lastStatus.get())
                .as("勾上区分大小写后数据里没有全大写 ALPHA —— 必须报 0")
                .contains("共 0 个匹配");
    }

    // ─────────────────────── 替换 ───────────────────────

    @Test
    void replaceCurrentWritesThroughTheEditBufferAndUndoRestores() {
        findField().setText("Alpha");
        replaceField().setText("Omega");
        interact(() -> fireFindNext());
        assertThat(focusedCell())
                .as("先把焦点落在第一个命中上（row0 name）")
                .isEqualTo(new int[] {0, 1});

        interact(() -> replaceOne().fire());
        assertThat(cellText(0, "name"))
                .as("替换必须真的改到格子")
                .isEqualTo("Omega");
        assertThat(saveButton().isDisabled())
                .as("替换后必须是脏的（保存键可用）—— 它走的是编辑缓冲")
                .isFalse();

        interact(page::undo);
        assertThat(cellText(0, "name"))
                .as("撤销必须原样退回 —— 替换不是直接落库")
                .isEqualTo("Alpha");
        assertThat(saveButton().isDisabled()).isTrue();
    }

    @Test
    void replaceAllRewritesEveryMatchInOneUndoStep() {
        findField().setText("find me");
        replaceField().setText("GONE");

        interact(() -> replaceAll().fire());
        assertThat(lastStatus.get()).contains("已替换 3 处");
        assertThat(cellText(0, "note")).isEqualTo("GONE");
        assertThat(cellText(1, "note")).isEqualTo("GONE too");
        assertThat(cellText(3, "name")).isEqualTo("GONE again");

        interact(page::undo);
        assertThat(cellText(0, "note"))
                .as("一次撤销必须把三处全部退回 —— 全部替换是一个撤销步")
                .isEqualTo("find me");
        assertThat(cellText(1, "note")).isEqualTo("find me too");
        assertThat(cellText(3, "name")).isEqualTo("find me again");
        assertThat(saveButton().isDisabled()).isTrue();
    }

    @Test
    void primaryKeyAndNumericColumnsAreSkippedAndCounted() {
        findField().setText("1");
        replaceField().setText("9");

        interact(() -> replaceAll().fire());
        assertThat(lastStatus.get())
                .as("id=1 是主键、amount=10 是非文本 —— 两处都跳过并报数")
                .contains("跳过 2")
                .contains("没有可替换");
        assertThat(cellText(0, "id"))
                .as("主键一个字都不许动")
                .isEqualTo("1");
        assertThat(cellText(0, "amount")).isEqualTo("10");
    }

    @Test
    void readOnlyViewsKeepTheReplaceKeysDisabled() throws Exception {
        session.execute("CREATE VIEW v_names AS SELECT id, name FROM t");
        DataGridPage viewPage = new DataGridPage(session, new SchemaService(),
                "v_names", "test.db", recordingFeedback(), name -> { }, executor);
        try {
            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                    () -> viewPage.lookup("#data-replace-one") != null
                            && tableOf(viewPage).getItems().size() == 4);
            Button one = (Button) viewPage.lookup("#data-replace-one");
            Button all = (Button) viewPage.lookup("#data-replace-all");
            assertThat(one.isDisabled())
                    .as("视图只读 —— 替换必须点不动")
                    .isTrue();
            assertThat(all.isDisabled()).isTrue();
            assertThat(viewPage.lookup("#data-find-next").isDisabled())
                    .as("但查找不受限：只读也照样能找")
                    .isFalse();
        } finally {
            interact(viewPage::close);
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> table() {
        return (TableView<Map<String, Object>>) page.lookup("#data-table");
    }

    @SuppressWarnings("unchecked")
    private static TableView<Map<String, Object>> tableOf(DataGridPage target) {
        return (TableView<Map<String, Object>>) target.lookup("#data-table");
    }

    private TextField findField() {
        return (TextField) page.lookup("#data-find");
    }

    private TextField replaceField() {
        return (TextField) page.lookup("#data-replace");
    }

    private Button replaceOne() {
        return (Button) page.lookup("#data-replace-one");
    }

    private Button replaceAll() {
        return (Button) page.lookup("#data-replace-all");
    }

    private Button saveButton() {
        return (Button) page.lookup("#data-save");
    }

    /** 点「下一个」= 用户点按钮（不走快捷键，键盘路径由第一条用例覆盖）。 */
    private void fireFindNext() {
        ((Button) page.lookup("#data-find-next")).fire();
    }

    /** 焦点格在「可见数据列」坐标系里的 (行, 列下标)；列按表头顺序 id/name/note/amount。 */
    private int[] focusedCell() {
        AtomicReference<int[]> result = new AtomicReference<>();
        interact(() -> {
            TableView<Map<String, Object>> grid = table();
            javafx.scene.control.TablePosition<Map<String, Object>, ?> focused =
                    (javafx.scene.control.TablePosition<Map<String, Object>, ?>)
                            grid.getFocusModel().getFocusedCell();
            if (focused == null || focused.getRow() < 0) {
                result.set(null);
                return;
            }
            int col = 0;
            // ⚠️ 只数数据列（userData 非空）——行号 / 勾选两列也在 getVisibleLeafColumns
            //    里，数进去整个坐标系 +2，断言就永远差两列。
            for (TableColumn<Map<String, Object>, ?> column : grid.getVisibleLeafColumns()) {
                if (column.getUserData() == null) {
                    continue;
                }
                if (column == focused.getTableColumn()) {
                    result.set(new int[] {focused.getRow(), col});
                    return;
                }
                col++;
            }
            result.set(null);
        });
        return result.get();
    }

    /**
     * 某行某列的<b>显示</b>文本 —— 走页面的显示口径（编辑缓冲已合并），
     * 不能读 items 原始 map：替换落的是缓冲，原始 map 到落库前一直不动。
     */
    private String cellText(int row, String columnName) {
        AtomicReference<String> result = new AtomicReference<>();
        interact(() -> result.set(page.displayedCellText(row, columnName)));
        return result.get();
    }

    private DataGridPage.Feedback recordingFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
                lastStatus.set(message);
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }

            @Override
            public void transaction(boolean inTransaction) {
            }
        };
    }

    /** 平台快捷键事件（macOS = ⌘，其它 = Ctrl）—— 与 QueryPageTest 同一口径。 */
    private static KeyEvent shortcut(KeyCode code) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, !mac, false, mac);
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, alt, meta);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
    }
}
