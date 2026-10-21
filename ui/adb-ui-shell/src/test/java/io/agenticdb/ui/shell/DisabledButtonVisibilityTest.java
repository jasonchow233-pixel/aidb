package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.net.URL;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 禁用的主按钮**必须看得见**。
 *
 * <p>实测发现：数据页的「保存」按钮（禁用的 primary）**整个看不见** ——
 * 白字白底。根因是 primary 变体把文字设成了 {@code -adb-text-inverse}（白色），
 * 而禁用态没有把文字改回灰色 —— 按钮底色变了、文字没变，于是「消失了」。
 *
 * <p>这条测试把「禁用的按钮不能消失」钉成断言。
 */
@ExtendWith(ApplicationExtension.class)
class DisabledButtonVisibilityTest {

    private Button primary;
    private Button ghost;

    @Start
    void start(Stage stage) {
        primary = new Button("保存");
        primary.getStyleClass().addAll("adb-button", "adb-button--primary");
        primary.setDisable(true);

        ghost = new Button("筛选");
        ghost.getStyleClass().addAll("adb-button", "adb-button--ghost");
        ghost.setDisable(true);

        StackPane root = new StackPane(primary, ghost);
        Scene scene = new Scene(root, 400, 120);
        URL appCss = getClass().getResource("/io/agenticdb/ui/app.css");
        URL theme = getClass().getResource("/io/agenticdb/ui/theme-light.css");
        assertThat(appCss).as("app.css").isNotNull();
        assertThat(theme).as("theme-light.css").isNotNull();
        scene.getStylesheets().add(theme.toExternalForm());
        scene.getStylesheets().add(appCss.toExternalForm());
        // ⚠️ 必须给根节点挂上主题类 —— 主题是**类选择器**（.theme-light），
        //    不挂这个类，-adb-* looked-up color 全部解析不出来，
        //    断言就会因为「拿到默认值」而假通过。
        root.getStyleClass().add("theme-light");
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void disabledPrimaryButtonKeepsAReadableLabel(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⚠️ TestFX 4.x 的 interact(Callable) **返回机器人本身**，不返回值 —— 用数组带出来。
        Paint[] holder = new Paint[1];
        robot.interact((Callable<Void>) () -> {
            holder[0] = labelFill(primary);
            return null;
        });
        Paint fill = holder[0];

        assertThat(fill).as("禁用的按钮文字必须有颜色").isNotNull();
        if (fill instanceof Color color) {
            assertThat(color.getRed())
                    .as("禁用的主按钮：文字不能是白色（白字白底 = 整个按钮看不见）")
                    .isLessThan(0.95);
        }
    }

    @Test
    void disabledButtonsAreNotDimmedByModena(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        // ⚠️ Modena 给所有 :disabled 控件加 -fx-opacity: 0.4。
        //    不抵消，禁用的文字只剩 40% 不透明度 —— 在浅色底上等于消失
        //    （实测：数据页的「保存」按钮整个看不见）。
        double[] opacity = new double[1];
        robot.interact((Callable<Void>) () -> {
            opacity[0] = primary.getOpacity();
            return null;
        });
        assertThat(opacity[0])
                .as("禁用的按钮不能被整体压暗 —— 禁用感要由颜色表达")
                .isEqualTo(1.0);
    }

    @Test
    void disabledGhostButtonKeepsAReadableLabel(FxRobot robot) {
        WaitForAsyncUtils.waitForFxEvents();
        Paint[] holder = new Paint[1];
        robot.interact((Callable<Void>) () -> {
            holder[0] = labelFill(ghost);
            return null;
        });
        Paint fill = holder[0];

        assertThat(fill).as("禁用的按钮文字必须有颜色").isNotNull();
        if (fill instanceof Color color) {
            assertThat(color.getRed())
                    .as("禁用的 ghost 按钮文字也不能是白色")
                    .isLessThan(0.95);
        }
    }

    /**
     * 取按钮**实际渲染**出来的文字颜色。
     *
     * <p>优先看皮肤里的标签（那才是画在屏幕上的东西）；
     * 皮肤还没建起来时退回按钮自身的 {@code textFill}。
     */
    private static Paint labelFill(Button button) {
        button.applyCss();
        button.layout();
        for (javafx.scene.Node child : button.getChildrenUnmodifiable()) {
            if (child instanceof Label label && label.getTextFill() != null) {
                Paint fill = label.getTextFill();
                if (fill instanceof Color color && color.getOpacity() > 0) {
                    return fill;
                }
            }
        }
        return button.getTextFill();
    }
}
