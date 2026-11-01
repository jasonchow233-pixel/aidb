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

    private final FakeDbSession session = FakeDbSession.of(DialectKind.SQLITE);
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
        assertThat(statuses).last().asString().contains("已经存在");
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
}
