package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(ApplicationExtension.class)
class ConnectionsPageTest {

    private ObservableList<ConnectionProfile> profiles;
    private AtomicInteger newCount;
    private AtomicInteger openCount;

    @Start
    void start(Stage stage) {
        profiles = FXCollections.observableArrayList(
                ConnectionProfile.newProfile("Demo", DialectKind.SQLITE, "", 0, "demo.db", ""),
                ConnectionProfile.newProfile("PG", DialectKind.POSTGRESQL, "db.local", 5432,
                        "shop", "app").withFavorite(true));
        newCount = new AtomicInteger();
        openCount = new AtomicInteger();
        ConnectionsPage page = new ConnectionsPage(profiles,
                newCount::incrementAndGet,
                profile -> openCount.incrementAndGet(),
                profile -> { },
                profile -> { },
                profiles::remove,
                profile -> profile.name().equals("Demo"),
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
        page.setDeleteConfirmation(profile -> true);
        stage.setScene(new javafx.scene.Scene(page, 900, 600));
        stage.show();
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void listsProfilesAndFiltersBySearch(FxRobot robot) {
        ListView<ConnectionProfile> list = robot.lookup("#connections-list")
                .queryAs(ListView.class);
        assertThat(list.getItems()).hasSize(2);

        TextField search = robot.lookup("#connections-search").queryAs(TextField.class);
        robot.clickOn(search);
        robot.write("PG");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().getFirst().name()).isEqualTo("PG");
    }

    @Test
    void invokesNewAndOpenCallbacks(FxRobot robot) {
        robot.clickOn("#connections-new");
        assertThat(newCount.get()).isEqualTo(1);

        robot.clickOn((javafx.scene.Node) robot.lookup((javafx.scene.Node node) -> node instanceof Label
                        && node.getStyleClass().contains("adb-connections__name")
                        && ((Label) node).getText().contains("Demo")).query());
        robot.clickOn("#connections-open");
        assertThat(openCount.get()).isEqualTo(1);
    }

    @Test
    void deleteUsesConfirmationHandler(FxRobot robot) {
        robot.clickOn((javafx.scene.Node) robot.lookup((javafx.scene.Node node) -> node instanceof Label
                        && node.getStyleClass().contains("adb-connections__name")
                        && ((Label) node).getText().contains("PG")).query());
        robot.clickOn("#connections-delete");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(profiles).extracting(ConnectionProfile::name).containsExactly("Demo");
    }
}
