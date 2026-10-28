package io.agenticdb.ui;

import io.agenticdb.ui.shell.AppShell;
import io.agenticdb.ui.shell.ScreenshotSession;
import io.agenticdb.ui.shell.SettingsStore;
import io.agenticdb.ui.shell.ThemeManager;
import io.agenticdb.ui.shell.ThemeMode;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * AIDB（AgenticDB）桌面入口。
 *
 * <p>P0：原生窗口 + 六区外壳（菜单/工具栏/侧栏/标签栏/内容区/状态栏）。
 * 不启动任何 HTTP 端口，不依赖浏览器。
 */
public class App extends Application {

    static final String APP_TITLE = "AIDB";

    private AppShell shell;

    @Override
    public void start(Stage stage) {
        // ⭐ P7：启动打点（首屏耗时写 ~/.agenticdb/logs/startup.log）。
        //    必须在最前面创建 —— 进程启动时间由 ProcessHandle 直接取，
        //    这里晚一点只影响「类初始化之后」的分段。
        io.agenticdb.ui.StartupTrace startup = new io.agenticdb.ui.StartupTrace();
        // ⭐ P7：崩溃日志兜底（写 ~/.agenticdb/logs/crash-*.log）。
        io.agenticdb.ui.CrashLog.install();
        startup.mark("crash-log");
        SettingsStore settings = new SettingsStore();
        ThemeManager themeManager = new ThemeManager(settings);
        startup.mark("settings");

        // ⭐ R34 N1-d：语言必须在**任何节点构造之前**定下来 —— 标签是构造时一次性取值的
        //    （JavaFX 的 setText 不是绑定），所以 configure 晚跑就等于没跑。
        //    优先级由 I18n.configure 决定：-Dadb.locale > 偏好 ui.locale > 默认英文。
        //    ⚠️ 这里只做到「启动时按偏好生效」，**不做运行时切换** —— 切换要重建整棵场景，
        //    而主题与密度都是 CSS 类切换、没有可借用的重建路径（见 AppShell.changeDensity）。
        //    运行时换语言是独立的一轮，不在视觉对齐轮里顺手塞。
        I18n.configure(toLocale(settings.get("ui.locale", "")));
        startup.mark("locale");

        shell = new AppShell(settings, themeManager, startup);
        Scene scene = new Scene(shell, 1280, 800);
        startup.mark("scene");

        themeManager.attach(scene);
        // ADB_THEME_FORCE / -Dadb.theme.force 只改本次外观、不写入偏好（验收截图用）。
        String forcedTheme = System.getProperty("adb.theme.force",
                System.getenv().getOrDefault("ADB_THEME_FORCE", "")).trim();
        boolean forced = !forcedTheme.isEmpty();
        themeManager.apply(forced
                ? ThemeMode.of(forcedTheme)
                : ThemeMode.of(settings.get("ui.theme", "system")), !forced);
        startup.mark("theme");

        stage.setTitle(APP_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        // ⭐ 应用图标（标签栏 / 窗口标题栏 / 程序坞）。
        //    品牌图标由 tools/GenLogo.java 生成，从 1024 到 16 多尺寸；
        //    macOS/Windows 会在不同位置用不同分辨率，全部注册以避免糊边。
        // ⚠️ 列表里每个名字都必须真实存在：下面 `url != null` 会把缺文件**静默跳过**，
        //    少一张图没人报错（踩过：`app-icon-512.png` 从未生成 —— 512 这一档按
        //    `brand/README.md` 约定就叫 `app-icon.png`）。AppIconResourceAuditTest 守着。
        for (String icon : new String[]{
                "app-icon-1024.png", "app-icon.png",
                "app-icon-256.png", "app-icon-128.png", "app-icon-64.png"}) {
            java.net.URL url = App.class.getResource("/io/agenticdb/ui/" + icon);
            if (url != null) {
                stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
            }
        }
        stage.setScene(scene);
        shell.restoreWindow(stage);
        stage.show();
        startup.mark("stage.shown");

        shell.afterShown(stage);
        // ⭐ 首屏 = 窗口已显示且首个页面/对象树已就位（afterShown 完成）。
        startup.firstScreen();
        // 落盘开关：测试 JVM（surefire）显式关掉，避免反复启动写满真实用户日志。
        String logging = System.getProperty("adb.startup.log",
                System.getenv().getOrDefault("ADB_STARTUP_LOG", "true")).trim();
        if (!logging.equalsIgnoreCase("false") && !logging.equals("0")) {
            startup.writeSummary();
        }

        // 验收截图模式：设置了 ADB_SHOTS 时截一张图后自动退出，平时零开销。
        ScreenshotSession.maybeRun(stage);
    }

    @Override
    public void stop() {
        if (shell != null) {
            shell.close();
        }
    }

    /**
     * 偏好字符串 → 语言。空串返回 {@code null}，让 {@link I18n#configure} 走它自己的
     * 「系统属性 &gt; 默认英文」链 —— 不在这里再复制一遍默认值。
     */
    private static java.util.Locale toLocale(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("zh")) {
            return I18n.CHINESE;
        }
        return value.isEmpty() ? null : I18n.ENGLISH;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
