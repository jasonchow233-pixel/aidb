package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
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
            page.editor().area().fireEvent(key(KeyCode.G, false, false, true));
        });
        assertThat(page.editor().area().getSelectedText()).isEqualTo("SELECT 2");

        interact(() -> page.editor().area().fireEvent(key(KeyCode.H, true, false, false)));
        assertThat(page.lookup("#query-find-bar").isVisible()).isTrue();
        assertThat(page.lookup("#query-replace").isFocused()).isTrue();
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, alt, meta);
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
