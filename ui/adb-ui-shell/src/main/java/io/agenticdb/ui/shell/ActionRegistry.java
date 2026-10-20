package io.agenticdb.ui.shell;

import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 动作注册表：菜单 / 工具栏 / 右键菜单 / 命令面板的唯一真相。 */
public final class ActionRegistry {

    private final Map<String, Action> actions = new LinkedHashMap<>();

    public Action register(String id, String label, String category,
                           KeyCombination accelerator, Runnable handler) {
        if (actions.containsKey(id)) {
            throw new IllegalArgumentException("动作 id 重复: " + id);
        }
        Action action = new Action(id, label, category, accelerator, handler);
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
        Action action = require(id);
        MenuItem item = new MenuItem(action.label());
        if (action.accelerator() != null) {
            item.setAccelerator(action.accelerator());
        }
        item.setOnAction(event -> action.run());
        item.disableProperty().bind(action.enabledProperty().not());
        return item;
    }

    public Button button(String id) {
        Action action = require(id);
        Button button = new Button(action.label());
        button.getStyleClass().add("adb-button");
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
