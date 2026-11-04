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

    /**
     * ⭐ D-37：真库帧的「宁可没图，不可有假图」出口。
     *
     * <p>矩阵档案没注册上（{@code ADB_MATRIX} 的取值写错、或那家的环境变量缺失）时，
     * 外壳会照常退回演示库（SQLite）把这一帧拍完 —— 帧名写着 mysql / postgresql，
     * 画面却是演示库，而 {@code scripts/shots.sh} 只看「文件在不在」，返回码还是 0。
     * 这种帧比没图更糟：它会替一颗控件「还掉」它其实没还的欠账。
     * 所以这里直接放弃这一帧 —— 不写文件、退出码非零，让脚本报「这一格没拍出来」。
     */
    public static void abortMatrixFrame(String reason) {
        if (prop("adb.shots", "ADB_SHOTS").isEmpty()) {
            // 不是拍摄会话也会走到这条（摆态的弃帧分支在测试里同样会命中）。
            // 那里没有帧可弃，而 {@code System.exit} 会端掉整批测试的 JVM ——
            // 退出码 3 的语义只属于「这一格没拍出来」，不许混用。
            // 话也不能说成「FRAME ABORTED, nothing written」：没在拍，就没有帧被弃。
            System.err.println("[screenshot] pose gave up, no shot session: " + reason);
            return;
        }
        System.err.println("[screenshot] FRAME ABORTED, nothing written: " + reason);
        Platform.exit();
        System.exit(3);
    }

    /**
     * ⭐ D-39：真库帧的落点检查，由 {@code AppShell} 在注册矩阵档案时挂上来。
     *
     * <p>默认「没有问题」：非矩阵帧（演示库那 47 张）本来就以演示库为对象，
     * 这一层不该管它们。返回一句原因就是不成立 —— 调用方只在这一帧**即将**落盘之前问一次，
     * 所以「快门时刻页面不在那台服务器上」和「档案压根没注册上」（D-37）是两道分开的门：
     * 前者拦的是连接还没建立完、或切库切到别的档案上去了。
     */
    private static java.util.function.Supplier<String> matrixFrameGuard = () -> null;

    static void setMatrixFrameGuard(java.util.function.Supplier<String> guard) {
        matrixFrameGuard = guard == null ? () -> null : guard;
    }

    /**
     * ⭐ R57 画面债：主场景**外面**那一层（补全弹层）。
     *
     * <p>{@link #targetScene} 在「弹窗是独立 Stage」时改拍弹窗，那是为对话框设计的：
     * 弹窗整屏、内容就是证据本身。补全弹层不是 —— 要证的恰恰是
     * 「候选贴在光标下面、编辑器还看得见」。ControlsFX 的 PopOver 是 PopupWindow，
     * {@code scene.snapshot} 拍主场景时它根本不在图里，于是这一格只剩两种坏出口：
     * 拍一张只有一列候选的小图，或者拍一张没有弹层的查询页并称之为证据。
     *
     * <p>所以加第三条：拍完主场景，把浮层那颗节点单独快照成带透明通道的图，
     * 按它在主场景里的坐标合成上去。给的是 {@code Supplier} 不是常量 ——
     * 弹层位置跟着光标走，坐标必须在快门那一刻现算。
     */
    private static java.util.function.Supplier<Overlay> overlayProvider = () -> null;

    /** 一层浮动证据：节点 + 它在主场景里的左上角。 */
    record Overlay(Node node, double x, double y) {
    }

    /**
     * 摆出浮层证据，并把「这一帧的目标是主场景」这件事告诉截图会话。
     *
     * <p>调用方是弹层自己（{@code QueryCompletions}）：它一边弹，一边把
     * 「快门时刻现算坐标」的取数口交出来。
     */
    static void setOverlayProvider(java.util.function.Supplier<Overlay> provider) {
        overlayProvider = provider == null ? () -> null : provider;
    }

    /**
     * 门禁读的一面：此刻登记的浮层（没有就是 null）。
     *
     * <p>必须在 FX 线程上问 —— 它要摸弹层那颗节点的 scene/window。
     */
    static Overlay overlayForShots() {
        return overlayProvider.get();
    }

    /**
     * ⭐ 兜底「发现最大化就取消」到底有没有用，之前只是**没被触发过**、没被证明过。
     * 设 {@code ADB_SHOTS_MAXIMIZE=1} 就在按快门前自己最大化一次，逼这条路径走一遍：
     * 帧若仍是 1280×800，就证明的是恢复本身而不是运气。
     */
    private static final boolean FORCE_MAXIMIZE =
            !prop("adb.shots.maximize", "ADB_SHOTS_MAXIMIZE").isEmpty();
    private static boolean forceMaximizeArmed = FORCE_MAXIMIZE;

    private static void capture(Stage stage, Path dir, String name) {
        capture(stage, dir, name, 0);
    }

    /**
     * 第 0 步：该修的一律修完并重新排一次；第 1 步：修完之后再排一次，
     * 因为**每一次**改尺寸都要隔一个布局脉冲才在 Scene 上读得到新值
     * （同一帧里读会拿到旧值 —— 实测把 setWidth 和 snapshot 放同一条 FX 任务里，
     * 快照仍是 1264×761）。第 2 步及以后：只拍。
     */
    private static void capture(Stage stage, Path dir, String name, int step) {
        // ⭐ 启动时那一次「发现最大化就取消」不够：Windows 可以在 stage 已经显示之后
        //   才把上次的窗口摆放置回来（2026-10-29 实测到 p2-find 仍是 1920×1017）。
        if (step == 0 && forceMaximizeArmed) {
            forceMaximizeArmed = false;
            System.out.println("[screenshot] forcing maximized state to exercise the guard");
            stage.setMaximized(true);
            schedule(600, () -> capture(stage, dir, name, 0));
            return;
        }
        if (step == 0 && stage.isMaximized()) {
            System.out.println("[screenshot] maximized at capture time; un-maximizing");
            stage.setMaximized(false);
            schedule(600, () -> capture(stage, dir, name, 1));
            return;
        }
        if (step == 1) {
            // ⭐ 只取消最大化不够：JavaFX 还原的是**外框**，取消后场景实测缩成 1264×761
            //   （差一圈边框 + 标题栏）。Scene.setWidth/setHeight 是 private，
            //   所以按「外框 − 场景」的差值反过来钉窗口尺寸，让场景回到 1280×800。
            Scene scene = stage.getScene();
            if (scene != null) {
                double decoW = stage.getWidth() - scene.getWidth();
                double decoH = stage.getHeight() - scene.getHeight();
                System.out.println("[screenshot] restoring scene size: stage="
                        + (int) stage.getWidth() + "x" + (int) stage.getHeight()
                        + " scene=" + (int) scene.getWidth() + "x" + (int) scene.getHeight());
                stage.setWidth(io.agenticdb.ui.App.SHOT_SCENE_WIDTH + decoW);
                stage.setHeight(io.agenticdb.ui.App.SHOT_SCENE_HEIGHT + decoH);
                schedule(600, () -> capture(stage, dir, name, 2));
                return;
            }
        }
        // ⭐ D-39：真库帧的**落点**也要在按快门前问一次。
        //   D-37 只钉住「这一帧连的是那台服务器」这个**声明**，而声明成立不等于此刻的页面
        //   在那台上：Oracle 首次连接要 8~12 秒，7 秒的帧就在「Connecting to matrix Oracle…」
        //   上按了快门 —— 拍出来的是演示库（SQLite）的表清单，文件名却写着 oracle。
        //   这种帧比缺帧更贵：它看起来正是那台服务器的证据。
        String problem = matrixFrameGuard.get();
        if (problem != null) {
            abortMatrixFrame(problem);
            return;
        }
        try {
            Overlay overlay = overlayProvider.get();
            // ⭐ 有浮层要合成时**不能**走 targetScene：那颗 PopOver 是 PopupWindow，
            //   而 targetScene 见到「主 Stage 之外还在显示的窗口」就改拍它 ——
            //   那是给对话框定的规矩，用在浮层上会拍出一张只有一列候选的小图。
            Scene scene = overlay == null ? targetScene(stage) : stage.getScene();
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
            BufferedImage canvas = toBufferedImage(image);
            if (overlay != null && overlay.node() != null && overlay.node().getScene() != null) {
                javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
                parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
                WritableImage floated = overlay.node().snapshot(parameters, null);
                int x = (int) Math.round(overlay.x());
                int y = (int) Math.round(overlay.y());
                blend(canvas, toBufferedImage(floated), x, y);
                System.out.println("[screenshot] overlay composed at " + x + "," + y
                        + " size=" + (int) overlay.node().getBoundsInLocal().getWidth()
                        + "x" + (int) overlay.node().getBoundsInLocal().getHeight()
                        + " (main scene kept, popup window not the target)");
            }
            ImageIo.write(canvas, target);
            System.out.println("[screenshot] " + target.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[screenshot] failed: " + e);
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
            // Tooltip 也是 PopupWindow（非 Stage）。物理光标恰好停在带 tooltip 的
            // 控件上时它会自己冒出来 —— 拍它的 scene 会拍出一张 163×48 的悬浮条
            // （2026-10-29 p0-shell 实拍），并且 snapshot 与 tooltip 自动 hide
            // 竞态直接把 glass.dll 干崩。任何帧都不该以 tooltip 为目标。
            if (window instanceof javafx.scene.control.Tooltip) {
                continue;
            }
            if (!(window instanceof Stage)) {
                System.out.println("[screenshot] popup captured (context menu): window class="
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
                System.out.println("[screenshot] modal captured: " + candidate.getTitle());
                return candidate.getScene();
            }
            if (candidate.getOwner() == stage) {
                owned = candidate.getScene();
                ownedTitle = candidate.getTitle();
            }
        }
        if (owned != null) {
            System.out.println("[screenshot] no modal; capturing an owned dialog of the main window: " + ownedTitle);
            return owned;
        }
        System.out.println("[screenshot] no popup; capturing the main window"
                + " (window count=" + Window.getWindows().size() + ")");
        return stage.getScene();
    }

    /** 手写像素循环把快照读成 ARGB 画布，避免引入 javafx.swing 模块。 */
    private static BufferedImage toBufferedImage(WritableImage image) {
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
        return buffered;
    }

    /**
     * src-over 叠一层带透明通道的图；落在画布外的部分裁掉，全透明的像素原样保留。
     *
     * <p>必须自己算 alpha，不能图省事交给 {@code Graphics2D.drawImage}：
     * 那条路径会把浮层自带的半透明阴影当成不透明方块画，边缘一圈发灰 ——
     * 而这张图是拿去当验收证据的，阴影糊边会被读成「弹层没有阴影」。
     *
     * <p>非预乘通道下的 src-over（把 0–255 的 alpha 记作 a，下同）：
     * {@code out = (src*as + dst*ad*(255-as)/255) / (as + ad*(255-as)/255)}；
     * {@code ad=255} 时 {@code as=128} 应得 {@code (128*src + 127*dst)/255}（半透明白压纯黑 ≈128）。
     */
    static void blend(BufferedImage canvas, BufferedImage overlay, int left, int top) {
        // 浮层贴在编辑器右下角时光标一动就会超出画布：先算可见子矩形，
        // 否则负坐标会直接 IndexOutOfBounds，或把「超出」的那几行读成画布另一头。
        int overlayX = Math.max(0, -left);
        int overlayY = Math.max(0, -top);
        int width = Math.min(overlay.getWidth() - overlayX, canvas.getWidth() - Math.max(left, 0));
        int height = Math.min(overlay.getHeight() - overlayY, canvas.getHeight() - Math.max(top, 0));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int source = overlay.getRGB(overlayX + x, overlayY + y);
                int sourceAlpha = source >>> 24;
                if (sourceAlpha == 0) {
                    continue;
                }
                int canvasX = left + overlayX + x;
                int canvasY = top + overlayY + y;
                int target = canvas.getRGB(canvasX, canvasY);
                int targetAlpha = target >>> 24;
                int resultAlpha = sourceAlpha + targetAlpha * (255 - sourceAlpha) / 255;
                int red = blendChannel(source >> 16 & 0xFF, sourceAlpha, target >> 16 & 0xFF,
                        targetAlpha, resultAlpha);
                int green = blendChannel(source >> 8 & 0xFF, sourceAlpha, target >> 8 & 0xFF,
                        targetAlpha, resultAlpha);
                int blue = blendChannel(source & 0xFF, sourceAlpha, target & 0xFF,
                        targetAlpha, resultAlpha);
                canvas.setRGB(canvasX, canvasY,
                        resultAlpha << 24 | red << 16 | green << 8 | blue);
            }
        }
    }

    /** 单通道 src-over；结果钳回 0–255（整数除法会因舍入差出界）。 */
    private static int blendChannel(int source, int sourceAlpha, int under, int underAlpha,
                                   int resultAlpha) {
        int value = (source * sourceAlpha + under * underAlpha * (255 - sourceAlpha) / 255)
                / resultAlpha;
        return Math.max(0, Math.min(255, value));
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
                System.err.println("[screenshot] node #" + trimmed + " not found; skipping the click");
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

    /** 在 FX 线程上延后一件事（必须在调用时已处于 FX 线程）。 */
    private static void schedule(long millis, Runnable action) {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(millis));
        pause.setOnFinished(event -> action.run());
        pause.play();
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
