package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.ui.Icons;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R63-3 门禁：<b>每家数据库各一枚官方商标标志</b>，官方色拍进路径、走完整条 CSS 仍然不变。
 *
 * <h2>这一版守的东西换了</h2>
 * R43-a 守的是「自绘同风格图形 + 品牌色经 CSS 上色」；R63-3 把图形换成官方标志原图，
 * 颜色改成<b>字面官方色</b>（{@code icons/brand/}，见 {@code Icons#BRAND_DIR} 的许可与代价）。
 * 于是这条链的失败模式也跟着换：
 * <ol>
 *   <li>两家共用一枚标志 → 分厂图标等于没画，界面照样能看（形状两两不同形才作数）；</li>
 *   <li>登记行漏一家 → 那家库顶着一颗通用圆柱，用户以为不支持
 *       （按 {@link DialectKind#values()} 穷举，不照抄手写清单）；</li>
 *   <li>官方色漏了 → 标志还在，但认不出是哪家（每家的<b>签名色</b>必须真的在分层里）；</li>
 *   <li>⚠️ 商标被我们的 CSS 染色 → 法律上各家条款禁止改色，视觉上也不是那家了。
 *       以前这条的反面才是风险（作用域类漏挂 → 品牌色空转），现在反过来：
 *       作用域类<b>不许</b>作用到商标的分层上。所以读的还是<b>计算色</b>，不是 CSS 文本。</li>
 * </ol>
 *
 * <p>明码写下的代价（拍板原话「全部表面换，我拍平素材」）：商标<b>不吃主题</b> ——
 * 暗色档下 MySQL 的深青、SQL Server 的深蓝会压在深底上，可读性靠「图形周围不留深色描边」
 * 而不是靠换色。这条不是漏了，是被 {@link #theOfficialColorsSurviveCssInBothThemes} 钉成契约：
 * 两档算出<b>同一组</b>官方色，谁将来给商标补一条主题规则，这里就红。
 *
 * <p>另一条边界仍然成立：商标的色与形状都只能从 {@link ConnectionCatalog#graphic} 出来 ——
 * 某个表面直接取资产，就是绕过登记行自己画了一遍（见 {@link #onlyTheSharedOutletAsksForBrandMarks}）。
 */
@ExtendWith(ApplicationExtension.class)
class VendorGlyphTest {

    /**
     * 六家真驱动 + 各自标志里<b>必须出现</b>的签名色。
     *
     * <p>签名色取自各家自己公布的色值（MariaDB/PostgreSQL/SQLite/MySQL 用官方品牌蓝，
     * SQL Server 用 Fluent 蓝）；Oracle 一枚是 {@code #e55844} —— 那是从 Oracle 自己发布的
     * 站点图标上逐像素量出来的红，取证口径写在 {@code icons/brand/TRADEMARK-NOTICE.txt}。
     */
    private record Vendor(DialectKind kind, String signature) {
    }

    private static final List<Vendor> VENDORS = List.of(
            new Vendor(DialectKind.SQLITE, "#0f80cc"),
            new Vendor(DialectKind.POSTGRESQL, "#336791"),
            new Vendor(DialectKind.MYSQL, "#00546b"),
            new Vendor(DialectKind.MARIADB, "#1f305f"),
            new Vendor(DialectKind.SQLSERVER, "#0078d4"),
            new Vendor(DialectKind.ORACLE, "#e55844"));

    static {
        // 方言加了项却忘了在这里登记 → 上面那六行会少一家，这条先炸在装配期。
        assertThat(VENDORS).as("每个方言都要有商标断言")
                .extracting(Vendor::kind)
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.asList(DialectKind.values()));
    }

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        StackPane root = new StackPane();
        // ⚠️ 主题类挂在场景根上，不是挂在样式表里：没有 .theme-light，-adb-* 全套
        //    looked-up color 都解析不出来。商标本来就不该吃这些色，但「不吃」必须是
        //    走完 CSS 之后的结论，而不是「样式表没加载所以没变化」的假绿。
        root.getStyleClass().add("theme-light");
        Scene scene = new Scene(root, 720, 640);
        scene.getStylesheets().add(style("/io/agenticdb/ui/theme-light.css"));
        scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
        stage.setScene(scene);
        stage.show();
    }

    /** 六家各登记一枚官方标志，且<b>两两不同形</b>（同形 = 分厂图标白画）。 */
    @Test
    void everyDialectHasItsOwnOfficialMark() {
        List<String> shapes = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            ConnectionCatalog.Entry entry = ConnectionCatalog.forKind(vendor.kind());
            assertThat(entry).as("%s 必须在登记表里有自己的一行", vendor.kind()).isNotNull();
            assertThat(Icons.hasBrandAsset(entry.brandKey()))
                    .as("%s 顶的还是通用圆柱 —— icons/brand/%s.svg 没落地",
                            vendor.kind(), entry.brandKey())
                    .isTrue();
            // 签名色漏了 = 标志被拍平成了别的颜色，认不出是哪家。
            List<Layer> painted = renderedLayers(vendor, false);
            assertThat(painted).as("%s 的标志没有任何可绘制分层", vendor.kind()).isNotEmpty();
            assertThat(painted.stream().map(Layer::hex).toList())
                    .as("%s 的标志里没有官方签名色 %s", vendor.kind(), vendor.signature())
                    .contains(vendor.signature);
            String shape = String.join("|", painted.stream().map(Layer::path).toList());
            assertThat(shape).as("%s 的标志是空路径", vendor.kind()).isNotEqualTo("");
            assertThat(shapes).as("%s 与前面某一家用了同一枚标志形状", vendor.kind())
                    .doesNotContain(shape);
            shapes.add(shape);
        }
    }

    /**
     * 官方色<b>走完 CSS 还是官方色</b>，两档主题算出同一组 —— 商标不跟着主题走。
     *
     * <p>这是 R63-3 换素材后新增的那条风险：作用域类照旧挂在容器上（库节点那种「形状是
     * 自己的、颜色跟厂走」的表面还要它），但它一旦作用到商标分层，就是把别人的 logo 染色。
     * 读计算色、而且在<b>挂上作用域类</b>的宿主里读，才能证明这条真被 CSS 过了一遍。
     */
    @Test
    void theOfficialColorsSurviveCssInBothThemes() {
        List<String> problems = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            Set<String> literal = literalFillsOf(vendor);
            assertThat(literal).as("%s 的资产里没有字面色", vendor.kind()).isNotEmpty();
            for (boolean dark : List.of(false, true)) {
                List<String> painted = renderedLayers(vendor, dark).stream()
                        .map(Layer::hex).toList();
                if (!new LinkedHashSet<>(painted).equals(literal)) {
                    problems.add(vendor.kind() + "（" + (dark ? "暗" : "亮") + "色档）→ 分层算出 "
                            + painted + "，资产里写的是 " + literal
                            + "（商标不许被我们的 CSS 染色）");
                }
            }
            if (!renderedLayers(vendor, false).stream().map(Layer::hex).toList()
                    .equals(renderedLayers(vendor, true).stream().map(Layer::hex).toList())) {
                problems.add(vendor.kind() + " → 两档主题算出的色不同 —— 商标跟着主题走了");
            }
        }
        assertThat(problems)
                .as("官方色没能在 CSS 之后活下来：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    /**
     * 统一出口照旧成立：类挂着（库节点/磁贴还要它取品牌色）、认不出方言时交回 {@code null}。
     *
     * <p>R54 立这条的原因是 D-38：同一个 MySQL 在两个表面长得不一样，而没有任何一处会报错。
     * 换了素材来源，这条的理由一个字都没变。
     */
    @Test
    void theSharedGraphicCarriesTheScopeClassAndStaysHonest() {
        List<String> problems = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            Node graphic = ConnectionCatalog.graphic(vendor.kind(), Icons.SIZE_LABEL);
            if (graphic == null) {
                problems.add(vendor.kind() + " → graphic() 返回 null，登记表里有这一家");
                continue;
            }
            String scope = "adb-db--" + vendor.kind().id();
            if (!graphic.getStyleClass().contains(scope)) {
                problems.add(vendor.kind() + " → 没挂 " + scope + "，还吃品牌色的表面会掉回中性蓝");
            }
        }
        assertThat(problems).as("统一出口没把类带出来：\n  %s", String.join("\n  ", problems))
                .isEmpty();

        assertThat(ConnectionCatalog.graphic(null, Icons.SIZE_LABEL))
                .as("认不出方言时必须交回 null，由调用方兜底 —— 画成某家就是编造")
                .isNull();
    }

    /**
     * 官方色不许改，那就改<b>它坐的表面</b>：底板。
     *
     * <p>暗色档侧栏底是 {@code #172033}，MySQL 的 {@code #00546B} 压上去只有 1.9:1、
     * MariaDB 的海豚 1.2:1 —— 非文本图形要 3:1，也就是说这两家在暗色档等于看不见，
     * 「换成能识别的图标」直接落空。商标条款禁止改色，于是给一层浅色底板，
     * 标志本身一个像素没动。这条守两件事：底板真的算得出浅色，槽位尺寸锁死
     * （树行的缩进全靠图标那一格的宽度，见 H5-k 付过的学费）。
     */
    @Test
    void thePlateKeepsTheMarkReadableInDarkTheme() {
        List<String> problems = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            Plate plate = renderedPlate(vendor);
            if (plate.fill() == null) {
                problems.add(vendor.kind() + " → 底板没有背景色，标志直接画在深底上");
                continue;
            }
            double brightness = plate.fill().getRed() + plate.fill().getGreen()
                    + plate.fill().getBlue();
            if (brightness < 1.5) {
                problems.add(vendor.kind() + " → 底板算出 " + hex(plate.fill())
                        + "，不够浅，压不住官方深色");
            }
            if (plate.width() != Icons.SIZE_LABEL || plate.height() != Icons.SIZE_LABEL) {
                problems.add(vendor.kind() + " → 底板是 " + plate.width() + "×" + plate.height()
                        + "，不是 " + Icons.SIZE_LABEL + "×" + Icons.SIZE_LABEL + "（会顶动缩进）");
            }
        }
        assertThat(problems)
                .as("商标的浅色底板没落地：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    /** 向导第 1 步那颗 MySQL 磁贴：类挂在磁贴自己身上，图形是官方标志。 */
    @Test
    void theWizardTileCarriesTheVendorScopeItself(FxRobot robot) {
        openWizard(robot);
        ToggleButton tile = robot.lookup("#wizard-tile-MYSQL").query();
        assertThat(tile.getStyleClass())
                .as("磁贴没挂上厂商作用域类 —— 还吃品牌色的表面就没了作用域")
                .contains("adb-db--mysql");

        Group mark = WaitForAsyncUtils.waitForAsyncFx(5_000,
                () -> graphicOf(tile));
        List<String> painted = mark.getChildren().stream()
                .map(SVGPath.class::cast).map(SVGPath::getFill)
                .map(c -> hex((Color) c)).toList();
        assertThat(painted)
                .as("磁贴里的图形不是 MySQL 官方标志（缺签名色 #00546b）：%s", painted)
                .contains("#00546b");
    }

    /**
     * ⭐ R54 / R63-3：厂商<b>标志资产</b>只准经 {@link ConnectionCatalog#graphic} 取。
     *
     * <p>某处表面直接写 {@code Icons.brand("mysql", ...)}，就是绕过登记行自己画了一遍：
     * 将来加一种方言，那处不会跟着变，而且没有任何一处会报错。门禁扫源码，
     * 因为「谁在直接取资产」这种违规只在写法里，不在行为里。
     */
    @Test
    void onlyTheSharedOutletAsksForBrandMarks() throws Exception {
        List<String> offenders = new ArrayList<>();
        // ⚠️ 扫整个 ui/（不只 adb-ui-shell）：资产在 adb-ui-core 里定义，将来加的表面
        //    未必落在外壳模块 —— 只扫一个目录等于给这条门禁留了一个免检区。
        Path uiRoot = repositoryRoot().resolve("ui");
        try (var walk = Files.walk(uiRoot)) {
            List<Path> sources = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.getFileName().toString().equals("ConnectionCatalog.java"))
                    .filter(p -> !p.getFileName().toString().equals("Icons.java"))
                    .toList();
            for (Path file : sources) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // 只认**调用点**：资产路径字面量在 Icons/LucideConverter 的注释里出现是
                // 说明文字，不是绕过出口 —— 把它们算成违规只会逼下一个人去改注释。
                var matcher = Pattern.compile("Icons\\.(brand|hasBrandAsset)\\(").matcher(source);
                if (matcher.find()) {
                    offenders.add(repositoryRoot().relativize(file) + " → " + matcher.group());
                }
            }
            assertThat(sources).as("扫描没找到任何主源码 —— 这条门禁是空的").isNotEmpty();
        }
        assertThat(offenders)
                .as("厂商标志必须经 ConnectionCatalog.graphic() 取：\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
    }

    /**
     * 「形状是自己的、颜色跟厂走」那批表面（树里的库/数据库节点）没被这次换素材弄断：
     * 作用域类仍在 app.css 里把品牌色钉到通用图形的填充层上。
     */
    @Test
    void eachVendorScopeClassStillCarriesItsOwnBrandToken() throws Exception {
        String css = Files.readString(repositoryRoot().resolve("ui").resolve("adb-ui-shell")
                .resolve("src").resolve("main").resolve("resources").resolve("io")
                .resolve("agenticdb").resolve("ui").resolve("app.css"), StandardCharsets.UTF_8);
        for (Vendor vendor : VENDORS) {
            String scope = ConnectionCatalog.forKind(vendor.kind()).styleClass();
            assertThat(scope).as("%s 的作用域类", vendor.kind())
                    .isEqualTo("adb-db--" + vendor.kind().id());
            assertThat(css)
                    .as("%s 没把品牌色作用到填充层 —— 通用图形的库节点会统一掉回中性蓝", scope)
                    .containsPattern(scope + "\\s+\\.adb-icon-tone-c\\s*\\{[^}]*-fx-fill: "
                            + "-adb-vendor-" + vendor.kind().id() + ";");
        }
    }

    /**
     * 商标目录里<b>不多不少</b>就是登记过的那六枚。
     *
     * <p>少一枚 = 那家顶通用圆柱（上面几条已经守）；多一枚更糟：一张没人引用的厂商商标
     * 照样打进 jar，白担一份商标风险，而且没有任何界面会发现它躺在那里。
     */
    @Test
    void brandDirectoryHoldsExactlyTheRegisteredMarks() throws Exception {
        Path dir = repositoryRoot().resolve("ui").resolve("adb-ui-core")
                .resolve("src").resolve("main").resolve("resources").resolve("io")
                .resolve("agenticdb").resolve("ui").resolve("icons").resolve("brand");
        Set<String> onDisk;
        try (var walk = Files.list(dir)) {
            onDisk = walk.filter(p -> p.toString().endsWith(".svg"))
                    .map(p -> {
                        String n = p.getFileName().toString();
                        return n.substring(0, n.lastIndexOf('.'));
                    })
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        Set<String> registered = new LinkedHashSet<>();
        for (ConnectionCatalog.Entry entry : ConnectionCatalog.CONNECTIONS) {
            registered.add(entry.brandKey());
        }
        assertThat(onDisk).as("商标目录 %s 是空的 —— 资源没打进模块", dir).isNotEmpty();
        assertThat(onDisk)
                .as("商标目录与登记表不同步（多出来 / 少了）")
                .containsExactlyInAnyOrderElementsOf(registered);
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private void openWizard(FxRobot robot) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            ConnectionWizard wizard = new ConnectionWizard(stage, null,
                    CredentialStore.inMemory(), spec -> null);
            ((StackPane) stage.getScene().getRoot()).getChildren().setAll(wizard.stepContent());
        });
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> { });
    }

    /** 该方言在统一出口里的那枚标志（浅色底板 + 作用域类）。 */
    private static StackPane plate(Vendor vendor) {
        Node node = ConnectionCatalog.graphic(vendor.kind(), Icons.SIZE_LABEL);
        assertThat(node).as("%s 的标志", vendor.kind()).isNotNull();
        return (StackPane) node;
    }

    /** 剥掉底板取回商标自己的分层。 */
    private static List<SVGPath> layersOf(StackPane plate) {
        assertThat(plate.getChildren()).as("底板里就该只有一枚标志").hasSize(1);
        Group mark = (Group) plate.getChildren().get(0);
        return mark.getChildren().stream().map(SVGPath.class::cast).toList();
    }

    /** 一枚标志里的一层：计算填充色 + 路径。 */
    private record Layer(String hex, String path) {
    }

    /**
     * 把一枚标志放进「树上的一行」走完两档 CSS，取回每层的<b>计算填充色</b>与路径。
     *
     * <p>宿主照 {@code SideBar} 的连接行挂类：{@code adb-tree__icon} + 该家的作用域类。
     * 少了这一层，「CSS 没改动官方色」这个结论只是没测过而已。
     */
    private static List<Layer> renderedLayers(Vendor vendor, boolean dark) {
        return WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            StackPane plate = plate(vendor);
            StackPane row = new StackPane(plate);
            row.getStyleClass().addAll("adb-tree__icon",
                    ConnectionCatalog.forKind(vendor.kind()).styleClass());
            StackPane host = new StackPane(row);
            host.getStyleClass().add(dark ? "theme-dark" : "theme-light");
            Scene scene = new Scene(host, 64, 64);
            scene.getStylesheets().add(style(dark
                    ? "/io/agenticdb/ui/theme-dark.css" : "/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            List<SVGPath> layers = layersOf(plate);
            // ⚠️ applyCss() 不向下层叠：先父后子，一层都不能漏。
            host.applyCss();
            row.applyCss();
            plate.applyCss();
            layers.forEach(Node::applyCss);
            return layers.stream()
                    .map(p -> new Layer(hex((Color) p.getFill()), p.getContent()))
                    .toList();
        });
    }

    /** 底板走完暗色档 CSS 之后的计算背景色与槽位尺寸。 */
    private record Plate(Color fill, double width, double height) {
    }

    private static Plate renderedPlate(Vendor vendor) {
        return WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            StackPane plate = plate(vendor);
            StackPane row = new StackPane(plate);
            row.getStyleClass().add("adb-tree__icon");
            StackPane host = new StackPane(row);
            host.getStyleClass().add("theme-dark");
            Scene scene = new Scene(host, 64, 64);
            scene.getStylesheets().add(style("/io/agenticdb/ui/theme-dark.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            host.applyCss();
            row.applyCss();
            plate.applyCss();
            return new Plate(plateColor(plate), plate.minWidth(0), plate.minHeight(0));
        });
    }

    /** 底板算出来的第一层背景填充色；没上色就是 {@code null}。 */
    private static Color plateColor(StackPane plate) {
        var background = plate.getBackground();
        if (background == null || background.getFills().isEmpty()) {
            return null;
        }
        return background.getFills().get(0).getFill() instanceof Color color ? color : null;
    }

    /** 资产里逐层写死的字面色（小写 {@code #rrggbb}）—— 商标通道的颜色真相。 */
    private static Set<String> literalFillsOf(Vendor vendor) {
        String svg = read(brandAsset(vendor));
        Set<String> fills = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("fill=\"(#\\w+)\"").matcher(svg);
        while (matcher.find()) {
            fills.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return fills;
    }

    private static Path brandAsset(Vendor vendor) {
        return repositoryRoot().resolve("ui").resolve("adb-ui-core")
                .resolve("src").resolve("main").resolve("resources").resolve("io")
                .resolve("agenticdb").resolve("ui").resolve("icons").resolve("brand")
                .resolve(ConnectionCatalog.forKind(vendor.kind()).brandKey() + ".svg");
    }

    /** 计算色 → 资产口径的 {@code #rrggbb} 小写字面量（商标不含 alpha，够用）。 */
    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                Math.round(color.getRed() * 255.0),
                Math.round(color.getGreen() * 255.0),
                Math.round(color.getBlue() * 255.0));
    }

    /** 一路剥进容器取第一个 {@code Group}（磁贴 = ToggleButton → VBox → Label → Group）。 */
    private static Group graphicOf(Parent parent) {
        for (Node child : childrenOf(parent)) {
            if (child instanceof Group group) {
                return group;
            }
            if (child instanceof Parent nested) {
                Group found = graphicOf(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new IllegalStateException("磁贴里找不到标志分层：" + parent);
    }

    private static List<Node> childrenOf(Parent parent) {
        if (parent instanceof javafx.scene.control.Labeled labeled) {
            // Label 的 graphic 不在 children 里，在 skin/label 的子节点上 —— 直接取属性。
            Node graphic = labeled.getGraphic();
            if (graphic != null) {
                return List.of(graphic);
            }
        }
        return parent.getChildrenUnmodifiable();
    }

    private static String style(String resource) {
        var url = VendorGlyphTest.class.getResource(resource);
        assertThat(url).as("样式表 %s", resource).isNotNull();
        return url.toExternalForm();
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
