package io.agenticdb.ui;

import io.agenticdb.ui.shell.AppShell;
import io.agenticdb.ui.shell.SettingsStore;
import io.agenticdb.ui.shell.ThemeManager;
import io.agenticdb.ui.shell.ThemeMode;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * AgenticDB 桌面入口。
 *
 * <p>P0：原生窗口 + 六区外壳（菜单/工具栏/侧栏/标签栏/内容区/状态栏）。
 * 不启动任何 HTTP 端口，不依赖浏览器。
 */
public class App extends Application {

    static final String APP_TITLE = "AgenticDB";

    private AppShell shell;

    @Override
    public void start(Stage stage) {
        SettingsStore settings = new SettingsStore();
        ThemeManager themeManager = new ThemeManager(settings);

        shell = new AppShell(settings, themeManager);
        Scene scene = new Scene(shell, 1280, 800);

        themeManager.attach(scene);
        themeManager.apply(ThemeMode.of(settings.get("ui.theme", "system")));

        stage.setTitle(APP_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        shell.restoreWindow(stage);
        stage.show();

        shell.afterShown(stage);
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
