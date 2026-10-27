package io.agenticdb.transfer;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;

import java.io.IOException;
import java.io.OutputStream;
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
 */
public final class TableExporter {

    private TableExporter() {
    }

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
        return stream(session, sql, List.of(), options, out, table);
    }

    /**
     * ⭐ P6-2：导出任意查询结果（查询页的「导出全部结果」）。
     *
     * <p>与 {@link #export} 的差别：不生成 {@code SELECT * FROM 表}，
     * 而是原样执行用户那条 SQL（带绑定参数），所以结果集的范围、排序、
     * 多表 JOIN 都与查询页一致；行数不受 UI 单页上限影响。
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
        return stream(session, sql, params == null ? List.of() : params, options, out, null);
    }

    /**
     * ⭐ P6-3：导出一张表为 xlsx（SXSSF 流式写出）。
     *
     * <p>xlsx 是二进制格式，所以单独走 {@link OutputStream}，
     * 不能复用 {@code Writer} 的 CSV/JSON/SQL 路径。
     */
    public static long exportXlsx(DbSession session, String table, List<String> columns,
                                  ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        Dialect dialect = session.dialect();
        List<String> selected = columns == null || columns.isEmpty() ? null : columns;
        return streamXlsx(session, selectSql(dialect, table, selected), List.of(), options, out);
    }

    /** ⭐ P6-3：把任意查询结果导出为 xlsx（查询结果没有目标表，同 {@link #exportSql}）。 */
    public static long exportXlsxSql(DbSession session, String sql, List<Object> params,
                                     ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        return streamXlsx(session, sql, params == null ? List.of() : params, options, out);
    }

    /**
     * 流式写出结果集。
     *
     * @param table 表名：只有整表导出（SQL 格式）需要它；查询结果传 {@code null}
     */
    private static long stream(DbSession session, String sql, List<Object> params,
                               ExportOptions options, Writer out, String table)
            throws SQLException, IOException {
        if (options.format().binary()) {
            throw new IllegalArgumentException(
                    "xlsx 是二进制格式，请改用 TableExporter.exportXlsx / exportXlsxSql");
        }
        Dialect dialect = session.dialect();
        long written = 0;

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
                if (options.includeHeader() && options.format().hasHeader()) {
                    CsvCodec.writeRow(out, names, options.format().delimiter());
                }
                if (options.format() == ExportFormat.SQL && table != null) {
                    writeSqlPreamble(out, dialect, table, names);
                }
                // ⭐ 一次只把一批行读进内存，写完就释放。
                //    这里用「先看有没有下一行」的写法（而不是读完再问），
                //    因为 SQL 格式要靠「是不是最后一行」决定写 "," 还是 ";"。
                if (!rs.next()) {
                    out.flush();
                    return 0;
                }
                while (true) {
                    if (options.maxRows() > 0 && written >= options.maxRows()) {
                        break;
                    }
                    List<Object> values = readValues(rs, names.size());
                    boolean hasMore = rs.next();
                    writeRow(options, out, values, names, dialect, hasMore);
                    written++;
                    if (!hasMore) {
                        break;
                    }
                }
            }
        }
        out.flush();
        return written;
    }

    /**
     * ⭐ P6-3：xlsx 的流式路径。
     *
     * <p>与文本路径同一条 SQL / 同一批行读取方式，区别只在于每行交给
     * {@link XlsxStreamWriter}（SXSSF，滚动窗口 + 临时文件），而不是拼接字符串。
     */
    private static long streamXlsx(DbSession session, String sql, List<Object> params,
                                   ExportOptions options, OutputStream out)
            throws SQLException, IOException {
        long written = 0;
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
                    if (options.maxRows() > 0 && written >= options.maxRows()) {
                        break;
                    }
                    xlsx.row(readValues(rs, names.size()));
                    written++;
                }
            }
        }
        out.flush();
        return written;
    }

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
                                         List<String> names) throws IOException {
        out.write("-- 导出自 AIDB\n");
        out.write("INSERT INTO " + dialect.quote(table) + " (");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.write(", ");
            }
            out.write(dialect.quote(names.get(i)));
        }
        out.write(") VALUES\n");
    }

    private static void writeRow(ExportOptions options, Writer out, List<Object> values,
                                 List<String> names, Dialect dialect, boolean hasMore)
            throws IOException {
        ExportFormat format = options.format();

        if (format == ExportFormat.JSON) {
            out.write('{');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.write(',');
                }
                out.write('"');
                out.write(jsonEscape(names.get(i)));
                out.write("\":");
                Object value = values.get(i);
                if (value == null) {
                    out.write("null");
                } else if (value instanceof Number || value instanceof Boolean) {
                    out.write(String.valueOf(value));
                } else {
                    out.write('"');
                    out.write(jsonEscape(String.valueOf(value)));
                    out.write('"');
                }
            }
            out.write("}\n");
            return;
        }

        if (format == ExportFormat.SQL) {
            out.write("  (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.write(", ");
                }
                out.write(sqlLiteral(values.get(i)));
            }
            out.write(hasMore ? "),\n" : ");\n");
            return;
        }

        List<String> texts = new ArrayList<>(values.size());
        for (Object value : values) {
            texts.add(value == null ? null : String.valueOf(value));
        }
        CsvCodec.writeRow(out, texts, format.delimiter());
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
     * SQL 字面量。
     *
     * <p>数字与布尔不加引号（保持类型），其余加引号并把单引号翻倍。
     * NULL 写成 {@code NULL}（不加引号）。
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
}
