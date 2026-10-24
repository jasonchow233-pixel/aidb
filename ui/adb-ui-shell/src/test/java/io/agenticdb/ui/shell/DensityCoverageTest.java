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
        // ⭐ UX-2：四档行高令牌 —— 密集 22 / 紧凑 24 / 标准 28 / 宽松 34；默认紧凑。
        assertThat(RowHeights.of("dense")).isEqualTo(22);
        assertThat(RowHeights.of("compact")).isEqualTo(24);
        assertThat(RowHeights.of("normal")).isEqualTo(28);
        assertThat(RowHeights.of("comfort")).isEqualTo(34);
        assertThat(RowHeights.of(null)).as("默认密度 = 紧凑").isEqualTo(24);
        assertThat(RowHeights.of("不存在的档位")).as("未知档位回落到默认紧凑").isEqualTo(24);
    }

    @Test
    void theTableListFollowsTheDensity(FxRobot robot) throws Exception {
        try (Fixture fixture = Fixture.open()) {
            List<Integer> heights = new ArrayList<>();
            List<Integer> expected = new ArrayList<>();
            robot.interact((Callable<Void>) () -> {
                TableListPage page = new TableListPage(fixture.session, fixture.schemaService,
                        fixture.executor, name -> { }, message -> { });
                for (String density : DENSITIES) {
                    page.setDensity(density);
                    heights.add((int) page.table().getFixedCellSize());
                    expected.add(RowHeights.of(density));
                }
                return null;
            });
            assertThat(heights)
                    .as("表清单要跟全部四档密度")
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void everyTableOfTheStructurePageFollowsTheDensity(FxRobot robot) throws Exception {
        try (Fixture fixture = Fixture.open()) {
            List<Integer> heights = new ArrayList<>();
            robot.interact((Callable<Void>) () -> {
                StructurePage page = new StructurePage(fixture.session, fixture.schemaService,
                        "t", "demo", feedback(), fixture.executor, () -> { });
                for (String density : DENSITIES) {
                    page.setDensity(density);
                    for (TableView<?> table : page.tables()) {
                        heights.add((int) table.getFixedCellSize());
                    }
                }
                return null;
            });
            List<Integer> expected = DENSITIES.stream()
                    .flatMap(density -> java.util.stream.Stream.generate(
                            () -> RowHeights.of(density)).limit(4))
                    .toList();
            assertThat(heights)
                    .as("结构页的每一张表都要跟全部四档密度")
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    void theQueryResultFollowsTheDensity(FxRobot robot) throws Exception {
        try (Fixture fixture = Fixture.open()) {
            List<Integer> heights = new ArrayList<>();
            List<Integer> expected = new ArrayList<>();
            robot.interact((Callable<Void>) () -> {
                QueryPage page = new QueryPage(fixture.session, "demo", null,
                        fixture.executor, new SettingsStore(), feedback());
                for (String density : DENSITIES) {
                    page.setDensity(density);
                    heights.add(page.resultRowHeight());
                    expected.add(RowHeights.of(density));
                }
                return null;
            });
            assertThat(heights)
                    .as("查询结果网格要跟全部四档密度")
                    .containsExactlyElementsOf(expected);
        }
    }

    /** UX-2 的四档密度，按行高从密到松。 */
    private static final List<String> DENSITIES = List.of("dense", "compact", "normal", "comfort");

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
