package io.agenticdb.ui.shell;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 轻量设置存储（P0 用 java.util.prefs）。
 *
 * <p>P5 会换成 {@code ~/.agenticdb/config.db}；届时只替换实现，
 * 调用方（ThemeManager / AppShell）不改。
 *
 * <p>⭐ UX-0：截图与测试可通过 {@code ADB_PREFS_NODE} 指定独立偏好根，
 * 并用 {@code ADB_PREFS_RESET=1} 在首次构造时清空它 —— 否则真实用户偏好
 * （密度 / 侧栏宽度 / 上次连接）会让同一份截图脚本拍出不同画面。
 * reset 只在显式指定 node 时生效，避免误清默认偏好。
 */
public final class SettingsStore {

    private static final AtomicBoolean RESET_DONE = new AtomicBoolean();

    private final Preferences prefs = openNode();

    public String get(String key, String fallback) {
        return prefs.get(key, fallback);
    }

    public void set(String key, String value) {
        prefs.put(key, value);
    }

    public int getInt(String key, int fallback) {
        return prefs.getInt(key, fallback);
    }

    public void setInt(String key, int value) {
        prefs.putInt(key, value);
    }

    public boolean getBoolean(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    public void setBoolean(String key, boolean value) {
        prefs.putBoolean(key, value);
    }

    private static Preferences openNode() {
        String override = System.getProperty("adb.prefs.node",
                System.getenv().getOrDefault("ADB_PREFS_NODE", "")).trim();
        Preferences node = override.isEmpty()
                ? Preferences.userNodeForPackage(SettingsStore.class)
                : Preferences.userRoot().node(override.replace('.', '/'));
        boolean reset = Boolean.parseBoolean(System.getProperty("adb.prefs.reset",
                System.getenv().getOrDefault("ADB_PREFS_RESET", "false")).trim());
        if (reset && !override.isEmpty() && RESET_DONE.compareAndSet(false, true)) {
            try {
                node.clear();
            } catch (BackingStoreException e) {
                System.err.println("[adb] 清理截图偏好失败: " + e.getMessage());
            }
        }
        return node;
    }
}
