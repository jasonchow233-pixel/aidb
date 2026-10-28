package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R33 验收：<b>导入 / 导出往返</b> —— 导出的文件原样导回来，数据要一模一样。
 *
 * <p>本轮 G5（编码 / 表头行 / 导入模式）与 G6（列选择 / XML / HTML）各自的门禁都只
 * 证明了「界面选项传进了参数」，证明不了「导出端写的字节，导入端按同一套约定读得回来」。
 * 这一组用真文件走完整链路：GBK 中文 → 磁盘 → 另一个表，含 NULL、含逗号引号、
 * 含 REPLACE 与列子集。
 */
class TransferRoundTripTest {

    private static final Charset GBK = Charset.forName("GBK");

    @TempDir
    Path temp;

    @Test
    void gbkCsvSurvivesExportThenImportIntoAFreshTable() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("orders-gbk.csv");
            try (Writer writer = new BufferedWriter(Files.newBufferedWriter(file, GBK))) {
                TableExporter.export(session, "orders", List.of(),
                        ExportOptions.of(ExportFormat.CSV).withCharset(GBK), writer);
            }

            // 文件真的是 GBK：按 UTF-8 读回来不该出现「张三」
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                    .doesNotContain("张三");
            assertThat(Files.readString(file, GBK)).contains("张三");

            session.execute("CREATE TABLE orders_copy (id INTEGER, name TEXT, note TEXT)");
            TableImporter.ImportReport report = TableImporter.importTypedRows(session,
                    "orders_copy", columns(), ImportOptions.of(ImportFormat.CSV)
                            .withCharset(GBK).withHeaderRow(1), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(report.skipped()).isZero();

            List<Map<String, Object>> rows = session
                    .query("SELECT id, name, note FROM orders_copy ORDER BY id", 10).rows();
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0)).containsEntry("name", "张三").containsEntry("note", "含,逗号和\"引号\" & 符号");
            assertThat(rows.get(1))
                    .as("CSV 里 NULL 与空串不可区分，导回来是空串（Navicat 同样）")
                    .containsEntry("note", "");
            assertThat(rows.get(2)).containsEntry("name", "王五").containsEntry("note", "换行后第二段");
        }
    }

    @Test
    void columnSelectionControlsWhatTheRoundTripCarries() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("orders-two-columns.csv");
            try (Writer writer = new BufferedWriter(Files.newBufferedWriter(file, GBK))) {
                TableExporter.export(session, "orders", List.of("id", "name"),
                        ExportOptions.of(ExportFormat.CSV).withCharset(GBK), writer);
            }
            assertThat(Files.readString(file, GBK).lines().findFirst().orElse(""))
                    .isEqualTo("id,name");

            session.execute("CREATE TABLE names_only (id INTEGER, name TEXT)");
            TableImporter.ImportReport report = TableImporter.importTypedRows(session,
                    "names_only", nameColumns(),
                    ImportOptions.of(ImportFormat.CSV).withCharset(GBK).withHeaderRow(1), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(session.query("SELECT id, name FROM names_only ORDER BY id", 10).rows())
                    .extracting(row -> row.get("name"))
                    .containsExactly("张三", "李四", "王五");
        }
    }

    @Test
    void replaceModeEmptiesTheTargetBeforeLoadingTheFile() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("orders-replace.csv");
            try (Writer writer = new BufferedWriter(Files.newBufferedWriter(file, GBK))) {
                TableExporter.export(session, "orders", List.of("id", "name"),
                        ExportOptions.of(ExportFormat.CSV).withCharset(GBK), writer);
            }

            session.execute("CREATE TABLE orders_dst (id INTEGER, name TEXT)");
            session.execute("INSERT INTO orders_dst VALUES (900,'旧数据'),(901,'旧数据二')");

            TableImporter.ImportReport report = TableImporter.importTypedRows(session,
                    "orders_dst", nameColumns(),
                    ImportOptions.of(ImportFormat.CSV).withCharset(GBK)
                            .withHeaderRow(1).withMode(ImportMode.REPLACE), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(session.query("SELECT COUNT(*) AS c FROM orders_dst", 1)
                    .rows().getFirst().get("c")).isEqualTo(3);
            assertThat(session.query("SELECT COUNT(*) AS c FROM orders_dst WHERE id >= 900", 1)
                    .rows().getFirst().get("c")).as("REPLACE 要先清空目标表").isEqualTo(0);
        }
    }

    @Test
    void xmlAndHtmlEscapingKeepsTheSameCellsReadable() throws Exception {
        try (JdbcSession session = seeded()) {
            String xml;
            try (Writer writer = new java.io.StringWriter()) {
                TableExporter.export(session, "orders", List.of("id", "name", "note"),
                        ExportOptions.of(ExportFormat.XML), writer);
                xml = writer.toString();
            }
            assertThat(xml).startsWith("<?xml")
                    .contains("<row>")
                    .as("引号与 & 必须转义，否则 XML 读不回来").contains("&amp;")
                    .contains("张三")
                    .as("列名写在 <c name> 上，顺序与选择一致").contains("<c name=\"note\">");

            String html;
            try (Writer writer = new java.io.StringWriter()) {
                TableExporter.export(session, "orders", List.of("id", "name"),
                        ExportOptions.of(ExportFormat.HTML), writer);
                html = writer.toString();
            }
            assertThat(html).contains("<table").contains("<th>id</th>")
                    .contains("<th>name</th>").contains("李四")
                    .as("没选 note 列就不该出现在 HTML 里").doesNotContain("note");
        }
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 1 号行的备注里同时放逗号、引号和 &，用来同时压 CSV 引号与 XML 转义。 */
    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE orders (id INTEGER, name TEXT, note TEXT)");
        session.execute("INSERT INTO orders VALUES "
                + "(1,'张三','含,逗号和\"引号\" & 符号'),"
                + "(2,'李四',NULL),"
                + "(3,'王五','换行后第二段')");
        return session;
    }

    private static List<ColumnDef> columns() {
        return List.of(column("id", ColumnType.NUMBER), column("name", ColumnType.TEXT),
                column("note", ColumnType.TEXT));
    }

    private static List<ColumnDef> nameColumns() {
        return List.of(column("id", ColumnType.NUMBER), column("name", ColumnType.TEXT));
    }

    private static ColumnDef column(String name, ColumnType type) {
        return new ColumnDef(name, type, true, false, null, null, null,
                null, null, null, false);
    }
}
