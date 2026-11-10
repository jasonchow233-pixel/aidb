package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.HostKeyDecision;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.concurrent.CompletableFuture;

/**
 * ⭐ SSH 未知主机密钥的交互式信任决策（Navicat 同款弹窗）。
 *
 * <p>{@link HostKeyDecision} 的「交互式」实现：跳板机递来一把没记过的密钥时，在 FX 线程上
 * 弹一张窗，把指纹摆给用户肉眼比对，由用户决定「信任并记录」还是「取消」。弹窗只负责问；
 * 真正把密钥写进 known_hosts 是 {@code SshTunnel} 在用户同意后做的事（走 TOFU 记录同源路径）。
 *
 * <p>线程安全：MINA 在自己的 NIO 工作线程上调用 {@code decide}，本类把它派发到 FX 线程、
 * 阻塞那条工作线程直到用户作答 —— 不会死锁，因为调用方从来不是 FX 线程。若真的在 FX 线程上
 * 被调用（极端情况），则直接同步弹窗，行为仍是模态等待用户。
 */
final class HostKeyPrompt {

    private HostKeyPrompt() {
    }

    /** 返回一个交互式决策；{@code owner} 是拥有者窗口（用来取主题与定位）。 */
    static HostKeyDecision interactive(Window owner) {
        return info -> {
            if (Platform.isFxApplicationThread()) {
                return show(owner, info);
            }
            CompletableFuture<HostKeyDecision.Choice> future = new CompletableFuture<>();
            Platform.runLater(() -> future.complete(show(owner, info)));
            try {
                return future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return HostKeyDecision.Choice.REJECT;
            } catch (java.util.concurrent.ExecutionException e) {
                return HostKeyDecision.Choice.REJECT;
            }
        };
    }

    private static HostKeyDecision.Choice show(Window owner, HostKeyDecision.HostKeyPromptInfo info) {
        Dialog<HostKeyDecision.Choice> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("ssh.hostkey.title"));
        dialog.setHeaderText(I18n.t("ssh.hostkey.header"));

        Label message = new Label(I18n.t("ssh.hostkey.message",
                info.hostPort(), info.keyType(), info.fingerprintSha256()));
        message.setWrapText(true);
        message.getStyleClass().add("adb-muted");
        VBox content = new VBox(message);
        content.setSpacing(10);
        content.setPrefWidth(440);
        content.getStyleClass().add("adb-dialog__body");
        dialog.getDialogPane().setContent(content);

        // ⭐ 主按钮走 OK_DONE（实心强调色、排在右侧）；取消用标准 CANCEL 单例，
        //    由 DialogStyles 收进资源包、并按 Navicat 口径排到左侧。
        ButtonType trust = new ButtonType(I18n.t("ssh.hostkey.accept"),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(trust, cancel);

        dialog.setResultConverter(button -> {
            if (button != null && button.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                return HostKeyDecision.Choice.ACCEPT_AND_REMEMBER;
            }
            return HostKeyDecision.Choice.REJECT;
        });

        // ⭐ U20/D-66：弹窗必须套应用主题，否则退回 Modena 灰皮肤。
        DialogStyles.apply(dialog, owner);
        return dialog.showAndWait().orElse(HostKeyDecision.Choice.REJECT);
    }
}
