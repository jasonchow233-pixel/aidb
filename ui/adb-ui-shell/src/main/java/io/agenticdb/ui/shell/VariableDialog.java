package io.agenticdb.ui.shell;

import io.agenticdb.sql.SqlParameters;
import io.agenticdb.ui.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⭐ Q2：变量（参数）面板。
 *
 * <p>语句里的 {@code ?} 与 {@code :name} 会被列出来，逐个填值。
 * 值最终走 {@link java.sql.PreparedStatement} **参数化绑定**，
 * 不做字符串替换 —— 所以不存在注入。
 */
final class VariableDialog {

    /**
     * 用户填的值。
     *
     * @param named      命名参数（名字 → 值）
     * @param positional 位置参数的值（按出现顺序）
     */
    record Bound(Map<String, String> named, List<String> positional) {
    }

    private final List<SqlParameters.Parameter> parameters;
    private final List<TextField> fields = new ArrayList<>();
    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content;
    private boolean confirmed;

    VariableDialog(List<SqlParameters.Parameter> parameters) {
        this.parameters = parameters;

        Label hint = new Label(I18n.t("var.hint"));
        hint.getStyleClass().add("adb-muted");
        hint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints inputs = new ColumnConstraints();
        inputs.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, inputs);

        for (int i = 0; i < parameters.size(); i++) {
            SqlParameters.Parameter parameter = parameters.get(i);
            Label label = new Label(parameter.label());
            label.getStyleClass().add("adb-muted");
            TextField field = new TextField();
            field.setId("variable-" + i);
            field.setPromptText(parameter.named() ? I18n.t("var.valuePrompt")
                    : I18n.t("var.positionalPrompt", i + 1));
            grid.add(label, 0, i);
            grid.add(field, 1, i);
            fields.add(field);
        }

        Button ok = new Button(I18n.t("common.ok"));
        ok.setId("variable-ok");
        ok.getStyleClass().addAll("adb-button", "adb-button--primary");
        ok.setOnAction(event -> {
            confirmed = true;
            close();
        });

        Button cancel = new Button(I18n.t("common.cancel"));
        cancel.setId("variable-cancel");
        cancel.getStyleClass().addAll("adb-button", "adb-button--ghost");
        cancel.setOnAction(event -> close());

        // Navicat 口径（§6.7）：取消在左、主动作贴右下。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spacer, cancel, ok);
        actions.setAlignment(Pos.CENTER_RIGHT);

        content = new VBox(10, hint, grid, actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle(I18n.t("query.params"));

        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。

        dialog.getDialogPane().setPrefWidth(DialogSizes.FORM);

        dialog.getDialogPane().setMinWidth(DialogSizes.FORM);

        dialog.setHeaderText(I18n.t("var.summary", parameters.size()));
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);
    }

    VBox content() {
        return content;
    }

    /**
     * 打开变量面板。
     *
     * @param sql 当前语句
     * @return 用户填好的值；没有参数或用户取消 → {@code null}
     */
    static Bound prompt(String sql, javafx.stage.Window owner) {
        List<SqlParameters.Parameter> parameters = SqlParameters.detect(sql);
        if (parameters.isEmpty()) {
            return null;
        }
        VariableDialog panel = new VariableDialog(parameters);
        DialogStyles.apply(panel.dialog, owner);
        panel.dialog.showAndWait();
        if (!panel.confirmed) {
            return null;
        }
        Map<String, String> named = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < panel.parameters.size(); i++) {
            SqlParameters.Parameter parameter = panel.parameters.get(i);
            String value = panel.fields.get(i).getText() == null
                    ? "" : panel.fields.get(i).getText();
            if (parameter.named()) {
                named.put(parameter.name(), value);
            } else {
                positional.add(value);
            }
        }
        return new Bound(named, positional);
    }

    private void close() {
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }
}
