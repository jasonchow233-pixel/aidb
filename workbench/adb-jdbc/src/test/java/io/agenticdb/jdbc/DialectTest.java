package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A1 的门禁：方言行为抽象。
 *
 * <p>两件事：
 * <ol>
 *   <li>每个 {@link DialectKind} 都必须有自己的 {@link Dialect} 实现，
 *       而且实现的行为要与「这个库实际怎么写 SQL」一致；</li>
 *   <li><b>代码里不许再出现散落的方言分支</b> ——
 *       {@code dialect == DialectKind.XXX} 这种写法会让「加一种库要改五个文件」
 *       的老问题回来。</li>
 * </ol>
 */
class DialectTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(DialectKind.class)
    void everyKindHasItsOwnDialect(DialectKind kind) {
        Dialect dialect = kind.dialect();
        assertThat(dialect).as("%s 必须有方言实现", kind).isNotNull();
        assertThat(dialect.kind()).isEqualTo(kind);
        assertThat(dialect.displayName()).isNotBlank();
        assertThat(dialect.driverClassName()).isNotBlank();
    }

    @Test
    void dialectKindsAreDistinct() {
        assertThat(DialectKind.values()).hasSize(6);
        assertThat(java.util.Arrays.stream(DialectKind.values())
                .map(DialectKind::id).toList()).doesNotHaveDuplicates();
    }

    // ─────────────────────────── 标识符引用 ───────────────────────────

    @Test
    void eachFamilyUsesItsOwnIdentifierQuotes() {
        assertThat(DialectKind.MYSQL.dialect().quote("users")).isEqualTo("`users`");
        assertThat(DialectKind.MARIADB.dialect().quote("users"))
                .as("MariaDB 继承 MySQL 的反引号（同源，写法逐字一致）")
                .isEqualTo("`users`");
        assertThat(DialectKind.POSTGRESQL.dialect().quote("users")).isEqualTo("\"users\"");
        assertThat(DialectKind.SQLITE.dialect().quote("users")).isEqualTo("\"users\"");
        assertThat(DialectKind.ORACLE.dialect().quote("users"))
                .as("Oracle 与 PG 同用双引号（但大小写敏感：不加引号折成大写）")
                .isEqualTo("\"users\"");
        assertThat(DialectKind.SQLSERVER.dialect().quote("users"))
                .as("⭐ SQL Server 是方括号 —— 既不是反引号也不是双引号")
                .isEqualTo("[users]");
    }

    @Test
    void quotingEscapesTheQuoteCharacter() {
        assertThat(DialectKind.MYSQL.dialect().quote("a`b")).isEqualTo("`a``b`");
        assertThat(DialectKind.SQLITE.dialect().quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(DialectKind.SQLSERVER.dialect().quote("a]b"))
                .as("方括号里的右括号写两遍（T-SQL 的转义只有这一种）")
                .isEqualTo("[a]]b]");
    }

    @Test
    void quotingRejectsNull() {
        assertThatThrownBy(() -> DialectKind.SQLITE.dialect().quote(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * ⭐ D-68：目录里读回来的名字<b>裸写</b>进 SQL 能不能落回同一个对象，是每库一条折叠规则。
     *
     * <p>补全通道（SqlCompletions）以前只判「是不是纯 ASCII 形状」就裸插 ——
     * Oracle 把裸名折成大写，设计器加引号建的 {@code users} 原样存在目录里，
     * 插出去一执行就是 ORA-00942；PG 反向，折成小写，裸插 {@code Orders} 落不到 {@code "Orders"}。
     * MySQL / SQL Server / SQLite 大小写不敏感，这条对它们恒真 —— 规则必须在方言层，
     * 界面不写分支（A1 / DialectBranchAuditTest 同一口径）。
     */
    @Test
    void bareNamesResolvePerEachEnginesOwnFoldRule() {
        assertThat(DialectKind.ORACLE.dialect().bareResolvesTo("users"))
                .as("Oracle 裸名折大写：小写目录名不许裸写")
                .isFalse();
        assertThat(DialectKind.ORACLE.dialect().bareResolvesTo("USERS")).isTrue();
        assertThat(DialectKind.POSTGRESQL.dialect().bareResolvesTo("Orders"))
                .as("PG 裸名折小写：混合大小写的目录名不许裸写")
                .isFalse();
        assertThat(DialectKind.POSTGRESQL.dialect().bareResolvesTo("orders")).isTrue();
        assertThat(DialectKind.SQLITE.dialect().bareResolvesTo("Orders"))
                .as("SQLite / MySQL / SQL Server 大小写不敏感：一律能落回")
                .isTrue();
        assertThat(DialectKind.MYSQL.dialect().bareResolvesTo("Orders")).isTrue();
        assertThat(DialectKind.SQLSERVER.dialect().bareResolvesTo("Orders")).isTrue();
    }

    /**
     * 事务性 DDL 是方言能力，不是 UI 里的 switch。
     *
     * <p>MySQL / MariaDB / Oracle 的 DDL 会隐式提交；另外三家可加入用户事务。
     */
    @Test
    void transactionalDdlIsDeclaredByEachDialect() {
        assertThat(DialectKind.SQLITE.dialect().supportsTransactionalDdl()).isTrue();
        assertThat(DialectKind.POSTGRESQL.dialect().supportsTransactionalDdl()).isTrue();
        assertThat(DialectKind.SQLSERVER.dialect().supportsTransactionalDdl()).isTrue();
        assertThat(DialectKind.MYSQL.dialect().supportsTransactionalDdl()).isFalse();
        assertThat(DialectKind.MARIADB.dialect().supportsTransactionalDdl()).isFalse();
        assertThat(DialectKind.ORACLE.dialect().supportsTransactionalDdl()).isFalse();
    }

    @Test
    void transferCompatibilityDefaultsToSameEngine() {
        assertThat(DialectKind.SQLITE.dialect()
                .canTransferTo(DialectKind.SQLITE.dialect())).isTrue();
        assertThat(DialectKind.SQLITE.dialect()
                .canTransferTo(DialectKind.POSTGRESQL.dialect())).isFalse();
        assertThat(DialectKind.MYSQL.dialect()
                .canTransferTo(DialectKind.MARIADB.dialect()))
                .as("MySQL / MariaDB 共享实现不等于同一个引擎：传输仍按同引擎限制")
                .isFalse();
    }

    // ─────────────────────────── 连接 ───────────────────────────

    @Test
    void jdbcUrlsCarryTheRightPrefix() {
        assertThat(DialectKind.SQLITE.dialect().jdbcUrl("", 0, "/tmp/a.db"))
                .isEqualTo("jdbc:sqlite:/tmp/a.db");
        assertThat(DialectKind.POSTGRESQL.dialect().jdbcUrl("h", 5432, "db"))
                .as("PostgreSQL：带超时的 URL（没有超时会冻住界面）")
                .startsWith("jdbc:postgresql://h:5432/db?")
                .contains("connectTimeout=")
                .contains("socketTimeout=");
        assertThat(DialectKind.MYSQL.dialect().jdbcUrl("h", 3306, "db"))
                .as("MySQL：带超时的 URL（没有超时会冻住界面）")
                .startsWith("jdbc:mysql://h:3306/db?")
                .contains("connectTimeout=")
                .contains("socketTimeout=");
    }

    @Test
    void defaultPorts() {
        assertThat(DialectKind.SQLITE.dialect().defaultPort()).isZero();
        assertThat(DialectKind.POSTGRESQL.dialect().defaultPort()).isEqualTo(5432);
        assertThat(DialectKind.MYSQL.dialect().defaultPort()).isEqualTo(3306);
    }

    @Test
    void sqliteConnectsToAFileTheOthersToAHost() {
        assertThat(DialectKind.SQLITE.dialect().usesFilePath()).isTrue();
        assertThat(DialectKind.POSTGRESQL.dialect().usesFilePath()).isFalse();
        assertThat(DialectKind.MYSQL.dialect().usesFilePath()).isFalse();
    }

    // ─────────────────────────── 能力矩阵 ───────────────────────────

    @Test
    void sqliteHasNoColumnCommentAndNeedsPragma() {
        Dialect sqlite = DialectKind.SQLITE.dialect();
        assertThat(sqlite.supportsColumnComment())
                .as("SQLite 没有列注释 —— UI 必须据此收起注释控件")
                .isFalse();
        assertThat(sqlite.readsColumnsViaJdbcMetadata())
                .as("SQLite 的结果集元数据不带声明类型与默认值")
                .isFalse();
        assertThat(sqlite.readsIndexesViaJdbcMetadata())
                .as("SQLite 的 getIndexInfo 不可用")
                .isFalse();
        assertThat(sqlite.hasCatalog()).isFalse();
        assertThat(sqlite.hasSchema()).isFalse();
        assertThat(sqlite.rebuildsTableOnColumnChange())
                .as("SQLite 改列必须整表重建")
                .isTrue();
        assertThat(sqlite.requiresDefaultForNotNullColumn()).isTrue();
    }

    @Test
    void serverDatabasesUseJdbcMetadata() {
        for (DialectKind kind : new DialectKind[]{DialectKind.POSTGRESQL, DialectKind.MYSQL}) {
            Dialect dialect = kind.dialect();
            assertThat(dialect.supportsColumnComment()).isTrue();
            assertThat(dialect.readsColumnsViaJdbcMetadata()).isTrue();
            assertThat(dialect.readsIndexesViaJdbcMetadata()).isTrue();
            assertThat(dialect.hasCatalog()).isTrue();
            assertThat(dialect.hasSchema()).isTrue();
            assertThat(dialect.rebuildsTableOnColumnChange()).isFalse();
            assertThat(dialect.requiresDefaultForNotNullColumn()).isFalse();
        }
    }

    @Test
    void onlyMysqlOffersTablePhysicalOptions() {
        String sql = DialectKind.MYSQL.dialect().tableOptionsSql();
        assertThat(sql)
                .as("MySQL 的引擎 / 排序规则 / 行格式在 information_schema.tables 里")
                .isNotNull()
                .contains("information_schema.tables")
                .contains("engine")
                .contains("table_collation")
                .contains("auto_increment")
                .contains("row_format")
                .contains("table_name = ?")
                .contains("AND table_type = 'BASE TABLE'");

        assertThat(DialectKind.SQLITE.dialect().tableOptionsSql())
                .as("SQLite 没有表级引擎 / 排序规则 / 行格式，不能假装有")
                .isNull();
        assertThat(DialectKind.POSTGRESQL.dialect().tableOptionsSql())
                .as("PostgreSQL 的表级选项不在 information_schema.tables 里")
                .isNull();
    }

    // ─────────────────────────── 结构变更 SQL ───────────────────────────

    @Test
    void mysqlPutsTheCommentInsideTheColumnDefinition() {
        Dialect mysql = DialectKind.MYSQL.dialect();
        ColumnSpec column = new ColumnSpec("email", "TEXT", false, null, "用户邮箱", false, false);

        String definition = mysql.columnDefinition(column, true);
        assertThat(definition)
                .as("MySQL 的注释写在列定义里")
                .contains("COMMENT '用户邮箱'");

        List<String> statements = new ArrayList<>();
        mysql.appendColumnComment("users", column, statements);
        assertThat(statements)
                .as("MySQL 不需要额外的注释语句")
                .isEmpty();
    }

    @Test
    void postgresUsesAStatementForTheComment() {
        Dialect postgres = DialectKind.POSTGRESQL.dialect();
        ColumnSpec column = new ColumnSpec("email", "TEXT", false, null, "用户邮箱", false, false);

        assertThat(postgres.columnDefinition(column, true))
                .as("PostgreSQL 的注释不在列定义里")
                .doesNotContain("COMMENT");

        List<String> statements = new ArrayList<>();
        postgres.appendColumnComment("users", column, statements);
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst())
                .isEqualTo("COMMENT ON COLUMN \"users\".\"email\" IS '用户邮箱'");
    }

    @Test
    void postgresAlterColumnSplitsIntoThreeStatements() {
        Dialect postgres = DialectKind.POSTGRESQL.dialect();
        ColumnSpec next = new ColumnSpec("email", "TEXT", false, "'x'", null, false, false);

        List<String> statements =
                postgres.alterColumnStatements("users", "email", next, "VARCHAR(50)");

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).contains("TYPE TEXT");
        assertThat(statements.get(1)).contains("SET NOT NULL");
        assertThat(statements.get(2)).contains("SET DEFAULT 'x'");
    }

    @Test
    void mysqlAlterColumnIsOneModifyWithFullDefinition() {
        Dialect mysql = DialectKind.MYSQL.dialect();
        ColumnSpec next = new ColumnSpec("email", "TEXT", false, null, null, false, false);

        List<String> statements =
                mysql.alterColumnStatements("users", "email", next, "VARCHAR(50)");

        assertThat(statements)
                .as("MySQL 没有独立的 SET NOT NULL —— 一条 MODIFY 带完整定义")
                .hasSize(1);
        assertThat(statements.getFirst()).startsWith("ALTER TABLE `users` MODIFY COLUMN `email`");
    }

    @Test
    void sqliteRefusesInPlaceAlterColumn() {
        assertThatThrownBy(() -> DialectKind.SQLITE.dialect().alterColumnStatements(
                "users", "email", new ColumnSpec("email", "TEXT", true, null, null, false, false),
                "TEXT"))
                .as("SQLite 改列必须整表重建，不能就地改")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addAndDropColumnUseTheRightQuotes() {
        assertThat(DialectKind.MYSQL.dialect()
                .addColumnSql("users", new ColumnSpec("email", "TEXT", true, null, null, false, false)))
                .isEqualTo("ALTER TABLE `users` ADD COLUMN `email` TEXT");
        assertThat(DialectKind.SQLITE.dialect()
                .dropColumnSql("users", "email"))
                .isEqualTo("ALTER TABLE \"users\" DROP COLUMN \"email\"");
    }

    // ─────────────────────────── 执行计划 ───────────────────────────

    @Test
    void explainSqlIsDialectSpecific() {
        assertThat(DialectKind.SQLITE.dialect().explainSql("SELECT 1"))
                .isEqualTo("EXPLAIN QUERY PLAN SELECT 1");
        assertThat(DialectKind.POSTGRESQL.dialect().explainSql("SELECT 1"))
                .isEqualTo("EXPLAIN SELECT 1");
        assertThat(DialectKind.MYSQL.dialect().explainSql("SELECT 1"))
                .isEqualTo("EXPLAIN SELECT 1");
    }

    // ─────────────────────────── 内省 SQL ───────────────────────────

    @Test
    void sqliteReadsPragmaTheOthersUseJdbcMetadata() {
        assertThat(DialectKind.SQLITE.dialect().columnsSql("users"))
                .isEqualTo("PRAGMA table_info(\"users\")");
        assertThat(DialectKind.SQLITE.dialect().indexesSql("users"))
                .isEqualTo("PRAGMA index_list(\"users\")");
        assertThat(DialectKind.POSTGRESQL.dialect().columnsSql("users")).isNull();
        assertThat(DialectKind.MYSQL.dialect().indexesSql("users")).isNull();
    }

    @Test
    void onlyPostgresHasNoShowCreateTable() {
        assertThat(DialectKind.POSTGRESQL.dialect().readsDdlFromDatabase())
                .as("PostgreSQL 没有 SHOW CREATE TABLE —— DDL 只能由内省生成")
                .isFalse();
        assertThat(DialectKind.MYSQL.dialect().readsDdlFromDatabase()).isTrue();
        assertThat(DialectKind.SQLITE.dialect().readsDdlFromDatabase()).isTrue();
    }

    @Test
    void sqliteHasNoCatalogNorSchema() throws SQLException {
        try (Connection connection =
                     DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThat(DialectKind.SQLITE.dialect().currentCatalog(connection)).isNull();
            assertThat(DialectKind.SQLITE.dialect().currentSchema(connection)).isNull();
        }
    }

    // ─────────────────── 真库实测：动作码与注释钩子 ───────────────────

    /**
     * ⭐ R46 第一条真库发现（2026-11-02，PG 16.15 / pgjdbc 42.7.14 实测）：
     * PG 驱动返回的外键动作码与通用映射**不是一套**（0=CASCADE、3=NO ACTION）。
     *
     * <p>沿用通用映射不是「显示得不好看」，而是**双向写错**：真 CASCADE 读成
     * NO ACTION，默认（NO ACTION）读成 SET NULL —— 下一次改列把 {@code ON DELETE}
     * 静默改写成另一条动作，删父行的行为整个变了。SQLite 是同一个坑（已修），
     * 所以这条按方言分别钉死。
     */
    @Test
    void postgresAndSqliteShareTheirOwnForeignKeyActionCodes() {
        Dialect postgres = DialectKind.POSTGRESQL.dialect();
        Dialect sqlite = DialectKind.SQLITE.dialect();
        Dialect mysql = DialectKind.MYSQL.dialect();

        assertThat(postgres.foreignKeyActionName("0")).isEqualTo("CASCADE");
        assertThat(postgres.foreignKeyActionName("1")).isEqualTo("RESTRICT");
        assertThat(postgres.foreignKeyActionName("2")).isEqualTo("SET NULL");
        assertThat(postgres.foreignKeyActionName("3")).isEqualTo("NO ACTION");
        assertThat(postgres.foreignKeyActionName("4")).isEqualTo("SET DEFAULT");

        assertThat(sqlite.foreignKeyActionName("0")).as("SQLite 驱动与 PG 同码").isEqualTo("CASCADE");
        assertThat(sqlite.foreignKeyActionName("3")).isEqualTo("NO ACTION");

        assertThat(mysql.foreignKeyActionName("2"))
                .as("MySQL 驱动走通用映射：2 才是 CASCADE")
                .isEqualTo("CASCADE");
        assertThat(mysql.foreignKeyActionName("0")).isEqualTo("NO ACTION");
    }

    /**
     * ⭐ 声称支持表注释的方言，必须**真的**给得出读取查询。
     *
     * <p>这条钉的是一类编译器管不住的缺陷：一行 {@code /** ...} 忘了闭合，
     * 整段 {@code @Override} 方法被当成注释吃掉，接口默认（返回 null）悄悄生效，
     * 结构页的表注释就永远是空 —— 实测 MySQL 与 PG 两条方言都中过（2026-11-02）。
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(DialectKind.class)
    void commentCapableDialectsReturnARealQuery(DialectKind kind) {
        Dialect dialect = kind.dialect();
        if (!dialect.supportsTableComment()) {
            assertThat(dialect.tableCommentQuery("users"))
                    .as("%s 不支持表注释 → 读取查询必须是 null，界面才显示「—」", kind)
                    .isNull();
            return;
        }
        String sql = dialect.tableCommentQuery("users");
        assertThat(sql).as("%s 声称支持表注释，就必须给得出读取查询", kind).isNotNull();
        assertThat(sql).as("表名必须是绑定参数，不许拼进 SQL").contains("?");
    }

    // ─────────────────────────── DDL 读取 ───────────────────────────

    @Test
    void sqliteReadsDdlFromSqliteMaster() throws SQLException {
        Dialect sqlite = DialectKind.SQLITE.dialect();
        try (Connection connection =
                     DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)");
                statement.execute("CREATE VIEW v_users AS SELECT id FROM users");
            }

            String ddl = sqlite.readDdl(connection, "users", false);
            assertThat(ddl).as("SQLite 的 DDL 就在 sqlite_master 里").isNotNull();
            assertThat(ddl).contains("CREATE TABLE");

            assertThat(sqlite.readDdl(connection, "v_users", true))
                    .as("视图的 DDL 也能读到")
                    .contains("CREATE VIEW");

            assertThat(sqlite.readDdl(connection, "missing", false)).isNull();
        }
    }

    @Test
    void postgresCannotReadDdlFromTheDatabase() throws SQLException {
        Dialect postgres = DialectKind.POSTGRESQL.dialect();
        try (Connection connection =
                     DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThat(postgres.readDdl(connection, "users", false))
                    .as("PostgreSQL 没有 SHOW CREATE TABLE —— 返回 null 让上层由内省生成")
                    .isNull();
            assertThat(postgres.readsDdlFromDatabase()).isFalse();
        }
    }

    @Test
    void onlySqliteTracksDependentViews() throws SQLException {
        try (Connection connection =
                     DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE users (id INTEGER)");
                statement.execute("CREATE VIEW v_users AS SELECT id FROM users");
                statement.execute("CREATE VIEW v_other AS SELECT 1");
            }

            assertThat(DialectKind.SQLITE.dialect().dependentViews(connection, "users"))
                    .as("整表重建前要拆掉引用这张表的视图")
                    .containsExactly("v_users");
            assertThat(DialectKind.POSTGRESQL.dialect().dependentViews(connection, "users"))
                    .isEmpty();
            assertThat(DialectKind.MYSQL.dialect().dependentViews(connection, "users"))
                    .isEmpty();
        }
    }

    @Test
    void indexColumnsSqlOnlyExistsForPragmaDialects() {
        assertThat(DialectKind.SQLITE.dialect().indexColumnsSql("idx_users_email"))
                .isEqualTo("PRAGMA index_info(\"idx_users_email\")");
        assertThat(DialectKind.POSTGRESQL.dialect().indexColumnsSql("idx")).isNull();
        assertThat(DialectKind.MYSQL.dialect().indexColumnsSql("idx")).isNull();
    }

    /**
     * 索引类型与每列排序方向是**方言能力**，不是界面想摆就摆的装饰。
     *
     * <p>摆一个「选了不生效」的控件，比不给控件更糟：用户会以为索引建成了降序
     * （任务书 §6.5.6 禁的就是这种假成功）。所以 SQLite / MySQL 必须给空列表，
     * 而 {@code indexTypeClause} 在非白名单输入下必须返回空串 —— 这里拼的是裸 SQL。
     */
    @Test
    void onlyPostgresOffersIndexTypesAndColumnDirection() {
        assertThat(DialectKind.SQLITE.dialect().indexTypes()).isEmpty();
        assertThat(DialectKind.MYSQL.dialect().indexTypes())
                .as("InnoDB 只有 BTREE；给一个选了必报错的下拉比不给更坏")
                .isEmpty();
        assertThat(DialectKind.POSTGRESQL.dialect().indexTypes())
                .contains("BTREE", "HASH", "GIST", "SPGIST", "GIN", "BRIN");

        assertThat(DialectKind.POSTGRESQL.dialect().indexTypeClause("HASH"))
                .isEqualTo(" USING hash");
        assertThat(DialectKind.POSTGRESQL.dialect().indexTypeClause("hash"))
                .as("大小写不认死：界面给什么都能落到同一个方法名")
                .isEqualTo(" USING hash");
        assertThat(DialectKind.POSTGRESQL.dialect().indexTypeClause(null)).isEmpty();
        assertThat(DialectKind.POSTGRESQL.dialect().indexTypeClause("")).isEmpty();
        assertThat(DialectKind.POSTGRESQL.dialect().indexTypeClause("btree; DROP TABLE users"))
                .as("白名单外的字符串一个字符都不许进 SQL")
                .isEmpty();
        assertThat(DialectKind.SQLITE.dialect().indexTypeClause("HASH")).isEmpty();

        assertThat(DialectKind.POSTGRESQL.dialect().supportsIndexColumnDirection()).isTrue();
        assertThat(DialectKind.MYSQL.dialect().supportsIndexColumnDirection())
                .as("DESC 要 8.0 才真生效，而我们没有运行时版本判定")
                .isFalse();
        assertThat(DialectKind.SQLITE.dialect().supportsIndexColumnDirection()).isFalse();
    }

    /**
     * ⭐ D-33：「不加密」这一档必须落到<b>显式关闭</b>的驱动参数上。
     *
     * <p>真库 TLS 对端（{@code ExternalTlsDatabaseTest} 契约①）首跑就是这条红的：
     * 三家原先都把 OFF 翻成「不写任何参数」，而 Connector/J 默认 {@code PREFERRED}、
     * pgjdbc 默认 {@code prefer} —— 服务端支持 TLS 时<b>照样加密</b>，
     * 于是连接向导里写着「不加密」的那一格实际含义是「按驱动默认」。
     * 那条契约要 Docker 才能跑，这条不用 —— 参数名与取值写错，CI 就该红。
     *
     * <p>取值是三家驱动参数表里的原文（{@code getPropertyInfo} 实测）：
     * {@code DISABLED} / {@code DISABLE} / {@code disable}，一个字母都不能改。
     */
    @Test
    void offLevelDisablesEncryptionInsteadOfLeavingItToTheDriver() {
        assertThat(DialectKind.MYSQL.dialect().sslOptions(Dialect.SslLevel.OFF))
                .containsEntry("sslMode", "DISABLED");
        assertThat(DialectKind.MARIADB.dialect().sslOptions(Dialect.SslLevel.OFF))
                .containsEntry("sslMode", "DISABLE");
        assertThat(DialectKind.POSTGRESQL.dialect().sslOptions(Dialect.SslLevel.OFF))
                .containsEntry("sslmode", "disable");

        // 反向读回也要落在同一档：否则「不加密」保存一次就变成「优先加密」。
        for (DialectKind kind : List.of(DialectKind.MYSQL, DialectKind.MARIADB,
                DialectKind.POSTGRESQL)) {
            Dialect dialect = kind.dialect();
            assertThat(dialect.sslLevel(dialect.sslOptions(Dialect.SslLevel.OFF)))
                    .as("%s 的「不加密」存下去再读回来必须还是「不加密」", kind)
                    .isEqualTo(Dialect.SslLevel.OFF);
        }
    }

    /**
     * 档位 → 参数的三套拼写（同一下拉框，三家三个键名）。
     * 这三家之外没有可表达的档位，界面因此把控件收成说明页。
     */
    @Test
    void sslLevelsMapToEachDriversOwnSpelling() {
        assertThat(DialectKind.MYSQL.dialect().sslOptions(Dialect.SslLevel.REQUIRE))
                .containsEntry("sslMode", "REQUIRED");
        assertThat(DialectKind.MARIADB.dialect().sslOptions(Dialect.SslLevel.REQUIRE))
                .as("MariaDB 的「必须加密」叫 TRUST，不是 PG 的 require 也不是 MySQL 的 REQUIRED")
                .containsEntry("sslMode", "TRUST");
        assertThat(DialectKind.MARIADB.dialect().sslLevels())
                .as("mariadb-java-client 的 SslMode 枚举里没有 prefer 这一档")
                .doesNotContain(Dialect.SslLevel.PREFER);
        assertThat(DialectKind.POSTGRESQL.dialect().sslOptions(Dialect.SslLevel.VERIFY_IDENTITY))
                .containsEntry("sslmode", "verify-full");

        // CA 交给驱动的通道：PG 与 MariaDB 收 PEM，MySQL 什么都不收（收了也是静默忽略）。
        assertThat(DialectKind.POSTGRESQL.dialect().sslRootCertOption()).isEqualTo("sslrootcert");
        assertThat(DialectKind.MARIADB.dialect().sslRootCertOption()).isEqualTo("serverSslCert");
        assertThat(DialectKind.MYSQL.dialect().sslRootCertOption())
                .as("Connector/J 8.2 没有 sslCA / sslCapath：摆一个填了不生效的框比没有更糟")
                .isNull();
    }
}
