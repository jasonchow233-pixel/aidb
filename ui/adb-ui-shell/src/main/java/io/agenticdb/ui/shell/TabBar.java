package io.agenticdb.ui.shell;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import io.agenticdb.ui.Icons;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.shape.SVGPath;
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
    /** ⭐ S5：常驻标签（「对象」首页）—— 不可关闭，永远排第一。 */
    private String permanentId;
    private ContextMenu lastContextMenu;
    private Consumer<TabItem> onActivate = tab -> { };
    private Consumer<TabItem> onClose = tab -> { };
    private Predicate<TabItem> closeGuard = tab -> true;

    public TabBar(ActionRegistry actions) {
        this.actions = actions;
        getStyleClass().add("adb-tabbar");
        setAlignment(Pos.CENTER_LEFT);
        int height = io.agenticdb.ui.Tokens.H_TABBAR_LIGHT;
        setPrefHeight(height);
        setMinHeight(height);
        setMaxHeight(height);
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

        Button add = IconButtons.icon(Icons.Icon.ADD, "新建标签");
        add.setAccessibleText("新建标签");
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

    /**
     * ⭐ 打开**常驻标签**（S5 的「对象」首页）。
     *
     * <p>常驻标签永远排第一、**不可关闭** —— 它是浏览的起点
     * （Navicat 的标签栏最左边就是常驻的「对象」标签，实拍见
     * {@code docs/NAVICAT-COMPARE.md} §2.2 C1）。
     */
    public void openPermanent(TabItem tab) {
        permanentId = tab.id();
        if (items.containsKey(tab.id())) {
            return;
        }
        items.put(tab.id(), tab);
        Node node = buildTab(tab);
        nodes.put(tab.id(), node);
        tabs.getChildren().add(0, node);
        setVisible(true);
        setManaged(true);
        // ⭐ 常驻标签就是**默认落点** —— 启动时它应该是活动标签
        //    （Navicat 启动时停在「对象」标签上）。
        activate(tab.id());
    }

    /**
     * 标签节点的 DOM id。
     *
     * <p>⚠️ CSS 的 id 选择器里 {@code :} 是伪类分隔符、{@code .} 是类分隔符 ——
     * 标签 id 形如 {@code grid:demo:users}，直接用会查不到节点。
     * 统一换成短横线（同 {@code AppBar} 对动作 id 的处理）。
     */
    public static String nodeId(String tabId) {
        return "tab-" + (tabId == null ? "" : tabId.replaceAll("[^a-zA-Z0-9_-]", "-"));
    }

    /** 常驻标签的 id（没有则 null）。 */
    public String permanentId() {
        return permanentId;
    }

    /** 这个标签是常驻的（不可关闭）吗？ */
    public boolean isPermanent(String id) {
        return id != null && id.equals(permanentId);
    }

    /**
     * ⭐ X7：激活**常驻标签**（「对象」首页）。
     *
     * <p>恢复上次打开的标签之后，活动标签是「最后恢复的那个」——
     * 用户看到的不是浏览的起点。恢复完把落点交回常驻标签。
     */
    public void activatePermanent() {
        if (permanentId != null && items.containsKey(permanentId)) {
            activate(permanentId);
        }
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
        if (isPermanent(id)) {
            // ⭐ 常驻标签不可关闭 —— 没有任何标签时，它就是落点。
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
        // ⭐ 常驻标签不参与「关闭」类动作的可用性 ——
        //    否则在「对象」标签上点「关闭标签」会是「点了没反应」。
        actions.require("tab.close").setEnabled(!isPermanent(activeId));
        actions.find("tab.close-others").ifPresent(action ->
                action.setEnabled(closableCount() > 1));
        actions.find("tab.close-all").ifPresent(action ->
                action.setEnabled(closableCount() > 0));
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

    /** 除常驻标签外还剩几个标签（用于「关闭全部」的可用性）。 */
    public int closableCount() {
        return (int) items.keySet().stream().filter(id -> !isPermanent(id)).count();
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

    /** 供门禁检查标签栏右键菜单是否来自注册表（4.5-29）。 */
    ContextMenu contextMenuForTest() {
        return lastContextMenu;
    }

    private Node buildTab(TabItem tab) {
        // ⭐ 矢量图标（16×16）；标签栏是用户一直在看的地方，不能靠 Unicode 符号的字形运气。
        SVGPath icon = Icons.of(tab.kind().icon(), Icons.SIZE_LABEL);
        icon.getStyleClass().add("adb-tab__icon");

        Label title = new Label(tab.title());
        title.getStyleClass().add("adb-tab__title");

        Label dirty = new Label();
        dirty.getStyleClass().add("adb-tab__dirty");
        // ⭐ 状态点用矢量实心圆（不是 Unicode 的 ●）。
        dirty.setGraphic(Icons.of(Icons.Icon.DOT, Icons.SIZE_DOT));
        dirty.textProperty().bind(Bindings.convert(tab.dirtyCountProperty()));
        dirty.visibleProperty().bind(tab.dirtyProperty());
        dirty.managedProperty().bind(tab.dirtyProperty());

        // ⭐ 常驻标签**根本没有关闭钮** —— 不是「藏起来」，
        //    而是不放进场景图：界面上不该出现一个点了没反应的 ×，
        //    留在树里还会干扰「查找可见节点」这类操作。
        Button close = IconButtons.icon(Icons.Icon.CLOSE, "关闭标签");
        close.setAccessibleText("关闭");
        close.getStyleClass().add("adb-tab__close");
        close.setFocusTraversable(false);
        close.setOnAction(event -> {
            event.consume();
            close(tab.id());
        });

        HBox node = new HBox(6, icon, title, dirty);
        if (!isPermanent(tab.id())) {
            node.getChildren().add(close);
        }
        node.getStyleClass().add("adb-tab");
        // 给标签节点一个 id —— 门禁要能按 id 找到某个标签（常驻标签尤其需要）。
        node.setId(nodeId(tab.id()));
        node.setAlignment(Pos.CENTER_LEFT);
        node.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.MIDDLE) {
                close(tab.id());
            } else {
                activate(tab.id());
            }
        });

        ContextMenu menu = new ContextMenu();
        lastContextMenu = menu;
        // ⭐ 右键菜单走 ActionRegistry（4.5-29）：和菜单栏、命令面板同源，
        //    enabled 状态统一，也能在 ⌘K 里搜到。
        MenuItem closeItem = actions.menuItem("tab.close", "关闭");
        if (isPermanent(tab.id())) {
            // ⭐ 常驻标签不能关 —— 菜单项直接不出现，而不是「点了没反应」。
            closeItem.setVisible(false);
        }
        MenuItem closeOthers = actions.menuItem("tab.close-others", "关闭其他");
        MenuItem closeAll = actions.menuItem("tab.close-all", "全部关闭");
        // ⭐ S8：补「复制表名 / 复制 SELECT」—— Navicat 的标签右键也有。
        MenuItem copyName = actions.menuItem("tab.copy-name", "复制表名");
        MenuItem copySelect = actions.menuItem("tab.copy-select", "复制 SELECT");
        menu.getItems().addAll(copyName, copySelect, new SeparatorMenuItem(),
                closeItem, closeOthers, new SeparatorMenuItem(), closeAll);
        node.setOnContextMenuRequested(event -> {
            // 右键先激活该标签：这样菜单里的「关闭 / 关闭其他」作用的就是
            // 用户右键的那个标签，而不是上一次激活的那个。
            activate(tab.id());
            menu.show(node, event.getScreenX(), event.getScreenY());
        });

        return node;
    }
}
