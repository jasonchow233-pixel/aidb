package io.agenticdb.ui.shell;

import io.agenticdb.introspect.CheckDef;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import javafx.event.ActionEvent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Collection;
import java.util.Optional;

/**
 * ⭐ H4-b：新建 CHECK 约束 —— 弹窗（从新建表设计器里弹出来）。
 *
 * <p>名字可留空（SQLite 允许匿名 CHECK），表达式必填。预览里那一句就是
 * {@link StructureSqlBuilder#checkClause} 写进 CREATE TABLE 的子句 ——
 * 同一个出口，不是弹窗里另抄一份拼串。
 */
final class CheckDialog {

    private final Dialog<CheckDef> dialog = new Dialog<>();
    private final DbSession session;

    private final TextField name = new TextField();
    private final TextArea expression = new TextArea();
    private final TextArea preview = new TextArea();
    private final Label error = new Label();

    private CheckDialog(DbSession session) {
        this.session = session;
        buildUi();
        refreshPreview();
    }

    static CheckDialog create(DbSession session) {
        return new CheckDialog(session);
    }

    Optional<CheckDef> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null && !stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
        }
        DialogStyles.apply(dialog, owner);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    private void buildUi() {
        dialog.setTitle(I18n.t("check.dialog.title"));
        dialog.getDialogPane().setPrefWidth(DialogSizes.LONG_TEXT);
        dialog.getDialogPane().setMinWidth(DialogSizes.LONG_TEXT);

        name.setId("check-dialog-name");
        name.setPromptText(I18n.t("check.namePrompt"));
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        expression.setId("check-dialog-expression");
        expression.setPromptText(I18n.t("check.expressionPrompt"));
        expression.setPrefRowCount(3);
        expression.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        preview.setId("check-dialog-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(2);

        error.setId("check-dialog-error");
        error.getStyleClass().add("adb-error");

        VBox body = new VBox(10,
                labeled(I18n.t("column.name"), name),
                labeled(I18n.t("structure.check.expression"), expression),
                labeled(I18n.t("check.previewLabel"), preview),
                error);
        body.getStyleClass().add("adb-form");
        dialog.getDialogPane().setContent(body);

        ButtonType ok = new ButtonType(I18n.t("common.ok"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        dialog.getDialogPane().lookupButton(ok).addEventFilter(ActionEvent.ACTION, event -> {
            if (!validate()) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok ? current() : null);
    }

    private static VBox labeled(String text, javafx.scene.Node field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        return new VBox(4, label, field);
    }

    private CheckDef current() {
        return new CheckDef(name.getText(), expression.getText());
    }

    private boolean validate() {
        String problem = problem();
        error.setText(problem == null ? "" : problem);
        return problem == null;
    }

    private String problem() {
        if (expression.getText() == null || expression.getText().isBlank()) {
            return I18n.t("check.error.noExpression");
        }
        return null;
    }

    private void refreshPreview() {
        try {
            preview.setText(StructureSqlBuilder.checkClause(session, current()));
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : NewTableDialog.problemText(e));
        }
    }

    DialogPane content() {
        return dialog.getDialogPane();
    }

    Dialog<CheckDef> dialog() {
        return dialog;
    }
}