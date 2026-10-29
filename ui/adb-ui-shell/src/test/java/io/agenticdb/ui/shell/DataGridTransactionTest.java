package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.editing.EditSqlBuilder;
import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ G2 的门禁：数据页的显式事务（开始 / 提交 / 回滚）。
 *
 * <p>这一轮要证的不是「按钮在不在」，而是<b>语义</b>：
 * <ol>
 *   <li>事务里写进去的东西，回滚后库里必须一行没变（G2 存在的理由）；</li>
 *   <li>提交之后必须真的落库，并且回到自动提交；</li>
 *   <li>保存路径不许再说谎 —— 事务里保存时，状态栏与预览文案都不能写「单事务执行 / 已保存」；</li>
 *   <li>结构变更与导入不许把用户没提交的事务一起提交掉或回滚掉。</li>
 * </ol>
 *
 * <p>⚠️ 保存按钮走模态确认（{@code showAndWait}），Monocle 无头下真 show 会 NPE，
 * 所以第 3 条用「不显示的构造方法 + 文案断言」验，写入效果用与保存路径同一条
 * {@link EditSqlBuilder#execute} 驱动（它才是「保存」真正落地的那一步）。
 */
@ExtendWith(ApplicationExtension.class)
class DataGridTransactionTest {

    @TempDir
    Path temp;

    private Path db;
    private DbSession session;
    private ExecutorService executor;
    /** 状态栏与事务段的最后一次播报 —— 超时时的唯一线索。 */
    private final AtomicReference<String> lastStatus = new AtomicReference<>("<无>");
    private final AtomicReference<Boolean> lastTransaction = new AtomicReference<>();

    @BeforeEach
    void openSession() throws Exception {
        db = temp.resolve("tx.db");
        DemoDatabase.create(db);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE keyed (code TEXT PRIMARY KEY, label TEXT)");
            statement.execute("INSERT INTO keyed VALUES ('a', 'A'), ('b', 'B'), ('c', 'C')");
        }
        session = JdbcSession.open(ConnectionSpec.sqlite(db));
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void closeSession() throws Exception {
        executor.shutdownNow();
        session.close();
    }

    // ─────────────────────── ① 三键的状态机 ───────────────────────

    @Test
    void beginSwapsTheStartKeyForCommitAndRollback() throws Exception {
        DataGridPage page = openPage("keyed");
        try {
            assertThat(button(page, "data-begin-tx").isDisabled())
                    .as("载入完成前不许开始事务")
                    .isFalse();
            assertThat(button(page, "data-commit-tx").isVisible())
                    .as("自动提交态下「提交」不占位")
                    .isFalse();
            assertThat(button(page, "data-rollback-tx").isManaged())
                    .as("自动提交态下「回滚」不占位")
                    .isFalse();

            fire(page, "data-begin-tx");
            await(() -> page.transactionOpen());

            assertThat(button(page, "data-begin-tx").isManaged())
                    .as("事务里「开始事务」无事可做，整颗让位给提交/回滚，不许灰着占位")
                    .isFalse();
            assertThat(button(page, "data-commit-tx").isVisible()).isTrue();
            assertThat(button(page, "data-rollback-tx").isVisible()).isTrue();
            assertThat(onFx(page::transactionOpen)).isTrue();
            assertThat(lastTransaction.get())
                    .as("状态栏那一段必须常显「事务中」—— 工具条上的键会随状态换")
                    .isTrue();
        } finally {
            close(page);
        }
    }

    @Test
    void commitClosesTheTransactionAndResumesAutocommit() throws Exception {
        DataGridPage page = openPage("keyed");
        try {
            fire(page, "data-begin-tx");
            await(page::transactionOpen);
            fire(page, "data-commit-tx");
            await(() -> !page.transactionOpen());

            assertThat(session.inTransaction())
                    .as("提交后必须回到自动提交（JdbcSession 的 commit 会恢复 autoCommit）")
                    .isFalse();
            assertThat(button(page, "data-commit-tx").isVisible())
                    .as("事务结束后提交/回滚退回不占位")
                    .isFalse();
            assertThat(button(page, "data-begin-tx").isManaged())
                    .as("事务结束后「开始事务」必须自己长回来")
                    .isTrue();
            assertThat(lastTransaction.get()).isFalse();
        } finally {
            close(page);
        }
    }

    // ─────────────── ② 回滚=0 变更 / 提交=落库（G2 的验收口径） ───────────────

    @Test
    void rollbackDiscardsEveryWriteMadeInsideTheTransaction() throws Exception {
        DataGridPage page = openPage("keyed");
        try {
            fire(page, "data-begin-tx");
            await(page::transactionOpen);

            writeThreeLabels();
            assertThat(sessionLabels())
                    .as("事务里必须读得到自己的写入（否则用户以为没写进去）")
                    .containsExactly("X", "X", "X");
            assertThat(labels())
                    .as("但另一条连接读不到 —— 写入确实还悬在事务里，没落库")
                    .containsExactly("A", "B", "C");

            onFxRun(page::rollbackTransaction);
            await(() -> !page.transactionOpen());

            assertThat(labels())
                    .as("回滚必须把三处写入全部抹掉 —— 这是 G2 存在的全部理由")
                    .containsExactly("A", "B", "C");
            assertThat(sessionLabels())
                    .as("回滚后同一连接也不能再看见那些写入")
                    .containsExactly("A", "B", "C");
        } finally {
            close(page);
        }
    }

    @Test
    void commitMakesEveryWriteInsideTheTransactionPermanent() throws Exception {
        DataGridPage page = openPage("keyed");
        try {
            fire(page, "data-begin-tx");
            await(page::transactionOpen);
            writeThreeLabels();
            fire(page, "data-commit-tx");
            await(() -> !page.transactionOpen());

            assertThat(labels())
                    .as("提交后必须真的落库")
                    .containsExactly("X", "X", "X");
        } finally {
            close(page);
        }
    }

    @Test
    void saveInsideATransactionDoesNotCommitByItself() throws Exception {
        DataGridPage page = openPage("keyed");
        try {
            fire(page, "data-begin-tx");
            await(page::transactionOpen);
            // 保存路径真正落地的那一步（EditSqlBuilder.execute）：它必须识别外层事务、不擅自提交。
            writeThreeLabels();

            assertThat(session.inTransaction())
                    .as("保存只是写进用户的事务，事务必须还开着")
                    .isTrue();
            assertThat(labels())
                    .as("事务里的「保存」绝不允许自己落库")
                    .containsExactly("A", "B", "C");
            onFxRun(page::rollbackTransaction);
            await(() -> !page.transactionOpen());
            assertThat(labels()).containsExactly("A", "B", "C");
        } finally {
            close(page);
        }
    }

    // ─────────────────── ③ 保存路径不许说谎 ───────────────────

    @Test
    void savePreviewTextChangesWhenTheWriteGoesIntoAnOpenTransaction() throws Exception {
        DataGridPage page = openPage("keyed");
        EditSqlBuilder.Plan plan = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement(
                        "UPDATE \"keyed\" SET \"label\" = ? WHERE \"code\" = ?",
                        List.of("X", "a"))));
        try {
            // 措辞按资源比，不抄中文原句 —— 默认换英文后原句就不存在了。
            // 比的是「这一档该用哪条文案」，所以两条各钉一次。
            String plain = I18n.t("grid.save.headPlain", plan.size(), Danger.MUTATING.label());
            String inTx = I18n.t("grid.save.headTx", plan.size(), Danger.MUTATING.label());
            assertThat(headerOf(page, plan))
                    .as("自动提交下才是「单事务执行 · 失败回滚」那套措辞")
                    .isEqualTo(plain);

            fire(page, "data-begin-tx");
            await(page::transactionOpen);

            assertThat(headerOf(page, plan))
                    .as("事务里保存不能谎称会自己提交 —— 提交权在用户手上")
                    .isEqualTo(inTx)
                    .isNotEqualTo(plain);
        } finally {
            close(page);
        }
    }

    @Test
    void rollbackConfirmationIsDestructiveButNotTheDefault() throws Exception {
        DataGridPage page = openPage("keyed");
        try {
            Dialog<ButtonType> dialog = onFx(page::buildRollbackDialog);
            DialogPane pane = dialog.getDialogPane();
            ButtonType rollback = pane.getButtonTypes().stream()
                    .filter(type -> I18n.t("grid.tx.rollbackButton").equals(type.getText())).findFirst().orElseThrow();
            ButtonType cancel = pane.getButtonTypes().stream()
                    .filter(type -> I18n.t("common.cancel").equals(type.getText())).findFirst().orElseThrow();

            assertThat(rollback.getButtonData().isDefaultButton())
                    .as("回车绝不能落在「回滚」上")
                    .isFalse();
            assertThat(cancel.getButtonData().isCancelButton()).isTrue();
            assertThat(((Label) pane.getHeader()).getText())
                    .as("回滚确认的正文必须就是那条资源文案")
                    .isEqualTo(I18n.t("grid.tx.rollbackHeader"));
            // 意图不能随语言丢掉：这句话必须同时点到「未提交的写入」与「未保存的编辑」。
            assertThat(I18n.t("grid.tx.rollbackHeader").toLowerCase())
                    .as("文案漏掉任何一边，这条门禁就退化成同义反复")
                    .contains("uncommitted", "unsaved");
        } finally {
            close(page);
        }
    }

    // ─────────── ④ 别的写入者不许动用户的事务（连接级作用域） ───────────

    @Test
    void structurePlanJoinsAnOpenTransactionWithoutCommittingIt() throws Exception {
        onFxRun(() -> {
            // 无按钮可点的入口：直接开事务（同 beginTransaction 的第一步）。
            try {
                session.begin();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        writeThreeLabels();

        var plan = addColumnPlan();
        executor.submit(() -> {
            StructurePlanRunner.execute(session, plan);
            return null;
        }).get(10, TimeUnit.SECONDS);

        assertThat(session.inTransaction())
                .as("结构变更不许把用户的事务提交掉")
                .isTrue();
        onFxRun(() -> {
            try {
                session.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(labels())
                .as("回滚之后，用户事务里的数据写入与结构变更必须一起消失")
                .containsExactly("A", "B", "C");
    }

    @Test
    void structurePlanStillCommitsWhenItOwnsTheTransaction() throws Exception {
        var plan = addColumnPlan();
        executor.submit(() -> {
            StructurePlanRunner.execute(session, plan);
            return null;
        }).get(10, TimeUnit.SECONDS);

        assertThat(session.inTransaction())
                .as("没有用户事务时，结构计划自己开、自己提交（不许把这条改坏）")
                .isFalse();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var rs = connection.createStatement().executeQuery("PRAGMA table_info(keyed)")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
            assertThat(columns).contains("note");
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    /** 一条低风险结构计划：加列。用于验「结构变更加入用户事务时不许自己提交」。 */
    private static io.agenticdb.introspect.StructurePlan addColumnPlan() {
        return new io.agenticdb.introspect.StructurePlan(
                "加一列", List.of("ALTER TABLE \"keyed\" ADD COLUMN \"note\" TEXT"),
                Danger.MUTATING, false, false);
    }

    /** 三处写入 —— 与「保存」走的是同一条 {@link EditSqlBuilder#execute}。 */
    private void writeThreeLabels() throws Exception {
        EditSqlBuilder.Plan plan = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement(
                        "UPDATE \"keyed\" SET \"label\" = ? WHERE \"code\" = ?", List.of("X", "a")),
                new EditSqlBuilder.Statement(
                        "UPDATE \"keyed\" SET \"label\" = ? WHERE \"code\" = ?", List.of("X", "b")),
                new EditSqlBuilder.Statement(
                        "UPDATE \"keyed\" SET \"label\" = ? WHERE \"code\" = ?", List.of("X", "c"))));
        executor.submit(() -> EditSqlBuilder.execute(session, plan)).get(10, TimeUnit.SECONDS);
    }

    /** 用<b>会话自己</b>的连接读：看见的是「事务里自己的写入 + 已落库的值」。 */
    private List<String> sessionLabels() throws Exception {
        var pageData = session.query("SELECT label FROM keyed ORDER BY code", 10);
        List<String> values = new ArrayList<>();
        for (Map<String, Object> row : pageData.rows()) {
            values.add(String.valueOf(row.get("label")));
        }
        return values;
    }

    /** 用<b>另一条</b>连接读 —— 证明看到的是库里真正落库的值，不是本会话的未提交写入。 */
    private List<String> labels() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT label FROM keyed ORDER BY code")) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }

    private String headerOf(DataGridPage page, EditSqlBuilder.Plan plan) {
        return onFx(() -> page.buildSavePreviewDialog(plan, Danger.MUTATING)
                .getDialogPane().getHeaderText());
    }

    private DataGridPage openPage(String table) throws Exception {
        AtomicReference<DataGridPage> ref = new AtomicReference<>();
        onFxRun(() -> ref.set(new DataGridPage(session, new SchemaService(), table, "tx-test",
                recordingFeedback(), name -> { }, executor)));
        DataGridPage page = ref.get();
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () ->
                page.lookup("#data-table") != null && items(page).size() == 3);
        return page;
    }

    private void close(DataGridPage page) {
        onFxRun(page::close);
    }

    private static List<Map<String, Object>> items(DataGridPage page) {
        return onFx(() -> {
            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> table =
                    (TableView<Map<String, Object>>) page.lookup("#data-table");
            return table == null ? List.of() : List.copyOf(table.getItems());
        });
    }

    /**
     * 在 FX 线程上点某颗按钮。
     *
     * <p>⚠️ 查找必须在<b>测试线程</b>完成：{@link #button} 自己要用一次 FX 往返取节点，
     * 把它塞进 {@code onFxRun} 的回调里等于在 FX 线程上等 FX 线程，那点下去的其实是
     * 一个抛异常的空任务 —— 按钮根本没被点，页面自然一直不动。
     */
    private void fire(DataGridPage page, String id) {
        Button button = button(page, id);
        onFxRun(button::fire);
    }

    private static Button button(DataGridPage page, String id) {
        return onFx(() -> (Button) page.lookup("#" + id));
    }

    private DataGridPage.Feedback recordingFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
                lastStatus.set(message);
            }

            @Override
            public void transaction(boolean inTransaction) {
                lastTransaction.set(inTransaction);
            }

            @Override
            public void range(String message) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }

    /**
     * 等某个状态在 FX 线程上成立。
     *
     * <p>不能把 {@code Platform.runLater} 塞进 {@code waitFor(...)} 的轮询回调里 ——
     * 那条回调跑在 TestFX 的后台线程上，再等 FX 事件会把两边一起堵死（首轮就是六条全超时）。
     * 这里改成测试线程自己轮询：每轮先跑空 FX 队列推进状态，再用一次 FX 往返读值
     * （往返本身就是内存屏障）。
     */
    private void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            WaitForAsyncUtils.waitForFxEvents();
            if (onFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待超时（10 秒）：条件始终没成立 · 状态栏最后一次说「"
                + lastStatus.get() + "」· 事务段最后一次说 " + lastTransaction.get());
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
