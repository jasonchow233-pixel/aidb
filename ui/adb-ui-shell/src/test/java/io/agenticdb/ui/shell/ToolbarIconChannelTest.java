package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.ui.Tokens;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R35 N4-c 门禁：16px 内联工具条必须走**彩色通道**，且按钮态要把分层一起翻色。
 *
 * <h2>钉的是什么缺陷</h2>
 * UI-VISUAL-SPEC I-4：同一个图标在树上（{@code Icons.colored}）是彩的，
 * 在工具条上（{@code Icons.of}）是单色 —— 用户看到的是「两个不同的东西」，
 * 而它们表达的是同一个动作。工具条统一改走 {@link Icons#colored} 之后，
 * 「有没有彩色资产」由资产决定，不再由摆放位置决定。
 *
 * <p>⚠️ 但 Navicat 的 16px 本身分两区（逐像素看
 * {@code docs/screenshots/navicat/navicat-data-grid-2026-10-24.png}）：
 * 上沿工具条是蓝色实心图形，贴在网格左下的行操作条 {@code + − ✓ ✗} 是灰的单线。
 * 所以本门禁<b>两个方向都钉</b>：彩的必须彩（{@code iconMono} 用错位置会红），
 * 单色的必须单色（{@link IconButtons#iconMono} 那排若被改成彩色也会红）。
 *
 * <h2>为什么还要断言按钮态</h2>
 * 分层把「一个 {@code SVGPath}」换成「一个 {@code Group} 加几条子层」，
 * 于是只认 {@code .adb-icon} 的那几条单色状态规则（hover / primary / disabled）
 * <b>一条都命中不了子层</b>。后果不是不够好看，是两条硬伤：
 * <ul>
 *   <li>主动作按钮蓝底 + 蓝边轮廓 = 图标看不见；</li>
 *   <li>禁用态还亮着 = 「看起来能点」，撞诚实条款 —— 本仓库的禁用感由**颜色**表达
 *       （Modena 的 {@code -fx-opacity: .4} 在 {@code app.css} 的 DISABLED 段被显式还原成 1）。</li>
 * </ul>
 *
 * <h2>断言口径</h2>
 * 读<b>计算色</b>（{@code getStroke()} / {@code getFill()}），不读 CSS 文本 ——
 * 规则写着 ≠ 它匹配到了节点（类名挂错层、特异性被盖住，文本审计全都看不出来）。
 * 同 {@link IconKindHueTest}。
 */
@ExtendWith(ApplicationExtension.class)
class ToolbarIconChannelTest {

    @Start
    void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 320, 200));
        stage.show();
    }

    @Test
    @DisplayName("有彩色资产的图标：工具条上拿到的就是分层 Group，不是单色 SVGPath")
    void everyColoredIconStaysColoredInAToolbar() {
        List<String> flattened = new ArrayList<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            if (!Icons.hasColoredVariant(icon)) {
                continue;
            }
            if (!(IconButtons.icon(icon, icon.name()).getGraphic() instanceof Group group)
                    || !containsLayer(group, "adb-icon-edge")) {
                flattened.add(icon.name());
            }
        }
        assertThat(flattened)
                .as("这些图标有彩色资产，却在工具条上掉回单色 —— I-4 的「两种画法」又回来了：%s",
                        flattened)
                .isEmpty();
    }

    @Test
    @DisplayName("没有彩色资产的图标：继续单色回退，不许画出半套配色")
    void anIconWithoutAColorAssetFallsBackToMonochrome() {
        Node graphic = IconButtons.icon(Icons.Icon.REFRESH, "刷新").getGraphic();
        assertThat(Icons.hasColoredVariant(Icons.Icon.REFRESH))
                .as("REFRESH 本就没有彩色资产（否则这条断言在验证一个不存在的前提）")
                .isFalse();
        assertThat(graphic)
                .as("回退就是回退：单条 SVGPath + .adb-icon，而不是一个空的 Group")
                .isInstanceOf(SVGPath.class);
        assertThat(graphic.getStyleClass()).contains("adb-icon");
    }

    @Test
    @DisplayName("行操作条工厂：图标即便有彩色资产也保持单色（Navicat 的 + − ✓ ✗ 是灰线）")
    void theRowStripFactoryStaysMonochrome() {
        assertThat(Icons.hasColoredVariant(Icons.Icon.ADD))
                .as("ADD 是有彩色资产的 —— 否则这条断言没在验任何东西")
                .isTrue();
        Node graphic = IconButtons.iconMono(Icons.Icon.ADD, "新增行").getGraphic();
        assertThat(graphic)
                .as("贴着网格的行操作条走单色道：彩色的编辑开关会抢过数据本身")
                .isInstanceOf(SVGPath.class);
        assertThat(graphic.getStyleClass()).contains("adb-icon");
    }

    @Test
    @DisplayName("禁用态：轮廓塌成禁用灰，每一层填充都不再是身份色")
    void aDisabledToolbarButtonGreysEveryLayer() {
        Button disabled = new Button();
        disabled.getStyleClass().addAll("adb-button", "adb-icon-button");
        disabled.setDisable(true);
        host(disabled);

        Layers layers = layersOf(IconButtons.icon(Icons.Icon.SAVE, "保存").getGraphic(), disabled);
        assertThat(layers.edge())
                .as("禁用后轮廓必须吃 -adb-text-disabled —— 亮着蓝边等于告诉用户「能点」")
                .isEqualTo(Color.web(Tokens.TEXT_DISABLED_LIGHT));
        assertThat(layers.tones())
                .as("禁用后填充塌成禁用底 —— 淡蓝填充压在禁用底上就是「还没灰干净」")
                .containsOnly(Color.web(Tokens.BG_DISABLED_LIGHT));
    }

    @Test
    @DisplayName("主动作按钮（蓝底）：轮廓翻白，填充层全部透明")
    void aPrimaryToolbarButtonFlipsTheEdgeAndClearsTheTones() {
        Button primary = new Button();
        primary.getStyleClass().addAll("adb-button", "adb-button--primary");
        host(primary);

        Layers layers = layersOf(IconButtons.icon(Icons.Icon.SAVE, "保存").getGraphic(), primary);
        assertThat(layers.edge())
                .as("蓝底上必须是反色轮廓，否则蓝边压蓝底 = 图标看不见")
                .isEqualTo(Color.web(Tokens.TEXT_INVERSE_LIGHT));
        assertThat(layers.tones())
                .as("淡蓝填充压在蓝底上是一坨，主动作按钮里填充层必须透明")
                .containsOnly(Color.TRANSPARENT);
    }

    @Test
    @DisplayName("常态工具条图标：轮廓走身份色，填充走 tone 令牌")
    void theRestingToolbarIconKeepsTheIdentityColors() {
        Button plain = new Button();
        plain.getStyleClass().add("adb-button");
        host(plain);

        Layers layers = layersOf(IconButtons.icon(Icons.Icon.SAVE, "保存").getGraphic(), plain);
        assertThat(layers.edge())
                .as("常态轮廓 = -adb-icon-edge（非文字元素的对比度由 ContrastTest 卡 ≥3:1）")
                .isEqualTo(Color.web(Tokens.ICON_EDGE_LIGHT));
        assertThat(layers.tones())
                .as("常态填充 = tone 令牌，不能和轮廓同色（同色等于分层没起作用）")
                .containsOnly(Color.web(Tokens.ICON_TONE_A_LIGHT), Color.web(Tokens.ICON_TONE_B_LIGHT))
                .doesNotContain(Color.web(Tokens.ICON_EDGE_LIGHT));
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 一条分层图标的计算色：轮廓 + 所有填充层。 */
    private record Layers(Color edge, List<Color> tones) {
    }

    /**
     * 把图标装进按钮、按钮放进带主题的链路，走完 CSS 后读子层计算色。
     *
     * <p>走完整链路而不是直接把类名挂到 Group 上：{@code :disabled} 与
     * {@code --primary} 都在**按钮**上，后代选择器只有真父子链路才成立 ——
     * 这正是被验的东西不能在测试里替产品搭好。
     */
    private Layers layersOf(Node graphic, Button hostButton) {
        Group group = (Group) graphic;
        hostButton.setGraphic(group);
        hostButton.applyCss();
        group.applyCss();
        group.getChildren().forEach(Node::applyCss);
        SVGPath edge = null;
        List<Color> tones = new ArrayList<>();
        for (Node child : group.getChildren()) {
            if (!(child instanceof SVGPath path)) {
                continue;
            }
            if (path.getStyleClass().contains("adb-icon-edge")) {
                edge = path;
            }
            if (path.getStyleClass().stream().anyMatch(c -> c.startsWith("adb-icon-tone"))) {
                tones.add((Color) path.getFill());
            }
        }
        assertThat(edge).as("分层图标必须有轮廓层（约定：EDGE 收尾画在最上）").isNotNull();
        assertThat(tones).as("SAVE 的彩色资产应当带填充层").isNotEmpty();
        return new Layers(edge == null ? null : (Color) edge.getStroke(), List.copyOf(tones));
    }

    /** 主题样式表 + 按钮一起放进场景，走一遍 CSS。 */
    private void host(Button button) {
        StackPane root = new StackPane(button);
        root.getStyleClass().add("theme-light");
        Scene scene = new Scene(root, 120, 60);
        scene.getStylesheets().add(style("/io/agenticdb/ui/theme-light.css"));
        scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
        scene.getRoot().applyCss();
        button.applyCss();
    }

    private static boolean containsLayer(Group group, String styleClass) {
        return group.getChildren().stream()
                .anyMatch(child -> child.getStyleClass().contains(styleClass));
    }

    private static String style(String resource) {
        URL url = ToolbarIconChannelTest.class.getResource(resource);
        assertThat(url).as("样式表 %s", resource).isNotNull();
        return url.toExternalForm();
    }
}
