package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.ExternalDatabase;
import io.agenticdb.jdbc.ExternalUnreachable;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;


import java.io.StringReader;
import java.lang.reflect.Method;
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
@ExtendWith(MySqlIntegrationTest.EnvironmentFailureGuard.class)
class MySqlIntegrationTest {

    /**
     * 把「外部服务器把连接掐了」变成**跳过**，而不是失败。
     *
     * <p>实测这台 MySQL 会间歇性掐连接（TLS 握手卡住 / 服务端主动断），
     * 表现是 {@code No operations allowed after connection closed}。
     * 那是**环境问题**，不该让门禁变红 —— 但也不能悄悄算通过，
     * 所以记成**跳过**（门禁统计里看得见，不计入门禁）。
     *
     * <p>其它异常（SQL 语法错、断言错）**一律照常失败**。
     */
    static class EnvironmentFailureGuard implements InvocationInterceptor {
        @Override
        public void interceptTestMethod(Invocation<Void> invocation,
                                       ReflectiveInvocationContext<Method> invocationContext,
                                       ExtensionContext context) throws Throwable {
            try {
                invocation.proceed();
            } catch (java.sql.SQLException e) {
                if (ExternalUnreachable.isCommunications(e)) {
                    Assumptions.abort("外部数据库连接中断（服务端 / 网络问题）："
                            + e.getMessage() + " —— 跳过，不计入门禁");
                }
                throw e;
            }
        }
    }

    private static final String PREFIX = "adb_it_";

    /** ⭐ 环境变量读取**共用** adb-jdbc 测试产物（见 ExternalDatabase）。 */
    private static ExternalDatabase target;

    private DbSession session;
    private String table;
    /** 本次真的建出触发器了吗？（账号没有 TRIGGER 权限时为 false） */
    private boolean triggerCreated;

    /** 环境变量齐了吗？（与 adb-jdbc 的真库契约用同一份读取逻辑） */
    public static boolean isConfigured() {
        return ExternalDatabase.isMySqlConfigured();
    }

    @BeforeAll
    static void readEnvironment() {
        target = ExternalDatabase.mysql();
    }

    @BeforeEach
    void open() throws Exception {
        session = openWithRetry();
        table = PREFIX + "t" + System.nanoTime();
        exec("CREATE TABLE `" + table + "` ("
                + "id INT PRIMARY KEY, name VARCHAR(64) NOT NULL, score DOUBLE)");
    }

    @AfterEach
    void clean() throws Exception {
        if (session == null) {
            return;
        }
        // ⚠️ 清理要**尽力清干净**：视图、临时表都要删 ——
        //    之前的失败跑在服务器上留过孤儿对象。
        //
        // ⭐ 关键：清理要用**独立的新连接** ——
        //    被服务端掐断的那条连接已经没法执行 DROP 了，
        //    用它清理就等于「什么都没删」，孤儿对象会越攒越多
        //    （实测真在这个服务器上攒了 8 张）。
        try (DbSession cleaner = JdbcSession.open(target.spec())) {
            for (String suffix : new String[]{"", "_v"}) {
                drop(cleaner, "DROP TABLE IF EXISTS `" + table + suffix + "`");
                drop(cleaner, "DROP VIEW IF EXISTS `" + table + suffix + "`");
            }
            // 触发器**只删我们真的建出来的** —— 账号没有 TRIGGER 权限时，
            // 无条件 DROP TRIGGER 会刷一屏「没有 SUPER 权限」的噪音。
            if (triggerCreated) {
                drop(cleaner, "DROP TRIGGER IF EXISTS `" + table + "_trg`");
            }
        } catch (Exception e) {
            System.err.println("[mysql-it] 清理用的新连接也建不起来：" + e.getMessage());
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
        assertThat(session.dialect().jdbcUrl(target.host(), target.port(), target.database()))
                .startsWith("jdbc:mysql://" + target.host() + ":" + target.port() + "/")
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
        triggerCreated = false;
        try {
            exec("CREATE TRIGGER `" + table + "_trg` AFTER INSERT ON `" + table
                    + "` FOR EACH ROW SET @x = 1");
            triggerCreated = true;
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
    void tableMetaCarriesAllEightNavicatColumns() throws Exception {
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
        assertThat(meta.sizeText())
                .as("数据长度要按 B/KB/MB/GB 展示，且只保留 1 位小数")
                .matches("\\d+(\\.\\d)? (B|KB|MB|GB)");
        assertThat(meta.createdAt())
                .as("创建时间要有")
                .isNotBlank();
        // ⭐ 本轮补的三列。update_time 在 InnoDB 上允许为 NULL —— 不硬断言；
        //    排序规则一定有（除非用户显式指定了非法 collation，那建表就会失败）。
        assertThat(meta.collation())
                .as("排序规则要有（information_schema.table_collation）")
                .isNotBlank();
        assertThat(meta.commentText())
                .as("注释为空时也要给出占位符，不能是 null / 空白")
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

    // ─────────────────────────── 表注释（B5-S4）───────────────────────────

    @Test
    void tableCommentIsWrittenAndReadBack() throws Exception {
        // ⭐ B5-S4：表注释以前**只写不读** —— 填了以后再也看不见。
        //    这条真库契约断言：写进去 → **读得回来** → 改掉 → 读回来是新值 → 清掉 → 读回来是空。
        String temp = PREFIX + "cm" + System.nanoTime();
        try {
            StructureSqlBuilder.createTable(session, temp,
                    List.of(new io.agenticdb.model.ColumnSpec("id", "INT", false, null,
                            null, false, true)),
                    "第一版注释", List.of()).statements()
                    .forEach(statement -> {
                        try {
                            exec(statement);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });

            SchemaService schemaService = new SchemaService();
            assertThat(schemaService.structure(session, temp).comment())
                    .as("表注释要能读回来")
                    .isEqualTo("第一版注释");

            exec(StructureSqlBuilder.setTableComment(session,
                    schemaService.structure(session, temp), "第二版注释").sql());
            assertThat(schemaService.structure(session, temp).comment())
                    .isEqualTo("第二版注释");

            exec(StructureSqlBuilder.setTableComment(session,
                    schemaService.structure(session, temp), "").sql());
            assertThat(schemaService.structure(session, temp).comment())
                    .as("清空注释后要读回空串")
                    .isEmpty();
        } finally {
            exec("DROP TABLE IF EXISTS `" + temp + "`");
        }
    }

    // ─────────────────────────── 调度事件（O1）───────────────────────────

    @Test
    void eventShowsUpInTheObjectListAndItsDefinitionIsReadable() throws Exception {
        // ⭐ O1：以前**连枚举位都没有**（MySQL EVENT 完全没建模）——
        //    现在：事件进对象树，定义体也读得回来。
        String event = PREFIX + "ev" + System.nanoTime();
        try {
            exec("CREATE EVENT `" + event + "` ON SCHEDULE EVERY 1 HOUR "
                    + "DO SELECT 1");
            SchemaService schemaService = new SchemaService();

            assertThat(schemaService.listObjects(session).stream()
                    .filter(object -> object.kind() == SchemaService.ObjectKind.EVENT)
                    .map(SchemaService.SchemaObject::name))
                    .as("事件要出现在对象列表里")
                    .contains(event);

            assertThat(schemaService.eventDefinition(session, event))
                    .as("事件定义体要能读回来")
                    .contains(event);
        } finally {
            exec("DROP EVENT IF EXISTS `" + event + "`");
        }
    }

    // ─────────────────────────── 函数定义体（O2）───────────────────────────

    @Test
    void functionDefinitionIsReadBack() throws Exception {
        // ⭐ O2：以前对象树里只有函数**名字** —— 点开看不到函数体。
        //    这条真库契约断言：建函数 → **读回定义体** → 内容对得上。
        String function = PREFIX + "fn" + System.nanoTime();
        try {
            exec("CREATE FUNCTION `" + function + "`() RETURNS INT "
                    + "DETERMINISTIC READS SQL DATA RETURN 42");
            SchemaService schemaService = new SchemaService();
            String definition = schemaService.routineDefinition(session, function, false);
            assertThat(definition)
                    .as("函数定义体要能读回来")
                    .contains(function)
                    .contains("42");
        } finally {
            exec("DROP FUNCTION IF EXISTS `" + function + "`");
        }
    }

    // ─────────────────────────── 自增列 ───────────────────────────

    @Test
    void theAutoIncrementColumnIsDetected() throws Exception {
        String auto = PREFIX + "auto";
        exec("CREATE TABLE `" + auto + "` ("
                + "id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(20) NOT NULL)");
        try {
            SchemaService schemaService = new SchemaService();
            SchemaService.TableSchema schema = schemaService.describe(session, auto);
            assertThat(schema.autoIncrementColumn())
                    .as("MySQL 的 AUTO_INCREMENT 列要能认出来 —— "
                            + "新增行时这一列留空，数据库自动赋值")
                    .isEqualTo("id");
            assertThat(schema.autoIncrement("id")).isTrue();
            assertThat(schema.autoIncrement("name")).isFalse();
        } finally {
            exec("DROP TABLE IF EXISTS `" + auto + "`");
        }
    }

    @Test
    void aTableWithoutAutoIncrementHasNone() throws Exception {
        String plain = PREFIX + "plain";
        exec("CREATE TABLE `" + plain + "` (id INT PRIMARY KEY, name VARCHAR(20))");
        try {
            SchemaService schemaService = new SchemaService();
            SchemaService.TableSchema schema = schemaService.describe(session, plain);
            assertThat(schema.autoIncrementColumn())
                    .as("没有 AUTO_INCREMENT 的表不该瞎标")
                    .isNull();
        } finally {
            exec("DROP TABLE IF EXISTS `" + plain + "`");
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

    /**
     * 连上真库；**通信类**失败重试。
     *
     * <p>⚠️ 实测这台 MySQL 的 TLS 握手会**间歇性卡住**（6 次里挂 2 次）——
     * 那是服务端 / 网络的问题，不是我们代码的问题。
     * 但它会让门禁随机变红，所以这里对通信失败重试；
     * 其它异常（SQL 错、断言错）一律立刻失败。
     */
    private static DbSession openWithRetry() throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return JdbcSession.open(target.spec());
            } catch (java.sql.SQLException e) {
                if (!ExternalUnreachable.isCommunications(e)) {
                    throw e;
                }
                last = e;
                System.out.println("[mysql-it] 第 " + attempt
                        + " 次通信失败（服务端 / 网络问题），重试：" + e.getMessage());
                Thread.sleep(500L * attempt);
            }
        }
        // ⭐ 连了 5 次都不行 —— 外部服务器的问题，记成**跳过**（看得见，不计入门禁）。
        org.junit.jupiter.api.Assumptions.abort(
                "外部数据库暂时不可达（重试 5 次仍失败）：" + last.getMessage()
                        + " —— 跳过，不计入门禁");
        throw last;
    }


    /** 尽力执行一条清理语句；失败只报告，不影响其余清理。 */
    private void drop(DbSession cleaner, String sql) {
        try (Statement statement = cleaner.connection().createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            // ⚠️ 「没有 SUPER 权限」是**服务器侧**的已知限制，不是清理失败。
            //    它不该刷成一屏红色错误 —— 但也不能悄悄吞掉。
            if (message.contains("SUPER privilege")) {
                System.out.println("[mysql-it] 跳过（服务器不允许该操作）：" + sql);
                return;
            }
            System.err.println("[mysql-it] 清理语句失败（" + message + "）：" + sql);
        }
    }

    /**
     * 执行一条 SQL。
     *
     * <p>⚠️ 外部真库是**第三方依赖**：实测这台 MySQL 会间歇性把连接掐掉
     * （TLS 握手卡住 / 服务端主动断），表现出来是
     * {@code No operations allowed after connection closed}。
     * 那是**环境问题**，不是我们代码的问题 ——
     * 所以这里记成**跳过**（Assumption 失败）：门禁里看得见，但不计入门禁。
     */
    private void exec(String sql) throws Exception {
        try (Statement statement = session.connection().createStatement()) {
            statement.execute(sql);
        } catch (java.sql.SQLException e) {
            if (ExternalUnreachable.isCommunications(e)) {
                org.junit.jupiter.api.Assumptions.abort(
                        "外部数据库连接中断（服务端 / 网络问题）：" + e.getMessage()
                                + " —— 跳过，不计入门禁");
            }
            throw e;
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
