package io.agenticdb.ui.shell;

import io.agenticdb.introspect.TableMeta;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;

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
    private final List<String> messages = new ArrayList<>();
    private final List<String> selections = new ArrayList<>();
    /** 报给右侧「对象详情面板」的表名；{@code null} = 没有单一对象可看。 */
    private final List<String> inspected = new ArrayList<>();

    private final TableListPage.Host host = new TableListPage.Host() {
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
    void showsTheEightNavicatColumns() {
        assertThat(page.table().getColumns().stream().map(column -> column.getText()).toList())
                .containsExactly("名称", "行", "数据长度", "引擎",
                        "创建日期", "修改日期", "排序规则", "注释");
    }

    // ─────────────────────── 第十九轮 S-1 列可见性 ───────────────────────

    /** 当前**可见**的列标题（顺序同 getColumns()）。 */
    private List<String> visibleTitles() {
        return page.table().getColumns().stream()
                .filter(column -> column.isVisible())
                .map(column -> column.getText())
                .toList();
    }

    /**
     * MySQL 形态（每列都有真值）→ 8 列一个都不能少。
     *
     * <p>这是「不能一刀切砍列」的反向门禁：S-1 只在**全空**时才藏列。
     */
    @Test
    void mysqlLikeDataKeepsEveryColumnVisible() {
        assertThat(visibleTitles())
                .as("MySQL 连接下 8 列全有值 → 一列都不能藏")
                .containsExactly("名称", "行", "数据长度", "引擎",
                        "创建日期", "修改日期", "排序规则", "注释");
    }

    /**
     * SQLite 形态（只有行数）→ 6 个方言相关列全是「—」→ 整列隐藏，只留「名称 / 行」。
     *
     * <p>逐像素实测：SQLite 演示库 8 列有 6 列恒为「—」，占首选宽度 72%，
     * 横向滚动才看得到有内容的「名称」（{@code AIDB-UIUX-ANALYSIS.md} §10.3 C1）。
     */
    @Test
    void sqliteLikeDataHidesTheAllPlaceholderColumns(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(
                new TableMeta("users", 200L, null, null, null, null, null, null),
                new TableMeta("logs", 1000L, null, null, null, null, null, null),
                new TableMeta("audit", 0L, null, null, null, null, null, null))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles())
                .as("SQLite 拿不到引擎/日期/排序规则/注释/长度 → 全是「—」的列整列隐藏")
                .containsExactly("名称", "行");
    }

    /**
     * 混合场景：只要有一行有真值，整列就留着。
     *
     * <p>这是「看数据不看方言」比白名单稳的地方 —— 一个没填注释的表，
     * 不该连累整个注释列被藏掉。
     */
    @Test
    void oneRowWithARealValueKeepsTheColumnVisible(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(
                new TableMeta("plain", 1L, null, null, null, null, null, null),
                new TableMeta("documented", 1L, null, "InnoDB", null, null, null, "有注释"))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles())
                .as("混合场景：有行有引擎/注释 → 这两列要留着，其余全空的列仍然隐藏")
                .containsExactly("名称", "行", "引擎", "注释");
    }

    /**
     * 判定必须基于**整批已加载数据**，不是搜索后的子集。
     *
     * <p>否则用户一搜「users」（唯一有引擎的表），引擎列就会凭空出现，
     * 清空搜索又消失 —— 界面在脚下动。
     */
    @Test
    void searchDoesNotChangeColumnVisibility(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.render(List.of(
                new TableMeta("users", 1L, null, "InnoDB", null, null, null, null),
                new TableMeta("logs", 1L, null, null, null, null, null, null))));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(visibleTitles()).contains("引擎");

        robot.interact(() -> page.searchField().setText("logs"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles())
                .as("搜索只过滤行，不许动列布局")
                .containsExactly("名称", "行", "引擎");
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
     * <p>实测 SQLite 演示库上一张卡片 3 行里 2 行是「— · —」和「—」
     * （{@code AIDB-UIUX-ANALYSIS.md} §10.3 C2）。
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
                .anyMatch(text -> text.equals("200 行"));
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
        assertThat(button("#table-list-drop").isDisable())
                .as("没选行时删除必须禁用")
                .isTrue();
        assertThat(button("#table-list-design").isDisable()).isTrue();

        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(button("#table-list-design").isDisable()).isFalse();
        assertThat(button("#table-list-drop").isDisable()).isFalse();

        robot.interact(() -> button("#table-list-design").fire());
        assertThat(designed).containsExactly("users");
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

        assertThat(selections).last().asString().isEqualTo("已选 3 个项目");
    }

    // ─────────────────────── N5 状态栏文案 ───────────────────────

    @Test
    void singleSelectionReportsNameAndRowCount(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> page.table().getSelectionModel().select(0));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString()
                .as("单表要带表名与行数（N5）")
                .isEqualTo("已选 users · 200 行");

        robot.interact(() -> {
            page.table().getSelectionModel().clearSelection();
            page.table().getSelectionModel().select(2);
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(selections).last().asString()
                .as("行数未知时不要编一个 0")
                .isEqualTo("已选 audit");
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

    private Button button(String selector) {
        return (Button) page.lookup(selector);
    }
}
