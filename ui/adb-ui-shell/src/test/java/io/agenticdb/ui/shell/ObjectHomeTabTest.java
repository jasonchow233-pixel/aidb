package io.agenticdb.ui.shell;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
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
 * ⭐ S5 门禁：**「对象」首页标签是常驻的**。
 *
 * <p>Navicat 的标签栏第一个就是常驻的「对象」标签（官方截图实拍）——
 * 它是浏览的起点，**不可关闭**，永远排第一。
 */
@ExtendWith(ApplicationExtension.class)
class ObjectHomeTabTest {

    private TabBar tabBar;
    private ActionRegistry actions;

    @Start
    void start(javafx.stage.Stage stage) {
        actions = new ActionRegistry();
        actions.register("tab.close", "关闭", "tab", null, () -> { });
        actions.register("tab.close-others", "关闭其他", "tab", null, () -> { });
        actions.register("tab.close-all", "全部关闭", "tab", null, () -> { });
        actions.register("tab.copy-name", "复制表名", "tab", null, () -> { });
        actions.register("tab.copy-select", "复制 SELECT", "tab", null, () -> { });
        tabBar = new TabBar(actions);
        stage.setScene(new javafx.scene.Scene(tabBar, 900, 200));
        stage.show();
    }

    @Test
    void theObjectHomeTabIsAlwaysFirstAndCannotBeClosed() {
        AtomicReference<List<String>> ids = new AtomicReference<>(List.of());
        AtomicReference<Boolean> closed = new AtomicReference<>();
        WaitForAsyncUtils.waitFor(WaitForAsyncUtils.asyncFx(() -> {
            tabBar.openPermanent(new TabItem("object-home", "对象", TabKind.OBJECT));
            tabBar.open(new TabItem("grid:demo:users", "users", TabKind.GRID, "users", "demo"));
            tabBar.open(new TabItem("query:demo:1", "查询示例", TabKind.QUERY, null, "demo"));
            List<String> opened = new ArrayList<>();
            tabBar.openTabs().forEach(tab -> opened.add(tab.id()));
            ids.set(List.copyOf(opened));
            closed.set(tabBar.close("object-home"));
            return null;
        }));

        assertThat(ids.get())
                .as("常驻标签永远排第一")
                .isNotEmpty()
                .first()
                .isEqualTo("object-home");
        assertThat(closed.get())
                .as("常驻标签不可关闭")
                .isFalse();
    }

    @Test
    void thePermanentTabHasNoCloseButton() {
        WaitForAsyncUtils.waitFor(WaitForAsyncUtils.asyncFx(() -> {
            tabBar.openPermanent(new TabItem("object-home", "对象", TabKind.OBJECT));
            tabBar.open(new TabItem("grid:demo:users", "users", TabKind.GRID, "users", "demo"));
            return null;
        }));

        Node permanent = findTabNode("object-home");
        Node ordinary = findTabNode("grid:demo:users");
        assertThat(permanent).as("常驻标签节点").isNotNull();
        assertThat(ordinary).as("普通标签节点").isNotNull();
        assertThat(closeButton(permanent))
                .as("常驻标签**不该有**关闭钮 —— 界面上就不该出现一个点了没反应的 ×")
                .isNull();
        assertThat(closeButton(ordinary))
                .as("普通标签要有关闭钮")
                .isNotNull();
    }

    @Test
    void closingEverythingElseLeavesTheObjectHome() {
        AtomicReference<List<String>> ids = new AtomicReference<>(List.of());
        AtomicReference<Integer> closable = new AtomicReference<>();
        WaitForAsyncUtils.waitFor(WaitForAsyncUtils.asyncFx(() -> {
            tabBar.openPermanent(new TabItem("object-home", "对象", TabKind.OBJECT));
            tabBar.open(new TabItem("grid:demo:users", "users", TabKind.GRID, "users", "demo"));
            tabBar.open(new TabItem("query:demo:1", "查询示例", TabKind.QUERY, null, "demo"));
            tabBar.closeAll();
            List<String> left = new ArrayList<>();
            tabBar.openTabs().forEach(tab -> left.add(tab.id()));
            ids.set(List.copyOf(left));
            closable.set(tabBar.closableCount());
            return null;
        }));

        assertThat(ids.get())
                .as("关掉其它标签后，常驻标签还在")
                .containsExactly("object-home");
        assertThat(closable.get()).isZero();
    }

    @Test
    void thePermanentTabContextMenuHasNoCloseEntry() {
        AtomicReference<javafx.scene.control.ContextMenu> menu = new AtomicReference<>();
        WaitForAsyncUtils.waitFor(WaitForAsyncUtils.asyncFx(() -> {
            tabBar.openPermanent(new TabItem("object-home", "对象", TabKind.OBJECT));
            Node permanent = findTabNode("object-home");
            permanent.fireEvent(new javafx.scene.input.ContextMenuEvent(
                    javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0,
                    false, null));
            menu.set(tabBar.contextMenuForTest());
            return null;
        }));
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(menu.get()).isNotNull();
        boolean hasClose = menu.get().getItems().stream()
                .anyMatch(item -> item instanceof MenuItem menuItem
                        && "关闭".equals(menuItem.getText())
                        && menuItem.isVisible());
        assertThat(hasClose)
                .as("常驻标签的右键菜单里不该有「关闭」")
                .isFalse();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 找到某个标签对应的节点（按 id 查，不依赖 CSS 引擎）。 */
    private Node findTabNode(String id) {
        return tabBar.lookup("#" + TabBar.nodeId(id));
    }

    /** 标签节点里的关闭钮（没有则 null）。 */
    private static Button closeButton(Node tabNode) {
        if (!(tabNode instanceof HBox box)) {
            return null;
        }
        for (Node child : box.getChildrenUnmodifiable()) {
            if (child instanceof Button button
                    && button.getStyleClass().contains("adb-tab__close")
                    && button.isVisible()) {
                return button;
            }
        }
        return null;
    }
}
