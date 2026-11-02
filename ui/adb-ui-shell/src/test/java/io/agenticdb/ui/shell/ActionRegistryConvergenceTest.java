package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 批次的门禁：动作注册表收口 + 状态栏接真实数据。
 *
 * <p>覆盖 4.5-8 / 4.5-9 / 4.5-10 / 4.5-11 / 4.5-29 / 4.5-30，以及 A19（密度覆盖网格行高）。
 */
@ExtendWith(ApplicationExtension.class)
class ActionRegistryConvergenceTest {

    private static Path demoDirectory;

    @BeforeAll
    static void isolateDemoDatabase() throws IOException {
        demoDirectory = Files.createTempDirectory("adb-action-test-");
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
        new App().start(stage);
    }

    // ─────────────────── 4.5-8 侧栏树右键菜单进注册表 ───────────────────

    @Test
    void objectTreeContextMenuItemsComeFromTheRegistry(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> ids = onFx(() -> {
            List<String> found = new ArrayList<>();
            ContextMenu menu = shell(robot).sideBar().contextMenuForTest();
            if (menu != null) {
                for (MenuItem item : menu.getItems()) {
                    if (item.getId() != null) {
                        found.add(item.getId());
                    }
                }
            }
            return found;
        });

        assertThat(ids)
                .as("侧栏树的右键菜单必须走 ActionRegistry（4.5-8）")
                .contains("object.open-data", "object.open-structure", "object.new-query",
                        "object.favorite", "object.copy-name", "object.copy-select",
                        "object.diagnostics");
    }

    /**
     * ⭐ R34 第八批：菜单项的**文字**也必须来自动作自己。
     *
     * <p>上一版这里 28 个 `menuItem(id, …)` 里有 27 个带中文覆盖，其中 21 个与动作的
     * label 逐字相同 —— 那层覆盖本身就是本地化 bug：{@code menuItemFor} 是
     * {@code labelOverride == null ? action.label() : labelOverride}，于是英文构建里
     * 本地化好的动作名又被中文盖回去。删掉覆盖之后，这条钉住"删了之后真的生效了"。
     *
     * <p>⚠️ 这条闸补的是**证据面**的洞：对象树的右键菜单不在任何一张验收帧里，
     * 上面那条 ids 断言只比 id、不比文字 —— 没有这条，21 处改动等于没人看过。
     * 剩下 6 处覆盖是**故意的**（措辞确实和动作名不同：「编辑连接」「打开查询」
     * 「刷新」「打开」，以及收藏/取消收藏那一对照），列在下面，不在名单里的一律算漂移。
     */
    @Test
    void objectTreeMenuLabelsComeFromTheActionsThemselves(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> drifted = onFx(() -> {
            List<String> found = new ArrayList<>();
            ContextMenu menu = shell(robot).sideBar().contextMenuForTest();
            if (menu == null) {
                return List.of("右键菜单为 null —— 这条门禁在空转");
            }
            ActionRegistry registry = shell(robot).actionRegistry();
            int checked = 0;
            for (MenuItem item : menu.getItems()) {
                if (item.getId() == null || DELIBERATE_OVERRIDES.contains(item.getId())) {
                    continue;
                }
                checked++;
                Action action = registry.find(item.getId()).orElse(null);
                if (action == null) {
                    found.add(item.getId() + " 不在注册表里");
                } else if (!action.label().equals(item.getText())) {
                    found.add(item.getId() + " 显示「" + item.getText()
                            + "」而动作名叫「" + action.label() + "」");
                }
            }
            if (checked < 8) {
                found.add("表节点菜单只比了 " + checked + " 项（应有 9 项走注册表）"
                        + " —— 白名单写宽了，这条闸在空转");
            }
            return found;
        });

        assertThat(drifted)
                .as("菜单项文字与动作 label 不一致（一个动作一个名字；要换措辞就改动作，"
                        + "或在 DELIBERATE_OVERRIDES 里说明为什么这里必须不同）：\n  %s",
                        String.join("\n  ", drifted))
                .isEmpty();
    }

    /**
     * 表节点菜单里刻意与动作 label 不同的覆盖。
     *
     * <p>只有这一条：「收藏 / 取消收藏」随当前状态二选一，动作自己的 label 是
     * 合并写法（{@code action.object.favorite=收藏 / 取消收藏}），不能直接当菜单文字。
     * 其余四处覆盖（连接节点的「编辑连接」、已保存查询分组行的「刷新」、
     * 查询节点的「打开查询」、default 分支的「打开」）长在**别的节点种类**上，
     * 本用例走的是表节点，不进这份名单 —— 写宽了就会把表节点那 9 项一起放过。
     */
    private static final List<String> DELIBERATE_OVERRIDES = List.of("object.favorite");

    @Test
    void objectTreeActionsAreRegisteredAndDiscoverable(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        List<String> ids = onFx(() -> shell(robot).actionRegistry().all().stream()
                .map(Action::id).toList());

        assertThat(ids)
                .as("对象树动作要进注册表，才能在命令面板里搜到")
                .contains("object.open-data", "object.copy-select", "object.diagnostics");
    }

    // ─────────────────── 4.5-10 两个动作要有菜单入口 ───────────────────

    @Test
    void previouslyOrphanActionsNowHaveMenuEntries(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        List<String> ids = onFx(() -> {
            List<String> found = new ArrayList<>();
            MenuBar bar = (MenuBar) shell(robot).lookup(".menu-bar");
            for (Menu menu : bar.getMenus()) {
                collectIds(menu, found);
            }
            return found;
        });

        assertThat(ids)
                .as("connections.manage / connection.reconnect 以前只注册没入口（4.5-10）")
                .contains("connections.manage", "connection.reconnect");
    }

    // ─────────────────── 4.5-9 工具栏图标按钮绑 enabled ───────────────────

    @Test
    void toolbarIconButtonsFollowActionEnabledState(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        Boolean ok = onFx(() -> {
            AppShell shell = shell(robot);
            Button theme = (Button) shell.lookup("#appbar-theme");
            if (theme == null) {
                return null;
            }
            boolean before = theme.isDisable();
            shell.actionRegistry().require("theme.toggle").setEnabled(false);
            boolean after = theme.isDisable();
            shell.actionRegistry().require("theme.toggle").setEnabled(true);
            return before == false && after == true;
        });

        assertThat(ok)
                .as("动作禁用时工具栏图标按钮必须同步变灰（4.5-9）")
                .isTrue();
    }

    // ─────────────────── 4.5-29 标签栏右键菜单进注册表 ───────────────────

    @Test
    void tabContextMenuItemsComeFromTheRegistry(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> ids = onFx(() -> {
            List<String> found = new ArrayList<>();
            ContextMenu menu = shell(robot).tabBar().contextMenuForTest();
            if (menu != null) {
                for (MenuItem item : menu.getItems()) {
                    if (item.getId() != null) {
                        found.add(item.getId());
                    }
                }
            }
            return found;
        });

        assertThat(ids)
                .as("标签栏右键菜单必须走 ActionRegistry（4.5-29）")
                .contains("tab.close", "tab.close-others", "tab.close-all");
    }

    // ─────────────────── 4.5-30 禁用动作给出反馈 ───────────────────

    @Test
    void disabledActionExplainsItselfInsteadOfSilentlyDoingNothing() {
        ActionRegistry actions = new ActionRegistry();
        List<String> messages = new ArrayList<>();
        actions.setFeedback(messages::add);
        actions.register("demo", "演示动作", "测试", null, () -> { });

        onFxRun(() -> actions.require("demo").setEnabled(false));
        onFxRun(() -> actions.run("demo"));

        assertThat(messages)
                .as("禁用动作必须解释自己，不能静默 no-op（4.5-30）")
                .isNotEmpty()
                .anyMatch(message -> message.contains("演示动作"));
    }

    // ─────────────────── 4.5-11 状态栏接真实数据 ───────────────────

    @Test
    void statusBarShowsDialectInsteadOfAFakeDevBadge(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        String dialect = onFx(() -> shell(robot).statusBar().dialectText());

        assertThat(dialect)
                .as("状态栏左端以前写死「DEV」，现在显示真实方言（4.5-11）")
                .isNotEqualTo("DEV")
                .isEqualTo("SQLite");
    }

    @Test
    void statusBarSelectionFollowsTheDataPage(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        String before = onFx(() -> shell(robot).statusBar().selectionText());
        assertThat(before).isEqualTo(I18n.t("status.noneSelected"));

        onFxRun(() -> {
            TableView<?> table = (TableView<?>) shell(robot).lookup("#data-table");
            table.getSelectionModel().selectAll();
        });
        WaitForAsyncUtils.waitForFxEvents();

        String after = onFx(() -> shell(robot).statusBar().selectionText());
        // 这条门禁要钉的是"选择段反映了真实行数"，不是"这句话用中文怎么说" ——
        // 所以断言「不等于空态词 + 含数字」，换语言也不用改（原来写死 startsWith("已选 ")）。
        assertThat(after)
                .as("状态栏的「选择」段以前永远是「未选择」（4.5-11）")
                .isNotEqualTo(I18n.t("status.noneSelected"))
                .matches("(?s).*\\d.*");
    }

    // ─────────────────── A19 密度覆盖数据网格行高 ───────────────────

    @Test
    void densityChangesTheGridRowHeight(FxRobot robot) {
        openTableTab(robot, "users", "tab-grid-demo-users");

        // ⭐ 四档全部显式设定：起点不能是「机器上次留下的偏好」——
        //    那样这条断言测的是跑它的人的历史，不是密度档位本身。
        //    （以前它读到的「标准」其实是 22px 的 dense，于是 24 < 22 不成立。）
        Double comfort = rowHeightAt(robot, "comfort");
        Double normal = rowHeightAt(robot, "normal");
        Double compact = rowHeightAt(robot, "compact");
        Double dense = rowHeightAt(robot, "dense");

        assertThat(normal).isNotNull();
        assertThat(compact).isLessThan(normal);
        assertThat(dense).isLessThan(compact);
        assertThat(comfort).isGreaterThan(normal);
    }

    /**
     * 双击树里的表节点，并等它的数据页<b>真的开出来</b>（与 {@code AccessibilityAuditTest}
     * 的同名助手同一条理由，R40 记录）：TestFX 的 {@code doubleClickOn} 是两次独立单击，
     * 机器压满时两次脉冲的间隔越过连击判定窗口，JavaFX 只看到两次单击 → 页根本不开。
     *
     * <p>⚠️ 这条原来直接往下读 {@code workspace().activeGrid().rowHeight()}：页没开时
     * NPE 落在 FX 线程上被 {@code onFx} 吞掉，回一个 {@code null}，于是报的是
     * 「Expecting actual not to be null」—— 看着像网格坏了，其实是<b>页没开</b>。
     * 三次仍开不出来就是真失败，不拿重试盖住链路故障。
     */
    private void openTableTab(FxRobot robot, String table, String tabId) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            robot.doubleClickOn(treeCell(robot, table));
            WaitForAsyncUtils.waitForFxEvents();
            try {
                WaitForAsyncUtils.waitFor(3, java.util.concurrent.TimeUnit.SECONDS, () ->
                        robot.lookup("#" + tabId).tryQuery().isPresent());
                return;
            } catch (java.util.concurrent.TimeoutException ignored) {
                // 连击没被识别：重取单元格再双击一次。
            }
        }
        throw new AssertionError("双击树节点「" + table + "」三次仍没开出标签 #" + tabId
                + " —— 不是重试能盖住的慢，要查「双击 → 开数据页」这条链路");
    }

    private static Double rowHeightAt(FxRobot robot, String density) {
        onFxRun(() -> shell(robot).applyDensityForTest(density));
        return onFx(() -> shell(robot).workspace().activeGrid().rowHeight());
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private static AppShell shell(FxRobot robot) {
        return robot.lookup(".app-shell").queryAs(AppShell.class);
    }

    private static void collectIds(Menu menu, List<String> out) {
        for (MenuItem item : menu.getItems()) {
            if (item.getId() != null) {
                out.add(item.getId());
            }
            if (item instanceof Menu sub) {
                collectIds(sub, out);
            }
        }
    }

    private static javafx.scene.Node treeCell(FxRobot robot, String name) {
        return robot.lookup(node -> node instanceof javafx.scene.control.TreeCell<?> cell
                        && cell.getTreeItem() != null
                        && cell.getTreeItem().getValue() != null
                        && cell.getTreeItem().getValue().toString()
                                .toLowerCase(java.util.Locale.ROOT).contains(name))
                .query();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
