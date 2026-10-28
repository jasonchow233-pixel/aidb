package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入导出引擎的门禁。
 *
 * <p>守三件事：
 * <ol>
 *   <li>CSV/TSV 的引号、换行、分隔符转义与解析（RFC 4180）；</li>
 *   <li>四种导出格式的输出内容；</li>
 *   <li>导入的列匹配、批量提交与坏行报告。</li>
 * </ol>
 */
class TransferTest {

    // ─────────────────────────── CSV 编解码 ───────────────────────────

    @Test
    void escapesOnlyWhenNeeded() {
        assertThat(CsvCodec.escape("plain", ',')).isEqualTo("plain");
        assertThat(CsvCodec.escape("a,b", ',')).isEqualTo("\"a,b\"");
        assertThat(CsvCodec.escape("a\"b", ',')).isEqualTo("\"a\"\"b\"");
        assertThat(CsvCodec.escape("a\nb", ',')).isEqualTo("\"a\nb\"");
        assertThat(CsvCodec.escape(null, ',')).isEmpty();
    }

    @Test
    void readsQuotedFieldsWithSeparatorsAndNewlines() throws IOException {
        String text = "a,\"b,c\",\"d\ne\",\"f\"\"g\"\r\n1,2,3,4\r\n";
        PushbackReader reader = new PushbackReader(new StringReader(text), 8);

        List<String> first = CsvCodec.readRow(reader, ',');
        assertThat(first).containsExactly("a", "b,c", "d\ne", "f\"g");
        assertThat(CsvCodec.readRow(reader, ',')).containsExactly("1", "2", "3", "4");
        assertThat(CsvCodec.readRow(reader, ',')).isNull();
    }

    @Test
    void handlesCrlfLineEndings() throws IOException {
        PushbackReader reader = new PushbackReader(new StringReader("a,b\r\nc,d\r\n"), 8);
        assertThat(CsvCodec.readRow(reader, ',')).containsExactly("a", "b");
        assertThat(CsvCodec.readRow(reader, ',')).containsExactly("c", "d");
        assertThat(CsvCodec.readRow(reader, ',')).isNull();
    }

    @Test
    void roundTripsThroughWriteAndRead() throws IOException {
        List<List<String>> rows = List.of(
                List.of("plain", "with,comma", "with\"quote", "with\nnewline"),
                List.of("1", "2", "3", "4"));
        StringWriter text = new StringWriter();
        for (List<String> row : rows) {
            CsvCodec.writeRow(text, row, ',');
        }
        PushbackReader reader = new PushbackReader(new StringReader(text.toString()), 8);
        for (List<String> expected : rows) {
            assertThat(CsvCodec.readRow(reader, ',')).isEqualTo(expected);
        }
    }

    // ─────────────────────────── 导出 ───────────────────────────

    @Test
    void exportsCsvWithHeader() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            long rows = TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.CSV), out);

            assertThat(rows).isEqualTo(3);
            assertThat(out.toString())
                    .startsWith("id,name\r\n")
                    .contains("1,alpha\r\n")
                    .contains("3,gamma\r\n");
        }
    }

    @Test
    void exportsTsvWithTabDelimiter() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.TSV), out);

            assertThat(out.toString())
                    .startsWith("id\tname\r\n")
                    .contains("1\talpha\r\n");
        }
    }

    @Test
    void exportsJsonLinesWithRealTypes() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.JSON), out);

            assertThat(out.toString())
                    .contains("\"id\":1")
                    .contains("\"name\":\"alpha\"")
                    .doesNotContain("\"id\":\"1\"")
                    .doesNotContain("id\tname");
        }
    }

    @Test
    void exportsReplayableInsertStatements() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.SQL), out);

            String sql = out.toString();
            assertThat(sql).contains("INSERT INTO \"t\" (\"id\", \"name\") VALUES");
            assertThat(sql).contains("(1, 'alpha'),");
            // 最后一行以分号收尾，没有多余的逗号
            assertThat(sql.trim()).endsWith(";");
            assertThat(sql).doesNotContain(");,");
        }
    }

    @Test
    void exportRespectsMaxRows() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            long rows = TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.CSV).withMaxRows(2), out);

            assertThat(rows).isEqualTo(2);
            assertThat(out.toString()).doesNotContain("gamma");
        }
    }

    @Test
    void exportQuotesSqlLiteralsSafely() {
        assertThat(TableExporter.sqlLiteral(null)).isEqualTo("NULL");
        assertThat(TableExporter.sqlLiteral(42)).isEqualTo("42");
        assertThat(TableExporter.sqlLiteral(true)).isEqualTo("true");
        assertThat(TableExporter.sqlLiteral("O'Brien")).isEqualTo("'O''Brien'");
    }

    // ─────────────────────────── 导入 ───────────────────────────

    @Test
    void importsCsvMatchingColumnsByHeaderName() throws Exception {
        try (JdbcSession session = seeded()) {
            String csv = "name,id\ndelta,4\n";
            TableImporter.ImportReport report = TableImporter.importRows(session, "t",
                    List.of("id", "name"), ImportOptions.of(ImportFormat.CSV),
                    new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(report.skipped()).isZero();
            assertThat(countRows(session, "t")).isEqualTo(4);
            assertThat(valueOf(session, "SELECT name FROM t WHERE id = 4")).isEqualTo("delta");
        }
    }

    @Test
    void importsSequentiallyWhenThereIsNoHeader() throws Exception {
        try (JdbcSession session = seeded()) {
            String csv = "9,epsilon\n";
            TableImporter.ImportReport report = TableImporter.importRows(session, "t",
                    List.of("id", "name"),
                    new ImportOptions(ImportFormat.CSV, false, 200, List.of(), 0, false,
                            java.nio.charset.StandardCharsets.UTF_8, ImportMode.APPEND),
                    new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(valueOf(session, "SELECT name FROM t WHERE id = 9")).isEqualTo("epsilon");
        }
    }

    @Test
    void reportsBadRowsWithoutLosingTheGoodOnes() throws Exception {
        try (JdbcSession session = seeded()) {
            // id 是 INTEGER 主键，"oops" 插不进去 → 坏行
            String csv = "id,name\n10,ok\nbad,oops\n11,fine\n";
            TableImporter.ImportReport report = TableImporter.importRows(session, "t",
                    List.of("id", "name"), ImportOptions.of(ImportFormat.CSV),
                    new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.hasErrors()).isTrue();
            // 数据行从 1 开始（表头不算）：10,ok 是第 1 行，bad,oops 是第 2 行
            assertThat(report.errors().getFirst()).contains("第 2 行");
        }
    }

    @Test
    void importCommitsInBatches() throws Exception {
        try (JdbcSession session = seeded()) {
            StringBuilder csv = new StringBuilder("id,name\n");
            for (int i = 100; i < 150; i++) {
                csv.append(i).append(",row").append(i).append('\n');
            }
            TableImporter.ImportReport report = TableImporter.importRows(session, "t",
                    List.of("id", "name"),
                    new ImportOptions(ImportFormat.CSV, true, 10, List.of(), 0, false,
                            java.nio.charset.StandardCharsets.UTF_8, ImportMode.APPEND),
                    new StringReader(csv.toString()));

            assertThat(report.inserted()).isEqualTo(50);
            assertThat(countRows(session, "t")).isEqualTo(53);
        }
    }

    @Test
    void columnMappingIsResolvedByNameFirst() {
        List<String> target = List.of("id", "name", "email");

        // 表头同名 → 按名字（email 在文件第 0 列 → 目标第 2 列；id 在第 1 列 → 目标第 0 列）
        List<int[]> mapping = TableImporter.resolveMapping(
                ImportOptions.of(ImportFormat.CSV), List.of("email", "id"), target);
        // 映射按「目标列」顺序给出：id → 文件第 1 列；email → 文件第 0 列
        assertThat(mapping).hasSize(2);
        assertThat(mapping.get(0)).containsExactly(1, 0);
        assertThat(mapping.get(1)).containsExactly(0, 2);

        // 没有表头 → 顺序
        assertThat(TableImporter.resolveMapping(
                ImportOptions.of(ImportFormat.CSV), null, target)).isEmpty();

        // 表头完全对不上且列数不同 → 无法匹配
        assertThat(TableImporter.resolveMapping(
                ImportOptions.of(ImportFormat.CSV), List.of("a", "b"), target)).isNull();
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        try (Statement statement = session.connection().createStatement()) {
            statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO t VALUES (1, 'alpha'), (2, 'beta'), (3, 'gamma')");
        }
        return session;
    }

    private static int countRows(JdbcSession session, String table) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String valueOf(JdbcSession session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
