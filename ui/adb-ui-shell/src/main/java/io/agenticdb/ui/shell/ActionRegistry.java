package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCombination;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** 动作注册表：菜单 / 工具栏 / 右键菜单 / 命令面板的唯一真相。 */
public final class ActionRegistry {

    private final Map<String, Action> actions = new LinkedHashMap<>();

    /** 动作被禁用时的反馈出口（一般是状态栏）。 */
    private Consumer<String> feedback = message -> { };

    /**
     * 设置「动作当前不可用」的反馈出口。
     *
     * <p>⛔ 以前禁用动作是静默 no-op —— 用户点了没反应也不知道为什么。
     */
    public void setFeedback(Consumer<String> feedback) {
        this.feedback = feedback == null ? message -> { } : feedback;
    }

    public Action register(String id, String label, String category,
                           KeyCombination accelerator, Runnable handler) {
        if (actions.containsKey(id)) {
            throw new IllegalArgumentException("动作 id 重复: " + id);
        }
        Action action = new Action(id, label, category, accelerator, handler);
        // 读字段而不是捕获值：feedback 可能在注册之后才被设置。
        // 后缀走资源 —— 「X 当前不可用」这句在 Java 里写死，英文界面就会长出一句中文。
        action.setOnDisabled(() -> feedback.accept(label + io.agenticdb.ui.I18n.t("action.disabledSuffix")));
        actions.put(id, action);
        return action;
    }

    public Action require(String id) {
        Action action = actions.get(id);
        if (action == null) {
            throw new IllegalArgumentException("未注册的动作: " + id);
        }
        return action;
    }

    public Optional<Action> find(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    public Collection<Action> all() {
        return actions.values();
    }

    public void run(String id) {
        require(id).run();
    }

    public MenuItem menuItem(String id) {
        return menuItem(id, null);
    }

    /**
     * 菜单项，文案可覆盖。
     *
     * <p>行为与 enabled 仍来自动作本身；只是允许在不同入口用不同措辞
     * （比如标签栏右键写「关闭」，菜单栏写「关闭标签」）。
     */
    public MenuItem menuItem(String id, String labelOverride) {
        return menuItemFor(require(id), labelOverride);
    }

    /**
     * 由一个动作实例直接生成菜单项。
     *
     * <p>表清单页拿到的是注册表里的 Action（不是 id），但菜单项的启用态、
     * 样式钩子与快捷键必须和其它入口完全一致 —— 所以收口到这里，
     * 不让页面再手搓一份 {@link MenuItem}。
     */
    public static MenuItem menuItemFor(Action action, String labelOverride) {
        Objects.requireNonNull(action, "action");
        MenuItem item = new MenuItem(labelOverride == null ? action.label() : labelOverride);
        item.setId(action.id());
        // ⭐ 统一的菜单项样式钩子：`.adb-menu-item` 让禁用态的透明度还原规则
        //    真正作用到菜单项上（以前这个类在 CSS 里定义着，却没有任何节点用它）。
        item.getStyleClass().add("adb-menu-item");
        if (action.accelerator() != null) {
            item.setAccelerator(action.accelerator());
        }
        item.setOnAction(event -> action.run());
        item.disableProperty().bind(action.enabledProperty().not());
        return item;
    }

    public static MenuItem menuItemFor(Action action) {
        return menuItemFor(action, null);
    }

    /**
     * 单选菜单项：勾选态与 {@link Action#selectedProperty()} 双向绑定，
     * 选中状态跟着动作走（密度三档、主题三选一都用它）。
     */
    public RadioMenuItem radioMenuItem(String id, ToggleGroup group) {
        Action action = require(id);
        RadioMenuItem item = new RadioMenuItem(action.label());
        item.setId(id);
        item.setToggleGroup(group);
        item.selectedProperty().bindBidirectional(action.selectedProperty());
        item.setOnAction(event -> action.run());
        item.disableProperty().bind(action.enabledProperty().not());
        return item;
    }

    public Button button(String id) {
        return buttonFor(require(id));
    }

    /** 由一个动作实例生成文字按钮（表清单页拿到的是 Action，不是 id）。 */
    public static Button buttonFor(Action action) {
        Objects.requireNonNull(action, "action");
        Button button = new Button(action.label());
        button.getStyleClass().add("adb-button");
        // 与 menuItem 一致：CSS id 就是动作 id，方便测试与样式定位。
        button.setId(action.id());
        button.setOnAction(event -> action.run());
        button.disableProperty().bind(action.enabledProperty().not());
        return button;
    }

    /**
     * 图标按钮工厂：只放符号，靠 Tooltip 与 accessible text 说明语义。
     *
     * <p>⛔ 以前图标按钮不走 enabled 绑定 —— 动作禁用时按钮<b>看起来仍可点</b>，
     * 只是点了没反应。现在 enabled 与动作同步。
     */
    public Button iconButton(String id, Icons.Icon icon, String tooltip, String accessibleText) {
        return iconButtonFor(require(id), icon, tooltip, accessibleText);
    }

    /**
     * 由一个动作实例生成纯图标按钮（提示文字由调用方给，accessibleText 同理）。
     *
     * <p>⭐ U26：动作带快捷键时，tooltip 自动追加「(Ctrl+K)」—— 这一层就是
     * 收口点：调用方只写语义提示，漏没漏快捷键不再取决于手打。
     * accessibleText 保持纯文案（读屏会单独播报按键，写进正文反而是重复）。
     */
    public static Button iconButtonFor(Action action, Icons.Icon icon,
                                       String tooltip, String accessibleText) {
        Objects.requireNonNull(action, "action");
        // ⭐ 矢量图标（不是 Unicode 符号 —— 符号的字形随系统字体变）。
        Button button = new Button();
        button.setGraphic(Icons.of(icon));
        button.getStyleClass().addAll("adb-icon-button");
        button.setId(action.id());
        button.setTooltip(new javafx.scene.control.Tooltip(action.hintWithShortcut(tooltip)));
        button.setAccessibleText(accessibleText);
        button.setOnAction(event -> action.run());
        button.disableProperty().bind(action.enabledProperty().not());
        return button;
    }

    /** 冲突检测：同一个组合键只允许绑定一个动作。 */
    public void validateUniqueAccelerators() {
        Map<KeyCombination, String> seen = new LinkedHashMap<>();
        for (Action action : actions.values()) {
            if (action.accelerator() == null) {
                continue;
            }
            String previous = seen.putIfAbsent(action.accelerator(), action.id());
            if (previous != null) {
                throw new IllegalStateException(
                        "快捷键冲突: " + action.accelerator()
                                + " 同时绑定 " + previous + " 与 " + action.id());
            }
        }
    }

    public void requireExists(String id) {
        Objects.requireNonNull(actions.get(id), () -> "动作不存在: " + id);
    }
}
