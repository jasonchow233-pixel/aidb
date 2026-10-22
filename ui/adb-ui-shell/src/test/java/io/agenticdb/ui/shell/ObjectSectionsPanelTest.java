package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐⭐ 对象切面门禁（§6.2）—— **本项目最重要的一道新门禁**。
 *
 * <p>对象切面（结构 / 索引 / 外键 / 触发器 / SQL）读的是**真实的内省结果**，
 * 不是写死的示例。门禁要钉住三件事：
 *
 * <ol>
 *   <li>切面读的是**数据库里真实存在的东西** —— 建了什么表，列就显示什么；
 *       断言用的是「刚建出来的表」，而不是硬编码的期望值。</li>
 *   <li>读不到时**明说读不到**，不显示空白表格让用户猜。</li>
 *   <li>SQL 切面给出的是**数据库返回的 DDL + 按方言生成的查询**。</li>
 * </ol>
 *
 * <p>为什么这道门禁重要：切面是「看起来最像静态示例」的地方 ——
 * 如果它偷偷用写死的数据，用户在一个新库上看到「7 列、主键 1」会以为
 * 数据库里真有这些东西。**造假比没有这个功能更糟。**
 */
@ExtendWith(ApplicationExtension.class)
class ObjectSectionsPanelTest {

    private Path db;
    private DbSession session;
    private ExecutorService executor;
    private SchemaService schemaService;

    @Start
    void start(Stage stage) {
        // 只建一个空壳场景 —— 面板自己加载数据。
        stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.VBox(), 900, 600));
        stage.show();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Files.createTempFile("adb-sections-", ".db");
        Files.delete(db);
        // ⭐ 用**独立的** JDBC 连接建表 —— 不能用 session.connection()：
        //    那是会话共享的连接，close 掉会把会话一起带走。
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE widgets (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      label TEXT NOT NULL,
                      weight REAL DEFAULT 0,
                      created_at TIMESTAMP
                    )""");
            statement.execute("CREATE INDEX idx_widgets_label ON widgets(label)");
            statement.execute("CREATE TRIGGER trg_widgets AFTER UPDATE ON widgets "
                    + "BEGIN SELECT 1; END");
        }
        session = JdbcSession.open(io.agenticdb.jdbc.ConnectionSpec.sqlite(db));
        executor = Executors.newSingleThreadExecutor();
        schemaService = new SchemaService();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (session != null) {
            session.close();
        }
        if (db != null) {
            Files.deleteIfExists(db);
        }
    }

    private ObjectSectionsPanel panel() {
        return new ObjectSectionsPanel(session, schemaService, "widgets", executor, () -> { });
    }

    @Test
    void theColumnsSectionShowsTheRealColumns(FxRobot robot) throws Exception {
        ObjectSectionsPanel panel = panel();
        robot.interact(() -> panel.show(ObjectSectionsPanel.SECTION_COLUMNS));
        WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                () -> panel.structure() != null || panel.lastError() != null);
        assertThat(panel.lastError()).as("读取失败原因").isNull();

        TableView<ColumnDef> table = (TableView<ColumnDef>) panel.lookup("#object-columns-table");
        assertThat(table.getItems())
                .as("列必须是刚建出来的这张表的真实列")
                .extracting(ColumnDef::name)
                .containsExactly("id", "label", "weight", "created_at");
        assertThat(table.getItems().get(1).type()).isEqualTo(ColumnType.TEXT);
        assertThat(table.getItems().get(0).primaryKey())
                .as("主键标记必须来自真实元数据")
                .isTrue();
    }

    @Test
    void theIndexesSectionShowsTheRealIndexes(FxRobot robot) throws Exception {
        ObjectSectionsPanel panel = panel();
        robot.interact(() -> panel.show(ObjectSectionsPanel.SECTION_INDEXES));
        WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                () -> panel.structure() != null || panel.lastError() != null);
        assertThat(panel.lastError()).as("读取失败原因").isNull();

        TableView<?> table = (TableView<?>) panel.lookup("#object-indexes-table");
        assertThat(table.getItems())
                .as("索引必须是数据库里真实建出来的那个")
                .extracting(item -> ((io.agenticdb.introspect.IndexDef) item).name())
                .contains("idx_widgets_label");
    }

    @Test
    void theTriggersSectionShowsTheRealTriggers(FxRobot robot) throws Exception {
        ObjectSectionsPanel panel = panel();
        robot.interact(() -> panel.show(ObjectSectionsPanel.SECTION_TRIGGERS));
        WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                () -> panel.structure() != null || panel.lastError() != null);
        assertThat(panel.lastError()).as("读取失败原因").isNull();

        TableView<?> table = (TableView<?>) panel.lookup("#object-triggers-table");
        assertThat(table.getItems())
                .extracting(item -> ((io.agenticdb.introspect.TriggerDef) item).name())
                .contains("trg_widgets");
    }

    @Test
    void theSqlSectionShowsTheDatabaseDdl(FxRobot robot) throws Exception {
        ObjectSectionsPanel panel = panel();
        robot.interact(() -> panel.show(ObjectSectionsPanel.SECTION_SQL));
        WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                () -> panel.structure() != null || panel.lastError() != null);
        assertThat(panel.lastError()).as("读取失败原因").isNull();

        TextArea sql = (TextArea) panel.lookup("#object-sql-view");
        assertThat(sql.getText())
                .as("SQL 切面必须是数据库返回的真实 DDL + 按方言生成的查询")
                .contains("CREATE TABLE widgets")
                // 标识符按方言加引号（SQLite/PG 用双引号，MySQL 用反引号）。
                .contains("SELECT * FROM \"widgets\" LIMIT 200;");
    }

    @Test
    void aMissingTableFailsLoudlyInsteadOfShowingAnEmptyTable(FxRobot robot) throws Exception {
        ObjectSectionsPanel panel = new ObjectSectionsPanel(session, schemaService,
                "no_such_table_here", executor, () -> { });
        robot.interact(() -> panel.show(ObjectSectionsPanel.SECTION_COLUMNS));
        WaitForAsyncUtils.waitFor(10, java.util.concurrent.TimeUnit.SECONDS,
                () -> panel.lastError() != null);

        assertThat(panel.structure())
                .as("读不到结构时不能伪造一个空结构出来")
                .isNull();
        assertThat(panel.lastError())
                .as("读失败必须给出可读的原因")
                .isNotBlank();
        // 失败状态必须可见 —— 不能是一张空白表格。
        assertThat(panel.lookup(".adb-grid__state"))
                .as("读失败要显示明确的错误状态")
                .isNotNull();
    }
}
