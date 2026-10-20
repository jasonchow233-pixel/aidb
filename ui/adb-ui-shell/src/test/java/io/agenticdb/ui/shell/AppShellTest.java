package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
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
import java.util.Comparator;
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
    }

    @AfterAll
    static void cleanDemoDatabase() throws IOException {
        System.clearProperty("adb.demo.db");
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
        assertThat(robot.lookup(".adb-welcome").tryQuery()).isPresent();

        javafx.scene.control.MenuBar menuBar =
                robot.lookup(".menu-bar").queryAs(javafx.scene.control.MenuBar.class);
        assertThat(menuBar.getMenus()).hasSize(7);

        // 无标签时标签栏按规范隐藏；打开标签后由下一个测试验证它出现。
        assertThat(robot.lookup(".adb-tabbar").queryAs(TabBar.class).isVisible()).isFalse();
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
        assertThat(robot.lookup(".adb-tab").tryQuery()).isEmpty();
        assertThat(robot.lookup(".adb-welcome").tryQuery()).isPresent();
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
        assertThat(robot.lookup(".adb-tab__dirty").queryAs(Label.class).isVisible()).isTrue();

        robot.clickOn("#data-undo");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> page.dirtyCount() == 0);
        assertThat(robot.lookup(".adb-tab__dirty").queryAs(Label.class).isVisible()).isFalse();

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
}
