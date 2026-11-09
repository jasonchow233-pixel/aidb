package io.agenticdb.ui.shell;

import io.agenticdb.introspect.CheckDef;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.ui.I18n;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H4-b：新建 CHECK 约束弹窗的门禁。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li><b>预览 == 执行</b> —— 预览里那一句就是 {@code StructureSqlBuilder.checkClause}
 *       写进 CREATE TABLE 的子句（同一个出口，不是弹窗里另抄一份拼串）；</li>
 *   <li><b>空表达式不放行</b> —— OK 的 EventFilter 咽回去、错误就地显形，
 *       不产出结果（D-69 同族：关窗后再弹 ERROR 等于把刚填的名字丢了）；</li>
 *   <li><b>匿名是合法输入</b> —— SQLite 允许无名 CHECK，名字留空不该被拦，
 *       预览里也不长出 CONSTRAINT 关键字。</li>
 * </ol>
 */
@ExtendWith(ApplicationExtension.class)
class CheckDialogTest {

    private static Stage stage;

    @Start
    void start(Stage primaryStage) {
        stage = primaryStage;
        StackPane root = new StackPane();
        root.getStyleClass().add("theme-light");
        javafx.scene.Scene scene = new javafx.scene.Scene(root);
        scene.getStylesheets().add(resource("/io/agenticdb/ui/theme-light.css"));
        scene.getStylesheets().add(resource("/io/agenticdb/ui/app.css"));
        stage.setScene(scene);
        stage.show();
    }

    private static String resource(String path) {
        java.net.URL url = CheckDialogTest.class.getResource(path);
        assertThat(url).as("样式资源 %s", path).isNotNull();
        return url.toExternalForm();
    }

    @Test
    void blankExpressionNeitherProducesAResultNorHidesTheError() {
        CheckDialog dialog = mounted();
        TextField name = (TextField) lookup(dialog, "#check-dialog-name");
        TextArea expression = (TextArea) lookup(dialog, "#check-dialog-expression");
        onFxRun(() -> name.setText("chk_blank"));

        fireOk(dialog);
        assertThat(onFx(() -> dialog.dialog().getResult()))
                .as("空表达式按下 OK 不许产出结果 —— 产出了弹窗就会关闭")
                .isNull();
        Label error = (Label) lookup(dialog, "#check-dialog-error");
        assertThat(onFx(error::getText)).contains(I18n.t("check.error.noExpression"));
        assertThat(onFx(error::isVisible)).isTrue();

        onFxRun(() -> expression.setText("id > 0"));
        fireOk(dialog);
        assertThat(onFx(() -> dialog.dialog().getResult()))
                .as("补上表达式后同一扇窗直接通过")
                .isEqualTo(new CheckDef("chk_blank", "id > 0"));
        assertThat(onFx(error::getText)).isEmpty();
    }

    @Test
    void previewShowsTheExactClauseThatGoesIntoCreateTable() {
        CheckDialog dialog = mounted();
        onFxRun(() -> {
            ((TextField) lookup(dialog, "#check-dialog-name")).setText("chk_id");
            ((TextArea) lookup(dialog, "#check-dialog-expression")).setText("id > 0");
        });

        assertThat(onFx(() -> ((TextArea) lookup(dialog, "#check-dialog-preview")).getText()))
                .isEqualTo("CONSTRAINT \"chk_id\" CHECK (id > 0)");

        fireOk(dialog);
        assertThat(onFx(() -> dialog.dialog().getResult()))
                .isEqualTo(new CheckDef("chk_id", "id > 0"));
    }

    @Test
    void anonymousCheckIsLegalAndOmitsTheConstraintKeyword() {
        CheckDialog dialog = mounted();
        onFxRun(() -> ((TextArea) lookup(dialog, "#check-dialog-expression")).setText("id > 0"));

        assertThat(onFx(() -> ((TextArea) lookup(dialog, "#check-dialog-preview")).getText()))
                .isEqualTo("CHECK (id > 0)");

        fireOk(dialog);
        assertThat(onFx(() -> dialog.dialog().getResult()))
                .as("名字留空是 SQLite 的合法匿名约束，不许拦")
                .isEqualTo(new CheckDef("", "id > 0"));
    }

    @Test
    void previewFollowsTheExpressionWhileTyping() {
        CheckDialog dialog = mounted();
        TextArea expression = (TextArea) lookup(dialog, "#check-dialog-expression");
        onFxRun(() -> expression.setText("id > 0"));
        assertThat(onFx(() -> ((TextArea) lookup(dialog, "#check-dialog-preview")).getText()))
                .contains("id > 0");

        onFxRun(() -> expression.setText("id BETWEEN 1 AND 99"));
        assertThat(onFx(() -> ((TextArea) lookup(dialog, "#check-dialog-preview")).getText()))
                .as("表达式改了预览必须跟着改 —— 监听器没接就是静态假预览")
                .contains("id BETWEEN 1 AND 99")
                .doesNotContain("id > 0");
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private static CheckDialog mounted() {
        AtomicReference<CheckDialog> created = new AtomicReference<>();
        onFxRun(() -> {
            CheckDialog dialog = CheckDialog.create(FakeDbSession.of(DialectKind.SQLITE));
            StackPane root = (StackPane) stage.getScene().getRoot();
            root.getChildren().setAll(dialog.content());
            created.set(dialog);
        });
        WaitForAsyncUtils.waitForFxEvents();
        return created.get();
    }

    private static Node lookup(CheckDialog dialog, String selector) {
        return onFx(() -> dialog.content().lookup(selector));
    }

    /** fire 派发 ACTION，走真实的 EventFilter → DialogPane 关窗监听链（不 show，D-69 先例）。 */
    private static void fireOk(CheckDialog dialog) {
        onFxRun(() -> {
            DialogPane pane = dialog.content();
            ButtonType ok = pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                    .findFirst().orElseThrow();
            ((Button) pane.lookupButton(ok)).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static <T> T onFx(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        onFxRun(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                problem.set(t);
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        if (problem.get() != null) {
            throw new AssertionError("FX 线程上的取值失败", problem.get());
        }
        return result.get();
    }

    private static void onFxRun(Runnable action) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            action.run();
        } else {
            AtomicReference<Throwable> problem = new AtomicReference<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    problem.set(t);
                }
            });
            WaitForAsyncUtils.waitForFxEvents();
            if (problem.get() != null) {
                throw new AssertionError("FX 线程上的操作失败", problem.get());
            }
        }
    }
}