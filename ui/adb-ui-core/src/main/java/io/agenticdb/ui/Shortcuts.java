package io.agenticdb.ui;

import java.util.Locale;

/**
 * 快捷键文案的平台化（U1，2026-10-26）。
 *
 * <p>问题：全仓 28 处用户可见文案把快捷键**手写成了 macOS 符号**
 * （{@code ⌘K}、{@code ⇧⌘N}、{@code ⌥⇧F}）。Windows 用户看到 ⌘ 只会愣住 ——
 * 键盘上没有这个键，而实拍截图全部来自 Windows。
 *
 * <p>口径：源码里只写**语义**（{@code "Mod+K"}），渲染交给这里。
 * {@code Mod} 是平台主修饰键 —— macOS 上是 ⌘，其余平台是 Ctrl。
 *
 * <p>⭐ H5-g：{@code Shortcut} 与 {@code Mod} 同义，因为注册表侧必须写
 * {@code Shortcut} —— {@code KeyCombination.keyCombination("Mod+N")} 会抛
 * {@code Cannot parse key binding}，而 {@code F1} 表现在读的是
 * {@code action.accelerator().toString()}（JavaFX 的规范写法就是 {@code Shortcut}）。
 * 两个词在这里合流，界面上才只有一种读法。
 *
 * <pre>
 *   Shortcuts.of("Mod+K")         → Windows "Ctrl+K"      macOS "⌘K"
 *   Shortcuts.of("Shift+Mod+N")   → Windows "Ctrl+Shift+N" macOS "⇧⌘N"
 *   Shortcuts.of("Alt+Shift+F")   → Windows "Alt+Shift+F"  macOS "⌥⇧F"
 *   Shortcuts.of("Mod+Enter")     → Windows "Ctrl+Enter"   macOS "⌘↵"
 * </pre>
 *
 * <p>排序按各家惯例：macOS 是 ⌃⌥⇧⌘+键（无分隔符），Windows 是
 * Ctrl→Alt→Shift→键（{@code +} 分隔）。所以源码里的书写顺序只是**集合**，
 * 不代表显示顺序。
 */
public final class Shortcuts {

    /** 平台主修饰键的语义名，源码里一律写它，不要写 Ctrl / ⌘。 */
    public static final String MOD = "Mod";

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private Shortcuts() {
    }

    /** 当前平台是否 macOS（决定符号与分隔符口径）。 */
    public static boolean mac() {
        return MAC;
    }

    /**
     * 把语义快捷键渲染成当前平台的读法。
     *
     * @param spec {@code +} 分隔的按键，如 {@code "Shift+Mod+N"}；大小写不敏感
     */
    public static String of(String spec) {
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        boolean cmd = false;
        String key = null;
        for (String raw : spec.split("\\+")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            switch (token.toUpperCase(Locale.ROOT)) {
                case "MOD", "SHORTCUT" -> {
                    if (MAC) {
                        cmd = true;
                    } else {
                        ctrl = true;
                    }
                }
                case "CMD", "COMMAND" -> cmd = true;
                case "CTRL", "CONTROL" -> ctrl = true;
                case "ALT", "OPTION" -> alt = true;
                case "SHIFT" -> shift = true;
                case "ENTER", "RETURN" -> key = MAC ? "↵" : "Enter";
                case "SPACE" -> key = MAC ? "␣" : "Space";
                case "BACKSLASH" -> key = "\\";
                case "PERIOD" -> key = ".";
                case "COMMA" -> key = ",";
                case "SLASH" -> key = "/";
                case "SEMICOLON" -> key = ";";
                case "MINUS" -> key = "-";
                case "EQUALS" -> key = "=";
                case "UP" -> key = "↑";
                case "DOWN" -> key = "↓";
                case "LEFT" -> key = "←";
                case "RIGHT" -> key = "→";
                case "ESCAPE" -> key = "Esc";
                case "BACKSPACE" -> key = MAC ? "⌫" : "Backspace";
                case "DELETE" -> key = MAC ? "⌦" : "Del";
                case "PAGE_UP" -> key = "PgUp";
                case "PAGE_DOWN" -> key = "PgDn";
                case "HOME" -> key = "Home";
                case "END" -> key = "End";
                case "TAB" -> key = "Tab";
                default -> key = token.length() == 1
                        ? token.toUpperCase(Locale.ROOT) : token;
            }
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (MAC) {
            // Apple 的固定顺序是 ⌃⌥⇧⌘，且按键紧跟其后、不加分隔符。
            if (ctrl) {
                parts.add("⌃");
            }
            if (alt) {
                parts.add("⌥");
            }
            if (shift) {
                parts.add("⇧");
            }
            if (cmd) {
                parts.add("⌘");
            }
            if (key != null) {
                parts.add(key);
            }
            return String.join("", parts);
        }
        // Windows / Linux：Ctrl → Alt → Shift → 按键，用 + 连接。
        if (ctrl) {
            parts.add("Ctrl");
        }
        if (alt) {
            parts.add("Alt");
        }
        if (shift) {
            parts.add("Shift");
        }
        if (cmd) {
            parts.add("Win");
        }
        if (key != null) {
            parts.add(key);
        }
        return String.join("+", parts);
    }

    /** 括号里的读法，如 {@code "新建查询 (Ctrl+Shift+N)"}；空 spec 返回原文案。 */
    public static String labelled(String text, String spec) {
        return spec == null || spec.isBlank() ? text : text + " (" + of(spec) + ")";
    }
}
