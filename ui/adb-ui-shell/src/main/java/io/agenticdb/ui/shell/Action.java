package io.agenticdb.ui.shell;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCodeCombination;

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

    /**
     * 快捷键的当前平台读法（如 Windows「Ctrl+K」、macOS「⌘K」）；无快捷键返回 null。
     *
     * <p>U26：tooltip 是快捷键的唯一鼠标入口 —— 菜单里 FX 自己画 accelerator，
     * 工具栏按钮灰着的时候没人告诉用户「其实有快捷键」。文案一律经
     * {@link io.agenticdb.ui.Shortcuts} 平台化，源码里不出现 ⌘ 字形。
     */
    public String shortcutText() {
        if (accelerator == null) {
            return null;
        }
        String spec = semanticSpec();
        return spec == null ? accelerator.getDisplayText() : io.agenticdb.ui.Shortcuts.of(spec);
    }

    /** 把本动作的快捷键追加到提示文案后面：「保存」→「保存 (Ctrl+S)」。 */
    public String hintWithShortcut(String hint) {
        String shortcut = shortcutText();
        return shortcut == null ? hint : hint + " (" + shortcut + ")";
    }

    /** 把 KeyCombination 拆回 Shortcuts 认识的语义串；拆不动（非常规组合）返回 null。 */
    private String semanticSpec() {
        if (!(accelerator instanceof KeyCodeCombination byCode)) {
            return null;
        }
        KeyCombination.ModifierValue down = KeyCombination.ModifierValue.DOWN;
        if (accelerator.getMeta() == down) {
            // Meta（Win 键）不在语义表里，硬翻会把「Win+E」写成别的键 —— 退回 FX 自己的读法。
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (accelerator.getShortcut() == down) {
            sb.append("Mod+");
        }
        if (accelerator.getControl() == down) {
            sb.append("Ctrl+");
        }
        if (accelerator.getAlt() == down) {
            sb.append("Alt+");
        }
        if (accelerator.getShift() == down) {
            sb.append("Shift+");
        }
        return sb.append(byCode.getCode().getName()).toString();
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
