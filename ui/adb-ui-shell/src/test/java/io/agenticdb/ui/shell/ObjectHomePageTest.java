package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import javafx.scene.Scene;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对象首页的**呈现门禁 + 对象分派门禁**。
 *
 * <p>本类补的是截图审计（{@code docs/UI-AUDIT.md} §2 / §7）暴露的两类漏洞：
 *
 * <ol>
 *   <li><b>控件被创建了、事件也绑了，但没有加入场景图</b> —— 行为测试发现不了，
 *       因为它根本不渲染；443 条门禁全绿而界面上没有那个按钮（S2）。</li>
 *   <li><b>点「函数 / 过程 / 触发器」必然报错</b> —— 对象页只把名字传给外壳，
 *       外壳无条件开数据页，于是去 SELECT 一张根本不存在的表（D1）。</li>
 * </ol>
 *
 * <p>这两类都不是"逻辑算错了"，而是"做出来的东西和用户看到的不一致"。
 * 所以这里既断言"它在界面上"，也断言"点它传出去的东西是对的"。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectHomePageTest {

    private ObjectHomePage page;
    /** 记录 onOpen 收到的东西 —— 用来断言 kind 没有被丢掉。 */
    private final List<SchemaService.SchemaObject> opened = new ArrayList<>();

    @Start
    void start(Stage stage) {
        // session 恒为 null → reload() 走「未连接」分支，不触碰任何数据库。
        page = new ObjectHomePage(() -> null, null, Runnable::run,
                opened::add, message -> { }, () -> { });
        page.reload();
        stage.setScene(new Scene(page, 900, 600));
        stage.show();
    }

    @Test
    void refreshButtonIsActuallyInTheScene() {
        assertThat(page.lookup("#object-home-refresh"))
                .as("刷新按钮必须真的挂在场景图里 —— "
                        + "它曾经只被 new 出来、绑了动作、设了 id，却从未加入 header（UI-AUDIT S2）")
                .isNotNull();
    }

    /** S-6 / C3：对象首页同样不能因为详情面板挤压把列头压没。 */
    @Test
    void columnsKeepReadableMinimumsWhenTheListIsSqueezed() {
        TableView<ObjectHomePage.ObjectRow> table = page.table();
        assertThat(table.getColumnResizePolicy())
                .as("填满宽屏 + 挤窄卡在 minWidth → CONSTRAINED_FLEX_LAST "
                        + "（UNCONSTRAINED 会让宽屏留一大片空白）")
                .isSameAs(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        assertThat(table.getColumns()).allSatisfy(column -> assertThat(column.getMinWidth())
                .as("%s 列的最小宽度", column.getText())
                .isGreaterThanOrEqualTo(72.0));
        assertThat(table.getColumns().stream().mapToDouble(TableColumn::getMinWidth).sum())
                .as("九列的可读下限总和")
                .isGreaterThanOrEqualTo(800.0);
    }

    @Test
    void disconnectionStateIsNotABlankPage() {
        // 无连接时至少要有一句可读的提示，而不是空白区域。
        assertThat(page.lookup("#object-home-summary"))
                .as("未连接时要给出可见提示")
                .isNotNull();
        // ⭐ X2：未连接空态必须带「新建连接」这个出口，而不只是陈述事实。
        assertThat(page.lookupAll(".adb-empty")).as("未连接要显示空态卡片").isNotEmpty();
    }

    @Test
    void rowPassesTheWholeObjectSoCallerCanDispatchOnKind(FxRobot robot) {
        robot.interact(() -> page.render(List.of(
                new SchemaService.SchemaObject("total_amount",
                        SchemaService.ObjectKind.FUNCTION),
                new SchemaService.SchemaObject("users",
                        SchemaService.ObjectKind.TABLE))));
        WaitForAsyncUtils.waitForFxEvents();

        AtomicReference<TableView<ObjectHomePage.ObjectRow>> list = new AtomicReference<>();
        AtomicReference<Object> row = new AtomicReference<>();
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            TableView<ObjectHomePage.ObjectRow> found =
                    (TableView<ObjectHomePage.ObjectRow>) page.lookup("#object-list");
            list.set(found);
            // ⭐ 呈现门禁：清单必须真的渲染出来了，而且每行能按 id 定位。
            //    对象首页曾经是「一堵 chip 墙」—— 现在改成带元数据的清单，
            //    类型分派语义不变（D1），但呈现方式变了，所以门禁也要跟着盯呈现。
            row.set(page.lookup("#object-row-function-total_amount"));
        });
        assertThat(list.get()).as("对象清单（TableView）要挂在场景图里").isNotNull();
        assertThat(row.get())
                .as("函数那一行要能被按 id 找到 —— 否则没法验证点它的行为")
                .isNotNull();

        // 选中函数行 + 回车 → 等于用户双击打开
        robot.interact(() -> {
            TableView<ObjectHomePage.ObjectRow> table = list.get();
            int index = -1;
            for (int i = 0; i < table.getItems().size(); i++) {
                if (table.getItems().get(i).object().kind()
                        == SchemaService.ObjectKind.FUNCTION) {
                    index = i;
                }
            }
            assertThat(index).as("清单里要有函数那一行").isNotNegative();
            table.getSelectionModel().select(index);
            table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
        });

        assertThat(opened).hasSize(1);
        // ⭐ 关键：传出去的是整个对象，kind 还在。
        //    以前只传 String name，外壳无从判断它是不是一张表（UI-AUDIT D1）。
        assertThat(opened.get(0).kind()).isEqualTo(SchemaService.ObjectKind.FUNCTION);
        assertThat(opened.get(0).name()).isEqualTo("total_amount");
    }

    @Test
    void theListShowsTableMetadataNotJustNames(FxRobot robot) {
        robot.interact(() -> page.render(
                List.of(new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE)),
                List.of(new TableMeta("users", 200L, 3 * 1024L * 1024L, "InnoDB",
                        "2026-01-01 09:00:00"))));
        WaitForAsyncUtils.waitForFxEvents();

        AtomicReference<List<String>> texts = new AtomicReference<>();
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            TableView<ObjectHomePage.ObjectRow> table =
                    (TableView<ObjectHomePage.ObjectRow>) page.lookup("#object-list");
            texts.set(table.getColumns().stream()
                    .map(column -> column.getText())
                    .toList());
        });
        // ⭐ 这是与 Navicat 的最大呈现差距：清单要能回答「这张表多大、多少人用过」，
        //    而不是只有名字。列集合就是这条契约 —— 与 Navicat 表清单的 8 列对齐
        //    （名称 / 类型 / 行 / 数据长度 / 引擎 / 创建时间 / 修改时间 / 排序规则 / 注释）。
        assertThat(texts.get())
                .containsExactly("名称", "类型", "行数", "数据长度", "引擎", "创建时间",
                        "修改时间", "排序规则", "注释");
    }

    // ─────────────────────── 第十九轮 S-1 列可见性 ───────────────────────

    /** 当前**可见**的列标题（顺序同 getColumns()）。 */
    private List<String> visibleTitles(FxRobot robot) {
        AtomicReference<List<String>> titles = new AtomicReference<>();
        robot.interact(() -> titles.set(page.table().getColumns().stream()
                .filter(column -> column.isVisible())
                .map(column -> column.getText())
                .toList()));
        return titles.get();
    }

    /**
     * SQLite 形态：表没有引擎 / 日期 / 排序规则 / 注释 / 长度，
     * 函数 / 触发器更是连元数据都没有 → 6 个可选列全是「—」→ 整列隐藏。
     *
     * <p>实测 SQLite 演示库上对象首页 9 列有 6 列是「—」墙
     * （{@code AIDB-UIUX-ANALYSIS.md} §10.3 C1）。
     */
    @Test
    void sqliteLikeObjectsHideTheAllPlaceholderColumns(FxRobot robot) {
        robot.interact(() -> page.render(
                List.of(
                        new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                        new SchemaService.SchemaObject("orders", SchemaService.ObjectKind.TABLE),
                        new SchemaService.SchemaObject("trg_audit",
                                SchemaService.ObjectKind.TRIGGER)),
                // 表只有行数；触发器连 meta 都没有（byName 查不到 → meta = null）。
                List.of(
                        new TableMeta("users", 200L, null, null, null, null, null, null),
                        new TableMeta("orders", 3L, null, null, null, null, null, null))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles(robot))
                .as("SQLite 拿不到长度/引擎/日期/排序规则/注释 → 全「—」的列整列隐藏，"
                        + "核心列「名称 / 类型 / 行数」必须留着")
                .containsExactly("名称", "类型", "行数");
    }

    /**
     * 混合场景：只要有**一行**有真值，整列就留着。
     *
     * <p>对象首页比表清单更容易踩到这个：同一个清单里有表（有引擎）
     * 也有视图 / 触发器（没有引擎）。白名单式的「这个方言没这列」会把
     * 引擎列连累藏掉；看数据的规则不会。
     */
    @Test
    void oneObjectWithRealMetadataKeepsTheColumnVisible(FxRobot robot) {
        robot.interact(() -> page.render(
                List.of(
                        new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                        new SchemaService.SchemaObject("v_users", SchemaService.ObjectKind.VIEW),
                        new SchemaService.SchemaObject("trg_audit",
                                SchemaService.ObjectKind.TRIGGER)),
                // 表有引擎 + 注释；视图 / 触发器没有 meta。
                List.of(new TableMeta("users", 200L, 3072L, "InnoDB",
                        "2026-01-01 09:00:00", "2026-02-01 10:00:00",
                        "utf8mb4_0900_ai_ci", "用户表"))));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(visibleTitles(robot))
                .as("混合场景：表有引擎/注释 → 这些列要留着；但没有任何行给出"
                        + "「创建/修改/排序规则」以外真值的列仍按数据判定")
                .containsExactly("名称", "类型", "行数", "数据长度", "引擎",
                        "创建时间", "修改时间", "排序规则", "注释");
    }

    @Test
    void densityChangesTheObjectListRowHeight(FxRobot robot) {
        robot.interact(() -> {
            page.setDensity("comfort");
            double comfort = page.table().getFixedCellSize();
            page.setDensity("compact");
            double compact = page.table().getFixedCellSize();
            assertThat(compact)
                    .as("对象清单的行高要跟密度走（紧凑 < 宽松）")
                    .isLessThan(comfort);
            assertThat(compact).isEqualTo(io.agenticdb.ui.RowHeights.of("compact"));
        });
    }

    @Test
    void onlyTablesAndViewsAreBrowsableAsRows() {
        assertThat(ObjectHomePage.unsupportedReason(SchemaService.ObjectKind.TABLE, "users"))
                .isNull();
        assertThat(ObjectHomePage.unsupportedReason(SchemaService.ObjectKind.VIEW, "v_users"))
                .isNull();

        // ⭐ 函数 / 过程：O2 之后**能看定义体**了 —— 但**仍然不能当表浏览**。
        //    「能开定义体页」与「能开数据页」是两件事，这里守住后者。
        for (SchemaService.ObjectKind kind : List.of(
                SchemaService.ObjectKind.FUNCTION,
                SchemaService.ObjectKind.PROCEDURE)) {
            assertThat(ObjectHomePage.unsupportedReason(kind, "x"))
                    .as(kind + " 不该被当成表去 SELECT（那会报「表不存在」）")
                    .isNull();
        }

        // 触发器仍然不在这条路径上（去结构页的「触发器」页签看）。
        assertThat(ObjectHomePage.unsupportedReason(SchemaService.ObjectKind.TRIGGER, "x"))
                .as("触发器要给出「去哪里看」的指引")
                .isNotNull()
                .contains("结构页");
    }

    @Test
    void emptyConnectionShowsAnEmptyStateNotJustWords(FxRobot robot) {
        AtomicReference<java.util.Set<javafx.scene.Node>> empties = new AtomicReference<>();
        robot.interact(() -> {
            page.render(List.of());
            empties.set(page.lookupAll(".adb-empty"));
        });
        assertThat(empties.get())
                .as("连上了但一个对象都没有时，也要有图标+说明+出口")
                .isNotEmpty();
    }

    // ───────────────────── 第二十二轮 P2-1：列宽记忆 + 长文本 Tooltip ─────────────────────

    /**
     * 长注释截断后 Tooltip 是拿到全文的唯一出口；短值完整显示就不该弹气泡。
     *
     * <p>与表清单页是同一套 {@link ListColumns}，但两页必须各自证明自己接上了。
     */
    @Test
    void aTruncatedCommentCarriesTheFullTextButAShortValueStaysQuiet(FxRobot robot) {
        String longComment = "这是一张存放全部历史审计记录的主表，按月分区保存，保留七年";
        robot.interact(() -> page.render(
                List.of(new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE)),
                List.of(new TableMeta("users", 200L, 1024L, "InnoDB", "2026-01-01",
                        null, null, longComment))));
        WaitForAsyncUtils.waitForFxEvents();

        TableCell<?, ?> comment = cellShowing(robot, "历史审计记录");
        assertThat(comment).as("注释那一格要真的渲染出来").isNotNull();
        assertThat(comment.getTooltip())
                .as("注释被截断时，Tooltip 是拿到全文的唯一出口")
                .isNotNull();
        assertThat(comment.getTooltip().getText()).isEqualTo(longComment);

        TableCell<?, ?> engine = cellShowing(robot, "InnoDB");
        assertThat(engine.getTooltip())
                .as("完整显示着的短值不该弹气泡 —— 扫过每一行都冒泡是噪音")
                .isNull();
    }

    /** 列宽记住：新页面拿到的宽度是本页真实的宽度，不是写死的那个。 */
    @Test
    void columnWidthsAreRememberedAcrossAReopen(FxRobot robot) throws Exception {
        SettingsStore store = new SettingsStore();

        // 本节 @Start 里那张页已经在场景中布局过 → 列宽是真实的（不是 0）。
        // ⚠️ TableColumnBase.setWidth 不是公开 API，所以不手工摆宽度，
        //    直接用布局出来的真实宽度做往返。
        robot.interact(() -> {
            page.rememberColumnsIn(store);
            page.persistColumnWidthsForTest();
        });
        double actual = page.columnTitled("注释").getWidth();
        assertThat(ListColumns.usable(actual))
                .as("拿到的列宽必须是布局出来的真实宽度（0 说明还没布局，这条会假绿）")
                .isTrue();

        ObjectHomePage second = WaitForAsyncUtils.asyncFx(() -> {
            ObjectHomePage created = new ObjectHomePage(() -> null, null, Runnable::run,
                    opened::add, message -> { }, () -> { });
            created.rememberColumnsIn(store);
            return created;
        }).get();
        assertThat(second.columnTitled("注释").getPrefWidth())
                .as("记住的宽度要还回来 —— 新页面退回写死的 160 就等于没记")
                .isEqualTo(actual);
    }

    @SuppressWarnings("unchecked")
    private TableCell<?, ?> cellShowing(FxRobot robot, String fragment) {
        AtomicReference<TableCell<?, ?>> found = new AtomicReference<>();
        robot.interact(() -> found.set(page.table().lookupAll(".table-cell").stream()
                .map(node -> (TableCell<?, ?>) node)
                .filter(cell -> cell.getText() != null && cell.getText().contains(fragment))
                .findFirst()
                .orElse(null)));
        return found.get();
    }
}
