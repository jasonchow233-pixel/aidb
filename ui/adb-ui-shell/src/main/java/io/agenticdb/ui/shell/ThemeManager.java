package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.Node;

import java.net.URL;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 主题切换：主题 CSS 来自 ui-core，业务 CSS 来自 ui-shell。 */
public final class ThemeManager {

    private final SettingsStore settings;
    private Scene scene;
    private ThemeMode mode = ThemeMode.SYSTEM;

    public ThemeManager(SettingsStore settings) {
        this.settings = settings;
    }

    public void attach(Scene scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    public ThemeMode mode() {
        return mode;
    }

    public boolean isDark() {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemPrefersDark();
        };
    }

    public void apply(ThemeMode next) {
        this.mode = next;
        settings.set("ui.theme", next.id());
        if (scene == null) {
            return;
        }

        URL theme = resource(isDark() ? "/io/agenticdb/ui/theme-dark.css" : "/io/agenticdb/ui/theme-light.css");
        URL app = resource("/io/agenticdb/ui/app.css");
        scene.getStylesheets().setAll(theme.toExternalForm(), app.toExternalForm());

        Node root = scene.getRoot();
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(isDark() ? "theme-dark" : "theme-light");
    }

    public void toggle() {
        apply(mode.next());
    }

    private URL resource(String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("缺少样式资源: " + path);
        }
        return url;
    }

    /** macOS 深色模式探测；其他平台先返回 false（亮色是评审基准）。 */
    private static boolean systemPrefersDark() {
        String override = System.getenv("ADB_THEME");
        if (override != null) {
            String value = override.toLowerCase(Locale.ROOT);
            if (value.equals("dark")) {
                return true;
            }
            if (value.equals("light")) {
                return false;
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(400, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return output.equalsIgnoreCase("Dark");
        } catch (Exception ignored) {
            return false;
        }
    }
}
