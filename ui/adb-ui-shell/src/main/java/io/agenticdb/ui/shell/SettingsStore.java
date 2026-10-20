package io.agenticdb.ui.shell;

import java.util.prefs.Preferences;

/**
 * 轻量设置存储（P0 用 java.util.prefs）。
 *
 * <p>P5 会换成 {@code ~/.agenticdb/config.db}；届时只替换实现，
 * 调用方（ThemeManager / AppShell）不改。
 */
public final class SettingsStore {

    private final Preferences prefs = Preferences.userNodeForPackage(SettingsStore.class);

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
}
