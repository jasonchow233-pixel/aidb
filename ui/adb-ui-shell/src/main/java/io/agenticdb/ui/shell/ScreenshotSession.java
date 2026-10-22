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
     * 有模态弹窗时拍弹窗，否则拍主窗口。
     *
     * <p>⭐ JavaFX 的 {@code Dialog} 是<b>独立的 Stage</b>，不是主场景的子节点 ——
     * 直接 {@code stage.getScene().snapshot()} 只会拍到主窗口，弹窗整个消失。
     * 实测踩过：拍了张「新建索引」弹窗，结果和结构页的图 MD5 完全一样。
     */
    private static Scene targetScene(Stage stage) {
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
        }
        System.out.println("[screenshot] 没有模态窗口，拍主窗口"
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
