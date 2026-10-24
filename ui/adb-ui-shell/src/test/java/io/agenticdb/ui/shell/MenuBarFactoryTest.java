package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单门禁：每个菜单项都必须来自 {@link ActionRegistry}（无死项），
 * 单选菜单（密度三档）的勾选态与动作的 {@code selected} 双向绑定。
 *
 * <p>这是 4.5-18 / 4.5-19 的验收：旧版「编辑」菜单 5 个永久禁用占位项 +
 * 密度菜单自建 RadioMenuItem 的问题不允许回归。
 */
@ExtendWith(ApplicationExtension.class)
class MenuBarFactoryTest {

    /** MenuBarFactory 引用的全部动作 id —— 漏注册一个就会在 build() 时抛异常。 */
    private static final List<String> MENU_ACTION_IDS = List.of(
            "new-connection", "connections.manage", "new-query", "open-sql",
            "tab.close", "window.close", "app.quit",
            "edit.undo", "edit.redo", "edit.copy", "focus-search", "query.comment",
            "command-palette",
            "sidebar.toggle", "theme.toggle", "details.toggle",
            "density-dense", "density-compact", "density-normal", "density-comfort",
            "window.zoom",
            "refresh", "connection.reconnect", "connection.disconnect", "demo.open",
            // T1–T4 表级生命周期（注册在 SideBar，菜单里补入口，避免成为孤儿动作）
            "object.new-table", "object.truncate-table", "object.rename-table",
            "object.drop-table",
            // ⭐ X5：顶级「对象」菜单（对象动作不再只存在于右键菜单）
            "object.tables", "object.open-data", "object.open-structure", "object.new-query",
            "object.favorite", "object.copy-name", "object.copy-select",
            "object.refresh-objects",
            "query.run", "query.run-all", "query.cancel", "query.explain",
            "query.format", "query.save", "query.save-snippet", "query.insert-snippet",
            "favorites.open", "favorites.clear", "diagnostics", "import-export", "settings",
            "window.minimize", "shortcuts", "about");

    @Start
    void start(Stage stage) {
        stage.show();
    }

    private static ActionRegistry registry() {
        ActionRegistry actions = new ActionRegistry();
        for (String id : MENU_ACTION_IDS) {
            actions.register(id, "label-" + id, "测试", null, () -> { });
        }
        return actions;
    }

    private static MenuBar bar(ActionRegistry actions) {
        return onFx(() -> new MenuBarFactory(actions).build());
    }

    private static List<MenuItem> flatten(MenuBar bar) {
        List<MenuItem> items = new ArrayList<>();
        for (Menu menu : bar.getMenus()) {
            collect(menu, items);
        }
        return items;
    }

    private static void collect(Menu menu, List<MenuItem> items) {
        items.addAll(menu.getItems());
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu sub) {
                collect(sub, items);
            }
        }
    }

    @Test
    void everyMenuItemComesFromTheRegistry() {
        ActionRegistry actions = registry();
        MenuBar menuBar = bar(actions);

        Set<String> labels = new LinkedHashSet<>();
        for (MenuItem item : flatten(menuBar)) {
            labels.add(item.getText());
        }
        for (String id : MENU_ACTION_IDS) {
            assertThat(labels).contains("label-" + id);
        }
        // 无第三个来源：除「null（分隔线）」外，每个 label 都必须来自注册表。
        // 注意 new-query / window.zoom 被两个菜单复用，所以比的是集合不是数量。
        for (MenuItem item : flatten(menuBar)) {
            String text = item.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            assertThat(labels).as("菜单项「%s」必须来自 ActionRegistry", text).contains(text);
            assertThat(MENU_ACTION_IDS)
                    .as("菜单项「%s」找不到对应动作", text)
                    .contains(text.substring("label-".length()));
        }
    }

    @Test
    void noMenuItemIsPermanentlyDisabled() {
        // 旧版编辑菜单有 5 个 setDisable(true) 的死项。现在所有禁用态都必须
        // 绑定到 Action.enabledProperty()（可恢复），而不是常量 true。
        MenuBar menuBar = bar(registry());

        for (MenuItem item : flatten(menuBar)) {
            if (item.getText() == null || item.getText().isEmpty()) {
                continue; // 分隔线
            }
            // 注册时 enabled 默认 true；若此处为 false，说明有人写死了禁用。
            assertThat(item.isDisable())
                    .as("菜单项「%s」不应写死禁用", item.getText())
                    .isFalse();
        }
    }

    @Test
    void densityRadioItemsFollowActionSelection() {
        ActionRegistry actions = registry();
        MenuBar menuBar = bar(actions);

        RadioMenuItem dense = findRadio(menuBar, "label-density-dense");
        RadioMenuItem compact = findRadio(menuBar, "label-density-compact");
        RadioMenuItem normal = findRadio(menuBar, "label-density-normal");
        RadioMenuItem comfort = findRadio(menuBar, "label-density-comfort");

        assertThat(dense.getToggleGroup()).isSameAs(normal.getToggleGroup());
        assertThat(compact.getToggleGroup()).isSameAs(normal.getToggleGroup());
        assertThat(comfort.getToggleGroup()).isSameAs(normal.getToggleGroup());

        // 初始互斥：选中 compact 后 dense / normal / comfort 自动取消。
        onFxRun(() -> actions.require("density-compact").setSelected(true));
        assertThat(compact.isSelected()).isTrue();
        assertThat(dense.isSelected()).isFalse();
        assertThat(normal.isSelected()).isFalse();
        assertThat(comfort.isSelected()).isFalse();

        // 反向也通：改动作状态，菜单勾选跟着走（AppShell.applyDensity 的同步路径）。
        onFxRun(() -> actions.require("density-comfort").setSelected(true));
        assertThat(comfort.isSelected()).isTrue();
        assertThat(compact.isSelected()).isFalse();
        assertThat(dense.isSelected()).isFalse();

        // ⭐ UX-2：四档里最密的一档也必须是「可选中、可互斥」的真选项。
        onFxRun(() -> actions.require("density-dense").setSelected(true));
        assertThat(dense.isSelected()).isTrue();
        assertThat(comfort.isSelected()).isFalse();
        assertThat(normal.isSelected()).isFalse();
        assertThat(compact.isSelected()).isFalse();

        // disabled 绑定同样生效。
        onFxRun(() -> actions.require("density-normal").setEnabled(false));
        assertThat(normal.isDisable()).isTrue();
    }

    @Test
    void editMenuDelegatesToCurrentPage() {
        // 编辑菜单的撤销 / 重做 / 复制都走 actions.menuItem()，
        // disable 绑定 Action.enabledProperty，由 AppShell.updateEditActions 驱动。
        ActionRegistry actions = registry();
        MenuBar menuBar = bar(actions);

        Menu edit = menuBar.getMenus().stream()
                .filter(menu -> "编辑".equals(menu.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(edit.getItems().stream().map(MenuItem::getText))
                .containsExactly("label-edit.undo", "label-edit.redo", null,
                        "label-edit.copy", null,
                        "label-focus-search", "label-query.comment", "label-command-palette");

        onFxRun(() -> actions.require("edit.undo").setEnabled(false));
        MenuItem undo = edit.getItems().getFirst();
        assertThat(undo.isDisable()).isTrue();

        onFxRun(() -> actions.require("edit.undo").setEnabled(true));
        assertThat(undo.isDisable()).isFalse();
    }

    private static RadioMenuItem findRadio(MenuBar bar, String label) {
        return flatten(bar).stream()
                .filter(item -> item instanceof RadioMenuItem && label.equals(item.getText()))
                .map(item -> (RadioMenuItem) item)
                .findFirst()
                .orElseThrow();
    }

    /** 在 FX 线程上执行并等待（不用 FxRobot —— Monocle 无头下它的鼠标通道会 NPE）。 */
    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * JavaFX 控件只能在 FX 线程上创建。
     *
     * <p>⚠️ 异常必须**原样抛出**：早期版本把 {@code runLater} 里的异常吞掉，
     * 于是「MenuBarFactory 引用了一个没注册的动作 id」这种真错误，
     * 在现场只剩一句 {@code Cannot invoke "MenuBar.getMenus()" because "bar" is null} ——
     * 排查方向整个被带偏。
     */
    private static <T> T onFx(Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ref.set(supplier.get());
            } catch (RuntimeException thrown) {
                failure.set(thrown);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (failure.get() != null) {
            throw failure.get();
        }
        return ref.get();
    }
}
