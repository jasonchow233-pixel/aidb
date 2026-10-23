package io.agenticdb.transfer;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;

import java.io.IOException;
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
        long written = 0;

        Connection connection = session.connection();
        try (PreparedStatement statement =
                     connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                             ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(options.batchSize());
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> names = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    names.add(meta.getColumnLabel(i));
                }
                if (options.includeHeader() && options.format().hasHeader()) {
                    CsvCodec.writeRow(out, names, options.format().delimiter());
                }
                if (options.format() == ExportFormat.SQL) {
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
