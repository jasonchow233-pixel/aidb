package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P3-4：标签总览面板的行为门禁 + P3-5 关闭链路的结构门禁。
 *
 * <p>行为侧：按连接分组、搜索过滤、逐条激活 / 关闭、按连接关一组，四条回调都收到正确的
 * {@link TabItem} / 组名；空结果给 {@code EmptyState} 而不是一片空白。
 *
 * <p>结构侧：总览页<b>必须</b>把关闭交回 {@code TabBar.close()}，否则「从总览里关」
 * 就成了绕过 {@code closeGuard} 脏检查与钉住态的后门。这条按源码扫 —— 运行时门禁
 * 要造一个带脏标签的整个外壳，成本远高于它能提供的额外信息。
 */
@ExtendWith(ApplicationExtension.class)
class TabOverviewPanelTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static List<TabItem> sampleTabs() {
        return List.of(
                new TabItem("grid:a:users", "users", TabKind.GRID, "users", "conn-a"),
                new TabItem("grid:a:orders", "orders", TabKind.GRID, "orders", "conn-a"),
                new TabItem("redis-keys:b:0", "db 0", TabKind.REDIS_KEYS, "0", "conn-b"));
    }

    private static List<Button> buttons(Node root, String text) {
        List<Button> found = new ArrayList<>();
        collectButtons(root, text, found);
        return found;
    }

    private static void collectButtons(Node node, String text, List<Button> found) {
        if (node instanceof Button button && text.equals(button.getText())) {
            found.add(button);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectButtons(child, text, found);
            }
        }
    }

    private static List<String> labels(Node root) {
        List<String> found = new ArrayList<>();
        collectLabels(root, found);
        return found;
    }

    private static void collectLabels(Node node, List<String> found) {
        if (node instanceof Label label && label.getText() != null) {
            found.add(label.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabels(child, found);
            }
        }
    }

    private TabOverviewPanel panel(List<TabItem> tabs,
                                   List<TabItem> activated, List<TabItem> closed,
                                   List<String> groups) {
        return onFx(() -> new TabOverviewPanel(tabs, activated::add, closed::add, groups::add));
    }

    @Test
    void groupsTabsByConnectionAndShowsAnEmptyState() {
        TabOverviewPanel panel = panel(sampleTabs(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());
        List<String> text = labels(panel);
        assertThat(text)
                .as("两组各一个分组标题，三条标签标题都在")
                .contains(I18n.t("tab.overview.group", "conn-a"),
                        I18n.t("tab.overview.group", "conn-b"),
                        "users", "orders", "db 0");

        TabOverviewPanel empty = panel(List.of(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());
        assertThat(labels(empty))
                .as("没有已打开标签时给空态，不是一片空白")
                .contains(I18n.t("tab.overview.empty"));
    }

    @Test
    void searchNarrowsRowsToMatchingTitleOrConnection() {
        TabOverviewPanel panel = panel(sampleTabs(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());

        TextField search = onFx(() -> (TextField) panel.lookup("#tab-overview-search"));
        assertThat(search).isNotNull();
        onFxRun(() -> search.setText("orders"));

        List<String> text = labels(panel);
        assertThat(text).contains("orders").doesNotContain("users", "db 0");

        onFxRun(() -> search.setText("nomatch"));
        assertThat(labels(panel)).contains(I18n.t("tab.overview.empty"));
    }

    @Test
    void activateCloseAndCloseGroupButtonsCallTheRightCallbacks() {
        List<TabItem> activated = new ArrayList<>();
        List<TabItem> closed = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        TabOverviewPanel panel = panel(sampleTabs(), activated, closed, groups);

        onFxRun(() -> panel.lookup("#tab-overview-search").requestFocus());
        // 挂进 Scene 才能稳定 fire；面板本身不依赖 Scene 也能渲染。
        onFxRun(() -> stage.setScene(new Scene(new StackPane(panel), 800, 600)));
        WaitForAsyncUtils.waitForFxEvents();

        List<Button> activate = buttons(panel, I18n.t("tab.overview.activate"));
        List<Button> close = buttons(panel, I18n.t("tab.overview.close"));
        List<Button> closeGroup = buttons(panel, I18n.t("tab.overview.closeGroup"));
        assertThat(activate).hasSize(3);
        assertThat(close).hasSize(3);
        assertThat(closeGroup).hasSize(2);

        onFxRun(() -> activate.get(1).fire());
        assertThat(activated).extracting(TabItem::id).containsExactly("grid:a:orders");

        onFxRun(() -> close.getFirst().fire());
        assertThat(closed).extracting(TabItem::id).containsExactly("grid:a:users");

        onFxRun(() -> closeGroup.getLast().fire());
        assertThat(groups).containsExactly("conn-b");
    }

    /**
     * ⭐ P3-5：总览页的关闭必须走 TabBar.close()（复用 closeGuard / pinned / permanent），
     * 不能直接改 TabBar 的内部集合、也不能绕过外壳自己删页面。
     */
    @Test
    void appShellRoutesOverviewClosuresThroughTabBarClose() throws IOException {
        Path root = repositoryRoot();
        Path shell = root.resolve("ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/AppShell.java");
        assertThat(shell).as("找不到 AppShell.java —— 结构门禁扫到 0 个文件就是假绿").exists();

        String source = Files.readString(shell, StandardCharsets.UTF_8);
        int start = source.indexOf("private void openTabsOverview()");
        assertThat(start).as("找不到 openTabsOverview 方法").isNotNegative();
        int end = source.indexOf("\n    private void ", start + 1);
        String body = source.substring(start, end < 0 ? source.length() : end);

        assertThat(body)
                .as("总览的逐条关闭 / 关整组都要经 TabBar.close() 走脏检查")
                .contains("tabBar.close(tab.id())");
        assertThat(body)
                .as("激活走 TabBar.activate()，与标签栏点击同一条链路")
                .contains("tabBar.activate(tab.id())");
        assertThat(body)
                .as("总览是数据快照的一次性治理面板，不是常驻订阅视图")
                .contains("tabBar.openTabs()");
        assertThat(body)
                .as("不许绕开 TabBar 直接删页面 / 直接操作 items")
                .doesNotContain("items.remove(", "workspace.closeTab(", "tabClosed(");
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("ui"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
