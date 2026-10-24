package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U-B5 门禁：数据网格的**交互细节**。
 *
 * <ol>
 *   <li><b>记录导航条</b>（首 / 上 / 下 / 末 + 行号）—— Navicat 形态；</li>
 *   <li><b>列头筛选钮悬停才浮现</b> —— 以前每列常显一个漏斗，
 *       12 列就挂 12 个，噪音压过列名本身。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class GridInteractionTest {

    @TempDir
    Path temp;

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;
    private Stage stage;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
        // ⭐ 带 SettingsStore —— 列顺序要能落盘（没有它 persistLayout 直接返回）。
        page = new DataGridPage(session, new SchemaService(), "users",
                "test.db", noOpFeedback(), name -> { }, executor, new SettingsStore());
        Scene scene = new Scene(page, 1200, 700);
        // ⚠️ 必须把样式表挂上 —— 否则 CSS 规则一条都不生效
        //    （App 里是由 ThemeManager 挂的，测试里要自己挂）。
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        page.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * 等表格加载完。
     *
     * <p>⚠️ 这个等待**不能**放在 {@code @Start} 里 ——
     * {@code WaitForAsyncUtils.waitFor} 会阻塞 FX 线程，
     * 而表格的加载结果恰恰要靠 FX 线程派发 → 死锁 → 超时。
     */
    @BeforeEach
    void waitForTheTable() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null
                        && ((TableView<?>) page.lookup("#data-table")).getItems().size() == 200);
    }

    @AfterEach
    void tearDown() {
        // 清掉测试写进用户偏好的列顺序，别影响下一次跑。
        java.util.prefs.Preferences.userNodeForPackage(SettingsStore.class)
                .remove("ui.grid.test.db.users.order");
        java.util.prefs.Preferences.userNodeForPackage(SettingsStore.class)
                .remove("ui.grid.test.db.users.hidden");
        executor.shutdownNow();
        try {
            session.close();
        } catch (Exception ignored) {
            // 清理失败不影响结论。
        }
    }

    @Test
    void theRecordNavigatorWalksTheRows() {
        assertThat(page.lookup("#data-record-nav")).as("记录导航条").isNotNull();
        assertThat(page.lookup("#data-record-first")).as("首行").isNotNull();
        assertThat(page.lookup("#data-record-prev")).as("上一行").isNotNull();
        assertThat(page.lookup("#data-record-next")).as("下一行").isNotNull();
        assertThat(page.lookup("#data-record-last")).as("末行").isNotNull();

        TableView<?> table = (TableView<?>) page.lookup("#data-table");

        click("#data-record-next");
        int afterNext = table.getSelectionModel().getSelectedIndex();

        click("#data-record-next");
        assertThat(table.getSelectionModel().getSelectedIndex())
                .as("「下一行」要把选中行往下挪")
                .isEqualTo(afterNext + 1);

        click("#data-record-last");
        assertThat(table.getSelectionModel().getSelectedIndex())
                .as("「末行」要跳到本页最后一行")
                .isEqualTo(table.getItems().size() - 1);

        click("#data-record-first");
        assertThat(table.getSelectionModel().getSelectedIndex()).isZero();

        Label position = (Label) page.lookup("#data-record-position");
        assertThat(position.getText())
                .as("行数要在数据加载后更新（不能停在「共 0 行」）")
                .contains("200 行");
    }

    @Test
    void theColumnFilterButtonOnlyShowsOnHover() {
        Button funnel = (Button) page.lookup("#data-filter-name");
        assertThat(funnel).as("列头筛选钮").isNotNull();
        assertThat(funnel.getStyleClass())
                .as("常态没有筛选条件时不该常显")
                .doesNotContain("adb-grid__filter-button--active");
        assertThat(funnel.getOpacity())
                .as("常态隐藏 —— 悬停列头才浮现")
                .isEqualTo(0.0);
    }

    @Test
    void aFilteredColumnKeepsItsFilterButtonVisible() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> page.applyQuery(io.agenticdb.introspect.DataQuery.empty()
                .withFilter(io.agenticdb.introspect.FilterCondition.of(
                        "active", io.agenticdb.introspect.FilterOperator.EQ, 1)))).get();
        WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                () -> page.lastSql().contains("WHERE"));
        WaitForAsyncUtils.waitForFxEvents();

        Button funnel = (Button) page.lookup("#data-filter-active");
        assertThat(funnel).as("被筛选的列的筛选钮").isNotNull();
        assertThat(funnel.getStyleClass())
                .as("该列**有**筛选条件时，筛选钮要一直看得见 —— 那是状态，不是装饰")
                .contains("adb-grid__filter-button--active");
        assertThat(funnel.getOpacity()).isEqualTo(1.0);
    }

    // ─────────────────────────── G6：自增列 ───────────────────────────

    @Test
    void theAutoIncrementColumnIsDetected() {
        io.agenticdb.introspect.SchemaService schemaService =
                new io.agenticdb.introspect.SchemaService();
        try {
            io.agenticdb.introspect.SchemaService.TableSchema schema =
                    schemaService.describe(session, "users");
            // SQLite 的 rowid 别名规则：恰好声明为 INTEGER PRIMARY KEY 的单列主键
            assertThat(schema.autoIncrementColumn())
                    .as("id INTEGER PRIMARY KEY 就是 SQLite 的 rowid 别名（自增列）")
                    .isEqualTo("id");
            assertThat(schema.autoIncrement("id")).isTrue();
            assertThat(schema.autoIncrement("name")).isFalse();
        } catch (java.sql.SQLException e) {
            throw new AssertionError("读表结构失败", e);
        }
    }

    @Test
    void theGridMarksTheAutoIncrementColumn() {
        javafx.scene.control.Label type = (javafx.scene.control.Label)
                page.lookup(".adb-grid__column-type");
        assertThat(type).isNotNull();
        boolean anyMarked = page.lookupAll(".adb-grid__column-type").stream()
                .filter(node -> node instanceof javafx.scene.control.Label label)
                .map(node -> ((javafx.scene.control.Label) node).getText())
                .anyMatch(text -> text != null && text.contains("自增"));
        assertThat(anyMarked)
                .as("自增列要在列头上标出来 —— 否则用户会以为得自己填主键")
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewRowLeavesTheAutoIncrementColumnEmpty() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        int before = table.getItems().size();

        WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.control.Button add =
                    (javafx.scene.control.Button) page.lookup("#data-add");
            add.fire();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table.getItems().size()).isEqualTo(before + 1);
        // 草稿行排在**前面**（rebuildItems 的顺序：草稿 → 本页数据）
        java.util.Map<String, Object> draft = null;
        for (Object item : table.getItems()) {
            java.util.Map<String, Object> row = (java.util.Map<String, Object>) item;
            if (row.containsKey(io.agenticdb.editing.EditBuffer.DRAFT_KEY)) {
                draft = row;
                break;
            }
        }
        assertThat(draft).as("新增的草稿行").isNotNull();
        assertThat(draft.get("id"))
                .as("新增行时自增列必须**留空** —— 由数据库赋值")
                .isNull();
    }

    @Test
    void columnsCanBeReorderedAtRuntime() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);
        assertThat(before)
                .as("先确认有可重排的数据列")
                .hasSizeGreaterThan(2);

        // ⭐ G2：列头**拖拽**重排 —— 顺序本来就能持久化，只是没有运行时入口。
        //    这里用「向右移动一列」这条精确入口验证同一套重排逻辑
        //    （拖拽要用真实的鼠标轨迹，测试里不稳）。
        int from = before.indexOf("score");
        assertThat(from).as("score 列存在").isGreaterThan(0);
        WaitForAsyncUtils.asyncFx(() -> {
            page.reorderColumn("score", from + 1);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        List<String> after = columnOrder(table);
        assertThat(after)
                .as("列顺序要真的变了")
                .isNotEqualTo(before);
        assertThat(after)
                .as("只是换了位置，列本身一个不能少")
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(after.get(from + 1))
                .as("被移动的列要落到目标位置")
                .isEqualTo("score");
    }

    @Test
    void draggingAHeaderReordersTheColumns(FxRobot robot) {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);
        // ⭐ S-6 之后列有可读下限：12 列在 1200px 里放不下时，**最右边那列在视野外**
        //    （节点在、鼠标却够不着，TestFX 报 bounds are not visible in Scene）。
        //    拖拽验证的是「列头拖放 → 重排」这条链路，不挑哪一列，
        //    所以抓前两列 —— 它们在任何宽度下都可见。
        javafx.scene.Node source = page.lookup("#data-header-" + before.get(1));
        javafx.scene.Node target = page.lookup("#data-header-" + before.get(0));
        assertThat(source).as("第二个列头").isNotNull();
        assertThat(target).as("第一个列头").isNotNull();

        // TestFX 4.x：drag(源) + dropTo(目标)
        robot.drag(source);
        robot.dropTo(target);
        WaitForAsyncUtils.waitForFxEvents();

        List<String> after = columnOrder(table);
        assertThat(after)
                .as("拖拽后列顺序要变")
                .isNotEqualTo(before);
        assertThat(after)
                .as("只是换了位置，列本身一个不能少")
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void theReorderedColumnsArePersisted() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);
        int from = before.indexOf("score");
        WaitForAsyncUtils.asyncFx(() -> {
            page.reorderColumn("score", from + 1);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        String saved = page.savedColumnOrder();
        assertThat(saved)
                .as("新顺序要落盘 —— 下次打开还是这个顺序")
                .isNotBlank()
                .contains("score");
    }

    /** 网格里可见的数据列顺序（列名）。 */
    private static List<String> columnOrder(TableView<?> table) {
        List<String> names = new java.util.ArrayList<>();
        table.getColumns().forEach(column -> {
            if (column.getUserData() instanceof String name) {
                names.add(name);
            }
        });
        return names;
    }

    /** 在 FX 线程上点一个按钮。 */
    private void click(String id) {
        Button button = (Button) page.lookup(id);
        assertThat(button).as("按钮 %s", id).isNotNull();
        WaitForAsyncUtils.asyncFx(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }
}
