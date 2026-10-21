package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 冒烟门禁：窗口、六区、侧栏折叠、主题、标签与命令面板。
 *
 * <p>运行参数由父 POM 的 surefire 配置提供（Monocle Headless）。
 */
@ExtendWith(ApplicationExtension.class)
class AppShellTest {

    private static Path demoDirectory;

    @BeforeAll
    static void isolateDemoDatabase() throws IOException {
        demoDirectory = Files.createTempDirectory("adb-app-test-");
        System.setProperty("adb.demo.db", demoDirectory.resolve("demo.db").toString());
        System.setProperty("adb.connections.file", demoDirectory.resolve("connections.json").toString());
    }

    @AfterAll
    static void cleanDemoDatabase() throws IOException {
        System.clearProperty("adb.demo.db");
        System.clearProperty("adb.connections.file");
        if (demoDirectory == null) {
            return;
        }
        try (var paths = Files.walk(demoDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响测试结论。
                }
            });
        }
    }

    @Start
    void start(Stage stage) {
        Preferences preferences = Preferences.userNodeForPackage(SettingsStore.class);
        preferences.put("ui.theme", "light");
        preferences.put("ui.density", "normal");
        preferences.putBoolean("ui.sidebar.collapsed", false);
        preferences.putInt("ui.sidebar.width", 240);
        new App().start(stage);
    }

    @BeforeEach
    void resetUi(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        if (robot.lookup(".adb-sidebar--collapsed").tryQuery().isPresent()) {
            robot.clickOn("#sidebar-expand");
            WaitForAsyncUtils.waitForFxEvents();
        }

        while (robot.lookup(".adb-tab__close").tryQuery().isPresent()) {
            robot.clickOn(".adb-tab__close");
            WaitForAsyncUtils.waitForFxEvents();
        }

        javafx.scene.layout.StackPane palette =
                robot.lookup(".adb-palette-overlay")
                        .queryAs(javafx.scene.layout.StackPane.class);
        if (palette.isVisible()) {
            robot.push(javafx.scene.input.KeyCode.ESCAPE);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    @Test
    void showsSixRegions(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup(".app-shell").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-top").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-toolbar").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-sidebar").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-workspace").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-statusbar").tryQuery()).isPresent();
        // ⭐ S5：启动时停在**常驻的「对象」首页**（Navicat 同形），
        //    不再是空白欢迎页。
        assertThat(robot.lookup(".adb-object-home").tryQuery()).isPresent();

        javafx.scene.control.MenuBar menuBar =
                robot.lookup(".menu-bar").queryAs(javafx.scene.control.MenuBar.class);
        // 8 个顶级菜单：文件 / 编辑 / 视图 / 数据库 / **收藏夹** / 工具 / 窗口 / 帮助
        assertThat(menuBar.getMenus()).hasSize(8);
        assertThat(menuBar.getMenus().stream().map(javafx.scene.control.Menu::getText))
                .contains("收藏夹");

        // ⭐ S5：标签栏**永远可见** —— 第一个标签是常驻的「对象」标签，
        //    它是浏览的起点（Navicat 同形）。
        assertThat(robot.lookup(".adb-tabbar").queryAs(TabBar.class).isVisible()).isTrue();
        assertThat(robot.lookup("#" + TabBar.nodeId("object-home")).tryQuery())
                .as("常驻的「对象」标签")
                .isPresent();
    }

    @Test
    void sidebarCollapsesToRail(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        if (robot.lookup(".adb-sidebar--collapsed").tryQuery().isPresent()) {
            robot.clickOn("#sidebar-expand");
        }

        robot.clickOn("#sidebar-collapse");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup(".adb-sidebar--collapsed").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-rail").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-sidebar").queryAs(SideBar.class).getPrefWidth())
                .isEqualTo(48.0);
    }

    @Test
    void themeToggles(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn("#theme-toggle-button");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup(".theme-dark").tryQuery()).isPresent();
    }

    @Test
    void opensTableTabAndClosesIt(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.control.TreeCell<?> usersCell = robot.lookup(node ->
                node instanceof javafx.scene.control.TreeCell<?> cell
                        && cell.getTreeItem() != null
                        && cell.getTreeItem().getValue() != null
                        && cell.getTreeItem().getValue().toString().contains("users"))
                .queryAs(javafx.scene.control.TreeCell.class);
        robot.doubleClickOn(usersCell);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup(".adb-tabbar").queryAs(TabBar.class).isVisible()).isTrue();
        assertThat(robot.lookup(".adb-tab").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-page__title").queryAs(javafx.scene.control.Label.class)
                .getText()).isEqualTo("users");

        robot.clickOn(".adb-tab__close");
        WaitForAsyncUtils.waitForFxEvents();
        // ⭐ S5：关掉数据页后落回**常驻的「对象」标签**（不是空白欢迎页）。
        assertThat(robot.lookup(".adb-tab").queryAs(javafx.scene.layout.HBox.class).getId())
                .isEqualTo(TabBar.nodeId("object-home"));
        assertThat(robot.lookup(".adb-object-home").tryQuery())
                .as("对象首页")
                .isPresent();
    }

    @Test
    void loadsRealUsersPageAndStatusRange(FxRobot robot) throws Exception {
        openTable(robot, "users");

        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                table(robot).getItems().size() == 200);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table(robot).getColumns()).hasSize(9); // 选择 + # + 7 columns
        assertThat(robot.lookup("#data-range").queryAs(Label.class).getText())
                .isEqualTo("1–200 / 200");
        assertThat(robot.lookup("#data-page").queryAs(Label.class).getText())
                .isEqualTo("第 1 / 1 页");
        assertThat(robot.lookup(".adb-status__range").queryAs(Label.class).getText())
                .isEqualTo("1–200 / 200");
        assertThat(robot.lookup("#data-prev").queryAs(javafx.scene.control.Button.class).isDisabled())
                .isTrue();
        assertThat(robot.lookup("#data-next").queryAs(javafx.scene.control.Button.class).isDisabled())
                .isTrue();
    }

    @Test
    void sortsByColumnHeaderAndClearsQuery(FxRobot robot) throws Exception {
        openTable(robot, "users");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                table(robot).getItems().size() == 200);

        DataGridPage page = robot.lookup(".adb-grid-page").queryAs(DataGridPage.class);
        robot.clickOn("#data-header-name");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                page.lastSql().contains("ORDER BY \"name\" ASC"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#data-query-chips")
                .queryAs(javafx.scene.layout.FlowPane.class).isVisible()).isTrue();
        assertThat(robot.lookup("#data-clear-query")
                .queryAs(javafx.scene.control.Button.class).isDisabled()).isFalse();

        robot.clickOn("#data-clear-query");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                !page.lastSql().contains("ORDER BY"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.query().isDefault()).isTrue();
        assertThat(robot.lookup("#data-query-chips")
                .queryAs(javafx.scene.layout.FlowPane.class).isVisible()).isFalse();
    }

    @Test
    void editsCellZoomUndoRedoThroughBuffer(FxRobot robot) throws Exception {
        openTable(robot, "users");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                table(robot).getItems().size() == 200);
        DataGridPage page = robot.lookup(".adb-grid-page").queryAs(DataGridPage.class);

        javafx.scene.control.TableCell<?, ?> nameCell = robot.lookup(node ->
                node instanceof javafx.scene.control.TableCell<?, ?> cell
                        && "User 001".equals(cell.getText()))
                .queryAs(javafx.scene.control.TableCell.class);
        robot.doubleClickOn(nameCell);
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.TextField editor = robot.lookup(".adb-grid__editor")
                .queryAs(javafx.scene.control.TextField.class);
        robot.clickOn(editor);
        robot.write("Renamed User");
        robot.push(javafx.scene.input.KeyCode.ENTER);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 1);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#data-save").queryAs(javafx.scene.control.Button.class)
                .isDisabled()).isFalse();
        // ⚠️ 脏标记要按**标签**找 —— 常驻的「对象」标签排在前面，
        //    它的脏标记是不可见的，直接 lookup 会找错节点。
        assertThat(dirtyMarkerOf(robot, "grid:demo:users").isVisible()).isTrue();

        robot.clickOn("#data-undo");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 0);
        assertThat(dirtyMarkerOf(robot, "grid:demo:users").isVisible()).isFalse();

        robot.clickOn("#data-redo");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 1);
        robot.clickOn("#data-undo");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 0);
    }

    @Test
    void pagesOrdersWithoutLosingTabState(FxRobot robot) throws Exception {
        openTable(robot, "orders");

        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                table(robot).getItems().size() == 200);
        assertThat(robot.lookup("#data-range").queryAs(Label.class).getText())
                .isEqualTo("1–200 / 500");

        robot.clickOn("#data-next");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                robot.lookup("#data-range").queryAs(Label.class).getText()
                        .equals("201–400 / 500"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(table(robot).getItems()).hasSize(200);
        assertThat(((Number) table(robot).getItems().getFirst().get("id")).longValue())
                .isEqualTo(201L);

        openTable(robot, "users");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                table(robot).getItems().size() == 200);
        assertThat(robot.lookup(".adb-page__title").queryAs(Label.class).getText())
                .isEqualTo("users");
        assertThat(robot.lookup("#data-range").queryAs(Label.class).getText())
                .isEqualTo("1–200 / 200");
    }

    @Test
    void showsEmptyStateForEmptyTable(FxRobot robot) throws Exception {
        openTable(robot, "notes");

        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                robot.lookup("#data-range").queryAs(Label.class).getText().equals("0 行"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table(robot).getItems()).isEmpty();
        assertThat(robot.lookup("#data-page").queryAs(Label.class).getText())
                .isEqualTo("第 0 / 0 页");
        assertThat(robot.lookup(".adb-grid .placeholder .label").queryAs(Label.class).getText())
                .isEqualTo("没有数据");
    }

    /** 某个标签里的脏标记。 */
    private static Label dirtyMarkerOf(FxRobot robot, String tabId) {
        return robot.lookup("#" + TabBar.nodeId(tabId) + " .adb-tab__dirty")
                .queryAs(Label.class);
    }

    private static void openTable(FxRobot robot, String name) {
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.TreeCell<?> cell = robot.lookup(node ->
                node instanceof javafx.scene.control.TreeCell<?> treeCell
                        && treeCell.getTreeItem() != null
                        && treeCell.getTreeItem().getValue() != null
                        && String.valueOf(treeCell.getTreeItem().getValue()).contains(name))
                .queryAs(javafx.scene.control.TreeCell.class);
        robot.doubleClickOn(cell);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @SuppressWarnings("unchecked")
    private static TableView<java.util.Map<String, Object>> table(FxRobot robot) {
        return (TableView<java.util.Map<String, Object>>) robot.lookup("#data-table")
                .queryAs(TableView.class);
    }

    @Test
    void opensQueryPageRunsSqlAndShowsResultTab(FxRobot robot) throws Exception {
        robot.clickOn("#feature-new-query");
        WaitForAsyncUtils.waitForFxEvents();

        CodeArea editor = robot.lookup("#query-editor").queryAs(CodeArea.class);
        assertThat(editor).isNotNull();
        WaitForAsyncUtils.waitForAsyncFx(5_000,
                () -> editor.replaceText("SELECT 42 AS answer;"));

        robot.clickOn("#query-run");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                robot.lookup("#query-results").queryAs(TabPane.class).getTabs().size() == 1);
        WaitForAsyncUtils.waitForFxEvents();

        TabPane results = robot.lookup("#query-results").queryAs(TabPane.class);
        assertThat(results.getTabs().getFirst().getText()).contains("结果 1");
        assertThat(robot.lookup("#query-status").queryAs(Label.class).getText())
                .contains("执行完成");
        assertThat(robot.lookup("#query-risk").queryAs(Label.class).getText())
                .isEqualTo("只读");

        QueryPage query = robot.lookup(".adb-query-page").queryAs(QueryPage.class);
        WaitForAsyncUtils.waitForAsyncFx(5_000, query::markClean);
    }

    @Test
    void commandPaletteOpensAndFilters(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn(".adb-search-trigger");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup(".adb-palette-overlay").queryAs(javafx.scene.layout.StackPane.class).isVisible()).isTrue();

        robot.write("theme");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup(".adb-palette__label").queryAll()).isNotEmpty();

        robot.push(javafx.scene.input.KeyCode.ESCAPE);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup(".adb-palette-overlay").queryAs(javafx.scene.layout.StackPane.class).isVisible()).isFalse();
    }

    @Test
    void editMenuFollowsActivePageAndDensitySelectionTracksAction(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();

        MenuItem editCopy = menuItem(robot, "edit.copy");
        RadioMenuItem densityNormal = radioItem(robot, "density-normal");

        // 欢迎页：复制没有目标，禁用；密度默认标准且已勾选。
        assertThat(editCopy.isDisable()).isTrue();
        assertThat(densityNormal.isSelected()).isTrue();

        openTable(robot, "users");
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                robot.lookup(".adb-grid-page").tryQuery().isPresent());

        // 数据页：复制可用；撤销在编辑前禁用。
        assertThat(editCopy.isDisable()).isFalse();
        assertThat(menuItem(robot, "edit.undo").isDisable()).isTrue();

        javafx.scene.control.TableCell<?, ?> nameCell = robot.lookup(node ->
                node instanceof javafx.scene.control.TableCell<?, ?> cell
                        && "User 001".equals(cell.getText()))
                .queryAs(javafx.scene.control.TableCell.class);
        robot.doubleClickOn(nameCell);
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.TextField editor = robot.lookup(".adb-grid__editor")
                .queryAs(javafx.scene.control.TextField.class);
        robot.clickOn(editor);
        robot.write("Renamed User");
        robot.push(javafx.scene.input.KeyCode.ENTER);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                !menuItem(robot, "edit.undo").isDisable());

        // 密度切到紧凑：勾选态跟着动作走（MenuBarFactory 用 bindBidirectional）。
        // 菜单项不是 Node，不能 clickOn；直接 fire 与真实点击等价。
        WaitForAsyncUtils.asyncFx(radioItem(robot, "density-compact")::fire).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(radioItem(robot, "density-compact").isSelected()).isTrue();
        assertThat(densityNormal.isSelected()).isFalse();
        assertThat(robot.lookup(".app-shell").queryAs(AppShell.class)
                .getStyleClass().contains("density-compact")).isTrue();
    }

    private static MenuItem menuItem(FxRobot robot, String id) {        return items(robot).stream()
                .filter(item -> id.equals(item.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static RadioMenuItem radioItem(FxRobot robot, String id) {
        MenuItem item = menuItem(robot, id);
        assertThat(item).isInstanceOf(RadioMenuItem.class);
        return (RadioMenuItem) item;
    }

    /** 菜单项不是 Node，TestFX 查不到；从 MenuBar（Node）反推。 */
    private static List<MenuItem> items(FxRobot robot) {
        MenuBar bar = robot.lookup(".menu-bar").queryAs(MenuBar.class);
        List<MenuItem> all = new ArrayList<>();
        for (Menu menu : bar.getMenus()) {
            all.addAll(menu.getItems());
        }
        return all;
    }

    @Test
    void toastDurationsComeInThreeTiers(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);

        // 1) 普通提示：1.8 秒（刷新对象走的就是这一档）
        WaitForAsyncUtils.asyncFx(() -> shell.actionRegistry().run("refresh")).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(shell.toastDelayMillis()).isEqualTo(1_800);

        // 2) 带「撤销」的：6 秒 —— 用户要读完消息再决定点不点
        WaitForAsyncUtils.asyncFx(() -> shell.pageFeedback()
                .toast("已保存 3 条变更 · 可撤销", () -> { })).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(shell.toastDelayMillis()).isEqualTo(6_000);

        // 3) 不可撤销结果：4 秒 —— 没有撤销按钮，但要留足阅读时间
        WaitForAsyncUtils.asyncFx(() -> shell.pageFeedback()
                .toastUnreversible("已保存 3 条变更（新增行无主键，不能反向撤销）")).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(shell.toastDelayMillis()).isEqualTo(4_000);
    }

    /**
     * 4.5-23 复现门禁：主题热切换后 {@code scene.snapshot()} 不得返回空白帧。
     *
     * <p>真实窗口里点「切换主题」后截图曾出现过「场景图完好、CSS 已挂载、
     * 控件可交互，但快照像素全是背景色」的暗显示缺陷。截图链路已用
     * {@code ADB_THEME_FORCE} 规避；这里把现象固化成测试，修好后它就是回归门禁。
     */
    @Test
    void themeHotSwitchKeepsSnapshotPixels(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);

        double before = nonBackgroundRatio(robot);
        assertThat(before).as("初始快照就必须有内容").isGreaterThan(0.01);

        robot.clickOn("#theme-toggle-button");
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        double after = nonBackgroundRatio(robot);
        assertThat(after)
                .as("主题热切换后快照不得变空白（当前 before=%.3f after=%.3f）", before, after)
                .isGreaterThan(0.01);
    }

    /** 快照里与四角背景色差异超过阈值的像素占比。 */
    private static double nonBackgroundRatio(FxRobot robot) throws Exception {
        javafx.scene.image.WritableImage shot = WaitForAsyncUtils
                .asyncFx(() -> robot.window(0).getScene().snapshot(null)).get();
        int width = shot.getWidth() > 0 ? (int) shot.getWidth() : 1;
        int height = shot.getHeight() > 0 ? (int) shot.getHeight() : 1;
        javafx.scene.image.PixelReader reader = shot.getPixelReader();
        javafx.scene.paint.Color background = reader.getColor(0, 0);
        long total = 0;
        long differing = 0;
        for (int y = 0; y < height; y += 4) {
            for (int x = 0; x < width; x += 4) {
                javafx.scene.paint.Color color = reader.getColor(x, y);
                total++;
                if (Math.abs(color.getRed() - background.getRed())
                        + Math.abs(color.getGreen() - background.getGreen())
                        + Math.abs(color.getBlue() - background.getBlue()) > 0.05) {
                    differing++;
                }
            }
        }
        return total == 0 ? 0 : (double) differing / total;
    }
}
