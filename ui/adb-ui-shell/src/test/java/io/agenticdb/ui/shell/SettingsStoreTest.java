package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U29（2026-10-26）：偏好隔离的**行为**门禁。
 *
 * <p>UX-0 起 shots.sh 就导出 {@code ADB_PREFS_RESET=1}，但实现用的是
 * {@code Boolean.parseBoolean} —— 它只认 "true"，"1" 一律是 false。
 * 「每次启动清空截图偏好」从未发生过：U28 的棘轮脏值在截图节点里越攒越大，
 * 同一份脚本拍出两种尺寸的弹窗。当时的门禁只查了脚本里有没有那行 export
 * （{@code ScreenshotScriptAuditTest}），没查它真的会清 —— 文本绿、行为哑。
 *
 * <p>这里两条各钉一半：取值口径认 "1"，以及**真的清得掉**（按节点名隔离，
 * 每次用唯一节点，不碰真实用户偏好）。
 */
class SettingsStoreTest {

    @Test
    void resetAcceptsTheValueTheShotScriptActuallyExports() {
        assertThat(SettingsStore.resetRequested("1"))
                .as("shots.sh 导出的就是 1 —— 不认它，整条隔离都是哑门禁")
                .isTrue();
        assertThat(SettingsStore.resetRequested("true")).isTrue();
        assertThat(SettingsStore.resetRequested("TRUE")).isTrue();
        assertThat(SettingsStore.resetRequested("false"))
                .as("默认值必须仍然是不清")
                .isFalse();
        assertThat(SettingsStore.resetRequested("")).isFalse();
        assertThat(SettingsStore.resetRequested("0")).isFalse();
    }

    @Test
    void aResetRunReallyClearsTheNode() throws BackingStoreException {
        // 唯一节点名：CLEARED 按节点记账，别的测试先跑过也不影响这里。
        String node = "adb-reset-gate-" + System.nanoTime();
        Preferences direct = Preferences.userRoot().node(node);
        direct.putInt("dialog.size.v2.width.index", 912);
        direct.flush();
        System.setProperty("adb.prefs.node", node);
        System.setProperty("adb.prefs.reset", "1");
        try {
            Preferences opened = SettingsStore.openNode();
            assertThat(opened.getInt("dialog.size.v2.width.index", -1))
                    .as("reset=1 的首次 openNode 必须把节点清干净 —— 弹窗尺寸记忆不许串到下一帧")
                    .isEqualTo(-1);
        } finally {
            direct.clear();
            direct.flush();
            System.clearProperty("adb.prefs.node");
            System.clearProperty("adb.prefs.reset");
        }
    }

    @Test
    void withoutAnExplicitNodeNothingIsCleared() throws BackingStoreException {
        // 真实用户偏好（未显式给节点名）哪怕带着 reset=1 也不许被清 —— UX-0 的原约束。
        Preferences userNode = Preferences.userNodeForPackage(SettingsStore.class);
        String key = "reset-gate-" + System.nanoTime();
        userNode.put(key, "keep");
        System.setProperty("adb.prefs.reset", "1");
        try {
            SettingsStore.openNode();
            assertThat(userNode.get(key, null))
                    .as("没给 ADB_PREFS_NODE 时 reset 一律不生效，不许误清真实偏好")
                    .isEqualTo("keep");
        } finally {
            userNode.remove(key);
            System.clearProperty("adb.prefs.reset");
        }
    }
}
