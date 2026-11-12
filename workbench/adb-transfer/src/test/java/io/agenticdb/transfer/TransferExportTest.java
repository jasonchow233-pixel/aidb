package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ P6-2：查询结果导出的门禁。
 *
 * <p>与整表导出的区别在于「导出的是用户那条 SQL 的结果」：
 * 绑定参数要生效、JOIN / 排序 / 过滤原样保留、行数不受 UI 单页上限影响。
 */
class TransferExportTest {

    @Test
    void exportSqlStreamsTheQueryResultWithBoundParameters() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();

            long written = TableExporter.exportSql(session,
                    "SELECT id, name FROM t WHERE id > ? ORDER BY id",
                    List.of(2), ExportOptions.of(ExportFormat.CSV), out);

            assertThat(written).isEqualTo(3);
            String[] lines = out.toString().split("\\R");
            assertThat(lines[0]).isEqualTo("id,name");
            assertThat(lines).hasSize(4);
            assertThat(lines[1]).isEqualTo("3,c");
            assertThat(lines[3]).isEqualTo("5,e");
        }
    }

    @Test
    void exportSqlWritesJsonAndHonoursTheRowLimit() throws Exception {
        try (JdbcSession session = seeded()) {
            StringWriter out = new StringWriter();

            long written = TableExporter.exportSql(session, "SELECT id, name FROM t ORDER BY id",
                    List.of(), ExportOptions.of(ExportFormat.JSON).withMaxRows(2), out);

            assertThat(written).isEqualTo(2);
            String[] lines = out.toString().split("\\R");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).isEqualTo("{\"id\":1,\"name\":\"a\"}");
            assertThat(lines[1]).isEqualTo("{\"id\":2,\"name\":\"b\"}");
        }
    }

    @Test
    void exportSqlRefusesInsertFormatBecauseThereIsNoTargetTable() throws Exception {
        try (JdbcSession session = seeded()) {
            assertThatThrownBy(() -> TableExporter.exportSql(session, "SELECT * FROM t",
                    List.of(), ExportOptions.of(ExportFormat.SQL), new StringWriter()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CSV");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sqlCases")
    void sqlBoundariesFollowSuccessfulRowsAndReplayOnSqlite(String scenario, int count,
            Set<Integer> badIds, long maxRows, List<Integer> expectedIds, int errors)
            throws Exception {
        try (JdbcSession source = seeded()) {
            source.update("DELETE FROM t WHERE id > ?", List.of(count));
            JdbcSession streaming = JdbcSession.wrap(withBadValues(source.connection(), badIds), "export");
            List<List<Object>> rows = memoryRows(count, badIds);
            ExportOptions options = ExportOptions.of(ExportFormat.SQL)
                    .withContinueOnError(true).withMaxRows(maxRows);
            for (boolean jdbc : List.of(false, true)) {
                StringWriter out = new StringWriter();
                ExportResult result = jdbc
                        ? TableExporter.exportWithResult(streaming, "t", List.of("id", "name"),
                                options, out, false)
                        : TableExporter.exportRowsWithResult("t", List.of("id", "name"), rows,
                                options, out, false);
                assertThat(result.rows()).as("%s JDBC=%s rows", scenario, jdbc)
                        .isEqualTo(expectedIds.size());
                assertThat(result.errors()).as("%s JDBC=%s errors", scenario, jdbc).isEqualTo(errors);
                assertThat(result.hasErrors()).isEqualTo(errors > 0);
                assertSqlReplays(out.toString(), expectedIds);

                StringWriter legacy = new StringWriter();
                long written = jdbc
                        ? TableExporter.export(streaming, "t", List.of("id", "name"), options, legacy)
                        : TableExporter.exportRows("t", List.of("id", "name"), rows, options, legacy);
                assertThat(written).isEqualTo(result.rows());
                assertThat(legacy.toString()).isEqualTo(out.toString());
            }
        }
    }

    private static Stream<Arguments> sqlCases() {
        return Stream.of(
                Arguments.of("empty", 0, Set.of(), 0L, List.of(), 0),
                Arguments.of("all bad", 3, Set.of(1, 2, 3), 0L, List.of(), 3),
                Arguments.of("first bad", 3, Set.of(1), 0L, List.of(2, 3), 1),
                Arguments.of("last bad", 3, Set.of(3), 0L, List.of(1, 2), 1),
                Arguments.of("middle bad", 3, Set.of(2), 0L, List.of(1, 3), 1),
                Arguments.of("only success", 3, Set.of(1, 3), 0L, List.of(2), 2),
                Arguments.of("single", 1, Set.of(), 0L, List.of(1), 0),
                Arguments.of("limit truncates", 3, Set.of(), 1L, List.of(1), 0),
                Arguments.of("limit counts successes", 5, Set.of(1, 3), 2L, List.of(2, 4), 2),
                Arguments.of("bad row beyond limit", 3, Set.of(3), 2L, List.of(1, 2), 0),
                Arguments.of("limit exceeds successes", 3, Set.of(3), 5L, List.of(1, 2), 1),
                Arguments.of("negative limit is unlimited", 3, Set.of(), -1L, List.of(1, 2, 3), 0));
    }

    private static void assertSqlReplays(String sql, List<Integer> expectedIds) throws Exception {
        if (expectedIds.isEmpty()) {
            assertThat(sql).isEmpty();
        } else {
            String tuples = expectedIds.stream()
                    .map(id -> "  (" + id + ", '" + name(id) + "')")
                    .collect(java.util.stream.Collectors.joining(",\n"));
            assertThat(sql).isEqualTo("-- 导出自 AIDB\nINSERT INTO \"t\" (\"id\", \"name\") VALUES\n"
                    + tuples + ";\n");
        }
        try (JdbcSession replay = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            replay.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
            if (!sql.isEmpty()) {
                try (var statement = replay.connection().createStatement()) {
                    statement.execute(sql);
                }
            }
            var restored = replay.query("SELECT id, name FROM t ORDER BY id", 10).rows();
            assertThat(restored).extracting(row -> row.get("id"))
                    .containsExactlyElementsOf(expectedIds);
            assertThat(restored).extracting(row -> row.get("name"))
                    .containsExactlyElementsOf(expectedIds.stream().map(TransferExportTest::name).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sqlQuotesIdentifiersAndLiteralsInBothPaths(boolean jdbc) throws Exception {
        String table = "odd\"table";
        List<String> columns = List.of("select", "a\"b");
        List<List<Object>> rows = List.of(List.of(7, "O'Brien"), Arrays.asList(8, null));
        String ddl = "CREATE TABLE \"odd\"\"table\" (\"select\" INTEGER, \"a\"\"b\" TEXT)";
        try (JdbcSession source = JdbcSession.open(ConnectionSpec.sqliteMemory());
             JdbcSession replay = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            source.execute(ddl);
            replay.execute(ddl);
            source.update("INSERT INTO \"odd\"\"table\" VALUES (?, ?)", rows.getFirst());
            source.update("INSERT INTO \"odd\"\"table\" VALUES (?, ?)", rows.getLast());
            StringWriter out = new StringWriter();
            ExportOptions options = ExportOptions.of(ExportFormat.SQL).withRecordDelimiter("\r\n")
                    .withHeader(false).withAppend(true);
            ExportResult result = jdbc
                    ? TableExporter.exportWithResult(source, table, columns, options, out, true)
                    : TableExporter.exportRowsWithResult(table, columns, rows, options, out, true);
            assertThat(result.rows()).isEqualTo(2);
            assertThat(result.errors()).isZero();
            assertThat(out.toString()).isEqualTo("-- 导出自 AIDB\r\n"
                    + "INSERT INTO \"odd\"\"table\" (\"select\", \"a\"\"b\") VALUES\r\n"
                    + "  (7, 'O''Brien'),\r\n  (8, NULL);\r\n");
            try (var statement = replay.connection().createStatement()) {
                statement.execute(out.toString());
            }
            String select = "SELECT * FROM \"odd\"\"table\" ORDER BY \"select\"";
            assertThat(replay.query(select, 10).rows()).isEqualTo(source.query(select, 10).rows());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sqlFailFastDoesNotEmitAPreambleForTheFirstBadRow(boolean jdbc) throws Exception {
        try (JdbcSession source = seeded()) {
            JdbcSession streaming = JdbcSession.wrap(withBadValues(source.connection(), Set.of(1)), "export");
            StringWriter out = new StringWriter();
            ExportOptions options = ExportOptions.of(ExportFormat.SQL);
            assertThatThrownBy(() -> {
                if (jdbc) {
                    TableExporter.exportWithResult(streaming, "t", List.of("id", "name"), options, out, false);
                } else {
                    TableExporter.exportRowsWithResult("t", List.of("id", "name"),
                            memoryRows(3, Set.of(1)), options, out, false);
                }
            }).isInstanceOf(IllegalStateException.class).hasMessage("bad export value");
            assertThat(out.toString()).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ExportFormat.class, names = {"CSV", "TSV"})
    void sqliteStreamingUsesDefaultCrlfAndHonoursExplicitLf(ExportFormat format) throws Exception {
        try (JdbcSession session = seeded()) {
            ExportOptions defaults = ExportOptions.of(format).withMaxRows(2);
            String delimiter = String.valueOf(format.delimiter());
            for (ExportOptions options : List.of(defaults, defaults.withRecordDelimiter("\n"))) {
                String rd = options.recordDelimiter();
                StringWriter out = new StringWriter();
                ExportResult result = TableExporter.exportWithResult(session, "t", List.of("id", "name"),
                        options, out, false);
                assertThat(result.rows()).isEqualTo(2);
                assertThat(result.errors()).isZero();
                assertThat(out.toString()).isEqualTo("id" + delimiter + "name" + rd
                        + "1" + delimiter + "a" + rd + "2" + delimiter + "b" + rd);
                StringWriter appended = new StringWriter();
                TableExporter.exportWithResult(session, "t", List.of("id", "name"),
                        options.withAppend(true), appended, true);
                assertThat(appended.toString()).isEqualTo("1" + delimiter + "a" + rd
                        + "2" + delimiter + "b" + rd);
            }
            assertThat(defaults.recordDelimiter()).isEqualTo("\r\n");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ioFailuresAtEverySqlWriteAndFlushAreNeverSkipped(boolean jdbc) throws Exception {
        try (JdbcSession source = seeded()) {
            source.update("DELETE FROM t WHERE id > ?", List.of(3));
            JdbcSession streaming = JdbcSession.wrap(withBadValues(source.connection(), Set.of(1)), "export");
            List<List<Object>> rows = memoryRows(3, Set.of(1));
            ExportOptions options = ExportOptions.of(ExportFormat.SQL).withContinueOnError(true);
            StringWriter complete = new StringWriter();
            TableExporter.exportRows("t", List.of("id", "name"), rows, options, complete);
            for (int offset = 0; offset <= complete.toString().length(); offset++) {
                IOException failure = new IOException("output failed at " + offset);
                Writer out = failingWriter(offset, failure);
                assertThatThrownBy(() -> {
                    if (jdbc) {
                        TableExporter.exportWithResult(streaming, "t", List.of("id", "name"), options, out, false);
                    } else {
                        TableExporter.exportRowsWithResult("t", List.of("id", "name"), rows, options, out, false);
                    }
                }).isSameAs(failure);
            }
        }
    }

    private static Writer failingWriter(int allowedCharacters, IOException failure) {
        return new Writer() {
            private int remaining = allowedCharacters;

            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                if (length > remaining) {
                    throw failure;
                }
                remaining -= length;
            }

            @Override
            public void flush() throws IOException {
                throw failure;
            }

            @Override
            public void close() {
            }
        };
    }

    private static List<List<Object>> memoryRows(int count, Set<Integer> badIds) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(id -> List.<Object>of(id, badIds.contains(id) ? explodingValue() : name(id)))
                .toList();
    }

    private static String name(int id) {
        return String.valueOf((char) ('a' + id - 1));
    }

    private static Object explodingValue() {
        return new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("bad export value");
            }
        };
    }

    // 仍由 SQLite 执行查询、移动游标和关闭资源，只替换指定单元格以触发渲染失败。
    private static Connection withBadValues(Connection connection, Set<Integer> badIds) {
        ClassLoader loader = TransferExportTest.class.getClassLoader();
        return (Connection) Proxy.newProxyInstance(loader, new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object value = invoke(connection, method, args);
                    if (method.getName().equals("prepareStatement") && value instanceof PreparedStatement statement) {
                        return Proxy.newProxyInstance(loader, new Class<?>[]{PreparedStatement.class},
                                (statementProxy, statementMethod, statementArgs) -> {
                                    Object result = invoke(statement, statementMethod, statementArgs);
                                    if (statementMethod.getName().equals("executeQuery") && result instanceof ResultSet rs) {
                                        return Proxy.newProxyInstance(loader, new Class<?>[]{ResultSet.class},
                                                (rsProxy, rsMethod, rsArgs) -> {
                                                    Object cell = invoke(rs, rsMethod, rsArgs);
                                                    if (rsMethod.getName().equals("getObject")
                                                            && Integer.valueOf(2).equals(rsArgs[0])
                                                            && badIds.contains(rs.getInt(1))) {
                                                        return explodingValue();
                                                    }
                                                    return cell;
                                                });
                                    }
                                    return result;
                                });
                    }
                    return value;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)");
        session.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");
        return session;
    }
}
