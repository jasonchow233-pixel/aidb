package io.agenticdb.ui.shell;

import io.agenticdb.asset.AssetKind;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P1-7：SideBar 对象树的 Redis 接线门禁。
 *
 * <p>守三件事：
 * <ol>
 *   <li>Redis 连接下挂一个「Redis 键」分组；AppShell 回填库清单后展开出
 *       {@code db 0 / db 1 …} 各 {@code REDIS_KEY} 叶子（双击开键浏览器）；</li>
 *   <li>双击 / 回车 {@code REDIS_KEY} 叶子 → {@code openTab} 收到
 *       {@code TabItem(id="redis-keys:<connId>:<db>", kind=REDIS_KEYS, target="<db>", connectionId)}；</li>
 *   <li>空态（库清单还没读）下展开「Redis 键」分组 → 触发 {@code onLoadRedisDbs} 回调。</li>
 * </ol>
 *
 * <p>复用 {@link SideBarTreeTest} 的同款 FX 辅助，但独立成类，避免把 SQL/Redis 门禁耦合在一起。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class RedisTreeTest {

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

    /** 直接子节点名字。 */
    private static List<String> childNames(TreeItem node) {
        List<String> names = new ArrayList<>();
        for (Object child : node.getChildren()) {
            names.add(nameOf(valueOf(child)));
        }
        return names;
    }

    /** DemoNode 的 toString 是 {@code DemoNode[name=..., kind=..., ...]} —— 取 name 段。 */
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

    /** Redis 档案（用主构造器显式给 id，便于断言 tab id 编码）。 */
    private static ConnectionProfile redisProfile(String id, String name) {
        return new ConnectionProfile(id, name, AssetKind.REDIS, "localhost", 6379,
                "", "u", false, 0, "", java.util.Map.of(), SshConfig.NONE);
    }

    // ───────────────────────────── 用例 ─────────────────────────────

    /**
     * Redis 连接下先挂一个「Redis 键」空分组（加载中占位）；
     * AppShell 回填 {@code setRedisDbs(connId, [0,1])} 后，分组里展开出
     * {@code db 0 / db 1} 两个 {@code REDIS_KEY} 叶子。
     */
    @Test
    void redisConnectionShowsKeysGroupWithDatabasesAfterLoad() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile redis = redisProfile("redis-1", "缓存库");
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "缓存库",
                tab -> { }, message -> { }, "redis-1", List.of(redis), p -> { }));

        TreeItem root = rootOf(treeOf(sideBar));
        TreeItem conn = findExact(root, "缓存库");
        assertThat(conn).as("Redis 连接节点存在").isNotNull();

        // 未读库清单前：本连接下只有一个「Redis 键」分组（空态占位）。
        List<String> before = childNames(conn);
        assertThat(before)
                .as("未加载库清单时本连接下只有一个 Redis 键分组")
                .containsExactly(I18n.t("sidebar.group.redisKeys"));

        TreeItem group = findExact(conn, I18n.t("sidebar.group.redisKeys"));
        assertThat(group).as("Redis 键分组节点存在").isNotNull();
        assertThat(group.getChildren())
                .as("空态：分组里有「加载中」占位子节点")
                .isNotEmpty();

        // AppShell 读完库清单回填。
        onFxRun(() -> sideBar.setRedisDbs("redis-1", List.of(0, 1)));

        TreeItem root2 = rootOf(treeOf(sideBar));
        TreeItem conn2 = findExact(root2, "缓存库");
        TreeItem group2 = findExact(conn2, I18n.t("sidebar.group.redisKeys"));

        assertThat(childNames(group2))
                .as("回填 db 0 / db 1 后展开出两个库叶子")
                .containsExactlyInAnyOrder("db 0", "db 1");

        for (Object child : group2.getChildren()) {
            TreeItem leaf = (TreeItem) child;
            assertThat(String.valueOf(leaf.getValue()))
                    .as("%s 是 REDIS_KEY 叶子", nameOf(leaf.getValue()))
                    .contains("kind=REDIS_KEY");
        }
    }

    /**
     * 双击 / 回车 {@code REDIS_KEY} 叶子 → {@code openTab} 收到正确的键浏览器标签：
     * id 编码成 {@code redis-keys:<connId>:<db>}，kind=REDIS_KEYS，target=db 下标，connectionId 回带。
     */
    @Test
    void doubleClickOnRedisKeyOpensKeysTab() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile redis = redisProfile("redis-1", "缓存库");
        AtomicReference<TabItem> opened = new AtomicReference<>();
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "缓存库",
                opened::set, message -> { }, "redis-1", List.of(redis), p -> { }));
        onFxRun(() -> sideBar.setRedisDbs("redis-1", List.of(0, 1)));

        TreeView tree = treeOf(sideBar);
        TreeItem root = rootOf(tree);
        TreeItem conn = findExact(root, "缓存库");
        TreeItem group = findExact(conn, I18n.t("sidebar.group.redisKeys"));
        TreeItem db1 = findExact(group, "db 1");

        onFxRun(() -> {
            tree.getSelectionModel().select(db1);
            tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
        });

        TabItem tab = opened.get();
        assertThat(tab).as("双击 db 1 应打开键浏览器标签").isNotNull();
        assertThat(tab.id()).isEqualTo("redis-keys:redis-1:1");
        assertThat(tab.kind()).isEqualTo(TabKind.REDIS_KEYS);
        assertThat(tab.target()).isEqualTo("1");
        assertThat(tab.connectionId()).isEqualTo("redis-1");
    }

    /**
     * 空态（还没读库清单）下展开「Redis 键」分组 → 触发 {@code onLoadRedisDbs} 回调，
     * 让 AppShell 去连真 Redis 拉库清单。这是懒加载链的入口。
     */
    @Test
    void expandingEmptyRedisGroupTriggersLoadCallback() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile redis = redisProfile("redis-1", "缓存库");
        List<String> loaded = new ArrayList<>();
        SideBar sideBar = onFx(() -> {
            SideBar created = new SideBar(actions, List.of(), "缓存库",
                    tab -> { }, message -> { }, "redis-1", List.of(redis), p -> { });
            created.setOnLoadRedisDbs(loaded::add);
            return created;
        });

        TreeView tree = treeOf(sideBar);
        TreeItem root = rootOf(tree);
        TreeItem conn = findExact(root, "缓存库");
        TreeItem group = findExact(conn, I18n.t("sidebar.group.redisKeys"));

        onFxRun(() -> group.setExpanded(true));

        assertThat(loaded)
                .as("展开空分组应触发 onLoadRedisDbs（懒加载入口）")
                .containsExactly("redis-1");
    }
}
