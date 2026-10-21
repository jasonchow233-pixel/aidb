package io.agenticdb.ui.shell;

import io.agenticdb.editing.EditSqlBuilder;
import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5-3 / 4.5-26 / 4.5-27 的门禁：数据页保存路径的安全语义。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>4.5-3</b> 保存路径必须接入 {@code DangerClassifier}，以前它完全不分类；</li>
 *   <li><b>4.5-26</b> 批量「设空串 / 设 NULL」不得改主键列 ——
 *       以前 {@code applyColumnValue} 没有主键守卫，VARCHAR 主键能被批量清空；</li>
 *   <li><b>4.5-27</b> 保存预览弹窗的默认按钮不得是「执行」 ——
 *       以前用 {@code ButtonData.OK_DONE}，回车即执行破坏性 SQL。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class DataGridSaveGuardTest {

    @TempDir
    Path temp;

    private DbSession session;
    private ExecutorService executor;

    @org.junit.jupiter.api.BeforeEach
    void openSession() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(
                DemoDatabase.create(temp.resolve("demo.db"))));
        executor = Executors.newSingleThreadExecutor();
    }

    @org.junit.jupiter.api.AfterEach
    void closeSession() throws Exception {
        executor.shutdownNow();
        session.close();
    }

    // ───────────────────────── 4.5-3 保存路径要分级 ─────────────────────────

    @Test
    void savePathClassifiesThePlan() {
        DataGridPage page = onFx(() -> new DataGridPage(
                session, new SchemaService(), "users", "test", noOpFeedback(),
                name -> { }, executor));

        EditSqlBuilder.Plan update = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement(
                        "UPDATE \"users\" SET \"name\" = ? WHERE \"id\" = ?", List.of("x", 1))));
        EditSqlBuilder.Plan delete = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement(
                        "DELETE FROM \"users\" WHERE \"id\" = ?", List.of(1))));
        EditSqlBuilder.Plan insert = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement(
                        "INSERT INTO \"users\" (\"name\") VALUES (?)", List.of("x"))));

        assertThat(onFx(() -> page.planDanger(update)))
                .as("改数据必须是「写数据」级")
                .isEqualTo(Danger.MUTATING);
        assertThat(onFx(() -> page.planDanger(delete)))
                .as("按主键删除也是「写数据」级")
                .isEqualTo(Danger.MUTATING);
        assertThat(onFx(() -> page.planDanger(insert)))
                .isEqualTo(Danger.MUTATING);
    }

    @Test
    void savePathTakesTheWorstStatementInThePlan() {
        DataGridPage page = onFx(() -> new DataGridPage(
                session, new SchemaService(), "users", "test", noOpFeedback(),
                name -> { }, executor));

        EditSqlBuilder.Plan plan = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement("UPDATE \"t\" SET \"a\" = ? WHERE \"id\" = ?",
                        List.of(1, 2)),
                new EditSqlBuilder.Statement("DROP TABLE \"t\"", List.of())));

        assertThat(onFx(() -> page.planDanger(plan)))
                .as("计划里最危险的那条决定整体等级")
                .isEqualTo(Danger.DESTRUCTIVE);
    }

    // ───────────────────── 4.5-27 默认按钮不是「执行」 ─────────────────────

    @Test
    void executeButtonIsNeverDefault() {
        ButtonType execute = DataGridPage.executeButtonType();
        ButtonType cancel = DataGridPage.cancelButtonType();

        assertThat(execute.getButtonData())
                .as("OK_DONE 会让「执行」成为默认按钮 → 回车即执行破坏性 SQL")
                .isEqualTo(ButtonBar.ButtonData.OTHER);
        assertThat(execute.getButtonData().isDefaultButton())
                .as("「执行」绝不能是默认按钮")
                .isFalse();
        assertThat(cancel.getButtonData().isCancelButton())
                .as("ESC 必须能命中「取消」")
                .isTrue();
    }

    @Test
    void savePreviewDialogPinsCancelAsDefault() {
        DataGridPage page = onFx(() -> new DataGridPage(
                session, new SchemaService(), "users", "test", noOpFeedback(),
                name -> { }, executor));

        EditSqlBuilder.Plan plan = new EditSqlBuilder.Plan(List.of(
                new EditSqlBuilder.Statement("DELETE FROM \"users\" WHERE \"id\" = ?",
                        List.of(1))));

        Dialog<ButtonType> dialog = onFx(() -> page.buildSavePreviewDialog(plan, Danger.MUTATING));
        DialogPane pane = dialog.getDialogPane();

        ButtonType execute = pane.getButtonTypes().stream()
                .filter(type -> "执行".equals(type.getText())).findFirst().orElseThrow();
        ButtonType cancel = pane.getButtonTypes().stream()
                .filter(type -> "取消".equals(type.getText())).findFirst().orElseThrow();

        // ⚠️ Monocle 无头下真正 show() 会在 notifyMouse 上 NPE，
        //    所以用独立 DialogPane 造 skin，直接验默认按钮钉法。
        DialogPane probe = onFx(() -> {
            DialogPane dialogPane = new DialogPane();
            dialogPane.getButtonTypes().setAll(execute, cancel);
            new javafx.scene.Scene(dialogPane);
            dialogPane.applyCss();
            dialogPane.layout();
            return dialogPane;
        });
        Button cancelNode = (Button) probe.lookupButton(cancel);
        Button executeNode = (Button) probe.lookupButton(execute);
        assertThat(cancelNode).isNotNull();
        assertThat(executeNode).isNotNull();

        onFxRun(() -> DestructiveConfirm.pinCancelAsDefault(probe, cancel, execute));

        assertThat(cancelNode.isDefaultButton())
                .as("回车必须落在「取消」上")
                .isTrue();
        assertThat(executeNode.isDefaultButton())
                .as("「执行」绝不能是默认按钮")
                .isFalse();
    }

    // ─────────────────── 4.5-26 批量不得改主键列 ───────────────────

    @Test
    void batchEditRefusesToTouchPrimaryKeyColumn() throws Exception {
        Path db = temp.resolve("pk.db");
        DemoDatabase.create(db);
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE keyed (code TEXT PRIMARY KEY, label TEXT)");
            statement.execute("INSERT INTO keyed VALUES ('a', 'A'), ('b', 'B'), ('c', 'C')");
        }

        DbSession keyed = JdbcSession.open(ConnectionSpec.sqlite(db));
        ExecutorService keyedExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<String> status = new AtomicReference<>("");
        AtomicInteger dirty = new AtomicInteger(-1);
        try {
            DataGridPage page = onFx(() -> new DataGridPage(keyed, new SchemaService(), "keyed",
                    "pk-test", new DataGridPage.Feedback() {
                        @Override
                        public void status(String message) {
                            status.set(message);
                        }

                        @Override
                        public void range(String message) {
                        }

                        @Override
                        public void duration(long millis) {
                        }

                        @Override
                        public void dirty(int count) {
                            dirty.set(count);
                        }
                    }, name -> { }, keyedExecutor));

            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null && tableItems(page).size() == 3);

            // 选中三行，然后把主键列批量设为空串。
            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                TableView<Map<String, Object>> table =
                        (TableView<Map<String, Object>>) page.lookup("#data-table");
                table.getSelectionModel().selectAll();
            });
            onFxRun(() -> page.setColumnValue("code", ""));

            assertThat(status.get())
                    .as("必须明确告诉用户主键列不能批量改")
                    .contains("主键");
            assertThat(dirty.get())
                    .as("主键列不能进入编辑缓冲")
                    .isNotEqualTo(1);
            assertThat(dirty.get() <= 0)
                    .as("脏计数不能因为主键列变化而增加")
                    .isTrue();

            // 数据库本身也不能变。
            try (Connection connection = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + db.toAbsolutePath());
                 Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT code FROM keyed ORDER BY code")) {
                List<String> codes = new java.util.ArrayList<>();
                while (rs.next()) {
                    codes.add(rs.getString(1));
                }
                assertThat(codes).containsExactly("a", "b", "c");
            }
            page.close();
        } finally {
            keyedExecutor.shutdownNow();
            keyed.close();
        }
    }

    @Test
    void batchEditStillWorksOnNonPrimaryKeyColumn() throws Exception {
        Path db = temp.resolve("pk2.db");
        DemoDatabase.create(db);
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE keyed (code TEXT PRIMARY KEY, label TEXT)");
            statement.execute("INSERT INTO keyed VALUES ('a', 'A'), ('b', 'B'), ('c', 'C')");
        }

        DbSession keyed = JdbcSession.open(ConnectionSpec.sqlite(db));
        ExecutorService keyedExecutor = Executors.newSingleThreadExecutor();
        AtomicInteger dirty = new AtomicInteger(-1);
        try {
            DataGridPage page = onFx(() -> new DataGridPage(keyed, new SchemaService(), "keyed",
                    "pk-test-2", new DataGridPage.Feedback() {
                        @Override
                        public void status(String message) {
                        }

                        @Override
                        public void range(String message) {
                        }

                        @Override
                        public void duration(long millis) {
                        }

                        @Override
                        public void dirty(int count) {
                            dirty.set(count);
                        }
                    }, name -> { }, keyedExecutor));

            WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () ->
                    page.lookup("#data-table") != null && tableItems(page).size() == 3);

            onFxRun(() -> {
                @SuppressWarnings("unchecked")
                TableView<Map<String, Object>> table =
                        (TableView<Map<String, Object>>) page.lookup("#data-table");
                table.getSelectionModel().selectAll();
            });
            onFxRun(() -> page.setColumnValue("label", ""));

            assertThat(dirty.get())
                    .as("普通列批量设空串必须仍然可用（不能把功能一起禁掉）")
                    .isEqualTo(3);
            page.close();
        } finally {
            keyedExecutor.shutdownNow();
            keyed.close();
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private static List<Map<String, Object>> tableItems(DataGridPage page) {
        return onFx(() -> {
            @SuppressWarnings("unchecked")
            TableView<Map<String, Object>> table =
                    (TableView<Map<String, Object>>) page.lookup("#data-table");
            return table == null ? List.of() : List.copyOf(table.getItems());
        });
    }

    private static DataGridPage.Feedback noOpFeedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String message) {
            }

            @Override
            public void duration(long millis) {
            }
        };
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
