package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.Node;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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

    /**
     * ⭐ D-85 第二族：环境隔离只在 {@code @BeforeAll} 做<b>一次</b>。
     *
     * <p>原先这四行捕获写在 {@code @Start} 里 —— 而 TestFX 的 {@code @Start}
     * 每个测试方法都跑一次，第二个方法会把<b>第一个方法设的临时目录</b>当成
     * 「原值」捕获进静态 old*，{@code @AfterAll} 就把 {@code adb.demo.db}
     * 还原成那个<b>已被删除</b>的目录。实测这条链毒不出失败（{@code ensure()}
     * 会重建父目录和库），但「一类一份、收尾删一份」仍是正确的卫生：一类只
     * 建一份临时环境，不再每方法造一个、只删最后一个。
     */
    @BeforeAll
    static void isolateEnvironmentOnce() throws IOException {
        oldDemoDb = System.getProperty("adb.demo.db");
        oldConnectionsFile = System.getProperty("adb.connections.file");
        oldPrefsNode = System.getProperty("adb.prefs.node");
        oldDensity = System.getProperty("adb.density");

        demoDirectory = Files.createTempDirectory("adb-chrome-test-");
        prefsNode = "io/agenticdb/tests/chrome-" + System.nanoTime();
        System.setProperty("adb.demo.db", demoDirectory.resolve("demo.db").toString());
        System.setProperty("adb.connections.file",
                demoDirectory.resolve("connections.json").toString());
        System.setProperty("adb.prefs.node", prefsNode);
        // ⭐ 压到最密一档：这是「765 高窗口里 ≥ 22 行」的验收档。
        System.setProperty("adb.density", "dense");
    }

    @Start
    void start(Stage stage) throws IOException {
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
    void openUsers(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        if (robot.lookup("#data-table").tryQuery().isPresent()) {
            return;
        }
        // 演示库连接是异步的：冷启动下对象树可能还没长出来，先等它出现。
        try {
            WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS,
                    () -> robot.lookup(node -> isTableCell(node, "users"))
                            .queryAs(TreeCell.class) != null);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("8 秒内对象树里没有出现 users 节点", e);
        }
        // ⭐ D-85 第二族（翻案）：robot.doubleClickOn 在冷 JVM / 满负载下会丢双击
        //   —— 两连击之间被 FX 脉冲拉开，Monocle 判成两次单击，树根本不进数据页，
        //   8 秒等成超时（批量跑红第二个方法、单跑冷启动红第一个方法，栽的都是它；
        //   而 @AfterAll 还原已删目录其实无害：ensure() 会重建父目录和库）。
        //   改成在 FX 线程上选中 users + 合成 clickCount=2 的 MOUSE_CLICKED ——
        //   SideBar 的双击处理器（tree.setOnMouseClicked）读的正是「选中项」，
        //   与真实双击同语义，不再依赖鼠标时序。
        WaitForAsyncUtils.asyncFx(() -> {
            @SuppressWarnings("rawtypes")
            TreeCell cell = robot.lookup(node -> isTableCell(node, "users"))
                    .queryAs(TreeCell.class);
            @SuppressWarnings("rawtypes")
            TreeView tree = cell.getTreeView();
            tree.getSelectionModel().select(cell.getTreeItem());
            tree.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED,
                    10, 10, 10, 10, MouseButton.PRIMARY, 2,
                    false, false, false, false, false, false, false,
                    false, false, false, null));
        }).get(5, TimeUnit.SECONDS);
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
