package io.agenticdb.ui.shell;

import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
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
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
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
 * C 索引列（单列表 + 「添加列」下拉，计数来自真实可用列）· D SQL 预览 ·
 * E 底部：左校验提示 + 右取消 / 确定。骨架类与其他弹窗共用 {@code .adb-dialog__*}，
 * 让两个弹窗看起来出自同一个软件（§七）。
 *
 * <h2>为什么列要能排序</h2>
 * 复合索引的<b>列顺序直接决定它能不能被用上</b>（最左前缀）。
 * 所以这里不是「勾几个列」就完了 —— 必须能调顺序，且顺序要体现在预览 SQL 里。
 *
 * <h2>为什么是单列表而不是双列传输框（U21）</h2>
 * 「可用列 ⇄ 索引列」双列表中间竖排四颗按钮（添加 / 移除 / 上移 / 下移）是 2000 年代的
 * 传输框形态：为了加一列，用户得先在左列点中、再移开鼠标去点中间那颗按钮；
 * 调顺序还要先选中、再点箭头。这里改成<b>结果即界面</b>：
 * 唯一的列表就是「索引列」，加列走右上角一个下拉（里面正是可用列，计数写在按钮上），
 * 排序直接拖行、移除直接点行尾的 ×。少一个列表 = 少一处需要同步的状态，
 * 也少一类「左列已经没了、右列还在」的错位。
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
    private final CheckBox unique = new CheckBox(I18n.t("idx.unique"));
    private final Label error = new Label();
    private final Label chosenHeader = new Label();
    private final SqlEditor preview = new SqlEditor();
    private final ObservableList<String> available = FXCollections.observableArrayList();
    private final ObservableList<PickedColumn> chosen = FXCollections.observableArrayList();
    private final ListView<PickedColumn> chosenList = new ListView<>(chosen);
    /** 加列的唯一入口：下拉里就是「可用列」，计数写在按钮上（U21）。 */
    private final MenuButton add = new MenuButton();
    /** 拖拽中的行下标；{@code -1} = 没有在本列表内拖（拖放只接受自己人）。 */
    private int dragFrom = -1;
    private final Button copySql = IconButtons.of(Icons.Icon.COPY, I18n.t("newtable.sql.copy"));
    private final Button collapseSql = IconButtons.of(Icons.Icon.CHEVRON_RIGHT,
            I18n.t("idx.sql.collapse"));
    private final Region sqlGap = new Region();
    private final VBox picker = new VBox(8);
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
        refreshAvailable();
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
        DialogStyles.apply(dialog, owner, "index");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    // ─────────────────────────── 界面 ───────────────────────────

    private void buildUi() {
        dialog.setTitle(I18n.t("structure.index.add"));
        // ⭐ V-11：宽度走档位规范。U21 之前它是 PICKER=800 —— 那一档是为「两个列表并排
        //    + 中间四颗按钮」开的；单列表之后 720 就放得下「序号 + 列名 + 方向 + ×」，
        //    再宽只是让 SQL 预览两侧留白。
        // ⚠️ 高度取 §6.1 区间的上界 620（原来 560）：560 下标题 / 基本信息 / 索引列 / 提示
        //    把内容区吃满，SQL 预览被压成**一行**（D 区形同不存在，验收帧 p7 实拍抓到）。
        //    最小高度仍留 560，小屏时由 DialogStyles 限高。
        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);
        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);
        dialog.getDialogPane().setPrefHeight(620);
        dialog.getDialogPane().setMinHeight(560);
        // 不用 setHeaderText：A 区要同时放标题 + 真实表名 + 关闭钮，
        // 标准 header 只给一行居中的小字（与「新建表」共用同一套骨架）。
        dialog.setHeaderText(null);
        dialog.setResizable(true);

        name.setId("index-dialog-name");
        name.setPromptText(I18n.t("idx.name.prompt"));
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

        ButtonType ok = new ButtonType(I18n.t("common.ok"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
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
        Label title = new Label(I18n.t("structure.index.add"));
        title.getStyleClass().add("adb-dialog__title");
        // ⭐ 表名 / 库名 / 方言全部来自真实上下文，不写死示例（§6.2 A）。
        //    会话标签本身就是「方言 · 库」（见 JdbcSession.of），再补一遍方言就成了
        //    「SQLite · 文件 · SQLite」—— 实测截图里就是这么重复的。
        Label context = DialogStyles.contextLabel(I18n.t("idx.context",
                table == null ? "?" : table.name(), session.label()));
        Button close = IconButtons.icon(Icons.Icon.CLOSE, I18n.t("tab.close"));
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
        VBox nameBox = labelledField(I18n.t("idx.field.name"), name);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox row;
        if (indexTypes.isEmpty()) {
            row = new HBox(nameBox);
        } else {
            buildTypeChooser();
            VBox typeBox = labelledField(I18n.t("idx.field.type"), type);
            typeBox.setPrefWidth(200);
            row = new HBox(12, nameBox, typeBox);
        }
        return section(I18n.t("idx.section.basic"), row, unique);
    }

    /**
     * 索引类型下拉 —— 只有 {@link io.agenticdb.jdbc.Dialect#indexTypes()} 给了候选
     * 才摆出来。只有一个候选时也不摆：一个永远只能填同一个值的下拉，
     * 比没有下拉更让用户以为「这里有得选」（§九 不留无用控件）。
     */
    private void buildTypeChooser() {
        type.setId("index-dialog-type");
        type.getItems().setAll(indexTypes);
        type.setPromptText(I18n.t("idx.type.prompt"));
        type.setMaxWidth(Double.MAX_VALUE);
        type.setTooltip(new Tooltip(I18n.t("idx.type.tooltip",
                session.dialect().displayName())));
        type.valueProperty().addListener((observable, oldValue, newValue) -> refreshPreview());
    }

    /** C 区：索引列单列表 —— 加列走「添加列」下拉，排序靠拖行与 {@code Alt+↑/↓}。 */
    private VBox buildPickerSection() {
        buildPicker();
        chosenHeader.getStyleClass().add("adb-dialog__section-title");
        Label hint = new Label(I18n.t("idx.pick.hint",
                io.agenticdb.ui.Shortcuts.of("Delete")));
        hint.getStyleClass().add("adb-muted");
        Region headGap = new Region();
        HBox.setHgrow(headGap, Priority.ALWAYS);
        HBox head = new HBox(8, chosenHeader, headGap, add);
        head.setAlignment(Pos.CENTER_LEFT);
        picker.getChildren().setAll(head, chosenList, hint);
        VBox.setVgrow(chosenList, Priority.ALWAYS);
        chosenList.setMinHeight(140);
        // 220 → 200：给 D 区（SQL 预览）留出一行半以上的余量，见 buildUi 的高度注释。
        picker.setPrefHeight(200);
        return section(I18n.t("idx.section.columns"), picker);
    }

    /**
     * 单列表的接线：加列 / 移除 / 排序三条路都收在一处方法上 ——
     * 鼠标、键盘、下拉各写一份迟早会走出不同结果（§6.5.5）。
     */
    private void buildPicker() {
        chosenList.setId("index-dialog-chosen");
        chosenList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        chosenList.setPlaceholder(EmptyState.pickerHint(
                I18n.t("idx.picker.empty")));
        chosenList.setCellFactory(list -> new PickedCell());
        chosenList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                removeSelected();
                event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.UP) {
                moveSelected(-1);
                event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.DOWN) {
                moveSelected(1);
                event.consume();
            }
        });

        IconButtons.labeled(add, Icons.Icon.ADD, I18n.t("idx.add.label"),
                I18n.t("idx.add.tooltip"));
        add.setId("index-dialog-add");
        // 与 U4 同一口径：宁可让窄弹窗里出现滚动，也不把带文字的按钮压成光秃图标。
        add.setMinWidth(Region.USE_PREF_SIZE);

        // 索引列一变：可用列重算、计数与下拉重写、预览重生成（§6.5.2/3/4）。
        chosen.addListener((ListChangeListener<PickedColumn>) change -> {
            refreshAvailable();
            refreshPreview();
        });

        copySql.setId("index-dialog-copy-sql");
        copySql.setOnAction(event -> copyPreview());
        collapseSql.setId("index-dialog-collapse-sql");
        collapseSql.setOnAction(event -> setSqlVisible(!sqlVisible));
    }

    // ─────────────────────────── 行为 ───────────────────────────

    /** 一列进索引列，追加在末尾 —— 复合索引的前缀顺序由用户（或拖拽）决定。 */
    private void addColumn(String column) {
        chosen.add(new PickedColumn(column));
        chosenList.getSelectionModel().clearAndSelect(chosen.size() - 1);
    }

    /** 选中行退回可用列，选中仍然停在同一位置的那一项。 */
    private void removeSelected() {
        removeAt(chosenList.getSelectionModel().getSelectedIndex());
    }

    /** 键盘排序：Alt+↑ / Alt+↓ 每次一位（拖拽走 {@link #move}，同一条路）。 */
    private void moveSelected(int direction) {
        int from = chosenList.getSelectionModel().getSelectedIndex();
        move(from, from + direction);
    }

    /** 把第 {@code from} 列挪到 {@code to}：复合索引的列顺序就是最左前缀，必须能调。 */
    private boolean move(int from, int to) {
        if (from < 0 || to < 0 || from >= chosen.size() || to >= chosen.size()
                || from == to) {
            return false;
        }
        PickedColumn moving = chosen.remove(from);
        chosen.add(to, moving);
        chosenList.getSelectionModel().clearAndSelect(to);
        // 序号写在单元格里，数据位置变了但 item 没变 → 不 refresh 的话
        // 「1. 2. 3.」会跟着行走，界面上的顺序就是假的。
        chosenList.refresh();
        return true;
    }

    /**
     * 可用列按表的原始顺序重算（不会出现顺序漂移），并同步「添加列」下拉、
     * 计数与可用性 —— 一列一列加完，下拉里最后会没有东西可选，那就禁用。
     */
    private void refreshAvailable() {
        List<String> picked = chosen.stream().map(PickedColumn::name).toList();
        List<String> remaining = new ArrayList<>(table == null ? List.of()
                : table.columns().stream().map(column -> column.name()).toList());
        remaining.removeAll(picked);
        available.setAll(remaining);
        chosenHeader.setText(I18n.t("idx.chosen.header", chosen.size()));
        // 计数写在按钮上：可用列不再有独立列表，这一颗下拉就是它唯一的入口和证据。
        add.setText(I18n.t("idx.add.count", remaining.size()));
        add.setDisable(remaining.isEmpty());
        String hint = remaining.isEmpty()
                ? I18n.t("idx.add.allPicked") : I18n.t("idx.add.tooltip");
        add.setTooltip(new Tooltip(hint));
        add.setAccessibleText(hint);
        List<MenuItem> items = new ArrayList<>();
        for (String column : remaining) {
            MenuItem item = new MenuItem(column);
            item.setOnAction(event -> addColumn(column));
            items.add(item);
        }
        add.getItems().setAll(items);
    }

    /** D 区：SQL 预览（等宽、行号、可折叠），与真正执行的那条同源。 */
    private VBox buildSqlSection() {
        Label title = new Label(I18n.t("idx.sql.title"));
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
        preview.setHint(I18n.t("idx.sql.hint"));
        preview.setPrefHeight(96);
        // ⭐ 预览是 D 区，不是一条装订线：不给最小高度，VBox 在高度紧张时
        //    会把它压到只剩一行（560px 窗口下实测 16px），用户看不到完整语句。
        preview.setMinHeight(72);
        return new VBox(8, header, preview);
    }

    private void setSqlVisible(boolean visible) {
        sqlVisible = visible;
        preview.setVisible(visible);
        preview.setManaged(visible);
        collapseSql.setGraphic(Icons.of(visible
                ? Icons.Icon.CHEVRON_RIGHT : Icons.Icon.CHEVRON_LEFT));
        collapseSql.setText(visible ? I18n.t("idx.sql.collapse") : I18n.t("idx.sql.expand"));
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
            return I18n.t("idx.error.noName");
        }
        if (!raw.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            return I18n.t("idx.error.badName");
        }
        for (String existing : existingIndexNames) {
            if (existing.equalsIgnoreCase(raw)) {
                return I18n.t("idx.error.duplicate", raw);
            }
        }
        if (chosen.isEmpty()) {
            return I18n.t("idx.error.noColumns");
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
     * 已选索引列的一行：<b>序号 + 列名 + 排序方向 + 移除</b>
     * （方向只在方言支持时出现）。
     *
     * <p>序号是复合索引唯一的顺序证据 —— 只靠列表行序，用户看不出谁是前缀。
     *
     * <p>这一行同时是<b>拖拽源与落点</b>（U21）：拖到哪一行就落到哪个位置，
     * 所以排序不需要「上移 / 下移」两颗按钮；键盘走 {@code Alt+↑/↓}，
     * 两条路最终都进 {@link #move(int, int)} 同一个方法。
     */
    private final class PickedCell extends ListCell<PickedColumn> {

        private final Label order = new Label();
        private final Label column = new Label();
        /** 方言不支持排序方向时是 {@code null} —— 连控件都不创建（§九 不留无用控件）。 */
        private final ComboBox<String> direction;
        private final Button remove;
        private final HBox row;
        private PickedColumn bound;

        PickedCell() {
            order.getStyleClass().add("adb-picked__order");
            order.setMinWidth(Region.USE_PREF_SIZE);
            column.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(column, Priority.ALWAYS);
            // 拖动手势在整行上，但**光标只在非交互区**显示成「移动」：
            // 下拉和 × 上再挂 MOVE 就是骗人 —— 它们点下去是选值、是移除，不是拖。
            order.setCursor(Cursor.MOVE);
            column.setCursor(Cursor.MOVE);
            List<Node> cells = new ArrayList<>();
            cells.add(order);
            cells.add(column);
            if (directionSupported) {
                // ⭐ 比较用同一个取好的值，不再 "降序".equals —— 翻译后字面量必然失配。
                String descendingValue = I18n.t("idx.direction.desc");
                ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(
                        I18n.t("idx.direction.asc"), descendingValue));
                box.setPrefWidth(76);
                box.setTooltip(new Tooltip(I18n.t("idx.direction.tooltip",
                        io.agenticdb.ui.Shortcuts.of("Alt+UP"),
                        io.agenticdb.ui.Shortcuts.of("Alt+DOWN"))));
                // 用值变化而不是 onAction：方向是这一列的**状态**，不是一次动作，
                // 而且程序化选择（默认值回填、单测）也要走同一条路。
                box.valueProperty().addListener((observable, oldValue, newValue) -> {
                    if (bound == null) {
                        return;
                    }
                    bound.setDescending(descendingValue.equals(newValue));
                    refreshPreview();
                });
                direction = box;
                cells.add(box);
            } else {
                direction = null;
            }
            remove = IconButtons.icon(Icons.Icon.CLOSE,
                    I18n.t("idx.remove.tooltip", io.agenticdb.ui.Shortcuts.of("Delete")));
            remove.getStyleClass().add("adb-icon-button");
            remove.setMinWidth(Region.USE_PREF_SIZE);
            remove.setCursor(Cursor.HAND);
            remove.setOnAction(event -> removeAt(indexOf(bound)));
            cells.add(remove);
            row = new HBox(8, cells.toArray(Node[]::new));
            row.setAlignment(Pos.CENTER_LEFT);
            wireDrag();
        }

        /** bound 在列表里的位置 —— 单元格被复用后必须按当前绑定重新算，不能用 getIndex() 的缓存。 */
        private int indexOf(PickedColumn item) {
            return item == null ? -1 : chosen.indexOf(item);
        }

        /** 拖拽排序：整行既是源也是落点，落在哪一行就插到哪个位置。 */
        private void wireDrag() {
            row.setOnDragDetected(event -> {
                if (bound == null) {
                    return;
                }
                dragFrom = indexOf(bound);
                ClipboardContent content = new ClipboardContent();
                content.putString(bound.name());
                Dragboard board = row.startDragAndDrop(TransferMode.MOVE);
                board.setContent(content);
                event.consume();
            });
            row.setOnDragOver(event -> {
                // dragFrom 只有本列表的行会写：拖到弹窗别处（输入框、SQL 预览）不会被接受。
                if (dragFrom >= 0) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            row.setOnDragDropped(event -> {
                boolean done = move(dragFrom, getIndex());
                event.setDropCompleted(done);
                dragFrom = -1;
                event.consume();
            });
            row.setOnDragDone(event -> dragFrom = -1);
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

    /** 行尾 × 移除第 {@code index} 列 —— 与 Delete 键走同一个出口（不依赖选中）。 */
    private void removeAt(int index) {
        if (index < 0 || index >= chosen.size()) {
            return;
        }
        chosen.remove(index);
        if (!chosen.isEmpty()) {
            chosenList.getSelectionModel().clearAndSelect(Math.min(index, chosen.size() - 1));
        }
    }

    // ─────────────────────────── 布局小件 ───────────────────────────

    /** 标签在上、控件在下的一格表单字段（与「新建表」同一份形态）。 */
    private static VBox labelledField(String text, Control field) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-form__label");
        return new VBox(4, label, field);
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
     * <p>为什么要有它：不摆态的话，验收帧里永远是「索引列（0）+ 空预览」，
     * 而 §6.4 要的恰恰是顺序与预览那一面 —— 与 {@code ADB_CELL} 同一形态。
     * 加的列走 {@link #addColumn(String)}（和点下拉同一条路），不是往列表里塞数据：
     * 摆出来的态和点出来的态必须是同一份代码算出来的，否则截图在骗人。
     */
    void poseForShots() {
        List<String> toAdd = available.stream().limit(2).toList();
        for (String column : toAdd) {
            addColumn(column);
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

    ListView<PickedColumn> chosenListView() {
        return chosenList;
    }

    MenuButton addButton() {
        return add;
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
