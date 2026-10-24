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
        Scene scene = new Scene(bar, 1280, 800);
        // ⭐ 必须挂样式表：App 里由 ThemeManager 挂，测试里不挂则 CSS 一条都不生效，
        //    「品牌字形的对比度」这类断言会全部假绿（S-7 就是这么发现蓝底灰星的）。
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        bar.getStyleClass().add("theme-light");
        stage.setScene(scene);
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

    @Test
    void theBrandMarkIsTheAidbMarkNotAGenericDatabaseIcon(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⭐ S-7「接」的那一半：品牌改造把 Logo 做出来了，但界面里一处没用 ——
        //    方块里挂的是谁都在画的通用圆柱。欢迎页不可达（见 BrandMarkAuditTest），
        //    应用栏才是**唯一**看得见品牌的地方，所以钉在这里。
        javafx.scene.shape.SVGPath glyph =
                robot.lookup("#appbar-brand-glyph").queryAs(javafx.scene.shape.SVGPath.class);
        assertThat(glyph.getContent())
                .as("品牌方块必须是 AIDB 自己的 mark（圆柱 + 右上 AI 星芒），"
                        + "不是 Icons.Icon.DATABASE 那个通用圆柱 —— 换回去一眼认不出这是谁家的 DB 客户端")
                .isEqualTo(io.agenticdb.ui.Icons.Icon.BRAND.path());
        assertThat(glyph.getContent())
                .as("退化成通用圆柱 = 品牌等于没有")
                .isNotEqualTo(io.agenticdb.ui.Icons.Icon.DATABASE.path());
    }

    @Test
    void theBrandGlyphKeepsItsContrastAgainstTheGenericIconRule(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⭐ S-7 顺带挖出的真 bug（截图对比度量出来的）：通用 `.adb-icon` 也设 `-fx-stroke`，
        //    且**排在后面**、同为单类选择器 → JavaFX「同特异性后写胜」，把灰字色盖在了白字上，
        //    蓝底方块里的品牌 mark 实测是 #64748B（对比度约 1.9:1），那颗 AI 星几乎看不见。
        //    这条断言测的是**层叠之后的计算值**，不是 CSS 里写了什么 —— 改回单类选择器立刻红。
        javafx.scene.shape.SVGPath glyph =
                robot.lookup("#appbar-brand-glyph").queryAs(javafx.scene.shape.SVGPath.class);
        javafx.scene.paint.Paint stroke = glyph.getStroke();
        assertThat(stroke)
                .as("品牌方块上的字形必须走 `text-inverse`（亮色主题＝白）—— "
                        + "被通用 .adb-icon 的 text-secondary 顶掉就看不见")
                .isEqualTo(javafx.scene.paint.Color.web(
                        io.agenticdb.ui.Tokens.TEXT_INVERSE_LIGHT));
        assertThat(stroke)
                .as("反证：不许是灰字色")
                .isNotEqualTo(javafx.scene.paint.Color.web(
                        io.agenticdb.ui.Tokens.TEXT_SECONDARY_LIGHT));
    }

    /**
     * ⭐ UX-5：应用栏那颗连接状态点，**在线时得真是绿的**。
     *
     * <p>侧栏加存活点时发现：{@code .adb-appbar__connection-dot--online} 写在
     * {@code .adb-icon--filled}（486 行）**前面**、同为单类选择器 —— 同一个
     * S-7 的坑换了个位置：层叠算下来在线/离线都是 text-secondary 灰，
     * 那颗点从来没绿过。断言读计算值，不看 CSS 里写了什么。
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
