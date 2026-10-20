package io.agenticdb.ui.shell;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

/** 主工具栏：只放最高频动作，其余进菜单与命令面板。 */
public final class MainToolBar extends HBox {

    public MainToolBar(ActionRegistry actions, String connectionName, Consumer<String> status) {
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

        ComboBox<String> connections = new ComboBox<>();
        connections.getStyleClass().add("adb-connection-picker");
        connections.getItems().addAll(connectionName + " · 只读");
        connections.getSelectionModel().selectFirst();
        connections.setPrefWidth(190);
        connections.setMaxWidth(190);
        connections.setOnAction(event ->
                status.accept("当前连接：" + connections.getValue()));

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
                connect, refresh, separator(), connections,
                leftSpacer, search, rightSpacer,
                theme, settings);
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
}
