package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.TableMeta;
import io.agenticdb.jdbc.DialectKind;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

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
    /** P2-1：列宽偏好用独立作用域，跑完清掉，别污染真实用户偏好。 */
    private final String layoutScopePrefix = "p21-" + System.nanoTime();

    @Start
    void start(Stage stage) {
        // session 恒为 null → reload() 走「未连接」分支，不触碰任何数据库。
        page = new ObjectHomePage(() -> null, null, Runnable::run,
                opened::add, message -> { }, () -> { });
        page.reload();
        stage.setScene(new Scene(page, 900, 600));
        stage.show();
    }

    @AfterEach
    void removeLayoutPreferences() throws Exception {
        Preferences preferences = Preferences.userNodeForPackage(SettingsStore.class);
        String prefix = "ui.objecthome." + layoutScopePrefix;
        for (String key : preferences.keys()) {
            if (key.startsWith(prefix)) {
                preferences.remove(key);
            }
        }
        preferences.flush();
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

    // ─────────────────────── 第二十二轮 P2-1 清单可读性 ───────────────────────

    /**
     * 对象首页是常驻实例：列宽要按**当前连接**记住，并且切连接时换一套；
     * 同一连接内的重载不能把用户刚拖的宽度顶回去。
     */
    @Test
    void columnWidthsAreRememberedPerConnection() {
        SettingsStore settings = new SettingsStore();
        String scope = layoutScopePrefix + "-a";
        ObjectHomePage first = pageWithLayout(settings, () -> scope);

        TableColumn<?, ?> engine = first.table().getColumns().get(4);
        engine.setPrefWidth(222);
        first.persistColumnWidthsForTest();

        assertThat(settings.getInt("ui.objecthome." + scope + ".width.engine", -1))
                .as("拖完的列宽要落盘")
                .isEqualTo(222);

        ObjectHomePage reopened = pageWithLayout(settings, () -> scope);
        assertThat(reopened.table().getColumns().get(4).getPrefWidth())
                .as("重开同一连接的对象首页要恢复列宽")
                .isEqualTo(222);

        String otherScope = layoutScopePrefix + "-b";
        ObjectHomePage other = pageWithLayout(settings, () -> otherScope);
        assertThat(other.table().getColumns().get(4).getPrefWidth())
                .as("另一个连接必须用默认列宽，不能串用")
                .isEqualTo(90);

        // 连接切换：同一个常驻页从 A 切到 B，B 用 B 的宽，切回 A 仍是 A 的宽。
        AtomicReference<String> activeScope = new AtomicReference<>(scope);
        ObjectHomePage switching = pageWithLayout(settings, activeScope::get);
        switching.render(List.of(new SchemaService.SchemaObject("users",
                SchemaService.ObjectKind.TABLE)), List.of());
        assertThat(switching.table().getColumns().get(4).getPrefWidth())
                .as("常驻页停在 A 连接时用 A 的列宽")
                .isEqualTo(222);

        activeScope.set(otherScope);
        switching.render(List.of(new SchemaService.SchemaObject("users",
                SchemaService.ObjectKind.TABLE)), List.of());
        assertThat(switching.table().getColumns().get(4).getPrefWidth())
                .as("切到没有记忆的 B 连接要回到默认宽，不能留着 A 的 222")
                .isEqualTo(90);

        activeScope.set(scope);
        switching.render(List.of(new SchemaService.SchemaObject("users",
                SchemaService.ObjectKind.TABLE)), List.of());
        assertThat(switching.table().getColumns().get(4).getPrefWidth())
                .as("切回 A 连接要恢复 A 的列宽")
                .isEqualTo(222);
    }

    /** 注释列的 tooltip 给全文；占位符「—」不挂。 */
    @Test
    void longCommentsAreReachableThroughATooltip(FxRobot robot) {
        String longComment = "对象首页的一条长注释：列宽放不下全文，"
                + "悬停时必须能读到完整内容。";
        robot.interact(() -> {
            page.render(
                    List.of(
                            new SchemaService.SchemaObject("documented",
                                    SchemaService.ObjectKind.TABLE),
                            new SchemaService.SchemaObject("blank",
                                    SchemaService.ObjectKind.TABLE)),
                    List.of(
                            new TableMeta("documented", 1L, 2048L, "InnoDB",
                                    null, null, null, longComment),
                            new TableMeta("blank", 1L, 2048L, "InnoDB",
                                    null, null, null, null)));
            page.applyCss();
            page.layout();
            // 9 列在 900 宽下放不下，注释列可能还没生成单元格；
            // 滚到注释列即可（不要拉宽 stage —— 超出 headless 屏幕缓冲会抛
            // BufferOverflowException，而且会污染同一 JVM 里后续的用例）。
            page.table().scrollToColumn(commentColumn());
        });
        WaitForAsyncUtils.waitForFxEvents();

        TableCell<?, ?> documented = commentCell(longComment);
        assertThat(documented.getTooltip())
                .as("长注释被截断时必须给 tooltip")
                .isNotNull();
        assertThat(documented.getTooltip().getText()).isEqualTo(longComment);

        assertThat(commentCell("—").getTooltip())
                .as("「—」表示没有注释，不该再弹一个 tooltip 解释它")
                .isNull();
    }

    private ObjectHomePage pageWithLayout(SettingsStore settings,
                                          java.util.function.Supplier<String> scope) {
        return new ObjectHomePage(() -> null, null, Runnable::run,
                opened::add, message -> { }, () -> { }, null, settings, scope);
    }

    // ─────────────────────── 第二十三轮 UX-6 列表 / 网格 ───────────────────────

    /**
     * 对象首页也要有列表 / 网格两种看法，且两种视图共用同一份选择。
     *
     * <p>网格不是「另一张只读页面」：切回列表时选中的对象必须还在，
     * 否则用户刚选好一张表、切一下视图，工具条的「打开 / 删除」就灰了。
     */
    @Test
    void gridViewShowsCardsAndKeepsTheSelectionInSync(FxRobot robot) {
        robot.interact(() -> page.render(
                List.of(
                        new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                        new SchemaService.SchemaObject("orders", SchemaService.ObjectKind.TABLE),
                        new SchemaService.SchemaObject("trg_audit",
                                SchemaService.ObjectKind.TRIGGER)),
                List.of(
                        new TableMeta("users", 200L, 3072L, "InnoDB", null),
                        new TableMeta("orders", 3L, 1024L, "InnoDB", null))));

        // 列表视图里选中 users，再切网格 —— 卡片必须带着选中态出现。
        robot.interact(() -> {
            TableView<ObjectHomePage.ObjectRow> table = page.table();
            for (int i = 0; i < table.getItems().size(); i++) {
                if ("users".equals(table.getItems().get(i).object().name())) {
                    table.getSelectionModel().select(i);
                }
            }
            ((javafx.scene.control.Button) page.lookup("#object-home-view-grid")).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page.isGridView()).as("点网格按钮后要切到卡片视图").isTrue();
        assertThat(page.table().isVisible()).as("网格视图下表格要隐藏").isFalse();
        javafx.scene.Node usersCard = page.lookup("#object-card-table-users");
        assertThat(usersCard).as("对象卡片要真的挂在场景图里").isNotNull();
        assertThat(usersCard.getStyleClass())
                .as("从列表切过来时 users 的选中态要带进卡片")
                .contains("adb-table-card--selected");

        // 在网格里改选 orders —— 工具条的选择也要跟着换。
        robot.clickOn(page.lookup("#object-card-table-orders"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.lookup("#object-card-table-orders").getStyleClass())
                .contains("adb-table-card--selected");
        assertThat(page.lookup("#object-card-table-users").getStyleClass())
                .doesNotContain("adb-table-card--selected");

        // 切回列表：表格里选中的应当还是 orders，而不是空选。
        robot.interact(() ->
                ((javafx.scene.control.Button) page.lookup("#object-home-view-list")).fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(page.isGridView()).isFalse();
        assertThat(page.table().isVisible()).isTrue();
        assertThat(page.table().getSelectionModel().getSelectedItems())
                .extracting(row -> row.object().name())
                .containsExactly("orders");
    }

    /** 视图偏好要跨会话记住；切走时立即落盘。 */
    @Test
    void theChosenViewIsRememberedAcrossSessions(FxRobot robot) throws Exception {
        SettingsStore settings = new SettingsStore();
        Preferences preferences = Preferences.userNodeForPackage(SettingsStore.class);
        String key = "ui.objectHome.view";
        String original = preferences.get(key, null);
        try {
            settings.set(key, "grid");
            AtomicReference<ObjectHomePage> ref = new AtomicReference<>();
            robot.interact(() -> {
                ObjectHomePage restored =
                        pageWithLayout(settings, () -> layoutScopePrefix + "-view");
                // 不在场景里的节点 lookup 拿不到 CSS 树；挂一个 Scene 再断言。
                new Scene(restored, 900, 600);
                restored.render(List.of(new SchemaService.SchemaObject("users",
                        SchemaService.ObjectKind.TABLE)), List.of());
                // ScrollPane 的视口由 skin 创建；不跑一次 CSS/布局，
                // lookup 走不到卡片（这不是产品缺陷，只是测试没让皮肤上桌）。
                restored.applyCss();
                restored.layout();
                ref.set(restored);
            });
            ObjectHomePage restored = ref.get();
            assertThat(restored.isGridView())
                    .as("上次选了网格，重开对象首页就该直接是网格")
                    .isTrue();
            assertThat(restored.lookup("#object-card-table-users"))
                    .as("恢复网格后也要真的渲染出卡片")
                    .isNotNull();

            robot.interact(() -> restored.showView("list"));
            assertThat(settings.get(key, "list"))
                    .as("切回列表要立刻记住，不能等退出应用")
                    .isEqualTo("list");
        } finally {
            if (original == null) {
                preferences.remove(key);
            } else {
                preferences.put(key, original);
            }
            preferences.flush();
        }
    }

    /**
     * ⭐ U16：页头说的是「我在哪个库」，不是「这是什么页」。
     *
     * <p>tab 上已经写着「对象」，页内再来一个 16px 粗体「对象」是同一句话读两遍；
     * 而用户真正要在进页面前确认的「连的是哪个库、什么方言」原本哪儿都没有。
     */
    @Test
    void theHeaderNamesTheContextNotTheTab() {
        Label disconnected = (Label) page.lookup("#object-home-breadcrumb");
        assertThat(disconnected)
                .as("页头必须真的在场景图里（不是 new 出来没挂上去）")
                .isNotNull();
        assertThat(disconnected.getText())
                .as("没连接就直说，不编一个库名")
                .isEqualTo("未连接");
        assertThat(page.lookup(".adb-page__title"))
                .as("对象首页不再有页内 H1 —— 它和 tab 名重复")
                .isNull();

        // 连上之后显示的是会话自己的标识与方言，全部来自真实对象，不是写死的文案。
        ObjectHomePage connected = new ObjectHomePage(
                () -> FakeDbSession.of(DialectKind.SQLITE), null, Runnable::run,
                opened::add, message -> { }, () -> { });
        Label crumb = (Label) connected.lookup("#object-home-breadcrumb");
        assertThat(crumb.getText())
                .as("方言 + 连接标识：取自会话本身；label 已带方言前缀就不许再拼一遍「› SQLite」")
                .isEqualTo("SQLite · fake-db");

        // p0-shell 第二拍抓到的：文件型会话的 label 带整条绝对路径，页头只留文件名。
        FakeDbSession file = FakeDbSession.of(DialectKind.SQLITE)
                .withLabel("SQLite · C:\\Users\\me\\.agenticdb\\agenticdb-demo.db");
        ObjectHomePage filePath = new ObjectHomePage(
                () -> file, null, Runnable::run, opened::add, message -> { }, () -> { });
        Label shortCrumb = (Label) filePath.lookup("#object-home-breadcrumb");
        assertThat(shortCrumb.getText())
                .as("页头不是资源管理器 —— 长路径截成文件名，前缀方言保留")
                .isEqualTo("SQLite · agenticdb-demo.db");
        assertThat(shortCrumb.getTooltip())
                .as("全路径不能丢，进 tooltip")
                .isNotNull()
                .extracting(javafx.scene.control.Tooltip::getText)
                .isEqualTo("SQLite · C:\\Users\\me\\.agenticdb\\agenticdb-demo.db");
    }

    /**
     * ⭐ U19：禁用按钮要交代「为什么点不动」，而不只是变灰。
     *
     * <p>开场四颗按钮全灰，第一眼像界面坏了。灰是对的，缺的是理由；
     * 理由必须**跟着当前状态变**（未连接 vs 没选中），否则用户照着做也点不亮。
     */
    @Test
    void disabledToolbarButtonsSayWhyTheyAreDisabled() {
        // 未连接的页：所有按钮的原因都是「先连上库」。
        Button open = (Button) page.lookup("#object-home-open");
        assertThat(open.isDisabled()).isTrue();
        assertThat(open.getTooltip().getText())
                .as("禁用态的 tooltip 必须是原因，不是按钮名")
                .isEqualTo("连上一个库才能打开");

        Button create = (Button) page.lookup("#object-home-new");
        assertThat(create.isDisabled()).isTrue();
        assertThat(create.getTooltip().getText()).isEqualTo("连上一个库才能建表");

        // 已连接但没选中：原因换成下一步真正要做的事。
        ObjectHomePage connected = new ObjectHomePage(
                () -> FakeDbSession.of(DialectKind.SQLITE), null, Runnable::run,
                opened::add, message -> { }, () -> { });
        Button connectedOpen = (Button) connected.lookup("#object-home-open");
        assertThat(connectedOpen.isDisabled()).isTrue();
        assertThat(connectedOpen.getTooltip().getText()).isEqualTo("先在清单里选中一个对象");
        // 「新建表」只依赖连接，连上了就该可点 —— tooltip 也要跟着回到按钮本身的说明。
        Button connectedCreate = (Button) connected.lookup("#object-home-new");
        assertThat(connectedCreate.isDisabled()).isFalse();
        assertThat(connectedCreate.getTooltip().getText())
                .as("恢复可用后不能把禁用原因留在 tooltip 上误导人")
                .isEqualTo("新建一张表（弹窗填字段）");
    }

    /**
     * ⭐ U25：右键菜单与工具条共用一套权限判断，两种视图都挂了入口。
     *
     * <p>问题：对象首页此前根本没有右键 —— DBA 想「复制这个表名」得先双击进
     * 数据页再从别处抄。菜单不是新功能，是把工具条的四个动作原样递到鼠标右边；
     * 所以门禁盯的是**同一个 kind 在两边给同一个答复**。
     */
    @Test
    void objectMenuMirrorsTheToolbarPermissions(FxRobot robot) {
        ObjectHomePage connected = new ObjectHomePage(
                () -> FakeDbSession.of(DialectKind.SQLITE), null, Runnable::run,
                opened::add, message -> { }, () -> { });

        AtomicReference<ContextMenu> tableMenu = new AtomicReference<>();
        robot.interact(() -> tableMenu.set(connected.objectMenu(
                new ObjectHomePage.ObjectRow(new SchemaService.SchemaObject(
                        "users", SchemaService.ObjectKind.TABLE), null))));
        assertThat(menuLabels(tableMenu.get()))
                .as("菜单条目 = 工具条动作，术语一字不差")
                .containsExactly("打开", "设计表", "复制名称", "删除表");
        assertThat(disabledMenuIds(tableMenu.get())).as("连着库点表：全都能点").isEmpty();

        AtomicReference<ContextMenu> viewMenu = new AtomicReference<>();
        robot.interact(() -> viewMenu.set(connected.objectMenu(
                new ObjectHomePage.ObjectRow(new SchemaService.SchemaObject(
                        "v_active", SchemaService.ObjectKind.VIEW), null))));
        assertThat(disabledMenuIds(viewMenu.get()))
                .as("视图能设计、不能被这里的删除干掉（宿主只会删表）")
                .containsExactly("object-menu-drop");

        // 未连接的页（setup 里的 page）：三个数据库动作全灰，只有复制名称可用。
        AtomicReference<ContextMenu> offMenu = new AtomicReference<>();
        robot.interact(() -> offMenu.set(page.objectMenu(
                new ObjectHomePage.ObjectRow(new SchemaService.SchemaObject(
                        "users", SchemaService.ObjectKind.TABLE), null))));
        assertThat(disabledMenuIds(offMenu.get()))
                .as("没连库时禁用的是动作，不是整个菜单")
                .contains("object-menu-open", "object-menu-design", "object-menu-drop")
                .doesNotContain("object-menu-copy-name");
    }

    /** U25：清单行与网格卡片都要真的挂上右键入口（挂一行漏一行 = 两种视图两套真相）。 */
    @Test
    void rowsAndCardsBothCarryTheContextMenuEntry(FxRobot robot) {
        robot.interact(() -> page.render(List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));
        WaitForAsyncUtils.waitForFxEvents();

        javafx.scene.Node row = page.lookup("#object-row-table-users");
        assertThat(row).as("表行要能按 id 定位").isNotNull();
        assertThat(row.getOnContextMenuRequested())
                .as("列表视图的行必须挂右键处理器")
                .isNotNull();

        robot.interact(() -> page.showView("grid"));
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.Node card = page.lookup("#object-card-table-users");
        assertThat(card).as("网格视图的卡片要能按 id 定位").isNotNull();
        assertThat(card.getOnContextMenuRequested())
                .as("卡片必须有同一个入口 —— 右键不该只活在表格视图里")
                .isNotNull();
    }

    private static List<String> menuLabels(ContextMenu menu) {
        List<String> labels = new ArrayList<>();
        for (var item : menu.getItems()) {
            // ⚠️ SeparatorMenuItem 也是 MenuItem（text 恒为 null）—— 数条目必须先跳过它。
            if (item instanceof javafx.scene.control.MenuItem menuItem
                    && !(item instanceof javafx.scene.control.SeparatorMenuItem)) {
                labels.add(menuItem.getText());
            }
        }
        return labels;
    }

    private static List<String> disabledMenuIds(ContextMenu menu) {
        List<String> ids = new ArrayList<>();
        for (var item : menu.getItems()) {
            if (item instanceof javafx.scene.control.MenuItem menuItem
                    && !(item instanceof javafx.scene.control.SeparatorMenuItem)
                    && menuItem.isDisable()) {
                ids.add(menuItem.getId());
            }
        }
        return ids;
    }

    private TableColumn<ObjectHomePage.ObjectRow, ?> commentColumn() {
        return page.table().getColumns().stream()
                .filter(column -> "注释".equals(column.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("对象首页没有注释列"));
    }

    private TableCell<?, ?> commentCell(String text) {
        return page.table().lookupAll(".table-cell").stream()
                .filter(TableCell.class::isInstance)
                .map(node -> (TableCell<?, ?>) node)
                .filter(cell -> cell.getTableColumn() != null
                        && "注释".equals(cell.getTableColumn().getText()))
                .filter(cell -> text.equals(cell.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到注释列的单元格：" + text));
    }
}
