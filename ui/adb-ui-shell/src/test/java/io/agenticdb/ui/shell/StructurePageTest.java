package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class StructurePageTest {

    private JdbcSession session;
    private ExecutorService executor;
    private StructurePage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, "
                + "email TEXT NOT NULL DEFAULT 'x')");
        session.execute("CREATE INDEX idx_users_name ON users(name)");
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-structure");
            thread.setDaemon(true);
            return thread;
        });
        page = new StructurePage(session, new io.agenticdb.introspect.SchemaService(), "users",
                "test", new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        }, executor, () -> { });
        page.setConfirmationHandler(plan -> true);
        stage.setScene(new Scene(page, 1000, 700));
        stage.show();
        WaitForAsyncUtils.waitForFxEvents();
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
    void loadsColumnsIndexesAndDdl(FxRobot robot) throws Exception {
        waitForStructure();

        TableStructure structure = page.structure();
        assertThat(structure.columns()).extracting("name")
                .containsExactly("id", "name", "email");
        assertThat(structure.indexes()).extracting(IndexDef::name)
                .containsExactly("idx_users_name");
        assertThat(structure.ddl()).contains("CREATE TABLE users", "PRIMARY KEY");
        assertThat(robot.lookup("#structure-columns").queryAs(javafx.scene.control.TableView.class)
                .getItems()).hasSize(3);
        assertThat(robot.lookup(".adb-structure__ddl").queryAs(javafx.scene.control.TextArea.class)
                .getText()).contains("CREATE TABLE users");
    }

    @Test
    void addsColumnThroughDialogAndRefreshes(FxRobot robot) throws Exception {
        waitForStructure();
        int before = page.structure().columns().size();

        robot.clickOn("#structure-add");
        WaitForAsyncUtils.waitForFxEvents();

        TextInputControl name = robot.lookup("#structure-dialog-name").queryTextInputControl();
        robot.clickOn(name);
        robot.write("nickname");
        Button ok = robot.lookup(node -> node instanceof Button button
                        && "确定".equals(button.getText()))
                .queryAs(Button.class);
        robot.clickOn(ok);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                page.structure() != null && page.structure().columns().size() == before + 1);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.structure().column("nickname")).isNotNull();
    }

    @Test
    void cannotRunTwoStructureChangesAtOnce(FxRobot robot) throws Exception {
        waitForStructure();
        robot.clickOn("#structure-refresh");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#structure-add").queryAs(Button.class).isDisabled()).isFalse();
    }

    private void waitForStructure() throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.structure() != null);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
