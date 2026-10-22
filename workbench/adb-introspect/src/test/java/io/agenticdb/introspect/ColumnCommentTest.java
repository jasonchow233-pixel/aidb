package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列注释：读得回来、改列时留得住、清空时真能清掉。
 *
 * <p>这两条是<b>丢数据</b>路径，不是显示问题：
 * 注释读不回来 → 改一次列注释被静默抹掉，且没有撤销路径。
 */
class ColumnCommentTest {

    // ─────────────────────────── 读：方言必须给出来源 ───────────────────────────

    @Test
    void mysqlReadsCommentsFromInformationSchema() {
        String sql = DialectKind.MYSQL.dialect().columnCommentsSql("users");

        assertThat(sql)
                .as("MySQL 必须读列注释 —— 不读就意味着改一次列注释就没了")
                .isNotNull()
                .contains("information_schema.COLUMNS")
                .contains("COLUMN_COMMENT");
    }

    @Test
    void postgresReadsCommentsFromColDescription() {
        String sql = DialectKind.POSTGRESQL.dialect().columnCommentsSql("users");

        assertThat(sql)
                .as("PostgreSQL 的注释存在 pg_description 里，只能靠 col_description 取")
                .isNotNull()
                .contains("col_description");
    }

    @Test
    void sqliteHasNoCommentSource() {
        assertThat(DialectKind.SQLITE.dialect().columnCommentsSql("users"))
                .as("SQLite 根本没有注释概念 —— 返回 null，调用方直接跳过")
                .isNull();
    }

    @Test
    void tableNameIsEscapedInCommentQuery() {
        assertThat(DialectKind.MYSQL.dialect().columnCommentsSql("us'ers"))
                .as("表名里的单引号必须转义，否则这条探测 SQL 自己就语法错误")
                .contains("us''ers");
    }

    // ─────────────────────────── 合并：纯函数 ───────────────────────────

    @Test
    void applyCommentsFillsInWhatIntrospectionMissed() {
        List<ColumnDef> columns = List.of(
                column("id", true, null),
                column("email", false, null));

        List<ColumnDef> merged = SchemaService.applyComments(columns,
                page(Map.of("id", "主键", "email", "登录邮箱")));

        assertThat(merged).extracting(ColumnDef::comment)
                .containsExactly("主键", "登录邮箱");
    }

    @Test
    void applyCommentsKeepsEverythingElse() {
        List<ColumnDef> columns = List.of(column("id", true, null));

        List<ColumnDef> merged = SchemaService.applyComments(columns,
                page(Map.of("id", "主键")));

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().primaryKey())
                .as("并注释不能把主键标记弄丢 —— 那会让整张表变成不可编辑")
                .isTrue();
        assertThat(merged.getFirst().rawType()).isEqualTo("INTEGER");
    }

    @Test
    void applyCommentsIgnoresBlankAndMissingRows() {
        List<ColumnDef> columns = List.of(column("id", true, null));

        List<ColumnDef> merged = SchemaService.applyComments(columns,
                page(Map.of("id", "   ")));

        assertThat(merged.getFirst().comment())
                .as("空注释不能覆盖成空白字符串 —— 那会让每一次改列都多一条无意义语句")
                .isNull();
    }

    @Test
    void applyCommentsSurvivesAnEmptyResult() {
        List<ColumnDef> columns = List.of(column("id", true, null));

        assertThat(SchemaService.applyComments(columns, page(Map.of())))
                .as("探测不到就当没有 —— 不能让整张表的结构读不出来")
                .isEqualTo(columns);
    }

    // ─────────────────────────── 写：改列时注释的三种走向 ───────────────────────────

    @Test
    void mysqlKeepsCommentOnAlter() {
        StructurePlan plan = StructureSqlBuilder.alterColumn(
                FakeDialectSession.of(DialectKind.MYSQL),
                table("users", column("email", false, "登录邮箱")),
                "email", spec("email", "登录邮箱"));

        assertThat(plan.sql())
                .as("MySQL 把注释写在 MODIFY COLUMN 里 —— 丢了就是改一次列注释没了")
                .contains("COMMENT '登录邮箱'");
    }

    @Test
    void postgresClearsCommentWhenUserEmptiesIt() {
        StructurePlan plan = StructureSqlBuilder.alterColumn(
                FakeDialectSession.of(DialectKind.POSTGRESQL),
                table("users", column("email", false, "登录邮箱")),
                "email", spec("email", null));

        assertThat(plan.sql())
                .as("PostgreSQL 的注释独立于列定义 —— 不清空就显式 IS NULL，否则「清空」是假的")
                .contains("COMMENT ON COLUMN \"users\".\"email\" IS NULL");
    }

    @Test
    void postgresDoesNotEmitResetWhenThereWasNoComment() {
        StructurePlan plan = StructureSqlBuilder.alterColumn(
                FakeDialectSession.of(DialectKind.POSTGRESQL),
                table("users", column("email", false, null)),
                "email", spec("email", null));

        assertThat(plan.sql())
                .as("本来就没注释就别发 IS NULL —— 每次改列多一条空操作只会让用户怀疑")
                .doesNotContain("COMMENT ON COLUMN");
    }

    @Test
    void postgresSetCommentIsEscaped() {
        StructurePlan plan = StructureSqlBuilder.alterColumn(
                FakeDialectSession.of(DialectKind.POSTGRESQL),
                table("users", column("email", false, null)),
                "email", spec("email", "it's fine"));

        assertThat(plan.sql())
                .as("注释里的单引号必须转义，否则这条 DDL 自己就语法错误")
                .contains("IS 'it''s fine'");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static ColumnDef column(String name, boolean primaryKey, String comment) {
        return new ColumnDef(name, ColumnType.TEXT, !primaryKey, primaryKey, null, comment,
                null, null, null, null, false, "INTEGER");
    }

    private static io.agenticdb.model.ColumnSpec spec(String name, String comment) {
        return new io.agenticdb.model.ColumnSpec(name, "INTEGER", true, null, comment,
                false, false);
    }

    private static TableStructure table(String name, ColumnDef... columns) {
        return new TableStructure(name, false, List.of(columns), List.of(),
                List.of(), List.of(), List.of(), "");
    }

    /** 两列结果集：列名 / 注释。 */
    private static RowPage page(Map<String, String> comments) {
        List<ColumnDef> meta = List.of(
                new ColumnDef("column_name", ColumnType.TEXT, false, false, null, null,
                        null, null, null, null, false, "TEXT"),
                new ColumnDef("column_comment", ColumnType.TEXT, true, false, null, null,
                        null, null, null, null, false, "TEXT"));
        List<Map<String, Object>> rows = comments.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "column_name", entry.getKey(),
                        "column_comment", entry.getValue()))
                .toList();
        return new RowPage(meta, rows, null, null, 0L, List.of(), false, false);
    }
}
