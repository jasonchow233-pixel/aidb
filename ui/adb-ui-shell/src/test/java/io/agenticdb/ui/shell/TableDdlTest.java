package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1–T4 表级生命周期的门禁。
 *
 * <p>守的是三条最容易静默出错的规则：
 * <ol>
 *   <li><b>取消就一定不执行</b> —— 确认框那一步返回假，一条 SQL 都不许发出去。
 *       这条如果破了，就是「点了取消结果表没了」。</li>
 *   <li><b>SQL 由方言决定</b> —— SQLite 上清空表必须是 {@code DELETE FROM}，
 *       写死 {@code TRUNCATE} 在 SQLite 上会直接炸。</li>
 *   <li><b>重名要挡住</b> —— 重命名到一个已存在的表名不能发出 SQL。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class TableDdlTest {

    @Start
    void start(Stage stage) {
        stage.show();
    }

    private FakeDbSession session = FakeDbSession.of(DialectKind.SQLITE);
    private final List<String> statuses = new ArrayList<>();
    private final AtomicInteger changes = new AtomicInteger();

    @Test
    void dropTableRunsExactlyTheDialectsDropStatement() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.dropTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .containsExactly("DROP TABLE \"orders\"");
        assertThat(statuses).last().asString().endsWith(I18n.t("ddl.done", "").trim());
        assertThat(changes.get())
                .as("删完表必须刷新对象树，否则树上还挂着一张已经不存在的表")
                .isEqualTo(1);
    }

    @Test
    void cancellingSendsNoSqlAtAll() {
        TableDdl ddl = ddl(List.of(), false);

        ddl.dropTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();
        ddl.truncateTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();
        ddl.renameTable("conn", "orders", "orders2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("点了取消就等于什么都没发生 —— 一条 SQL 都不许发出去")
                .isEmpty();
        assertThat(changes.get()).isZero();
    }

    @Test
    void truncateOnSqliteDeletesBecauseThereIsNoTruncate() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.truncateTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).containsExactly("DELETE FROM \"orders\"");
    }

    @Test
    void renameToAnExistingTableNameIsRefused() {
        TableDdl ddl = ddl(List.of("users"), true);

        ddl.renameTable("conn", "orders", "users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        // ⭐ R45 G3：重名原因从「模型层写死的中文」变成「键 + 参数」，文字由渲染口按
        // 当前语言给。原来这里钉死「已经存在」，钉住的其实是 D-9 那个 bug ——
        // 英文界面上漏出中文，测试却跟着中文一起绿。现在换成键，两种语言都成立。
        assertThat(statuses).last().asString()
                .contains(I18n.t("table.error.exists", "users"))
                .doesNotContain("⚠");
    }

    @Test
    void renameRunsOnASqliteSession() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.renameTable("conn", "orders", "orders2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .containsExactly("ALTER TABLE \"orders\" RENAME TO \"orders2\"");
    }

    @Test
    void withoutAConnectionNothingIsSentAndTheUserIsTold() {
        TableDdl ddl = new TableDdl(id -> null, id -> Runnable::run, statuses::add,
                id -> List.of(), id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> true);

        ddl.dropTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString().isEqualTo(I18n.t("ddl.noSession"));
    }

    @Test
    void batchDropRunsEverySelectedTableAndReportsFailures() {
        TableDdl ddl = ddl(List.of("logs"), true);
        session.failOn("logs");

        ddl.dropTables("conn", List.of("orders", "logs", "audit"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("能删的要继续删，不能删的要报告，而不是整批回滚")
                .containsExactly("DROP TABLE \"orders\"", "DROP TABLE \"logs\"",
                        "DROP TABLE \"audit\"");
        assertThat(statuses).last().asString()
                .contains(I18n.t("ddl.droppedPartial", 2, 3, "").trim())
                .contains("logs");
        assertThat(changes.get()).isEqualTo(1);
        assertThat(session.inTransaction())
                .as("失败的那张表也必须回滚干净")
                .isFalse();
    }

    @Test
    void cancellingABatchSendsNoSql() {
        TableDdl ddl = ddl(List.of(), false);

        ddl.dropTables("conn", List.of("orders", "logs"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString().contains(I18n.t("ddl.cancelled", "").trim());
    }

    @Test
    void everyPlanGoesThroughASingleTransaction() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.dropTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.inTransaction())
                .as("执行结束后不能还挂在事务里 —— 那会把后面的查询一起锁住")
                .isFalse();
    }

    /**
     * ⭐ R45 G3-a 正向日证：结构计划的标题在<b>两种语言里都渲染成人话</b>。
     *
     * <p>为什么「键存在」不够（上一条门禁已经证明键存在了）：D-9 的症状是
     * <b>英文界面上露出中文</b>，而「键存在 + 参数对得上」推不出「值没抄错边」——
     * 把 zh 的句子抄进 en，门禁全绿，界面照样漏中文。所以这里<b>两侧各钉一次</b>：
     * en 侧必须是英文句子，zh 侧必须是中文句子，谁也不许冒充谁。
     *
     * <p>钉的是「清空表」和「重命名表」两个：一个单参数、一个双参数（箭头那条例子），
     * 参数个数不同的形状各留一份证据。
     */
    @Test
    void planTitlesRenderAsHumanTextInBothLanguages() {
        List<StructurePlan> seen = new ArrayList<>();
        TableDdl ddl = new TableDdl(id -> session, id -> Runnable::run, statuses::add,
                id -> List.of(), id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> {
            seen.add(plan);
            return false;
        });

        ddl.truncateTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();
        ddl.renameTable("conn", "orders", "orders2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(seen).hasSize(2);
        try {
            I18n.setLocale(Locale.ENGLISH);
            assertThat(StructurePlanRunner.title(seen.get(0)))
                    .as("英文构建里标题必须是英文 —— D-9 就是把中文原样带进了英文界面")
                    .isEqualTo("Empty table orders");
            assertThat(StructurePlanRunner.title(seen.get(1)))
                    .isEqualTo("Rename table orders to orders2");

            I18n.setLocale(Locale.CHINESE);
            assertThat(StructurePlanRunner.title(seen.get(0)))
                    .isEqualTo("清空表 orders");
            assertThat(StructurePlanRunner.title(seen.get(1)))
                    .as("双参数那条：箭头两侧都得上值")
                    .isEqualTo("重命名表 orders → orders2");

            assertThat(StructurePlanRunner.title(seen.get(1)))
                    .as("掉键会露出 ⚠key，掉占位符会露出字面 {0}")
                    .doesNotContain("⚠").doesNotContain("{");
        } finally {
            I18n.setLocale(I18n.DEFAULT_LOCALE);
        }
    }

    private TableDdl ddl(List<String> existingTables, boolean confirmed) {
        TableDdl ddl = new TableDdl(id -> session, id -> Runnable::run, statuses::add,
                id -> existingTables, id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> {
            assertThat(plan).as("确认框拿到的一定是一个非空计划").isNotNull();
            return confirmed;
        });
        return ddl;
    }

    /**
     * ⭐ U1：DDL 必须落在**用户点的那个连接**上。
     *
     * <p>多连接对象树 / catalog 树之后，右键的节点可能属于另一个库。
     * 以前 TableDdl 只认「当前会话」—— 在 db2 的节点上点删表，
     * 删掉的是 db1 里同名的那张（如果存在的话）。用户根本看不出来。
     */
    @Test
    void ddlOnAnotherConnectionUsesThatConnectionsSession() {
        FakeDbSession other = FakeDbSession.of(DialectKind.SQLITE);
        TableDdl ddl = new TableDdl(id -> "other".equals(id) ? other : null,
                id -> Runnable::run, statuses::add,
                id -> List.of(), id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> true);

        ddl.dropTable("other", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("当前连接的会话一条 SQL 都不许收到").isEmpty();
        assertThat(other.executed())
                .containsExactly("DROP TABLE \"orders\"");
    }

    /** ⭐ U1：执行器也按连接取 —— 同一连接的库操作才需要串行。 */
    @Test
    void ddlRunsOnTheTargetConnectionsExecutor() {
        List<String> used = new ArrayList<>();
        TableDdl ddl = new TableDdl(id -> session, id -> {
            used.add(id);
            return Runnable::run;
        }, statuses::add, id -> List.of(), id -> { }, () -> null, List::of);
        ddl.setConfirmation(plan -> true);

        ddl.dropTable("conn-b", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(used).containsExactly("conn-b");
    }

    // ─────────────────── R42 N28-a：视图级生命周期（V1–V3） ───────────────────

    /**
     * V3 删视图：SQL 由方言给，且与删表一样要确认。
     *
     * <p>钉这条是因为视图「看着像表」—— 一旦复用表那条路径，
     * 很容易在某个方言上发出 {@code DROP TABLE}（那会连数据一起没）。
     */
    @Test
    void dropViewSendsTheViewsOwnDropStatement() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.dropView("conn", "v_active_users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("SQLite 的引用符号是双引号，而且必须是 DROP VIEW")
                .containsExactly("DROP VIEW \"v_active_users\"");
        assertThat(changes.get()).isEqualTo(1);
    }

    @Test
    void cancellingAViewDropSendsNoSql() {
        TableDdl ddl = ddl(List.of(), false);

        ddl.dropView("conn", "v_active_users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString()
                .contains(I18n.t("view.plan.drop", "v_active_users"));
    }

    /**
     * V2 设计视图：读不回定义就<b>不开弹窗、更不发 SQL</b>。
     *
     * <p>守的是最坏的一种失败：把读不出形状的视图当成「空正文」打开设计器，
     * 用户点一次「更新视图」就把库里的原定义覆盖没了。
     */
    @Test
    void designViewStopsWhenTheStoredDefinitionCannotBeRead() {
        TableDdl ddl = new TableDdl(id -> session, id -> Runnable::run, statuses::add,
                id -> List.of(), (connectionId, view) -> null,
                id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> true);

        ddl.designView("conn", "v_active_users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(statuses).last().asString()
                .isEqualTo(I18n.t("newview.read.failed", "v_active_users"));
        assertThat(session.executed())
                .as("读不出形状时一条 SQL 都不许发出去")
                .isEmpty();
        assertThat(changes.get()).isZero();
    }

    /** 没选中视图（右键落在容器节点上）不能静默去操作第一个视图。 */
    @Test
    void designViewWithoutANameAsksForOne() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.designView("conn", "  ");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(statuses).last().asString().isEqualTo(I18n.t("ddl.pickView"));
        assertThat(session.executed()).isEmpty();
    }

    // ─────────────────── B3：视图重命名（V3 改名，与表重命名同构） ───────────────────

    @Test
    void sqliteViewRenameIsRefusedWithoutOpeningADialogOrExecuting() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.renameView("conn", "v_active_users");
        ddl.renameView("conn", "v_active_users", "v_active_users2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(changes.get()).isZero();
        assertThat(statuses).hasSize(2).allSatisfy(text ->
                assertThat(text).isEqualTo(I18n.t("view.error.renameUnsupported", "SQLite")));
    }

    @Test
    void postgresViewRenameUsesTheViewStatement() {
        session = FakeDbSession.of(DialectKind.POSTGRESQL);
        TableDdl ddl = ddl(List.of(), true);

        ddl.renameView("conn", "v_active_users", "v_active_users2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .containsExactly("ALTER VIEW \"v_active_users\" RENAME TO \"v_active_users2\"");
        assertThat(changes.get()).isEqualTo(1);
    }

    /** 视图重名必须被拦下，且不发任何 SQL（与表重名同一规则）。 */
    @Test
    void renameViewToAnExistingNameIsRefused() {
        TableDdl ddl = ddl(List.of("users"), true);

        ddl.renameView("conn", "v_active_users", "users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString()
                .contains(I18n.t("view.error.nameTaken", "users"))
                .doesNotContain("⚠");
    }

    /** 新名与旧名相同 = 无操作，必须拦下（与表改名同一规则）。 */
    @Test
    void renamingAViewToItselfIsRefused() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.renameView("conn", "v_active_users", "v_active_users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString()
                .contains(I18n.t("view.error.sameName"))
                .doesNotContain("⚠");
    }

    /**
     * V3 取消重命名视图：与表同一规则 —— 点了取消一条 SQL 都不许发出去。
     */
    @Test
    void cancellingAViewRenameSendsNoSql() {
        session = FakeDbSession.of(DialectKind.POSTGRESQL);
        TableDdl ddl = ddl(List.of(), false);

        ddl.renameView("conn", "v_active_users", "v_active_users2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("点了取消就等于什么都没发生 —— 一条 SQL 都不许发出去")
                .isEmpty();
    }

    /**
     * ⭐ B3：结构计划标题在两种语言里都渲染成人话（视图重命名这条）。
     */
    @Test
    void renameViewPlanTitlesRenderAsHumanTextInBothLanguages() {
        session = FakeDbSession.of(DialectKind.POSTGRESQL);
        List<StructurePlan> seen = new ArrayList<>();
        TableDdl ddl = new TableDdl(id -> session, id -> Runnable::run, statuses::add,
                id -> List.of(), id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> {
            seen.add(plan);
            return false;
        });

        ddl.renameView("conn", "v_active_users", "v_active_users2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(seen).hasSize(1);
        try {
            I18n.setLocale(Locale.ENGLISH);
            assertThat(StructurePlanRunner.title(seen.get(0)))
                    .as("英文构建里标题必须是英文")
                    .isEqualTo("Rename view v_active_users to v_active_users2");
            I18n.setLocale(Locale.CHINESE);
            assertThat(StructurePlanRunner.title(seen.get(0)))
                    .isEqualTo("重命名视图 v_active_users → v_active_users2");
            assertThat(StructurePlanRunner.title(seen.get(0)))
                    .as("掉键会露出 ⚠key，掉占位符会露出字面 {0}")
                    .doesNotContain("⚠").doesNotContain("{");
        } finally {
            I18n.setLocale(I18n.DEFAULT_LOCALE);
        }
    }

    /**
     * ⭐ B3：视图重命名弹窗同样要拦在关窗之前（D-69 范式不能只守表）。
     *
     * <p>与 {@link #theRenameDialogInterceptsBeforeClosingIt} 同一套判法，只是换成视图的
     * 字眼：弹窗 OK 按下必须先过 {@code viewNameProblem}，且不能绕开校验直发
     * {@code renameView(connectionId, view, next.trim())}。
     */
    @Test
    void theRenameViewDialogInterceptsBeforeClosingIt() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/io/agenticdb/ui/shell/TableDdl.java");
        String text = stripRenameComments(
                java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8));

        assertThat(text)
                .as("视图弹窗的 OK 按下也要先过校验拦一次（EventFilter consume = 弹窗留在原地）")
                .contains("addEventFilter(javafx.event.ActionEvent.ACTION");
        assertThat(text)
                .as("错误要就地显示在弹窗里：内容区必须有错误行")
                .contains("adb-error");
        assertThat(text)
                .as("视图弹窗关窗后的 ERROR 弹窗必须拆掉")
                .doesNotContain("new Alert(");
        assertThat(text)
                .as("视图弹窗不能绕开校验直发 renameView —— 那等于 D-69 没修")
                .doesNotContain("renameView(connectionId, view, next.trim())");
    }

    /**
     * ⭐ D-15：重命名框必须是<b>我们自建的 Dialog</b>，不许退回 {@code TextInputDialog}。
     *
     * <p>{@code showAndWait()} 在单测里按不住（它会阻塞等真人点按钮），所以这条走
     * <b>源码扫描</b> —— 和 {@code NewTableDialogTest.newTableIsADialogNotATab} 同一套判法。
     * 三条各钉一个回退面：
     * <ol>
     *   <li>源码里出现 {@code TextInputDialog} = 换回了内置皮肤；</li>
     *   <li>输入框没有 id = 验收帧/自动化再也指不到它；</li>
     *   <li>确认按钮不走资源键 = 英文界面上会露出 JavaFX 的「OK」，
     *       和同一页其它弹窗的按钮不是一套话。</li>
     * </ol>
     */
    @Test
    void theRenamePromptIsOurOwnDialogNotTheBuiltInOne() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/io/agenticdb/ui/shell/TableDdl.java");
        String text = java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(text)
                .as("换回 TextInputDialog 就等于把按钮文字交回 JavaFX（D-15 的病灶）")
                // ⚠️ 判的是<b>构造点</b>而不是类名：注释里要写「这里原来是
                // TextInputDialog」，按类名扫的门禁会把解释自己的那句话判红 ——
                // 那种门禁只会逼人删掉说明。
                .doesNotContain("new TextInputDialog");
        assertThat(text).contains("Dialog<String> dialog = new Dialog<>();");
        assertThat(text).as("输入框的 id 是帧与自动化唯一的抓手").contains("rename-table-input");
        assertThat(text).contains("I18n.t(\"ddl.rename.apply\")");
        assertThat(I18n.t("ddl.rename.apply")).doesNotContain("⚠");
    }

    /** 顺带钉住：计划里的 SQL 是给人看的，不能是空字符串。 */
    @Test
    void planSqlIsNotEmpty() {
        List<StructurePlan> seen = new ArrayList<>();
        TableDdl ddl = new TableDdl(id -> session, id -> Runnable::run, statuses::add,
                id -> List.of(), id -> changes.incrementAndGet(), () -> null, List::of);
        ddl.setConfirmation(plan -> {
            seen.add(plan);
            return false;
        });

        ddl.truncateTable("conn", "orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().sql()).isNotEmpty();
    }

    /**
     * ⭐⭐ D-69：改名的校验必须发生在**弹窗关闭之前**，错误就地显示。
     *
     * <p>旧流程是「showAndWait 收名字 → 校验 → 弹窗已关、错误另开一扇 ERROR 弹窗」：
     * 用户刚输完名字，窗口没了，错误在另一扇弹窗里，改名字要把整扇窗重开、
     * 名字重打 —— 与「结构页 4.5-12」「新建表」立的规矩相反（校验失败留在原地改一个字就能继续）。
     *
     * <p>这条走 {@link TableDdl#showRenameDialog}（**不 show**）并按住弹窗里那颗真的
     * 「重命名」按钮：{@code Button.fire()} 会派发 ACTION —— 先过 EventFilter，
     * 被 consume 就落不到 DialogPane 的「setResult + 关窗」监听上。
     * 所以断言的是按序本身（filter 摘掉 → value 被置上 = 窗会关；converter 摘掉同理），
     * 不是注释、不是形状。无头下不 show 模态窗（Monocle 会 NPE，见 D-15 注释）。
     */
    @Test
    void pressingRenameOnABadNameNeitherProducesAResultNorShowsTheErrorElsewhere()
            throws Exception {
        TableDdl ddl = new TableDdl(id -> session, id -> Runnable::run, statuses::add,
                id -> List.of("users"), id -> changes.incrementAndGet(), () -> null, List::of);

        javafx.scene.control.Dialog<String> dialog = WaitForAsyncUtils.asyncFx(() ->
                ddl.showRenameDialog("conn", session, "orders")).get();
        // 观察层：在那颗钮上记一次 ACTION。EventFilter 先于所有 handler 派发 ——
        // filter 摘掉后坏名按下也会放行到这里（DialogPane 自己的「setResult+关窗」
        // 监听同属 handler 层，会一起跑），这条计数才看得见 filter 在不在干活。
        int[] presses = {0};
        javafx.scene.Node button = dialog.getDialogPane().lookupButton(applyButtonType(dialog));
        WaitForAsyncUtils.asyncFx(() -> {
            ((javafx.scene.control.Button) button).addEventHandler(
                    javafx.event.ActionEvent.ACTION,
                    event -> presses[0]++);
            return null;
        }).get();

        // 撞名按下「重命名」：不产出结果、DialogPane 的关窗监听也不许跑、错误就地显形。
        setFieldText(dialog, "users");
        pressApply(dialog);
        assertThat(dialog.getResult())
                .as("撞名按下「重命名」不许产出结果 —— 产出了弹窗就会关闭（D-69 的病灶）")
                .isNull();
        assertThat(presses[0])
                .as("撞名按下必须被 EventFilter 咽回去：落到 handler = DialogPane 会走关窗路径")
                .isZero();
        // 错误行先按（此刻 visible=true）才指得到 —— JavaFX 的 lookup 会跳过不可见节点。
        javafx.scene.control.Label error = errorRow(dialog);
        assertThat(error.getText())
                .as("错误必须显示在弹窗内部，而不是另一扇 ERROR 弹窗里")
                .contains(I18n.t("table.error.exists", "users"));
        assertThat(error.isVisible()).as("错误行按下后必须显形").isTrue();

        // 改一个合法名再按：同一扇窗内直接通过 —— 按下放行、产出新名字、错误行收起。
        setFieldText(dialog, "orders2");
        pressApply(dialog);
        assertThat(presses[0]).as("合法名不许被拦").isEqualTo(1);
        assertThat(dialog.getResult()).isEqualTo("orders2");
        assertThat(error.getText()).as("通过校验后错误行要收起").isEmpty();
    }

    /** 在 FX 线程上按住弹窗里的「重命名」钮（fire 派发 ACTION，走真实的 filter→listener 链）。 */
    private static void pressApply(javafx.scene.control.Dialog<String> dialog) throws Exception {
        javafx.scene.Node button = dialog.getDialogPane()
                .lookupButton(applyButtonType(dialog));
        assertThat(button instanceof javafx.scene.control.Button)
                .as("lookupButton 拿到的必须就是那颗 Button")
                .isTrue();
        WaitForAsyncUtils.asyncFx(() -> {
            ((javafx.scene.control.Button) button).fire();
            return null;
        }).get();
    }

    /** 从建好的改名弹窗里取出「重命名」那颗钮的 ButtonType（按 ButtonData 找，不猜字段）。 */
    private static javafx.scene.control.ButtonType applyButtonType(
            javafx.scene.control.Dialog<String> dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == javafx.scene.control.ButtonBar
                        .ButtonData.OK_DONE)
                .findFirst().orElseThrow();
    }

    private static void setFieldText(javafx.scene.control.Dialog<String> dialog, String text)
            throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            javafx.scene.Node input = lookupRenameInput(dialog);
            ((javafx.scene.control.TextField) input).setText(text);
            return null;
        }).get();
    }

    private static javafx.scene.Node lookupRenameInput(
            javafx.scene.control.Dialog<String> dialog) {
        javafx.scene.Node input = dialog.getDialogPane().lookup("#rename-table-input");
        assertThat(input).as("改名输入框的 id 抓手（D-15）必须还指得到").isNotNull();
        return input;
    }

    private static javafx.scene.control.Label errorRow(
            javafx.scene.control.Dialog<String> dialog) throws Exception {
        javafx.scene.Node row = WaitForAsyncUtils.asyncFx(() ->
                dialog.getDialogPane().lookup("#rename-table-error")).get();
        assertThat(row)
                .as("弹窗内的错误行（id 抓手）—— 没有它错误只能去另一扇窗里说")
                .isInstanceOf(javafx.scene.control.Label.class);
        return (javafx.scene.control.Label) row;
    }

    /**
     * ⭐ D-69 的接线面：弹窗内部拦校验，**关窗之后不再另开 ERROR 弹窗**。
     *
     * <p>行为门禁钉的是 converter；「按下不关窗」的 EventFilter 与就地错误行是接线，
     * 模态框在无头环境按不住（见 D-15 注释），只能像那条一样扫源码。
     * 旧病灶（关窗后的 {@code new Alert}/{@code ddl.rename.rejected}）必须消失 ——
     * 留着它等于两条错误通道并存，用户看到哪种全看运气。
     */
    @Test
    void theRenameDialogInterceptsBeforeClosingIt() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/io/agenticdb/ui/shell/TableDdl.java");
        String text = stripRenameComments(
                java.nio.file.Files.readString(source, java.nio.charset.StandardCharsets.UTF_8));

        assertThat(text)
                .as("OK 按下必须先过校验拦一次（EventFilter consume = 弹窗留在原地）")
                .contains("addEventFilter(javafx.event.ActionEvent.ACTION");
        assertThat(text)
                .as("错误要就地显示在弹窗里：内容区必须有错误行")
                .contains("adb-error");
        assertThat(text)
                .as("关窗后的 ERROR 弹窗必须拆掉 —— 错误出现在另一扇窗里 = D-69 没修")
                .doesNotContain("new Alert(");
        assertThat(text)
                .as("回车也要过同一道拦（fire 按钮会走 EventFilter，不许另接一条直发路径）")
                .doesNotContain("renameTable(connectionId, table, next.trim())");
    }

    /** 只剥注释，不动结构（D-15 立的判据：扫的是构造点，不是解释自己的话）。 */
    private static String stripRenameComments(String source) {
        StringBuilder kept = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            int comment = line.indexOf("//");
            kept.append(comment < 0 ? line : line.substring(0, comment)).append('\n');
        }
        return kept.toString();
    }
}
