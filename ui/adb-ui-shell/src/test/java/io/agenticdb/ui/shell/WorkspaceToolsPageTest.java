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
 * 门禁：侧栏的「查询历史」与「诊断」必须是<b>真功能</b>，不是空壳。
 *
 * <p>以前这两个入口开的是一句「该工具只保留入口」的占位页 ——
 * 按项目规则「占位要么接要么删」，现在都接上了真功能。
 */
@ExtendWith(ApplicationExtension.class)
class WorkspaceToolsPageTest {

    private static Path demoDirectory;

    @BeforeAll
    static void isolateDemoDatabase() throws IOException {
        demoDirectory = Files.createTempDirectory("adb-tools-test-");
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
        preferences.put("query.history", "[]");
        new App().start(stage);
    }

    @Test
    void queryHistoryPageListsExecutedSql(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();

        // 先跑一条 SQL，让它进历史。
        robot.clickOn("#appbar-new-query");
        WaitForAsyncUtils.waitForFxEvents();
        CodeArea editor = robot.lookup("#query-editor").queryAs(CodeArea.class);
        WaitForAsyncUtils.waitForAsyncFx(5_000,
                () -> editor.replaceText("SELECT 7 AS seven;"));
        robot.clickOn("#query-run");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                robot.lookup("#query-results") != null);
        WaitForAsyncUtils.waitForFxEvents();

        // 侧栏的「查询历史」入口。
        clickSidebar(robot, "查询历史");
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

        // 选中对象树里的 users，再从侧栏的「诊断」入口进。
        selectTreeCell(robot, "users");
        clickSidebar(robot, "诊断");
        WaitForAsyncUtils.waitForFxEvents();

        Label title = robot.lookup("#diagnostics-title").queryAs(Label.class);
        assertThat(title).as("诊断必须是真页面，不能是空壳").isNotNull();
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> title.getText() != null && title.getText().contains("users"));
        assertThat(title.getText()).contains("users");
    }

    private static void clickSidebar(FxRobot robot, String text) {
        // 侧栏按钮的文案带图标前缀（"◷  查询历史"），所以用包含匹配。
        javafx.scene.Node button = robot.lookup(node -> node instanceof javafx.scene.control.Button b
                        && b.getText() != null && b.getText().contains(text))
                .query();
        assertThat(button).as("侧栏按钮「%s」应存在", text).isNotNull();
        robot.clickOn(button);
        WaitForAsyncUtils.waitForFxEvents();
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
