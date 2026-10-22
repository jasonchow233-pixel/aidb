package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DialectKind;
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

        ddl.dropTable("orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .containsExactly("DROP TABLE \"orders\"");
        assertThat(statuses).last().asString().contains("已完成");
        assertThat(changes.get())
                .as("删完表必须刷新对象树，否则树上还挂着一张已经不存在的表")
                .isEqualTo(1);
    }

    @Test
    void cancellingSendsNoSqlAtAll() {
        TableDdl ddl = ddl(List.of(), false);

        ddl.dropTable("orders");
        WaitForAsyncUtils.waitForFxEvents();
        ddl.truncateTable("orders");
        WaitForAsyncUtils.waitForFxEvents();
        ddl.renameTable("orders", "orders2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("点了取消就等于什么都没发生 —— 一条 SQL 都不许发出去")
                .isEmpty();
        assertThat(changes.get()).isZero();
    }

    @Test
    void truncateOnSqliteDeletesBecauseThereIsNoTruncate() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.truncateTable("orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).containsExactly("DELETE FROM \"orders\"");
    }

    @Test
    void renameToAnExistingTableNameIsRefused() {
        TableDdl ddl = ddl(List.of("users"), true);

        ddl.renameTable("orders", "users");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString().contains("已经存在");
    }

    @Test
    void renameRunsOnASqliteSession() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.renameTable("orders", "orders2");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .containsExactly("ALTER TABLE \"orders\" RENAME TO \"orders2\"");
    }

    @Test
    void withoutAConnectionNothingIsSentAndTheUserIsTold() {
        TableDdl ddl = new TableDdl(() -> null, Runnable::run, statuses::add,
                List::of, changes::incrementAndGet, () -> null, List::of);
        ddl.setConfirmation(plan -> true);

        ddl.dropTable("orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString().contains("请先连上数据库");
    }

    @Test
    void batchDropRunsEverySelectedTableAndReportsFailures() {
        TableDdl ddl = ddl(List.of("logs"), true);
        session.failOn("logs");

        ddl.dropTables(List.of("orders", "logs", "audit"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed())
                .as("能删的要继续删，不能删的要报告，而不是整批回滚")
                .containsExactly("DROP TABLE \"orders\"", "DROP TABLE \"logs\"",
                        "DROP TABLE \"audit\"");
        assertThat(statuses).last().asString()
                .contains("已删除 2/3")
                .contains("logs");
        assertThat(changes.get()).isEqualTo(1);
        assertThat(session.inTransaction())
                .as("失败的那张表也必须回滚干净")
                .isFalse();
    }

    @Test
    void cancellingABatchSendsNoSql() {
        TableDdl ddl = ddl(List.of(), false);

        ddl.dropTables(List.of("orders", "logs"));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.executed()).isEmpty();
        assertThat(statuses).last().asString().contains("已取消");
    }

    @Test
    void everyPlanGoesThroughASingleTransaction() {
        TableDdl ddl = ddl(List.of(), true);

        ddl.dropTable("orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(session.inTransaction())
                .as("执行结束后不能还挂在事务里 —— 那会把后面的查询一起锁住")
                .isFalse();
    }

    private TableDdl ddl(List<String> existingTables, boolean confirmed) {
        TableDdl ddl = new TableDdl(() -> session, Runnable::run, statuses::add,
                () -> existingTables, changes::incrementAndGet, () -> null, List::of);
        ddl.setConfirmation(plan -> {
            assertThat(plan).as("确认框拿到的一定是一个非空计划").isNotNull();
            return confirmed;
        });
        return ddl;
    }

    /** 顺带钉住：计划里的 SQL 是给人看的，不能是空字符串。 */
    @Test
    void planSqlIsNotEmpty() {
        List<StructurePlan> seen = new ArrayList<>();
        TableDdl ddl = new TableDdl(() -> session, Runnable::run, statuses::add,
                List::of, changes::incrementAndGet, () -> null, List::of);
        ddl.setConfirmation(plan -> {
            seen.add(plan);
            return false;
        });

        ddl.truncateTable("orders");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().sql()).isNotEmpty();
    }
}
