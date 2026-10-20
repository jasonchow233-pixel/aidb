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
        assertThat(table.getColumns()).hasSize(3);
        assertThat(table.getItems().getFirst().toString()).contains("n=1", "n_2=2");
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
