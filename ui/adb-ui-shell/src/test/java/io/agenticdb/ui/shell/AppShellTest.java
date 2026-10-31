package io.agenticdb.ui.shell;

import io.agenticdb.introspect.TableMeta;
import io.agenticdb.ui.App;
import io.agenticdb.ui.I18n;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
        // ⭐ 走 SettingsStore 而不是直接拿 Preferences.userNodeForPackage：
        //    后者是**开发机真实偏好**，往里写等于把测试状态灌进用户的 AIDB
        //    （而且写完没人清理）。走 SettingsStore 就自动落到本测试的隔离节点里。
        SettingsStore preferences = new SettingsStore();
        preferences.set("ui.theme", "light");
        preferences.set("ui.density", "normal");
        preferences.setBoolean("ui.sidebar.collapsed", false);
        preferences.setInt("ui.sidebar.width", 240);
        // ⭐ §6：详情面板的显隐是**持久化**的偏好。不显式关掉的话，
        //    上一次运行留下的 true 会让「默认关闭」这条断言随机失败。
        preferences.setBoolean("ui.details.visible", false);
        // ⭐ 固定窗口尺寸：窗口大小是**记住**的（本地偏好），
        //    不固定的话测试会受上一次运行留下的尺寸影响 ——
        //    窗口比屏幕大时，底部的分页条会落在可视区之外，点击根本落不到。
        preferences.setInt("ui.window.width", 1280);
        preferences.setInt("ui.window.height", 800);
        preferences.setInt("ui.window.x", 0);
        preferences.setInt("ui.window.y", 0);
        preferences.set("ui.tableList.view", "list");
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
        assertThat(robot.lookup(".adb-appbar").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-sidebar").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-workspace").tryQuery()).isPresent();
        assertThat(robot.lookup(".adb-statusbar").tryQuery()).isPresent();
        // ⭐ N33：启动时停在**常驻的「对象」标签**，它托管的就是对象清单页
        //    （Navicat 同形），不再是空白欢迎页。
        assertThat(robot.lookup(".adb-table-list-page").tryQuery()).isPresent();

        javafx.scene.control.MenuBar menuBar =
                robot.lookup(".menu-bar").queryAs(javafx.scene.control.MenuBar.class);
        // ⭐ R34 N2：顶级组改成 Navicat 实拍的那一套。常驻 9 组 + 一个只在表上下文里
        //    显出来的 Table 菜单（Navicat 的查询页实拍就没有 Table，所以停在对象首页时
        //    可见的是 9 组，不是 10 组）。
        //    旧的「数据库 / 对象」两组是 AIDB 自有的，Navicat 没有 —— 已撤。
        assertThat(menuBar.getMenus())
                .as("菜单组必须**按顺序**等于 Navicat 的那一套（含隐藏的 Table）")
                .extracting(javafx.scene.control.Menu::getText)
                .containsExactly(
                        I18n.t("menu.file"), I18n.t("menu.edit"), I18n.t("menu.view"),
                        I18n.t("menu.table"), I18n.t("menu.query"), I18n.t("menu.format"),
                        I18n.t("menu.favorites"), I18n.t("menu.tools"),
                        I18n.t("menu.window"), I18n.t("menu.help"));
        assertThat(menuBar.getMenus().stream().filter(javafx.scene.control.Menu::isVisible)
                .map(javafx.scene.control.Menu::getText))
                .as("对象首页不是「某一张表」，Table 菜单此刻必须藏着")
                .doesNotContain(I18n.t("menu.table"));

        // ⭐ S5：标签栏**永远可见** —— 第一个标签是常驻的「对象」标签，
        //    它是浏览的起点（Navicat 同形）。
        assertThat(robot.lookup(".adb-tabbar").queryAs(TabBar.class).isVisible()).isTrue();
        assertThat(robot.lookup("#" + TabBar.nodeId("object-home")).tryQuery())
                .as("常驻的「对象」标签")
                .isPresent();
    }

    /**
     * ⭐ 2026-10-30 R36 顶区重做的门禁：**页签行只放页签，工具件回到 Ribbon 右端**。
     *
     * <p>顶区 = 菜单 24 + Ribbon 91 + 页签行 25 = 140px，逐行对回 Navicat 17 实拍
     *（真源 §1.1）。10-28 那版把工具条塞进标签行的精简被撤销 —— 用户裁定完全照
     * Navicat：12 格磁贴 + 右端工具件，标签行回归纯页签（N7）。
     *
     * <p>钉三件事：{@code #adb-chrome-row} 里只有页签栏；工具件住在 Ribbon 行里；
     * Ribbon 是令牌高度 91。任一条回退，「顶部栏目乱」或「页签行混进全局控件」就复活了。
     */
    @Test
    void theTabRowHoldsOnlyTabsAndTheUtilityLivesInTheRibbon(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.layout.HBox row =
                robot.lookup("#adb-chrome-row").queryAs(javafx.scene.layout.HBox.class);
        assertThat(row.getChildren())
                .as("页签行只放页签栏（R36 N7）")
                .singleElement()
                .isSameAs(robot.lookup(".adb-tabbar").query());
        assertThat(row.getChildren().stream()
                .anyMatch(n -> "appbar-utility".equals(n.getId())))
                .as("全局工具条不许再回到页签行")
                .isFalse();

        javafx.scene.Node ribbonRow = robot.lookup("#appbar-utility").query().getParent();
        assertThat(ribbonRow.getStyleClass()).as("工具件住在 Ribbon 行右端")
                .contains("adb-appbar__row");

        javafx.scene.layout.Region appBar =
                robot.lookup(".adb-appbar").queryAs(javafx.scene.layout.Region.class);
        assertThat(appBar.getHeight())
                .as("Ribbon 91px（Navicat 实拍：菜单 23 + 带子 91 + 页签 25 = 顶区 140，我们菜单取 24）")
                .isEqualTo(io.agenticdb.ui.Tokens.H_APPBAR_LIGHT)
                .isEqualTo(91);

        assertThat(robot.lookup("#appbar-brand-name").tryQuery())
                .as("AIDB 品牌块已随顶区精简删除（品牌在窗口标题与任务栏图标）")
                .isEmpty();
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

    /**
     * ⭐ UX-5：启动时同步连上的演示库，侧栏那条连接要挂着「在线」点。
     *
     * <p>存活判据只能来自 {@code SessionManager} —— 侧栏没有会话。
     * 把 {@code sideBar.setConnectionLiveness(...)} 那行接线删掉，这条立刻红。
     */
    @Test
    void theSidebarMarksTheLiveConnection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup(node -> node instanceof javafx.scene.shape.SVGPath path
                && path.getStyleClass().contains("adb-tree__dot--online")).tryQuery())
                .as("一个点都没有 = 没接线：「这个连接活着吗」又退化成「双击试一下才知道」")
                .isPresent();
    }

    /**
     * ⭐ UX-5：侧栏宽度是「令牌给默认、偏好记实际」。
     *
     * <p>{@code @Start} 往偏好里写了 240，所以这里看到的必须是 240 而不是令牌的 260 ——
     * 把 {@code AppShell} 读偏好那一步删掉，这条就红（默认值会顶上来）。
     */
    @Test
    void thePersistedSidebarWidthWinsOverTheTokenDefault(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(new SettingsStore().getInt("ui.sidebar.width", -1)).isEqualTo(240);
        assertThat(robot.lookup(".adb-sidebar").queryAs(SideBar.class).getPrefWidth())
                .as("上次留下的宽度必须赢过 w-sidebar 默认值")
                .isEqualTo(240.0);
    }

    /**
     * ⭐ UX-5：双击拖拽条 = 回到令牌默认，且**必须写回偏好**。
     *
     * <p>只改界面不写回的话，用户下次启动又看到旧宽度，等于重置没生效。
     */
    @Test
    void doubleClickingTheResizerResetsToTheDefaultAndPersists(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        // ⚠️ 用 doubleClickOn 而不是 clickOn(target, PRIMARY, PRIMARY)：
        //    后者在这个 headless 环境下不触发 getClickCount() == 2 的分支（实测宽度没变）。
        robot.doubleClickOn(".adb-resizer");
        WaitForAsyncUtils.waitForFxEvents();

        int expected = io.agenticdb.ui.Tokens.W_SIDEBAR_LIGHT;
        assertThat(robot.lookup(".adb-sidebar").queryAs(SideBar.class).getPrefWidth())
                .isEqualTo((double) expected);
        assertThat(new SettingsStore().getInt("ui.sidebar.width", -1))
                .as("重置后的宽度必须写回偏好")
                .isEqualTo(expected);
    }

    @Test
    void themeToggles(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn("#appbar-theme");
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
        assertThat(robot.lookup(".adb-table-list-page").tryQuery())
                .as("对象清单页（常驻「对象」标签的内容）")
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
                .isEqualTo(I18n.t("grid.pager.pageOf", 1, 1));
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
    void windowSizeIsClampedToTheScreen() throws Exception {
        // ⭐ 真实缺陷：窗口尺寸是**记住**的。换个分辨率更小的屏幕（或拔掉外接屏），
        //    窗口会比屏幕还大 —— 底部的分页条 / 状态栏被顶到可视区之外，
        //    用户点不到「下一页」，也看不到状态栏（实测分页条落在 y=800..826，
        //    而窗口只有 800 高）。
        javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
        javafx.geometry.Rectangle2D bounds = screen == null ? null : screen.getVisualBounds();
        org.junit.jupiter.api.Assumptions.assumeTrue(bounds != null,
                "无头环境拿不到屏幕信息，跳过");

        // ⭐ 往**本测试的隔离节点**里写，不再直接动 Preferences.userNodeForPackage
        //    （那是开发机的真实偏好；测试跑完由 IsolatedPreferencesExtension 整节点删掉，
        //    所以这里也不用再手工还原）。
        SettingsStore preferences = new SettingsStore();
        preferences.setInt("ui.window.width", (int) (bounds.getWidth() * 2));
        preferences.setInt("ui.window.height", (int) (bounds.getHeight() * 2));

        java.util.concurrent.atomic.AtomicReference<javafx.stage.Stage> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.testfx.util.WaitForAsyncUtils.asyncFx(() -> {
            javafx.stage.Stage stage = new javafx.stage.Stage();
            AppShell shell = new AppShell(new SettingsStore(),
                    new io.agenticdb.ui.shell.ThemeManager(new SettingsStore()));
            shell.restoreWindow(stage);
            holder.set(stage);
        }).get();
        javafx.stage.Stage stage = holder.get();
        assertThat(stage.getWidth())
                .as("窗口宽度不能超过屏幕宽度（否则底部控件点不到）")
                .isLessThanOrEqualTo(bounds.getWidth());
        assertThat(stage.getHeight())
                .as("窗口高度不能超过屏幕高度")
                .isLessThanOrEqualTo(bounds.getHeight());
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
                robot.lookup("#data-range").queryAs(Label.class).getText().equals(I18n.t("grid.pager.zeroRows")));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table(robot).getItems()).isEmpty();
        assertThat(robot.lookup("#data-page").queryAs(Label.class).getText())
                .isEqualTo(I18n.t("grid.pager.pageZero"));
        // ⭐ 空表不再是「没有数据」四个字 —— 要有图标 + 说明 + 一个真能点的出口。
        assertThat(robot.lookup(".adb-grid .placeholder .adb-empty__title")
                .queryAs(Label.class).getText())
                .isEqualTo(I18n.t("grid.empty.title"));
        assertThat(robot.lookup(".adb-grid .placeholder .button")
                .queryAs(Button.class).getText())
                .as("空表必须给用户一个出口，而不只是陈述事实")
                .isEqualTo(I18n.t("grid.empty.action"));
    }

    /**
     * ⭐ U1：SQLite 连接**没有** catalog 层 —— 表分组直接挂在连接节点下。
     *
     * <p>MySQL 这类「切库要重连」的方言才会多出库一层（见 SideBarTreeTest）。
     * 这里守的是反面：别把演示库（SQLite）的形状也改了 ——
     * 一旦 SQLite 也报出 catalog，用户就会在树里看到一层点不开的东西。
     */
    @Test
    void sqliteConnectionHasNoCatalogLayer(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        String tree = onFx(() -> {
            StringBuilder text = new StringBuilder();
            AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);
            appendNodes(sidebarTreeRoot(shell), text);
            return text.toString();
        });

        assertThat(tree)
                .as("演示库的表要出现在树里")
                .contains("name=users");
        assertThat(tree)
                .as("SQLite 没有 catalog 概念 —— 树里不许出现库节点")
                .doesNotContain("kind=CATALOG");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TreeItem sidebarTreeRoot(AppShell shell) {
        TreeView view = (TreeView) shell.sideBar().lookup(".adb-object-tree");
        return (TreeItem) view.getRoot();
    }

    private static void appendNodes(TreeItem item, StringBuilder text) {
        if (item == null) {
            return;
        }
        text.append(item.getValue()).append(" | ");
        for (Object child : item.getChildren()) {
            appendNodes((TreeItem) child, text);
        }
    }

    /** 某个标签里的脏标记。 */
    private static Label dirtyMarkerOf(FxRobot robot, String tabId) {
        return robot.lookup("#" + TabBar.nodeId(tabId) + " .adb-tab__dirty")
                .queryAs(Label.class);
    }

    /**
     * ⭐ R34 N2：窗口标题必须是 Navicat 式 {@code <连接> [@库] (<对象>) - <页型> - <产品>}。
     *
     * <p>钉的不是措辞而是**信息在不在、脏东西有没有**：多开几个窗口时任务栏要靠它分清
     * 哪个连的是生产库。SQLite 的 database() 是整个 .db 文件路径，直接拼会把临时目录
     * 摊到标题栏上（本仓库第一版就是这么写的，被这条门禁抓到）—— 所以除了"该有的在"，
     * 还要断言"不该来的没来"。
     */
    @Test
    void theWindowTitleCarriesTheConnectionAndTheProduct(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        openTable(robot, "users");

        Stage stage = robot.listWindows().stream()
                .filter(w -> w instanceof Stage).map(w -> (Stage) w).findFirst().orElseThrow();
        String title = stage.getTitle();
        assertThat(title)
                .as("标题必须说清连的是哪个库、停在哪个页型、产品是什么")
                .contains("demo.db")
                .contains("(users)")
                .contains(I18n.t("window.pageType.grid"))
                .endsWith(I18n.t("window.product"));
        assertThat(title)
                .as("SQLite 的文件路径不进标题 —— 那是临时目录，摊在标题栏上既长又没信息量")
                .doesNotContain(".db @")
                .doesNotContain("Temp")
                .doesNotContain("@ ")
                .doesNotContain("()");
    }

    /**
     * ⭐ R34 N2 的另一半：{@code Table} 菜单必须**真的会显出来**。
     *
     * <p>只测"默认隐藏"是不够的 —— 一个坏掉的、永远不显示的菜单也能通过它。
     * Navicat 的 Table 菜单是上下文菜单，所以这里打开一张表，断言它出现，
     * 并且里面装的是表级动作而不是别的组。
     */
    @Test
    void theTableMenuAppearsOnlyWhenATableIsActive(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.MenuBar menuBar =
                robot.lookup(".menu-bar").queryAs(javafx.scene.control.MenuBar.class);
        javafx.scene.control.Menu table = menuBar.getMenus().stream()
                .filter(menu -> I18n.t("menu.table").equals(menu.getText()))
                .findFirst().orElseThrow();
        assertThat(table.isVisible())
                .as("停在对象首页时不该有 Table 菜单")
                .isFalse();

        openTable(robot, "users");

        assertThat(table.isVisible())
                .as("打开一张表（GRID 上下文）之后 Table 菜单必须出现")
                .isTrue();
        assertThat(table.getItems().stream()
                .filter(item -> item instanceof javafx.scene.control.MenuItem
                        && item.getText() != null && !item.getText().isEmpty())
                .map(javafx.scene.control.MenuItem::getText))
                .as("里面是表级动作")
                .contains(I18n.t("action.object.open-data"),
                        I18n.t("action.object.new-table"),
                        I18n.t("action.object.copy-select"));
    }

    /**
     * 双击对象树里叫这个名字的表 / 视图，并<b>等它真的打开</b>。
     *
     * <p>两个坑都踩过，写在这里免得有人再改回去：
     * <ol>
     *   <li>用 {@code contains(name)} 匹配会同时命中 {@code v_active_users} 和
     *       {@code trg_users_audit} —— 取第一个的话，命中触发器时压根不会打开页面，
     *       失败信息却是「标题还是上一张表」，看上去像分页 bug，其实是选错了节点。</li>
     *   <li>双击之后直接断言标题，偶发读到<b>上一个标签</b>的标题（新标签还没渲染完），
     *       表现是整条用例时红时绿。所以这里等标题真的变成目标名字再返回。</li>
     * </ol>
     */
    private static void openTable(FxRobot robot, String name) {
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.TreeCell<?> cell =
                robot.lookup(node -> isTableOrViewCell(node, name))
                        .queryAs(javafx.scene.control.TreeCell.class);
        robot.doubleClickOn(cell);
        try {
            WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () ->
                    robot.lookup(".adb-page__title").queryAll().stream()
                            .anyMatch(node -> node instanceof Label title
                                    && name.equals(title.getText())));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("双击对象树里的 " + name + " 之后，8 秒内没有打开叫 "
                    + name + " 的页面", e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 树上「名字正好是 name」的表 / 视图单元格。
     *
     * <p>{@code DemoNode} 是侧栏的私有记录，测试里取不到 {@code name()}，
     * 只能从 {@code toString()} 认 —— 所以按 {@code name=xxx,} 整字段匹配，
     * 不是 {@code contains(name)}。
     */
    @SuppressWarnings("rawtypes")
    private static boolean isTableOrViewCell(javafx.scene.Node node, String name) {
        if (!(node instanceof javafx.scene.control.TreeCell cell)
                || cell.getTreeItem() == null
                || cell.getTreeItem().getValue() == null) {
            return false;
        }
        String text = String.valueOf(cell.getTreeItem().getValue());
        return text.contains("name=" + name + ",")
                && (text.contains("kind=TABLE") || text.contains("kind=VIEW"));
    }

    @SuppressWarnings("unchecked")
    private static TableView<java.util.Map<String, Object>> table(FxRobot robot) {
        return (TableView<java.util.Map<String, Object>>) robot.lookup("#data-table")
                .queryAs(TableView.class);
    }

    @Test
    void opensQueryPageRunsSqlAndShowsResultTab(FxRobot robot) throws Exception {
        robot.clickOn("#ribbon-new-query");
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
        assertThat(results.getTabs().getFirst().getText()).contains(I18n.t("query.resultTab", 1));
        assertThat(robot.lookup("#query-status").queryAs(Label.class).getText())
                .contains(I18n.t("query.done"));
        assertThat(robot.lookup("#query-risk").queryAs(Label.class).getText())
                .isEqualTo(I18n.t("status.readOnly"));

        QueryPage query = robot.lookup(".adb-query-page").queryAs(QueryPage.class);
        WaitForAsyncUtils.waitForAsyncFx(5_000, query::markClean);
    }

    @Test
    void commandPaletteOpensAndFilters(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn(".adb-appbar__search");
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

        // 欢迎页：复制没有目标，禁用。
        assertThat(editCopy.isDisable()).isTrue();

        // ⭐ 自己把密度扳到「标准」再往下走，不依赖 @Start 写进偏好的那个值。
        //    这条测的是「勾选态跟着动作走」，不该顺手把一次状态也当成被测对象 ——
        //    那样它会在别的类改了启动流程时莫名其妙地红。
        WaitForAsyncUtils.asyncFx(densityNormal::fire).get();
        WaitForAsyncUtils.waitForFxEvents();
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

    /**
     * ⭐ UX-0：ADB_DENSITY 优先于偏好，且**不写回**偏好。
     *
     * <p>截图脚本靠它把行高固定成 compact；如果写回偏好，
     * 下一次不带环境变量启动就会被这次截图“污染”。
     */
    @Test
    void forcedDensityWinsAndDoesNotWriteBackToPreferences() throws Exception {
        String oldNode = System.getProperty("adb.prefs.node");
        String oldDensity = System.getProperty("adb.density");
        String node = "io/agenticdb/tests/density-" + System.nanoTime();
        Preferences testNode = Preferences.userRoot().node(node);
        try {
            System.setProperty("adb.prefs.node", node);
            SettingsStore store = new SettingsStore();
            store.set("ui.density", "comfort");
            System.setProperty("adb.density", "compact");

            AtomicReference<AppShell> holder = new AtomicReference<>();
            WaitForAsyncUtils.asyncFx(() -> holder.set(
                    new AppShell(store, new ThemeManager(new SettingsStore())))).get();
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(holder.get().getStyleClass())
                    .as("强制密度必须生效")
                    .contains("density-compact");
            assertThat(store.get("ui.density", ""))
                    .as("ADB_DENSITY 只影响本次运行，不能覆盖用户偏好")
                    .isEqualTo("comfort");
        } finally {
            if (oldNode == null) {
                System.clearProperty("adb.prefs.node");
            } else {
                System.setProperty("adb.prefs.node", oldNode);
            }
            if (oldDensity == null) {
                System.clearProperty("adb.density");
            } else {
                System.setProperty("adb.density", oldDensity);
            }
            try {
                testNode.removeNode();
            } catch (Exception ignored) {
                // 测试偏好节点清理失败不影响结论。
            }
        }
    }

    /**
     * ⭐ 第二十一轮改判：没有存过偏好时默认是 **normal（28px）**，不再是 compact。
     *
     * <p>原来的裁定（UX-2 那轮）照的是 Navicat「默认偏紧」，理由是 800 高窗口
     * 28px 只能看 20 行。无边框化之后这条不再成立：线撤了，行与行之间
     * 本来就靠留白分开，24px 的行高在没线之后读起来比有线时更挤 ——
     * 所以默认回到 28，**密度交给用户**（视图菜单四档都在），dense/compact 才是主动选择。
     */
    @Test
    void densityDefaultsToNormalWhenNoPreferenceIsStored() throws Exception {
        String oldNode = System.getProperty("adb.prefs.node");
        String oldDensity = System.getProperty("adb.density");
        String node = "io/agenticdb/tests/density-default-" + System.nanoTime();
        Preferences testNode = Preferences.userRoot().node(node.replace('.', '/'));
        try {
            System.setProperty("adb.prefs.node", node);
            System.clearProperty("adb.density");
            SettingsStore store = new SettingsStore();

            AtomicReference<AppShell> holder = new AtomicReference<>();
            WaitForAsyncUtils.asyncFx(() -> holder.set(
                    new AppShell(store, new ThemeManager(new SettingsStore())))).get();
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(holder.get().getStyleClass())
                    .as("未存偏好时默认密度必须是 normal")
                    .contains("density-normal")
                    .doesNotContain("density-dense", "density-compact", "density-comfort");
            assertThat(store.get("ui.density", ""))
                    .as("首次启动要把默认密度落到偏好里，下次启动不变")
                    .isEqualTo("normal");
        } finally {
            if (oldNode == null) {
                System.clearProperty("adb.prefs.node");
            } else {
                System.setProperty("adb.prefs.node", oldNode);
            }
            if (oldDensity == null) {
                System.clearProperty("adb.density");
            } else {
                System.setProperty("adb.density", oldDensity);
            }
            try {
                testNode.removeNode();
            } catch (Exception ignored) {
                // 测试偏好节点清理失败不影响结论。
            }
        }
    }

    private static MenuItem menuItem(FxRobot robot, String id) {
        return items(robot).stream()
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

        robot.clickOn("#appbar-theme");
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitForFxEvents();

        double after = nonBackgroundRatio(robot);
        assertThat(after)
                .as("主题热切换后快照不得变空白（当前 before=%.3f after=%.3f）", before, after)
                .isGreaterThan(0.01);
    }

    // ─────────────────── N1–N5 表清单动作接线 ───────────────────

    @Test
    void tableListActionsAreRegisteredAndWired(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);

        assertThat(onFx(() -> List.of(
                "table-list.new", "table-list.open", "table-list.design",
                "table-list.rename", "table-list.truncate", "table-list.drop",
                "table-list.refresh", "table-list.select-all",
                "table-list.view-list", "table-list.view-grid",
                "table-list.new-ai", "table-list.import", "table-list.export")
                .stream().allMatch(id -> shell.actionRegistry().find(id).isPresent())))
                .as("表清单动作必须全部进注册表（工具条 / 右键 / 命令面板同一份）")
                .isTrue();

        onFxRun(() -> shell.actionRegistry().run("object.tables"));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> onFx(() -> robot.lookup("#table-row-users").tryQuery().isPresent()));

        assertThat(onFx(() -> shell.lookup("#table-list"))).isNotNull();
    }

    @Test
    void tableListSelectionDrivesTheStatusBarAndViewToggle(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);

        onFxRun(() -> shell.actionRegistry().run("object.tables"));
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> onFx(() -> robot.lookup("#table-row-users").tryQuery().isPresent()));

        onFxRun(() -> {
            @SuppressWarnings("unchecked")
            TableView<TableMeta> table = (TableView<TableMeta>) shell.lookup("#table-list");
            int index = -1;
            for (int i = 0; i < table.getItems().size(); i++) {
                if ("users".equals(table.getItems().get(i).name())) {
                    index = i;
                    break;
                }
            }
            table.getSelectionModel().select(index);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(onFx(() -> shell.statusBar().selectionText()))
                .as("N5：选中表后状态栏要同时报出表名与行数（不照抄实现的拼接，否则退化成同义反复）")
                .contains("users")
                .contains("200")
                .isNotEqualTo(I18n.t("status.noneSelected"));

        onFxRun(() -> shell.actionRegistry().run("table-list.view-grid"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> shell.lookup("#table-card-users")))
                .as("N4：视图切换动作要真的切到卡片")
                .isNotNull();
        assertThat(onFx(() -> shell.lookup("#table-list").isVisible()))
                .as("切到网格后表格应隐藏")
                .isFalse();

        onFxRun(() -> shell.actionRegistry().run("table-list.view-list"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> shell.lookup("#table-list").isVisible())).isTrue();
    }

    // ─────────────────────── §6 右侧对象详情面板 ───────────────────────

    /**
     * 详情面板**默认关闭**，且 ⌥⌘D / 动作能真的把它放出来。
     *
     * <p>守的是规格 §6.3：它是有用的补充，不是该常驻的第三栏。
     * 默认开着的第三栏会在 1280×720 上吃掉 300px 主工作区 ——
     * 而绝大多数时候用户并不需要它。
     */
    @Test
    void detailsPanelIsHiddenByDefaultAndTogglesOn(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);

        assertThat(onFx(() -> Boolean.valueOf(
                shell.lookup(".adb-details-panel").isVisible()))).isFalse();

        onFxRun(() -> shell.actionRegistry().run("details.toggle"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> Boolean.valueOf(
                shell.lookup(".adb-details-panel").isVisible()))).isTrue();

        onFxRun(() -> shell.actionRegistry().run("details.toggle"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> Boolean.valueOf(
                shell.lookup(".adb-details-panel").isVisible()))).isFalse();
    }

    /**
     * ⭐ 1280 宽 + 详情面板：三栏最小宽度超过窗口时，主区必须让位。
     *
     * <p>以前 {@code body} / {@code main} 的 minWidth 跟着内容走，总宽超过窗口，
     * {@code StackPane} 只能把根节点居中 —— 左侧菜单与侧栏被挤出场景，
     * 快照里直接从「视图」开始（`p2-details` 实测）。
     */
    /** ⭐ P7：启动打点必须真的接在启动链路上，而不是建了一个没人用的对象。 */
    @Test
    void startupTraceCoversChromeDemoAndFirstScreen(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);
        io.agenticdb.ui.StartupTrace trace = shell.startupTrace();

        assertThat(trace.marks())
                .extracting(io.agenticdb.ui.StartupTrace.Mark::name)
                .as("启动链路：构造 → chrome → 演示库 → afterShown")
                .containsSubsequence("shell.construct", "shell.chrome", "shell.demo",
                        "shell.after-shown");
        assertThat(trace.firstScreenMillis())
                .as("首屏耗时（进程启动 → 首屏）必须有值")
                .isPositive();
        assertThat(trace.summary()).contains("shell.demo=").contains("first-screen=");
    }

    @Test
    void detailsPanelKeepsChromeInsideTheSceneAt1280(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);
        onFxRun(() -> {
            Stage stage = (Stage) shell.getScene().getWindow();
            stage.setWidth(1280);
            stage.setHeight(800);
        });
        onFxRun(() -> shell.actionRegistry().run("details.toggle"));
        WaitForAsyncUtils.waitForFxEvents();
        onFxRun(shell::applyCss);
        onFxRun(shell::layout);

        assertThat(onFx(() -> shell.lookup(".adb-details-panel").isVisible())).isTrue();
        double sceneWidth = onFx(() -> shell.getScene().getWidth());
        assertThat(sceneWidth).as("测试必须真的落在 1280 宽的场景里").isEqualTo(1280.0);
        // ⭐ 量的是**布局边界**，不是 getBoundsInParent()。
        // 第二十一轮无边框语言给顶栏挂了一层弥散投影（elevation 一级），
        // 而 JavaFX 的 boundsInParent **包含效果光晕** —— 实测顶栏左右各外扩 6px，
        // 一路传到根节点变成 minX = -6。光晕出界是设计要的，不是 chrome 被挤出场景，
        // 所以这条门禁必须换成不受 effect 污染的 localToParent(layoutBounds)。
        double left = onFx(() -> shell.lookup(".adb-root")
                .localToParent(((javafx.scene.layout.Region) shell.lookup(".adb-root"))
                        .getLayoutBounds()).getMinX());
        double right = onFx(() -> shell.lookup(".adb-root")
                .localToParent(((javafx.scene.layout.Region) shell.lookup(".adb-root"))
                        .getLayoutBounds()).getMaxX());
        assertThat(left).as("根节点左边缘不能被推出场景").isGreaterThanOrEqualTo(-0.5);
        assertThat(right).as("根节点右边缘不能超出场景")
                .isLessThanOrEqualTo(sceneWidth + 0.5);
        // 这里同时守机制：主区与 body 必须允许收缩（实拍由 p2-details 核对）。
        assertThat(onFx(() -> shell.lookup(".adb-main").minWidth(-1)))
                .as("主区最小宽度必须为 0，否则 1280 宽 + 详情面板会把左侧 chrome 挤出场景")
                .isZero();
        assertThat(onFx(() -> shell.lookup(".adb-body").minWidth(-1)))
                .as("body 最小宽度必须为 0").isZero();
    }

    /**
     * 树里选中一张表 → 右侧出现该表的**真实**概况。
     *
     * <p>这是整条接线（SideBar 上报 → AppShell 读元数据 → 面板渲染）
     * 唯一的端到端判据：任何一环断了，这里就只剩一个空面板。
     *
     * <p>⚠️ 选中走 {@code TreeView} 的选择模型，不用 {@code clickOn("users")}：
     * 后者会点到对象首页那张同名的表行上，选中的根本不是树节点。
     */
    @Test
    void selectingATableInTheTreeFillsTheDetailsPanel(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);
        onFxRun(() -> shell.actionRegistry().run("details.toggle"));
        WaitForAsyncUtils.waitForFxEvents();

        onFxRun(() -> selectTreeNode(shell, "users"));

        // ⭐ 元数据是异步读的（真实 SQLite 查询跑在自己的执行器上）——
        //    固定等两拍在慢机器上会假失败，改成轮询直到真的出现内容。
        List<String> items = awaitDetailsItems(shell);

        assertThat(onFx(() -> {
            javafx.scene.Node title = shell.lookup(".adb-details-panel__title");
            return title == null ? null : ((javafx.scene.control.Labeled) title).getText();
        })).isEqualTo("users");

        // 字段摘要必须是真列名 —— 演示库的 users 表有这些列。
        assertThat(items).isNotEmpty();
        assertThat(items).anyMatch(text -> text.startsWith("id"));
        assertThat(items).anyMatch(text -> text.startsWith("email"));
        // 不许出现占位 / 编造内容。
        assertThat(items).noneMatch(text -> text.contains("示例") || text.contains("TODO"));
    }

    /** 轮询等详情面板的字段摘要真的渲染出来（上限约 5 秒）。 */
    private static List<String> awaitDetailsItems(AppShell shell) {
        for (int attempt = 0; attempt < 50; attempt++) {
            WaitForAsyncUtils.waitForFxEvents();
            List<String> items = onFx(() -> shell.lookupAll(".adb-details-panel__list-item")
                    .stream()
                    .map(node -> ((javafx.scene.control.Labeled) node).getText())
                    .toList());
            if (!items.isEmpty()) {
                return items;
            }
        }
        return List.of();
    }

    /** 在对象树里按名字选中一个节点（DemoNode 是私有的，只能按 toString 找）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void selectTreeNode(AppShell shell, String nodeName) {
        TreeView view = (TreeView) shell.sideBar().lookup(".adb-object-tree");
        TreeItem target = findNode((TreeItem) view.getRoot(), nodeName);
        assertThat(target).as("对象树里要能找到 " + nodeName).isNotNull();
        view.getSelectionModel().select(target);
    }

    private static TreeItem findNode(TreeItem item, String nodeName) {
        if (item == null) {
            return null;
        }
        if (String.valueOf(item.getValue()).contains("name=" + nodeName)) {
            return item;
        }
        for (Object child : item.getChildren()) {
            TreeItem found = findNode((TreeItem) child, nodeName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void onFxRun(Runnable action) {
        javafx.application.Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        javafx.application.Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
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
