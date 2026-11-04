package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.Tokens;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ UX-5：侧栏连接存活点的门禁。
 *
 * <p>Navicat 的对象树在连接名旁边挂一个状态点：绿=已连上、灰=没连。
 * 我们以前只有应用栏那颗点 —— 侧栏里三个连接**并排站着，看不出哪个是活的**，
 * 要判断只能双击试一下（失败才知道没连）。
 *
 * <p>两条不可让的规矩：
 * <ul>
 *   <li>判据只能来自 {@code SessionManager}（外壳注入）—— 侧栏没有会话，
 *       它自己判断就是第二套真相；</li>
 *   <li>没注入判据时**不画点**，而不是画一个灰点 —— 灰点是「这个连接离线」这个
 *       陈述，而侧栏并不知道这件事。界面不许编造状态。</li>
 * </ul>
 *
 * <p>⚠️ 断言读的是**计算后的 fill**（S-7 的教训：只看样式类，等于只看「有人给它
 * 起了个名字」，CSS 里那条规则写错成灰色时门禁照样绿）。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(ApplicationExtension.class)
class SideBarLivenessDotTest {

    private SideBar sideBar;

    @Start
    void start(Stage stage) {
        ActionRegistry actions = new ActionRegistry();
        sideBar = new SideBar(actions, List.of(), "演示库", tab -> { }, message -> { },
                "demo", List.of(profile("demo", "演示库"), profile("other", "另一个库")),
                profile -> { });
        // 只有 demo 是活的 —— 这正是「点」要表达的差别。
        sideBar.setConnectionLiveness(id -> "demo".equals(id));

        Scene scene = new Scene(sideBar, 300, 600);
        scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/theme-light.css")
                .toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/app.css")
                .toExternalForm());
        sideBar.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void anOpenConnectionGetsTheSuccessDot() {
        SVGPath dot = dotOf("demo");
        assertThat(dot).as("demo 连接格必须有状态点").isNotNull();
        assertThat(dot.getStyleClass())
                .as("活连接用 online 类（与应用栏同一套状态词汇）")
                .contains("adb-tree__dot--online");
        assertThat(dot.getFill())
                .as("计算出来的必须是 success 绿；样式类挂上了但颜色错 = 用户看到的还是灰点")
                .isEqualTo(Color.web(Tokens.SUCCESS_LIGHT));
    }

    @Test
    void aClosedConnectionGetsTheMutedDot() {
        SVGPath dot = dotOf("other");
        assertThat(dot).as("另一个连接也要有状态点（差别才是信息）").isNotNull();
        assertThat(dot.getStyleClass())
                .contains("adb-tree__dot--offline")
                .doesNotContain("adb-tree__dot--online");
        assertThat(dot.getFill())
                .as("离线点是 text-muted 灰，不是 success 绿")
                .isEqualTo(Color.web(Tokens.TEXT_MUTED_LIGHT));
    }

    /**
     * ⭐ H5-l（R62-a）：状态点是**图标的角标**，不是名字前面多出的一列。
     *
     * <p>钉三件事（都是几何，不是颜色）：
     * ① 内容盒只有两个孩子（图形 + 名字）—— 点以前是第三个孩子，夹在中间，
     *    于是连接名比同深度的别的行多缩一档，扫视时读成「不同层级」；
     * ② 图形槽的宽度就是图标本身（{@code SIZE_LABEL}）—— 挂上点不许把槽撑宽；
     * ③ 点的圆心落在图标的**右下四分之一**（量的 {@code getBoundsInParent} 的
     *    minX/minY，相对图形槽）—— 居中挂点等于压在图形身上，读成「图形缺了一块」。
     */
    @Test
    void theBadgeSitsOnTheGlyphInsteadOfInFrontOfTheName() {
        List<Object> measured = onFx(() -> {
            HBox box = connectionCellGraphic(sideBar, "demo");
            if (box == null) {
                return List.of();
            }
            SVGPath dot = dotIn(box);
            Node lead = box.getChildren().isEmpty() ? null : box.getChildren().get(0);
            if (dot == null || lead == null) {
                return List.of();
            }
            return List.of(box.getChildren().size(), lead.prefWidth(-1),
                    dot.getBoundsInParent().getMinX(), dot.getBoundsInParent().getMinY(),
                    lead.getBoundsInLocal().getWidth(), lead.getBoundsInLocal().getHeight());
        });
        assertThat(measured)
                .as("连接格必须渲染出「图形 + 状态点」（拿不到就是这条门禁空转）")
                .isNotEmpty();
        assertThat(((Number) measured.get(0)).intValue())
                .as("内容盒只能有图形和名字两个孩子 —— 点不再占中间那一列")
                .isEqualTo(2);
        assertThat(((Number) measured.get(1)).doubleValue())
                .as("图形槽宽必须还是图标本身的 %s px，挂上点不许撑宽", io.agenticdb.ui.Icons.SIZE_LABEL)
                .isEqualTo(io.agenticdb.ui.Icons.SIZE_LABEL);
        double leadW = ((Number) measured.get(4)).doubleValue();
        double leadH = ((Number) measured.get(5)).doubleValue();
        double dotX = ((Number) measured.get(2)).doubleValue();
        double dotY = ((Number) measured.get(3)).doubleValue();
        assertThat(dotX)
                .as("点的盒子必须落在图标右半边（x 实测 %s / 槽宽 %s）", dotX, leadW)
                .isGreaterThan(leadW / 2);
        assertThat(dotY)
                .as("点必须落在图标下半部分（y 实测 %s / 槽高 %s）", dotY, leadH)
                .isGreaterThan(leadH / 2);
    }

    /** 没有判据 = 没有状态 = 不画点。 */
    @Test
    void aSidebarWithoutALivenessJudgeDrawsNoDot() {
        ActionRegistry actions = new ActionRegistry();
        SideBar unwired = onFx(() -> new SideBar(actions, List.of(), "演示库",
                tab -> { }, message -> { }, "demo",
                List.of(profile("demo", "演示库")), profile -> { }));
        // ⚠️ 必须**放进场景并显示**：不在场景图里的 TreeView 一个 cell 都不会渲染，
        //    lookupAll 永远返回空 —— 那条断言就成了「测了个不存在的东西」的假绿。
        Stage extra = onFx(() -> {
            Scene scene = new Scene(unwired, 300, 600);
            scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/theme-light.css")
                    .toExternalForm());
            scene.getStylesheets().add(getClass().getResource("/io/agenticdb/ui/app.css")
                    .toExternalForm());
            unwired.getStyleClass().add("theme-light");
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            return stage;
        });
        try {
            HBox graphic = onFx(() -> connectionCellGraphic(unwired, "demo"));
            // 先证明「这一格真的渲染出来了」，再证明它没有点。
            assertThat(graphic).as("连接格必须已渲染 —— 否则下面那条断言是空的").isNotNull();
            assertThat(dotIn(graphic))
                    .as("外壳没注入存活判据时不许画点 —— 灰点是一句「它离线」的陈述，而侧栏并不知道")
                    .isNull();
        } finally {
            onFxRun(extra::close);
        }
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    /** 某个连接格（connectionId 匹配）的内容盒；没渲染出来返回 {@code null}。 */
    private static HBox connectionCellGraphic(SideBar bar, String connectionId) {
        TreeView tree = (TreeView) bar.lookup(".adb-object-tree");
        if (tree == null) {
            return null;
        }
        for (Node node : tree.lookupAll(".tree-cell")) {
            if (!(node instanceof TreeCell)) {
                continue;
            }
            TreeCell cell = (TreeCell) node;
            Object value = cell.getTreeItem() == null ? null : cell.getTreeItem().getValue();
            // ⚠️ 只认 CONNECTION 格：ROOT（「我的连接」）也挂 adb-tree-cell--connection，
            //    但它没有 connectionId —— 匹配串不能命中它。
            //    ⚠️ 边界是 `,` 或 `]` 而**不是**「connectionId 在末尾」：R37 去掉尾部的
            //    count 时这里红过一次，R43-a 加 vendorClass 又红一次 —— 靠 record 的
            //    字段顺序找节点，等于每加一个字段就要重写一次门禁。按值匹配，字段随它加。
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (!text.contains("kind=CONNECTION")
                    || !text.matches("(?s).*connectionId=" + java.util.regex.Pattern
                            .quote(connectionId) + "[,\\]].*")) {
                continue;
            }
            return cell.getGraphic() instanceof HBox box ? box : null;
        }
        return null;
    }

    /**
     * 内容盒里的状态点；没有返回 {@code null}。
     *
     * <p>⭐ R62-a H5-l：点从「图标与名字之间」挪到**图标右下角**之后，它不再是这个
     * HBox 的直接子节点 —— 所以这里改成整棵子树里找。⚠️ 只改直接子节点那一层是不够的：
     * 挪了位置还只看 {@code box.getChildren()}，两条状态点门禁会一起变成「读不到点」，
     * 而它们恰好都「通过」（一条要 null、一条要非 null 的那条直接红，另一条却假绿）。
     */
    private static SVGPath dotIn(HBox box) {
        for (Node node : box.lookupAll(".adb-tree__dot")) {
            if (node instanceof SVGPath path) {
                return path;
            }
        }
        return null;
    }

    /** {@link #sideBar}（@Start 里已注入判据）中某个连接的状态点。 */
    private SVGPath dotOf(String connectionId) {
        HBox graphic = onFx(() -> connectionCellGraphic(sideBar, connectionId));
        return graphic == null ? null : onFx(() -> dotIn(graphic));
    }

    private static ConnectionProfile profile(String id, String name) {
        return new ConnectionProfile(id, name, DialectKind.SQLITE, "localhost", 0,
                "db", "u", false, 0, "", java.util.Map.of());
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
