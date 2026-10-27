package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ P6-2：查询结果导出的门禁。
 *
 * <p>与整表导出的区别在于「导出的是用户那条 SQL 的结果」：
 * 绑定参数要生效、JOIN / 排序 / 过滤原样保留、行数不受 UI 单页上限影响。
 */
class TransferExportTest {

    @Test
    void exportSqlStreamsTheQueryResultWithBoundParameters() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();

            long written = TableExporter.exportSql(session,
                    "SELECT id, name FROM t WHERE id > ? ORDER BY id",
                    List.of(2), ExportOptions.of(ExportFormat.CSV), out);

            assertThat(written).isEqualTo(3);
            String[] lines = out.toString().split("\\R");
            assertThat(lines[0]).isEqualTo("id,name");
            assertThat(lines).hasSize(4);
            assertThat(lines[1]).isEqualTo("3,c");
            assertThat(lines[3]).isEqualTo("5,e");
        }
    }

    @Test
    void exportSqlWritesJsonAndHonoursTheRowLimit() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();

            long written = TableExporter.exportSql(session, "SELECT id, name FROM t ORDER BY id",
                    List.of(), ExportOptions.of(ExportFormat.JSON).withMaxRows(2), out);

            assertThat(written).isEqualTo(2);
            String[] lines = out.toString().split("\\R");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).isEqualTo("{\"id\":1,\"name\":\"a\"}");
            assertThat(lines[1]).isEqualTo("{\"id\":2,\"name\":\"b\"}");
        }
    }

    @Test
    void exportSqlRefusesInsertFormatBecauseThereIsNoTargetTable() throws Exception {
        try (JdbcSession session = seeded()) {
            assertThatThrownBy(() -> TableExporter.exportSql(session, "SELECT * FROM t",
                    List.of(), ExportOptions.of(ExportFormat.SQL), new StringWriter()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CSV");
        }
    }

    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");
        return session;
    }
}
