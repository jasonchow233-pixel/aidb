package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX-2 框架尺寸门禁。
 *
 * <p>数据区能看见多少行，由「窗口高度 − 各条框」决定。这里把每条框的
 * 高度钉成预算（Navicat 对标的值），并直接在 765px 高的窗口里数可见行 ——
 * 只断言 CSS 字符串不够，真正的验收是「一眼能看到 ≥ 22 行」。
 */
@ExtendWith(ApplicationExtension.class)
class ChromeMetricsTest {

    /** 截图口径的窗口（p2-users 1280×800 → 验收时压到 765 高）。 */
    private static final int WINDOW_WIDTH = 1268;
    private static final int WINDOW_HEIGHT = 765;

    private static Path demoDirectory;
    private static String prefsNode;
    private static String oldDemoDb;
    private static String oldConnectionsFile;
    private static String oldPrefsNode;
    private static String oldDensity;

    @Start
    void start(Stage stage) throws IOException {
        demoDirectory = Files.createTempDirectory("adb-chrome-test-");
        prefsNode = "io/agenticdb/tests/chrome-" + System.nanoTime();

        oldDemoDb = System.getProperty("adb.demo.db");
        oldConnectionsFile = System.getProperty("adb.connections.file");
        oldPrefsNode = System.getProperty("adb.prefs.node");
        oldDensity = System.getProperty("adb.density");

        System.setProperty("adb.demo.db", demoDirectory.resolve("demo.db").toString());
        System.setProperty("adb.connections.file",
                demoDirectory.resolve("connections.json").toString());
        System.setProperty("adb.prefs.node", prefsNode);
        // ⭐ 压到最密一档：这是「765 高窗口里 ≥ 22 行」的验收档。
        System.setProperty("adb.density", "dense");

        Preferences preferences = Preferences.userRoot().node(prefsNode.replace('.', '/'));
        preferences.put("ui.theme", "light");
        preferences.put("ui.density", "dense");
        preferences.putBoolean("ui.sidebar.collapsed", false);
        preferences.putInt("ui.sidebar.width", 240);
        preferences.putBoolean("ui.details.visible", false);
        preferences.put("ui.tableList.view", "list");
        preferences.putInt("ui.window.width", WINDOW_WIDTH);
        preferences.putInt("ui.window.height", WINDOW_HEIGHT);
        preferences.putInt("ui.window.x", 0);
        preferences.putInt("ui.window.y", 0);

        new App().start(stage);
    }

    @AfterAll
    static void restore() throws IOException {
        restoreProperty("adb.demo.db", oldDemoDb);
        restoreProperty("adb.connections.file", oldConnectionsFile);
        restoreProperty("adb.prefs.node", oldPrefsNode);
        restoreProperty("adb.density", oldDensity);
        if (prefsNode != null) {
            try {
                Preferences.userRoot().node(prefsNode.replace('.', '/')).removeNode();
            } catch (Exception ignored) {
                // 测试偏好节点清理失败不影响结论。
            }
        }
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

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @BeforeEach
    void openUsers(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        if (robot.lookup("#data-table").tryQuery().isPresent()) {
            return;
        }
        @SuppressWarnings("rawtypes")
        TreeCell<?> cell = robot.lookup(node -> isTableCell(node, "users"))
                .queryAs(TreeCell.class);
        robot.doubleClickOn(cell);
        try {
            WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () -> {
                Node table = robot.lookup("#data-table").tryQuery().orElse(null);
                return table instanceof TableView<?> view && !view.getItems().isEmpty();
            });
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("双击对象树里的 users 之后，8 秒内没有加载出数据", e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void chromeHeightsMatchTheDenseBudget(FxRobot robot) throws Exception {
        // ⭐ 2026-10-30 R36：按 Navicat 17 逐像素实拍再收一档 —— 页签行 26→25、
        //    状态栏 22→23（实拍值），Ribbon 由 AppBarTest/AppShellTest 钉在 91。
        //    顶区 24+91+25=140 与 Navicat 140 对齐（他们菜单 23，我们 24）。
        assertHeight(robot, ".adb-tabbar", 25);
        // 对象标题栏 40 与分页器 38 本轮未动（Navicat 无对应可见框，保留可读性预算）。
        assertHeight(robot, ".adb-object-header", 40);
        assertHeight(robot, "#data-toolbar", 30);
        assertHeight(robot, ".adb-grid__pager", 38);
        assertHeight(robot, ".adb-statusbar", 23);

        double rowHeight = WaitForAsyncUtils.asyncFx(() ->
                ((TableView<?>) robot.lookup("#data-table").query()).getFixedCellSize()).get();
        assertThat(rowHeight)
                .as("dense 档网格行高 = 列表 19 + 2（Navicat 列表 21 / 网格 23 的同一档差）")
                .isEqualTo(21.0);
    }

    @Test
    void denseDensityShowsAtLeast22RowsAt765(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        // 数「真正渲染出来的行」—— 就是用户一眼能看到的行数。
        // 虚拟流只渲染可见行（+ 极少量缓存），比拿表格高度反推更接近验收口径。
        long rows = WaitForAsyncUtils.asyncFx(() -> robot.lookup("#data-table")
                .queryAs(TableView.class)
                .lookupAll(".table-row-cell").stream()
                .filter(Node::isVisible)
                .count()).get();
        assertThat(rows)
                .as("dense 档在 %d×%d 窗口里至少要看见 22 行", WINDOW_WIDTH, WINDOW_HEIGHT)
                .isGreaterThanOrEqualTo(22);
    }

    private static void assertHeight(FxRobot robot, String selector, double expected)
            throws Exception {
        Node node = WaitForAsyncUtils.asyncFx(() ->
                robot.lookup(selector).queryAs(Node.class)).get();
        assertThat(node).as("找不到框架节点 %s", selector).isNotNull();
        double height = WaitForAsyncUtils.asyncFx(
                () -> node.getLayoutBounds().getHeight()).get();
        assertThat(height)
                .as("%s 的高度（UX-2 框架预算）", selector)
                .isEqualTo(expected);
    }

    /** 树上「名字正好是 users」的表单元格。 */
    @SuppressWarnings("rawtypes")
    private static boolean isTableCell(Node node, String name) {
        if (!(node instanceof TreeCell cell)
                || cell.getTreeItem() == null
                || cell.getTreeItem().getValue() == null) {
            return false;
        }
        TreeItem<?> item = cell.getTreeItem();
        String text = String.valueOf(item.getValue());
        return text.contains("name=" + name + ",")
                && text.contains("kind=TABLE");
    }
}
