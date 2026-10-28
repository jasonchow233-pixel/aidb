package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ S-7 门禁：**品牌只有一个入口，欢迎页那张死卡片不许回来**。
 *
 * <h2>为什么</h2>
 * 第十九轮做 S-5 时给欢迎页画了一张品牌门面，第二十二轮顺着它挖出更大的坑：
 * {@code AppShell} 构造器无条件 {@code openPermanent(object-home)} ⇒
 * 欢迎页**从来没被用户看到过**。三百行代码里最容易骗人的就是这种 ——
 * 单元测试全绿（{@code WelcomeBrandTest} 三条就是绿的），因为测试直接 new 了
 * {@code WorkspaceView}，绕过了真实的启动路径。
 *
 * <p>裁定是「接 + 删」：品牌先接到**用户真看得见**的应用栏方块上（矢量
 * {@link Icons.Icon#BRAND}，PNG 在暗色主题里不会换色、放大会糊），
 * 不可达的欢迎页连同它专用的样式类、尺寸档一起删掉。
 * 2026-10-28 用户裁定顶区精简后，方块本身也不留了 ——
 * 品牌改由窗口标题 / 窗口图标 / 「关于」弹窗图形承担。
 *
 * <h2>守两件事</h2>
 * <ol>
 *   <li><b>删干净</b>：{@code showWelcome} / {@code brandMark} / {@code adb-welcome}
 *       / {@code SIZE_BRAND} 在主源码与 CSS 里一处都不许留 ——
 *       半删最坏：Java 走了、CSS 还留着，{@code StyleClassCoverageTest} 之外没人知道
 *       这块规则是给谁写的。</li>
 *   <li><b>接到看得见的地方</b>：品牌字形必须出现在用户真的能看到的位置。
 *       2026-10-28 之前那是应用栏里的一个 26px 方块；用户裁定「那一栏多余」之后，
 *       品牌改由<b>窗口标题与任务栏/程序坞图标</b>承担（见
 *       {@link #theBrandLivesInTheWindowIdentityNotInAnExtraTopRow()}），
 *       而不是又变成一个「定义了但没人用」的图标。</li>
 * </ol>
 */
class BrandMarkAuditTest {

    /** 欢迎页专用的符号；出现即说明死代码又长回来了。 */
    private static final List<String> DEAD_SYMBOLS = List.of(
            "showWelcome", "brandMark", "adb-welcome", "SIZE_BRAND");

    @Test
    void theUnreachableWelcomePageStaysDeleted() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Path file : uiMainSources()) {
            scanned++;
            String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
            for (String symbol : DEAD_SYMBOLS) {
                if (source.contains(symbol)) {
                    violations.add(relative(file) + " → " + symbol);
                }
            }
        }

        assertThat(scanned)
                .as("没扫到 UI 主源码 —— 路径过滤失效，这份审计是假绿")
                .isGreaterThan(30);
        assertThat(violations)
                .as("欢迎页是不可达的死代码，已经删掉了；这些引用是残留或复活：\n  %s\n"
                                + "（要恢复欢迎页，先给它一个真的入口：AppShell 构造器不再无条件 "
                                + "openPermanent(object-home)，或者把它挂到「关闭全部标签」上）",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    /**
     * ⭐ 2026-10-28 顶区精简后的品牌归属：**窗口标题 + 任务栏图标**。
     *
     * <p>用户裁定「AIDB logo 那一栏有多多余」——顶栏的蓝方块 + 字标整块删掉，
     * 因为操作系统层面的窗口标题（{@code AIDB}）与任务栏/程序坞图标本来就在讲
     * 同一件事，应用内再讲一遍只是白占高度。品牌没有消失，它换了个更权威的位置：
     *
     * <ul>
     *   <li>{@link io.agenticdb.ui.App} 的 {@code APP_TITLE} = 「AIDB」；</li>
     *   <li>{@code App} 把多尺寸品牌图标注册进 {@code stage.getIcons()}
     *       （{@code AppIconResourceAuditTest} 保证文件真的存在）；</li>
     *   <li>顶栏里**不许**再长出 {@code #appbar-brand-*} 之类的品牌块。</li>
     * </ul>
     */
    @Test
    void theBrandLivesInTheWindowIdentityNotInAnExtraTopRow() throws IOException {
        Path app = repositoryRoot().resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/App.java");
        String appSource = Files.readString(app, StandardCharsets.UTF_8);
        assertThat(appSource)
                .as("窗口标题就是品牌 —— 顶栏删掉品牌行之后，这是用户最先看到的名字")
                .containsPattern("APP_TITLE\\s*=\\s*\"AIDB\"");
        assertThat(appSource)
                .as("品牌图标必须真的注册进窗口（任务栏 / 程序坞 / 标题栏图标）")
                .contains("stage.getIcons().add(");

        Path appBar = repositoryRoot().resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/AppBar.java");
        String appBarSource = Files.readString(appBar, StandardCharsets.UTF_8);
        assertThat(appBarSource)
                .as("顶栏品牌块已删——它要是回来了，那条 26px 行就跟着回来了")
                .doesNotContain("appbar-brand");
        assertThat(Icons.Icon.BRAND.path())
                .as("品牌 mark 走矢量图标体系（路径在 Icons 里，尺寸由 Icons.SIZE_* 定）")
                .isNotBlank();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** UI 层主源码 + 业务 CSS —— 欢迎页的三类残留（方法、样式类、尺寸档）都在这两处。 */
    private static List<Path> uiMainSources() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // Windows 用 `\`：不归一化会一个文件都不扫，门禁静默变绿。
                String relative = root.relativize(path).toString().replace('\\', '/');
                boolean uiJava = relative.endsWith(".java")
                        && relative.contains("src/main/java")
                        && relative.contains("ui/");
                boolean businessCss = relative.equals("ui/adb-ui-shell/src/main/resources/io/agenticdb/ui/app.css");
                if (uiJava || businessCss) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    /**
     * 去掉注释，只留代码。
     *
     * <p>审计的对象是**代码**：注释里写「原来那个 {@code showWelcome()} 已删」是有用的上下文，
     * 不该被自己的门禁判成残留。（第一版没剥注释，直接被这两条解释注释判红。）
     *
     * <p>已知妥协：按正则剥，字符串字面量里形如 {@code //} 的内容会连带吞掉该行剩余部分。
     * 本仓 UI 主源码没有这种串，且这里找的是样式类名与方法名，误伤方向是「多剥」而非「漏报」。
     */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
    }

    private static String relative(Path path) {
        return repositoryRoot().relativize(path).toString().replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("pom.xml"))
                    && Files.isDirectory(cursor.resolve("core"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录");
    }
}
