package io.agenticdb.ui.shell;

import java.util.Locale;

public enum ThemeMode {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "亮色"),
    DARK("dark", "暗色");

    private final String id;
    private final String label;

    ThemeMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
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
