package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import javafx.event.ActionEvent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Collection;
import java.util.Optional;

/**
 * B5-S4 表注释 —— <b>弹窗</b>。
 *
 * <p>形态依据见 {@code docs/UI-FORMS-AND-STATES.md} §4：改一个已存在对象的属性 → 弹窗。
 *
 * <h2>为什么需要它</h2>
 * 表注释以前<strong>只写不读</strong>：建表时能填，建完就再也看不见、改不了
 * （{@code TableStructure} 里连字段都没有）。
 * 结果就是「填了等于没填」—— 用户填完注释，界面上再也看不到它。
 *
 * <p>现在：<b>读得回来、改得了、清得掉</b>，并且有实时 SQL 预览
 * （PostgreSQL 是 {@code COMMENT ON TABLE}，MySQL 是 {@code ALTER TABLE … COMMENT =}）。
 */
final class TableCommentDialog {

    private final Dialog<String> dialog = new Dialog<>();
    private final DbSession session;
    private final TableStructure table;
    private final TextArea comment = new TextArea();
    private final TextArea preview = new TextArea();
    private final Label error = new Label();

    private TableCommentDialog(DbSession session, TableStructure table) {
        this.session = session;
        this.table = table;
        buildUi();
        refreshPreview();
    }

    static TableCommentDialog create(DbSession session, TableStructure table) {
        return new TableCommentDialog(session, table);
    }

    Optional<String> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null && !stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
            // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空。
        DialogStyles.apply(dialog, owner, "table-comment");
        }
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    private void buildUi() {
        dialog.setTitle(I18n.t("structure.tableComment.button"));
        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.FORM);
        dialog.getDialogPane().setMinWidth(DialogSizes.FORM);

        dialog.setHeaderText(I18n.t("comment.header", table == null ? "?" : table.name()));

        comment.setId("table-comment-dialog-text");
        comment.setPromptText(I18n.t("comment.prompt"));
        comment.setPrefRowCount(3);
        comment.setText(table == null ? "" : table.comment());
        comment.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        preview.setId("table-comment-dialog-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(3);

        error.setId("table-comment-dialog-error");
        error.getStyleClass().add("adb-error");

        VBox body = new VBox(10,
                labeled(I18n.t("structure.tableComment.button"), comment),
                labeled(I18n.t("structure.preview.execute"), preview),
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
        dialog.setResultConverter(button -> button == ok
                ? (comment.getText() == null ? "" : comment.getText().trim())
                : null);
    }

    private static VBox labeled(String text, javafx.scene.Node field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        return new VBox(4, label, field);
    }

    private boolean validate() {
        String problem = problem();
        error.setText(problem == null ? "" : problem);
        return problem == null;
    }

    private String problem() {
        if (table == null) {
            return I18n.t("ddl.pickTable");
        }
        if (!session.dialect().supportsTableComment()) {
            return I18n.t("comment.unsupported", session.dialect().displayName());
        }
        return null;
    }

    private void refreshPreview() {
        if (table == null) {
            preview.setText("");
            return;
        }
        try {
            preview.setText(StructureSqlBuilder
                    .setTableComment(session, table, comment.getText()).sql());
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
