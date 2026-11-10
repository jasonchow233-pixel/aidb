package io.agenticdb.introspect;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2 Backup：转储编排的核心不变量。
 *
 * <p>落盘路径不碰真实写库，所以能完全用内存 SQLite 覆盖：每表一段 CREATE + INSERT、
 * 空表不写悬空 INSERT、视图只写 DDL 且恒排在全部基表之后。
 */
class BackupDumperTest {

    @Test
    void dumpWritesCreateTableAndInsertsForEachTable() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
            session.execute("INSERT INTO users VALUES (1,'a'),(2,'b')");
            session.execute("CREATE TABLE logs (id INTEGER PRIMARY KEY, msg TEXT)");
            session.execute("INSERT INTO logs VALUES (1,'x')");

            StringWriter out = new StringWriter();
            long rows = BackupDumper.dump(session, List.of("users", "logs"), out);

            String sql = out.toString();
            assertThat(rows).isEqualTo(3);
            assertThat(sql).contains("CREATE TABLE");
            assertThat(sql).contains("INSERT INTO");
            assertThat(sql).contains("'a'");
            assertThat(sql).contains("'x'");
        }
    }

    @Test
    void emptyTableProducesNoDanglingInsert() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE empty (id INTEGER PRIMARY KEY)");

            StringWriter out = new StringWriter();
            long rows = BackupDumper.dump(session, List.of("empty"), out);

            assertThat(rows).isEqualTo(0);
            String sql = out.toString();
            assertThat(sql).contains("-- table: empty");
            // ⭐ 空表只建表、不写 INSERT —— 否则会留下「INSERT INTO empty (...) VALUES」悬空语句。
            assertThat(sql).doesNotContain("INSERT INTO");
        }
    }

    /** 视图现在进 dump，但只写 DDL、不写 INSERT，且必须排在所有基表之后。 */
    @Test
    void viewDdlIsWrittenAfterAllTables() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
            session.execute("INSERT INTO t VALUES (1,'a')");
            session.execute("CREATE VIEW v AS SELECT id FROM t");

            StringWriter out = new StringWriter();
            long rows = BackupDumper.dump(session, List.of("t", "v"), out);

            String sql = out.toString();
            assertThat(rows).isEqualTo(1);
            assertThat(sql).contains("-- table: t", "-- view: v", "CREATE VIEW");
            assertThat(sql.indexOf("-- view: v")).isGreaterThan(sql.lastIndexOf("-- table: t"));
            // 视图段里不该混进数据 INSERT（视图没有自己的行）。
            assertThat(sql.substring(sql.indexOf("-- view: v"))).doesNotContain("INSERT INTO");
        }
    }

    /** 全库 dump 也要带上视图；视图名混进显式列表时同样按视图处理。 */
    @Test
    void wholeSchemaDumpIncludesViews() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
            session.execute("CREATE VIEW v AS SELECT id FROM t");
            session.execute("CREATE VIEW w AS SELECT id FROM v");

            StringWriter whole = new StringWriter();
            BackupDumper.dump(session, whole);
            assertThat(whole.toString()).contains("-- table: t", "-- view: v", "-- view: w");

            StringWriter explicit = new StringWriter();
            BackupDumper.dump(session, List.of("v", "t"), explicit);
            String sql = explicit.toString();
            assertThat(sql).contains("-- view: v").doesNotContain("INSERT INTO");
            assertThat(sql.indexOf("-- view: v")).isGreaterThan(sql.indexOf("-- table: t"));
        }
    }

    /**
     * ⭐ 最要紧的一条：方言拿不到视图原文时 {@code ddl()} 会退化成 CREATE TABLE，
     * 那句话绝不能被当成视图 DDL 写进可重放脚本。
     */
    @Test
    void viewDdlShapeIsValidatedBeforeWriting() {
        assertThat(BackupDumper.looksLikeViewDdl("CREATE VIEW v AS SELECT 1")).isTrue();
        assertThat(BackupDumper.looksLikeViewDdl(
                "CREATE OR REPLACE VIEW public.v AS SELECT 1")).isTrue();
        assertThat(BackupDumper.looksLikeViewDdl(
                "CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`%` "
                        + "SQL SECURITY DEFINER VIEW `v` AS select 1")).isTrue();
        assertThat(BackupDumper.looksLikeViewDdl(
                "CREATE MATERIALIZED VIEW mv AS SELECT 1")).isTrue();
        assertThat(BackupDumper.looksLikeViewDdl(
                "CREATE TABLE v (id INTEGER, view TEXT)")).isFalse();
        assertThat(BackupDumper.looksLikeViewDdl("-- a comment mentioning CREATE VIEW")).isFalse();
        assertThat(BackupDumper.looksLikeViewDdl(null)).isFalse();
        assertThat(BackupDumper.looksLikeViewDdl("")).isFalse();
    }
}
