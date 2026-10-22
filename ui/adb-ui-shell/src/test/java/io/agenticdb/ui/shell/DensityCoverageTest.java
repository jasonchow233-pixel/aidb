package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.ui.RowHeights;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 密度必须**全局**生效。
 *
 * <p>实测发现：密度档位以前只作用到<b>对象树</b>与<b>数据网格</b>，
 * 查询结果、表清单、结构页的表**行高纹丝不动** ——
 * 用户在数据页调到「紧凑」，切到查询结果页行高没变，
 * 于是密度设置**看起来时灵时不灵**。
 *
 * <p>这道门禁钉住四张表：数据网格 / 查询结果 / 表清单 / 结构页。
 */
@ExtendWith(ApplicationExtension.class)
class DensityCoverageTest {

    @Start
    void start(Stage stage) {
        // 只需要 JavaFX 工具箱启动，页面本身在测试里现建。
    }

    @Test
    void theRowHeightsComeFromTheTokens() {
        // ⭐ 2026-10 密度对齐 Navicat 后的行高令牌：紧凑 24 / 常规 28 / 宽松 34。
        assertThat(RowHeights.of("compact")).isEqualTo(24);
        assertThat(RowHeights.of("normal")).isEqualTo(28);
        assertThat(RowHeights.of("comfort")).isEqualTo(34);
        assertThat(RowHeights.of(null)).isEqualTo(28);
        assertThat(RowHeights.of("不存在的档位")).isEqualTo(28);
    }

    @Test
    void theTableListFollowsTheDensity(FxRobot robot) throws Exception {
        try (Fixture fixture = Fixture.open()) {
            int[] heights = new int[2];
            robot.interact((Callable<Void>) () -> {
                TableListPage page = new TableListPage(fixture.session, fixture.schemaService,
                        fixture.executor, name -> { }, message -> { });
                page.setDensity("normal");
                heights[0] = (int) page.table().getFixedCellSize();
                page.setDensity("compact");
                heights[1] = (int) page.table().getFixedCellSize();
                return null;
            });
            assertThat(heights[1])
                    .as("表清单要跟密度")
                    .isLessThan(heights[0]);
            assertThat(heights[1]).isEqualTo(RowHeights.of("compact"));
        }
    }

    @Test
    void everyTableOfTheStructurePageFollowsTheDensity(FxRobot robot) throws Exception {
        try (Fixture fixture = Fixture.open()) {
            List<Integer> heights = new ArrayList<>();
            robot.interact((Callable<Void>) () -> {
                StructurePage page = new StructurePage(fixture.session, fixture.schemaService,
                        "t", "demo", feedback(), fixture.executor, () -> { });
                page.setDensity("compact");
                for (TableView<?> table : page.tables()) {
                    heights.add((int) table.getFixedCellSize());
                }
                return null;
            });
            assertThat(heights)
                    .as("结构页的每一张表都要跟密度")
                    .hasSize(4)
                    .allMatch(height -> height == RowHeights.of("compact"));
        }
    }

    @Test
    void theQueryResultFollowsTheDensity(FxRobot robot) throws Exception {
        try (Fixture fixture = Fixture.open()) {
            int[] height = new int[1];
            robot.interact((Callable<Void>) () -> {
                QueryPage page = new QueryPage(fixture.session, "demo", null,
                        fixture.executor, new SettingsStore(), feedback());
                page.setDensity("compact");
                height[0] = page.resultRowHeight();
                return null;
            });
            assertThat(height[0])
                    .as("查询结果网格要跟密度")
                    .isEqualTo(RowHeights.of("compact"));
        }
    }

    /** 一个空反馈（页面只是被构造出来，不会真去跑查询）。 */
    private static DataGridPage.Feedback feedback() {
        return new DataGridPage.Feedback() {
            @Override
            public void status(String message) {
            }

            @Override
            public void range(String text) {
            }

            @Override
            public void duration(long millis) {
            }
        };
    }

    /** 一个内存 SQLite 库 + 会话。 */
    private static final class Fixture implements AutoCloseable {

        private final JdbcSession session;
        private final io.agenticdb.introspect.SchemaService schemaService =
                new io.agenticdb.introspect.SchemaService();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private Fixture(JdbcSession session) {
            this.session = session;
        }

        static Fixture open() throws Exception {
            // 内存库：每次跑都是干净的，不用管清理。
            JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
            try (Statement statement = session.connection().createStatement()) {
                statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
                statement.execute("INSERT INTO t (id, name) VALUES (1, 'a')");
            }
            return new Fixture(session);
        }

        @Override
        public void close() {
            executor.shutdownNow();
            try {
                session.close();
            } catch (Exception ignored) {
                // 测试夹具清理失败不影响结论。
            }
        }
    }
}
