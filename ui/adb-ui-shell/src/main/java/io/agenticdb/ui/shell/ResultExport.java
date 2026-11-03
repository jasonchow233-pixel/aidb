package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.TableExporter;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询结果的导出接线层 —— R57-d 把「只导 CSV」换成 {@code adb-transfer} 那台七格式机器。
 *
 * <h2>格式挂在系统保存对话框上，不再自己搭一层选择器</h2>
 * {@link FileChooser} 的扩展名档就是原生对话框自带的「格式」下拉：选 {@code JSON (*.json)}
 * 出来的就是 JSON。再摆一个自绘的格式下拉，等于在同一件事上给两个入口，
 * 而且两边都会各自记住一个默认值（这类「同一个决定两个地方能改」正是本仓库反复记账的形状）。
 * 于是格式与文件名后缀由<b>同一次</b>对话框交互给出，规则收在这一个类里。
 *
 * <h2>SQL（{@code INSERT}）这一档这里没有，而且不是漏了</h2>
 * {@link TableExporter#exportSql} 明写拒绝查询结果走 SQL 格式：结果集<b>没有目标表名</b>，
 * {@code INSERT INTO} 的那一行写不出来，硬写会产出一个「只有值元组、不能执行」的文件 ——
 * 那比不给这一档更坏。要 {@code INSERT} 请用导出向导（那边有真表名，那一档是真的能用）。
 *
 * <h2>为什么「本页 / 选中」交原始值而不再交 {@code String.valueOf}</h2>
 * 旧路径把网格里显示成字符串的每格再 {@code String.valueOf} 一遍写 CSV —— 对 CSV 看不出差别，
 * 换成 JSON 就会把 {@code 42} 写成 {@code "42"}、把 NULL 写成 {@code ""}。
 * 现在三个范围都交<b>JDBC 原始值</b>，与「全部结果」（重跑 SQL 流式写出）同一条口径：
 * 同一次导出，选哪个范围都该是同一套类型规则。
 *
 * <h2>BOM 只给 CSV / TSV</h2>
 * Excel 打开无 BOM 的 UTF-8 会把中文拆错，所以分隔符格式前面补一枚 {@code U+FEFF}。
 * JSON / XML / HTML 不加：那三家各有自己的编码声明，多出来的 BOM 会让严格的解析器报错。
 */
final class ResultExport {

    /** 查询结果可导出的格式；顺序 = 保存对话框里那一列，CSV 打头（默认档）。 */
    private static final List<ExportFormat> FORMATS = List.of(
            ExportFormat.CSV, ExportFormat.TSV, ExportFormat.JSON,
            ExportFormat.XLSX, ExportFormat.XML, ExportFormat.HTML);

    private ResultExport() {
    }

    static List<ExportFormat> formats() {
        return FORMATS;
    }

    /** 保存对话框的扩展名档：标签写成「CSV (*.csv)」，选哪档就导哪种。 */
    static List<FileChooser.ExtensionFilter> filters() {
        List<FileChooser.ExtensionFilter> out = new ArrayList<>();
        for (ExportFormat format : FORMATS) {
            String pattern = "*" + format.extension();
            out.add(new FileChooser.ExtensionFilter(format.label() + " (" + pattern + ")", pattern));
        }
        return out;
    }

    /**
     * 选中的那一档 → 格式。按扩展名匹配（不是按标签 —— 标签是给人看的，会跟着语言变）。
     *
     * <p>认不出来时<b>不猜</b>：抛出来让人看见，比悄悄导成 CSV 然后用户以为导的是 JSON 好。
     */
    static ExportFormat formatOf(FileChooser.ExtensionFilter filter) {
        if (filter == null || filter.getExtensions().isEmpty()) {
            throw new IllegalArgumentException("No export format selected");
        }
        String pattern = filter.getExtensions().get(0);
        String extension = pattern.substring(pattern.lastIndexOf('.') >= 0
                ? pattern.lastIndexOf('.') : pattern.length());
        for (ExportFormat candidate : ExportFormat.values()) {
            if (candidate.extension().equalsIgnoreCase(extension)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported export format: " + pattern);
    }

    /**
     * 文件名跟着格式走：已知的导出后缀换掉（规则在 {@link ExportWizard#withExtension}，
     * 两边共用一份），<b>对不上任何一档的后缀就补上</b>这一档的后缀 —— 保存对话框里选了
     * {@code JSON (*.json)} 却落出 {@code report.txt} 或没有后缀的文件，双击什么也打不开，
     * 而打开的人只会以为是导出坏了。
     *
     * <p>名字承诺 {@code .txt}、内容其实是 JSON，比文件名多一段后缀更坏 —— 所以这一条
     * <b>不保留</b>用户自己写的陌生后缀。
     */
    static String withExtension(String baseName, ExportFormat format) {
        String swapped = ExportWizard.withExtension(baseName, format);
        if (swapped == null || swapped.isBlank()) {
            return swapped;
        }
        String lower = swapped.toLowerCase(java.util.Locale.ROOT);
        for (ExportFormat known : ExportFormat.values()) {
            if (lower.endsWith(known.extension())) {
                return swapped;
            }
        }
        return swapped + format.extension();
    }

    /** {@link #withExtension} 的文件版：换完后缀再落回 {@link java.nio.file.Path}。 */
    static java.nio.file.Path withExtension(java.nio.file.Path target, ExportFormat format) {
        return java.nio.file.Paths.get(withExtension(target.toString(), format));
    }

    /**
     * 导出<b>已经在内存里</b>的行（「本页」「选中行」，以及不可分页结果的「这些行」）。
     *
     * <p>表名交 {@code null}：查询结果没有目标表，SQL 格式因此会在
     * {@link TableExporter#exportRows} 那里被拒 —— 那是上面写明的边界，不是这一层的疏忽。
     */
    static long writeRows(List<String> columns, List<List<Object>> rows,
                          ExportFormat format, Writer out) throws IOException {
        if (format.binary()) {
            throw new IllegalArgumentException(format + " is binary; use writeRowsXlsx");
        }
        if (format.hasHeader()) {
            out.write('\uFEFF');
        }
        return TableExporter.exportRows(null, columns, rows, ExportOptions.of(format), out);
    }

    /** {@link #writeRows} 的 xlsx 分支。 */
    static long writeRowsXlsx(List<String> columns, List<List<Object>> rows,
                              ExportFormat format, OutputStream out) throws IOException {
        if (!format.binary()) {
            throw new IllegalArgumentException(format + " is textual; use writeRows");
        }
        return TableExporter.exportRowsXlsx(columns, rows, ExportOptions.of(format), out);
    }

    /**
     * 导出<b>全部结果</b>：重跑原始 SQL 流式写出，不受 UI 单页上限限制。
     *
     * <p>BOM 在 SQL 侧补（界面这一层写不进 {@link TableExporter} 的流式循环里），
     * 与 {@link #writeRows} 同一条规则：只有 CSV / TSV 要。
     */
    static long writeQuery(DbSession session, String sql, List<Object> params,
                           ExportFormat format, Writer out) throws SQLException, IOException {
        if (format.binary()) {
            throw new IllegalArgumentException(format + " is binary; use writeQueryXlsx");
        }
        if (format.hasHeader()) {
            out.write('\uFEFF');
        }
        return TableExporter.exportSql(session, sql, params, ExportOptions.of(format), out);
    }

    /** {@link #writeQuery} 的 xlsx 分支。 */
    static long writeQueryXlsx(DbSession session, String sql, List<Object> params,
                               OutputStream out) throws SQLException, IOException {
        return TableExporter.exportXlsxSql(session, sql, params,
                ExportOptions.of(ExportFormat.XLSX), out);
    }
}
