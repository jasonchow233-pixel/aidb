package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P3-1：首启引导浮层的行为门禁。
 *
 * <p>三条事实：① 没看过才弹；② 关后即焚（写 {@code ui.onboarding.seen}、撤出布局，
 * 第二次启动不再弹）；③ Help 菜单走的 {@link OnboardingOverlay#reopenGuide()} 不看这个标志。
 *
 * <p>另外钉住「不替代工作区页面」这条结构承诺：浮层是 {@link StackPane} 的一个
 * <b>叠加子层</b>，底下那一层页面原样留在 {@code getChildren()} 里 —— v4 修过一次
 * 「欢迎页把对象首页盖掉」，这里断的是回退。
 *
 * <p>偏好节点由 {@link IsolatedPreferencesExtension} 逐用例隔离，不碰开发机真实设置。
 */
@ExtendWith({ApplicationExtension.class, IsolatedPreferencesExtension.class})
class OnboardingOverlayTest {

    private static final String SEEN = "ui.onboarding.seen";

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.show();
    }

    private static <T> T onFx(java.util.function.Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }

    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static OnboardingOverlay overlay(SettingsStore settings, Runnable onOpenDemo) {
        return onFx(() -> new OnboardingOverlay(settings, onOpenDemo));
    }

    private SettingsStore isolated() {
        SettingsStore settings = new SettingsStore();
        settings.remove(SEEN);
        return settings;
    }

    @Test
    void firstStartShowsGuideAndDismissPersistsSeen() {
        SettingsStore settings = isolated();
        OnboardingOverlay guide = overlay(settings, () -> { });

        onFxRun(guide::maybeShow);
        assertThat(onFx(guide::isVisible)).as("首次启动应弹出引导").isTrue();
        assertThat(onFx(guide::isManaged)).as("浮层要占布局，不是只画一层透明").isTrue();

        onFxRun(guide::dismiss);
        assertThat(onFx(guide::isVisible)).as("关后即焚").isFalse();
        assertThat(onFx(guide::isManaged)).isFalse();
        assertThat(settings.getBoolean(SEEN, false))
                .as("「看过」写进 SettingsStore").isTrue();

        OnboardingOverlay secondRun = overlay(settings, () -> { });
        onFxRun(secondRun::maybeShow);
        assertThat(onFx(secondRun::isVisible))
                .as("第二次启动不再自动弹").isFalse();
    }

    @Test
    void reopenGuideIgnoresTheSeenFlag() {
        SettingsStore settings = isolated();
        settings.setBoolean(SEEN, true);
        OnboardingOverlay guide = overlay(settings, () -> { });

        onFxRun(guide::maybeShow);
        assertThat(onFx(guide::isVisible)).as("已看过 → 不自动弹").isFalse();

        onFxRun(guide::reopenGuide);
        assertThat(onFx(guide::isVisible))
                .as("Help「重新打开欢迎引导」无视 seen 标志").isTrue();
    }

    @Test
    void openDemoButtonRunsTheActionAndDismisses(FxRobot robot) {
        SettingsStore settings = isolated();
        AtomicInteger opened = new AtomicInteger();
        OnboardingOverlay guide = overlay(settings, opened::incrementAndGet);

        // 底下垫一层真实页面：证明引导是叠加层而非替换。
        javafx.scene.control.Label behind = new javafx.scene.control.Label("object home");
        behind.setId("behind-page");
        StackPane root = new StackPane(behind, guide);
        root.getStyleClass().add("theme-light");
        onFxRun(() -> {
            stage.setScene(new Scene(root, 900, 700));
            guide.maybeShow();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(robot.lookup("#onboarding-panel").tryQuery()).isPresent();
        assertThat(robot.lookup("#behind-page").tryQuery())
                .as("引导盖在工作区之上，不替换任何页面").isPresent();

        robot.clickOn("#onboarding-open-demo");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(opened.get()).as("演示库入口真的跑了一次").isEqualTo(1);
        assertThat(onFx(guide::isVisible)).as("打开示例库后引导自动关闭").isFalse();
        assertThat(settings.getBoolean(SEEN, false)).isTrue();
    }
}
