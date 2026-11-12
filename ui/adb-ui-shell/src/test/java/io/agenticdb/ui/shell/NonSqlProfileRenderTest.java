package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ EXT：非 SQL 档案（Redis / 对象存储）在**清单页与标签栏**里的存活门禁。
 *
 * <p>这一类是截图工作逼出来的：注册一条 Redis 档案后打开任意标签页，整个应用
 * 抛 {@code IllegalStateException: Redis is not a SQL asset and has no DialectKind} 退场。
 * 根因是 UI 里有三处把「档案的 kind」当 SQL 方言用 ——
 * 非 SQL 成员没有方言，那三处一执行就抛。
 *
 * <p>三条门禁各盯一处：
 * <ol>
 *   <li>连接清单页把 Redis / 对象存储两行**真的渲染出来**（端口列的取值也要对）；</li>
 *   <li>标签栏给一条 Redis 连接开标签，标题照常拼（不抛）；</li>
 *   <li>源码审计：UI 层不许再出现**没有 isSql() 兜着的** {@code kind().dialect()}。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class NonSqlProfileRenderTest {

    private ObservableList<ConnectionProfile> profiles;
    private ConnectionsPage page;

    @Start
    void start(Stage stage) {
        profiles = FXCollections.observableArrayList(
                redisProfile("Redis cache", "127.0.0.1", 6379, "0"),
                objectStoreProfile("Object storage", "s3", "us-east-1"));
        page = new ConnectionsPage(profiles,
                () -> { },
                profile -> { },
                profile -> { },
                profile -> { },
                profiles::remove,
                profile -> false,
                new DataGridPage.Feedback() {
                    @Override
                    public void status(String message) {
                    }

                    @Override
                    public void range(String text) {
                    }

                    @Override
                    public void duration(long millis) {
                    }
                });
        stage.setScene(new javafx.scene.Scene(page, 900, 600));
        stage.show();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static ConnectionProfile redisProfile(String name, String host, int port, String db) {
        return new ConnectionProfile("redis-1", name, AssetKind.REDIS, host, port, db, "",
                false, 0, "", Map.of(), SshConfig.NONE);
    }

    private static ConnectionProfile objectStoreProfile(String name, String provider, String region) {
        return new ConnectionProfile("oss-1", name, AssetKind.OBJECT_STORE, "", 0, "", "",
                false, 0, "", Map.of("provider", provider, "region", region), SshConfig.NONE);
    }

    /**
     * ⭐ 崩溃点之一：连接清单页的端口列以前直接问方言「你走不走文件路径」。
     * 非 SQL 成员没有方言 —— 这一格一渲染就抛，整页打不开。
     */
    @Test
    void theConnectionsListRendersRedisAndObjectStoreRows(FxRobot robot) {
        TableView<ConnectionProfile> table = robot.lookup("#connections-table")
                .queryAs(TableView.class);
        assertThat(table.getItems())
                .as("两条非 SQL 档案都得在列表里")
                .hasSize(2);

        TableColumn<ConnectionProfile, String> port = portColumn(table);
        assertThat(cellTextOf(table, port, table.getItems().get(0)))
                .as("Redis 有真端口，要显示出来")
                .isEqualTo("6379");
        assertThat(cellTextOf(table, port, table.getItems().get(1)))
                .as("对象存储走 endpoint，没有端口概念 —— 显示「—」，不是 0")
                .isEqualTo("—");
    }

    /**
     * ⭐ 崩溃点之二：标签栏给一条 Redis 连接开标签时，标题要拼上连接名 ——
     * 拼的过程中会问「这一族要不要切库重连」，非 SQL 族的答案必须是「不要」，
     * 而不是抛异常。
     */
    @Test
    void openingATabForARedisConnectionDoesNotThrow() {
        TabBar bar = new TabBar(tabBarActions());
        bar.setConnectionLabels(id -> "Redis cache");
        bar.setConnectionCatalogs(id -> AssetKind.REDIS.switchesCatalogByReconnect() ? "0" : "");
        bar.setConnectionKinds(id -> AssetKind.REDIS);

        bar.open(new TabItem("redis-keys:redis-1:0", "Redis Keys",
                TabKind.REDIS_KEYS, "0", "redis-1"));

        assertThat(bar.openTabs()).hasSize(1);
        assertThat(bar.openTabs().get(0).kind()).isEqualTo(TabKind.REDIS_KEYS);
    }

    /** 标签栏建标签时要挂的那几个动作（这一格只关心标题拼接，不关心动作行为）。 */
    private static ActionRegistry tabBarActions() {
        ActionRegistry actions = new ActionRegistry();
        for (String id : List.of("tab.close", "tab.pin", "tab.close-others", "tab.close-all",
                "tab.copy-name", "tab.copy-select", "new-query")) {
            actions.register(id, id, "window", null, () -> { });
        }
        return actions;
    }

    /**
     * ⭐ 源码审计：UI 层不许出现**没有 isSql() 兜着的** {@code kind().dialect()} /
     * {@code kind().dialectKind()}。
     *
     * <p>规则很简单：出现这两个调用的那一行，或它前面两行里，必须有 {@code isSql()}。
     * 非 SQL 成员没有方言，UI 直取方言就是一次崩溃的引信。
     */
    @Test
    void theUiLayerNeverCallsADialectOnANonSqlKind() throws IOException {
        Path uiRoot = repositoryRoot().resolve("ui/adb-ui-shell/src/main/java");
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(uiRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.toString().endsWith(".java") || path.toString().contains("/target/")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(path);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains(".kind().dialect(") && !line.contains(".kind().dialectKind(")) {
                        continue;
                    }
                    boolean guarded = line.contains("isSql()");
                    for (int back = 1; back <= 2 && !guarded; back++) {
                        int idx = i - back;
                        if (idx >= 0 && lines.get(idx).contains("isSql()")) {
                            guarded = true;
                        }
                    }
                    if (!guarded) {
                        violations.add(uiRoot.relativize(path) + ":" + (i + 1) + " — " + line.trim());
                    }
                }
            }
        }
        assertThat(violations)
                .as("非 SQL 档案没有方言；UI 直取 kind().dialect() 会在有 Redis / 对象存储"
                        + "连接时抛 IllegalStateException")
                .isEmpty();
    }

    private static TableColumn<ConnectionProfile, String> portColumn(
            TableView<ConnectionProfile> table) {
        @SuppressWarnings("unchecked")
        TableColumn<ConnectionProfile, String> port = table.getColumns().stream()
                .filter(column -> "connections-column-port".equals(column.getId()))
                .map(column -> (TableColumn<ConnectionProfile, String>) column)
                .findFirst().orElseThrow();
        return port;
    }

    /**
     * 取**渲染出来**的那一格文字。
     *
     * <p>不直接调 {@code cellValueFactory.call(...)}：它要一个
     * {@code TableColumn.CellDataFeatures}，那个构造器不是公开 API，绕它就得反射 ——
     * 而反射只验得出「工厂被调用时不抛」。渲染出来的那一格才是用户真的看到的。
     */
    private static String cellTextOf(TableView<ConnectionProfile> table,
            TableColumn<ConnectionProfile, String> column, ConnectionProfile profile) {
        String[] holder = new String[1];
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            for (javafx.scene.Node node : table.lookupAll(".table-cell")) {
                if (!(node instanceof TableCell<?, ?> cell)
                        || cell.getTableColumn() != column
                        || !(cell.getTableRow() instanceof javafx.scene.control.TableRow<?> row)
                        || row.getItem() != profile) {
                    continue;
                }
                holder[0] = cell.getText();
                return;
            }
            org.assertj.core.api.Assertions.fail(
                    "%s 那一格没渲染出来 —— 断言是空的", profile.name());
        });
        return holder[0];
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("workbench"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
