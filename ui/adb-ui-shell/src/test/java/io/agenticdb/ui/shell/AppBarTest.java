package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
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
 *   <li>⭐ 2026-10-30 R36 顶区重做之后：<b>品牌行不再存在</b>、命令带是
 *       <b>12 格 Navicat 磁贴</b>（每格都必须有注册动作与真实落点，见
 *       {@code RibbonCapabilityAuditTest}）、工具件住在 Ribbon 右端、
 *       窄窗口下工具条切紧凑形态（磁贴排横向滚动，标签永不截字）。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class AppBarTest {

    private AppBar bar;
    /** R36 后工具件住在 Ribbon 右端，测试场景里这行只是占位（真实装配归 AppShellTest 钉）。 */
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
        // R36 新增的七格磁贴动作（外壳真实装配里同样注册）。
        actions.register("ribbon.view", "视图清单", "对象", null, () -> { });
        actions.register("ribbon.function", "函数", "对象", null, () -> { });
        actions.register("ribbon.user", "用户", "对象", null, () -> { });
        actions.register("ribbon.others", "其他对象", "对象", null, () -> { });
        actions.register("ribbon.backup", "备份", "工具", null, () -> { });
        actions.register("ribbon.automation", "自动化", "工具", null, () -> { });
        actions.register("ribbon.model", "模型", "工具", null, () -> { });
        actions.register("ribbon.bi", "BI", "工具", null, () -> { });

        List<ConnectionProfile> profiles = List.of(
                profile("demo", "agenticdb-demo.db", DialectKind.SQLITE),
                profile("pg", "prod-postgres", DialectKind.POSTGRESQL));

        bar = new AppBar(actions, profiles, "demo", selected::add);

        // 与 AppShell 的真实装配一致：R36 之后顶区只有 Ribbon 一行
        //（工具件在 Ribbon 右端，页签行归 AppShellTest 钉）。
        chromeRow = new HBox();
        chromeRow.setId("adb-chrome-row");
        chromeRow.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(bar, chromeRow);
        Scene scene = new Scene(root, 1280, 800);
        // ⭐ 必须挂样式表：App 里由 ThemeManager 挂，测试里不挂则 CSS 一条都不生效，
        //    紧凑形态断言会全部假绿。
        // ⚠️ 主题类挂在**根**上（真实装配里 ThemeManager 就是挂在场景根）：
        //    挂在 AppBar 上时，Ribbon 里收不到 -adb-* 变量的节点会算成纯黑（0x000000）
        //    —— 连接状态点门禁当场抓到过。
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
     * ⭐ R36：命令带是 Navicat 17 的 <b>12 格磁贴</b>，顺序按真源 §1.2。
     *
     * <p>「与别处重复」不再是裁格子的理由 —— Navicat 就是这么排的，用户裁定
     * 完全照它做；重复入口的代价由 {@code RibbonCapabilityAuditTest} 盯住
     * （每格必须有注册动作 + 真实落点），而不是靠减少格子。
     */
    @Test
    void theRibbonCarriesTheNavicatTwelveTiles(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⚠️ 不能按 `#ribbon-object.new-table` 查找：JavaFX 选择器把 `.` 当类分隔符，
        //    带点的 id 永远查不到 —— AppBar 已把 id 里的点统一换成短横线。
        List<String> ids = robot.lookup(".adb-ribbon__button").queryAllAs(Button.class)
                .stream().map(Button::getId).toList();
        assertThat(ids)
                .as("12 格磁贴，顺序 = Connection·NewQuery·Table·View·Function·User·Others"
                        + "｜Query·Backup·Automation·Model·BI")
                .containsExactly("ribbon-new-connection", "ribbon-new-query",
                        "ribbon-object-new-table", "ribbon-ribbon-view",
                        "ribbon-ribbon-function", "ribbon-ribbon-user",
                        "ribbon-ribbon-others", "ribbon-saved-queries",
                        "ribbon-ribbon-backup", "ribbon-ribbon-automation",
                        "ribbon-ribbon-model", "ribbon-ribbon-bi");
    }

    /**
     * ⭐ 命令带的六个标签**一个都不许被裁成省略号**。
     *
     * <p>格子原本写死 `min=pref=62px` —— 那是按中文量出来的（"已保存查询" 五个字 62px 装得下）。
     * 换成英文之后 "Saved Queries" 要 ~72px、"Import / Export" 要 ~78px，
     * 实拍里直接长成 "Saved Qu…" / "Import / …"（2026-10-29 第五批重拍帧目视发现）。
     * 这条门禁钉的是**结果**不是手段：只要标签实得宽度小于它想要的宽度就红，
     * 无论是格子写死、字号变大还是文案变长引起的。
     */
    @Test
    void ribbonLabelsAreNeverEllipsised(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        // 喂宽度和读几何必须在**同一条 FX 任务**里（第四批的教训：分开两次调用，
        // 中间能插入布局事件，读到自相矛盾的帧）。lookup 也必须在 FX 线程上 ——
        // Parent.lookup 会在测试线程遍历场景树，那本身就是竞态。
        List<String> squeezed = WaitForAsyncUtils.asyncFx(() -> {
            bar.applyCss();
            bar.layout();
            List<String> bad = new ArrayList<>();
            for (Button button : bar.lookupAll(".adb-ribbon__button").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast).toList()) {
                if (!(button.getGraphic() instanceof VBox box)) {
                    continue;
                }
                box.getChildren().stream()
                        .filter(Label.class::isInstance).map(Label.class::cast)
                        .findFirst().ifPresent(label -> {
                            label.applyCss();
                            label.requestLayout();
                            double want = label.prefWidth(-1);
                            if (label.getWidth() + 0.5 < want) {
                                bad.add(button.getId() + "「" + label.getText() + "」"
                                        + " 实得 " + Math.round(label.getWidth())
                                        + "px < 需要 " + Math.round(want) + "px");
                            }
                        });
            }
            return bad;
        }).get();
        assertThat(squeezed)
                .as("命令带标签被裁 —— 英文比中文宽，格子宽度不能按中文写死")
                .isEmpty();

        // 反向的一半：12 格按英文标签排开可能超过 1280 窗口 —— 替代截字的方案是
        // **横向滚动**。这条钉住「放不下时磁贴排真的在可滚动的容器里」，
        // 否则将来有人摘掉 ScrollPane，上面的标签截字门禁会静默失效。
        List<Boolean> geometry = WaitForAsyncUtils.asyncFx(() -> {
            bar.applyCss();
            bar.layout();
            javafx.scene.Node strip = bar.lookup("#adb-ribbon");
            // ScrollPane 的内容挂在内部 viewport（Group）下，直接 getParent() 拿不到
            // ScrollPane —— 沿父链往上找第一个 ScrollPane 祖先。
            javafx.scene.Parent ancestor = strip.getParent();
            while (ancestor != null && !(ancestor instanceof ScrollPane)) {
                ancestor = ancestor.getParent();
            }
            double available = bar.getWidth() - bar.lookup("#appbar-utility").prefWidth(-1);
            boolean fits = strip.prefWidth(-1) <= available + 0.5;
            boolean scrollable = ancestor instanceof ScrollPane pane
                    && pane.getHbarPolicy() == ScrollPane.ScrollBarPolicy.AS_NEEDED;
            return List.of(fits || scrollable);
        }).get();
        assertThat(geometry.get(0))
                .as("磁贴排放不下时必须横向滚动（ScrollPane AS_NEEDED），而不是截标签")
                .isTrue();
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
                        I18n.t("appbar.manageConnections"), "重新连接", "断开连接");
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
                .as("2026-10-30 R36：顶区按 Navicat 实测定格 91px（菜单 24 + 91 + 页签 25 = 140）")
                .isEqualTo(io.agenticdb.ui.Tokens.H_APPBAR_LIGHT)
                .isEqualTo(91);
        assertThat(bar.getMaxHeight()).isEqualTo(bar.getPrefHeight());
        assertThat(bar.getMinHeight()).isEqualTo(bar.getPrefHeight());
    }

    /**
     * ⭐ 窄窗形态：磁贴排自己会滚动，这里收的是**工具件的外观** ——
     * Ribbon 行低于 780px 时搜索降级成图标、连接胶囊只留名字，
     * 把右端让给磁贴排。命令面板本身仍可 ⌘K 直达，收起的是外观不是入口。
     */
    @Test
    void theUtilityCollapsesWhenTheRibbonRowIsNarrow(FxRobot robot) {
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
