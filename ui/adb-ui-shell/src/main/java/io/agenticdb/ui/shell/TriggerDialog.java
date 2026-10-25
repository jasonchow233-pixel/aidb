package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * B5-S3 新建触发器 —— <b>弹窗</b>（从结构页里弹出来）。
 *
 * <p>形态依据见 {@code docs/UI-FORMS-AND-STATES.md} §4。
 *
 * <h2>触发器体怎么写</h2>
 * 触发器体是**一段 SQL**，各家方言语法不同：
 * <ul>
 *   <li><b>SQLite / MySQL</b>：{@code BEGIN … END} 里的内容
 *       （例如 {@code UPDATE t SET a = 1 WHERE id = NEW.id}）；</li>
 *   <li><b>PostgreSQL</b>：返回 {@code trigger} 的<b>函数体</b>
 *       （例如 {@code BEGIN UPDATE t SET a = 1; RETURN NEW; END}）。</li>
 * </ul>
 * 弹窗里有**实时 SQL 预览** —— 方言差异一眼可见，不用猜。
 */
final class TriggerDialog {

    /**
     * 触发器规格：名字 / 时机 / 事件 / 体。
     *
     * @param timing BEFORE / AFTER
     * @param event  INSERT / UPDATE / DELETE
     */
    record Spec(String name, String timing, String event, String body) {
    }

    private final Dialog<Spec> dialog = new Dialog<>();
    private final DbSession session;
    private final TableStructure table;

    private final TextField name = new TextField();
    private final ComboBox<String> timing = new ComboBox<>();
    private final ComboBox<String> event = new ComboBox<>();
    private final TextArea body = new TextArea();
    private final TextArea preview = new TextArea();
    private final Label error = new Label();

    private TriggerDialog(DbSession session, TableStructure table) {
        this.session = session;
        this.table = table;
        buildUi();
        refreshPreview();
    }

    static TriggerDialog create(DbSession session, TableStructure table) {
        return new TriggerDialog(session, table);
    }

    Optional<Spec> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null && !stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
            // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空。
        DialogStyles.apply(dialog, owner);
        }
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    private void buildUi() {
        dialog.setTitle("新建触发器");
        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.LONG_TEXT);
        dialog.getDialogPane().setMinWidth(DialogSizes.LONG_TEXT);

        dialog.setHeaderText("在 " + (table == null ? "?" : table.name()) + " 上新建触发器");

        name.setId("trigger-dialog-name");
        name.setPromptText("触发器名");
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        timing.setId("trigger-dialog-timing");
        timing.getItems().addAll("BEFORE", "AFTER");
        timing.getSelectionModel().select("AFTER");
        timing.valueProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        event.setId("trigger-dialog-event");
        event.getItems().addAll("INSERT", "UPDATE", "DELETE");
        event.getSelectionModel().select("INSERT");
        event.valueProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        body.setId("trigger-dialog-body");
        body.setPromptText("触发器体（SQL）");
        body.setPrefRowCount(5);
        body.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        preview.setId("trigger-dialog-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(5);

        error.setId("trigger-dialog-error");
        error.getStyleClass().add("adb-error");

        // ⭐ 提示文案由**方言层**给（PostgreSQL 写函数体、其它写 SQL 体）——
        //    UI 不做方言判断（那会破 A1 的承诺）。
        Label bodyHint = new Label(session.dialect().triggerBodyHint());
        bodyHint.getStyleClass().add("adb-muted");
        bodyHint.setWrapText(true);

        VBox bodyBox = new VBox(10,
                labeled("名称", name),
                new HBox(10, labeled("时机", timing), labeled("事件", event)),
                labeled("触发器体", bodyBoxWithHint(body, bodyHint)),
                labeled("将执行", preview),
                error);
        bodyBox.getStyleClass().add("adb-form");
        dialog.getDialogPane().setContent(bodyBox);

        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        dialog.getDialogPane().lookupButton(ok).addEventFilter(ActionEvent.ACTION, event -> {
            if (!validate()) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok
                ? new Spec(name.getText() == null ? "" : name.getText().trim(),
                timing.getValue(), event.getValue(), currentBody())
                : null);
    }

    private static VBox bodyBoxWithHint(javafx.scene.Node field, Label hint) {
        VBox box = new VBox(4, field, hint);
        return box;
    }

    private static VBox labeled(String text, javafx.scene.Node field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        return new VBox(4, label, field);
    }

    private String currentBody() {
        return body.getText() == null ? "" : body.getText().trim();
    }

    private boolean validate() {
        String problem = problem();
        error.setText(problem == null ? "" : problem);
        return problem == null;
    }

    private String problem() {
        if (name.getText() == null || name.getText().isBlank()) {
            return "触发器名不能为空";
        }
        if (currentBody().isEmpty()) {
            return "触发器体不能为空";
        }
        return null;
    }

    private void refreshPreview() {
        if (table == null) {
            preview.setText("");
            return;
        }
        try {
            preview.setText(StructureSqlBuilder.createTrigger(session, table,
                    name.getText(), timing.getValue(), event.getValue(), currentBody()).sql());
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
