package io.agenticdb.ui.shell;

import io.agenticdb.ui.App;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P7：可访问性门禁（键盘 + 读屏）。
 *
 * <p>本仓已有两条静态 / 局部门禁：
 * {@code FocusRingAuditTest}（CSS 焦点环）与 {@code IconAuditTest}（纯图标按钮）。
 * 这里补的是**运行时整棵树**的三件事：
 * <ol>
 *   <li>可见可用的交互控件必须有可朗读名称（文字 / accessibleText / 占位提示）；</li>
 *   <li>六区焦点顺序符合 {@code UI-VISUAL-SPEC §10.4}，状态栏不抢 Tab；</li>
 *   <li>自绘控件（标签、切面、卡片）必须真的能用键盘操作，而不是只挂了名称。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class AccessibilityAuditTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        Preferences preferences = Preferences.userNodeForPackage(SettingsStore.class);
        preferences.put("ui.theme", "light");
        preferences.put("ui.density", "normal");
        preferences.putBoolean("ui.sidebar.collapsed", false);
        preferences.putBoolean("ui.details.visible", false);
        new App().start(stage);
    }

    @Test
    void everyVisibleInteractiveControlHasAReadableName(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(auditNames()).as("首页：\n  %s", String.join("\n  ", auditNames())).isEmpty();

        // 数据页 + 查询页：不同的控件集合，逐页审（非活动标签页不渲染，一次只看得到一页）。
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();
        List<String> gridViolations = auditNames();
        assertThat(gridViolations).as("数据页：\n  %s",
                String.join("\n  ", gridViolations)).isEmpty();

        robot.clickOn("#appbar-new-query");
        WaitForAsyncUtils.waitForFxEvents();
        List<String> queryViolations = auditNames();
        assertThat(queryViolations)
                .as("可见交互控件必须有可朗读名称（文字 / accessibleText / 占位提示）：\n  %s",
                        String.join("\n  ", queryViolations))
                .isEmpty();
    }

    @Test
    void customTabsAndCardsDeclareTheirAccessibleRole(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        List<String> violations = onFx(() -> {
            List<String> found = new ArrayList<>();
            walk(stage.getScene().getRoot(), node -> {
                if (!showing(node)) {
                    return;
                }
                if (hasClass(node, "adb-tab")
                        && node.getAccessibleRole() != javafx.scene.AccessibleRole.TAB_ITEM) {
                    found.add(node.getId() + " 的标签角色不是 TAB_ITEM");
                }
                if (hasClass(node, "adb-object-tab")
                        && node.getAccessibleRole() != javafx.scene.AccessibleRole.TAB_ITEM) {
                    found.add(node.getId() + " 的切面角色不是 TAB_ITEM");
                }
            });
            return found;
        });
        assertThat(violations).as("自绘标签 / 切面必须声明 TAB_ITEM：\n  %s",
                String.join("\n  ", violations)).isEmpty();
    }

    @Test
    void focusOrderFollowsTheDocumentedRegionOrder(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();

        List<String> order = onFx(() -> {
            List<Node> nodes = new ArrayList<>();
            walk(stage.getScene().getRoot(), nodes::add);
            List<String> regions = List.of(".menu-bar", ".adb-appbar", ".adb-sidebar",
                    ".adb-tabbar", ".adb-workspace", ".adb-statusbar");
            List<String> missing = new ArrayList<>();
            List<Integer> indexes = new ArrayList<>();
            for (String region : regions) {
                int index = indexOf(nodes, region);
                if (index < 0) {
                    missing.add(region);
                } else {
                    indexes.add(index);
                }
            }
            if (!missing.isEmpty()) {
                return List.of("缺少区域：" + missing);
            }
            if (!isSorted(indexes)) {
                return List.of("区域顺序不是 " + regions + "：" + indexes);
            }
            return List.of();
        });
        assertThat(order).as("焦点顺序 = 菜单 → 功能栏 → 侧栏 → 标签栏 → 内容区 → 状态栏")
                .isEmpty();

        assertThat(onFx(() -> focusableControls(
                stage.getScene().getRoot().lookup(".adb-statusbar"))))
                .as("状态栏不抢 Tab 焦点（规格 §10.4）")
                .isEmpty();
        for (String region : List.of(".adb-sidebar", ".adb-tabbar", ".adb-workspace")) {
            assertThat(onFx(() -> focusableControls(
                    stage.getScene().getRoot().lookup(region))))
                    .as("%s 必须至少有一个 Tab 停靠点", region)
                    .isNotEmpty();
        }
    }

    @Test
    void tabBarIsOperableFromTheKeyboard(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "orders"));
        WaitForAsyncUtils.waitForFxEvents();

        Node usersTab = onFx(() -> stage.getScene().getRoot().lookup("#tab-grid-demo-users"));
        assertThat(usersTab).as("users 标签必须存在").isNotNull();
        assertThat(onFx(usersTab::isFocusTraversable)).as("标签必须是 Tab 停靠点").isTrue();

        onFx(() -> { usersTab.requestFocus(); return null; });
        WaitForAsyncUtils.waitForFxEvents();
        robot.push(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> usersTab.getStyleClass().contains("adb-tab--active")))
                .as("Enter 必须激活标签")
                .isTrue();

        robot.push(KeyCode.RIGHT);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> usersTab.getStyleClass().contains("adb-tab--active")))
                .as("右方向键应移到下一个标签")
                .isFalse();
    }

    @Test
    void objectTabsAreOperableFromTheKeyboard(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        robot.doubleClickOn(treeCell(robot, "users"));
        WaitForAsyncUtils.waitForFxEvents();

        Node structure = onFx(() -> stage.getScene().getRoot().lookup("#object-tab-columns"));
        assertThat(structure).as("结构切面必须存在").isNotNull();
        assertThat(onFx(structure::isFocusTraversable)).as("切面必须是 Tab 停靠点").isTrue();

        onFx(() -> { structure.requestFocus(); return null; });
        WaitForAsyncUtils.waitForFxEvents();
        robot.push(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(onFx(() -> structure.getStyleClass().contains("adb-object-tab--active")))
                .as("Enter 必须切到结构切面")
                .isTrue();
    }

    // ─────────────────────────── 审计助手 ───────────────────────────

    private List<String> auditNames() {
        return onFx(() -> {
            Map<String, String> problems = new LinkedHashMap<>();
            walk(stage.getScene().getRoot(), node -> {
                if (!showing(node) || !interactive(node) || named(node)) {
                    return;
                }
                problems.putIfAbsent(describe(node), "缺少可朗读名称");
            });
            List<String> found = new ArrayList<>();
            problems.forEach((node, problem) -> found.add(node + "：" + problem));
            return found;
        });
    }

    private static boolean interactive(Node node) {
        if (node instanceof TableCell<?, ?> || node instanceof TreeCell<?>) {
            return false; // 单元格渲染的是数据，不是控件
        }
        if (node instanceof ListView<?> && insideComboBox(node)) {
            return false; // ComboBox 皮肤内部的列表：名称由 ComboBox 本身承担
        }
        return node instanceof ButtonBase
                || node instanceof TextInputControl
                || node instanceof ComboBoxBase<?>
                || node instanceof TreeView<?>
                || node instanceof TableView<?>
                || node instanceof ListView<?>
                || hasClass(node, "adb-table-card")
                || hasClass(node, "adb-tab");
    }

    private static boolean insideComboBox(Node node) {
        for (Node current = node.getParent(); current != null; current = current.getParent()) {
            if (current instanceof ComboBoxBase<?>) {
                return true;
            }
        }
        return false;
    }

    private static boolean named(Node node) {
        String accessible = node.getAccessibleText();
        if (accessible != null && !accessible.isBlank()) {
            return true;
        }
        if (node instanceof Labeled labeled) {
            String text = labeled.getText();
            if (text != null && !text.isBlank()) {
                return true;
            }
        }
        if (node instanceof TextInputControl input) {
            String prompt = input.getPromptText();
            return prompt != null && !prompt.isBlank();
        }
        return false;
    }

    private static String describe(Node node) {
        String id = node.getId();
        Node parent = node.getParent();
        String where = parent == null ? "" : "（父：" + parent.getClass().getSimpleName()
                + (parent.getId() == null ? "" : "#" + parent.getId())
                + " " + parent.getStyleClass() + "）";
        return node.getClass().getSimpleName()
                + (id == null ? "" : "#" + id)
                + " " + node.getStyleClass() + where;
    }

    private static boolean showing(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return node.getScene() != null;
    }

    private static void walk(Node node, java.util.function.Consumer<Node> visitor) {
        visitor.accept(node);
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, visitor);
            }
        }
    }

    private static int indexOf(List<Node> nodes, String styleClass) {
        for (int i = 0; i < nodes.size(); i++) {
            if (hasClass(nodes.get(i), styleClass)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSorted(List<Integer> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i - 1) > values.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> focusableControls(Node region) {
        if (region == null) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        walk(region, node -> {
            if (showing(node) && interactive(node) && node.isFocusTraversable()
                    && !node.isDisabled()) {
                found.add(describe(node));
            }
        });
        return found;
    }

    private static boolean hasClass(Node node, String styleClass) {
        // 区域常量按 CSS 选择器写（`.adb-appbar`），样式类本身不带点。
        String name = styleClass.startsWith(".") ? styleClass.substring(1) : styleClass;
        return node.getStyleClass().contains(name);
    }

    private static Node treeCell(FxRobot robot, String name) {
        return robot.lookup(node -> node instanceof TreeCell<?> cell
                        && cell.getTreeItem() != null
                        && cell.getTreeItem().getValue() != null
                        && cell.getTreeItem().getValue().toString()
                                .toLowerCase(Locale.ROOT).contains(name))
                .query();
    }

    private static <T> T onFx(Supplier<T> supplier) {
        java.util.concurrent.atomic.AtomicReference<T> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
