package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.session.CredentialStore;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;
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
 * 连接向导门禁：三步流转、输入不丢、校验不关窗、保存才出结果。
 *
 * <p>挂载方式：{@code ConnectionWizard} 的 DialogPane 归属 JavaFX Dialog 的内部
 * Scene（平台会在脉冲里修正归属，无法稳定挂到外部 Stage），所以测试挂
 * {@link ConnectionWizard#stepContent()}——这也正是 4.5-12 把导航按钮
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
        stage.setScene(new Scene(new StackPane(), 640, 560));
        stage.show();
    }

    @BeforeEach
    void resetStage() {
        stage.setWidth(640);
        stage.setHeight(560);
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
        // TestFX 的 NodeQuery 只在一次 FX 同步之后才看得到新挂上的子树
        // （robot.clickOn 内部会做这次同步，裸 lookup 不会）。
        robot.interact(() -> { });
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** 步骤切换后把新内容放进 Scene 的 StackPane 根（DialogPane 不能挂，内容节点可以）。 */
    private void remount() {
        if (stage.getScene() == null) {
            stage.setScene(new Scene(new StackPane(), 640, 560));
        }
        ((StackPane) stage.getScene().getRoot()).getChildren().setAll(wizard.stepContent());
    }

    /** 点内容区按钮 + 等待 + 同步内容，等价于一次真实的用户点击。 */
    private void click(FxRobot robot, String id) {
        robot.clickOn("#" + id);
        WaitForAsyncUtils.waitForFxEvents();
        interact(this::remount);
    }

    @Test
    void completesThreeStepsAndReturnsProfileWithoutPassword(FxRobot robot) throws Exception {
        open(robot, null);

        robot.clickOn("#wizard-name");
        robot.write("Local SQLite");
        click(robot, "wizard-next");

        robot.clickOn("#wizard-file");
        robot.write(sqliteFile.toString());
        click(robot, "wizard-next");

        click(robot, "wizard-test");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> robot
                .lookup("#wizard-test-result").queryAs(Label.class).getText().contains("连接成功"));

        click(robot, "wizard-save");

        Optional<io.agenticdb.session.ConnectionProfile> result = wizard.dialogResult();
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().kind()).isEqualTo(io.agenticdb.jdbc.DialectKind.SQLITE);
        assertThat(result.orElseThrow().name()).isEqualTo("Local SQLite");
        assertThat(result.orElseThrow().database()).isEqualTo(sqliteFile.toString());
        assertThat(tested.get()).isNotNull();
        assertThat(wizard.password()).isEmpty();
    }

    @Test
    void serverWizardCollectsHostPortDatabaseUser(FxRobot robot) throws Exception {
        open(robot, null);

        robot.clickOn("#wizard-type");
        robot.push(javafx.scene.input.KeyCode.DOWN);
        robot.push(javafx.scene.input.KeyCode.ENTER);
        robot.clickOn("#wizard-name");
        robot.write("PG");
        click(robot, "wizard-next");

        robot.clickOn("#wizard-host");
        robot.write("db.example.com");
        robot.clickOn("#wizard-port");
        robot.write("5433");
        robot.clickOn("#wizard-database");
        robot.write("shop");
        robot.clickOn("#wizard-username");
        robot.write("app");
        robot.clickOn("#wizard-password");
        robot.write("s3cret");
        PasswordField password = robot.lookup("#wizard-password").queryAs(PasswordField.class);
        assertThat(password.getText()).isEqualTo("s3cret");

        click(robot, "wizard-next");
        click(robot, "wizard-save");

        assertThat(wizard.dialogResult().orElseThrow().host()).isEqualTo("db.example.com");
        assertThat(wizard.dialogResult().orElseThrow().port()).isEqualTo(5433);
        assertThat(wizard.dialogResult().orElseThrow().database()).isEqualTo("shop");
        assertThat(wizard.dialogResult().orElseThrow().username()).isEqualTo("app");
    }

    /**
     * 回归（4.5-12）：DialogPane 会给按钮栏里每个按钮挂 setResultAndClose，
     * 点「下一步」曾经把整个向导关掉。现在导航按钮在内容区，且校验失败不关窗。
     */
    @Test
    void clickingNextDoesNotCloseWizard(FxRobot robot) throws Exception {
        open(robot, null);

        robot.clickOn("#wizard-name");
        robot.write("Local SQLite");
        click(robot, "wizard-next");

        // 向导还活着：第 2 步的文件输入框仍在场景里
        assertThat(robot.lookup("#wizard-file").queryTextInputControl()).isNotNull();
        assertThat(wizard.step()).isEqualTo(2);

        click(robot, "wizard-back");
        assertThat(robot.lookup("#wizard-name").queryTextInputControl()).isNotNull();
        assertThat(wizard.step()).isEqualTo(1);

        // 输入没丢（可回退且不丢输入）
        assertThat(robot.lookup("#wizard-name").queryTextInputControl().getText())
                .isEqualTo("Local SQLite");
    }

    /** 回归：校验失败必须留在当前步，并把原因写在常驻错误行里，不能静默 return。 */
    @Test
    void showsValidationErrorWithoutClosingWizard(FxRobot robot) throws Exception {
        open(robot, null);

        // 不填名字直接下一步：应停在第 1 步并给出可见错误
        click(robot, "wizard-next");

        Label error = robot.lookup("#wizard-save-error").queryAs(Label.class);
        assertThat(error.getText()).isEqualTo("连接名称不能为空");
        assertThat(error.isVisible()).isTrue();
        assertThat(robot.lookup("#wizard-name").queryTextInputControl()).isNotNull();
        assertThat(wizard.step()).isEqualTo(1);

        // 第 2 步同理：不填数据库文件，点下一步停在原地且错误可见
        robot.clickOn("#wizard-name");
        robot.write("No File");
        click(robot, "wizard-next");
        assertThat(wizard.step()).isEqualTo(2);

        click(robot, "wizard-next");
        assertThat(wizard.step()).isEqualTo(2);
        Label step2Error = robot.lookup("#wizard-save-error").queryAs(Label.class);
        assertThat(step2Error.getText()).contains("数据库文件");
        assertThat(step2Error.isVisible()).isTrue();
        assertThat(wizard.dialogResult()).isEmpty();
    }

    @Test
    void editingExistingProfilePrefillsAndKeepsId(FxRobot robot) throws Exception {
        ConnectionProfile existing =
                new ConnectionProfile("fixed-id", "My PG",
                        io.agenticdb.jdbc.DialectKind.SQLITE, "", 0,
                        sqliteFile.toString(), "", true, 42L, "本地", java.util.Map.of());
        open(robot, existing);

        // 第 1 步回填名称
        assertThat(robot.lookup("#wizard-name").queryTextInputControl().getText())
                .isEqualTo("My PG");

        // 第 2 步回填数据库文件
        click(robot, "wizard-next");
        assertThat(robot.lookup("#wizard-file").queryTextInputControl().getText())
                .isEqualTo(sqliteFile.toString());

        click(robot, "wizard-next");
        click(robot, "wizard-save");

        assertThat(wizard.dialogResult().orElseThrow().id()).isEqualTo("fixed-id");
        assertThat(wizard.dialogResult().orElseThrow().favorite()).isTrue();
    }
}
