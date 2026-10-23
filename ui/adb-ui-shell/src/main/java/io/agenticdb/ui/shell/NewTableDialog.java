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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
    /** ⭐ 规格 §13.4：SQL 预览是只读的 SQL 编辑器（等宽 / 行号 / 高亮）。 */
    private SqlEditor previewEditor;
    private VBox sqlPreview;
    private boolean sqlPreviewVisible = true;
    private final Label error = new Label();
    private final ObservableList<ColumnRow> rows = FXCollections.observableArrayList();
    /** 列网格的强引用 —— 门禁要直接驱动它的布尔单元格验证键盘可达。 */
    private TableView<ColumnRow> columnsTable;

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
        dialog.setTitle("新建数据表");
        // ⭐ 规格 §13.1：850–1000px。720px 下「默认值 / 注释」两列会被压成
        //    一个省略号，改一个默认值先得横向滚 —— 建表是高频操作，不能这样。
        dialog.getDialogPane().setPrefWidth(DialogSizes.TABLE);
        dialog.getDialogPane().setMinWidth(DialogSizes.TABLE);
        dialog.getDialogPane().setMinHeight(600);
        // 不用 setHeaderText：标题栏要同时放「新建数据表」+ 当前库名 + 关闭按钮
        // （规格 §13.2），而 JavaFX 的标准 header 只给一行居中的小字。
        dialog.setHeaderText(null);

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
        this.columnsTable = table;
        // ⚠️ TableView 的默认首选高度很大（400+）—— 弹窗会被撑到比屏幕还高，
        //    底部的「创建 / 取消」跑到屏幕外，用户点不到。固定行高 + 限高。
        table.setFixedCellSize(30);
        fitTableHeight(table);

        Button add = IconButtons.of(Icons.Icon.ADD, "添加列");
        add.setId("new-table-add-column");
        add.setOnAction(event -> {
            addRow();
            fitTableHeight(table);
        });
        Button remove = IconButtons.of(Icons.Icon.DELETE, "删除列");
        remove.setId("new-table-remove-column");
        remove.setOnAction(event -> {
            ColumnRow selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            rows.remove(selected);
            fitTableHeight(table);
            refreshPreview();
        });
        HBox rowButtons = new HBox(8, add, remove);
        rowButtons.setAlignment(Pos.CENTER_LEFT);
        rowButtons.getStyleClass().add("adb-new-table__row-actions");

        // ⭐ 规格 §13.4：SQL 预览要像编辑器（等宽 / 行号 / 高亮），
        //    不是一个灰底的 TextArea。复用 SqlEditor —— 查询页那个本来就是它。
        previewEditor = new SqlEditor();
        previewEditor.area().setEditable(false);
        previewEditor.area().setId("new-table-preview");
        // ⭐ 预览是**只读的**、也**不能执行** —— 沿用 SqlEditor 默认那句
        //    「输入 SQL，⌘↵ 执行当前语句」是误导（用户会去敲键盘、按 ⌘↵，
        //    然后什么都没发生）。setHint 是 §11.2/§28.8 为只读视图加的口子，
        //    这里必须用上（结构页 DDL 已经用了）。
        previewEditor.setHint("填写表名后，这里会显示将要执行的建表语句");
        previewEditor.setPrefHeight(168);
        VBox.setVgrow(previewEditor, Priority.ALWAYS);

        Button copySql = IconButtons.of(Icons.Icon.COPY, "复制 SQL");
        copySql.setId("new-table-copy-sql");
        copySql.setOnAction(event -> copyPreview());
        Button collapseSql = IconButtons.of(Icons.Icon.CHEVRON_RIGHT, "折叠 SQL 预览");
        collapseSql.setId("new-table-collapse-sql");
        collapseSql.setOnAction(event -> setSqlPreviewVisible(!sqlPreviewVisible, collapseSql));

        Label sqlTitle = new Label("将要执行的 SQL");
        sqlTitle.getStyleClass().add("adb-new-table__section-title");
        HBox sqlHeader = new HBox(8, sqlTitle, copySql, collapseSql);
        sqlHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sqlTitle, Priority.ALWAYS);

        error.setId("new-table-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox body = new VBox(12,
                buildHeader(),
                buildTableInfoSection(),
                buildColumnsSection(table, rowButtons),
                buildSqlSection(sqlHeader, previewEditor),
                error);        body.getStyleClass().add("adb-new-table__body");
        body.setPadding(new Insets(16, 16, 8, 16));
        VBox.setVgrow(previewEditor, Priority.ALWAYS);
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

        rows.addListener((ListChangeListener<ColumnRow>) change -> {
            refreshPreview();
            fitTableHeight(table);
        });
    }

    /**
     * 字段网格的高度跟着行数走。
     *
     * <p>以前固定 200px：只有一行「id」时下面挂着 170px 空白网格，
     * 看起来像「还有很多行没填」；加到 8 行时又开始内部滚动。
     */
    private void fitTableHeight(TableView<ColumnRow> table) {
        double height = 34 + Math.max(1, rows.size()) * 30;
        table.setPrefHeight(clamp(height, 120, 300));
        table.setMinHeight(120);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 标题栏：新建数据表 + 当前库 / 方言 + 关闭按钮（规格 §13.2）。 */
    private HBox buildHeader() {
        Label title = new Label("新建数据表");
        title.getStyleClass().add("adb-new-table__title");
        // ⭐ 库名与方言都来自真实会话，不是写死的示例。
        Label context = new Label(session.label() + " · " + session.dialect().displayName());
        context.getStyleClass().add("adb-new-table__context");
        Button close = IconButtons.icon(Icons.Icon.CLOSE, "关闭");
        close.setId("new-table-close");
        close.getStyleClass().addAll("adb-icon-button", "adb-new-table__close");
        close.setOnAction(event -> dialog.setResult(null));
        HBox header = new HBox(8, title, context, close);
        header.getStyleClass().add("adb-new-table__header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(context, Priority.ALWAYS);
        return header;
    }

    /** 表基本信息（规格 §13.2 第一部分）。 */
    private VBox buildTableInfoSection() {
        Label title = new Label("表基本信息");
        title.getStyleClass().add("adb-new-table__section-title");
        VBox box = new VBox(8,
                row(formLabel("表名"), name),
                row(formLabel("表注释"), tableComment));
        box.getStyleClass().add("adb-new-table__section");
        return new VBox(4, title, box);
    }

    /** 表单行的标签（复用全局的 adb-form__label，不为弹窗另立一套）。 */
    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-form__label");
        return label;
    }

    /** 字段编辑网格（规格 §13.2 第二部分）。 */
    private VBox buildColumnsSection(TableView<ColumnRow> table, HBox rowButtons) {
        Label title = new Label("字段");
        title.getStyleClass().add("adb-new-table__section-title");
        VBox box = new VBox(8, table, rowButtons);
        box.getStyleClass().add("adb-new-table__section");
        return new VBox(4, title, box);
    }

    /** SQL 预览（规格 §13.2 第三部分，可折叠）。 */
    private VBox buildSqlSection(HBox header, SqlEditor editor) {
        VBox box = new VBox(8, header, editor);
        box.getStyleClass().add("adb-new-table__section");
        return box;
    }

    private void setSqlPreviewVisible(boolean visible, Button toggle) {
        sqlPreviewVisible = visible;
        previewEditor.setVisible(visible);
        previewEditor.setManaged(visible);
        toggle.setGraphic(Icons.of(visible ? Icons.Icon.CHEVRON_RIGHT : Icons.Icon.CHEVRON_LEFT));
        toggle.setText(visible ? "折叠 SQL 预览" : "展开 SQL 预览");
    }

    private void copyPreview() {
        String sql = previewEditor.text();
        if (sql == null || sql.isBlank()) {
            return;
        }
        javafx.scene.input.ClipboardContent content =
                new javafx.scene.input.ClipboardContent();
        content.putString(sql);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
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
        // ⭐ 规格 §13.3：字段名 / 类型拿宽列，三个布尔约束列保持紧凑。
        //    以前布尔列也是 60px，勾选框孤零零漂在格子中间；而「注释」
        //    吃掉所有剩余宽度却常常是空的。
        table.getColumns().setAll(
                textColumn("字段名", "name", 200, row -> row.name),
                textColumn("类型", "type", 160, row -> row.type),
                booleanColumn("主键", 52, row -> row.primaryKey),
                booleanColumn("可空", 52, row -> row.nullable),
                booleanColumn("唯一", 52, row -> row.unique),
                textColumn("默认值", "defaultValue", 140, row -> row.defaultValue),
                textColumn("注释", "comment", 120, row -> row.comment));
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
        // ⭐ 规格 §13.3 / UI 硬规则「一切都要键盘可达」：
        //    CheckBoxTableCell 的复选框默认 focusTraversable=false 且只响应鼠标点 ——
        //    「主键 / 可空 / 唯一」三列用键盘根本勾不上。这里自己做单元格工厂：
        //    空格切换、Enter 切换、Tab 移到下一列（与文本列同一套节奏）。
        column.setCellFactory(view -> new BooleanCell(property));
        column.setEditable(true);
        return column;
    }

    /**
     * 可键盘操作的布尔单元格。
     *
     * <p>与 {@code CheckBoxTableCell} 的差别只有一件事：**空格 / 回车能切换**。
     * 焦点留在单元格上（复选框本身不吃焦点），读屏软件不会读两遍；
     * 样式类只用既有的 {@code adb-table__bool-cell}，不新增视觉。
     */
    static final class BooleanCell extends TableCell<ColumnRow, Boolean> {

        private final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox();
        private final java.util.function.Function<ColumnRow, BooleanProperty> property;
        /** 防止「把模型值同步进复选框」被当成用户点击，形成回环。 */
        private boolean syncing;

        BooleanCell(java.util.function.Function<ColumnRow, BooleanProperty> property) {
            this.property = property;
            getStyleClass().add("adb-table__bool-cell");
            box.setFocusTraversable(false);
            box.getStyleClass().add("adb-table__bool-box");
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            setAlignment(javafx.geometry.Pos.CENTER);

            box.selectedProperty().addListener((observable, was, now) -> {
                ColumnRow row = currentRow();
                if (syncing || row == null) {
                    return;
                }
                // 用户点的：写回模型，SQL 预览立刻跟着变。
                property.apply(row).set(now);
            });

            // 键盘：空格 / 回车切换 —— 这是本单元格存在的全部理由。
            //    逻辑抽进 handleKey()/isToggleKey()，门禁可直接驱动（headless 下
            //    fireEvent 送不到没有真实焦点的节点，靠事件派发写测试会假绿或假红）。
            setOnKeyPressed(event -> {
                if (handleKey(event.getCode())) {
                    event.consume();
                }
            });
        }

        /** 当前绑定的数据行；单元格未上屏（无 TableRow）时为 null。 */
        private ColumnRow currentRow() {
            return getTableRow() == null ? null : getTableRow().getItem();
        }

        /**
         * 这个键是不是「切换」键（空格 / 回车）。
         *
         * <p>方向键、Tab、字母键一律不是 —— 它们必须留给 TableView 的导航。
         */
        static boolean isToggleKey(javafx.scene.input.KeyCode code) {
            return code == javafx.scene.input.KeyCode.SPACE
                    || code == javafx.scene.input.KeyCode.ENTER;
        }

        /**
         * 处理一个按键；返回 {@code true} 表示本单元格吃掉了这个键。
         *
         * <p>只吃空格 / 回车；其它键一律交给 TableView 的 Tab / 方向键导航 —— 不抢别人的键。
         */
        boolean handleKey(javafx.scene.input.KeyCode code) {
            if (!isToggleKey(code)) {
                return false;
            }
            ColumnRow row = currentRow();
            if (row == null) {
                return true; // 未上屏：吃掉键但无模型可改（避免空格意外触发表格全选）。
            }
            toggle(row);
            return true;
        }

        /** 切换一行的某个布尔属性并刷新表格 —— 纯模型操作，门禁可脱离场景图直接驱动。 */
        void toggle(ColumnRow row) {
            BooleanProperty p = property.apply(row);
            p.set(!p.get());
            if (getTableView() != null) {
                getTableView().refresh();
            }
        }

        @Override
        protected void updateItem(Boolean item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            syncing = true;
            box.setSelected(Boolean.TRUE.equals(item));
            syncing = false;
            setGraphic(box);
        }
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
            previewEditor.setText("");
            return;
        }
        previewEditor.setText(plan.sql());
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

    /**
     * 列网格（供门禁断言键盘可达性）。
     *
     * <p>布尔单元格的空格 / 回车切换要能被直接驱动，
     * 不能只靠「点一下鼠标」验证 —— 那正好漏掉了要守的东西。
     */
    TableView<ColumnRow> columnsTable() {
        return columnsTable;
    }

    TextField nameField() {
        return name;
    }

    /**
     * SQL 预览的编辑区（供门禁断言内容）。
     *
     * <p>返回 {@link org.fxmisc.richtext.CodeArea} 而不是包一层：
     * 它就是 {@link SqlEditor} 真正的编辑控件，断言
     * {@code getText()} 与原来那个 TextArea 一字不差，
     * 于是「预览内容」这条规则不需要跟着实现一起改。
     */
    org.fxmisc.richtext.CodeArea previewArea() {
        return previewEditor.area();
    }

    /** SQL 预览是不是折叠状态（规格 §13.4：预览可折叠）。 */
    boolean sqlPreviewCollapsed() {
        return !sqlPreviewVisible;
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
