package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
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
                .queryAs(javafx.scene.layout.FlowPane.class)
                .getChildren()).hasSize(DialectKind.values().length);
        assertThat(robot.lookup("#wizard-type-uri").tryQuery()).isPresent();
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

    @Test
    void searchFiltersTheTiles(FxRobot robot) {
        open(robot, null);

        robot.clickOn("#wizard-type-search");
        robot.write("my");

        ToggleButton mysql = robot.lookup("#wizard-tile-MYSQL")
                .queryAs(ToggleButton.class);
        ToggleButton sqlite = robot.lookup("#wizard-tile-SQLITE")
                .queryAs(ToggleButton.class);
        assertThat(mysql.isVisible()).isTrue();
        assertThat(sqlite.isVisible()).as("搜 my 时 SQLite 不该还在").isFalse();
        assertThat(sqlite.isManaged()).isFalse();
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
                        () -> ConnectionWizard.parseUri("oracle://host/db")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持");
    }

    // ─────────────────── 第 2 步：7 个页签 ───────────────────

    @Test
    void propertiesHaveSevenTabsInNavicatOrder(FxRobot robot) {
        open(robot, null);
        goToProperties(robot, DialectKind.POSTGRESQL);

        TabPane tabs = robot.lookup("#wizard-tabs").queryAs(TabPane.class);
        assertThat(tabs.getTabs()).extracting(Tab::getText)
                .containsExactly("常规", "高级", "数据库", "SSL", "SSH", "HTTP", "备注");
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
                .contains("SSH 隧道").contains("ssh -L");
        assertThat(allText(http.getContent()))
                .as("HTTP 页同理，不能假装有字段")
                .contains("不支持 HTTP 隧道");
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
        selectSslMode(robot, ConnectionWizard.SslMode.REQUIRE);
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
        selectSslMode(robot, ConnectionWizard.SslMode.REQUIRE);
        click(robot, "wizard-save");

        assertThat(wizard.dialogResult().orElseThrow().options())
                .containsEntry("sslMode", "REQUIRED")
                .doesNotContainKey("sslmode");
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
                .lookup("#wizard-test-result").queryAs(Label.class).getText().contains("连接成功"));

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
        assertThat(error.getText()).isEqualTo("连接名不能为空");
        assertThat(error.isVisible()).isTrue();
        assertThat(wizard.step()).isEqualTo(2);
        assertThat(wizard.dialogResult()).isEmpty();

        // 填了名字但没选文件：仍停在原地，错误换成文件那条
        robot.clickOn("#wizard-name");
        robot.write("No File");
        click(robot, "wizard-save");
        assertThat(wizard.dialogResult()).isEmpty();
        assertThat(robot.lookup("#wizard-save-error").queryAs(Label.class).getText())
                .contains("数据库文件");
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
                .getValue()).isEqualTo(ConnectionWizard.SslMode.VERIFY_IDENTITY);
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

    // ───────────────────────────── 工具 ─────────────────────────────

    @SuppressWarnings("unchecked")
    private static void selectSslMode(FxRobot robot, ConnectionWizard.SslMode mode) {
        selectTab(robot, "wizard-tab-ssl");
        interact(() -> {
            ComboBox<ConnectionWizard.SslMode> combo = robot.lookup("#wizard-ssl-mode")
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
