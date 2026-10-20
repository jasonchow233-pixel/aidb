package io.agenticdb.ui.shell;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 标签栏：每个标签独立状态，关闭有脏改动时由上层确认。 */
public final class TabBar extends HBox {

    private final ActionRegistry actions;
    private final HBox tabs = new HBox();
    private final Map<String, TabItem> items = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();

    private String activeId;
    private Consumer<TabItem> onActivate = tab -> { };
    private Consumer<TabItem> onClose = tab -> { };
    private Predicate<TabItem> closeGuard = tab -> true;

    public TabBar(ActionRegistry actions) {
        this.actions = actions;
        getStyleClass().add("adb-tabbar");
        setAlignment(Pos.CENTER_LEFT);
        setPrefHeight(36);
        setMinHeight(36);
        setMaxHeight(36);
        setVisible(false);
        setManaged(false);

        tabs.getStyleClass().add("adb-tabbar__tabs");
        tabs.setAlignment(Pos.CENTER_LEFT);

        ScrollPane scroll = new ScrollPane(tabs);
        scroll.getStyleClass().add("adb-tabbar__scroll");
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPannable(true);
        HBox.setHgrow(scroll, Priority.ALWAYS);

        Button add = new Button("＋");
        add.getStyleClass().addAll("adb-icon-button", "adb-tabbar__add");
        add.setTooltip(new javafx.scene.control.Tooltip("新建查询 ⇧⌘N"));
        add.setOnAction(event -> actions.run("new-query"));

        getChildren().addAll(scroll, add);
    }

    public void setOnActivate(Consumer<TabItem> onActivate) {
        this.onActivate = onActivate;
    }

    public void setOnClose(Consumer<TabItem> onClose) {
        this.onClose = onClose;
    }

    /** 返回 false 时取消关闭，标签和页面缓冲原样保留。 */
    public void setCloseGuard(Predicate<TabItem> closeGuard) {
        this.closeGuard = closeGuard == null ? tab -> true : closeGuard;
    }

    public List<TabItem> openTabs() {
        return List.copyOf(items.values());
    }

    public boolean hasDirtyTabs() {
        return items.values().stream().anyMatch(tab -> tab.dirtyProperty().get());
    }

    public void open(TabItem tab) {
        if (items.containsKey(tab.id())) {
            activate(tab.id());
            return;
        }
        items.put(tab.id(), tab);
        Node node = buildTab(tab);
        nodes.put(tab.id(), node);
        tabs.getChildren().add(node);
        setVisible(true);
        setManaged(true);
        activate(tab.id());
    }

    public void activate(String id) {
        if (!items.containsKey(id)) {
            return;
        }
        activeId = id;
        nodes.forEach((key, node) ->
                node.getStyleClass().remove("adb-tab--active"));
        Node node = nodes.get(id);
        if (node != null && !node.getStyleClass().contains("adb-tab--active")) {
            node.getStyleClass().add("adb-tab--active");
        }
        onActivate.accept(items.get(id));
    }

    public void closeActive() {
        if (activeId != null) {
            close(activeId);
        }
    }

    public boolean close(String id) {
        TabItem tab = items.get(id);
        if (tab == null) {
            return false;
        }
        if (!closeGuard.test(tab)) {
            return false;
        }
        items.remove(id);
        Node node = nodes.remove(id);
        if (node != null) {
            tabs.getChildren().remove(node);
        }

        if (id.equals(activeId)) {
            activeId = null;
            if (!items.isEmpty()) {
                activate(items.keySet().iterator().next());
            }
        }

        if (items.isEmpty()) {
            setVisible(false);
            setManaged(false);
        }
        actions.require("tab.close").setEnabled(!items.isEmpty());
        onClose.accept(tab);
        return true;
    }

    public void closeOthers(String keepId) {
        List<String> ids = List.copyOf(items.keySet());
        for (String id : ids) {
            if (!id.equals(keepId)) {
                close(id);
            }
        }
        activate(keepId);
    }

    public void closeAll() {
        List<String> ids = List.copyOf(items.keySet());
        ids.forEach(this::close);
    }

    public int count() {
        return items.size();
    }

    public String activeId() {
        return activeId;
    }

    public TabItem active() {
        return activeId == null ? null : items.get(activeId);
    }

    private Node buildTab(TabItem tab) {
        Label icon = new Label(tab.kind().icon());
        icon.getStyleClass().add("adb-tab__icon");

        Label title = new Label(tab.title());
        title.getStyleClass().add("adb-tab__title");

        Label dirty = new Label();
        dirty.getStyleClass().add("adb-tab__dirty");
        dirty.textProperty().bind(Bindings.concat("●", tab.dirtyCountProperty()));
        dirty.visibleProperty().bind(tab.dirtyProperty());
        dirty.managedProperty().bind(tab.dirtyProperty());

        Button close = new Button("✕");
        close.getStyleClass().add("adb-tab__close");
        close.setFocusTraversable(false);
        close.setOnAction(event -> {
            event.consume();
            close(tab.id());
        });

        HBox node = new HBox(6, icon, title, dirty, close);
        node.getStyleClass().add("adb-tab");
        node.setAlignment(Pos.CENTER_LEFT);
        node.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.MIDDLE) {
                close(tab.id());
            } else {
                activate(tab.id());
            }
        });

        ContextMenu menu = new ContextMenu();
        MenuItem closeItem = new MenuItem("关闭");
        closeItem.setOnAction(event -> close(tab.id()));
        MenuItem closeOthers = new MenuItem("关闭其他");
        closeOthers.setOnAction(event -> closeOthers(tab.id()));
        MenuItem closeAll = new MenuItem("全部关闭");
        closeAll.setOnAction(event -> closeAll());
        menu.getItems().addAll(closeItem, closeOthers, new SeparatorMenuItem(), closeAll);
        node.setOnContextMenuRequested(event -> menu.show(node, event.getScreenX(), event.getScreenY()));

        return node;
    }
}
