package io.agenticdb.introspect;

import io.agenticdb.jdbc.ColumnProblem;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.ViewReplace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ R42 N28-a「视图生命周期」的 SQL 门禁。
 *
 * <p>与 {@link TableLifecycleSqlTest} 同一套思路：<b>不需要真数据库</b>，
 * 把六种方言生成的建 / 重建 / 删视图 SQL 逐字锁死。视图这一层最典型的
 * 坑就是「MariaDB 有 {@code CREATE OR REPLACE VIEW}，MySQL 没有」——
 * 同源两个方言差一条语法，写死在 UI 里早晚只在一边上对。
 *
 * <p>⚠️ 校验失败断言的是<b>键名</b>而不是中文句子：模型层不产文案（D-9 的方向），
 * 渲染由界面做。界面侧的翻译对账在 {@code NewViewDialogTest}。
 */
class ViewLifecycleSqlTest {

    private static final String BODY = "SELECT id, name FROM users";

    // ─────────────────────────── 建视图 ───────────────────────────

    @Test
    void createViewQuotesTheNamePerDialectAndKeepsTheBodyVerbatim() {
        assertThat(view(DialectKind.MYSQL, false)).containsExactly(
                "CREATE VIEW `active_users` AS\n" + BODY);
        assertThat(view(DialectKind.MARIADB, false)).containsExactly(
                "CREATE VIEW `active_users` AS\n" + BODY);
        assertThat(view(DialectKind.POSTGRESQL, false)).containsExactly(
                "CREATE VIEW \"active_users\" AS\n" + BODY);
        assertThat(view(DialectKind.SQLITE, false)).containsExactly(
                "CREATE VIEW \"active_users\" AS\n" + BODY);
        assertThat(view(DialectKind.SQLSERVER, false)).containsExactly(
                "CREATE VIEW [active_users] AS\n" + BODY);
        assertThat(view(DialectKind.ORACLE, false)).containsExactly(
                "CREATE VIEW \"active_users\" AS\n" + BODY);
    }

    @Test
    void theRedefineVocabularyMatchesWhatEachServerActuallyAccepts() {
        // MySQL 8.0 没有 CREATE OR REPLACE VIEW（那是 MariaDB 的分支特性）。
        assertThat(DialectKind.MYSQL.dialect().viewReplace())
                .isEqualTo(ViewReplace.DROP_THEN_CREATE);
        assertThat(DialectKind.MARIADB.dialect().viewReplace())
                .isEqualTo(ViewReplace.OR_REPLACE);
        assertThat(DialectKind.POSTGRESQL.dialect().viewReplace())
                .isEqualTo(ViewReplace.OR_REPLACE);
        assertThat(DialectKind.ORACLE.dialect().viewReplace())
                .isEqualTo(ViewReplace.OR_REPLACE);
        assertThat(DialectKind.SQLSERVER.dialect().viewReplace())
                .isEqualTo(ViewReplace.OR_ALTER);
        assertThat(DialectKind.SQLITE.dialect().viewReplace())
                .isEqualTo(ViewReplace.DROP_THEN_CREATE);
    }

    @Test
    void replacingAnExistingViewUsesOneStatementWhereTheDialectHasIt() {
        assertThat(view(DialectKind.MARIADB, true)).containsExactly(
                "CREATE OR REPLACE VIEW `active_users` AS\n" + BODY);
        assertThat(view(DialectKind.POSTGRESQL, true)).containsExactly(
                "CREATE OR REPLACE VIEW \"active_users\" AS\n" + BODY);
        assertThat(view(DialectKind.ORACLE, true)).containsExactly(
                "CREATE OR REPLACE VIEW \"active_users\" AS\n" + BODY);
        assertThat(view(DialectKind.SQLSERVER, true)).containsExactly(
                "CREATE OR ALTER VIEW [active_users] AS\n" + BODY);
    }

    @Test
    void mysqlAndSqliteRedefineIsTwoStatementsBecauseNeitherHasOrReplace() {
        // ⭐ 预览里看到两句，库里就跑两句 —— 单句形式不存在时不能假装只有一句。
        assertThat(view(DialectKind.MYSQL, true)).containsExactly(
                "DROP VIEW `active_users`",
                "CREATE VIEW `active_users` AS\n" + BODY);
        assertThat(view(DialectKind.SQLITE, true)).containsExactly(
                "DROP VIEW \"active_users\"",
                "CREATE VIEW \"active_users\" AS\n" + BODY);
    }

    @Test
    void dropViewQuotesPerDialect() {
        assertThat(StructureSqlBuilder.dropViewStatements(
                FakeDialectSession.of(DialectKind.MYSQL), "active_users"))
                .containsExactly("DROP VIEW `active_users`");
        assertThat(StructureSqlBuilder.dropViewStatements(
                FakeDialectSession.of(DialectKind.SQLITE), "active_users"))
                .containsExactly("DROP VIEW \"active_users\"");
        assertThat(StructureSqlBuilder.dropViewStatements(
                FakeDialectSession.of(DialectKind.SQLSERVER), "active_users"))
                .containsExactly("DROP VIEW [active_users]");
    }

    // ─────────────────────────── 正文形态 ───────────────────────────

    @Test
    void trailingSemicolonAndTheMySQLVersionMarkerAreStripped() {
        assertThat(view(DialectKind.MYSQL, BODY + ";")).containsExactly(
                "CREATE VIEW `active_users` AS\n" + BODY);
        // SHOW CREATE VIEW 交回的正文尾巴上是 /*!*/; —— 留着它拼进 DDL 会多一个注释结束符。
        assertThat(StructureSqlBuilder.normalizeSelectBody(BODY + "/*!*/;")).isEqualTo(BODY);
    }

    @Test
    void aBodyThatIsNotASingleQueryIsRefusedWithAKey() {
        assertThat(keyOf(null)).isEqualTo("view.error.emptySelect");
        assertThat(keyOf("   ")).isEqualTo("view.error.emptySelect");
        assertThat(keyOf("SELECT 1; DROP TABLE users")).isEqualTo("view.error.multiStatement");
        assertThat(keyOf("DELETE FROM users")).isEqualTo("view.error.notSelect");
        assertThat(keyOf("CREATE VIEW x AS SELECT 1")).isEqualTo("view.error.fullDdl");
        // 组合查询以左括号起头，同样是「一条查询」。
        assertThat(StructureSqlBuilder.normalizeSelectBody("(SELECT 1) UNION (SELECT 2)"))
                .isEqualTo("(SELECT 1) UNION (SELECT 2)");
        assertThat(StructureSqlBuilder.normalizeSelectBody("WITH t AS (SELECT 1) SELECT * FROM t"))
                .isEqualTo("WITH t AS (SELECT 1) SELECT * FROM t");
    }

    @Test
    void theRefusedBodyNamesTheWordItFound() {
        ColumnProblem problem = StructureSqlBuilder.viewSelectProblem("UPDATE users SET a = 1");
        assertThat(problem).isNotNull();
        assertThat(problem.key()).isEqualTo("view.error.notSelect");
        assertThat(problem.args()).containsExactly("UPDATE");
    }

    // ─────────────────────────── 名字 ───────────────────────────

    @Test
    void viewNameUsesTheSameShapeRuleAsTableName() {
        assertThat(StructureSqlBuilder.viewNameProblem("  ", List.of()).key())
                .isEqualTo("view.error.emptyName");
        assertThat(StructureSqlBuilder.viewNameProblem("2 active", List.of()).key())
                .isEqualTo("view.error.nameShape");
        assertThat(StructureSqlBuilder.viewNameProblem("v_users", List.of())).isNull();
    }

    @Test
    void aNameAlreadyTakenByATableOrAViewIsReportedWithTheWinner() {
        // 表名与视图名共用一个命名空间 —— 只对照视图清单会漏掉「和表撞名」。
        ColumnProblem problem = StructureSqlBuilder.viewNameProblem(
                "users", List.of("orders", "USERS"));
        assertThat(problem.key()).isEqualTo("view.error.nameTaken");
        assertThat(problem.args()).containsExactly("USERS");
    }

    @Test
    void anIllegalBodyNeverReachesTheStatementList() {
        assertThatThrownBy(() -> view(DialectKind.SQLITE, "SELECT 1; SELECT 2"))
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "view.error.multiStatement");
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private static List<String> view(DialectKind kind, boolean existing) {
        return StructureSqlBuilder.viewStatements(
                FakeDialectSession.of(kind), "active_users", BODY, existing);
    }

    private static List<String> view(DialectKind kind, String body) {
        return StructureSqlBuilder.viewStatements(
                FakeDialectSession.of(kind), "active_users", body, false);
    }

    private static String keyOf(String body) {
        ColumnProblem problem = StructureSqlBuilder.viewSelectProblem(body);
        return problem == null ? null : problem.key();
    }
}
