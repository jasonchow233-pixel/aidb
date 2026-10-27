package io.agenticdb.ui.shell;

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
import javafx.stage.Window;

/**
 * ⭐ P2-2：批量操作汇总弹窗 —— 状态徽标 + 可复制的失败明细。
 *
 * <p>与 {@link BatchSummary} 分工同 {@link ErrorDetailsDialog}：模型写什么，
 * 这里只负责摆。{@link #create(BatchSummary)} 供门禁断言，
 * {@link #show(Window, BatchSummary)} 才弹。
 */
final class BatchSummaryDialog {

    private BatchSummaryDialog() {
    }

    /** 构造但不显示；调用方负责 owner 与样式。 */
    static Alert create(BatchSummary summary) {
        Alert alert = new Alert(summary.allSucceeded()
                ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR);
        alert.setTitle(summary.title());
        alert.setHeaderText(summary.headline());

        Label status = new Label(summary.allSucceeded()
                ? "全部完成" : summary.failed() + " 项失败");
        status.setId("batch-summary-status");
        status.getStyleClass().add("adb-badge");
        status.getStyleClass().add(summary.allSucceeded()
                ? "adb-badge--muted" : "adb-error-kind");

        TextArea text = new TextArea(summary.body());
        text.setId("batch-summary-text");
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefColumnCount(64);
        text.setPrefRowCount(rowCount(summary));

        Button copy = new Button("复制报告");
        copy.setId("batch-summary-copy");
        copy.setOnAction(event -> copy(summary.copyText()));

        HBox actions = new HBox(copy);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(8, status, text, actions);
        VBox.setVgrow(text, Priority.ALWAYS);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setPrefWidth(DialogSizes.LONG_TEXT);
        alert.getDialogPane().getButtonTypes().setAll(ButtonType.OK);
        return alert;
    }

    /** 真的弹出来；没有窗口（单测）时静默跳过。 */
    static void show(Window owner, BatchSummary summary) {
        if (owner == null) {
            return;
        }
        Alert alert = create(summary);
        alert.initOwner(owner);
        DialogStyles.apply(alert, owner);
        alert.showAndWait();
    }

    private static int rowCount(BatchSummary summary) {
        String body = summary.body();
        int lines = body.isEmpty() ? 1 : body.split("\n", -1).length;
        return Math.min(16, Math.max(4, lines + 1));
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
