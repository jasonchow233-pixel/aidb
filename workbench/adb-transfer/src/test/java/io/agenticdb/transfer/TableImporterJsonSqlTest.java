package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ H4-a：JSON（NDJSON）与 SQL（INSERT）导入 —— 与导出侧同形往返。
 *
 * <p>语义拍板（台账 H4-a「映射与预览语义要先定」的那一笔）：
 * <ul>
 *   <li><b>表头内嵌</b>：JSON 的表头 = 首个对象的键（它同时是第一行数据 ——
 *       NDJSON 每行都是数据，导出侧没有表头行）；SQL 的表头 = 首条 INSERT 的列清单。
 *       表头行 / 跳过行是 CSV 的概念，这两种格式不适用（编码仍然适用 —— 它们也是文本文件）。
 *       入口处把选项归一，预览与执行走同一条语义。</li>
 *   <li><b>按名字对齐</b>：后续行 / 后续语句按表头名字对齐（SQL 大小写不敏感、JSON 按原文）；
 *       缺键落 NULL，多余键忽略。SQL 的目标表以参数为准，文件里 INSERT INTO 的表名不读。</li>
 *   <li><b>NULL 保真</b>：JSON 的 {@code null} 与 SQL 的 {@code NULL} 落库就是 NULL ——
 *       文本列也是。CSV 里 NULL 与空串不可区分（TransferRoundTripTest 钉过），
 *       这两种格式做得到；空串也原样保住，两者不再混。</li>
 *   <li><b>解析层坏行</b>：坏掉的行 / 语句跳过并计入坏行报告，不中断整场导入 ——
 *       与值类型坏行同一套口径（「能进多少进多少，坏的报告出来」）。
 *       注释（{@code --}）是 SQL 的空白，不算坏行。</li>
 * </ul>
 */
class TableImporterJsonSqlTest {

    @TempDir
    Path temp;

    // ─────────────────────────── 编解码器层 ───────────────────────────

    @Test
    void jsonValueKindsSurviveTheReader() throws Exception {
        Path file = temp.resolve("kinds.json");
        Files.writeString(file, """
                {"id":42,"score":-1.5,"flag":true,"gone":null,"name":"含\\"引号\\",逗号"}
                """, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        JsonCodec.read(file, StandardCharsets.UTF_8, rows::add, bad::add);

        assertThat(bad).isEmpty();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .as("表头 = 首个对象的键，按出现顺序")
                .containsExactly("id", "score", "flag", "gone", "name");
        assertThat(rows.get(1)).containsExactly("42", "-1.5", "true", null, "含\"引号\",逗号");

        assertThat(JsonCodec.preview(file, StandardCharsets.UTF_8, 1))
                .as("preview 的行数上限含表头行")
                .hasSize(1);
    }

    @Test
    void sqlLiteralKindsSurviveTheReader() throws Exception {
        Path file = temp.resolve("kinds.sql");
        Files.writeString(file, """
                INSERT INTO whatever ("id", "score", "flag", "gone", "name") VALUES
                  (42, -1.5, true, NULL, '含''引号'',逗号');
                """, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        SqlCodec.read(file, StandardCharsets.UTF_8, rows::add, bad::add);

        assertThat(bad).isEmpty();
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst()).containsExactly("id", "score", "flag", "gone", "name");
        assertThat(rows.get(1)).containsExactly("42", "-1.5", "true", null, "含'引号',逗号");

        assertThat(SqlCodec.preview(file, StandardCharsets.UTF_8, 1)).hasSize(1);
    }

    // ─────────────────────────── 往返（对导出侧契约） ───────────────────────────

    @Test
    void jsonRoundTripKeepsNullsThatCsvLoses() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("orders.json");
            try (Writer writer = new BufferedWriter(
                    Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                TableExporter.export(session, "orders", List.of(),
                        ExportOptions.of(ExportFormat.JSON), writer);
            }

            session.execute("CREATE TABLE orders_json (id INTEGER, name TEXT, note TEXT)");
            TableImporter.ImportReport report = TableImporter.importJson(session, "orders_json",
                    columns(), ImportOptions.of(ImportFormat.JSON), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(report.skipped()).isZero();
            List<Map<String, Object>> rows = session
                    .query("SELECT id, name, note FROM orders_json ORDER BY id", 10).rows();
            assertThat(rows.get(1))
                    .as("JSON 往返保 NULL —— CSV 做不到的那一档")
                    .containsEntry("name", "李四").containsEntry("note", null);
            assertThat(rows.get(0)).containsEntry("note", "含,逗号和\"引号\" & 符号");
            assertThat(rows.get(2)).as("空串也保住，与 NULL 不再混").containsEntry("note", "");
        }
    }

    @Test
    void sqlRoundTripKeepsQuotesAndNulls() throws Exception {
        try (JdbcSession session = seeded()) {
            Path file = temp.resolve("orders.sql");
            try (Writer writer = new BufferedWriter(
                    Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                TableExporter.export(session, "orders", List.of(),
                        ExportOptions.of(ExportFormat.SQL), writer);
            }

            session.execute("CREATE TABLE orders_sql (id INTEGER, name TEXT, note TEXT)");
            TableImporter.ImportReport report = TableImporter.importSql(session, "orders_sql",
                    columns(), ImportOptions.of(ImportFormat.SQL), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(report.skipped()).isZero();
            List<Map<String, Object>> rows = session
                    .query("SELECT id, name, note FROM orders_sql ORDER BY id", 10).rows();
            assertThat(rows.get(0)).containsEntry("name", "张三")
                    .containsEntry("note", "含,逗号和\"引号\" & 符号");
            assertThat(rows.get(1)).as("NULL 关键字落 NULL").containsEntry("note", null);
            assertThat(rows.get(2)).as("'' 落空串，与 NULL 区分").containsEntry("note", "");
        }
    }

    // ─────────────────────────── 对齐与多语句 ───────────────────────────

    @Test
    void jsonAlignsLaterObjectsByTheFirstObjectsKeys() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER, name TEXT, note TEXT)");
            Path file = temp.resolve("aligned.json");
            Files.writeString(file, KEY_ORDER_NDJSON, StandardCharsets.UTF_8);

            TableImporter.ImportReport report = TableImporter.importJson(session, "t",
                    columns(), ImportOptions.of(ImportFormat.JSON), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(report.skipped()).isZero();
            List<Map<String, Object>> rows = session
                    .query("SELECT id, name, note FROM t ORDER BY id", 10).rows();
            assertThat(rows.get(1))
                    .as("键序乱了也按名字对齐")
                    .containsEntry("name", "李四").containsEntry("note", "按名字对齐");
            assertThat(rows.get(2)).as("缺键落 NULL")
                    .containsEntry("name", null).containsEntry("note", null);
        }
    }

    @Test
    void sqlSecondStatementMapsByItsOwnColumnList() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER, name TEXT, note TEXT)");
            Path file = temp.resolve("two-inserts.sql");
            Files.writeString(file, """
                    INSERT INTO whatever ("id", "name", "note") VALUES (1, '张三', '齐');
                    INSERT INTO whatever ("note", "id") VALUES ('第二句自己的列序', 2);
                    """, StandardCharsets.UTF_8);

            TableImporter.ImportReport report = TableImporter.importSql(session, "t",
                    columns(), ImportOptions.of(ImportFormat.SQL), file);

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(report.skipped()).isZero();
            List<Map<String, Object>> rows = session
                    .query("SELECT id, name, note FROM t ORDER BY id", 10).rows();
            assertThat(rows.get(0)).containsEntry("name", "张三").containsEntry("note", "齐");
            assertThat(rows.get(1))
                    .as("第二句的列清单说了算；缺的 name 落 NULL；文件里的表名不读")
                    .containsEntry("id", 2).containsEntry("name", null)
                    .containsEntry("note", "第二句自己的列序");
        }
    }

    // ─────────────────────────── 解析层坏行 ───────────────────────────

    @Test
    void jsonParseErrorIsSkippedAndReported() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER, name TEXT, note TEXT)");
            Path file = temp.resolve("broken.json");
            Files.writeString(file, """
                    {"id":1,"name":"张三","note":"表头"}
                    {"id":2,"name":"李四","note":"好的"}
                    {"id":三,"name":"缺引号"}
                    {"id":4,"name":"王五","note":"收尾"}
                    """, StandardCharsets.UTF_8);

            TableImporter.ImportReport report = TableImporter.importJson(session, "t",
                    columns(), ImportOptions.of(ImportFormat.JSON), file);

            assertThat(report.inserted()).isEqualTo(3);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.errors()).anySatisfy(error -> {
                assertThat(error).contains("第 3 行").contains("JSON 解析失败");
            });
        }
    }

    @Test
    void sqlNonInsertStatementIsSkippedAndReported() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            session.execute("CREATE TABLE t (id INTEGER, name TEXT, note TEXT)");
            Path file = temp.resolve("noisy.sql");
            Files.writeString(file, """
                    -- 表结构说明：注释是空白，不算坏行
                    INSERT INTO whatever ("id", "name") VALUES (1, '张三');
                    SELECT * FROM whatever;
                    INSERT INTO whatever ("id", "name") VALUES (2, '李四');
                    """, StandardCharsets.UTF_8);

            TableImporter.ImportReport report = TableImporter.importSql(session, "t",
                    columns(), ImportOptions.of(ImportFormat.SQL), file);

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.errors()).anySatisfy(error ->
                    assertThat(error).contains("不是 INSERT"));
        }
    }

    // ─────────────────────────── 表头选项归一 ───────────────────────────

    @Test
    void jsonHeaderIsImplicitNotFromTheSpinner() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            Path file = temp.resolve("implicit.json");
            Files.writeString(file, KEY_ORDER_NDJSON, StandardCharsets.UTF_8);

            session.execute("CREATE TABLE t1 (id INTEGER, name TEXT, note TEXT)");
            TableImporter.ImportReport spun = TableImporter.importJson(session, "t1",
                    columns(), ImportOptions.of(ImportFormat.JSON).withHeaderRow(5), file);
            assertThat(spun.inserted())
                    .as("表头行拧到 5 也不该把前四个对象跳掉 —— 键内嵌于格式")
                    .isEqualTo(3);

            session.execute("CREATE TABLE t2 (id INTEGER, name TEXT, note TEXT)");
            TableImporter.ImportReport headerless = TableImporter.importJson(session, "t2",
                    columns(), ImportOptions.of(ImportFormat.JSON).withHeaderRow(0), file);
            assertThat(headerless.inserted())
                    .as("拧成「无表头」也不该把键行当成数据")
                    .isEqualTo(3);
            assertThat(session.query("SELECT COUNT(*) AS c FROM t2 WHERE name = 'id'", 1)
                    .rows().getFirst().get("c")).as("键行不进表").isEqualTo(0);
        }
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 2 号行备注是 NULL、3 号行备注是空串 —— 往返门禁靠这两行分出 NULL 与空串。 */
    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("CREATE TABLE orders (id INTEGER, name TEXT, note TEXT)");
        session.execute("INSERT INTO orders VALUES "
                + "(1,'张三','含,逗号和\"引号\" & 符号'),"
                + "(2,'李四',NULL),"
                + "(3,'王五','')");
        return session;
    }

    private static final String KEY_ORDER_NDJSON = """
            {"id":1,"name":"张三","note":"首行定表头"}
            {"note":"按名字对齐","name":"李四","id":2}
            {"id":3}
            """;

    private static List<ColumnDef> columns() {
        return List.of(column("id", ColumnType.NUMBER), column("name", ColumnType.TEXT),
                column("note", ColumnType.TEXT));
    }

    private static ColumnDef column(String name, ColumnType type) {
        return new ColumnDef(name, type, true, false, null, null, null,
                null, null, null, false);
    }
}
