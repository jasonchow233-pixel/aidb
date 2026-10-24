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
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
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
 *   <li>焦点单元格用的外发光**被邻居裁掉**，看起来像根本没有焦点；</li>
 *   <li>只有横线没有竖线，宽表读到第 5 列就串行。</li>
 * </ul>
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
     * 竖线 + 脏值条共用同一套槽位：三处都不许出现宽度声明。
     */
    @Test
    void gridLinesAndSemanticBarsShareOneSlot() throws IOException {
        String css = css();
        String cell = ruleBody(css, ".adb-grid .table-cell");
        assertThat(cell)
                .as("单元格要有淡竖线，颜色复用 border-subtle（不引入新色）")
                .containsPattern("-fx-border-color:\\s*transparent -adb-border-subtle "
                        + "transparent transparent;")
                .containsPattern("-fx-border-width:\\s*0px 1px 0px 2px;");
        for (String state : List.of(".adb-grid .table-cell.adb-grid__cell--dirty",
                ".adb-grid .table-cell.adb-grid__cell--rect")) {
            String body = ruleBody(css, state);
            assertThat(body).as(state + " 不许改宽度").doesNotContain("-fx-border-width");
        }
        assertThat(ruleBody(css, ".adb-grid .table-cell.adb-grid__cell--dirty"))
                .as("脏值条还是那条 warning 色的左条")
                .contains("-adb-warning");
        assertThat(ruleBody(css, ".adb-grid .table-cell.adb-grid__cell--rect"))
                .as("矩形选区用焦点色描边 + accent 淡底")
                .contains("-adb-border-focus")
                .contains("-adb-bg-active");
    }

    /**
     * 斑马纹再降一档：暗色 5% → 3.5%，亮色 #F7F9FB → #FAFBFC。
     * 加了竖线之后，条纹 + 横线 + 竖线三层同时在场会显花，条纹必须最弱。
     */
    @Test
    void theZebraStripeIsTheQuietestLayer() throws IOException {
        JsonNode stripe = tokens().path("bg-stripe");
        assertThat(stripe.path("dark").asText())
                .as("暗色条纹降到 3.5%：竖线上场后条纹必须退到最弱一层")
                .isEqualTo("rgba(148,163,184,.035)");
        assertThat(stripe.path("light").asText())
                .as("亮色条纹向纯白再靠一档")
                .isEqualTo("#FAFBFC");
        assertThat(tokens().path("bg-surface").path("light").asText())
                .as("条纹仍然要和底色区分（降过头就成纯白，斑马纹等于没有）")
                .isNotEqualTo(stripe.path("light").asText());
        assertThat(ruleBody(css(), ".adb-grid .table-row-cell:filled:odd"))
                .as("网格斑马纹走令牌，不许写死颜色")
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
