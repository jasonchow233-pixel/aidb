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
    /** ⭐ P7 门禁读数：真的做过「强制 CSS + 布局」的次数。 */
    private int forcedPasses;

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
        // ⭐ P7 启动性能：窗口还没显示时**不做**这次强制 pass —— stage.show() 本来
        //    就会跑一次，提前跑等于把整棵场景图的 CSS/布局算两遍（实测 706ms）。
        //    主题切换（窗口已显示）仍然强制，截图确定性不受影响。
        if (shouldForcePass(scene.getWindow())) {
            forcedPasses++;
            root.applyCss();
            if (root instanceof Parent parent) {
                parent.layout();
            }
        }
    }

    /** 只有「窗口已经显示」才需要强制 CSS/布局；启动时交给 {@code stage.show()}。 */
    static boolean shouldForcePass(javafx.stage.Window window) {
        return window != null && window.isShowing();
    }

    /** 供门禁读数：做过几次强制 CSS + 布局。 */
    int forcedPassesForTest() {
        return forcedPasses;
    }

    public void toggle() {
        apply(mode.next());
    }

    private URL resource(String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url == null) {
            throw new IllegalStateException("missing stylesheet resource: " + path);
        }
        return url;
    }

    /** macOS 深色模式探测；其他平台先返回 false（亮色是评审基准）。 */
    /**
     * ⭐ A22：系统主题探测的结果**缓存起来**。
     *
     * <p>以前每次应用主题都要 shell out 一次（macOS 的 {@code defaults} 有 400ms 超时）——
     * 启动、切主题、换标签都会白等一次。
     * 现在只在**第一次**探测，之后一直用缓存（用户中途改系统主题时需要重启应用，
     * 这是可接受的取舍 —— 换来的是启动不再卡 400ms）。
     */
    private static Boolean cachedSystemDark;

    /**
     * ⭐ A18：跨平台探测系统是否偏好暗色。
     *
     * <ul>
     *   <li><b>macOS</b>：{@code defaults read -g AppleInterfaceStyle}；</li>
     *   <li><b>Windows</b>：注册表 {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize}
     *       的 {@code AppsUseLightTheme}（0 = 暗色）；</li>
     *   <li><b>Linux</b>：{@code gsettings get org.gnome.desktop.interface color-scheme}
     *       （{@code prefer-dark} = 暗色）；KDE 走 {@code kdeglobals} 里的配置。</li>
     * </ul>
     * 探测不到就按亮色（保守：亮色在所有平台都可读）。
     */
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
        // ⭐ A22：缓存（探测一次就够）。
        if (cachedSystemDark != null) {
            return cachedSystemDark;
        }
        boolean dark = detectSystemDark();
        cachedSystemDark = dark;
        return dark;
    }

    private static boolean detectSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return detectMacDark();
        }
        if (os.contains("win")) {
            return detectWindowsDark();
        }
        if (os.contains("linux") || os.contains("nix") || os.contains("nux")) {
            return detectLinuxDark();
        }
        return false;
    }

    private static boolean detectMacDark() {
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

    /** Windows：AppsUseLightTheme = 0 表示暗色。 */
    private static boolean detectWindowsDark() {
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(400, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            // 输出形如：AppsUseLightTheme    REG_DWORD    0x0
            int index = output.lastIndexOf("0x");
            if (index < 0) {
                return false;
            }
            return output.substring(index + 2).trim().equals("0");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Linux：GNOME 的 color-scheme，或 KDE 的 kdeglobals。 */
    private static boolean detectLinuxDark() {
        String gnome = runAndRead("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
        if (gnome != null) {
            return gnome.toLowerCase(Locale.ROOT).contains("dark");
        }
        String kde = runAndRead("kreadconfig5", "--file", "kdeglobals", "--group", "KDE",
                "--key", "ColorScheme");
        if (kde != null) {
            return kde.toLowerCase(Locale.ROOT).contains("dark");
        }
        return false;
    }

    /** 跑一条命令并读它的输出；超时 / 失败 / 无输出 → null。 */
    private static String runAndRead(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(400, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return output.isEmpty() ? null : output;
        } catch (Exception ignored) {
            return null;
        }
    }
}
