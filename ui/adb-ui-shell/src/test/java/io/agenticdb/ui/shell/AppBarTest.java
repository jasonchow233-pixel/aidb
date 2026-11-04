package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
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
        actions.register("query-history", "查询历史", "工具", null, () -> { });
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

    /**
     * ⭐ H5-h（R62-a）：Ribbon 溢出时那条横向滚动条必须是**细轨 + 无箭头**。
     *
     * <p>上一条门禁只问了「放得下吗 / 能不能滚」，本条问的是它**长什么样**：12 格按英文标签
     * 排开必然溢出，于是那条滚动条就是功能区的一部分。Modena 默认给 16px 整宽灰槽 +
     * 两端 {@code ⟨ ⟩} 箭头 —— 实拍里读起来像界面多出一条不属于它的工具。
     *
     * <p>⚠️ 钉**几何**不钉颜色，因为修法在这里很容易被做假：{@code ScrollBarSkin.getBreadth()}
     * 对横向条取的是**两端箭头按钮的 prefHeight 最大值**再加上下 inset，所以「把箭头调透明」
     * 会让画面看起来干净、槽却还是 16px —— 那条修法在这条门禁上直接红。
     * 阈值 8px = 本轮 CSS 定的 6px 轨 + 2px 余量；箭头宽度阈值 1px 区分「真收掉了」与
     * 「还在占位」（占位就会把轨道两端各吃掉 ~11px，滚动行程跟着变短）。
     */
    @Test
    void theRibbonOverflowBarIsThinAndArrowless(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        List<Double> measured = WaitForAsyncUtils.<List<Double>>asyncFx(() -> {
            Scene scene = bar.getScene();
            // Scene.setWidth 是 private —— 挤宽度只能挤窗口。
            javafx.stage.Stage stage = (javafx.stage.Stage) scene.getWindow();
            double originalWidth = stage.getWidth();
            try {
                // 挤到必然放不下（磁贴排 ~1050px + 右端工具件）。喂宽度和读数在**同一条 FX 任务**里，
                // 读完立刻还原 —— primary stage 是同类共用的，尺寸漏给下一条像素门禁就是 R57-g 那笔账。
                stage.setWidth(900);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                javafx.scene.Node strip = bar.lookup("#adb-ribbon");
                javafx.scene.Parent ancestor = strip == null ? null : strip.getParent();
                while (ancestor != null && !(ancestor instanceof ScrollPane)) {
                    ancestor = ancestor.getParent();
                }
                if (!(ancestor instanceof ScrollPane pane)) {
                    return List.<Double>of();
                }
                for (javafx.scene.Node node : pane.lookupAll(".scroll-bar")) {
                    if (node instanceof ScrollBar sb
                            && sb.getOrientation() == Orientation.HORIZONTAL && sb.isVisible()) {
                        double arrows = java.util.stream.Stream
                                .concat(sb.lookupAll(".increment-button").stream(),
                                        sb.lookupAll(".decrement-button").stream())
                                .mapToDouble(n -> ((javafx.scene.layout.Region) n).getWidth())
                                .max().orElse(-1);
                        return List.of(sb.getHeight(), arrows);
                    }
                }
                return List.<Double>of();
            } finally {
                stage.setWidth(originalWidth);
            }
        }).get();
        assertThat(measured)
                .as("900 宽下磁贴排必须真的溢出（拿不到可见的横向滚动条，这条门禁就是空的）")
                .isNotEmpty();
        assertThat(measured.get(0))
                .as("溢出条厚度必须 ≤8px（Modena 默认 16px）—— 实测 %s px", measured.get(0))
                .isLessThanOrEqualTo(8.0);
        assertThat(measured.get(1))
                .as("两端箭头必须真的收成 0 宽，不是「看不见但还占位」—— 实测 %s px", measured.get(1))
                .isLessThanOrEqualTo(1.0);
    }

    /**
     * ⭐ H5-i（R62-a · 拍板 Q8 第②案）：落「本版本未提供」说明页的那几格挂统一角标。
     *
     * <p>两个方向都要钉：<b>该挂的一格不落</b>（实话要在点之前说），
     * <b>不该挂的一格不多</b> —— 角标一旦会传染，它表达的就不再是「这格没有」，
     * 而是「功能区一半是摆设」，整条带子的可信度跟着掉。
     *
     * <p>判据不是我自己拍的：哪几格该挂由 {@code AppShell} 的处理器决定
     * （走 {@code openRibbonTool(SideBar.TOOL_…)} 的就是落说明页的那几格）。
     * 那一半由 {@code RibbonCapabilityAuditTest} 钉，这里钉**场景图里真的画出来了**。
     *
     * <p>⚠️ 颜色读**计算后的 border**，不读样式类名（S-7 的教训：类挂上了、CSS 写错成
     * 别的色，用户看到的仍是错的那一档，门禁却全绿）。位置读环与那一格在<b>同一条
     * 磁贴排里</b>的相对边角 —— 角标必须贴着那格的<b>右上角</b>，飘到中间就成了另一枚
     * 谁都不属于的点（H5-l 刚为这句话在侧栏付过学费）。
     *
     * <p>环是排的孩子、不是按钮的孩子（{@code managed=false}，不参与排版），所以
     * {@code button.lookup(...)} 找不到它 —— 这里按**几何**认领：谁的右上角被一枚环贴着，
     * 那一格就算挂了标。认领规则写错的话会一格都认不出，下面的 size 断言会先红。
     */
    @Test
    void pendingTilesCarryTheCornerBadgeAndOthersDoNot(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        List<String> shouldBadge = List.of("ribbon-ribbon-function", "ribbon-ribbon-user",
                "ribbon-ribbon-backup", "ribbon-ribbon-automation", "ribbon-ribbon-model",
                "ribbon-ribbon-bi");
        // 每格一行：id、有没有环、环的计算描边色与粗细、环宽、环是不是托管的、吃不吃点击。
        List<List<Object>> rows = WaitForAsyncUtils.<List<List<Object>>>asyncFx(() -> {
            bar.getScene().getRoot().applyCss();
            bar.getScene().getRoot().layout();
            List<javafx.scene.layout.Region> marks = bar.lookupAll(".adb-pending__mark").stream()
                    .filter(javafx.scene.layout.Region.class::isInstance)
                    .map(javafx.scene.layout.Region.class::cast).toList();
            List<List<Object>> out = new ArrayList<>();
            for (Button button : ribbonTiles()) {
                javafx.scene.layout.Region mine = null;
                for (javafx.scene.layout.Region mark : marks) {
                    if (mark.getParent() != button.getParent()) {
                        continue;
                    }
                    double insetRight = button.getBoundsInParent().getMaxX()
                            - mark.getBoundsInParent().getMaxX();
                    double insetTop = mark.getBoundsInParent().getMinY()
                            - button.getBoundsInParent().getMinY();
                    if (insetRight >= 0 && insetRight <= 6 && insetTop >= 0 && insetTop <= 6) {
                        mine = mark;
                    }
                }
                if (mine == null) {
                    out.add(List.of(button.getId(), false));
                    continue;
                }
                java.util.List<javafx.scene.layout.BorderStroke> strokes =
                        mine.getBorder().getStrokes();
                Object ring = "无边框";
                Object thickness = -1.0;
                for (javafx.scene.layout.BorderStroke stroke : strokes) {
                    if (stroke != null && stroke.getTopStroke() != null) {
                        ring = stroke.getTopStroke();
                        thickness = stroke.getWidths().getTop();
                        break;
                    }
                }
                out.add(List.of(button.getId(), true, ring, thickness,
                        mine.getBoundsInLocal().getWidth(), mine.isManaged(),
                        mine.isMouseTransparent()));
            }
            return out;
        }).get();
        assertThat(rows).as("12 格磁贴都要被读到（少一格这条门禁就是空的）").hasSize(12);
        List<String> badged = rows.stream().filter(r -> (Boolean) r.get(1))
                .map(r -> (String) r.get(0)).toList();
        assertThat(badged)
                .as("角标 = 「本版本未提供」，只许挂在落说明页的那几格上")
                .containsExactlyInAnyOrderElementsOf(shouldBadge);
        for (List<Object> row : rows) {
            if (!(Boolean) row.get(1)) {
                continue;
            }
            assertThat(row.get(2))
                    .as("%s：角标描边必须是 text-tertiary 那一档（比正文淡，但在 chrome 底上过 3:1）",
                            row.get(0))
                    .isEqualTo(javafx.scene.paint.Color.web(io.agenticdb.ui.Tokens
                            .TEXT_TERTIARY_LIGHT));
            assertThat(row.get(3))
                    .as("%s：环只有 1px 粗才是「淡角标」，粗了就和图标抢视线", row.get(0))
                    .isEqualTo(1.0);
            assertThat((Double) row.get(4))
                    .as("%s：环与状态点同一档尺寸（8px）—— 大小不一的角标读成装饰", row.get(0))
                    .isEqualTo((double) io.agenticdb.ui.Icons.SIZE_DOT);
            assertThat(row.get(5))
                    .as("%s：环不许参与排版（managed=true 就会把这格垫下去）", row.get(0))
                    .isEqualTo(false);
            assertThat(row.get(6))
                    .as("%s：环压在按钮角上，必须让点击透到那一格", row.get(0))
                    .isEqualTo(true);
        }
    }

    /**
     * ⭐ H5-i 的另一半：<b>挂角标不许动这一排的几何</b>。
     *
     * <p>角标是往图形外面加一个节点，加错地方就会出现「六格的标签比另外六格低一截」
     * —— 同一排磁贴读成两排，而这只有肉眼和这条门禁看得见。钉两个量：
     * 十二个标签在同一基线上、十二格等高。
     *
     * <p>⚠️ 容差 1.5px 是<b>对照量出来</b>的，不是拍的：这一排本来就有 1px 抖动 —— 挂标
     * 与不挂标都读到 view / function / others 三格的标签 Y=45、其余 46。做过控制实验：
     * 把 {@code PENDING_TILES} 清空（一格标都不挂）重跑，偏的仍是这三格、仍是同样的 1px，
     * 所以<b>角标造成的位移是 0px</b>，那 1px 是图标资产本身的高度差（记在 H6-g 的账上）。
     * 对照之下，把环裹进图形槽的两种写法实测造成 +6 / −7px 的落差 —— 1.5px 正好卡在
     * 「既有抖动」与「真回归」之间。
     */
    @Test
    void thePendingBadgeDoesNotMoveTheTileGrid(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        List<List<Object>> measured = WaitForAsyncUtils.<List<List<Object>>>asyncFx(() -> {
            bar.getScene().getRoot().applyCss();
            bar.getScene().getRoot().layout();
            List<List<Object>> out = new ArrayList<>();
            for (Button button : ribbonTiles()) {
                javafx.scene.Node label = button.lookup(".adb-ribbon__label");
                out.add(List.of(button.getId(),
                        label == null ? -1.0 : label.localToScene(0, 0).getY(),
                        button.getHeight()));
            }
            return out;
        }).get();
        assertThat(measured).hasSize(12);
        List<Object> first = measured.get(0);
        assertThat((Double) first.get(1)).as("第一格没读到标签 —— 这条门禁空转").isNotEqualTo(-1.0);
        List<String> offBaseline = new ArrayList<>();
        for (List<Object> row : measured) {
            if (Math.abs((Double) row.get(1) - (Double) first.get(1)) > 1.5
                    || Math.abs((Double) row.get(2) - (Double) first.get(2)) > 1.5) {
                offBaseline.add(row.get(0) + "：标签 Y=" + row.get(1) + "、格高 " + row.get(2));
            }
        }
        assertThat(offBaseline)
                .as("十二格磁贴的标签基线与格高必须一致（第一格基准：Y=%s、高=%s）——"
                        + "角标只许贴在图形角上，不参与排版", first.get(1), first.get(2))
                .isEmpty();
    }

    /** 12 格磁贴，按 Ribbon 里从左到右的遍历顺序（只能在 FX 线程上调）。 */
    private List<Button> ribbonTiles() {
        return bar.lookupAll(".adb-ribbon__button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }

    /**
     * ⭐ D-12（R44 小账）：搜索框的快捷键胶囊不许截成「Ct…」。
     *
     * <p>「宽度按中文量」家族第四次（自增列头 / 详情页脚 / 列筛选浮窗之后）。
     * 220px 定宽里提示文字和胶囊抢空间 —— 钉住**收缩的分配**：胶囊 min=pref
     * （快捷键是功能承诺，截了等于没承诺），提示文字才是可省略的那位。
     */
    @Test
    void theSearchShortcutCapsuleIsNeverEllipsised(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        List<Object> geometry = WaitForAsyncUtils.asyncFx(() -> {
            bar.applyCss();
            bar.layout();
            Label kbd = bar.lookupAll(".adb-appbar__search-kbd").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .findFirst().orElseThrow(() -> new AssertionError("顶栏没有快捷键胶囊"));
            Label hint = bar.lookupAll(".adb-appbar__search-hint").stream()
                    .filter(Label.class::isInstance).map(Label.class::cast)
                    .findFirst().orElseThrow(() -> new AssertionError("顶栏没有搜索提示文字"));
            kbd.applyCss();
            kbd.requestLayout();
            return List.<Object>of(
                    kbd.getMinWidth() == javafx.scene.layout.Region.USE_PREF_SIZE,
                    hint.getMinWidth() != javafx.scene.layout.Region.USE_PREF_SIZE,
                    kbd.getWidth() + 0.5 >= kbd.prefWidth(-1),
                    kbd.getText());
        }).get();
        assertThat((Boolean) geometry.get(0))
                .as("胶囊必须先声明 min=pref，截字门禁才有几何意义（只测画面会被绕过）")
                .isTrue();
        assertThat((Boolean) geometry.get(1))
                .as("220px 定宽里可收缩的那位必须是提示文字，不是快捷键胶囊")
                .isTrue();
        assertThat((Boolean) geometry.get(2))
                .as("快捷键胶囊实得宽度必须装得下全文")
                .isTrue();
        assertThat((String) geometry.get(3))
                .as("胶囊文字就是命令面板承诺的那个键")
                .isIn("Ctrl+K", "⌘K");
    }

    @Test
    void theConnectionPillShowsTheRealConnectionName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#appbar-connection-name").queryAs(Label.class).getText())
                .isEqualTo("agenticdb-demo.db");
        assertThat(robot.lookup("#appbar-connection-kind").queryAs(Label.class).getText())
                .isEqualTo("SQLite");
    }

    /**
     * ⭐ R54：胶囊最左那一格画的是**当前连接那一家**的图形，不是一枚人人相同的中性点。
     *
     * <p>顶栏是全应用唯一常驻的「我在哪家库」，而它此前只有中性点 + 名字 + 类型文字。
     * 文字在窄窗口会被 {@code fitChromeWidth} 收掉 —— 所以这条门同时钉住
     * 「收掉文字之后图形必须还在」：没地方写字的那一刻，才是这句话最需要有人替它说的时候。
     *
     * <p>⚠️ 不能 {@code lookup(".adb-db--sqlite")}：作用域类挂在 Label 的 graphic 上，
     * 而 graphic 不在 {@code getChildren()} 里，选择器遍历不到（状态栏那条门禁踩过）。
     * 这里按结构读 —— 胶囊 HBox 的第 0 格就是厂商槽位。
     */
    @Test
    void theConnectionPillWearsTheConnectionsVendorGlyph(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(pillVendorClasses(robot))
                .as("胶囊图形必须带上当前连接（SQLite）的品牌作用域类")
                .contains("adb-db--sqlite");

        onFxRun(() -> bar.fitChromeWidth(420));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#appbar-connection-kind").queryAs(Label.class).isManaged())
                .as("前提：窄窗口确实把类型文字收掉了").isFalse();
        assertThat(pillVendorClasses(robot))
                .as("文字收掉后厂商图形不能跟着消失")
                .contains("adb-db--sqlite");
    }

    /** ⭐ R54：下拉里每一行顶的都是**它自己那一家**的图形，不是清一色同一枚。 */
    @Test
    void everyConnectionInTheMenuWearsItsOwnVendorsGlyph(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        MenuButton menu = robot.lookup("#appbar-connection-menu").queryAs(MenuButton.class);
        assertThat(menuGraphicClasses(menu, "agenticdb-demo.db"))
                .as("演示库那一行是 SQLite").contains("adb-db--sqlite");
        assertThat(menuGraphicClasses(menu, "prod-postgres"))
                .as("另一行是 PostgreSQL —— 两行必须不同形不同色")
                .contains("adb-db--postgresql")
                .doesNotContain("adb-db--sqlite");
    }

    /**
     * ⭐ R54：认不出方言时那一格整格收起 —— 不许留一枚「看着像某家」的假图形。
     *
     * <p>诚实条款的图形版：没有连接就没有引擎可说，画一枚圆柱会让人以为左边有库。
     */
    @Test
    void thePillDropsTheGlyphWhenThereIsNoConnection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        onFxRun(() -> bar.setConnections(List.of(), null));
        WaitForAsyncUtils.waitForFxEvents();
        MenuButton menu = robot.lookup("#appbar-connection-menu").queryAs(MenuButton.class);
        Label slot = (Label) ((HBox) menu.getGraphic()).getChildren().get(0);
        assertThat(slot.getGraphic()).as("没连接时厂商槽里没有图形").isNull();
        assertThat(slot.isManaged())
                .as("没有图形时整格收起，不留一段空白把名字往右推")
                .isFalse();
    }

    private static List<String> pillVendorClasses(FxRobot robot) {
        MenuButton menu = robot.lookup("#appbar-connection-menu").queryAs(MenuButton.class);
        Label slot = (Label) ((HBox) menu.getGraphic()).getChildren().get(0);
        javafx.scene.Node glyph = slot.getGraphic();
        return glyph == null ? List.of() : glyph.getStyleClass();
    }

    private static List<String> menuGraphicClasses(MenuButton menu, String textPrefix) {
        MenuItem item = menu.getItems().stream()
                .filter(each -> each.getText() != null && each.getText().startsWith(textPrefix))
                .findFirst().orElseThrow(() -> new AssertionError("下拉里找不到 " + textPrefix));
        return item.getGraphic() == null ? List.of() : item.getGraphic().getStyleClass();
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
