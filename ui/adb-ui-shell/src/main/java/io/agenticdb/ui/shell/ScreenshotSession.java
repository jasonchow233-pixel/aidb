package io.agenticdb.ui.shell;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 验收截图会话：由环境变量驱动的单发截图工具，用于生成 docs/screenshots。
 *
 * <p>只在设置了 {@code ADB_SHOTS}（或 {@code -Dadb.shots}）时激活，平时零开销。
 * 用法（每条命令产出一张图后自动退出）：
 *
 * <pre>
 * ADB_SHOTS=docs/screenshots ADB_SHOTS_NAME=p0-shell  ./mvnw -pl bootstrap/adb-desktop javafx:run
 * ADB_SHOTS=docs/screenshots ADB_SHOTS_NAME=p2-users ADB_OPEN=users ADB_SHOTS_WAIT=4000 ./mvnw ...
 * ADB_SHOTS=docs/screenshots ADB_SHOTS_NAME=p5-connections ADB_SHOTS_CLICK=sidebar-connections ...
 * </pre>
 *
 * <p>快照走 {@link Scene#snapshot}，直接渲染场景图，不依赖屏幕录制权限；
 * 窗口是否被遮挡不影响结果。像素落盘用手写 PNG 编码，不引入 javafx.swing。
 */
public final class ScreenshotSession {

    private ScreenshotSession() {
    }

    /** App 启动完成后调用；未设置 ADB_SHOTS 时是空操作。 */
    public static void maybeRun(Stage stage) {
        String dir = prop("adb.shots", "ADB_SHOTS");
        if (dir.isEmpty()) {
            return;
        }
        String name = prop("adb.shots.name", "ADB_SHOTS_NAME");
        String clickId = prop("adb.shots.click", "ADB_SHOTS_CLICK");
        long waitMs = parseLong(prop("adb.shots.wait", "ADB_SHOTS_WAIT"), 2500);

        // ⭐ 窗口一旦被最大化，拍出来的就不是 1280×800 而是整屏（实测 1920×1017），
        //   而「1280 下不溢出」这类结论全部作废 —— 字节数看不出差别，只能比尺寸。
        //   来源不是本应用：偏好节点里没有 ui.window.* 两条（查过注册表），
        //   代码里也只有 Window 菜单那一项会 setMaximized，而截图模式不点它。
        //   是 Windows 把「同一个可执行文件上一次的窗口摆放」还给了新窗口
        //   （每张帧是一个新 JVM，但都是 java.exe）。取消最大化会退回创建时的 1280×800。
        runOnFx(() -> {
            if (stage.isMaximized()) {
                // 这条日志本身必须保持 ASCII：它是主源码里的字符串字面量，
                // 写成中文会直接顶穿 I18nAuditTest 的棘轮（踩过，1504 → 1505）。
                System.out.println("[screenshot] window came up maximized; un-maximizing");
                stage.setMaximized(false);
            }
        });

        Thread worker = new Thread(() -> {
            sleep(waitMs);
            if (!clickId.isEmpty()) {
                runOnFx(() -> click(stage, clickId));
                // 点击后的 UI 变化（开页 / 换主题）多半是同步的，这里留出余量。
                sleep(1200);
            }
            runOnFx(() -> capture(stage, Path.of(dir), name));
        }, "adb-screenshot");
        worker.setDaemon(true);
        worker.start();
    }

    private static void capture(Stage stage, Path dir, String name) {
        try {
            Scene scene = targetScene(stage);
            // 样式表切换后强制走一遍 CSS + 布局，避免快照落在重排中途。
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            System.out.println("[screenshot] theme=" + scene.getRoot().getStyleClass()
                    + " scene=" + (int) scene.getWidth() + "x" + (int) scene.getHeight()
                    + " css=" + scene.getStylesheets().size()
                    + " refreshBtn=" + (scene.getRoot().lookup("#refresh-button") != null)
                    + " toolbar=" + (scene.getRoot().lookup(".adb-toolbar") != null));
            WritableImage image = scene.snapshot(null);
            Files.createDirectories(dir);
            Path target = dir.resolve(name.toLowerCase(Locale.ROOT) + ".png");
            writePng(image, target);
            System.out.println("[screenshot] " + target.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[screenshot] 失败：" + e);
            e.printStackTrace();
        } finally {
            Platform.exit();
        }
    }

    /**
     * 有弹窗时拍弹窗，否则拍主窗口。
     *
     * <p>⭐ JavaFX 的 {@code Dialog} 是<b>独立的 Stage</b>，不是主场景的子节点 ——
     * 直接 {@code stage.getScene().snapshot()} 只会拍到主窗口，弹窗整个消失。
     * 实测踩过：拍了张「新建索引」弹窗，结果和结构页的图 MD5 完全一样。
     *
     * <p>⚠️ 优先模态，但**不能只认模态**：Alert 默认 {@code Modality.NONE}，
     * 只按模态筛会把「没设模态的弹窗」悄悄拍成主窗口（U7 的 p2-error 就是这么假的）。
     * 所以第二轮兜底认「有 owner 且正在显示」的窗口，并把选中的窗口打进日志。
     */
    private static Scene targetScene(Stage stage) {
        // ⭐ U25：ContextMenu 的浮窗不是 Stage（是 PopupWindow），主窗口的
        // scene.snapshot 拍不到它 —— 菜单帧必须直接拍浮窗自己的场景。
        for (Window window : Window.getWindows()) {
            if (window == stage || !window.isShowing() || window.getScene() == null) {
                continue;
            }
            if (!(window instanceof Stage)) {
                System.out.println("[screenshot] 拍到浮窗（右键菜单）：窗口类="
                        + window.getClass().getSimpleName());
                return window.getScene();
            }
        }
        Scene owned = null;
        String ownedTitle = null;
        for (Window window : Window.getWindows()) {
            if (!(window instanceof Stage candidate) || candidate == stage) {
                continue;
            }
            if (!candidate.isShowing() || candidate.getScene() == null) {
                continue;
            }
            if (candidate.getModality() != Modality.NONE) {
                System.out.println("[screenshot] 拍到模态窗口：" + candidate.getTitle());
                return candidate.getScene();
            }
            if (candidate.getOwner() == stage) {
                owned = candidate.getScene();
                ownedTitle = candidate.getTitle();
            }
        }
        if (owned != null) {
            System.out.println("[screenshot] 没有模态窗口，拍到主窗口的从属弹窗：" + ownedTitle);
            return owned;
        }
        System.out.println("[screenshot] 没有弹窗，拍主窗口"
                + "（窗口总数=" + Window.getWindows().size() + "）");
        return stage.getScene();
    }

    /** 手写像素循环把 WritableImage 编码为 PNG，避免引入 javafx.swing 模块。 */
    private static void writePng(WritableImage image, Path target) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = reader.getArgb(x, y);
            }
            buffered.setRGB(0, y, width, 1, row, 0, width);
        }
        ImageIo.write(buffered, target);
    }

    /** 模拟左键单击：按钮直接 fire，其余节点投递合成 MouseEvent。支持逗号分隔的多次点击。 */
    private static void click(Stage stage, String ids) {
        Scene scene = stage.getScene();
        for (String id : ids.split(",")) {
            String trimmed = id.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Node node = scene.getRoot().lookup("#" + trimmed);
            if (node == null) {
                System.err.println("[screenshot] 找不到节点 #" + trimmed + "，跳过点击");
                continue;
            }
            if (node instanceof Button button) {
                button.fire();
                continue;
            }
            double x = Math.max(1, node.getBoundsInLocal().getWidth() / 2);
            double y = Math.max(1, node.getBoundsInLocal().getHeight() / 2);
            Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, x, y,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    false, false, false, false, false, false, null));
        }
    }

    private static void runOnFx(Runnable action) {
        Platform.runLater(action);
        // runOnFx 无法等待 FX 队列清空，调用方用固定 sleep 兜底。
        sleep(300);
    }

    private static String prop(String key, String envKey) {
        return System.getProperty(key, System.getenv().getOrDefault(envKey, "")).trim();
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 独立小类，把 ImageIO 的受检异常收拢。 */
    private static final class ImageIo {
        static void write(BufferedImage image, Path target) throws IOException {
            javax.imageio.ImageIO.write(image, "png", target.toFile());
        }
    }
}
