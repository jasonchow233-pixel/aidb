package io.agenticdb.ui.shell;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import io.agenticdb.ui.I18n;
import io.agenticdb.ui.Icons;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** 标签栏：每个标签独立状态，关闭有脏改动时由上层确认。 */
public final class TabBar extends HBox {

    private final ActionRegistry actions;
    private final HBox tabs = new HBox();
    private final Map<String, TabItem> items = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    /** 每个标签的标题节点（重名时要改写，所以得能单独找到）。 */
    private final Map<String, Label> titles = new LinkedHashMap<>();
    /** 每个标签的全名提示（HBox 没有 setTooltip，只能用 Tooltip.install 挂）。 */
    private final Map<String, Tooltip> tooltips = new LinkedHashMap<>();

    private String activeId;
    /** ⭐ S5：常驻标签（「对象」首页）—— 不可关闭，永远排第一。 */
    private String permanentId;
    private ContextMenu lastContextMenu;
    private Consumer<TabItem> onActivate = tab -> { };
    private Consumer<TabItem> onClose = tab -> { };
    private Predicate<TabItem> closeGuard = tab -> true;
    /**
     * ⭐ UX-5：连接 id → 给人看的连接名。
     *
     * <p>标签自己只认识 {@code pg} 这种 id，把 id 直接写进标题等于没写
     * （「users @ pg」对用户毫无信息量）。名字只有外壳知道，所以由它注入；
     * 没注入时退回 id —— 至少不骗人。
     */
    private Function<String, String> connectionLabel = id -> id;
    /** ⭐ N7-b：连接 id → 库名（空串 = 没有库名）。 */
    private Function<String, String> connectionCatalog = id -> "";

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

        Button add = IconButtons.icon(Icons.Icon.ADD, I18n.t("tab.newTab"));
        add.setAccessibleText(I18n.t("tab.newTab"));
        add.getStyleClass().addAll("adb-icon-button", "adb-tabbar__add");
        add.setTooltip(new javafx.scene.control.Tooltip(
                io.agenticdb.ui.Shortcuts.labelled(I18n.t("tab.newQuery"), "Shift+Mod+N")));
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

    /**
     * ⭐ UX-5：注入「连接 id → 连接名」（外壳才有档案名字）。
     *
     * <p>顺带重写一次标题：档案改名后，重名标签的后缀必须跟着变。
     */
    public void setConnectionLabels(Function<String, String> connectionLabel) {
        this.connectionLabel = connectionLabel == null ? id -> id : connectionLabel;
        refreshTitles();
    }

    /**
     * ⭐ N7-b：注入「连接 id → 库名」。
     *
     * <p>页签标题的格式是 {@code <对象> @<库> (<连接>)} —— 库名只有外壳知道
     * （catalog 虚拟连接的库名在 id 后缀里，父连接的在档案里）。
     * 返回空串 = 这个连接没有库名（SQLite 单文件），标题退回 {@code @<连接>}。
     */
    public void setConnectionCatalogs(Function<String, String> connectionCatalog) {
        this.connectionCatalog = connectionCatalog == null ? id -> "" : connectionCatalog;
        refreshTitles();
    }

    /**
     * ⭐ UX-5：重名标签加连接后缀，每个标签都挂上「全名」提示。
     *
     * <p>多连接之下两张 {@code users} 表并排站着，标题一模一样 —— 点哪个
     * 全凭猜。Navicat 的做法是给**重名的那几个**都补上连接名（不是只补后来的，
     * 否则先开的那张看起来像「本尊」，另一个像副本，而它们完全平权）。
     * 不重名时保持干净标题，全名进 tooltip。
     */
    private void refreshTitles() {
        Map<String, Integer> counted = new LinkedHashMap<>();
        for (TabItem tab : items.values()) {
            counted.merge(tab.title(), 1, Integer::sum);
        }
        for (TabItem tab : items.values()) {
            Label title = titles.get(tab.id());
            if (title == null) {
                continue;
            }
            boolean duplicated = counted.getOrDefault(tab.title(), 1) > 1;
            title.setText(duplicated ? qualified(tab) : tab.title());
            // ⭐ P7：读屏名称跟着可见标题走（重名时也带上连接名）。
            Node tabNode = nodes.get(tab.id());
            if (tabNode != null) {
                tabNode.setAccessibleText(duplicated ? qualified(tab) : tab.title());
            }
            // tooltip **永远**给全名：不重名不代表不用告诉用户这张表在哪个库。
            // ⚠️ 标签是 HBox（不是 Control），没有 setTooltip —— 用静态的
            //    Tooltip.install(node, tooltip)，它接受任意 Node。
            Tooltip tooltip = tooltips.get(tab.id());
            if (tooltip != null) {
                tooltip.setText(qualified(tab));
            }
        }
    }

    /**
     * 带库名与连接名的全名（N7-b：{@code <对象> @<库> (<连接>)}）。
     *
     * <p>没有归属连接（设置页这类全局标签）就只给标题；没有库名
     * （SQLite 单文件）退回 {@code @<连接>}，不空着占位。
     */
    private String qualified(TabItem tab) {
        String connectionId = tab.connectionId();
        if (connectionId == null || connectionId.isBlank()) {
            return tab.title();
        }
        String label = connectionLabel.apply(connectionId);
        // 解析不出名字（档案已删）时宁可显示 id，也不冒充「未知连接」。
        String connection = label == null || label.isBlank() ? connectionId : label;
        String catalog = connectionCatalog.apply(connectionId);
        if (catalog == null || catalog.isBlank()) {
            return tab.title() + " @" + connection;
        }
        return tab.title() + " @" + catalog + " (" + connection + ")";
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
        refreshTitles();
        setVisible(true);
        setManaged(true);
        activate(tab.id());
    }

    /**
     * ⭐ 打开**常驻标签**（S5 的「对象」首页）。
     *
     * <p>常驻标签永远排第一、**不可关闭** —— 它是浏览的起点
     * （Navicat 的标签栏最左边就是常驻的「对象」标签，官方截图实拍）。
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
        refreshTitles();
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
        titles.remove(id);
        tooltips.remove(id);
        if (node != null) {
            tabs.getChildren().remove(node);
        }
        // ⭐ UX-5：关掉一张重名表，剩下那张必须**退回干净标题** ——
        //    留着「users @ 生产库」是在告诉用户「还有一个同名的人」，而那人已经走了。
        refreshTitles();

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

    /**
     * 供门禁读取的全名提示文本。
     *
     * <p>{@code Tooltip.install(node, tooltip)} 挂在节点上但**取不回实例**
     * （Node 没有 getTooltip），所以这里留一个包级读数口 —— 断言测的是
     * 「用户停上去会看到什么」，不是「map 里有没有东西」。
     */
    String tooltipTextForTest(String tabId) {
        Tooltip tooltip = tooltips.get(tabId);
        return tooltip == null ? null : tooltip.getText();
    }

    /** 方向键在标签之间移动：焦点按视觉顺序走，并同步激活（Tab 控件的惯例）。 */
    private void stepTabFocus(Node from, int delta) {
        List<Node> visual = new ArrayList<>(tabs.getChildren());
        int index = visual.indexOf(from);
        if (index < 0 || visual.isEmpty()) {
            return;
        }
        Node target = visual.get(Math.floorMod(index + delta, visual.size()));
        target.requestFocus();
        nodes.entrySet().stream()
                .filter(entry -> entry.getValue() == target)
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(this::activate);
    }

    private Node buildTab(TabItem tab) {
        // ⭐ 矢量图标（16×16）；标签栏是用户一直在看的地方，不能靠 Unicode 符号的字形运气。
        //    R35 N4-b：页签图标走彩色档 —— {@code colored()} 自己会在没有彩色资产时
        //    退回单色，所以调用方不需要判断（判断一次就多一条会写错的分叉）。
        Node icon = Icons.colored(tab.kind().icon(), Icons.SIZE_LABEL);
        icon.getStyleClass().add("adb-tab__icon");

        Label title = new Label(tab.title());
        title.getStyleClass().add("adb-tab__title");
        // ⭐ UX-5：留着节点 —— 重名判定变了要改写标题（只改 TabItem 的标题没用，
        //    页面工厂靠 target 取数据，标题是给人看的）。
        titles.put(tab.id(), title);

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
        Button close = IconButtons.icon(Icons.Icon.CLOSE, I18n.t("tab.closeTab"));
        close.setAccessibleText(I18n.t("tab.close"));
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
        // ⭐ UX-5：全名提示（重名时标题带连接名，不重名时标题只有表名 ——
        //    但「这张表在哪个库」永远值得鼠标停上去就能看到）。
        Tooltip tooltip = new Tooltip(tab.title());
        tooltips.put(tab.id(), tooltip);
        Tooltip.install(node, tooltip);
        node.setAlignment(Pos.CENTER_LEFT);
        // ⭐ P7：标签是 HBox（不是 Button），默认不可聚焦 —— 键盘用户根本到不了
        //    标签栏。补上 Tab 停靠点、TAB_ITEM 角色与 Enter/Space、左右方向键。
        node.setFocusTraversable(true);
        node.setAccessibleRole(AccessibleRole.TAB_ITEM);
        node.setAccessibleText(tab.title());
        node.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                activate(tab.id());
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.RIGHT) {
                stepTabFocus(node, event.getCode() == KeyCode.LEFT ? -1 : 1);
                event.consume();
            }
        });
        node.setOnMouseClicked(event -> {
            node.requestFocus();
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
        MenuItem closeItem = actions.menuItem("tab.close", I18n.t("tab.close"));
        if (isPermanent(tab.id())) {
            // ⭐ 常驻标签不能关 —— 菜单项直接不出现，而不是「点了没反应」。
            closeItem.setVisible(false);
        }
        MenuItem closeOthers = actions.menuItem("tab.close-others", I18n.t("tab.closeOthers"));
        MenuItem closeAll = actions.menuItem("tab.close-all", I18n.t("tab.closeAll"));
        // ⭐ S8：补「复制表名 / 复制 SELECT」—— Navicat 的标签右键也有。
        MenuItem copyName = actions.menuItem("tab.copy-name", I18n.t("tab.copyName"));
        MenuItem copySelect = actions.menuItem("tab.copy-select", I18n.t("tab.copySelect"));
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
