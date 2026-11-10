package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P-3：脱敏导出的端到端门禁。
 *
 * <p>安全功能最怕「某一条写出路径忘了打码」：CSV / JSON / SQL（内存行）、
 * 整表 JDBC 流式、xlsx 二进制，五条路径各验一次明文确实没落盘。
 * 表头一律保持原样 —— 列名不是数据，打码了文件反而不能用。
 */
class MaskedExportTest {

    @TempDir
    Path temp;

    private static final List<String> NAMES = List.of("id", "email", "age");
    private static final List<List<Object>> ROWS = List.of(
            List.of(1, "alice@example.com", 30),
            List.of(2, "bob@example.com", 41));
    private static final List<MaskRule> RULES =
            List.of(new MaskRule("email", MaskRule.MaskMode.FULL));

    @Test
    void csvRowsMaskOnlyTheConfiguredColumn() throws Exception {
        StringWriter out = new StringWriter();
        long written = TableExporter.exportRows("users", NAMES, ROWS,
                ExportOptions.of(ExportFormat.CSV).withMasks(RULES), out);

        assertThat(written).isEqualTo(2);
        String[] lines = out.toString().split("\\R");
        assertThat(lines[0]).as("表头不是数据，不许打码").isEqualTo("id,email,age");
        assertThat(lines[1]).isEqualTo("1,******,30");
        assertThat(lines[2]).isEqualTo("2,******,41");
        assertThat(out.toString())
                .doesNotContain("alice@example.com", "bob@example.com");
    }

    @Test
    void jsonAndSqlRowsUseTheSameMask() throws Exception {
        StringWriter json = new StringWriter();
        TableExporter.exportRows("users", NAMES, ROWS,
                ExportOptions.of(ExportFormat.JSON).withMasks(RULES), json);
        assertThat(json.toString()).contains("\"email\":\"******\"");
        assertThat(json.toString()).doesNotContain("alice@example.com");

        StringWriter sql = new StringWriter();
        TableExporter.exportRows("users", NAMES, ROWS,
                ExportOptions.of(ExportFormat.SQL).withMasks(RULES), sql);
        assertThat(sql.toString()).contains("'******'");
        assertThat(sql.toString()).doesNotContain("alice@example.com");
    }

    @Test
    void wholeTableJdbcStreamMasksToo() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();
            long written = TableExporter.export(session, "users", List.of(),
                    ExportOptions.of(ExportFormat.CSV).withMasks(RULES), out);

            assertThat(written).isEqualTo(2);
            assertThat(out.toString()).contains("id,email,age");
            assertThat(out.toString()).contains("******");
            assertThat(out.toString())
                    .doesNotContain("alice@example.com", "bob@example.com");
        }
    }

    @Test
    void xlsxBinaryPathMasksToo() throws Exception {
        Path file = temp.resolve("masked.xlsx");
        try (OutputStream out = Files.newOutputStream(file)) {
            TableExporter.exportRowsXlsx(NAMES, ROWS,
                    ExportOptions.of(ExportFormat.XLSX).withMasks(RULES), out);
        }
        List<List<String>> rows = XlsxCodec.preview(file, 10);
        assertThat(rows.getFirst()).containsExactly("id", "email", "age");
        assertThat(rows.get(1)).containsExactly("1", "******", "30");
        assertThat(rows.get(2)).containsExactly("2", "******", "41");
    }

    @Test
    void withHelpersNeverDropTheRules() {
        ExportOptions options = ExportOptions.of(ExportFormat.CSV)
                .withMasks(RULES)
                .withMaxRows(1)
                .withHeader(false)
                .withQuoteAll(true);
        assertThat(options.masks()).containsExactlyElementsOf(RULES);
        assertThat(options.maxRows()).isEqualTo(1);
        assertThat(options.includeHeader()).isFalse();
        assertThat(options.quoteAll()).isTrue();

        // 老的 6 参构造器（BackupDumper / 一批既有测试仍在用）默认不脱敏。
        assertThat(new ExportOptions(ExportFormat.CSV, true, 500, 0, false,
                java.nio.charset.StandardCharsets.UTF_8).masks()).isEmpty();
        assertThat(ExportOptions.of(ExportFormat.CSV).masks()).isEmpty();
    }

    /** ⭐ 盐值和规则一样，不能被任何 with* 弄丢；丢了就退回可被彩虹表反推的无盐哈希。 */
    @Test
    void saltSurvivesWithHelpersAndChangesTheWrittenHash() throws Exception {
        List<MaskRule> hashRules =
                List.of(new MaskRule("email", MaskRule.MaskMode.HASH));
        ExportOptions options = ExportOptions.of(ExportFormat.CSV)
                .withMasks(hashRules)
                .withMaskSalt("k1")
                .withMaxRows(0)
                .withHeader(true)
                .withQuoteAll(true)
                .withCharset(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(options.maskSalt()).isEqualTo("k1");
        assertThat(options.masks()).containsExactlyElementsOf(hashRules);

        StringWriter unsaltedOut = new StringWriter();
        TableExporter.exportRows("users", NAMES, ROWS,
                ExportOptions.of(ExportFormat.CSV).withMasks(hashRules), unsaltedOut);
        StringWriter saltedOut = new StringWriter();
        TableExporter.exportRows("users", NAMES, ROWS, options, saltedOut);

        String unsalted = unsaltedOut.toString();
        String salted = saltedOut.toString();
        assertThat(salted).contains("sha256:").doesNotContain("alice@example.com");
        assertThat(salted).isNotEqualTo(unsalted)
                .as("同一个原值在加盐后必须换一个指纹");
        assertThat(salted).doesNotContain("k1")
                .as("盐值本身绝不能写进导出文件");
    }

    /** 盐值要走到 JDBC 流式与 xlsx 两条路径，不只内存行文本格式。 */
    @Test
    void saltReachesJdbcStreamAndXlsx() throws Exception {
        List<MaskRule> hashRules =
                List.of(new MaskRule("email", MaskRule.MaskMode.HASH));
        ExportOptions salted = ExportOptions.of(ExportFormat.CSV)
                .withMasks(hashRules).withMaskSalt("k2");
        ExportOptions plain = ExportOptions.of(ExportFormat.CSV).withMasks(hashRules);

        try (JdbcSession session = seeded()) {
            StringWriter a = new StringWriter();
            StringWriter b = new StringWriter();
            TableExporter.export(session, "users", List.of(), plain, a);
            TableExporter.export(session, "users", List.of(), salted, b);
            assertThat(b.toString()).isNotEqualTo(a.toString());
            assertThat(b.toString()).doesNotContain("alice@example.com", "k2");
        }

        Path file = temp.resolve("salted.xlsx");
        try (OutputStream out = Files.newOutputStream(file)) {
            TableExporter.exportRowsXlsx(NAMES, ROWS,
                    ExportOptions.of(ExportFormat.XLSX)
                            .withMasks(hashRules).withMaskSalt("k2"), out);
        }
        List<List<String>> rows = XlsxCodec.preview(file, 10);
        assertThat(rows.get(1).get(1)).startsWith("sha256:");
        assertThat(rows.get(1).get(1)).isNotEqualTo(
                DataMasker.mask("alice@example.com",
                        new MaskRule("email", MaskRule.MaskMode.HASH)).toString());
    }

    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE users (id INTEGER, email TEXT, age INTEGER)");
        session.execute("INSERT INTO users VALUES "
                + "(1,'alice@example.com',30),(2,'bob@example.com',41)");
        return session;
    }
}
