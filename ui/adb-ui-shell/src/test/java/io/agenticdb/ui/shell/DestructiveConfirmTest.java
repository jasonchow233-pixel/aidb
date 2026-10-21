package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.5-7 / 4.5-17 的门禁：破坏性确认弹窗的默认按钮**不能**是破坏性动作。
 *
 * <p>按下去的后果是丢数据，所以回车必须落在「取消」上。
 * 既验 {@code ButtonData}（静态契约），也验 {@code onShown} 之后的真实节点。
 */
@ExtendWith(ApplicationExtension.class)
class DestructiveConfirmTest {

    @Start
    void start(Stage stage) {
        stage.show();
    }

    @Test
    void destructiveButtonIsNeverDefaultByButtonData() {
        DestructiveConfirm confirm = onFx(() -> DestructiveConfirm.of(
                "删除连接", "删除 Demo？", "不可恢复。", "删除"));

        assertThat(confirm.alert().getButtonTypes())
                .containsExactly(confirm.destructive(), confirm.cancel());
        assertThat(confirm.destructive().getButtonData().isDefaultButton())
                .as("破坏性按钮绝不能是默认按钮，否则回车即删")
                .isFalse();
        assertThat(confirm.cancel().getButtonData().isCancelButton())
                .as("取消按钮必须是 cancel 语义，ESC 才能命中它")
                .isTrue();
    }

    @Test
    void defaultButtonNodeIsCancel() {
        DestructiveConfirm confirm = onFx(() -> DestructiveConfirm.of(
                "未保存的修改", "有 3 处未保存修改", "关闭会丢弃这些修改。", "放弃修改"));

        // ⚠️ Monocle 无头下 alert.show() 会在 notifyMouse 上 NPE，
        //    所以这里用一个独立 DialogPane 造出 skin，直接验证默认按钮钉法。
        ButtonType destructive = new ButtonType("放弃修改", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        DialogPane pane = onFx(() -> {
            DialogPane dialogPane = new DialogPane();
            dialogPane.getButtonTypes().setAll(destructive, cancel);
            new Scene(dialogPane);
            dialogPane.applyCss();
            dialogPane.layout();
            return dialogPane;
        });

        Button cancelNode = (Button) pane.lookupButton(cancel);
        Button destructiveNode = (Button) pane.lookupButton(destructive);
        assertThat(cancelNode).isNotNull();
        assertThat(destructiveNode).isNotNull();

        onFxRun(() -> DestructiveConfirm.pinCancelAsDefault(pane, cancel, destructive));

        assertThat(cancelNode.isDefaultButton())
                .as("回车必须落在取消上")
                .isTrue();
        assertThat(destructiveNode.isDefaultButton())
                .as("破坏性按钮不得是默认按钮")
                .isFalse();
    }

    @Test
    void buttonLabelsAreKept() {
        DestructiveConfirm confirm = onFx(() -> DestructiveConfirm.of(
                "删除连接", "删除 Demo？", "不可恢复。", "删除"));

        assertThat(confirm.destructive().getText()).isEqualTo("删除");
        assertThat(confirm.cancel().getText()).isEqualTo("取消");
        assertThat(confirm.destructive().getButtonData().isCancelButton()).isFalse();
    }

    /** 在 FX 线程上执行并等待（不用 FxRobot —— Monocle 无头下它的鼠标通道会 NPE）。 */
    private static void onFxRun(Runnable action) {
        Platform.runLater(action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** JavaFX 控件只能在 FX 线程上创建。 */
    private static <T> T onFx(Supplier<T> supplier) {
        AtomicReference<T> ref = new AtomicReference<>();
        Platform.runLater(() -> ref.set(supplier.get()));
        WaitForAsyncUtils.waitForFxEvents();
        return ref.get();
    }
}
