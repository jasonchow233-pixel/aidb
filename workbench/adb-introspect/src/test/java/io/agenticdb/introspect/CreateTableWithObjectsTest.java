package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.ColumnProblem;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.model.ColumnSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ R30-D：建表计划里带索引 / 外键 / 触发器。
 *
 * <p>表设计器的六页（字段 / 索引 / 外键 / 触发器 / 选项 / SQL）最后必须收敛成
 * <b>一个</b> {@link StructurePlan}：预览与执行同一个 SQL、同一个事务。这里钉住
 * 三件事：
 * <ol>
 *   <li>外键写在 {@code CREATE TABLE} 里（SQLite 只能这样建外键）；</li>
 *   <li>索引 / 触发器按「建表 → 索引 → 触发器」的顺序追加；</li>
 *   <li>任何一项不合法都抛可读的中文原因，不会生成半截 SQL。</li>
 * </ol>
 */
class CreateTableWithObjectsTest {

    private static final List<ColumnSpec> COLUMNS = List.of(
            new ColumnSpec("id", "INTEGER", false, null, null, false, true),
            new ColumnSpec("email", "TEXT", false, null, null, true, false),
            new ColumnSpec("dept_id", "INTEGER", true, null, null, false, false));

    // ─────────────────────────── 外键 ───────────────────────────

    @Test
    void foreignKeysAreWrittenIntoCreateTableNotAsAlterStatements() {
        StructurePlan plan = plan(DialectKind.SQLITE,
                List.of(new ForeignKeyDef("fk_users_dept", List.of("dept_id"),
                        "departments", List.of("id"), "NO ACTION", "CASCADE")),
                List.of(), List.of());

        assertThat(plan.statements())
                .as("SQLite 不支持 ALTER TABLE ADD CONSTRAINT —— 外键只能写进 CREATE TABLE")
                .hasSize(1);
        assertThat(plan.statements().getFirst())
                .contains("CONSTRAINT \"fk_users_dept\" FOREIGN KEY (\"dept_id\")")
                .contains("REFERENCES \"departments\" (\"id\")")
                .contains("ON UPDATE NO ACTION")
                .contains("ON DELETE CASCADE");

        assertThat(plan(DialectKind.MYSQL,
                List.of(new ForeignKeyDef("fk_users_dept", List.of("dept_id"),
                        "departments", List.of("id"), "NO ACTION", "RESTRICT")),
                List.of(), List.of()).statements().getFirst())
                .as("MySQL 用反引号，外键子句同样进 CREATE TABLE")
                .contains("CONSTRAINT `fk_users_dept` FOREIGN KEY (`dept_id`)")
                .contains("REFERENCES `departments` (`id`)");
    }

    @Test
    void compositeForeignKeyKeepsTheColumnOrder() {
        String sql = plan(DialectKind.SQLITE,
                List.of(new ForeignKeyDef("fk_pair", List.of("dept_id", "email"),
                        "t", List.of("a", "b"), null, null)),
                List.of(), List.of()).statements().getFirst();

        assertThat(sql).contains("FOREIGN KEY (\"dept_id\", \"email\") REFERENCES \"t\" (\"a\", \"b\")");
    }

    @Test
    void unnamedForeignKeyOmitsTheConstraintKeyword() {
        String sql = plan(DialectKind.SQLITE,
                List.of(new ForeignKeyDef("", List.of("dept_id"),
                        "departments", List.of("id"), null, null)),
                List.of(), List.of()).statements().getFirst();

        assertThat(sql).contains("\n    FOREIGN KEY (\"dept_id\")").doesNotContain("CONSTRAINT");
    }

    @Test
    void foreignKeyOnAMissingColumnIsRefused() {
        assertThatThrownBy(() -> plan(DialectKind.SQLITE,
                List.of(new ForeignKeyDef("fk", List.of("nope"),
                        "departments", List.of("id"), null, null)),
                List.of(), List.of()))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "column.error.notInTable")
                .hasFieldOrPropertyWithValue("args", new Object[]{"nope"});
    }

    @Test
    void foreignKeyWithMismatchedColumnCountIsRefused() {
        assertThatThrownBy(() -> plan(DialectKind.SQLITE,
                List.of(new ForeignKeyDef("fk", List.of("dept_id", "email"),
                        "departments", List.of("id"), null, null)),
                List.of(), List.of()))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "fk.error.columnCount");
    }

    // ─────────────────────────── 索引 / 触发器 ───────────────────────────

    @Test
    void indexesAndTriggersFollowTheCreateStatement() {
        StructurePlan plan = plan(DialectKind.SQLITE, List.of(),
                List.of(new IndexDef("idx_users_email", true, List.of("email"), "")),
                List.of(new TriggerDef("trg_users_audit", "AFTER", "INSERT",
                        "INSERT INTO audit (id) VALUES (NEW.id)")));

        assertThat(plan.statements()).hasSize(3);
        assertThat(plan.statements().get(0)).startsWith("CREATE TABLE \"users\"");
        assertThat(plan.statements().get(1))
                .isEqualTo("CREATE UNIQUE INDEX \"idx_users_email\" ON \"users\" (\"email\")");
        assertThat(plan.statements().get(2))
                .isEqualTo("CREATE TRIGGER \"trg_users_audit\" AFTER INSERT ON \"users\""
                        + " BEGIN INSERT INTO audit (id) VALUES (NEW.id); END");
    }

    @Test
    void indexOnAMissingColumnIsRefused() {
        assertThatThrownBy(() -> plan(DialectKind.SQLITE, List.of(),
                List.of(new IndexDef("idx_nope", false, List.of("nope"), "")), List.of()))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.columnNotFound");
    }

    @Test
    void duplicateIndexNamesAreRefused() {
        List<IndexDef> indexes = List.of(
                new IndexDef("idx_dup", false, List.of("email"), ""),
                new IndexDef("IDX_DUP", false, List.of("email"), ""));

        assertThatThrownBy(() -> plan(DialectKind.SQLITE, List.of(), indexes, List.of()))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "index.error.duplicate")
                .hasFieldOrPropertyWithValue("args", new Object[]{"IDX_DUP"});
    }

    @Test
    void triggerWithoutABodyIsRefused() {
        assertThatThrownBy(() -> plan(DialectKind.SQLITE, List.of(), List.of(),
                List.of(new TriggerDef("trg", "AFTER", "INSERT", "  "))))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "trigger.error.bodyEmpty");
    }

    @Test
    void duplicateTriggerNamesAreRefused() {
        List<TriggerDef> triggers = List.of(
                new TriggerDef("trg", "AFTER", "INSERT", "SELECT 1"),
                new TriggerDef("TRG", "AFTER", "UPDATE", "SELECT 2"));

        assertThatThrownBy(() -> plan(DialectKind.SQLITE, List.of(), List.of(), triggers))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "trigger.error.duplicate");
    }

    // ─────────────────────────── 计划口径 ───────────────────────────

    /**
     * 没有额外对象时，语句必须<b>逐字</b>等于旧的四段式建表 ——
     * 否则 R30-D 会顺手改掉所有既有建表 SQL（多版本真库矩阵才发现得了）。
     */
    @Test
    void withoutExtrasThePlanIsByteIdenticalToTheOldCreateTable() {
        DbSession session = FakeDialectSession.of(DialectKind.MYSQL);

        StructurePlan old = StructureSqlBuilder.createTable(session, "users", COLUMNS, null,
                List.of());
        StructurePlan now = StructureSqlBuilder.createTableWith(session, "users", COLUMNS, null,
                List.of(), List.of(), List.of(), List.of());

        assertThat(now).isEqualTo(old);
    }

    @Test
    void extrasStayInOnePlanThatNeedsNoSecondConfirmation() {
        StructurePlan plan = plan(DialectKind.SQLITE, List.of(), List.of(),
                List.of(new TriggerDef("trg", "AFTER", "INSERT", "SELECT 1")));

        assertThat(plan.titleKey()).isEqualTo("plan.title.createTable");
        assertThat(plan.titleArgs()).containsExactly("users");
        assertThat(plan.approvalRequired())
                .as("全是新增，同一个事务里一条计划 —— 不该再弹第二道确认")
                .isFalse();
        assertThat(plan.foreignKeysOff()).isFalse();
    }

    private static StructurePlan plan(DialectKind kind, List<ForeignKeyDef> foreignKeys,
                                      List<IndexDef> indexes, List<TriggerDef> triggers) {
        return StructureSqlBuilder.createTableWith(FakeDialectSession.of(kind), "users",
                COLUMNS, null, List.of(), foreignKeys, indexes, triggers);
    }
}
