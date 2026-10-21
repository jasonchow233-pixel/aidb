package io.agenticdb.introspect;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.Danger;
import io.agenticdb.safety.DangerClassifier;
import io.agenticdb.transfer.ExportFormat;
import io.agenticdb.transfer.ExportOptions;
import io.agenticdb.transfer.ImportFormat;
import io.agenticdb.transfer.ImportOptions;
import io.agenticdb.transfer.TableExporter;
import io.agenticdb.transfer.TableImporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.StringReader;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ MySQL 真库联调。
 *
 * <p>凭据**只从环境变量读**，仓库里不存任何密码：
 * <pre>
 * ADB_TEST_MYSQL_HOST=…  ADB_TEST_MYSQL_PORT=3306
 * ADB_TEST_MYSQL_DB=…    ADB_TEST_MYSQL_USER=…  ADB_TEST_MYSQL_PASSWORD=…
 * </pre>
 * 没设这些变量时整个类跳过 —— CI 与本地都不受影响。
 *
 * <p>⚠️ 测试只建一张带随机后缀的临时表，跑完必删。
 */
@EnabledIf("io.agenticdb.introspect.MySqlIntegrationTest#isConfigured")
class MySqlIntegrationTest {

    private static final String PREFIX = "adb_it_";

    private static String host;
    private static int port;
    private static String database;
    private static String user;
    private static String password;

    private DbSession session;
    private String table;

    /** 环境变量齐了吗？ */
    public static boolean isConfigured() {
        return System.getenv("ADB_TEST_MYSQL_HOST") != null
                && !System.getenv("ADB_TEST_MYSQL_HOST").isBlank();
    }

    @BeforeAll
    static void readEnvironment() {
        host = System.getenv("ADB_TEST_MYSQL_HOST");
        port = Integer.parseInt(orDefault(System.getenv("ADB_TEST_MYSQL_PORT"), "3306"));
        database = orDefault(System.getenv("ADB_TEST_MYSQL_DB"), "");
        user = orDefault(System.getenv("ADB_TEST_MYSQL_USER"), "");
        password = orDefault(System.getenv("ADB_TEST_MYSQL_PASSWORD"), "");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeEach
    void open() throws Exception {
        session = JdbcSession.open(ConnectionSpec.mysql(host, port, database, user, password));
        table = PREFIX + "t" + System.nanoTime();
        exec("CREATE TABLE `" + table + "` ("
                + "id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, score DOUBLE)");
    }

    @AfterEach
    void clean() throws Exception {
        if (session == null) {
            return;
        }
        // ⚠️ 清理要**尽力清干净**：视图、触发器、临时表都要删 ——
        //    之前的失败跑在服务器上留过孤儿对象。
        //    每条语句**各自** try：一条失败（比如账号没有 DROP TRIGGER 权限）
        //    不能把后面的 DROP TABLE 一起跳过。
        for (String suffix : new String[]{"", "_v", "_trg"}) {
            drop("DROP TABLE IF EXISTS `" + table + suffix + "`");
            drop("DROP VIEW IF EXISTS `" + table + suffix + "`");
            drop("DROP TRIGGER IF EXISTS `" + table + suffix + "`");
        }
        session.close();
    }

    // ─────────────────────────── 方言行为 ───────────────────────────

    @Test
    void mysqlDialectBehavesAsExpected() {
        Dialect dialect = session.dialect();
        assertThat(dialect.kind()).isEqualTo(DialectKind.MYSQL);
        assertThat(dialect.quote("users")).isEqualTo("`users`");
        assertThat(dialect.defaultPort()).isEqualTo(3306);
        assertThat(dialect.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(dialect.pooled()).isTrue();
        assertThat(dialect.supportsColumnComment()).isTrue();
        assertThat(dialect.readsColumnsViaJdbcMetadata()).isTrue();
        assertThat(dialect.readsIndexesViaJdbcMetadata()).isTrue();
        assertThat(dialect.hasCatalog()).isTrue();
        assertThat(dialect.hasSchema()).isTrue();
        assertThat(dialect.rebuildsTableOnColumnChange())
                .as("MySQL 改列不用整表重建")
                .isFalse();
        assertThat(dialect.requiresDefaultForNotNullColumn()).isFalse();
    }

    @Test
    void mysqlAcceptsTheGeneratedUrl() {
        assertThat(session.dialect().jdbcUrl(host, port, database))
                .startsWith("jdbc:mysql://" + host + ":" + port + "/")
                .contains("serverTimezone=UTC");
    }

    // ─────────────────────────── 危险分级（真库语法） ───────────────────────────

    @Test
    void dangerClassificationCoversRealMysqlSyntax() {
        assertThat(DangerClassifier.classify("SELECT * FROM t")).isEqualTo(Danger.READ_ONLY);
        assertThat(DangerClassifier.classify("INSERT INTO t VALUES (1)")).isEqualTo(Danger.MUTATING);
        assertThat(DangerClassifier.classify("REPLACE INTO t VALUES (1)")).isEqualTo(Danger.MUTATING);
        assertThat(DangerClassifier.classify("UPDATE t SET a = 1")).isEqualTo(Danger.MUTATING);
        assertThat(DangerClassifier.classify("DELETE FROM t WHERE id = 1")).isEqualTo(Danger.MUTATING);
        assertThat(DangerClassifier.classify("DELETE FROM t")).isEqualTo(Danger.DESTRUCTIVE);
        assertThat(DangerClassifier.classify("ALTER TABLE t ADD c INT")).isEqualTo(Danger.DESTRUCTIVE);
        assertThat(DangerClassifier.classify("DROP TABLE t")).isEqualTo(Danger.DESTRUCTIVE);
        assertThat(DangerClassifier.classify("TRUNCATE TABLE t")).isEqualTo(Danger.DESTRUCTIVE);
        // MySQL 特有：这些以前一律判只读
        assertThat(DangerClassifier.classify("CALL some_proc()"))
                .as("CALL 能执行任意代码")
                .isEqualTo(Danger.ADMIN);
        assertThat(DangerClassifier.classify("SET autocommit = 0"))
                .as("SET 改会话状态")
                .isNotEqualTo(Danger.READ_ONLY);
        assertThat(DangerClassifier.classify("LOCK TABLES t WRITE"))
                .as("LOCK 不是只读")
                .isNotEqualTo(Danger.READ_ONLY);
        assertThat(DangerClassifier.classify("CREATE INDEX i ON t (id)"))
                .isEqualTo(Danger.DESTRUCTIVE);
    }

    // ─────────────────────────── 内省 ───────────────────────────

    @Test
    void listsTablesViewsAndTriggers() throws Exception {
        SchemaService schemaService = new SchemaService();
        exec("CREATE VIEW `" + table + "_v` AS SELECT id FROM `" + table + "`");
        // ⚠️ MySQL 5.7 开二进制日志时，建触发器要 SUPER 权限 —— 普通测试账号没有。
        //    这是**服务器侧的限制**，不是我们的 bug：建不出来就跳过触发器那半段，
        //    但视图那半段必须照样验。
        boolean triggerCreated = true;
        try {
            exec("CREATE TRIGGER `" + table + "_trg` AFTER INSERT ON `" + table
                    + "` FOR EACH ROW SET @x = 1");
        } catch (Exception e) {
            triggerCreated = false;
            System.out.println("[mysql-it] 服务器不允许建触发器（"
                    + e.getMessage() + "）—— 跳过触发器断言");
        }
        try {
            List<SchemaService.SchemaObject> objects = schemaService.listObjects(session);

            assertThat(objects).extracting(SchemaService.SchemaObject::name)
                    .contains(table, table + "_v");
            assertThat(objects.stream()
                    .filter(o -> o.name().equals(table + "_v"))
                    .map(SchemaService.SchemaObject::kind)
                    .findFirst().orElseThrow())
                    .isEqualTo(SchemaService.ObjectKind.VIEW);
            if (triggerCreated) {
                assertThat(objects).extracting(SchemaService.SchemaObject::name)
                        .contains(table + "_trg");
                assertThat(objects.stream()
                        .filter(o -> o.name().equals(table + "_trg"))
                        .map(SchemaService.SchemaObject::kind)
                        .findFirst().orElseThrow())
                        .as("触发器要能被列出来（走方言的 triggersListSql）")
                        .isEqualTo(SchemaService.ObjectKind.TRIGGER);
            }
        } finally {
            if (triggerCreated) {
                exec("DROP TRIGGER IF EXISTS `" + table + "_trg`");
            }
            exec("DROP VIEW IF EXISTS `" + table + "_v`");
        }
    }

    @Test
    void tableMetaCarriesRowCountSizeAndEngine() throws Exception {
        SchemaService schemaService = new SchemaService();
        exec("INSERT INTO `" + table + "` VALUES (1, 'a', 1.0), (2, 'b', 2.0)");

        List<TableMeta> metas = schemaService.listTableMeta(session);
        TableMeta meta = metas.stream()
                .filter(item -> item.name().equals(table))
                .findFirst().orElseThrow();

        assertThat(meta.rows())
                .as("行数要对")
                .isNotNull()
                .isGreaterThanOrEqualTo(2L);
        assertThat(meta.engine())
                .as("引擎要有（MySQL 的 information_schema 给）")
                .isNotBlank();
        assertThat(meta.sizeBytes())
                .as("数据长度要有")
                .isNotNull()
                .isPositive();
        assertThat(meta.createdAt())
                .as("创建时间要有")
                .isNotBlank();
    }

    // ─────────────────────────── 结构变更 ───────────────────────────

    @Test
    void generatedColumnDdlActuallyRuns() throws Exception {
        SchemaService schemaService = new SchemaService();
        TableStructure tableSchema = schemaService.structure(session, table);

        // ⭐ StructureSqlBuilder 是**计划器** —— 它只生成 SQL，UI 负责执行。
        //    这里要真的把计划跑下去，才算验证了「生成的 DDL 在真库上能用」。
        apply(StructureSqlBuilder.addColumn(session, tableSchema,
                new io.agenticdb.model.ColumnSpec("email", "VARCHAR(120)", true, null,
                        "用户邮箱", false, false)));
        assertThat(showColumns()).contains("email");
        assertThat(columnComment("email"))
                .as("MySQL 的注释写在列定义里 —— 加列就该带上")
                .isEqualTo("用户邮箱");

        // ⭐ 结构是**快照** —— 改完列要重新读一次（UI 也会这么做）。
        tableSchema = schemaService.structure(session, table);
        apply(StructureSqlBuilder.alterColumn(session, tableSchema, "email",
                new io.agenticdb.model.ColumnSpec("email", "VARCHAR(200)", false, null,
                        null, false, false)));
        assertThat(columnType("email")).isEqualToIgnoringCase("varchar(200)");
        assertThat(nullableOf("email")).isEqualTo("NO");

        tableSchema = schemaService.structure(session, table);
        apply(StructureSqlBuilder.renameColumn(session, tableSchema, "email", "mail"));
        assertThat(showColumns()).contains("mail").doesNotContain("email");

        tableSchema = schemaService.structure(session, table);
        apply(StructureSqlBuilder.dropColumn(session, tableSchema, "mail"));
        assertThat(showColumns()).doesNotContain("mail");
    }

    @Test
    void generatedCreateTableActuallyRuns() throws Exception {
        String temp = PREFIX + "c" + System.nanoTime();
        try {
            String sql = StructureSqlBuilder.createTableSql(session, temp,
                    List.of(new io.agenticdb.model.ColumnDef("id",
                            io.agenticdb.model.ColumnType.NUMBER, false, true, null, null,
                            null, null, null, null, false, "INT"),
                            new io.agenticdb.model.ColumnDef("label",
                            io.agenticdb.model.ColumnType.TEXT, true, false, null, null,
                            null, null, null, null, false, "VARCHAR(32)")),
                    List.of("id"), List.of(), List.of());
            exec(sql);
            assertThat(showColumns(temp)).contains("id", "label");
        } finally {
            exec("DROP TABLE IF EXISTS `" + temp + "`");
        }
    }

    // ─────────────────────────── 导入导出 ───────────────────────────

    @Test
    void exportThenImportRoundTrips() throws Exception {
        exec("INSERT INTO `" + table + "` VALUES (1, 'alpha', 1.5), (2, 'beta', 2.5)");

        SchemaService schemaService = new SchemaService();
        TableStructure tableSchema = schemaService.structure(session, table);
        List<String> columns = tableSchema.columns().stream()
                .map(io.agenticdb.model.ColumnDef::name).toList();

        StringWriter out = new StringWriter();
        long rows = TableExporter.export(session, table, columns,
                ExportOptions.of(ExportFormat.CSV), out);
        assertThat(rows).isEqualTo(2);
        assertThat(out.toString()).contains("id,name,score").contains("1,alpha,1.5");

        // 导入到一张新表
        String target = PREFIX + "i" + System.nanoTime();
        try {
            exec("CREATE TABLE `" + target + "` (`id` INT, `name` VARCHAR(64), `score` DOUBLE)");
            TableImporter.ImportReport report = TableImporter.importRows(session, target,
                    columns, ImportOptions.of(ImportFormat.CSV),
                    new StringReader(out.toString()));
            assertThat(report.inserted()).isEqualTo(2);
            assertThat(report.skipped()).isZero();
            assertThat(countRows(target)).isEqualTo(2);
        } finally {
            exec("DROP TABLE IF EXISTS `" + target + "`");
        }
    }

    @Test
    void exportAsJsonAndSqlWorks() throws Exception {
        exec("INSERT INTO `" + table + "` VALUES (1, 'alpha', 1.5)");

        SchemaService schemaService = new SchemaService();
        TableStructure tableSchema = schemaService.structure(session, table);
        List<String> columns = tableSchema.columns().stream()
                .map(io.agenticdb.model.ColumnDef::name).toList();

        StringWriter json = new StringWriter();
        TableExporter.export(session, table, columns, ExportOptions.of(ExportFormat.JSON), json);
        assertThat(json.toString()).contains("\"id\":1").contains("\"name\":\"alpha\"");

        StringWriter sql = new StringWriter();
        TableExporter.export(session, table, columns, ExportOptions.of(ExportFormat.SQL), sql);
        assertThat(sql.toString())
                .contains("INSERT INTO `" + table + "`")
                .contains("'alpha'");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 把结构变更计划真的执行下去。 */
    private void apply(StructurePlan plan) throws Exception {
        for (String sql : plan.statements()) {
            System.out.println("[mysql-it] SQL> " + sql);
            exec(sql);
        }
    }

    /** 尽力执行一条清理语句；失败只报告，不影响其余清理。 */
    private void drop(String sql) {
        try {
            exec(sql);
        } catch (Exception e) {
            System.err.println("[mysql-it] 清理语句失败（" + e.getMessage() + "）：" + sql);
        }
    }

    private void exec(String sql) throws Exception {
        try (Statement statement = session.connection().createStatement()) {
            statement.execute(sql);
        }
    }

    private long countRows(String name) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM `" + name + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> showColumns() throws Exception {
        return showColumns(table);
    }

    private List<String> showColumns(String name) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COLUMN_NAME FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = '"
                             + name + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private String columnType(String column) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = '"
                             + table + "' AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            String type = rs.getString(1);
            long length = rs.getLong(2);
            return rs.wasNull() ? type : type + "(" + length + ")";
        }
    }

    private String columnComment(String column) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COLUMN_COMMENT FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = '"
                             + table + "' AND COLUMN_NAME = '" + column + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private String nullableOf(String column) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT IS_NULLABLE FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = '"
                             + table + "' AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
