package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class QueryPageTest {

    private JdbcSession session;
    private ExecutorService executor;
    private QueryPage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO users VALUES (1, 'Ada'), (2, 'Bob')");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-query");
            thread.setDaemon(true);
            return thread;
        });
        page = new QueryPage(session, "test", "", executor, null,
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
        page.setConfirmationHandler(danger -> true);
        stage.setScene(new Scene(page, 900, 700));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (page != null) {
            page.close();
        }
        executor.shutdownNow();
        session.close();
    }

    @Test
    void runsOnlyCaretStatementAndRendersRows() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;\nSELECT 2 AS n;");
            page.editor().moveTo(0);
            page.runCurrent();
        });
        waitForTabs(1);

        assertThat(page.statusText()).contains("执行完成");
        assertThat(page.resultTabCount()).isEqualTo(1);
        assertThat(page.statusText()).doesNotContain("2 条");
    }

    @Test
    void runsAllStatementsAndCreatesOneTabPerStatement() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;\nSELECT 2 AS n;");
            page.runAll();
        });
        waitForTabs(2);

        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs()).hasSize(2);
        assertThat(results.getTabs().get(0).getText()).contains("结果 1");
        assertThat(results.getTabs().get(1).getText()).contains("结果 2");
    }

    @Test
    void dmlWithoutRowsCreatesMessageTab() {
        interact(() -> {
            page.setSql("INSERT INTO users VALUES (3, 'Cara');");
            page.runAll();
        });
        waitForTabs(1);

        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs().getFirst().getText()).contains("消息 1");
    }

    @Test
    void sqlErrorCreatesErrorTabAndKeepsPriorResults() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;\nSELECT * FROM missing_table;");
            page.runAll();
        });
        waitForTabs(2);

        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs()).hasSize(2);
        assertThat(results.getTabs().get(1).getText()).contains("失败");
        assertThat(results.getTabs().get(1).getContent().lookup("#query-error-details"))
                .as("⭐ P2-2：错误页签必须给一个「查看详情」入口 —— "
                        + "页签只放下一步要改什么，完整证据在弹窗里")
                .isNotNull();
        assertThat(page.statusText()).contains("执行失败");
    }

    @Test
    void duplicateColumnNamesRemainReadable() {
        interact(() -> {
            page.setSql("SELECT 1 AS n, 2 AS n;");
            page.runAll();
        });
        waitForTabs(1);

        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        // ⭐ 两列都该取到，且值可区分。重复列的具体键名由驱动决定
        //   （SQLite 给第二列命名为 n:1，而不是 n_2），所以这里只校验「两列值都能取到」，
        //   不绑定具体键名 —— 否则换个驱动这个断言就假红。
        assertThat(table.getColumns()).hasSize(3);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> row =
                (java.util.Map<String, Object>) table.getItems().getFirst();
        assertThat(row).hasSize(2);
        assertThat(row.values()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void explainUsesDialectSpecificPrefix() {
        interact(() -> {
            page.setSql("SELECT * FROM users;");
            page.explainCurrent();
        });
        waitForTabs(1);

        assertThat(page.statusText()).contains("执行计划");
        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(results.getTabs().getFirst().getText()).contains("结果");
    }

    @Test
    void paginatesSelectResultsAndShowsPager() {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);

        // ⭐ Q5：可分页 SELECT 必须出现分页条，且默认页大小 200 → 250 行分 2 页。
        TabPane results = (TabPane) page.lookup("#query-results");
        assertThat(page.lookup("#query-result-pager")).isNotNull();
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);
        assertThat(pageLabel()).isEqualTo("第 1 / 2 页");
        assertThat(rangeLabel()).isEqualTo("250 行");
        // 第 1 页装的应是前 200 行（id 1..200），绝对行号从 1 开始。
        assertThat(firstRowText(table)).contains("1");
    }

    @Test
    void navigatingToNextPageLoadsTheFollowingRows() {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);

        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        interact(() -> ((javafx.scene.control.Button) page.lookup("#query-result-next")).fire());
        waitFor(() -> "第 2 / 2 页".equals(pageLabel()));
        // 第 2 页只有剩下的 50 行，且按 ORDER BY id 应为 id 201..250。
        assertThat(table.getItems()).hasSize(50);
        assertThat(firstRowText(table)).contains("201");
    }

    @Test
    void singleRowSelectShowsOnePagePager() {
        interact(() -> {
            page.setSql("SELECT 1 AS n;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 1);
        // 单行结果也走分页路径：分页条仍在，只是只有 1 页 —— 行为一致、无回归。
        assertThat(page.lookup("#query-result-pager")).isNotNull();
        assertThat(pageLabel()).isEqualTo("第 1 / 1 页");
    }

    // ─────────────── P6-2：导出范围菜单与「全部结果」 ───────────────

    @Test
    void exportMenuOffersThreeScopesAndDisablesEmptySelection() {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        MenuButton export = (MenuButton) page.lookup("#query-export");
        assertThat(export).as("分页条要有一个导出范围菜单").isNotNull();
        assertThat(export.getItems())
                .filteredOn(item -> item.getId() != null)
                .extracting(MenuItem::getId)
                .containsExactly("query-export-page", "query-export-selected",
                        "query-export-all");

        MenuItem selected = export.getItems().stream()
                .filter(item -> "query-export-selected".equals(item.getId()))
                .findFirst().orElseThrow();
        assertThat(selected.isDisable())
                .as("没有选区时「导出选中行」必须灰掉，否则导出的是空表")
                .isTrue();

        interact(() -> table.getSelectionModel().select(0));
        assertThat(selected.isDisable()).isFalse();
    }

    @Test
    void exportAllResultsIgnoresTheUiPageCap() throws Exception {
        seedRows(250);
        interact(() -> {
            page.setSql("SELECT * FROM users ORDER BY id;");
            page.runAll();
        });
        waitForTabs(1);
        TableView<?> table = (TableView<?>) page.lookup("#query-result-table");
        waitFor(() -> table.getItems().size() == 200);

        StringWriter all = new StringWriter();
        long exported = page.exportAllResultsTo(0, all);
        assertThat(exported)
                .as("⭐ 「全部结果」要重跑原始 SQL，不受 UI 单页 200 行上限")
                .isEqualTo(250);
        List<String> lines = all.toString().lines().toList();
        assertThat(lines).hasSize(251); // 表头 + 250 行
        assertThat(lines.getLast()).contains("250").contains("u250");

        StringWriter currentPage = new StringWriter();
        page.exportCurrentPageTo(0, currentPage);
        List<String> pageLines = currentPage.toString().lines().toList();
        assertThat(pageLines).hasSize(201); // 表头 + 当前页 200 行
        assertThat(pageLines.getLast()).doesNotContain("u250");
    }

    private void seedRows(int count) {
        try {
            // @Start 已预置 (1,'Ada'),(2,'Bob')，先清空避免主键冲突。
            session.execute("DELETE FROM users");
        } catch (java.sql.SQLException ignored) {
        }
        StringBuilder sql = new StringBuilder("INSERT INTO users (id, name) VALUES ");
        for (int i = 1; i <= count; i++) {
            sql.append("(").append(i).append(", 'u").append(i).append("')")
                    .append(i < count ? "," : "");
        }
        try {
            session.execute(sql.toString());
        } catch (java.sql.SQLException e) {
            throw new AssertionError("seed 失败", e);
        }
    }

    private String pageLabel() {
        return ((javafx.scene.control.Label) page.lookup("#query-result-page-label")).getText();
    }

    private String rangeLabel() {
        return ((javafx.scene.control.Label) page.lookup("#query-result-range")).getText();
    }

    private String firstRowText(TableView<?> table) {
        return table.getItems().getFirst().toString();
    }

    private void waitFor(java.util.function.BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition::getAsBoolean);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("条件超时；状态：" + page.statusText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void formatsSqlAndMarksEditorDirty() {
        interact(() -> page.setSql("select id, name from users where id = 1;"));
        assertThat(page.dirtyCount()).isEqualTo(1);

        interact(() -> page.formatSql());
        assertThat(page.sql()).contains("SELECT id, name").contains("\nFROM users");
    }

    @Test
    void togglesLineComments() {
        interact(() -> {
            page.setSql("SELECT 1;\nSELECT 2;");
            page.editor().select(0, page.sql().length());
            page.toggleComment();
        });
        assertThat(page.sql()).isEqualTo("-- SELECT 1;\n-- SELECT 2;");

        interact(() -> page.toggleComment());
        assertThat(page.sql()).isEqualTo("SELECT 1;\nSELECT 2;");
    }

    @Test
    void findAndReplaceShortcutsDriveFindBar() {
        interact(() -> {
            page.setSql("SELECT 1;\nSELECT 2;");
            page.editor().moveTo(0);
        });
        TextField find = (TextField) page.lookup("#query-find");
        interact(() -> {
            find.setText("SELECT 2");
            // ⚠️ 快捷键是「平台快捷键」：macOS 是 ⌘，Windows/Linux 是 Ctrl。
            //    写死 meta=true 只在 macOS 上成立（Windows 上 isShortcutDown() 为 false）。
            page.editor().area().fireEvent(shortcut(KeyCode.G));
        });
        assertThat(page.editor().area().getSelectedText()).isEqualTo("SELECT 2");

        interact(() -> page.editor().area().fireEvent(key(KeyCode.H, true, false, false)));
        assertThat(page.lookup("#query-find-bar").isVisible()).isTrue();
        assertThat(page.lookup("#query-replace").isFocused()).isTrue();
    }

    // ───────────────── 第十八轮：每页档位必须真的选中 ─────────────────

    @Test
    void resultPagerShowsThePageSizeInsteadOfAnEmptyBox() {
        interact(() -> {
            page.setSql("SELECT id FROM users;");
            page.editor().moveTo(0);
            page.runAll();
        });
        waitForTabs(1);

        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<Integer> box =
                (javafx.scene.control.ComboBox<Integer>) page.lookup("#query-result-page-size");
        assertThat(box).as("结果分页条要有「每页」下拉").isNotNull();

        // ⚠️ 回归守卫：`select(int)` 是按下标选（items 只有 0–3 四项），
        //    传原生 int 会静默失败，「每页」框渲染成空白 —— 截图里就是空的。
        assertThat(box.getValue())
                .as("「每页」必须显示当前档位（DEFAULT_PAGE_SIZE=200），不能是空框")
                .isNotNull()
                .isEqualTo(200);

        // ⚠️ 第十八轮实测补的回归守卫：**选中了不等于显示得出来**。
        //    78px 时截图里「200」被截成「2…」，96px 才完整渲染。
        assertThat(box.getPrefWidth())
                .as("「每页」框要够渲染最宽档位「1000」—— 实测 78px 会把「200」截成「2…」")
                .isGreaterThanOrEqualTo(90);
    }

    // ─────────────── 第十八轮：跳页框必须与数据页同规格 ───────────────

    @Test
    void resultPagerJumpFieldCarriesTheSameAffordancesAsTheDataPage() {
        interact(() -> {
            page.setSql("SELECT id FROM users;");
            page.editor().moveTo(0);
            page.runAll();
        });
        waitForTabs(1);

        TextField jump = (TextField) page.lookup("#query-result-jump");
        assertThat(jump).as("结果分页条要有跳页框").isNotNull();

        // 以前这里只有 id + 宽度 —— 截图上是一个**没有占位、读屏念不出用途**的空白框。
        assertThat(jump.getPromptText())
                .as("跳页框必须有占位提示（数据页 #data-jump 是「跳至页」，两页要一致）")
                .isNotEmpty();
        assertThat(jump.getAccessibleText())
                .as("跳页框必须有 accessibleText（同数据页）")
                .isNotEmpty();
        assertThat(jump.getStyleClass())
                .as("同一控件要走同一个样式类 adb-grid__jump")
                .contains("adb-grid__jump");
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, alt, meta);
    }

    /** 平台快捷键事件（macOS = ⌘，其它 = Ctrl）。 */
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

    private void waitForTabs(int count) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> page.resultTabCount() == count);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("等待结果页 " + count + " 超时；当前 "
                    + page.resultTabCount() + "，状态：" + page.statusText(), e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }
}
