package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
 * <p>应用栏是**全应用唯一的全局入口层** —— 门禁要钉住四件事：
 * <ol>
 *   <li>当前连接、全局搜索、主题、更多**都在场景图里**
 *       （本项目反复踩过「控件建了、事件绑了，却从未加入 header」的坑）；</li>
 *   <li>当前连接胶囊显示的是**真名**，下拉里是**真实连接列表**，切换回调真的传出去；</li>
 *   <li>纯图标按钮都有 Tooltip 与 accessible text（读屏能读出语义）；</li>
 *   <li>⭐ 2026-10-28 顶区精简之后：<b>品牌行不再存在</b>、命令带只剩
 *       六个不重复的全局命令、窄窗下工具条切紧凑形态。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class AppBarTest {

    private AppBar bar;
    /** 工具条，外壳在真实装配里把它挂进标签栏那一行。 */
    private HBox chromeRow;
    /** 记录被选中的连接。 */
    private final List<ConnectionProfile> selected = new ArrayList<>();

    @Start
    void start(Stage stage) {
        ActionRegistry actions = new ActionRegistry();
        actions.register("new-connection", "新建连接", "文件", null, () -> { });
        actions.register("new-query", "新建查询", "文件", null, () -> { });
        actions.register("connections.manage", "连接管理", "连接", null, () -> { });
        actions.register("object.new-table", "新建表", "对象", null, () -> { });
        actions.register("saved-queries", "已保存查询", "工具", null, () -> { });
        actions.register("favorites.open", "收藏夹", "收藏夹", null, () -> { });
        actions.register("refresh", "刷新", "数据库", null, () -> { });
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

        // 与 AppShell 的真实装配一致：命令带在上，工具条挂在标签栏那一行。
        chromeRow = new HBox(bar.utilityBar());
        chromeRow.setId("adb-chrome-row");
        chromeRow.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(bar, chromeRow);
        Scene scene = new Scene(root, 1280, 800);
        // ⭐ 必须挂样式表：App 里由 ThemeManager 挂，测试里不挂则 CSS 一条都不生效，
        //    紧凑形态断言会全部假绿。
        // ⚠️ 主题类挂在**根**上（真实装配里 ThemeManager 就是挂在场景根）：
        //    挂在 AppBar 上时，搬到 chromeRow 里的工具条收不到 -adb-* 变量，
        //    连接状态点会算成纯黑（0x000000）—— 门禁当场抓到过。
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    private static ConnectionProfile profile(String id, String name, DialectKind kind) {
        return new ConnectionProfile(id, name, kind, "", 0, "", "", false, 0, "", Map.of());
    }

    @Test
    void everyGlobalEntryIsActuallyInTheScene(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⭐ 「建好了但没挂上去」是本项目反复踩过的坑。
        for (String id : List.of("#appbar-connection-menu", "#appbar-search",
                "#appbar-theme", "#appbar-more")) {
            assertThat(robot.lookup(id).tryQuery()).as(id + " 必须真的在场景图里").isPresent();
        }
        // §2.1：「设置」不再是顶栏独立齿轮 —— 低频入口搬进 ⋯，右侧只留「主题 + 更多」。
        assertThat(robot.lookup("#appbar-settings").tryQuery())
                .as("独立设置齿轮必须已并入 ⋯ 菜单")
                .isEmpty();
    }

    /**
     * ⭐ 2026-10-28 顶区精简：品牌块整块删掉。
     *
     * <p>窗口标题与任务栏图标已经写着 AIDB，顶栏再占一段「蓝方块 + AIDB」
     * 就是纯粹的垂直/水平浪费 —— 用户原话「AIDBlogo 那一栏有多多余」。
     * 这条门禁防的是它悄悄长回来。
     */
    @Test
    void theBrandBlockIsGoneFromTheTopChrome(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        for (String id : List.of("#appbar-brand-mark", "#appbar-brand-name",
                "#appbar-brand-glyph")) {
            assertThat(robot.lookup(id).tryQuery())
                    .as(id + " 必须已随顶区精简删除（品牌由窗口标题 / 任务栏图标承担）")
                    .isEmpty();
        }
    }

    /**
     * 命令带只剩**不与侧栏 / 页内工具条 / ⋯ 菜单重复**的六个命令。
     *
     * <p>表清单、刷新、诊断、设置都还有第二条入口（侧栏 / F5 + 对象页 / ⋯ 菜单），
     * 在 64px 高的大图标带上再出现一遍只是让顶栏更挤。
     */
    @Test
    void theRibbonKeepsOnlyCommandsThatHaveNoSecondHome(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⚠️ 不能按 `#ribbon-object.new-table` 查找：JavaFX 选择器把 `.` 当类分隔符，
        //    带点的 id 永远查不到。直接比对命令带上每个按钮的 id。
        List<String> ids = robot.lookup(".adb-ribbon__button").queryAllAs(Button.class)
                .stream().map(Button::getId).toList();
        assertThat(ids)
                .as("命令带只剩六个不与侧栏 / 页内工具条 / ⋯ 菜单重复的全局命令")
                .containsExactly("ribbon-new-connection", "ribbon-new-query",
                        "ribbon-object.new-table", "ribbon-import-export",
                        "ribbon-saved-queries", "ribbon-favorites.open");
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
        for (String id : List.of("#appbar-theme", "#appbar-search")) {
            Button button = robot.lookup(id).queryAs(Button.class);
            assertThat(button.getTooltip()).as(id + " 的 Tooltip").isNotNull();
            assertThat(button.getAccessibleText()).as(id + " 的 accessibleText").isNotBlank();
        }
        MenuButton more = robot.lookup("#appbar-more").queryAs(MenuButton.class);
        assertThat(more.getTooltip()).as("#appbar-more 的 Tooltip").isNotNull();
        assertThat(more.getAccessibleText()).as("#appbar-more 的 accessibleText").isNotBlank();
    }

    @Test
    void theOverflowMenuCarriesRealActions(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        MenuButton more = robot.lookup("#appbar-more").queryAs(MenuButton.class);
        List<String> ids = more.getItems().stream()
                .map(javafx.scene.control.MenuItem::getId).toList();
        assertThat(ids)
                .as("更多菜单里的每一项都要是可执行的动作；"
                        + "命令带裁掉的四个命令必须都能从这里到达")
                .contains("connections.manage", "new-query", "object.tables",
                        "import-export", "refresh", "settings", "diagnostics",
                        "shortcuts", "about");
    }

    @Test
    void theBarHasOneFixedHeightAndDoesNotGrowWithContent() {
        assertThat(bar.getPrefHeight())
                .as("2026-10-28 顶区精简后只剩 64px 命令带（工具行并入标签栏行）")
                .isEqualTo(io.agenticdb.ui.Tokens.H_APPBAR_LIGHT)
                .isEqualTo(64);
        assertThat(bar.getMaxHeight()).isEqualTo(bar.getPrefHeight());
        assertThat(bar.getMinHeight()).isEqualTo(bar.getPrefHeight());
    }

    /**
     * ⭐ 窄窗形态：侧栏 + 详情面板同时打开时主区只剩四百多像素，
     * 220px 的搜索框会把标签栏挤没 —— 搜索降级成图标，连接胶囊只留名字。
     * 命令面板本身仍可 ⌘K 直达，收起的是**外观**不是入口。
     */
    @Test
    void theUtilityCollapsesWhenTheChromeRowIsNarrow(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        onFxRun(() -> bar.fitChromeWidth(1280));
        assertThat(bar.isChromeCompact()).as("宽窗下不收起").isFalse();
        assertThat(robot.lookup("#appbar-search").queryAs(Button.class).isManaged()).isTrue();
        assertThat(robot.lookup("#appbar-connection-kind").queryAs(Label.class).isManaged())
                .as("宽窗下连接类型可见").isTrue();

        onFxRun(() -> bar.fitChromeWidth(420));
        assertThat(bar.isChromeCompact()).as("窄窗下切紧凑").isTrue();
        assertThat(robot.lookup("#appbar-connection-kind").queryAs(Label.class).isManaged())
                .as("紧凑形态下连接胶囊只留名字")
                .isFalse();
        assertThat(robot.lookup(".adb-appbar__search-hint").queryAs(Label.class).isManaged())
                .as("紧凑形态下搜索框里的提示文字必须收起（只留放大镜）")
                .isFalse();
        assertThat(robot.lookup("#appbar-search").queryAs(Button.class).isManaged())
                .as("搜索按钮本身还在 —— 收的是外观不是入口")
                .isTrue();

        onFxRun(() -> bar.fitChromeWidth(1280));
        assertThat(bar.isChromeCompact()).as("拉宽后要能恢复").isFalse();
    }

    /**
     * ⭐ UX-5：被通用 {@code .adb-icon--filled} 顶成灰是同一个 S-7 坑换位置 ——
     * 断言读计算值，不看 CSS 里写了什么。
     */
    @Test
    void theConnectionDotTurnsGreenWhenOnline(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        onFxRun(() -> bar.setConnectionState(true, false));

        assertThat(fillOf(robot, ".adb-appbar__connection-dot"))
                .as("已连接 = success 绿；被通用 .adb-icon--filled 顶成灰，等于状态永远显示「没连上」")
                .isEqualTo(javafx.scene.paint.Color.web(io.agenticdb.ui.Tokens.SUCCESS_LIGHT));

        onFxRun(() -> bar.setConnectionState(false, false));
        assertThat(fillOf(robot, ".adb-appbar__connection-dot"))
                .as("断开必须是灰（text-muted），不能和在线同色")
                .isEqualTo(javafx.scene.paint.Color.web(io.agenticdb.ui.Tokens.TEXT_MUTED_LIGHT));

        onFxRun(() -> bar.setConnectionState(false, true));
        assertThat(fillOf(robot, ".adb-appbar__connection-dot"))
                .as("连接失败是第三种状态 —— 红（error），不能退回灰")
                .isEqualTo(javafx.scene.paint.Color.web(io.agenticdb.ui.Tokens.ERROR_LIGHT));
    }

    /** 取某个样式类节点在层叠**之后**的计算填充色。 */
    private static javafx.scene.paint.Color fillOf(FxRobot robot, String styleClass) {
        javafx.scene.shape.SVGPath node = robot.lookup(styleClass)
                .queryAs(javafx.scene.shape.SVGPath.class);
        onFxRun(node::applyCss);
        return (javafx.scene.paint.Color) node.getFill();
    }

    private static void onFxRun(Runnable action) {
        javafx.application.Platform.runLater(action);
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }
}
