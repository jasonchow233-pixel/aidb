package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 单元格详情抽屉。
 *
 * <p>文本/JSON/Hex/图片/元数据分 Tab 展示；大字段只在对应 Tab 首次选中时构建，
 * 避免打开详情就复制一份 10MB 文本。Esc 由数据页统一关闭。
 */
final class CellDetailsDrawer extends VBox {

    private final Label title = new Label("单元格详情");
    private final Label subtitle = new Label();
    private final Button close = IconButtons.icon(Icons.Icon.CLOSE, "关闭");
    private final Button copy = IconButtons.of(Icons.Icon.COPY, "复制");
    private final TabPane tabs = new TabPane();
    private final TextArea textArea = new TextArea();
    private final TextArea jsonArea = new TextArea();
    private final TextArea hexArea = new TextArea();
    private final StackPane imageBox = new StackPane();
    private final GridPane metadata = new GridPane();
    private final Tab textTab = new Tab("文本");
    private final Tab jsonTab = new Tab("JSON");
    private final Tab hexTab = new Tab("Hex");
    private final Tab imageTab = new Tab("图片");
    private final Tab metaTab = new Tab("元数据");

    private ColumnDef definition;
    private Object value;
    private Map<String, Object> row;
    private String tableName;
    private Runnable onClose = () -> { };
    private boolean loadedText;
    private boolean loadedJson;
    private boolean loadedHex;
    private boolean loadedImage;
    private boolean loadedMeta;

    // ⭐ UX-4：长文本 / JSON / CLOB 的多行编辑器。
    //   格子里那条单行输入框改不了 3KB 的 JSON —— 换行看不见、左右拖来拖去。
    //   这里把抽屉的文本 / JSON 页变成可写的，Ctrl+Enter 应用、Esc 取消。
    private final Button edit = IconButtons.of(Icons.Icon.EDIT, "编辑");
    private final Button apply = IconButtons.of(Icons.Icon.SAVE, "应用");
    private final Button cancel = IconButtons.of(Icons.Icon.CLOSE, "取消");
    private java.util.function.Consumer<String> onApply = text -> { };
    private java.util.function.Predicate<ColumnDef> editGate = column -> false;
    private boolean editing;
    /** ⭐ 编辑中的那一个区域 —— 不能靠「当前选中的页」反推，换页会把提交目标换掉。 */
    private TextArea editingArea;

    CellDetailsDrawer() {
        getStyleClass().add("adb-details");
        setId("data-details");
        setVisible(false);
        setManaged(false);
        setMinWidth(360);
        setMaxWidth(620);
        setPrefWidth(new SettingsStore().getInt("details.width", 480));
        installWidthResize();

        title.getStyleClass().add("adb-details__title");
        subtitle.getStyleClass().add("adb-details__subtitle");
        subtitle.setMaxWidth(320);

        close.setId("data-details-close");
        close.getStyleClass().addAll("adb-icon-button", "adb-details__close");
        close.setTooltip(new javafx.scene.control.Tooltip("关闭详情 (Esc)"));
        // 4.5-6 / 4.5-5：读屏软件要能读出「关闭」，
        // 且 ✕ 的语义必须与标签栏的 ✕ 一致（一个符号只表一个意思）。
        close.setAccessibleText("关闭");
        close.setOnAction(event -> close());

        copy.getStyleClass().addAll("adb-button", "adb-button--ghost");
        copy.setTooltip(new javafx.scene.control.Tooltip("复制当前 Tab 内容"));
        copy.setOnAction(event -> copyCurrent());

        edit.setId("data-details-edit");
        edit.getStyleClass().addAll("adb-button", "adb-button--ghost");
        edit.setTooltip(new javafx.scene.control.Tooltip(
                "在这格里直接改长文本 / JSON（Ctrl+Enter 应用，Esc 取消）"));
        edit.setVisible(false);
        edit.setManaged(false);
        edit.setOnAction(event -> startEdit());

        apply.setId("data-details-apply");
        apply.getStyleClass().addAll("adb-button", "adb-button--primary");
        apply.setTooltip(new javafx.scene.control.Tooltip("应用修改 (Ctrl+Enter)"));
        apply.setVisible(false);
        apply.setManaged(false);
        apply.setOnAction(event -> commit());

        cancel.setId("data-details-cancel");
        cancel.getStyleClass().addAll("adb-button", "adb-button--ghost");
        cancel.setTooltip(new javafx.scene.control.Tooltip("放弃这次编辑 (Esc)"));
        cancel.setVisible(false);
        cancel.setManaged(false);
        cancel.setOnAction(event -> stopEditing());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox labels = new VBox(2, title, subtitle);
        HBox header = new HBox(8, labels, spacer, edit, apply, cancel, copy, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("adb-details__header");

        prepareArea(textArea, "data-details-text");
        prepareArea(jsonArea, "data-details-json");
        prepareArea(hexArea, "data-details-hex");
        // 多行编辑器：Ctrl+Enter 应用。Esc 由数据页统一转成「取消编辑」。
        for (TextArea area : java.util.List.of(textArea, jsonArea)) {
            area.setOnKeyPressed(event -> {
                if (editing && event.getCode() == javafx.scene.input.KeyCode.ENTER
                        && event.isControlDown()) {
                    commit();
                    event.consume();
                }
            });
        }
        textTab.setContent(textArea);
        jsonTab.setContent(jsonArea);
        hexTab.setContent(hexArea);
        imageTab.setContent(imageBox);
        metaTab.setContent(metadata);
        tabs.getTabs().addAll(textTab, jsonTab, hexTab, imageTab, metaTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setId("data-details-tabs");
        // §2.5：抽屉的页签与结构页/数据页切面同一套语言 —— 样式类必须挂在
        //   TabPane 自己身上，`.adb-details .tab-pane …` 那种写法两头都不匹配。
        tabs.getStyleClass().add("adb-details__tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);
        tabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    // ⭐ 编辑中换页 = **放弃这次编辑**，不是把另一页的内容提交进格子。
                    //   文本页塞的是原值、JSON 页是美化过的（带换行），
                    //   让「当前哪一页」决定提交目标，就会出现在文本页敲字、
                    //   切到 JSON 页按 Ctrl+Enter，把整段美化 JSON 写进 TEXT 列。
                    if (editing) {
                        stopEditing();
                    }
                    load(selected);
                });

        imageBox.setAlignment(Pos.CENTER);
        imageBox.setPadding(new Insets(12));
        metadata.setHgap(12);
        metadata.setVgap(8);
        metadata.setPadding(new Insets(12));
        metadata.getStyleClass().add("adb-details__meta");

        getChildren().addAll(header, tabs);
        setSpacing(0);
    }

    private static void prepareArea(TextArea area, String id) {
        area.setId(id);
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("adb-details__text");
    }

    /**
     * §2.5：左缘 6px 按住可拖宽，松手落偏好。
     *
     * <p>抽屉是浮层（StackPane 右贴），不拖就只能在 360–620 的固定档里用；
     * 宽度记进与弹窗尺寸**同一个**偏好节点（{@link SettingsStore}），
     * 截图脚本隔离偏好时不会把用户拖出来的宽度拍进验收帧。
     */
    private void installWidthResize() {
        final double[] start = new double[2];
        setOnMouseMoved(event -> setCursor(
                event.getX() <= 6 ? Cursor.W_RESIZE : Cursor.DEFAULT));
        setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getX() <= 6) {
                start[0] = event.getScreenX();
                start[1] = getPrefWidth();
                event.consume();
            }
        });
        setOnMouseDragged(event -> {
            if (start[1] > 0) {
                // 左缘拖宽：向左拖 = 变宽，所以是「按下时的宽 − 位移」。
                double width = Math.max(getMinWidth(),
                        Math.min(getMaxWidth(), start[1] + start[0] - event.getScreenX()));
                setPrefWidth(width);
                event.consume();
            }
        });
        setOnMouseReleased(event -> {
            if (start[1] > 0) {
                new SettingsStore().setInt("details.width", (int) getPrefWidth());
                start[1] = 0;
                event.consume();
            }
        });
    }

    void setOnClose(Runnable action) {
        onClose = action == null ? () -> { } : action;
    }

    void showDetails(ColumnDef definition, Object value, Map<String, Object> row,
                     String tableName) {
        this.definition = definition;
        this.value = value;
        this.row = row;
        this.tableName = tableName;
        loadedText = loadedJson = loadedHex = loadedImage = loadedMeta = false;
        // 换一格就是一次全新的查看：上一格残留的编辑态必须清掉。
        editing = false;
        editingArea = null;
        for (TextArea area : java.util.List.of(textArea, jsonArea)) {
            area.setEditable(false);
            area.setWrapText(false);
        }
        textArea.clear();
        jsonArea.clear();
        hexArea.clear();
        imageBox.getChildren().clear();
        metadata.getChildren().clear();

        title.setText(definition.name());
        subtitle.setText(definition.type().name().toLowerCase(java.util.Locale.ROOT)
                + " · " + tableName + " · " + CellRenderer.sizeLabel(
                value instanceof byte[] bytes ? bytes.length
                        : value == null ? 0 : String.valueOf(value).getBytes(
                        StandardCharsets.UTF_8).length));
        tabs.getSelectionModel().select(textTab);
        load(textTab);
        flip(false);
        setVisible(true);
        setManaged(true);
    }

    void close() {
        if (!isVisible()) {
            return;
        }
        setVisible(false);
        setManaged(false);
        onClose.run();
    }

    boolean isOpen() {
        return isVisible();
    }

    /** 应用编辑时回调（参数是编辑器里的原始文本）。 */
    void setOnApply(java.util.function.Consumer<String> action) {
        onApply = action == null ? text -> { } : action;
    }

    /** 数据页告诉我们「这一格到底能不能改」—— 只读表、主键、保存中都算不能。 */
    void setEditGate(java.util.function.Predicate<ColumnDef> gate) {
        editGate = gate == null ? column -> false : gate;
    }

    /** 编辑态（数据页的 Esc 处理器要看它，别把「取消编辑」做成「关掉抽屉」）。 */
    boolean isEditing() {
        return editing;
    }

    /** 数据页把 Esc 翻译成「放弃这次编辑」，而不是「关掉抽屉」。 */
    void cancelEditing() {
        stopEditing();
    }

    private TextArea activeArea() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        if (selected == jsonTab) {
            return jsonArea;
        }
        if (selected == textTab) {
            return textArea;
        }
        return null;
    }

    private boolean canEdit() {
        return definition != null && activeArea() != null && editGate.test(definition);
    }

    /**
     * 进入编辑态。
     *
     * <p>回填的是**原值**而不是展示文本 —— 详情文本为了可读性做过收敛
     * （{@code 22.990000000000002 → 22.99}、NULL 显示成 {@code (NULL)}），
     * 一进一出就把精度弄丢、甚至写回一个字面量「(NULL)」。
     */
    private void startEdit() {
        TextArea area = activeArea();
        if (!canEdit() || area == null) {
            return;
        }
        editing = true;
        editingArea = area;
        area.setText(value == null ? "" : String.valueOf(value));
        area.setEditable(true);
        area.setWrapText(true);
        title.setText(definition.name() + " · 编辑中");
        flip(true);
        area.requestFocus();
    }

    private void commit() {
        TextArea area = editingArea;
        if (!editing || area == null) {
            return;
        }
        String text = area.getText();
        // 先复位再回调：回调里数据页会用新值重开抽屉，状态错一次就很难查。
        stopEditing();
        onApply.accept(text);
    }

    /** 退出编辑态（取消或应用都走这里），并把文本页恢复成渲染后的样子。 */
    private void stopEditing() {
        TextArea area = editingArea;
        for (TextArea candidate : java.util.List.of(textArea, jsonArea)) {
            candidate.setEditable(false);
            candidate.setWrapText(false);
        }
        editingArea = null;
        if (editing) {
            editing = false;
            if (area != null) {
                // 编辑态里塞的是原值，退出后要按渲染规则重新灌一遍。
                if (area == textArea) {
                    loadedText = false;
                    textArea.clear();
                } else {
                    loadedJson = false;
                    jsonArea.clear();
                }
                load(area == textArea ? textTab : jsonTab);
            }
        }
        if (definition != null && title.getText() != null
                && title.getText().endsWith(" · 编辑中")) {
            title.setText(definition.name());
        }
        flip(false);
    }

    private void flip(boolean editingNow) {
        edit.setVisible(!editingNow && canEdit());
        edit.setManaged(!editingNow && canEdit());
        apply.setVisible(editingNow);
        apply.setManaged(editingNow);
        cancel.setVisible(editingNow);
        cancel.setManaged(editingNow);
        // §2.5：编辑态里「取消」和右上「×」说的是同一句话 —— 留一个。
        // 关闭钮只在查看态出现（Esc 两条路径本来就分流：编辑中=取消，否则=关抽屉）。
        close.setVisible(!editingNow);
        close.setManaged(!editingNow);
    }

    private void load(Tab tab) {
        if (tab == null || definition == null) {
            return;
        }
        if (tab == textTab && !loadedText) {
            loadedText = true;
            textArea.setText(textContent());
        } else if (tab == jsonTab && !loadedJson) {
            loadedJson = true;
            jsonArea.setText(CellRenderer.prettyJson(value));
        } else if (tab == hexTab && !loadedHex) {
            loadedHex = true;
            hexArea.setText(CellRenderer.hexDump(value));
        } else if (tab == imageTab && !loadedImage) {
            loadedImage = true;
            loadImage();
        } else if (tab == metaTab && !loadedMeta) {
            loadedMeta = true;
            loadMetadata();
        }
    }

    private void loadImage() {
        imageBox.getChildren().clear();
        if (!(value instanceof byte[] bytes) || bytes.length == 0) {
            imageBox.getChildren().add(new Label("当前值不是二进制图片"));
            return;
        }
        try {
            Image image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError() || image.getWidth() <= 0) {
                throw new IllegalArgumentException("不是可识别的图片格式");
            }
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setFitWidth(440);
            view.setFitHeight(340);
            Label size = new Label((int) image.getWidth() + " × " + (int) image.getHeight()
                    + " · " + CellRenderer.sizeLabel(bytes.length));
            size.getStyleClass().add("adb-muted");
            imageBox.getChildren().add(new VBox(8, view, size));
        } catch (RuntimeException e) {
            imageBox.getChildren().add(new Label("不是可识别的图片格式"));
        }
    }

    private void loadMetadata() {
        metadata.getChildren().clear();
        int row = 0;
        addMeta("表", tableName, row++);
        addMeta("列", definition.name(), row++);
        addMeta("类型", definition.type().name(), row++);
        addMeta("可空", definition.nullable() ? "是" : "否", row++);
        addMeta("主键", definition.primaryKey() ? "是" : "否", row++);
        if (definition.charMaxLength() != null) {
            addMeta("长度", String.valueOf(definition.charMaxLength()), row++);
        }
        if (definition.numericPrecision() != null) {
            addMeta("精度", definition.numericPrecision()
                    + (definition.numericScale() == null ? ""
                    : "," + definition.numericScale()), row++);
        }
        if (definition.defaultValue() != null) {
            addMeta("默认值", definition.defaultValue(), row++);
        }
        if (definition.comment() != null && !definition.comment().isBlank()) {
            addMeta("注释", definition.comment(), row++);
        }
        addMeta("字节数", value instanceof byte[] bytes
                ? String.valueOf(bytes.length)
                : value == null ? "0" : String.valueOf(String.valueOf(value)
                .getBytes(StandardCharsets.UTF_8).length), row++);
        if (row > 0) {
            ScrollPane scroll = new ScrollPane(metadata);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("adb-details__scroll");
            metaTab.setContent(scroll);
        }
    }

    private void addMeta(String name, String value, int row) {
        Label key = new Label(name);
        key.getStyleClass().add("adb-details__meta-key");
        Label text = new Label(value == null ? "—" : value);
        text.getStyleClass().add("adb-details__meta-value");
        text.setWrapText(true);
        text.setMaxWidth(300);
        metadata.add(key, 0, row);
        metadata.add(text, 1, row);
    }

    private String textContent() {
        if (definition == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return "二进制数据 · " + CellRenderer.sizeLabel(bytes.length)
                    + "\n请切换到 Hex 或图片 Tab 查看。";
        }
        if (value == null) {
            // 和格子里同一套写法：括号的是「状态」，裸 NULL 留给**字面量就是 NULL 的文本**。
            return "(NULL)\n\n这是一条 NULL 值，不是空串，也不是内容为 \"NULL\" 的文本。";
        }
        if (value instanceof String text && text.isEmpty()) {
            return "空串\n\n这是一个长度为 0 的字符串，不是 NULL。";
        }
        if (definition.type() == ColumnType.JSON) {
            return CellRenderer.prettyJson(value);
        }
        return String.valueOf(value);
    }

    private void copyCurrent() {
        Tab selected = tabs.getSelectionModel().getSelectedItem();
        String text = selected == textTab ? textArea.getText()
                : selected == jsonTab ? jsonArea.getText()
                : selected == hexTab ? hexArea.getText()
                : selected == metaTab ? metadataText()
                : "";
        if (text == null || text.isEmpty()) {
            return;
        }
        javafx.scene.input.ClipboardContent content =
                new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private String metadataText() {
        StringBuilder text = new StringBuilder();
        for (Node node : metadata.getChildren()) {
            if (node instanceof Label label) {
                int column = GridPane.getColumnIndex(node) == null
                        ? 0 : GridPane.getColumnIndex(node);
                int row = GridPane.getRowIndex(node) == null ? 0 : GridPane.getRowIndex(node);
                if (column == 0) {
                    text.append(row > 0 ? "\n" : "").append(label.getText()).append(": ");
                } else {
                    text.append(label.getText());
                }
            }
        }
        return text.toString();
    }
}
