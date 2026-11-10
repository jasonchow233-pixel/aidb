package io.agenticdb.ui;

import io.agenticdb.ui.shell.AppShell;
import io.agenticdb.ui.shell.ScreenshotSession;
import io.agenticdb.ui.shell.SettingsStore;
import io.agenticdb.ui.shell.ThemeManager;
import io.agenticdb.ui.shell.ThemeMode;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * AIDB（AgenticDB）桌面入口。
 *
 * <p>P0：原生窗口 + 六区外壳（菜单/工具栏/侧栏/标签栏/内容区/状态栏）。
 * 不启动任何 HTTP 端口，不依赖浏览器。
 */
public class App extends Application {

    static final String APP_TITLE = "AIDB";

    /**
     * 验收帧的固定场景尺寸。⚠️ 不只是"初始大小"：窗口一旦被最大化再取消，
     * JavaFX 还原的是**外框**（实测场景缩成 1264×761，差一圈边框 + 标题栏），
     * 所以 {@link io.agenticdb.ui.shell.ScreenshotSession} 取消最大化之后要按这两个值把场景拉回来。
     */
    public static final double SHOT_SCENE_WIDTH = 1280;
    public static final double SHOT_SCENE_HEIGHT = 800;

    private AppShell shell;
    /** ⭐ Q4 后半：所有窗口各自一套 AppShell / 会话 / 线程池，退出时逐个释放。 */
    private final List<AppShell> shells = new ArrayList<>();
    private Stage primaryStage;

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
        shell.setOnNewWindow(this::openWindow);
        shells.add(shell);
        primaryStage = stage;
        Scene scene = new Scene(shell, SHOT_SCENE_WIDTH, SHOT_SCENE_HEIGHT);
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
        applyIcons(stage);
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

        // ⭐ R61-a：冷启动基准的出口。`scripts/startup-bench.sh` 要的是「量完就走」——
        //    它读 stdout 这一行，不去翻用户日志（那里是共享状态，多实例会互相看花）。
        //    只认显式开关：平时启动完全不进这条，零影响。
        String exitAfterFirstScreen = System.getProperty("adb.startup.exit",
                System.getenv().getOrDefault("ADB_STARTUP_EXIT", "")).trim();
        if (!exitAfterFirstScreen.isEmpty() && !exitAfterFirstScreen.equals("0")) {
            // ASCII：主源码里的字符串字面量，写中文直接顶穿 I18nAuditTest 的棘轮。
            System.out.println("[startup] " + startup.summary());
            javafx.application.Platform.exit();
        }

        // 验收截图模式：设置了 ADB_SHOTS 时截一张图后自动退出，平时零开销。
        ScreenshotSession.maybeRun(stage);
    }

    @Override
    public void stop() {
        for (AppShell open : List.copyOf(shells)) {
            closeShell(open);
        }
        shells.clear();
    }

    /** 关窗口与退出应用可能都走到 close()，用身份集合去重，别把同一组会话关两次。 */
    private final java.util.Set<AppShell> closedShells = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());

    private void closeShell(AppShell target) {
        if (closedShells.add(target)) {
            target.close();
        }
    }

    /**
     * 主窗口与后续窗口共用同一套图标清单（AppIconResourceAuditTest 守着文件名）。
     *
     * <p>⭐ 注意：这里必须写成 {@code stage.getIcons().add(...)} 这个字面量形态。
     * 抽成方法时曾经把形参改名成 {@code target}，结果 {@code BrandMarkAuditTest}
     * 那条「品牌图标必须真的注册进窗口」的源码审计门禁就找不到这行字了 ——
     * 门禁靠源码字面量守「图标真的进了窗口」，抽方法时把字面量一起抽掉了。
     */
    private void applyIcons(Stage stage) {
        for (String icon : new String[]{
                "app-icon-1024.png", "app-icon.png",
                "app-icon-256.png", "app-icon-128.png", "app-icon-64.png"}) {
            java.net.URL url = App.class.getResource("/io/agenticdb/ui/" + icon);
            if (url != null) {
                stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
            }
        }
    }

    /**
     * ⭐ Q4 后半：再开一个独立窗口。
     *
     * <p>不是把同一个 AppShell 塞进第二个 Scene（JavaFX 一个 Node 只能有一个父节点），
     * 而是整套新实例：SettingsStore / ThemeManager / AppShell / StartupTrace 各一份。
     * 连接档案是磁盘上的共享只读配置，但<b>会话、线程池、页签、窗口标题都在各自外壳里</b> ——
     * 关掉一个窗口不会顺手断掉另一个窗口的连接。
     */
    private void openWindow() {
        SettingsStore windowSettings = new SettingsStore();
        ThemeManager windowTheme = new ThemeManager(windowSettings);
        AppShell windowShell = new AppShell(windowSettings, windowTheme,
                new io.agenticdb.ui.StartupTrace());
        windowShell.setOnNewWindow(this::openWindow);
        shells.add(windowShell);

        Scene scene = new Scene(windowShell, SHOT_SCENE_WIDTH, SHOT_SCENE_HEIGHT);
        windowTheme.attach(scene);
        windowTheme.apply(ThemeMode.of(windowSettings.get("ui.theme", "system")), false);

        Stage stage = new Stage();
        stage.setTitle(APP_TITLE);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        applyIcons(stage);
        stage.setScene(scene);
        windowShell.restoreWindow(stage);
        // 错开一点，别让新窗口严丝合缝盖住主窗口 —— 多窗口要一眼看得出是两个。
        if (primaryStage != null) {
            stage.setX(primaryStage.getX() + 32);
            stage.setY(primaryStage.getY() + 32);
        }
        stage.setOnHidden(event -> {
            shells.remove(windowShell);
            closeShell(windowShell);
        });
        stage.show();
        windowShell.afterShown(stage);
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
