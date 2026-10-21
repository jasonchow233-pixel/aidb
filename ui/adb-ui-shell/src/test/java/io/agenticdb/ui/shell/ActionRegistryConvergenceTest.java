package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
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
            Button refresh = (Button) shell.lookup("#refresh-button");
            if (refresh == null) {
                return null;
            }
            boolean before = refresh.isDisable();
            shell.actionRegistry().require("refresh").setEnabled(false);
            boolean after = refresh.isDisable();
            shell.actionRegistry().require("refresh").setEnabled(true);
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
        assertThat(before).isEqualTo("未选择");

        onFxRun(() -> {
            TableView<?> table = (TableView<?>) shell(robot).lookup("#data-table");
            table.getSelectionModel().selectAll();
        });
        WaitForAsyncUtils.waitForFxEvents();

        String after = onFx(() -> shell(robot).statusBar().selectionText());
        assertThat(after)
                .as("状态栏的「选择」段以前永远是「未选择」（4.5-11）")
                .startsWith("已选 ")
                .contains("行");
    }

    @Test
    void statusBarTransactionDefaultsToAutoCommit(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> shell(robot).statusBar().transactionText())).isEqualTo("自动提交");
    }

    // ─────────────────── A19 密度覆盖数据网格行高 ───────────────────

    @Test
    void densityChangesTheGridRowHeight(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        Double normal = onFx(() -> shell(robot).workspace().activeGrid().rowHeight());
        onFxRun(() -> shell(robot).applyDensityForTest("compact"));
        Double compact = onFx(() -> shell(robot).workspace().activeGrid().rowHeight());
        onFxRun(() -> shell(robot).applyDensityForTest("comfort"));
        Double comfort = onFx(() -> shell(robot).workspace().activeGrid().rowHeight());
        onFxRun(() -> shell(robot).applyDensityForTest("normal"));

        assertThat(normal).isNotNull();
        assertThat(compact).isLessThan(normal);
        assertThat(comfort).isGreaterThan(normal);
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
