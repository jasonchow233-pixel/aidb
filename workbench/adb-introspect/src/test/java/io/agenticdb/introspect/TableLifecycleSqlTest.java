package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.Danger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1–T4「表级生命周期」的 SQL 门禁。
 *
 * <p>和 {@link DialectSqlConformanceTest} 同一套思路：<b>不需要真数据库</b>，
 * 把三种方言生成的建表 / 清空 / 重命名 / 删表 SQL 逐字锁死。
 * 这类差异（SQLite 没有 {@code TRUNCATE}、MySQL 用 {@code RENAME TABLE}）
 * 恰恰是本地最难发现、线上才炸的那一类。
 */
class TableLifecycleSqlTest {

    // ─────────────────────────── T1 新建表 ───────────────────────────

    @Test
    void createTableUsesEachDialectsQuoting() {
        StructurePlan plan = createTable(DialectKind.SQLITE, List.of(
                new ColumnSpec("id", "INTEGER", false, null, null, false, true),
                new ColumnSpec("title", "TEXT", false, null, null, false, false)));

        assertThat(plan.statements()).containsExactly(
                "CREATE TABLE \"orders\" (\n"
                        + "    \"id\" INTEGER NOT NULL PRIMARY KEY,\n"
                        + "    \"title\" TEXT NOT NULL\n"
                        + ")");
        assertThat(plan.danger()).isEqualTo(Danger.DESTRUCTIVE);
        assertThat(plan.approvalRequired())
                .as("建表是纯新增，没有旧数据可丢 —— 不该再要第二道确认")
                .isFalse();
    }

    @Test
    void createTablePutsPrimaryKeyBeforeTheMySQLComment() {
        StructurePlan plan = createTable(DialectKind.MYSQL, List.of(
                new ColumnSpec("id", "INTEGER", false, null, "主键", false, true)));

        assertThat(plan.statements()).containsExactly(
                "CREATE TABLE `orders` (\n"
                        + "    `id` INTEGER NOT NULL PRIMARY KEY COMMENT '主键'\n"
                        // ⭐ DEFAULT CHARSET=utf8mb4：MySQL 8.0 之前默认 latin1，
                        //    不带这条建出来的表存不了中文（多版本矩阵在 5.7 上抓出）。
                        + ") DEFAULT CHARSET=utf8mb4");
    }

    @Test
    void createTableUsesCommentOnForPostgres() {
        StructurePlan plan = createTable(DialectKind.POSTGRESQL, List.of(
                new ColumnSpec("id", "INTEGER", false, null, "主键", false, true)));

        assertThat(plan.statements()).containsExactly(
                "CREATE TABLE \"orders\" (\n"
                        + "    \"id\" INTEGER NOT NULL PRIMARY KEY\n"
                        + ")",
                "COMMENT ON COLUMN \"orders\".\"id\" IS '主键'");
    }

    @Test
    void createTableDropsCommentsOnSqliteInsteadOfFailing() {
        StructurePlan plan = createTable(DialectKind.SQLITE, List.of(
                new ColumnSpec("id", "INTEGER", false, null, "主键", false, true)));

        assertThat(plan.statements())
                .as("SQLite 没有列注释 —— 静默丢弃，不要生成一条会语法错误的 SQL")
                .hasSize(1)
                .first().asString().doesNotContain("主键");
    }

    @Test
    void compositePrimaryKeyBecomesATableConstraint() {
        StructurePlan plan = createTable(DialectKind.SQLITE, List.of(
                new ColumnSpec("a", "INTEGER", false, null, null, false, true),
                new ColumnSpec("b", "INTEGER", false, null, null, false, true)));

        assertThat(plan.statements().getFirst()).isEqualTo(
                "CREATE TABLE \"orders\" (\n"
                        + "    \"a\" INTEGER NOT NULL,\n"
                        + "    \"b\" INTEGER NOT NULL,\n"
                        + "    PRIMARY KEY (\"a\", \"b\")\n"
                        + ")");
    }

    @Test
    void tableCommentIsSkippedWhereTheDialectCannotStoreIt() {
        assertThat(createTable(DialectKind.POSTGRESQL, columns(), "订单表").statements())
                .last().asString().isEqualTo("COMMENT ON TABLE \"orders\" IS '订单表'");
        assertThat(createTable(DialectKind.MYSQL, columns(), "订单表").statements())
                .last().asString().isEqualTo("ALTER TABLE `orders` COMMENT = '订单表'");
        assertThat(createTable(DialectKind.SQLITE, columns(), "订单表").statements())
                .as("SQLite 没有表注释")
                .hasSize(1);
    }

    @Test
    void tableCommentEscapesSingleQuotes() {
        assertThat(createTable(DialectKind.POSTGRESQL, columns(), "it's a table").statements())
                .last().asString().isEqualTo("COMMENT ON TABLE \"orders\" IS 'it''s a table'");
    }

    // ─────────────────────────── T1 校验 ───────────────────────────

    @Test
    void refusesATableNameThatAlreadyExists() {
        assertThatThrownBy(() -> StructureSqlBuilder.createTable(
                FakeDialectSession.of(DialectKind.SQLITE), "users", columns(), null,
                List.of("Users")))
                .as("重名要给出人话，不能把数据库那句 'table already exists' 直接甩给用户")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经存在");
    }

    @Test
    void refusesAnEmptyOrIllegalTableName() {
        assertThatThrownBy(() -> StructureSqlBuilder.createTable(
                FakeDialectSession.of(DialectKind.SQLITE), "  ", columns(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名不能为空");

        assertThatThrownBy(() -> StructureSqlBuilder.createTable(
                FakeDialectSession.of(DialectKind.SQLITE), "9orders", columns(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表名只能");
    }

    @Test
    void refusesATableWithoutColumns() {
        assertThatThrownBy(() -> StructureSqlBuilder.createTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders", List.of(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一列");
    }

    @Test
    void refusesDuplicateColumnNames() {
        assertThatThrownBy(() -> createTable(DialectKind.SQLITE, List.of(
                new ColumnSpec("id", "INTEGER", false, null, null, false, true),
                new ColumnSpec("ID", "TEXT", true, null, null, false, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void refusesANullablePrimaryKey() {
        assertThatThrownBy(() -> createTable(DialectKind.SQLITE, List.of(
                new ColumnSpec("id", "INTEGER", true, null, null, false, true))))
                .as("三种数据库里主键都不能为 NULL —— 提前说清楚好过数据库报 cannot be null")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能允许 NULL");
    }

    // ─────────────────────────── T2 清空表 ───────────────────────────

    @Test
    void sqliteHasNoTruncateSoItDeletesInstead() {
        StructurePlan plan = StructureSqlBuilder.truncateTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders");

        assertThat(plan.statements()).containsExactly("DELETE FROM \"orders\"");
        assertThat(plan.approvalRequired())
                .as("清空表是删数据，必须第二道确认")
                .isTrue();
    }

    @Test
    void postgresAndMySqlTruncate() {
        assertThat(StructureSqlBuilder.truncateTable(
                FakeDialectSession.of(DialectKind.POSTGRESQL), "orders").statements())
                .containsExactly("TRUNCATE TABLE \"orders\"");
        assertThat(StructureSqlBuilder.truncateTable(
                FakeDialectSession.of(DialectKind.MYSQL), "orders").statements())
                .containsExactly("TRUNCATE TABLE `orders`");
    }

    // ─────────────────────────── T3 重命名表 ───────────────────────────

    @Test
    void mySqlRenamesWithRenameTableTheOthersWithAlterTable() {
        assertThat(StructureSqlBuilder.renameTable(
                FakeDialectSession.of(DialectKind.MYSQL), "orders", "orders2", List.of())
                .statements()).containsExactly("RENAME TABLE `orders` TO `orders2`");
        assertThat(StructureSqlBuilder.renameTable(
                FakeDialectSession.of(DialectKind.POSTGRESQL), "orders", "orders2", List.of())
                .statements()).containsExactly("ALTER TABLE \"orders\" RENAME TO \"orders2\"");
        assertThat(StructureSqlBuilder.renameTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders", "orders2", List.of())
                .statements()).containsExactly("ALTER TABLE \"orders\" RENAME TO \"orders2\"");
    }

    @Test
    void renameRefusesAnExistingTargetAndANoOp() {
        assertThatThrownBy(() -> StructureSqlBuilder.renameTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders", "users", List.of("users")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已经存在");

        assertThatThrownBy(() -> StructureSqlBuilder.renameTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders", "ORDERS", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相同");
    }

    // ─────────────────────────── T4 删除表 ───────────────────────────

    @Test
    void batchDropProducesOneStatementPerTableAndDeduplicatesNames() {
        StructurePlan plan = StructureSqlBuilder.dropTables(
                FakeDialectSession.of(DialectKind.SQLITE),
                List.of("orders", "logs", "ORDERS"));

        assertThat(plan.title()).isEqualTo("删除 2 张表");
        assertThat(plan.statements()).containsExactly(
                "DROP TABLE \"orders\"", "DROP TABLE \"logs\"");
        assertThat(plan.approvalRequired())
                .as("批量删除是破坏性动作，仍要第二道确认")
                .isTrue();
    }

    @Test
    void batchDropRefusesAnEmptySelection() {
        assertThatThrownBy(() -> StructureSqlBuilder.dropTables(
                FakeDialectSession.of(DialectKind.SQLITE), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少选择一张表");
    }

    @Test
    void dropTableIsQuotedPerDialectAndNeedsApproval() {
        assertThat(StructureSqlBuilder.dropTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders").statements())
                .containsExactly("DROP TABLE \"orders\"");
        assertThat(StructureSqlBuilder.dropTable(
                FakeDialectSession.of(DialectKind.MYSQL), "orders").statements())
                .containsExactly("DROP TABLE `orders`");
        assertThat(StructureSqlBuilder.dropTable(
                FakeDialectSession.of(DialectKind.SQLITE), "orders").approvalRequired())
                .isTrue();
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static List<ColumnSpec> columns() {
        return List.of(new ColumnSpec("id", "INTEGER", false, null, null, false, true));
    }

    private static StructurePlan createTable(DialectKind kind, List<ColumnSpec> columns) {
        return createTable(kind, columns, null);
    }

    private static StructurePlan createTable(DialectKind kind, List<ColumnSpec> columns,
                                             String tableComment) {
        return StructureSqlBuilder.createTable(FakeDialectSession.of(kind), "orders",
                columns, tableComment, List.of());
    }
}
