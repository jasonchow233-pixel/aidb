package io.agenticdb.ui.shell;

import javafx.geometry.Pos;
import io.agenticdb.session.ConnectionProfile;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.function.Consumer;

/** 主工具栏：只放最高频动作，其余进菜单与命令面板。 */
public final class MainToolBar extends HBox {

    private final ComboBox<ConnectionProfile> connections = new ComboBox<>();
    private String activeConnectionId;
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
        setPrefHeight(36);
        setMinHeight(36);
        setMaxHeight(36);

        Button connect = actions.button("new-connection");
        connect.getStyleClass().add("adb-button--primary");
        connect.setText("＋ 连接");

        Button refresh = iconButton("↻", "刷新 (F5)", () -> actions.run("refresh"));
        refresh.setId("refresh-button");

        Button query = actions.button("new-query");
        query.setText("SQL");
        query.setTooltip(new Tooltip("新建查询 (⇧⌘N)"));
        query.setId("new-query-button");

        connections.getStyleClass().add("adb-connection-picker");
        connections.setPrefWidth(230);
        connections.setMaxWidth(230);
        connections.setCellFactory(view -> new ConnectionCell());
        connections.setButtonCell(new ConnectionCell());
        setConnections(profiles, activeConnectionId);
        connections.setOnAction(event -> {
            ConnectionProfile selected = connections.getValue();
            if (selected != null) {
                onSelectConnection.accept(selected);
            }
        });

        Region leftSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);

        Button search = new Button("⌘K   搜索对象或命令");
        search.getStyleClass().add("adb-search-trigger");
        search.setPrefWidth(320);
        search.setMaxWidth(320);
        search.setOnAction(event -> actions.run("command-palette"));

        Region rightSpacer = new Region();
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        Button theme = iconButton("◐", "切换主题", () -> actions.run("theme.toggle"));
        theme.setId("theme-toggle-button");
        Button settings = iconButton("⚙", "设置 (⌘,)", () -> actions.run("settings"));
        settings.setId("settings-button");

        getChildren().addAll(
                connect, refresh, query, separator(), connections,
                leftSpacer, search, rightSpacer,
                theme, settings);
        this.onSelectConnection = onSelectConnection == null ? profile -> { } : onSelectConnection;
        this.activeConnectionId = activeConnectionId;
        setConnections(profiles, activeConnectionId);
    }

    public void setConnections(List<ConnectionProfile> profiles, String activeConnectionId) {
        this.activeConnectionId = activeConnectionId;
        connections.setItems(javafx.collections.FXCollections.observableArrayList(
                profiles == null ? List.of() : profiles));
        ConnectionProfile active = profiles == null ? null : profiles.stream()
                .filter(profile -> profile.id().equals(activeConnectionId))
                .findFirst().orElse(null);
        if (active != null) {
            connections.getSelectionModel().select(active);
        }
    }

    private static Button iconButton(String text, String tooltip, Runnable handler) {
        Button button = new Button(text);
        button.getStyleClass().addAll("adb-icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> handler.run());
        return button;
    }

    private static Region separator() {
        Region region = new Region();
        region.getStyleClass().add("adb-toolbar__separator");
        return region;
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
