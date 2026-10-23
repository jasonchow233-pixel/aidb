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
        // ⭐ P7：崩溃日志兜底（写 ~/.agenticdb/logs/crash-*.log）。
        io.agenticdb.ui.CrashLog.install();
        SettingsStore settings = new SettingsStore();
        ThemeManager themeManager = new ThemeManager(settings);

        shell = new AppShell(settings, themeManager);
        Scene scene = new Scene(shell, 1280, 800);

        themeManager.attach(scene);
        // ADB_THEME_FORCE / -Dadb.theme.force 只改本次外观、不写入偏好（验收截图用）。
        String forcedTheme = System.getProperty("adb.theme.force",
                System.getenv().getOrDefault("ADB_THEME_FORCE", "")).trim();
        boolean forced = !forcedTheme.isEmpty();
        themeManager.apply(forced
                ? ThemeMode.of(forcedTheme)
                : ThemeMode.of(settings.get("ui.theme", "system")), !forced);

        stage.setTitle(APP_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        // ⭐ 应用图标（标签栏 / 窗口标题栏 / 程序坞）。
        //    品牌图标由 tools/GenLogo.java 生成，从 1024 到 16 多尺寸；
        //    macOS/Windows 会在不同位置用不同分辨率，全部注册以避免糊边。
        for (String icon : new String[]{
                "app-icon-1024.png", "app-icon-512.png", "app-icon.png",
                "app-icon-256.png", "app-icon-128.png", "app-icon-64.png"}) {
            java.net.URL url = App.class.getResource("/io/agenticdb/ui/" + icon);
            if (url != null) {
                stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
            }
        }
        stage.setScene(scene);
        shell.restoreWindow(stage);
        stage.show();

        shell.afterShown(stage);

        // 验收截图模式：设置了 ADB_SHOTS 时截一张图后自动退出，平时零开销。
        ScreenshotSession.maybeRun(stage);
    }

    @Override
    public void stop() {
        if (shell != null) {
            shell.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
