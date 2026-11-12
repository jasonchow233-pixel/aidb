package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
import io.agenticdb.objectstore.ObjectStoreCredentials;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.SshConfig;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2-7：SideBar 对象树的对象存储接线门禁。
 *
 * <p>与 {@link RedisTreeTest} 同一套 FX 辅助，但断的是对象存储这一族的四个事实：
 * <ol>
 *   <li>对象存储连接下挂「桶」分组，未读清单时只放一个占位（懒加载，不假装列过桶）；</li>
 *   <li>{@code setBuckets} 回填后每个桶变成 {@code BUCKET} 叶子；</li>
 *   <li>双击 / 回车桶 → {@code TabItem(id="object-browser:<connId>:<bucket>",
 *       kind=OBJECT_BROWSER, target=<bucket>, connectionId)}；</li>
 *   <li>读失败 → 「展开重试」节点，展开再次触发 {@code onLoadBuckets}。</li>
 * </ol>
 *
 * <p>七牛只在后端声明「无 listBuckets」，树不谎称能列出全部桶 —— 这条由
 * {@code ObjectStoreSessionTest} 的能力矩阵守，本类只管 UI 接线。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class ObjectStoreTreeTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private static TreeView treeOf(SideBar sideBar) {
        return onFx(() -> (TreeView) sideBar.lookup(".adb-object-tree"));
    }

    private static TreeItem rootOf(TreeView tree) {
        return onFx(() -> (TreeItem) tree.getRoot());
    }

    private static Object valueOf(Object item) {
        return ((TreeItem) item).getValue();
    }

    private static TreeItem findExact(TreeItem node, String name) {
        if (nameOf(valueOf(node)).equals(name)) {
            return node;
        }
        for (Object child : node.getChildren()) {
            TreeItem found = findExact((TreeItem) child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<String> childNames(TreeItem node) {
        List<String> names = new ArrayList<>();
        for (Object child : node.getChildren()) {
            names.add(nameOf(valueOf(child)));
        }
        return names;
    }

    /** DemoNode 是 SideBar 的私有 record；从 toString 的 name / kind 段取值。 */
    private static String nameOf(Object demoNode) {
        String text = String.valueOf(demoNode);
        int start = text.indexOf("name=");
        if (start < 0) {
            return text;
        }
        int from = start + "name=".length();
        int to = text.indexOf(',', from);
        return to < 0 ? text.substring(from) : text.substring(from, to);
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

    private static ConnectionProfile objectStoreProfile(String id, String name) {
        return new ConnectionProfile(id, name, AssetKind.OBJECT_STORE, "s3.example.com", 443,
                "assets", "ak", false, 0, "",
                Map.of(ObjectStoreCredentials.OPT_PROVIDER, "s3"), SshConfig.NONE);
    }

    private static SideBar sideBar(List<ConnectionProfile> profiles,
                                   java.util.function.Consumer<TabItem> opened) {
        return onFx(() -> new SideBar(new ActionRegistry(), List.of(), "对象存储",
                opened, message -> { }, "os-1", profiles, p -> { }));
    }

    private static TreeItem bucketsGroup(SideBar bar) {
        TreeItem root = rootOf(treeOf(bar));
        TreeItem conn = findExact(root, "对象存储");
        assertThat(conn).as("对象存储连接节点存在").isNotNull();
        TreeItem group = findExact(conn, I18n.t("sidebar.group.buckets"));
        assertThat(group).as("桶分组节点存在").isNotNull();
        return group;
    }

    // ───────────────────────────── 用例 ─────────────────────────────

    @Test
    void objectStoreConnectionShowsBucketsGroupWithLeavesAfterLoad() {
        SideBar bar = sideBar(List.of(objectStoreProfile("os-1", "对象存储")), tab -> { });

        TreeItem group = bucketsGroup(bar);
        assertThat(group.getChildren())
                .as("未读清单时分组里有「加载中」占位子节点（箭头由头）")
                .isNotEmpty();

        onFxRun(() -> bar.setBuckets("os-1", List.of("assets", "backups")));

        TreeItem after = bucketsGroup(bar);
        assertThat(childNames(after))
                .as("回填后每个桶挂成叶子")
                .containsExactlyInAnyOrder("assets", "backups");
        for (Object child : after.getChildren()) {
            assertThat(String.valueOf(valueOf(child)))
                    .as("%s 是 BUCKET 叶子", nameOf(valueOf(child)))
                    .contains("kind=BUCKET");
        }
    }

    @Test
    void expandingEmptyBucketsGroupTriggersLoadCallback() {
        List<String> loaded = new ArrayList<>();
        SideBar bar = onFx(() -> {
            SideBar created = new SideBar(new ActionRegistry(), List.of(), "对象存储",
                    tab -> { }, message -> { }, "os-1",
                    List.of(objectStoreProfile("os-1", "对象存储")), p -> { });
            created.setOnLoadBuckets(loaded::add);
            return created;
        });

        TreeItem group = bucketsGroup(bar);
        onFxRun(() -> group.setExpanded(true));

        assertThat(loaded)
                .as("展开空分组应触发 onLoadBuckets（懒加载入口）")
                .containsExactly("os-1");
    }

    @Test
    void enterOnBucketOpensObjectBrowserTab() {
        AtomicReference<TabItem> opened = new AtomicReference<>();
        SideBar bar = sideBar(List.of(objectStoreProfile("os-1", "对象存储")), opened::set);
        onFxRun(() -> bar.setBuckets("os-1", List.of("assets")));

        TreeView tree = treeOf(bar);
        TreeItem bucket = findExact(bucketsGroup(bar), "assets");
        assertThat(bucket).as("assets 桶叶子存在").isNotNull();

        onFxRun(() -> {
            tree.getSelectionModel().select(bucket);
            tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
        });

        TabItem tab = opened.get();
        assertThat(tab).as("回车 assets 应打开对象浏览器标签").isNotNull();
        assertThat(tab.id()).isEqualTo("object-browser:os-1:assets");
        assertThat(tab.kind()).isEqualTo(TabKind.OBJECT_BROWSER);
        assertThat(tab.target()).isEqualTo("assets");
        assertThat(tab.connectionId()).isEqualTo("os-1");
    }

    @Test
    void failedLoadShowsRetryNodeThatReloads() {
        List<String> loaded = new ArrayList<>();
        SideBar bar = onFx(() -> {
            SideBar created = new SideBar(new ActionRegistry(), List.of(), "对象存储",
                    tab -> { }, message -> { }, "os-1",
                    List.of(objectStoreProfile("os-1", "对象存储")), p -> { });
            created.setOnLoadBuckets(loaded::add);
            return created;
        });

        onFxRun(() -> bar.setObjectStoreFailed("os-1"));
        TreeItem retry = findExact(bucketsGroup(bar), I18n.t("sidebar.bucketsFailed"));
        assertThat(retry).as("读失败时给「展开重试」节点，不假装空桶").isNotNull();

        onFxRun(() -> retry.setExpanded(true));
        assertThat(loaded)
                .as("展开重试节点应再次触发 onLoadBuckets")
                .containsExactly("os-1");
    }
}
