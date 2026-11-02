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

    /**
     * ⭐ D-18：偏好键的长度上限。
     *
     * <p>{@code java.util.prefs} 对**单个键**只放行 80 个字符，第 81 个直接抛
     * {@code IllegalArgumentException: Key too long}（2026-11-02 用临时 userRoot
     * 逐长度试过：长度 80 写进得去，81 抛）。这个数字不写在别处 —— 改了它，
     * {@link #key} 的行为门禁会红。
     */
    private static final int MAX_KEY_LENGTH = 80;

    /** 摘要部分的十六进制长度（48 bit，个人偏好里不会有碰撞）。 */
    private static final int DIGEST_HEX_LENGTH = 12;

    /**
     * ⭐ D-18：拼一个**一定写得下**的偏好键。
     *
     * <p>列宽/列序这类偏好键里塞过连接名、表名、列名，而连接名是自由文本 ——
     * SQLite 连接的显示名就是整条文件路径。原先这种键超限就抛，抛在拖拽/防抖
     * 回调里，崩机处理器只在日志留一行：**长名字连接的列宽记忆静默失效**，
     * 界面上和「没存过」长得一模一样。
     *
     * <p>超上限时保留可读前缀（人还要能在偏好文件里认出这是哪一类），
     * 把可变部分换成稳定摘要。短键的形状**完全不变** —— 已经存好的偏好一个都不作废。
     */
    public static String key(String prefix, String... parts) {
        StringBuilder joined = new StringBuilder(prefix);
        for (String part : parts) {
            joined.append('.').append(part == null ? "" : part);
        }
        if (joined.length() <= MAX_KEY_LENGTH) {
            return joined.toString();
        }
        return prefix + ".h" + digest(joined.toString());
    }

    private static String digest(String value) {
        byte[] hash;
        try {
            hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException absent) {
            // JDK 规范要求 SHA-256 必须在 —— 真没有时不该静默换一种摘要：
            // 换摘要等于把用户已经存下的列宽全变成另一批键。
            throw new IllegalStateException(absent);
        }
        StringBuilder hex = new StringBuilder(DIGEST_HEX_LENGTH);
        for (int i = 0; i < DIGEST_HEX_LENGTH / 2; i++) {
            hex.append(String.format("%02x", hash[i]));
        }
        return hex.toString();
    }

    /** 已清过的节点（按节点名）：一次性布尔会让第二个节点永远清不掉，行为门禁也测不了。 */
    private static final java.util.Set<String> CLEARED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final Preferences prefs = openNode();

    public String get(String key, String fallback) {
        return prefs.get(key, fallback);
    }

    public void set(String key, String value) {
        write(preferences -> preferences.put(key, value));
    }

    /** 删掉一个键（`put(key, null)` 会 NPE —— java.util.prefs 的 remove 才是删除）。 */
    public void remove(String key) {
        write(preferences -> preferences.remove(key));
    }

    public int getInt(String key, int fallback) {
        return prefs.getInt(key, fallback);
    }

    public void setInt(String key, int value) {
        write(preferences -> preferences.putInt(key, value));
    }

    public boolean getBoolean(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    public void setBoolean(String key, boolean value) {
        write(preferences -> preferences.putBoolean(key, value));
    }

    /**
     * 写入偏好；节点已经被外部删掉时**丢弃**这次写入。
     *
     * <p>⭐ 2026-10-31：{@code DataGridPage} 的列宽落盘是 400ms 防抖 ——
     * 回调在 FX 时钟上跑，回来时存储可能已经不在了（测试隔离扩展每个用例
     * 结束都会 {@code removeNode()}）。{@code AbstractPreferences.put}
     * 此时抛 {@code IllegalStateException: Node has been removed}，
     * 它发生在脉冲里，TestFX 会把它记成「延迟异常」并算到**后面**的用例头上：
     * 一条 400ms 前的写盘能把整批网格用例连带打红。
     * 存储不存在时这次写本来也无处可去，丢掉它才是这个边界上唯一说得通的行为。
     */
    private void write(java.util.function.Consumer<Preferences> action) {
        try {
            action.accept(prefs);
        } catch (IllegalStateException removed) {
            // 只吞「节点没了」这一种：java.util.prefs 的写操作只有它会抛 ISE。
        }
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
                System.err.println("[adb] failed to clear screenshot prefs: " + e.getMessage());
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
