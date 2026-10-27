package io.agenticdb.ui.shell;

import io.agenticdb.introspect.ForeignKeyDef;
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
import javafx.scene.control.ComboBox;
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
 * B5-S2 新建外键 —— <b>弹窗</b>（从结构页里弹出来）。
 *
 * <p>形态依据见 {@code docs/UI-FORMS-AND-STATES.md} §4：外键是「改已存在的东西」，
 * 改完就走 —— 给它开标签页，用户会在「外键」标签与「列」标签之间来回切。
 *
 * <h2>为什么列要能排序</h2>
 * 复合外键的<b>列顺序必须与被引用列一一对应</b>（第 1 列对第 1 列），
 * 所以两边都是「有序列表 + 上下移动」，而不是多选复选框。
 */
final class ForeignKeyDialog {

    private final Dialog<ForeignKeyDef> dialog = new Dialog<>();
    private final DbSession session;
    private final TableStructure table;
    /**
     * {@code true} = 给「新建表」设计器里的草稿表用。
     *
     * <p>草稿表还没建出来，SQLite 的 {@code addForeignKey} 会生成一整段「建影子表 →
     * 拷贝数据 → 删旧表 → 改名」的重建脚本 —— 对一张不存在的表是纯粹的误导。
     * 草稿模式预览的是将来写进 {@code CREATE TABLE} 的那一行（同一个
     * {@link StructureSqlBuilder#foreignKeyClause}，见建表计划的拼装处）。
     */
    private final boolean draft;

    private final TextField name = new TextField();
    private final ComboBox<String> referencedTable = new ComboBox<>();
    private final ObservableList<String> available = FXCollections.observableArrayList();
    private final ObservableList<String> chosen = FXCollections.observableArrayList();
    private final ObservableList<String> referenced = FXCollections.observableArrayList();
    private final ListView<String> chosenList = new ListView<>(chosen);
    private final ListView<String> referencedList = new ListView<>(referenced);
    private final ComboBox<String> onUpdate = new ComboBox<>();
    private final ComboBox<String> onDelete = new ComboBox<>();
    private final Label error = new Label();
    private final TextArea preview = new TextArea();

    private ForeignKeyDialog(DbSession session, TableStructure table, boolean draft) {
        this.session = session;
        this.table = table;
        this.draft = draft;
        buildUi();
        refreshPreview();
    }

    static ForeignKeyDialog create(DbSession session, TableStructure table) {
        return new ForeignKeyDialog(session, table, false);
    }

    /** ⭐ R30-D：给「新建表」设计器的草稿表用 —— 预览写进 CREATE TABLE 的那一行。 */
    static ForeignKeyDialog createDraft(DbSession session, TableStructure draft) {
        return new ForeignKeyDialog(session, draft, true);
    }

    Optional<ForeignKeyDef> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null && !stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
        }
        // ⭐ 主题类必须抄到 DialogPane 上 —— 否则弹窗里所有 -adb-* 查找落空，
        //    退回 JavaFX 默认皮肤。没传外部样式表时同样要挂（与「新建表」一致）。
        DialogStyles.apply(dialog, owner, "foreign-key");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    // ─────────────────────────── 界面 ───────────────────────────

    private void buildUi() {
        dialog.setTitle("新建外键");
        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。
        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);
        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        dialog.setHeaderText("在 " + (table == null ? "?" : table.name()) + " 上新建外键");
        name.setId("fk-dialog-name");
        name.setPromptText("约束名（可留空）");
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        available.setAll(table == null ? List.of()
                : table.columns().stream().map(column -> column.name()).toList());

        referencedTable.setId("fk-dialog-referenced-table");
        referencedTable.setPromptText("被引用的表");
        referencedTable.setEditable(true);
        referencedTable.getItems().addAll(tableNames());
        referencedTable.valueProperty().addListener((observable, oldValue, newValue) -> {
            // ⭐ 换表时自动把「被引用列」填成本表列的同名列 ——
            //    外键最常见的就是「同名列对同名列」，用户改两下就能提交。
            referenced.clear();
            referenced.addAll(new ArrayList<>(chosen));
            refreshPreview();
        });

        ListView<String> availableList = new ListView<>(available);
        availableList.setId("fk-dialog-available");
        availableList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        chosenList.setId("fk-dialog-chosen");
        chosenList.setPlaceholder(EmptyState.pickerHint("从左边选中列，按「添加」或双击加入"));
        referencedList.setId("fk-dialog-referenced");
        referencedList.setPlaceholder(EmptyState.pickerHint("先从左边选一个引用列，这里才会给出候选"));

        Button add = IconButtons.of(Icons.Icon.ADD, "添加");
        add.setId("fk-dialog-add");
        add.setOnAction(event -> {
            chosen.addAll(availableList.getSelectionModel().getSelectedItems());
            available.removeAll(chosen);
            while (referenced.size() < chosen.size()) {
                referenced.add(chosen.get(referenced.size()));
            }
            refreshPreview();
        });

        Button remove = IconButtons.of(Icons.Icon.DELETE, "移除");
        remove.setId("fk-dialog-remove");
        remove.setOnAction(event -> {
            String selected = chosenList.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            chosen.remove(selected);
            available.add(selected);
            refreshPreview();
        });

        Button refAdd = IconButtons.of(Icons.Icon.ADD, "添加引用列");
        refAdd.setId("fk-dialog-ref-add");
        refAdd.setOnAction(event -> {
            // 补齐到与本表列同样多（默认用同名列）
            while (referenced.size() < chosen.size()) {
                referenced.add(chosen.get(referenced.size()));
            }
            refreshPreview();
        });

        Button refRemove = IconButtons.of(Icons.Icon.DELETE, "移除引用列");
        refRemove.setId("fk-dialog-ref-remove");
        refRemove.setOnAction(event -> {
            String selected = referencedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                referenced.remove(selected);
                refreshPreview();
            }
        });

        VBox movers = new VBox(6, add, remove);
        movers.setAlignment(Pos.CENTER);
        VBox refMovers = new VBox(6, refAdd, refRemove);
        refMovers.setAlignment(Pos.CENTER);

        HBox picker = new HBox(10, availableList, movers, chosenList, refMovers, referencedList);
        HBox.setHgrow(availableList, Priority.ALWAYS);
        HBox.setHgrow(chosenList, Priority.ALWAYS);
        HBox.setHgrow(referencedList, Priority.ALWAYS);
        picker.setPrefHeight(190);

        for (String action : List.of("CASCADE", "SET NULL", "SET DEFAULT", "RESTRICT", "NO ACTION")) {
            onUpdate.getItems().add(action);
            onDelete.getItems().add(action);
        }
        onUpdate.setId("fk-dialog-on-update");
        onDelete.setId("fk-dialog-on-delete");
        onUpdate.getSelectionModel().select("NO ACTION");
        onDelete.getSelectionModel().select("NO ACTION");
        onUpdate.valueProperty().addListener((observable, oldValue, newValue) -> refreshPreview());
        onDelete.valueProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        preview.setId("fk-dialog-preview");
        preview.setEditable(false);
        preview.setPrefRowCount(4);

        error.setId("fk-dialog-error");
        error.getStyleClass().add("adb-error");

        VBox body = new VBox(10,
                labeled("约束名", name),
                labeled("被引用的表", referencedTable),
                labeled("列（左边是本表列，右边是被引用列，顺序一一对应）", picker),
                new HBox(10, labeled("ON UPDATE", onUpdate), labeled("ON DELETE", onDelete)),
                // 草稿表没有「将执行」这回事：外键在建表时内联进去，单独执行不了。
                labeled(draft ? "将写进 CREATE TABLE（外键在新建表时内联）" : "将执行", preview),
                error);
        body.getStyleClass().add("adb-form");
        dialog.getDialogPane().setContent(body);

        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(ok, cancel);
        dialog.getDialogPane().lookupButton(ok).addEventFilter(ActionEvent.ACTION, event -> {
            if (!validate()) {
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ok ? current() : null);
    }

    /** 库里有哪些表 —— 供「被引用的表」下拉用。读不到就空着（用户可以手输）。 */
    private List<String> tableNames() {
        try {
            return new io.agenticdb.introspect.SchemaService().listObjects(session).stream()
                    .filter(object -> object.kind()
                            == io.agenticdb.introspect.SchemaService.ObjectKind.TABLE)
                    .map(object -> object.name())
                    .sorted()
                    .toList();
        } catch (java.sql.SQLException | RuntimeException e) {
            return List.of();
        }
    }

    private static VBox labeled(String text, javafx.scene.Node field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-muted");
        return new VBox(4, label, field);
    }

    // ─────────────────────────── 行为 ───────────────────────────

    private ForeignKeyDef current() {
        String constraintName = name.getText() == null ? "" : name.getText().trim();
        // 草稿表的名字可能还是「?」（用户还没填表名）：拿它拼约束名会得到
        // 「fk_?_id」。草稿模式干脆不编名字 —— 建表时省略 CONSTRAINT 一样合法。
        if (constraintName.isEmpty() && !chosen.isEmpty() && !draft) {
            constraintName = "fk_" + table.name() + "_" + chosen.getFirst();
        }
        return new ForeignKeyDef(constraintName, new ArrayList<>(chosen),
                referencedTable.getValue() == null ? "" : referencedTable.getValue(),
                new ArrayList<>(referenced),
                onUpdate.getValue(), onDelete.getValue());
    }

    private boolean validate() {
        String problem = problem();
        error.setText(problem == null ? "" : problem);
        return problem == null;
    }

    /** 校验口径与 {@link StructureSqlBuilder#addForeignKey} 一致（纵深防御）。 */
    private String problem() {
        if (chosen.isEmpty()) {
            return "请至少选择一列";
        }
        if (referencedTable.getValue() == null || referencedTable.getValue().isBlank()) {
            return "请选择被引用的表";
        }
        if (referenced.isEmpty()) {
            return "请至少选择一个被引用列";
        }
        if (chosen.size() != referenced.size()) {
            return "本表列与被引用列的个数必须一致";
        }
        if (table != null) {
            for (String column : chosen) {
                if (table.column(column) == null) {
                    return "表里没有这一列: " + column;
                }
            }
        }
        return null;
    }

    private void refreshPreview() {
        if (table == null) {
            preview.setText("");
            return;
        }
        try {
            // 草稿表：预览「写进 CREATE TABLE 的那一行」；已有表：预览真正会执行的 ALTER / 重建。
            preview.setText(draft
                    ? StructureSqlBuilder.foreignKeyClause(session, current())
                    : StructureSqlBuilder.addForeignKey(session, table, current()).sql());
        } catch (RuntimeException e) {
            preview.setText(e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
