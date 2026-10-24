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
 * <p>裁定是「接 + 删」：品牌接到**用户真看得见**的应用栏方块上（矢量
 * {@link Icons.Icon#BRAND}，PNG 在暗色主题里不会换色、放大会糊，
 * {@code AppBarTest.theBrandMarkUsesTheVectorIconSystem} 也禁止位图），
 * 不可达的欢迎页连同它专用的样式类、尺寸档一起删掉。
 *
 * <h2>守两件事</h2>
 * <ol>
 *   <li><b>删干净</b>：{@code showWelcome} / {@code brandMark} / {@code adb-welcome}
 *       / {@code SIZE_BRAND} 在主源码与 CSS 里一处都不许留 ——
 *       半删最坏：Java 走了、CSS 还留着，{@code StyleClassCoverageTest} 之外没人知道
 *       这块规则是给谁写的。</li>
 *   <li><b>接到看得见的地方</b>：品牌字形必须出现在 {@code AppBar} 里，
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

    @Test
    void theBrandIsWiredIntoThePartTheUserActuallySees() throws IOException {
        Path appBar = repositoryRoot().resolve(
                "ui/adb-ui-shell/src/main/java/io/agenticdb/ui/shell/AppBar.java");
        String source = Files.readString(appBar, StandardCharsets.UTF_8);

        assertThat(source)
                .as("应用栏那个 26px 方块是全应用唯一常驻的品牌位 —— 它必须用自家 mark。"
                        + "换回 Icons.Icon.DATABASE 就是「又一个 DB 客户端」")
                .contains("Icons.Icon.BRAND");
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
