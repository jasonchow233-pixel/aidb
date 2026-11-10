package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具条可发现性门禁（N18 / N19 改判后）。
 *
 * <p>Navicat 17 的数据网格顶栏是 <b>13 个具名键</b>
 * （{@code docs/internal/NAVICAT-TODO.md} §1.4 的清单），每个都带图标 + 文字 +
 * tooltip + accessibleText。⭐ 2026-11-09 BI 整体移除后是 12 个；
 * ⭐ 2026-11-10「明确不做的入口全部删掉」后（{@code Data Generation} 整格删除）
 * 当前是 <b>11 个具名键</b> —— 少的那两颗是 Navicat 有、但我们已裁定不做
 * 也不再摆占位入口的 `Create BI Workspace` 与 `Data Generation`；
 * 这不是「漏了一颗」，{@link #NAMED_KEYS} 与 `DataGridPage.namedToolbarKeysForTest()` 同口径。
 * 放不下时<b>不再把整排文字收成图标</b> ——
 * 那样会把 {@code Begin Transaction} 这类本来就该带文字的键一起收掉；
 * 现在从右往左把放不下的键折进右端的 {@code »} 菜单，文字与动作原样保留，
 * 且 {@code »} 可以用键盘（Tab + Enter）到达。
 *
 * <p>编辑簇（{@code + − ✓ ✗ ⋯}）不在这份具名键名单里：N19 把它们移到了网格左下的
 * 行操作条，那里只有图标，但提示与朗读一样不许少。
 *
 * <p>这里钉五件事：
 * <ol>
 *   <li>具名键项集与顺序和 Navicat 对账，四样（图标 / 文字 / tooltip / accessibleText）齐全；</li>
 *   <li>折叠只发生在右端 —— 可见的永远是前缀，折叠的是连续后缀；</li>
 *   <li>可见键的文字要么完整、要么整颗折进菜单，绝不许出现「筛 …」；</li>
 *   <li>{@code »} 键盘可达，菜单项与折叠键一一对应（且保留禁用态）；</li>
 *   <li>行操作条只有图标与提示，且与顶栏不重复；工具条按语义分组、删除走 danger 色。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class ToolbarDiscoverabilityTest {

    /** 1280 是验收帧的宽度。 */
    private static final int WIDE_WIDTH = 1280;
    /** 窄窗口档：11 个键必然折掉大半。 */
    private static final int NARROW_WIDTH = 900;

    /**
     * ⭐ N19：Navicat 数据页顶栏的具名键（按视觉顺序）。
     *
     * <p>名单来自 {@code docs/internal/NAVICAT-TODO.md} §1.4 的实拍清单：
     * {@code Table Profile▾ · Begin Transaction · Cell Editor · Filter & Sort ·
     * Columns · History · Fill with AI▾ · Data Profiling · Import · Export ·
     * Data Generation · Data Masking}。2026-11-09 用户裁定移除 BI，
     * 原先的 {@code Create BI Workspace} 连同 Ribbon 的 BI 磁贴一并删掉；
     * 2026-11-10 用户裁定「明确不做的入口全部删掉」，{@code Data Generation}
     * 那一格整格删除 —— 当前 <b>11 个</b>。
     * 右端另有两颗小键：视图菜单（≡▾）与溢出（»），它们不算具名键。
     */
    private static final List<String> NAMED_KEYS = List.of(
            "data-table-profile", "data-begin-tx", "data-cell-editor", "data-filter",
            "data-columns", "data-history", "data-fill-ai", "data-profiling",
            "data-import", "data-export", "data-masking");

    /** N19：编辑簇在网格左下的行操作条里，只有图标但提示齐全。 */
    private static final List<String> ROW_STRIP_KEYS = List.of(
            "data-add", "data-delete-rows", "data-save", "data-row-cancel", "data-row-more");

    private static Path demoDirectory;
    private static String prefsNode;
    private static String oldDemoDb;
    private static String oldConnectionsFile;
    private static String oldPrefsNode;
    private static String oldDensity;

    private Stage stage;

    @Start
    void start(Stage stage) throws IOException {
        this.stage = stage;
        demoDirectory = Files.createTempDirectory("adb-toolbar-test-");
        prefsNode = "io/agenticdb/tests/toolbar-" + System.nanoTime();

        oldDemoDb = System.getProperty("adb.demo.db");
        oldConnectionsFile = System.getProperty("adb.connections.file");
        oldPrefsNode = System.getProperty("adb.prefs.node");
        oldDensity = System.getProperty("adb.density");

        System.setProperty("adb.demo.db", demoDirectory.resolve("demo.db").toString());
        System.setProperty("adb.connections.file",
                demoDirectory.resolve("connections.json").toString());
        System.setProperty("adb.prefs.node", prefsNode);
        System.setProperty("adb.density", "compact");

        Preferences preferences = Preferences.userRoot().node(prefsNode.replace('.', '/'));
        preferences.put("ui.theme", "light");
        preferences.put("ui.density", "compact");
        preferences.putBoolean("ui.sidebar.collapsed", false);
        preferences.putInt("ui.sidebar.width", 240);
        preferences.putBoolean("ui.details.visible", false);
        preferences.put("ui.tableList.view", "list");
        preferences.putInt("ui.window.width", WIDE_WIDTH);
        preferences.putInt("ui.window.height", 800);
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

    /** 打开 users 数据页 —— 工具条只有在这一页才存在。 */
    @BeforeEach
    void openUsers(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        if (hasSaveButton(robot)) {
            return;
        }
        Node cell = WaitForAsyncUtils.asyncFx(() -> robot.lookup(node -> isTableCell(node, "users"))
                .tryQuery().orElse(null)).get();
        assertThat(cell).as("对象树里找不到 users 表节点").isNotNull();
        WaitForAsyncUtils.asyncFx(() -> robot.doubleClickOn(cell)).get();
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () -> {
            try {
                return hasSaveButton(robot);
            } catch (Exception e) {
                return false;
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean hasSaveButton(FxRobot robot) throws Exception {
        Node page = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup(".adb-grid-page").tryQuery().orElse(null)).get();
        return page != null && findById(page, "data-save") != null;
    }

    @Test
    void namedKeysMatchTheNavicatToolbarAndCarryAllFourClues(FxRobot robot) throws Exception {
        DataGridPage page = page(robot);
        List<String> ids = WaitForAsyncUtils.asyncFx(page::namedToolbarKeysForTest).get()
                .stream().map(Node::getId).toList();
        assertThat(ids)
                .as("11 个具名键的项集与顺序必须和 Navicat 对账（§1.4 清单）")
                .containsExactlyElementsOf(NAMED_KEYS);

        List<ButtonBase> keys = WaitForAsyncUtils.asyncFx(page::namedToolbarKeysForTest).get();
        for (ButtonBase key : keys) {
            assertThat(key.getGraphic()).as("%s 必须带图标", key.getId()).isNotNull();
            assertHint(key.getId(), key);
            assertThat(key.getStyleClass())
                    .as("%s 是具名键，不许挂 adb-button--icon（会把文字钉没）", key.getId())
                    .doesNotContain("adb-button--icon");
            if (key.isVisible()) {
                assertThat(((Labeled) key).getText())
                        .as("%s 可见时必须带完整文字", key.getId()).isNotBlank();
            }
        }
    }

    @Test
    void wideWindowFoldsOnlyTheRightEnd(FxRobot robot) throws Exception {
        resize(WIDE_WIDTH);
        DataGridPage page = page(robot);
        List<ButtonBase> named = WaitForAsyncUtils.asyncFx(page::namedToolbarKeysForTest).get();
        List<ButtonBase> folded = WaitForAsyncUtils.asyncFx(page::foldedToolbarKeysForTest).get();

        assertThat(ids(folded))
                .as("折叠必须从右往左连续发生，不许跳过中间某颗")
                .isEqualTo(ids(named.subList(named.size() - folded.size(), named.size())));
        for (ButtonBase key : named) {
            if (folded.contains(key)) {
                assertThat(key.isManaged()).as("%s 折进 » 后仍占位", key.getId()).isFalse();
            } else {
                assertThat(key.isVisible()).as("%s 既没显示也没折叠 —— 动作丢了", key.getId()).isTrue();
                assertVisibleKeyIsNotSqueezed(key, WIDE_WIDTH);
            }
        }
        Node overflow = overflow(toolbar(robot));
        assertThat(overflow.isManaged())
                .as("宽窗口下折叠与否都要与 » 的可见性一致").isEqualTo(!folded.isEmpty());
        assertThat(((Labeled) require(robot, "data-table-profile")).getText())
                .as("第一颗具名键在任何宽度下都不该被折掉").isNotBlank();
        assertToolbarFits(robot, WIDE_WIDTH);
    }

    @Test
    void narrowWindowFoldsButEveryActionStaysKeyboardReachable(FxRobot robot) throws Exception {
        resize(NARROW_WIDTH);
        DataGridPage page = page(robot);
        List<ButtonBase> folded = WaitForAsyncUtils.asyncFx(page::foldedToolbarKeysForTest).get();
        assertThat(folded)
                .as("%dpx 下 11 个具名键不可能全放得下（前置条件：这条门禁在验折叠）", NARROW_WIDTH)
                .isNotEmpty();
        for (ButtonBase key : folded) {
            assertHint(key.getId(), key);
        }

        Node overflow = overflow(toolbar(robot));
        assertThat(overflow.isManaged()).as("有键折叠时 » 必须出现").isTrue();
        assertThat(overflow.isVisible()).isTrue();

        // 键盘：焦点落在 » 上按回车 —— 菜单必须列出所有折叠键，禁用态跟着原按钮走。
        WaitForAsyncUtils.asyncFx(() -> {
            overflow.requestFocus();
            overflow.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, false, false, false));
        }).get();
        WaitForAsyncUtils.waitForFxEvents(3);

        List<MenuItem> items = WaitForAsyncUtils.asyncFx(
                () -> List.copyOf(page.toolbarOverflowMenuForTest().getItems())).get();
        assertThat(items)
                .as("» 菜单项必须与折叠键一一对应（键盘用户靠它够到被折的动作）")
                .hasSize(folded.size());
        for (int i = 0; i < items.size(); i++) {
            ButtonBase key = folded.get(i);
            assertThat(items.get(i).getText()).as("菜单项文字").isNotBlank();
            assertThat(items.get(i).isDisable())
                    .as("菜单项 %s 的禁用态必须跟原按钮一致", items.get(i).getText())
                    .isEqualTo(key.isDisabled());
        }
        WaitForAsyncUtils.asyncFx(() -> page.toolbarOverflowMenuForTest().hide()).get();

        // 可见前缀仍是完整文字。
        List<ButtonBase> named = WaitForAsyncUtils.asyncFx(page::namedToolbarKeysForTest).get();
        for (ButtonBase key : named) {
            if (!folded.contains(key)) {
                assertVisibleKeyIsNotSqueezed(key, NARROW_WIDTH);
            }
        }
        assertToolbarFits(robot, NARROW_WIDTH);
    }

    @Test
    void labelsAreNeverSqueezedIntoEllipsis(FxRobot robot) throws Exception {
        DataGridPage page = page(robot);
        for (int width : new int[]{1100, 1180, 1280}) {
            resize(width);
            List<ButtonBase> named = WaitForAsyncUtils.asyncFx(page::namedToolbarKeysForTest).get();
            List<ButtonBase> folded =
                    WaitForAsyncUtils.asyncFx(page::foldedToolbarKeysForTest).get();
            for (ButtonBase key : named) {
                if (folded.contains(key)) {
                    continue; // 折进 » 是合法降级，提示在菜单项里仍念得出来。
                }
                assertVisibleKeyIsNotSqueezed(key, width);
            }
            assertToolbarFits(robot, width);
        }
    }

    @Test
    void groupsAreSeparatedAndDeleteIsMarkedDangerous(FxRobot robot) throws Exception {
        List<Node> separators = new ArrayList<>();
        collect(toolbar(robot), node -> node.getStyleClass().contains("adb-toolbar__separator"),
                separators);
        assertThat(separators.size())
                .as("工具条要按语义分组（表 / 事务 / 查询 / 视图 / 洞察 / 交换），组间一条竖线")
                .isGreaterThanOrEqualTo(2);

        DataGridPage page = page(robot);
        Node delete = findById(page, "data-delete-rows");
        assertThat(delete).as("行操作条里的删除键").isNotNull();
        assertThat(delete.getStyleClass())
                .as("删除是唯一会丢数据的动作，必须用 danger 色")
                .contains("adb-button--danger");
    }

    @Test
    void theEditClusterLivesInTheBottomRecordStrip(FxRobot robot) throws Exception {
        DataGridPage page = page(robot);
        HBox strip = WaitForAsyncUtils.asyncFx(
                () -> (HBox) page.lookup("#data-row-actions")).get();
        assertThat(strip).as("网格左下的行操作条 #data-row-actions").isNotNull();

        List<String> stripIds = WaitForAsyncUtils.asyncFx(() -> strip.getChildren().stream()
                .map(Node::getId).toList()).get();
        assertThat(stripIds)
                .as("编辑簇只在行操作条里出现一次：+ − ✓ ✗ ⋯")
                .containsExactlyElementsOf(ROW_STRIP_KEYS);

        for (String id : ROW_STRIP_KEYS) {
            // ⚠️ 用 findById 而不是 lookup：没有选中行时删除是 visible=false，
            //    lookup 的可见性过滤会把它当成「不存在」。
            Node key = findById(page, id);
            assertThat(key).as("行操作条里找不到 #%s", id).isNotNull();
            assertThat(((Labeled) key).getText())
                    .as("%s 在行操作条里只该有图标", id).isBlank();
            assertThat(((Labeled) key).getGraphic()).as("%s 图标不能丢", id).isNotNull();
            assertHint(id, key);
        }

        // 顶栏不再有第二份同名按钮（状态只有一个来源）。
        for (String id : ROW_STRIP_KEYS) {
            assertThat(findById(toolbar(robot), id))
                    .as("%s 不该再出现在顶部工具条里（N19 已整排下移）", id)
                    .isNull();
        }

        // 撤销 / 重做进了第 5 颗的菜单，动作仍是完整动作。
        MenuButton more = (MenuButton) findById(page, "data-row-more");
        assertThat(WaitForAsyncUtils.asyncFx(() -> more.getItems().stream()
                .map(MenuItem::getId).toList()).get())
                .as("撤销 / 重做 / 复制的菜单项")
                .contains("data-row-undo", "data-row-redo", "data-row-copy", "data-row-copy-sql");
        assertThat(WaitForAsyncUtils.asyncFx(() -> more.getItems().stream()
                .filter(item -> "data-row-undo".equals(item.getId()))
                .findFirst().orElseThrow().getText()).get())
                .isNotBlank();
    }

    @Test
    void labeledToolbarStillFitsInAWideWindow(FxRobot robot) throws Exception {
        resize(WIDE_WIDTH);
        assertToolbarFits(robot, WIDE_WIDTH);
    }

    /**
     * 选中一行之后左下条多了一颗「删除记录」—— 它不再影响顶栏宽度；
     * 但顶栏这一排本身仍必须整颗在框内，且文字不被压扁。
     */
    @Test
    void selectingARowKeepsTheToolbarInsideItsEdge(FxRobot robot) throws Exception {
        resize(WIDE_WIDTH);
        @SuppressWarnings("unchecked")
        TableView<Map<String, Object>> table = (TableView<Map<String, Object>>)
                WaitForAsyncUtils.asyncFx(
                        () -> robot.lookup("#data-table").tryQuery().orElse(null)).get();
        assertThat(table).as("数据网格里找不到 #data-table").isNotNull();
        WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () -> !table.getItems().isEmpty());
        WaitForAsyncUtils.asyncFx(() -> table.getSelectionModel().select(0)).get();
        WaitForAsyncUtils.waitForFxEvents(3);

        DataGridPage page = page(robot);
        Node delete = findById(page, "data-delete-rows");
        assertThat(delete).as("找不到 #data-delete-rows").isNotNull();
        assertThat(delete.isManaged())
                .as("选中行后「删除记录」没占位，这条门禁证不了任何事").isTrue();

        List<ButtonBase> folded = WaitForAsyncUtils.asyncFx(page::foldedToolbarKeysForTest).get();
        for (ButtonBase key : WaitForAsyncUtils.asyncFx(page::namedToolbarKeysForTest).get()) {
            if (!folded.contains(key)) {
                assertVisibleKeyIsNotSqueezed(key, WIDE_WIDTH);
            }
        }
        assertToolbarFits(robot, WIDE_WIDTH);
    }

    /**
     * 详情抽屉是浮层，开与关都不许改变工具条的宽度。
     *
     * <p>抽屉原来和数据区并排在同一个 HBox 里 —— 一开就把工具条挤窄，
     * 于是折叠集合跟着变、按钮在眼皮底下跳。现在它挂在 body 的 StackPane 上，
     * 不参与宽度分配。这里钉住这个结构：宽度必须**一模一样**。
     */
    @Test
    void openingTheDetailsDrawerLeavesTheToolbarWidthAlone(FxRobot robot) throws Exception {
        resize(WIDE_WIDTH);
        Region toolbar = toolbar(robot);
        double closedWidth = WaitForAsyncUtils.asyncFx(toolbar::getWidth).get();
        Node grid = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup("#data-table").tryQuery().orElse(null)).get();
        assertThat(grid).as("数据网格里找不到 #data-table").isNotNull();
        @SuppressWarnings("unchecked")
        TableView<Map<String, Object>> table = (TableView<Map<String, Object>>) grid;
        DataGridPage page = page(robot);
        Map<String, Object> row = table.getItems().get(0);
        WaitForAsyncUtils.asyncFx(() -> page.showDetails(row,
                new io.agenticdb.model.ColumnDef("name", io.agenticdb.model.ColumnType.TEXT,
                        true, false, null, null, null, null, null, null, false))).get();
        WaitForAsyncUtils.waitForFxEvents(3);
        Node drawer = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup("#data-details").tryQuery().orElse(null)).get();
        assertThat(drawer).as("详情抽屉节点 #data-details 不存在").isNotNull();
        assertThat(drawer.isVisible()).as("抽屉要真的开着，否则这条断言是空的").isTrue();

        double openWidth = WaitForAsyncUtils.asyncFx(toolbar::getWidth).get();
        assertThat(openWidth)
                .as("开抽屉改变了工具条宽度（%.0f → %.0f）—— 抽屉又参与布局了", closedWidth, openWidth)
                .isCloseTo(closedWidth, org.assertj.core.api.Assertions.within(0.5));

        // 浮层不许盖住工具条：两者在场景坐标里不能重叠。
        javafx.geometry.Bounds drawerBounds =
                drawer.localToScene(drawer.getBoundsInLocal());
        javafx.geometry.Bounds toolbarBounds =
                toolbar.localToScene(toolbar.getBoundsInLocal());
        assertThat(drawerBounds.intersects(toolbarBounds))
                .as("抽屉（%.0f,%.0f）盖住了工具条（%.0f,%.0f）",
                        drawerBounds.getMinX(), drawerBounds.getMinY(),
                        toolbarBounds.getMinX(), toolbarBounds.getMinY())
                .isFalse();
    }

    /**
     * 导入向导必须把当前表预选成目标表 —— 这是数据页「导入…」存在的全部理由。
     *
     * <p>走 {@code createImportWizard} 这个接缝：{@code show()} 是模态的，
     * 一点就卡住 FX 线程，门禁拿不到断言时机。
     */
    @Test
    void importWizardPreselectsTheCurrentTable(FxRobot robot) throws Exception {
        DataGridPage page = page(robot);
        ImportWizard wizard = WaitForAsyncUtils.asyncFx(
                () -> page.createImportWizard(List.of("orders", "users", "notes"))).get();
        ComboBox<?> box = WaitForAsyncUtils.asyncFx(
                () -> (ComboBox<?>) wizard.content().lookup("#import-table")).get();
        assertThat(box).as("导入向导里没有 #import-table").isNotNull();
        @SuppressWarnings("unchecked")
        ComboBox<String> tableBox = (ComboBox<String>) box;
        assertThat(tableBox.getItems()).as("当前表排第一，且不能重复出现")
                .containsExactly("users", "orders", "notes");
        assertThat(tableBox.getValue()).as("目标表预选成当前表").isEqualTo("users");
    }

    /**
     * ⭐ R43-c：工具条右端那排小键不许把下拉箭头挤成一颗「裸 ▾」。
     *
     * <p>1280px 实拍帧上，≡ 左边 14px 处孤零零立着一个 ▾ —— 它既没图标也没文字，
     * 用户无从知道点下去是哪颗键的动作（§11 说的骗人控件形态）。根因是
     * {@code data-view} 挂了 {@code adb-button--icon}：那颗键被钉死 30px，
     * 而它是 MenuButton，内容要「内边距 16 + 图标 14.5 + 箭头 16」。
     * 装不下时 ButtonSkin 把 16px 的 arrow-button 排到图标<b>左边</b>并压住它。
     *
     * <p>属性断言抓不到这个：钉宽 30 与「有图标有箭头」两条各自都成立。
     * 所以这里量<b>几何</b> —— 箭头必须在图标右边，且图标与箭头都在按钮自己的框里。
     * 读之前先跑一次布局，不靠 waitForFxEvents 碰运气。
     */
    @Test
    void menuKeysNeverSqueezeTheirArrowOntopOfTheIcon(FxRobot robot) throws Exception {
        resize(WIDE_WIDTH);
        Region toolbar = toolbar(robot);
        WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.Parent root = toolbar.getScene() == null
                    ? toolbar : toolbar.getScene().getRoot();
            root.applyCss();
            root.layout();
        }).get();

        List<MenuButton> menus = WaitForAsyncUtils.asyncFx(() -> {
            List<Node> hits = new ArrayList<>();
            collect(toolbar, node -> node instanceof MenuButton
                    && node.isVisible() && node.isManaged(), hits);
            return hits.stream().map(MenuButton.class::cast).toList();
        }).get();
        assertThat(menus.stream().map(Node::getId).toList())
                .as("1280px 档右端至少要摆出视图菜单这颗图标下拉键（前置条件：这条在验它）")
                .contains("data-view");

        for (MenuButton menu : menus) {
            assertThat(menu.getStyleClass())
                    .as("%s 是下拉键，挂 adb-button--icon 会把箭头挤到图标左边", menu.getId())
                    .doesNotContain("adb-button--icon");
            Node arrow = WaitForAsyncUtils.asyncFx(() -> menu.lookup(".arrow-button")).get();
            assertThat(arrow).as("%s 找不到下拉箭头节点").isNotNull();
            Node icon = menu.getGraphic();
            assertThat(icon).as("%s 必须带图标", menu.getId()).isNotNull();

            javafx.geometry.Bounds box = menu.localToScene(menu.getBoundsInLocal());
            javafx.geometry.Bounds arrowBox = arrow.localToScene(arrow.getBoundsInLocal());
            javafx.geometry.Bounds iconBox = icon.localToScene(icon.getBoundsInLocal());
            assertThat(menu.getWidth())
                    .as("%s 被压扁了：实际 %.0fpx < 需要 %.0fpx", menu.getId(),
                            menu.getWidth(), menu.prefWidth(-1))
                    .isGreaterThanOrEqualTo(menu.prefWidth(-1) - 0.5);
            assertThat(arrowBox.getMinX())
                    .as("%s 的箭头跑到了图标左边（箭头 %.0f &lt; 图标右边缘 %.0f）—— "
                            + "画面上就是一颗没有身份的裸 ▾",
                            menu.getId(), arrowBox.getMinX(), iconBox.getMaxX())
                    .isGreaterThanOrEqualTo(iconBox.getMaxX() - 0.5);
            assertThat(iconBox.getMinX())
                    .as("%s 的图标越出了按钮左边缘", menu.getId())
                    .isGreaterThanOrEqualTo(box.getMinX() - 0.5);
            assertThat(arrowBox.getMaxX())
                    .as("%s 的箭头越出了按钮右边缘", menu.getId())
                    .isLessThanOrEqualTo(box.getMaxX() + 0.5);
        }
        assertToolbarFits(robot, WIDE_WIDTH);
    }

    // ─────────────────────────── 助手 ───────────────────────────

    /** 可见的具名键不许被压扁：实际宽度 ≥ 带文字的需求宽。 */
    private static void assertVisibleKeyIsNotSqueezed(ButtonBase key, int width) {
        Labeled labeled = (Labeled) key;
        assertThat(labeled.getText())
                .as("%s 在 %dpx 档可见时必须是完整文字，不许收成空", key.getId(), width)
                .isNotBlank();
        Control control = (Control) key;
        assertThat(control.getWidth())
                .as("%s 在 %dpx 档被压扁了：实际 %.0fpx < 需要 %.0fpx（会截成省略号）",
                        key.getId(), width, control.getWidth(), labeled.prefWidth(-1))
                .isGreaterThanOrEqualTo(labeled.prefWidth(-1) - 0.5);
    }

    private static void assertHint(String id, Node button) {
        Tooltip tooltip = ((Control) button).getTooltip();
        assertThat(tooltip).as("%s 必须有 tooltip", id).isNotNull();
        assertThat(tooltip.getText()).as("%s 的 tooltip 不能是空的", id).isNotBlank();
        assertThat(button.getAccessibleText())
                .as("%s 必须有 accessibleText（读屏要念得出来）", id).isNotBlank();
    }

    private static List<String> ids(List<ButtonBase> keys) {
        return keys.stream().map(Node::getId).toList();
    }

    /** 改窗口宽度并等布局跑完。 */
    private void resize(int width) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> stage.setWidth(width)).get();
        WaitForAsyncUtils.waitForFxEvents(3);
        assertThat(stage.getWidth())
                .as("窗口没能设到 %dpx（实际 %.0f）", width, stage.getWidth())
                .isGreaterThan(0);
    }

    /** 工具条整排（含 »）都在自己的框内 —— 不是只查某一个点。 */
    private void assertToolbarFits(FxRobot robot, int width) throws Exception {
        Region toolbar = toolbar(robot);
        double toolbarRight =
                toolbar.localToScene(toolbar.getBoundsInLocal()).getMaxX();
        // ⭐ 框自己也不许被撑出场景：minWidth 钉死时溢出的是**整条**，
        //    子节点个个都在「自己的框」里，可框有一大截在窗外。
        //    ⚠️ 1100 档不查这条：那一档是**分页器**的最小宽度把整页撑出去的
        //    （G12 / R32-D 的「1280 宽下分页器被裁」），跟工具条无关。
        if (width >= 1180) {
            double sceneRight = toolbar.getScene() == null
                    ? Double.MAX_VALUE : toolbar.getScene().getWidth();
            assertThat(toolbarRight)
                    .as("工具条在 %dpx 档被撑出场景（右边缘 %.0f > 场景 %.0f）—— 它不肯折叠",
                            width, toolbarRight, sceneRight)
                    .isLessThanOrEqualTo(sceneRight + 0.5);
        }
        for (Node child : cascade(toolbar)) {
            double right = child.localToScene(child.getBoundsInLocal()).getMaxX();
            assertThat(right)
                    .as("%s 在 %dpx 档越出工具条右边缘（%.0f > %.0f）",
                            describe(child), width, right, toolbarRight)
                    .isLessThanOrEqualTo(toolbarRight + 0.5);
        }
    }

    private static String describe(Node node) {
        return node.getId() != null ? "#" + node.getId()
                : node instanceof Labeled labeled && !labeled.getText().isBlank()
                        ? "「" + labeled.getText() + "」"
                        : node.getStyleClass().toString();
    }

    /** 工具条里所有**可见且占位**的后代（隐藏 / 折叠的按钮不算溢出）。 */
    private static List<Node> cascade(Region toolbar) {
        List<Node> all = new ArrayList<>();
        collect(toolbar, node -> node != toolbar && node.isVisible() && node.isManaged(), all);
        return all;
    }

    private DataGridPage page(FxRobot robot) throws Exception {
        return WaitForAsyncUtils.asyncFx(
                () -> robot.lookup(".adb-grid-page").queryAs(DataGridPage.class)).get();
    }

    /**
     * 数据页节点。
     *
     * <p>⚠️ 不用 {@code lookup("#data-toolbar")}：TestFX 的 finder 带可见性过滤，
     * 一旦断的是「找不到所以跳过」，门禁就会**静默通过**（假绿）。
     * 这里宁可抛异常，也不放行。
     */
    private Region toolbar(FxRobot robot) throws Exception {
        Node page = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup(".adb-grid-page").tryQuery().orElse(null)).get();
        assertThat(page).as("当前不在数据页（工具条不存在，断言无从谈起）").isNotNull();
        Node toolbar = WaitForAsyncUtils.asyncFx(
                () -> findById(page, "data-toolbar")).get();
        assertThat(toolbar).as("数据页里找不到 #data-toolbar").isNotNull();
        return (Region) toolbar;
    }

    private Node require(FxRobot robot, String id) throws Exception {
        Node button = findById(toolbar(robot), id);
        assertThat(button).as("工具条里找不到 #%s", id).isNotNull();
        return button;
    }

    private Node overflow(Region toolbar) {
        return findById(toolbar, "data-toolbar-overflow");
    }

    /**
     * 按 id 深度遍历。
     *
     * <p>⚠️ 不用 {@code lookup}：它按可见性过滤，而「删除记录」在没有选中行时是
     * {@code visible=false, managed=false} —— 恰恰是最该被门禁看到的按钮。
     */
    private static Node findById(Node root, String id) {
        List<Node> hits = new ArrayList<>();
        collect(root, node -> id.equals(node.getId()), hits);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static void collect(Node node, Predicate<Node> match, List<Node> out) {
        if (node == null) {
            return;
        }
        if (match.test(node)) {
            out.add(node);
        }
        if (node instanceof Pane pane) {
            for (Node child : pane.getChildren()) {
                collect(child, match, out);
            }
        }
    }

    /** 树上「名字正好是给定名称」的表单元格。 */
    @SuppressWarnings("rawtypes")
    private static boolean isTableCell(Node node, String name) {
        if (!(node instanceof TreeCell cell)
                || cell.getTreeItem() == null
                || cell.getTreeItem().getValue() == null) {
            return false;
        }
        TreeItem<?> item = cell.getTreeItem();
        String text = String.valueOf(item.getValue());
        return text.contains("name=" + name + ",") && text.contains("kind=TABLE");
    }
}
