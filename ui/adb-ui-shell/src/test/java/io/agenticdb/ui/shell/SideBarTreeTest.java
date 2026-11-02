package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Tokens;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.session.ConnectionProfile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.stage.Stage;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
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
                .contains(I18n.t("sidebar.myConnections"));
        assertThat(root.getChildren())
                .as("每个连接一个节点")
                .hasSize(2);
    }

    /**
     * ⭐ P5-1：有分组的连接收进文件夹节点；未分组的直接挂在根下。
     *
     * <p>只用「连接」的用户看不到多出来的一层（上面的用例已守），
     * 这里守「分组真的出现、且在组里」。
     */
    @Test
    void connectionsAreGroupedUnderFolderNodes() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(
                profile("prod-1", "生产库甲").withGroup("生产"),
                profile("prod-2", "生产库乙").withGroup("生产"),
                profile("demo", "演示库"));

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", profiles, p -> { }));

        TreeItem root = rootOf(treeOf(sideBar));
        assertThat(root.getChildren())
                .as("未分组连接 + 一个「生产」分组 = 根下两项")
                .hasSize(2);

        TreeItem group = findExact(root, "生产");
        assertThat(group).as("分组文件夹节点").isNotNull();
        assertThat(childNames(group))
                .as("同组连接都在文件夹下面")
                .containsExactlyInAnyOrder("生产库甲", "生产库乙");
        assertThat(root.getChildren())
                .as("未分组连接仍直接挂在根下")
                .anyMatch(item -> nameOf(valueOf(item)).equals("演示库"));
    }

    @Test
    void objectsAreLoadedPerConnection() {        ActionRegistry actions = new ActionRegistry();
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

    /**
     * ⭐ R37 N33：例程（过程 / 函数）的定义体从树打开 —— 对象首页撤掉后的唯一入口。
     *
     * <p>Enter 与双击走同一段分派逻辑，这里守 Enter：把 (节点自己的连接, 名字, 种类)
     * 交给外壳，且 catalog 虚拟连接不能被换成父连接。
     */
    @Test
    void routinesOpenTheirDefinitionFromTheTree() {
        ActionRegistry actions = new ActionRegistry();
        List<SideBar.RoutineRequest> opened = new ArrayList<>();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "srv-1", List.of(mysql), p -> { }));
        sideBar.setOnOpenRoutine(opened::add);
        onFxRun(() -> sideBar.setCatalogs("srv-1", List.of(
                new SideBar.CatalogEntry("audit", "srv-1@audit"))));
        onFxRun(() -> sideBar.setObjects("srv-1@audit", List.of(
                new SchemaService.SchemaObject("sync_orders", SchemaService.ObjectKind.PROCEDURE),
                new SchemaService.SchemaObject("calc_total", SchemaService.ObjectKind.FUNCTION))));

        TreeView tree = treeOf(sideBar);
        TreeItem procedure = findNode(rootOf(tree), "sync_orders");
        assertThat(procedure).as("过程在 audit 库的「函数」组里").isNotNull();

        onFxRun(() -> {
            tree.getSelectionModel().select(procedure);
            tree.fireEvent(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
        });

        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().connectionId())
                .as("用节点自己的虚拟连接，不是父连接")
                .isEqualTo("srv-1@audit");
        assertThat(opened.getFirst().name()).isEqualTo("sync_orders");
        assertThat(opened.getFirst().procedure()).as("过程 → 过程定义体").isTrue();
    }

    /**
     * ⭐ R37 N12：触发器不再在对象树里占一个分组。
     *
     * <p>Navicat 的树只有 Tables / Views / Functions / Queries 四组；
     * 触发器按表归属（数据页的只读切面里看），树上没有它的家。
     * 这里把一个 TRIGGER 对象喂给树，树也只出表 / 视图 / 函数三组 ——
     * 不是「触发器读不到」，而是「读到了也不该在这一层出现」。
     */
    @Test
    void triggersNoLongerGetTheirOwnGroupInTheTree() {
        ActionRegistry actions = new ActionRegistry();
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "内存库",
                tab -> { }, message -> { }, "demo", List.of(profile("demo", "内存库")),
                p -> { }));
        onFxRun(() -> sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("t", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("trg_after_insert",
                        SchemaService.ObjectKind.TRIGGER))));

        List<String> names = new ArrayList<>();
        collect(rootOf(treeOf(sideBar)), names);
        String tree = String.join(" | ", names);
        assertThat(tree).as("表分组照常").contains("name=" + I18n.t("sidebar.group.tables"));
        assertThat(tree).as("触发器对象不单独成组、也不作为叶子出现")
                .doesNotContain("trg_after_insert");
    }

    /**
     * ⭐ U1：catalog 树 —— MySQL 这类「切库要重连」的方言，连接节点下先出库一层。
     *
     * <p>守的规则：库节点挂在连接下、对象分组挂在**库**下（不是连接下）、
     * 没有 catalog 的连接保持老形态（SQLite / PG 不受影响）。
     */
    @Test
    void catalogLevelSitsBetweenConnectionAndObjects() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "srv-1", List.of(mysql), p -> { }));
        // orders 由父连接 Serve；audit 是虚拟连接 srv-1@audit。
        onFxRun(() -> sideBar.setCatalogs("srv-1", List.of(
                new SideBar.CatalogEntry("orders", "srv-1"),
                new SideBar.CatalogEntry("audit", "srv-1@audit"))));
        onFxRun(() -> sideBar.setObjects("srv-1", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));
        onFxRun(() -> sideBar.setObjects("srv-1@audit", List.of(
                new SchemaService.SchemaObject("events", SchemaService.ObjectKind.TABLE))));

        List<String> names = new ArrayList<>();
        collect(rootOf(treeOf(sideBar)), names);
        String tree = String.join(" | ", names);

        assertThat(tree).as("两个库都出现在连接节点下").contains("name=orders").contains("name=audit");
        assertThat(tree).as("父库的表挂在库节点下").contains("name=users");
        assertThat(tree).as("另一个库的表也挂在它自己的库节点下").contains("name=events");

        // 树形层级：连接 → 库 → 分组 → 表（不是连接 → 分组 → 表）。
        TreeItem root = rootOf(treeOf(sideBar));
        TreeItem ordersNode = findNode(root, "orders");
        assertThat(ordersNode).as("orders 库节点存在").isNotNull();
        assertThat(ordersNode.getChildren())
                .as("库节点下是「表」分组")
                .anyMatch(item -> String.valueOf(valueOf(item)).contains(I18n.t("sidebar.group.tables")));
        TreeItem auditNode = findNode(root, "audit");
        assertThat(auditNode.getChildren())
                .as("另一个库的分组也在它自己下面")
                .anyMatch(item -> String.valueOf(valueOf(item)).contains(I18n.t("sidebar.group.tables")));
    }

    /**
     * ⭐ U1：没有 catalog 的连接保持老形态 —— 分组直接挂连接下，
     * 不会因为别的连接有库一层就跟着变。
     */
    @Test
    void connectionsWithoutCatalogsKeepTheOldShape() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile demo = profile("demo", "演示库");

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", List.of(demo), p -> { }));
        onFxRun(() -> sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeItem root = rootOf(treeOf(sideBar));
        TreeItem connection = (TreeItem) root.getChildren().getFirst();
        assertThat(connection.getChildren())
                .as("SQLite 没有 catalog —— 「表」分组直接挂在连接下")
                .anyMatch(item -> String.valueOf(valueOf(item)).contains(I18n.t("sidebar.group.tables")));
    }

    /**
     * ⭐ R43-a：连接节点顶的是**厂商图形**（与向导磁贴同一枚、同一个品牌色作用域类）。
     *
     * <p>这条断的是接线，不是图形画得好不好：树里过去人人都是同一颗插头，
     * 「这家是 MySQL 那家是 Oracle」在左树完全读不出来 —— 而左树是用户看数据库
     * 的第一眼。登记行、作用域类、上色规则三处任何一处漏了，症状都是
     * 「界面照常能用，只是信息没了」，所以必须钉在断言里。
     *
     * <p>⚠️ 断的是<b>渲染出来的 cell 里的图标容器</b>，不是 {@code DemoNode} 的取值：
     * 变异验证发现，只读节点 toString 时「单元格工厂忘了挂类」照样全绿 ——
     * 数据在节点里，类却没上到界面上。取值对不对由上面那些用例管，这里只管画出来。
     */
    @Test
    void connectionNodesWearTheirVendorGlyph() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());
        ConnectionProfile oracle = new ConnectionProfile("srv-2", "老库",
                DialectKind.ORACLE, "10.0.0.9", 1521, "orcl", "scott", false, 0, "", Map.of());

        TreeView tree = themedTree(actions, List.of(mysql, oracle));

        assertThat(iconClasses(tree, "生产库"))
                .as("厂商作用域类没挂到图标容器上")
                .contains("adb-db--mysql");
        assertThat(iconClasses(tree, "老库"))
                .as("每家必须是自己的类 —— 挂错一家比不挂更糟（颜色会说谎）")
                .contains("adb-db--oracle");

        assertThat(brandFill(tree, "生产库"))
                .as("品牌色要真的落到填充层：类挂上 ≠ 后代规则匹配到节点")
                .isEqualTo(Color.web(Tokens.VENDOR_MYSQL_LIGHT));
        assertThat(brandFill(tree, "老库"))
                .isEqualTo(Color.web(Tokens.VENDOR_ORACLE_LIGHT));
    }

    /** 认不出方言时不许挑一家画上去 —— 那是编造，回落到中性插头。 */
    @Test
    void aConnectionWithoutAProfileKeepsTheNeutralPlug() {
        ActionRegistry actions = new ActionRegistry();
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "临时连接",
                tab -> { }, message -> { }, "adhoc", List.of(), p -> { }));

        String node = String.valueOf(valueOf(rootOf(treeOf(sideBar)).getChildren().get(0)));
        assertThat(node)
                .as("没有连接档案、也没有方言解析器 → CONNECT 插头，不带任何 adb-db--* 类")
                .contains("CONNECT")
                .doesNotContain("adb-db--");
    }

    /** ⭐ U1：选中一个库节点 → 去读**那个库**的对象（Serve 它的是虚拟连接）。 */
    @Test
    void selectingACatalogNodeLoadsThatCatalogsObjects() {
        ActionRegistry actions = new ActionRegistry();
        List<String> loaded = new ArrayList<>();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "srv-1", List.of(mysql), p -> { }));
        sideBar.setOnLoadObjects(loaded::add);
        onFxRun(() -> sideBar.setCatalogs("srv-1", List.of(
                new SideBar.CatalogEntry("orders", "srv-1"),
                new SideBar.CatalogEntry("audit", "srv-1@audit"))));

        // ⚠️ tree 要在 FX 线程外取：treeOf() 内部要走一次 FX 队列往返，
        //    在 onFxRun 的 lambda（已经在 FX 线程）里再调它，内层 runLater
        //    永远排不上队，lookup 会返回 null。
        TreeView tree = treeOf(sideBar);
        TreeItem audit = findNode(rootOf(tree), "audit");
        assertThat(audit).isNotNull();
        onFxRun(() -> tree.getSelectionModel().select(audit));

        assertThat(loaded)
                .as("点 audit 库 → 读 srv-1@audit 这个虚拟连接的对象")
                .containsExactly("srv-1@audit");
    }

    /** ⭐ U1：右键「切换到此数据库」→ 外壳收到 (Serve 连接的 id, 库名)。 */
    @Test
    void switchCatalogActionCarriesTheServingConnection() {
        ActionRegistry actions = new ActionRegistry();
        List<SideBar.CatalogSwitch> switches = new ArrayList<>();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "srv-1", List.of(mysql), p -> { }));
        sideBar.setOnSwitchCatalog(switches::add);
        onFxRun(() -> sideBar.setCatalogs("srv-1", List.of(
                new SideBar.CatalogEntry("orders", "srv-1"),
                new SideBar.CatalogEntry("audit", "srv-1@audit"))));

        TreeView tree = treeOf(sideBar);
        TreeItem audit = findNode(rootOf(tree), "audit");
        onFxRun(() -> {
            tree.getSelectionModel().select(audit);
            sideBar.actionForTest("object.switch-catalog");
        });

        assertThat(switches).hasSize(1);
        assertThat(switches.getFirst().connectionId()).isEqualTo("srv-1@audit");
        assertThat(switches.getFirst().catalog()).isEqualTo("audit");
    }

    /** ⭐ U1：切库动作只在库节点上放行 —— 在表节点上必须是禁用的。 */
    @Test
    void switchCatalogActionIsDisabledOutsideCatalogNodes() {
        ActionRegistry actions = new ActionRegistry();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "srv-1", List.of(mysql), p -> { }));
        onFxRun(() -> sideBar.setObjects("srv-1", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        TreeItem users = findTable(rootOf(tree), "users");
        onFxRun(() -> tree.getSelectionModel().select(users));

        assertThat(actions.find("object.switch-catalog").orElseThrow().isEnabled())
                .as("表节点上没有「切换到此数据库」").isFalse();
    }

    // ───────────────────────────── S-3：展开出列 ─────────────────────

    /**
     * ⭐ S-3：没接「读列」回调的侧栏，表节点必须还是叶子。
     *
     * <p>JavaFX 只在节点<b>有子节点</b>时才画展开箭头 —— 换句话说，要让箭头
     * 出来就得先塞一个子节点。没接回调就塞一个假的，用户展开后什么都不会发生：
     * 那是一个「点开没反应」的箭头，比没有箭头更糟。
     */
    @Test
    void tableIsALeafUntilAColumnLoaderIsWired() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(profile("pg", "生产库"));

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "pg", profiles, p -> { }));
        onFxRun(() -> sideBar.setObjects("pg", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        TreeItem users = findTable(rootOf(tree), "users");

        assertThat(users.getChildren())
                .as("没接回调 —— 表节点是叶子，不摆点开没反应的箭头")
                .isEmpty();
    }

    /**
     * ⭐ S-3：展开表节点才读列（懒加载），读到就把占位节点换成真的列。
     *
     * <p>「连上就查每张表的列」在几千张表的库上会把界面卡死；Navicat 也是展开才读。
     * 这里守两件事：<b>展开之前一次都不查</b>，以及展开之后列真的出现。
     */
    @Test
    void expandingATableReadsItsColumnsLazily() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(profile("pg", "生产库"));
        List<String> asked = new ArrayList<>();

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "pg", profiles, p -> { }));
        onFxRun(() -> sideBar.setOnLoadColumns((connectionId, table) ->
                asked.add(connectionId + "·" + table)));
        onFxRun(() -> sideBar.setObjects("pg", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        TreeItem users = findTable(rootOf(tree), "users");

        assertThat(asked)
                .as("还没展开 —— 一次都不能查库")
                .isEmpty();
        assertThat(users.getChildren())
                .as("箭头得先出得来：挂一个「读取列…」占位子节点")
                .hasSize(1);
        assertThat(String.valueOf(valueOf(users.getChildren().get(0))))
                .as("占位节点要说自己正在读，而不是假装是某一列")
                .contains(I18n.t("sidebar.columnsLoading"));

        onFxRun(() -> users.setExpanded(true));
        assertThat(asked)
                .as("展开的瞬间才真去读")
                .containsExactly("pg·users");

        onFxRun(() -> sideBar.setColumns("pg", "users", List.of("id", "email")));
        assertThat(childNames(users))
                .as("读到的列顶掉占位节点")
                .containsExactly("id", "email");

        onFxRun(() -> users.setExpanded(false));
        onFxRun(() -> users.setExpanded(true));
        assertThat(asked)
                .as("列已经在手 —— 再展开一次不该重复查库")
                .hasSize(1);
    }

    /**
     * ⭐ S-3：读到的列要活过「重建树」。
     *
     * <p>搜索框每敲一个字都会重建整棵树 —— 列如果只挂在旧节点上就全没了，
     * 用户打一半搜索词就再也看不到列。缓存要在重建时被重新挂上。
     */
    @Test
    void columnsSurviveATreeRebuild() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(profile("pg", "生产库"));
        List<String> asked = new ArrayList<>();

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "pg", profiles, p -> { }));
        onFxRun(() -> sideBar.setOnLoadColumns((connectionId, table) ->
                asked.add(connectionId + "·" + table)));
        onFxRun(() -> sideBar.setObjects("pg", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        // ⚠️ rootOf / findTable 自己会调度到 FX 线程 —— 不能在 onFxRun 的
        //    lambda 里再调它们（FX 线程里等 FX 线程，拿回来是 null）。
        TreeItem beforeSearch = findTable(rootOf(tree), "users");
        onFxRun(() -> beforeSearch.setExpanded(true));
        onFxRun(() -> sideBar.setColumns("pg", "users", List.of("id", "email")));

        // 敲搜索词 → 整棵树重建（refreshTree）。
        TextField search = searchFieldOf(sideBar);
        onFxRun(() -> search.setText("use"));
        TreeItem users = findTable(rootOf(tree), "users");

        assertThat(childNames(users))
                .as("重建之后列还在（不用再查一次）")
                .containsExactly("id", "email");
        assertThat(asked)
                .as("重建不该触发新一轮查库")
                .hasSize(1);
    }

    /**
     * ⭐ S-3：列节点不是「对象」—— 它不能打开数据页。
     *
     * <p>选中一个列时，工具栏上「打开数据 / 打开结构 / 复制 SELECT」必须是灰的：
     * 这些动作只对表 / 视图成立。反过来「复制名称」对列是成立的（复制列名）。
     */
    @Test
    void columnNodesCannotOpenDataButCanCopyTheirName() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(profile("pg", "生产库"));

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "pg", profiles, p -> { }));
        onFxRun(() -> sideBar.setOnLoadColumns((connectionId, table) ->
                sideBar.setColumns(connectionId, table, List.of("id", "email"))));
        onFxRun(() -> sideBar.setObjects("pg", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        TreeItem users = findTable(rootOf(tree), "users");
        onFxRun(() -> users.setExpanded(true));
        TreeItem email = findTable(users, "email");
        onFxRun(() -> tree.getSelectionModel().select(email));

        assertThat(actions.find("object.open-data").orElseThrow().isEnabled())
                .as("列没有数据页 —— 「打开数据」必须是灰的")
                .isFalse();
        assertThat(actions.find("object.copy-select").orElseThrow().isEnabled())
                .as("列没有 SELECT 可复制")
                .isFalse();
        assertThat(actions.find("object.copy-name").orElseThrow().isEnabled())
                .as("复制列名是真需求 —— 这一项要放行")
                .isTrue();
    }

    /**
     * ⭐ S-3：读列失败要留得住「失败」这个事实，并且允许再试。
     *
     * <p>读到空清单只可能是读失败了（表不可能没有列）。这时候<b>不能</b>
     * 静默把占位节点删掉变成叶子 —— 用户会以为这张表没有列，
     * 而且再也点不出重试。失败要说出来，且再展开一次要真去重试。
     */
    @Test
    void aFailedColumnReadIsSaidOutLoudAndCanBeRetried() {
        ActionRegistry actions = new ActionRegistry();
        List<ConnectionProfile> profiles = List.of(profile("pg", "生产库"));
        List<String> asked = new ArrayList<>();

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "pg", profiles, p -> { }));
        onFxRun(() -> sideBar.setOnLoadColumns((connectionId, table) -> {
            asked.add(connectionId + "·" + table);
            // 第一次读失败（空清单），之后成功。
            sideBar.setColumns(connectionId, table,
                    asked.size() == 1 ? List.of() : List.of("id"));
        }));
        onFxRun(() -> sideBar.setObjects("pg", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE))));

        TreeView tree = treeOf(sideBar);
        TreeItem users = findTable(rootOf(tree), "users");
        onFxRun(() -> users.setExpanded(true));

        assertThat(childNames(users))
                .as("失败要说出来 —— 不是删掉占位节点装作这张表没有列")
                .containsExactly(I18n.t("sidebar.columnsFailed"));

        onFxRun(() -> users.setExpanded(false));
        onFxRun(() -> users.setExpanded(true));
        assertThat(asked)
                .as("失败不缓存 —— 再展开一次真去重试")
                .hasSize(2);
        assertThat(childNames(users))
                .as("重试成功后列就出来了")
                .containsExactly("id");
    }

    /**
     * ⭐ UX-6：连接节点下直接列「已保存查询」；双击 / 回车 = 新查询页 + 预填 SQL。
     *
     * <p>代码片段（SNIPPET）不进这个分组 —— 它是「插进当前编辑器」用的，
     * 和「载入新查询」是两种行为。
     */
    @Test
    void savedQueriesAppearUnderEveryConnectionAndOpenWithTheirSql() {
        ActionRegistry actions = new ActionRegistry();
        List<SideBar.SavedQueryRequest> opened = new ArrayList<>();
        List<ConnectionProfile> profiles = List.of(
                profile("demo", "演示库"),
                profile("other", "另一个库"));

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", profiles, p -> { }));
        onFxRun(() -> {
            sideBar.setSavedQuerySource(() -> List.of(
                    new SavedQueries.Entry("活跃用户",
                            "SELECT * FROM users WHERE active = 1;", 2L),
                    new SavedQueries.Entry("片段：计数", "COUNT(*)", 3L,
                            SavedQueries.Kind.SNIPPET)));
            sideBar.setOnOpenSavedQuery(opened::add);
        });

        TreeItem root = rootOf(treeOf(sideBar));
        TreeItem demoConnection = findExact(root, "演示库");
        TreeItem otherConnection = findExact(root, "另一个库");
        assertThat(demoConnection).isNotNull();
        assertThat(otherConnection).isNotNull();

        TreeItem demoGroup = findExact(demoConnection, I18n.t("sidebar.group.queries"));
        TreeItem otherGroup = findExact(otherConnection, I18n.t("sidebar.group.queries"));
        assertThat(demoGroup).as("连接节点下要有「已保存查询」分组").isNotNull();
        assertThat(otherGroup).as("每个连接都直接列一份").isNotNull();
        assertThat(childNames(demoGroup))
                .as("只列完整查询，代码片段不混进来")
                .containsExactly("活跃用户");

        TreeItem item = findExact(demoGroup, "活跃用户");
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> {
            tree.getSelectionModel().select(item);
            sideBar.actionForTest("saved-query.open");
        });

        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().connectionId())
                .as("查询页跟着条目所在的连接走")
                .isEqualTo("demo");
        assertThat(opened.getFirst().name()).isEqualTo("活跃用户");
        assertThat(opened.getFirst().sql()).isEqualTo("SELECT * FROM users WHERE active = 1;");
    }

    /** ⭐ UX-6：分组跟着搜索词过滤，并在保存 / 删除后刷新。 */
    @Test
    void savedQueryGroupFollowsSearchAndRefreshes() {
        ActionRegistry actions = new ActionRegistry();
        List<SavedQueries.Entry> store = new ArrayList<>(List.of(
                new SavedQueries.Entry("活跃用户", "SELECT 1;", 1L),
                new SavedQueries.Entry("月度报表", "SELECT 2;", 2L)));

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", List.of(profile("demo", "演示库")), p -> { }));
        onFxRun(() -> sideBar.setSavedQuerySource(() -> List.copyOf(store)));

        TreeItem demoConnection = findExact(rootOf(treeOf(sideBar)), "演示库");
        TreeItem group = findExact(demoConnection, I18n.t("sidebar.group.queries"));
        assertThat(childNames(group)).containsExactlyInAnyOrder("活跃用户", "月度报表");

        TextField search = searchFieldOf(sideBar);
        onFxRun(() -> search.setText("月度"));
        group = findExact(findExact(rootOf(treeOf(sideBar)), "演示库"), I18n.t("sidebar.group.queries"));
        assertThat(childNames(group))
                .as("搜索词同样过滤已保存查询")
                .containsExactly("月度报表");

        store.add(new SavedQueries.Entry("活跃用户归档", "SELECT 3;", 3L));
        onFxRun(() -> {
            search.setText("");
            sideBar.refreshSavedQueries();
        });
        group = findExact(findExact(rootOf(treeOf(sideBar)), "演示库"), I18n.t("sidebar.group.queries"));
        assertThat(childNames(group))
                .as("保存后不必重启，刷新就能看到新条目")
                .contains("活跃用户归档");

        onFxRun(() -> sideBar.setSavedQuerySource(List::of));
        assertThat(findExact(rootOf(treeOf(sideBar)), I18n.t("sidebar.group.queries")))
                .as("一条都没有时不摆空分组")
                .isNull();
    }

    /**
     * ⭐ R44 G2-a（一）：搜索的**真实作用域**是整棵树，函数也一样吃搜索词。
     *
     * <p>这条不是为了测过滤（上面几条已经测了），而是给下一条当证据：
     * 提示文字一旦点名「表、视图」，就比这里的实际行为窄 ——
     * 用户以为搜不到函数，于是不会去搜，功能等于没有。
     */
    @Test
    void searchingFiltersFunctionsJustLikeTables() {
        ActionRegistry actions = new ActionRegistry();
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", List.of(profile("demo", "演示库")), p -> { }));
        onFxRun(() -> sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("orders", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("fn_daily", SchemaService.ObjectKind.FUNCTION))));

        // ⚠️ 搜索框要在 FX 线程**外**取：searchFieldOf 内部自己走一次 FX 往返，
        //    塞进 onFxRun 的 lambda 里就是「在 FX 线程里等 FX 线程」→ 拿回 null。
        TextField search = searchFieldOf(sideBar);

        // 两个方向都要测：只测「搜函数名 → 函数在」是空转的 ——
        // 变异验证抓到过：把函数分组的搜索词换成空串（等于函数**不**过滤），
        // 那样这条仍然绿。必须再测「搜表名 → 函数得消失」。
        onFxRun(() -> search.setText("fn_daily"));
        TreeItem hitFunction = rootOf(treeOf(sideBar));
        assertThat(findExact(hitFunction, "fn_daily"))
                .as("函数名也吃搜索词")
                .isNotNull();
        assertThat(findExact(hitFunction, "orders"))
                .as("不匹配的表被滤掉（证明搜索真的在生效，而不是没重建树）")
                .isNull();

        onFxRun(() -> search.setText("orders"));
        TreeItem hitTable = rootOf(treeOf(sideBar));
        assertThat(findExact(hitTable, "orders")).isNotNull();
        assertThat(findExact(hitTable, "fn_daily"))
                .as("反过来搜表名时函数也得被滤掉 —— 函数分组吃的是同一个搜索词")
                .isNull();
    }

    /**
     * ⭐ R44 G2-a（二）：提示文字不许承诺比行为窄的作用域。
     *
     * <p>门禁的是**一句产品声明**：框里写的「搜索表、视图」把函数与已保存查询
     * 排除在外，而它们其实都搜（上一条已证）。Navicat 的框只写「Search」。
     *
     * <p>两种语言都要查：这条只在中文包上会红，英文包写 "Search" 本来就对 ——
     * 所以按 locale 各读一遍，而不是只读当前那一份。
     */
    @Test
    void theSearchPromptNamesNoObjectKind() {
        ActionRegistry actions = new ActionRegistry();
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", List.of(profile("demo", "演示库")), p -> { }));

        String english;
        String chinese;
        try {
            I18n.setLocale(java.util.Locale.ENGLISH);
            english = I18n.t("sidebar.searchPrompt");
            I18n.setLocale(java.util.Locale.CHINESE);
            chinese = I18n.t("sidebar.searchPrompt");
        } finally {
            I18n.setLocale(I18n.DEFAULT_LOCALE);
        }

        assertThat(searchFieldOf(sideBar).getPromptText())
                .as("框里用的就是这个键，不是另一处硬编码")
                .isEqualTo(english);

        for (String prompt : List.of(english, chinese)) {
            assertThat(prompt.toLowerCase(java.util.Locale.ROOT))
                    .as("点名任何一种对象 = 比真实作用域窄（函数、已保存查询也搜）")
                    .doesNotContain("table")
                    .doesNotContain("view")
                    .doesNotContain("function")
                    .doesNotContain("表")
                    .doesNotContain("视图")
                    .doesNotContain("函数");
        }
    }

    /**
     * ⭐ R44 G2-a（三）：清除一键 —— 空框时不出现，有字时一按回到全量。
     *
     * <p>「不出现」这一半也要测：一颗按了没反应的 ✕ 就是噪声（诚实条款的反面）。
     */
    @Test
    void theSearchClearButtonAppearsOnlyWithTextAndEmptiesTheFilter() {
        ActionRegistry actions = new ActionRegistry();
        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo", List.of(profile("demo", "演示库")), p -> { }));
        onFxRun(() -> sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("orders", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("audit_log", SchemaService.ObjectKind.TABLE))));

        javafx.scene.control.Button clear =
                onFx(() -> (javafx.scene.control.Button) sideBar.lookup("#sidebar-clear-search"));
        assertThat(clear).as("清除键必须在场景里").isNotNull();
        assertThat(clear.isVisible())
                .as("空框时不摆一颗按了没反应的 ✕")
                .isFalse();

        TextField search = searchFieldOf(sideBar);
        onFxRun(() -> search.setText("orders"));
        assertThat(clear.isVisible()).as("有字了就出现").isTrue();
        assertThat(findExact(rootOf(treeOf(sideBar)), "audit_log")).isNull();

        onFxRun(clear::fire);
        assertThat(search.getText()).isEmpty();
        assertThat(clear.isVisible()).as("清空后自己也要退场").isFalse();
        TreeItem root = rootOf(treeOf(sideBar));
        assertThat(findExact(root, "audit_log")).isNotNull();
        assertThat(findExact(root, "orders")).isNotNull();
    }

    /**
     * ⭐ R42 N28-a：视图节点给视图的动作，表节点给表的动作。
     *
     * <p>以前表与视图共用同一串右键菜单，于是视图上挂着「清空表 / 重命名表」——
     * 看得见、点下去只会收到一句「只有表能重命名」。这条同时钉住两面：
     * <ul>
     *   <li>视图菜单里有 design-view / drop-view，<b>没有</b>表的三项；</li>
     *   <li>表节点上视图两项变灰，且即使被程序化触发也不许发出请求 ——
     *       要落回一句解释。</li>
     * </ul>
     */
    @Test
    void viewAndTableNodesOfferTheirOwnLifecycle() {
        ActionRegistry actions = new ActionRegistry();
        List<SideBar.TableRequest> requests = new ArrayList<>();
        List<String> statuses = new ArrayList<>();

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "内存库",
                tab -> { }, statuses::add, "demo", List.of(profile("demo", "内存库")),
                p -> { }));
        onFxRun(() -> sideBar.setOnTableDdl(requests::add));
        onFxRun(() -> sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("v_users", SchemaService.ObjectKind.VIEW))));

        TreeView tree = treeOf(sideBar);
        TreeItem view = findNode(rootOf(tree), "v_users");
        assertThat(view).as("视图在对象树的视图分组里").isNotNull();
        onFxRun(() -> tree.getSelectionModel().select(view));

        assertThat(menuIds(sideBar))
                .as("视图菜单要有视图自己那两项")
                .contains("object.design-view", "object.drop-view");
        assertThat(menuIds(sideBar))
                .as("清空 / 重命名 / 删表不该出现在视图菜单里（点了只能报错）")
                .doesNotContain("object.truncate-table", "object.rename-table",
                        "object.drop-table");
        assertThat(actions.find("object.design-view").orElseThrow().isEnabled()).isTrue();

        onFxRun(() -> sideBar.actionForTest("object.drop-view"));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().action()).isEqualTo(SideBar.SideBarObject.DROP_VIEW);
        assertThat(requests.getFirst().tableName()).isEqualTo("v_users");
        assertThat(requests.getFirst().connectionId())
                .as("请求带节点自己的连接，DDL 才落得对库")
                .isEqualTo("demo");

        TreeItem users = findTable(rootOf(tree), "users");
        onFxRun(() -> tree.getSelectionModel().select(users));

        assertThat(menuIds(sideBar))
                .as("表菜单回到表那四项，视图两项不出现")
                .contains("object.truncate-table", "object.rename-table", "object.drop-table")
                .doesNotContain("object.design-view", "object.drop-view");
        assertThat(actions.find("object.drop-view").orElseThrow().isEnabled())
                .as("选中表时「删除视图」必须变灰")
                .isFalse();

        // ⭐ 变灰的动作在注册表那一层就被拦掉了（run() 对 disabled 直接不执行），
        //    所以「点了没反应」不需要额外断言。这里再验一次更狠的路径：
        //    万一 enabled 状态与选中节点不同步（命令面板那条路），
        //    分派层自己也要挡住 —— 不许拿「设计视图」去操作一张表。
        actions.require("object.design-view").setEnabled(true);
        onFxRun(() -> sideBar.actionForTest("object.design-view"));
        assertThat(requests)
                .as("灰掉的动作即使被程序化触发，也不许拿去操作一张表")
                .hasSize(1);
        assertThat(statuses).last().asString()
                .isEqualTo(I18n.t("sidebar.onlyView.design"));
    }

    /** ⭐ N28-a：容器节点（连接 / 库 / 分组）上「新建视图」与「新建表」同格可用。 */
    @Test
    void viewCreationSitsOnContainerNodesLikeTableCreation() {
        ActionRegistry actions = new ActionRegistry();
        // 连接节点的菜单里有「编辑连接」，那个动作注册在外壳不在侧栏 —— 这里补上，
        // 否则 menuFor 会在 require() 上抛「unregistered action id」。
        actions.register("new-connection", "New Connection", "test", null, () -> { });
        List<SideBar.TableRequest> requests = new ArrayList<>();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, "", Map.of());

        SideBar sideBar = onFx(() -> new SideBar(actions, List.of(), "生产库",
                tab -> { }, message -> { }, "srv-1", List.of(mysql), p -> { }));
        onFxRun(() -> sideBar.setOnTableDdl(requests::add));

        TreeView tree = treeOf(sideBar);
        TreeItem connection = findExact(rootOf(tree), "生产库");
        assertThat(connection).isNotNull();
        onFxRun(() -> tree.getSelectionModel().select(connection));

        assertThat(menuIds(sideBar))
                .as("连接节点的菜单里要能建视图（与建表并列）")
                .contains("object.new-view", "object.new-table");
        onFxRun(() -> sideBar.actionForTest("object.new-view"));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().action()).isEqualTo(SideBar.SideBarObject.NEW_VIEW);
        assertThat(requests.getFirst().tableName())
                .as("新建还没有名字 —— 名字是设计器里填的")
                .isNull();
    }

    /** 右键菜单的项 id 清单（{@code contextMenuForTest} 要先有选中节点）。 */
    private static List<String> menuIds(SideBar sideBar) {
        return onFx(() -> {
            List<String> found = new ArrayList<>();
            javafx.scene.control.ContextMenu menu = sideBar.contextMenuForTest();
            if (menu != null) {
                for (javafx.scene.control.MenuItem item : menu.getItems()) {
                    if (item.getId() != null) {
                        found.add(item.getId());
                    }
                }
            }
            return found;
        });
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    /** 原生 TreeItem 的 getChildren() 元素类型是 Object —— 统一在这里转。 */
    private static Object valueOf(Object item) {
        return ((TreeItem) item).getValue();
    }

    private static TreeItem findNode(TreeItem node, String name) {
        if (String.valueOf(node.getValue()).contains("name=" + name)) {
            return node;
        }
        for (Object child : node.getChildren()) {
            TreeItem found = findNode((TreeItem) child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 节点名**正好等于**给定值（区分「生产」和「生产库甲」）。 */
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

    private static ConnectionProfile profile(String id, String name) {
        return new ConnectionProfile(id, name, DialectKind.POSTGRESQL, "localhost", 5432,
                "db", "u", false, 0, "", java.util.Map.of());
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

    /** 一个节点的**直接子节点**的名字（S-3：看表节点下挂了哪些列）。 */
    private static List<String> childNames(TreeItem node) {
        List<String> names = new ArrayList<>();
        for (Object child : node.getChildren()) {
            names.add(nameOf(valueOf(child)));
        }
        return names;
    }

    /** 从 {@code name=xxx} 里取节点名（DemoNode 的 toString 形态）。 */
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

    private static TextField searchFieldOf(SideBar sideBar) {
        return onFx(() -> (TextField) sideBar.lookup(".adb-sidebar__search"));
    }

    /**
     * 把侧栏装进一条挂了主题样式表的链路并排版一次。
     *
     * <p>样式表必须挂在<b>场景</b>上，主题类挂在<b>根节点</b>上：两者缺一，
     * {@code -adb-*} 查找色就解析不出来，图标填充层算出来是黑的 ——
     * 断言看着「有颜色」，其实什么都没测（S-7 的老坑）。
     */
    private TreeView themedTree(ActionRegistry actions, List<ConnectionProfile> profiles) {
        SideBar sideBar = onFx(() -> {
            SideBar created = new SideBar(actions, List.of(), "生产库",
                    tab -> { }, message -> { }, "srv-1", profiles, p -> { });
            Scene scene = new Scene(created, 320, 600);
            scene.getStylesheets().add(styleUrl("/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(styleUrl("/io/agenticdb/ui/app.css"));
            created.getStyleClass().add("theme-light");
            stage.setScene(scene);
            return created;
        });
        TreeView tree = treeOf(sideBar);
        onFxRun(() -> {
            tree.applyCss();
            tree.layout();
        });
        return tree;
    }

    /** 已渲染 cell 里那枚图标容器的类名（虚拟树：没渲染就没有这条证据）。 */
    private static List<String> iconClasses(TreeView tree, String nodeName) {
        return onFx(() -> {
            Node icon = vendorIcon(renderedCell(tree, nodeName), nodeName);
            return List.copyOf(icon.getStyleClass());
        });
    }

    /** 同上，但取<b>填充层</b>的计算色 —— 类名对了不等于规则匹配到了节点。 */
    private static Color brandFill(TreeView tree, String nodeName) {
        return onFx(() -> {
            Node icon = vendorIcon(renderedCell(tree, nodeName), nodeName);
            icon.applyCss();
            List<Node> tones = new ArrayList<>();
            collectByClass(icon, "adb-icon-tone-c", tones);
            assertThat(tones)
                    .as("%s 的彩色资产要有且只有一条 tone-c 层", nodeName)
                    .hasSize(1);
            return (Color) ((SVGPath) tones.get(0)).getFill();
        });
    }

    private static Node vendorIcon(TreeCell cell, String nodeName) {
        assertThat(cell).as("%s 必须已渲染成 cell（没渲染就没图标可查）", nodeName).isNotNull();
        List<Node> found = new ArrayList<>();
        collectByClass(cellGraphic(cell), "adb-tree__icon", found);
        assertThat(found).as("%s 的图标节点", nodeName).hasSize(1);
        return found.get(0);
    }

    private static Node cellGraphic(TreeCell cell) {
        return cell instanceof Labeled labeled && labeled.getGraphic() instanceof Node graphic
                ? graphic : cell;
    }

    private static TreeCell renderedCell(Node node, String nodeName) {
        if (node instanceof TreeCell cell && cell.getItem() != null
                && nameOf(cell.getItem()).equals(nodeName)) {
            return cell;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TreeCell found = renderedCell(child, nodeName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectByClass(Node node, String styleClass, List<Node> out) {
        if (node.getStyleClass().contains(styleClass)) {
            out.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectByClass(child, styleClass, out);
            }
        }
    }

    private static String styleUrl(String resource) {
        URL url = SideBarTreeTest.class.getResource(resource);
        assertThat(url).as("样式表 %s", resource).isNotNull();
        return url.toExternalForm();
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
