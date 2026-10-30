package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * ⭐ P2-2：错误详情弹窗 —— 分类徽标 + 可复制的完整说明。
 *
 * <p>与 {@link ErrorDetails} 分工：模型负责「写什么」，这里只负责「怎么摆」。
 * {@link #create(ErrorDetails)} 不显示，门禁能直接查 id 与文本；
 * {@link #show(Window, ErrorDetails)} 才真的弹模态框。
 */
final class ErrorDetailsDialog {

    private ErrorDetailsDialog() {
    }

    /** 构造但不显示；调用方负责 owner 与样式。 */
    static Alert create(ErrorDetails details) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(details.title());
        alert.setHeaderText(details.headline());

        Label kind = new Label(details.kindLabel());
        kind.setId("error-details-kind");
        kind.getStyleClass().addAll("adb-badge", "adb-error-kind");

        TextArea text = new TextArea(details.body());
        text.setId("error-details-text");
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("adb-error-details");
        text.setPrefColumnCount(64);
        text.setPrefRowCount(rowCount(details));

        Button copy = new Button(I18n.t("error.dialog.copyDetails"));
        copy.setId("error-details-copy");
        copy.setOnAction(event -> copy(details.copyText()));

        HBox actions = new HBox(copy);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8, kind, text, actions);
        VBox.setVgrow(text, Priority.ALWAYS);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(DialogSizes.LONG_TEXT);
        alert.getDialogPane().getButtonTypes().setAll(ButtonType.OK);
        return alert;
    }

    /** 真的弹出来；没有窗口（单测）时静默跳过，避免模态框把测试挂住。 */
    static void show(Window owner, ErrorDetails details) {
        if (owner == null) {
            return;
        }
        Alert alert = create(details);
        alert.initOwner(owner);
        // ⭐ U7：Javadoc 一直写「弹模态框」，但 Alert 的默认模态是 NONE ——
        //    initOwner 不会顺带改模态。于是错误框能被主窗口盖住、能叠好几种，
        //    而验收截图按模态找窗口，p2-error 拍到的其实是主窗口（与 p0-shell 同 md5）。
        alert.initModality(Modality.APPLICATION_MODAL);
        DialogStyles.apply(alert, owner);
        alert.showAndWait();
    }

    /**
     * ⭐ 只给验收截图用（ADB_ERROR_DEMO）：摆出来但不进嵌套事件循环。
     *
     * <p>{@link #show} 走的 {@code showAndWait} 会开一个嵌套事件循环，而这条路径实测
     * 间歇性失灵（6 次冷启 2 次「window count=1」—— 弹窗压根没进
     * {@code Window.getWindows()}，于是 p2-error 拍成主窗口，与 p0-shell 同 md5，
     * 被 U7 去重门禁抓为假帧）。摆态只要「拍的那一瞬间它是可见的」，
     * 不需要阻塞业务线程，所以这里用 {@code show()}。
     */
    static void showForShots(Window owner, ErrorDetails details) {
        if (owner == null) {
            return;
        }
        Alert alert = create(details);
        alert.initOwner(owner);
        alert.initModality(Modality.APPLICATION_MODAL);
        DialogStyles.apply(alert, owner);
        alert.show();
        System.out.println("[screenshot] error dialog shown (non-blocking pose)");
    }

    private static int rowCount(ErrorDetails details) {
        String body = details.body();
        int lines = body.isEmpty() ? 1 : body.split("\n", -1).length;
        return Math.min(16, Math.max(4, lines + 1));
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
