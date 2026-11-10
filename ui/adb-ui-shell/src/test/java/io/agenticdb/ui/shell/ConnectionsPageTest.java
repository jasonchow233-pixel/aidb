package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
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
                .containsExactly(I18n.t("column.name"), I18n.t("detail.type"),
                        I18n.t("conn.col.group"), I18n.t("wizard.host"),
                        I18n.t("wizard.port"), I18n.t("category.database"),
                        I18n.t("conn.col.user"), I18n.t("conn.col.state"));
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

    /**
     * ⭐ R54：类型列以前<b>只有文字</b>（p5-connections 实拍确认：那列写着 "SQLITE"，一个图形都没有）。
     *
     * <p>这一列是用户挑连接时唯一在扫的一列，而六家里 MySQL / MariaDB 首字母相同、
     * PostgreSQL / Oracle 都是七个字母以上 —— 只给文字等于让人读字。图形与文字是两条线索，
     * 都要留着：图形负责「一眼」，文字负责「读得出全名」。
     */
    @Test
    void theTypeColumnCarriesTheVendorsOwnGlyphPerRow(FxRobot robot) {
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        TableColumn<ConnectionProfile, String> kind = kindColumn(table);

        javafx.scene.Node pg = graphicOf(table, kind, table.getItems().get(1));
        javafx.scene.Node sqlite = graphicOf(table, kind, table.getItems().get(0));

        assertThat(pg).as("PG 那一行必须有图形，不是只有文字").isNotNull();
        assertThat(sqlite).as("SQLite 那一行同理").isNotNull();
        assertThat(pg.getStyleClass()).contains("adb-db--postgresql");
        assertThat(sqlite.getStyleClass()).contains("adb-db--sqlite");
        assertThat(pg.getStyleClass()).doesNotContain("adb-db--sqlite");
        // ⭐ 两行必须**不同形**：同形 = 分了个假厂区，界面照样能看，只有这条断言会红。
        assertThat(shapes(pg)).isNotEqualTo(shapes(sqlite));
    }

    /** 取该列单元格里那枚商标的<b>形状</b>（SVGPath 列表，按绘制层顺序）。 */
    private static java.util.List<String> shapes(javafx.scene.Node graphic) {
        if (graphic instanceof javafx.scene.Group group) {
            return group.getChildren().stream()
                    .map(node -> node instanceof javafx.scene.shape.SVGPath path
                            ? path.getContent() : node.getClass().getSimpleName())
                    .toList();
        }
        // R63-3：统一出口现在交回的是「浅色底板 + 商标分层」，先剥掉底板再取形状。
        if (graphic instanceof javafx.scene.layout.Pane pane
                && pane.getChildren().size() == 1) {
            return shapes(pane.getChildren().get(0));
        }
        return java.util.List.of(((javafx.scene.shape.SVGPath) graphic).getContent());
    }

    private static TableColumn<ConnectionProfile, String> kindColumn(
            TableView<ConnectionProfile> table) {
        @SuppressWarnings("unchecked")
        TableColumn<ConnectionProfile, String> kind = table.getColumns().stream()
                .filter(column -> "connections-column-kind".equals(column.getId()))
                .map(column -> (TableColumn<ConnectionProfile, String>) column)
                .findFirst().orElseThrow();
        return kind;
    }

    /**
     * 取**皮肤渲染出来的**那一格里图形的形状。
     *
     * <p>⚠️ 不直接调 {@code cellFactory}：{@code TableCell.setTableRow} 是 private、
     * {@code updateItem} 是跨包 protected，测试碰不到；更重要的是，只喂工厂不喂皮肤等于
     * 又回到 R43-a 那个坑 —— 「数据/类在工厂交出来的东西里，皮肤没挂上」这条断言照样绿。
     * 页面在 {@code @Start} 里已经 show 过，两行都在视口内，这里先证明格子真的渲染出来了。
     */
    private static javafx.scene.Node graphicOf(TableView<ConnectionProfile> table,
            TableColumn<ConnectionProfile, String> column, ConnectionProfile profile) {
        javafx.scene.Node[] holder = new javafx.scene.Node[1];
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            for (javafx.scene.Node node : table.lookupAll(".table-cell")) {
                if (!(node instanceof TableCell<?, ?> cell)
                        || cell.getTableColumn() != column
                        || !(cell.getTableRow() instanceof javafx.scene.control.TableRow<?> row)
                        || row.getItem() != profile) {
                    continue;
                }
                holder[0] = cell.getGraphic();
                return;
            }
            org.assertj.core.api.Assertions.fail("%s 那一格没渲染出来 —— 断言是空的", profile.name());
        });
        return holder[0];
    }

    @Test
    void densityChangesTheTableRowHeight(FxRobot robot) {        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
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

    /** ⭐ SSH 已知主机入口常驻连接页，且不随「有没有选中连接」被禁用。 */
    @Test
    void sshKnownHostsEntryIsAlwaysReachable(FxRobot robot) {
        javafx.scene.control.Button button = robot.lookup("#connections-ssh-hosts")
                .queryAs(javafx.scene.control.Button.class);
        assertThat(button.getText()).isEqualTo(I18n.t("conn.ssh.hosts"));
        assertThat(button.isDisabled()).isFalse();
    }

    /** 找到文本等于连接名的表格单元。 */
    private static javafx.scene.Node cellNamed(FxRobot robot, String name) {
        return robot.lookup(node -> node instanceof TableCell<?, ?> cell
                        && cell.getText() != null
                        && cell.getText().equals(name))
                .query();
    }
}
