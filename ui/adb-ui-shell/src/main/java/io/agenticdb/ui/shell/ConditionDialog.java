package io.agenticdb.ui.shell;

import io.agenticdb.introspect.FilterCondition;
import io.agenticdb.ui.I18n;
import io.agenticdb.introspect.FilterOperator;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/** 条件构造器：列 + 操作符 + 值；不提供 IN，IN 属于列头值筛选。 */
final class ConditionDialog {

    private ConditionDialog() {
    }

    static Optional<FilterCondition> show(Window owner, TableSchema schema,
                                          Collection<String> stylesheets) {
        Dialog<FilterCondition> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("cond.title"));
        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.FORM);
        dialog.getDialogPane().setMinWidth(DialogSizes.FORM);

        dialog.setHeaderText(I18n.t("cond.header"));
        if (owner != null) {
            dialog.initOwner(owner);
        }

        ButtonType add = new ButtonType(I18n.t("fk.add"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(add, cancel);

        ComboBox<String> columnBox = new ComboBox<>();
        columnBox.getItems().setAll(schema.columns().stream().map(ColumnDef::name).toList());
        columnBox.getSelectionModel().selectFirst();
        columnBox.setMaxWidth(Double.MAX_VALUE);

        ComboBox<FilterOperator> operatorBox = new ComboBox<>();
        operatorBox.getItems().setAll(Arrays.stream(FilterOperator.values())
                .filter(operator -> operator != FilterOperator.IN)
                .toList());
        operatorBox.getSelectionModel().select(FilterOperator.EQ);
        operatorBox.setMaxWidth(Double.MAX_VALUE);

        TextField value = new TextField();
        value.setPromptText(I18n.t("var.valuePrompt"));
        TextField second = new TextField();
        second.setPromptText(I18n.t("cond.upperBound"));

        Label error = new Label();
        error.getStyleClass().add("adb-grid__state-text--error");
        error.setWrapText(true);

        Label to = new Label(I18n.t("cond.to"));
        to.visibleProperty().bind(second.visibleProperty());
        to.managedProperty().bind(second.visibleProperty());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 8, 4, 8));
        grid.addRow(0, new Label(I18n.t("structure.col.columns")), columnBox);
        grid.addRow(1, new Label(I18n.t("cond.operator")), operatorBox);
        grid.addRow(2, new Label(I18n.t("var.valuePrompt")), value);
        grid.addRow(3, to, second);
        grid.add(error, 0, 4, 2, 1);
        GridPane.setHgrow(columnBox, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(operatorBox, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(value, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(second, javafx.scene.layout.Priority.ALWAYS);
        dialog.getDialogPane().setContent(grid);
        // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空，
        //    弹窗会退回 JavaFX 默认皮肤（没有边框、按钮全是默认灰钮）。
        DialogStyles.apply(dialog, owner, "condition");

        operatorBox.setOnAction(event -> updateValueFields(operatorBox.getValue(), value, second));
        columnBox.setOnAction(event -> updatePrompt(columnBox.getValue(), schema, value));
        updateValueFields(operatorBox.getValue(), value, second);

        dialog.getDialogPane().lookupButton(add).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                parse(columnBox.getValue(), operatorBox.getValue(),
                        value.getText(), second.getText(), schema);
                error.setText("");
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
                event.consume();
            }
        });

        dialog.setResultConverter(button -> {
            if (button != add) {
                return null;
            }
            return parse(columnBox.getValue(), operatorBox.getValue(),
                    value.getText(), second.getText(), schema);
        });

        return dialog.showAndWait();
    }

    private static void updateValueFields(FilterOperator operator, TextField value,
                                          TextField second) {
        boolean accepts = operator != null && operator.acceptsValue();
        boolean between = operator != null && operator.acceptsSecondValue();
        value.setDisable(!accepts);
        value.setVisible(accepts);
        value.setManaged(accepts);
        second.setVisible(between);
        second.setManaged(between);
        if (!accepts) {
            value.clear();
            second.clear();
        }
    }

    private static void updatePrompt(String columnName, TableSchema schema, TextField field) {
        ColumnDef column = schema.columns().stream()
                .filter(candidate -> candidate.name().equals(columnName))
                .findFirst()
                .orElse(null);
        field.setPromptText(column == null ? I18n.t("var.valuePrompt") : prompt(column));
    }

    private static String prompt(ColumnDef column) {
        return switch (column.type()) {
            case NUMBER -> I18n.t("cond.kindNumber");
            case BOOLEAN -> "1 / 0 / true / false";
            case DATE -> "YYYY-MM-DD";
            case TIMESTAMP -> "YYYY-MM-DD HH:MM:SS";
            case TIME -> "HH:MM:SS";
            default -> I18n.t("cond.kindText");
        };
    }

    private static FilterCondition parse(String columnName, FilterOperator operator,
                                         String raw, String rawSecond, TableSchema schema) {
        ColumnDef column = schema.columns().stream()
                .filter(candidate -> candidate.name().equals(columnName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(I18n.t("cond.error.noColumn")));
        if (operator == null) {
            throw new IllegalArgumentException(I18n.t("cond.error.noOperator"));
        }
        if (!operator.acceptsValue()) {
            return switch (operator) {
                case IS_NULL -> FilterCondition.isNull(column.name());
                case IS_NOT_NULL -> FilterCondition.isNotNull(column.name());
                default -> throw new IllegalArgumentException(I18n.t("cond.error.noValueNeeded"));
            };
        }

        String text = raw == null ? "" : raw;
        if (text.isEmpty() && requiresNonEmpty(column.type())) {
            throw new IllegalArgumentException(I18n.t("cond.error.invalidValue", prompt(column)));
        }
        Object first = CellValueParser.parse(column, text);
        if (operator.acceptsSecondValue()) {
            String secondText = rawSecond == null ? "" : rawSecond;
            if (secondText.isEmpty() && requiresNonEmpty(column.type())) {
                throw new IllegalArgumentException(I18n.t("cond.error.noUpperBound"));
            }
            Object second = CellValueParser.parse(column, secondText);
            return FilterCondition.between(column.name(), first, second);
        }
        return FilterCondition.of(column.name(), operator, first);
    }

    private static boolean requiresNonEmpty(ColumnType type) {
        return type != ColumnType.TEXT && type != ColumnType.JSON
                && type != ColumnType.UUID;
    }
}
