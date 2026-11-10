package io.agenticdb.ui.shell;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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
        // ⭐ Q4：每个页签节点的右键菜单都要问注册表要 tab.pin —— 不注册就抛异常。
        actions.register("tab.pin", "固定 / 取消固定", "tab", null, () -> { });
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
     * ⭐ D-51：查询页签的标题要写出<b>它绑在哪个库</b>。
     *
     * <p>两张 {@code Untitled Query} 并排站着，绑定只藏在激活那一页的下拉和底部状态栏里 ——
     * 用户在 A 页写的 SQL 到底落在哪个库，点一下才知道。页签是唯一的常驻线索，
     * 所以库名要进标题，不是进 tooltip（tooltip 要鼠标停上去才看得到）。
     */
    @Test
    void aQueryTabTitleCarriesTheDatabaseItServes() {
        openQuery("query:pg:1", "Untitled Query 1", "pg");

        assertThat(titles())
                .as("标题 = 名字 + · + 它服务的那个库")
                .containsExactly("Untitled Query 1 · prod_db");
        assertThat(tooltips())
                .as("tooltip 照旧给连接 + 库的全名")
                .containsExactly("Untitled Query 1 @prod_db (生产库)");
        assertThat(accessibleTexts())
                .as("读屏名称跟可见标题走 —— 标题带了库，读屏少了这一格就是睁眼说瞎话")
                .containsExactly("Untitled Query 1 · prod_db");
    }

    /**
     * ⭐ D-51：没有库层的引擎（SQLite 这类单文件）退成<b>连接名</b>，不空着摆一个分隔符。
     */
    @Test
    void aQueryTabOnAnEngineWithoutDatabaseFallsBackToTheConnectionName() {
        run(() -> tabBar.setConnectionCatalogs(id -> ""));
        openQuery("query:demo:1", "Untitled Query 1", "demo");

        assertThat(titles())
                .as("没有库名就写连接名 —— 「这个页在哪台/哪个文件上跑」这个问题总得有个答案")
                .containsExactly("Untitled Query 1 · 演示库");
    }

    /**
     * ⭐ D-51 的门禁本体：切库开出来的<b>新页签</b>，标题当场带着<b>新库</b>。
     *
     * <p>外壳的切库语义是「换绑到目标库的连接上另开一页，原页留在原库」（P-1 裁定），
     * 所以这一格测的是两张页签各自说清自己那份 —— 而不是「最后打开的那张」。
     */
    @Test
    void theTabOpenedAfterSwitchingDatabasesNamesTheNewDatabase() {
        openQuery("query:demo:1", "Untitled Query 1", "demo");
        openQuery("query:pg:2", "Untitled Query 2", "pg");

        assertThat(titles())
                .as("旧页签留在旧库、新页签带新库：两条标题各自成立")
                .containsExactly("Untitled Query 1 · demo_db", "Untitled Query 2 · prod_db");
    }

    /**
     * ⭐ D-51：重名时全名那条形态<b>已经</b>带库了，不能再叠一段 {@code · 库名}。
     *
     * <p>把两段拼在一起不是「更清楚」，是把同一件事说两遍，还把标签栏这个横向稀缺资源撑宽。
     */
    @Test
    void duplicatedQueryTitlesUseTheQualifiedNameWithoutDoublingTheDatabase() {
        openQuery("query:demo:1", "Untitled Query 1", "demo");
        openQuery("query:pg:2", "Untitled Query 1", "pg");

        assertThat(titles())
                .as("重名的两张都补全名，且库名只出现一次")
                .containsExactly("Untitled Query 1 @demo_db (演示库)",
                        "Untitled Query 1 @prod_db (生产库)");
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

    // ───────────────────── Q4：固定标签 ─────────────────────

    /**
     * ⭐ Q4：钉住之后，× 与所有关闭通道都得一起失效 —— 只藏 × 而让 ⌘W 还能关掉
     * 就是「界面说不能关、动作偏能关」，比没有这个功能更糟。
     */
    @Test
    void aPinnedTabCannotBeClosedAndLosesItsCloseButton() {
        open("grid:demo:users", "users", "demo");
        open("grid:demo:orders", "orders", "demo");
        run(() -> tabBar.setPinned("grid:demo:users", true));

        java.util.concurrent.atomic.AtomicReference<Boolean> closed =
                new java.util.concurrent.atomic.AtomicReference<>(true);
        run(() -> closed.set(tabBar.close("grid:demo:users")));
        assertThat(closed.get()).as("固定标签不参与 close()").isFalse();
        assertThat(tabBar.openTabs()).extracting(TabItem::id)
                .as("关不掉就是真还在，不是只返回了 false")
                .contains("grid:demo:users");

        java.util.concurrent.atomic.AtomicReference<List<Object>> button =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        run(() -> {
            HBox box = (HBox) tabBar.tabNodeForTest("grid:demo:users");
            Node close = box.getChildren().stream()
                    .filter(child -> child.getStyleClass().contains("adb-tab__close"))
                    .findFirst().orElse(null);
            button.set(close == null ? List.of()
                    : List.of(close.isVisible(), close.isManaged()));
        });
        assertThat(button.get())
                .as("固定标签不该摆一颗点了没反应的 ×（visible 与 managed 都得收掉）")
                .containsExactly(false, false);
        assertThat(tabBar.tabNodeForTest("grid:demo:users").getStyleClass())
                .as("没有 × 要有个视觉解释")
                .contains("adb-tab--pinned");
    }

    /** ⭐ Q4：常驻 → 固定 → 普通，且 {@code openTabs()} 与场景图必须同一套顺序。 */
    @Test
    void pinnedTabsStayJustBehindThePermanentTab() {
        run(() -> tabBar.openPermanent(
                new TabItem("object-home", "对象", TabKind.OBJECT)));
        open("grid:demo:a", "a", "demo");
        open("grid:demo:b", "b", "demo");
        open("grid:demo:c", "c", "demo");
        run(() -> tabBar.setPinned("grid:demo:c", true));

        assertThat(tabBar.openTabs()).extracting(TabItem::id)
                .containsExactly("object-home", "grid:demo:c", "grid:demo:a", "grid:demo:b");

        java.util.concurrent.atomic.AtomicReference<List<String>> visual =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        run(() -> visual.set(tabBar.visualTabIdsForTest()));
        assertThat(visual.get())
                .as("items 与场景图各排一套的话，关闭后「下一个激活谁」会说假话")
                .containsExactly(TabBar.nodeId("object-home"), TabBar.nodeId("grid:demo:c"),
                        TabBar.nodeId("grid:demo:a"), TabBar.nodeId("grid:demo:b"));
    }

    /** ⭐ Q4：「关闭全部」不能顺手把用户钉住的标签一起端掉。 */
    @Test
    void closingAllKeepsPermanentAndPinnedTabsOnly() {
        run(() -> tabBar.openPermanent(
                new TabItem("object-home", "对象", TabKind.OBJECT)));
        open("grid:demo:a", "a", "demo");
        open("grid:demo:b", "b", "demo");
        run(() -> {
            tabBar.setPinned("grid:demo:b", true);
            tabBar.closeAll();
        });
        assertThat(tabBar.openTabs()).extracting(TabItem::id)
                .containsExactly("object-home", "grid:demo:b");

        // 取消固定之后就回到普通标签，关闭全部能把它们收干净。
        run(() -> {
            tabBar.setPinned("grid:demo:b", false);
            tabBar.closeAll();
        });
        assertThat(tabBar.openTabs()).extracting(TabItem::id)
                .containsExactly("object-home");
    }

    /** ⭐ Q4：固定入口来自 ActionRegistry（⌘K / 右键同源），作用于当前激活页。 */
    @Test
    void theContextMenuOffersPinAndItFollowsTheActiveTab() {
        open("grid:demo:users", "users", "demo");
        java.util.concurrent.atomic.AtomicReference<List<String>> menuIds =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        run(() -> menuIds.set(tabBar.contextMenuForTest().getItems().stream()
                .map(MenuItem::getId).toList()));
        assertThat(menuIds.get()).as("右键菜单必须走注册表那条 tab.pin").contains("tab.pin");

        run(() -> tabBar.togglePinnedActive());
        assertThat(tabBar.isPinned("grid:demo:users")).isTrue();
        run(() -> tabBar.togglePinnedActive());
        assertThat(tabBar.isPinned("grid:demo:users")).isFalse();
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

    /** ⭐ D-51：查询页签的 id 形如 {@code query:<连接>:<时间>}，标题是「未命名查询 N」。 */
    private void openQuery(String id, String title, String connectionId) {
        run(() -> tabBar.open(new TabItem(id, title, TabKind.QUERY, null, connectionId)));
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

    /** ⭐ D-51：标签的读屏名称（P7 那条：要和可见标题同一个字符串）。 */
    private List<String> accessibleTexts() {
        List<String> texts = new ArrayList<>();
        for (Node tab : tabNodes()) {
            texts.add(tab.getAccessibleText());
        }
        return texts;
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
