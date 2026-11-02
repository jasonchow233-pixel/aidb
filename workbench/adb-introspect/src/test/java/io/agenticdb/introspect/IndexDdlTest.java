package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ColumnProblem;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1 索引可编辑 —— 生成的 SQL 与拒绝规则。
 *
 * <p>重点不是「能不能拼出 CREATE INDEX」，而是<b>三种库的 DROP INDEX 不一样</b>，
 * 以及<b>数据库自动建的索引不能删</b> —— 这两条写错都会让用户以为工具坏了。
 *
 * <p>断言一律打在 {@code statements()} 的单条语句上，不打 {@code sql()}：
 * 后者是给人看的展示串（会主动补分号），前者才是真正交给 JDBC 逐条 execute 的
 * 东西。SQLite 的 execute 遇到 {@code near ";": syntax error}。
 */
class IndexDdlTest {

    private static final List<IndexDef> NO_INDEXES = List.of();

    // ─────────────────────────── 新建 ───────────────────────────

    @Test
    void createsPlainIndex() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                new IndexDef("idx_email", false, List.of("email"), "")).statements().getFirst())
                .isEqualTo("CREATE INDEX \"idx_email\" ON \"users\" (\"email\")");
    }

    @Test
    void createsUniqueIndex() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                new IndexDef("idx_email", true, List.of("email"), "")).statements().getFirst())
                .isEqualTo("CREATE UNIQUE INDEX \"idx_email\" ON \"users\" (\"email\")");
    }

    @Test
    void compositeIndexKeepsColumnOrder() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                new IndexDef("idx_names", false, List.of("last", "first"), "")).sql())
                .as("复合索引的列顺序直接决定它能不能被用上（最左前缀），不能重排")
                .contains("(\"last\", \"first\")");
    }

    @Test
    void mysqlUsesBackticks() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.MYSQL), table(),
                new IndexDef("idx_email", false, List.of("email"), "")).statements().getFirst())
                .isEqualTo("CREATE INDEX `idx_email` ON `users` (`email`)");
    }

    @Test
    void postgresEmitsTheChosenTypeAndDescendingColumns() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(),
                new IndexDef("idx_names", false, List.of("last", "first"), "",
                        "HASH", List.of("last"))).statements().getFirst())
                .as("类型与排序方向是新出来的能力，必须真的落到语句里 —— "
                        + "界面上选了、SQL 里没有，就是「显示成功但数据没变」")
                .isEqualTo("CREATE INDEX \"idx_names\" ON \"users\" USING hash (\"last\" DESC, \"first\")");
    }

    @Test
    void dialectsWithoutTheCapabilityEmitNeitherUsingNorDesc() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.MYSQL), table(),
                new IndexDef("idx_email", false, List.of("email"), "", "HASH", List.of("email")))
                        .statements().getFirst())
                .as("类型和降序是 MySQL 方言不认的能力 —— 数据里带了也不拼进 SQL："
                        + "界面选了却生成本库不支持的子句，执行时就是语法错误，"
                        + "把字符串原样塞进 SQL 更是注入面")
                .isEqualTo("CREATE INDEX `idx_email` ON `users` (`email`)");
    }

    @Test
    void legacyIndexDefStillProducesThePreCapabilityStatement() {
        assertThat(StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(),
                new IndexDef("idx_email", false, List.of("email"), "")).statements().getFirst())
                .as("四参构造器（内省结果走的这条）必须和改造前逐字节一致")
                .isEqualTo("CREATE INDEX \"idx_email\" ON \"users\" (\"email\")");
    }

    // ─────────────────────────── 新建的拒绝规则 ───────────────────────────

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                new IndexDef("  ", false, List.of("email"), "")))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.nameEmpty");
    }

    @Test
    void rejectsNoColumns() {
        assertThatThrownBy(() -> StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                new IndexDef("idx_empty", false, List.of(), "")))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.needColumns");
    }

    @Test
    void rejectsUnknownColumn() {
        assertThatThrownBy(() -> StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                new IndexDef("idx_x", false, List.of("nope"), "")))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "column.error.notInTable");
    }

    @Test
    void rejectsDuplicateName() {
        TableStructure table = new TableStructure("users", false, columns(), List.of(),
                List.of(new IndexDef("idx_email", false, List.of("email"), "")),
                List.of(), List.of(), "");

        assertThatThrownBy(() -> StructureSqlBuilder.createIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table,
                new IndexDef("IDX_EMAIL", false, List.of("first"), "")))
                .as("索引名在同一张表里不分大小写")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────── 删除：三种库不一样 ───────────────────────────

    @Test
    void mysqlDropIndexNeedsOnTable() {
        assertThat(StructureSqlBuilder.dropIndex(
                FakeDialectSession.of(DialectKind.MYSQL), tableWithIndex(), "idx_email")
                        .statements().getFirst())
                .as("MySQL 的 DROP INDEX 是 ALTER TABLE 的语法糖，不带 ON 表名就是语法错误")
                .isEqualTo("DROP INDEX `idx_email` ON `users`");
    }

    @Test
    void postgresDropIndexHasNoOnClause() {
        assertThat(StructureSqlBuilder.dropIndex(
                FakeDialectSession.of(DialectKind.POSTGRESQL), tableWithIndex(), "idx_email")
                        .statements().getFirst())
                .as("PostgreSQL 的 DROP INDEX 只认索引名，带 ON 反而语法错误")
                .isEqualTo("DROP INDEX \"idx_email\"");
    }

    @Test
    void sqliteDropIndexHasNoOnClause() {
        assertThat(StructureSqlBuilder.dropIndex(
                FakeDialectSession.of(DialectKind.SQLITE), tableWithIndex(), "idx_email").statements().getFirst())
                .isEqualTo("DROP INDEX \"idx_email\"");
    }

    // ─────────────────────────── 删除的拒绝规则 ───────────────────────────

    @Test
    void refusesToDropAutoCreatedIndex() {
        TableStructure table = new TableStructure("users", false, columns(), List.of(),
                List.of(new IndexDef("sqlite_autoindex_users_1", false, List.of("id"), "pk")),
                List.of(), List.of(), "");

        assertThatThrownBy(() -> StructureSqlBuilder.dropIndex(
                FakeDialectSession.of(DialectKind.SQLITE), table, "sqlite_autoindex_users_1"))
                .as("自动索引是约束的物理实现 —— 删了也会在下次结构变更时被重建，"
                        + "用户只会以为工具坏了")
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.autoCreated");
    }

    @Test
    void refusesToDropPrimaryIndexOnMySql() {
        TableStructure table = new TableStructure("users", false, columns(), List.of("id"),
                List.of(new IndexDef("PRIMARY", true, List.of("id"), "")),
                List.of(), List.of(), "");

        assertThatThrownBy(() -> StructureSqlBuilder.dropIndex(
                FakeDialectSession.of(DialectKind.MYSQL), table, "PRIMARY"))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.autoCreated");
    }

    @Test
    void refusesUnknownIndexName() {
        assertThatThrownBy(() -> StructureSqlBuilder.dropIndex(
                FakeDialectSession.of(DialectKind.SQLITE), tableWithIndex(), "nope"))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.notFound");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static List<ColumnDef> columns() {
        return List.of(
                column("id"),
                column("email"),
                column("first"),
                column("last"));
    }

    private static ColumnDef column(String name) {
        return new ColumnDef(name, ColumnType.TEXT, true, false, null, null,
                null, null, null, null, false, "TEXT");
    }

    private static TableStructure table() {
        return new TableStructure("users", false, columns(), List.of(),
                NO_INDEXES, List.of(), List.of(), "");
    }

    private static TableStructure tableWithIndex() {
        return new TableStructure("users", false, columns(), List.of(),
                List.of(new IndexDef("idx_email", false, List.of("email"), "")),
                List.of(), List.of(), "");
    }
}
