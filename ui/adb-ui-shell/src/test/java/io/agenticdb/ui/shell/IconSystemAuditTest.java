package io.agenticdb.ui.shell;

import io.agenticdb.ui.Icons;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ U-B4 门禁：**界面里不许再出现 Unicode 符号当图标**。
 *
 * <h2>为什么</h2>
 * 以前界面上的图标是 {@code ✕ ▽ ⇤ ⇥ ★ ◈ ↻ ☰ ↶ ↷ ＋ ‹ › ▶ ▦ ⌨ ⇅ ◆} 这些字符。三个问题：
 * <ol>
 *   <li><b>字形随系统字体变</b> —— 同一个 {@code ⇤} 在 macOS 和 Windows 上不是一个样，
 *       有的机器上根本没有这个字（变成方框）。</li>
 *   <li><b>符号与中文词混排</b> —— 一排按钮里既有 {@code ↻ 刷新} 又有 {@code 复制}，
 *       宽度忽宽忽窄，视觉重量完全不同。</li>
 *   <li><b>同形不同义</b> —— {@code ⚙} 曾经既表示「结构」又表示「设置」。</li>
 * </ol>
 *
 * <h2>规则</h2>
 * <ol>
 *   <li>按钮（Button / MenuButton / MenuItem）的文字里，<b>不许以图标字符开头</b>
 *       —— 那是「符号 + 中文词」混排；</li>
 *   <li>按钮文字<b>不许只由图标字符组成</b> —— 纯图标按钮要换成 {@link Icons} 矢量图；</li>
 *   <li>{@link Icons.Icon} 的每个常量都<b>必须被用到</b> —— 不许有死图标；</li>
 *   <li>领域层（{@code ObjectKind}）<b>不许带图标</b> —— 图标是 UI 的事。</li>
 * </ol>
 */
class IconSystemAuditTest {

    /**
     * 曾经当图标用过的字符。
     *
     * <p>只查这些 —— 箭头、文字、emoji 不在其列。
     * 表里的字符在 {@code app.css} 与数据标记（表格单元格的行号等）里仍然可能出现，
     * 但**不许出现在按钮上**。
     */
    private static final String ICON_GLYPHS =
            "✕▽⇤⇥★◈↻☰↶↷＋‹›▶▦⌨⇅◆▤◇⚡«»⌕⚙◷ⓟƒ◐⚑";

    /** 按钮构造：{@code new Button("…")} / {@code new MenuButton("…")} / {@code new MenuItem("…")}。 */
    private static final Pattern BUTTON = Pattern.compile(
            "new\\s+(?:Button|MenuButton|MenuItem|ToggleButton)\\s*\\(\\s*\"([^\"]*)\"");

    /** 直接写数字的图标尺寸：{@code Icons.of(icon, 16)} / {@code Icons.colored(icon, 16)}。 */
    private static final Pattern ICON_WITH_NUMERIC_SIZE = Pattern.compile(
            "Icons\\.(?:of|colored)\\([^)]*?,\\s*[0-9]+(?:\\.[0-9]+)?\\s*\\)");

    @Test
    void noButtonStartsWithAnIconGlyph() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : uiSources()) {
            String source = read(file);
            Matcher matcher = BUTTON.matcher(source);
            while (matcher.find()) {
                String text = matcher.group(1);
                if (text.isEmpty() || ICON_GLYPHS.indexOf(text.charAt(0)) < 0) {
                    continue;
                }
                violations.add(relative(file) + " → 按钮文字以图标字符开头：" + text);
            }
        }
        assertThat(violations)
                .as("按钮文字不许以 Unicode 图标字符开头 —— "
                        + "要么用 Icons 的矢量图（setGraphic），要么就写纯文字：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void noButtonIsMadeOfNothingButIconGlyphs() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : uiSources()) {
            String source = read(file);
            Matcher matcher = BUTTON.matcher(source);
            while (matcher.find()) {
                String text = matcher.group(1).trim();
                if (text.isEmpty()) {
                    continue;
                }
                boolean allGlyphs = text.chars()
                        .allMatch(ch -> ICON_GLYPHS.indexOf(ch) >= 0);
                if (allGlyphs) {
                    violations.add(relative(file) + " → 纯图标按钮：" + text);
                }
            }
        }
        assertThat(violations)
                .as("纯图标按钮必须换成 Icons 的矢量图（setGraphic）：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void everyIconIsActuallyUsed() throws IOException {
        Set<String> used = new LinkedHashSet<>();
        StringBuilder all = new StringBuilder();
        for (Path file : uiSources()) {
            all.append(read(file));
        }
        String sources = all.toString();

        List<String> unused = new ArrayList<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            // 引用形式：Icons.Icon.XXX
            if (!sources.contains("Icons.Icon." + icon.name())
                    && !sources.contains("Icon." + icon.name())) {
                unused.add(icon.name());
            }
        }
        assertThat(unused)
                .as("这些图标定义了却没人用 —— 是死代码：\n  %s", String.join("\n  ", unused))
                .isEmpty();
    }

    @Test
    void iconSizesComeFromTheNamedScale() throws IOException {
        // ⭐ UX-1：14 标签 / 16 工具条 / 20 主工具条 —— 尺寸只能从 Icons 常量来。
        //    R35 N4-b：彩色通道上线后这条必须同时管 Icons.colored()，
        //    否则「把尺寸写死的调用点迁到彩色 API」就等于把棘轮松开。
        assertThat(Icons.SIZE_LABEL).as("标签档").isEqualTo(14);
        assertThat(Icons.SIZE_TOOLBAR).as("工具条档").isEqualTo(16);
        assertThat(Icons.SIZE_PRIMARY).as("主工具条档").isEqualTo(20);
        // ⭐ 2026-10-26：Ribbon 大图标 26px（对齐 Navicat 90px 功能区）。
        assertThat(Icons.SIZE_RIBBON).as("Ribbon 档").isEqualTo(26);

        List<String> violations = new ArrayList<>();
        for (Path file : uiSources()) {
            Matcher matcher = ICON_WITH_NUMERIC_SIZE.matcher(read(file));
            while (matcher.find()) {
                violations.add(relative(file) + " → " + matcher.group());
            }
        }
        assertThat(violations)
                .as("图标尺寸必须用 Icons.SIZE_* 常量，不许写数字：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    /**
     * ⭐ R35 N4-b：**导航词汇表必须整本上色**。
     *
     * <p>彩色通道是一批一批铺的，最怕的状态不是「还没铺」，是<b>铺了一半自己不知道</b>：
     * 页签栏里数据页是彩的、查询页是单的，用户会读成两类东西；同一条树里表是彩的、
     * 列是单的，「列」看起来就不像对象的一部分。
     *
     * <p>所以这里钉两张清单：① {@link TabKind} 的全部页签图标（编译期就能穷举）；
     * ② 导航树 / 对象首页 / 结构页用到的对象类型图标（按 {@code Icons.Icon} 显式列出 ——
     * 词汇表在 {@code SideBar} 与 {@code ObjectHomePage} 的 switch 里，那两处一改，
     * 这里必须跟着改，否则等于漏了一个没上色的节点类型）。
     *
     * <p>⚠️ 这条<span>不</span>管动作图标（刷新 / 保存按钮 / 关闭）——
     * 按约定它们永远是单色线性（见 {@code docs/UI-GUIDE.md} 图标节），
     * 把它们染色会把「可点的」和「是东西」混成一类。
     */
    @Test
    void theNavigationVocabularyIsFullyColored() {
        for (TabKind kind : TabKind.values()) {
            assertThat(Icons.hasColoredVariant(kind.icon()))
                    .as("页签类型 %s 的图标 %s 没有彩色资产 —— 标签栏会一半彩一半单色",
                            kind, kind.icon())
                    .isTrue();
        }

        for (Icons.Icon icon : List.of(Icons.Icon.TABLE, Icons.Icon.VIEW_OBJECT,
                Icons.Icon.FUNCTION, Icons.Icon.TRIGGER, Icons.Icon.HISTORY,
                Icons.Icon.INDEX, Icons.Icon.COLUMNS, Icons.Icon.QUERY,
                Icons.Icon.STRUCTURE, Icons.Icon.DATABASE, Icons.Icon.CONNECT,
                Icons.Icon.FOLDER, Icons.Icon.SAVE, Icons.Icon.SETTINGS,
                Icons.Icon.DIAGNOSTICS)) {
            assertThat(Icons.hasColoredVariant(icon))
                    .as("导航词汇表里的 %s 没有彩色资产", icon)
                    .isTrue();
        }
    }

    @Test
    void theRibbonVocabularyIsFullyColored() {
        // AppBar 的 12 格磁贴（R36 按 Navicat 17 实拍重排后的全集）。Navicat 实测整条
        // 功能区是全 UI 最彩的一排，所以这 11 枚图标**必须**都有彩色资产：
        // 少一枚就是「十几个大按钮里有一个是灰的」，比全灰更扎眼。
        // （Query 与 New Query 共用 QUERY，所以 12 格只有 11 枚不同图标。）
        for (Icons.Icon icon : List.of(Icons.Icon.CONNECT, Icons.Icon.QUERY,
                Icons.Icon.ADD, Icons.Icon.VIEW, Icons.Icon.FUNCTION,
                Icons.Icon.USER, Icons.Icon.FOLDER, Icons.Icon.SAVE,
                Icons.Icon.HISTORY, Icons.Icon.STRUCTURE, Icons.Icon.GRID)) {
            assertThat(Icons.hasColoredVariant(icon))
                    .as("功能区磁贴 %s 的图标没有彩色资产 —— 大图标档会掉回单色", icon)
                    .isTrue();
        }
    }

    @Test
    void theDomainLayerKnowsNothingAboutIcons() throws IOException {
        // ObjectKind 是领域概念，不该带图标 —— 图标是 UI 的映射。
        Path kind = Path.of("..", "..", "workbench", "adb-introspect", "src", "main",
                "java", "io", "agenticdb", "introspect", "SchemaService.java");
        if (!Files.exists(kind)) {
            kind = Path.of("workbench", "adb-introspect", "src", "main", "java", "io",
                    "agenticdb", "introspect", "SchemaService.java");
        }
        assertThat(kind).as("SchemaService 路径").exists();
        String source = Files.readString(kind, StandardCharsets.UTF_8);
        assertThat(source)
                .as("领域层不许出现图标字面量")
                .doesNotContain("\"▦\"")
                .doesNotContain("\"◇\"")
                .doesNotContain("\"⚡\"")
                .doesNotContain("icon()");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static List<Path> uiSources() throws IOException {
        Path root = repositoryRoot();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                // Windows 用 `\`：不归一化会一个文件都不扫，门禁静默变绿。
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.endsWith(".java")
                        && relative.contains("src/main/java")
                        && relative.contains("ui/")) {
                    files.add(path);
                }
            }
        }
        assertThat(files)
                .as("没有扫到 UI 主源码 —— 路径过滤失效，这份审计是假绿")
                .hasSizeGreaterThan(30);
        return files;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String relative(Path path) {
        Path root = repositoryRoot();
        return root.relativize(path).toString().replace('\\', '/');
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
