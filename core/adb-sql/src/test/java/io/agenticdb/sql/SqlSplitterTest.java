package io.agenticdb.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSplitterTest {

    private static final String MULTI = """
            -- 第一条
            SELECT 'a;b' AS value;
            /* 第二条 ; */
            SELECT 2 AS n;;
            INSERT INTO t(v) VALUES ('x;y');
            """;

    @Test
    void splitsAtRealSemicolonsOnly() {
        List<SqlSplitter.SqlStatement> statements = SqlSplitter.split(MULTI);

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0).text()).contains("SELECT 'a;b' AS value;");
        assertThat(statements.get(1).text()).contains("SELECT 2 AS n;");
        assertThat(statements.get(2).text()).contains("INSERT INTO t(v) VALUES ('x;y');");
    }

    @Test
    void keepsTriggerBodySemicolonsTogether() {
        String sql = """
                CREATE TRIGGER audit AFTER UPDATE ON users
                BEGIN
                  INSERT INTO log(msg) VALUES ('updated');
                  UPDATE stats SET n = n + 1;
                END;
                SELECT 1;
                """;

        List<SqlSplitter.SqlStatement> statements = SqlSplitter.split(sql);
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst().text()).contains("INSERT INTO log");
        assertThat(statements.getFirst().text()).contains("UPDATE stats");
        assertThat(statements.get(1).text()).isEqualTo("SELECT 1;");
    }

    @Test
    void caseEndDoesNotLookLikeTriggerEnd() {
        String sql = "SELECT CASE WHEN 1 THEN 2 END; SELECT 3;";
        assertThat(SqlSplitter.split(sql)).hasSize(2);
    }

    @Test
    void emptyAndCommentOnlyInputHasNoStatements() {
        assertThat(SqlSplitter.split(null)).isEmpty();
        assertThat(SqlSplitter.split("  \n\t ")).isEmpty();
        assertThat(SqlSplitter.split("-- only comment\n/* nothing */")).isEmpty();
        assertThat(SqlSplitter.split(";;;")).isEmpty();
    }

    @Test
    void statementAtCaretReturnsContainingStatement() {
        String sql = "SELECT 1;\nSELECT 2;";
        assertThat(SqlSplitter.statementAt(sql, 0).orElseThrow().text())
                .isEqualTo("SELECT 1;");
        assertThat(SqlSplitter.statementAt(sql, 20).orElseThrow().text())
                .isEqualTo("SELECT 2;");
        assertThat(SqlSplitter.statementAt(sql, sql.length()).orElseThrow().text())
                .isEqualTo("SELECT 2;");
    }

    @Test
    void statementAtCaretHandlesGapAndBeforeFirstStatement() {
        String sql = "  -- lead\n  SELECT 1;   \n  SELECT 2;";
        assertThat(SqlSplitter.statementAt(sql, 0).orElseThrow().text())
                .isEqualTo("-- lead\n  SELECT 1;");
        assertThat(SqlSplitter.statementAt(sql, 29).orElseThrow().text())
                .isEqualTo("SELECT 2;");
    }

    @Test
    void offsetsStayInsideOriginalText() {
        List<SqlSplitter.SqlStatement> statements = SqlSplitter.split(MULTI);
        for (SqlSplitter.SqlStatement statement : statements) {
            assertThat(MULTI.substring(statement.start(), statement.end()))
                    .contains(statement.text());
        }
    }
}
