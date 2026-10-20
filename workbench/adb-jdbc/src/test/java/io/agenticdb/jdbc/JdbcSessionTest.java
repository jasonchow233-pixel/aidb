package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnType;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSessionTest {

    @Test
    void mapsColumnsAndRows() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("""
                    CREATE TABLE users (
                      id INTEGER PRIMARY KEY,
                      name VARCHAR(50) NOT NULL,
                      flag BOOLEAN,
                      payload JSON,
                      created TIMESTAMP
                    )
                    """);
            session.update("INSERT INTO users VALUES (1, '张三', 1, '{\"a\":1}', '2026-10-20 10:00:00')");

            RowPage page = session.query("SELECT * FROM users", 200);

            assertThat(page.rowCount()).isEqualTo(1);
            assertThat(page.columns()).extracting("name")
                    .containsExactly("id", "name", "flag", "payload", "created");
            assertThat(page.columns().get(0).type()).isEqualTo(ColumnType.NUMBER);
            assertThat(page.columns().get(1).type()).isEqualTo(ColumnType.TEXT);
            assertThat(page.columns().get(1).nullable()).isFalse();
            assertThat(page.columns().get(1).charMaxLength()).isEqualTo(50);
            assertThat(page.columns().get(2).type()).isEqualTo(ColumnType.BOOLEAN);
            assertThat(page.columns().get(3).type()).isEqualTo(ColumnType.JSON);
            assertThat(page.columns().get(4).type()).isEqualTo(ColumnType.TIMESTAMP);
            assertThat(page.rows().get(0)).containsEntry("name", "张三");
        }
    }

    @Test
    void keepsNullAndEmptyStringDistinct() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (v TEXT)");
            session.update("INSERT INTO t VALUES (NULL), (''), ('x')");

            RowPage page = session.query("SELECT v FROM t ORDER BY rowid", 200);

            assertThat(page.rows()).hasSize(3);
            assertThat(page.rows().get(0).get("v")).isNull();
            assertThat(page.rows().get(1).get("v")).isEqualTo("");
            assertThat(page.rows().get(2).get("v")).isEqualTo("x");
        }
    }

    @Test
    void enforcesMaxRows() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (id INTEGER)");
            for (int i = 0; i < 150; i++) {
                session.update("INSERT INTO t VALUES (" + i + ")");
            }

            RowPage page = session.query("SELECT id FROM t", 100);

            assertThat(page.rowCount()).isEqualTo(100);
        }
    }

    @Test
    void transactionRollsBack() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (v TEXT)");

            session.begin();
            assertThat(session.inTransaction()).isTrue();
            session.update("INSERT INTO t VALUES ('tmp')");
            session.rollback();
            assertThat(session.inTransaction()).isFalse();

            assertThat(session.query("SELECT v FROM t", 200).rowCount()).isZero();
        }
    }

    @Test
    void bindsParametersAndDistinguishesNullFromEmptyString() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.update("CREATE TABLE t (v TEXT, n INTEGER)");
            session.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.asList(null, 1));
            session.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.asList("", 2));
            session.update("INSERT INTO t VALUES (?, ?)", java.util.Arrays.asList("x", 3));

            RowPage nulls = session.query(
                    "SELECT n FROM t WHERE v IS NULL", 200, java.util.List.of());
            RowPage empties = session.query(
                    "SELECT n FROM t WHERE v = ?", 200, java.util.List.of(""));
            RowPage numbers = session.query(
                    "SELECT n FROM t WHERE n >= ? ORDER BY n", 200, java.util.List.of(2));

            assertThat(nulls.rows()).extracting(row -> row.get("n")).containsExactly(1);
            assertThat(empties.rows()).extracting(row -> row.get("n")).containsExactly(2);
            assertThat(numbers.rows()).extracting(row -> row.get("n")).containsExactly(2, 3);
        }
    }

    @Test
    void invalidSqlKeepsReadableMessage() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            assertThatThrownBy(() -> session.query("SELECT * FROM missing_table", 200))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("missing_table");
        }
    }

    @Test
    void executesQueryAndDmlWithOneApi() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            ExecutionResult create = session.execute("CREATE TABLE t (id INTEGER, name TEXT)");
            assertThat(create.hasRows()).isFalse();
            assertThat(create.updateCount()).isZero();

            ExecutionResult insert = session.execute("INSERT INTO t VALUES (1, 'a'), (2, 'b')");
            assertThat(insert.updateCount()).isEqualTo(2);

            ExecutionResult query = session.execute("SELECT id, name FROM t ORDER BY id");
            assertThat(query.hasRows()).isTrue();
            assertThat(query.columns()).extracting("name").containsExactly("id", "name");
            assertThat(query.rows()).hasSize(2);
            assertThat(query.rows().getFirst()).containsEntry("name", "a");
            assertThat(query.durationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void executeRespectsQueryRowLimit() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            ExecutionResult result = session.execute("""
                    WITH RECURSIVE seq(x) AS (
                      SELECT 1 UNION ALL SELECT x + 1 FROM seq WHERE x < 1500
                    )
                    SELECT x FROM seq
                    """);
            assertThat(result.rowCount()).isEqualTo(io.agenticdb.model.QueryLimits.MAX_PAGE_SIZE);
        }
    }

    @Test
    void duplicateColumnLabelsGetDistinctKeys() throws SQLException {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            RowPage page = session.query(
                    "SELECT 1 AS id, 2 AS id, 3 AS id_2", 200);

            assertThat(page.rows().get(0)).containsKeys("id", "id_2", "id_2_2");
        }
    }
}
