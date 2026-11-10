package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.Tokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 2026-11-10：角标环（{@link PendingMark}）的**样式契约**专项门禁。
 *
 * <p>为什么从 {@code AppBarTest} 里独立出来：那份门禁的逐行断言只对「挂了角标的那几格」
 * 生效，而它读的是 {@code AppBar.PENDING_TILES} —— 2026-11-10 之后功能区 11 格
 * <b>全部</b>有真落点，一格都不挂角标，于是那段断言整段空转：门禁还是绿的，
 * 契约却没人守了（这正是「空转的绿灯」——比红更危险）。
 *
 * <p>所以这里把契约从「哪几格挂标」里拆出来，<b>直接对 {@code PendingMark} 本身</b>断言：
 * 环是 8px 见方、1px 描边、颜色取 text-tertiary 那一档、{@code managed=false}（不吃排版）、
 * {@code mouseTransparent}（不吃点击）。以后哪一格再挂标，环长什么样由这条门禁说了算，
 * 与功能区有几格挂标无关。
 *
 * <p>⚠️ 必须挂样式表：不挂则 CSS 一条都不生效，量到的是 0×0 的假角标
 * （{@code AppBarTest} 早就记下这条 harness 脾气）。
 *
 * <p>⚠️ 读写控件状态必须在 <b>FX 线程</b>上（本类的读全部走 {@link #onFx}）——
 * headless harness 下从测试线程直接摸场景图会抛
 * {@code IllegalState: Not on FX application thread}。
 */
@ExtendWith(ApplicationExtension.class)
class PendingMarkTest {

    private Pane host;
    private Button button;

    @Start
    void start(Stage stage) {
        host = new HBox();
        button = new Button("target");
        button.setId("pending-mark-target");
        host.getChildren().add(button);

        Scene scene = new Scene(host, 400, 200);
        java.net.URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        java.net.URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        host.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** 环是宿主排的孩子（不是按钮的孩子）—— 这一点是「不吃排版」的前提。 */
    @Test
    void theRingLivesOnTheHostRowNotOnTheButton() {
        onFx(() -> PendingMark.attach(host, List.of(button)));
        List<Region> marks = onFx(this::marks);
        assertThat(marks).as("宿主排上必须有一枚角标环").hasSize(1);
        assertThat(onFx(() -> marks.get(0).getParent())).as("环是宿主排的孩子").isSameAs(host);
        assertThat(onFx(() -> {
            javafx.scene.Node graphic = button.getGraphic();
            if (graphic == null) {
                return true;
            }
            if (graphic instanceof javafx.scene.Parent parent) {
                return parent.getChildrenUnmodifiable().stream()
                        .noneMatch(node -> node.getStyleClass().contains(PendingMark.MARK_CLASS));
            }
            return true;
        })).as("环不许进按钮的图形槽 —— 实测会让这一排的标签基线整体挪 6~7px")
                .isTrue();
    }

    /** 尺寸与描边：8px 见方、1px 粗、颜色取 text-tertiary 那一档。 */
    @Test
    void theRingIsAnEightPixelHairlineInTheTertiaryTextColor() {
        onFx(() -> PendingMark.attach(host, List.of(button)));
        // ⚠️ 必须先 applyCss —— 描边在 CSS 里，不落样式表时 getBorder() 返回 null。
        onFx(() -> {
            host.getScene().getRoot().applyCss();
            host.getScene().getRoot().layout();
        });
        Region mark = onFx(this::theMark);

        assertThat(mark.getMinWidth()).as("环宽").isEqualTo(8.0);
        assertThat(mark.getMinHeight()).as("环高").isEqualTo(8.0);

        javafx.scene.paint.Paint expected =
                javafx.scene.paint.Color.web(Tokens.TEXT_TERTIARY_LIGHT);
        javafx.scene.paint.Paint actual = null;
        double thickness = -1.0;
        for (javafx.scene.layout.BorderStroke stroke : mark.getBorder().getStrokes()) {
            if (stroke != null && stroke.getTopStroke() != null) {
                actual = stroke.getTopStroke();
                thickness = stroke.getWidths().getTop();
                break;
            }
        }
        assertThat(actual)
                .as("角标描边必须是 text-tertiary 那一档（比正文淡，但在 chrome 底上过 3:1）")
                .isNotNull()
                .isEqualTo(expected);
        assertThat(thickness)
                .as("环只有 1px 粗才是「淡角标」，粗了就和图标抢视线")
                .isEqualTo(1.0);
        assertThat(onFx(mark::getWidth))
                .as("环与状态点同一档尺寸（8px）—— 大小不一的角标读成装饰")
                .isEqualTo((double) Icons.SIZE_DOT);
    }

    /** 环不许参与排版、不许吃点击 —— 这两条是它能贴在按钮角上的前提。 */
    @Test
    void theRingNeitherParticipatesInLayoutNorSwallowsClicks() {
        onFx(() -> PendingMark.attach(host, List.of(button)));
        Region mark = onFx(this::theMark);
        assertThat(onFx(mark::isManaged))
                .as("环不许参与排版（managed=true 就会把宿主排上的按钮垫下去）")
                .isFalse();
        assertThat(onFx(mark::isMouseTransparent))
                .as("环压在按钮角上，必须让点击透到那一格")
                .isTrue();
    }

    /** 环要贴着按钮的右上角（内缩 3px），不是随便搁在排上。 */
    @Test
    void theRingSitsAtTheTopRightCornerOfItsButton() {
        onFx(() -> PendingMark.attach(host, List.of(button)));
        onFx(() -> {
            host.applyCss();
            host.layout();
        });
        double[] insets = onFx(() -> {
            Region mark = theMark();
            double insetRight = button.getLayoutX() + button.getWidth()
                    - mark.getLayoutX() - mark.getWidth();
            double insetTop = mark.getLayoutY() - button.getLayoutY();
            return new double[]{insetRight, insetTop};
        });
        assertThat(insets[0])
                .as("环离右边缘的内缩")
                .isBetween(0.0, 6.0);
        assertThat(insets[1])
                .as("环离上边缘的内缩")
                .isBetween(0.0, 6.0);
    }

    /** 提示语：原文案 + 「本版本未提供」，Tooltip 与读屏侧同一句。 */
    @Test
    void theHintAppendsTheSameSentenceToBothSurfaces() {
        String hint = PendingMark.hint("Data Profiling");
        assertThat(hint).contains("Data Profiling");
        assertThat(hint).contains(I18n.t("ribbon.pending"));
    }

    /** {@code saySo} 那一档：只有文字实话、没有环。 */
    @Test
    void saySoStatesTheTruthWithoutAddingARing() {
        Button target = new Button("AI");
        target.setTooltip(new Tooltip("AI"));
        target.setAccessibleText("AI");
        Pane row = new HBox(target);
        onFx(() -> PendingMark.saySo(target));

        assertThat(onFx(target::getAccessibleText))
                .as("读屏侧要说同一句实话")
                .contains(I18n.t("ribbon.pending"));
        assertThat(onFx(() -> target.getTooltip().getText()))
                .as("tooltip 要说同一句实话")
                .contains(I18n.t("ribbon.pending"));
        assertThat(onFx(() -> row.getChildren().stream()
                .anyMatch(child -> child.getStyleClass().contains(PendingMark.MARK_CLASS))))
                .as("saySo 那一档不挂环（横排按钮包一层会撑宽，整排跟着挪）")
                .isFalse();
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    /** 宿主排上的角标环（必须在 FX 线程上调）。 */
    private List<Region> marks() {
        return host.getChildren().stream()
                .filter(Region.class::isInstance)
                .map(Region.class::cast)
                .filter(node -> node.getStyleClass().contains(PendingMark.MARK_CLASS))
                .toList();
    }

    /** 唯一那枚角标环（必须在 FX 线程上调）。 */
    private Region theMark() {
        List<Region> found = marks();
        assertThat(found).as("宿主排上必须有一枚角标环").hasSize(1);
        return found.get(0);
    }

    /** 在 FX 线程上跑一段代码并把它的返回值带回来。 */
    private <T> T onFx(Supplier<T> action) {
        return WaitForAsyncUtils.waitFor(WaitForAsyncUtils.asyncFx(() -> {
            T value = action.get();
            return value;
        }));
    }

    /** 在 FX 线程上跑一段无返回值代码。 */
    private void onFx(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
    }
}
