package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.Icons;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
 * <h2>五个分区（任务书 §6.2）</h2>
 * A 标题栏（标题 + 真实表名 / 库名 / 方言 + 关闭）· B 基本信息（名称 / 类型 / 唯一）·
 * C 索引列双列选择区（可用列 N / 索引列 N，计数来自真实列表）· D SQL 预览 ·
 * E 底部：左校验提示 + 右取消 / 确定。骨架类与其他弹窗共用 {@code .adb-dialog__*}，
 * 让两个弹窗看起来出自同一个软件（§七）。
 *
 * <h2>为什么列要能排序</h2>
 * 复合索引的<b>列顺序直接决定它能不能被用上</b>（最左前缀）。
 * 所以这里不是「勾几个列」就完了 —— 必须能调顺序，且顺序要体现在预览 SQL 里。
 *
 * <h2>能力边界（§6.6「遵循现有数据库适配层」）</h2>
 * 索引类型与每列 {@code ASC / DESC} 只在<b>方言说支持</b>时才出现：
 * {@link DbSession#dialect()}{@code .indexTypes()} 为空 → 整行控件不摆
 * （SQLite / MySQL 就是空，理由写在 {@code Dialect} 的注释里）；
 * {@code supportsIndexColumnDirection()} 为假 → 不摆方向下拉。
 * <b>宁可少给一个选项，也不给一个「选了不生效」的选项</b> —— 后者就是 §6.5.6
 * 禁止的「按钮显示成功但实际数据没有变化」。
 */
final class IndexDialog {

    /** 已选索引列的一项：列名 + 排序方向（方言不支持方向时恒为升序）。 */
    static final class PickedColumn {

        private final String name;
        private boolean descending;

        PickedColumn(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        boolean descending() {
            return descending;
        }

        void setDescending(boolean value) {
            descending = value;
        }
    }

    private final Dialog<IndexDef> dialog = new Dialog<>();
    private final DbSession session;
    private final TableStructure table;
    private final List<String> existingIndexNames;
    /** 方言给的可选索引类型；空 = 这个库没有「类型可选」这回事。 */
    private final List<String> indexTypes;
    private final boolean directionSupported;

    private final TextField name = new TextField();
    private final ComboBox<String> type = new ComboBox<>();
    private final CheckBox unique = new CheckBox("唯一索引");
    private final Label error = new Label();
    private final Label availableHeader = new Label();
    private final Label chosenHeader = new Label();
    private final SqlEditor preview = new SqlEditor();
    private final ObservableList<String> available = FXCollections.observableArrayList();
    private final ObservableList<PickedColumn> chosen = FXCollections.observableArrayList();
    private final ListView<String> availableList = new ListView<>(available);
    private final ListView<PickedColumn> chosenList = new ListView<>(chosen);
    private final Button moveIn = pickerButton("添加", "加入索引（双击左列或按 ↵）");
    private final Button moveOut = pickerButton("移除", "移出索引（双击右列或按 Delete）");
    private final Button moveUp = pickerButton("上移", "前移一位（Alt+↑）—— 顺序就是最左前缀");
    private final Button moveDown = pickerButton("下移", "后移一位（Alt+↓）—— 顺序就是最左前缀");
    private final Button copySql = IconButtons.of(Icons.Icon.COPY, "复制 SQL");
    private final Button collapseSql = IconButtons.of(Icons.Icon.CHEVRON_RIGHT, "折叠 SQL 预览");
    private final Region sqlGap = new Region();
    private final HBox picker = new HBox(12);
    private boolean sqlVisible = true;

    private IndexDialog(DbSession session, TableStructure table) {
        this.session = session;
        this.table = table;
        this.existingIndexNames = table == null ? List.of()
                : table.indexes().stream().map(IndexDef::name).toList();
        // 调用方（结构页、截图路径）保证 session 就绪，dialect() 也从不返回 null。
        this.indexTypes = session.dialect().indexTypes();
        this.directionSupported = session.dialect().supportsIndexColumnDirection();
        available.setAll(table == null ? List.of()
                : table.columns().stream().map(column -> column.name()).toList());
        buildUi();
        refreshCounts();
        syncActions();
        refreshPreview();
    }

    static IndexDialog create(DbSession session, TableStructure table) {
        return new IndexDialog(session, table);
    }

    Optional<IndexDef> show(Window owner, Collection<String> stylesheets) {
        if (stylesheets != null && !stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(stylesheets);
        }
        // ⭐ 主题类与 .adb-dialog 必须抄到 DialogPane 上 —— 否则弹窗里所有
        //    -adb-* 查找落空，退回 JavaFX 默认皮肤。没有外部样式表时同样要挂。
        DialogStyles.apply(dialog, owner);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    // ─────────────────────────── 界面 ───────────────────────────

    private void buildUi() {
        dialog.setTitle("新建索引");
        // ⭐ V-11：宽度走档位规范；§6.1 给双列选择单独留了 PICKER 一档。
        //    高度 560 落在 §6.1 的 560–620 区间，再由 DialogStyles 按屏幕限高。
        dialog.getDialogPane().setPrefWidth(DialogSizes.PICKER);
        dialog.getDialogPane().setMinWidth(DialogSizes.PICKER);
        dialog.getDialogPane().setPrefHeight(560);
        dialog.getDialogPane().setMinHeight(560);
        // 不用 setHeaderText：A 区要同时放标题 + 真实表名 + 关闭钮，
        // 标准 header 只给一行居中的小字（与「新建表」共用同一套骨架）。
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        name.setId("index-dialog-name");
        name.setPromptText("索引名");
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        unique.setId("index-dialog-unique");
        unique.selectedProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        error.setId("index-dialog-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox body = new VBox(16,
                buildHeader(),
                buildBasicSection(),
                buildPickerSection(),
                buildSqlSection(),
                error);
        body.getStyleClass().add("adb-dialog__body");
        VBox.setVgrow(picker, Priority.ALWAYS);
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
        // ⭐ §6.7 焦点管理：打开落在「索引名称」上，直接能打字。
        dialog.setOnShown(event -> name.requestFocus());
    }

    /** A 区标题栏：新建索引 + 真实的表 / 库 / 方言 + 关闭。 */
    private HBox buildHeader() {
        Label title = new Label("新建索引");
        title.getStyleClass().add("adb-dialog__title");
        // ⭐ 表名 / 库名 / 方言全部来自真实上下文，不写死示例（§6.2 A）。
        //    会话标签本身就是「方言 · 库」（见 JdbcSession.of），再补一遍方言就成了
        //    「SQLite · 文件 · SQLite」—— 实测截图里就是这么重复的。
        Label context = new Label("表 " + (table == null ? "?" : table.name())
                + " · " + session.label());
        context.getStyleClass().add("adb-dialog__context");
        Button close = IconButtons.icon(Icons.Icon.CLOSE, "关闭");
        close.setId("index-dialog-close");
        close.getStyleClass().addAll("adb-icon-button", "adb-dialog__close");
        close.setOnAction(event -> close());
        HBox header = new HBox(8, title, context, close);
        header.getStyleClass().add("adb-dialog__header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(context, Priority.ALWAYS);
        return header;
    }

    /** B 区基本信息：名称 +（方言支持时的）类型 + 唯一索引。 */
    private VBox buildBasicSection() {
        VBox nameBox = labelledField("索引名称", name);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox row;
        if (indexTypes.isEmpty()) {
            row = new HBox(nameBox);
        } else {
            buildTypeChooser();
            VBox typeBox = labelledField("索引类型", type);
            typeBox.setPrefWidth(200);
            row = new HBox(12, nameBox, typeBox);
        }
        return section("基本信息", row, unique);
    }

    /**
     * 索引类型下拉 —— 只有 {@link io.agenticdb.jdbc.Dialect#indexTypes()} 给了候选
     * 才摆出来。只有一个候选时也不摆：一个永远只能填同一个值的下拉，
     * 比没有下拉更让用户以为「这里有得选」（§九 不留无用控件）。
     */
    private void buildTypeChooser() {
        type.setId("index-dialog-type");
        type.getItems().setAll(indexTypes);
        type.setPromptText("默认");
        type.setMaxWidth(Double.MAX_VALUE);
        type.setTooltip(new Tooltip("不选则用 " + session.dialect().displayName()
                + " 的默认索引类型"));
        type.valueProperty().addListener((observable, oldValue, newValue) -> refreshPreview());
    }

    /** C 区：可用列 / 索引列双列选择 + 中间的添加、移除、上移、下移。 */
    private VBox buildPickerSection() {
        buildLists();
        availableHeader.getStyleClass().add("adb-dialog__section-title");
        chosenHeader.getStyleClass().add("adb-dialog__section-title");
        VBox movers = new VBox(8, moveIn, moveOut, growGap(), moveUp, moveDown);
        movers.setAlignment(Pos.CENTER);
        picker.getChildren().setAll(pane(availableHeader, availableList), movers,
                pane(chosenHeader, chosenList));
        picker.setPrefHeight(220);
        return section("索引列（顺序决定复合索引能否被用上）", picker);
    }

    private static Region growGap() {
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        return gap;
    }

    private static VBox pane(Label header, ListView<?> list) {
        VBox pane = new VBox(6, header, list);
        HBox.setHgrow(pane, Priority.ALWAYS);
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setMinHeight(120);
        list.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    /**
     * 两个列表的接线：选择驱动按钮可用性；双击 / ↵ / Delete / Alt+↑↓ 与鼠标点按钮
     * <b>走同一批方法</b> —— 两条路各写一份迟早会走出不同结果（§6.5.5）。
     */
    private void buildLists() {
        availableList.setId("index-dialog-available");
        availableList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        availableList.setPlaceholder(new Label("所有列都已加入索引"));
        availableList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                addSelected();
            }
        });
        availableList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                addSelected();
                event.consume();
            }
        });

        chosenList.setId("index-dialog-chosen");
        chosenList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        chosenList.setPlaceholder(new Label("从左边选中列，按「添加」或双击加入索引"));
        chosenList.setCellFactory(list -> new PickedCell());
        chosenList.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                removeSelected();
            }
        });
        chosenList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                removeSelected();
                event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.UP) {
                swap(-1);
                event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.DOWN) {
                swap(1);
                event.consume();
            }
        });

        moveIn.setId("index-dialog-add");
        moveIn.setOnAction(event -> addSelected());
        moveOut.setId("index-dialog-remove");
        moveOut.setOnAction(event -> removeSelected());
        moveUp.setId("index-dialog-up");
        moveUp.setOnAction(event -> swap(-1));
        moveDown.setId("index-dialog-down");
        moveDown.setOnAction(event -> swap(1));

        // 选择一变：按钮可用性立刻跟着变（§6.5.1）。
        InvalidationListener sync = observable -> syncActions();
        availableList.getSelectionModel().getSelectedIndices().addListener(sync);
        chosenList.getSelectionModel().getSelectedIndices().addListener(sync);
        // 索引列一变：可用列重算、计数重写、预览重生成（§6.5.2/3/4）。
        chosen.addListener((ListChangeListener<PickedColumn>) change -> {
            refreshAvailable();
            refreshCounts();
            syncActions();
            refreshPreview();
        });

        copySql.setId("index-dialog-copy-sql");
        copySql.setOnAction(event -> copyPreview());
        collapseSql.setId("index-dialog-collapse-sql");
        collapseSql.setOnAction(event -> setSqlVisible(!sqlVisible));
    }

    // ─────────────────────────── 行为 ───────────────────────────

    /** 左侧选中的列进索引列；可用列按表的原始顺序重算，不会出现顺序漂移。 */
    private void addSelected() {
        List<String> selected = new ArrayList<>(
                availableList.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        for (String column : selected) {
            chosen.add(new PickedColumn(column));
        }
        chosenList.getSelectionModel().clearAndSelect(chosen.size() - 1);
    }

    /** 右侧选中的列退回可用列，选中仍然停在同一位置的那一项。 */
    private void removeSelected() {
        int index = chosenList.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= chosen.size()) {
            return;
        }
        chosen.remove(index);
        if (!chosen.isEmpty()) {
            chosenList.getSelectionModel().clearAndSelect(Math.min(index, chosen.size() - 1));
        }
    }

    /** 上移 / 下移：复合索引的列顺序就是最左前缀的顺序，必须能调。 */
    private void swap(int direction) {
        int from = chosenList.getSelectionModel().getSelectedIndex();
        if (from < 0) {
            return;
        }
        int to = from + direction;
        if (to < 0 || to >= chosen.size()) {
            return;
        }
        PickedColumn moving = chosen.remove(from);
        chosen.add(to, moving);
        chosenList.getSelectionModel().clearAndSelect(to);
        // 序号写在单元格里，数据位置变了但 item 没变 → 不 refresh 的话
        // 「1. 2. 3.」会跟着行走，界面上的顺序就是假的。
        chosenList.refresh();
        syncActions();
        refreshPreview();
    }

    private void refreshAvailable() {
        List<String> picked = chosen.stream().map(PickedColumn::name).toList();
        List<String> remaining = new ArrayList<>(table == null ? List.of()
                : table.columns().stream().map(column -> column.name()).toList());
        remaining.removeAll(picked);
        available.setAll(remaining);
    }

    /** §6.2 C：两个列表的标题带计数，数字来自真实数据。 */
    private void refreshCounts() {
        availableHeader.setText("可用列（" + available.size() + "）");
        chosenHeader.setText("索引列（" + chosen.size() + "）");
    }

    /** §6.5.1：没东西可操作时按钮必须禁用，而不是「点了没反应」。 */
    private void syncActions() {
        moveIn.setDisable(availableList.getSelectionModel().getSelectedIndices().isEmpty());
        int selected = chosenList.getSelectionModel().getSelectedIndex();
        boolean hasSelection = selected >= 0;
        moveOut.setDisable(!hasSelection);
        moveUp.setDisable(!hasSelection || selected == 0);
        moveDown.setDisable(!hasSelection || selected == chosen.size() - 1);
    }

    /** D 区：SQL 预览（等宽、行号、可折叠），与真正执行的那条同源。 */
    private VBox buildSqlSection() {
        Label title = new Label("将执行的 SQL");
        title.getStyleClass().add("adb-dialog__section-title");
        sqlGap.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(8, title, sqlGap, copySql, collapseSql);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sqlGap, Priority.ALWAYS);

        preview.area().setEditable(false);
        preview.area().setId("index-dialog-preview");
        // 折叠开关的是整个编辑器（行号 + 文本），不是里面的 CodeArea。
        preview.setId("index-dialog-sql");
        // ⭐ 预览是只读的、也**不能执行**：SqlEditor 默认那句「⌘↵ 执行当前语句」
        //    在这儿是误导（用户会去敲键盘，然后什么都没发生）。
        preview.setHint("填好名称并选择列后，这里显示将要执行的语句");
        preview.setPrefHeight(96);
        return new VBox(8, header, preview);
    }

    private void setSqlVisible(boolean visible) {
        sqlVisible = visible;
        preview.setVisible(visible);
        preview.setManaged(visible);
        collapseSql.setGraphic(Icons.of(visible
                ? Icons.Icon.CHEVRON_RIGHT : Icons.Icon.CHEVRON_LEFT));
        collapseSql.setText(visible ? "折叠 SQL 预览" : "展开 SQL 预览");
    }

    private void copyPreview() {
        String sql = preview.text();
        if (sql == null || sql.isBlank()) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(sql);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /** 关闭与 Escape / 取消走同一条出口：结果为空，调用方什么都不会做（§6.7）。 */
    private void close() {
        dialog.setResult(null);
        Window window = dialog.getDialogPane().getScene() == null
                ? null : dialog.getDialogPane().getScene().getWindow();
        if (window != null) {
            window.hide();
        }
    }

    // ─────────────────────────── 校验 ───────────────────────────

    private boolean validate() {
        String problem = problem();
        error.setText(problem == null ? "" : problem);
        error.setVisible(problem != null);
        error.setManaged(problem != null);
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
                chosen.stream().map(PickedColumn::name).toList(), "",
                type.getValue() == null ? "" : type.getValue(),
                chosen.stream().filter(PickedColumn::descending).map(PickedColumn::name).toList());
    }

    private void refreshPreview() {
        if (chosen.isEmpty() || name.getText() == null || name.getText().isBlank()) {
            preview.setText("");
            return;
        }
        preview.setText(StructureSqlBuilder.createIndexSql(session,
                table == null ? "" : table.name(), current()));
    }

    // ─────────────────────── 已选列的单元格 ───────────────────────

    /**
     * 已选索引列的一行：<b>序号 + 列名 + 排序方向</b>（方向只在方言支持时出现）。
     *
     * <p>序号是复合索引唯一的顺序证据 —— 只靠列表行序，用户看不出谁是前缀。
     */
    private final class PickedCell extends ListCell<PickedColumn> {

        private final Label order = new Label();
        private final Label column = new Label();
        /** 方言不支持排序方向时是 {@code null} —— 连控件都不创建（§九 不留无用控件）。 */
        private final ComboBox<String> direction;
        private final HBox row;
        private PickedColumn bound;

        PickedCell() {
            order.getStyleClass().add("adb-picked__order");
            order.setMinWidth(Region.USE_PREF_SIZE);
            column.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(column, Priority.ALWAYS);
            if (directionSupported) {
                ComboBox<String> box =
                        new ComboBox<>(FXCollections.observableArrayList("升序", "降序"));
                box.setPrefWidth(76);
                box.setTooltip(new Tooltip("该列在索引里的排序方向"));
                // 用值变化而不是 onAction：方向是这一列的**状态**，不是一次动作，
                // 而且程序化选择（默认值回填、单测）也要走同一条路。
                box.valueProperty().addListener((observable, oldValue, newValue) -> {
                    if (bound == null) {
                        return;
                    }
                    bound.setDescending("降序".equals(newValue));
                    refreshPreview();
                });
                direction = box;
                row = new HBox(8, order, column, box);
            } else {
                direction = null;
                row = new HBox(8, order, column);
            }
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(PickedColumn item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                bound = null;
                setGraphic(null);
                return;
            }
            bound = item;
            order.setText(getIndex() + 1 + ".");
            column.setText(item.name());
            if (direction != null) {
                direction.getSelectionModel().select(item.descending() ? 1 : 0);
            }
            setGraphic(row);
        }
    }

    // ─────────────────────────── 布局小件 ───────────────────────────

    /** 标签在上、控件在下的一格表单字段（与「新建表」同一份形态）。 */
    private static VBox labelledField(String text, Control field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-form__label");
        return new VBox(4, label, field);
    }

    private static Button pickerButton(String text, String hint) {
        Button button = new Button(text);
        button.getStyleClass().add("adb-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setTooltip(new Tooltip(hint));
        button.setAccessibleText(text);
        return button;
    }

    private static VBox section(String text, Node... children) {
        Label title = new Label(text);
        title.getStyleClass().add("adb-dialog__section-title");
        List<Node> all = new ArrayList<>();
        all.add(title);
        all.addAll(List.of(children));
        return new VBox(8, all.toArray(Node[]::new));
    }

    /**
     * 截图摆态：把这张表<b>真实的前两列</b>加进索引并填上名字。
     *
     * <p>为什么要有它：不摆态的话，验收帧里永远是「索引列（0）+ 四颗灰按钮 + 空预览」，
     * 而 §6.4 要的恰恰是顺序与预览那一面 —— 与 {@code ADB_CELL} 同一形态。
     * 加的列走 {@link #addSelected()}（和点「添加」同一条路），不是往列表里塞数据：
     * 摆出来的态和点出来的态必须是同一份代码算出来的，否则截图在骗人。
     */
    void poseForShots() {
        for (int i = 0; i < 2 && !available.isEmpty(); i++) {
            availableList.getSelectionModel().select(0);
            addSelected();
        }
        if (!chosen.isEmpty() && table != null) {
            name.setText("idx_" + table.name() + "_" + chosen.get(0).name());
        }
    }

    // ─────────────────────────── 单测可见 ───────────────────────────

    TextField nameField() {
        return name;
    }

    CheckBox uniqueBox() {
        return unique;
    }

    ComboBox<String> typeBox() {
        return type;
    }

    ListView<String> availableListView() {
        return availableList;
    }

    ListView<PickedColumn> chosenListView() {
        return chosenList;
    }

    Button addButton() {
        return moveIn;
    }

    Button removeButton() {
        return moveOut;
    }

    Button upButton() {
        return moveUp;
    }

    Button downButton() {
        return moveDown;
    }

    Label availableHeader() {
        return availableHeader;
    }

    Label chosenHeader() {
        return chosenHeader;
    }

    String previewText() {
        return preview.text();
    }

    ObservableList<String> availableColumns() {
        return available;
    }

    ObservableList<PickedColumn> chosenColumns() {
        return chosen;
    }

    /** 弹窗内容区节点 —— 门禁把它挂进测试场景，列表单元格才会真正生成。 */
    Node content() {
        return dialog.getDialogPane().getContent();
    }

    /** 弹窗外壳 —— 门禁查宽度档位与底部按钮（E 区）。 */
    javafx.scene.control.DialogPane dialogPane() {
        return dialog.getDialogPane();
    }

    boolean isSqlPreviewVisible() {
        return sqlVisible;
    }
}
