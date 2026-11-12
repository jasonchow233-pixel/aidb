package io.agenticdb.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-86：导出选项的<b>行为</b>门禁 —— 证明每个选项不是「只存字段」，而是真的改变了写出的字节。
 *
 * <p>⚠️ 纯 JUnit + 字符串 / 文件断言，<b>无 JavaFX toolkit 依赖</b>：只调
 * {@link TableExporter} / {@link ExportOptions}，用 {@link StringWriter} 和 {@code @TempDir}。
 *
 * <h2>变异验证步骤（把生产代码改回 D-86 之前的旧行为，下面的用例必须红）</h2>
 * <ol>
 *   <li>把 {@code ExportOptions} 的新字段删掉、或让任意一个 {@code withXxx} 只
 *       {@code new} 旧的 8 个参数 → {@link #withHelpersNeverDropTheNewFields()} 红。</li>
 *   <li>让 {@code writePreamble} 不看 {@code skipHeader}（append 时照写表头） →
 *       {@link #appendWritesTwiceWithoutLosingRowsOrRepeatingHeader()} 红（表头重复）。</li>
 *   <li>把 {@code renderDataRow} 改回一律 {@code String.valueOf}、不经过
 *       {@code ExportValueFormatter} → decimalSymbol / binaryEncoding / formats 三条全红。</li>
 *   <li>把 {@code CsvCodec} 改回写死 {@code '"'} 与 {@code \r\n} →
 *       textQualifier / recordDelimiter 两条红。</li>
 *   <li>删掉 {@code writeDataRow} 的整行缓冲 try/catch →
 *       continueOnError 的两条红（坏行不再被跳过、或异常不再抛出）。</li>
 *   <li>把 CSV 的 recordDelimiter 默认值改成 {@code \n} →
 *       {@link #defaultsReproduceTheByteForByteLegacyOutput()} 红（旧行为是 CRLF）。</li>
 *   <li>把 HTML 标题改回硬编码中文「查询结果」（回退 D-44 WIP） →
 *       {@link #defaultsReproduceTheByteForByteLegacyOutput()} 红（基线是 {@code <title>t</title>}）。</li>
 * </ol>
 */
class ExportOptionsBehaviorTest {

    private static final List<String> NAMES = List.of("id", "note");

    @TempDir
    Path temp;

    // ───────────────────── 默认值 / 归一化 / 链式 ─────────────────────

    @Test
    void defaultsAreTheDocumentedOnes() {
        ExportOptions o = ExportOptions.of(ExportFormat.CSV);
        assertThat(o.append()).isFalse();
        assertThat(o.continueOnError()).isFalse();
        assertThat(o.recordDelimiter()).isEqualTo("\r\n");
        assertThat(o.textQualifier()).isEqualTo('"');
        assertThat(o.dateOrder()).isEqualTo("YMD");
        assertThat(o.dateDelimiter()).isEqualTo("-");
        assertThat(o.zeroPaddingDate()).isTrue();
        assertThat(o.timeDelimiter()).isEqualTo(":");
        assertThat(o.decimalSymbol()).isEqualTo('.');
        assertThat(o.binaryEncoding()).isEqualTo("HEX");
    }

    @Test
    void illegalValuesAreNormalizedToSafeDefaults() {
        ExportOptions o = new ExportOptions(null, true, 0, 0, false, null, null, null,
                false, false, null, '\n', "nope", null, false, null, 'x', "bogus");
        assertThat(o.format()).isEqualTo(ExportFormat.CSV);
        assertThat(o.batchSize()).isEqualTo(500);
        assertThat(o.charset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(o.masks()).isEmpty();
        assertThat(o.maskSalt()).isEmpty();
        assertThat(o.recordDelimiter()).isEqualTo("\r\n");
        assertThat(o.textQualifier()).isEqualTo('"');
        assertThat(o.dateOrder()).isEqualTo("YMD");
        assertThat(o.dateDelimiter()).isEqualTo("-");
        assertThat(o.timeDelimiter()).isEqualTo(":");
        assertThat(o.decimalSymbol()).isEqualTo('.');
        assertThat(o.binaryEncoding()).isEqualTo("HEX");
    }

    @Test
    void withHelpersNeverDropTheNewFields() {
        ExportOptions o = ExportOptions.of(ExportFormat.CSV)
                .withAppend(true)
                .withContinueOnError(true)
                .withRecordDelimiter("\r\n")
                .withTextQualifier('\'')
                .withFormats("DMY", "/", false, ".", ',', "BASE64")
                .withMasks(List.of(new MaskRule("x", MaskRule.MaskMode.FULL)))
                .withMaskSalt("salt")
                .withMaxRows(7)
                .withHeader(false)
                .withQuoteAll(true)
                .withCharset(StandardCharsets.UTF_16LE);
        assertThat(o.append()).isTrue();
        assertThat(o.continueOnError()).isTrue();
        assertThat(o.recordDelimiter()).isEqualTo("\r\n");
        assertThat(o.textQualifier()).isEqualTo('\'');
        assertThat(o.dateOrder()).isEqualTo("DMY");
        assertThat(o.dateDelimiter()).isEqualTo("/");
        assertThat(o.zeroPaddingDate()).isFalse();
        assertThat(o.timeDelimiter()).isEqualTo(".");
        assertThat(o.decimalSymbol()).isEqualTo(',');
        assertThat(o.binaryEncoding()).isEqualTo("BASE64");
        assertThat(o.masks()).hasSize(1);
        assertThat(o.maskSalt()).isEqualTo("salt");
        assertThat(o.maxRows()).isEqualTo(7);
        assertThat(o.includeHeader()).isFalse();
        assertThat(o.quoteAll()).isTrue();
        assertThat(o.charset()).isEqualTo(StandardCharsets.UTF_16LE);
    }

    @ParameterizedTest
    @EnumSource(ExportFormat.class)
    void defaultsAndLegacyConstructorsChooseRecordDelimiterByFormat(ExportFormat format)
            throws Exception {
        String expected = format.hasHeader() ? "\r\n" : "\n";
        ExportOptions defaults = ExportOptions.of(format);
        assertThat(defaults.recordDelimiter()).isEqualTo(expected);
        assertThat(defaults.withRecordDelimiter(null).recordDelimiter()).isEqualTo(expected);
        assertThat(defaults.withRecordDelimiter("").recordDelimiter()).isEqualTo(expected);
        for (ExportOptions legacy : List.of(
                new ExportOptions(format, true, 500, 0, false, StandardCharsets.UTF_8),
                new ExportOptions(format, true, 500, 0, false, StandardCharsets.UTF_8, List.of()),
                new ExportOptions(format, true, 500, 0, false, StandardCharsets.UTF_8, List.of(), ""))) {
            assertThat(legacy).isEqualTo(defaults);
            if (!format.binary()) {
                StringWriter out = new StringWriter();
                TableExporter.exportRows("t", NAMES, List.of(List.<Object>of(1, "a")), legacy, out);
                assertThat(out.toString()).isEqualTo(
                        render(format, NAMES, List.of(List.<Object>of(1, "a"))));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ExportFormat.class, names = {"CSV", "TSV"})
    void explicitLfSurvivesEveryCopyHelperAndIsWrittenVerbatim(ExportFormat format)
            throws Exception {
        ExportOptions lf = ExportOptions.of(format).withRecordDelimiter("\n");
        for (ExportOptions copy : List.of(
                lf.withMaxRows(1), lf.withCharset(StandardCharsets.UTF_16LE),
                lf.withQuoteAll(true), lf.withHeader(false),
                lf.withMasks(List.of(new MaskRule("note", MaskRule.MaskMode.FULL))),
                lf.withMaskSalt("salt"), lf.withAppend(true), lf.withContinueOnError(true),
                lf.withTextQualifier('\''), lf.withFormats("DMY", "/", false, ".", ',', "BASE64"),
                lf.withDateOrder("DMY"), lf.withDateDelimiter("/"), lf.withZeroPaddingDate(false),
                lf.withTimeDelimiter("."), lf.withDecimalSymbol(','), lf.withBinaryEncoding("BASE64"))) {
            assertThat(copy.recordDelimiter()).isEqualTo("\n");
            StringWriter out = new StringWriter();
            TableExporter.exportRows("t", NAMES, List.of(List.<Object>of(1, "a")), copy, out);
            assertThat(out.toString()).endsWith("\n").doesNotContain("\r");
        }
        StringWriter out = new StringWriter();
        TableExporter.exportRows("t", NAMES, List.of(List.<Object>of(1, "a")), lf, out);
        String delimiter = String.valueOf(format.delimiter());
        assertThat(out.toString()).isEqualTo("id" + delimiter + "note\n1" + delimiter + "a\n");
    }

    // ───────────────────── append ─────────────────────

    @Test
    void appendWritesTwiceWithoutLosingRowsOrRepeatingHeader() throws Exception {
        Path file = temp.resolve("append.csv");
        ExportOptions first = ExportOptions.of(ExportFormat.CSV);
        ExportOptions second = ExportOptions.of(ExportFormat.CSV).withAppend(true);

        // 第一次：目标不存在，TRUNCATE + 表头（旧行为）。
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            TableExporter.exportRowsWithResult("t", NAMES,
                    List.of(List.<Object>of(1, "a")), first, writer, false);
        }
        // 第二次：模拟 ExportWizard.openForAppend —— APPEND 打开 + skipHeader=true。
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            TableExporter.exportRowsWithResult("t", NAMES,
                    List.of(List.<Object>of(2, "b")), second, writer, true);
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("id,note\r\n1,a\r\n2,b\r\n");
        assertThat(text.split("id,note", -1)).hasSize(2);   // 表头只出现一次
        assertThat(text).contains("1,a", "2,b");             // 两次的行都在
    }

    // ───────────────────── continueOnError ─────────────────────

    /** 一个「渲染即抛」的坏值：String.valueOf 会调它的 toString()。 */
    private static Object explodingValue() {
        return new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        };
    }

    @Test
    void continueOnErrorSkipsBadRowsAndReportsTheCount() throws Exception {
        List<List<Object>> rows = List.of(
                List.<Object>of(1, "ok"),
                List.<Object>of(2, explodingValue()),
                List.<Object>of(3, "fine"));
        StringWriter out = new StringWriter();
        ExportResult result = TableExporter.exportRowsWithResult("t", NAMES, rows,
                ExportOptions.of(ExportFormat.CSV).withContinueOnError(true), out, false);

        assertThat(result.rows()).isEqualTo(2);
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.hasErrors()).isTrue();
        assertThat(out.toString())
                .isEqualTo("id,note\r\n1,ok\r\n3,fine\r\n")
                .doesNotContain("boom");   // 整行缓冲，坏行一个字节都没落盘
    }

    @Test
    void continueOnErrorOffSurfacesTheFailure() {
        List<List<Object>> rows = List.of(
                List.<Object>of(1, "ok"),
                List.<Object>of(2, explodingValue()));
        assertThatThrownBy(() -> TableExporter.exportRowsWithResult("t", NAMES, rows,
                ExportOptions.of(ExportFormat.CSV), new StringWriter(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    // ───────────────────── 各选项真的改变字节 ─────────────────────

    @Test
    void recordDelimiterChangesEveryTextRecordEnding() throws Exception {
        StringWriter csv = new StringWriter();
        TableExporter.exportRows("t", NAMES, List.of(List.<Object>of(1, "a")),
                ExportOptions.of(ExportFormat.CSV).withRecordDelimiter("|\n"), csv);
        assertThat(csv.toString()).isEqualTo("id,note|\n1,a|\n");

        StringWriter json = new StringWriter();
        TableExporter.exportRows("t", NAMES, List.of(List.<Object>of(1, "a")),
                ExportOptions.of(ExportFormat.JSON).withRecordDelimiter("<EOR>\n"), json);
        assertThat(json.toString()).isEqualTo("{\"id\":1,\"note\":\"a\"}<EOR>\n");
    }

    @Test
    void textQualifierChangesCsvQuotingAndEscaping() throws Exception {
        // quoteAll 与 textQualifier 是两件事：前者决定「给所有字段加引号」，
        // 后者决定「用哪个字符加、字段内怎么翻倍」。这里两个都设，验证彼此独立又同时生效。
        StringWriter out = new StringWriter();
        TableExporter.exportRows("t", List.of("a", "b"),
                List.of(List.<Object>of("x'y", "plain")),
                ExportOptions.of(ExportFormat.CSV).withTextQualifier('\'').withQuoteAll(true), out);
        assertThat(out.toString()).isEqualTo("'a','b'\r\n'x''y','plain'\r\n");
    }

    @Test
    void decimalSymbolRendersDecimalsWithTheChosenSymbol() throws Exception {
        StringWriter out = new StringWriter();
        TableExporter.exportRows("t", List.of("amount"),
                List.of(List.<Object>of(new BigDecimal("12.50"))),
                ExportOptions.of(ExportFormat.CSV).withDecimalSymbol(','), out);
        assertThat(out.toString()).isEqualTo("amount\r\n\"12,50\"\r\n");
    }

    @Test
    void binaryEncodingRendersBytesAsHexOrBase64() throws Exception {
        byte[] data = "Hi".getBytes(StandardCharsets.UTF_8);   // 48 69
        StringWriter hex = new StringWriter();
        TableExporter.exportRows("t", List.of("blob"), List.of(List.<Object>of(data)),
                ExportOptions.of(ExportFormat.CSV), hex);       // 默认 HEX
        assertThat(hex.toString()).isEqualTo("blob\r\n4869\r\n");

        StringWriter b64 = new StringWriter();
        TableExporter.exportRows("t", List.of("blob"), List.of(List.<Object>of(data)),
                ExportOptions.of(ExportFormat.CSV).withBinaryEncoding("BASE64"), b64);
        assertThat(b64.toString()).isEqualTo("blob\r\nSGk=\r\n");
    }

    @Test
    void formatsRenderDatesAndTimes() throws Exception {
        java.sql.Date date = java.sql.Date.valueOf("2026-03-07");
        java.sql.Time time = java.sql.Time.valueOf("09:04:05");
        List<List<Object>> rows = List.of(List.<Object>of(date, time));
        List<String> names = List.of("d", "t");

        StringWriter dmy = new StringWriter();
        TableExporter.exportRows("t", names, rows,
                ExportOptions.of(ExportFormat.CSV)
                        .withDateOrder("DMY").withDateDelimiter("/"), dmy);
        assertThat(dmy.toString()).contains("07/03/2026");

        StringWriter noPad = new StringWriter();
        TableExporter.exportRows("t", names, rows,
                ExportOptions.of(ExportFormat.CSV).withZeroPaddingDate(false), noPad);
        assertThat(noPad.toString()).contains("2026-3-7");

        StringWriter dots = new StringWriter();
        TableExporter.exportRows("t", names, rows,
                ExportOptions.of(ExportFormat.CSV).withTimeDelimiter("."), dots);
        assertThat(dots.toString()).contains("09.04.05");
    }

    // ───────────────────── 默认值 == 旧行为（逐字节回归） ─────────────────────

    /**
     * 基线是 D-86 改动前逐字节抄下来的输出。任何一次「顺手重构」让默认选项
     * 多写/少写一个字符，都会在这里红。HTML 里的 {@code <title>t</title>} 同时守住
     * D-44 的动态标题 WIP —— 回退成硬编码中文会立刻红。
     */
    @Test
    void defaultsReproduceTheByteForByteLegacyOutput() throws Exception {
        List<String> names = List.of("id", "note");
        List<List<Object>> rows = List.of(
                List.<Object>of(1, "alpha"),
                List.<Object>of(2, "b,eta"));   // 含逗号：触发 CSV 旧引号规则

        assertThat(render(ExportFormat.CSV, names, rows))
                .isEqualTo("id,note\r\n1,alpha\r\n2,\"b,eta\"\r\n");
        assertThat(render(ExportFormat.TSV, names, rows))
                .isEqualTo("id\tnote\r\n1\talpha\r\n2\tb,eta\r\n");
        assertThat(render(ExportFormat.JSON, names, rows))
                .isEqualTo("{\"id\":1,\"note\":\"alpha\"}\n"
                        + "{\"id\":2,\"note\":\"b,eta\"}\n");
        // 裸 tuples 是旧缺陷，不作为兼容基线保留。
        assertThat(render(ExportFormat.SQL, names, rows))
                .isEqualTo("-- 导出自 AIDB\nINSERT INTO \"t\" (\"id\", \"note\") VALUES\n"
                        + "  (1, 'alpha'),\n  (2, 'b,eta');\n");
        assertThat(render(ExportFormat.XML, names, rows))
                .isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n"
                        + "<row><c name=\"id\">1</c><c name=\"note\">alpha</c></row>\n"
                        + "<row><c name=\"id\">2</c><c name=\"note\">b,eta</c></row>\n"
                        + "</rows>\n");
        assertThat(render(ExportFormat.HTML, names, rows))
                .isEqualTo("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
                        + "<title>t</title>\n</head>\n<body>\n<table>\n"
                        + "<thead><tr><th>id</th><th>note</th></tr></thead>\n<tbody>\n"
                        + "<tr><td>1</td><td>alpha</td></tr>\n"
                        + "<tr><td>2</td><td>b,eta</td></tr>\n"
                        + "</tbody>\n</table>\n</body>\n</html>\n");
    }

    private static String render(ExportFormat format, List<String> names,
                                 List<List<Object>> rows) throws IOException {
        StringWriter out = new StringWriter();
        TableExporter.exportRows("t", names, rows, ExportOptions.of(format), out);
        return out.toString();
    }
}
