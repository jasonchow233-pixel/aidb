package io.agenticdb.ui.shell;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.input.KeyCombination;

/**
 * 一个可执行动作。菜单、工具栏、右键菜单、命令面板共用同一个实例。
 *
 * <p>⛔ 不要在菜单里另写 enabled 判断 —— 那正是旧版「7 个菜单项全是摆设」的根因。
 */
public final class Action {

    private final String id;
    private final String label;
    private final String category;
    private final KeyCombination accelerator;
    private final BooleanProperty enabled = new SimpleBooleanProperty(true);
    private final Runnable handler;

    public Action(String id, String label, String category,
                  KeyCombination accelerator, Runnable handler) {
        this.id = id;
        this.label = label;
        this.category = category;
        this.accelerator = accelerator;
        this.handler = handler;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String category() {
        return category;
    }

    public KeyCombination accelerator() {
        return accelerator;
    }

    public BooleanProperty enabledProperty() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public void run() {
        if (enabled.get()) {
            handler.run();
        }
    }
}
