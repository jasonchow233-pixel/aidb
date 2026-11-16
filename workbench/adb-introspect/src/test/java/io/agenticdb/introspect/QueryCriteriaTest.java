package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCriteriaTest {

    private final DbSession sqlite = FakeDialectSession.of(DialectKind.SQLITE);

    private static QueryCriteria.Criterion and(String col, FilterOperator op, String v) {
        return new QueryCriteria.Criterion(QueryCriteria.Connector.AND, col, op, v, null);
    }

    private static QueryCriteria.Criterion or(String col, FilterOperator op, String v) {
        return new QueryCriteria.Criterion(QueryCriteria.Connector.OR, col, op, v, null);
    }

    @Test
    void emptyRowsYieldNoCondition() {
        DataQuery q = QueryCriteria.empty().toDataQuery();
        assertThat(q.hasFilters()).isFalse();
        assertThat(SelectBuilder.select(sqlite, "t", List.of(), q))
                .isEqualTo("SELECT * FROM \"t\"");
    }

    @Test
    void andRowsStayInOneGroup() {
        QueryCriteria c = new QueryCriteria(List.of(
                and("a", FilterOperator.EQ, "1"),
                and("b", FilterOperator.EQ, "2")));
        assertThat(SelectBuilder.select(sqlite, "t", List.of(), c.toDataQuery()))
                .contains("\"a\" = 1 AND \"b\" = 2");
    }

    @Test
    void orStartsNewGroup() {
        QueryCriteria c = new QueryCriteria(List.of(
                and("a", FilterOperator.EQ, "1"),
                and("b", FilterOperator.EQ, "2"),
                or("c", FilterOperator.EQ, "3")));
        String sql = SelectBuilder.select(sqlite, "t", List.of(), c.toDataQuery());
        assertThat(sql).contains("(\"a\" = 1 AND \"b\" = 2) OR (\"c\" = 3)");
    }

    @Test
    void blankColumnRowsAreIgnored() {
        QueryCriteria c = new QueryCriteria(List.of(
                and("", FilterOperator.EQ, "1"),
                and("a", FilterOperator.EQ, "2")));
        String sql = SelectBuilder.select(sqlite, "t", List.of(), c.toDataQuery());
        assertThat(sql).contains("\"a\" = 2").doesNotContain(" = 1");
    }

    @Test
    void valueTypeInference() {
        assertThat(QueryCriteria.parseValue("42")).isEqualTo(42L);
        assertThat(QueryCriteria.parseValue("-7")).isEqualTo(-7L);
        assertThat(QueryCriteria.parseValue("3.14")).isEqualTo(3.14d);
        assertThat(QueryCriteria.parseValue("true")).isEqualTo(Boolean.TRUE);
        assertThat(QueryCriteria.parseValue("FALSE")).isEqualTo(Boolean.FALSE);
        assertThat(QueryCriteria.parseValue("hello")).isEqualTo("hello");
        assertThat(QueryCriteria.parseValue(" ")).isNull();
    }

    @Test
    void numericValueBecomesUnquotedLiteral() {
        QueryCriteria c = new QueryCriteria(List.of(and("age", FilterOperator.GT, "18")));
        assertThat(SelectBuilder.select(sqlite, "t", List.of(), c.toDataQuery()))
                .contains("\"age\" > 18");
    }

    @Test
    void inSplitsCommaSeparatedValues() {
        QueryCriteria c = new QueryCriteria(List.of(and("status", FilterOperator.IN, "A,B'X,9")));
        assertThat(SelectBuilder.select(sqlite, "t", List.of(), c.toDataQuery()))
                .contains("\"status\" IN ('A', 'B''X', 9)");
    }

    @Test
    void betweenUsesTwoValueBoxes() {
        QueryCriteria.Criterion row = new QueryCriteria.Criterion(
                QueryCriteria.Connector.AND, "age", FilterOperator.BETWEEN, "18", "65");
        QueryCriteria c = new QueryCriteria(List.of(row));
        assertThat(SelectBuilder.select(sqlite, "t", List.of(), c.toDataQuery()))
                .contains("\"age\" BETWEEN 18 AND 65");
    }
}
