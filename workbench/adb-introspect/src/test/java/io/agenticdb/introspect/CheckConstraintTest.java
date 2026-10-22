package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ D3 门禁：CHECK 约束**内省 + 重建存活**。
 *
 * <h2>这个缺陷长什么样</h2>
 * SQLite 改列要整表重建，重建的 {@code CREATE TABLE} 以前只搬运
 * 「列 / 主键 / UNIQUE / DEFAULT / 外键」——<b>不搬 CHECK</b>。
 * 于是：用户改一列 → 重建 → <b>该表所有 CHECK 约束静默消失</b>。
 *
 * <p>这是最坏的一类缺陷：<b>丢数据且无撤销路径</b>，而且用户不会立刻察觉
 * （约束没了 = 脏数据能写进去了）。
 *
 * <h2>本门禁守三件事</h2>
 * <ol>
 *   <li>解析器能从 {@code CREATE TABLE} 原文里抽出 CHECK（SQLite 只有这一条路）；</li>
 *   <li>结构内省**看得见** CHECK；</li>
 *   <li>改列重建之后，CHECK <b>还在</b>，而且<b>仍然拦得住违规数据</b>
 *       —— 只「看得见」不够，约束必须真的还在生效。</li>
 * </ol>
 */
class CheckConstraintTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schema;

    @BeforeEach
    void open() throws Exception {
        Path file = DemoDatabase.create(temp.resolve("demo.db"));
        session = JdbcSession.open(ConnectionSpec.sqlite(file));
        schema = new SchemaService();
        try (java.sql.Statement statement = session.connection().createStatement()) {
            statement.execute("CREATE TABLE people ("
                    + "id INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "age INTEGER, "
                    + "score REAL, "
                    + "CONSTRAINT age_positive CHECK (age >= 0), "
                    + "CONSTRAINT score_range CHECK (score <= 100)"
                    + ")");
            statement.execute("INSERT INTO people (id, name, age, score) "
                    + "VALUES (1, '张三', 30, 9.5)");
        }
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    // ─────────────────────── 解析器 ───────────────────────

    @Test
    void parsesNamedAndUnnamedChecks() {
        List<CheckDef> checks = CheckParser.extract("CREATE TABLE t ("
                + "id INTEGER PRIMARY KEY, "
                + "age INTEGER CONSTRAINT age_ok CHECK (age >= 0), "
                + "score REAL CHECK (score <= 100))");

        assertThat(checks).hasSize(2);
        assertThat(checks.getFirst().name()).isEqualTo("age_ok");
        assertThat(checks.getFirst().expression()).isEqualTo("age >= 0");
        assertThat(checks.get(1).named()).isFalse();
        assertThat(checks.get(1).expression()).isEqualTo("score <= 100");
    }

    @Test
    void ignoresKeywordsThatMerelyLookLikeCheck() {
        List<CheckDef> checks = CheckParser.extract(
                "CREATE TABLE t (id INTEGER, checked INTEGER, note TEXT DEFAULT 'CHECK (x)')");

        assertThat(checks)
                .as("CHECKED 不是 CHECK；字符串里的 'CHECK (x)' 也不是约束")
                .isEmpty();
    }

    @Test
    void keepsParenthesesInsideStringLiterals() {
        List<CheckDef> checks = CheckParser.extract(
                "CREATE TABLE t (id INTEGER, tag TEXT, CHECK (tag <> 'a)b'))");

        assertThat(checks).hasSize(1);
        assertThat(checks.getFirst().expression()).isEqualTo("tag <> 'a)b'");
    }

    // ─────────────────────── 内省 ───────────────────────

    @Test
    void structureExposesCheckConstraints() throws Exception {
        TableStructure people = schema.structure(session, "people");

        assertThat(people.checks()).extracting(CheckDef::name)
                .containsExactly("age_positive", "score_range");
        assertThat(people.checks()).extracting(CheckDef::expression)
                .contains("age >= 0", "score <= 100");
    }

    // ─────────────────────── 重建存活（D3 的正题）───────────────

    @Test
    void checkConstraintsSurviveAColumnChangeOnSqlite() throws Exception {
        TableStructure people = schema.structure(session, "people");
        assertThat(people.checks()).hasSize(2);

        // 改一列 → SQLite 整表重建
        StructurePlan plan = StructureSqlBuilder.alterColumn(session, people, "name",
                new ColumnSpec("name", "TEXT", true, null, null, false, false));
        assertThat(plan.foreignKeysOff()).as("SQLite 改列走整表重建").isTrue();
        assertThat(plan.sql())
                .as("重建语句必须把 CHECK 一起搬过去")
                .contains("CHECK (age >= 0)")
                .contains("CHECK (score <= 100)");

        execute(plan);

        TableStructure after = schema.structure(session, "people");
        assertThat(after.checks()).extracting(CheckDef::name)
                .as("改列之后 CHECK 约束必须还在")
                .containsExactly("age_positive", "score_range");
    }

    @Test
    void theCheckStillBlocksViolatingDataAfterTheRebuild() throws Exception {
        TableStructure people = schema.structure(session, "people");
        execute(StructureSqlBuilder.alterColumn(session, people, "name",
                new ColumnSpec("name", "TEXT", true, null, null, false, false)));

        assertThatThrownBy(() -> {
            try (java.sql.Statement statement = session.connection().createStatement()) {
                statement.execute("INSERT INTO people (id, name, age) VALUES (2, '李四', -1)");
            }
        })
                .as("约束必须在重建之后**仍然生效** —— 只「看得见」不算修好")
                .isInstanceOf(SQLException.class);

        // 合法的数据仍然能写进去（别把约束搬坏了）
        try (java.sql.Statement statement = session.connection().createStatement()) {
            statement.execute("INSERT INTO people (id, name, age) VALUES (3, '王五', 20)");
        }
        assertThat(schema.count(session, "people")).isEqualTo(2);
    }

    @Test
    void createTableSqlCarriesChecks() {
        String sql = StructureSqlBuilder.createTableSqlWithChecks(session, "people",
                List.of(new io.agenticdb.model.ColumnDef("id", io.agenticdb.model.ColumnType.NUMBER,
                        false, true, null, null, null, null, null, null, false, "INTEGER")),
                List.of("id"), List.of(), List.of(),
                List.of(new CheckDef("age_positive", "age >= 0")));

        assertThat(sql).contains("CONSTRAINT \"age_positive\" CHECK (age >= 0)");
    }

    private void execute(StructurePlan plan) throws Exception {
        try (java.sql.Statement statement = session.connection().createStatement()) {
            for (String sql : plan.statements()) {
                statement.execute(sql);
            }
        }
    }
}
