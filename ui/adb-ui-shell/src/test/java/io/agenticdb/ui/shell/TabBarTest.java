package io.agenticdb.ui.shell;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ UX-5：标签标题的门禁 —— 重名要加连接名，全名永远在 tooltip 里。
 *
 * <p>多连接是这一轮之后才真正可用的功能。两张 {@code users} 表并排站着，
 * 标题<b>一模一样</b>，点哪个全凭猜；关掉一张，另一张还顶着「@ 生产库」，
 * 等于在说一件已经不成立的事。Navicat 给重名的那几个都补上连接名。
 */
@ExtendWith(ApplicationExtension.class)
class TabBarTest {

    private TabBar tabBar;

    @Start
    void start(Stage stage) {
        ActionRegistry actions = new ActionRegistry();
        actions.register("tab.close", "关闭", "tab", null, () -> { });
        actions.register("tab.close-others", "关闭其他", "tab", null, () -> { });
        actions.register("tab.close-all", "全部关闭", "tab", null, () -> { });
        actions.register("tab.copy-name", "复制表名", "tab", null, () -> { });
        actions.register("tab.copy-select", "复制 SELECT", "tab", null, () -> { });
        tabBar = new TabBar(actions);
        // 标签只认识 id；名字与库名是外壳给的（档案里才有）。
        tabBar.setConnectionLabels(id -> "pg".equals(id) ? "生产库" : "演示库");
        tabBar.setConnectionCatalogs(id -> "pg".equals(id) ? "prod_db" : "demo_db");
        stage.setScene(new javafx.scene.Scene(tabBar, 900, 200));
        stage.show();
    }

    @Test
    void duplicatedTabTitlesCarryTheirConnection() {
        open("grid:demo:users", "users", "demo");
        open("grid:pg:users", "users", "pg");

        assertThat(titles())
                .as("重名的**每一个**都要补连接名 —— 只补后开的那个，"
                        + "先开的看起来像「本尊」，可两张表完全平权")
                .containsExactly("users @demo_db (演示库)", "users @prod_db (生产库)");
    }

    @Test
    void aUniqueTabKeepsItsPlainTitleButItsTooltipCarriesTheFullName() {
        open("grid:demo:users", "users", "demo");

        assertThat(titles())
                .as("不重名就别把标题写长 —— 标签栏是横向稀缺资源")
                .containsExactly("users");
        assertThat(tooltips())
                .as("不重名也要在 tooltip 里给全名：这张表在哪个库，鼠标停上去就该知道")
                .containsExactly("users @demo_db (演示库)");
    }

    @Test
    void closingOneDuplicateFallsBackToThePlainTitle() {
        open("grid:demo:users", "users", "demo");
        open("grid:pg:users", "users", "pg");
        close("grid:pg:users");

        assertThat(titles())
                .as("剩下那张已经没有对手了，标题必须退回干净 —— "
                        + "留着「@prod_db (生产库)」是在陈述一个已经不成立的事实")
                .containsExactly("users");
        assertThat(tooltips())
                .as("退回干净标题不等于丢掉信息：全名留在 tooltip 里")
                .containsExactly("users @demo_db (演示库)");
    }

    @Test
    void aConnectionWithoutDatabaseFallsBackToItsName() {
        run(() -> tabBar.setConnectionCatalogs(id -> ""));
        open("sqlite:demo:users", "users", "demo");

        assertThat(tooltips())
                .as("SQLite 没有库名 —— 标题只写连接名，不空着摆一个 @")
                .containsExactly("users @演示库");
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    private void open(String id, String title, String connectionId) {
        run(() -> tabBar.open(new TabItem(id, title, TabKind.GRID, title, connectionId)));
    }

    private void close(String id) {
        run(() -> tabBar.close(id));
    }

    /** 标签栏上**可见**的标题文本，按打开顺序。 */
    private List<String> titles() {
        List<String> texts = new ArrayList<>();
        for (Node tab : tabNodes()) {
            HBox box = (HBox) tab;
            for (Node child : box.getChildren()) {
                if (child instanceof Label label && label.getStyleClass().contains("adb-tab__title")) {
                    texts.add(label.getText());
                }
            }
        }
        return texts;
    }

    /** 标签的全名提示文本（没有提示记为 null，免得静默通过）。 */
    private List<String> tooltips() {
        List<String> texts = new ArrayList<>();
        run(() -> {
            for (TabItem tab : tabBar.openTabs()) {
                texts.add(tabBar.tooltipTextForTest(tab.id()));
            }
        });
        return texts;
    }

    private List<Node> tabNodes() {
        List<Node> result = new ArrayList<>();
        run(() -> {
            for (TabItem tab : tabBar.openTabs()) {
                Node node = tabBar.lookup("#" + TabBar.nodeId(tab.id()));
                if (node != null) {
                    result.add(node);
                }
            }
        });
        return result;
    }

    private static void run(Runnable action) {
        org.testfx.util.WaitForAsyncUtils.waitFor(
                org.testfx.util.WaitForAsyncUtils.asyncFx(() -> {
                    action.run();
                    return null;
                }));
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
    }
}
