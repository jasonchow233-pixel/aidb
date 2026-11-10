package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P3 Data Profiling 工具页的门禁：**页面接线**。
 *
 * <p>{@link ProfilingStatsTest} 钉的是统计数字对不对；这里钉的是「页面真的能用」：
 * 选表 → 跑画像 → 出表 → 摘要写着采样行数。
 *
 * <p>用<b>真实的内存 SQLite</b>（不是替身）：画像页的核心就是「发一条
 * {@code SELECT * ... LIMIT 200} 然后读回结果集」，替身没有 ResultSet，
 * 接上去等于把真正的链路绕开了 —— 那正是最该测的一段。
 */
@ExtendWith(ApplicationExtension.class)
class DataProfilingPageTest {

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void profilingATableProducesAGridAndASampledRowCount() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE metrics (id INTEGER, label TEXT, score INTEGER)");
                statement.execute("INSERT INTO metrics VALUES (1, 'a', 10)");
                statement.execute("INSERT INTO metrics VALUES (2, 'b', 20)");
                statement.execute("INSERT INTO metrics VALUES (3, NULL, NULL)");
            }

            Executor direct = Runnable::run;
            JdbcSession session = JdbcSession.wrap(connection, "SQLite · test");
            SchemaService schemaService = new SchemaService();

            DataProfilingPage page = onFx(() -> new DataProfilingPage(session, schemaService, direct));

            // 表选择器必须列出库里的表，且「跑」按钮这时是可点的。
            ComboBox<String> picker = (ComboBox<String>) onFx(() -> page.lookup("#profiling-table"));
            assertThat(picker).isNotNull();
            assertThat(onFx(picker::getItems)).contains("metrics");
            assertThat(onFx(() -> ((Button) page.lookup("#profiling-run")).isDisabled()))
                    .as("有表可画像时「跑」不许是灰的")
                    .isFalse();

            // 选中表并跑一次。
            onFxRun(() -> {
                picker.setValue("metrics");
                ((Button) page.lookup("#profiling-run")).fire();
            });

            // 结果表出现，且行数 = 列数（metrics 三列）。
            TableView<?> grid = (TableView<?>) onFx(() -> page.lookup(".adb-grid"));
            assertThat(grid).as("画像结果要真的画成表").isNotNull();
            assertThat(onFx(grid::getItems)).hasSize(3);

            // 摘要如实写采样行数。
            assertThat(onFx(() -> ((Label) page.lookup("#profiling-summary")).getText()))
                    .isEqualTo(I18n.t("tool.profiling.rows", 3));

            // 表头：两列固定（列名 + 类型）+ 八个指标。
            assertThat(onFx(grid::getColumns)).hasSize(10);
            assertThat(onFx(() -> grid.getColumns().get(0).getText()))
                    .isEqualTo(I18n.t("tool.profiling.column"));
            assertThat(onFx(() -> grid.getColumns().get(1).getText()))
                    .isEqualTo(I18n.t("tool.profiling.type"));

            session.close();
        }
    }

    @Test
    void anEmptyDatabaseSaysSoInsteadOfShowingAnEmptyGrid() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcSession session = JdbcSession.wrap(connection, "SQLite · empty");
            DataProfilingPage page = onFx(() -> new DataProfilingPage(session,
                    new SchemaService(), Runnable::run));

            assertThat(onFx(() -> page.lookup(".adb-grid")))
                    .as("没有表就不该出现一张空表")
                    .isNull();
            assertThat(onFx(() -> page.lookup("#profiling-body")))
                    .isNotNull();
            session.close();
        }
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
