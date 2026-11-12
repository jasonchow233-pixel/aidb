package io.agenticdb.ui.shell;

import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.StructurePlan;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import io.agenticdb.model.RowPage;
import io.agenticdb.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(ApplicationExtension.class)
class BackupToolPageTest {

    @TempDir
    Path temp;

    private DbSession real;
    private RecordingSession session;
    private QueuedExecutor executor;
    private BackupToolPage page;
    private Path database;

    @BeforeEach
    void open() throws Exception {
        database = temp.resolve("target.db");
        real = JdbcSession.open(ConnectionSpec.sqlite(database));
        real.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
        real.execute("INSERT INTO users VALUES (1, 'original')");
        real.execute("CREATE TABLE audit_log (message TEXT)");
        real.execute("INSERT INTO audit_log VALUES ('keep')");
        session = new RecordingSession(real);
        executor = new QueuedExecutor();
    }

    @AfterEach
    void close() throws Exception {
        if (real != null) {
            if (real.inTransaction()) {
                real.rollback();
            }
            real.close();
        }
    }

    @Test
    void cancellationExecutesNothingAndPreservesExistingData() throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        createPage(plan -> {
            assertThat(Platform.isFxApplicationThread()).isTrue();
            assertThat(session.executed).isEmpty();
            assertThat(session.transactionCalls).isEmpty();
            confirmations.incrementAndGet();
            return false;
        }, true);
        start(script("-- table: users\nDROP TABLE users;"));
        assertLocked(true);
        assertThat(session.executed).isEmpty();

        executor.runNext();

        assertThat(confirmations.get()).isEqualTo(1);
        assertThat(session.executed).isEmpty();
        assertThat(executor.tasks).isEmpty();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertThat(value(real, "SELECT message FROM audit_log")).isEqualTo("keep");
        assertThat(status()).isEqualTo(I18n.t("tool.backup.restoreCancelled"));
        assertLocked(false);
    }

    @Test
    void previewContainsEveryExecutedStatementAndBindsTheReadFile() throws Exception {
        Path file = script("""
                -- table: users
                CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);
                INSERT INTO users VALUES (2, 'restored; value');
                CREATE VIEW restored_users AS SELECT * FROM users;
                """);
        AtomicReference<StructurePlan> preview = new AtomicReference<>();
        createPage(plan -> {
            assertThat(Platform.isFxApplicationThread()).isTrue();
            assertThat(session.executed).isEmpty();
            assertThat(session.transactionCalls).isEmpty();
            assertThat(plan.titleKey()).isEqualTo("tool.backup.restorePlan");
            assertThat(plan.titleArgs()).containsExactly(session.label(), file.toString());
            assertThat(plan.danger()).isEqualTo(Danger.DESTRUCTIVE);
            assertThat(plan.approvalRequired()).isTrue();
            assertThat(plan.foreignKeysOff()).isFalse();
            assertThat(plan.statements()).containsExactly(
                    "DROP TABLE \"users\"",
                    "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)",
                    "INSERT INTO users VALUES (2, 'restored; value')",
                    "CREATE VIEW restored_users AS SELECT * FROM users");
            assertThatThrownBy(() -> plan.statements().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            preview.set(plan);
            try {
                Files.writeString(file, "DROP TABLE audit_log;");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return true;
        }, true);
        start(file);

        executor.runNext();
        assertThat(session.executed).isEmpty();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertLocked(true);
        executor.runNext();

        assertThat(session.executed).containsExactlyElementsOf(preview.get().statements());
        assertThat(session.declarations).containsOnly(Danger.DESTRUCTIVE).hasSize(4);
        assertThat(value(real, "SELECT name FROM restored_users")).isEqualTo("restored; value");
        assertThat(value(real, "SELECT message FROM audit_log")).isEqualTo("keep");
        assertLocked(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ; ;\n-- table: users\n/* no executable SQL */"})
    void emptyScriptsNeverDropTablesOrAskForConfirmation(String content) throws Exception {
        createPage(plan -> {
            throw new AssertionError("Empty scripts must not be confirmed");
        }, true);
        start(script(content));

        executor.runNext();

        assertThat(session.executed).isEmpty();
        assertThat(executor.tasks).isEmpty();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertThat(status()).isEqualTo(I18n.t("tool.backup.restoreEmpty"));
        assertLocked(false);
    }

    @Test
    void aRealDropFailureStopsBeforeAnyRestoreStatement() throws Exception {
        real.execute("PRAGMA foreign_keys = ON");
        real.execute("CREATE TABLE child (user_id INTEGER REFERENCES users(id))");
        real.execute("INSERT INTO child VALUES (1)");
        createPage(plan -> true, true);
        start(script("-- table: users\nINSERT INTO audit_log VALUES ('must not run');"));

        executor.runNext();
        executor.runNext();

        assertThat(session.executed).containsExactly("DROP TABLE \"users\"");
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertThat(count(real, "child")).isEqualTo(1);
        assertThat(count(real, "audit_log")).isEqualTo(1);
        assertThat(status()).isEqualTo(I18n.t("tool.backup.restoreFailed", 0, 2, session.failure));
        assertLocked(false);
    }

    @Test
    void dropsOnlyDistinctListedBaseTablesThatReallyExist() throws Exception {
        real.execute("CREATE VIEW active_users AS SELECT * FROM users");
        real.execute("CREATE TABLE \"order\" (id INTEGER)");
        real.execute("INSERT INTO \"order\" VALUES (1)");
        AtomicReference<StructurePlan> preview = new AtomicReference<>();
        createPage(plan -> {
            preview.set(plan);
            return true;
        }, true);
        start(script("""
                -- table: users
                -- table: users
                -- table: active_users
                -- table: missing
                -- table: us%
                -- table: order
                -- view: audit_log
                CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);
                INSERT INTO users VALUES (2, 'new');
                CREATE TABLE "order" (id INTEGER);
                INSERT INTO "order" VALUES (2);
                """));

        executor.runNext();
        executor.runNext();

        assertThat(preview.get().statements().stream().filter(sql -> sql.startsWith("DROP")))
                .containsExactly("DROP TABLE \"users\"", "DROP TABLE \"order\"");
        assertThat(session.executed).containsExactlyElementsOf(preview.get().statements());
        assertThat(value(real, "SELECT name FROM active_users")).isEqualTo("new");
        assertThat(value(real, "SELECT id FROM \"order\"")).isEqualTo("2");
        assertThat(value(real, "SELECT message FROM audit_log")).isEqualTo("keep");
        assertLocked(false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingUserTransactionIsNeitherCommittedNorRolledBack(boolean fail) throws Exception {
        real.begin();
        real.execute("INSERT INTO users VALUES (2, 'user pending')");
        createPage(plan -> true, false);
        start(script("INSERT INTO users VALUES (3, 'restore pending');"
                + (fail ? "INSERT INTO nonexistent VALUES (1);INSERT INTO users VALUES (4, 'skip');" : "")));

        executor.runNext();
        executor.runNext();

        assertThat(real.inTransaction()).isTrue();
        assertThat(session.transactionCalls).isEmpty();
        assertThat(count(real, "users")).isEqualTo(3);
        try (DbSession observer = JdbcSession.open(ConnectionSpec.sqlite(database))) {
            assertThat(count(observer, "users")).isEqualTo(1);
        }
        if (fail) {
            assertThat(session.executed).hasSize(2);
            assertThat(status()).isEqualTo(I18n.t("tool.backup.restoreFailed", 1, 3, session.failure));
        }
        real.rollback();
        assertThat(count(real, "users")).isEqualTo(1);
        assertLocked(false);
    }

    @Test
    void sqliteDdlJoinsTheUserTransactionWithoutCommittingTheirOtherChanges() throws Exception {
        real.begin();
        real.execute("INSERT INTO audit_log VALUES ('user pending')");
        createPage(plan -> true, true);
        start(script("""
                -- table: users
                CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT);
                INSERT INTO users VALUES (2, 'pending restore');
                """));

        executor.runNext();
        executor.runNext();

        assertThat(real.inTransaction()).isTrue();
        assertThat(session.transactionCalls).isEmpty();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("pending restore");
        assertThat(count(real, "audit_log")).isEqualTo(2);
        try (DbSession observer = JdbcSession.open(ConnectionSpec.sqlite(database))) {
            assertThat(value(observer, "SELECT name FROM users")).isEqualTo("original");
            assertThat(count(observer, "audit_log")).isEqualTo(1);
        }
        real.rollback();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertThat(count(real, "audit_log")).isEqualTo(1);
        assertLocked(false);
    }

    @Test
    void partialFailureInvalidatesSchemaCacheAndStopsSubsequentStatements() throws Exception {
        SchemaService schema = new SchemaService();
        schema.describe(real, "users");
        page = onFx(() -> {
            BackupToolPage result = new BackupToolPage(real, schema, executor, plan -> true);
            ((CheckBox) result.lookup("#backup-drop-first")).setSelected(true);
            return result;
        });
        start(script("""
                -- table: users
                CREATE TABLE users (id INTEGER, restored TEXT);
                INSERT INTO users VALUES (2, 'partial');
                INSERT INTO nonexistent VALUES (1);
                INSERT INTO audit_log VALUES ('must not run');
                """));

        executor.runNext();
        executor.runNext();

        assertThat(value(real, "SELECT restored FROM users")).isEqualTo("partial");
        assertThat(count(real, "audit_log")).isEqualTo(1);
        assertThat(schema.describe(real, "users").columns())
                .extracting(column -> column.name()).containsExactly("id", "restored");
        assertLocked(false);
    }

    @Test
    void repeatedClicksWhileReadingPreviewingAndExecutingNeverQueueAnotherRestore() throws Exception {
        Path file = script("INSERT INTO users VALUES (2, 'once');");
        AtomicInteger confirmations = new AtomicInteger();
        createPage(plan -> {
            confirmations.incrementAndGet();
            assertControlsDisabled(true);
            page.restoreFromFile(file);
            return true;
        }, false);
        onFx(() -> {
            page.restoreFromFile(file);
            page.restoreFromFile(file);
            ((Button) page.lookup("#backup-restore")).fire();
            ((Button) page.lookup("#backup-dump")).fire();
            return null;
        });
        assertThat(executor.submissions).isEqualTo(1);
        assertLocked(true);

        executor.runNext();
        start(file);
        assertThat(executor.submissions).isEqualTo(2);
        assertThat(executor.tasks).hasSize(1);
        executor.runNext();

        assertThat(confirmations.get()).isEqualTo(1);
        assertThat(session.executed).containsExactly("INSERT INTO users VALUES (2, 'once')");
        assertThat(count(real, "users")).isEqualTo(2);
        assertLocked(false);
    }

    @Test
    void readFailureUnlocksAndAllowsAnotherAttempt() throws Exception {
        createPage(plan -> true, false);
        start(temp.resolve("missing.sql"));
        executor.runNext();

        assertThat(session.executed).isEmpty();
        assertThat(status()).startsWith(I18n.t("tool.backup.failed"));
        assertLocked(false);

        start(script("INSERT INTO users VALUES (2, 'retry');"));
        executor.runNext();
        executor.runNext();
        assertThat(count(real, "users")).isEqualTo(2);
        assertLocked(false);
    }

    @Test
    void confirmationFailureUnlocksWithoutWriting() throws Exception {
        createPage(plan -> {
            throw new IllegalStateException("preview unavailable");
        }, true);
        start(script("-- table: users\nDROP TABLE users;"));

        executor.runNext();

        assertThat(session.executed).isEmpty();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertThat(status()).contains("preview unavailable");
        assertLocked(false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedPreparationOrExecutionUnlocksWithoutWriting(boolean rejectExecution) throws Exception {
        createPage(plan -> true, true);
        executor.reject = !rejectExecution;
        start(script("-- table: users\nDROP TABLE users;"));
        if (rejectExecution) {
            executor.reject = true;
            executor.runNext();
        } else {
            WaitForAsyncUtils.waitForFxEvents();
        }

        assertThat(session.executed).isEmpty();
        assertThat(value(real, "SELECT name FROM users")).isEqualTo("original");
        assertThat(status()).startsWith(I18n.t("tool.backup.failed"));
        assertLocked(false);
    }

    private void createPage(Predicate<StructurePlan> confirmation, boolean drop) throws Exception {
        page = onFx(() -> {
            BackupToolPage result = new BackupToolPage(session, new SchemaService(), executor, confirmation);
            ((CheckBox) result.lookup("#backup-drop-first")).setSelected(drop);
            return result;
        });
    }

    private Path script(String content) throws Exception {
        return Files.writeString(temp.resolve("restore.sql"), content).toAbsolutePath().normalize();
    }

    private void start(Path file) throws Exception {
        onFx(() -> {
            page.restoreFromFile(file);
            return null;
        });
    }

    private void assertLocked(boolean expected) throws Exception {
        onFx(() -> {
            assertControlsDisabled(expected);
            return null;
        });
    }

    private void assertControlsDisabled(boolean expected) {
        for (String id : List.of("#backup-dump", "#backup-restore", "#backup-drop-first")) {
            assertThat(page.lookup(id).isDisabled()).as(id).isEqualTo(expected);
        }
    }

    private String status() throws Exception {
        return onFx(() -> ((Label) page.lookup("#backup-status")).getText());
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        return WaitForAsyncUtils.waitForAsyncFx(5_000, action);
    }

    private static String value(DbSession session, String sql) throws SQLException {
        try (var statement = session.connection().createStatement();
             var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static int count(DbSession session, String table) throws SQLException {
        return Integer.parseInt(value(session, "SELECT COUNT(*) FROM " + session.dialect().quote(table)));
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private int submissions;
        private volatile boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) {
                throw new RejectedExecutionException("executor unavailable");
            }
            submissions++;
            tasks.add(command);
        }

        void runNext() {
            assertThat(Platform.isFxApplicationThread()).isFalse();
            Runnable task = tasks.poll();
            assertThat(task).isNotNull();
            task.run();
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private static final class RecordingSession implements DbSession {
        private final DbSession delegate;
        private final List<String> executed = new ArrayList<>();
        private final List<Danger> declarations = new ArrayList<>();
        private final List<String> transactionCalls = new ArrayList<>();
        private String failure;

        RecordingSession(DbSession delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connection() {
            return delegate.connection();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        @Override
        public Dialect dialect() {
            return delegate.dialect();
        }

        @Override
        public RowPage query(String sql, int maxRows, List<Object> params) {
            throw new AssertionError("Restore must execute only its approved plan");
        }

        @Override
        public int update(String sql, List<Object> params) {
            throw new AssertionError("Restore must explicitly declare destructive execution");
        }

        @Override
        public ExecutionResult execute(String sql) {
            throw new AssertionError("Restore must explicitly declare destructive execution");
        }

        @Override
        public ExecutionResult execute(String sql, Danger declared) throws SQLException {
            assertThat(Platform.isFxApplicationThread()).isFalse();
            executed.add(sql);
            declarations.add(declared);
            try {
                return delegate.execute(sql, declared);
            } catch (SQLException e) {
                failure = e.getMessage();
                throw e;
            }
        }

        @Override
        public void begin() throws SQLException {
            transactionCalls.add("begin");
            delegate.begin();
        }

        @Override
        public void commit() throws SQLException {
            transactionCalls.add("commit");
            delegate.commit();
        }

        @Override
        public void rollback() throws SQLException {
            transactionCalls.add("rollback");
            delegate.rollback();
        }

        @Override
        public boolean inTransaction() throws SQLException {
            return delegate.inTransaction();
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }
    }
}
