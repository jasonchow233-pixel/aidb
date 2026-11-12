package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;
import io.agenticdb.session.ConnectionProfile;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real SQLite, real selection events, and a queued worker: no forced button enablement. */
@ExtendWith(ApplicationExtension.class)
class TransferToolPageTest {

    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        stage.setScene(new javafx.scene.Scene(new VBox(), 800, 460));
        stage.setX(20);
        stage.setY(20);
        stage.show();
    }

    @Test
    void defaultConfirmationCanCancelThenTransferThroughVisibleButtons(org.testfx.api.FxRobot robot)
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.page = fx(() -> {
                TransferToolPage page = new TransferToolPage(f.source, new SchemaService(), f.worker,
                        List.of(f.profile), id -> f.resolved.get());
                javafx.scene.Scene scene = new javafx.scene.Scene(page, 800, 460);
                ThemeManager theme = new ThemeManager(null);
                theme.attach(scene);
                theme.apply(ThemeMode.LIGHT, false);
                stage.setScene(scene);
                stage.sizeToScene();
                stage.toFront();
                stage.requestFocus();
                return page;
            });
            f.worker.runNext();
            f.select();
            robot.targetWindow(stage);
            saveEvidence(f.page.getScene(), "transfer-ready");
            robot.clickOn("#transfer-run");
            WaitForAsyncUtils.waitForFxEvents();
            f.assertBusy();
            f.worker.runNext();
            TextArea sql = robot.lookup("#plan-sql").queryAs(TextArea.class);
            String preview = fx(sql::getText);
            assertThat(preview).contains("CREATE TABLE", "INSERT INTO", "orders");
            f.assertNoTargetWrites();
            saveEvidence(sql.getScene(), "transfer-confirm");
            javafx.scene.control.DialogPane pane = fx(() ->
                    (javafx.scene.control.DialogPane) sql.getScene().lookup(".dialog-pane"));
            Button cancel = fx(() -> (Button) pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData().isCancelButton())
                    .map(pane::lookupButton).findFirst().orElseThrow());
            assertThat(fx(cancel::isDefaultButton)).isTrue();
            robot.clickOn(cancel);
            WaitForAsyncUtils.waitForFxEvents();
            f.assertNoTargetWrites();
            f.assertIdleAndReady();

            robot.clickOn("#transfer-run");
            WaitForAsyncUtils.waitForFxEvents();
            f.assertBusy();
            f.worker.runNext();
            TextArea approvedSql = robot.lookup("#plan-sql").queryAs(TextArea.class);
            String approvedText = fx(approvedSql::getText);
            assertThat(approvedText.replaceAll("(?m)^-- generated:.*$", ""))
                    .isEqualTo(preview.replaceAll("(?m)^-- generated:.*$", ""));
            int visibleRows = fx(approvedSql::getPrefRowCount);
            double dialogWidth = fx(() -> approvedSql.getScene().getWidth());
            String warningText = fx(() -> ((Label) approvedSql.getScene().lookup("#plan-warning")).getText());
            assertThat(visibleRows).isGreaterThanOrEqualTo(14);
            assertThat(dialogWidth).isLessThanOrEqualTo(800);
            assertThat(warningText).isEqualTo(I18n.t("plan.body"));
            javafx.scene.control.DialogPane approvedPane = fx(() ->
                    (javafx.scene.control.DialogPane) approvedSql.getScene().lookup(".dialog-pane"));
            Button proceed = fx(() -> (Button) approvedPane.getButtonTypes().stream()
                    .filter(type -> !type.getButtonData().isCancelButton())
                    .map(approvedPane::lookupButton).findFirst().orElseThrow());
            assertThat(fx(proceed::isDefaultButton)).isFalse();
            robot.clickOn(proceed);
            WaitForAsyncUtils.waitForFxEvents();
            f.worker.runNext();
            assertThat(String.join(";\n\n", f.target.executed) + ";").isEqualTo(approvedText);
            assertThat(f.target.delegate.query("SELECT * FROM orders", 10).rows())
                    .isEqualTo(f.source.delegate.query("SELECT * FROM orders", 10).rows());
            assertThat(f.target.declarations).containsOnly(Danger.DESTRUCTIVE);
            f.assertIdleAndReady();
            saveEvidence(f.page.getScene(), "transfer-complete");
        }
    }

    private static void saveEvidence(javafx.scene.Scene scene, String name) throws Exception {
        String directory = System.getProperty("adb.test.screenshotDir");
        if (directory == null) return;
        javafx.scene.image.WritableImage image = fx(() -> scene.snapshot(null));
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage output = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        javax.imageio.ImageIO.write(output, "png", java.nio.file.Path.of(directory, name + ".png").toFile());
    }

    @Test
    void selectionChangesEnableBothActionsAndClearingEitherDisablesThem() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open();
            onFx(() -> {
                assertThat(f.sourcePicker().getItems()).contains("orders");
                assertThat(f.run().isDisabled()).isTrue();
                assertThat(f.compare().isDisabled()).isTrue();
                f.sourcePicker().getSelectionModel().select("orders");
                assertThat(f.run().isDisabled()).isTrue();
                f.targetPicker().getSelectionModel().select(f.profile);
                assertThat(f.run().isDisabled()).isFalse();
                assertThat(f.compare().isDisabled()).isFalse();
                f.sourcePicker().getSelectionModel().clearSelection();
                assertThat(f.run().isDisabled()).isTrue();
                assertThat(f.compare().isDisabled()).isTrue();
                f.sourcePicker().getSelectionModel().select("orders");
                assertThat(f.run().isDisabled()).isFalse();
                f.targetPicker().getSelectionModel().clearSelection();
                assertThat(f.run().isDisabled()).isTrue();
                assertThat(f.compare().isDisabled()).isTrue();
            });
            assertThat(f.worker.queue).isEmpty();
            f.assertNoTargetWrites();
        }
    }

    @Test
    void missingSourceSessionNeverQueuesWork() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open(null, plan -> true);
            assertThat(f.worker.submissions).isZero();
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.noSession"));
            onFx(() -> {
                assertThat(f.run().isDisabled()).isTrue();
                assertThat(f.compare().isDisabled()).isTrue();
                f.run().getOnAction().handle(new ActionEvent());
            });
            f.assertNoTargetWrites();
        }
    }

    @Test
    void closedSourceAtOpenNeverQueuesWork() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.close();
            f.open();
            assertThat(f.worker.submissions).isZero();
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.noSession"));
            f.assertNoTargetWrites();
        }
    }

    @Test
    void sourceClosedAfterSelectionIsRejectedWithoutTargetWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.source.close();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.noSession"));
            assertThat(f.previews).isEmpty();
            onFx(() -> {
                assertThat(f.run().isDisabled()).isTrue();
                assertThat(f.compare().isDisabled()).isTrue();
                assertThat(f.spinner().isVisible()).isFalse();
            });
            f.assertNoTargetWrites();
        }
    }

    @Test
    void offlineTargetIsRejectedWithoutConfirmationOrWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.resolved.set(null);
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetOffline", f.profile.name()));
            assertThat(f.previews).isEmpty();
            f.assertIdleAndReady();
            f.assertNoTargetWrites();
        }
    }

    @Test
    void closedTargetIsRejectedWithoutConfirmationOrWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.target.close();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetOffline", f.profile.name()));
            assertThat(f.previews).isEmpty();
            assertThat(f.target.executed).isEmpty();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            f.assertIdleAndReady();
        }
    }

    @Test
    void sameSessionIsRejectedWithoutConfirmationOrWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.resolved.set(f.source);
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.sameSession"));
            assertThat(f.previews).isEmpty();
            assertThat(f.source.executed).isEmpty();
            f.assertNoTargetWrites();
            f.assertIdleAndReady();
        }
    }

    @Test
    void differentDialectKindsRejectTransferButAllowReadOnlyCompare() throws Exception {
        try (Fixture f = new Fixture()) {
            // Only the policy discriminator changes; both JDBC fixtures remain real SQLite.
            // This tests the cross-engine gate, not PostgreSQL driver compatibility.
            f.target.kind = DialectKind.POSTGRESQL;
            f.target.setup("CREATE TABLE orders (id INTEGER PRIMARY KEY, note TEXT)");
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.unsupportedDialect",
                    f.source.dialectKind().displayName(), f.target.dialectKind().displayName()));
            assertThat(f.previews).isEmpty();
            f.assertIdleAndReady();

            onFx(() -> f.compare().fire());
            f.assertBusy();
            onFx(() -> {
                f.run().fire();
                f.compare().getOnAction().handle(new ActionEvent());
            });
            assertThat(f.worker.queue).hasSize(1);
            f.worker.runNext();
            assertThat(fx(() -> ((TextArea) f.page.lookup("#transfer-diff-report")).getText()))
                    .contains(I18n.t("tool.transfer.diff.rows", 1L, 0L));
            assertThat(f.previews).isEmpty();
            assertThat(f.target.executed).isEmpty();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            assertThat(f.target.delegate.query("SELECT * FROM orders", 10).rows()).isEmpty();
            f.assertIdleAndReady();
        }
    }

    @Test
    void existingTableIsNotOverwritten() throws Exception {
        try (Fixture f = new Fixture()) {
            f.target.setup("CREATE TABLE ORDERS (id INTEGER)");
            f.target.setup("INSERT INTO ORDERS VALUES (88)");
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetExists", "orders", f.profile.name()));
            assertThat(f.previews).isEmpty();
            assertThat(f.target.delegate.query("SELECT id FROM ORDERS", 10).rows().getFirst().get("id"))
                    .isEqualTo(88);
            assertThat(f.target.executed).isEmpty();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            f.assertIdleAndReady();
        }
    }

    @Test
    void existingViewIsNotOverwritten() throws Exception {
        try (Fixture f = new Fixture()) {
            f.target.setup("CREATE VIEW orders AS SELECT id FROM sentinel");
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetExists", "orders", f.profile.name()));
            assertThat(f.previews).isEmpty();
            assertThat(f.target.executed).isEmpty();
            assertThat(f.target.delegate.query("SELECT id FROM orders", 10).rows().getFirst().get("id"))
                    .isEqualTo(7);
            f.assertIdleAndReady();
        }
    }

    @Test
    void decliningPreviewDoesNotQueueExecutionAndRestoresControls() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open(f.source, plan -> false);
            f.select();
            f.fireRun();
            f.assertBusy();
            f.worker.runNext();
            assertThat(f.previews).hasSize(1);
            StructurePlan plan = f.previews.getFirst();
            assertThat(f.confirmedOnFx).isTrue();
            assertThat(plan.titleKey()).isEqualTo("tool.transfer.plan");
            assertThat(plan.titleArgs()).containsExactly("orders", f.profile.name(),
                    f.profile.id(), f.target.label());
            assertThat(plan.sql()).contains("CREATE TABLE", "INSERT INTO", "semi; O''Brien");
            assertThat(plan.danger()).isEqualTo(Danger.DESTRUCTIVE);
            assertThat(f.message()).isEqualTo(I18n.t("ddl.cancelled", StructurePlanRunner.title(plan)));
            assertThat(f.worker.queue).isEmpty();
            f.assertNoTargetWrites();
            f.assertIdleAndReady();
        }
    }

    @Test
    void busyTransferCannotQueueRunOrCompareAgainIncludingDuringConfirmation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.open(f.source, plan -> {
                f.busyDuringConfirmation = f.run().isDisabled() && f.compare().isDisabled()
                        && f.sourcePicker().isDisabled() && f.targetPicker().isDisabled()
                        && f.spinner().isVisible();
                f.run().getOnAction().handle(new ActionEvent());
                f.compare().getOnAction().handle(new ActionEvent());
                return true;
            });
            f.select();
            f.fireRun();
            f.assertBusy();
            onFx(() -> {
                f.run().fire();
                f.compare().fire();
                // Even an already-dispatched action must not bypass the shared busy guard.
                f.run().getOnAction().handle(new ActionEvent());
                f.compare().getOnAction().handle(new ActionEvent());
            });
            assertThat(f.worker.queue).hasSize(1);
            assertThat(f.worker.submissions).isEqualTo(2); // Load + prepare.
            f.worker.runNext();
            assertThat(f.busyDuringConfirmation).isTrue();
            assertThat(f.previews).hasSize(1);
            assertThat(f.worker.queue).hasSize(1);
            assertThat(f.worker.submissions).isEqualTo(3); // Exactly one execution.
            f.assertBusy();
            f.assertNoTargetWrites();
            f.worker.runNext();
            assertThat(f.worker.queue).isEmpty();
            f.assertIdleAndReady();
        }
    }

    @Test
    void sqliteTransferExecutesExactlyPreviewedSqlWithDeclaredDanger() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.confirmedOnFx).isTrue();
            f.assertNoTargetWrites();
            f.worker.runNext();
            StructurePlan plan = f.previews.getFirst();
            assertThat(plan.statements()).hasSize(2);
            assertThat(f.target.executed).containsExactlyElementsOf(plan.statements());
            assertThat(f.target.declarations).containsOnly(Danger.DESTRUCTIVE);
            assertThat(f.target.executedOnFx).isFalse();
            assertThat(f.source.executed).isEmpty();
            assertThat(f.target.delegate.query("SELECT note FROM orders", 10).rows().getFirst().get("note"))
                    .isEqualTo("semi; O'Brien");
            assertThat(f.target.begins).isEqualTo(1);
            assertThat(f.target.commits).isEqualTo(1);
            assertThat(f.target.rollbacks).isZero();
            assertThat(f.target.inTransaction()).isFalse();
            assertThat(f.message()).isEqualTo(I18n.tPlural("tool.transfer.done", 2,
                    "orders", f.profile.name(), 2));
            f.assertIdleAndReady();
        }
    }

    @Test
    void sqliteExecutionFailureRollsBackOwnedTransactionAndRestoresControls() throws Exception {
        try (Fixture f = new Fixture()) {
            f.target.failOnInsert = true;
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.worker.runNext();
            assertThat(f.target.executed).hasSize(2);
            assertThat(f.target.declarations).containsOnly(Danger.DESTRUCTIVE);
            assertThat(f.target.begins).isEqualTo(1);
            assertThat(f.target.commits).isZero();
            assertThat(f.target.rollbacks).isEqualTo(1);
            assertThat(f.target.inTransaction()).isFalse();
            assertThat(f.objects()).containsExactly("sentinel");
            assertThat(f.sentinel()).isEqualTo(7);
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.executionFailed", "injected insert failure"));
            f.assertIdleAndReady();
        }
    }

    @Test
    void successfulTransferLeavesUserTransactionUncommitted() throws Exception {
        try (Fixture f = new Fixture()) {
            f.target.delegate.begin();
            f.target.setup("UPDATE sentinel SET id = 99");
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.worker.runNext();
            assertThat(f.target.inTransaction()).isTrue();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            assertThat(f.objects()).contains("orders");
            assertThat(f.sentinel()).isEqualTo(99);
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.donePending", "orders", f.profile.name(), 2));
            f.assertIdleAndReady();
            f.target.delegate.rollback();
            assertThat(f.objects()).containsExactly("sentinel");
            assertThat(f.sentinel()).isEqualTo(7);
        }
    }

    @Test
    void failedTransferLeavesUserTransactionAndExistingChangesUntouched() throws Exception {
        try (Fixture f = new Fixture()) {
            f.target.delegate.begin();
            f.target.setup("UPDATE sentinel SET id = 99");
            f.target.failOnInsert = true;
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.worker.runNext();
            assertThat(f.target.inTransaction()).isTrue();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            assertThat(f.sentinel()).isEqualTo(99);
            assertThat(f.objects()).contains("orders");
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.executionFailed", "injected insert failure"));
            f.assertIdleAndReady();
            f.target.delegate.rollback();
            assertThat(f.objects()).containsExactly("sentinel");
            assertThat(f.sentinel()).isEqualTo(7);
        }
    }

    @Test
    void implicitCommitDialectsRefuseAnExistingUserTransaction() throws Exception {
        try (Fixture f = new Fixture()) {
            f.source.kind = DialectKind.MYSQL;
            f.target.kind = DialectKind.MYSQL;
            f.target.delegate.begin();
            f.target.setup("UPDATE sentinel SET id = 99");
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.activeTransaction"));
            assertThat(f.previews).isEmpty();
            assertThat(f.target.inTransaction()).isTrue();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            assertThat(f.target.executed).isEmpty();
            assertThat(f.sentinel()).isEqualTo(99);
            f.assertIdleAndReady();
            f.target.delegate.rollback();
            assertThat(f.sentinel()).isEqualTo(7);
        }
    }

    @Test
    void objectCreatedAfterPreviewIsRejectedBeforeFirstWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.target.setup("CREATE TABLE orders (id INTEGER)");
            f.target.setup("INSERT INTO orders VALUES (88)");
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetExists", "orders", f.profile.name()));
            assertThat(f.target.executed).isEmpty();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            assertThat(f.target.delegate.query("SELECT id FROM orders", 10).rows().getFirst().get("id"))
                    .isEqualTo(88);
            f.assertIdleAndReady();
        }
    }

    @Test
    void replacedTargetAfterPreviewIsRejectedBeforeFirstWrite() throws Exception {
        try (Fixture f = new Fixture(); RecordingSession replacement = new RecordingSession("replacement")) {
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.resolved.set(replacement);
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetOffline", f.profile.name()));
            assertThat(replacement.executed).isEmpty();
            f.assertNoTargetWrites();
            f.assertIdleAndReady();
        }
    }

    @Test
    void sourceClosedAfterPreviewIsRejectedBeforeFirstWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.source.close();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.noSession"));
            f.assertNoTargetWrites();
            assertThat(fx(() -> f.spinner().isVisible())).isFalse();
        }
    }

    @Test
    void targetClosedAfterPreviewIsRejectedBeforeFirstWrite() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.fireRun();
            f.worker.runNext();
            f.target.close();
            f.worker.runNext();
            assertThat(f.message()).contains(I18n.t("tool.transfer.targetOffline", f.profile.name()));
            assertThat(f.target.executed).isEmpty();
            assertThat(f.target.begins + f.target.commits + f.target.rollbacks).isZero();
            f.assertIdleAndReady();
        }
    }

    @Test
    void executorRejectionRestoresControlsBeforePreparationAndBeforeExecution() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            f.worker.reject = true;
            f.fireRun();
            assertThat(f.worker.queue).isEmpty();
            assertThat(f.previews).isEmpty();
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.failed", "worker unavailable"));
            f.assertIdleAndReady();
            f.worker.reject = false;
            f.fireRun();
            f.worker.reject = true;
            f.worker.runNext();
            assertThat(f.previews).hasSize(1);
            assertThat(f.worker.queue).isEmpty();
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.failed", "worker unavailable"));
            f.assertNoTargetWrites();
            f.assertIdleAndReady();
        }
    }

    @Test
    void compareMissingTableAndOfflineFailureRestoreControlsWithoutWrites() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openAndSelect();
            onFx(() -> f.compare().fire());
            f.worker.runNext();
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.diff.missing", "orders"));
            f.assertIdleAndReady();
            f.resolved.set(null);
            onFx(() -> f.compare().fire());
            f.worker.runNext();
            assertThat(f.message()).isEqualTo(I18n.t("tool.transfer.diff.failed",
                    I18n.t("tool.transfer.targetOffline", f.profile.name())));
            assertThat(f.previews).isEmpty();
            f.assertNoTargetWrites();
            f.assertIdleAndReady();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final RecordingSession source = new RecordingSession("SQLite source");
        final RecordingSession target = new RecordingSession("SQLite target");
        final ConnectionProfile profile = ConnectionProfile.sqlite("target-id", "Target", ":memory:");
        final AtomicReference<DbSession> resolved = new AtomicReference<>(target);
        final QueuedExecutor worker = new QueuedExecutor();
        final List<StructurePlan> previews = new ArrayList<>();
        TransferToolPage page;
        boolean confirmedOnFx = true;
        boolean busyDuringConfirmation;

        Fixture() throws SQLException {
            source.setup("CREATE TABLE orders (id INTEGER PRIMARY KEY, note TEXT)");
            source.setup("INSERT INTO orders VALUES (1, 'semi; O''Brien')");
            target.setup("CREATE TABLE sentinel (id INTEGER)");
            target.setup("INSERT INTO sentinel VALUES (7)");
        }

        void open() throws Exception {
            open(source, plan -> true);
        }

        void open(DbSession current, Predicate<StructurePlan> confirmation) throws Exception {
            page = fx(() -> new TransferToolPage(current, new SchemaService(), worker,
                    List.of(profile), id -> resolved.get(), plan -> {
                        confirmedOnFx &= Platform.isFxApplicationThread();
                        previews.add(plan);
                        return confirmation.test(plan);
                    }));
            if (!worker.queue.isEmpty()) {
                assertBusy();
                worker.runNext();
            }
        }

        void openAndSelect() throws Exception {
            open();
            select();
        }

        void select() throws Exception {
            onFx(() -> {
                sourcePicker().getSelectionModel().select("orders");
                targetPicker().getSelectionModel().select(profile);
                assertThat(run().isDisabled()).isFalse();
                assertThat(compare().isDisabled()).isFalse();
            });
        }

        void fireRun() throws Exception {
            onFx(() -> {
                assertThat(run().isDisabled()).isFalse();
                run().fire();
            });
        }

        void assertBusy() throws Exception {
            onFx(() -> {
                assertThat(sourcePicker().isDisabled()).isTrue();
                assertThat(targetPicker().isDisabled()).isTrue();
                assertThat(run().isDisabled()).isTrue();
                assertThat(compare().isDisabled()).isTrue();
                assertThat(spinner().isVisible()).isTrue();
            });
        }

        void assertIdleAndReady() throws Exception {
            onFx(() -> {
                assertThat(sourcePicker().isDisabled()).isFalse();
                assertThat(targetPicker().isDisabled()).isFalse();
                assertThat(run().isDisabled()).isFalse();
                assertThat(compare().isDisabled()).isFalse();
                assertThat(spinner().isVisible()).isFalse();
            });
        }

        void assertNoTargetWrites() throws SQLException {
            assertThat(target.executed).isEmpty();
            assertThat(target.begins + target.commits + target.rollbacks).isZero();
            assertThat(objects()).containsExactly("sentinel");
            assertThat(sentinel()).isEqualTo(7);
        }

        List<String> objects() throws SQLException {
            return new SchemaService().listObjects(target).stream()
                    .map(SchemaService.SchemaObject::name).toList();
        }

        Object sentinel() throws SQLException {
            return target.delegate.query("SELECT id FROM sentinel", 10).rows().getFirst().get("id");
        }

        String message() throws Exception {
            return fx(() -> ((Label) ((VBox) page.lookup("#transfer-body")).getChildren().getFirst()).getText());
        }

        @SuppressWarnings("unchecked")
        ComboBox<String> sourcePicker() {
            return (ComboBox<String>) page.lookup("#transfer-source");
        }

        @SuppressWarnings("unchecked")
        ComboBox<ConnectionProfile> targetPicker() {
            return (ComboBox<ConnectionProfile>) page.lookup("#transfer-target");
        }

        Button run() {
            return (Button) page.lookup("#transfer-run");
        }

        Button compare() {
            return (Button) page.lookup("#transfer-compare");
        }

        ProgressIndicator spinner() {
            return (ProgressIndicator) page.lookup("#transfer-progress");
        }

        @Override
        public void close() throws SQLException {
            try {
                source.close();
            } finally {
                target.close();
            }
        }
    }

    /** Runs each queued JDBC phase off FX, with deterministic barriers between phases. */
    private static final class QueuedExecutor implements Executor {
        final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        int submissions;
        boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("worker unavailable");
            }
            submissions++;
            queue.add(command);
        }

        void runNext() throws Exception {
            Runnable task = queue.poll();
            assertThat(task).as("A database phase must have been enqueued").isNotNull();
            CompletableFuture.runAsync(task).get(10, TimeUnit.SECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** JDBC fixture plus write/transaction observations; setup bypasses the observations. */
    private static final class RecordingSession implements DbSession {
        final JdbcSession delegate;
        final List<String> executed = new ArrayList<>();
        final List<Danger> declarations = new ArrayList<>();
        DialectKind kind = DialectKind.SQLITE;
        boolean failOnInsert;
        boolean executedOnFx;
        int begins;
        int commits;
        int rollbacks;

        RecordingSession(String label) throws SQLException {
            delegate = JdbcSession.wrap(DriverManager.getConnection("jdbc:sqlite::memory:"), label);
        }

        void setup(String sql) throws SQLException {
            delegate.execute(sql, Danger.DESTRUCTIVE);
        }

        @Override
        public Connection connection() {
            return delegate.connection();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        /**
         * 策略判据走 {@code dialect()}（生产代码不再枚举 {@code dialectKind()}）——
         * 夹具也必须让方言对象跟着 {@link #kind} 变，否则「跨引擎拒绝 / 隐式提交拒绝」
         * 两条用例只是在测真实 SQLite，门禁会假绿。
         *
         * <p>底层 JDBC 仍是内存 SQLite；这两条用例都在生成 SQL 之前就被拒绝，
         * 不会拿 PostgreSQL / MySQL 方言去执行 SQLite 语句。
         */
        @Override
        public Dialect dialect() {
            return kind.dialect();
        }

        @Override
        public DialectKind dialectKind() {
            return kind;
        }

        @Override
        public RowPage query(String sql, int maxRows, List<Object> params) throws SQLException {
            return delegate.query(sql, maxRows, params);
        }

        @Override
        public int update(String sql, List<Object> params) throws SQLException {
            throw new AssertionError("Transfer must use the explicitly declared execute path");
        }

        @Override
        public ExecutionResult execute(String sql) {
            throw new AssertionError("Transfer must declare Danger explicitly");
        }

        @Override
        public ExecutionResult execute(String sql, Danger declared) throws SQLException {
            executed.add(sql);
            declarations.add(declared);
            executedOnFx |= Platform.isFxApplicationThread();
            if (failOnInsert && sql.contains("INSERT INTO")) {
                throw new SQLException("injected insert failure");
            }
            return delegate.execute(sql, declared);
        }

        @Override
        public void begin() throws SQLException {
            begins++;
            delegate.begin();
        }

        @Override
        public void commit() throws SQLException {
            commits++;
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            rollbacks++;
            delegate.rollback();
        }

        @Override
        public boolean inTransaction() throws SQLException {
            return delegate.inTransaction();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void close() throws SQLException {
            if (!delegate.isClosed()) {
                delegate.close();
            }
        }
    }

    private static <T> T fx(Supplier<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> action.get()).get(10, TimeUnit.SECONDS);
    }

    private static void onFx(Runnable action) throws Exception {
        WaitForAsyncUtils.asyncFx(action).get(10, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
