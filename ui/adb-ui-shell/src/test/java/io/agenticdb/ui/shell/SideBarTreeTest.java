package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.session.ConnectionProfile;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.stage.Stage;

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

    /**
     * ⭐ S3：过程 / 函数 / 触发器也进对象树。
     *
     * <p>触发器用真 SQLite 建一个 —— JDBC 元数据没有触发器列表，
     * 这条路径走的是方言给的 {@code triggersListSql}。
     */
    @Test
    void triggersShowUpInTheTree() throws Exception {
        io.agenticdb.jdbc.DbSession session = io.agenticdb.jdbc.JdbcSession.open(
                io.agenticdb.jdbc.ConnectionSpec.sqliteMemory());
        try (java.sql.Statement statement = session.connection().createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER)");
            statement.execute("CREATE TRIGGER trg_after_insert AFTER INSERT ON t BEGIN"
                    + " SELECT 1; END");
        }
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
        assertThat(String.join(" | ", names))
                .contains("name=trg_after_insert")
                .contains("name=触发器");
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
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, Map.of());

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
                .anyMatch(item -> String.valueOf(valueOf(item)).contains("表"));
        TreeItem auditNode = findNode(root, "audit");
        assertThat(auditNode.getChildren())
                .as("另一个库的分组也在它自己下面")
                .anyMatch(item -> String.valueOf(valueOf(item)).contains("表"));
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
                .anyMatch(item -> String.valueOf(valueOf(item)).contains("表"));
    }

    /** ⭐ U1：选中一个库节点 → 去读**那个库**的对象（Serve 它的是虚拟连接）。 */
    @Test
    void selectingACatalogNodeLoadsThatCatalogsObjects() {
        ActionRegistry actions = new ActionRegistry();
        List<String> loaded = new ArrayList<>();
        ConnectionProfile mysql = new ConnectionProfile("srv-1", "生产库",
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, Map.of());

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
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, Map.of());

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
                DialectKind.MYSQL, "10.0.0.8", 3306, "orders", "root", false, 0, Map.of());

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
                .contains("读取列");

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
                .containsExactly("列读取失败（展开重试）");

        onFxRun(() -> users.setExpanded(false));
        onFxRun(() -> users.setExpanded(true));
        assertThat(asked)
                .as("失败不缓存 —— 再展开一次真去重试")
                .hasSize(2);
        assertThat(childNames(users))
                .as("重试成功后列就出来了")
                .containsExactly("id");
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
