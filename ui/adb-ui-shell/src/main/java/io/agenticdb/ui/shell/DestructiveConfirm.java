package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.DialogEvent;

/**
 * 破坏性确认弹窗。
 *
 * <h2>为什么需要这个类</h2>
 * 早期版本里每一处确认弹窗都手搓两个 {@link ButtonType}：
 *
 * <pre>
 * new ButtonType("删除", ButtonBar.ButtonData.OK_DONE)   ← 它是默认按钮
 * new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE)
 * </pre>
 *
 * {@code OK_DONE} 语义上就是「默认且确认」，于是**回车 = 删除**。
 * 删除连接、放弃修改、放弃并退出三处全中招 —— 用户按回车确认习惯动作，
 * 结果是丢数据。这不是理论风险，是能在键盘上复现的数据丢失路径。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li>破坏性按钮的 {@link ButtonBar.ButtonData} 用 {@code OTHER} —— 它不是默认按钮。</li>
 *   <li>取消按钮用 {@code CANCEL_CLOSE}，并在 {@code onShown} 里显式设为 default。</li>
 *   <li>结果转换器显式返回被点击的按钮；按 ESC / 直接关闭得到 {@code null}，
 *       一律按「取消」处理。</li>
 * </ol>
 */
public final class DestructiveConfirm {

    private final Alert alert;
    private final ButtonType destructive;
    private final ButtonType cancel;

    private DestructiveConfirm(String title, String header, String content,
                               String destructiveLabel) {
        // ⭐ OTHER：不是默认按钮，因此回车不会落在它上面。
        this.destructive = new ButtonType(destructiveLabel, ButtonBar.ButtonData.OTHER);
        this.cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        this.alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getButtonTypes().setAll(destructive, cancel);
        alert.setResultConverter(type -> type);
        // ⭐ 必须在 skin 创建之后（shown 之后）改默认按钮，
        //    否则 ButtonBar 会按 ButtonData 重新算一遍，把设置覆盖掉。
        alert.setOnShown(event -> applyDefaultButton());
    }

    /**
     * 构造一个破坏性确认弹窗。
     *
     * @param title            窗口标题
     * @param header           主问题（一句话说清要发生什么）
     * @param content          后果说明（说清会丢什么、不会丢什么）
     * @param destructiveLabel 破坏性按钮文案，例如「删除」「放弃修改」
     */
    public static DestructiveConfirm of(String title, String header, String content,
                                        String destructiveLabel) {
        return new DestructiveConfirm(title, header, content, destructiveLabel);
    }

    /** 显示并返回结果：只有用户点了破坏性按钮才返回 {@code true}。 */
    public boolean confirmed() {
        ButtonType result = alert.showAndWait().orElse(null);
        return result == destructive;
    }

    public Alert alert() {
        return alert;
    }

    public ButtonType destructive() {
        return destructive;
    }

    public ButtonType cancel() {
        return cancel;
    }

    /**
     * 把默认按钮钉在「取消」上。
     *
     * <p>必须是 skin 创建之后才有效（{@code lookupButton} 依赖 skin）。
     * 正常路径由 {@code onShown} 触发；单测里可以手动调用。
     */
    public void applyDefaultButton() {
        pinCancelAsDefault(alert.getDialogPane(), cancel, destructive);
    }

    /**
     * 把默认按钮钉在「取消」上。
     *
     * <p>要求 {@code pane} 已有 skin（也就是 {@code lookupButton} 拿得到节点）。
     * 正常路径由 {@code onShown} 触发；单测可以对一个独立 {@link DialogPane} 直接调用。
     */
    public static void pinCancelAsDefault(DialogPane pane, ButtonType cancel,
                                          ButtonType destructive) {
        Button cancelButton = (Button) pane.lookupButton(cancel);
        Button destructiveButton = (Button) pane.lookupButton(destructive);
        if (cancelButton != null) {
            cancelButton.setDefaultButton(true);
        }
        if (destructiveButton != null) {
            destructiveButton.setDefaultButton(false);
        }
    }
}
