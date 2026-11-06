package io.agenticdb.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快捷键语义渲染（U1）。
 *
 * <p>符号按平台分叉，CI 只跑在一个平台上，所以这里查的是**不随平台变**的三件事：
 * 书写顺序不代表显示顺序、修饰键集合等价、以及主修饰键始终只有一个来源。
 */
@DisplayName("Shortcuts：语义 spec → 平台读法")
class ShortcutsTest {

    @Test
    @DisplayName("书写顺序不影响结果：Shift+Mod+Z 与 Mod+Shift+Z 同一读法")
    void modifierOrderIsCanonical() {
        assertThat(Shortcuts.of("Shift+Mod+Z")).isEqualTo(Shortcuts.of("Mod+Shift+Z"));
        assertThat(Shortcuts.of(" Mod + Z ")).isEqualTo(Shortcuts.of("Mod+Z"));
    }

    @Test
    @DisplayName("主修饰键 Mod 永远只产出一个：macOS ⌘，其它平台 Ctrl")
    void modMapsToPlatformPrimary() {
        String text = Shortcuts.of("Mod+K");
        if (Shortcuts.mac()) {
            assertThat(text).isEqualTo("⌘K");
        } else {
            assertThat(text).isEqualTo("Ctrl+K");
        }
    }

    @Test
    @DisplayName("符号只出现在 mac 分支：Windows 读法全是 ASCII 键名")
    void nonMacLabelsHaveNoAppleGlyphs() {
        if (Shortcuts.mac()) {
            return;
        }
        for (String spec : new String[]{"Mod+K", "Shift+Mod+Z", "Alt+Shift+F",
                "Mod+Alt+D", "Mod+Enter", "Mod+PERIOD", "Mod+BACKSLASH"}) {
            for (String glyph : new String[]{"⌘", "⌥", "⌃", "⇧", "↵"}) {
                assertThat(Shortcuts.of(spec))
                        .as(spec + " 在 Windows 上不该出现 Apple 键位符号 " + glyph)
                        .doesNotContain(glyph);
            }
        }
        assertThat(Shortcuts.of("Mod+Enter")).isEqualTo("Ctrl+Enter");
        assertThat(Shortcuts.of("Mod+PERIOD")).isEqualTo("Ctrl+.");
        assertThat(Shortcuts.of("Mod+BACKSLASH")).isEqualTo("Ctrl+\\");
        assertThat(Shortcuts.of("Alt+Shift+F")).isEqualTo("Alt+Shift+F");
    }

    @Test
    @DisplayName("注册表写法 Shortcut 与语义写法 Mod 同一读法（F1 表读的就是前者）")
    void shortcutIsTheSamePrimaryAsMod() {
        // 注册表侧只能写 Shortcut（JavaFX 不认 Mod），而 F1 表的按键列现在直接读
        // action.accelerator().toString()。改动前 "Shortcut+N" 会掉进 default 分支，
        // 修饰键被后面的按键覆盖，界面上拿到裸键 "N" —— 不报错。
        assertThat(Shortcuts.of("Shortcut+N")).isEqualTo(Shortcuts.of("Mod+N"));
        assertThat(Shortcuts.of("Shift+Shortcut+N")).isEqualTo(Shortcuts.of("Shift+Mod+N"));
        assertThat(Shortcuts.of("Shortcut+Alt+D")).isEqualTo(Shortcuts.of("Mod+Alt+D"));
        assertThat(Shortcuts.of("Shortcut+Period")).isEqualTo(Shortcuts.of("Mod+PERIOD"));
        assertThat(Shortcuts.of("Shortcut+Backslash")).isEqualTo(Shortcuts.of("Mod+BACKSLASH"));
        // 裸键不受影响：F1 / F5 本来就没有修饰键。
        assertThat(Shortcuts.of("F1")).isEqualTo("F1");
    }

    @Test
    @DisplayName("括注形态：文案 +（按键），空 spec 退化为原文案")
    void labelledAppendsParenthesis() {
        assertThat(Shortcuts.labelled("搜索", "Mod+F"))
                .isEqualTo("搜索 (" + Shortcuts.of("Mod+F") + ")");
        assertThat(Shortcuts.labelled("搜索", null)).isEqualTo("搜索");
        assertThat(Shortcuts.labelled("搜索", "  ")).isEqualTo("搜索");
    }
}
