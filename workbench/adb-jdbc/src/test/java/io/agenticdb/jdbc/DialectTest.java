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
        assertThat(DialectKind.values()).hasSize(3);
        assertThat(java.util.Arrays.stream(DialectKind.values())
                .map(DialectKind::id).toList()).doesNotHaveDuplicates();
    }

    // ─────────────────────────── 标识符引用 ───────────────────────────

    @Test
    void mysqlUsesBackticksOthersUseDoubleQuotes() {
        assertThat(DialectKind.MYSQL.dialect().quote("users")).isEqualTo("`users`");
        assertThat(DialectKind.POSTGRESQL.dialect().quote("users")).isEqualTo("\"users\"");
        assertThat(DialectKind.SQLITE.dialect().quote("users")).isEqualTo("\"users\"");
    }

    @Test
    void quotingEscapesTheQuoteCharacter() {
        assertThat(DialectKind.MYSQL.dialect().quote("a`b")).isEqualTo("`a``b`");
        assertThat(DialectKind.SQLITE.dialect().quote("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test
    void quotingRejectsNull() {
        assertThatThrownBy(() -> DialectKind.SQLITE.dialect().quote(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────── 连接 ───────────────────────────

    @Test
    void jdbcUrlsCarryTheRightPrefix() {
        assertThat(DialectKind.SQLITE.dialect().jdbcUrl("", 0, "/tmp/a.db"))
                .isEqualTo("jdbc:sqlite:/tmp/a.db");
        assertThat(DialectKind.POSTGRESQL.dialect().jdbcUrl("h", 5432, "db"))
                .isEqualTo("jdbc:postgresql://h:5432/db");
        assertThat(DialectKind.MYSQL.dialect().jdbcUrl("h", 3306, "db"))
                .startsWith("jdbc:mysql://h:3306/db");
    }

    @Test
    void defaultPortsAndPooling() {
        assertThat(DialectKind.SQLITE.dialect().defaultPort()).isZero();
        assertThat(DialectKind.POSTGRESQL.dialect().defaultPort()).isEqualTo(5432);
        assertThat(DialectKind.MYSQL.dialect().defaultPort()).isEqualTo(3306);

        assertThat(DialectKind.SQLITE.dialect().pooled())
                .as("SQLite 是单文件直连，不池化")
                .isFalse();
        assertThat(DialectKind.POSTGRESQL.dialect().pooled()).isTrue();
        assertThat(DialectKind.MYSQL.dialect().pooled()).isTrue();
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
}
