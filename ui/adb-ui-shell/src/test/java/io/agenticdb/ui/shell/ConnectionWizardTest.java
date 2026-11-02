package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
import io.agenticdb.ui.I18n;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接向导门禁（R33-G8/G20）。
 *
 * <p>挂载方式：{@code ConnectionWizard} 的 DialogPane 归属 JavaFX Dialog 的内部
 * Scene（平台会在脉冲里修正归属，无法稳定挂到外部 Stage），所以测试挂
 * {@link ConnectionWizard#stepContent()} —— 这也正是 4.5-12 把导航按钮
 * 挪进内容区的原因：按钮跟着内容走，才既可交互又可测。
 */
@ExtendWith(ApplicationExtension.class)
class ConnectionWizardTest {

    private Path sqliteFile;
    private AtomicReference<ConnectionSpec> tested;
    private ConnectionWizard wizard;
    private Stage stage;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        sqliteFile = Files.createTempFile("wizard", ".db");
        Files.deleteIfExists(sqliteFile);
        tested = new AtomicReference<>();
        stage.setScene(new Scene(new StackPane(), 720, 640));
        stage.show();
    }

    @BeforeEach
    void resetStage() {
        stage.setWidth(720);
        stage.setHeight(640);
    }

    @AfterEach
    void clean() throws Exception {
        Files.deleteIfExists(sqliteFile);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 新建向导并把第 1 步内容挂上 Stage。 */
    private void open(FxRobot robot, ConnectionProfile initial) {
        interact(() -> {
            wizard = new ConnectionWizard(robot.window(0), initial,
                    CredentialStore.inMemory(), spec -> {
                tested.set(spec);
                return null;
            });
            remount();
        });
        // TestFX 的 NodeQuery 只在一次 FX 同步之后才看得到新挂上的子树。
        robot.interact(() -> { });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 步骤切换后把新内容放进 Scene 的 StackPane 根。 */
    private void remount() {
        if (stage.getScene() == null) {
            stage.setScene(new Scene(new StackPane(), 720, 640));
        }
        ((StackPane) stage.getScene().getRoot()).getChildren().setAll(wizard.stepContent());
    }

    /** 点内容区按钮 + 等待 + 同步内容，等价于一次真实的用户点击。 */
    private void click(FxRobot robot, String id) {
        robot.clickOn("#" + id);
        WaitForAsyncUtils.waitForFxEvents();
        interact(this::remount);
    }

    /** 清空后重填 —— 端口框预填了默认端口，直接 write 会追加成 54335432 并被 parsePort 回落。 */
    private void retype(FxRobot robot, String id, String text) {
        robot.clickOn("#" + id);
        interact(() -> robot.lookup("#" + id).queryTextInputControl().selectAll());
        robot.write(text);
    }

    /** 走完第 1 步（选类型、填名字前先到属性页）。 */
    private void goToProperties(FxRobot robot, DialectKind kind) {
        click(robot, "wizard-tile-" + kind.name());
        click(robot, "wizard-next");
    }

    // ─────────────────── 第 1 步：类型选择页 ───────────────────

    @Test
    void typePageOffersOnlyTheThreeRealDialects(FxRobot robot) {
        open(robot, null);

        for (DialectKind kind : DialectKind.values()) {
            assertThat(robot.lookup("#wizard-tile-" + kind.name()).tryQuery())
                    .as("真实支持的库型都要有磁贴：" + kind)
                    .isPresent();
        }
        // 磁贴总数 = 方言数（不摆没实现的 Oracle / SQL Server 磁贴）
        assertThat(robot.lookup("#wizard-type-tiles")
                .queryAs(javafx.scene.layout.TilePane.class)
                .getChildren()).hasSize(ConnectionCatalog.CONNECTIONS.size());
        assertThat(robot.lookup("#wizard-type-uri").tryQuery()).isPresent();
    }

    /**
     * ⭐ R43-b：类型页只摆<b>真有驱动</b>的六家，一行六张 + 一行「其它怎么走」的提示。
     *
     * <p>原来照 Navicat 17 摆 22 张磁贴（9 类型 + 13 厂商）配 22 个复选与一个搜索框，
     * 其中 16 张点进去是一份「本版不支持」。那不是 Navicat 的形态 —— 它摆这些家是因为
     * 真连得上去；我们摆出来只是让用户多点十六次空。所以这条门禁断的是反方向：
     * 死磁贴、复选栏、搜索框都不许回来。
     */
    @Test
    void typePageShowsOnlyTheSixDriversItActuallyShips(FxRobot robot) {
        open(robot, null);

        assertThat(ConnectionCatalog.CONNECTIONS).hasSize(6);
        for (ConnectionCatalog.Entry entry : ConnectionCatalog.CONNECTIONS) {
            assertThat(robot.lookup("#wizard-tile-" + entry.id()).tryQuery())
                    .as("磁贴：" + entry.name())
                    .isPresent();
        }
        assertThat(robot.lookup("#wizard-tile-MONGODB").tryQuery())
                .as("没有驱动的磁贴不许回来")
                .isEmpty();
        assertThat(robot.lookup("#wizard-filters").tryQuery())
                .as("六张磁贴不需要左栏复选")
                .isEmpty();
        assertThat(robot.lookup("#wizard-type-search").tryQuery())
                .as("六张磁贴不需要搜索框")
                .isEmpty();

        javafx.scene.layout.TilePane tiles = robot.lookup("#wizard-type-tiles")
                .queryAs(javafx.scene.layout.TilePane.class);
        assertThat(tiles.getPrefColumns())
                .as("六张摆成一行，不再为 22 张留多列偏好")
                .isEqualTo(ConnectionCatalog.CONNECTIONS.size());
        assertThat(tiles.getAlignment())
                .as("磁贴块在中间带里居中：类型页沿用属性页的高度，贴顶摆会让下半屏空着")
                .isEqualTo(javafx.geometry.Pos.CENTER);
        // 居中最容易空转：只要 grow 链断一层，磁贴块就只在自己的 pref 框里居中，
        // 画面照旧空着下半屏而属性断言全绿（本轮实拍就是这么抓出来的）。
        // 所以断的是**链路本身**，不是量高度 —— 量高度要等布局，会引进时序假红。
        assertThat(javafx.scene.layout.VBox.getVgrow(tiles))
                .as("tiles 要吃下 body 里多出来的高度")
                .isEqualTo(javafx.scene.layout.Priority.ALWAYS);
        assertThat(tiles.getParent())
                .as("磁贴块外面那一层 VBox 也要愿意长")
                .isInstanceOf(javafx.scene.layout.VBox.class);
        assertThat(javafx.scene.layout.VBox.getVgrow(tiles.getParent()))
                .as("body 要吃下 outer 里多出来的高度（少这一句，居中=空转）")
                .isEqualTo(javafx.scene.layout.Priority.ALWAYS);
        assertThat(tiles.getChildren()).hasSize(6);
        assertThat(tiles.getChildren()).allSatisfy(tile ->
                assertThat(((javafx.scene.Node) tile).isVisible()).isTrue());

        // 告知不铺路（§11）：收成六张之后，「其它数据库怎么办」必须写在页面上，
        // 而不是像以前那样藏进一张点得进去的说明页里。
        assertThat(((javafx.scene.control.Label) robot.lookup("#wizard-type-hint").query())
                .getText()).isEqualTo(I18n.t("wizard.type.hint"));
    }

    /** 选一种进下一步：六家都有表单，不再有「无驱动」分支。 */
    @Test
    void everyTileLeadsToTheRealForm(FxRobot robot) {
        open(robot, null);

        click(robot, "wizard-tile-POSTGRESQL");
        click(robot, "wizard-next");

        assertThat(wizard.step()).isEqualTo(2);
        assertThat(robot.lookup("#wizard-tabs").tryQuery())
                .as("真有驱动：直接进属性页")
                .isPresent();
        assertThat(robot.lookup("#wizard-unsupported").tryQuery())
                .as("说明页随死磁贴一起删了")
                .isEmpty();
    }

    /**
     * ⭐ R33：磁贴是**卡片**（一行摆开），底部是**一行**按钮。
     *
     * <p>实拍踩过两次：① 磁贴原来是 VBox + VGrow，三个磁贴被拉成每个 ~220px 的空白竖条；
     * ② 「取消」留在 Dialog 的按钮栏里，和内容区那行导航叠成上下两层。
     */
    @Test
    void typePageUsesCompactTilesAndASingleFooterRow(FxRobot robot) {
        open(robot, null);

        javafx.scene.layout.Region tile = robot.lookup("#wizard-tile-SQLITE")
                .queryAs(javafx.scene.layout.Region.class);
        assertThat(tile.getPrefHeight())
                .as("磁贴高度必须是卡片档（≤120），不能被 VGrow 拉满整页")
                .isLessThanOrEqualTo(120);
        assertThat(tile.getPrefWidth())
                .as("磁贴宽度是卡片档（≤200），不是整行")
                .isLessThanOrEqualTo(200);

        javafx.scene.Node next = robot.lookup("#wizard-next").query();
        javafx.scene.Node cancel = robot.lookup("#wizard-cancel").query();
        assertThat(cancel.getParent())
                .as("「取消」必须和「下一步」在同一行（同一父节点）")
                .isSameAs(next.getParent());
        assertThat(wizard.dialogButtonTypes())
                .as("dialog 的按钮栏留空 —— 否则会多出一层按钮")
                .isEmpty();
    }

    /** N27-b：属性页底部五键 —— 左「测试连接 / URI…」，右「上一步 / 取消 / 确定」，同一行。 */
    @Test
    void propertiesPageHasTheFiveButtonFooter(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.POSTGRESQL);

        javafx.scene.Node test = robot.lookup("#wizard-test").query();
        javafx.scene.Node uri = robot.lookup("#wizard-uri-open").query();
        javafx.scene.Node back = robot.lookup("#wizard-back").query();
        javafx.scene.Node cancel = robot.lookup("#wizard-cancel").query();
        javafx.scene.Node save = robot.lookup("#wizard-save").query();

        assertThat(List.of(uri, back, cancel, save))
                .as("五颗按钮必须同属一行（同一父节点），不许再叠出第二层")
                .allSatisfy(node -> assertThat(node.getParent()).isSameAs(test.getParent()));
        javafx.scene.layout.HBox row = (javafx.scene.layout.HBox) test.getParent();
        assertThat(row.getChildren()).as("左二 + 弹簧 + 右三").hasSize(6);
        assertThat(row.getChildren().getFirst()).isSameAs(test);
        assertThat(row.getChildren().get(1)).isSameAs(uri);
        assertThat(row.getChildren().indexOf(back))
                .as("左「上一步」，右「取消 / 确定」—— Navicat 的次序")
                .isLessThan(row.getChildren().indexOf(cancel));
        assertThat(row.getChildren().indexOf(cancel))
                .isLessThan(row.getChildren().indexOf(save));
        assertThat(save).as("主动作贴右缘").isSameAs(row.getChildren().getLast());
        assertThat(wizard.dialogTitle()).as("N26：标题走系统标题栏")
                .isEqualTo(I18n.t("wizard.title.new"));
    }

    /** N27-b：常规页有 `AIDB ⟷ Database` 示意图，右边写当前选中的库型。 */
    @Test
    void generalPageShowsTheConnectionDiagram(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.POSTGRESQL);

        javafx.scene.Node diagram = robot.lookup("#wizard-general").query()
                .lookup(".adb-wizard__diagram");
        assertThat(diagram).as("常规页要有示意图").isNotNull();
        assertThat(diagram.lookupAll(".adb-wizard__diagram-name").stream()
                .map(node -> ((javafx.scene.control.Label) node).getText()))
                .containsExactly("AIDB", DialectKind.POSTGRESQL.displayName());
    }

    /** N27：Navicat 17 的这对页面是 900×700 —— 720 宽装不下 5 列磁贴。 */
    @Test
    void wizardOpensAtTheNavicatSize(FxRobot robot) {
        open(robot, null);
        assertThat(wizard.dialogPane().getPrefWidth())
                .as("类型页要 5 列磁贴 + 212px 复选栏")
                .isEqualTo(900);
        assertThat(wizard.dialogPane().getPrefHeight()).isEqualTo(700);
    }

    @Test
    void userInterfaceUriPrefillsEveryFieldAndJumpsToProperties(FxRobot robot) {
        open(robot, null);

        click(robot, "wizard-type-uri");
        robot.clickOn("#wizard-uri");
        robot.write("postgresql://app:s3cret@db.example.com:5433/shop");
        click(robot, "wizard-uri-apply");

        assertThat(wizard.step()).isEqualTo(2);
        assertThat(robot.lookup("#wizard-name").queryTextInputControl().getText())
                .as("连接名按库名自动给出，用户可改").contains("shop");
        assertThat(robot.lookup("#wizard-host").queryTextInputControl().getText())
                .isEqualTo("db.example.com");
        assertThat(robot.lookup("#wizard-port").queryTextInputControl().getText())
                .isEqualTo("5433");
        assertThat(robot.lookup("#wizard-database").queryTextInputControl().getText())
                .isEqualTo("shop");
        assertThat(robot.lookup("#wizard-username").queryTextInputControl().getText())
                .isEqualTo("app");
        assertThat(robot.lookup("#wizard-password").queryTextInputControl().getText())
                .isEqualTo("s3cret");
    }

    @Test
    void uriParserRejectsUnknownSchemesAndReadsSqlitePaths() {
        ConnectionWizard.UriTarget sqlite =
                ConnectionWizard.parseUri("sqlite:C:/data/shop.db");
        assertThat(sqlite.kind()).isEqualTo(DialectKind.SQLITE);
        assertThat(sqlite.database()).isEqualTo("C:/data/shop.db");

        ConnectionWizard.UriTarget mysql =
                ConnectionWizard.parseUri("mysql://root@127.0.0.1/orders");
        assertThat(mysql.kind()).isEqualTo(DialectKind.MYSQL);
        assertThat(mysql.port()).isEqualTo(ConnectionProfile.defaultPort(DialectKind.MYSQL));
        assertThat(mysql.password()).isEmpty();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> ConnectionWizard.parseUri("mongodb://host/db")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(I18n.t("wizard.uri.unsupported"));
    }

    @Test
    void uriParserReadsEveryRegisteredSchemeAndItsParameterStyle() {
        // Oracle 的 EZConnect 写法（驱动文档口径）也要能粘进 URI 框。
        ConnectionWizard.UriTarget oracle =
                ConnectionWizard.parseUri("jdbc:oracle:thin:@//db.local:1521/ORCLPDB");
        assertThat(oracle.kind()).isEqualTo(DialectKind.ORACLE);
        assertThat(oracle.port()).isEqualTo(1521);
        assertThat(oracle.database()).isEqualTo("ORCLPDB");

        // SQL Server 的参数用分号、库名在 databaseName 上 —— 按 ? 切会拿到一个错库名。
        ConnectionWizard.UriTarget mssql = ConnectionWizard.parseUri(
                "jdbc:sqlserver://db.local:1433;databaseName=shop;encrypt=true");
        assertThat(mssql.kind()).isEqualTo(DialectKind.SQLSERVER);
        assertThat(mssql.database()).isEqualTo("shop");
        assertThat(mssql.options()).containsEntry("encrypt", "true");

        // 查询串的参数不再被静默丢掉（以前界面上显示「不加密」，URI 里写的是 require）。
        ConnectionWizard.UriTarget pg = ConnectionWizard.parseUri(
                "postgresql://app@db.local/shop?sslmode=require");
        assertThat(pg.options()).containsEntry("sslmode", "require");
    }

    @Test
    void uriParametersReachTheTlsControls(FxRobot robot) {
        open(robot, null);
        click(robot, "wizard-type-uri");
        robot.clickOn("#wizard-uri");
        robot.write("postgresql://app@db.local:5432/shop?sslmode=require");
        click(robot, "wizard-uri-apply");

        selectTab(robot, "wizard-tab-ssl");
        @SuppressWarnings("unchecked")
        ComboBox<Dialect.SslLevel> combo = robot.lookup("#wizard-ssl-mode")
                .queryAs(ComboBox.class);
        assertThat(combo.getValue()).as("URI 里的档位要回填下拉，不能显示「不加密」")
                .isEqualTo(Dialect.SslLevel.REQUIRE);
        assertThat(robot.lookup("#wizard-extra").queryAs(javafx.scene.control.TextArea.class)
                .getText()).as("TLS 页管的键不再重复出现在附加参数里").doesNotContain("sslmode");
    }

    @Test
    void tlsLevelsAreOnlyWhatTheDriverCanExpress(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.MARIADB);
        robot.clickOn("#wizard-name");
        robot.write("Maria SSL");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");

        selectTab(robot, "wizard-tab-ssl");
        ComboBox<Dialect.SslLevel> combo = robot.lookup("#wizard-ssl-mode").queryAs(ComboBox.class);
        assertThat(combo.getItems())
                .as("mariadb-java-client 的 SslMode 枚举里没有 prefer 这一档")
                .containsExactly(Dialect.SslLevel.OFF, Dialect.SslLevel.REQUIRE,
                        Dialect.SslLevel.VERIFY_CA, Dialect.SslLevel.VERIFY_IDENTITY);
        assertThat(robot.lookup("#wizard-ssl-root").queryTextInputControl().isDisabled())
                .as("MariaDB 收 serverSslCert，根证书框可用")
                .isFalse();

        selectSslMode(robot, Dialect.SslLevel.REQUIRE);
        click(robot, "wizard-save");
        assertThat(wizard.dialogResult().orElseThrow().options())
                .as("「必须加密」在 MariaDB 驱动里叫 TRUST，不是 PG 的 require")
                .containsEntry("sslMode", "TRUST")
                .doesNotContainKey("sslmode");
    }

    @Test
    void dialectsWithoutASelectableTlsLevelSaySoInsteadOfFakeControls(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.SQLSERVER);
        selectTab(robot, "wizard-tab-ssl");

        TabPane tabs = robot.lookup("#wizard-tabs").queryAs(TabPane.class);
        Tab ssl = tabs.getTabs().get(3);
        assertThat(ssl.getContent().lookup("#wizard-notice")).isNotNull();
        assertThat(allText(ssl.getContent()))
                .as("SQL Server 要两个参数配对，界面必须说清楚去哪填")
                .contains("trustServerCertificate");
    }

    // ─────────────────── 第 2 步：7 个页签 ───────────────────

    @Test
    void propertiesHaveSevenTabsInNavicatOrder(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.POSTGRESQL);

        TabPane tabs = robot.lookup("#wizard-tabs").queryAs(TabPane.class);
        assertThat(tabs.getTabs()).extracting(Tab::getText)
                .containsExactly(I18n.t("wizard.tab.general"), I18n.t("wizard.tab.advanced"),
                        I18n.t("wizard.tab.databases"), I18n.t("wizard.tab.ssl"),
                        I18n.t("wizard.tab.ssh"), I18n.t("wizard.tab.http"),
                        I18n.t("wizard.tab.remarks"));
    }

    @Test
    void sshAndHttpPagesSayWhatIsMissingInsteadOfFakeFields(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.POSTGRESQL);

        TabPane tabs = robot.lookup("#wizard-tabs").queryAs(TabPane.class);
        Tab ssh = tabs.getTabs().get(4);
        Tab http = tabs.getTabs().get(5);

        assertThat(ssh.getContent().lookup("#wizard-notice")).isNotNull();
        assertThat(allText(ssh.getContent()))
                .as("SSH 页必须说清楚没有隧道引擎，并给替代做法")
                .contains(I18n.t("wizard.ssh.notice"))
                .contains(I18n.t("wizard.ssh.workaround"));
        assertThat(allText(http.getContent()))
                .as("HTTP 页同理，不能假装有字段")
                .contains(I18n.t("wizard.http.notice"));
    }

    @Test
    void sslModeMapsToTheDialectSpecificDriverProperty(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.POSTGRESQL);

        robot.clickOn("#wizard-name");
        robot.write("PG SSL");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        selectSslMode(robot, Dialect.SslLevel.REQUIRE);
        click(robot, "wizard-save");

        assertThat(wizard.dialogResult().orElseThrow().options())
                .containsEntry("sslmode", "require");

        // MySQL 用另一个键和另一套取值 —— 同一档「必须加密」
        open(robot, null);
        goToProperties(robot, DialectKind.MYSQL);
        robot.clickOn("#wizard-name");
        robot.write("MySQL SSL");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        selectSslMode(robot, Dialect.SslLevel.REQUIRE);
        click(robot, "wizard-save");

        assertThat(wizard.dialogResult().orElseThrow().options())
                .containsEntry("sslMode", "REQUIRED")
                .doesNotContainKey("sslmode");
    }

    /**
     * ⭐ D-33 / D-31：TLS 页的<b>说明</b>与「不加密」这一档都要对得上驱动的真本事。
     *
     * <p>两件事各由一条实测撑着：
     * <ul>
     *   <li>MySQL 选了校验档，界面不能只说「本版不改根证书这一格」——用户要知道的是
     *       <b>那该给什么</b>。Connector/J 8.2 的 {@code getPropertyInfo} 里既没有
     *       {@code sslCA} 也没有 {@code sslCapath}，能给的只有 PKCS12 信任库三件套，
     *       所以这句里必须出现 {@code trustCertificateKeyStoreUrl}（两种语言都有这个词）。</li>
     *   <li>「不加密」这一档以前不写任何参数，而驱动默认是「能加密就加密」
     *       （真库 TLS 对端实测：会话读回 {@code TLS_AES_256_GCM_SHA384}）。
     *       门禁把「标签说什么」钉成「保存下来的是什么参数」，DialectTest 那条钉的是映射本身。</li>
     * </ul>
     */
    @Test
    void tlsPageSaysWhatEachDriverCanBeGivenAndOffReallyMeansOff(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.MYSQL);
        robot.clickOn("#wizard-name");
        robot.write("MySQL TLS 说明");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        selectTab(robot, "wizard-tab-ssl");
        selectSslMode(robot, Dialect.SslLevel.VERIFY_CA);

        assertThat(robot.lookup("#wizard-ssl-root").queryTextInputControl().isDisabled())
                .as("这一家收不了 PEM，输入框必须禁用而不是让人填了不生效")
                .isTrue();
        String mysqlHint = robot.lookup("#wizard-ssl-hint").queryAs(Label.class).getText();
        assertThat(mysqlHint)
                .as("说明要给能用的通道，不能只说「这一格不动」")
                .contains("trustCertificateKeyStoreUrl");

        // 「不加密」保存下来必须是一条显式关闭的参数（不是「什么都不写」）。
        open(robot, null);
        goToProperties(robot, DialectKind.MYSQL);
        robot.clickOn("#wizard-name");
        robot.write("MySQL 明文");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        selectSslMode(robot, Dialect.SslLevel.OFF);
        click(robot, "wizard-save");
        assertThat(wizard.dialogResult().orElseThrow().options())
                .as("靠驱动默认值来实现「不加密」= 标签骗人（D-33）")
                .containsEntry("sslMode", "DISABLED");

        // MariaDB 反过来：PEM 直接可用，输入框不许禁，说明里也不许留「加密没跑过」那句旧话。
        open(robot, null);
        goToProperties(robot, DialectKind.MARIADB);
        robot.clickOn("#wizard-name");
        robot.write("Maria 根证书");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        selectTab(robot, "wizard-tab-ssl");
        selectSslMode(robot, Dialect.SslLevel.VERIFY_CA);

        assertThat(robot.lookup("#wizard-ssl-root").queryTextInputControl().isDisabled())
                .as("MariaDB 收 serverSslCert，根证书框可用")
                .isFalse();
        String mariaHint = robot.lookup("#wizard-ssl-hint").queryAs(Label.class).getText();
        assertThat(mariaHint)
                .as("R47 已经在 Docker 的 TLS 对端上跑过加密连接，这句旧话必须翻掉")
                .doesNotContain("没跑")
                .doesNotContain("did not");
        click(robot, "wizard-save");
        assertThat(wizard.dialogResult().orElseThrow().options())
                .as("MariaDB 的「校验 CA」落到自己的取值上，不是 MySQL 的 REQUIRED")
                .containsEntry("sslMode", "VERIFY_CA")
                .doesNotContainKey("sslrootcert");
    }

    /**
     * 上面那条走的是渲染口，而界面默认语言是<b>英文</b>（{@code I18n.DEFAULT_LOCALE}）——
     * 于是它只钉得住英文那份文案。中文那份要是漂回「本版不改根证书这一格」或者
     * 重新长出「加密连接没跑过」，渲染口这条是看不见的，所以两种语言各钉一次。
     *
     * <p>⭐ 变异验证抓出这条门禁<b>原来的形状不够</b>：把中文 mysqlHint 末尾那句
     * 「都在本机 Docker 的 TLS 对端上跑过」换成「未在真库验证」，一条都不红 ——
     * 它只查信任库那两个 token 在不在，没查<b>结论</b>在不在；旧话术的黑名单里只写了
     * 「没跑」，新说法从缝里过去了。所以改成两件事一起断：
     * ① 两条 hint × 两份 bundle 都要<b>正面写出</b>跑过（中文钉「TLS 对端上跑过」，
     * 英文钉 {@code local Docker TLS peer}）；② 旧话术（中文「未在真库验证 / 没跑」、
     * 英文 {@code never run / not verified}）漂回任何一句都红。
     */
    @Test
    void tlsHintsSayTheSameThingInBothLanguages() {
        try {
            I18n.setLocale(java.util.Locale.ENGLISH);
            assertThat(I18n.t("wizard.ssl.mysqlHint"))
                    .as("英文侧也得给出能用的那条通道")
                    .contains("trustCertificateKeyStoreUrl")
                    .contains("silently ignored")
                    .contains("local Docker TLS peer")
                    .doesNotContain("never run")
                    .doesNotContain("not verified");
            assertThat(I18n.t("wizard.ssl.mariadbHint"))
                    .contains("serverSslCert")
                    .contains("local Docker TLS peer")
                    .doesNotContain("did not")
                    .doesNotContain("never run");

            I18n.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            assertThat(I18n.t("wizard.ssl.mysqlHint"))
                    .as("中文侧同样要写着信任库的参数名（这一格对 MySQL 用不上，理由是驱动没有 CA 文件参数）")
                    .contains("trustCertificateKeyStoreUrl")
                    .contains("静默忽略")
                    .contains("TLS 对端上跑过")
                    .doesNotContain("未在真库验证")
                    .doesNotContain("没跑");
            assertThat(I18n.t("wizard.ssl.mariadbHint"))
                    .contains("serverSslCert")
                    .contains("TLS 对端上跑过")
                    .doesNotContain("没跑")
                    .doesNotContain("未在真库验证");
        } finally {
            I18n.setLocale(I18n.DEFAULT_LOCALE);
        }
    }

    @Test
    void advancedFieldsAndUnknownParametersReachTheProfile(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.MYSQL);

        robot.clickOn("#wizard-name");
        robot.write("Shop");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        selectTab(robot, "wizard-tab-advanced");
        robot.clickOn("#wizard-query-timeout");
        robot.write("15");
        robot.clickOn("#wizard-charset");
        robot.write("UTF-8");
        robot.clickOn("#wizard-extra");
        robot.write("serverTimezone=Asia/Shanghai");
        selectTab(robot, "wizard-tab-remarks");
        robot.clickOn("#wizard-remarks");
        robot.write("生产库，只读账号");
        click(robot, "wizard-save");

        ConnectionProfile saved = wizard.dialogResult().orElseThrow();
        assertThat(saved.options())
                .containsEntry("queryTimeout", "15")
                .containsEntry("characterEncoding", "UTF-8")
                .containsEntry("serverTimezone", "Asia/Shanghai")
                .containsEntry("remarks", "生产库，只读账号");
    }

    // ─────────────────── 既有回归（4.5-12 / 回填 / 口令） ───────────────────

    @Test
    void completesStepsAndReturnsProfileWithoutPassword(FxRobot robot) throws Exception {
        open(robot, null);

        goToProperties(robot, DialectKind.SQLITE);
        robot.clickOn("#wizard-name");
        robot.write("Local SQLite");
        robot.clickOn("#wizard-file");
        robot.write(sqliteFile.toString());
        click(robot, "wizard-test");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot
                .lookup("#wizard-test-result").queryAs(Label.class).getText().contains(I18n.t("wizard.test.ok")));

        click(robot, "wizard-save");

        Optional<ConnectionProfile> result = wizard.dialogResult();
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().kind()).isEqualTo(DialectKind.SQLITE);
        assertThat(result.orElseThrow().name()).isEqualTo("Local SQLite");
        assertThat(result.orElseThrow().database()).isEqualTo(sqliteFile.toString());
        assertThat(tested.get()).isNotNull();
        assertThat(wizard.password()).isEmpty();
    }

    @Test
    void serverWizardCollectsHostPortDatabaseUser(FxRobot robot) {
        open(robot, null);

        goToProperties(robot, DialectKind.POSTGRESQL);
        robot.clickOn("#wizard-name");
        robot.write("PG");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        retype(robot, "wizard-port", "5433");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        robot.clickOn("#wizard-username");
        robot.write("app");
        robot.clickOn("#wizard-password");
        robot.write("s3cret");
        PasswordField password = robot.lookup("#wizard-password").queryAs(PasswordField.class);
        assertThat(password.getText()).isEqualTo("s3cret");

        click(robot, "wizard-save");

        assertThat(wizard.dialogResult().orElseThrow().host()).isEqualTo("db.example.com");
        assertThat(wizard.dialogResult().orElseThrow().port()).isEqualTo(5433);
        assertThat(wizard.dialogResult().orElseThrow().database()).isEqualTo("shop");
        assertThat(wizard.dialogResult().orElseThrow().username()).isEqualTo("app");
        assertThat(wizard.password()).as("勾着「保存口令」时口令要交给调用方落库")
                .isEqualTo("s3cret");
    }

    @Test
    void savePasswordOffWithholdsTheSecret(FxRobot robot) {
        open(robot, null);

        goToProperties(robot, DialectKind.POSTGRESQL);
        robot.clickOn("#wizard-name");
        robot.write("PG");
        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        robot.clickOn("#wizard-password");
        robot.write("s3cret");
        robot.clickOn("#wizard-save-password");

        click(robot, "wizard-save");
        assertThat(wizard.dialogResult()).isPresent();
        assertThat(wizard.password()).as("用户明确说不保存口令").isEmpty();
    }

    /**
     * 回归（4.5-12）：DialogPane 会给按钮栏里每个按钮挂 setResultAndClose，
     * 点「下一步」曾经把整个向导关掉。现在导航按钮在内容区，且校验失败不关窗。
     */
    @Test
    void clickingNextDoesNotCloseWizard(FxRobot robot) {
        open(robot, null);

        goToProperties(robot, DialectKind.POSTGRESQL);
        assertThat(robot.lookup("#wizard-host").queryAs(TextField.class)).isNotNull();
        assertThat(wizard.step()).isEqualTo(2);

        click(robot, "wizard-back");
        assertThat(robot.lookup("#wizard-type-tiles").tryQuery()).isPresent();
        assertThat(wizard.step()).isEqualTo(1);
    }

    /** 回归：校验失败必须留在属性页，并把原因写在常驻错误行里，不能静默 return。 */
    @Test
    void showsValidationErrorWithoutClosingWizard(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.SQLITE);

        // 不填名字直接确定：应留在属性页并给出可见错误
        click(robot, "wizard-save");

        Label error = robot.lookup("#wizard-save-error").queryAs(Label.class);
        assertThat(error.getText()).isEqualTo(I18n.t("wizard.error.name"));
        assertThat(error.isVisible()).isTrue();
        assertThat(wizard.step()).isEqualTo(2);
        assertThat(wizard.dialogResult()).isEmpty();

        // 填了名字但没选文件：仍停在原地，错误换成文件那条
        robot.clickOn("#wizard-name");
        robot.write("No File");
        click(robot, "wizard-save");
        assertThat(wizard.dialogResult()).isEmpty();
        assertThat(robot.lookup("#wizard-save-error").queryAs(Label.class).getText())
                .isEqualTo(I18n.t("wizard.error.file"));
    }

    @Test
    void editingExistingProfilePrefillsAndKeepsId(FxRobot robot) {
        ConnectionProfile existing =
                new ConnectionProfile("fixed-id", "My PG", DialectKind.POSTGRESQL, "db.local", 5432,
                        "shop", "app", true, 42L, "生产",
                        java.util.Map.of("sslmode", "verify-full", "queryTimeout", "20",
                                "remarks", "老连接"));
        open(robot, existing);

        goToProperties(robot, DialectKind.POSTGRESQL);
        assertThat(robot.lookup("#wizard-name").queryTextInputControl().getText())
                .isEqualTo("My PG");
        assertThat(robot.lookup("#wizard-host").queryTextInputControl().getText())
                .isEqualTo("db.local");
        assertThat(robot.lookup("#wizard-query-timeout").queryTextInputControl().getText())
                .isEqualTo("20");
        assertThat(((ComboBox<?>) robot.lookup("#wizard-ssl-mode").queryAs(ComboBox.class))
                .getValue()).isEqualTo(Dialect.SslLevel.VERIFY_IDENTITY);
        assertThat(robot.lookup("#wizard-remarks").queryAs(javafx.scene.control.TextArea.class)
                .getText()).isEqualTo("老连接");

        click(robot, "wizard-save");
        ConnectionProfile saved = wizard.dialogResult().orElseThrow();
        assertThat(saved.id()).isEqualTo("fixed-id");
        assertThat(saved.favorite()).isTrue();
        assertThat(saved.group()).isEqualTo("生产");
        assertThat(saved.options()).containsEntry("sslmode", "verify-full");
    }

    @Test
    void switchingTypeUpdatesTheDefaultPort(FxRobot robot) {
        open(robot, null);

        goToProperties(robot, DialectKind.POSTGRESQL);
        assertThat(robot.lookup("#wizard-port").queryTextInputControl().getText())
                .isEqualTo("5432");

        click(robot, "wizard-back");
        click(robot, "wizard-tile-MYSQL");
        click(robot, "wizard-next");
        assertThat(robot.lookup("#wizard-port").queryTextInputControl().getText())
                .as("没手改过端口时跟着库型换默认值").isEqualTo("3306");
    }

    @Test
    void everyRegisteredDialectHasItsOwnTile(FxRobot robot) {
        open(robot, null);
        for (DialectKind kind : DialectKind.values()) {
            ToggleButton tile = robot.lookup("#wizard-tile-" + kind.name())
                    .queryAs(ToggleButton.class);
            assertThat(tile.isVisible())
                    .as("方言层登记了 %s，类型页就必须摆它的磁贴", kind)
                    .isTrue();
            assertThat(tile.getGraphic())
                    .as("磁贴是图标 + 名字 + 一句说明，不是一个纯文字按钮")
                    .isNotNull();
        }
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    @SuppressWarnings("unchecked")
    private static void selectSslMode(FxRobot robot, Dialect.SslLevel mode) {
        selectTab(robot, "wizard-tab-ssl");
        interact(() -> {
            ComboBox<Dialect.SslLevel> combo = robot.lookup("#wizard-ssl-mode")
                    .queryAs(ComboBox.class);
            combo.getSelectionModel().select(mode);
        });
    }

    /** 选中某个页签（Tab 只有 id、不是场景图里的可点节点）。 */
    private static void selectTab(FxRobot robot, String tabId) {
        TabPane tabs = robot.lookup("#wizard-tabs").queryAs(TabPane.class);
        tabs.getTabs().stream()
                .filter(tab -> tabId.equals(tab.getId()))
                .findFirst()
                .ifPresent(tab -> tabs.getSelectionModel().select(tab));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 节点树里的全部文字（页签内容可能套了几层容器）。 */
    private static String allText(javafx.scene.Node node) {
        StringBuilder text = new StringBuilder();
        collectText(node, text);
        return text.toString();
    }

    private static void collectText(javafx.scene.Node node, StringBuilder text) {
        if (node instanceof Label label && label.getText() != null) {
            text.append(label.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, text);
            }
        }
    }
}
