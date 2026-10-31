package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ UX-4 门禁一：网格的**选中态与焦点态**。
 *
 * <p>改之前的问题（都是肉眼可见的）：
 * <ul>
 *   <li>选中行只有一层淡底，24px 行高里「选了哪一行」经常要横向扫一遍；</li>
 *   <li>焦点单元格用的外发光**被邻居裁掉**，看起来像根本没有焦点。</li>
 * </ul>
 *
 * <p>⭐ 2026-10-26 用户裁定「保持和 Navicat 一样」：列间竖线回归。
 * Navicat 17 数据网格实测每列边界都有 1px `#F0F0F0` 竖线；第二十一轮
 * 「竖线全部退场」的裁定作废。左 2px 槽位仍然常留：光标条 / 脏值条 / 选中强调条只换颜色。
 *
 * <p>但加强调条 / 描边有个副作用必须一起钉住：<b>JavaFX 的边框算进内容内缩</b> ——
 * 常态 0px、选中 2px 的话，一选中整行文字就往右跳 2px，键盘走一格就抖一次。
 * 所以这套规则的核心约束是「语义态只换颜色、不改宽度」，
 * 槽位由基础规则常留。下面三条运行时断言量的就是这个「不跳」。
 */
@ExtendWith(ApplicationExtension.class)
class SelectionStateTest {

    @TempDir
    Path temp;

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;

    @Start
    void start(Stage stage) throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
        page = new DataGridPage(session, new SchemaService(), "users",
                "test.db", noOpFeedback(), name -> { }, executor, new SettingsStore());
        Scene scene = new Scene(page, 1200, 700);
        // ⚠️ 样式表必须自己挂 —— 不挂则下面所有 CSS 断言只对文件生效、运行时一条都不生效。
        scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/theme-light.css")
                .toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/app.css")
                .toExternalForm());
        page.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void waitForTheTable() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                table() != null && table().getItems().size() == 200);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        try {
            session.close();
        } catch (Exception ignored) {
            // 清理失败不影响结论。
        }
    }

    // ─────────────────────────── 运行时：不抖 ───────────────────────────

    /**
     * 选中一行 —— 这一行的单元格**一个都不许挪**。
     *
     * <p>变异：把 {@code :selected} 的 {@code -fx-border-width} 改回来（0 0 1 2）→ 红。
     */
    @Test
    void selectingARowNeverMovesItsCells() {
        TableView<?> table = requireTable();
        TableRow<?> row = requireRow(table, 1);
        List<Double> before = cellPositions(row);
        double heightBefore = row.getHeight();
        assertThat(before).as("先取到该行的单元格位置").isNotEmpty();

        selectRow(1);
        TableRow<?> selected = requireRow(table, 1);
        assertThat(selected.isSelected())
                .as("前置条件：这一行确实处于选中态")
                .isTrue();
        assertThat(cellPositions(selected))
                .as("选中只许换颜色：行线左条给的是常留槽位，任何单元格的 x/y 都不能变")
                .isEqualTo(before);
        assertThat(round(selected.getHeight()))
                .as("选中也不能把这一行顶高 —— 整页会跟着抖（对比的是行自身，"
                        + "不是可见行数：选中会唤出批量条，视口高度本来就变）")
                .isEqualTo(round(heightBefore));
    }

    /**
     * 点进一个单元格 —— 光标格标出来了，文字却**不许跳**。
     *
     * <p>⭐ 顺带钉住一个 JavaFX 的坑：行选模式下单元格的 {@code :focused}
     * 永远不会为真（{@code TableCell.updateFocus()} 在非 cell-selection 模式里
     * 直接 {@code setFocused(false)}），所以这里断言的是样式类，不是 {@code isFocused()}。
     *
     * <p>变异：删掉 {@code --cursor} 这个类名 / 给光标态加 {@code -fx-border-width} → 红。
     */
    @Test
    void clickingACellMarksTheCursorWithoutMovingText(FxRobot robot) {
        TableView<?> table = requireTable();
        TableCell<?, ?> cell = requireCell(requireRow(table, 1));
        List<Double> before = cellPositions(requireRow(table, 1));
        double cellTop = cell.getLayoutY();
        assertThat(cell.getStyleClass())
                .as("点之前这一格还不是光标格")
                .doesNotContain("adb-grid__cell--cursor");

        robot.clickOn(cell);
        WaitForAsyncUtils.waitForFxEvents();

        TableCell<?, ?> clicked = requireCell(requireRow(table, 1));
        assertThat(clicked.getStyleClass())
                .as("点进去的这一格要标成光标格 —— 键盘用户要看得见自己在哪一列")
                .contains("adb-grid__cell--cursor");
        assertThat(cellPositions(requireRow(table, 1)))
                .as("光标只改描边颜色 + 淡底，槽位不变 → 文字一格都不跳")
                .isEqualTo(before);
        assertThat(round(requireCell(requireRow(table, 1)).getLayoutY()))
                .as("垂直方向同样不许跳（行高固定，上下描边必须恒为 0）")
                .isEqualTo(round(cellTop));
    }

    /**
     * 键盘往下走一格：光标要跟着换行，而且**上一格要摘掉**光标类。
     *
     * <p>变异：{@code bindFocusCursor} 里不 refresh → 光标停在原地，红。
     */
    @Test
    void theCursorFollowsTheKeyboard(FxRobot robot) {
        TableView<?> table = requireTable();
        TableCell<?, ?> start = requireCell(requireRow(table, 1));
        robot.clickOn(start);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(requireCell(requireRow(table, 1)).getStyleClass())
                .as("起点：第 1 行的 name 格是光标格")
                .contains("adb-grid__cell--cursor");

        robot.press(javafx.scene.input.KeyCode.DOWN).release(javafx.scene.input.KeyCode.DOWN);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(requireCell(requireRow(table, 2)).getStyleClass())
                .as("按 ↓ 之后光标要落到第 2 行同一列")
                .contains("adb-grid__cell--cursor");
        assertThat(requireCell(requireRow(table, 1)).getStyleClass())
                .as("上一格的光标类必须摘掉 —— 留着就是两个光标")
                .doesNotContain("adb-grid__cell--cursor");
    }

    /**
     * ⭐ N17：光标所在列的表头整格着蓝 tint（帧 6 实测 `#AED4F2`），换列即换。
     *
     * <p>样式类挂在 {@code TableColumn} 上 —— {@code TableColumnHeader} 会把它复制到
     * 表头单元格。所以这里既断言列上的类，也断言场景图里真的有一个表头单元格带着它
     * （挂在 graphic 上也能让「列上有类」为真，但染不满整格）。
     *
     * <p>变异：只给列加类、不给表头节点 —— 第二条断言红；换列不摘旧类 —— 第三条红。
     */
    @Test
    void theCurrentColumnHeaderIsTintedAndTheTintFollowsTheCursor() throws IOException {
        TableView<?> table = requireTable();
        TableColumn<?, ?> nameColumn = dataColumn(table, "name");
        TableColumn<?, ?> scoreColumn = dataColumn(table, "score");

        WaitForAsyncUtils.asyncFx(() -> {
            focusCell(table, 1, nameColumn);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(nameColumn.getStyleClass())
                .as("光标列（name）要带 current 类")
                .contains("adb-grid__column--current");
        assertThat(page.lookupAll(".column-header.adb-grid__column--current"))
                .as("表头单元格自己要带上这个类 —— 只挂在列上，CSS 染不到整格")
                .isNotEmpty();
        assertThat(ruleBody(css(), ".adb-grid .column-header.adb-grid__column--current"))
                .as("表头 tint 必须走令牌，不是随手写的色值")
                .contains("-adb-bg-header-current");

        WaitForAsyncUtils.asyncFx(() -> {
            focusCell(table, 1, scoreColumn);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(scoreColumn.getStyleClass())
                .as("光标换到 score")
                .contains("adb-grid__column--current");
        assertThat(nameColumn.getStyleClass())
                .as("旧列的表头 tint 必须摘掉")
                .doesNotContain("adb-grid__column--current");
    }

    /**
     * ⭐ N17：进编辑态 = 格子变成白底蓝框的编辑盒，值全选；退出后还原。
     *
     * <p>帧 6 实测：编辑格白底 `#FFFFFF` + 2px `#0078D7`（border-focus）方框，
     * 贴着整格画，不是浮起的圆角输入框。
     *
     * <p>变异：不摘 {@code adb-grid__cell--editing} → 取消编辑后仍是编辑盒，红。
     */
    @Test
    void startingAnInlineEditTurnsTheCellIntoASelectedEditorBox() throws IOException {
        TableView<?> table = requireTable();
        TableColumn<?, ?> nameColumn = dataColumn(table, "name");

        WaitForAsyncUtils.asyncFx(() -> {
            editCell(table, 1, nameColumn);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        TableCell<?, ?> cell = requireCell(requireRow(table, 1), "name");
        assertThat(cell.getStyleClass())
                .as("编辑中的格子要带 editing 类")
                .contains("adb-grid__cell--editing");
        assertThat(cell.getGraphic())
                .as("编辑盒是格子里的 TextField")
                .isInstanceOf(TextField.class);
        TextField editor = (TextField) cell.getGraphic();
        assertThat(editor.getSelectedText())
                .as("进入编辑即全选，免得连旧值一起敲掉")
                .isEqualTo(editor.getText());
        assertThat(requireRow(table, 1).isSelected())
                .as("编辑行同时是选中行 —— 整行色带来自身选中态")
                .isTrue();

        String editingRule = ruleBody(css(), ".adb-grid .table-cell.adb-grid__cell--editing");
        assertThat(editingRule)
                .as("白底 + 2px 蓝框，且都走令牌")
                .contains("-adb-bg-surface")
                .contains("-adb-border-focus")
                .contains("2px");
        assertThat(ruleBody(css(), ".adb-grid__editor"))
                .as("输入框自己不许再画框 —— 编辑盒边界由格子给")
                .contains("-fx-border-color: transparent");

        WaitForAsyncUtils.asyncFx(() -> {
            cell.cancelEdit();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(requireCell(requireRow(table, 1), "name").getStyleClass())
                .as("退出编辑必须摘掉 editing 类")
                .doesNotContain("adb-grid__cell--editing");
    }

    /** 悬停在已选中的行上，选中态不能被顶掉（顺序门禁见 CSS 侧）。 */
    @Test
    void hoverAndSelectionAreIndependentStates(FxRobot robot) {
        TableView<?> table = requireTable();
        selectRow(2);
        robot.moveTo(requireRow(table, 2));
        WaitForAsyncUtils.waitForFxEvents();

        TableRow<?> hovered = requireRow(table, 2);
        assertThat(hovered.isSelected())
                .as("悬停已选中的行之后，选中状态还在")
                .isTrue();
        assertThat(hovered.isHover())
                .as("同一行同时也处于 hover —— 两个状态必须能共存")
                .isTrue();
    }

    /**
     * ⭐ UX-5-F：截图那一帧要摆的三个交互态，必须**真的都落地**，而且落在同一格上。
     *
     * <p>为什么要这条：{@code scripts/shots.sh} 的 {@code p2-cell} 帧靠
     * {@link DataGridPage#poseInteractionStates} 摆状态。这个方法一旦「只是调用了但没生效」，
     * 拍出来的仍是一张无辜的表，而脚本返回码照样是 0 ——
     * 交互态以前 12 帧一帧没覆盖，靠的就是这条门禁把它从「没人看过」变成「拍得到且测得到」。
     *
     * <p>变异：pose 里删 {@code focusModel.focus(..)} → 光标断言红；删 {@code select(..)}
     * → 选中断言红；删 {@code showDetails(..)} → 抽屉断言红；列名写错 → 诚实返回 false 那条红。
     */
    @Test
    void theScreenshotPoseLightsUpSelectionCursorAndDrawer() {
        TableView<?> table = requireTable();
        boolean[] posed = new boolean[1];
        WaitForAsyncUtils.asyncFx(() -> {
            posed[0] = page.poseInteractionStates(1, "name");
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(posed[0])
                .as("行已灌进来、name 是可见列 → 摆状态必须成功")
                .isTrue();
        assertThat(requireRow(table, 1).isSelected())
                .as("摆完要有一行处于选中态")
                .isTrue();
        assertThat(requireCell(requireRow(table, 1), "name").getStyleClass())
                .as("光标要落在 name 这一格 —— 和抽屉同一格，这张图讲的才是同一件事")
                .contains("adb-grid__cell--cursor");
        var focused = table.getFocusModel().getFocusedCell();
        assertThat(focused.getRow())
                .as("光标经 focusModel 摆出来（不是把样式类直接糊上去：那样截图好看和键盘真有光标就脱钩了）")
                .isEqualTo(1);
        assertThat(focused.getTableColumn().getUserData())
                .as("光标所在列就是 name")
                .isEqualTo("name");

        Node drawer = page.lookup("#data-details");
        assertThat(drawer).as("详情抽屉 #data-details").isNotNull();
        assertThat(drawer.isVisible())
                .as("抽屉要开着")
                .isTrue();
        assertThat(drawer.isManaged())
                .as("还要占位 —— 只 visible 不 managed，截图里它会被整块挤掉")
                .isTrue();
        assertThat(((Label) page.lookup(".adb-details__title")).getText())
                .as("抽屉标题就是这一格")
                .isEqualTo("name");

        boolean[] bogus = new boolean[1];
        WaitForAsyncUtils.asyncFx(() -> {
            bogus[0] = page.poseInteractionStates(1, "no_such_column");
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(bogus[0])
                .as("列不存在必须返回 false：摆不出完整状态就不能装作摆好了（AppShell 靠它决定重试）")
                .isFalse();
    }

    // ─────────────────────────── CSS：规则本身 ───────────────────────────

    /**
     * 选中 = 强 tint + accent 强调条，且描边宽度**不在这里改**。
     * 顺序也钉住：{@code :selected} 必须排在 {@code :hover} 之后，
     * 同特异度下后写的赢 —— 排前面就会被 hover 顶掉。
     */
    @Test
    void theSelectedRowHasAnAccentBarWithoutChangingWidth() throws IOException {
        String css = css();
        String selected = ruleBody(css, ".adb-grid .table-row-cell:selected");
        assertThat(selected)
                .as("选中行要用强调色令牌（不是新写死的颜色）")
                .contains("-adb-bg-selected")
                .contains("-adb-accent");
        assertThat(selected)
                .as("选中态只许改颜色：一旦在这里写 -fx-border-width，选中就把整行内容推走")
                .doesNotContain("-fx-border-width");
        assertThat(ruleBody(css, ".adb-grid .table-row-cell"))
                .as("左槽必须由基础规则常留 2px，且明确写出宽度")
                .containsPattern("-fx-border-width:\\s*0px 0px 1px 2px;");
        assertThat(css.indexOf(".adb-grid .table-row-cell:hover"))
                .as("选中规则要排在悬停之后，否则悬停会把选中色顶掉")
                .isLessThan(css.indexOf(".adb-grid .table-row-cell:selected"));
    }

    /**
     * 光标用**活的**选择器（样式类），不用永远匹配不上的 {@code :focused}；
     * 树的焦点格改用**淡底**，不再画蓝边 + 光晕（UX-5 的裁定，理由见下面第二条断言）。
     */
    @Test
    void theGridCursorIsLiveAndTheTreeCursorIsAFill() throws IOException {
        String css = css();
        String cursor = ruleBody(css, ".adb-grid .table-cell.adb-grid__cell--cursor");
        assertThat(cursor)
                .as("当前格用焦点色描边 + accent 淡底")
                .contains("-fx-border-color: -adb-border-focus")
                .contains("-adb-bg-active");
        assertThat(cursor)
                .as("光标态不许改描边宽度 —— 键盘每走一格就抖一次")
                .doesNotContain("-fx-border-width");
        assertThat(cursor)
                .as("网格光标不再用外发光（邻居会把光晕裁掉，实测像没有焦点）")
                .doesNotContain("dropshadow");
        assertThat(css)
                .as("行选模式下 .table-cell:focused 永远不匹配（TableCell.updateFocus 源码），"
                        + "留着就是一条死规则 —— 不许再写回来")
                .doesNotContain(".adb-grid .table-cell:focused");
        String treeFocus = ruleBody(css, ".adb-object-tree .tree-cell:focused");
        assertThat(treeFocus)
                .as("⭐ UX-5：树的焦点格改成淡底 —— 容器自己已经有焦点环（.tree-view:focused），"
                        + "格子里再画一圈就是「框中框」，一整列蓝边互相贴着像坏了；"
                        + "而且蓝边压在 bg-selected 上是两种语言叠一起")
                .doesNotContain("dropshadow")
                .doesNotContain("-fx-border-color")
                .contains("-adb-bg-active");
        assertThat(css.indexOf(".adb-object-tree .tree-cell:focused"))
                .as(":focused 与 :selected 同为 0,3,0，靠先后取胜 —— "
                        + "焦点必须排在选中**之前**，否则选中格只剩这层更淡的底，强调条没了")
                .isLessThan(css.indexOf(".adb-object-tree .tree-cell:selected"));
    }

    /**
     * 左槽由基础规则常留，光标 / 脏值 / 矩形选区三条只换颜色：都不许出现宽度声明。
     */
    @Test
    void gridLinesAndSemanticBarsShareOneSlot() throws IOException {
        String css = css();
        String cell = ruleBody(css, ".adb-grid .table-cell");
        assertThat(cell)
                .as("⭐ 2026-10-26（Navicat 对齐）：列间竖线回归 —— 实测 Navicat 数据网格"
                        + "每列边界 1px #F0F0F0；第二十一轮「竖线全部退场」作废。"
                        + "左右槽位常留，语义态只换颜色")
                .containsPattern("-fx-border-color:\\s*transparent -adb-border-subtle transparent transparent;")
                .containsPattern("-fx-border-width:\\s*0px 1px 0px 2px;");
        assertThat(ruleBody(css, ".adb-grid .column-header"))
                .as("表头同样要有列间竖线（Navicat 列头实测 #E5E5E5，落到 border-subtle）")
                .contains("-adb-border-subtle");
        for (String state : List.of(".adb-grid .table-cell.adb-grid__cell--dirty",
                ".adb-grid .table-cell.adb-grid__cell--rect")) {
            String body = ruleBody(css, state);
            assertThat(body).as(state + " 不许改宽度").doesNotContain("-fx-border-width");
        }
        assertThat(ruleBody(css, ".adb-grid .table-cell.adb-grid__cell--dirty"))
                .as("⭐ U13（2026-10-26）：脏值改挂专属 -adb-dirty / -adb-dirty-bg —— "
                        + "以前「未保存」和「写入风险」共用 warning 橙，两种语义一种颜色；"
                        + "左槽位置不变，仍只换颜色")
                .contains("-adb-dirty")
                .contains("-adb-dirty-bg")
                .doesNotContain("-adb-warning");
        assertThat(ruleBody(css, ".adb-grid .table-cell.adb-grid__cell--rect"))
                .as("矩形选区用焦点色描边 + accent 淡底")
                .contains("-adb-border-focus")
                .contains("-adb-bg-active");
    }

    /**
     * 表清单（表列表页）与 Navicat 17 实测对齐：表体纯白、无横向行线；
     * 列间分隔只在表头（竖线），表体不画格子。
     *
     * <p>实拍见 {@code docs/screenshots/navicat/navicat-table-list-2026-10-24.png}。
     * 变异：把行线加回去 → 红。
     */
    @Test
    void theTableListBodyStaysPlainLikeNavicat() throws IOException {
        String css = css();
        assertThat(ruleBody(css, ".adb-table-list .table-row-cell"))
                .as("Navicat 表清单表体没有横向行线")
                .doesNotContain("-adb-border-subtle");
        assertThat(ruleBody(css, ".adb-table-list .column-header"))
                .as("表头保留列间竖线")
                .contains("-adb-border-subtle");
    }

    /**
     * 三个表格作用域里的行态档（悬停 / 选中 / 新增 / 删除）都必须压得过自家条纹档。
     *
     * <p>⭐ R31 实拍抓到两类同源缺陷：弹窗字段网格选中的那一行只剩一圈蓝色描边、
     * 数据网格 {@code p2-cell} 选中的 {@code User 002} 整行是白的 —— 而按 Navicat，选中就该整行铺底。
     * 根因是条纹档多带一个伪类（{@code :filled:odd} 比所有行态档重 10），于是<b>只有奇数行</b>掉色：
     * 偶数行一直是对的，所以以前的帧一张都没看出来。
     * 这条门禁不查「规则在不在」，查的是<b>谁压谁</b>：权重不低于条纹档，同权重时声明在后。
     * 变异：任一条纹档写回 {@code :filled:odd} → 该作用域红；把选中档挪到条纹档前面 → 顺序红。
     */
    @Test
    void stateRulesOutweighTheZebraStripeInEveryTableScope() throws IOException {
        String css = css();
        assertThat(ruleBody(css, ".adb-dialog .table-view .table-row-cell:selected"))
                .as("弹窗表格的选中行必须整行铺 bg-selected，不是只剩单元格描边")
                .contains("-adb-bg-selected");

        for (String scope : List.of(".adb-grid", ".adb-table-list",
                ".adb-dialog .table-view")) {
            // 条纹档按「:odd 结尾」找，把选择器本身当参照物 —— 它被改回 :filled:odd 时，
            // 门禁要比的是「行态档压不压得动改后的它」，而不是先抱怨参照物没了。
            Matcher stripeRule = Pattern.compile("(?m)^(" + Pattern.quote(scope)
                    + " \\.table-row-cell[^{\\n]*:odd[^{\\n]*)\\{").matcher(css);
            assertThat(stripeRule.find())
                    .as(scope + " 的条纹档是本门禁的参照物，动它要连这里一起动")
                    .isTrue();
            String stripe = stripeRule.group(1).trim();
            int stripeAt = stripeRule.start(1);

            Matcher rules = Pattern.compile("(?m)^(" + Pattern.quote(scope)
                    + " \\.table-row-cell[^{\\n]*)\\{([^}]*)\\}").matcher(css);
            int checked = 0;
            while (rules.find()) {
                String selector = rules.group(1).trim();
                String body = rules.group(2);
                boolean rowState = selector.contains(":selected") || selector.contains(":hover")
                        || selector.contains("--deleted") || selector.contains("--new");
                if (!rowState || !body.contains("-fx-background-color") || selector.equals(stripe)) {
                    continue;
                }
                assertThat(specificity(selector))
                        .as(selector + " 不许被条纹档 " + stripe + " 压住 —— 否则奇数行一选中/悬停就刷回底色")
                        .isGreaterThanOrEqualTo(specificity(stripe));
                assertThat(rules.start(1))
                        .as(selector + " 与条纹档同权重时后声明的赢，必须排在 " + stripe + " 之后")
                        .isGreaterThan(stripeAt);
                checked++;
            }
            assertThat(checked)
                    .as(scope + " 至少要有一条行态档被比过（选择器一改写法，这里会静默变成 0 条）")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    /**
     * 斑马纹保持最弱一层。
     *
     * <p>⭐ 2026-10-26 用户裁定「保持和 Navicat 一样」：亮色条纹改为 Navicat 实测的
     * {@code #F9F9F9}（第二十一轮的 {@code #FAFBFC} 作废 —— 那一档是为「无边框 + 竖线全退场」
     * 定的；现在网格重新有 #F0F0F0 细线，条纹要跟 Navicat 的实测值走，
     * 但仍不能压过悬停底 / 选中底，否则表又变成数格子）。
     */
    @Test
    void theZebraStripeIsTheQuietestLayer() throws IOException {
        JsonNode stripe = tokens().path("bg-stripe");
        assertThat(stripe.path("dark").asText())
                .as("暗色条纹保持 3.5%：三层底色里条纹必须最弱")
                .isEqualTo("rgba(148,163,184,.035)");
        assertThat(stripe.path("light").asText())
                .as("亮色条纹 = Navicat 实测 #F9F9F9")
                .isEqualTo("#F9F9F9");
        assertThat(tokens().path("bg-surface").path("light").asText())
                .as("条纹仍然要和底色区分（降过头就成纯白，斑马纹等于没有）")
                .isNotEqualTo(stripe.path("light").asText());
        assertThat(ruleBody(css(), ".adb-grid .table-row-cell:odd"))
                .as("网格斑马纹走令牌，不许写死颜色（⭐ R31：伪类只许带 :odd 一个，"
                        + "多带 :filled 会把行态档整条压掉）")
                .contains("-adb-bg-stripe");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private TableView<?> table() {
        Node node = page.lookup("#data-table");
        return node instanceof TableView<?> tableView ? tableView : null;
    }

    private TableView<?> requireTable() {
        TableView<?> table = table();
        assertThat(table).as("数据表格 #data-table").isNotNull();
        return table;
    }

    /**
     * 取某一行的 {@code TableRow}。
     *
     * <p>⚠️ 不用 {@code robot.lookup(".table-row-cell").n(i).query()}：
     * VirtualFlow 里混着空行、匹配也不保证顺序。这里按 {@code getIndex()} 精确认领，
     * 找不到就直接抛 —— 宁可抛异常，也不放行。
     */
    private static TableRow<?> requireRow(TableView<?> table, int index) {
        List<TableRow<?>> rows = rows(table);
        assertThat(rows).as("渲染出来的行（VirtualFlow 只挂可见行）").isNotEmpty();
        for (TableRow<?> row : rows) {
            if (row.getIndex() == index) {
                assertThat(row.isEmpty())
                        .as("第 %d 行要有数据", index)
                        .isFalse();
                return row;
            }
        }
        throw new AssertionError("第 " + index + " 行没有被渲染出来（可见行："
                + rows.stream().map(TableRow::getIndex).toList() + "）");
    }

    private static List<TableRow<?>> rows(TableView<?> table) {
        List<TableRow<?>> found = new ArrayList<>();
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row) {
                found.add(row);
            }
        }
        return found;
    }

    /** 取「name」这一列的单元格 —— 文本列，点下去不会误碰复选框。 */
    private static TableCell<?, ?> requireCell(TableRow<?> row) {
        return requireCell(row, "name");
    }

    /** 取指定列在这一行渲染出来的单元格。 */
    private static TableCell<?, ?> requireCell(TableRow<?> row, String column) {
        List<TableCell<?, ?>> cells = cells(row);
        assertThat(cells).as("该行渲染出来的单元格").isNotEmpty();
        for (TableCell<?, ?> cell : cells) {
            if (cell.getTableColumn() != null
                    && column.equals(cell.getTableColumn().getUserData())) {
                assertThat(cell.isVisible()).as(column + " 列的单元格要可见").isTrue();
                return cell;
            }
        }
        throw new AssertionError("没有渲染出 " + column + " 列的单元格（列："
                + cells.stream()
                        .map(c -> String.valueOf(c.getTableColumn() == null
                                ? null : c.getTableColumn().getUserData()))
                        .toList() + "）");
    }

    private static List<TableCell<?, ?>> cells(TableRow<?> row) {
        List<TableCell<?, ?>> found = new ArrayList<>();
        for (Node node : row.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> cell) {
                found.add(cell);
            }
        }
        found.sort((a, b) -> Double.compare(a.getLayoutX(), b.getLayoutX()));
        return found;
    }

    /** 该行所有单元格的相对位置（x, y 交替，按 x 排序）—— 「有没有跳」就是量这个。 */
    private static List<Double> cellPositions(TableRow<?> row) {
        List<Double> positions = new ArrayList<>();
        for (TableCell<?, ?> cell : cells(row)) {
            positions.add(round(cell.getLayoutX()));
            positions.add(round(cell.getLayoutY()));
        }
        return positions;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private void selectRow(int index) {
        TableView<?> table = requireTable();
        WaitForAsyncUtils.asyncFx(() -> {
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(index);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 按列名取数据列（行首槽与隐藏列不算）。 */
    private static TableColumn<?, ?> dataColumn(TableView<?> table, String name) {
        for (TableColumn<?, ?> column : table.getColumns()) {
            if (name.equals(column.getUserData())) {
                return column;
            }
        }
        throw new AssertionError("没有这一列: " + name);
    }

    /**
     * 把键盘光标放到某一格 —— {@code TableView<?>} 的通配符会让
     * {@code FocusModel.focus(..)} 的泛型对不上，这里用 raw 调一次。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void focusCell(TableView<?> table, int row, TableColumn<?, ?> column) {
        ((TableView) table).getFocusModel().focus(row, column);
    }

    /** 选中并进入行内编辑（与真实双击走同一条 {@code TableView.edit(..)}）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void editCell(TableView<?> table, int row, TableColumn<?, ?> column) {
        ((TableView) table).getSelectionModel().clearAndSelect(row, column);
        ((TableView) table).edit(row, column);
    }

    /** 选择器的粗粒度权重（#id=100、类/伪类=10、元素=1），够用来比「谁压谁」。 */
    private static int specificity(String selector) {
        int score = 0;
        for (char c : selector.toCharArray()) {
            if (c == '#') {
                score += 100;
            } else if (c == '.' || c == ':') {
                score += 10;
            }
        }
        return score;
    }

    private static String ruleBody(String css, String selector) {
        Matcher matcher = Pattern.compile(
                "(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)\\}").matcher(css);
        assertThat(matcher.find()).as("app.css 里找不到选择器 %s", selector).isTrue();
        return matcher.group(1);
    }

    private static String css() throws IOException {
        return readResource("/io/agenticdb/ui/app.css").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static JsonNode tokens() throws IOException {
        return new ObjectMapper().readTree(readResource("/tokens/tokens.json")).path("tokens");
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = SelectionStateTest.class.getResourceAsStream(path)) {
            assertThat(stream).as("缺少资源 %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }
}
