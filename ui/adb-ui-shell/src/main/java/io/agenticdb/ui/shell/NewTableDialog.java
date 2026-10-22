package io.agenticdb.ui.shell;

import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.ui.Icons;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * T1 新建表 —— <b>弹窗</b>，不是标签页。
 *
 * <p>形态依据见 {@code docs/UI-FORMS-AND-STATES.md} §4：建表是「改一次、然后关掉」的
 * 一次性动作。做成标签页会留下一个<b>关不掉</b>的「空表」标签 —— 表还没建呢，
 * 标签已经在那儿了；建完也不知该不该关。Navicat 也是弹窗。
 *
 * <h2>与结构页共用的部分</h2>
 * 列定义、主键摆放顺序、注释落点全部交给 {@link StructureSqlBuilder} 和方言，
 * 这里只收集输入并把生成的 SQL <b>实时</b>显示出来。
 */
final class NewTableDialog {

    private final Dialog<StructurePlan> dialog = new Dialog<>();
    private final DbSession session;
    private final List<String> existingTables;

    private final TextField name = new TextField();
    private final TextField tableComment = new TextField();
    private final TextArea preview = new TextArea();
    private final Label error = new Label();
    private final ObservableList<ColumnRow> rows = FXCollections.observableArrayList();

    private NewTableDialog(DbSession session, List<String> existingTables) {
        this.session = session;
        this.existingTables = existingTables == null ? List.of() : List.copyOf(existingTables);
        buildUi();
        addDefaultRow();
        refreshPreview();
    }

    static NewTableDialog create(DbSession session, List<String> existingTables) {
        return new NewTableDialog(session, existingTables);
    }

    /** 显示并返回建表计划；用户取消或校验不通过时为 {@link Optional#empty()}。 */
    Optional<StructurePlan> show(Window owner, Collection<String> stylesheets) {
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

    // ─────────────────────────── 界面 ───────────────────────────

    private void buildUi() {
        dialog.setTitle("新建表");
        // ⭐ V-11：弹窗宽度走三档规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);
        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        dialog.setHeaderText("在当前连接里新建一张表");

        name.setId("new-table-name");
        name.setPromptText("表名");
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        boolean commentSupported = session.dialect().supportsTableComment();
        tableComment.setId("new-table-comment");
        tableComment.setPromptText(commentSupported
                ? "表注释（可空）" : session.dialect().displayName() + " 不支持表注释");
        tableComment.setDisable(!commentSupported);
        tableComment.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        TableView<ColumnRow> table = buildColumnsTable();
        table.setId("new-table-columns");
        // ⚠️ TableView 的默认首选高度很大（400+）—— 弹窗会被撑到比屏幕还高，
        //    底部的「创建 / 取消」跑到屏幕外，用户点不到。固定行高 + 限高。
        table.setFixedCellSize(30);
        table.setPrefHeight(200);
        table.setMinHeight(120);

        Button add = IconButtons.of(Icons.Icon.ADD, "添加列");
        add.setId("new-table-add-column");
        add.setOnAction(event -> addRow());
        Button remove = IconButtons.of(Icons.Icon.DELETE, "删除列");
        remove.setId("new-table-remove-column");
        remove.setOnAction(event -> {
            ColumnRow selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            rows.remove(selected);
            refreshPreview();
        });
        HBox rowButtons = new HBox(8, add, remove);
        rowButtons.setAlignment(Pos.CENTER_LEFT);

        preview.setId("new-table-preview");
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setPrefRowCount(8);
        preview.setPromptText("这里会实时显示将要执行的 CREATE TABLE");

        error.setId("new-table-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Label header = new Label("表名");
        header.getStyleClass().add("adb-form__label");

        VBox body = new VBox(10,
                row(header, name),
                row(new Label("表注释"), tableComment),
                new Label("列"),
                table,
                rowButtons,
                new Label("将要执行的 SQL"),
                preview,
                error);
        body.setPadding(new Insets(16, 8, 8, 8));
        VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);
        dialog.setResizable(true);

        ButtonType ok = new ButtonType("创建", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        // ⭐ 校验失败必须把弹窗留在原地：用户改一个字符就能继续，
        //    关掉再重开等于把刚填的列全丢了（与结构页 4.5-12 同一条规则）。
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            StructurePlan plan = buildPlan();
            if (plan == null) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok ? buildPlan() : null);

        rows.addListener((ListChangeListener<ColumnRow>) change -> refreshPreview());
    }

    private static HBox row(Label label, TextField field) {
        HBox box = new HBox(10, label, field);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        label.setMinWidth(56);
        return box;
    }

    private TableView<ColumnRow> buildColumnsTable() {
        TableView<ColumnRow> table = new TableView<>(rows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().setAll(
                textColumn("列名", "name", 150, row -> row.name),
                textColumn("类型", "type", 130, row -> row.type),
                booleanColumn("主键", 60, row -> row.primaryKey),
                booleanColumn("可空", 60, row -> row.nullable),
                booleanColumn("唯一", 60, row -> row.unique),
                textColumn("默认值", "defaultValue", 110, row -> row.defaultValue),
                textColumn("注释", "comment", 160, row -> row.comment));
        return table;
    }

    private static TableColumn<ColumnRow, String> textColumn(
            String title, String id, double width,
            java.util.function.Function<ColumnRow, StringProperty> property) {
        TableColumn<ColumnRow, String> column = new TableColumn<>(title);
        column.setId("new-table-column-" + id);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> property.apply(cell.getValue()));
        column.setCellFactory(TextFieldTableCell.forTableColumn());
        column.setOnEditCommit(event -> {
            property.apply(event.getRowValue()).set(event.getNewValue() == null
                    ? "" : event.getNewValue().trim());
            event.getTableView().refresh();
        });
        return column;
    }

    private static TableColumn<ColumnRow, Boolean> booleanColumn(
            String title, double width,
            java.util.function.Function<ColumnRow, BooleanProperty> property) {
        TableColumn<ColumnRow, Boolean> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> property.apply(cell.getValue()));
        column.setCellFactory(CheckBoxTableCell.forTableColumn(column));
        column.setEditable(true);
        return column;
    }

    private void addDefaultRow() {
        // ⭐ 默认给一行 id 主键 —— 空表开出来是空的，用户得先想「第一列叫什么」，
        //    而九成的表第一列就是主键 id。
        ColumnRow row = new ColumnRow(this::refreshPreview);
        row.name.set("id");
        row.type.set("INTEGER");
        row.primaryKey.set(true);
        row.nullable.set(false);
        rows.add(row);
    }

    private void addRow() {
        ColumnRow row = new ColumnRow(this::refreshPreview);
        row.name.set("column" + (rows.size() + 1));
        row.type.set("TEXT");
        rows.add(row);
        refreshPreview();
    }

    // ─────────────────────────── 计划与校验 ───────────────────────────

    /** 当前的建表计划；输入不合法时返回 {@code null} 并把原因显示在弹窗里。 */
    StructurePlan buildPlan() {
        List<ColumnSpec> columns = new ArrayList<>();
        for (ColumnRow row : rows) {
            columns.add(row.toSpec());
        }
        try {
            StructurePlan plan = StructureSqlBuilder.createTable(session, name.getText(),
                    columns, commentText(), existingTables);
            hideError();
            return plan;
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return null;
        }
    }

    /** 当前输入的问题；{@code null} 表示可以建。供单测直接断言规则。 */
    static String problem(DbSession session, String rawName, List<ColumnSpec> columns,
                          List<String> existingTables) {
        try {
            StructureSqlBuilder.createTable(session, rawName, columns, null, existingTables);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private String commentText() {
        return session.dialect().supportsTableComment() ? tableComment.getText() : null;
    }

    private void refreshPreview() {
        StructurePlan plan;
        try {
            List<ColumnSpec> columns = rows.stream().map(ColumnRow::toSpec).toList();
            plan = StructureSqlBuilder.createTable(session, name.getText(), columns,
                    commentText(), existingTables);
        } catch (IllegalArgumentException e) {
            preview.setText("");
            return;
        }
        preview.setText(plan.sql());
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
    }

    private void hideError() {
        error.setText("");
        error.setVisible(false);
        error.setManaged(false);
    }

    // ─────────────────────────── 门禁用 ───────────────────────────

    Dialog<StructurePlan> dialog() {
        return dialog;
    }

    ObservableList<ColumnRow> rows() {
        return rows;
    }

    TextField nameField() {
        return name;
    }

    TextArea previewArea() {
        return preview;
    }

    // ─────────────────────────── 行模型 ───────────────────────────

    /** 一行列定义。用 JavaFX 属性是为了让表格能直接编辑。 */
    static final class ColumnRow {

        final StringProperty name = new SimpleStringProperty("");
        final StringProperty type = new SimpleStringProperty("TEXT");
        final BooleanProperty primaryKey = new SimpleBooleanProperty(false);
        final BooleanProperty nullable = new SimpleBooleanProperty(true);
        final BooleanProperty unique = new SimpleBooleanProperty(false);
        final StringProperty defaultValue = new SimpleStringProperty("");
        final StringProperty comment = new SimpleStringProperty("");

        ColumnRow(Runnable onChange) {
            // ⭐ 主键列不能为 NULL —— 勾上主键就自动取消「可空」，
            //    而不是让用户填完再被一句报错打回来。
            primaryKey.addListener((observable, oldValue, newValue) -> {
                if (newValue) {
                    nullable.set(false);
                }
                onChange.run();
            });
            name.addListener(observable -> onChange.run());
            type.addListener(observable -> onChange.run());
            nullable.addListener(observable -> onChange.run());
            unique.addListener(observable -> onChange.run());
            defaultValue.addListener(observable -> onChange.run());
            comment.addListener(observable -> onChange.run());
        }

        ColumnSpec toSpec() {
            return new ColumnSpec(name.get(), type.get(), nullable.get(),
                    defaultValue.get(), comment.get(), unique.get(), primaryKey.get());
        }
    }
}
