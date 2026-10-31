package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U-B6 门禁：长文本 / JSON / CLOB 的**多行单元格编辑器**。
 *
 * <p>格子里那条单行输入框改不了 3KB 的 JSON —— 看不见换行，只能左右拖。
 * 抽屉的文本页因此变成可写的：应用写进**同一个编辑缓冲**，
 * Esc 只放弃这次编辑（不能顺手把抽屉关了），只读表和主键根本不出现编辑器。
 */
@ExtendWith(ApplicationExtension.class)
class CellEditorTest {

    @TempDir
    Path temp;

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;
    private SchemaService schemaService;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
        schemaService = new SchemaService();
        page = new DataGridPage(session, schemaService, "users",
                "test.db", noOpFeedback(), name -> { }, executor, new SettingsStore());
        Scene scene = new Scene(page, 1200, 700);
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
     * <p>⚠️ 不能放进 {@code @Start} —— {@code waitFor} 会阻塞 FX 线程，
     * 而加载结果恰恰要靠 FX 线程派发 → 死锁 → 超时。
     */
    @BeforeEach
    void waitForTheTable() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null
                        && ((TableView<?>) page.lookup("#data-table")).getItems().size() == 200);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        try {
            session.close();
        } catch (Exception ignored) {
            // 清理失败不影响结论。
        }
    }

    /** 应用一次编辑：缓冲里真的多出一处待保存，格子刷新成新的值。 */
    @Test
    void theDrawerEditorWritesIntoTheSameBuffer(FxRobot robot) {
        openDetails("name");
        TextArea area = (TextArea) page.lookup("#data-details-text");
        assertThat(area.isEditable())
                .as("没点「编辑」之前，详情文本只读")
                .isFalse();

        click("#data-details-edit");
        assertThat(area.isEditable()).as("点编辑后文本页可写").isTrue();
        assertThat(page.lookup("#data-details-apply"))
                .as("编辑态要给出「应用」").isNotNull();
        assertThat(((Button) page.lookup("#data-details-edit")).isManaged())
                .as("进了编辑态，「编辑」按钮自己让位")
                .isFalse();

        String replacement = "edited-by-drawer-" + System.nanoTime();
        typeInto(robot, area, replacement);
        // ⭐ Ctrl+Enter 应用 —— 多行编辑器里回车是换行，不能拿来提交。
        robot.push(KeyCode.CONTROL, KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.dirtyCount())
                .as("应用后要落进编辑缓冲（和格子里改是同一条路）")
                .isEqualTo(1);
        assertThat(area.getText())
                .as("应用后退出编辑态，文本页恢复成新值")
                .contains("edited-by-drawer");
        assertThat(((TextArea) page.lookup("#data-details-text")).isEditable())
                .as("应用完就是查看态，不能停在编辑中")
                .isFalse();
        assertThat(((Button) page.lookup("#data-save")).isDisabled())
                .as("缓冲脏了，行操作条的「保存」同时亮起")
                .isFalse();
    }

    /** Esc 只放弃编辑：抽屉还开着、缓冲一个字节都没动。 */
    @Test
    void escapeAbandonsTheEditButKeepsTheDrawer(FxRobot robot) {
        openDetails("name");
        click("#data-details-edit");
        TextArea area = (TextArea) page.lookup("#data-details-text");
        String before = area.getText();

        typeInto(robot, area, "要放弃的内容");
        robot.press(KeyCode.ESCAPE).release(KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.dirtyCount())
                .as("Esc 之后缓冲必须还是空的")
                .isZero();
        assertThat(area.isEditable())
                .as("Esc 退出编辑态")
                .isFalse();
        assertThat(area.getText())
                .as("文本页恢复成渲染后的原值，不是留在半成品上")
                .isEqualTo(before);
        assertThat(((javafx.scene.Node) page.lookup("#data-details")).isVisible())
                .as("Esc 关的是编辑态，不是整个抽屉 —— 用户还想接着看这一格")
                .isTrue();
        assertThat(((Button) page.lookup("#data-details-cancel")).isManaged())
                .as("退出编辑态后「取消」让位给「编辑」")
                .isFalse();
    }

    /**
     * 主键列与只读表**不出现**编辑器，可写的普通文本列**要出现**。
     *
     * <p>改主键等于改「这行是谁」，UPDATE 的 WHERE 会跟着失效；
     * 视图压根不能写。两者都不该给一个点了会出事的输入框。
     *
     * <p>⚠️ 正例（可写列真的出现「编辑」）不能省 —— 只写反例的话，
     *    「编辑器永远不出现」这种改坏也能全绿。
     */
    @Test
    void primaryKeyAndReadOnlyTablesNeverOfferTheEditor() throws Exception {
        openDetails("id");
        assertThat(((Button) page.lookup("#data-details-edit")).isManaged())
                .as("主键列不出现「编辑」")
                .isFalse();

        // ⭐ 演示库里所有主键都是 INTEGER，光靠 type!=TEXT 就能把这一条糊过去，
        //    所以自建一张「文本主键」表：那一列既是文本又是主键，糊不了。
        try (var statement = session.connection().createStatement()) {
            statement.execute("CREATE TABLE codes ("
                    + "code TEXT PRIMARY KEY, note TEXT NOT NULL)");
            statement.execute("INSERT INTO codes (code, note) VALUES ('A-1', '第一行备注')");
        }
        DataGridPage codes = new DataGridPage(session, schemaService,
                "codes", "test.db", noOpFeedback(), name -> { }, executor,
                new SettingsStore());
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                codes.lookup("#data-details") != null
                        && ((TableView<?>) codes.lookup("#data-table")).getItems().size() == 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> codeRow = (Map<String, Object>)
                ((TableView<Map<String, Object>>) codes.lookup("#data-table")).getItems().get(0);

        codes.showDetails(codeRow, columnDef("codes", "code"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(((Button) codes.lookup("#data-details-edit")).isManaged())
                .as("文本主键列也不给编辑器（type 是 TEXT，拦它的是主键规则）")
                .isFalse();

        codes.showDetails(codeRow, columnDef("codes", "note"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(((Button) codes.lookup("#data-details-edit")).isManaged())
                .as("可写表的普通文本列必须出现「编辑」")
                .isTrue();

        DataGridPage viewPage = new DataGridPage(session, schemaService,
                "v_active_users", "test.db", noOpFeedback(), name -> { }, executor,
                new SettingsStore());
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                viewPage.lookup("#data-details") != null
                        && ((TableView<?>) viewPage.lookup("#data-table"))
                        .getItems().size() > 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>)
                ((TableView<Map<String, Object>>) viewPage.lookup("#data-table"))
                        .getItems().get(0);
        viewPage.showDetails(row, columnDef("users", "name"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(((Button) viewPage.lookup("#data-details-edit")).isManaged())
                .as("只读视图不出现「编辑」")
                .isFalse();
        assertThat(((TextArea) viewPage.lookup("#data-details-text")).isEditable())
                .as("只读视图的文本页永远只读")
                .isFalse();
    }

    /**
     * 编辑中切到别的页签 = **放弃这次编辑**，绝不能把另一页的内容提交进格子。
     *
     * <p>文本页塞的是原值、JSON 页是美化过的（带换行缩进）。如果提交目标由
     * 「当前哪一页」决定，就会出现：在文本页敲两下、切到 JSON 页、按 Ctrl+Enter，
     * 把整段美化 JSON 写进一个 TEXT 列 —— 缓冲里还显示「1 处待保存」，谁都想不到的那种错。
     */
    @Test
    void switchingTabsMidEditAbandonsTheEdit(FxRobot robot) throws Exception {
        openDetails("name");
        click("#data-details-edit");
        TextArea area = (TextArea) page.lookup("#data-details-text");
        typeInto(robot, area, "不该被写进去的字");
        assertThat(page.dirtyCount()).as("前置条件：还在编辑态").isZero();

        @SuppressWarnings("unchecked")
        javafx.scene.control.TabPane tabs =
                (javafx.scene.control.TabPane) page.lookup("#data-details-tabs");
        WaitForAsyncUtils.<Void>asyncFx(() -> {
            tabs.getSelectionModel().select(1);   // 切到「JSON」页
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(((TextArea) page.lookup("#data-details-text")).isEditable())
                .as("换页就退出编辑态，文本页恢复只读")
                .isFalse();
        robot.push(KeyCode.CONTROL, KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.dirtyCount())
                .as("切页后按 Ctrl+Enter 什么都不许写进缓冲")
                .isZero();
        assertThat(((Button) page.lookup("#data-details-edit")).isManaged())
                .as("「编辑」回到原位，不留在编辑态的按钮排上")
                .isTrue();
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 打开某一列第一行的详情抽屉。 */
    @SuppressWarnings("unchecked")
    private void openDetails(String column) {
        TableView<Map<String, Object>> table =
                (TableView<Map<String, Object>>) page.lookup("#data-table");
        Map<String, Object> row = table.getItems().get(1);
        ColumnDef definition = columnDef("users", column);
        WaitForAsyncUtils.asyncFx(() -> {
            page.showDetails(row, definition);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.Node drawer = page.lookup("#data-details");
        assertThat(drawer).as("详情抽屉").isNotNull();
        assertThat(drawer.isVisible()).as("抽屉要开着").isTrue();
    }

    private ColumnDef columnDef(String table, String name) {
        try {
            List<ColumnDef> columns = schemaService.describe(session, table).columns();
            return columns.stream().filter(column -> column.name().equals(name))
                    .findFirst().orElseThrow(() -> new AssertionError("没有这一列: " + name));
        } catch (java.sql.SQLException e) {
            throw new AssertionError("读表结构失败", e);
        }
    }

    private static void typeInto(FxRobot robot, TextArea area, String text) {
        robot.clickOn(area);
        robot.write(text);
        WaitForAsyncUtils.waitForFxEvents();
    }

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
