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
    private final java.util.List<String> renames = new java.util.ArrayList<>();
    private final java.util.List<String> groups = new java.util.ArrayList<>();
    private ConnectionsPage page;

    @Start
    void start(Stage stage) {
        profiles = FXCollections.observableArrayList(
                ConnectionProfile.newProfile("Demo", DialectKind.SQLITE, "", 0, "demo.db", ""),
                ConnectionProfile.newProfile("PG", DialectKind.POSTGRESQL, "db.local", 5432,
                        "shop", "app").withFavorite(true).withGroup("生产"));
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
        page.setRenameHandler((profile, name) -> renames.add(profile.name() + "→" + name));
        page.setGroupHandler((profile, group) -> groups.add(profile.name() + "→" + group));
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
                .hasSize(8);
        assertThat(table.getColumns())
                .extracting(TableColumn::getText)
                .containsExactly("名称", "类型", "分组", "主机", "端口", "数据库", "用户", "状态");
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

    // ─────────────────────── P5-1：重命名 / 分组 ───────────────────────

    /**
     * ⭐ P5-1：分组必须是**可见的一列**，不是藏在某个弹窗里的属性。
     * 未分组显示「—」—— 空白单元格看起来像渲染坏了。
     */
    @Test
    void groupColumnShowsTheGroupOrADash(FxRobot robot) {
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        @SuppressWarnings("unchecked")
        TableColumn<ConnectionProfile, String> group = table.getColumns().stream()
                .filter(column -> "connections-column-group".equals(column.getId()))
                .map(column -> (TableColumn<ConnectionProfile, String>) column)
                .findFirst().orElseThrow();
        ConnectionProfile demo = table.getItems().stream()
                .filter(profile -> profile.name().equals("Demo")).findFirst().orElseThrow();
        ConnectionProfile pg = table.getItems().stream()
                .filter(profile -> profile.name().equals("PG")).findFirst().orElseThrow();

        assertThat(group.getCellData(pg)).as("PG 在「生产」分组").isEqualTo("生产");
        assertThat(group.getCellData(demo)).as("Demo 未分组 —— 显示横线").isEqualTo("—");
    }

    /** ⭐ P5-1：重命名 / 换分组走同一套回调（落盘与内存替换归外壳）。 */
    @Test
    void renameAndGroupSeamsReachTheHandlers(FxRobot robot) {
        robot.clickOn(cellNamed(robot, "Demo"));
        page.renameSelectedTo("Demo 开发库");
        page.groupSelectedAs("本地");

        assertThat(renames).containsExactly("Demo→Demo 开发库");
        assertThat(groups).containsExactly("Demo→本地");
    }

    /** 搜索也要覆盖分组名 —— 否则「分好组了却搜不到」比没有分组更奇怪。 */
    @Test
    void searchAlsoMatchesTheGroupName(FxRobot robot) {
        TextField search = robot.lookup("#connections-search").queryAs(TextField.class);
        robot.clickOn(search);
        robot.write("生产");

        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        assertThat(table.getItems()).extracting(ConnectionProfile::name).containsExactly("PG");
    }

    /** 找到文本等于连接名的表格单元。 */
    private static javafx.scene.Node cellNamed(FxRobot robot, String name) {
        return robot.lookup(node -> node instanceof TableCell<?, ?> cell
                        && cell.getText() != null
                        && cell.getText().equals(name))
                .query();
    }
}
