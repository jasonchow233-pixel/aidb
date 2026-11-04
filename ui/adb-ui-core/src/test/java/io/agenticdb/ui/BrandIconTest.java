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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R63-3 门禁：<b>厂商商标通道</b>（{@code icons/brand/*.svg}）里的每一枚都必须是
 * 「拍平到能被 {@link LucideConverter#toBrandLayers} 原样吃下去」的官方标志。
 *
 * <h2>为什么这条链要单独一组门禁</h2>
 * 商标和自有图标不是一回事：彩色通道（{@code icons/color/}）的约定是<b>资产不许写颜色</b>，
 * 颜色由 CSS 的角色令牌给；商标通道正好相反 —— <b>官方色就是识别本身</b>，
 * 所以颜色必须字面钉在资产里，而且<b>不许</b>被 CSS 改动。约定反过来，失败模式也就反了：
 * <ol>
 *   <li>资产里留着 {@code transform} / {@code <g>} / {@code url(#grad)} →
 *       {@code toBrandLayers} 会当场抛，但那是<b>首次渲染</b>时才抛，也就是用户点开连接
 *       向导的那一刻；这条门禁把它挪到构建期；</li>
 *   <li>非方形 viewBox 或有偏移（官方 SVG 常见 {@code viewBox="-2 -1 18 12"}）→
 *       归一化算错比例，标志被压扁或切掉半边；</li>
 *   <li>坐标越界 → {@code SVGPath} 不报错，只是画到框外面，图标看起来缺了一块；</li>
 *   <li>命令里混进 {@code A}（弧线）或相对小写命令 → 转换器不吃，JavaFX 也不吃；</li>
 *   <li>资产里出现 {@code class} / {@code data-paint} / {@code style} → 商标被接回
 *       {@code -adb-icon-*} 上色通道，等于我们把别人的 logo 重新染了一遍色
 *       （MariaDB、Microsoft 的条款明确禁止改色，这也是这次拍板时明码写下的风险）。</li>
 * </ol>
 *
 * <p>「哪家方言该有哪枚标志」不在这个模块里判 —— 登记表是 {@code ConnectionCatalog}，
 * 由 shell 侧的 {@code VendorGlyphTest} 守。这里只守资产<b>本身的形状</b>，
 * 加上署名文件在不在（{@link #brandAssetsShipTheirTrademarkNotice()}）。
 */
class BrandIconTest {

    private static final Path BRAND_DIR = repositoryRoot().resolve("ui").resolve("adb-ui-core")
            .resolve("src").resolve("main").resolve("resources").resolve("io")
            .resolve("agenticdb").resolve("ui").resolve("icons").resolve("brand");

    /** 根元素：必须是无偏移的正方形视框。 */
    private static final Pattern VIEW_BOX =
            Pattern.compile("viewBox\\s*=\\s*\"\\s*([\\d.]+)\\s+([\\d.]+)\\s+(\\d+(?:\\.\\d+)?)"
                    + "\\s+(\\d+(?:\\.\\d+)?)\\s*\"");

    private static final Pattern PATH_ELEMENT = Pattern.compile("<path\\b([^>]*?)/?>");

    /** 商标通道<b>只</b>允许这一个元素标签；出现别的就等于说没拍干净。 */
    private static final Pattern FORBIDDEN_ELEMENT = Pattern.compile(
            "<(g|defs|mask|clipPath|image|text|tspan|linearGradient|radialGradient|filter|"
                    + "circle|ellipse|rect|line|polyline|polygon|use|style|symbol)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HEX_FILL = Pattern.compile("fill=\"#([0-9a-fA-F]{6})\"");

    /** 商标不许挂任何颜色钩子（类、角色、内联样式）—— 一挂就交回 CSS 上色通道。 */
    private static final Pattern PAINT_HOOK = Pattern.compile(
            "\\b(class|style|data-paint|fill-opacity|stroke)\\s*=", Pattern.CASE_INSENSITIVE);

    /** 转换器不吃、JavaFX 也不认的写法（弧线 A/a、闭合 z、相对命令小写）。 */
    private static final Set<String> JAVAFX_COMMANDS = Set.of("M", "L", "H", "V", "C", "S", "Q", "Z");

    private static final Pattern ANY_COMMAND_LETTER = Pattern.compile("[A-Za-z]");

    /** 视框边界容差：坐标归一化到 16 单位后四舍五入 3 位，留 1 单位给贴边的极值。 */
    private static final double TOLERANCE = 1.0;

    @Test
    void brandDirectoryHoldsAssets() throws IOException {
        assertThat(assetNames()).as("商标资产目录 %s 不能是空的", BRAND_DIR).isNotEmpty();
    }

    /** 每一枚都能被<b>产品真正用的那条解析路径</b>吃下去 —— 而不是只看着像 SVG。 */
    @Test
    void everyBrandAssetParsesThroughTheBrandConverter() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : assetNames()) {
            try {
                List<LucideConverter.BrandLayer> layers =
                        LucideConverter.toBrandLayers(read(BRAND_DIR.resolve(name + ".svg")),
                                Icons.VIEW_BOX);
                if (layers.isEmpty()) {
                    problems.add(name + " → 解析出 0 层");
                }
            } catch (RuntimeException e) {
                problems.add(name + " → " + e.getMessage());
            }
        }
        assertThat(problems)
                .as("这些商标会在首次渲染时才炸（用户点开向导的那一刻）：\n  %s",
                        String.join("\n  ", problems))
                .isEmpty();
    }

    /** 官方色<b>字面</b>写在资产里，且不吃 CSS —— 与彩色通道（只标角色）正好相反。 */
    @Test
    void brandAssetsDeclareLiteralOfficialColorsWithoutCssHooks() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : assetNames()) {
            String svg = stripComments(read(BRAND_DIR.resolve(name + ".svg")));
            Matcher element = PATH_ELEMENT.matcher(svg);
            int layers = 0;
            while (element.find()) {
                layers++;
                String attrs = element.group(1);
                if (!Pattern.compile("fill=\"#[0-9a-fA-F]{6}\"").matcher(attrs).find()) {
                    problems.add(name + " 第 " + layers + " 层 → 填充色不是字面 #rrggbb（官方色"
                            + "必须拍进资产，CSS 上色等于给商标改色）");
                }
                Matcher hook = PAINT_HOOK.matcher(attrs);
                if (hook.find()) {
                    problems.add(name + " 第 " + layers + " 层 → 带了颜色钩子 " + hook.group()
                            + "，会被 app.css 覆盖");
                }
                if (attrs.toLowerCase(Locale.ROOT).contains("transform")) {
                    problems.add(name + " 第 " + layers + " 层 → 留着 transform，转换器不吃");
                }
            }
            if (layers == 0) {
                problems.add(name + " → 没有 path 层");
            }
            Matcher forbidden = FORBIDDEN_ELEMENT.matcher(svg);
            if (forbidden.find()) {
                problems.add(name + " → 还有没拍平的元素 <" + forbidden.group(1)
                        + ">（分组、蒙版、渐变、位图都不许出现在商标资产里）");
            }
        }
        assertThat(problems)
                .as("商标通道必须是「字面官方色 + 纯路径」：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    /** 视框：无偏移的正方形 —— 归一化比例全靠这一条，偏一点就是压扁或切边。 */
    @Test
    void brandAssetsUseASquareViewBoxAtTheOrigin() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : assetNames()) {
            Matcher box = VIEW_BOX.matcher(read(BRAND_DIR.resolve(name + ".svg")));
            if (!box.find()) {
                problems.add(name + " → 没有 viewBox（转换器算不出归一化比例）");
                continue;
            }
            double minX = Double.parseDouble(box.group(1));
            double minY = Double.parseDouble(box.group(2));
            double width = Double.parseDouble(box.group(3));
            double height = Double.parseDouble(box.group(4));
            if (minX != 0 || minY != 0) {
                problems.add(name + " → 视框原点不在 (0,0)：" + minX + "," + minY
                        + "（官方 SVG 常带负原点，拍平时要挪）");
            }
            if (Math.abs(width - height) > 0.001) {
                problems.add(name + " → 视框不是正方形：" + width + "×" + height
                        + "（正方形之外的留白由拍平时的 margin 负责）");
            }
        }
        assertThat(problems)
                .as("视框决定缩放，缩放错了标志就变形：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    /** 转换后的分层：命令全绝对、坐标都在 16 单位框内 —— 与彩色通道同一套可渲染口径。 */
    @Test
    void brandLayersStayInsideTheSixteenUnitBox() {
        List<String> problems = new ArrayList<>();
        for (String name : assetNames()) {
            List<LucideConverter.BrandLayer> layers =
                    LucideConverter.toBrandLayers(read(BRAND_DIR.resolve(name + ".svg")),
                            Icons.VIEW_BOX);
            for (int i = 0; i < layers.size(); i++) {
                LucideConverter.BrandLayer layer = layers.get(i);
                String path = layer.path();
                Matcher letters = ANY_COMMAND_LETTER.matcher(path);
                while (letters.find()) {
                    String op = letters.group();
                    if (!op.equals(op.toUpperCase(Locale.ROOT))
                            || !JAVAFX_COMMANDS.contains(op)) {
                        problems.add(name + " 第 " + (i + 1) + " 层 → JavaFX 不认的命令 " + op);
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
                    problems.add(name + " 第 " + (i + 1) + " 层 → 坐标越界 [" + min + ", " + max
                            + "]，会画到线框外（图标看着缺一块）");
                }
                assertThat(layer.fill())
                        .as("%s 第 %d 层的色值口径".formatted(name, i + 1))
                        .matches("#[0-9a-f]{6}");
            }
        }
        assertThat(problems)
                .as("拍平不等于拍对：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    /** 同一枚形状不许出现两次：重复资产既没人引用，也白担一份商标风险。 */
    @Test
    void noTwoBrandAssetsShareOneShape() {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (String name : assetNames()) {
            List<LucideConverter.BrandLayer> layers =
                    LucideConverter.toBrandLayers(read(BRAND_DIR.resolve(name + ".svg")),
                            Icons.VIEW_BOX);
            String shape = layers.stream().map(LucideConverter.BrandLayer::path)
                    .reduce("", (a, b) -> a + "|" + b);
            if (!seen.add(shape)) {
                duplicates.add(name);
            }
        }
        assertThat(duplicates)
                .as("这些商标与前面某一家同形（两家共用一枚 = 分厂图标白画）：%s", duplicates)
                .isEmpty();
    }

    /**
     * 借用素材的署名义务跟着资源走：{@code TRADEMARK-NOTICE.txt} 必须写明每一枚的
     * 出处、许可，以及<b>我们改了什么</b>。
     *
     * <p>这条不是形式主义：这次拍平的每一项改动（烘焙 transform、渐变取主色、方视框、
     * Oracle 环按官方站点图标量色）都落在各家商标条款的「不得改色/不得衍生」灰区里。
     * 出处与改动清单不在 jar 里，出事时拿不出「我们当时是按什么口径做的」。
     */
    @Test
    void brandAssetsShipTheirTrademarkNotice() throws IOException {
        Path notice = BRAND_DIR.resolve("TRADEMARK-NOTICE.txt");
        assertThat(notice).as("商标目录必须有署名与改动说明").isRegularFile();
        String text = read(notice);
        assertThat(text)
                .as("说明文件要逐枚登记出处与改动，这几块内容不能少")
                .contains("Wikimedia Commons")
                .contains("transform")
                .contains("渐变");
        for (String name : assetNames()) {
            assertThat(text).as("每枚资产都要在说明里点名：%s", name)
                    .containsIgnoringCase(name);
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static List<String> assetNames() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(BRAND_DIR)) {
            files.filter(path -> path.toString().endsWith(".svg"))
                    .map(path -> {
                        String file = path.getFileName().toString();
                        return file.substring(0, file.lastIndexOf('.'));
                    })
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到商标资产目录 " + BRAND_DIR, e);
        }
        return names;
    }

    private static String stripComments(String svg) {
        return svg.replaceAll("(?s)<!--.*?-->", "");
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
