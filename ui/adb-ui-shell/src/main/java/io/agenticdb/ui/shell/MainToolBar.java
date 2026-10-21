package io.agenticdb.ui.shell;

import io.agenticdb.session.ConnectionProfile;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * 主工具栏 —— ⭐ **大图标 + 文字的功能栏**（Navicat 的形态）。
 *
 * <p>旧版是一排小按钮（连接 / 刷新 / SQL / 下拉 / 伪搜索框），
 * 功能入口全藏在二级菜单里 —— 新手根本不知道「建表」「导入」在哪。
 * 现在把高频入口摆到台面上：<b>连接 / 查询 / 表 / 导入导出 / 工具</b>。
 *
 * <p>每个入口 = 一个大图标 + 一行文字（图标在上、文字在下，和 Navicat 一致）。
 */
public final class MainToolBar extends HBox {

    private final ComboBox<ConnectionProfile> connections = new ComboBox<>();
    private Consumer<ConnectionProfile> onSelectConnection = profile -> { };

    public MainToolBar(ActionRegistry actions, String connectionName, Consumer<String> status) {
        this(actions, status, List.of(), null, profile -> { });
    }

    public MainToolBar(ActionRegistry actions, Consumer<String> status,
                       List<ConnectionProfile> profiles, String activeConnectionId,
                       Consumer<ConnectionProfile> onSelectConnection) {
        getStyleClass().add("adb-toolbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        setPrefHeight(52);
        setMinHeight(52);
        setMaxHeight(52);

        // ⭐ 大图标功能入口。图标与文字的语义必须和全应用一致
        //    （见 IconAuditTest 的「符号 → 语义唯一」门禁）。
        feature(actions, "connections.manage", "◈", "连接", "打开连接管理");
        feature(actions, "new-query", "⌨", "查询", "新建查询 (⇧⌘N)");
        feature(actions, "object.tables", "▦", "表", "打开表清单");
        feature(actions, "import-export", "⇅", "导入导出", "导入 / 导出数据");
        feature(actions, "diagnostics", "◆", "工具", "诊断与运维工具");

        Region featureSpacer = new Region();
        HBox.setHgrow(featureSpacer, Priority.ALWAYS);

        connections.setId("toolbar-connection-picker");
        connections.getStyleClass().add("adb-connection-picker");
        connections.setPrefWidth(220);
        connections.setMaxWidth(220);
        connections.setCellFactory(view -> new ConnectionCell());
        connections.setButtonCell(new ConnectionCell());
        setConnections(profiles, activeConnectionId);
        connections.setOnAction(event -> {
            ConnectionProfile selected = connections.getValue();
            if (selected != null) {
                onSelectConnection.accept(selected);
            }
        });

        Button search = new Button("⌘K   搜索对象或命令");
        search.setId("toolbar-search");
        search.getStyleClass().add("adb-search-trigger");
        search.setPrefWidth(240);
        search.setMaxWidth(240);
        search.setOnAction(event -> actions.run("command-palette"));

        Button theme = actions.iconButton("theme.toggle", "◐", "切换主题", "切换主题");
        theme.setId("theme-toggle-button");
        Button settings = actions.iconButton("settings", "⚙", "设置 (⌘,)", "设置");
        settings.setId("settings-button");

        getChildren().addAll(featureSpacer, connections, search, theme, settings);
        this.onSelectConnection = onSelectConnection == null ? profile -> { } : onSelectConnection;
    }

    /**
     * 一个大图标功能入口。
     *
     * <p>用 {@link VBox}（图标在上、文字在下）而不是普通 Button ——
     * 普通 Button 的文字和图标没法分两行，而这是 Navicat 那种「看得见的功能栏」的关键。
     */
    private void feature(ActionRegistry actions, String actionId, String symbol, String label,
                         String tooltip) {
        Label icon = new Label(symbol);
        icon.getStyleClass().add("adb-feature__icon");
        icon.setAccessibleText(label);
        // 图标要**居中**在文字上方：Label 默认按内容宽度摆，会偏左。
        icon.setMaxWidth(Double.MAX_VALUE);
        icon.setAlignment(Pos.CENTER);
        // ⚠️ setTooltip 在 Control 上，不在 Node 上 —— 纯 VBox 挂不了 Tooltip，
        //    所以挂在图标 Label 上。
        icon.setTooltip(new Tooltip(tooltip));

        Label text = new Label(label);
        text.getStyleClass().add("adb-feature__label");
        text.setMaxWidth(Double.MAX_VALUE);
        text.setAlignment(Pos.CENTER);

        VBox box = new VBox(1, icon, text);
        // ⚠️ CSS 的 id 选择器里「.」是类选择器分隔符 —— 动作 id 带点的话
        //    lookup("#feature-object.tables") 会找不到节点。统一换成短横线。
        box.setId("feature-" + actionId.replace('.', '-'));
        box.getStyleClass().add("adb-feature");
        box.setAlignment(Pos.CENTER);
        box.setOnMouseClicked(event -> actions.run(actionId));

        getChildren().add(box);
    }

    public void setConnections(List<ConnectionProfile> profiles, String activeConnectionId) {
        connections.setItems(javafx.collections.FXCollections.observableArrayList(
                profiles == null ? List.of() : profiles));
        ConnectionProfile active = profiles == null ? null : profiles.stream()
                .filter(profile -> profile.id().equals(activeConnectionId))
                .findFirst().orElse(null);
        if (active != null) {
            connections.getSelectionModel().select(active);
        }
    }

    private static final class ConnectionCell extends ListCell<ConnectionProfile> {
        @Override
        protected void updateItem(ConnectionProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.name() + "  ·  " + item.kindLabel());
        }
    }
}
