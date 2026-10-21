package io.agenticdb.ui.shell;

import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.Parent;

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
        apply(next, true);
    }

    /**
     * {@code persist=false} 只改运行时外观、不写入设置 —— 供验收截图
     * 等一次性场景使用，避免把测试用的主题覆盖进用户偏好。
     */
    public void apply(ThemeMode next, boolean persist) {
        this.mode = next;
        if (persist) {
            settings.set("ui.theme", next.id());
        }
        if (scene == null) {
            return;
        }

        // ⭐ 4.5-23：两套主题样式表<b>一直都在</b>，切换主题只改根节点的 style class。
        //    以前是 scene.getStylesheets().setAll(...) 换掉整份样式表 ——
        //    那会让 JavaFX 对整棵场景图重做 CSS 计算，紧随其后的 scene.snapshot()
        //    会拿到「样式表已换、像素还没重画」的空白帧（真实窗口复现过：
        //    中央密度 0.000、109 色）。改成类选择器后不再换样式表，问题从根上消失。
        URL dark = resource("/io/agenticdb/ui/theme-dark.css");
        URL light = resource("/io/agenticdb/ui/theme-light.css");
        URL app = resource("/io/agenticdb/ui/app.css");
        if (!scene.getStylesheets().contains(dark.toExternalForm())) {
            scene.getStylesheets().setAll(dark.toExternalForm(), light.toExternalForm(),
                    app.toExternalForm());
        }

        Node root = scene.getRoot();
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(isDark() ? "theme-dark" : "theme-light");

        // 强制同步一次 CSS 与布局，别让快照拿到「样式已换、像素还没重画」的帧。
        root.applyCss();
        if (root instanceof Parent parent) {
            parent.layout();
        }
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
