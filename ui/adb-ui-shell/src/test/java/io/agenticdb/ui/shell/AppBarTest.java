package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 应用栏门禁（§5.1）。
 *
 * <p>应用栏是**全应用唯一的全局入口层** —— 门禁要钉住三件事：
 * <ol>
 *   <li>品牌、当前连接、全局搜索、主题、设置、更多**都在场景图里**
 *       （本项目反复踩过「控件建了、事件绑了，却从未加入 header」的坑）；</li>
 *   <li>当前连接胶囊显示的是**真名**，下拉里是**真实连接列表**，切换回调真的传出去；</li>
 *   <li>纯图标按钮都有 Tooltip 与 accessible text（读屏能读出语义）。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class AppBarTest {

    private AppBar bar;
    /** 记录被选中的连接。 */
    private final List<ConnectionProfile> selected = new ArrayList<>();

    @Start
    void start(Stage stage) {
        ActionRegistry actions = new ActionRegistry();
        actions.register("new-query", "新建查询", "文件", null, () -> { });
        actions.register("connections.manage", "连接管理", "连接", null, () -> { });
        actions.register("theme.toggle", "切换主题", "视图", null, () -> { });
        actions.register("settings", "设置", "工具", null, () -> { });
        actions.register("command-palette", "命令面板", "编辑", null, () -> { });
        actions.register("object.tables", "表清单", "对象", null, () -> { });
        actions.register("import-export", "导入 / 导出", "工具", null, () -> { });
        actions.register("diagnostics", "诊断", "工具", null, () -> { });
        actions.register("shortcuts", "快捷键", "帮助", null, () -> { });
        actions.register("about", "关于", "帮助", null, () -> { });
        actions.register("connection.reconnect", "重新连接", "数据库", null, () -> { });
        actions.register("connection.disconnect", "断开连接", "数据库", null, () -> { });

        List<ConnectionProfile> profiles = List.of(
                profile("demo", "agenticdb-demo.db", DialectKind.SQLITE),
                profile("pg", "prod-postgres", DialectKind.POSTGRESQL));

        bar = new AppBar(actions, profiles, "demo", selected::add);
        stage.setScene(new Scene(bar, 1280, 800));
        stage.show();
    }

    private static ConnectionProfile profile(String id, String name, DialectKind kind) {
        return new ConnectionProfile(id, name, kind, "", 0, "", "", false, 0, Map.of());
    }

    @Test
    void everyGlobalEntryIsActuallyInTheScene(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⭐ 「建好了但没挂上去」是本项目反复踩过的坑（UI-AUDIT S2）。
        for (String id : List.of("#appbar-brand-mark", "#appbar-brand-name",
                "#appbar-connection-menu", "#appbar-search", "#appbar-theme",
                "#appbar-settings", "#appbar-more", "#appbar-new-query",
                "#appbar-manage-connections")) {
            assertThat(robot.lookup(id).tryQuery()).as(id + " 必须真的在场景图里").isPresent();
        }
    }

    @Test
    void theBrandShowsTheProductName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#appbar-brand-name").queryAs(Label.class).getText())
                .isEqualTo("AIDB");
    }

    @Test
    void theConnectionPillShowsTheRealConnectionName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#appbar-connection-name").queryAs(Label.class).getText())
                .isEqualTo("agenticdb-demo.db");
        assertThat(robot.lookup("#appbar-connection-kind").queryAs(Label.class).getText())
                .isEqualTo("SQLite");
    }

    @Test
    void theConnectionMenuListsEveryConnection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        MenuButton menu = robot.lookup("#appbar-connection-menu").queryAs(MenuButton.class);
        List<String> labels = menu.getItems().stream()
                .map(javafx.scene.control.MenuItem::getText).toList();
        assertThat(labels)
                .as("下拉里必须有全部连接 + 管理 / 重连 / 断开")
                .contains("agenticdb-demo.db  ·  SQLite", "prod-postgres  ·  PostgreSQL",
                        "管理连接…", "重新连接", "断开连接");
    }

    @Test
    void switchingAConnectionFiresTheCallback(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        MenuButton menu = robot.lookup("#appbar-connection-menu").queryAs(MenuButton.class);
        menu.getItems().stream()
                .filter(item -> item.getText().startsWith("prod-postgres"))
                .findFirst()
                .ifPresent(item -> item.fire());
        assertThat(selected).as("切换连接必须真的回调出去").hasSize(1);
        assertThat(selected.getFirst().name()).isEqualTo("prod-postgres");
    }

    @Test
    void theConnectionDotReflectsTheConnectionState(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        for (boolean[] state : new boolean[][]{{true, false}, {false, true}, {false, false}}) {
            bar.setConnectionState(state[0], state[1]);
            assertThat(robot.lookup("#appbar-connection-name").tryQuery())
                    .as("状态点只是视觉反馈 —— 不允许因此丢失节点或抛异常")
                    .isPresent();
        }
    }

    @Test
    void iconOnlyButtonsDeclareTooltipAndAccessibleText(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⭐ 纯图标按钮少一个 Tooltip 或 accessible text 就不合格。
        for (String id : List.of("#appbar-theme", "#appbar-settings",
                "#appbar-new-query", "#appbar-manage-connections")) {
            Button button = robot.lookup(id).queryAs(Button.class);
            assertThat(button.getTooltip()).as(id + " 的 Tooltip").isNotNull();
            assertThat(button.getAccessibleText()).as(id + " 的 accessibleText").isNotBlank();
        }
    }

    @Test
    void theOverflowMenuCarriesRealActions(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        MenuButton more = robot.lookup("#appbar-more").queryAs(MenuButton.class);
        List<String> ids = more.getItems().stream()
                .map(javafx.scene.control.MenuItem::getId).toList();
        assertThat(ids)
                .as("更多菜单里的每一项都要是可执行的动作")
                .contains("connections.manage", "new-query", "object.tables",
                        "import-export", "diagnostics", "shortcuts", "about");
    }

    @Test
    void theBarHasOneFixedHeightAndDoesNotGrowWithContent() {
        assertThat(bar.getPrefHeight())
                .as("应用栏高度来自令牌 h-appbar（46px）")
                .isEqualTo(io.agenticdb.ui.Tokens.H_APPBAR_LIGHT);
        assertThat(bar.getMaxHeight()).isEqualTo(bar.getPrefHeight());
        assertThat(bar.getMinHeight()).isEqualTo(bar.getPrefHeight());
    }

    @Test
    void theBrandMarkUsesTheVectorIconSystem(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#appbar-brand-mark").queryAs(javafx.scene.layout.VBox.class)
                .lookupAll(".adb-icon"))
                .as("品牌标识必须是矢量图标（不是位图、不是 Unicode 符号）")
                .isNotEmpty();
    }
}
