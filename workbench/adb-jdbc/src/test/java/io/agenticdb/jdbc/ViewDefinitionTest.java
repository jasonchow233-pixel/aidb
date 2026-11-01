package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ R42 N28-a：从各方言交回的视图 DDL 里取<b>查询体</b>。
 *
 * <p>设计器预填靠这一步。切错位置的后果不是报错而是<b>静默建出一个怪视图</b>：
 * 把整段 DDL 当正文，拼出来就是 {@code CREATE VIEW x AS CREATE OR REPLACE VIEW x AS SELECT …}。
 * 所以六个真实形状逐字钉住，认不出来的形态必须返回 {@code null} 让界面如实说。
 */
class ViewDefinitionTest {

    @Test
    void mysqlShowCreateViewHeaderIsDropped() {
        // SHOW CREATE VIEW 的头段带 ALGORITHM / DEFINER / SQL SECURITY，全都在 VIEW 之前。
        assertThat(ViewDefinition.selectBody(
                "CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`localhost` "
                        + "SQL SECURITY DEFINER VIEW `v_users` AS "
                        + "select `u`.`id` AS `id`,`u`.`name` AS `name` from `users` `u`"))
                .isEqualTo("select `u`.`id` AS `id`,`u`.`name` AS `name` from `users` `u`");
    }

    @Test
    void theMysqlVersionMarkerAtTheEndIsNotPartOfTheBody() {
        assertThat(ViewDefinition.selectBody(
                "CREATE VIEW `v` AS select 1 AS `one`/*!*/;"))
                .isEqualTo("select 1 AS `one`");
    }

    @Test
    void postgresAndSqliteAndOracleShapesSplitAfterTheViewName() {
        assertThat(ViewDefinition.selectBody(
                "CREATE OR REPLACE VIEW \"v_users\" AS\n"
                        + " SELECT users.id,\n"
                        + "    users.name\n"
                        + "   FROM users\n"
                        + "  WHERE (users.active IS TRUE);"))
                .isEqualTo("SELECT users.id,\n"
                        + "    users.name\n"
                        + "   FROM users\n"
                        + "  WHERE (users.active IS TRUE)");
        assertThat(ViewDefinition.selectBody("CREATE VIEW \"v\" AS SELECT 1"))
                .isEqualTo("SELECT 1");
        // Oracle 12.2+ 给完整 DDL，带列别名表 —— 分界仍然是 VIEW 之后的第一个 AS。
        assertThat(ViewDefinition.selectBody(
                "CREATE OR REPLACE FORCE VIEW \"SCOTT\".\"V\" (\"A\",\"B\") AS SELECT 1, 2 FROM dual"))
                .isEqualTo("SELECT 1, 2 FROM dual");
    }

    @Test
    void sqlServerShapeKeepsTheSchemaQualifiedName() {
        assertThat(ViewDefinition.selectBody(
                "CREATE VIEW [dbo].[v_users] AS SELECT [id] FROM [dbo].[users]"))
                .isEqualTo("SELECT [id] FROM [dbo].[users]");
    }

    @Test
    void aBareSelectBodyIsReturnedAsIs() {
        // Oracle 12.1 以前、以及部分驱动直接给查询体：没有 VIEW 关键字，正文就是正文。
        assertThat(ViewDefinition.selectBody("SELECT 1 AS one"))
                .isEqualTo("SELECT 1 AS one");
        assertThat(ViewDefinition.selectBody("  SELECT id FROM t  "))
                .isEqualTo("SELECT id FROM t");
        // 表名就叫 view：裸查询体直接收下，不去找那个根本不存在的 VIEW 关键字分界。
        assertThat(ViewDefinition.selectBody("SELECT * FROM view"))
                .isEqualTo("SELECT * FROM view");
    }

    @Test
    void shapesThatCannotBeSplitReturnNullInsteadOfGuessing() {
        // 有 CREATE 却没有 VIEW…AS 分界：认不出来，不许把整段 DDL 当查询体交回去。
        assertThat(ViewDefinition.selectBody("CREATE TABLE t (id INT)")).isNull();
        assertThat(ViewDefinition.selectBody("CREATE VIEW v")).isNull();
        assertThat(ViewDefinition.selectBody(null)).isNull();
        assertThat(ViewDefinition.selectBody("   ")).isNull();
        assertThat(ViewDefinition.selectBody("CREATE VIEW v AS")).isNull();
    }

    @Test
    void theWordViewInsideTheBodyDoesNotMoveTheSplitPoint() {
        // 对象名就叫 view（反引号里）—— 分界取的是 VIEW 关键字之后的第一个 AS，
        // 也就是头段那一次，不能被正文里的同名对象带偏。
        assertThat(ViewDefinition.selectBody(
                "CREATE VIEW `v` AS select `x` AS `a` from `view`"))
                .isEqualTo("select `x` AS `a` from `view`");
    }

    @Test
    void ascAndOtherWordsContainingAsAreNotTheSeparator() {
        assertThat(ViewDefinition.selectBody(
                "CREATE VIEW `v` AS select `id` from `t` ORDER BY `id` ASC"))
                .isEqualTo("select `id` from `t` ORDER BY `id` ASC");
    }
}
