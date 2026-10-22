package io.agenticdb.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * ⭐ 应用版本号 —— **唯一真相**。
 *
 * <h2>为什么要有它</h2>
 * 状态栏以前写死 {@code "v0.1.0 · P3"}：
 * <ul>
 *   <li>版本号与构建产物脱节 —— 界面还在说 v0.1.0，代码已经是第八轮了；</li>
 *   <li>而且带一个**阶段代号** {@code P3} —— 阶段代号必然再次过时
 *       （第一轮写「P3」时项目才做到 P3，后来一路做到 P6，它一直没改）。</li>
 * </ul>
 * 现在版本号由 Maven 资源过滤写进 {@code version.properties}，
 * 状态栏与「关于」对话框**共用**这一个来源。
 *
 * <h2>为什么不用 jar manifest</h2>
 * {@code Implementation-Version} 只在**打成 jar 之后**才有值；
 * 开发时 {@code javafx:run} 跑的是 {@code target/classes}，
 * manifest 根本不存在 → 版本号会变成 {@code null}。
 * 资源过滤在两种场景下都有值，所以用它。
 */
public final class AppVersion {

    /** 原始版本号，例如 {@code 1.0.0-SNAPSHOT}；读不到时是 {@code dev}。 */
    public static final String VERSION;

    /** 展示用版本号，例如 {@code v1.0.0-SNAPSHOT}。 */
    public static final String DISPLAY;

    static {
        String version = read();
        VERSION = version;
        DISPLAY = version.startsWith("v") ? version : "v" + version;
    }

    private AppVersion() {
    }

    private static String read() {
        try (InputStream stream = AppVersion.class
                .getResourceAsStream("/io/agenticdb/ui/version.properties")) {
            if (stream == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version", "").trim();
            // 未过滤（例如从 IDE 直接跑源码）时会看到 ${project.version} 字面量。
            if (version.isEmpty() || version.startsWith("${")) {
                return "dev";
            }
            return version;
        } catch (IOException e) {
            return "dev";
        }
    }
}
