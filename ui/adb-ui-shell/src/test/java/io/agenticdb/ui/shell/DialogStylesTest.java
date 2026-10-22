package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐⭐ 弹窗主题门禁。
 *
 * <h2>为什么这道门禁必须在</h2>
 * JavaFX 的 looked-up color（{@code -adb-*}）定义在 {@code .theme-light} /
 * {@code .theme-dark} **类选择器**上，而主题类挂在**主场景根节点**上。
 * 弹窗有自己的 Scene 与根节点（DialogPane）—— 那个根节点上没有主题类，
 * 于是弹窗里所有 {@code -adb-*} 查找全部落空，声明被丢弃，
 * 弹窗退回 JavaFX 默认皮肤。
 *
 * <p><b>症状</b>：新建表弹窗没有边框、没有标题分隔、按钮全是默认灰钮，
 * 和主窗口完全不是一套东西 —— 而且**截图能看出来，行为门禁测不出来**
 * （控件都在、事件都绑着，只是没上样式）。
 *
 * <p>所以门禁断言四件事：样式表挂上了、主题类抄过去了、重复挂不重复、弹窗不比屏幕高。
 */
@ExtendWith(ApplicationExtension.class)
class DialogStylesTest {

    private Stage owner;

    @Start
    void start(Stage stage) {
        this.owner = stage;
        Scene scene = new Scene(new javafx.scene.layout.VBox(), 1280, 800);
        // 模拟真实外壳：根节点带主题类，场景挂三张样式表。
        scene.getStylesheets().addAll(
                "io/agenticdb/ui/theme-light.css",
                "io/agenticdb/ui/theme-dark.css",
                "io/agenticdb/ui/app.css");
        scene.getRoot().getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    /** 在 FX 线程上建弹窗并套样式（Dialog 只能在 FX 线程上 new）。 */
    private Dialog<Void> styled(Stage ownerWindow) throws Exception {
        return WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> dialog = new Dialog<>();
            DialogStyles.apply(dialog, ownerWindow);
            return dialog;
        }).get();
    }

    @Test
    void theThemeClassIsCopiedOntoTheDialogPane(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        assertThat(dialog.getDialogPane().getStyleClass())
                .as("主题类必须抄到 DialogPane —— 否则弹窗里所有 -adb-* 查找落空")
                .contains("theme-light", "adb-dialog");
    }

    @Test
    void theApplicationStylesheetsAreAttached(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        assertThat(dialog.getDialogPane().getStylesheets())
                .as("弹窗必须继承应用样式表（主题表 + 业务表）")
                .isNotEmpty();
        assertThat(dialog.getDialogPane().getStylesheets())
                .anyMatch(sheet -> sheet.endsWith("app.css"));
    }

    @Test
    void applyingTwiceDoesNotDuplicateTheStylesheets(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> d = new Dialog<>();
            DialogStyles.apply(d, owner);
            DialogStyles.apply(d, owner);
            return d;
        }).get();
        long appSheets = dialog.getDialogPane().getStylesheets().stream()
                .filter(sheet -> sheet.endsWith("app.css")).count();
        assertThat(appSheets)
                .as("重复 apply 不许把样式表挂两遍（每次弹窗都翻倍）")
                .isEqualTo(1);
    }

    @Test
    void aDialogNeverGrowsTallerThanTheScreen(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(owner);
        double screenHeight = javafx.stage.Screen.getScreens().stream()
                .mapToDouble(screen -> screen.getBounds().getHeight()).max().orElse(1080);
        assertThat(dialog.getDialogPane().getMaxHeight())
                .as("弹窗比屏幕还高 → 底部按钮跑到屏幕外，用户点不到")
                .isLessThan(screenHeight);
    }

    @Test
    void theDialogKeepsItsOwnTitleAndContent(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = WaitForAsyncUtils.asyncFx((Callable<Dialog<Void>>) () -> {
            Dialog<Void> d = new Dialog<>();
            d.setTitle("标题");
            d.setHeaderText("题头");
            d.getDialogPane().setContent(new Label("正文"));
            DialogStyles.apply(d, owner);
            return d;
        }).get();
        assertThat(dialog.getTitle()).isEqualTo("标题");
        assertThat(dialog.getDialogPane().getContent())
                .as("套样式不许动内容")
                .isNotNull();
    }

    @Test
    void aNullOwnerDoesNotBreakTheDialog(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        Dialog<Void> dialog = styled(null);
        assertThat(dialog.getDialogPane().getStyleClass()).contains("adb-dialog");
    }

    @Test
    void theDialogButtonStyleExistsInTheBusinessCss() {
        // 弹窗里的按钮必须走 .adb-dialog .button 规则（圆角、描边、主按钮实心）。
        String css = readAppCss();
        assertThat(css)
                .as("弹窗按钮要有自己的规则 —— 否则是 JavaFX 默认灰钮")
                .contains(".adb-dialog .button");
        assertThat(css)
                .as("弹窗主按钮必须是实心强调色")
                .contains(".adb-dialog .button:default");
    }

    private static String readAppCss() {
        try (var stream = javafx.scene.control.Dialog.class
                .getResourceAsStream("/io/agenticdb/ui/app.css")) {
            assertThat(stream).as("app.css 必须在 classpath 上").isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
