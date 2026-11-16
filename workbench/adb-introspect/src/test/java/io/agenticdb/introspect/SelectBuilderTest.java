package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询构造器的纯逻辑门禁：结构化条件 → 可直接执行的字面量 SELECT。
 *
 * <p>全部走 {@link FakeDialectSession}（只提供方言、不连库）—— 生成的 SQL 文本正确
 * 就够，不需要真跑一条 SELECT。方言差异按各实现真实引用规则断言。
 */
class SelectBuilderTest {

    private final DbSession sqlite = FakeDialectSession.of(DialectKind.SQLITE);

    @Test
    void noConditionSelectsStar() {
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), null))
                .isEqualTo("SELECT * FROM \"users\"");
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), DataQuery.empty()))
                .isEqualTo("SELECT * FROM \"users\"");
    }

    @Test
    void projectsOnlyChosenColumnsWithDialectQuotes() {
        assertThat(SelectBuilder.select(sqlite, "users", List.of("id", "name"), null))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"users\"");
    }

    @Test
    void dialectQuotingDiffersPerKind() {
        assertThat(SelectBuilder.select(FakeDialectSession.of(DialectKind.MYSQL),
                "order", List.of("select"), null))
                .isEqualTo("SELECT `select` FROM `order`");
        assertThat(SelectBuilder.select(FakeDialectSession.of(DialectKind.SQLSERVER),
                "order", List.of("select"), null))
                .isEqualTo("SELECT [select] FROM [order]");
        assertThat(SelectBuilder.select(FakeDialectSession.of(DialectKind.POSTGRESQL),
                "users", List.of(), null))
                .isEqualTo("SELECT * FROM \"users\"");
    }

    @Test
    void stringLiteralEscapesSingleQuotes() {
        DataQuery q = DataQuery.empty().withFilter(FilterCondition.of("name", FilterOperator.EQ, "O'Brien"));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), q))
                .isEqualTo("SELECT * FROM \"users\" WHERE \"name\" = 'O''Brien'");
    }

    @Test
    void sqlInjectionValueIsNeutralizedAsLiteral() {
        DataQuery q = DataQuery.empty().withFilter(
                FilterCondition.of("name", FilterOperator.EQ, "' OR 1=1 --"));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), q))
                .isEqualTo("SELECT * FROM \"users\" WHERE \"name\" = ''' OR 1=1 --'");
    }

    @Test
    void numericBooleanAndNullLiterals() {
        DataQuery q = DataQuery.empty()
                .withFilter(FilterCondition.of("age", FilterOperator.GT, 18))
                .withFilter(FilterCondition.of("active", FilterOperator.EQ, true));
        String sql = SelectBuilder.select(sqlite, "users", List.of(), q);
        assertThat(sql).contains("\"age\" > 18").contains("\"active\" = true");

        DataQuery nullEq = DataQuery.empty().withFilter(FilterCondition.of("name", FilterOperator.EQ, null));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), nullEq))
                .contains("\"name\" IS NULL");
        DataQuery nullNotEq = DataQuery.empty().withFilter(FilterCondition.of("name", FilterOperator.NOT_EQ, null));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), nullNotEq))
                .contains("\"name\" IS NOT NULL");
    }

    @Test
    void betweenAndInProduceValidLiterals() {
        DataQuery between = DataQuery.empty().withFilter(FilterCondition.between("age", 18, 65));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), between))
                .contains("\"age\" BETWEEN 18 AND 65");

        DataQuery in = DataQuery.empty().withFilter(
                FilterCondition.in("status", List.of("A", "B'X")));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), in))
                .contains("\"status\" IN ('A', 'B''X')");
    }

    @Test
    void likePatternsEscapeWildcards() {
        DataQuery contains = DataQuery.empty().withFilter(
                FilterCondition.of("name", FilterOperator.CONTAINS, "50%_!x"));
        assertThat(SelectBuilder.select(sqlite, "users", List.of(), contains))
                .contains("\"name\" LIKE '%50!%!_!!x%' ESCAPE '!'");
    }

    @Test
    void nullValueInSomeOperatorsYieldsFalse() {
        for (FilterOperator op : List.of(FilterOperator.CONTAINS, FilterOperator.GT,
                FilterOperator.BETWEEN)) {
            DataQuery q = DataQuery.empty().withFilter(FilterCondition.of("x", op, null));
            assertThat(SelectBuilder.select(sqlite, "t", List.of(), q))
                    .as(op.name()).contains("1 = 0");
        }
    }

    @Test
    void andWithinGroupOrAcrossGroups() {
        FilterGroup g1 = FilterGroup.of(List.of(
                FilterCondition.of("a", FilterOperator.EQ, 1),
                FilterCondition.of("b", FilterOperator.EQ, 2)));
        FilterGroup g2 = FilterGroup.of(List.of(FilterCondition.of("c", FilterOperator.EQ, 3)));
        DataQuery q = new DataQuery(List.of(g1, g2), List.of());
        String sql = SelectBuilder.select(sqlite, "t", List.of(), q);
        assertThat(sql).contains("(\"a\" = 1 AND \"b\" = 2) OR (\"c\" = 3)");
    }

    @Test
    void sortsAreEmittedInOrder() {
        DataQuery q = DataQuery.empty().withSorts(List.of(
                new SortSpec("name", true), new SortSpec("age", false)));
        assertThat(SelectBuilder.select(sqlite, "t", List.of(), q))
                .isEqualTo("SELECT * FROM \"t\" ORDER BY \"name\" ASC, \"age\" DESC");
    }

    @Test
    void blobValueIsRejectedFailClosed() {
        DataQuery q = DataQuery.empty().withFilter(
                FilterCondition.of("blob_col", FilterOperator.EQ, new byte[]{1, 2, 3}));
        assertThatThrownBy(() -> SelectBuilder.select(sqlite, "t", List.of(), q))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Binary value");
    }

    @Test
    void blankIdentifiersAreRejected() {
        assertThatThrownBy(() -> SelectBuilder.select(sqlite, "", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SelectBuilder.select(sqlite, "t", List.of("  "), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatesOnlySelectNeverDmlOrLimit() {
        DataQuery q = DataQuery.empty().withFilter(FilterCondition.of("a", FilterOperator.EQ, 1));
        String sql = SelectBuilder.select(sqlite, "t", List.of("a"), q);
        assertThat(sql).startsWith("SELECT ");
        assertThat(sql.toUpperCase()).doesNotContain("DELETE", "UPDATE", "DROP", "INSERT", " LIMIT ", " OFFSET ");
    }

    @Test
    void literalHelperDirectly() {
        assertThat(SelectBuilder.literal(null)).isEqualTo("NULL");
        assertThat(SelectBuilder.literal(42)).isEqualTo("42");
        assertThat(SelectBuilder.literal(false)).isEqualTo("false");
        assertThat(SelectBuilder.literal("a'b")).isEqualTo("'a''b'");
    }
}
