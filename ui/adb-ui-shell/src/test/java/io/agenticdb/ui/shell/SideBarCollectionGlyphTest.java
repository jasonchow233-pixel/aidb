package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.Icons;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.shape.SVGPath;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R62-a H6-g：树上的**集合行必须画自己的字形**。
 *
 * <p>这条账原来是这样：{@code SideBar.groupIcon} 对 TABLE / VIEW / FUNCTION 直接
 * 返回叶子那三枚图标，于是「Tables」这一行画的就是一个表，它下面每一行也画一个表 ——
 * 父与子同形，「这一行是集合还是对象」只能靠缩进和展开箭头读，扫视时每次都要停一下。
 *
 * <p>门禁读<b>两个通道</b>，缺一个都会假绿：
 * <ol>
 *   <li><b>场景图</b>：那一格真的画出来的路径几何（{@code SVGPath#getContent()}，
 *       彩色档拼子层）—— 这才是用户看到的；</li>
 *   <li><b>模型</b>：每一格用的是哪一枚图标（{@code icon=} 字段名）—— 几何那条抓不到
 *       「退回一枚没和叶子撞车的通用容器」（例如连接分组换回 {@code FOLDER}），
 *       而按 H6-g 的账，那一格和功能区「其他」不许是同一个图形。</li>
 * </ol>
 *
 * <p>⚠️ 根节点「我的连接」与库节点**故意**共用 {@code DATABASE} 一枚（两者都是
 * 「一个数据库容器」，R54 定的，不在这次账上），所以集合这一侧只算
 * {@code GROUP / CONNECTION_GROUP / SAVED_GROUP} 三种。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class SideBarCollectionGlyphTest {

    /** 集合行（这次要钉的三类；根节点见类注释）。 */
    private static final Set<String> COLLECTION_KINDS =
            Set.of("GROUP", "CONNECTION_GROUP", "SAVED_GROUP");

    private static final Pattern KIND = Pattern.compile("kind=([A-Z_]+)");
    private static final Pattern ICON = Pattern.compile("icon=([A-Z_]+)");
    private static final Pattern NAME = Pattern.compile("name=(.*?), kind=");

    private SideBar sideBar;

    @Start
    void start(Stage stage) {
        // @Start 就跑在 FX 线程上，注入数据不必再绕一次线程。
        ActionRegistry actions = new ActionRegistry();
        sideBar = new SideBar(actions, List.of(), "演示库", tab -> { }, message -> { },
                "demo", List.of(profile("demo", "演示库"),
                        profile("prod-1", "生产库甲").withGroup("生产"),
                        profile("prod-2", "生产库乙").withGroup("生产")), p -> { });
        sideBar.setObjects("demo", List.of(
                new SchemaService.SchemaObject("users", SchemaService.ObjectKind.TABLE),
                new SchemaService.SchemaObject("v_users", SchemaService.ObjectKind.VIEW),
                new SchemaService.SchemaObject("fn_daily", SchemaService.ObjectKind.FUNCTION)));
        sideBar.setSavedQuerySource(() -> List.of(
                new SavedQueries.Entry("晨报", "SELECT 1;", 1L)));

        Scene scene = new Scene(sideBar, 320, 700);
        scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/theme-light.css")
                .toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/app.css")
                .toExternalForm());
        sideBar.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * 集合行的字形与叶子行的字形必须是两批几何，五枚集合字形彼此也不同。
     *
     * <p>读的是**渲染出来的路径串**，不是枚举名 —— 枚举名不同而几何相同（两枚不同的名字
     * 指向同一个形状）照样是用户眼里的那个「父子同形」，只有几何能抓到。
     */
    @Test
    void collectionRowsDrawGlyphsNoLeafRowUses() {
        List<List<String>> rows = onFx(this::readRows);
        List<List<String>> collections = rows.stream()
                .filter(row -> COLLECTION_KINDS.contains(row.get(0))).toList();
        List<List<String>> leaves = rows.stream()
                .filter(row -> !COLLECTION_KINDS.contains(row.get(0))).toList();

        // ⭐ 数的是**去重后的行身份**，不是渲染出来的格数。
        //    「Queries」这一组挂在**每个连接**下面（savedQuerySource 是全局的），
        //    三个连接就是三行同名同图形的集合行 —— 直接 hasSize(5) 会在 7 行上红，
        //    而红的原因是「这一组本来就该出现三次」，不是「字形画错了」。
        Set<String> identities = new LinkedHashSet<>();
        for (List<String> row : collections) {
            identities.add(row.get(0) + " " + row.get(1));
        }
        assertThat(identities)
                .as("五类集合行都得真的渲染出来（读不到就是这条门禁空转）—— 实到 %s 类 / %s 行：%s",
                        identities.size(), collections.size(), describe(collections))
                .containsExactlyInAnyOrder("GROUP Tables", "GROUP Views", "GROUP Functions",
                        "SAVED_GROUP Queries", "CONNECTION_GROUP 生产");
        assertThat(leaves)
                .as("必须至少有一行叶子参与对照 —— 没有叶子，「父与子同形」根本无从比对")
                .isNotEmpty();

        Set<String> collectionShapes = new LinkedHashSet<>();
        for (List<String> row : collections) {
            assertThat(row.get(2))
                    .as("%s 行没画出图形（读到空串，下面的不相交就成空转了）", row.get(1))
                    .isNotBlank();
            collectionShapes.add(row.get(2));
        }
        assertThat(collectionShapes)
                .as("五枚集合字形彼此必须不同形 —— 五格画成同一枚，等于把三种对象读成一种")
                .hasSize(5);

        Set<String> leafShapes = new LinkedHashSet<>();
        for (List<String> row : leaves) {
            if (!row.get(2).isBlank()) {
                leafShapes.add(row.get(2));
            }
        }
        assertThat(leafShapes)
                .as("叶子行也要真的画出图形（对照侧空了，那条不相交就是空的）")
                .isNotEmpty();

        List<String> collisions = new ArrayList<>();
        for (List<String> row : collections) {
            if (leafShapes.contains(row.get(2))) {
                collisions.add(row.get(1) + "（" + row.get(3) + "）");
            }
        }
        assertThat(collisions)
                .as("这些集合行画的还是它孩子那枚字形 —— H6-g 要修的就是这一条")
                .isEmpty();
    }

    /** 第二个通道：五枚集合字形各自是谁，钉在模型上（理由见类注释）。 */
    @Test
    void theFiveCollectionGlyphsAreTheNamedOnes() {
        List<List<String>> rows = onFx(this::readRows);
        Set<String> drawn = new LinkedHashSet<>();
        for (List<String> row : rows) {
            if (COLLECTION_KINDS.contains(row.get(0))) {
                drawn.add(row.get(3));
            }
        }
        assertThat(drawn)
                .as("集合行必须就用这五枚 GROUP_*（名字重复说明两格共用了同一枚）—— 实到：%s",
                        describe(rows))
                .containsExactlyInAnyOrder("GROUP_TABLES", "GROUP_VIEWS", "GROUP_FUNCTIONS",
                        "GROUP_QUERIES", "GROUP_CONNECTIONS");
    }

    /**
     * ⭐ R62-a 开工前定的第③条：集合字形<b>保持单色</b>。
     *
     * <p>分组节点拿不到对象类型（{@code iconStyleClass} 按 kind 分色，GROUP 只有一档灰），
     * 给它上色等于宣告「集合自成一类对象」—— 而集合不是对象。
     * 这条钉的是「别哪天顺手给它们画了彩色资产」：{@code icons/color/} 里一旦冒出同名文件，
     * {@code Icons.colored} 会自动分层上色，界面上没人会提醒。
     */
    @Test
    void theCollectionGlyphsHaveNoColorVariant() {
        for (Icons.Icon icon : List.of(Icons.Icon.GROUP_TABLES, Icons.Icon.GROUP_VIEWS,
                Icons.Icon.GROUP_FUNCTIONS, Icons.Icon.GROUP_QUERIES,
                Icons.Icon.GROUP_CONNECTIONS)) {
            assertThat(Icons.hasColoredVariant(icon))
                    .as("%s 是集合字形，按定案③只能单色", icon)
                    .isFalse();
        }
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    /**
     * 每一行读到 {@code [kind, 行文字, 画出来的几何, 图标名]}（只含<b>已渲染</b>的格）。
     *
     * <p>⚠️ 先递归展开再量：{@code TreeView} 的 cell 是虚拟的，折叠分支里的行一个都不渲染，
     * {@code lookupAll(".tree-cell")} 就只拿到最上面那几行 —— 那时「五格都要读到」会红，
     * 而红的原因是「没渲染」不是「画错了」。
     */
    private List<List<String>> readRows() {
        TreeView tree = (TreeView) sideBar.lookup(".adb-object-tree");
        if (tree == null) {
            return List.of();
        }
        expandAll(tree.getRoot());
        tree.getScene().getRoot().applyCss();
        tree.getScene().getRoot().layout();
        List<List<String>> out = new ArrayList<>();
        for (Node node : tree.lookupAll(".tree-cell")) {
            if (!(node instanceof TreeCell cell)) {
                continue;
            }
            Object item = cell.getItem();
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item);
            Matcher kind = KIND.matcher(text);
            Matcher icon = ICON.matcher(text);
            if (!kind.find() || !icon.find()) {
                continue;
            }
            Matcher name = NAME.matcher(text);
            out.add(List.of(kind.group(1),
                    name.find() ? name.group(1) : String.valueOf(cell.getIndex()),
                    glyphOf(cell), icon.group(1)));
        }
        return out;
    }

    /** 那一格真正画出来的路径串（彩色档拼子层；没有图形返回空串）。 */
    private static String glyphOf(TreeCell cell) {
        if (!(cell.getGraphic() instanceof HBox box)) {
            return "";
        }
        Node icon = box.lookup(".adb-tree__icon");
        if (icon instanceof SVGPath path) {
            return String.valueOf(path.getContent());
        }
        if (icon instanceof Group group) {
            StringBuilder joined = new StringBuilder();
            for (Node child : group.getChildren()) {
                if (child instanceof SVGPath path) {
                    joined.append(path.getContent());
                }
            }
            return joined.toString();
        }
        return "";
    }

    private static void expandAll(TreeItem item) {
        item.setExpanded(true);
        for (Object child : item.getChildren()) {
            expandAll((TreeItem) child);
        }
    }

    private static String describe(List<List<String>> rows) {
        StringBuilder text = new StringBuilder();
        for (List<String> row : rows) {
            text.append('[').append(row.get(0)).append(' ').append(row.get(1))
                    .append(" → ").append(row.get(3)).append("] ");
        }
        return text.toString();
    }

    private static ConnectionProfile profile(String id, String name) {
        return new ConnectionProfile(id, name, DialectKind.SQLITE, "localhost", 0,
                "db", "u", false, 0, "", java.util.Map.of());
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
