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

/**
 * ⭐ R35 门禁：<b>彩色图标分层通道</b>（{@code icons/color/*.svg} → {@link IconPaint} 角色
 * → {@code app.css} 的 {@code -adb-icon-*} 令牌）必须真的成立。
 *
 * <h2>为什么单独一条链要单独一组门禁</h2>
 * 单色通道早就有 {@link LucideIconTest} 守着；彩色通道多出来的不是一段几何，
 * 而是<b>一套约定</b>：资产只标角色、不写颜色。约定破了，症状都不在编译期：
 * <ol>
 *   <li>资产里塞进十六进制 → 开出<b>第三个颜色来源</b>（工程约束 1：令牌唯一真相），
 *       而且暗色档不会跟着变，只有切主题肉眼看才发现；</li>
 *   <li>角色名写错 → {@link IconPaint#fromAsset} 运行期才炸，且炸在渲染路径上；</li>
 *   <li>没有 EDGE 层 / EDGE 被填充层压住 → 图标糊成一团色块，缩到 14px 就是一块斑；</li>
 *   <li>CSS 里某个角色只写了 {@code -fx-fill} 没写 {@code -fx-stroke} → 那条线沿用
 *       继承来的<b>黑色</b>，暗色主题下看不见、亮色主题下像描边 bug。</li>
 * </ol>
 *
 * <p>另外守着一条硬边界：<b>本目录不许出现第三方或竞品的图标资产</b>。
 * 这里的每一张都是按 Lucide 的几何口径自绘的 ISC 衍生作品，
 * 竞品（Navicat 等）的位图属于他人版权素材，进产品就是法律风险。
 * （R63-3 之后厂商<b>商标</b>确实进了 jar，但走的是 {@code icons/brand/} 那条通道 ——
 * 那条通道不标角色、写死官方色，署名口径也不同，见 {@code BrandIconTest}。）
 */
class ColoredIconTest {

    /** {@code icons/color/} 目录（与 {@link Icons#LUCIDE_DIR} 同一套定位方式，走磁盘不走 classpath）。 */
    private static final Path COLOR_DIR = repositoryRoot().resolve("ui").resolve("adb-ui-core")
            .resolve("src").resolve("main").resolve("resources").resolve("io")
            .resolve("agenticdb").resolve("ui").resolve("icons").resolve("color");

    /** 图形元素标签（{@code <svg>} 自身不算）。 */
    private static final Pattern SHAPE_ELEMENT =
            Pattern.compile("<(path|circle|ellipse|rect|line|polyline|polygon)\\b([^>]*)>");

    private static final Pattern PAINT_ATTRIBUTE =
            Pattern.compile("data-paint\\s*=\\s*\"([^\"]*)\"");

    /** 颜色字面量：十六进制、{@code rgb()}、{@code hsl()}、以及 SVG 的具称色。 */
    private static final Pattern COLOR_LITERAL = Pattern.compile(
            "#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsla?\\(|"
                    + "\"(black|white|red|green|blue|gray|grey|orange|purple|yellow|cyan|magenta)\"");

    /** JavaFX {@code SVGPath} 认的绝对命令 —— 与 {@link LucideIconTest} 同一口径。 */
    private static final Set<String> JAVAFX_COMMANDS =
            Set.of("M", "L", "H", "V", "C", "S", "Q", "Z");

    private static final Pattern ANY_COMMAND_LETTER = Pattern.compile("[A-Za-z]");

    /** 视框边界（留 1px：描边居中，圆头端点会略微出线框）。 */
    private static final double TOLERANCE = 1.0;

    @Test
    void everyColoredAssetIsReferencedByAnIcon() throws IOException {
        Set<String> onDisk = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(COLOR_DIR)) {
            files.filter(path -> path.toString().endsWith(".svg"))
                    .forEach(path -> onDisk.add(assetName(path)));
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            referenced.add(Icons.colorAssetKey(icon));
        }
        List<String> dead = new ArrayList<>(onDisk);
        dead.removeAll(referenced);
        assertThat(onDisk).as("彩色资产目录不能是空的 —— 空的说明资源没打进模块").isNotEmpty();
        assertThat(dead)
                .as("这些彩色资产放进来了，但没有任何图标能取到它 —— 死资源：%s", dead)
                .isEmpty();
    }

    @Test
    void coloredAssetsDeclareRolesInsteadOfColors() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : assetNames()) {
            // ⭐ 先剥注释再扫（与 StyleClassCoverageTest 对 CSS 的处理同一口径）：
            //    注释里写的「tone-a（#CDE8FF）在 14px 下看不见」是设计说明，不是颜色来源。
            String svg = stripComments(read(COLOR_DIR.resolve(name + ".svg")));
            Matcher color = COLOR_LITERAL.matcher(svg);
            while (color.find()) {
                problems.add(name + " → 资产里写了颜色字面量 " + color.group()
                        + "（颜色只能来自 tokens.json → app.css）");
            }
            Matcher element = SHAPE_ELEMENT.matcher(svg);
            int shapes = 0;
            while (element.find()) {
                shapes++;
                Matcher paint = PAINT_ATTRIBUTE.matcher(element.group(2));
                if (!paint.find()) {
                    problems.add(name + " → 第 " + shapes + " 个元素没标 data-paint，"
                            + "会静默回落到 EDGE（填充面就变成描边）");
                    continue;
                }
                try {
                    IconPaint.fromAsset(paint.group(1));
                } catch (IllegalStateException e) {
                    problems.add(name + " → " + e.getMessage());
                }
            }
            if (shapes == 0) {
                problems.add(name + " → 没有可绘制的图形元素");
            }
        }
        assertThat(problems)
                .as("彩色资产必须是「角色标注」而不是「配色表」：\n  %s",
                        String.join("\n  ", problems))
                .isEmpty();
    }

    @Test
    void everyColoredIconKeepsOneEdgeLayerOnTop() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : assetNames()) {
            List<String> roles = rolesOf(COLOR_DIR.resolve(name + ".svg"));
            long edges = roles.stream().filter("edge"::equals).count();
            if (edges == 0) {
                problems.add(name + " → 一个 EDGE 层都没有，14px 下就是一块没有轮廓的色斑");
            }
            if (!roles.isEmpty() && !"edge".equals(roles.get(roles.size() - 1))) {
                problems.add(name + " → 最上层是 " + roles.get(roles.size() - 1)
                        + "，把轮廓压在下面（约定：自下而上画，EDGE 收尾）");
            }
        }
        assertThat(problems)
                .as("分层顺序决定可读性：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    @Test
    void coloredLayersConvertIntoTheSixteenViewBox() {
        List<String> problems = new ArrayList<>();
        for (Icons.Icon icon : Icons.Icon.values()) {
            if (!Icons.hasColoredVariant(icon)) {
                continue;
            }
            List<LucideConverter.Layer> layers = Icons.cachedLayers(icon);
            assertThat(layers).as(icon.name() + " 已判定有彩色资产，分层必须拿得到").isNotNull();
            boolean hasEdge = layers.stream().anyMatch(layer -> layer.paint() == IconPaint.EDGE);
            if (!hasEdge) {
                problems.add(icon.name() + " → 转换后没有 EDGE 层");
            }
            for (int i = 0; i < layers.size(); i++) {
                LucideConverter.Layer layer = layers.get(i);
                String path = layer.path();
                Matcher letters = ANY_COMMAND_LETTER.matcher(path);
                while (letters.find()) {
                    String op = letters.group();
                    if (op.equals(op.toLowerCase()) || !JAVAFX_COMMANDS.contains(op)) {
                        problems.add(icon.name() + " 第 " + (i + 1) + " 层 → JavaFX 不认的命令 " + op);
                        break;
                    }
                }
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                Matcher number = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(path);
                while (number.find()) {
                    double value = Double.parseDouble(number.group());
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                if (min < -TOLERANCE || max > Icons.VIEW_BOX + TOLERANCE) {
                    problems.add(icon.name() + " 第 " + (i + 1) + " 层（" + layer.paint()
                            + "）→ 坐标越界 [" + min + ", " + max + "]");
                }
            }
        }
        assertThat(problems)
                .as("彩色分层必须与单色同一套可渲染口径：\n  %s",
                        String.join("\n  ", problems))
                .isEmpty();
    }

    @Test
    void everyRoleCssRuleSetsBothFillAndStroke() throws IOException {
        String css = Files.readString(appCss(), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "");
        for (IconPaint paint : IconPaint.values()) {
            String rule = ruleBody(css, paint.styleClass());
            assertThat(rule)
                    .as("角色 %s 在 app.css 里要有规则，否则这层沿用继承来的黑色", paint)
                    .isNotNull();
            assertThat(rule)
                    .as("%s 必须同时钉死 -fx-fill 与 -fx-stroke —— 漏一个，那一条就吃继承色",
                            paint.styleClass())
                    .contains("-fx-fill")
                    .contains("-fx-stroke");
            assertThat(rule)
                    .as("%s 只能引用令牌或 transparent", paint.styleClass())
                    .doesNotContain("#")
                    .doesNotContain("rgb");
        }
    }

    @Test
    void monochromeFallbackStaysAvailableForUnpaintedIcons() {
        // 彩色档是一批一批铺的：没铺到的图标必须还能拿单色，不能返回 null。
        int colored = 0;
        for (Icons.Icon icon : Icons.Icon.values()) {
            assertThat(Icons.of(icon, Icons.SIZE_LABEL))
                    .as("%s 的单色兜底不能失效", icon.name())
                    .isNotNull();
            if (Icons.hasColoredVariant(icon)) {
                colored++;
                assertThat(Icons.colored(icon, Icons.SIZE_LABEL))
                        .as("%s 的彩色实例不能是 null", icon.name())
                        .isNotNull();
            }
        }
        assertThat(colored)
                .as("至少要有真图标走了彩色通道，否则这条链等于没被验证过")
                .isGreaterThan(0);
    }

    @Test
    void everyBorrowedAssetDirectoryShipsItsLicense() {
        // ISC 的义务是「副本里保留版权声明与许可原文」。许可文件只写在 docs 里、
        // 不跟着资源走，等于借了路径数据却没署名 —— 而这两个目录都会打进 jar。
        List<Path> dirs = List.of(COLOR_DIR, COLOR_DIR.getParent().resolve("lucide"));
        List<String> problems = new ArrayList<>();
        for (Path dir : dirs) {
            long borrowed;
            try (Stream<Path> files = Files.list(dir)) {
                borrowed = files.filter(path -> path.toString().endsWith(".svg")).count();
            } catch (IOException e) {
                problems.add(dir + " → 目录读不出来：" + e);
                continue;
            }
            Path license = dir.resolve("ISC-LICENSE.txt");
            if (borrowed == 0) {
                continue;
            }
            if (!Files.isRegularFile(license)) {
                problems.add(dir + " → 有 " + borrowed + " 枚借用资产，却没有 ISC-LICENSE.txt");
                continue;
            }
            String text = read(license);
            if (!text.contains("ISC License") || !text.contains("Lucide")) {
                problems.add(license + " → 许可原文缺版权声明头（ISC License / Lucide）");
            }
        }
        // ⭐ R63-3：第三个借用目录 = 厂商商标。它借的不是 ISC 素材而是**商标**，
        //    署名文件自然也不同名（TRADEMARK-NOTICE.txt：出处、各家条款、我们改了什么）。
        //    内容逐条由 {@code BrandIconTest} 守，这里只守「打进 jar 的那份在不在」——
        //    这条门禁的意义是「借用目录不许有免检区」，漏一个目录就等于破功。
        Path brandDir = COLOR_DIR.getParent().resolve("brand");
        long brandMarks = 0;
        try (Stream<Path> files = Files.list(brandDir)) {
            brandMarks = files.filter(path -> path.toString().endsWith(".svg")).count();
        } catch (IOException e) {
            problems.add(brandDir + " → 目录读不出来：" + e);
        }
        if (brandMarks > 0) {
            Path notice = brandDir.resolve("TRADEMARK-NOTICE.txt");
            if (!Files.isRegularFile(notice)) {
                problems.add(brandDir + " → 有 " + brandMarks
                        + " 枚厂商商标，却没有 TRADEMARK-NOTICE.txt（改色与拍平的口径必须跟着 jar 走）");
            }
        }
        assertThat(problems)
                .as("借用素材的署名义务不能靠人记：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static List<String> assetNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(COLOR_DIR)) {
            files.filter(path -> path.toString().endsWith(".svg"))
                    .map(ColoredIconTest::assetName)
                    .sorted()
                    .forEach(names::add);
        }
        return names;
    }

    /** 资产里图形元素的角色序列（按绘制顺序，也就是 SVG 文档顺序）。 */
    private static List<String> rolesOf(Path file) {
        List<String> roles = new ArrayList<>();
        Matcher element = SHAPE_ELEMENT.matcher(stripComments(read(file)));
        while (element.find()) {
            Matcher paint = PAINT_ATTRIBUTE.matcher(element.group(2));
            roles.add(paint.find() ? IconPaint.fromAsset(paint.group(1)).name().toLowerCase(
                    java.util.Locale.ROOT).replace('_', '-') : "<未标注>");
        }
        return roles;
    }

    /** 剥掉 {@code <!-- ... -->}：设计说明里出现的色值不算颜色来源。 */
    private static String stripComments(String svg) {
        return svg.replaceAll("(?s)<!--.*?-->", "");
    }

    /** 取 {@code .类名 { ... }} 规则体（同一选择器的多条规则合并）；没有则返回 {@code null}。 */
    private static String ruleBody(String css, String styleClass) {
        Matcher matcher = Pattern.compile("\\." + styleClass + "[^{]*\\{([^}]*)}")
                .matcher(css);
        StringBuilder joined = new StringBuilder();
        while (matcher.find()) {
            joined.append(matcher.group(1));
        }
        return joined.isEmpty() ? null : joined.toString();
    }

    private static Path appCss() {
        Path css = Path.of("..", "adb-ui-shell", "src", "main", "resources",
                "io", "agenticdb", "ui", "app.css");
        if (!Files.exists(css)) {
            css = repositoryRoot().resolve("ui").resolve("adb-ui-shell")
                    .resolve("src").resolve("main").resolve("resources")
                    .resolve("io").resolve("agenticdb").resolve("ui").resolve("app.css");
        }
        assertThat(css).as("app.css 路径").exists();
        return css;
    }

    private static String assetName(Path path) {
        String name = path.getFileName().toString();
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
