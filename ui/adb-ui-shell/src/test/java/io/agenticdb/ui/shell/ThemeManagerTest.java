package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P7 启动性能：主题切换的「强制 CSS + 布局」只做该做的那一次。
 *
 * <p>{@code ThemeManager.apply} 里原本无条件跑一次 {@code root.applyCss() +
 * parent.layout()}。窗口**还没显示**时这一趟是纯浪费 —— {@code stage.show()}
 * 本来就会做同样的事，等于把整棵场景图的 CSS/布局算两遍（实测 706 ms，
 * 占首屏预算的三成半）。
 *
 * <p>但窗口**已经显示**时（用户切主题、验收截图）这一趟必须留着，
 * 否则紧随其后的 {@code scene.snapshot()} 会拿到「样式已换、像素还没重画」的空白帧。
 * 所以这里两头都钉住：启动那一次不许多做，切换那一次不许少做。
 */
@ExtendWith(ApplicationExtension.class)
class ThemeManagerTest {

    /** 未挂到场景时：只记模式，不碰任何 UI。 */
    @Test
    void applyBeforeAttachOnlyRemembersTheMode(FxRobot robot) {
        ThemeManager[] holder = new ThemeManager[1];
        robot.interact((Callable<Void>) () -> {
            ThemeManager manager = new ThemeManager(new SettingsStore());
            manager.apply(ThemeMode.DARK, false);
            holder[0] = manager;
            return null;
        });
        ThemeManager manager = holder[0];
        assertThat(manager.mode()).isEqualTo(ThemeMode.DARK);
        assertThat(manager.isDark()).isTrue();
        assertThat(manager.forcedPassesForTest())
                .as("没有场景时不可能做 CSS pass")
                .isZero();
    }

    /** 窗口还没显示：装好样式表与主题类，但**不**提前跑 CSS/布局。 */
    @Test
    void applyBeforeTheWindowShowsInstallsCssWithoutAForcedPass(FxRobot robot) {
        robot.interact((Callable<Void>) () -> {
            ThemeManager manager = new ThemeManager(new SettingsStore());
            Scene scene = new Scene(new StackPane(), 320, 200);
            manager.attach(scene);
            manager.apply(ThemeMode.LIGHT, false);

            assertThat(scene.getStylesheets())
                    .as("两套主题 + 业务 CSS 必须都在（切主题只改类，不换样式表）")
                    .anyMatch(s -> s.endsWith("theme-dark.css"))
                    .anyMatch(s -> s.endsWith("theme-light.css"))
                    .anyMatch(s -> s.endsWith("app.css"));
            assertThat(scene.getRoot().getStyleClass())
                    .as("根节点必须带上当前主题类")
                    .contains("theme-light");
            assertThat(manager.forcedPassesForTest())
                    .as("启动时窗口还没显示 —— 这一趟交给 stage.show()，不能重复算")
                    .isZero();
            return null;
        });
    }

    /** 窗口已经显示（切主题 / 截图）：必须跑那一次强制 pass。 */
    @Test
    void applyWhileTheWindowIsShowingForcesOnePass(FxRobot robot) {
        robot.interact((Callable<Void>) () -> {
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(), 320, 200);
            stage.setScene(scene);
            stage.show();
            try {
                ThemeManager manager = new ThemeManager(new SettingsStore());
                manager.attach(scene);
                manager.apply(ThemeMode.DARK, false);
                manager.apply(ThemeMode.LIGHT, false);

                assertThat(manager.forcedPassesForTest())
                        .as("已显示的窗口切主题必须强制重算，否则快照拿到空白帧")
                        .isEqualTo(2);
            } finally {
                stage.hide();
            }
            return null;
        });
    }

    /** 判定规则本身：只有「窗口存在且正在显示」才需要强制 pass。 */
    @Test
    void onlyAShowingWindowNeedsTheForcedPass(FxRobot robot) {
        boolean[] results = new boolean[3];
        robot.interact((Callable<Void>) () -> {
            results[0] = ThemeManager.shouldForcePass(null);

            Stage hidden = new Stage();
            hidden.setScene(new Scene(new StackPane(), 80, 80));
            results[1] = ThemeManager.shouldForcePass(hidden);

            Stage shown = new Stage();
            shown.setScene(new Scene(new StackPane(), 80, 80));
            shown.show();
            try {
                results[2] = ThemeManager.shouldForcePass(shown);
            } finally {
                shown.hide();
            }
            return null;
        });
        assertThat(results).containsExactly(false, false, true);
    }
}
