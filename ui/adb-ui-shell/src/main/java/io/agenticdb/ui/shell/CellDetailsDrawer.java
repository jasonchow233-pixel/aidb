package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

    CellDetailsDrawer() {
        getStyleClass().add("adb-details");
        setId("data-details");
        setVisible(false);
        setManaged(false);
        setPrefWidth(480);
        setMinWidth(360);
        setMaxWidth(620);

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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox labels = new VBox(2, title, subtitle);
        HBox header = new HBox(8, labels, spacer, copy, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("adb-details__header");

        prepareArea(textArea, "data-details-text");
        prepareArea(jsonArea, "data-details-json");
        prepareArea(hexArea, "data-details-hex");
        textTab.setContent(textArea);
        jsonTab.setContent(jsonArea);
        hexTab.setContent(hexArea);
        imageTab.setContent(imageBox);
        metaTab.setContent(metadata);
        tabs.getTabs().addAll(textTab, jsonTab, hexTab, imageTab, metaTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setId("data-details-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);
        tabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> load(selected));

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
            return "NULL\n\n这是一条 NULL 值，不是空串。";
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
