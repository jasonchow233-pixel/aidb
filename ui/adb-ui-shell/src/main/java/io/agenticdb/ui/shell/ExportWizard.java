package io.agenticdb.ui.shell;

import io.agenticdb.introspect.DataQuery;
import io.agenticdb.introspect.DataSqlBuilder;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;
import io.agenticdb.ui.I18n;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.MaskRule;
import io.agenticdb.transfer.TableExporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 导出向导。
 *
 * <p>⛔ 按钮放在<b>内容区</b>，不放 Dialog 按钮栏 ——
 * {@code DialogPane.createButton} 会给按钮栏里的每个按钮挂
 * {@code setResultAndClose}，点一下就把整个对话框关掉（见架构文档 §7.1）。
 *
 * <p>导出在后台线程跑：整表导出可能是几百万行，不能卡住界面。
 *
 * <p>⭐ R33-G6：三个范围现在<b>真的</b>各自生效。「当前页」「选中行」走
 * {@link RowSource}（网格里那几行，已经在内存里），不再「只影响确认框、写入永远整表」。
 * 同时补上字段勾选、编码、表头、文本限定符四个选项与 XML / HTML 两种格式。
 */
final class ExportWizard {

    /** 导出范围。⭐ 存键、渲染时解析（第九批 SslMode 同规）—— 枚举值本身不进文案。 */
    enum Scope {
        CURRENT_PAGE("export.scope.page"),
        SELECTED("export.scope.selected"),
        WHOLE_TABLE("export.scope.whole");

        private final String labelKey;

        Scope(String labelKey) {
            this.labelKey = labelKey;
        }

        @Override
        public String toString() {
            return I18n.t(labelKey);
        }
    }

    /**
     * 行数来源：给定导出范围，返回「将读取多少行」。
     *
     * <p>抽出来是为了门禁能注入一个超阈值的大数 —— 否则要造一百万行才能测到这条路径。
     */
    @FunctionalInterface
    interface RowCounter {
        long count(Scope scope) throws Exception;
    }

    /**
     * 内存行的来源（「当前页」「选中行」）。
     *
     * <p>抽取出来是为了让向导不依赖 {@code DataGridPage} 的任何控件：
     * 传进来的是<b>已经摊平的值</b>，列顺序与 {@code columns} 参数一致。
     */
    @FunctionalInterface
    interface RowSource {
        List<List<Object>> rows(Scope scope, List<String> columns);
    }

    /**
     * 超限确认：拿到提示文案，返回 {@code true} 才继续导出。
     *
     * <p>默认实现在 FX 线程上弹模态框阻塞等用户 —— 这是 JavaFX 的正常用法，
     * 且调用点本来就在 FX 线程上（见 {@link #start()}）。
     */
    @FunctionalInterface
    interface Confirmation {
        boolean confirm(String message);
    }

    /** 可选编码。GBK 是中文 Windows 上最常见的「另存为」结果。 */
    private static final List<Charset> CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GBK"),
            StandardCharsets.UTF_16LE,
            StandardCharsets.UTF_16BE);

    private final DbSession session;
    private final String tableName;
    private final List<String> columns;
    private final Executor dbExecutor;
    private final Consumer<String> status;
    private final RowSource rowSource;

    private final Dialog<Void> dialog = new Dialog<>();
    private final VBox content = new VBox(10);
    private final ComboBox<Scope> scopeBox = new ComboBox<>();
    private final ComboBox<ExportFormat> formatBox = new ComboBox<>();
    private final ComboBox<Charset> charsetBox = new ComboBox<>();
    private final CheckBox headerBox = new CheckBox(I18n.t("export.header.check"));
    private final CheckBox quoteBox = new CheckBox(I18n.t("export.quote.check"));
    private final Label optionNote = new Label();
    private final Label formatHint = new Label();
    private final VBox columnBox = new VBox(3);
    private final List<CheckBox> columnChecks = new ArrayList<>();
    // ⭐ P-3：列级脱敏。刻意不把下拉塞进 columnBox 的每一行 —— 那份 VBox 的孩子
    //    是 CheckBox（TransferWizardTest 按 index 取它），改结构会把二十多处门禁全打翻。
    private final CheckBox maskBox = new CheckBox(I18n.t("export.mask.enable"));
    private final ComboBox<MaskRule.MaskMode> maskModeBox = new ComboBox<>();
    private final VBox maskColumnBox = new VBox(3);
    private final List<CheckBox> maskColumnChecks = new ArrayList<>();
    private final Label maskNote = new Label();
    // ⭐ P-3 加固：HASH 模式的导出级盐值。只在选了 HASH 时露出来，
    //    盐值不写进导出文件，拿到文件的人无法对低熵值跑彩虹表。
    private final TextField maskSaltField = new TextField();
    private final Button maskSaltGenerate = new Button(I18n.t("export.mask.salt.generate"));
    private final Label maskSaltHint = new Label(I18n.t("export.mask.salt.hint"));
    private final HBox maskSaltRow = new HBox(8);
    private final TextField targetField = new TextField();
    private final TextArea log = new TextArea();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label summary = new Label();
    private final Button exportButton = new Button(I18n.t("export.start"));
    private final Button closeButton = new Button(I18n.t("tab.close"));

    private final RowCounter counter;
    private final Confirmation confirmation;

    private volatile boolean running;
    /** 打开时记住拥有者窗口 —— 大表确认那颗 Alert 要用它取主题。 */
    private javafx.stage.Window owner;

    /**
     * ⭐ D-73：文件选择通道。默认是系统保存面板；门禁换成替身
     * （{@link #setFileChooserForTest}）—— Monocle 按不住原生面板，
     * 真弹窗在这台机器上永远等不到返回值，于是「按钮点了没反应」在测试里无法与
     * 「按钮根本没接选择器」区分。
     */
    private java.util.function.Function<javafx.stage.Window, java.io.File> fileChooser = window -> {
        ExportFormat format = formatBox.getValue();
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle(I18n.t("export.target.chooserTitle"));
        chooser.setInitialFileName(withExtension("export", format));
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter(format.label(), "*" + format.extension()),
                new javafx.stage.FileChooser.ExtensionFilter(
                        I18n.t("wizard.file.filter.all"), "*.*"));
        return chooser.showSaveDialog(window);
    };

    /** 测试替身：见 {@link #fileChooser}。 */
    void setFileChooserForTest(java.util.function.Function<javafx.stage.Window, java.io.File> chooser) {
        this.fileChooser = chooser;
    }

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status) {
        this(session, tableName, columns, defaultScope, defaultTarget, dbExecutor, status,
                null, null, null);
    }

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status,
                 RowSource rowSource) {
        this(session, tableName, columns, defaultScope, defaultTarget, dbExecutor, status,
                rowSource, null, null);
    }

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status,
                 RowCounter counter, Confirmation confirmation) {
        this(session, tableName, columns, defaultScope, defaultTarget, dbExecutor, status,
                null, counter, confirmation);
    }

    ExportWizard(DbSession session, String tableName, List<String> columns, Scope defaultScope,
                 Path defaultTarget, Executor dbExecutor, Consumer<String> status,
                 RowSource rowSource, RowCounter counter, Confirmation confirmation) {
        this.session = session;
        this.tableName = tableName;
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.dbExecutor = dbExecutor;
        this.status = status == null ? message -> { } : status;
        this.rowSource = rowSource;
        this.counter = counter == null ? this::countRows : counter;
        this.confirmation = confirmation == null ? this::askUser : confirmation;

        // ⭐ 没有内存行来源（表清单入口）时就别提供「当前页 / 选中行」——
        //    提供了却退化成整表，正是这一版要根除的那条谎。
        scopeBox.getItems().setAll(rowSource == null
                ? List.of(Scope.WHOLE_TABLE) : List.of(Scope.values()));
        scopeBox.getSelectionModel().select(
                rowSource == null ? Scope.WHOLE_TABLE : defaultScope);
        scopeBox.setId("export-scope");

        formatBox.getItems().setAll(ExportFormat.values());
        formatBox.getSelectionModel().select(ExportFormat.CSV);
        formatBox.setId("export-format");
        // 换格式时同步目标文件后缀，避免把 xlsx 字节写进 .csv。
        formatBox.valueProperty().addListener((observable, old, format) -> {
            targetField.setText(withExtension(targetField.getText(), format));
            fitOptionAvailability();
        });

        charsetBox.getItems().setAll(CHARSETS);
        charsetBox.getSelectionModel().select(StandardCharsets.UTF_8);
        charsetBox.setId("export-charset");

        headerBox.setSelected(true);
        headerBox.setId("export-header");
        quoteBox.setSelected(false);
        quoteBox.setId("export-quote");

        // ⭐ D-58：禁用不静默 —— 行内说清「为什么这一格灰了」，格式说明行同理（D-83）。
        formatHint.setId("export-format-hint");
        formatHint.getStyleClass().add("adb-muted");
        formatHint.setWrapText(true);
        optionNote.setId("export-option-note");
        optionNote.getStyleClass().add("adb-muted");
        optionNote.setWrapText(true);

        for (String column : this.columns) {
            CheckBox box = new CheckBox(column);
            box.setSelected(true);
            box.getStyleClass().add("adb-check");
            columnChecks.add(box);
            columnBox.getChildren().add(box);
        }
        columnBox.setPadding(new Insets(4, 8, 4, 8));
        ScrollPane columnScroll = new ScrollPane(columnBox);
        columnScroll.setId("export-columns");
        columnScroll.setFitToWidth(true);
        // 行高 24（复选框 16 + 上下各 4）；按列数长到 7 行封顶 —— 7 列的表
        // 不该只露出 4 行半再给一条滚动条（p8-export 实拍：第 4 行被切一半）。
        columnScroll.setPrefHeight(Math.min(Math.max(this.columns.size(), 4), 7) * 24 + 12);
        columnScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        Button allColumns = new Button(I18n.t("action.table-list.select-all"));
        allColumns.setId("export-columns-all");
        allColumns.getStyleClass().addAll("adb-button", "adb-button--ghost");
        allColumns.setOnAction(event -> selectAllColumns(true));
        Button noColumns = new Button(I18n.t("export.columns.none"));
        noColumns.setId("export-columns-none");
        noColumns.getStyleClass().addAll("adb-button", "adb-button--ghost");
        noColumns.setOnAction(event -> selectAllColumns(false));
        Label fieldCount = new Label();
        fieldCount.setId("export-column-count");
        fieldCount.getStyleClass().add("adb-muted");
        columnChecks.forEach(box -> box.selectedProperty().addListener(
                (observable, old, value) -> fieldCount.setText(fieldCountText())));
        fieldCount.setText(fieldCountText());
        Region fieldSpacer = new Region();
        HBox.setHgrow(fieldSpacer, Priority.ALWAYS);
        HBox fieldActions = new HBox(6, fieldCount, fieldSpacer, allColumns, noColumns);
        fieldActions.setAlignment(Pos.CENTER_LEFT);

        targetField.setId("export-target");
        targetField.setText(defaultTarget == null ? "" : defaultTarget.toString());
        targetField.setPromptText(I18n.t("export.target.prompt"));

        // ⭐ D-73：路径格右侧补浏览按钮 —— 抄的是 `ConnectionWizard#wizard-browse`，
        //    以前整个向导族只有连接向导能选文件，这两个向导的路径只能一个字符一个字符敲。
        Button targetBrowse = new Button(I18n.t("wizard.browse"));
        targetBrowse.setId("export-target-browse");
        targetBrowse.getStyleClass().addAll("adb-button", "adb-button--ghost");
        targetBrowse.setOnAction(event -> {
            java.io.File picked = fileChooser.apply(owner);
            if (picked != null) {
                // 选完仍要过 withExtension：用户在面板里把后缀打掉了，格式与文件就不能对不上。
                targetField.setText(withExtension(picked.getAbsolutePath(), formatBox.getValue()));
            }
        });
        HBox targetRow = new HBox(8, targetField, targetBrowse);
        HBox.setHgrow(targetField, Priority.ALWAYS);

        log.setId("export-log");
        log.setEditable(false);
        log.setPrefRowCount(5);
        VBox.setVgrow(log, Priority.ALWAYS);

        spinner.setMaxSize(16, 16);
        spinner.setVisible(false);

        summary.setId("export-summary");
        summary.setText(I18n.t("export.summary.initial"));
        summary.getStyleClass().add("adb-muted");

        exportButton.setId("export-start");
        exportButton.getStyleClass().addAll("adb-button", "adb-button--primary");
        exportButton.setOnAction(event -> start());

        closeButton.setId("export-close");
        closeButton.getStyleClass().addAll("adb-button", "adb-button--ghost");
        closeButton.setOnAction(event -> {
            if (!running) {
                close();
            }
        });

        // Navicat 口径（§6.7）：关闭在左、主动作在最右，进度指示留在最左。
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, spinner, spacer, closeButton, exportButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox optionRow = new HBox(12, headerBox, quoteBox);
        optionRow.setAlignment(Pos.CENTER_LEFT);

        // ⭐ P-3：列级脱敏。总开关与策略常驻一行；「要保护哪几列」收在折叠区里，
        //    没开脱敏时这一节不占位，也不会让人误以为默认就脱敏了。
        maskBox.setId("export-mask");
        maskBox.getStyleClass().add("adb-check");
        maskModeBox.getItems().setAll(MaskRule.MaskMode.values());
        maskModeBox.getSelectionModel().select(MaskRule.MaskMode.FULL);
        maskModeBox.setId("export-mask-mode");
        // 领域枚举不认识 i18n（workbench 层不许反向依赖 UI），文案在这里映射。
        maskModeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(MaskRule.MaskMode mode) {
                if (mode == null) {
                    return "";
                }
                return switch (mode) {
                    case FULL -> I18n.t("export.mask.mode.full");
                    case EDGES -> I18n.t("export.mask.mode.edges");
                    case HASH -> I18n.t("export.mask.mode.hash");
                };
            }

            @Override
            public MaskRule.MaskMode fromString(String text) {
                return null;
            }
        });
        for (String column : this.columns) {
            CheckBox box = new CheckBox(column);
            box.getStyleClass().add("adb-check");
            maskColumnChecks.add(box);
            maskColumnBox.getChildren().add(box);
        }
        maskColumnBox.setId("export-mask-columns");
        maskNote.setId("export-mask-note");
        maskNote.getStyleClass().add("adb-muted");
        maskNote.setWrapText(true);

        maskSaltField.setId("export-mask-salt");
        maskSaltField.setPromptText(I18n.t("export.mask.salt.prompt"));
        maskSaltField.setPrefColumnCount(24);
        HBox.setHgrow(maskSaltField, Priority.ALWAYS);
        maskSaltGenerate.setId("export-mask-salt-generate");
        maskSaltGenerate.setOnAction(event -> maskSaltField.setText(randomSalt()));
        maskSaltHint.setId("export-mask-salt-hint");
        maskSaltHint.getStyleClass().add("adb-muted");
        maskSaltHint.setWrapText(true);
        Label maskSaltCaption = new Label(I18n.t("export.mask.salt"));
        maskSaltCaption.getStyleClass().add("adb-muted");
        maskSaltRow.setId("export-mask-salt-row");
        maskSaltRow.setAlignment(Pos.CENTER_LEFT);
        maskSaltRow.getChildren().setAll(maskSaltCaption, maskSaltField, maskSaltGenerate);

        Label maskColumnsCaption = new Label(I18n.t("export.mask.columns"));
        maskColumnsCaption.getStyleClass().add("adb-muted");
        // 盐值这一行挂在折叠区里，不动 content 的直接子节点结构（TransferWizardTest 按 index 取）。
        VBox maskSection = new VBox(6, maskColumnsCaption, maskColumnBox,
                maskSaltRow, maskSaltHint, maskNote);
        maskSection.setVisible(false);
        maskSection.setManaged(false);
        HBox maskRow = new HBox(8, maskBox, maskModeBox);
        maskRow.setAlignment(Pos.CENTER_LEFT);
        maskBox.selectedProperty().addListener((observable, old, on) -> {
            maskSection.setVisible(on);
            maskSection.setManaged(on);
            refreshMaskNote();
        });
        maskColumnChecks.forEach(box -> box.selectedProperty()
                .addListener((observable, old, on) -> refreshMaskNote()));
        maskModeBox.valueProperty()
                .addListener((observable, old, mode) -> refreshMaskNote());
        refreshMaskNote();

        content.getChildren().addAll(
                field(I18n.t("export.field.scope"), scopeBox),
                field(I18n.t("menu.format"), formatBox),
                formatHint,
                field(I18n.t("export.field.target"), targetRow),
                new Label(I18n.t("newtable.tab.fields")),
                columnScroll,
                fieldActions,
                field(I18n.t("import.field.encoding"), charsetBox),
                optionRow,
                maskRow,
                maskSection,
                optionNote,
                summary,
                log,
                actions);
        content.setPadding(new Insets(16));
        content.getStyleClass().add("adb-wizard__content");

        dialog.setTitle(I18n.t("export.title", tableName));

        // ⭐ V-11：弹窗宽度走档位规范（DialogSizes）。

        dialog.getDialogPane().setPrefWidth(DialogSizes.WIDE);

        dialog.getDialogPane().setMinWidth(DialogSizes.WIDE);

        dialog.setHeaderText(I18n.t("export.steps"));
        // ⛔ 不放任何按钮到按钮栏 —— 放了会被 setResultAndClose 一键关掉。
        dialog.getDialogPane().getButtonTypes().clear();
        dialog.getDialogPane().setContent(content);

        fitOptionAvailability();
    }

    /** 打开向导（模态）。 */
    void show(javafx.stage.Window owner) {
        this.owner = owner;
        if (owner != null) {
            dialog.initOwner(owner);
        }
        DialogStyles.apply(dialog, owner);
        dialog.showAndWait();
    }

    void close() {
        if (dialog.isShowing()) {
            dialog.hide();
        }
    }

    /**
     * 验收帧（G6）：摆成「选了字段子集 + XML/HTML + 目标路径」那一屏，不执行导出。
     *
     * <p>光弹一个全选的默认窗等于没拍「列选择」与「附加选项」这两条新能力 ——
     * 所以这里真取消一个字段、真换格式，选项可用性跟着格式联动。
     */
    void poseForShots(String pose) {
        switch (pose == null ? "" : pose.toLowerCase(java.util.Locale.ROOT)) {
            case "html" -> formatBox.getSelectionModel().select(ExportFormat.HTML);
            case "xml" -> formatBox.getSelectionModel().select(ExportFormat.XML);
            default -> { }
        }
        if (columnChecks.size() > 1) {
            columnChecks.getLast().setSelected(false);
        }
        if (rowSource != null) {
            scopeBox.getSelectionModel().select(Scope.WHOLE_TABLE);
        }
        targetField.setText(withExtension(targetField.getText(), formatBox.getValue()));
        // ⭐ D-83：以前这里往日志框手搓一句假日志（export.pose.log）——帧上是编的。
        // 撤掉；格式说明改由上面 formatBox 选择走真实联动（hint 行 + 可用性）。
    }

    /** 供门禁直接驱动。 */
    VBox content() {
        return content;
    }

    private HBox field(String label, javafx.scene.Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("adb-muted");
        caption.setMinWidth(64);
        HBox row = new HBox(8, caption, control);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }

    /** 编码只对文本格式有意义；「全加引号」只对 CSV / TSV 有意义。 */
    private void fitOptionAvailability() {
        ExportFormat format = formatBox.getValue();
        if (format == null) {
            return;
        }
        charsetBox.setDisable(format.binary());
        quoteBox.setDisable(!(format == ExportFormat.CSV || format == ExportFormat.TSV));
        // ⭐ R33：「写表头行」只对分隔符文本有意义 —— XML/HTML 的结构自带头，
        // xlsx 的表头是写死的，SQL 的列名在 INSERT 语句里。以前这格在 XML 下
        // 照样勾着，等于一个勾了不生效的谎（p8-export 实拍抓到）。
        headerBox.setDisable(!(format == ExportFormat.CSV || format == ExportFormat.TSV));

        // ⭐ D-83：hint 以前只躺在枚举里（零消费者），现在真的上屏。
        formatHint.setText(I18n.t(format.hintKey()));

        // D-58：灰掉的格子要自己说为什么灰 —— tooltip 在禁用控件上不可靠
        //（JavaFX 不给 disabled 节点送鼠标事件），所以走行内说明。
        List<String> reasons = new ArrayList<>();
        if (format.binary()) {
            reasons.add(I18n.t("transfer.option.note.binary"));
        }
        if (headerBox.isDisabled() || quoteBox.isDisabled()) {
            reasons.add(I18n.t("export.option.note.textonly"));
        }
        optionNote.setText(String.join(" · ", reasons));
        optionNote.setVisible(!reasons.isEmpty());
        optionNote.setManaged(!reasons.isEmpty());
    }

    private void selectAllColumns(boolean selected) {
        for (CheckBox box : columnChecks) {
            box.setSelected(selected);
        }
    }

    private String fieldCountText() {
        return I18n.t("export.field.count", selectedColumns().size(), columnChecks.size());
    }

    /** 勾选的字段（保持表结构顺序）。 */
    List<String> selectedColumns() {
        List<String> selected = new ArrayList<>();
        for (CheckBox box : columnChecks) {
            if (box.isSelected()) {
                selected.add(box.getText());
            }
        }
        return selected;
    }

    // ───────────────────── P-3：列级脱敏 ─────────────────────

    /**
     * 当前生效的列级规则。未启用 / 一列没勾 / 策略没选时返回空列表 ——
     * 空规则意味着「原样导出」，{@link io.agenticdb.transfer.DataMasker} 会直接放行。
     */
    List<MaskRule> activeMaskRules() {
        if (!maskBox.isSelected()) {
            return List.of();
        }
        MaskRule.MaskMode mode = maskModeBox.getValue();
        if (mode == null) {
            return List.of();
        }
        List<MaskRule> rules = new ArrayList<>();
        for (CheckBox box : maskColumnChecks) {
            if (box.isSelected()) {
                rules.add(new MaskRule(box.getText(), mode));
            }
        }
        return List.copyOf(rules);
    }

    /**
     * ⭐ 从网格「数据脱敏」入口进来时的预置：按列名提示哪些像敏感列。
     *
     * <p>只是<b>预置勾选</b>，不是自动判定，更不是「已经脱敏」—— 用户能看见、
     * 能改、能整关掉。没认出任何列时<b>仍要打开脱敏区</b>：从「数据脱敏」按钮
     * 进来却看到一个和普通导出窗一模一样、开关还是关着的界面，等于按钮说了谎。
     */
    void presetMasking(java.util.Set<String> lowerCaseNames) {
        maskBox.setSelected(true);
        java.util.Set<String> hints = lowerCaseNames == null
                ? java.util.Set.of() : lowerCaseNames;
        for (CheckBox box : maskColumnChecks) {
            box.setSelected(hints.contains(
                    box.getText().toLowerCase(java.util.Locale.ROOT)));
        }
        refreshMaskNote();
    }

    /**
     * 当前生效的 HASH 盐值。只有「脱敏开着 + 策略是 HASH」才把框里的值递出去 ——
     * FULL / EDGES 用不到盐，切回别的策略时不该留一个看似生效、其实没用的开关。
     */
    String activeMaskSalt() {
        if (!maskBox.isSelected()
                || maskModeBox.getValue() != MaskRule.MaskMode.HASH) {
            return "";
        }
        String value = maskSaltField.getText();
        return value == null ? "" : value.trim();
    }

    /** 生成 16 字节随机盐（hex）。只在用户点「生成」时跑，不在向导打开时偷偷随机。 */
    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private void refreshMaskNote() {
        long count = maskColumnChecks.stream().filter(CheckBox::isSelected).count();
        maskNote.setText(maskBox.isSelected() && count > 0
                ? I18n.tPlural("export.mask.active", count, count)
                : I18n.t("export.mask.note"));
        boolean hashMode = maskBox.isSelected()
                && maskModeBox.getValue() == MaskRule.MaskMode.HASH;
        maskSaltRow.setVisible(hashMode);
        maskSaltRow.setManaged(hashMode);
        maskSaltHint.setVisible(hashMode);
        maskSaltHint.setManaged(hashMode);
    }

    /** 开始导出（在后台线程跑）。 */
    void start() {
        if (running) {
            return;
        }
        String text = targetField.getText() == null ? "" : targetField.getText().trim();
        if (text.isBlank()) {
            summary.setText(I18n.t("export.summary.pickTarget"));
            return;
        }
        List<String> selected = selectedColumns();
        if (selected.isEmpty()) {
            summary.setText(I18n.t("export.summary.pickField"));
            return;
        }
        Path target = Path.of(text);
        ExportFormat format = formatBox.getValue();
        Scope scope = scopeBox.getValue();

        running = true;
        exportButton.setDisable(true);
        spinner.setVisible(true);
        summary.setText(I18n.t("export.counting", tableName));

        ExportOptions options = new ExportOptions(format, headerBox.isSelected(), 500, 0,
                quoteBox.isSelected(), charsetBox.getValue())
                .withMasks(activeMaskRules())
                .withMaskSalt(activeMaskSalt());
        // ⭐ 内存范围必须在 FX 线程上取快照（start() 就在 FX 线程），
        //    导出线程只碰这份拷贝 —— 否则后台线程会去读网格控件。
        List<List<Object>> snapshot = scope == Scope.WHOLE_TABLE || rowSource == null
                ? null : rowSource.rows(scope, selected);
        // ⭐ 行数统计在后台线程跑：整表 COUNT(*) 是一次全表扫描，
        //    大表上可能要几十秒 —— 不能因为它把界面卡住。
        dbExecutor.execute(() -> {
            long rows;
            try {
                rows = counter.count(scope);
            } catch (Exception e) {
                // 数不出来就按「不知道」处理：不因此拦住用户，但也不假装知道行数。
                rows = -1;
            }
            if (rows > 0 && QueryLimits.exportRequiresConfirmation(rows)) {
                String message = I18n.tPlural("export.confirm", rows,
                        NumberFormat.getInstance().format(rows),
                        NumberFormat.getInstance().format(QueryLimits.MAX_EXPORT_ROWS));
                Platform.runLater(() -> {
                    if (confirmation.confirm(message)) {
                        runExport(target, options, scope, selected, snapshot);
                    } else {
                        finish(I18n.t("export.cancelled", message));
                    }
                });
                return;
            }
            if (rows < 0) {
                Platform.runLater(() ->
                        summary.setText(I18n.t("export.countUnknown", tableName)));
            }
            runExport(target, options, scope, selected, snapshot);
        });
    }

    private void runExport(Path target, ExportOptions options, Scope scope,
                           List<String> selected, List<List<Object>> snapshot) {
        dbExecutor.execute(() -> {
            String message;
            try {
                long rows = run(target, options, scope, selected, snapshot);
                message = I18n.t("export.done", rows) + I18n.t("export.done.detail",
                        target, scope, options.format().label(), selected.size(),
                        options.charset().name());
            } catch (Exception e) {
                message = I18n.t("export.failed",
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            String finalMessage = message;
            Platform.runLater(() -> finish(finalMessage));
        });
    }

    /** 收尾：解锁按钮、写日志。必须在 FX 线程上调用。 */
    private void finish(String message) {
        running = false;
        exportButton.setDisable(false);
        spinner.setVisible(false);
        summary.setText(message);
        log.appendText(message + "\n");
        status.accept(message);
    }

    /**
     * 默认的行数来源。
     *
     * <p>⭐ 只有<b>整表</b>可能过百万 —— 「当前页」和「选中行」都受页大小约束
     * （{@code QueryLimits.MAX_PAGE_SIZE}），永远不该触发确认。
     * 所以这两档直接返回 0，表示「不用问」。
     */
    long countRows(Scope scope) throws Exception {
        if (scope != Scope.WHOLE_TABLE) {
            return 0;
        }
        DataSqlBuilder.Sql sql = DataSqlBuilder.count(session, tableName, DataQuery.empty());
        RowPage page = session.query(sql.text(), 1, sql.params());
        if (page.rows().isEmpty()) {
            return 0;
        }
        Object value = page.rows().get(0).get("adb_count");
        return value instanceof Number number ? number.longValue() : 0;
    }

    /**
     * 默认的确认方式：模态框，且把真实行数写出来（不抽象的「确定吗」）。
     *
     * <p>⭐ 必须走 {@link DialogStyles}：这是一颗 {@link Alert}，自带自己的 Scene，
     * 主题类不在 DialogPane 上 → {@code -adb-*} 全部落空 → 弹出的是一个 Modena 灰皮肤，
     * 和同一个软件里的其它弹窗不是一套东西（任务书 §七）。
     */
    private boolean askUser(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message,
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle(I18n.t("export.confirm.title"));
        alert.setHeaderText(I18n.t("export.confirm.header"));
        DialogStyles.apply(alert, owner);
        return alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }

    /** @return 实际写出的行数 */
    long run(Path target, ExportOptions options, Scope scope, List<String> selected,
             List<List<Object>> snapshot) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (scope == Scope.WHOLE_TABLE || snapshot == null) {
            return runWholeTable(target, options, selected);
        }
        return runRows(target, options, selected, snapshot);
    }

    private long runWholeTable(Path target, ExportOptions options, List<String> selected)
            throws Exception {
        if (options.format().binary()) {
            try (OutputStream out = Files.newOutputStream(target)) {
                return TableExporter.exportXlsx(session, tableName, selected, options, out);
            }
        }
        try (Writer writer = new BufferedWriter(
                Files.newBufferedWriter(target, options.charset()))) {
            return TableExporter.export(session, tableName, selected, options, writer);
        }
    }

    private long runRows(Path target, ExportOptions options, List<String> selected,
                         List<List<Object>> rows) throws Exception {
        if (options.format().binary()) {
            try (OutputStream out = Files.newOutputStream(target)) {
                return TableExporter.exportRowsXlsx(selected, rows, options, out);
            }
        }
        try (Writer writer = new BufferedWriter(
                Files.newBufferedWriter(target, options.charset()))) {
            return TableExporter.exportRows(tableName, selected, rows, options, writer);
        }
    }

    /** 只替换已知的导出后缀；用户自定义文件名保持原样。 */
    static String withExtension(String target, ExportFormat format) {
        if (target == null || target.isBlank() || format == null) {
            return target;
        }
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        for (ExportFormat known : ExportFormat.values()) {
            if (lower.endsWith(known.extension())) {
                return target.substring(0, target.length() - known.extension().length())
                        + format.extension();
            }
        }
        return target;
    }

    /** 超过导出上限时要不要确认。 */
    static boolean requiresConfirmation(long rowCount) {
        return QueryLimits.exportRequiresConfirmation(rowCount);
    }
}
