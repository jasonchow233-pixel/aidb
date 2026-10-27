package io.agenticdb.ui.shell;

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

    /** 已清过的节点（按节点名）：一次性布尔会让第二个节点永远清不掉，行为门禁也测不了。 */
    private static final java.util.Set<String> CLEARED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Preferences prefs = openNode();

    public String get(String key, String fallback) {
        return prefs.get(key, fallback);
    }

    public void set(String key, String value) {
        prefs.put(key, value);
    }

    /** 删掉一个键（`put(key, null)` 会 NPE —— java.util.prefs 的 remove 才是删除）。 */
    public void remove(String key) {
        prefs.remove(key);
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

    /**
     * 本项目的偏好节点。
     *
     * <p>⭐ 包内可见是给 {@code DialogStyles} 用的：弹窗尺寸记忆必须和窗口几何
     * 走**同一个**节点，否则截图脚本用 {@code adb.prefs.node} 隔离偏好时，
     * 弹窗尺寸会漏到真实用户偏好里（同一份脚本拍出两种尺寸）。
     */
    static Preferences openNode() {
        String override = System.getProperty("adb.prefs.node",
                System.getenv().getOrDefault("ADB_PREFS_NODE", "")).trim();
        Preferences node = override.isEmpty()
                ? Preferences.userNodeForPackage(SettingsStore.class)
                : Preferences.userRoot().node(override.replace('.', '/'));
        String resetRaw = System.getProperty("adb.prefs.reset",
                System.getenv().getOrDefault("ADB_PREFS_RESET", "false")).trim();
        if (resetRequested(resetRaw) && !override.isEmpty() && CLEARED.add(override)) {
            try {
                node.clear();
                node.flush();
            } catch (BackingStoreException e) {
                System.err.println("[adb] 清理截图偏好失败: " + e.getMessage());
            }
        }
        return node;
    }

    /**
     * ⭐ U29（2026-10-26）：reset 的取值口径必须认 {@code 1}。
     *
     * <p>{@code Boolean.parseBoolean("1")} 是 <b>false</b> —— 而 shots.sh 从 UX-0 起
     * 一直导出 {@code ADB_PREFS_RESET=1}，于是「每次启动清空截图偏好」从未发生过：
     * 弹窗尺寸棘轮（U28）的脏值在截图节点里一路攒大，同一份脚本拍出两种尺寸。
     * 门禁只查过脚本里有没有那行 export（文本），没查过它真的会清（行为）——
     * 哑门禁就是这么漏的。
     */
    static boolean resetRequested(String raw) {
        return "1".equals(raw) || Boolean.parseBoolean(raw);
    }
}
