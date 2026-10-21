package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.session.ConnectionProfile;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多连接对象树的门禁。
 *
 * <p>以前侧栏的树根就是<b>当前连接</b> —— 切到另一个连接，整棵树就换掉了，
 * 用户没法同时浏览两个库。Navicat 的对象树是<b>所有连接都在同一棵树里</b>。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class SideBarTreeTest {

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void treeShowsEveryConnectionUnderOneRoot() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(
                profile("pg", "生产库"),
                profile("demo", "演示库"));

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", profiles, p -> { }));

        TreeView tree = treeOf(sideBar);
        TreeItem root = rootOf(tree);

        assertThat(String.valueOf(root.getValue()))
                .as("树根应该是「我的连接」而不是某个连接")
                .contains("我的连接");
        assertThat(root.getChildren())
                .as("每个连接一个节点")
                .hasSize(2);
    }

    @Test
    void objectsAreLoadedPerConnection() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile demo = profile("demo", "演示库");
        List<SchemaService.SchemaObject> demoObjects = List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("v_users", SchemaService.ObjectKind.VIEW));

        SideBar sideBar = onFx(() -> new SideBar(actions, demoObjects, "演示库",
                tab -> { }, message -> { }, "demo", List.of(demo), p -> { }));
        onFxRun(() -> sideBar.setObjects("demo", demoObjects));

        List<String> names = new ArrayList<>();
        collect(rootOf(treeOf(sideBar)), names);

        // 节点 toString 是整条记录，用「整棵树文本」来断言
        assertThat(String.join(" | ", names))
                .contains("name=users")
                .contains("name=v_users");
    }

    @Test
    void openingATableUsesItsOwnConnection() {
        ActionRegistry actions = new ActionRegistry();
        List<TabItem> opened = new ArrayList<>();
        ConnectionProfile other = profile("other", "另一个库");

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                opened::add, message -> { }, "demo", List.of(other), p -> { }));
        onFxRun(() -> sideBar.setObjects("other", List.of(
                new SchemaService.SchemaObject("orders", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        TreeItem tableNode = findTable(rootOf(tree), "orders");
        assertThat(tableNode).as("另一个连接下的表也要在树里").isNotNull();

        onFxRun(() -> {
            tree.getSelectionModel().select(tableNode);
            sideBar.actionForTest("object.open-data");
        });

        assertThat(opened).as("打开别的连接的表，标签必须绑那个连接").hasSize(1);
        assertThat(opened.getFirst().connectionId()).isEqualTo("other");
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private static ConnectionProfile profile(String id, String name) {
        return new ConnectionProfile(id, name, DialectKind.POSTGRESQL, "localhost", 5432,
                "db", "u", false, 0, java.util.Map.of());
    }

    private static TreeView treeOf(SideBar sideBar) {
        return onFx(() -> (TreeView) sideBar.lookup(".adb-object-tree"));
    }

    private static TreeItem rootOf(TreeView tree) {
        return onFx(() -> (TreeItem) tree.getRoot());
    }

    private static void collect(TreeItem node, List<String> names) {
        names.add(String.valueOf(node.getValue()));
        for (Object child : node.getChildren()) {
            collect((TreeItem) child, names);
        }
    }

    private static TreeItem findTable(TreeItem node, String name) {
        if (String.valueOf(node.getValue()).contains(name)) {
            return node;
        }
        for (Object child : node.getChildren()) {
            TreeItem found = findTable((TreeItem) child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
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
