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
        // ⭐ R54：哪家库也是档案里才知道的事。这里**故意只给 pg 一家** ——
        //    「演示库」那条认不出方言，正好用来钉「认不出就退回通用圆柱，不许蒙一家」。
        tabBar.setConnectionKinds(id -> "pg".equals(id)
                ? io.agenticdb.jdbc.DialectKind.POSTGRESQL : null);
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

    /**
     * ⭐ R54：「对象」页签顶的是<b>那一家自己的图形</b>。
     *
     * <p>这一格页签唯一的职责就是说「这是某条连接的对象页」，而它以前顶着人人相同的圆柱 ——
     * 形状什么也没说，颜色说的又是「这是个库」。两条腿一起废掉，多连接同屏只能读标题里的连接名。
     *
     * <p>⚠️ 只换 <b>OBJECT</b>：表格 / 查询 / 函数那些页签的形状说的是「这是哪种页」，
     * 而且它们的类型色由 {@code IconKindHueTest} 钉着 —— 把它们染成厂商色，
     * 就是产品和自己的门禁打架。
     */
    @Test
    void theObjectTabWearsItsOwnVendorsGlyph() {
        openObject("obj-pg", "对象", "pg");
        openObject("obj-demo", "对象", "demo");

        assertThat(graphicOf("obj-pg").getStyleClass())
                .as("PG 连接的「对象」页签要带上 PostgreSQL 的作用域类，品牌色那条后代规则才吃得到")
                .contains("adb-db--postgresql");
        assertThat(graphicOf("obj-demo").getStyleClass())
                .as("认不出方言时**不许蒙一家** —— 留在通用圆柱上")
                .doesNotContain("adb-db--postgresql", "adb-db--mysql", "adb-db--sqlite");
    }

    /**
     * 注入<b>晚于</b>开页签也得画对：外壳重建场景、恢复工作区都可能先有页签后有方言。
     *
     * <p>这条不是锦上添花 ——  setter 里那段「顺手重画已开页签」没有它就是不执行的死代码。
     */
    @Test
    void aKindInjectedAfterTheTabIsOpenedStillRepaintsIt() {
        openObject("obj-late", "对象", "late");
        assertThat(graphicOf("obj-late").getStyleClass())
                .as("还没注入方言时是通用圆柱")
                .doesNotContain("adb-db--mysql");

        run(() -> tabBar.setConnectionKinds(id -> "late".equals(id)
                ? io.agenticdb.jdbc.DialectKind.MYSQL : null));

        assertThat(graphicOf("obj-late").getStyleClass())
                .as("后到的方言必须重画已开着的页签")
                .contains("adb-db--mysql");
        // ⭐ 重画换的是**整个节点**，不是节点上的一个类 —— 新节点必须把 adb-tab__icon 一起交出来，
        //    否则页签图标丢掉尺寸与配色那条类，而「有图形」这件事照样成立（第一次没人看得出来）。
        assertThat(graphicOf("obj-late").getStyleClass())
                .as("重画后页签图标的类还在")
                .contains("adb-tab__icon");
    }

    /**
     * ⭐ R54：常驻「对象」页签建出来时<b>没有</b> connectionId（启动顺序：标签先有、连接后到），
     * 实拍抓到过这一格因此永远停在通用圆柱上 —— 页签的接线全绿，画面却什么都没有。
     *
     * <p>这条钉的是「{@code null} 要交给注入的函数，而不是在页签这侧短路掉」：
     * 谁代表当前连接只有外壳知道（与 {@code sessionOrCurrent} 同一个兜底口径）。
     */
    @Test
    void thePermanentObjectTabFallsBackToTheActiveConnection() {
        run(() -> {
            // 外壳那侧的形态：认不出 id 就回落到活动档案（这里是 MySQL）。
            tabBar.setConnectionKinds(id -> io.agenticdb.jdbc.DialectKind.MYSQL);
            tabBar.openPermanent(new TabItem("object-home", "Objects", TabKind.OBJECT));
        });

        assertThat(graphicOf("object-home").getStyleClass())
                .as("没有 connectionId 的常驻页签也要有图形 —— 它是用户看得最多的那一格")
                .contains("adb-db--mysql");
        assertThat(graphicOf("object-home").getStyleClass())
                .as("重画/兜底都不能丢掉页签图标自己的类")
                .contains("adb-tab__icon");
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    private void openObject(String id, String title, String connectionId) {
        run(() -> tabBar.open(new TabItem(id, title, TabKind.OBJECT, title, connectionId)));
    }

    /** 某个页签的图形节点（HBox 的第一个孩子）。 */
    private Node graphicOf(String tabId) {
        Node[] holder = new Node[1];
        run(() -> {
            HBox box = (HBox) tabBar.lookup("#" + TabBar.nodeId(tabId));
            holder[0] = box == null ? null : box.getChildren().get(0);
        });
        return holder[0];
    }

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
