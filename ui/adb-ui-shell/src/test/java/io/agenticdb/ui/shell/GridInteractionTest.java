package io.agenticdb.ui.shell;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U-B5 门禁：数据网格的**交互细节**。
 *
 * <ol>
 *   <li><b>记录导航条</b>（首 / 上 / 下 / 末 + 行号）—— Navicat 形态；</li>
 *   <li><b>列头筛选钮悬停才浮现</b> —— 以前每列常显一个漏斗，
 *       12 列就挂 12 个，噪音压过列名本身。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class GridInteractionTest {

    @TempDir
    Path temp;

    private DbSession session;
    private ExecutorService executor;
    private DataGridPage page;
    private Stage stage;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
        // ⭐ 带 SettingsStore —— 列顺序要能落盘（没有它 persistLayout 直接返回）。
        page = new DataGridPage(session, new SchemaService(), "users",
                "test.db", noOpFeedback(), name -> { }, executor, new SettingsStore());
        Scene scene = new Scene(page, 1200, 700);
        // ⚠️ 必须把样式表挂上 —— 否则 CSS 规则一条都不生效
        //    （App 里是由 ThemeManager 挂的，测试里要自己挂）。
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        page.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * 等表格加载完。
     *
     * <p>⚠️ 这个等待**不能**放在 {@code @Start} 里 ——
     * {@code WaitForAsyncUtils.waitFor} 会阻塞 FX 线程，
     * 而表格的加载结果恰恰要靠 FX 线程派发 → 死锁 → 超时。
     */
    @BeforeEach
    void waitForTheTable() throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null
                        && ((TableView<?>) page.lookup("#data-table")).getItems().size() == 200);
    }

    @AfterEach
    void tearDown() {
        // 清掉测试写进用户偏好的列顺序，别影响下一次跑。
        java.util.prefs.Preferences.userNodeForPackage(SettingsStore.class)
                .remove("ui.grid.test.db.users.order");
        java.util.prefs.Preferences.userNodeForPackage(SettingsStore.class)
                .remove("ui.grid.test.db.users.hidden");
        executor.shutdownNow();
        try {
            session.close();
        } catch (Exception ignored) {
            // 清理失败不影响结论。
        }
    }

    @Test
    void theRecordNavigatorWalksTheRows() throws Exception {
        // ⭐ N20：网格里点一行就是导航；记录导航条只在表单视图里出现。
        WaitForAsyncUtils.asyncFx(() -> {
            page.setFormView(true);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.lookup("#data-record-nav")).as("记录导航条").isNotNull();
        assertThat(page.lookup("#data-record-nav").isVisible()).as("表单视图里导航条可见").isTrue();
        assertThat(page.lookup("#data-record-first")).as("首行").isNotNull();
        assertThat(page.lookup("#data-record-prev")).as("上一行").isNotNull();
        assertThat(page.lookup("#data-record-next")).as("下一行").isNotNull();
        assertThat(page.lookup("#data-record-last")).as("末行").isNotNull();

        TableView<?> table = (TableView<?>) page.lookup("#data-table");

        click("#data-record-next");
        int afterNext = table.getSelectionModel().getSelectedIndex();

        click("#data-record-next");
        assertThat(table.getSelectionModel().getSelectedIndex())
                .as("「下一行」要把选中行往下挪")
                .isEqualTo(afterNext + 1);

        click("#data-record-last");
        assertThat(table.getSelectionModel().getSelectedIndex())
                .as("「末行」要跳到本页最后一行")
                .isEqualTo(table.getItems().size() - 1);

        click("#data-record-first");
        assertThat(table.getSelectionModel().getSelectedIndex()).isZero();

        Label position = (Label) page.lookup("#data-record-position");
        assertThat(position.getText())
                .as("行数要在数据加载后更新（不能停在「共 0 行」）")
                .isEqualTo(I18n.t("grid.record.position", 1, 200));
    }

    @Test
    void theColumnFilterButtonOnlyShowsOnHover() {
        Button funnel = (Button) page.lookup("#data-filter-name");
        assertThat(funnel).as("列头筛选钮").isNotNull();
        assertThat(funnel.getStyleClass())
                .as("常态没有筛选条件时不该常显")
                .doesNotContain("adb-grid__filter-button--active");
        assertThat(funnel.getOpacity())
                .as("常态隐藏 —— 悬停列头才浮现")
                .isEqualTo(0.0);
    }

    @Test
    void aFilteredColumnKeepsItsFilterButtonVisible() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> page.applyQuery(io.agenticdb.introspect.DataQuery.empty()
                .withFilter(io.agenticdb.introspect.FilterCondition.of(
                        "active", io.agenticdb.introspect.FilterOperator.EQ, 1)))).get();
        WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                () -> page.lastSql().contains("WHERE"));
        WaitForAsyncUtils.waitForFxEvents();

        Button funnel = (Button) page.lookup("#data-filter-active");
        assertThat(funnel).as("被筛选的列的筛选钮").isNotNull();
        assertThat(funnel.getStyleClass())
                .as("该列**有**筛选条件时，筛选钮要一直看得见 —— 那是状态，不是装饰")
                .contains("adb-grid__filter-button--active");
        assertThat(funnel.getOpacity()).isEqualTo(1.0);
    }

    // ─────────────────────────── G6：自增列 ───────────────────────────

    @Test
    void theAutoIncrementColumnIsDetected() {
        io.agenticdb.introspect.SchemaService schemaService =
                new io.agenticdb.introspect.SchemaService();
        try {
            io.agenticdb.introspect.SchemaService.TableSchema schema =
                    schemaService.describe(session, "users");
            // SQLite 的 rowid 别名规则：恰好声明为 INTEGER PRIMARY KEY 的单列主键
            assertThat(schema.autoIncrementColumn())
                    .as("id INTEGER PRIMARY KEY 就是 SQLite 的 rowid 别名（自增列）")
                    .isEqualTo("id");
            assertThat(schema.autoIncrement("id")).isTrue();
            assertThat(schema.autoIncrement("name")).isFalse();
        } catch (java.sql.SQLException e) {
            throw new AssertionError("读表结构失败", e);
        }
    }

    @Test
    void theGridMarksTheAutoIncrementColumnInTheTypeHint() {
        javafx.scene.control.Label type = (javafx.scene.control.Label)
                page.lookup(".adb-grid__column-type");
        assertThat(type).isNotNull();
        // N19 之后第二行只放类型，约束在 tooltip —— 自增列必须仍能被找出来。
        boolean anyMarked = page.lookupAll(".adb-grid__column-type").stream()
                .filter(javafx.scene.control.Label.class::isInstance)
                .map(javafx.scene.control.Label.class::cast)
                .anyMatch(label -> label.getTooltip() != null
                        && label.getTooltip().getText() != null
                        && label.getTooltip().getText().contains(I18n.t("grid.head.autoInc")));
        assertThat(anyMarked)
                .as("自增列要在列头上标出来 —— 否则用户会以为得自己填主键")
                .isTrue();
    }

    /**
     * ⭐ U5 + N19（2026-10-31 改判）：第一行只有列名，第二行只有类型
     * （数值带 {@code #} 前缀，与 Navicat 帧一致）；约束改用类型标签的 tooltip 说明。
     *
     * <p>U5 的动机没变：不要出现 {@code id [PK]} 和没人解释的 {@code name •}。
     * N19 只是把「解释约束」从可见的第二行挪进 tooltip —— 信息不丢，第二行
     * 回到「一眼认出类型」这一件事。
     */
    @Test
    void headerSecondLineShowsOnlyTheTypeAndTheHintKeepsTheConstraints() {
        assertThat(headerName("id"))
                .as("第一行只剩列名 —— [PK] 徽章退场")
                .isEqualTo("id");
        assertThat(headerName("name"))
                .as("第一行不许再挂那个没有解释的圆点")
                .isEqualTo("name");
        assertThat(headerType("id"))
                .as("数值列第二行是 `# 类型`，不再带约束文案")
                .startsWith("# ")
                .doesNotContain(I18n.t("grid.head.pk"))
                .doesNotContain(I18n.t("grid.head.autoInc"))
                .doesNotContain(I18n.t("grid.head.notNull"));
        assertThat(headerType("name"))
                .as("非数值列第二行只有类型")
                .doesNotContain(I18n.t("grid.head.notNull"))
                .doesNotContain("·");
        assertThat(headerTypeHint("id"))
                .as("主键与自增仍在 tooltip 里说清楚")
                .contains(I18n.t("grid.head.pk"))
                .contains(I18n.t("grid.head.autoInc"));
        assertThat(headerTypeHint("name"))
                .as("非空仍在 tooltip 里说清楚，不是没人解释的符号")
                .contains(I18n.t("grid.head.notNull"));
        assertThat(page.lookupAll(".adb-grid__column-name").stream()
                .filter(node -> node instanceof javafx.scene.control.Label)
                .map(node -> ((javafx.scene.control.Label) node).getText())
                .filter(text -> text != null && (text.contains("[PK]") || text.contains("•")))
                .toList())
                .as("任何列头第一行都不许残留 [PK] / • 旧语法")
                .isEmpty();
    }

    /**
     * ⭐⭐ D-56：列头第二行的类型必须是**引擎自己的类型名**，不是我们的抽象枚举。
     *
     * <p>Navicat 帧（navicat17-data-grid）读 {@code int(11)} / {@code varchar(255)} ——
     * 一眼分清 int 与 bigint、varchar 与 text。我们的 `p2-users` 帧读
     * {@code number / text(65535)}：抽象 {@code ColumnType} 把六种整型折成一颗
     * number，用户没法从界面上判断能不能塞下他的数。
     * {@link io.agenticdb.model.ColumnDef#displayType()} 早就存在（rawType 优先），
     * 结构页/详情面板都接了，唯独网格列头还在走 {@code type().name().toLowerCase()}。
     */
    @Test
    void theHeaderTypeLineShowsTheRealDeclaredType() {
        assertThat(headerType("id"))
                .as("D-56：数值列第二行是声明类型 INTEGER（# 前缀按 N19 保留）")
                .isEqualTo("# INTEGER");
        assertThat(headerType("email"))
                .as("D-56：文本列第二行是声明类型 TEXT —— 不是抽象 text 加 65535 长度")
                .isEqualTo("TEXT");
        assertThat(page.lookupAll(".adb-grid__column-type").stream()
                .filter(node -> node instanceof javafx.scene.control.Label)
                .map(node -> ((javafx.scene.control.Label) node).getText())
                .filter(text -> text != null
                        && (text.contains("number") || text.startsWith("text(")))
                .toList())
                .as("抽象枚举词汇不许再出现在任何列头第二行")
                .isEmpty();
    }

    /**
     * ⭐⭐ D-61：网格工具条上仍落如实说明页的**假按钮**必须在点之前就说实话 ——
     * 右上角挂 PendingMark 环，tooltip 与 accessibleText 带同一句「本版本未提供」。
     *
     * <p>名单随能力面收窄：BI Workspace 已整体移除、Data Masking 在 P-3 接上真
     * 导出向导，两者都不再挂环（判据始终是落点是不是 {@code showCapabilityNotice}）。
     * 环的几何断言走**宿主排里有 mark 孩子**这一构造事实，
     * 不量像素（像素归帧，这里归接线）。
     */
    @Test
    void capabilityPlaceholdersAdvertiseThemselvesBeforeTheClick() {
        for (String id : java.util.List.of("data-fill-ai", "data-profiling",
                "data-generation")) {
            javafx.scene.Node node = page.lookup("#" + id);
            assertThat(node).as("落点按钮 #%s", id).isNotNull();
            javafx.scene.control.ButtonBase button = (javafx.scene.control.ButtonBase) node;
            assertThat(button.getTooltip())
                    .as("#%s 的 tooltip 必须提前说「本版本未提供」", id)
                    .isNotNull();
            assertThat(button.getTooltip().getText())
                    .contains(I18n.t("ribbon.pending"));
            assertThat(button.getAccessibleText())
                    .as("#%s 读屏侧要说同一句", id)
                    .contains(I18n.t("ribbon.pending"));
            assertThat(((javafx.scene.layout.Pane) button.getParent()).getChildren().stream()
                    .anyMatch(child -> child.getStyleClass().contains("adb-pending__mark")))
                    .as("#%s 所在那一排必须挂了角标环（managed=false，不吃排版）", id)
                    .isTrue();
        }
    }

    private String headerName(String column) {
        javafx.scene.control.Label name = (javafx.scene.control.Label)
                page.lookup("#data-header-" + column);
        assertThat(name).as("列头 %s 的第一行", column).isNotNull();
        return name.getText();
    }

    private String headerType(String column) {
        javafx.scene.control.Label name = (javafx.scene.control.Label)
                page.lookup("#data-header-" + column);
        javafx.scene.layout.VBox labels = (javafx.scene.layout.VBox) name.getParent();
        return ((javafx.scene.control.Label) labels.getChildren().get(1)).getText();
    }

    /** 第二行（类型）标签的 tooltip —— N19 之后约束写在这里。 */
    private String headerTypeHint(String column) {
        javafx.scene.control.Label name = (javafx.scene.control.Label)
                page.lookup("#data-header-" + column);
        javafx.scene.layout.VBox labels = (javafx.scene.layout.VBox) name.getParent();
        javafx.scene.control.Label type =
                (javafx.scene.control.Label) labels.getChildren().get(1);
        assertThat(type.getTooltip()).as("列头 %s 的类型标签必须有 tooltip", column).isNotNull();
        return type.getTooltip().getText();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aNewRowLeavesTheAutoIncrementColumnEmpty() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        int before = table.getItems().size();

        WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.control.Button add =
                    (javafx.scene.control.Button) page.lookup("#data-add");
            add.fire();
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(table.getItems().size()).isEqualTo(before + 1);
        // 草稿行排在**前面**（rebuildItems 的顺序：草稿 → 本页数据）
        java.util.Map<String, Object> draft = null;
        for (Object item : table.getItems()) {
            java.util.Map<String, Object> row = (java.util.Map<String, Object>) item;
            if (row.containsKey(io.agenticdb.editing.EditBuffer.DRAFT_KEY)) {
                draft = row;
                break;
            }
        }
        assertThat(draft).as("新增的草稿行").isNotNull();
        assertThat(draft.get("id"))
                .as("新增行时自增列必须**留空** —— 由数据库赋值")
                .isNull();
    }

    @Test
    void columnsCanBeReorderedAtRuntime() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);
        assertThat(before)
                .as("先确认有可重排的数据列")
                .hasSizeGreaterThan(2);

        // ⭐ G2：列头**拖拽**重排 —— 顺序本来就能持久化，只是没有运行时入口。
        //    这里用「向右移动一列」这条精确入口验证同一套重排逻辑
        //    （拖拽要用真实的鼠标轨迹，测试里不稳）。
        int from = before.indexOf("score");
        assertThat(from).as("score 列存在").isGreaterThan(0);
        WaitForAsyncUtils.asyncFx(() -> {
            page.reorderColumn("score", from + 1);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        List<String> after = columnOrder(table);
        assertThat(after)
                .as("列顺序要真的变了")
                .isNotEqualTo(before);
        assertThat(after)
                .as("只是换了位置，列本身一个不能少")
                .containsExactlyInAnyOrderElementsOf(before);
        assertThat(after.get(from + 1))
                .as("被移动的列要落到目标位置")
                .isEqualTo("score");
    }

    @Test
    void draggingAHeaderReordersTheColumns(FxRobot robot) {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);
        // ⭐ S-6 之后列有可读下限：12 列在 1200px 里放不下时，**最右边那列在视野外**
        //    （节点在、鼠标却够不着，TestFX 报 bounds are not visible in Scene）。
        //    拖拽验证的是「列头拖放 → 重排」这条链路，不挑哪一列，
        //    所以抓前两列 —— 它们在任何宽度下都可见。
        javafx.scene.Node source = page.lookup("#data-header-" + before.get(1));
        javafx.scene.Node target = page.lookup("#data-header-" + before.get(0));
        assertThat(source).as("第二个列头").isNotNull();
        assertThat(target).as("第一个列头").isNotNull();

        // TestFX 4.x：drag(源) + dropTo(目标)
        robot.drag(source);
        robot.dropTo(target);
        WaitForAsyncUtils.waitForFxEvents();

        List<String> after = columnOrder(table);
        assertThat(after)
                .as("拖拽后列顺序要变")
                .isNotEqualTo(before);
        assertThat(after)
                .as("只是换了位置，列本身一个不能少")
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void theReorderedColumnsArePersisted() throws Exception {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);
        int from = before.indexOf("score");
        WaitForAsyncUtils.asyncFx(() -> {
            page.reorderColumn("score", from + 1);
            return null;
        });
        WaitForAsyncUtils.waitForFxEvents();

        // ⭐ 落盘是防抖的（400ms），必须等它真的写下去。
        //    以前这条直接读 savedColumnOrder() 就能过 —— 因为读到的其实是
        //    **上一轮跑留在偏好里的旧顺序**，落盘本身根本没发生。
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> !page.savedColumnOrder().isBlank());

        String saved = page.savedColumnOrder();
        assertThat(saved)
                .as("新顺序要落盘 —— 下次打开还是这个顺序")
                .isNotBlank()
                .contains("score");
    }

    // ─────────────────────────── UX-4：列头右键菜单 ───────────────────────────

    /**
     * 列头右键菜单要装齐 Navicat 那一套列工具 —— 重点是<b>冻结</b>。
     * 冻结原来只藏在工具条「视图 → 冻结左侧列」的子菜单里，
     * 看不见子菜单的人就等于没有这个功能。
     */
    @Test
    void theHeaderMenuCarriesTheWholeColumnToolset() {
        ContextMenu menu = openHeaderMenu("name");
        List<String> texts = menu.getItems().stream()
                .map(javafx.scene.control.MenuItem::getText)
                .toList();
        assertThat(texts)
                .as("排序 / 移动 / 筛选 / 隐藏 / 自适应 / 冻结都要在列头右键里")
                .contains(I18n.t("grid.head.sortAsc"), I18n.t("grid.head.sortDesc"),
                        I18n.t("grid.head.clearSort"), I18n.t("grid.head.moveLeft"),
                        I18n.t("grid.head.moveRight"), I18n.t("grid.head.moveFirst"),
                        I18n.t("grid.head.moveLast"), I18n.t("grid.head.filterColumn"),
                        I18n.t("grid.head.hideColumn"), I18n.t("grid.head.fit"),
                        I18n.t("grid.head.freezeHere"), I18n.t("grid.head.unfreezeColumns"));
        hide(menu);
    }

    /** 冻结到此列 = 该列（含它左边的列）搬进左边的冻结面板。 */
    @Test
    void freezingToAColumnMovesItIntoTheFrozenPane() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        TableView<?> frozen = (TableView<?>) page.lookup("#data-frozen-table");
        List<String> order = columnOrder(table);
        assertThat(order).as("至少 3 列才谈得上冻结").hasSizeGreaterThanOrEqualTo(3);
        // 取第 2 列：它右边还有列，「冻结到此列」不会是禁用态。
        String columnToFreeze = order.get(1);
        int target = order.indexOf(columnToFreeze);

        ContextMenu menu = openHeaderMenu(columnToFreeze);
        assertThat(item(menu, "data-freeze-here").isDisable())
                .as("前置条件：这一列右边还剩列，菜单项必须是可用态")
                .isFalse();
        fireItem(menu, "data-freeze-here");

        assertThat(page.frozenColumnCount())
                .as("冻结到第 %d 列就要冻结 %d 列", target + 1, target + 1)
                .isEqualTo(target + 1);
        assertThat(columnNames(frozen))
                .as("被冻结的列要在冻结面板里")
                .contains(columnToFreeze);
        assertThat(columnNames(table))
                .as("同一列不能同时出现在两张表里")
                .doesNotContain(columnToFreeze);
    }

    /** 取消冻结 = 列全部回到主表，一个不丢。 */
    @Test
    void unfreezingReturnsEveryColumnToOneTable() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> before = columnOrder(table);

        ContextMenu menu = openHeaderMenu(before.get(1));
        fireItem(menu, "data-freeze-here");
        assertThat(columnNames((TableView<?>) page.lookup("#data-frozen-table")))
                .as("前置条件：先真的冻上").isNotEmpty();

        ContextMenu again = openHeaderMenu(before.get(0));
        fireItem(again, "data-unfreeze");

        assertThat(page.frozenColumnCount())
                .as("取消冻结后冻结列数归零")
                .isZero();
        assertThat(columnNames(table))
                .as("取消冻结后所有列回到主表，一列不能少")
                .containsExactlyElementsOf(before);
    }

    /**
     * 已经冻上的列、以及右边剩不下两列的列，「冻结到此列」必须**禁用**。
     * 否则用户点了会得到一个和菜单文字不符的结果（最后一列点冻结只会冻到倒数第二列）。
     */
    @Test
    void freezeHereIsDisabledWhenItWouldDoNothingOrLie() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        List<String> order = columnNames(table);
        assertThat(order).as("至少 3 列才谈得上冻结").hasSizeGreaterThanOrEqualTo(3);

        ContextMenu menu = openHeaderMenu(order.get(1));
        fireItem(menu, "data-freeze-here");
        assertThat(page.frozenColumnCount())
                .as("前置条件：第 2 列点「冻结到此列」= 冻 2 列")
                .isEqualTo(2);

        ContextMenu onFrozen = openHeaderMenu(order.get(1));
        assertThat(item(onFrozen, "data-freeze-here").isDisable())
                .as("已经冻上的列，「冻结到此列」要禁用")
                .isTrue();
        assertThat(item(onFrozen, "data-unfreeze").isDisable())
                .as("冻着的时候「取消冻结」必须可用")
                .isFalse();
        hide(onFrozen);

        ContextMenu onLast = openHeaderMenu(order.get(order.size() - 1));
        assertThat(item(onLast, "data-freeze-here").isDisable())
                .as("最右列冻结后右边一列都不剩，必须禁用而不是偷偷少冻一列")
                .isTrue();
        hide(onLast);
    }

    /**
     * 双击列头 = 按**实际渲染出来的内容**自适应列宽。
     * 门禁盯的是「宽度真的变了」，不是「有没有这个处理器」。
     */
    @Test
    void doubleClickingTheHeaderFitsTheColumnToItsContent() {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        TableColumn<?, ?> email = table.getColumns().stream()
                .filter(column -> "email".equals(column.getUserData()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("email 列不存在"));
        fx(() -> email.setPrefWidth(45));
        assertThat(email.getPrefWidth())
                .as("前置条件：先把列压窄").isLessThanOrEqualTo(45.0);

        javafx.scene.Node header = page.lookup("#data-header-email");
        assertThat(header).as("email 列头").isNotNull();
        fx(() -> {
            double x = Math.max(1, header.getBoundsInLocal().getWidth() / 2);
            double y = Math.max(1, header.getBoundsInLocal().getHeight() / 2);
            header.fireEvent(new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, x, y, x, y,
                    javafx.scene.input.MouseButton.PRIMARY, 2,
                    false, false, false, false,
                    false, false, false, false, false, false, null));
        });

        assertThat(email.getPrefWidth())
                .as("双击列头要按内容把宽度撑开，而不是停在 45px")
                .isGreaterThan(45.0);
    }

    // ─────────────────────── UX-4：行操作条（网格左下） ───────────────────────

    /**
     * ⭐ N19：编辑簇整排搬进网格左下的行操作条（{@code + − ✓ ✗ ⋯}），
     * 顶栏不再有第二份同名按钮 —— 一簇状态只有一份真相。
     *
     * <p>本用例钉住：五颗都在这一条里、新增可用 / 保存与取消灰着、
     * 删除随选中行出现、改一笔后保存与取消一起亮。
     */
    @Test
    void theRowActionBarOwnsTheEditState() throws Exception {
        Button add = (Button) page.lookup("#data-add");
        Button delete = (Button) page.lookup("#data-delete-rows");
        Button save = (Button) page.lookup("#data-save");
        Button cancel = (Button) page.lookup("#data-row-cancel");
        ButtonBase more = (ButtonBase) page.lookup("#data-row-more");
        HBox bar = (HBox) page.lookup("#data-row-actions");
        assertThat(bar).as("行操作条本体").isNotNull();
        assertThat(bar.getChildren())
                .as("编辑簇只在行操作条里出现一次：+ − ✓ ✗ ⋯")
                .containsExactly(add, delete, save, cancel, more);

        // 刚打开、还没改任何东西：能新增，但「保存 / 取消」必须还灰着。
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !add.isDisabled());
        assertThat(save.isDisabled())
                .as("没东西可保存时「保存」必须灰着")
                .isTrue();
        assertThat(cancel.isDisabled())
                .as("「取消」跟着「保存」一起灰 —— 没有可放弃的改动")
                .isTrue();

        // 没选中行：删除不占位（底部不许摆一颗点不动的删除）
        assertThat(delete.isVisible())
                .as("没有选中行时删除消失")
                .isFalse();

        // 选中一行：删除出现且可用（网格视图里点行 = 选中，不再有记录导航条）
        WaitForAsyncUtils.asyncFx(() -> {
            ((TableView<?>) page.lookup("#data-table")).getSelectionModel().select(0);
            return null;
        }).get();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(delete.isVisible())
                .as("选中行之后删除要跟着出现")
                .isTrue();
        assertThat(delete.isDisabled()).isFalse();

        // 改一笔：保存 / 取消一起亮
        click("#data-add");
        assertThat(save.isDisabled())
                .as("缓冲脏了，「保存」必须可点")
                .isFalse();
        assertThat(cancel.isDisabled()).isFalse();
        assertThat(add.isDisabled())
                .as("草稿行已经加进来，新增仍然可用")
                .isFalse();
    }

    /**
     * 「取消」要真的把缓冲清空（回到数据库的样子），
     * 只读表（视图没有主键）则**整排行操作条不出现**。
     */
    @Test
    void cancelDropsTheBufferAndReadOnlyTablesHideTheBar() throws Exception {
        TableView<?> table = (TableView<?>) page.lookup("#data-table");
        int before = table.getItems().size();

        click("#data-add");
        assertThat(table.getItems().size())
                .as("前置条件：草稿行先真的加上")
                .isEqualTo(before + 1);

        click("#data-row-cancel");
        assertThat(table.getItems().size())
                .as("点「取消」要把草稿行退回去")
                .isEqualTo(before);
        assertThat(((Button) page.lookup("#data-save")).isDisabled())
                .as("缓冲空了，「保存」必须重新变灰")
                .isTrue();

        // ⭐ 只读：视图没有主键 → editable() = false → 整排不该出现
        DataGridPage viewPage = new DataGridPage(session, new SchemaService(),
                "v_active_users", "test.db", noOpFeedback(), name -> { }, executor,
                new SettingsStore());
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                viewPage.lookup("#data-row-actions") != null
                        && ((TableView<?>) viewPage.lookup("#data-table"))
                        .getItems().size() > 0);
        HBox bar = (HBox) viewPage.lookup("#data-row-actions");
        assertThat(bar.isVisible())
                .as("只读表不出现行操作条 —— 一排永远点不动的按钮只会碍事")
                .isFalse();
        assertThat(bar.isManaged())
                .as("不占位：整排从布局里拿掉，不是盖一层 opacity")
                .isFalse();
    }

    /** 网格里可见的数据列顺序（列名）。 */
    private static List<String> columnOrder(TableView<?> table) {
        List<String> names = new java.util.ArrayList<>();
        table.getColumns().forEach(column -> {
            if (column.getUserData() instanceof String name) {
                names.add(name);
            }
        });
        return names;
    }

    /** 表上**挂着**的数据列（含隐藏列）—— 冻结看的是列归属，不是可见性。 */
    private static List<String> columnNames(TableView<?> table) {
        return columnOrder(table);
    }

    /** 在 FX 线程上跑一段代码并**等它真的跑完**；被打断就当测试失败，不放行。 */
    private static void fx(Runnable action) {
        try {
            WaitForAsyncUtils.asyncFx(action).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("FX 线程上的动作没完成：" + e, e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * 派发一次真实的「右键请求」，触发列头菜单处理器。
     *
     * <p>菜单是处理器里现场构建的，测试读的是处理器**实际建出来的那个实例**
     * （{@code headerMenuForTest()} 只在处理器里赋值）——
     * 拿一个 builder 方法直接喂给测试是验不出「handler 挂在列头上」的。
     */
    private ContextMenu openHeaderMenu(String column) {
        javafx.scene.Node header = page.lookup("#data-header-" + column);
        assertThat(header).as("列头 %s", column).isNotNull();
        // 处理器挂在列头容器上（Label → VBox → HBox），事件要派给真正挂 handler 的节点。
        javafx.scene.Node box = header.getParent().getParent();
        fx(() -> box.fireEvent(new javafx.scene.input.ContextMenuEvent(
                javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                20, 10, 200, 200, false, null)));
        WaitForAsyncUtils.waitForFxEvents();
        ContextMenu menu = page.headerMenuForTest();
        assertThat(menu)
                .as("右键列头要真的触发菜单处理器（拿不到 = 处理器没挂在列头上）")
                .isNotNull();
        return menu;
    }

    /** 按 id 取菜单项；取不到就抛 —— 别用可静默通过的 tryQuery。 */
    private static MenuItem item(ContextMenu menu, String id) {
        MenuItem item = menu.getItems().stream()
                .filter(candidate -> id.equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        assertThat(item).as("菜单项 %s", id).isNotNull();
        return item;
    }

    private void fireItem(ContextMenu menu, String id) {
        fx(item(menu, id)::fire);
    }

    private void hide(ContextMenu menu) {
        fx(menu::hide);
    }

    /** 在 FX 线程上点一个按钮。 */
    private void click(String id) {
        Button button = (Button) page.lookup(id);
        assertThat(button).as("按钮 %s", id).isNotNull();
        WaitForAsyncUtils.asyncFx(button::fire);
        WaitForAsyncUtils.waitForFxEvents();
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
