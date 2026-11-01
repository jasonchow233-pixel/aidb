package io.agenticdb.ui.shell;

import io.agenticdb.introspect.ForeignKeyDef;
import io.agenticdb.introspect.IndexDef;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.introspect.StructureSqlBuilder;
import io.agenticdb.introspect.TableStructure;
import io.agenticdb.introspect.TriggerDef;
import io.agenticdb.jdbc.ColumnTypes;
import io.agenticdb.jdbc.ColumnTypeOption;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.ColumnType;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.RowHeights;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * T1 新建表 —— <b>弹窗</b>，不是标签页。
 *
 * <p>形态依据见 {@code docs/UI-FORMS-AND-STATES.md} §4：建表是「改一次、然后关掉」的
 * 一次性动作。做成标签页会留下一个<b>关不掉</b>的「空表」标签 —— 表还没建呢，
 * 标签已经在那儿了；建完也不知该不该关。Navicat 也是弹窗。
 *
 * <h2>R30-D：弹窗内部是多标签设计器</h2>
 * 字段 / 索引 / 外键 / 触发器 / 选项 / SQL 预览六页，对齐 Navicat 的表设计器。
 * 索引 / 外键 / 触发器三页是**列表 + 子弹窗**（Navicat 同样如此）：采集与校验直接
 * 复用 {@link IndexDialog} / {@link ForeignKeyDialog} / {@link TriggerDialog}，
 * 本类只负责把它们收集到同一个 {@link StructurePlan} 里 —— 预览的 SQL 与
 * {@link StructurePlanRunner} 真正执行的 SQL 出自同一处。
 *
 * <p>「还没建出来的表」用一个<b>草稿结构</b>（{@link #draftStructure()}）喂给那三个
 * 子弹窗：它们的校验（列存在、重名、列数对齐）因此对新表同样成立。
 *
 * <h2>与结构页共用的部分</h2>
 * 列定义、主键摆放顺序、注释落点、外键 / 索引 / 触发器的 SQL 全部交给
 * {@link StructureSqlBuilder} 和方言，这里只收集输入并把生成的 SQL <b>实时</b>显示出来。
 */
final class NewTableDialog {

    /** 表名还没填时的预览空态；填了却拼不出 SQL 时换成真正的原因。 */
    private static String previewEmptyHint() {
        return I18n.t("newtable.preview.emptyHint");
    }

    /**
     * ⭐ R31：字段网格行高跟随清单档位（Navicat 实测 22px，本档位 21px）。
     *
     * <p>以前写死 30px —— 同样一屏 Navicat 能放 15 个字段，这里只放得下 10 个；
     * 建一张宽表要反复滚动，而滚动时表头之外的另一半信息（属性面板）也看不见。
     */
    private static final int FIELD_ROW_HEIGHT = RowHeights.of("normal");

    /**
     * ⭐ S-4：网格坐标（行下标 / 列下标）。
     *
     * <p>用 record 是为了让 {@link #move} / {@link #next} / {@link #previous} 能直接被
     * 门禁驱动 —— 它们不碰 JavaFX，headless 下也不需要真实焦点（§28.10 的教训）。
     */
    record CellPos(int row, int column) {
    }

    private final Dialog<StructurePlan> dialog = new Dialog<>();
    private final DbSession session;
    private final List<String> existingTables;

    private final TextField name = new TextField();
    private final TextField tableComment = new TextField();
    /** ⭐ 规格 §13.4：SQL 预览是只读的 SQL 编辑器（等宽 / 行号 / 高亮）。 */
    private SqlEditor previewEditor;
    private final Label error = new Label();
    private final ObservableList<ColumnRow> rows = FXCollections.observableArrayList();
    /** 列网格的强引用 —— 门禁要直接驱动它的布尔单元格验证键盘可达。 */
    private TableView<ColumnRow> columnsTable;
    /** ⭐ R31：「类型」列 —— 门禁要断言它是「编辑态才弹」的可编辑下拉。 */
    private TableColumn<ColumnRow, String> typeColumn;
    /** ⭐ R31：字段页的属性面板（Navicat 形态：网格选中一行，下面编辑它的属性）。 */
    private final TextField fieldDefault = new TextField();
    private final ComboBox<String> fieldCharset = new ComboBox<>();
    private final ComboBox<String> fieldCollation = new ComboBox<>();
    private final Label fieldPropertiesHint = new Label(I18n.t("newtable.properties.hint"));
    /** 面板正在回填选中行的值 —— 这时的控件变化不是用户输入，不能写回模型。 */
    private boolean syncingProperties;
    /** ⭐ R31：Navicat 底部的「字段数」。 */
    private final Label fieldCount = new Label();
    /** 本方言的「类型」候选；空 = 自由文本输入。 */
    private final List<ColumnTypeOption> typeOptions;

    /** 设计器里「还没建出来」的索引 / 外键 / 触发器（与字段一起进同一个计划）。 */
    private final ObservableList<IndexDef> indexes = FXCollections.observableArrayList();
    private final ObservableList<ForeignKeyDef> foreignKeys = FXCollections.observableArrayList();
    private final ObservableList<TriggerDef> triggers = FXCollections.observableArrayList();
    private final ListView<IndexDef> indexList = new ListView<>(indexes);
    private final ListView<ForeignKeyDef> foreignKeyList = new ListView<>(foreignKeys);
    private final ListView<TriggerDef> triggerList = new ListView<>(triggers);
    private final TabPane tabs = new TabPane();
    private Tab columnsTab;
    private Tab indexTab;
    private Tab foreignKeyTab;
    private Tab triggerTab;

    /** 子弹窗（索引 / 外键 / 触发器）的宿主与样式表 —— show() 时记下来。 */
    private Window owner;
    private Collection<String> stylesheets = List.of();

    private NewTableDialog(DbSession session, List<String> existingTables) {
        this.session = session;
        this.existingTables = existingTables == null ? List.of() : List.copyOf(existingTables);
        this.typeOptions = session.dialect().columnTypes();
        buildUi();
        addDefaultRow();
        refreshPreview();
    }

    static NewTableDialog create(DbSession session, List<String> existingTables) {
        return new NewTableDialog(session, existingTables);
    }

    /** 显示并返回建表计划；用户取消或校验不通过时为 {@link Optional#empty()}。 */
    Optional<StructurePlan> show(Window owner, Collection<String> stylesheets) {
        this.owner = owner;
        this.stylesheets = stylesheets == null ? List.of() : List.copyOf(stylesheets);
        if (!this.stylesheets.isEmpty()) {
            dialog.getDialogPane().getStylesheets().addAll(this.stylesheets);
        }
        // ⭐ 主题类与 adb-dialog 类必须抄到 DialogPane 上 —— 否则弹窗里所有
        //    -adb-* 查找落空，退回 JavaFX 默认皮肤。这条不能排在 if 里面：
        //    没有外部样式表（单测直接 show(null, List.of())）时同样要挂上。
        DialogStyles.apply(dialog, owner);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        return dialog.showAndWait();
    }

    // ─────────────────────────── 界面 ───────────────────────────

    private void buildUi() {
        // ⭐ N26：标题 + 库名进系统标题栏；内容区不再画自绘抬头条。
        String context = DialogStyles.shortenContext(session.label(), DialogStyles.PATH_BUDGET);
        dialog.setTitle(I18n.t("ribbon.newTable") + " · " + context);
        // ⭐ 规格 §13.1：850–1000px。720px 下「默认值 / 注释」两列会被压成
        //    一个省略号，改一个默认值先得横向滚 —— 建表是高频操作，不能这样。
        dialog.getDialogPane().setPrefWidth(DialogSizes.TABLE);
        dialog.getDialogPane().setMinWidth(DialogSizes.TABLE);
        dialog.getDialogPane().setMinHeight(600);
        // ⭐ R30-D：有了页签，窗口要比「一屏字段 + 预览」高一点，否则对象页的列表
        //    只剩两行、SQL 预览页要内部滚动。680 仍在 1280×800 下 DialogStyles 的限高内。
        dialog.getDialogPane().setPrefHeight(680);
        dialog.setHeaderText(null);

        name.setId("new-table-name");
        name.setPromptText(I18n.t("newtable.name"));
        name.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        boolean commentSupported = session.dialect().supportsTableComment();
        tableComment.setId("new-table-comment");
        tableComment.setPromptText(commentSupported
                ? I18n.t("newtable.comment.prompt") : I18n.t("newtable.comment.unsupportedPrompt", session.dialect().displayName()));
        tableComment.setDisable(!commentSupported);
        tableComment.textProperty().addListener((observable, oldValue, newValue) -> refreshPreview());

        TableView<ColumnRow> table = buildColumnsTable();
        table.setId("new-table-columns");
        this.columnsTable = table;
        // ⚠️ TableView 的默认首选高度很大（400+）—— 弹窗会被撑到比屏幕还高，
        //    底部的「创建 / 取消」跑到屏幕外，用户点不到。固定行高 + 限高。
        table.setFixedCellSize(FIELD_ROW_HEIGHT);
        fitTableHeight(table);

        // ⭐ R31：工具条按 Navicat 摆在网格<b>上方</b>（添加 / 插入 / 删除 / 上移 / 下移）——
        //    以前只有「添加 / 删除」两颗按钮、还挂在网格下面，调列顺序无路可走。
        Button add = IconButtons.of(Icons.Icon.ADD, I18n.t("newtable.field.add"));
        add.setId("new-table-add-column");
        add.setOnAction(event -> {
            addRow();
            fitTableHeight(table);
        });
        Button insert = IconButtons.of(Icons.Icon.ADD, I18n.t("newtable.field.insert"));
        insert.setId("new-table-insert-column");
        insert.setOnAction(event -> {
            insertRow();
            fitTableHeight(table);
        });
        Button remove = IconButtons.of(Icons.Icon.DELETE, I18n.t("newtable.field.delete"));
        remove.setId("new-table-remove-column");
        remove.setOnAction(event -> {
            removeSelectedRow();
            fitTableHeight(table);
        });
        Button moveUp = IconButtons.icon(Icons.Icon.MOVE_UP, I18n.t("newtable.moveUp"));
        moveUp.setId("new-table-move-up");
        moveUp.setOnAction(event -> moveSelectedRow(-1));
        Button moveDown = IconButtons.icon(Icons.Icon.MOVE_DOWN, I18n.t("newtable.moveDown"));
        moveDown.setId("new-table-move-down");
        moveDown.setOnAction(event -> moveSelectedRow(1));

        HBox rowButtons = new HBox(8, add, insert, remove, moveUp, moveDown);
        rowButtons.setAlignment(Pos.CENTER_LEFT);
        rowButtons.getStyleClass().add("adb-new-table__row-actions");
        // ⭐ 内容区按钮挂基础类：`.adb-new-table__row-actions .adb-button` 这条
        //    紧凑内边距的规则此前一直是死的（按钮没这个类），按下 / 禁用态也一起落空。
        for (Button button : new Button[] {add, insert, remove, moveUp, moveDown}) {
            button.getStyleClass().add("adb-button");
        }

        fieldCount.setId("new-table-field-count");
        fieldCount.getStyleClass().add("adb-muted");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox fieldToolbar = new HBox(8, rowButtons, spacer, fieldCount);
        fieldToolbar.setAlignment(Pos.CENTER_LEFT);

        // ⭐ R31：Navicat 形态 —— 工具条 / 网格 / 属性面板三段。
        //    属性面板（默认值 / 字符集 / 排序规则）放在网格下方，选中哪行改哪行；
        //    默认值从网格里挪出来，给「长度 / 小数 / 自增」腾出列宽。
        VBox fieldPage = new VBox(8, fieldToolbar, table, buildFieldProperties());
        VBox.setVgrow(table, Priority.ALWAYS);
        // 还没有选中行：属性面板三格先按「不可编辑」摆出来。
        showProperties(null);

        // ⭐ 规格 §13.4：SQL 预览要像编辑器（等宽 / 行号 / 高亮），
        //    不是一个灰底的 TextArea。复用 SqlEditor —— 查询页那个本来就是它。
        previewEditor = new SqlEditor();
        previewEditor.area().setEditable(false);
        previewEditor.area().setId("new-table-preview");
        // ⭐ 预览是**只读的**、也**不能执行** —— 沿用 SqlEditor 默认那句
        //    「输入 SQL，⌘↵ 执行当前语句」是误导（用户会去敲键盘、按 ⌘↵，
        //    然后什么都没发生）。setHint 是 §11.2/§28.8 为只读视图加的口子，
        //    这里必须用上（结构页 DDL 已经用了）。
        previewEditor.setHint(previewEmptyHint());
        previewEditor.setPrefHeight(168);
        VBox.setVgrow(previewEditor, Priority.ALWAYS);

        error.setId("new-table-error");
        error.getStyleClass().add("adb-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        tabs.setId("new-table-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        columnsTab = tab(I18n.t("newtable.tab.fields"), "new-table-tab-columns", fieldPage);
        indexTab = tab(I18n.t("structure.tab.indexes"), "new-table-tab-indexes", buildObjectsPage(indexList,
                I18n.t("newtable.empty.indexes"), I18n.t("newtable.index.add"), "new-table-indexes",
                indexes, this::addIndex,
                index -> index.name() + "  ·  " + index.columnsLabel()
                        + (index.unique() ? "  ·  " + I18n.t("structure.col.unique") : "")));
        foreignKeyTab = tab(I18n.t("structure.tab.foreignKeys"), "new-table-tab-foreign-keys", buildObjectsPage(
                foreignKeyList, I18n.t("newtable.empty.foreignKeys"), I18n.t("newtable.foreignKey.add"),
                "new-table-foreign-keys", foreignKeys, this::addForeignKey,
                key -> key.displayName() + "  ·  " + String.join(", ", key.columns())
                        + " → " + key.referencedTable()));
        triggerTab = tab(I18n.t("structure.tab.triggers"), "new-table-tab-triggers", buildObjectsPage(triggerList,
                I18n.t("newtable.empty.triggers"), I18n.t("newtable.trigger.add"),
                "new-table-triggers", triggers, this::addTrigger,
                trigger -> trigger.name() + "  ·  " + trigger.timingLabel()
                        + " " + trigger.eventLabel()));
        tabs.getTabs().setAll(columnsTab, indexTab, foreignKeyTab, triggerTab,
                buildOptionsTab(), buildCommentTab(), buildSqlTab());

        // 间距与内边距由 .adb-dialog__body 统一给（CSS 会盖掉这里的构造参数，
        // 写成一致的值只为了读代码的人不被误导）。
        VBox body = new VBox(12,
                buildNameSection(),
                tabs,
                error);
        body.getStyleClass().add("adb-dialog__body");
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dialog.getDialogPane().setContent(body);
        dialog.setResizable(true);

        ButtonType ok = new ButtonType(I18n.t("newtable.create"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(I18n.t("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
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
        // 三张对象清单任何一张变了，SQL 预览与页签计数都要跟着变 —— 预览与执行同源。
        indexes.addListener((ListChangeListener<IndexDef>) change -> refreshPreview());
        foreignKeys.addListener((ListChangeListener<ForeignKeyDef>) change -> refreshPreview());
        triggers.addListener((ListChangeListener<TriggerDef>) change -> refreshPreview());
    }

    // ─────────────────────── R30-D：设计器分区 ───────────────────────

    private static Tab tab(String title, String id, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setId(id);
        tab.setClosable(false);
        return tab;
    }

    /** 表名固定在最上面：它是这张表的身份，切到任何一页都得看得见。 */
    private HBox buildNameSection() {
        Label label = formLabel(I18n.t("newtable.name"));
        label.setMinWidth(56);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox box = new HBox(10, label, name);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * 对象页（索引 / 外键 / 触发器）：<b>列表 + 两颗按钮</b>。
     *
     * <p>与 Navicat 表设计器同形：页里是「已经加进来的对象」，新建走子弹窗。
     * 删除有两条路（按钮 / Delete 键），最终都进同一个 remove。
     */
    private <T> VBox buildObjectsPage(ListView<T> list, String emptyText, String addLabel,
                                      String idPrefix, ObservableList<T> backing,
                                      Runnable onAdd, java.util.function.Function<T, String> label) {
        list.setId(idPrefix);
        list.setPlaceholder(EmptyState.pickerHint(emptyText));
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label.apply(item));
            }
        });
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                removeSelected(list, backing);
                event.consume();
            }
        });

        Button add = IconButtons.of(Icons.Icon.ADD, addLabel);
        add.setId(idPrefix + "-add");
        add.getStyleClass().add("adb-button");
        add.setOnAction(event -> onAdd.run());
        Button remove = IconButtons.of(Icons.Icon.DELETE, I18n.t("newtable.objects.remove"));
        remove.setId(idPrefix + "-remove");
        remove.getStyleClass().add("adb-button");
        remove.setOnAction(event -> removeSelected(list, backing));
        HBox buttons = new HBox(8, add, remove);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(I18n.t("newtable.objectsHint"));
        hint.getStyleClass().add("adb-muted");
        VBox.setVgrow(list, Priority.ALWAYS);
        return new VBox(8, list, buttons, hint);
    }

    private <T> void removeSelected(ListView<T> list, ObservableList<T> backing) {
        T selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            backing.remove(selected);
        }
    }

    private void addIndex() {
        IndexDialog.create(session, draftStructure())
                .show(owner, stylesheets)
                .ifPresent(index -> {
                    indexes.add(index);
                    indexList.getSelectionModel().select(index);
                });
    }

    private void addForeignKey() {
        // ⭐ draft：预览给的是将来写进 CREATE TABLE 的那一段，而不是 SQLite 的整表重建脚本。
        ForeignKeyDialog.createDraft(session, draftStructure())
                .show(owner, stylesheets)
                .ifPresent(key -> {
                    foreignKeys.add(key);
                    foreignKeyList.getSelectionModel().select(key);
                });
    }

    private void addTrigger() {
        TriggerDialog.create(session, draftStructure())
                .show(owner, stylesheets)
                .ifPresent(spec -> {
                    TriggerDef trigger = new TriggerDef(spec.name(), spec.timing(),
                            spec.event(), spec.body());
                    triggers.add(trigger);
                    triggerList.getSelectionModel().select(trigger);
                });
    }

    /**
     * 草稿结构：把「正在填的字段」当成一张还没建出来的表，喂给三个子弹窗。
     *
     * <p>这样它们的校验（列存在 / 重名 / 列数对齐）对新表<b>同样成立</b>，
     * 不用为「新表」另写一套采集逻辑 —— 两套迟早会走出不同规则。
     * 类型只用于展示，`rawType` 带上用户填的原样类型。
     */
    TableStructure draftStructure() {
        List<ColumnDef> columns = rows.stream()
                .map(row -> new ColumnDef(row.name.get().trim(), ColumnType.TEXT,
                        !row.notNull.get(), row.primaryKey.get(),
                        blankToNull(row.defaultValue.get()), blankToNull(row.comment.get()),
                        null, null, null, null, row.unique.get(), row.rawType()))
                .toList();
        List<String> primaryKeys = rows.stream()
                .filter(row -> row.primaryKey.get())
                .map(row -> row.name.get().trim())
                .toList();
        String tableName = name.getText() == null ? "" : name.getText().trim();
        return new TableStructure(tableName.isEmpty() ? "?" : tableName, false, columns,
                primaryKeys, List.copyOf(indexes), List.copyOf(foreignKeys),
                List.copyOf(triggers), List.of(), "", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 选项页：方言会追加什么（只读 —— 没做的选项不摆控件）。
     *
     * <p>⭐ R31：表注释从这里挪到独立的「备注」页 —— Navicat 的页签就是
     * 字段 / 索引 / 外键 / 触发器 / 选项 / 备注 / SQL 预览，注释是和建表选项
     * 并列的一类输入，塞在选项页里是为了少一个页签，代价是用户找不到。
     */
    private Tab buildOptionsTab() {
        Label options = new Label(optionsText());
        options.setWrapText(true);
        Label dialect = new Label(session.dialect().displayName());

        VBox box = new VBox(12,
                labelledInfo(I18n.t("newtable.options.heading"), options),
                labelledInfo(I18n.t("newtable.options.dialect"), dialect));
        return tab(I18n.t("structure.tab.options"), "new-table-tab-options", box);
    }

    /** ⭐ R31：「备注」页 —— 表注释的唯一入口（不支持的方言置灰并说明原因）。 */
    private Tab buildCommentTab() {
        tableComment.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tableComment, Priority.ALWAYS);
        HBox commentRow = new HBox(10, formLabel(I18n.t("structure.tableComment.button")), tableComment);
        commentRow.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label(session.dialect().supportsTableComment()
                ? I18n.t("newtable.comment.note")
                : I18n.t("newtable.comment.unsupportedNote", session.dialect().displayName()));
        note.getStyleClass().add("adb-muted");
        note.setWrapText(true);

        VBox box = new VBox(12, commentRow, note);
        return tab(I18n.t("newtable.tab.comment"), "new-table-tab-comment", box);
    }

    /** 方言会自动追加的建表选项；没有就明说「不追加」，不让用户以为漏了设置。 */
    private String optionsText() {
        String options = session.dialect().createTableOptions();
        return options == null || options.isBlank()
                ? I18n.t("newtable.options.none") : options;
    }

    private static VBox labelledInfo(String text, Label value) {
        return new VBox(4, formLabel(text), value);
    }

    private Tab buildSqlTab() {
        Button copySql = IconButtons.of(Icons.Icon.COPY, I18n.t("newtable.sql.copy"));
        copySql.setId("new-table-copy-sql");
        copySql.setOnAction(event -> copyPreview());
        Label sqlTitle = new Label(I18n.t("newtable.sql.title"));
        sqlTitle.getStyleClass().add("adb-dialog__section-title");
        HBox header = new HBox(8, sqlTitle, copySql);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(sqlTitle, Priority.ALWAYS);
        VBox box = new VBox(8, header, previewEditor);
        VBox.setVgrow(previewEditor, Priority.ALWAYS);
        return tab(I18n.t("newtable.tab.sql"), "new-table-tab-sql", box);
    }

    /**
     * 字段网格的高度跟着行数走。
     *
     * <p>以前固定 200px：只有一行「id」时下面挂着 170px 空白网格，
     * 看起来像「还有很多行没填」；加到 8 行时又开始内部滚动。
     */
    private void fitTableHeight(TableView<ColumnRow> table) {
        double height = 30 + Math.max(1, rows.size()) * FIELD_ROW_HEIGHT;
        table.setPrefHeight(clamp(height, 120, 300));
        table.setMinHeight(120);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 表单行的标签（复用全局的 adb-form__label，不为弹窗另立一套）。 */
    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("adb-form__label");
        return label;
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

    /**
     * ⭐ R31：字段网格对齐 Navicat —— 名称 / 类型 / 长度 / 小数 / 不为空 / 主键 / 唯一 / 自增 / 注释。
     *
     * <p>两处关键改动：
     * <ul>
     *   <li>类型是<b>下拉框</b>（可编辑），选完只会填方言里真的存在的类型名；</li>
     *   <li>长度 / 小数单独成列，类型不吃这两个参数时格子置灰 ——
     *       用户不用再背 {@code DECIMAL(10,2)} 的括号和逗号。</li>
     * </ul>
     *
     * <p>默认值从网格挪到下面的属性面板（Navicat 也是这样）：它只在改某一列时用得上，
     * 占着一整列反而把「自增」挤没了。
     */
    private TableView<ColumnRow> buildColumnsTable() {
        TableView<ColumnRow> table = new TableView<>(rows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        this.typeColumn = buildTypeColumn(146);
        table.getColumns().setAll(
                textColumn(I18n.t("column.name"), "name", 180, row -> row.name),
                typeColumn,
                numberColumn(I18n.t("newtable.col.length"), "length", 58, row -> row.length, this::takesLength),
                numberColumn(I18n.t("newtable.col.decimals"), "decimals", 58, row -> row.decimals, this::takesDecimals),
                booleanColumn(I18n.t("newtable.col.notNull"), 58, row -> row.notNull),
                booleanColumn(I18n.t("structure.col.key"), 46, row -> row.primaryKey),
                booleanColumn(I18n.t("structure.col.unique"), 46, row -> row.unique),
                booleanColumn(I18n.t("newtable.col.autoIncrement"), 72, row -> row.autoIncrement),
                textColumn(I18n.t("column.comment"), "comment", 130, row -> row.comment));
        // ⭐ S-4：Tab / 方向键在整张网格里移动（装上去之前，7 列里有 4 列只能靠鼠标点准）。
        installGridNavigation(table);
        table.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> showProperties(newValue));
        return table;
    }

    /**
     * 类型列：静态时是一行文字，进入编辑态才弹出下拉 —— 常驻 ComboBox 在 21px 行里
     * 会把箭头和文字一起压扁；Navicat 同样是「点开才选」。
     */
    private TableColumn<ColumnRow, String> buildTypeColumn(double width) {
        List<String> names = typeOptions.stream().map(ColumnTypeOption::name).toList();
        TableColumn<ColumnRow, String> column = new TableColumn<>(I18n.t("detail.type"));
        column.setId("new-table-column-type");
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> cell.getValue().type);
        column.setCellFactory(view -> {
            ComboBoxTableCell<ColumnRow, String> cell =
                    new ComboBoxTableCell<>(FXCollections.observableArrayList(names));
            cell.setComboBoxEditable(true);
            return cell;
        });
        column.setOnEditCommit(event -> {
            ColumnRow row = event.getRowValue();
            row.applyRawType(event.getNewValue());
            normalizeType(row);
        });
        return column;
    }

    /**
     * 长度 / 小数：只收数字；类型不吃这个参数时格子置灰。
     *
     * <p>置灰而不是「允许填、执行时忽略」—— 后者会让用户以为自己设上了。
     */
    private TableColumn<ColumnRow, String> numberColumn(String title, String id, double width,
            Function<ColumnRow, StringProperty> property, Predicate<ColumnRow> applicable) {
        TableColumn<ColumnRow, String> column = new TableColumn<>(title);
        column.setId("new-table-column-" + id);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> property.apply(cell.getValue()));
        column.setCellFactory(view -> new TextFieldTableCell<ColumnRow, String>(
                new javafx.util.converter.DefaultStringConverter()) {
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                ColumnRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
                setDisable(row == null || !applicable.test(row));
            }
        });
        column.setOnEditCommit(event -> {
            property.apply(event.getRowValue()).set(event.getNewValue() == null
                    ? "" : event.getNewValue().trim());
        });
        return column;
    }

    /** 换了类型之后，把新类型不接受的参数清掉 —— 否则 DECIMAL 的 10 会跟着走进 INT。 */
    void normalizeType(ColumnRow row) {
        ColumnTypeOption option = option(row);
        if (option.name().isEmpty()) {
            return;
        }
        if (!takesLength(row)) {
            row.length.set("");
        }
        if (!takesDecimals(row)) {
            row.decimals.set("");
        }
    }

    /** 这一列的类型吃不吃「长度」参数（{@code INTEGER} 不吃，{@code VARCHAR} 吃）。 */
    boolean takesLength(ColumnRow row) {
        return option(row).length();
    }

    /** 这一列的类型吃不吃「小数位」参数（只有定点数吃）。 */
    boolean takesDecimals(ColumnRow row) {
        return option(row).decimals();
    }

    /** 一行当前的类型在候选表里的定义；自定义类型（下拉里没有）当作「不吃参数」。 */
    private ColumnTypeOption option(ColumnRow row) {
        String base = row.baseTypeName();
        for (ColumnTypeOption candidate : typeOptions) {
            if (candidate.name().equalsIgnoreCase(base)) {
                return candidate;
            }
        }
        return ColumnTypeOption.plain(base);
    }

    // ─────────────────────── R31：字段属性面板 ───────────────────────

    /**
     * 网格下方的属性面板：默认值 / 字符集 / 排序规则（Navicat 同位置）。
     *
     * <p>选中哪行就编辑哪行 —— 不选中时三个控件都置灰，提示先选一行。
     * 方言不支持的项置灰并留一句原因（PostgreSQL 的编码是建库时定的）。
     */
    private VBox buildFieldProperties() {
        fieldDefault.setId("new-table-field-default");
        fieldDefault.setPromptText(I18n.t("newtable.default.prompt"));
        fieldDefault.setPrefWidth(300);
        fieldDefault.textProperty().addListener((observable, oldValue, newValue) -> {
            ColumnRow row = selectedRow();
            if (!syncingProperties && row != null) {
                row.defaultValue.set(newValue == null ? "" : newValue);
            }
        });

        fieldCharset.setId("new-table-field-charset");
        fieldCharset.setPrefWidth(300);
        fieldCharset.setEditable(true);
        fieldCharset.getItems().setAll(charsetPresets());
        fieldCharset.setPromptText(charsetSupported()
                ? I18n.t("newtable.charset.prompt")
                : I18n.t("newtable.charset.unsupported", session.dialect().displayName()));
        fieldCharset.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
            ColumnRow row = selectedRow();
            if (!syncingProperties && row != null) {
                row.charset.set(newValue == null ? "" : newValue.trim());
            }
        });

        fieldCollation.setId("new-table-field-collation");
        fieldCollation.setPrefWidth(300);
        fieldCollation.setEditable(true);
        fieldCollation.getItems().setAll(collationPresets());
        fieldCollation.setPromptText(I18n.t("newtable.collation.prompt"));
        fieldCollation.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
            ColumnRow row = selectedRow();
            if (!syncingProperties && row != null) {
                row.collation.set(newValue == null ? "" : newValue.trim());
            }
        });

        GridPane grid = new GridPane();
        grid.getStyleClass().add("adb-new-table__properties");
        grid.setHgap(12);
        grid.setVgap(6);
        grid.add(formLabel(I18n.t("structure.col.default")), 0, 0);
        grid.add(fieldDefault, 1, 0);
        grid.add(formLabel(I18n.t("newtable.field.charset")), 0, 1);
        grid.add(fieldCharset, 1, 1);
        grid.add(formLabel(I18n.t("column.collation")), 0, 2);
        grid.add(fieldCollation, 1, 2);

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(64);
        ColumnConstraints values = new ColumnConstraints();
        values.setPrefWidth(300);
        values.setHgrow(Priority.NEVER);
        grid.getColumnConstraints().setAll(labels, values);

        fieldPropertiesHint.setId("new-table-field-properties-hint");
        fieldPropertiesHint.getStyleClass().add("adb-muted");
        return new VBox(6, fieldPropertiesHint, grid);
    }

    /** 选中行变了：把它的值回填进面板（守卫住回写，避免「回填 → 写回」绕圈）。 */
    private void showProperties(ColumnRow row) {
        boolean textual = row != null && takesTextOptions(row);
        syncingProperties = true;
        boolean has = row != null;
        fieldDefault.setDisable(!has);
        fieldCharset.setDisable(!has || !charsetSupported() || !textual);
        fieldCollation.setDisable(!has || !textual);
        fieldCharset.setPromptText(!charsetSupported()
                ? I18n.t("newtable.charset.unsupported", session.dialect().displayName())
                : !textual ? I18n.t("newtable.charset.textOnly") : I18n.t("newtable.charset.prompt"));
        fieldCollation.setPromptText(textual ? I18n.t("newtable.collation.prompt")
                : I18n.t("newtable.collation.textOnly"));
        fieldDefault.setText(has ? row.defaultValue.get() : "");
        fieldCharset.getEditor().setText(has && row.charset.get() != null
                ? row.charset.get() : "");
        fieldCollation.getEditor().setText(has && row.collation.get() != null
                ? row.collation.get() : "");
        syncingProperties = false;
        fieldPropertiesHint.setVisible(!has);
        fieldPropertiesHint.setManaged(!has);
    }

    /** 字符集 / 排序规则只对文本类型有意义（整数列写 COLLATE 会被库拒绝）。 */
    boolean takesTextOptions(ColumnRow row) {
        return ColumnTypes.isTextual(row.baseTypeName());
    }

    /** 本方言有没有「列级字符集」这个东西（MySQL 有，PG / SQLite 的编码是建库时定的）。 */
    private boolean charsetSupported() {
        return session.dialect().supportsColumnCharset();
    }

    private ColumnRow selectedRow() {
        return columnsTable == null ? null : columnsTable.getSelectionModel().getSelectedItem();
    }

    /**
     * 候选来自方言（不在这里判 {@code kind() == MYSQL}）：
     * 那等于把「加一种数据库要改几个文件」重新写回 UI 层。
     */
    private List<String> charsetPresets() {
        return session.dialect().columnCharsetPresets();
    }

    private List<String> collationPresets() {
        return session.dialect().columnCollationPresets();
    }

    // ─────────────────────── S-4：网格键盘导航 ───────────────────────

    /**
     * ⭐ S-4：Tab / 方向键在**整张列网格**里移动 + 落点可直接编辑。
     *
     * <p>没有这段时的样子：<b>方向键只换行不选列</b>（7 列里 4 列要编辑，每次都得用鼠标
     * 点准那一格）；<b>Tab 直接跳出整张表格</b> —— 想把一列的 7 个属性挨个填完，
     * 要来回点击。这违反本项目「一切键盘可达」的硬规则（Navicat 的同类网格全键盘可填）。
     *
     * <p><b>为什么判断逻辑全是纯静态方法</b>：headless 门禁里 {@code fireEvent} 送不到
     * 没有真实焦点的单元格（§28.10 踩过：靠事件派发写的测试会假绿或假红）。
     * 所以 {@link #move} / {@link #step} / {@link #next} / {@link #previous}
     * 都是「给坐标、返回坐标」，测试直接驱动它们。
     */
    private void installGridNavigation(TableView<ColumnRow> table) {
        table.setOnKeyPressed(event -> {
            int rowCount = table.getItems().size();
            int columnCount = table.getColumns().size();
            if (rowCount == 0 || columnCount == 0) {
                return;
            }
            CellPos from = currentCell(table);
            boolean backwards = event.getCode() == KeyCode.TAB && event.isShiftDown();
            CellPos to = backwards ? previous(from, rowCount, columnCount)
                    : event.getCode() == KeyCode.TAB ? next(from, rowCount, columnCount)
                    : step(from, event.getCode(), rowCount, columnCount);
            if (to == null) {
                // ⭐ 不吃这个键：最后一格还要让 Tab 能把焦点带出网格，
                //    否则用户会被困在这张表里出不去（键盘可达的反面）。
                return;
            }
            event.consume();
            focusCell(table, to);
            if (event.getCode() == KeyCode.TAB) {
                // Tab 落到新格就进编辑态 —— 「连着往下填」才是 Tab 在这里的意义。
                table.edit(to.row(), table.getColumns().get(to.column()));
            }
        });
    }

    /**
     * ⭐ S-4：从 {@code from} 走一格；出界返回 {@code null}（=「这一步没有意义」）。
     *
     * <p>返回 null 而不是把坐标夹住是有意的：<b>走到头就该停在原处</b>，
     * 不应该「按 ↓ 从最后一行绕回第一行」—— 那会让用户在表格里迷路。
     */
    static CellPos move(CellPos from, int dRow, int dColumn, int rowCount, int columnCount) {
        if (from == null || rowCount <= 0 || columnCount <= 0) {
            return null;
        }
        int row = from.row() + dRow;
        int column = from.column() + dColumn;
        if (row < 0 || row >= rowCount || column < 0 || column >= columnCount) {
            return null;
        }
        return new CellPos(row, column);
    }

    /** ⭐ S-4：方向键各走一格；不是方向键则返回 {@code null}（键要留给别人）。 */
    static CellPos step(CellPos from, KeyCode code, int rowCount, int columnCount) {
        return switch (code) {
            case UP -> move(from, -1, 0, rowCount, columnCount);
            case DOWN -> move(from, 1, 0, rowCount, columnCount);
            case LEFT -> move(from, 0, -1, rowCount, columnCount);
            case RIGHT -> move(from, 0, 1, rowCount, columnCount);
            default -> null;
        };
    }

    /**
     * ⭐ S-4：Tab 的顺序 —— 往右一格，走到行尾换行首。
     *
     * <p><b>最后一格返回 null</b>：那时候 Tab 不再吃键，焦点按系统默认走到网格外面。
     * 一贯到此为止的 Tab 会把用户锁在这张表里。
     */
    static CellPos next(CellPos from, int rowCount, int columnCount) {
        if (rowCount <= 0 || columnCount <= 0) {
            return null;
        }
        if (from == null) {
            return new CellPos(0, 0);
        }
        CellPos right = move(from, 0, 1, rowCount, columnCount);
        if (right != null) {
            return right;
        }
        if (from.row() + 1 < rowCount) {
            return new CellPos(from.row() + 1, 0);
        }
        return null;
    }

    /** ⭐ S-4：⇧Tab 反过来走；回到第一格第一列时同样返回 {@code null}。 */
    static CellPos previous(CellPos from, int rowCount, int columnCount) {
        if (from == null || rowCount <= 0 || columnCount <= 0) {
            return null;
        }
        CellPos left = move(from, 0, -1, rowCount, columnCount);
        if (left != null) {
            return left;
        }
        if (from.row() > 0) {
            return new CellPos(from.row() - 1, columnCount - 1);
        }
        return null;
    }

    /** 当前 focus 的格子；还没有焦点时退回选中那一行的最左列。 */
    private static CellPos currentCell(TableView<ColumnRow> table) {
        TablePosition<ColumnRow, ?> focused = table.getFocusModel().getFocusedCell();
        int row = focused.getRow();
        if (row < 0) {
            row = table.getSelectionModel().getSelectedIndex();
        }
        if (row < 0 || row >= table.getItems().size()) {
            return null;
        }
        int column = focused.getColumn();
        return new CellPos(row, Math.max(0, column));
    }

    private static void focusCell(TableView<ColumnRow> table, CellPos pos) {
        table.getSelectionModel().clearAndSelect(pos.row());
        table.getFocusModel().focus(pos.row(), table.getColumns().get(pos.column()));
        table.scrollTo(pos.row());
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

    /**
     * 建一行<b>和生产同一条刷新链</b>的列 —— 门禁摆态也必须走这里。
     *
     * <p>{@code new ColumnRow(() -> { })} 加进行里之后，改它的属性预览不会重算，
     * 于是「写完默认值 SQL 没变」这种回退能在门禁里全绿（踩过）。
     */
    ColumnRow newRow() {
        return new ColumnRow(this::refreshPreview);
    }

    private void addDefaultRow() {
        // ⭐ 默认给一行 id 主键 —— 空表开出来是空的，用户得先想「第一列叫什么」，
        //    而九成的表第一列就是主键 id。R31 起默认勾上「自增」：
        //    不带自增的主键在 MySQL / PostgreSQL 上等于建完还要手填 id（G15 的原始诉求）。
        ColumnRow row = newRow();
        row.name.set("id");
        row.type.set("INTEGER");
        row.primaryKey.set(true);
        row.autoIncrement.set(true);
        rows.add(row);
    }

    private void addRow() {
        ColumnRow row = newRow();
        row.name.set("column" + (rows.size() + 1));
        row.type.set("TEXT");
        rows.add(row);
        columnsTable.getSelectionModel().select(row);
        refreshPreview();
    }

    /** ⭐ R31：在当前行之前插入一列（没选中就追加到末尾）。 */
    private void insertRow() {
        int at = columnsTable == null ? -1 : columnsTable.getSelectionModel().getSelectedIndex();
        if (at < 0) {
            at = rows.size();
        }
        ColumnRow row = newRow();
        row.name.set("column" + (rows.size() + 1));
        row.type.set("TEXT");
        rows.add(at, row);
        columnsTable.getSelectionModel().select(row);
        refreshPreview();
    }

    private void removeSelectedRow() {
        ColumnRow selected = selectedRow();
        if (selected == null) {
            return;
        }
        rows.remove(selected);
        refreshPreview();
    }

    /** ⭐ R31：上移 / 下移选中列 —— 列顺序就是 CREATE TABLE 里的顺序。 */
    private void moveSelectedRow(int delta) {
        if (columnsTable == null) {
            return;
        }
        int at = columnsTable.getSelectionModel().getSelectedIndex();
        int to = at + delta;
        if (at < 0 || to < 0 || to >= rows.size()) {
            return;
        }
        ColumnRow row = rows.remove(at);
        rows.add(to, row);
        columnsTable.getSelectionModel().select(to);
        columnsTable.getFocusModel().focus(to);
    }

    // ─────────────────────────── 计划与校验 ───────────────────────────

    /** 当前的建表计划；输入不合法时返回 {@code null} 并把原因显示在弹窗里。 */
    StructurePlan buildPlan() {
        try {
            StructurePlan plan = currentPlan();
            hideError();
            return plan;
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            // 报错落在哪一页就把用户带到哪一页 —— 在「字段」页看着一句
            // 「索引引用了表里没有的列」是找不到出路的。
            selectTabFor(e);
            return null;
        }
    }

    /**
     * 设计器的完整计划：字段 + 外键（写进 CREATE TABLE）+ 索引 + 触发器。
     *
     * <p>预览与 {@link StructurePlanRunner} 执行的必须是同一个方法的同一次输出。
     */
    private StructurePlan currentPlan() {
        List<ColumnSpec> columns = rows.stream().map(ColumnRow::toSpec).toList();
        return StructureSqlBuilder.createTableWith(session, name.getText(), columns,
                commentText(), existingTables, List.copyOf(foreignKeys), List.copyOf(indexes),
                List.copyOf(triggers));
    }

    /**
     * 校验失败落在哪一页，就把用户带到哪一页。
     *
     * <p>路由按 {@link StructureSqlBuilder.DesignStep} 的类型走，不按文案猜 ——
     * 按文案 {@code contains("索引")} 的路由，模型层文案一改（迟早双语化）就静默失效。
     */
    private void selectTabFor(IllegalArgumentException e) {
        if (e instanceof StructureSqlBuilder.DesignStepException typed) {
            switch (typed.step()) {
                case FOREIGN_KEY -> tabs.getSelectionModel().select(foreignKeyTab);
                case INDEX -> tabs.getSelectionModel().select(indexTab);
                case TRIGGER -> tabs.getSelectionModel().select(triggerTab);
            }
            return;
        }
        tabs.getSelectionModel().select(columnsTab);
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
        // 页签上的计数是「这几页里有东西」的唯一证据 —— 在字段页也看得见。
        indexTab.setText(counted(I18n.t("structure.tab.indexes"), indexes.size()));
        foreignKeyTab.setText(counted(I18n.t("structure.tab.foreignKeys"), foreignKeys.size()));
        triggerTab.setText(counted(I18n.t("structure.tab.triggers"), triggers.size()));
        fieldCount.setText(I18n.t("newtable.fieldCount", rows.size()));
        try {
            previewEditor.setText(currentPlan().sql());
            previewEditor.setHint(previewEmptyHint());
        } catch (IllegalArgumentException e) {
            previewEditor.setText("");
            // ⭐ 预览空着就必须说出原因：表名已经填了却还显示「填写表名后…」，
            //    会让用户以为预览坏了（实测踩到：演示库里本来就有 orders）。
            previewEditor.setHint(previewProblemHint(e.getMessage()));
        }
    }

    /** 表名为空时保留空态提示；已经填了表名（问题在别处）就照实说。 */
    private String previewProblemHint(String message) {
        boolean nameBlank = name.getText() == null || name.getText().isBlank();
        if (nameBlank || message == null || message.isBlank()) {
            return previewEmptyHint();
        }
        return message;
    }

    private static String counted(String title, int count) {
        return count == 0 ? title : I18n.t("newtable.tabCount", title, count);
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

    /** ⭐ R31：属性面板的「默认值」格（门禁断言它跟着选中行走）。 */
    TextField fieldDefaultField() {
        return fieldDefault;
    }

    /** ⭐ R31：属性面板的「字符集」格（不支持的方言应当是置灰的）。 */
    ComboBox<String> fieldCharsetBox() {
        return fieldCharset;
    }

    /** ⭐ R31：属性面板的「排序规则」格。 */
    ComboBox<String> fieldCollationBox() {
        return fieldCollation;
    }

    /** ⭐ R31：字段数标签（Navicat 底部的 Number of Fields）。 */
    Label fieldCountLabel() {
        return fieldCount;
    }

    /** ⭐ R31：「类型」下拉的候选 —— 必须是本方言真的存在的类型名。 */
    List<String> typeCandidates() {
        return typeOptions.stream().map(ColumnTypeOption::name).toList();
    }

    /** ⭐ R31：「备注」页的表注释输入（门禁断言它挂在那一页、并且进得了计划）。 */
    TextField commentField() {
        return tableComment;
    }

    /** ⭐ R31：「类型」列（门禁断言它的单元格是「编辑态才弹」的可编辑下拉）。 */
    TableColumn<ColumnRow, String> typeGridColumn() {
        return typeColumn;
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

    /**
     * 整个预览编辑器（含空态提示）。
     *
     * <p>提示挂在编辑器这层，页签没被选中时它不在 DialogPane 的 children 里，
     * {@code getDialogPane().lookup(...)} 找不到 —— 门禁从这里拿到它。
     */
    SqlEditor previewEditor() {
        return previewEditor;
    }

    /** 设计器的六个页签（字段 / 索引 / 外键 / 触发器 / 选项 / SQL 预览）。 */
    TabPane tabs() {
        return tabs;
    }

    ObservableList<IndexDef> indexes() {
        return indexes;
    }

    ObservableList<ForeignKeyDef> foreignKeys() {
        return foreignKeys;
    }

    ObservableList<TriggerDef> triggers() {
        return triggers;
    }

    ListView<IndexDef> indexList() {
        return indexList;
    }

    ListView<ForeignKeyDef> foreignKeyList() {
        return foreignKeyList;
    }

    ListView<TriggerDef> triggerList() {
        return triggerList;
    }

    /**
     * 截图摆态：切到指定页并（对对象页）先加一条真的对象。
     *
     * <p>⭐ 空列表的帧证明不了任何事 —— 验收要的是「索引写进了同一个建表计划」那一面。
     * 摆态走的是<b>用户点按钮同一条路</b>（{@code addIndex()} 那类入口需要弹窗，
     * 截图里没法点子弹窗），所以这里直接往 backing 列表里加一条按当前字段算出来的
     * 定义，预览仍由 {@code currentPlan()} 重新生成 —— 摆出来的 SQL 不是手写的。
     */
    void poseForShots(String pose) {
        String wanted = pose == null ? "" : pose.trim();
        if (wanted.isEmpty()) {
            return;
        }
        // 表名不填的话，七页里有一半是空态（预览空白、列名拼不出对象名）。
        // 摆态就按「用户已经敲了表名」开始 —— 与手敲走同一个 textProperty 监听。
        // ⚠️ 名字不能撞已有的表：演示库里本来就有 orders，撞上之后
        //    currentPlan() 直接抛「已经存在」，帧里的预览是一片空白（踩过）。
        if (name.getText() == null || name.getText().isBlank()) {
            name.setText(poseTableName());
        }
        switch (wanted) {
            case "index" -> {
                String column = ensureColumn("email", "VARCHAR(255)");
                IndexDef index = new IndexDef("idx_" + safeTableName() + "_" + column,
                        false, List.of(column), "c", "", List.of());
                indexes.add(index);
                // 与 addIndex() 同一条收尾：刚加进来的那条就是选中项。
                indexList.getSelectionModel().select(index);
                selectTab("new-table-tab-indexes");
            }
            case "foreign-key" -> {
                String column = ensureColumn("dept_id", "INTEGER");
                ForeignKeyDef key = new ForeignKeyDef("fk_" + safeTableName() + "_" + column,
                        List.of(column), "departments", List.of("id"),
                        "NO ACTION", "CASCADE");
                foreignKeys.add(key);
                foreignKeyList.getSelectionModel().select(key);
                selectTab("new-table-tab-foreign-keys");
            }
            case "trigger" -> {
                TriggerDef trigger = new TriggerDef("trg_" + safeTableName() + "_audit", "AFTER",
                        "INSERT", "UPDATE " + safeTableName() + " SET name = name");
                triggers.add(trigger);
                triggerList.getSelectionModel().select(trigger);
                selectTab("new-table-tab-triggers");
            }
            case "options" -> selectTab("new-table-tab-options");
            // ⭐ R31：字段页的「有深度」那一帧 —— 长度 / 小数填上、id 带着自增、
            //    属性面板挂着选中列的默认值与字符集。空网格证不了任何事。
            case "fields" -> {
                String email = ensureColumn("email", "VARCHAR(255)");
                ensureColumn("amount", "DECIMAL(10,2)");
                rows.stream().filter(row -> email.equalsIgnoreCase(row.name.get())).findFirst()
                        .ifPresent(row -> {
                            row.defaultValue.set("''");
                            // 摆的是**本方言自己的**候选：演示库是 SQLite，
                            // 在这里写 utf8mb4 等于拍出一张「字符集被灰着却填了 MySQL 值」的自相矛盾帧。
                            List<String> charsets = charsetPresets();
                            if (!charsets.isEmpty()) {
                                row.charset.set(charsets.getFirst());
                            }
                            List<String> collations = collationPresets();
                            if (!collations.isEmpty()) {
                                row.collation.set(collations.getFirst());
                            }
                            row.comment.set(I18n.t("newtable.pose.comment"));
                            columnsTable.getSelectionModel().select(row);
                            showProperties(row);
                        });
                selectTab("new-table-tab-columns");
            }
            case "comment" -> {
                tableComment.setText(I18n.t("newtable.pose.tableComment"));
                selectTab("new-table-tab-comment");
            }
            case "sql" -> {
                String column = ensureColumn("email", "VARCHAR(255)");
                indexes.add(new IndexDef("uniq_" + safeTableName() + "_" + column,
                        true, List.of(column), "c", "", List.of()));
                selectTab("new-table-tab-sql");
            }
            default -> { }
        }
    }

    /** 摆态用的表名：挑一个当前连接里没有的名字，否则预览页只会拍到「已经存在」。 */
    private String poseTableName() {
        String base = "members";
        String candidate = base;
        for (int suffix = 2; taken(candidate); suffix++) {
            candidate = base + "_" + suffix;
        }
        return candidate;
    }

    private boolean taken(String candidate) {
        return existingTables.stream().anyMatch(existing -> existing != null
                && existing.equalsIgnoreCase(candidate));
    }

    /** 摆态用的列：已经有同名就复用，避免帧里出现两列 email。 */
    private String ensureColumn(String column, String rawType) {
        Optional<ColumnRow> existing = rows.stream()
                .filter(row -> column.equalsIgnoreCase(row.name.get()))
                .findFirst();
        if (existing.isEmpty()) {
            ColumnRow row = new ColumnRow(this::refreshPreview);
            row.name.set(column);
            row.applyRawType(rawType);
            rows.add(row);
        }
        return column;
    }

    private void selectTab(String id) {
        tabs.getTabs().stream()
                .filter(tab -> id.equals(tab.getId()))
                .findFirst()
                .ifPresent(tab -> tabs.getSelectionModel().select(tab));
    }

    private String safeTableName() {
        String value = name.getText() == null ? "" : name.getText().trim();
        return value.isEmpty() ? "orders" : value;
    }

    // ─────────────────────────── 行模型 ───────────────────────────

    /**
     * 一行列定义。用 JavaFX 属性是为了让表格能直接编辑。
     *
     * <p>⭐ R31：类型拆成「类型 + 长度 + 小数」三项（{@link #rawType()} 负责拼回去），
     * 并新增「自增」。布尔语义按 Navicat 摆成正向的「不为空」（{@link #notNull}），
     * 而不是反过来的「可空」—— 勾选框问的应该是「有什么」。
     */
    static final class ColumnRow {

        final StringProperty name = new SimpleStringProperty("");
        final StringProperty type = new SimpleStringProperty("TEXT");
        /** 长度（{@code VARCHAR(255)} 的 255）；空 = 不带长度。 */
        final StringProperty length = new SimpleStringProperty("");
        /** 小数位（{@code DECIMAL(10,2)} 的 2）；空 = 不带小数位。 */
        final StringProperty decimals = new SimpleStringProperty("");
        final BooleanProperty notNull = new SimpleBooleanProperty(false);
        final BooleanProperty primaryKey = new SimpleBooleanProperty(false);
        final BooleanProperty unique = new SimpleBooleanProperty(false);
        final BooleanProperty autoIncrement = new SimpleBooleanProperty(false);
        final StringProperty defaultValue = new SimpleStringProperty("");
        final StringProperty comment = new SimpleStringProperty("");
        final StringProperty charset = new SimpleStringProperty("");
        final StringProperty collation = new SimpleStringProperty("");

        ColumnRow(Runnable onChange) {
            // ⭐ 主键列不能为 NULL —— 勾上主键就自动勾上「不为空」，
            //    而不是让用户填完再被一句报错打回来。
            primaryKey.addListener((observable, oldValue, newValue) -> {
                if (newValue) {
                    notNull.set(true);
                } else {
                    // 自增列必须是主键 —— 取消主键时自增一并没有意义。
                    autoIncrement.set(false);
                }
                onChange.run();
            });
            // 自增列必须是键：勾自增等于同时勾主键（主键再带出「不为空」）。
            autoIncrement.addListener((observable, oldValue, newValue) -> {
                if (newValue) {
                    primaryKey.set(true);
                }
                onChange.run();
            });
            name.addListener(observable -> onChange.run());
            type.addListener(observable -> onChange.run());
            length.addListener(observable -> onChange.run());
            decimals.addListener(observable -> onChange.run());
            notNull.addListener(observable -> onChange.run());
            unique.addListener(observable -> onChange.run());
            defaultValue.addListener(observable -> onChange.run());
            comment.addListener(observable -> onChange.run());
            charset.addListener(observable -> onChange.run());
            collation.addListener(observable -> onChange.run());
        }

        /**
         * 把用户在「类型」格里敲的整串（{@code VARCHAR(255)} / {@code DECIMAL(10,2)}）
         * 拆回三格；换类型时先清掉旧参数，免得 {@code DECIMAL} 的 10 跟着走进 {@code INT}。
         */
        void applyRawType(String raw) {
            String text = raw == null ? "" : raw.trim();
            int open = text.indexOf('(');
            String base = (open < 0 ? text : text.substring(0, open)).trim();
            if (!base.equalsIgnoreCase(type.get())) {
                length.set("");
                decimals.set("");
            }
            type.set(base);
            if (open < 0) {
                return;
            }
            String inside = text.substring(open + 1).replace(")", "").trim();
            String[] parts = inside.split(",");
            length.set(parts[0].trim());
            decimals.set(parts.length > 1 ? parts[1].trim() : "");
        }

        /** 类型名（去掉长度部分、统一大写）—— 查候选表 / 过滤用。 */
        String baseTypeName() {
            String raw = rawType();
            int paren = raw.indexOf('(');
            return (paren < 0 ? raw : raw.substring(0, paren)).trim().toUpperCase(Locale.ROOT);
        }

        /** 写进 DDL 的声明类型：{@code 类型(长度[,小数])}。 */
        String rawType() {
            String base = type.get() == null ? "" : type.get().trim();
            if (base.isEmpty()) {
                return "";
            }
            String len = length.get() == null ? "" : length.get().trim();
            String dec = decimals.get() == null ? "" : decimals.get().trim();
            if (len.isEmpty()) {
                return base;
            }
            return dec.isEmpty() ? base + "(" + len + ")" : base + "(" + len + "," + dec + ")";
        }

        /**
         * 生成列定义；长度 / 小数的非法组合在这里就拒绝，而不是等到执行。
         *
         * <p>抛出的原因会被 {@code buildPlan()} 接住显示在弹窗里 —— 与库自己报的
         * 语法错误相比，用户至少能看到「哪一列、错在哪」。
         */
        ColumnSpec toSpec() {
            String len = length.get() == null ? "" : length.get().trim();
            String dec = decimals.get() == null ? "" : decimals.get().trim();
            if (!len.isEmpty() && !len.matches("\\d{1,4}")) {
                throw new IllegalArgumentException(I18n.t("newtable.error.lengthDigits", label()));
            }
            if (!dec.isEmpty() && !dec.matches("\\d{1,4}")) {
                throw new IllegalArgumentException(I18n.t("newtable.error.decimalsDigits", label()));
            }
            if (!dec.isEmpty()) {
                if (len.isEmpty()) {
                    throw new IllegalArgumentException(I18n.t("newtable.error.decimalsNeedsLength", label()));
                }
                if (Integer.parseInt(dec) > Integer.parseInt(len)) {
                    throw new IllegalArgumentException(I18n.t("newtable.error.decimalsTooLarge", label()));
                }
            }
            return new ColumnSpec(name.get(), rawType(), !notNull.get(), defaultValue.get(),
                    comment.get(), unique.get(), primaryKey.get(), autoIncrement.get(),
                    charset.get(), collation.get());
        }

        private String label() {
            String value = name.get() == null ? "" : name.get().trim();
            return value.isEmpty() ? I18n.t("newtable.error.unnamedColumn") : value;
        }
    }
}
