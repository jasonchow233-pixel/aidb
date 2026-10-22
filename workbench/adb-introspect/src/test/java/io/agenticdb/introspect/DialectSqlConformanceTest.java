package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ExecutionResult;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ 方言 SQL 一致性门禁。
 *
 * <p><b>为什么不需要真数据库也能守</b>：方言差异最终都体现在<b>生成的 SQL 文本</b>上。
 * 把三种方言的输出逐字锁死，就能在没有 PG / MySQL 实例的机器上挡住
 * 「某个方言下生成的 DDL 是错的」这类问题 —— 而这类问题恰恰是最难在本地发现的。
 *
 * <p>真库的联调由 {@code PostgresMySqlIT}（Testcontainers）负责，本类是它的**前置闸门**：
 * 容器跑不起来时，至少 SQL 文本是对的。
 */
class DialectSqlConformanceTest {

    // ─────────────────────────── 新增列 ───────────────────────────

    @Test
    void addColumnUsesEachDialectsOwnSyntax() {
        ColumnSpec column = new ColumnSpec("email", "TEXT", true, null, null, false, false);

        assertThat(addColumn(DialectKind.SQLITE, column).statements())
                .containsExactly("ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT");
        assertThat(addColumn(DialectKind.POSTGRESQL, column).statements())
                .containsExactly("ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT");
        assertThat(addColumn(DialectKind.MYSQL, column).statements())
                .containsExactly("ALTER TABLE `users` ADD COLUMN `email` TEXT");
    }

    @Test
    void addColumnPutsTheCommentWhereTheDialectWantsIt() {
        ColumnSpec column = new ColumnSpec("email", "TEXT", true, null, "用户邮箱", false, false);

        assertThat(addColumn(DialectKind.MYSQL, column).statements())
                .as("MySQL 的注释写在列定义里")
                .containsExactly("ALTER TABLE `users` ADD COLUMN `email` TEXT COMMENT '用户邮箱'");

        assertThat(addColumn(DialectKind.POSTGRESQL, column).statements())
                .as("PostgreSQL 用独立的 COMMENT ON 语句")
                .containsExactly(
                        "ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT",
                        "COMMENT ON COLUMN \"users\".\"email\" IS '用户邮箱'");

        assertThat(addColumn(DialectKind.SQLITE, column).statements())
                .as("SQLite 没有列注释")
                .containsExactly("ALTER TABLE \"users\" ADD COLUMN \"email\" TEXT");
    }

    @Test
    void sqliteRefusesNotNullColumnWithoutDefault() {
        ColumnSpec column = new ColumnSpec("email", "TEXT", false, null, null, false, false);

        assertThatThrownBy(() -> addColumn(DialectKind.SQLITE, column))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认值");

        // 其它方言允许 —— 已有行不会违反新约束。
        assertThat(addColumn(DialectKind.POSTGRESQL, column).statements()).hasSize(1);
        assertThat(addColumn(DialectKind.MYSQL, column).statements()).hasSize(1);
    }

    // ─────────────────────────── 改列 ───────────────────────────

    @Test
    void alterColumnSplitsForPostgresAndMergesForMySql() {
        ColumnSpec next = new ColumnSpec("email", "TEXT", false, "'x'", null, false, false);

        assertThat(alterColumn(DialectKind.POSTGRESQL, "email", next, "VARCHAR(50)").statements())
                .as("PostgreSQL 把类型 / 非空 / 默认值拆成三条")
                .containsExactly(
                        "ALTER TABLE \"users\" ALTER COLUMN \"email\" TYPE TEXT",
                        "ALTER TABLE \"users\" ALTER COLUMN \"email\" SET NOT NULL",
                        "ALTER TABLE \"users\" ALTER COLUMN \"email\" SET DEFAULT 'x'");

        assertThat(alterColumn(DialectKind.MYSQL, "email", next, "VARCHAR(50)").statements())
                .as("MySQL 一条 MODIFY 带完整定义")
                .containsExactly("ALTER TABLE `users` MODIFY COLUMN `email` TEXT NOT NULL DEFAULT 'x'");
    }

    @Test
    void alterColumnKeepsTheTypeWhenItDidNotChange() {
        ColumnSpec next = new ColumnSpec("email", "TEXT", true, null, null, false, false);

        assertThat(alterColumn(DialectKind.POSTGRESQL, "email", next, "text").statements())
                .as("类型没变就不该生成 TYPE 语句")
                .containsExactly(
                        "ALTER TABLE \"users\" ALTER COLUMN \"email\" DROP NOT NULL",
                        "ALTER TABLE \"users\" ALTER COLUMN \"email\" DROP DEFAULT");
    }

    @Test
    void sqliteAlterColumnFallsBackToTableRebuild() {
        // SQLite 改不了列 —— 必须走**整表重建**（CREATE TABLE + 拷数据 + 改名），
        // 而不是就地 ALTER。
        //
        // ⚠️ 这条断言以前写的是「expect 抛异常」—— 那是**借异常表达意图**，
        // 很脆：重建路径一旦不再依赖真连接（读不到依赖视图时优雅降级），
        // 异常就没了，测试却红了。现在直接断言 SQL 形态 —— 意图更准，也更稳。
        ColumnSpec next = new ColumnSpec("email", "TEXT", true, null, null, false, false);
        StructurePlan plan = alterColumn(DialectKind.SQLITE, "email", next, "TEXT");

        assertThat(plan.sql())
                .as("SQLite 改列 = 整表重建")
                .contains("CREATE TABLE")
                .contains("INSERT INTO")
                .doesNotContain("ALTER COLUMN")
                .doesNotContain("MODIFY COLUMN");
    }

    // ─────────────────────────── 重命名 / 删列 ───────────────────────────

    @Test
    void renameAndDropUseEachDialectsQuotes() {
        assertThat(StructureSqlBuilder.renameColumn(session(DialectKind.MYSQL),
                table(), "old", "new").statements())
                .as("⭐ MySQL 5.7 没有 RENAME COLUMN（8.0 才有）—— 用 CHANGE COLUMN 且带完整定义")
                .containsExactly("ALTER TABLE `users` CHANGE COLUMN `old` `new` VARCHAR(64) NOT NULL");
        assertThat(StructureSqlBuilder.renameColumn(session(DialectKind.POSTGRESQL),
                table(), "old", "new").statements())
                .containsExactly("ALTER TABLE \"users\" RENAME COLUMN \"old\" TO \"new\"");

        assertThat(StructureSqlBuilder.dropColumn(session(DialectKind.SQLITE),
                table(), "email").statements())
                .containsExactly("ALTER TABLE \"users\" DROP COLUMN \"email\"");
    }

    // ─────────────────────────── 建表 DDL ───────────────────────────

    @Test
    void createTableQuotesEveryIdentifier() {
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", ColumnType.NUMBER, false, true, null, null,
                        null, null, null, null, false, "INTEGER"),
                new ColumnDef("name", ColumnType.TEXT, false, false, null, null,
                        null, null, null, null, false, "TEXT"));

        String sqlite = StructureSqlBuilder.createTableSql(session(DialectKind.SQLITE),
                "users", columns, List.of("id"), List.of(), List.of());
        String mysql = StructureSqlBuilder.createTableSql(session(DialectKind.MYSQL),
                "users", columns, List.of("id"), List.of(), List.of());

        assertThat(sqlite)
                .contains("CREATE TABLE \"users\"")
                .contains("\"id\" INTEGER PRIMARY KEY NOT NULL");
        assertThat(mysql)
                .contains("CREATE TABLE `users`")
                .contains("`id` INTEGER PRIMARY KEY NOT NULL");
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private static StructurePlan addColumn(DialectKind kind, ColumnSpec column) {
        return StructureSqlBuilder.addColumn(session(kind), table(), column);
    }

    private static StructurePlan alterColumn(DialectKind kind, String column, ColumnSpec next,
                                             String oldType) {
        // 旧类型来自表结构 —— 这里让表里的 email 列用 oldType，
        // 才能验证「类型没变就不生成 TYPE 语句」。
        return StructureSqlBuilder.alterColumn(session(kind), table(oldType), column, next);
    }

    private static TableStructure table() {
        return table("TEXT");
    }

    private static TableStructure table(String emailRawType) {
        return new TableStructure("users", false,
                List.of(new ColumnDef("email", ColumnType.TEXT, true, false, null, null,
                                null, null, null, null, false, emailRawType),
                        // ⭐ MySQL 5.7 的 CHANGE COLUMN 要带完整定义 —— 所以表里要有 old 列
                        new ColumnDef("old", ColumnType.TEXT, false, false, null, null,
                                null, 64, null, null, false, "VARCHAR")),
                List.of("id"), List.of(), List.of(), List.of(), "");
    }

    /** 只关心方言的会话替身（与表级生命周期测试共用一份）。 */
    private static DbSession session(DialectKind kind) {
        return FakeDialectSession.of(kind);
    }
}
