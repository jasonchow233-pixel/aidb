package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R66（D-60 + D-81）：设置页那一屏。
 *
 * <p>帧证（{@code p9-settings}，2026-11-05 09:41 重拍）四条全中：
 * <ol>
 *   <li><b>四档密度只摆三颗</b> —— 视图菜单里有 dense/compact/normal/comfort 四档，
 *       设置页只有 Compact / Normal / Comfortable。同一设置在两条通道上数量不一致，
 *       菜单里最密的那档在设置页根本点不到。</li>
 *   <li><b>三颗同色同权重</b> —— 当前是哪档完全看不出来（动作里有 {@code selected}，
 *       页面没读它）。</li>
 *   <li><b>说明句被裁成「…or the theme icon in the a…）」</b> —— 卡片定宽 520、
 *       Label 不换行，句尾直接吃掉。</li>
 *   <li><b>内容列从 x≈525 起</b> —— 三张卡片居中摆在右半屏，左半屏是 200px 死区，
 *       标题与控件竖着堆成一坨。</li>
 * </ol>
 *
 * <p>⚠️ 口径（本代硬规矩）：样式类断言一律读**上屏后的计算值**，CSS 文本命中不算证据
 * —— 同族先例 D-50 / UX-5。所以选中态这条读的是按钮的 {@code getBackground()}，
 * 截字那条读的是 Label 皮肤里那颗 {@link Text#isTruncated()}。
 */
@ExtendWith(ApplicationExtension.class)
class SettingsPageTest {

    /** 浅色底（{@code -adb-bg-inset}）与选中底（{@code -adb-bg-selected}）的实测填充串。 */
    private static final String FILL_INSET = "0xf1f5f9ff";
    private static final String FILL_SELECTED = "0xcde8ffff";

    /** 窄口径内容宽：让说明句的可用宽落到真实最窄窗口（960）下的那个量级（约 460px）。 */
    private static final double NARROW_CONTENT_WIDTH = 700;

    /**
     * 期望档位 = 菜单通道里注册的那四档（同一个清单，两条通道不许各写一份）。
     * 顺序按 {@code RowHeights} 从密到松。
     */
    private static final List<String> LEVELS = MenuBarFactoryTest.MENU_ACTION_IDS.stream()
            .filter(id -> id.startsWith("density-"))
            .toList();

    private ActionRegistry actions;
    private WorkspaceView workspace;
    private StackPane root;
    /** 动作触发记录 —— 由 FX 线程写、测试线程读，所以用同步清单。 */
    private final List<String> runs = java.util.Collections.synchronizedList(new ArrayList<>());

    @Start
    void start(Stage stage) {
        assertThat(LEVELS).as("菜单通道必须有四档密度").hasSize(4);

        actions = new ActionRegistry();
        for (String id : LEVELS) {
            actions.register(id, I18n.t("action." + id), "测试", null, () -> runs.add(id));
        }
        workspace = new WorkspaceView(actions, "demo.db",
                tab -> null, tab -> null, tab -> null, tab -> null, tab -> null);
        onFxRun(() -> workspace.showTab(
                new TabItem("settings", I18n.t("window.pageType.settings"), TabKind.SETTINGS)));

        root = new StackPane(workspace);
        Scene scene = new Scene(root, 1280, 800);
        URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        assertThat(theme).as("theme-light.css").isNotNull();
        assertThat(appCss).as("app.css").isNotNull();
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        // ⚠️ 主题类必须挂在根节点上，否则 -adb-* 令牌全解析不出来，断言会因为
        //    「读到默认值」而假绿（同族坑见 DisabledButtonVisibilityTest）。
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** D-60 ①：菜单里有几档，设置页就得有几颗，顺序也要一致。 */
    @Test
    void theSettingsPageOffersEveryDensityLevelTheMenuOffers() {
        assertThat(densityButtons())
                .as("设置页的密度档位必须与菜单通道完全一致（D-60：四档只摆三颗）")
                .containsExactlyElementsOf(LEVELS.stream().map(id -> id.substring("density-".length()))
                        .toList());
    }

    /** D-60 ①：每颗按钮点的必须是它自己那一档。 */
    @Test
    void everyDensityButtonRunsItsOwnLevel() {
        // ⚠️ 遍历**期望的四档**而不是「页面上找到的按钮」—— 后者在少摆一档时会少跑一轮、
        //    照样全绿（本轮第一版就假绿过一次）。
        for (String id : LEVELS) {
            String level = id.substring("density-".length());
            runs.clear();
            Button button = onFx(() -> (Button) workspace.lookup("#settings-density-" + level));
            assertThat(button).as("档位「%s」必须有按钮（D-60：设置页只摆了三颗）", level).isNotNull();
            onFxRun(button::fire);
            assertThat(List.copyOf(runs)).as("档位「%s」必须触发自己的动作", level)
                    .containsExactly(id);
        }
    }

    /** D-81：当前档要**看得出来** —— 读计算底色，不读样式类字符串。 */
    @Test
    void theActiveDensityLevelIsPaintedDifferentlyFromTheOthers() {
        onFxRun(() -> actions.require("density-normal").setSelected(true));
        Map<String, String> fills = backgrounds();

        assertThat(fills).hasSize(LEVELS.size());
        assertThat(fills.get("normal"))
                .as("当前档必须与其余三档不同色（帧上四档同色 = 看不出选的是哪档）")
                .isEqualTo(FILL_SELECTED);
        assertThat(fills.get("compact")).as("非当前档保持普通底色").isEqualTo(FILL_INSET);
        assertThat(fills.get("dense")).as("非当前档保持普通底色").isEqualTo(FILL_INSET);
        assertThat(fills.get("comfort")).as("非当前档保持普通底色").isEqualTo(FILL_INSET);
    }

    /** D-81：换档时标记要跟着走（AppShell.applyDensity 只改动作的 selected）。 */
    @Test
    void theMarkMovesWhenTheLevelChanges() {
        onFxRun(() -> actions.require("density-normal").setSelected(true));
        assertThat(backgrounds().get("normal")).isEqualTo(FILL_SELECTED);

        onFxRun(() -> {
            actions.require("density-normal").setSelected(false);
            actions.require("density-comfort").setSelected(true);
        });
        Map<String, String> after = backgrounds();
        assertThat(after.get("comfort")).as("标记必须挪到新档上").isEqualTo(FILL_SELECTED);
        assertThat(after.get("normal")).as("旧档要退回普通底色").isEqualTo(FILL_INSET);
    }

    /** D-60 ③：卡片里的说明句必须整句上屏，不许裁成「…」。 */
    @Test
    void settingsHintsShowTheirWholeSentence() {
        List<String> hints = new ArrayList<>();
        List<String> clipped = new ArrayList<>();
        // ⚠️ 必须在**窄口径**下复量：1280 的裸场景里这句本来就放得下，
        //    摘掉 wrapText 门禁照样绿（M3 实测存活过一次）。
        //    真实最窄窗口是 App.stage.setMinWidth(960)，扣掉侧栏与卡片内边距后
        //    说明句可用宽约 460px —— 这里把场景压到 700，让内容区落到那个量级。
        onFxRun(() -> {
            workspace.setMinWidth(NARROW_CONTENT_WIDTH);
            workspace.setMaxWidth(NARROW_CONTENT_WIDTH);
        });
        try {
            onFxRun(() -> {
                applyAndLayout();
                for (Node node : workspace.lookupAll(".adb-card .adb-muted")) {
                    Label label = (Label) node;
                    hints.add(label.getText());
                    // ⭐ 读的是 Labeled 自己算出来的「这句被裁了没有」，不是 CSS 文本里
                    //    有没有 wrapText —— 同 D-50 的口径。
                    if (label.isTextTruncated()) {
                        clipped.add(label.getText());
                    }
                }
            });
        } finally {
            // 尺寸约束是共享状态，漏给下一条就是另一种假绿（同 stage 几何泄漏那一族）。
            onFxRun(() -> {
                workspace.setMinWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
                workspace.setMaxWidth(javafx.scene.layout.Region.USE_COMPUTED_SIZE);
            });
        }

        assertThat(hints).as("设置页至少有主题与密度两句说明").hasSizeGreaterThanOrEqualTo(2);
        assertThat(clipped).as("说明句被裁断（帧上实拍「…or the theme icon in the a…」）")
                .isEmpty();
    }

    /**
     * D-60 ④：内容贴着左边缘起排、并且用满内容宽度 —— 左半屏不许是死区。
     *
     * <p>两条都要钉：只钉左边缘，「卡片按内容取宽、右边空 200px」照样绿（M4c 实测存活）；
     * 只钉宽度，旧代码那种「520px 定宽 + 整体居中」的左边缘也量不出来。
     */
    @Test
    void settingsContentUsesTheWholeColumn() {
        List<double[]> read = onFx(() -> {
            applyAndLayout();
            List<double[]> cards = new ArrayList<>();
            for (Node card : workspace.lookupAll(".adb-card")) {
                javafx.scene.layout.Region region = (javafx.scene.layout.Region) card;
                cards.add(new double[]{region.localToScene(0, 0).getX(), region.getWidth()});
            }
            cards.add(new double[]{root.getWidth(), 0});
            return cards;
        });
        assertThat(read.size()).as("设置页必须有卡片").isGreaterThan(1);
        double sceneWidth = read.getLast()[0];
        List<double[]> cards = read.subList(0, read.size() - 1);

        assertThat(cards).extracting(card -> card[0])
                .as("卡片左边缘必须贴着内容区左边（帧上实拍从 x≈525 起，左半屏 200px 全空）")
                .allSatisfy(x -> assertThat(x).isLessThanOrEqualTo(32.0));
        assertThat(cards).extracting(card -> card[1])
                .as("卡片必须用满内容宽度（旧版定宽 520 居中 = 右侧再空一片）")
                .allSatisfy(width -> assertThat(width).isGreaterThanOrEqualTo(sceneWidth * 0.9));
    }

    /**
     * D-60 ④：两栏版式 —— 标题在左列、控件在右列，同一行。
     *
     * <p>以前标题、说明、控件竖着堆成一列，整页读起来像一段说明文而不是一张设置表。
     */
    @Test
    void everySettingIsALabeledRow() {
        onFx(() -> {
            applyAndLayout();
            List<Node> titles = new ArrayList<>(workspace.lookupAll(".adb-card .adb-section-title"));
            assertThat(titles)
                    .as("设置页必须有 Appearance / Density / Keyboard Shortcuts 三段")
                    .hasSizeGreaterThanOrEqualTo(3);
            for (Node node : titles) {
                Label title = (Label) node;
                assertThat(title.getParent())
                        .as("「%s」的标题必须和控件在同一条横向行里（两栏）", title.getText())
                        .isInstanceOf(javafx.scene.layout.HBox.class);
                List<Node> controls = new ArrayList<>();
                collectControls(title.getParent(), controls);
                double titleX = title.localToScene(0, 0).getX();
                assertThat(controls)
                        .as("「%s」这一段必须有可操作的控件", title.getText())
                        .isNotEmpty();
                assertThat(controls.stream()
                        .anyMatch(control -> control.localToScene(0, 0).getX() > titleX))
                        .as("「%s」的控件必须排在标题右侧（帧上实拍是竖着堆成一坨）", title.getText())
                        .isTrue();
            }
            return null;
        });
    }

    // ────────────────────────── 夹具 ──────────────────────────

    /** 设置页上摆出的密度档位（按按钮 id，左到右）。 */
    private List<String> densityButtons() {
        return onFx(() -> {
            List<String> found = new ArrayList<>();
            for (Node node : workspace.lookupAll(".adb-button")) {
                String id = node.getId();
                if (id != null && id.startsWith("settings-density-")) {
                    found.add(id.substring("settings-density-".length()));
                }
            }
            return found;
        });
    }

    /** 把动作的勾选态翻成「上屏后每颗按钮的实际底色」。 */
    private Map<String, String> backgrounds() {
        return onFx(() -> {
            applyAndLayout();
            Map<String, String> fills = new LinkedHashMap<>();
            for (String level : LEVELS) {
                Button button = (Button) workspace.lookup("#settings-density-"
                        + level.substring("density-".length()));
                assertThat(button).as("档位「%s」必须有按钮", level).isNotNull();
                fills.put(level.substring("density-".length()), firstFill(button));
            }
            return fills;
        });
    }

    private static String firstFill(Button button) {
        javafx.scene.layout.Background bg = button.getBackground();
        if (bg == null || bg.getFills().isEmpty()) {
            return "null";
        }
        return bg.getFills().get(0).getFill().toString();
    }

    /** 行内真正能操作的东西（按钮 / 下拉 / 复选框…），说明文字不算控件。 */
    private static void collectControls(Node parent, List<Node> out) {
        if (parent == null) {
            return;
        }
        for (Node child : childrenOf(parent)) {
            if (child instanceof Label || child instanceof Text) {
                continue;
            }
            if (child instanceof javafx.scene.control.Control control) {
                out.add(control);
                continue;
            }
            collectControls(child, out);
        }
    }

    private static List<Node> childrenOf(Node node) {
        if (node instanceof javafx.scene.layout.Pane pane) {
            return new ArrayList<>(pane.getChildren());
        }
        return List.of();
    }

    private void applyAndLayout() {
        // 由调用方保证已在 FX 线程。
        root.applyCss();
        root.layout();
    }

    private void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private <T> T onFx(Callable<T> action) {
        try {
            return WaitForAsyncUtils.asyncFx(action).get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
