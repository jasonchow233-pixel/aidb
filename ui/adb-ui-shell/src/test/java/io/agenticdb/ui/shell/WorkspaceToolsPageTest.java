package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门禁：查询历史与诊断必须是<b>真功能</b>，不是空壳。
 *
 * <p>以前这两个入口开的是一句「该工具只保留入口」的占位页 ——
 * 按项目规则「占位要么接要么删」，现在都接上了真功能。
 *
 * <p>⭐ R37 N13：侧栏的三个导航项撤掉后，入口只剩菜单 / 功能区动作；
 * 这里走 {@code actionRegistry()}，验的是「动作真的开得出页面」，而不是某个按钮的位置。
 */
@ExtendWith(ApplicationExtension.class)
class WorkspaceToolsPageTest {

    private static Path demoDirectory;
    private static String prefsNode;

    @BeforeAll
    static void isolateDemoDatabase() throws IOException {
        demoDirectory = Files.createTempDirectory("adb-tools-test-");
        System.setProperty("adb.demo.db", demoDirectory.resolve("demo.db").toString());
        System.setProperty("adb.connections.file", demoDirectory.resolve("connections.json").toString());
        // ⭐ 偏好也要隔离：共享节点里上次留下的「恢复标签」会让场景里多出一个
        //    查询编辑器，lookup 抓到的可能不是当前页 —— 门禁会以「历史为空」的
        //    假象变红（实测）。每次跑一个一次性节点，结束即删。
        prefsNode = "io/agenticdb/tests/tools-" + System.nanoTime();
        System.setProperty("adb.prefs.node", prefsNode);
    }

    @AfterAll
    static void cleanDemoDatabase() throws IOException {
        System.clearProperty("adb.demo.db");
        System.clearProperty("adb.connections.file");
        System.clearProperty("adb.prefs.node");
        if (prefsNode != null) {
            try {
                Preferences.userRoot().node(prefsNode).removeNode();
            } catch (java.util.prefs.BackingStoreException ignored) {
                // 偏好节点清理失败不影响测试结论。
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

    @Start
    void start(Stage stage) {
        SettingsStore preferences = new SettingsStore();
        preferences.set("ui.theme", "light");
        preferences.set("ui.density", "normal");
        preferences.setBoolean("ui.sidebar.collapsed", false);
        preferences.setInt("ui.sidebar.width", 240);
        preferences.setBoolean("ui.details.visible", false);
        // ⭐ 窗口几何必须钉住：隔离出来的偏好节点里没有尺寸，若窗口落到
        //    可视区之外 / 尺寸异常，`clickOn` 的坐标点会落空 ——
        //    表现为「点新建查询没反应」，而 lookup 却能找到按钮（实测踩到）。
        preferences.setInt("ui.window.width", 1280);
        preferences.setInt("ui.window.height", 800);
        preferences.setInt("ui.window.x", 0);
        preferences.setInt("ui.window.y", 0);
        preferences.set("query.history", "[]");
        new App().start(stage);
    }

    @Test
    void queryHistoryPageListsExecutedSql(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();

        // 先跑一条 SQL，让它进历史。
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);
        robot.clickOn("#ribbon-new-query");
        WaitForAsyncUtils.waitForFxEvents();
        CodeArea editor = robot.lookup("#query-editor").queryAs(CodeArea.class);
        WaitForAsyncUtils.waitForAsyncFx(5_000,
                () -> editor.replaceText("SELECT 7 AS seven;"));
        robot.clickOn("#query-run");
        // 只等节点存在不算「跑完了」——历史是在结果回来之后才落一条的。
        // 等结果页签真的出现（与 AppShellTest 同一条判定），否则历史页刚开还读不到。
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                robot.lookup("#query-results").queryAs(javafx.scene.control.TabPane.class)
                        .getTabs().size() == 1);
        WaitForAsyncUtils.waitForFxEvents();

        // 「工具 → 查询历史」（侧栏导航项已撤，入口在菜单 / 功能区）。
        WaitForAsyncUtils.asyncFx(() -> shell.actionRegistry().run("query-history")).get();
        WaitForAsyncUtils.waitForFxEvents();

        ListView<?> list = robot.lookup("#history-list").queryAs(ListView.class);
        assertThat(list).as("查询历史必须是真页面，不能是空壳").isNotNull();
        assertThat(list.getItems())
                .as("执行过的 SQL 应当出现在历史列表里")
                .isNotEmpty();
        assertThat(String.valueOf(list.getItems().getFirst()))
                .contains("SELECT 7");
    }

    @Test
    void diagnosticsPageReportsTableHealth(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();

        // 选中对象树里的 users，再走「工具 → 诊断」动作。
        selectTreeCell(robot, "users");
        AppShell shell = robot.lookup(".app-shell").queryAs(AppShell.class);
        WaitForAsyncUtils.asyncFx(() -> shell.actionRegistry().run("diagnostics")).get();
        WaitForAsyncUtils.waitForFxEvents();

        Label title = robot.lookup("#diagnostics-title").queryAs(Label.class);
        assertThat(title).as("诊断必须是真页面，不能是空壳").isNotNull();
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> title.getText() != null && title.getText().contains("users"));
        assertThat(title.getText()).contains("users");
    }

    private static void selectTreeCell(FxRobot robot, String name) {
        javafx.scene.control.TreeCell<?> cell = robot.lookup(node ->
                        node instanceof javafx.scene.control.TreeCell<?> c
                                && c.getTreeItem() != null
                                && c.getTreeItem().getValue() != null
                                && String.valueOf(c.getTreeItem().getValue()).contains(name))
                .queryAs(javafx.scene.control.TreeCell.class);
        assertThat(cell).as("对象树里应有「%s」", name).isNotNull();
        robot.clickOn(cell);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
