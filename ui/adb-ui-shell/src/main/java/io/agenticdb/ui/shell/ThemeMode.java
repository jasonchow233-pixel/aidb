package io.agenticdb.ui.shell;

import io.agenticdb.ui.I18n;
import java.util.Locale;

public enum ThemeMode {
    SYSTEM("system", "theme.mode.system"),
    LIGHT("light", "theme.mode.light"),
    DARK("dark", "theme.mode.dark");

    private final String id;
    private final String labelKey;

    ThemeMode(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
    }

    public String id() {
        return id;
    }

    public String label() {
        return I18n.t(labelKey);
    }

    public static ThemeMode of(String id) {
        if (id == null) {
            return SYSTEM;
        }
        for (ThemeMode mode : values()) {
            if (mode.id.equals(id.toLowerCase(Locale.ROOT))) {
                return mode;
            }
        }
        return SYSTEM;
    }

    public ThemeMode next() {
        return switch (this) {
            case SYSTEM -> LIGHT;
            case LIGHT -> DARK;
            case DARK -> SYSTEM;
        };
    }
}
