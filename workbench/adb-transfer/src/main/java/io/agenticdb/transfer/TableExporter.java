package io.agenticdb.transfer;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.SqliteDialect;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表数据导出。
 *
 * <p>⭐ <b>流式</b>：一次只把一批行读进内存，写完就释放 ——
 * 导一千万行的表也不会 OOM。这是与「把整表读进 {@code ObservableList}」的根本区别。
 *
 * <p>⚠️ 导出走的是 {@link DbSession#connection()} 的原生 JDBC 路径，
 * 绕开了 {@code JdbcSession.query} 的单页上限（那是给 UI 网格用的）。
 * 所以这里**自己**负责行数上限与确认。
 *
 * <p>⭐ D-86：六个 {@code long} 入口的<b>签名一个都不动</b>（二十多处既有调用点靠它），
 * 它们只是 {@code *Result} 核心的薄包装。新增的 {@code *WithResult} 入口返回
 * {@link ExportResult}，用来回报 {@code continueOnError} 跳过的坏行数；
 * {@code append} 的「不重复写表头」由 {@code skipHeader} 参数落实（文件是否存在是
 * 运行时的文件层事实，不该塞进不可变的值对象）。
 *
 * <h2>七种格式对 D-86 选项的适用性（不允许「选了没生效」的假功能）</h2>
 * <ul>
 *   <li><b>CSV / TSV</b>：全部选项生效。recordDelimiter / textQualifier / quoteAll
 *       经 {@link CsvCodec} 落地；Formats 经 {@link ExportValueFormatter} 落地。</li>
 *   <li><b>JSON</b>：recordDelimiter、Formats 的 dateOrder / dateDelimiter /
 *       zeroPaddingDate / timeDelimiter / binaryEncoding 生效（日期、时间、二进制在
 *       JSON 里都是字符串）。<b>decimalSymbol 忽略</b>：{@code 1,5} 不是合法 JSON
 *       数字字面量，数字分支仍走 {@code String.valueOf}。textQualifier / quoteAll
 *       忽略：JSON 用 {@code "} 作字符串定界，是语法规定，没有「换引号」的余地。</li>
 *   <li><b>SQL</b>：recordDelimiter、Formats 的日期 / 时间 / 二进制生效。
 *       <b>decimalSymbol 忽略</b>：数字字面量必须是 {@code .}。textQualifier /
 *       quoteAll 忽略：字符串字面量恒用 {@code '} 且内部撇号翻倍，是 SQL 语法要求。</li>
 *   <li><b>XML / HTML</b>：recordDelimiter 作用于每个标签行尾、Formats 全部生效
 *       （值都是文本节点）。textQualifier / quoteAll 忽略：这两种格式用元素 / 转义，
 *       没有「引用字符」的概念。<b>append 忽略</b>：它们有文档级开闭标签，
 *       流式追加到 {@code </rows>} / {@code </html>} 之后只会产出非法文档，
 *       所以 UI 在这两种格式下把 append 开关置灰。</li>
 *   <li><b>XLSX</b>：continueOnError 生效，Formats 的 dateOrder / dateDelimiter /
 *       zeroPaddingDate / timeDelimiter / binaryEncoding 作用于字符串单元格。
 *       <b>decimalSymbol 忽略</b>：数值单元格按 {@code double} 写入，显示小数符号由
 *       Excel 的区域设置决定。<b>append / recordDelimiter / textQualifier /
 *       quoteAll 全部忽略</b>：xlsx 是 zip 二进制容器，没有文本记录结尾、
 *       没有引用字符，把两段 zip 拼一起也不是合法工作簿（UI 同样把 append 置灰）。</li>
 * </ul>
 */
public final class TableExporter {

    private TableExporter() {
    }

    // ───────────── 六个既有 public 入口：签名 / 返回 long 全部不变 ─────────────

    /**
     * 导出一张表。
     *
     * @param session 会话
     * @param table   表名（会被方言引用）
     * @param columns 要导出的列；为空表示全部列
     * @param options 导出参数
     * @param out     输出（调用方负责关闭）
     * @return 实际导出的行数
     */
    public static long export(DbSession session, String table, List<String> columns,
                              ExportOptions options, Writer out)
            throws SQLException, IOException {
        Dialect dialect = session.dialect();
        List<String> selected = columns == null || columns.isEmpty() ? null : columns;
        String sql = selectSql(dialect, table, selected);
        return streamResult(session, sql, List.of(), options, out, table, false).rows();
    }

    /**
     * ⭐ P6-2：导出任意查询结果（查询页的「导出全部结果」）。
     *
     * <p>SQL 格式会写 {@code INSERT INTO 表 ...}，查询结果没有目标表可写，
     * 因此明确拒绝，而不是产出不能执行的 SQL 文件。
     *
     * @param params 绑定参数（顺序与 SQL 中的 {@code ?} 一致）
     * @return 实际导出的行数
     */
    public static long exportSql(DbSession session, String sql, List<Object> params,
                                 ExportOptions options, Writer out)
            throws SQLException, IOException {
        if (options.format() == ExportFormat.SQL) {
            throw new IllegalArgumentException(
                    "查询结果不支持 SQL INSERT 格式导出，请改用 CSV / TSV / JSON");
        }
        return streamResult(session, sql, params == null ? List.of() : params,
                options, out, null, false).rows();
    }

    /** ⭐ P6-3：导出一张表为 xlsx（SXSSF 流式写出），二进制走 {@link OutputStream}。 */
    public static long exportXlsx(DbSession session, String table, List<String> columns,
                                  ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        Dialect dialect = session.dialect();
        List<String> selected = columns == null || columns.isEmpty() ? null : columns;
        return streamXlsxResult(session, selectSql(dialect, table, selected), List.of(),
                options, out).rows();
    }

    /** ⭐ P6-3：把任意查询结果导出为 xlsx（查询结果没有目标表，同 {@link #exportSql}）。 */
    public static long exportXlsxSql(DbSession session, String sql, List<Object> params,
                                     ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        return streamXlsxResult(session, sql, params == null ? List.of() : params,
                options, out).rows();
    }

    /**
     * 导出<b>已经在内存里</b>的行（「当前页」「选中行」两个范围）。
     *
     * @param table   表名：SQL 格式要用它拼 {@code INSERT INTO}；查询结果传 {@code null} 会被拒绝
     * @param columns 列名（顺序与每行的值一致）
     * @param rows    行数据（值为 JDBC 原始类型，{@code null} 表示 NULL）
     * @return 实际写出的行数
     */
    public static long exportRows(String table, List<String> columns, List<List<Object>> rows,
                                  ExportOptions options, Writer out) throws IOException {
        return exportRowsResult(table, columns, rows, options, out, false).rows();
    }

    /** xlsx 版本的内存行导出（「当前页」「选中行」）。 */
    public static long exportRowsXlsx(List<String> columns, List<List<Object>> rows,
                                      ExportOptions options, OutputStream out)
            throws IOException {
        return exportRowsXlsxResult(columns, rows, options, out).rows();
    }

    // ───────────── D-86：带 ExportResult 的入口（回报错误数 / 落实 append） ─────────────

    /**
     * 整表文本导出，返回完整结果。
     *
     * @param skipHeader 本次是追加到已有内容：CSV / TSV 不重复写表头
     */
    public static ExportResult exportWithResult(DbSession session, String table,
            List<String> columns, ExportOptions options, Writer out, boolean skipHeader)
            throws SQLException, IOException {
        Dialect dialect = session.dialect();
        List<String> selected = columns == null || columns.isEmpty() ? null : columns;
        return streamResult(session, selectSql(dialect, table, selected), List.of(),
                options, out, table, skipHeader);
    }

    /** 内存行文本导出，返回完整结果（{@code skipHeader} 同上）。 */
    public static ExportResult exportRowsWithResult(String table, List<String> columns,
            List<List<Object>> rows, ExportOptions options, Writer out, boolean skipHeader)
            throws IOException {
        return exportRowsResult(table, columns, rows, options, out, skipHeader);
    }

    /** xlsx 整表导出，返回完整结果。xlsx 忽略 append / skipHeader（见类注释）。 */
    public static ExportResult exportXlsxWithResult(DbSession session, String table,
            List<String> columns, ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        Dialect dialect = session.dialect();
        List<String> selected = columns == null || columns.isEmpty() ? null : columns;
        return streamXlsxResult(session, selectSql(dialect, table, selected), List.of(),
                options, out);
    }

    /** xlsx 内存行导出，返回完整结果。xlsx 忽略 append / skipHeader（见类注释）。 */
    public static ExportResult exportRowsXlsxWithResult(List<String> columns,
            List<List<Object>> rows, ExportOptions options, OutputStream out)
            throws IOException {
        return exportRowsXlsxResult(columns, rows, options, out);
    }

    // ─────────────────────────── 核心：文本 / 内存行 ───────────────────────────

    private static ExportResult exportRowsResult(String table, List<String> columns,
            List<List<Object>> rows, ExportOptions options, Writer out, boolean skipHeader)
            throws IOException {
        ExportFormat format = options.format();
        if (format.binary()) {
            throw new IllegalArgumentException(
                    "xlsx 是二进制格式，请改用 TableExporter.exportRowsXlsx");
        }
        if (format == ExportFormat.SQL && table == null) {
            throw new IllegalArgumentException(
                    "查询结果不支持 SQL INSERT 格式导出，请改用 CSV / TSV / JSON");
        }
        List<String> names = columns == null ? List.of() : List.copyOf(columns);
        List<List<Object>> data = rows == null ? List.of() : rows;
        ExportState state = new ExportState();
        // 无会话的 SQL 导出沿用双引号标识符约定。
        Dialect dialect = new SqliteDialect();

        writePreamble(out, options, names, table, skipHeader);
        for (int i = 0; i < data.size(); i++) {
            if (options.maxRows() > 0 && state.rows >= options.maxRows()) {
                break;
            }
            writeDataRow(options, out, data.get(i), names, dialect, table, state);
        }
        writeFooter(out, format, options, state);
        out.flush();
        return state.toResult();
    }

    /**
     * 流式写出结果集。
     *
     * @param table 表名：只有整表导出（SQL 格式）需要它；查询结果传 {@code null}
     */
    private static ExportResult streamResult(DbSession session, String sql, List<Object> params,
            ExportOptions options, Writer out, String table, boolean skipHeader)
            throws SQLException, IOException {
        if (options.format().binary()) {
            throw new IllegalArgumentException(
                    "xlsx 是二进制格式，请改用 TableExporter.exportXlsx / exportXlsxSql");
        }
        Dialect dialect = session.dialect();
        ExportState state = new ExportState();

        Connection connection = session.connection();
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                             ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(options.batchSize());
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> names = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    names.add(meta.getColumnLabel(i));
                }
                writePreamble(out, options, names, table, skipHeader);
                while ((options.maxRows() <= 0 || state.rows < options.maxRows()) && rs.next()) {
                    writeDataRow(options, out, readValues(rs, names.size()), names,
                            dialect, table, state);
                }
                writeFooter(out, options.format(), options, state);
            }
        }
        out.flush();
        return state.toResult();
    }

    /** 贯穿一次导出的可变计数（结果对象本身不可变，计数发生在写的过程中）。 */
    private static final class ExportState {
        long rows;
        int errors;

        ExportResult toResult() {
            return new ExportResult(rows, errors);
        }
    }

    /**
     * 写一行：先渲染到内存缓冲，成功才落到目标 Writer。
     *
     * <p>⭐ 缓冲是 {@code continueOnError} 能「干净跳过坏行」的前提：若边渲染边写，
     * 某行的第 3 个值炸了，前两个值已经落盘，文件里会留半行垃圾。现在整行要么全写、
     * 要么一个字节都不写。渲染阶段的 {@code RuntimeException}（典型是某个对象的
     * {@code toString()} 抛错）在开关打开时累计错误数并跳过，关闭时照旧抛出。
     */
    private static void writeDataRow(ExportOptions options, Writer out, List<Object> values,
            List<String> names, Dialect dialect, String table, ExportState state)
            throws IOException {
        StringWriter buffer = new StringWriter();
        try {
            List<Object> masked = DataMasker.maskRow(values, names,
                    options.masks(), options.maskSalt());
            renderDataRow(options, buffer, masked, names);
        } catch (RuntimeException e) {
            if (!options.continueOnError()) {
                throw e;
            }
            state.errors++;
            return;
        }
        // SQL 边界只依赖成功行；目标 Writer 的失败不属于可跳过的渲染错误。
        if (options.format() == ExportFormat.SQL) {
            if (state.rows == 0) {
                writeSqlPreamble(out, dialect, table, names, options.recordDelimiter());
            } else {
                out.write(",");
                out.write(options.recordDelimiter());
            }
        }
        out.write(buffer.toString());
        state.rows++;
    }

    /** 把一行渲染进缓冲（{@code Writer} 接口，StringWriter 实际不抛 IOException）。 */
    private static void renderDataRow(ExportOptions options, Writer out, List<Object> values,
            List<String> names) throws IOException {
        ExportFormat format = options.format();
        String rd = options.recordDelimiter();

        if (format == ExportFormat.JSON) {
            out.write("{");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.write(",");
                }
                out.write("\"");
                out.write(jsonEscape(names.get(i)));
                out.write("\":");
                Object value = values.get(i);
                if (value == null) {
                    out.write("null");
                } else if (value instanceof Number || value instanceof Boolean) {
                    // decimalSymbol 对 JSON 数字字面量忽略：'1,5' 不是合法 JSON 数字。
                    out.write(String.valueOf(value));
                } else {
                    out.write("\"");
                    out.write(jsonEscape(ExportValueFormatter.formatText(value, options)));
                    out.write("\"");
                }
            }
            out.write("}");
            out.write(rd);
            return;
        }

        if (format == ExportFormat.SQL) {
            out.write("  (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.write(", ");
                }
                out.write(sqlLiteral(values.get(i), options));
            }
            out.write(")");
            return;
        }

        if (format == ExportFormat.XML) {
            out.write("<row>");
            for (int i = 0; i < values.size(); i++) {
                out.write("<c name=\"");
                out.write(xmlEscape(names.get(i)));
                out.write("\">");
                Object value = values.get(i);
                if (value != null) {
                    out.write(xmlEscape(ExportValueFormatter.formatText(value, options)));
                }
                out.write("</c>");
            }
            out.write("</row>");
            out.write(rd);
            return;
        }

        if (format == ExportFormat.HTML) {
            out.write("<tr>");
            for (Object value : values) {
                out.write("<td>");
                if (value != null) {
                    out.write(xmlEscape(ExportValueFormatter.formatText(value, options)));
                }
                out.write("</td>");
            }
            out.write("</tr>");
            out.write(rd);
            return;
        }

        // CSV / TSV
        List<String> texts = new ArrayList<>(values.size());
        for (Object value : values) {
            texts.add(value == null ? null : ExportValueFormatter.formatText(value, options));
        }
        CsvCodec.writeRow(out, texts, format.delimiter(), options.textQualifier(),
                options.quoteAll(), rd);
    }

    private static void writePreamble(Writer out, ExportOptions options, List<String> names,
            String table, boolean skipHeader) throws IOException {
        ExportFormat format = options.format();
        String rd = options.recordDelimiter();
        if (format.hasHeader()) {
            // 表头与数据行走同一个引号口径 —— 不一致会让消费方把表头当数据解析。
            // append（skipHeader）时表头已在上一次导出写过，本次不重复。
            if (options.includeHeader() && !skipHeader) {
                CsvCodec.writeRow(out, names, format.delimiter(), options.textQualifier(),
                        options.quoteAll(), rd);
            }
            return;
        }
        if (format == ExportFormat.XML) {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            out.write(rd);
            out.write("<rows>");
            out.write(rd);
            return;
        }
        if (format == ExportFormat.HTML) {
            out.write("<!DOCTYPE html>");
            out.write(rd);
            out.write("<html>");
            out.write(rd);
            out.write("<head>");
            out.write(rd);
            out.write("<meta charset=\"utf-8\">");
            out.write(rd);
            // D-44：不再硬编码中文“查询结果”，随导出上下文动态生成标题。
            // 当有表名时使用表名；否则留空或使用上下文中的可用信息。
            String title = table != null ? table : "";
            out.write("<title>" + xmlEscape(title) + "</title>");
            out.write(rd);
            out.write("</head>");
            out.write(rd);
            out.write("<body>");
            out.write(rd);
            out.write("<table>");
            out.write(rd);
            if (options.includeHeader()) {
                out.write("<thead><tr>");
                for (String name : names) {
                    out.write("<th>");
                    out.write(xmlEscape(name));
                    out.write("</th>");
                }
                out.write("</tr></thead>");
                out.write(rd);
            }
            out.write("<tbody>");
            out.write(rd);
        }
    }

    private static void writeFooter(Writer out, ExportFormat format, ExportOptions options,
            ExportState state) throws IOException {
        String rd = options.recordDelimiter();
        if (format == ExportFormat.SQL && state.rows > 0) {
            out.write(";");
            out.write(rd);
        } else if (format == ExportFormat.XML) {
            out.write("</rows>");
            out.write(rd);
        } else if (format == ExportFormat.HTML) {
            out.write("</tbody>");
            out.write(rd);
            out.write("</table>");
            out.write(rd);
            out.write("</body>");
            out.write(rd);
            out.write("</html>");
            out.write(rd);
        }
    }

    // ─────────────────────────── 核心：xlsx 二进制 ───────────────────────────

    /**
     * ⭐ P6-3：xlsx 的流式路径。
     *
     * <p>append / recordDelimiter / textQualifier / quoteAll 对 xlsx 全部忽略（见类注释）：
     * 它是 zip 容器，没有文本记录结尾，也没有引用字符。Formats 只作用于字符串单元格，
     * 数值单元格仍按 {@code double} 写（decimalSymbol 由 Excel 区域设置决定）。
     * continueOnError 在这里同样生效：格式化阶段炸掉的行整行跳过。
     */
    private static ExportResult streamXlsxResult(DbSession session, String sql,
            List<Object> params, ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        ExportState state = new ExportState();
        Connection connection = session.connection();
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                             ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(options.batchSize());
            for (int i = 0; i < params.size(); i++) {
                statement.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = statement.executeQuery();
                 XlsxStreamWriter xlsx = new XlsxStreamWriter(out)) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> names = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    names.add(meta.getColumnLabel(i));
                }
                if (options.includeHeader()) {
                    xlsx.header(names);
                }
                while (rs.next()) {
                    if (options.maxRows() > 0 && state.rows >= options.maxRows()) {
                        break;
                    }
                    List<Object> masked = DataMasker.maskRow(
                            readValues(rs, names.size()), names, options.masks(), options.maskSalt());
                    writeXlsxRow(xlsx, masked, options, state);
                }
            }
        }
        out.flush();
        return state.toResult();
    }

    private static ExportResult exportRowsXlsxResult(List<String> columns,
            List<List<Object>> rows, ExportOptions options, OutputStream out)
            throws IOException {
        List<String> names = columns == null ? List.of() : List.copyOf(columns);
        ExportState state = new ExportState();
        try (XlsxStreamWriter xlsx = new XlsxStreamWriter(out)) {
            if (options.includeHeader()) {
                xlsx.header(names);
            }
            for (List<Object> row : rows == null ? List.<List<Object>>of() : rows) {
                if (options.maxRows() > 0 && state.rows >= options.maxRows()) {
                    break;
                }
                List<Object> masked = DataMasker.maskRow(row, names,
                        options.masks(), options.maskSalt());
                writeXlsxRow(xlsx, masked, options, state);
            }
        }
        out.flush();
        return state.toResult();
    }

    /** 先格式化（可能抛）再交给 SXSSF，坏行不会留下半行单元格。 */
    private static void writeXlsxRow(XlsxStreamWriter xlsx, List<Object> masked,
            ExportOptions options, ExportState state) {
        List<Object> formatted;
        try {
            formatted = formatRowForXlsx(masked, options);
        } catch (RuntimeException e) {
            if (!options.continueOnError()) {
                throw e;
            }
            state.errors++;
            return;
        }
        xlsx.row(formatted);
        state.rows++;
    }

    /** 数值 / 布尔保留类型（数值单元格），其余走 Formats 文本渲染。 */
    private static List<Object> formatRowForXlsx(List<Object> row, ExportOptions options) {
        List<Object> out = new ArrayList<>(row.size());
        for (Object value : row) {
            if (value == null || value instanceof Number || value instanceof Boolean) {
                out.add(value);
            } else {
                out.add(ExportValueFormatter.formatText(value, options));
            }
        }
        return out;
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static String selectSql(Dialect dialect, String table, List<String> columns) {
        StringBuilder sql = new StringBuilder("SELECT ");
        if (columns == null) {
            sql.append('*');
        } else {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(dialect.quote(columns.get(i)));
            }
        }
        sql.append(" FROM ").append(dialect.quote(table));
        return sql.toString();
    }

    private static void writeSqlPreamble(Writer out, Dialect dialect, String table,
            List<String> names, String rd) throws IOException {
        out.write("-- 导出自 AIDB");
        out.write(rd);
        out.write("INSERT INTO " + dialect.quote(table) + " (");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.write(", ");
            }
            out.write(dialect.quote(names.get(i)));
        }
        out.write(") VALUES");
        out.write(rd);
    }

    /** 读一行的原始值（保留类型 —— JSON / SQL 要靠类型决定要不要加引号）。 */
    private static List<Object> readValues(ResultSet rs, int columnCount) throws SQLException {
        List<Object> values = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            values.add(rs.getObject(i));
        }
        return values;
    }

    /**
     * SQL 字面量（D-86）：数字 / 布尔不加引号；其余按 Formats 渲染后加单引号并翻倍撇号。
     * decimalSymbol 对数字字面量忽略（见类注释）。
     */
    static String sqlLiteral(Object value, ExportOptions options) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + ExportValueFormatter.formatText(value, options).replace("'", "''") + "'";
    }

    /**
     * SQL 字面量。保留旧签名 —— {@code TransferTest#exportQuotesSqlLiteralsSafely}
     * 直接调用它，不能改成别的形状。
     */
    static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    /** JSON 字符串转义。 */
    static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20) {
                        text.append(String.format("\\u%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        return text.toString();
    }

    /** XML / HTML 共用的文本转义（HTML 里也用同一套，够用且不会引号失衡）。 */
    static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> text.append("&amp;");
                case '<' -> text.append("&lt;");
                case '>' -> text.append("&gt;");
                case '"' -> text.append("&quot;");
                case '\'' -> text.append("&#39;");
                default -> text.append(c);
            }
        }
        return text.toString();
    }
}
