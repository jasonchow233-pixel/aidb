package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ P6-3：xlsx 导入导出的门禁。
 *
 * <p>导出走 SXSSF（滚动窗口 + 临时文件），导入走 SAX 逐行回调；
 * 这里用「写出去再读回来」证明两条路径对得上，再单独验证类型转换与坏行报告。
 */
class XlsxCodecTest {

    @TempDir
    Path temp;

    @Test
    void exportXlsxStreamsTheTableAndReadsBack() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("t.xlsx");
            long written;
            try (OutputStream out = Files.newOutputStream(file)) {
                written = TableExporter.exportXlsx(session, "t", List.of(),
                        ExportOptions.of(ExportFormat.XLSX), out);
            }

            assertThat(written).isEqualTo(3);
            List<List<String>> rows = XlsxCodec.preview(file, 10);
            assertThat(rows).hasSize(4);
            assertThat(rows.getFirst()).containsExactly("id", "name", "score");
            assertThat(rows.get(1)).containsExactly("1", "Ada", "9.5");
            assertThat(rows.getLast()).containsExactly("3", "Cid", "7.25");
        }
    }

    @Test
    void exportXlsxHonoursTheRowLimitAndHeaderFlag() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("limited.xlsx");
            ExportOptions options = new ExportOptions(ExportFormat.XLSX, false, 500, 2, true);
            try (OutputStream out = Files.newOutputStream(file)) {
                TableExporter.exportXlsx(session, "t", List.of(), options, out);
            }

            List<List<String>> rows = XlsxCodec.preview(file, 10);
            assertThat(rows).as("不写表头 + 最多 2 行").hasSize(2);
            assertThat(rows.getFirst()).containsExactly("1", "Ada", "9.5");
        }
    }

    @Test
    void importXlsxCoercesTypedColumnsAndReportsBadRows() throws Exception {
        Path file = temp.resolve("typed.xlsx");
        writeWorkbook(file, new String[][]{
                {"id", "price", "active", "born"},
                {"1", "9.5", "true", "2026-03-04"},
                {"2", "12", "no", "2026-04-05"},
                {"3", "abc", "1", "2026-01-01"},
        });

        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, price REAL,"
                    + " active INTEGER, born DATE)");
            List<ColumnDef> columns = List.of(
                    column("id", ColumnType.NUMBER),
                    column("price", ColumnType.NUMBER),
                    column("active", ColumnType.BOOLEAN),
                    column("born", ColumnType.DATE));

            TableImporter.ImportReport report = TableImporter.importXlsx(session, "items",
                    columns, ImportOptions.of(ImportFormat.XLSX), file);

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.errors()).hasSize(1);
            assertThat(report.errors().getFirst()).contains("price").contains("abc");
            assertThat(valueOf(session, "SELECT SUM(price) FROM items"))
                    .isEqualTo("21.5");
            assertThat(valueOf(session, "SELECT COUNT(*) FROM items WHERE active = 1"))
                    .isEqualTo("1");
            assertThat(valueOf(session, "SELECT COUNT(*) FROM items WHERE born IS NOT NULL"))
                    .isEqualTo("2");
        }
    }

    @Test
    void previewStopsAtTheRequestedRowCount() throws Exception {
        Path file = temp.resolve("many.xlsx");
        writeWorkbook(file, new String[][]{
                {"n"}, {"1"}, {"2"}, {"3"}, {"4"}, {"5"},
        });

        List<List<String>> rows = XlsxCodec.preview(file, 3);
        assertThat(rows).hasSize(3);
        assertThat(rows.getLast()).containsExactly("2");
    }

    @Test
    void writerPathRefusesXlsxBecauseItIsBinary() throws Exception {
        try (JdbcSession session = seeded()) {
            assertThatThrownBy(() -> TableExporter.export(session, "t", List.of(),
                    ExportOptions.of(ExportFormat.XLSX), new StringWriter()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exportXlsx");
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, score REAL)");
        session.execute("INSERT INTO t VALUES (1, 'Ada', 9.5), (2, 'Bob', 12.0),"
                + " (3, 'Cid', 7.25)");
        return session;
    }

    private static void writeWorkbook(Path file, String[][] text) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(file)) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < text.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < text[r].length; c++) {
                    row.createCell(c).setCellValue(text[r][c]);
                }
            }
            workbook.write(out);
        }
    }

    private static ColumnDef column(String name, ColumnType type) {
        return new ColumnDef(name, type, false, false, null, null, null, null, null, null,
                false, type.name());
    }

    private static String valueOf(JdbcSession session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
