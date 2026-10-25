package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.session.ConnectionProfile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import io.agenticdb.ui.Tokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ UX-5：侧栏选中态的门禁。
 *
 * <p>规格：侧栏的连接/库/表选中后要和网格选中行**同一套语言** ——
 * {@code bg-selected} tint 填充 + 左侧 2px {@code accent} 强调条，
 * 不再画「蓝描边 + 外发光」那种焦点框（窄栏里一圈蓝框既抢注意力又吃掉文字宽度）。
 *
 * <p>⚠️ 三条门禁全部读**计算后的 CSS 值**（{@code getBackground()} / {@code getBorder()} /
 * {@code getEffect()}），所以 {@link #start} 必须把两套样式表挂上场景 ——
 * S-7 那轮的教训：样式表没挂时这些 getter 拿到的是节点默认值，断言等于没测。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class SidebarSelectionTest {

    private SideBar sideBar;

    @Start
    void start(Stage stage) {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile demo = new ConnectionProfile("demo", "演示库",
                DialectKind.SQLITE, "localhost", 0, "db", "u", false, 0, "", java.util.Map.of());
        List<SchemaService.SchemaObject> objects = List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE));
        sideBar = new SideBar(actions, objects, "演示库", tab -> { }, message -> { },
                "demo", List.of(demo), profile -> { });

        Scene scene = new Scene(sideBar, 300, 600);
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        sideBar.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** 选中节点 = 与网格选中行同一个 tint 令牌，不是「只有文字变粗」。 */
    @Test
    void theSelectedNodeIsFilledWithTheGridsSelectionTint(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> {
            tree.getSelectionModel().select(0);
            tree.getFocusModel().focus(0);
        });

        TreeCell cell = cellAt(tree, 0);
        assertThat(cell).as("第一个连接节点必须已渲染成 cell").isNotNull();
        assertThat(cell.isSelected()).as("先确认选中状态本身成立").isTrue();

        assertThat(cell.getBackground()).as("CSS 没生效时这里是 null —— 断言会假绿").isNotNull();
        Paint paint = cell.getBackground().getFills().get(0).getFill();
        assertThat(paint)
                .as("侧栏选中必须用 bg-selected（与 .adb-grid .table-row-cell:selected 同一令牌）")
                .isEqualTo(Color.web(Tokens.BG_SELECTED_LIGHT));
    }

    /** 键盘焦点不再画蓝框 + 外发光；淡底留给「有焦点但没选中」这一态。 */
    @Test
    void theFocusedButUnselectedNodeHasNoBlueHalo(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> {
            tree.getSelectionModel().select(0);
            tree.getFocusModel().focus(1);
        });
        // ⚠️ 必须让**格子自己**成为场景焦点拥有者：getFocusModel().focus(1) 只移动
        //    TreeView 内部的焦点索引，cell.isFocused() 仍然是 false（实测），
        //    那样下面两条断言测的是一个「根本没聚焦的格子」—— 门禁自己就是假的。
        TreeCell focused = cellAt(tree, 1);
        assertThat(focused).isNotNull();
        onFxRun(() -> focused.requestFocus());

        assertThat(focused.isFocused()).as("先确认焦点确实落在这一格").isTrue();
        assertThat(focused.isSelected()).as("它不能同时是选中格").isFalse();

        assertThat(focused.getEffect())
                .as("外发光（dropshadow 焦点环）必须去掉：窄栏里它既盖不住内容也不体面")
                .isNull();
        assertThat(borderPaint(focused))
                .as("焦点格不再描 border-focus 那圈蓝，改用淡底")
                .isNotEqualTo(Color.web(Tokens.BORDER_FOCUS_LIGHT));
        assertThat(focused.getBackground().getFills().get(0).getFill())
                .as("焦点格用 bg-active 淡底，键盘用户仍看得见焦点在哪")
                .isEqualTo(Color.web(Tokens.BG_ACTIVE_LIGHT));
    }

    /** 槽位常留：选中只上色不改宽度，否则选中一次文字就左右跳一次。 */
    @Test
    void selectingANodeDoesNotShiftItsText(FxRobot robot) {
        TreeView tree = treeOf(sideBar);
        TreeCell before = cellAt(tree, 0);
        double widthBefore = borderLeftWidth(before);

        onFxRun(() -> tree.getSelectionModel().select(0));
        TreeCell after = cellAt(tree, 0);

        assertThat(borderLeftWidth(after))
                .as("选中不能靠加粗边框表达 —— 边框槽位由基础规则常留，选中只换颜色")
                .isEqualTo(widthBefore);
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    private static Paint borderPaint(TreeCell cell) {
        if (cell.getBorder() == null || cell.getBorder().getStrokes().isEmpty()) {
            return null;
        }
        return cell.getBorder().getStrokes().get(0).getLeftStroke();
    }

    private static double borderLeftWidth(TreeCell cell) {
        if (cell.getBorder() == null || cell.getBorder().getStrokes().isEmpty()) {
            return 0.0;
        }
        return cell.getBorder().getStrokes().get(0).getWidths().getLeft();
    }

    private static TreeView treeOf(SideBar sideBar) {
        return onFx(() -> (TreeView) sideBar.lookup(".adb-object-tree"));
    }

    private static TreeCell cellAt(TreeView tree, int index) {
        return onFx(() -> {
            for (Node node : tree.lookupAll(".tree-cell")) {
                if (node instanceof TreeCell) {
                    TreeCell cell = (TreeCell) node;
                    if (cell.getIndex() == index) {
                        return cell;
                    }
                }
            }
            return null;
        });
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
