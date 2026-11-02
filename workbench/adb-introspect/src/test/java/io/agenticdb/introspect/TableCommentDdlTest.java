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
 * ⭐ B5-S4 门禁：表注释**读得回来、改得了、清得掉**。
 *
 * <p>以前表注释是「<b>只写不读</b>」：建表时能填，建完就再也看不见
 * （{@code TableStructure} 里连字段都没有）—— 填了等于没填。
 *
 * <p>三个方言的差异逐字锁死：
 * <ul>
 *   <li>PostgreSQL：{@code COMMENT ON TABLE … IS '…'}，<b>清空是 {@code IS NULL}</b>
 *       （写成 {@code IS ''} 等于没清）；</li>
 *   <li>MySQL：{@code ALTER TABLE … COMMENT = '…'}，清空是空串；</li>
 *   <li>SQLite：<b>没有表注释</b> → 明确报错 + UI 禁用按钮（不假装有）。</li>
 * </ul>
 */
class TableCommentDdlTest {

    @Test
    void postgresSetsAndClearsTheComment() {
        StructurePlan set = StructureSqlBuilder.setTableComment(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(), "订单表");

        assertThat(set.statements()).containsExactly(
                "COMMENT ON TABLE \"orders\" IS '订单表'");

        StructurePlan clear = StructureSqlBuilder.setTableComment(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(), "  ");

        assertThat(clear.statements())
                .as("⭐ PostgreSQL 清注释必须是 IS NULL —— 写成 IS '' 等于没清")
                .containsExactly("COMMENT ON TABLE \"orders\" IS NULL");
    }

    @Test
    void mySqlSetsAndClearsTheComment() {
        StructurePlan set = StructureSqlBuilder.setTableComment(
                FakeDialectSession.of(DialectKind.MYSQL), table(), "订单表");

        assertThat(set.statements()).containsExactly(
                "ALTER TABLE `orders` COMMENT = '订单表'");

        StructurePlan clear = StructureSqlBuilder.setTableComment(
                FakeDialectSession.of(DialectKind.MYSQL), table(), "");

        assertThat(clear.statements()).containsExactly(
                "ALTER TABLE `orders` COMMENT = ''");
    }

    @Test
    void sqliteRefusesTableCommentsInsteadOfFailingSilently() {
        assertThatThrownBy(() -> StructureSqlBuilder.setTableComment(
                FakeDialectSession.of(DialectKind.SQLITE), table(), "订单表"))
                .as("SQLite 没有表注释 —— 要**明确拒绝**，不能生成一条会报错的 SQL")
                .isInstanceOf(ColumnProblem.class)
                .hasFieldOrPropertyWithValue("key", "table.error.noCommentSupport");
    }

    @Test
    void quotesInsideTheCommentAreEscaped() {
        StructurePlan plan = StructureSqlBuilder.setTableComment(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(), "it's 表");

        assertThat(plan.statements())
                .as("注释里的单引号必须转义，否则 SQL 直接坏掉")
                .containsExactly("COMMENT ON TABLE \"orders\" IS 'it''s 表'");
    }

    @Test
    void createTableStillCarriesTheComment() {
        // 建表时填的表注释要真的下发（回退的能力不能被这次改动弄丢）。
        StructurePlan plan = StructureSqlBuilder.createTable(
                FakeDialectSession.of(DialectKind.POSTGRESQL), "orders",
                List.of(new io.agenticdb.model.ColumnSpec("id", "INTEGER", false, null,
                        null, false, true)),
                "订单表", List.of());

        assertThat(plan.statements()).contains("COMMENT ON TABLE \"orders\" IS '订单表'");
    }

    private static TableStructure table() {
        List<ColumnDef> columns = List.of(new ColumnDef("id", ColumnType.NUMBER,
                false, true, null, null, null, null, null, null, false, "INTEGER"));
        return new TableStructure("orders", false, columns, List.of("id"),
                List.of(), List.of(), List.of(), List.of(), "", "");
    }
}
