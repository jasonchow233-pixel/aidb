package io.agenticdb.editing;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditSqlBuilderTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schemaService;

    @BeforeEach
    void open() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(DemoDatabase.create(temp.resolve("demo.db"))));
        schemaService = new SchemaService();
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    @Test
    void updatesOnlyTargetRowAndColumn() throws Exception {
        TableSchema schema = schemaService.describe(session, "users");
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity row = row(2);
        buffer.setCell(row, "name", "User 002", "Renamed");

        EditSqlBuilder.Plan plan = EditSqlBuilder.plan(session, schema, buffer);
        assertThat(plan.size()).isEqualTo(1);
        assertThat(plan.statements().getFirst().sql())
                .isEqualTo("UPDATE \"users\" SET \"name\" = ? WHERE \"id\" = ?");
        assertThat(plan.statements().getFirst().params())
                .containsExactly("Renamed", 2);

        assertThat(EditSqlBuilder.execute(session, plan)).isEqualTo(1);

        RowPage page = session.query("SELECT * FROM users WHERE id = 2", 1);
        assertThat(page.rows().getFirst().get("name")).isEqualTo("Renamed");
        assertThat(page.rows().getFirst().get("email")).isEqualTo("user002@example.com");

        RowPage other = session.query("SELECT name FROM users WHERE id = 3", 1);
        assertThat(other.rows().getFirst().get("name")).isEqualTo("User 003");
    }

    @Test
    void nullAndEmptyStringAreDifferentUpdates() throws Exception {
        TableSchema schema = schemaService.describe(session, "users");
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity row = row(5);
        buffer.setCell(row, "bio", null, "");

        EditSqlBuilder.execute(session, EditSqlBuilder.plan(session, schema, buffer));

        Object value = session.query("SELECT bio FROM users WHERE id = 5", 1)
                .rows().getFirst().get("bio");
        assertThat(value).isEqualTo("");

        EditBuffer second = new EditBuffer("users");
        second.setCell(row, "bio", "", null);
        EditSqlBuilder.execute(session, EditSqlBuilder.plan(session, schema, second));

        Object nullValue = session.query("SELECT bio FROM users WHERE id = 5", 1)
                .rows().getFirst().get("bio");
        assertThat(nullValue).isNull();
    }

    @Test
    void deletesOnlyRowsMarkedDeleted() throws Exception {
        TableSchema schema = schemaService.describe(session, "users");
        EditBuffer buffer = new EditBuffer("users");
        buffer.deleteRow(row(4));
        buffer.deleteRow(row(6));

        EditSqlBuilder.Plan plan = EditSqlBuilder.plan(session, schema, buffer);
        assertThat(plan.size()).isEqualTo(2);
        assertThat(plan.statements().getFirst().sql())
                .isEqualTo("DELETE FROM \"users\" WHERE \"id\" = ?");
        assertThat(plan.statements().getFirst().params()).containsExactly(4);

        assertThat(EditSqlBuilder.execute(session, plan)).isEqualTo(2);
        long remaining = ((Number) session.query(
                "SELECT COUNT(*) AS adb_count FROM users WHERE id IN (4, 6)", 1)
                .rows().getFirst().get("adb_count")).longValue();
        assertThat(remaining).isZero();
    }

    @Test
    void insertsDraftsAndKeepsDatabaseDefaultsForOmittedColumns() throws Exception {
        TableSchema schema = schemaService.describe(session, "users");
        EditBuffer buffer = new EditBuffer("users");
        long id = buffer.addDraft(new java.util.LinkedHashMap<>(Map.of(
                "name", "New User",
                "email", "new@example.com",
                "created_at", "2026-10-20 12:00:00")));
        buffer.setDraftCell(id, "score", 9.5);

        EditSqlBuilder.Plan plan = EditSqlBuilder.plan(session, schema, buffer);
        assertThat(plan.size()).isEqualTo(1);
        assertThat(plan.statements().getFirst().sql())
                .startsWith("INSERT INTO \"users\" (");
        assertThat(plan.statements().getFirst().sql()).doesNotContain("\"id\"");
        assertThat(plan.preview()).contains("New User").contains("new@example.com");

        assertThat(EditSqlBuilder.execute(session, plan)).isEqualTo(1);

        RowPage page = session.query(
                "SELECT id, name, email, active, score FROM users WHERE email = 'new@example.com'",
                1);
        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().getFirst();
        assertThat(row.get("id")).isNotNull();
        assertThat(row.get("name")).isEqualTo("New User");
        assertThat(((Number) row.get("active")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("score")).doubleValue()).isEqualTo(9.5);
    }

    @Test
    void inversePlanRestoresUpdateDeleteAndExplicitInsert() throws Exception {
        TableSchema schema = schemaService.describe(session, "users");
        EditBuffer buffer = new EditBuffer("users");
        RowIdentity updated = row(2);
        buffer.setCell(updated, "name", "User 002", "Renamed");
        Map<String, Object> deletedRow = session.query(
                "SELECT * FROM users WHERE id = 4", 1).rows().getFirst();
        buffer.deleteRow(row(4), deletedRow);
        buffer.addDraft(new java.util.LinkedHashMap<>(Map.of(
                "id", 999, "name", "Temp", "email", "temp@example.com",
                "created_at", "2026-10-20 12:00:00")));

        EditSqlBuilder.Plan forward = EditSqlBuilder.plan(session, schema, buffer);
        EditSqlBuilder.Plan inverse = EditSqlBuilder.inversePlan(session, schema, buffer);
        assertThat(inverse.complete()).isTrue();
        assertThat(inverse.size()).isEqualTo(3);
        assertThat(inverse.preview()).contains("DELETE FROM \"users\"")
                .contains("INSERT INTO \"users\"")
                .contains("SET \"name\" = 'User 002'");

        EditSqlBuilder.execute(session, forward);
        assertThat(session.query("SELECT name FROM users WHERE id = 2", 1)
                .rows().getFirst().get("name")).isEqualTo("Renamed");
        assertThat(((Number) session.query("SELECT COUNT(*) AS c FROM users WHERE id = 4", 1)
                .rows().getFirst().get("c")).longValue()).isZero();

        EditSqlBuilder.execute(session, inverse);
        assertThat(session.query("SELECT name FROM users WHERE id = 2", 1)
                .rows().getFirst().get("name")).isEqualTo("User 002");
        assertThat(session.query("SELECT name FROM users WHERE id = 4", 1)
                .rows().getFirst().get("name")).isEqualTo("User 004");
        assertThat(((Number) session.query("SELECT COUNT(*) AS c FROM users WHERE id = 999", 1)
                .rows().getFirst().get("c")).longValue()).isZero();
    }

    @Test
    void inversePlanIsIncompleteForGeneratedPrimaryKeyInsert() throws Exception {
        TableSchema schema = schemaService.describe(session, "users");
        EditBuffer buffer = new EditBuffer("users");
        buffer.addDraft(new java.util.LinkedHashMap<>(Map.of(
                "name", "No Key", "email", "nokey@example.com")));

        EditSqlBuilder.Plan inverse = EditSqlBuilder.inversePlan(session, schema, buffer);

        assertThat(inverse.complete()).isFalse();
        assertThat(inverse.isEmpty()).isTrue();
    }

    @Test
    void previewEscapesQuotes() {
        TableSchema schema;
        try {
            schema = schemaService.describe(session, "users");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        EditBuffer buffer = new EditBuffer("users");
        buffer.setCell(row(1), "name", "User 001", "O'Reilly");

        String preview = EditSqlBuilder.plan(session, schema, buffer).preview();

        assertThat(preview).contains("SET \"name\" = 'O''Reilly'");
        assertThat(preview).contains("WHERE \"id\" = 1");
    }

    @Test
    void refusesTablesWithoutPrimaryKey() throws Exception {
        TableSchema view = schemaService.describe(session, "v_active_users");
        EditBuffer buffer = new EditBuffer("v_active_users");
        buffer.setCell(row(1), "name", "User 001", "x");

        assertThatThrownBy(() -> EditSqlBuilder.plan(session, view, buffer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("主键");
    }

    private static RowIdentity row(int id) {
        return RowIdentity.from(List.of("id"), Map.of("id", id));
    }
}
