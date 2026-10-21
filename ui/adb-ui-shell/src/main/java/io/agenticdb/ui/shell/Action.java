package io.agenticdb.ui.shell;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.input.KeyCombination;

/**
 * 一个可执行动作。菜单、工具栏、右键菜单、命令面板共用同一个实例。
 *
 * <p>⛔ 不要在菜单里另写 enabled 判断 —— 那正是旧版「7 个菜单项全是摆设」的根因。
 *
 * <p>{@code selected} 供单选菜单（密度三档）表达「当前档位」，
 * 勾选态跟着动作走，而不是菜单项自己存一份。
 */
public final class Action {

    private final String id;
    private final String label;
    private final String category;
    private final KeyCombination accelerator;
    private final BooleanProperty enabled = new SimpleBooleanProperty(true);
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Runnable handler;
    private Runnable onDisabled = () -> { };

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

    /** 单选菜单的勾选态（如密度三档）。默认不勾选。 */
    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    /**
     * 动作被禁用时的反馈回调。
     *
     * <p>⛔ 以前禁用动作是<b>静默 no-op</b>：用户点了没反应，也不知道为什么。
     * 「为什么这个按钮是灰的」是用户能问出口的问题，界面必须能回答。
     */
    public void setOnDisabled(Runnable onDisabled) {
        this.onDisabled = onDisabled == null ? () -> { } : onDisabled;
    }

    public void run() {
        if (enabled.get()) {
            handler.run();
        } else {
            onDisabled.run();
        }
    }
}
