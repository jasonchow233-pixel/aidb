package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.HostKeyDecision;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 未知主机密钥弹窗的门禁（本轮交付的 Navicat 同款信任窗）。
 *
 * <h2>验什么</h2>
 * <ol>
 *   <li><b>指纹对用户可见</b> —— 弹窗正文必须把 {@code hostPort / keyType / SHA256 指纹}
 *       摆出来，否则「比对」无从谈起（这件事任何行为门禁都测不出，只能读上屏文本）。</li>
 *   <li><b>弹窗套了应用主题</b> —— {@code DialogStyles.apply} 必须被调过：
 *       DialogPane 上有 {@code adb-dialog} 类、且挂了 {@code app.css}；否则退回 Modena 灰皮肤。</li>
 *   <li><b>「信任并记录」→ ACCEPT_AND_REMEMBER，「取消」→ REJECT</b> ——
 *       决策分支（{@code SshTunnel} 那一端怎么用这个选择）由 {@code SshTunnelTest} 守，
 *       这里守的是窗本身把用户意图翻译成了正确的 {@link HostKeyDecision.Choice}。</li>
 * </ol>
 *
 * <h2>怎么不踩 Monocle 鼠标 NPE</h2>
 * {@code DestructiveConfirmTest} 的先例：无头下 robot 鼠标通道会在 {@code notifyMouse} 上 NPE，
 * 所以这里<b>不点鼠标</b>。{@code decide} 必须在<b>非 FX 线程</b>上调（否则 {@code showAndWait}
 * 会占住 FX 线程、嵌套事件循环里再发 {@code runLater} 就死锁）—— 于是用一条后台线程触发它，
 * 弹窗在 FX 线程上 {@code showAndWait}，本测试的 JUnit 线程再经 {@code onFx} 把按钮 {@code fire}
 * 掉；FX 线程的嵌套事件循环会处理那次 {@code fire}、关窗、让 {@code decide} 返回。
 */
@ExtendWith(ApplicationExtension.class)
class HostKeyPromptTest {

    private static Stage owner;

    @Start
    void start(Stage stage) {
        owner = stage;
        StackPane root = new StackPane();
        root.getStyleClass().add("theme-light");
        Scene scene = new Scene(root, 980, 680);
        scene.getStylesheets().addAll(
                resource("/io/agenticdb/ui/theme-light.css"),
                resource("/io/agenticdb/ui/app.css"));
        stage.setScene(scene);
        stage.show();
    }

    private static String resource(String path) {
        java.net.URL url = HostKeyPromptTest.class.getResource(path);
        assertThat(url).as("样式资源 %s", path).isNotNull();
        return url.toExternalForm();
    }

    @Test
    void theFingerprintIsShownAndTrustReturnsAccept() throws Exception {
        HostKeyDecision decision = HostKeyPrompt.interactive(owner);
        HostKeyDecision.HostKeyPromptInfo info = new HostKeyDecision.HostKeyPromptInfo(
                "ops@bastion:2222", "ssh-ed25519", "SHA256:deadbeefdeadbeef");

        // ⭐ decide 必须在后台线程：FX 线程会被 showAndWait 占住。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<HostKeyDecision.Choice> answer = pool.submit(() -> decision.decide(info));
        try {
            DialogPane pane = waitForOpenDialog();

            String message = onFx(() -> {
                Label label = (Label) pane.lookup(".adb-muted");
                assertThat(label).as("弹窗正文必须有 .adb-muted 标识").isNotNull();
                return label.getText();
            });
            assertThat(message)
                    .as("正文必须把地址摆出来让用户认")
                    .contains("ops@bastion:2222")
                    .as("正文必须把算法摆出来让用户认")
                    .contains("ssh-ed25519")
                    .as("指纹必须原样可见，否则没法比对")
                    .contains("SHA256:deadbeef");

            assertThat(windowTitle(pane)).isEqualTo(I18n.t("ssh.hostkey.title"));

            boolean themed = onFx(() -> pane.getStyleClass().contains("adb-dialog")
                    && pane.getStylesheets().stream().anyMatch(s -> s.endsWith("app.css")));
            assertThat(themed)
                    .as("弹窗必须套过 DialogStyles（否则退回 Modena 灰皮肤）")
                    .isTrue();

            fireByText(pane, I18n.t("ssh.hostkey.accept"));
            assertThat(answer.get(5, TimeUnit.SECONDS))
                    .as("点「信任并记录」必须得到 ACCEPT_AND_REMEMBER")
                    .isEqualTo(HostKeyDecision.Choice.ACCEPT_AND_REMEMBER);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelReturnsReject() throws Exception {
        HostKeyDecision decision = HostKeyPrompt.interactive(owner);
        HostKeyDecision.HostKeyPromptInfo info = new HostKeyDecision.HostKeyPromptInfo(
                "ops@bastion:2222", "ssh-rsa", "SHA256:cafebabecafebabe");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<HostKeyDecision.Choice> answer = pool.submit(() -> decision.decide(info));
        try {
            DialogPane pane = waitForOpenDialog();
            // 取消是标准 CANCEL_CLOSE 单例，文字由资源包给（默认英文 = "Cancel"）。
            fireByButtonData(pane, ButtonBar.ButtonData.CANCEL_CLOSE);
            assertThat(answer.get(5, TimeUnit.SECONDS))
                    .as("点取消必须得到 REJECT")
                    .isEqualTo(HostKeyDecision.Choice.REJECT);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * ⭐ 在「非 FX 线程调 decide、FX 线程 showAndWait」的窗口里，等弹窗真正上屏。
     *
     * <p>弹窗是独立的 Stage，挂在 {@link Window#getWindows()} 里；它的 scene 根是 DialogPane。
     * 轮询而不是 {@code waitFor} 带条件，是因为 FX 线程此刻在 {@code showAndWait} 的嵌套事件循环，
     * 我们要的是「那扇窗已经出现在窗口列表里」这一客观事实。
     */
    private static DialogPane waitForOpenDialog() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            DialogPane pane = openDialogPane();
            if (pane != null) {
                return pane;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("弹窗始终没出现 —— decide 可能没走到 showAndWait");
    }

    private static DialogPane openDialogPane() {
        for (Window window : Window.getWindows()) {
            if (window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane pane) {
                return pane;
            }
        }
        return null;
    }

    private static String windowTitle(DialogPane pane) {
        Window window = pane.getScene().getWindow();
        return window instanceof Stage stage ? stage.getTitle() : "";
    }

    /** 按文字 fire（「信任并记录」那颗自定义 OK_DONE 按钮）。 */
    private static void fireByText(DialogPane pane, String text) {
        onFxRun(() -> {
            Button button = buttonByText(buttonBarOf(pane), text);
            assertThat(button).as("找不到文字为「%s」的按钮", text).isNotNull();
            button.fire();
        });
    }

    /** 按 ButtonData fire（标准取消按钮，文字随语言变，按语义找更稳）。 */
    private static void fireByButtonData(DialogPane pane, ButtonBar.ButtonData data) {
        onFxRun(() -> {
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(t -> t.getButtonData() == data)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("没有 ButtonData=" + data + " 的按钮"));
            Button button = (Button) pane.lookupButton(type);
            assertThat(button).as("找不到 ButtonData=%s 的按钮", data).isNotNull();
            button.fire();
        });
    }

    private static ButtonBar buttonBarOf(DialogPane pane) {
        for (Node child : pane.getChildren()) {
            if (child instanceof ButtonBar bar) {
                return bar;
            }
        }
        throw new IllegalStateException("DialogPane 上没有 ButtonBar，JavaFX 结构变了");
    }

    private static Button buttonByText(ButtonBar bar, String text) {
        for (Node child : bar.getButtons()) {
            if (child instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    private static <T> T onFx(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                value.set(action.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (failure.get() != null) {
            if (failure.get() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure.get() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failure.get());
        }
        return value.get();
    }

    private static void onFxRun(Runnable action) {
        onFx(() -> {
            action.run();
            return null;
        });
    }
}
