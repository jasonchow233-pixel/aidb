package io.agenticdb.transfer;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ P6-1 导入类型转换的门禁。
 *
 * <p>为什么需要它：以前导入把每个字段都按字符串绑定。SQLite 是弱类型库，
 * 把 {@code "abc"} 写进 INTEGER 列<b>不会报错</b> —— 坏数据当时全绿，
 * 等用户几个月后查询才发现。现在先按目标列类型转换，
 * 转不了的行在绑定前就进报告；同时 {@code "1,234"} / {@code yes} / {@code 2026/3/4}
 * 这类常见写法要能正确落库，而不是一律当成字符串。
 */
class ImportTypeCoercionTest {

    private static final List<ColumnDef> COLUMNS = List.of(
            column("id", ColumnType.NUMBER),
            column("amount", ColumnType.NUMBER),
            column("active", ColumnType.BOOLEAN),
            column("born", ColumnType.DATE),
            column("created", ColumnType.TIMESTAMP),
            column("code", ColumnType.TEXT));

    private static ColumnDef column(String name, ColumnType type) {
        return new ColumnDef(name, type, true, false, null, null, null,
                null, null, null, false);
    }

    @Test
    void coercesNumbersBooleansAndDatesBeforeBinding() throws Exception {
        try (JdbcSession session = seeded()) {
            String csv = "id,amount,active,born,created,code\n"
                    + "1,\"1,234.5\",yes,2026/3/4,2026-03-04T09:05:06,00123\n";

            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "typed",
                    COLUMNS, ImportOptions.of(ImportFormat.CSV), new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(report.skipped()).isZero();
            assertThat(report.hasErrors()).isFalse();
            // 文本列不能被「顺手数字化」：00123 仍是 00123。
            assertThat(valueOf(session, "SELECT code FROM typed")).isEqualTo("00123");
            assertThat(valueOf(session, "SELECT amount FROM typed")).isEqualTo("1234.5");
            assertThat(valueOf(session, "SELECT active FROM typed")).isEqualTo("1");
            assertThat(valueOf(session, "SELECT born FROM typed")).isNotNull();
            assertThat(valueOf(session, "SELECT created FROM typed")).isNotNull();
        }
    }

    @Test
    void invalidNumbersBecomeBadRowsWithoutLosingTheGoodOnes() throws Exception {
        try (JdbcSession session = seeded()) {
            String csv = "id,amount\n1,10\n2,abc\n3,\"2,000\"\n";

            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "typed",
                    COLUMNS, ImportOptions.of(ImportFormat.CSV), new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.errors().getFirst())
                    .contains("第 2 行", "列 amount", "数字");
            assertThat(countRows(session, "typed")).isEqualTo(2);
            assertThat(valueOf(session, "SELECT amount FROM typed WHERE id = 3"))
                    .isEqualTo("2000");
        }
    }

    @Test
    void invalidDatesAreReportedWithTheColumnName() throws Exception {
        try (JdbcSession session = seeded()) {
            String csv = "id,born\n1,2026-03-04\n2,2026-13-40\n";

            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "typed",
                    COLUMNS, ImportOptions.of(ImportFormat.CSV), new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.errors().getFirst()).contains("列 born", "日期");
        }
    }

    @Test
    void emptyValuesInTypedColumnsBecomeNull() throws Exception {
        try (JdbcSession session = seeded()) {
            String csv = "id,amount,active,born,created,code\n1,,,,,\n";

            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "typed",
                    COLUMNS, ImportOptions.of(ImportFormat.CSV), new StringReader(csv));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(valueOf(session, "SELECT amount IS NULL FROM typed")).isEqualTo("1");
            assertThat(valueOf(session, "SELECT born IS NULL FROM typed")).isEqualTo("1");
            // TEXT 列的空串保留为空串，不擅自改成 NULL。
            assertThat(valueOf(session, "SELECT code IS NULL FROM typed")).isEqualTo("0");
        }
    }

    @Test
    void textLikeColumnsKeepTheirValueVerbatim() {
        assertThat(TableImporter.coerce("00123", ColumnType.TEXT)).isEqualTo("00123");
        assertThat(TableImporter.coerce("  padded  ", ColumnType.TEXT))
                .isEqualTo("  padded  ");
        assertThat(TableImporter.coerce("{\"a\":1}", ColumnType.JSON))
                .isEqualTo("{\"a\":1}");
        assertThat(TableImporter.coerce("req-001", ColumnType.UUID))
                .isEqualTo("req-001");
    }

    @Test
    void booleansAcceptWordsAndNumbers() {
        assertThat(TableImporter.coerce("yes", ColumnType.BOOLEAN)).isEqualTo(Boolean.TRUE);
        assertThat(TableImporter.coerce("T", ColumnType.BOOLEAN)).isEqualTo(Boolean.TRUE);
        assertThat(TableImporter.coerce("0", ColumnType.BOOLEAN)).isEqualTo(Boolean.FALSE);
        assertThat(TableImporter.coerce("否", ColumnType.BOOLEAN)).isEqualTo(Boolean.FALSE);
        assertThatThrownBy(() -> TableImporter.coerce("maybe", ColumnType.BOOLEAN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maybe");
    }

    @Test
    void datesAndTimesAcceptCommonChineseFormats() {
        assertThat(TableImporter.coerce("2026/3/4", ColumnType.DATE).toString())
                .isEqualTo("2026-03-04");
        assertThat(TableImporter.coerce("9:05", ColumnType.TIME).toString())
                .isEqualTo("09:05:00");
        assertThat(TableImporter.coerce("2026-03-04T09:05", ColumnType.TIMESTAMP).toString())
                .isEqualTo("2026-03-04 09:05:00.0");
    }

    @Test
    void databaseErrorsAreStillReportedAfterCoercion() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory())) {
            try (Statement statement = session.connection().createStatement()) {
                statement.execute("CREATE TABLE u (id INTEGER PRIMARY KEY)");
            }
            List<ColumnDef> columns = List.of(column("id", ColumnType.NUMBER));
            // 第二行主键冲突 —— 类型都对，错在数据库约束；仍要按行报告。
            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "u",
                    columns, ImportOptions.of(ImportFormat.CSV),
                    new StringReader("id\n1\n1\n"));

            assertThat(report.inserted()).isEqualTo(1);
            assertThat(report.skipped()).isEqualTo(1);
            assertThat(report.errors().getFirst()).contains("第 2 行");
        }
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * ⭐ G2 的另一半：导入在<b>用户已经开着的事务</b>里跑坏行重试时，
     * 只许丢弃自己那一批，不许碰用户自己的写入。
     *
     * <p>坏行会走「丢弃这批 → 逐行重试」，那是导入唯一会调用
     * {@code rollback()/begin()} 的地方。不加「谁开的事务谁负责」的判断，
     * 这里就会把用户在数据页里改了半天的未提交内容一起抹掉 ——
     * 而用户根本不知道导入跑在同一根连接上。
     */
    @Test
    void badRowRetryInsideAUserTransactionDoesNotDiscardTheUsersOwnWrites() throws Exception {
        try (JdbcSession session = seeded()) {
            session.begin();
            try (Statement statement = session.connection().createStatement()) {
                statement.execute("INSERT INTO typed (id, amount) VALUES (100, 99)");
            }

            // 第 2 行主键冲突：类型全对、错在数据库约束 —— 只有这条路才会走到
            // 「丢弃这批 → 逐行重试」，转换错误在绑定前就被拦下了，碰不到事务。
            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "typed",
                    COLUMNS, ImportOptions.of(ImportFormat.CSV),
                    new StringReader("id,amount\n1,10\n1,20\n3,30\n"));

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(session.inTransaction())
                    .as("导入结束不能替用户提交或结束事务")
                    .isTrue();
            assertThat(valueOf(session, "SELECT amount FROM typed WHERE id = 100"))
                    .as("用户自己的未提交写入必须还在 —— 导入无权回滚别人的事务")
                    .isEqualTo("99");

            session.rollback();
            assertThat(countRows(session, "typed"))
                    .as("回滚由用户发起：导入的行与用户的行一起消失（说明它们真在同一个事务里）")
                    .isZero();
        }
    }

    @Test
    void importStillOwnsAndCommitsItsBatchWhenNoTransactionIsOpen() throws Exception {
        try (JdbcSession session = seeded()) {
            TableImporter.ImportReport report = TableImporter.importTypedRows(session, "typed",
                    COLUMNS, ImportOptions.of(ImportFormat.CSV),
                    new StringReader("id,amount\n1,10\n2,abc\n3,30\n"));

            assertThat(report.inserted()).isEqualTo(2);
            assertThat(session.inTransaction())
                    .as("没有用户事务时导入自己开、自己收尾，不许把连接留在事务里")
                    .isFalse();
            assertThat(countRows(session, "typed")).isEqualTo(2);
        }
    }

    private static JdbcSession seeded() throws Exception {
        JdbcSession session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        try (Statement statement = session.connection().createStatement()) {
            statement.execute("CREATE TABLE typed (id INTEGER PRIMARY KEY, amount NUMERIC,"
                    + " active BOOLEAN, born DATE, created TIMESTAMP, code TEXT)");
        }
        return session;
    }

    private static int countRows(JdbcSession session, String table) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String valueOf(JdbcSession session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
