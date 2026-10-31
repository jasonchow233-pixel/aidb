package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.ui.Tokens;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R35 N4-b 门禁：彩色图标的**类型色**必须真的落到轮廓层上。
 *
 * <h2>为什么这条必须单独钉</h2>
 * 彩色分层通道（{@code icons/color/*.svg} → {@link Icons#colored} → {@code .adb-icon-*}）
 * 把「一个 SVGPath」换成了「一个 Group 加几条分层路径」。
 * 于是<b>原先挂在单个路径上的类型色类</b>（{@code .adb-tree__icon--table} 这种）
 * 落点变成了<b>容器</b> —— 而 JavaFX 的 {@code Group} 没有任何可声明的绘制属性，
 * 那条 {@code -fx-stroke} 打在容器上就是<b>空转</b>。
 *
 * <p>空转的症状不是报错，是<b>悄悄变色</b>：表 / 视图 / 触发器的轮廓全统一成
 * {@code -adb-icon-edge}，颜色这条线索整条消失，界面照样能看、能点、能过编译。
 * 「形状 + 颜色是一对线索」（{@code docs/UI-GUIDE.md}）—— 彩色档没有资格删掉其中一条。
 *
 * <h2>断言口径</h2>
 * 读<b>计算色</b>（{@code SVGPath#getStroke()}），不读 CSS 文本：
 * CSS 里写着那条后代规则 ≠ 它匹配到了节点（选择器写错、类名挂错层、
 * 特异性被盖住，文本审计全都看不出来）。
 */
@ExtendWith(ApplicationExtension.class)
class IconKindHueTest {

    @Start
    void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 240, 160));
        stage.show();
    }

    @Test
    void coloredTreeIconsKeepTheirKindHue() {
        assertThat(edgeStroke(Icons.Icon.TABLE, "adb-tree__icon--table", false))
                .as("表：轮廓层吃 -adb-kind-table")
                .isEqualTo(Color.web(Tokens.KIND_TABLE_LIGHT));
        assertThat(edgeStroke(Icons.Icon.VIEW_OBJECT, "adb-tree__icon--view", false))
                .as("视图：轮廓层吃 -adb-kind-view")
                .isEqualTo(Color.web(Tokens.KIND_VIEW_LIGHT));
        assertThat(edgeStroke(Icons.Icon.TRIGGER, "adb-tree__icon--trigger", false))
                .as("触发器：轮廓层吃 -adb-kind-trigger")
                .isEqualTo(Color.web(Tokens.KIND_TRIGGER_LIGHT));
        assertThat(edgeStroke(Icons.Icon.INDEX, "adb-structure__index-icon", false))
                .as("索引（结构页）：轮廓层吃 -adb-kind-index")
                .isEqualTo(Color.web(Tokens.KIND_INDEX_LIGHT));
    }

    @Test
    void theToneLayersStayNeutralSoTheKindHueIsTheOnlySemanticChannel() {
        // 三层 tone 必须保持中性浅蓝：类型色只走轮廓这一条道，
        // 否则同一个对象会有两处颜色来源，改一处漏一处。
        List<Color> tones = toneFills(Icons.Icon.TABLE, "adb-tree__icon--table");
        assertThat(tones)
                .as("tone 层不能吃类型色")
                .allSatisfy(paint -> assertThat(paint).isNotEqualTo(Color.web(Tokens.KIND_TABLE_LIGHT)));
        assertThat(tones).as("彩色资产至少要有两层填充").isNotEmpty();
    }

    @Test
    void anIconWithoutAKindClassFallsBackToTheIdentityEdgeColor() {
        // 页签 / 标题栏不吃类型色 —— 它们表达的是「页签类型」不是「对象类型」，
        // 没有类名时轮廓必须是身份色，不能是黑的（黑色在暗色档等于隐形）。
        assertThat(edgeStroke(Icons.Icon.TABLE, "adb-tab__icon", false))
                .isEqualTo(Color.web(Tokens.ICON_EDGE_LIGHT));
        assertThat(edgeStroke(Icons.Icon.TABLE, "adb-tab__icon", true))
                .as("暗色档：同一张资产自动换色 —— 这正是分层通道要解决的 P3")
                .isEqualTo(Color.web(Tokens.ICON_EDGE_DARK));
    }

    @Test
    void theKindHueFollowsTheThemeWithoutASecondAssetSet() {
        assertThat(edgeStroke(Icons.Icon.TABLE, "adb-tree__icon--table", true))
                .as("暗色档的类型色")
                .isEqualTo(Color.web(Tokens.KIND_TABLE_DARK));
    }

    @Test
    void ribbonCommandsKeepTheirToneOnTheEdgeLayer() {
        // R35 N4-a：功能区那四条命令色走的是同一套「宿主类 + 后代 .adb-icon-edge」规则。
        // 单类那条 .adb-ribbon__icon--table 挂在 Group 上是**死的** —— 这条门禁防的正是
        // 「把死规则当成生效规则，彩色档静默掉回身份色」（第一批就是这么把类型色弄丢的）。
        assertThat(edgeStroke(Icons.Icon.ADD, "adb-ribbon__icon--table", false))
                .as("新建表：轮廓吃 -adb-kind-table")
                .isEqualTo(Color.web(Tokens.KIND_TABLE_LIGHT));
        assertThat(edgeStroke(Icons.Icon.VIEW, "adb-ribbon__icon--view", false))
                .as("视图：轮廓吃 -adb-kind-view")
                .isEqualTo(Color.web(Tokens.KIND_VIEW_LIGHT));
        assertThat(edgeStroke(Icons.Icon.FUNCTION, "adb-ribbon__icon--function", false))
                .as("函数：轮廓吃 -adb-kind-function")
                .isEqualTo(Color.web(Tokens.KIND_FUNCTION_LIGHT));
        assertThat(edgeStroke(Icons.Icon.QUERY, "adb-ribbon__icon--query", true))
                .as("暗色档：新建查询的轮廓跟着主题走，不出第二套资产")
                .isEqualTo(Color.web(Tokens.KIND_PROCEDURE_DARK));
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 造一个彩色图标、挂上类型色类、走完 CSS，返回<b>轮廓层</b>的计算描边色。 */
    private Color edgeStroke(Icons.Icon icon, String kindClass, boolean dark) {
        Group colored = (Group) Icons.colored(icon, Icons.SIZE_LABEL);
        assertThat(colored)
                .as("%s 应当有彩色资产（没有就等于本门禁没在验证彩色通道）", icon.name())
                .isNotNull();
        colored.getStyleClass().add(kindClass);
        resolve(colored, dark);
        SVGPath edge = null;
        for (var child : colored.getChildren()) {
            if (child.getStyleClass().contains("adb-icon-edge")) {
                edge = (SVGPath) child;
            }
        }
        assertThat(edge).as("%s 的轮廓层（约定：EDGE 收尾画在最上）", icon.name()).isNotNull();
        return (Color) edge.getStroke();
    }

    /** 同上，取<b>填充层</b>（tone-*）的计算填充色。 */
    private List<Color> toneFills(Icons.Icon icon, String kindClass) {
        Group colored = (Group) Icons.colored(icon, Icons.SIZE_LABEL);
        colored.getStyleClass().add(kindClass);
        resolve(colored, false);
        return colored.getChildren().stream()
                .map(SVGPath.class::cast)
                .filter(path -> path.getStyleClass().stream()
                        .anyMatch(c -> c.startsWith("adb-icon-tone")))
                .map(path -> (Color) path.getFill())
                .toList();
    }

    /**
     * 把节点放进一条带主题样式表的链路并走完 CSS。
     *
     * <p>⚠️ {@code Node#applyCss()} <b>不向下层叠</b>（只作用于该节点自身），
     * 所以父容器和每一条子层都要各自 applyCss；顺序也必须是先父后子 ——
     * 子层的 looked-up color 要从父链继承，父链还没解析时子层只能拿到默认值。
     */
    private void resolve(Group colored, boolean dark) {
        StackPane host = new StackPane(colored);
        host.getStyleClass().add(dark ? "theme-dark" : "theme-light");
        Scene scene = new Scene(host, 64, 64);
        addTheme(scene, dark);
        host.applyCss();
        colored.applyCss();
        colored.getChildren().forEach(child -> child.applyCss());
    }

    private void addTheme(Scene scene, boolean dark) {
        String theme = dark ? "/io/agenticdb/ui/theme-dark.css" : "/io/agenticdb/ui/theme-light.css";
        scene.getStylesheets().add(style(theme));
        scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
    }

    private static String style(String resource) {
        URL url = IconKindHueTest.class.getResource(resource);
        assertThat(url).as("样式表 %s", resource).isNotNull();
        return url.toExternalForm();
    }
}
