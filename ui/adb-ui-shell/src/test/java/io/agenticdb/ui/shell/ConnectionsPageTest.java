package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
    private ConnectionsPage page;

    @Start
    void start(Stage stage) {
        profiles = FXCollections.observableArrayList(
                ConnectionProfile.newProfile("Demo", DialectKind.SQLITE, "", 0, "demo.db", ""),
                ConnectionProfile.newProfile("PG", DialectKind.POSTGRESQL, "db.local", 5432,
                        "shop", "app").withFavorite(true));
        newCount = new AtomicInteger();
        openCount = new AtomicInteger();
        page = new ConnectionsPage(profiles,
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
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        assertThat(table.getItems()).hasSize(2);

        TextField search = robot.lookup("#connections-search").queryAs(TextField.class);
        robot.clickOn(search);
        robot.write("PG");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table.getItems()).hasSize(1);
        assertThat(table.getItems().getFirst().name()).isEqualTo("PG");
    }

    @Test
    void invokesNewAndOpenCallbacks(FxRobot robot) {
        robot.clickOn("#connections-new");
        assertThat(newCount.get()).isEqualTo(1);

        robot.clickOn(cellNamed(robot, "Demo"));
        robot.clickOn("#connections-open");
        assertThat(openCount.get()).isEqualTo(1);
    }

    @Test
    void deleteUsesConfirmationHandler(FxRobot robot) {
        robot.clickOn(cellNamed(robot, "PG"));
        robot.clickOn("#connections-delete");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(profiles).extracting(ConnectionProfile::name).containsExactly("Demo");
    }

    // ─────────────────────────── U-B5：连接列表是表格 ───────────────────────────

    @Test
    void theConnectionListIsATableWithNamedColumns(FxRobot robot) {
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);

        assertThat(table.getColumns())
                .as("Navicat 形态：每个信息一列，可排序、可拖列宽")
                .hasSize(7);
        assertThat(table.getColumns())
                .extracting(TableColumn::getText)
                .containsExactly("名称", "类型", "主机", "端口", "数据库", "用户", "状态");
        assertThat(table.getColumns())
                .as("每一列都要能排序")
                .allMatch(TableColumn::isSortable);
    }

    @Test
    void theTableShowsTheConnectionDetailsColumnByColumn(FxRobot robot) {
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        ConnectionProfile pg = table.getItems().get(1);
        assertThat(pg.name()).isEqualTo("PG");
        assertThat(pg.host()).isEqualTo("db.local");
        assertThat(pg.port()).isEqualTo(5432);
        assertThat(pg.database()).isEqualTo("shop");
        assertThat(pg.username()).isEqualTo("app");
    }

    @Test
    void densityChangesTheTableRowHeight(FxRobot robot) {
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        int before = (int) table.getFixedCellSize();
        page.setDensity("compact");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(table.getFixedCellSize())
                .as("密度要作用到连接表 —— 否则用户觉得「密度时灵时不灵」")
                .isLessThan(before);
    }

    /** 找到文本等于连接名的表格单元。 */
    private static javafx.scene.Node cellNamed(FxRobot robot, String name) {
        return robot.lookup(node -> node instanceof TableCell<?, ?> cell
                        && cell.getText() != null
                        && cell.getText().equals(name))
                .query();
    }
}
