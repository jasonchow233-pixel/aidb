package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
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
 * UX-3 工具条可发现性门禁。
 *
 * <p>一条工具栏里有的带文字、有的只有图标，用户就只能猜 —— 猜错的代价是点错按钮。
 * 所以这里钉五件事：
 * <ol>
 *   <li><b>具名操作</b>（Begin Transaction / Filter &amp; Sort / Columns / History）必须
 *       <b>图标 + 文字 + tooltip + accessibleText</b> 四样齐全；</li>
 *   <li><b>瞬时编辑键</b>（新增 / 撤销 / 重做 / 保存 / 删除 / 视图 / 导入 / 导出）
 *       <b>只有图标</b>，但 tooltip 与 accessibleText 一样不许少 ——
 *       这条分工是 2026-10-28 按 Navicat 实拍改判的，见 {@code MAIN_OPERATIONS} 的注释；</li>
 *   <li>窗口窄到放不下文字时<b>退成图标</b>，但 tooltip 与 accessibleText 不许跟着丢；
 *       宽度回来文字必须自己复原（不能靠重开页面）；</li>
 *   <li>工具条按语义分组（组间有分隔线）、删除走 danger 色，且 1280 宽下不溢出；</li>
 *   <li>U4：文字要么完整、要么整颗收成图标 —— 不许出现「筛 …」这种压扁的文字，
 *       且详情抽屉是浮层，开关都不改变工具条宽度。</li>
 * </ol>
 *
 * <p>数据页「导入…」开出来的向导必须把<b>当前表</b>预选成目标表（最后一条门禁）。
 */
@ExtendWith(ApplicationExtension.class)
class ToolbarDiscoverabilityTest {

    /** 宽窗口档：具名操作带文字。 */
    private static final int WIDE_WIDTH = 1280;
    /** 窄窗口档（低于 {@code DataGridPage.TOOLBAR_LABEL_MIN_WIDTH}）：收字。 */
    private static final int NARROW_WIDTH = 900;

    /**
     * 必须带文字的<b>具名操作</b>（按工具条从左到右）。
     *
     * <p>⭐ 2026-10-28 R34 N1-b 改判。名单从六颗换成四颗，删掉的是
     * {@code data-save / data-view / data-import / data-export}，补进的是
     * {@code data-begin-tx / data-history}。两条理由：
     * <ol>
     *   <li><b>实拍分工</b>：Navicat 数据网格工具条（帧
     *       {@code docs/screenshots/navicat/navicat17-data-grid-2026-10-27.png}）上
     *       {@code + ✓ ✗ C ■} 这一簇全是纯图标，带文字的只有
     *       {@code Begin Transaction} {@code Column Filter} {@code Filter & Sort}
     *       {@code Columns} {@code History} 这类具名操作；</li>
     *   <li><b>英文档放不下</b>：这一排在 1280 窗口实测只有 1036px，
     *       中文档九颗文字全开刚好 1026px（贴着上限），换英文后同样九颗要 1153px。
     *       原来的兜底是「放不下就整排收字」，那会把该带文字的具名操作一起收掉 ——
     *       反而比中文档更不像 Navicat。按实拍的分工重排后实测 899px，留 137px 余量。</li>
     * </ol>
     *
     * <p>「保存 3」这个待保存计数随按钮文字一并退场，由状态栏
     * {@code status.pending}（"{0} pending"）报同一个数；
     * 保存期间的转圈由 {@code IconButtons.loading} 负责，按钮宽度不变。
     */
    private static final List<String> MAIN_OPERATIONS = List.of(
            "data-begin-tx", "data-filter", "data-columns", "data-history");

    /** 只有图标的瞬时编辑键：文字必须为空，但提示与朗读必须齐全。 */
    private static final List<String> ICON_ONLY_EDITS = List.of(
            "data-add", "data-undo", "data-redo", "data-save", "data-delete-rows",
            "data-view", "data-export", "data-import");

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

    /**
     * ⭐ 宽窗口下「文字全开」必须<b>真的放得下</b> —— 直接比这两个实测数。
     *
     * <p>为什么单独立一条：其余门禁量的都是「按钮有没有文字」，而本轮的失败恰恰是
     * <b>有文字却放不下</b> —— 英文档九颗文字键要 1153px，工具条只分到 1036px，
     * 于是兜底逻辑把整排收字，门禁才红。只看结果（"文字为空"）永远说不出是
     * 「按分工该空」还是「挤不下被迫空」，这条门禁量的就是这个差。
     *
     * <p>换语言、换措辞、加按钮，都会先在这里红，而不是等到验收帧才发现。
     */
    @Test
    void namedLabelsActuallyFitTheWideToolbar(FxRobot robot) throws Exception {
        Node page = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup(".adb-grid-page").tryQuery().orElse(null)).get();
        assertThat(page).as("没打开数据页，量不了").isNotNull();
        double[] pair = WaitForAsyncUtils.asyncFx(() -> {
            try {
                java.lang.reflect.Method req = page.getClass().getDeclaredMethod("labelRequirement");
                java.lang.reflect.Method avail = page.getClass()
                        .getDeclaredMethod("availableToolbarWidth");
                req.setAccessible(true);
                avail.setAccessible(true);
                return new double[]{(double) req.invoke(page), (double) avail.invoke(page)};
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }).get();
        double requirement = pair[0];
        double available = pair[1];
        assertThat(available)
                .as("%dpx 窗口没量到工具条可用宽度（%.1f）—— 量不出来这条门禁就是空气",
                        WIDE_WIDTH, available)
                .isGreaterThan(0);
        assertThat(requirement)
                .as("带文字的具名操作在 %dpx 窗口下放不下：要 %.0fpx，实得 %.0fpx。"
                        + "要么按 Navicat 的分工把文字留给具名操作（见 MAIN_OPERATIONS），"
                        + "要么等 R38 N18 的 >> 折叠菜单；不许悄悄缩短 Navicat 的原词。",
                        WIDE_WIDTH, requirement, available)
                .isLessThanOrEqualTo(available + 0.5);
    }

    @Test
    void mainOperationsCarryIconTextAndHint(FxRobot robot) throws Exception {
        for (String id : MAIN_OPERATIONS) {
            Node button = require(robot, id);
            Labeled labeled = (Labeled) button;
            assertThat(labeled.getText()).as("%s 在宽窗口下必须有文字", id).isNotBlank();
            assertThat(labeled.getGraphic()).as("%s 必须带图标", id).isNotNull();
            assertThat(button.getStyleClass())
                    .as("%s 带文字时不许挂 adb-button--icon（会把宽度钉成 30px）", id)
                    .doesNotContain("adb-button--icon");
            assertHint(id, button);
        }
    }

    @Test
    void narrowWindowCollapsesTextButNeverTheHints(FxRobot robot) throws Exception {
        resize(NARROW_WIDTH);
        for (String id : MAIN_OPERATIONS) {
            Node button = require(robot, id);
            Labeled labeled = (Labeled) button;
            assertThat(labeled.getText())
                    .as("%s 在 %dpx 窗口下应收成纯图标", id, NARROW_WIDTH).isBlank();
            assertThat(button.getStyleClass())
                    .as("%s 收字后必须回到 30px 图标档", id).contains("adb-button--icon");
            assertThat(labeled.getGraphic()).as("%s 收字后图标不能丢", id).isNotNull();
            assertHint(id, button);
        }
        // 宽度回来，文字必须自己复原 —— 不能靠重开页面。
        resize(WIDE_WIDTH);
        for (String id : MAIN_OPERATIONS) {
            assertThat(((Labeled) require(robot, id)).getText())
                    .as("%s 回到宽窗口后文字要复原", id).isNotBlank();
        }
    }

    @Test
    void groupsAreSeparatedAndDeleteIsMarkedDangerous(FxRobot robot) throws Exception {
        List<Node> separators = new ArrayList<>();
        collect(toolbar(robot), node -> node.getStyleClass().contains("adb-toolbar__separator"),
                separators);
        assertThat(separators.size())
                .as("工具条要按语义分组（编辑 / 查询与显示 / 数据交换），组间一条竖线")
                .isGreaterThanOrEqualTo(2);

        Node delete = require(robot, "data-delete-rows");
        assertThat(delete.getStyleClass())
                .as("删除是这一排唯一会丢数据的动作，必须用 danger 色")
                .contains("adb-button--danger");
    }

    /**
     * 瞬时编辑键的分工：只有图标，但提示与朗读一样不许少。
     *
     * <p>这条门禁是改判的另一半 —— 只钉「具名操作有文字」不够，
     * 因为把编辑键的文字加回来也能让它全绿，而那一加就把 117px 欠债重新欠上。
     * 所以反向钉住：这一簇<b>不许</b>带文字，同时<b>不许</b>因为没文字就变成哑按钮。
     */
    @Test
    void editClusterIsIconOnlyButNeverSilent(FxRobot robot) throws Exception {
        for (String id : ICON_ONLY_EDITS) {
            Node button = require(robot, id);
            Labeled labeled = (Labeled) button;
            assertThat(labeled.getText())
                    .as("%s 属于瞬时编辑键，按 Navicat 的分工只该有图标（带文字会顶爆这一排）", id)
                    .isBlank();
            assertThat(labeled.getGraphic()).as("%s 只有图标，图标本身不能丢", id).isNotNull();
            assertHint(id, button);
        }
    }

    @Test
    void labeledToolbarStillFitsInAWideWindow(FxRobot robot) throws Exception {
        Node toolbar = toolbar(robot);
        Node search = require(robot, "data-search");
        double toolbarRight = toolbar.localToScene(toolbar.getBoundsInLocal()).getMaxX();
        double searchRight = search.localToScene(search.getBoundsInLocal()).getMaxX();
        assertThat(searchRight)
                .as("带文字之后工具条不能溢出：%dpx 窗口里搜索框还在框内", WIDE_WIDTH)
                .isLessThanOrEqualTo(toolbarRight + 0.5);
    }

    /**
     * 选中一行之后这一排多了一颗「删除记录」—— 不许因此把右边挤出框外。
     *
     * <p>实拍踩过（`p2-cell` 帧）：选中行 →「删除记录」整颗出现 → 总宽超过可用宽度，
     * 最右的「本页搜索」被裁到只剩一个「本」字。成因是收字档只在<b>工具条宽度变化</b>
     * 时重判，而这次变的是<b>按钮个数</b>：宽度没动，判据一次都没跑。
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

        Node delete = findById(toolbar(robot), "data-delete-rows");
        assertThat(delete).as("找不到 #data-delete-rows").isNotNull();
        assertThat(delete.isManaged())
                .as("选中行后「删除记录」没占位，这条门禁证不了任何事").isTrue();
        // 摆不下就整排退图标（合法降级），但绝不许压扁某个按钮。
        for (String id : MAIN_OPERATIONS) {
            Labeled labeled = (Labeled) require(robot, id);
            if (labeled.getText().isBlank()) {
                assertThat(labeled.getStyleClass())
                        .as("%s 收字后必须回到图标档", id).contains("adb-button--icon");
            } else {
                assertThat(((Control) labeled).getWidth())
                        .as("%s 带文字时被压扁了（%.0f < %.0f）", id,
                                ((Control) labeled).getWidth(), labeled.prefWidth(-1))
                        .isGreaterThanOrEqualTo(labeled.prefWidth(-1) - 0.5);
            }
        }
        assertToolbarFits(robot, WIDE_WIDTH);
    }

    /**
     * U4（其一）：文字档的每一档宽度里，主操作按钮要么**完整**，要么**收成图标**。
     *
     * <p>工具条是 HBox —— 放不下时它会**逐个压窄**子节点，
     * 「筛选」就变成「筛 …」。收字阈值原先只看**窗口**宽度，
     * 而窗口 ≠ 工具条宽度（侧栏和边距要吃掉几百 px），所以过了阈值不代表真放得下。
     * 现在多一条工具条自己的实测判据：整排带文字放不下就退回图标 ——
     * 于是合法状态只有两种，「压扁的文字」必须是 0 个。
     */
    @Test
    void labelsAreNeverSqueezedIntoEllipsis(FxRobot robot) throws Exception {
        for (int width : new int[]{1100, 1180, 1280}) {
            resize(width);
            for (String id : MAIN_OPERATIONS) {
                Node node = require(robot, id);
                Labeled labeled = (Labeled) node;
                if (labeled.getText().isBlank()) {
                    // 收成纯图标是**合法降级**：尺寸档、图标、tooltip 一样都不能少。
                    assertThat(node.getStyleClass())
                            .as("%s 在 %dpx 档收成了图标，就必须回到 30px 图标档", id, width)
                            .contains("adb-button--icon");
                    assertThat(labeled.getGraphic())
                            .as("%s 在 %dpx 档收字后图标不能丢", id, width).isNotNull();
                    assertHint(id, node);
                } else {
                    Control button = (Control) node;
                    assertThat(button.getWidth())
                            .as("%s 在 %dpx 档被压扁了：实际 %.0fpx < 需要的 %.0fpx"
                                            + "（文字会被截成省略号）",
                                    id, width, button.getWidth(), button.prefWidth(-1))
                            .isGreaterThanOrEqualTo(button.prefWidth(-1) - 0.5);
                }
            }
            assertToolbarFits(robot, width);
        }
        // 1280 是全套验收帧的宽度：这一档必须真的给文字，
        // 否则「主操作图标 + 文字」这条承诺等于没有。
        resize(WIDE_WIDTH);
        for (String id : MAIN_OPERATIONS) {
            assertThat(((Labeled) require(robot, id)).getText())
                    .as("%s 在 %dpx 档应该带文字", id, WIDE_WIDTH).isNotBlank();
        }
    }

    /**
     * U4（其二）：详情抽屉是浮层，开与关都不许改变工具条的宽度。
     *
     * <p>抽屉原来和数据区并排在同一个 HBox 里 —— 一开就把工具条挤窄，
     * 「筛 …」「导 …」就是这么来的。现在它挂在 body 的 StackPane 上，
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
        DataGridPage page = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup(".adb-grid-page").queryAs(DataGridPage.class)).get();
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

    /** 工具条整排（含搜索框）都在自己的框内 —— 不是只查搜索框那一个点。 */
    private void assertToolbarFits(FxRobot robot, int width) throws Exception {
        Region toolbar = toolbar(robot);
        double toolbarRight =
                toolbar.localToScene(toolbar.getBoundsInLocal()).getMaxX();
        // ⭐ 框自己也不许被撑出场景：minWidth 钉死时溢出的是**整条**，
        //    子节点个个都在「自己的框」里，可框有一大截在窗外（p2-cell 实拍 1406 > 1280）。
        //    ⚠️ 1100 档不查这条：那一档是**分页器**的最小宽度把整页撑出去的
        //    （G12 / R32-D 的「1280 宽下分页器被裁」），跟工具条无关。
        if (width >= 1180) {
            double sceneRight = toolbar.getScene() == null
                    ? Double.MAX_VALUE : toolbar.getScene().getWidth();
            assertThat(toolbarRight)
                    .as("工具条在 %dpx 档被撑出场景（右边缘 %.0f > 场景 %.0f）—— 它不肯收缩",
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

    /** 工具条里所有**可见且占位**的后代（隐藏的按钮不算溢出）。 */
    private static List<Node> cascade(Region toolbar) {
        List<Node> all = new ArrayList<>();
        collect(toolbar, node -> node != toolbar && node.isVisible() && node.isManaged(), all);
        return all;
    }

    /**
     * 导入向导必须把当前表预选成目标表 —— 这是数据页「导入…」存在的全部理由。
     *
     * <p>走 {@code createImportWizard} 这个接缝：{@code show()} 是模态的，
     * 一点就卡住 FX 线程，门禁拿不到断言时机。
     */
    @Test
    void importWizardPreselectsTheCurrentTable(FxRobot robot) throws Exception {
        DataGridPage page = WaitForAsyncUtils.asyncFx(
                () -> robot.lookup(".adb-grid-page").queryAs(DataGridPage.class)).get();
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

    // ─────────────────────────── 助手 ───────────────────────────

    private static void assertHint(String id, Node button) {
        Tooltip tooltip = ((Control) button).getTooltip();
        assertThat(tooltip).as("%s 必须有 tooltip", id).isNotNull();
        assertThat(tooltip.getText()).as("%s 的 tooltip 不能是空的", id).isNotBlank();
        assertThat(button.getAccessibleText())
                .as("%s 必须有 accessibleText（读屏要念得出来）", id).isNotBlank();
    }

    /**
     * 改窗口宽度并等布局跑完。
     *
     * <p>⚠️ Monocle 无头窗口有最小宽度（实测 900 会被抬到 960），
     * 所以这里不断言「正好多少 px」，只断言「确实跨过了收字阈值」——
     * 前提成立即可，收没收到字由各用例自己判。
     */
    private void resize(int width) throws Exception {
        WaitForAsyncUtils.asyncFx(() -> stage.setWidth(width)).get();
        WaitForAsyncUtils.waitForFxEvents(3);
        boolean requestedWide = width >= DataGridPage.TOOLBAR_LABEL_MIN_WIDTH;
        assertThat(stage.getWidth() >= DataGridPage.TOOLBAR_LABEL_MIN_WIDTH)
                .as("窗口没能跨过收字阈值（请求 %dpx，实际 %s px）",
                        width, String.format("%.0f", stage.getWidth()))
                .isEqualTo(requestedWide);
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
