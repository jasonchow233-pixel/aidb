package io.agenticdb.ui.shell;

import io.agenticdb.transfer.ExportFormat;
import javafx.stage.FileChooser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ R57-d：查询结果导出的<b>接线层</b>规则。
 *
 * <p>这里测的是「界面递给那台七格式机器之前必须先立的规矩」，不是编码器本身 ——
 * CSV 引号、XML 转义、xlsx 分块那些在 {@code adb-transfer} 有自己的门禁
 * （{@code TransferExportTest} / {@code TransferDepthTest}），这里再抄一遍只会变成
 * 同一笔账两张条：改一处格式细节，两边一起红，然后没人分得出哪边是真的坏了。
 *
 * <p>四件事各钉一条：
 * <ol>
 *   <li><b>档是哪六档</b> —— SQL（{@code INSERT}）不在里面，且是有理由的不该在里面；</li>
 *   <li><b>认不出格式时不许猜</b> —— 悄悄导成 CSV，用户会拿着一个"看起来是 JSON"的文件走人；</li>
 *   <li><b>文件名后缀跟着选中的档走</b> —— 选了 JSON 却落出没后缀的文件，双击什么也打不开；</li>
 *   <li><b>BOM 只给分隔符格式</b> —— Excel 拆中文要靠它，但 JSON / XML / HTML 多一枚会报错。</li>
 * </ol>
 */
class ResultExportTest {

    private static final List<String> COLUMNS = List.of("id", "name", "score");
    private static final List<List<Object>> ROWS = List.of(
            List.of(42, "Ada", 98.5),
            java.util.Arrays.asList(7, null, false));

    // ─────────────────────── 1. 档是哪六档 ───────────────────────

    @Test
    void offersSixFormatsWithCsvFirstAndNoSqlFormat() {
        assertThat(ResultExport.formats())
                .as("⭐ 查询结果没有目标表名，SQL(INSERT) 那一档写不出可执行的文件 —— "
                        + "少了它不是漏，是这一层的边界（要 INSERT 用导出向导）")
                .containsExactly(ExportFormat.CSV, ExportFormat.TSV, ExportFormat.JSON,
                        ExportFormat.XLSX, ExportFormat.XML, ExportFormat.HTML)
                .doesNotContain(ExportFormat.SQL);
        assertThat(ResultExport.formats().getFirst())
                .as("第一档就是保存对话框打开时的默认档，导 CSV 的人最多")
                .isEqualTo(ExportFormat.CSV);
    }

    @Test
    void filterListCarriesOnePatternPerFormat() {
        List<FileChooser.ExtensionFilter> filters = ResultExport.filters();
        assertThat(filters).hasSize(ResultExport.formats().size());
        for (int i = 0; i < filters.size(); i++) {
            ExportFormat format = ResultExport.formats().get(i);
            assertThat(filters.get(i).getExtensions())
                    .as("每一档只一个扩展名，否则 formatOf 取第一档会取到没约定的那个")
                    .containsExactly("*" + format.extension());
            assertThat(filters.get(i).getDescription())
                    .as("对话框里那一行要同时写出格式名和扩展名，人才知道选的是哪档")
                    .isEqualTo(format.label() + " (*" + format.extension() + ")");
        }
    }

    @Test
    void selectedFilterMapsBackToItsFormatByExtension() {
        for (FileChooser.ExtensionFilter filter : ResultExport.filters()) {
            assertThat(ResultExport.formatOf(filter).extension())
                    .as("按扩展名往返：认档不能靠标签，标签跟着界面语言变")
                    .isEqualTo(filter.getExtensions().getFirst().substring(1));
        }
    }

    // ─────────────────── 2. 认不出来就不猜 ───────────────────

    @Test
    void missingFilterIsRefusedInsteadOfGuessingCsv() {
        assertThatThrownBy(() -> ResultExport.formatOf(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No export format selected");
    }

    @Test
    void unknownExtensionIsRefusedNotSilentlyExported() {
        FileChooser.ExtensionFilter txt =
                new FileChooser.ExtensionFilter("Text (*.txt)", "*.txt");
        assertThatThrownBy(() -> ResultExport.formatOf(txt))
                .as("用户自定义的档不在此列：宁可乐失败，也不导一个他没选的格式")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported export format: *.txt");
    }

    // ─────────────────── 3. 文件名跟着档走 ───────────────────

    @Test
    void knownSuffixIsReplacedAndMissingSuffixIsAppended() {
        assertThat(ResultExport.withExtension("query-result.csv", ExportFormat.JSON))
                .isEqualTo("query-result.json");
        assertThat(ResultExport.withExtension("query-result", ExportFormat.XLSX))
                .as("⭐ 完全没有后缀时必须补上 —— 保存对话框里选了 xlsx 却落出无后缀文件，"
                        + "双击打不开，用户只会以为是导出坏了")
                .isEqualTo("query-result.xlsx");
        assertThat(ResultExport.withExtension("report.txt", ExportFormat.CSV))
                .as("⭐ 对不上任何导出档的后缀要补上，不是原样留着 —— "
                        + "名字写着 .txt、里面是 CSV，下一个打开的人会以为是导出坏了")
                .isEqualTo("report.txt.csv");
    }

    @Test
    void pathVersionKeepsDirectoryAndFollowsTheSameRule() {
        java.nio.file.Path swapped = ResultExport.withExtension(
                java.nio.file.Paths.get("/tmp/out/query-result.csv"), ExportFormat.TSV);
        assertThat(swapped.toString())
                .isEqualTo(java.nio.file.Paths.get("/tmp/out/query-result.tsv").toString());
    }

    // ─────────────────────── 4. BOM 的边界 ───────────────────────

    @Test
    void bomPrecedesOnlyTheDelimiterFormats() throws IOException {
        assertThat(textOf(ExportFormat.CSV)).startsWith("\uFEFF")
                .as("表头仍紧跟 BOM，Excel 才拆得开中文列名").contains("id,name,score");
        assertThat(textOf(ExportFormat.TSV)).startsWith("\uFEFF");
        for (ExportFormat format : List.of(ExportFormat.JSON, ExportFormat.XML,
                ExportFormat.HTML)) {
            assertThat(textOf(format))
                    .as("%s 有自己的编码声明，多一枚 BOM 会让严格的解析器报错", format)
                    .doesNotStartWith("\uFEFF");
        }
    }

    @Test
    void rawValuesSurviveIntoJsonInsteadOfEverythingBecomingText() throws IOException {
        String json = textOf(ExportFormat.JSON);
        assertThat(json)
                .as("⭐ 旧的「本页/选中行」交的是 String.valueOf 之后的网格文本，"
                        + "换 JSON 会把 42 写成 \"42\"、把 NULL 写成 \"\"。"
                        + "现在三个范围同一条口径：交 JDBC 原始值")
                .contains("\"id\":42")
                .contains("\"score\":98.5")
                .contains("\"name\":null");
        assertThat(textOf(ExportFormat.CSV))
                .as("CSV 那边 NULL 是空字段（分隔符格式没有 NULL 的表示法）")
                .contains("7,,false");
    }

    // ─────────────────── 5. 二进制与文本不许走错门 ───────────────────

    @Test
    void sqlFormatIsRefusedForInMemoryRows() {
        StringWriter out = new StringWriter();
        assertThatThrownBy(() -> ResultExport.writeRows(COLUMNS, ROWS, ExportFormat.SQL, out))
                .as("这一档在 UI 里选不到（见第一条），递进来也照样拒 —— 两道都算数")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void binaryAndTextSidesGuardEachOther() {
        StringWriter out = new StringWriter();
        assertThatThrownBy(() -> ResultExport.writeRows(COLUMNS, ROWS, ExportFormat.XLSX, out))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is binary; use writeRowsXlsx");
        assertThatThrownBy(() -> ResultExport.writeRowsXlsx(COLUMNS, ROWS,
                ExportFormat.CSV, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is textual; use writeRows");
    }

    @Test
    void xlsxBranchProducesARealWorkbookStream() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long written = ResultExport.writeRowsXlsx(COLUMNS, ROWS, ExportFormat.XLSX, bytes);
        assertThat(written).isEqualTo(2);
        assertThat(bytes.toByteArray())
                .as("xlsx 是 zip：头两字节必须是 PK，否则写出的是一个打不开的文件")
                .startsWith('P', 'K');
    }

    @Test
    void rowCountMatchesTheRowsHandedIn() throws IOException {
        assertThat(ResultExport.writeRows(COLUMNS, ROWS, ExportFormat.CSV, new StringWriter()))
                .as("返回的行数 = 状态栏那句「已导出 N 行」里的 N，导 2 行说 2 行")
                .isEqualTo(2);
        assertThat(ResultExport.writeRows(COLUMNS, List.of(), ExportFormat.JSON,
                new StringWriter()))
                .isEqualTo(0);
    }

    private static String textOf(ExportFormat format) throws IOException {
        StringWriter out = new StringWriter();
        ResultExport.writeRows(COLUMNS, ROWS, format, out);
        return out.toString();
    }
}
