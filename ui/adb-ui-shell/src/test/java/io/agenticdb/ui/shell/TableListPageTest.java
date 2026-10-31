package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.introspect.TableMeta;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表清单页门禁。
 *
 * <p>本轮（N1–N5）把表清单从「只能看」补成「能操作」：
 * 新建 / 删除 / 设计工具条、右键菜单、多选批量删除、列表 / 网格视图、
 * 状态栏选中项文案。这里锁住这些行为，避免以后又缩回一张只读表格。
 */
@ExtendWith(ApplicationExtension.class)
class TableListPageTest {

    private TableListPage page;
    private final List<String> opened = new ArrayList<>();
    private final List<String> designed = new ArrayList<>();
    private final List<List<String>> dropped = new ArrayList<>();
    private final List<String> aiRequests = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final List<String> selections = new ArrayList<>();
    /** 报给右侧「对象详情面板」的表名；{@code null} = 没有单一对象可看。 */
    private final List<String> inspected = new ArrayList<>();
    /** P2-1：列宽偏好用独立作用域，跑完清掉，别污染真实用户偏好。 */
    private final String layoutScopePrefix = "p21-" + System.nanoTime();

    @AfterEach
    void removeLayoutPreferences() throws Exception {
        Preferences preferences = Preferences.userNodeForPackage(SettingsStore.class);
        String prefix = "ui.tablelist." + layoutScopePrefix;
        for (String key : preferences.keys()) {
            if (key.startsWith(prefix)) {
                preferences.remove(key);
            }
        }
        preferences.flush();
    }

    private final TableListPage.Host host = new TableListPage.Host() {
        @Override
        public void newTableWithAi() {
            aiRequests.add("requested");
        }

        @Override
        public void openTable(String name) {
            opened.add(name);
        }

        @Override
        public void designTable(String name) {
            designed.add(name);
        }

        @Override
        public void dropTables(List<String> names) {
            dropped.add(List.copyOf(names));
        }

        @Override
        public void selectionChanged(String text) {
            selections.add(text);
        }

        @Override
        public void inspectTable(String name) {
            inspected.add(name);
        }
    };

    @Start
    void start(Stage stage) {
        // 构造器里的后台读取丢弃（executor 不执行），具体数据由 render(...) 直接喂，
        // 避免竞态：真实 reload 完成时会把 render 的数据覆盖掉。
        // actions 传 null → 页面用本地动作（按钮 / 右键菜单仍是同一组 Action）。
        page = new TableListPage(null, null, command -> { }, opened::add, messages::add,
                null, host, "list", null);
        page.render(List.of(
                new TableMeta("users", 200L, 3072L, "InnoDB",
                        "2026-01-01 09:00:00", "2026-02-01 10:00:00",
                        "utf8mb4_0900_ai_ci", "用户表"),
                new TableMeta("logs", 1000L, 1024L * 1024L, "InnoDB",
                        "2026-01-02 09:00:00", null, null, null),
                new TableMeta("audit", null, null, null, null, null, null, null)));
        stage.setScene(new Scene(page, 1100, 640));
        stage.show();
    }

    @Test
    void showsTheSixNavicatColumns() {
        assertThat(page.table().getColumns().stream().map(column -> column.getText()).toList())
                .containsExactly(I18n.t("column.name"), I18n.t("column.rows"),
                        I18n.t("column.dataLength"), I18n.t("column.engine"),
                        I18n.t("column.updated"), I18n.t("column.comment"));
    }

    /** S-6 / C3：详情面板挤窄表清单时，列不能被压成省略号。 */
    @Test
    void columnsKeepReadableMinimumsWhenTheListIsSqueezed() {
        TableView<TableMeta> table = page.table();
        assertThat(table.getColumnResizePolicy())
                .as("填满宽屏 + 挤窄卡在 minWidth → CONSTRAINED_FLEX_LAST "
                        + "（UNCONSTRAINED 会让宽屏留一大片空白）")
                .isSameAs(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        assertThat(table.getColumns()).allSatisfy(column -> assertThat(column.getMinWidth())
                .as("%s 列的最小宽度", column.getText())
                .isGreaterThanOrEqualTo(72.0));
        assertThat(table.getColumns().stream().mapToDouble(TableColumn::getMinWidth).sum())
                .as("六列的可读下限总和")
                .isGreaterThanOrEqualTo(600.0);
    }

    /** 当前**可见**的列标题（顺序同 getColumns()）。 */
    private List<String> visibleTitles() {
        return page.table().getColumns().stream()
                .filter(column -> column.isVisible())
                .map(column -> column.getText())
                .toList();
    }

    /**
     * ⭐ N14：方言拿不到的格显示「—」，但**列本身不消失**。
     *
     * <p>以前的「按数据整列隐藏」让 SQLite 上只剩两列，且搜索时列会跳动；
     * Navicat 的六列是固定布局 —— 空就空着，位置不挪。
     */
    @Test
    void everyColumnStaysVisibleRegardlessOfDialect(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(
                new TableMeta("users", 200L, null, null, null, null, null, null),
                new TableMeta("logs", 1000L, null, null, null, null, null, null))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles())
                .as("SQLite 只有行数 —— 其余五列仍要在，格子里读「—」")
                .containsExactly(I18n.t("column.name"), I18n.t("column.rows"),
                        I18n.t("column.dataLength"), I18n.t("column.engine"),
                        I18n.t("column.updated"), I18n.t("column.comment"));
    }

    /** 搜索只过滤行，不许动列布局（六列恒在）。 */
    @Test
    void searchDoesNotChangeColumnVisibility(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(
                new TableMeta("users", 1L, null, "InnoDB", null, null, null, null),
                new TableMeta("logs", 1L, null, null, null, null, null, null))));
        WaitForAsyncUtils.waitForFxEvents();

        robot.interact(() -> page.searchField().setText("logs"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles())
                .as("搜索只过滤行，六列一列都不许动")
                .containsExactly(I18n.t("column.name"), I18n.t("column.rows"),
                        I18n.t("column.dataLength"), I18n.t("column.engine"),
                        I18n.t("column.updated"), I18n.t("column.comment"));
    }

    // ─────────────────────── 第十九轮 S-2 卡片不摆「—」 ───────────────────────

    /** 一张卡片下所有 Label 的文本（含嵌套的标题行）。 */
    private List<String> cardLabelTextures(String cardId) {
        Node card = page.lookup(cardId);
        assertThat(card).as("卡片 " + cardId + " 应当存在").isNotNull();
        return card.lookupAll(".label").stream()
                .map(node -> ((javafx.scene.control.Label) node).getText())
                .filter(text -> text != null)
                .toList();
    }

    /**
     * SQLite 卡片不能出现纯破折号片段。
     *
     * <p>实测 SQLite 演示库上一张卡片 3 行里 2 行是「— · —」和「—」。
     */
    @Test
    void cardViewOmitsPlaceholderLinesOnSqliteLikeData(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(
                new TableMeta("users", 200L, null, null, null, null, null, null))));
        robot.interact(() -> page.showView("grid"));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> labels = cardLabelTextures("#table-card-users");

        assertThat(labels)
                .as("事实行只留有值的片段：「200 行」而不是「200 行 · —」")
                .anyMatch(text -> text.equals(I18n.t("status.rows", 200)));
        assertThat(labels)
                .as("整行都是占位符就不渲染这一行 —— 卡片里不许出现「—」")
                .noneMatch(text -> text.contains("—"));
        assertThat(labels)
                .as("引擎行 / 注释行全空 → 干脆不出现（连空占位都不留）")
                .hasSize(2); // 标题行的 name + 事实行
    }

    /** MySQL 形态：卡片照常显示引擎 / 日期 / 注释，S-2 不许误伤有值的行。 */
    @Test
    void cardViewStillShowsRealMetadataWhenItExists(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(new TableMeta("users", 200L, 3072L, "InnoDB",
                "2026-01-01 09:00:00", "2026-02-01 10:00:00",
                "utf8mb4_0900_ai_ci", "用户表"))));
        robot.interact(() -> page.showView("grid"));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> labels = cardLabelTextures("#table-card-users");

        assertThat(labels)
                .as("有值的引擎 / 修改日期 / 注释都要照常显示")
                .anyMatch(text -> text.contains("InnoDB"))
                .anyMatch(text -> text.contains("2026-02-01"))
                .anyMatch(text -> text.equals("用户表"));

        // 卡片只有 220px 宽，注释一定被截断 —— 悬停必须能读到全文。
        Label comment = page.lookupAll(".adb-table-card__comment").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .filter(label -> "用户表".equals(label.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("卡片上找不到注释行"));
        assertThat(comment.getTooltip())
                .as("卡片里的长注释也要有 tooltip")
                .isNotNull();
        assertThat(comment.getTooltip().getText()).isEqualTo("用户表");
    }

    @Test
    void searchFiltersByTableName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.table().getItems()).hasSize(3);

        robot.interact(() -> page.searchField().setText("use"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.table().getItems())
                .as("搜索框要真的过滤清单，而不是只改占位文字")
                .extracting(TableMeta::name)
                .containsExactly("users");
    }

    @Test
    void enterOpensTheSelectedTable(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            page.table().getSelectionModel().select(0);
            page.table().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
        });

        assertThat(opened).containsExactly(page.table().getItems().get(0).name());
    }

    // ─────────────────────── N1 工具条 ───────────────────────

    @Test
    void toolbarOffersNewDropAndDesignAndFollowsSelection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-new").isDisable())
                .as("新建表不依赖行选择")
                .isFalse();
        assertThat(button("#table-list-new-ai").isDisable())
                .as("「用 AI 新建表」也不依赖行选择")
                .isFalse();
        assertThat(button("#table-list-drop").isDisable())
                .as("没选行时删除必须禁用")
                .isTrue();
        assertThat(button("#table-list-design").isDisable()).isTrue();
        assertThat(button("#table-list-import").isDisable())
                .as("有表可导时导入向导可直接进（没选表时先选表）")
                .isFalse();

        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-design").isDisable()).isFalse();
        assertThat(button("#table-list-drop").isDisable()).isFalse();

        robot.interact(() -> button("#table-list-design").fire());
        assertThat(designed).containsExactly("users");
    }

    /**
     * ⭐ N15：「用 AI 新建表」不是死按钮 —— 它把请求交给宿主，
     * 生产环境里宿主打开如实说明页（能力面还没建）。
     */
    @Test
    void theAiEntryHandsOffToTheHost(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> button("#table-list-new-ai").fire());
        assertThat(aiRequests)
                .as("点「用 AI 新建表」必须真的触发宿主，而不是静默 no-op")
                .hasSize(1);
    }

    /**
     * 窄窗口下动作名不许被压成「打开…」：挤不下时该缩的是搜索框。
     *
     * <p>2026-10-25 截图对比发现：1280 宽窗口里清单工具条把五个文字按钮压到省略号，
     * 而同排「导入 / 导出」因为显式设了 {@code minWidth} 完好 —— 一眼能看出不是「设计如此」。
     *
     * <p>⚠️ 无头环境（未挂 app.css）复现不出同样的挤压，所以这里守**机制**
     * （最小宽度 = 首选宽度），渲染结果由截图核对。
     */
    @Test
    void toolbarLabelsAreNotAllowedToShrinkIntoAnEllipsis() {
        for (String id : List.of("#table-list-open", "#table-list-design",
                "#table-list-new", "#table-list-new-ai", "#table-list-drop",
                "#table-list-import", "#table-list-export")) {
            assertThat(button(id).getMinWidth())
                    .as("%s 的文字不能被压成省略号", id)
                    .isEqualTo(Region.USE_PREF_SIZE);
        }
        assertThat(page.lookup("#table-list-refresh"))
                .as("Navicat 的对象工具条没有刷新键（刷新仍在右键菜单）")
                .isNull();
    }

    // ─────────────────────── N2 右键菜单 ───────────────────────

    @Test
    void contextMenuComesFromTheSameActionSet(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        ContextMenu menu = page.contextMenuForTest();
        List<String> ids = menu.getItems().stream()
                .map(MenuItem::getId)
                .filter(java.util.Objects::nonNull)
                .toList();

        assertThat(ids)
                .as("右键菜单必须与工具条共用同一组动作")
                .containsExactly("table-list.open", "table-list.design", "table-list.new",
                        "table-list.rename", "table-list.truncate", "table-list.drop",
                        "table-list.refresh", "table-list.select-all");
    }

    // ─────────────────────── N3 多选批量删除 ───────────────────────

    @Test
    void multipleSelectionEnablesBatchDropAndKeepsSingleItemActionsOff(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().selectAll());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-open").isDisable())
                .as("多选时「打开」不知道该打开哪一张")
                .isTrue();
        assertThat(button("#table-list-drop").isDisable()).isFalse();

        robot.interact(() -> button("#table-list-drop").fire());
        assertThat(dropped).hasSize(1);
        assertThat(dropped.getFirst())
                .as("批量删除必须带上全部选中项")
                .containsExactly("users", "logs", "audit");
    }

    // ─────────────────────── N4 视图切换 ───────────────────────

    @Test
    void gridViewShowsCardsAndRemembersSelection(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.isGridView()).isFalse();

        robot.interact(() -> page.table().getSelectionModel().select(0));
        robot.interact(() -> page.showView("grid"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.isGridView()).isTrue();
        assertThat(page.lookup("#table-card-users"))
                .as("网格视图要出现卡片")
                .isNotNull();
        assertThat(page.lookup("#table-card-users").getStyleClass())
                .as("切到网格后原来的选择要保留")
                .contains("adb-table-card--selected");

        robot.interact(() -> page.showView("list"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.isGridView()).isFalse();
        assertThat(page.table().getSelectionModel().getSelectedItems())
                .extracting(TableMeta::name)
                .containsExactly("users");
    }

    @Test
    void gridViewSelectionDrivesTheStatusText(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            page.showView("grid");
            page.selectAll();
        });
        WaitForAsyncUtils.waitForFxEvents();

        // 断"信息在不在"而不是"这句话怎么拼" —— 照抄实现里的 I18n.t 组合会退化成同义反复，
        // 实现改错键它照样绿。
        assertThat(selections).last().asString()
                .as("多选要报出项目数，且不能退回空态词")
                .contains("3")
                .isNotEqualTo(I18n.t("status.noneSelected"));
    }

    // ─────────────────────── N5 状态栏文案 ───────────────────────

    @Test
    void singleSelectionReportsNameAndRowCount(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString()
                .as("单表要带表名与行数（N5）")
                .contains("users")
                .contains("200")
                .isNotEqualTo(I18n.t("status.noneSelected"));

        robot.interact(() -> {
            page.table().getSelectionModel().clearSelection();
            page.table().getSelectionModel().select(2);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString()
                .as("行数未知时不要编一个 0")
                .isEqualTo(I18n.t("tableList.selectedOne", "audit"));
    }

    // ─────────────────────── §6.3 详情面板联动 ───────────────────────

    @Test
    void selectingATableFeedsTheDetailsPanel(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inspected)
                .as("单选一张表时要把表名报给右侧详情面板（规格 §6.3）")
                .containsExactly("users");

        robot.interact(() -> page.table().getSelectionModel().selectAll());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inspected)
                .as("多选 = 没有单一对象可看，要报 null 让面板回空态")
                .containsNull();
    }

    @Test
    void theSameSelectionIsNotReportedTwice(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();
        int afterFirst = inspected.size();

        // 标签切回来 / 刷新都会重跑 updateSelectionState()。
        robot.interact(() -> page.refreshSelectionStatus());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(inspected).hasSize(afterFirst)
                .as("同一个表名反复上报 = 每次都触发一次元数据查询 + 一次面板重绘");
    }

    // ─────────────────────── 第二十一轮 UX-3 工具条可发现性 ───────────────────────

    /**
     * 清单页的「导入 / 导出」：图标 + 文字 + tooltip + accessibleText 四样齐全，
     * 并且**预选当前选中的那张表**。
     *
     * <p>预选的做法是把选中表挪到候选列表第一位 —— {@code TransferHub} 默认选第一项，
     * 所以门禁断言这个顺序就够了，不必真点（点了是模态弹窗，会卡住 FX 线程）。
     */
    @Test
    void transferEntriesAreLabeledAndPreselectTheSelectedTable(FxRobot robot) {
        for (String id : List.of("#table-list-import", "#table-list-export")) {
            Button button = button(id);
            assertThat(button.getText()).as("%s 必须带文字（图标猜不出语义）", id).isNotBlank();
            assertThat(button.getGraphic()).as("同时要有图标").isNotNull();
            assertThat(button.getTooltip()).as("带文字的按钮也要有 tooltip").isNotNull();
            assertThat(button.getTooltip().getText()).isNotBlank();
            assertThat(button.getAccessibleText()).isNotBlank();
        }

        assertThat(page.transferCandidates())
                .as("没选中时按清单原序").containsExactly("users", "logs", "audit");
        TableMeta logs = page.table().getItems().stream()
                .filter(meta -> meta.name().equals("logs"))
                .findFirst().orElseThrow();
        robot.interact(() -> page.table().getSelectionModel().select(logs));
        assertThat(page.transferCandidates())
                .as("选中 logs 后，向导的第一候选就是 logs（且只出现一次）")
                .containsExactly("logs", "users", "audit");
    }

    /** 工具条必须分堆（组间竖线），否则一排 11 颗按钮没有可读顺序。 */
    @Test
    void toolbarIsGroupedWithSeparators(FxRobot robot) {
        assertThat(page.lookupAll(".adb-toolbar__separator"))
                .as("对象动作 / 表操作 / 数据交换 / 视图 四组之间各一条竖线")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    private Button button(String selector) {
        return (Button) page.lookup(selector);
    }

    // ─────────────────────── 第二十二轮 P2-1 清单可读性 ───────────────────────

    /**
     * 列宽要按连接记住：拖宽「引擎」后重开清单仍是那个宽度；
     * 换一个连接不能继承别人的布局。
     */
    @Test
    void columnWidthsAreRememberedPerConnection() {
        SettingsStore settings = new SettingsStore();
        String scope = layoutScopePrefix + "-a";
        TableListPage first = pageWithLayout(settings, scope);

        TableColumn<?, ?> engine = first.table().getColumns().get(3);
        engine.setPrefWidth(222);
        first.persistColumnWidthsForTest();

        assertThat(settings.getInt("ui.tablelist." + scope + ".width.engine", -1))
                .as("拖完的列宽要落盘")
                .isEqualTo(222);

        TableListPage reopened = pageWithLayout(settings, scope);
        assertThat(reopened.table().getColumns().get(3).getPrefWidth())
                .as("重开同一连接的清单要恢复列宽")
                .isEqualTo(222);

        TableListPage other = pageWithLayout(settings, layoutScopePrefix + "-b");
        assertThat(other.table().getColumns().get(3).getPrefWidth())
                .as("另一个连接必须用默认列宽，不能串用")
                .isEqualTo(90);
    }

    /**
     * 注释列被列宽截断时，tooltip 必须给全文；占位符「—」不挂 tooltip。
     */
    @Test
    void longCommentsAreReachableThroughATooltip(FxRobot robot) {
        String longComment = "这是表清单里的一条长注释：列宽一定放不下，"
                + "但用户必须能从悬停提示里读到完整内容。";
        robot.interact(() -> page.render(List.of(
                new TableMeta("documented", 1L, 2048L, "InnoDB",
                        null, null, null, longComment),
                new TableMeta("blank", 1L, 2048L, "InnoDB",
                        null, null, null, null))));
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            page.applyCss();
            page.layout();
        });
        WaitForAsyncUtils.waitForFxEvents();

        TableCell<?, ?> documented = commentCell(longComment);
        assertThat(documented.getTooltip())
                .as("长注释被截断时必须给 tooltip")
                .isNotNull();
        assertThat(documented.getTooltip().getText())
                .as("tooltip 给的是全文，不是截断后的显示串")
                .isEqualTo(longComment);

        assertThat(commentCell("—").getTooltip())
                .as("「—」表示没有注释，不该再弹一个 tooltip 解释它")
                .isNull();
    }

    private TableListPage pageWithLayout(SettingsStore settings, String scope) {
        return new TableListPage(null, null, command -> { }, opened::add, messages::add,
                null, host, "list", null, settings, scope);
    }

    private TableCell<?, ?> commentCell(String text) {
        return page.table().lookupAll(".table-cell").stream()
                .filter(TableCell.class::isInstance)
                .map(node -> (TableCell<?, ?>) node)
                .filter(cell -> cell.getTableColumn() != null
                        && I18n.t("column.comment").equals(cell.getTableColumn().getText()))
                .filter(cell -> text.equals(cell.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到注释列的单元格：" + text));
    }
}
