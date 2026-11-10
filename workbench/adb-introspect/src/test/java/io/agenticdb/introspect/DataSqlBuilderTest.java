package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.introspect.FilterGroup;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSqlBuilderTest {

    @TempDir
    Path temp;

    private DbSession session;

    @BeforeEach
    void open() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqlite(DemoDatabase.create(temp.resolve("demo.db"))));
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    @Test
    void pageSqlContainsOnlySafeWhereAndOrderBy() {
        DataQuery query = DataQuery.empty()
                .withSorts(List.of(new SortSpec("name", true), new SortSpec("id", false)))
                .withFilter(FilterCondition.of("email", FilterOperator.CONTAINS, "user0"));

        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "users", query, 200, 400);

        assertThat(sql.text()).isEqualTo(
                "SELECT * FROM \"users\" WHERE \"email\" LIKE ? ESCAPE '!'"
                        + " ORDER BY \"name\" ASC, \"id\" DESC LIMIT 200 OFFSET 400");
        assertThat(sql.params()).containsExactly("%user0%");
    }

    @Test
    void countAndPageExecuteWithBoundParams() throws Exception {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.of("status", FilterOperator.EQ, "PAID"))
                .withSorts(List.of(new SortSpec("amount", false)));

        DataSqlBuilder.Sql count = DataSqlBuilder.count(session, "orders", query);
        RowPage countPage = session.query(count.text(), 1, count.params());
        long expected = ((Number) countPage.rows().getFirst().get("adb_count")).longValue();

        DataSqlBuilder.Sql page = DataSqlBuilder.page(session, "orders", query, 10, 0);
        RowPage rows = session.query(page.text(), 10, page.params());

        assertThat(expected).isEqualTo(125);
        assertThat(rows.rows()).hasSize(10);
        assertThat(rows.rows()).allMatch(row -> "PAID".equals(row.get("status")));
        assertThat(rows.rows()).extracting(row -> row.get("amount"))
                .isSortedAccordingTo((a, b) -> Double.compare(
                        ((Number) b).doubleValue(), ((Number) a).doubleValue()));
    }

    @Test
    void inFilterDistinguishesNullEmptyAndValue() throws Exception {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.in("bio", Arrays.asList(null, "", "Bio for user 1")));

        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "users", query, 10, 0);
        RowPage page = session.query(sql.text(), 50, sql.params());

        assertThat(sql.text()).contains("\"bio\" IN (?, ?) OR \"bio\" IS NULL");
        assertThat(sql.params()).containsExactly("", "Bio for user 1");
        assertThat(page.rows()).extracting(row -> row.get("bio"))
                .contains(null, "Bio for user 1");
    }

    @Test
    void emptyInMeansNoRowsInsteadOfAllRows() {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.in("status", List.of()));

        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "orders", query, 10, 0);

        assertThat(sql.text()).contains("WHERE 1 = 0");
        assertThat(sql.params()).isEmpty();
    }

    @Test
    void likeWildcardsAreEscaped() throws Exception {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.of("name", FilterOperator.CONTAINS, "%_!"));
        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "users", query, 10, 0);

        assertThat(sql.params()).containsExactly("%!%!_!!%");
        assertThat(session.query(sql.text(), 10, sql.params()).rowCount()).isZero();
    }

    @Test
    void distinctValuesHonoursOtherColumnFilters() throws Exception {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.of("active", FilterOperator.EQ, 1));

        DataSqlBuilder.Sql sql = DataSqlBuilder.distinctValues(
                session, "users", "active", query, 1001);
        RowPage page = session.query(sql.text(), 1001, sql.params());

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().get("adb_value")).isEqualTo(1);
        assertThat(((Number) page.rows().getFirst().get("adb_count")).longValue()).isEqualTo(180);
    }

    @Test
    void booleanValueBindsAgainstSQLiteIntegerStorage() throws Exception {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.of("active", FilterOperator.EQ, true));

        DataSqlBuilder.Sql sql = DataSqlBuilder.count(session, "users", query);
        RowPage page = session.query(sql.text(), 1, sql.params());

        assertThat(((Number) page.rows().getFirst().get("adb_count")).longValue()).isEqualTo(180);
    }

    @Test
    void rejectsMoreThanThreeSortLevels() {
        assertThatThrownBy(() -> DataQuery.empty().withSorts(List.of(
                new SortSpec("id", true), new SortSpec("name", true),
                new SortSpec("email", true), new SortSpec("score", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3");
    }

    @Test
    void cycleSortFollowsHeaderClickSemantics() {
        DataQuery query = DataQuery.empty()
                .cycleSort("name", false)
                .cycleSort("name", false)
                .cycleSort("name", false);

        assertThat(query.sorts()).isEmpty();
    }

    @Test
    void orGroupsCombineConditionsWithOr() {
        DataQuery query = new DataQuery(List.of(
                FilterGroup.of(List.of(FilterCondition.of("status", FilterOperator.EQ, "PAID"))),
                FilterGroup.of(List.of(FilterCondition.of("active", FilterOperator.EQ, 1)))),
                List.of());

        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "orders", query, 10, 0);

        assertThat(sql.text()).contains("WHERE (\"status\" = ?) OR (\"active\" = ?)");
        assertThat(sql.params()).containsExactly("PAID", 1);
    }

    @Test
    void andWithinGroupAndOrBetweenGroups() {
        FilterGroup group0 = FilterGroup.of(List.of(
                FilterCondition.of("status", FilterOperator.EQ, "PAID"),
                FilterCondition.of("active", FilterOperator.EQ, 1)));
        FilterGroup group1 = FilterGroup.of(List.of(
                FilterCondition.of("amount", FilterOperator.GT, 0)));
        DataQuery query = new DataQuery(List.of(group0, group1), List.of());

        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "orders", query, 10, 0);

        assertThat(sql.text())
                .contains("WHERE (\"status\" = ? AND \"active\" = ?) OR (\"amount\" > ?)");
    }

    @Test
    void singleGroupStillProducesFlatAnd() {
        // ⭐ B1 向后兼容：单组（默认）等价于旧版平铺 AND，单条件不加括号。
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.of("status", FilterOperator.EQ, "PAID"))
                .withFilter(FilterCondition.of("active", FilterOperator.EQ, 1));

        DataSqlBuilder.Sql sql = DataSqlBuilder.page(session, "orders", query, 10, 0);

        assertThat(sql.text()).isEqualTo(
                "SELECT * FROM \"orders\" WHERE \"status\" = ? AND \"active\" = ? LIMIT 10 OFFSET 0");
        assertThat(sql.params()).containsExactly("PAID", 1);
    }
}
