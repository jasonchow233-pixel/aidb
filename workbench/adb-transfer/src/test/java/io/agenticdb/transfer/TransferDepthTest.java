package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R33 导入导出深度的门禁：编码（含 BOM）、表头行、覆盖模式、列选择、XML/HTML。
 *
 * <p>这些能力的第一版都是「界面有开关、引擎没接过」的半成品风险点，
 * 所以每一条都直接打引擎的公共入口，而不是只断言控件存在。
 */
class TransferDepthTest {

    @TempDir
    Path temp;

    // ─────────────────────────── 导入：编码 / BOM ───────────────────────────

    @Test
    void importsGbkCsvWithoutManglingChinese() throws Exception {
        // GBK 里「张三」= D5 C5 C8 FD；用 UTF-8 读会得到替换字符
        Path file = temp.resolve("gbk.csv");
        byte[] gbk = "id,name\n11,张三\n12,李四\n".getBytes(Charset.forName("GBK"));
        Files.write(file, gbk);

        try (JdbcSession session = seeded()) {
            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "t",
                    columns(), ImportOptions.of(ImportFormat.CSV)
                            .withCharset(Charset.forName("GBK")), file);

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(valueOf(session, "SELECT name FROM t WHERE id = 11")).isEqualTo("张三");
        }
    }

    @Test
    void stripsTheUtfBomSoTheFirstHeaderStillMatches() throws Exception {
        Path file = temp.resolve("bom.csv");
        byte[] body = "id,name\n7,eta\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        Files.write(file, bytes);

        try (JdbcSession session = seeded()) {
            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "t",
                    columns(), ImportOptions.of(ImportFormat.CSV), file);

            // 表头没被 BOM 污染 → 不会出现「表头与目标列无法匹配」
            assertThat(report.hasErrors()).isFalse();
            assertThat(report.inserted()).isEqualTo(1);
            assertThat(valueOf(session, "SELECT name FROM t WHERE id = 7")).isEqualTo("eta");
        }
    }

    // ─────────────────────────── 导入：表头行 ───────────────────────────

    @Test
    void headerRowSkipsTheLeadingJunkLines() throws Exception {
        // 真实导出文件常带标题/说明行，表头在第 3 行
        String text = "导出时间 2026-10-28\n\nid,name\n9,iota\n";

        try (JdbcSession session = seeded()) {
            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "t",
                    columns(), ImportOptions.of(ImportFormat.CSV).withHeaderRow(3),
                    new java.io.StringReader(text));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(valueOf(session, "SELECT name FROM t WHERE id = 9")).isEqualTo("iota");
        }
    }

    // ─────────────────────────── 导入：覆盖模式 ───────────────────────────

    @Test
    void replaceModeClearsTheTableFirstAndReportsHowManyRowsWent() throws Exception {
        try (JdbcSession session = seeded()) {
            TableImporter.ImportReport report = TableImporter.importRows(session, "t",
                    List.of("id", "name"),
                    ImportOptions.of(ImportFormat.CSV).withMode(ImportMode.REPLACE),
                    new java.io.StringReader("id,name\n1,fresh\n"));

            assertThat(report.deleted()).isEqualTo(3);
            assertThat(report.inserted()).isEqualTo(1);
            assertThat(countRows(session, "t")).isEqualTo(1);
            assertThat(valueOf(session, "SELECT name FROM t WHERE id = 1")).isEqualTo("fresh");
        }
    }

    @Test
    void replaceModeRefusesToDeleteInsideSomeoneElsesTransaction() throws Exception {
        try (JdbcSession session = seeded()) {
            session.begin();
            try {
                assertThatThrownBy(() -> TableImporter.importRows(session, "t",
                        List.of("id", "name"),
                        ImportOptions.of(ImportFormat.CSV).withMode(ImportMode.REPLACE),
                        new java.io.StringReader("id,name\n4,delta\n")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("覆盖模式");
                // ⭐ 拒绝的必须是「什么都没发生」：已有 3 行还在
                assertThat(countRows(session, "t")).isEqualTo(3);
            } finally {
                session.rollback();
            }
        }
    }

    // ─────────────────────────── 导出：文本限定符 ───────────────────────────

    @Test
    void quoteAllWrapsEveryFieldWhileTheDefaultStaysMinimal() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter minimal = new StringWriter();
            TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.CSV), minimal);
            assertThat(minimal.toString().lines().findFirst().orElse(""))
                    .isEqualTo("id,name");

            StringWriter quoted = new StringWriter();
            TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.CSV).withQuoteAll(true), quoted);
            assertThat(quoted.toString().lines().findFirst().orElse(""))
                    .isEqualTo("\"id\",\"name\"");
            // 表头与数据同一口径：第一条数据行也带引号
            assertThat(quoted.toString().lines().skip(1).findFirst().orElse(""))
                    .isEqualTo("\"1\",\"alpha\"");
        }
    }

    // ─────────────────────────── 导出：列选择 ───────────────────────────

    @Test
    void selectingColumnsExportsOnlyThoseColumns() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            TableExporter.export(session, "t", List.of("name"),
                    ExportOptions.of(ExportFormat.CSV), out);

            assertThat(out.toString().lines().findFirst().orElse("")).isEqualTo("name");
            assertThat(out.toString()).doesNotContain("id,");
        }
    }

    // ─────────────────────────── 导出：XML / HTML ───────────────────────────

    @Test
    void exportsXmlWithColumnNamesAsAttributes() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            long rows = TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.XML).withMaxRows(1), out);

            assertThat(rows).isEqualTo(1);
            assertThat(out.toString())
                    .contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    .contains("<rows>")
                    .contains("<row><c name=\"id\">1</c><c name=\"name\">alpha</c></row>")
                    .endsWith("</rows>\n");
        }
    }

    @Test
    void exportsHtmlWithAHeaderRowAndACompleteDocument() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            TableExporter.export(session, "t", List.of("id", "name"),
                    ExportOptions.of(ExportFormat.HTML).withMaxRows(1), out);

            String html = out.toString();
            assertThat(html).contains("<thead><tr><th>id</th><th>name</th></tr></thead>");
            assertThat(html).contains("<tr><td>1</td><td>alpha</td></tr>");
            assertThat(html).endsWith("</tbody>\n</table>\n</body>\n</html>\n");
        }
    }

    @Test
    void htmlEscapesMarkupInValues() throws Exception {
        StringWriter out = new StringWriter();
        TableExporter.exportRows("t", List.of("id", "name"),
                List.of(List.of(5, "<b>&</b>")),
                ExportOptions.of(ExportFormat.HTML), out);

        assertThat(out.toString()).contains("<td>&lt;b&gt;&amp;&lt;/b&gt;</td>")
                .doesNotContain("<b>&</b>");
    }

    // ─────────────────────────── 导出：内存行（当前页 / 选中行） ───────────────────────────

    @Test
    void exportRowsWritesExactlyTheRowsItWasGiven() throws Exception {
        StringWriter out = new StringWriter();
        long rows = TableExporter.exportRows("t", List.of("id", "name"),
                List.of(List.of(2, "beta"), List.of(3, "gamma")),
                ExportOptions.of(ExportFormat.CSV), out);

        assertThat(rows).isEqualTo(2);
        assertThat(out.toString()).contains("beta").contains("gamma")
                .doesNotContain("alpha");
    }

    @Test
    void exportRowsRefusesSqlWithoutATableName() {
        assertThatThrownBy(() -> TableExporter.exportRows(null, List.of("id"),
                List.of(List.of(1)), ExportOptions.of(ExportFormat.SQL), new StringWriter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL");
    }

    // ─────────────────────────── 格式元数据 ───────────────────────────

    @Test
    void everyTextFormatHasAnExtensionAndOnlyCsvTsvHaveHeaders() {
        assertThat(ExportFormat.XML.extension()).isEqualTo(".xml");
        assertThat(ExportFormat.HTML.extension()).isEqualTo(".html");
        assertThat(ExportFormat.CSV.hasHeader()).isTrue();
        assertThat(ExportFormat.JSON.hasHeader()).isFalse();
        assertThat(ExportFormat.HTML.hasHeader()).isFalse();
    }

    // ─────────────────────────── 脚手架 ───────────────────────────

    private static List<ColumnDef> columns() {
        return List.of(
                new ColumnDef("id", ColumnType.NUMBER, false, true, null, null, null,
                        null, null, null, false),
                new ColumnDef("name", ColumnType.TEXT, true, false, null, null, null,
                        null, null, null, false));
    }

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
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String valueOf(JdbcSession session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
