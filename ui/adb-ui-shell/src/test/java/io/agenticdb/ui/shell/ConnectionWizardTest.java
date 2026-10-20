package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.session.CredentialStore;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextInputControl;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
        stage.setScene(new Scene(new javafx.scene.layout.VBox(), 320, 240));
        stage.show();
    }

    @AfterEach
    void clean() throws Exception {
        Files.deleteIfExists(sqliteFile);
    }

    private static void interact(Runnable action) {
        WaitForAsyncUtils.waitForAsyncFx(5_000, action);
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * JavaFX 的 {@code HeavyweightDialog} 已经把 DialogPane 设为它自己内部 Scene 的根，
     * 所以不能再 {@code new Scene(dialogPane)}。这里直接复用对话框内部的那个 Scene。
     */
    private void mount(ConnectionWizard wizard) {
        Scene dialogScene = wizard.dialogPane().getScene();
        assertThat(dialogScene).isNotNull();
        stage.setScene(dialogScene);
        stage.setWidth(640);
        stage.setHeight(520);
        stage.show();
    }

    @Test
    void completesThreeStepsAndReturnsProfileWithoutPassword(FxRobot robot) throws Exception {
        interact(() -> {
            wizard = new ConnectionWizard(robot.window(0), null,
                    CredentialStore.inMemory(), spec -> {
                tested.set(spec);
                return null;
            });
            mount(wizard);
        });

        TextInputControl name = robot.lookup("#wizard-name").queryTextInputControl();
        robot.clickOn(name);
        robot.write("Local SQLite");
        robot.clickOn("#wizard-next");
        WaitForAsyncUtils.waitForFxEvents();

        TextInputControl file = robot.lookup("#wizard-file").queryTextInputControl();
        robot.clickOn(file);
        robot.write(sqliteFile.toString());
        robot.clickOn("#wizard-next");
        WaitForAsyncUtils.waitForFxEvents();

        robot.clickOn("#wizard-test");
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> {
            Label result = robot.lookup("#wizard-test-result").queryAs(Label.class);
            return result.getText().contains("连接成功");
        });
        assertThat(tested.get().kind()).isEqualTo(io.agenticdb.jdbc.DialectKind.SQLITE);
        assertThat(tested.get().toString()).doesNotContain("secret");

        Button save = robot.lookup(node -> node instanceof Button button
                        && "保存".equals(button.getText()))
                .queryAs(Button.class);
        assertThat(save.isDisabled()).isFalse();
        assertThat(wizard.password()).isEmpty();
    }

    @Test
    void serverWizardCollectsHostPortDatabaseUser(FxRobot robot) throws Exception {        interact(() -> {
            wizard = new ConnectionWizard(robot.window(0), null,
                    CredentialStore.inMemory(), spec -> null);
            mount(wizard);
        });

        robot.clickOn("#wizard-type");
        robot.push(javafx.scene.input.KeyCode.DOWN);
        robot.push(javafx.scene.input.KeyCode.ENTER);
        robot.clickOn("#wizard-name");
        robot.write("PG");
        robot.clickOn("#wizard-next");
        WaitForAsyncUtils.waitForFxEvents();

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

        robot.clickOn("#wizard-next");
        WaitForAsyncUtils.waitForFxEvents();

        Button save = robot.lookup(node -> node instanceof Button button
                        && "保存".equals(button.getText()))
                .queryAs(Button.class);
        assertThat(save.isDisabled()).isFalse();
    }

    /**
     * 回归：DialogPane 会给按钮栏里的每个按钮挂 setResultAndClose 处理器，
     * 点“下一步”曾经把整个向导关掉。导航按钮必须留在内容区。
     */
    @Test
    void clickingNextDoesNotCloseWizard(FxRobot robot) throws Exception {
        interact(() -> {
            wizard = new ConnectionWizard(robot.window(0), null,
                    CredentialStore.inMemory(), spec -> null);
            mount(wizard);
        });

        robot.clickOn("#wizard-name");
        robot.write("Local SQLite");
        robot.clickOn("#wizard-next");
        WaitForAsyncUtils.waitForFxEvents();

        // 向导还活着：第 2 步的文件输入框仍然在场景里
        assertThat(robot.lookup("#wizard-file").queryTextInputControl()).isNotNull();
        assertThat(stage.getScene().getRoot()).isInstanceOf(javafx.scene.control.DialogPane.class);
    }

    /** 回归：第 1、2 步看不到第 3 步的摘要区，校验错误必须写在常驻的错误行里。 */
    @Test
    void showsValidationErrorWithoutClosingWizard(FxRobot robot) throws Exception {
        interact(() -> {
            wizard = new ConnectionWizard(robot.window(0), null,
                    CredentialStore.inMemory(), spec -> null);
            mount(wizard);
        });

        robot.clickOn("#wizard-next");
        WaitForAsyncUtils.waitForFxEvents();

        Label error = robot.lookup("#wizard-error").queryAs(Label.class);
        assertThat(error.getText()).isEqualTo("连接名称不能为空");
        // 校验失败不应离开第 1 步
        assertThat(robot.lookup("#wizard-name").queryTextInputControl()).isNotNull();
    }
}
