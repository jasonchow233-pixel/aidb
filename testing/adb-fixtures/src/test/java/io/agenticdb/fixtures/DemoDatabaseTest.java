package io.agenticdb.fixtures;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DemoDatabaseTest {

    @TempDir
    Path temp;

    @Test
    void createsDeterministicDemoDatabase() throws Exception {
        Path file = DemoDatabase.create(temp.resolve("nested/demo.db"));

        assertThat(Files.isRegularFile(file)).isTrue();
        try (DbSession session = JdbcSession.open(ConnectionSpec.sqlite(file))) {
            assertThat(count(session, "users")).isEqualTo(DemoDatabase.USER_COUNT);
            assertThat(count(session, "orders")).isEqualTo(DemoDatabase.ORDER_COUNT);
            assertThat(count(session, "products")).isEqualTo(DemoDatabase.PRODUCT_COUNT);
            assertThat(count(session, "notes")).isZero();
            assertThat(count(session, "v_active_users")).isEqualTo(180);

            RowPage nullBio = session.query(
                    "SELECT COUNT(*) AS n FROM users WHERE bio IS NULL", 1);
            assertThat(((Number) nullBio.rows().getFirst().get("n")).longValue()).isEqualTo(40);

            RowPage metadata = session.query(
                    "SELECT metadata FROM products WHERE id = 1", 1);
            assertThat(metadata.rows().getFirst().get("metadata"))
                    .isEqualTo("{\"color\":\"red\",\"weight\":1}");

            // ⭐ S3：演示库带一个触发器（对象树要能看到「触发器」分组）
            RowPage triggers = session.query(
                    "SELECT name FROM sqlite_master WHERE type = 'trigger'", 10);
            assertThat(triggers.rows()).hasSize(1);
            assertThat(triggers.rows().getFirst().get("name"))
                    .isEqualTo("trg_users_audit");
        }
    }

    @Test
    void ensureIsIdempotentAndKeepsUserChanges() throws Exception {
        Path file = DemoDatabase.ensure(temp.resolve("demo.db"));

        try (DbSession session = JdbcSession.open(ConnectionSpec.sqlite(file))) {
            session.update("INSERT INTO users(id, name, email, active, score, created_at) "
                    + "VALUES (999, 'Local', 'local@example.com', 1, 1.0, '2026-01-01 00:00:00')");
        }
        long modified = Files.getLastModifiedTime(file).toMillis();

        assertThat(DemoDatabase.ensure(file)).isEqualTo(file);
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modified);
        try (DbSession session = JdbcSession.open(ConnectionSpec.sqlite(file))) {
            assertThat(count(session, "users")).isEqualTo(DemoDatabase.USER_COUNT + 1);
        }
    }

    @Test
    void rebuildsEmptyOrBrokenFile() throws Exception {
        Path broken = temp.resolve("broken.db");
        Files.writeString(broken, "");

        DemoDatabase.ensure(broken);

        try (DbSession session = JdbcSession.open(ConnectionSpec.sqlite(broken))) {
            assertThat(count(session, "users")).isEqualTo(DemoDatabase.USER_COUNT);
        }
    }

    private static long count(DbSession session, String object) throws Exception {
        RowPage page = session.query("SELECT COUNT(*) AS n FROM \"" + object + "\"", 1);
        return ((Number) page.rows().getFirst().get("n")).longValue();
    }
}
