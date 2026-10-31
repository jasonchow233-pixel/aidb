package io.agenticdb.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ U-ICON 门禁：<b>Lucide 源文件 → JavaFX 路径</b>这条转换链必须真的成立。
 *
 * <h2>为什么</h2>
 * JavaFX {@code SVGPath} 的方言是 SVG 的子集：<b>不认弧命令 {@code A/a}</b>，也不认
 * {@code circle/rect/polygon} 这些元素 —— 而 Lucide 大量使用两者。
 * {@link LucideConverter} 负责把它们展平成 JavaFX 支持的绝对命令，缩放到 16 视框。
 * 这条链一旦出错，症状是<b>图标画成乱线</b>（相对命令的隐式重复段会被算成绝对坐标，
 * 紧凑数字流 {@code 00-2.474} 会被切错），而编译和现有测试全都发现不了。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li>每个图标的产出路径只含 JavaFX 认的绝对命令，坐标都落在 16×16 视框里；</li>
 *   <li>{@code icons/lucide/*.svg} 与枚举引用<b>一一对应</b> —— 不许有死资源，
 *       也不许引用不存在的文件；</li>
 *   <li>转换器逐条验证：弧命令展平、相对命令隐式重复、紧凑数字流、基本图形展开。</li>
 * </ol>
 */
class LucideIconTest {

    /**
     * JavaFX {@code SVGPath} 认的命令（绝对形式）。
     *
     * <p>弧命令 {@code A} 不在其列 —— 这正是 {@link LucideConverter} 存在的原因；
     * {@code T} 也画不出来，这里一并当作非法。相对形式（小写）不许出现在产出里：
     * 展平后全是绝对坐标，留着小写意味着转换器漏了段。
     */
    private static final Set<String> JAVAFX_COMMANDS =
            Set.of("M", "L", "H", "V", "C", "S", "Q", "Z");

    private static final Pattern ANY_COMMAND_LETTER = Pattern.compile("[A-Za-z]");

    /** 视框边界（留 1px：描边居中，圆头端点会略微出线框）。 */
    private static final double TOLERANCE = 1.0;
    @Test
    void everyIconYieldsJavaFxLegalGeometry() {
        List<String> violations = new ArrayList<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            String path = icon.path();
            if (path == null || path.isBlank()) {
                violations.add(icon.name() + " → 空路径");
                continue;
            }
            Matcher letters = ANY_COMMAND_LETTER.matcher(path);
            while (letters.find()) {
                String op = letters.group();
                if (op.equals(op.toLowerCase())) {
                    violations.add(icon.name() + " → 含相对命令 " + op + "（展平后应全是绝对坐标）");
                    break;
                }
                if (!JAVAFX_COMMANDS.contains(op)) {
                    violations.add(icon.name() + " → 含 JavaFX 不认的命令 " + op);
                    break;
                }
            }
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            Matcher number = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(path);
            while (number.find()) {
                double value = Double.parseDouble(number.group());
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (min < -TOLERANCE || max > Icons.VIEW_BOX + TOLERANCE) {
                violations.add(icon.name() + " → 坐标越界 [" + min + ", " + max + "]："
                        + path.substring(0, Math.min(80, path.length())));
            }
        }
        assertThat(violations)
                .as("图标必须展平成 16×16 视框内的绝对路径：\n  %s",
                        String.join("\n  ", violations))
                .isEmpty();
    }

    @Test
    void everyLucideResourceIsUsedAndEveryReferenceResolves() throws IOException {
        Set<String> onDisk = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(lucideDirectory())) {
            files.filter(path -> path.toString().endsWith(".svg"))
                    .forEach(path -> onDisk.add(fileName(path)));
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            String resource = iconResourceName(icon);
            if (resource != null) {
                referenced.add(resource);
            }
        }
        List<String> dead = new ArrayList<>(onDisk);
        dead.removeAll(referenced);
        List<String> missing = new ArrayList<>(referenced);
        missing.removeAll(onDisk);
        assertThat(dead)
                .as("这些 SVG 放进来了却没人引用 —— 死资源：%s", dead)
                .isEmpty();
        assertThat(missing)
                .as("这些图标引用了不存在的 SVG（运行期才炸）：%s", missing)
                .isEmpty();
    }

    @Test
    void selfDrawnIconsAreTheDocumentedExceptions() {
        // BRAND（品牌 mark）、DOT（实心状态点）与 AI（星芒，Lucide 没有 sparkles）
        // 没有 Lucide 等价物，其余全部来自源文件。
        List<String> selfDrawn = new ArrayList<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            if (iconResourceName(icon) == null) {
                selfDrawn.add(icon.name());
            }
        }
        assertThat(selfDrawn).containsExactlyInAnyOrder("BRAND", "DOT", "AI");
    }

    // ─────────────────────────── 转换器逐条验证 ───────────────────────────

    @Test
    void arcsBecomeCubicsThatKeepBothEndpoints() {
        String path = LucideConverter.toPath(svg("<path d=\"M2 12 A10 10 0 0 1 22 12\"/>"), 24);
        assertThat(path).doesNotContain("A").doesNotContain("a");
        assertThat(path).startsWith("M 2 12").contains("C ");
        // 半圆（180°）必须分段：单段三次贝塞尔拟合不了。
        assertThat(countOps(path, "C")).isGreaterThanOrEqualTo(2);
        assertThat(lastPoint(path)).isEqualTo("22 12");
    }

    @Test
    void zeroRadiusArcDegradesToALine() {
        String path = LucideConverter.toPath(svg("<path d=\"M2 2 A0 0 0 0 1 8 8\"/>"), 24);
        assertThat(path).isEqualTo("M 2 2 L 8 8");
    }

    @Test
    void relativeCommandsApplyToEveryImplicitRepeat() {
        // Lucide 里最常见的坑：`m` 后面跟着没有命令字母的相对坐标对。
        // 旧实现把「相对」只绑在命令字母那一次迭代上，后续段被算成绝对坐标 ——
        // 结果是 REDO 画成 "M 10 9.3 L 3.3 -3.3"（负坐标，图跑出视框）。
        String path = LucideConverter.toPath(svg("<path d=\"m4 4 l-9 9 l9 9\"/>"), 24);
        assertThat(path).isEqualTo("M 4 4 L -5 13 L 4 22");
    }

    @Test
    void compactNumberStreamsSplitLikeTheSvgGrammar() {
        // 弧标志位各占一位数字，后面可以紧接一个数（`0 0-2.474` 写成 `00-2.474`）。
        String path = LucideConverter.toPath(
                svg("<path d=\"m1.5 1.5 a1.5 1.5 0 00-2.474-1.561\"/>"), 24);
        assertThat(path).startsWith("M 1.5 1.5");
        assertThat(path).endsWith("-0.974 -0.061");
        assertThat(path).doesNotContain("A").doesNotContain("a");
    }

    @Test
    void basicShapesAreExpandedIntoPaths() {
        String circle = LucideConverter.toPath(svg("<circle cx=\"12\" cy=\"12\" r=\"10\"/>"), 24);
        assertThat(circle).startsWith("M 2 12").endsWith("Z");
        assertThat(circle).doesNotContain("circle");

        String rect = LucideConverter.toPath(
                svg("<rect x=\"2\" y=\"2\" width=\"20\" height=\"20\"/>"), 24);
        assertThat(rect).isEqualTo("M 2 2 L 22 2 L 22 22 L 2 22 Z");

        String rounded = LucideConverter.toPath(
                svg("<rect x=\"2\" y=\"2\" width=\"20\" height=\"20\" rx=\"4\"/>"), 24);
        assertThat(rounded).contains("C ").doesNotContain("rx");

        String polygon = LucideConverter.toPath(
                svg("<polygon points=\"6 3 20 12 6 21 6 3\"/>"), 24);
        assertThat(polygon).isEqualTo("M 6 3 L 20 12 L 6 21 L 6 3 Z");

        String line = LucideConverter.toPath(svg("<line x1=\"5\" x2=\"5\" y1=\"19\" y2=\"5\"/>"), 24);
        assertThat(line).isEqualTo("M 5 19 L 5 5");
    }

    @Test
    void shapesScaleIntoTheTargetViewBox() {
        // 24 → 16：坐标乘以 2/3。
        String path = LucideConverter.toPath(svg("<path d=\"M6 6 L18 18\"/>"), 16);
        assertThat(path).isEqualTo("M 4 4 L 12 12");
    }

    @Test
    void illegalSvgFailsLoudlyInsteadOfDrawingNothing() {
        assertThatThrownBy(() -> LucideConverter.toPath("<svg></svg>", 16))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LucideConverter.toPath(
                svg("<path d=\"M2 2 K5 5\"/>"), 16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a number");
        assertThatThrownBy(() -> LucideConverter.toPath(
                "<svg viewBox=\"0 0 24 16\"><path d=\"M2 2 L8 8\"/></svg>", 16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("square");
    }

    @Test
    void lucideStrokeWeightIsNormalisedAcrossTheSet() {
        // 全套视觉粗细由 Icons.STROKE 统一（1.5px），源文件里的 2 不参与渲染。
        assertThat(Icons.STROKE).isEqualTo(1.5);
        List<String> offSpec = new ArrayList<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            String resource = iconResourceName(icon);
            if (resource == null) {
                continue;
            }
            String svg = read(svgFile(resource));
            Matcher stroke = Pattern.compile("stroke-width\\s*=\\s*\"([0-9.]+)\"").matcher(svg);
            while (stroke.find()) {
                if (!stroke.group(1).equals("2")) {
                    offSpec.add(resource + " → stroke-width=" + stroke.group(1));
                }
            }
        }
        assertThat(offSpec)
                .as("Lucide 源文件描边必须统一（本套为 2，渲染时归一到 1.5）：%s", offSpec)
                .isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static String svg(String body) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">"
                + body + "</svg>";
    }

    private static int countOps(String path, String op) {
        int count = 0;
        int from = 0;
        while ((from = path.indexOf(op, from)) >= 0) {
            count++;
            from += op.length();
        }
        return count;
    }

    /** 取路径里最后一个坐标对（终点）。 */
    private static String lastPoint(String path) {
        Matcher m = Pattern.compile("(-?\\d+(?:\\.\\d+)?) (-?\\d+(?:\\.\\d+)?)\\s*$")
                .matcher(path.trim());
        return m.find() ? m.group(1) + " " + m.group(2) : "<无终点>";
    }

    private static String iconResourceName(Icons.Icon icon) {
        return icon.lucideResource();
    }

    private static Path lucideDirectory() {
        return repositoryRoot().resolve("ui").resolve("adb-ui-core").resolve("src")
                .resolve("main").resolve("resources").resolve("io").resolve("agenticdb")
                .resolve("ui").resolve("icons").resolve("lucide");
    }

    private static Path svgFile(String name) {
        return lucideDirectory().resolve(name + ".svg");
    }

    private static String fileName(Path path) {
        String name = path.getFileName().toString().replace('\\', '/');
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
