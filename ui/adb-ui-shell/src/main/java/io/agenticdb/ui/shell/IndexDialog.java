package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * S1 新建索引 —— <b>弹窗</b>（从结构页里弹出来），不是标签页。
 *
 * <p>形态依据见 {@code docs/UI-FORMS-AND-STATES.md} §4：索引属于「改已存在的东西」，
 * 改完就走。给它开一个标签页，用户会在「索引」标签和「列」标签之间来回切，
 * 而索引本来就是列的附属信息。
 *
 * <h2>为什么列要能排序</h2>
 * 复合索引的<b>列顺序直接决定它能不能被用上</b>（最左前缀）。
 * 所以这里不是「勾几个列」就完了 —— 必须能调顺序，且顺序要体现在预览 SQL 里。
 */
final class IndexDialog {

    private final Dialog<IndexDef> dialog = new Dialog<>();
    private final DbSession session;
    private final TableStructure table;
    private final List<String> existingIndexNames;

    private final TextField name = new TextField();
    private final CheckBox unique = new CheckBox("唯一索引");
    private final Label error = new Label();
    private final TextArea preview = new TextArea();
    private final ObservableList<String> available = FXCollections.observableArrayList();
    private final ObservableList<String> chosen = FXCollections.observableArrayList();
    private final ListView<String> chosenList = new ListView<>(chosen);

    private IndexDialog(DbSession session, TableStructure table) {
        this.session = session;
        this.table = table;
        this.existingIndexNames = table == null ? List.of()
                : table.indexes().stream().map(IndexDef::name).toList();
        available.setAll(table == null ? List.of()
                : table.columns().stream().map(column -> column.name()).toList());
        buildUi();
        refreshPreview();
    }

    static IndexDialog create(DbSession session, TableStructure table) {
        return new IndexDialog(session, table);
    }

    Optional<IndexDef> show(Window owner, Collection<String> stylesheets) {
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
        dialog.setTitle("新建索引");
        // ⭐ V-11：弹窗宽度走三档规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.FORM);
        dialog.getDialogPane().setMinWidth(DialogSizes.FORM);

        dialog.setHeaderText("在 " + (table == null ? "?" : table.name()) + " 上新建索引");

        name.setId("index-dialog-name");
        name.setPromptText("索引名");
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        unique.setId("index-dialog-unique");
        unique.selectedProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        ListView<String> availableList = new ListView<>(available);
        availableList.setId("index-dialog-available");
        availableList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        availableList.setPlaceholder(new Label("没有更多列了"));

        chosenList.setId("index-dialog-chosen");
        chosenList.setPlaceholder(new Label("从左边添加列"));

        Button add = IconButtons.of(Icons.Icon.ADD, "添加");
        add.setId("index-dialog-add");
        add.setOnAction(event -> move(availableList.getSelectionModel().getSelectedItems()));

        Button remove = IconButtons.of(Icons.Icon.DELETE, "移除");
        remove.setId("index-dialog-remove");
        remove.setOnAction(event -> {
            String selected = chosenList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            int index = chosen.indexOf(selected);
            chosen.remove(selected);
            available.add(index == 0 ? 0 : index, selected);
            refreshPreview();
        });

        Button up = new Button("上移");
        up.setId("index-dialog-up");
        up.setOnAction(event -> swap(-1));

        Button down = new Button("下移");
        down.setId("index-dialog-down");
        down.setOnAction(event -> swap(1));

        VBox movers = new VBox(6, add, remove, up, down);
        movers.setAlignment(Pos.CENTER);

        HBox picker = new HBox(10, availableList, movers, chosenList);
        HBox.setHgrow(availableList, Priority.ALWAYS);
        HBox.setHgrow(chosenList, Priority.ALWAYS);
        picker.setPrefHeight(190);

        preview.setId("index-dialog-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(4);

        error.setId("index-dialog-error");
        error.getStyleClass().add("adb-error");

        VBox body = new VBox(10,
                labeled("名称", name),
                unique,
                labeled("列（顺序决定复合索引能否被用上）", picker),
                labeled("将执行", preview),
                error);
        body.getStyleClass().add("adb-form");
        dialog.getDialogPane().setContent(body);

        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        // ⭐ 校验不过就吞掉这次关闭 —— 否则用户点确定什么也没发生，还以为卡了。
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

    // ─────────────────────────── 行为 ───────────────────────────

    private void move(List<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return;
        }
        for (String column : new ArrayList<>(selected)) {
            available.remove(column);
            chosen.add(column);
        }
        refreshPreview();
    }

    private void swap(int direction) {
        if (chosen.size() < 2) {
            return;
        }
        int from = chosenList.getSelectionModel().getSelectedIndex();
        if (from < 0) {
            return;
        }
        int to = from + direction;
        if (to < 0 || to >= chosen.size()) {
            return;
        }
        String moving = chosen.remove(from);
        chosen.add(to, moving);
        chosenList.getSelectionModel().select(to);
        refreshPreview();
    }

    private boolean validate() {
        String problem = problem();
        error.setText(problem == null ? "" : problem);
        return problem == null;
    }

    /** 校验口径与 {@link StructureSqlBuilder#createIndex} 保持一致（纵深防御）。 */
    String problem() {
        String raw = name.getText() == null ? "" : name.getText().trim();
        if (raw.isEmpty()) {
            return "请填索引名";
        }
        if (!raw.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            return "索引名只能包含字母、数字、下划线或 $，且不能以数字开头";
        }
        for (String existing : existingIndexNames) {
            if (existing.equalsIgnoreCase(raw)) {
                return "已经有一个叫「" + raw + "」的索引了";
            }
        }
        if (chosen.isEmpty()) {
            return "请至少选择一列";
        }
        return null;
    }

    private IndexDef current() {
        return new IndexDef(name.getText().trim(), unique.isSelected(),
                List.copyOf(chosen), "");
    }

    private void refreshPreview() {
        if (chosen.isEmpty() || name.getText() == null || name.getText().isBlank()) {
            preview.setText("");
            return;
        }
        preview.setText(StructureSqlBuilder.createIndexSql(session,
                table == null ? "" : table.name(), current()));
    }

    // ─────────────────────────── 单测可见 ───────────────────────────

    TextField nameField() {
        return name;
    }

    CheckBox uniqueBox() {
        return unique;
    }

    TextArea previewArea() {
        return preview;
    }

    ObservableList<String> availableColumns() {
        return available;
    }

    ObservableList<String> chosenColumns() {
        return chosen;
    }
}
