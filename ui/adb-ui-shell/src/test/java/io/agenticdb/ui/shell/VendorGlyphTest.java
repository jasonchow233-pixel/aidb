package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.ui.Icons;
import io.agenticdb.ui.Tokens;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R43-a 门禁：<b>每家数据库各一枚厂商图形</b>，而且图形与品牌色真的成对落地。
 *
 * <h2>为什么这条要单独钉</h2>
 * 「图标按厂区分」的症状全都<b>不在编译期</b>：
 * <ol>
 *   <li>两家用了同一枚图形 → 分厂图标等于没画，界面照样能看；</li>
 *   <li>作用域类没挂到容器上 → 品牌色那条后代规则<b>空转</b>，图形掉回中性蓝
 *       （{@code IconKindHueTest} 抓到过同一类坑，所以这里读的是<b>计算色</b>，不是 CSS 文本）；</li>
 *   <li>只验了一档主题 → 暗色下品牌色压在黑底上等于隐形；</li>
 *   <li>登记行漏了一项 → 那家库顶着一颗通用圆柱，用户以为不支持
 *       （所以按 {@link DialectKind#values()} 穷举，不照抄手写清单）。</li>
 * </ol>
 *
 * <p>另一条边界：图形是<b>同风格自绘标识</b>，不是任何厂商的商标图形 ——
 * 可辨识度靠「形状互不相同 + 各自品牌色」两条腿，不靠复刻 logo
 * （竞品位图进产品就是他人版权素材，见 {@code ColoredIconTest} 的硬边界）。
 */
@ExtendWith(ApplicationExtension.class)
class VendorGlyphTest {

    /** 六家真驱动（按方言枚举穷举出来，漏一家这里就少一行）。 */
    private record Vendor(DialectKind kind, String light, String dark) {
    }

    private static final List<Vendor> VENDORS = List.of(
            new Vendor(DialectKind.SQLITE, Tokens.VENDOR_SQLITE_LIGHT, Tokens.VENDOR_SQLITE_DARK),
            new Vendor(DialectKind.POSTGRESQL, Tokens.VENDOR_POSTGRESQL_LIGHT,
                    Tokens.VENDOR_POSTGRESQL_DARK),
            new Vendor(DialectKind.MYSQL, Tokens.VENDOR_MYSQL_LIGHT, Tokens.VENDOR_MYSQL_DARK),
            new Vendor(DialectKind.MARIADB, Tokens.VENDOR_MARIADB_LIGHT,
                    Tokens.VENDOR_MARIADB_DARK),
            new Vendor(DialectKind.SQLSERVER, Tokens.VENDOR_SQLSERVER_LIGHT,
                    Tokens.VENDOR_SQLSERVER_DARK),
            new Vendor(DialectKind.ORACLE, Tokens.VENDOR_ORACLE_LIGHT, Tokens.VENDOR_ORACLE_DARK));

    static {
        // 方言加了项却忘了在这里登记 → 上面那六行会少一家，这条先炸在装配期。
        assertThat(VENDORS).as("每个方言都要有品牌色断言")
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
        //    looked-up color 都解析不出来，图标会算成**黑色**（0x000000）而不是「没上色」。
        //    这正是这条门禁要防的失败模式，所以测试自己先把舞台搭对。
        root.getStyleClass().add("theme-light");
        Scene scene = new Scene(root, 720, 640);
        scene.getStylesheets().add(style("/io/agenticdb/ui/theme-light.css"));
        scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
        stage.setScene(scene);
        stage.show();
    }

    /** 六家各登记一枚图形，且<b>两两不同形</b>（同形 = 分厂图标白画）。 */
    @Test
    void everyDialectHasItsOwnGlyphAndNoTwoShareOne() {
        List<String> shapes = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            ConnectionCatalog.Entry entry = ConnectionCatalog.forKind(vendor.kind());
            assertThat(entry).as("%s 必须在登记表里有自己的一行", vendor.kind()).isNotNull();
            Icons.Icon glyph = entry.glyph();
            assertThat(glyph).as("%s 顶的还是通用圆柱 —— 图形没登记", vendor.kind())
                    .isNotEqualTo(Icons.Icon.DATABASE);
            assertThat(Icons.hasColoredVariant(glyph))
                    .as("%s 的图形 %s 缺彩色资产，磁贴会掉回单色", vendor.kind(), glyph)
                    .isTrue();
            assertThat(shapes).as("%s 与前面某一家用了同一枚形状", vendor.kind())
                    .doesNotContain(glyph.path());
            shapes.add(glyph.path());
        }
    }

    /** 每家的作用域类都要在 app.css 里把品牌色钉到<b>填充层</b>上。 */
    @Test
    void eachVendorScopeClassCarriesItsOwnBrandToken() throws Exception {
        String css = Files.readString(repositoryRoot().resolve("ui").resolve("adb-ui-shell")
                .resolve("src").resolve("main").resolve("resources").resolve("io")
                .resolve("agenticdb").resolve("ui").resolve("app.css"));
        for (Vendor vendor : VENDORS) {
            String scope = ConnectionCatalog.forKind(vendor.kind()).styleClass();
            assertThat(scope).as("%s 的作用域类", vendor.kind())
                    .isEqualTo("adb-db--" + vendor.kind().id());
            assertThat(css)
                    .as("%s 没把品牌色作用到填充层 —— 图形会统一掉回中性蓝", scope)
                    .containsPattern(scope + "\\s+\\.adb-icon-tone-c\\s*\\{[^}]*-fx-fill: "
                            + "-adb-vendor-" + vendor.kind().id() + ";");
        }
    }

    /** 读<b>计算色</b>：挂上作用域类后，填充层在两档主题下都算得出品牌色。 */
    @Test
    void theBrandColorLandsOnTheFillLayerInBothThemes() {
        List<String> problems = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            for (boolean dark : List.of(false, true)) {
                String expected = dark ? vendor.dark() : vendor.light();
                Color painted = brandFill(vendor.kind(), dark);
                if (!Color.web(expected).equals(painted)) {
                    problems.add(vendor.kind() + "（" + (dark ? "暗" : "亮") + "色档）→ 填充层算出 "
                            + painted + "，应为 " + Color.web(expected));
                }
            }
        }
        assertThat(problems)
                .as("品牌色没有真的落到填充层：\n  %s", String.join("\n  ", problems))
                .isEmpty();
    }

    /** 没挂作用域类时必须是中性蓝 —— 否则厂商色漏到了通用图标上，等于到处乱染色。 */
    @Test
    void aGlyphOutsideItsVendorScopeStaysNeutral() {
        Color neutral = WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            Group colored = (Group) Icons.colored(Icons.Icon.DB_MYSQL, Icons.SIZE_LABEL);
            StackPane host = new StackPane(colored);
            host.getStyleClass().add("theme-light");
            Scene scene = new Scene(host, 64, 64);
            scene.getStylesheets().add(style("/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            host.applyCss();
            colored.applyCss();
            colored.getChildren().forEach(Node::applyCss);
            return toneFill(colored);
        });
        assertThat(neutral)
                .as("MySQL 图形落到没有厂商类的宿主里，应当还是中性 icon-tone-c")
                .isEqualTo(Color.web(Tokens.ICON_TONE_C_LIGHT));
    }

    /** 向导第 1 步那颗 MySQL 磁贴：类挂在<b>磁贴自己</b>身上，色落在图形上。 */
    @Test
    void theWizardTileCarriesTheVendorScopeItself(FxRobot robot) {
        openWizard(robot);
        ToggleButton tile = robot.lookup("#wizard-tile-MYSQL").query();
        assertThat(tile.getStyleClass())
                .as("磁贴没挂上厂商作用域类 —— 品牌色那条后代规则就是空转")
                .contains("adb-db--mysql");

        Color painted = WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            Group glyph = (Group) graphicOf(tile);
            glyph.applyCss();
            glyph.getChildren().forEach(Node::applyCss);
            return toneFill(glyph);
        });
        assertThat(painted)
                .as("磁贴里的图形没吃到 MySQL 品牌色")
                .isEqualTo(Color.web(Tokens.VENDOR_MYSQL_LIGHT));
    }

    /**
     * ⭐ R54：图形只有一个出口 —— {@link ConnectionCatalog#graphic}。
     *
     * <p>「取字形 → 上彩色 → 挂作用域类」这三步以前在导航树里内联做过一次，其余表面各自
     * 退回通用图形。分叉过一次就是 D-38 的形状：同一个 MySQL 在两个地方长得不一样，
     * 而没有任何一处会报错。这条钉住出口本身：类挂对了、色真的落下来了、
     * 认不出方言时交回 {@code null}（画成某家就是编造）。
     */
    @Test
    void theSharedGraphicCarriesTheScopeClassAndTheBrandColor() {
        List<String> problems = new ArrayList<>();
        for (Vendor vendor : VENDORS) {
            Node graphic = ConnectionCatalog.graphic(vendor.kind(), Icons.SIZE_LABEL);
            if (graphic == null) {
                problems.add(vendor.kind() + " → graphic() 返回 null，登记表里有这一家");
                continue;
            }
            String scope = "adb-db--" + vendor.kind().id();
            if (!graphic.getStyleClass().contains(scope)) {
                problems.add(vendor.kind() + " → 没挂 " + scope + "，品牌色那条后代规则空转");
                continue;
            }
            Group layers = (Group) graphic;
            Color painted = WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
                StackPane host = new StackPane(layers);
                host.getStyleClass().add("theme-light");
                Scene scene = new Scene(host, 64, 64);
                scene.getStylesheets().add(style("/io/agenticdb/ui/theme-light.css"));
                scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
                // ⚠️ applyCss() 不向下层叠：先父后子，一层都不能漏。
                host.applyCss();
                layers.applyCss();
                layers.getChildren().forEach(Node::applyCss);
                return toneFill(layers);
            });
            if (!Color.web(vendor.light()).equals(painted)) {
                problems.add(vendor.kind() + " → 填充层算出 " + painted + "，应为 "
                        + Color.web(vendor.light()));
            }
        }
        assertThat(problems).as("统一出口没把类和色一起带出来：\n  %s", String.join("\n  ", problems))
                .isEmpty();

        assertThat(ConnectionCatalog.graphic(null, Icons.SIZE_LABEL))
                .as("认不出方言时必须交回 null，由调用方兜底 —— 不能猜一家")
                .isNull();
    }

    /**
     * ⭐ R54：厂商字形字面量<b>只准出现在登记表里</b>。
     *
     * <p>某处表面直接写 {@code Icons.Icon.DB_MYSQL}，就是绕过登记行自己画了一遍：将来加
     * 一种方言，那处不会跟着变，而且没有任何一条编译期或运行期线索能发现它。门禁扫源码，
     * 因为「谁在直接取字形」这种违规只在写法里，不在行为里。
     */
    @Test
    void onlyTheRegistrationRowPicksVendorGlyphs() throws Exception {
        List<String> offenders = new ArrayList<>();
        // ⚠️ 扫整个 ui/（不只 adb-ui-shell）：图形资产在 adb-ui-core 里定义，将来加的表面
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
                String source = Files.readString(file);
                var matcher = java.util.regex.Pattern
                        .compile("Icons\\.Icon\\.DB_[A-Z]+").matcher(source);
                if (matcher.find()) {
                    offenders.add(repositoryRoot().relativize(file) + " → " + matcher.group());
                }
            }
            assertThat(sources).as("扫描没找到任何主源码 —— 这条门禁是空的").isNotEmpty();
        }
        assertThat(offenders)
                .as("厂商图形必须经 ConnectionCatalog.graphic()/forKind().glyph() 取；\n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();
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

    /** 某家图形放进自己的作用域类里走完 CSS，取填充层的计算色。 */
    private Color brandFill(DialectKind kind, boolean dark) {
        ConnectionCatalog.Entry entry = ConnectionCatalog.forKind(kind);
        return WaitForAsyncUtils.waitForAsyncFx(5_000, () -> {
            Group colored = (Group) Icons.colored(entry.glyph(), Icons.SIZE_LABEL);
            colored.getStyleClass().add(entry.styleClass());
            StackPane host = new StackPane(colored);
            host.getStyleClass().add(dark ? "theme-dark" : "theme-light");
            Scene scene = new Scene(host, 64, 64);
            scene.getStylesheets().add(style(dark
                    ? "/io/agenticdb/ui/theme-dark.css" : "/io/agenticdb/ui/theme-light.css"));
            scene.getStylesheets().add(style("/io/agenticdb/ui/app.css"));
            // ⚠️ applyCss() 不向下层叠：先父后子，一层都不能漏。
            host.applyCss();
            colored.applyCss();
            colored.getChildren().forEach(Node::applyCss);
            return toneFill(colored);
        });
    }

    /** 彩色实例里 {@code adb-icon-tone-c}（最深填充面 = 厂商图形的主体）的计算填充色。 */
    private static Color toneFill(Group colored) {
        for (Node child : colored.getChildren()) {
            if (child instanceof SVGPath path && path.getStyleClass().contains("adb-icon-tone-c")) {
                return (Color) path.getFill();
            }
        }
        throw new IllegalStateException("no tone-c layer: " + colored);
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
        throw new IllegalStateException("磁贴里找不到彩色图形分层：" + parent);
    }

    private static List<Node> childrenOf(Parent parent) {
        if (parent instanceof javafx.scene.layout.Region region
                && region instanceof javafx.scene.control.Labeled labeled) {
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
