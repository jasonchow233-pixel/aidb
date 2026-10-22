package io.agenticdb.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPaginatorTest {

    @Test
    void selectIsPaginable() {
        assertThat(QueryPaginator.isPaginable("SELECT * FROM users")).isTrue();
        assertThat(QueryPaginator.isPaginable("  select 1 as n")).isTrue();
        assertThat(QueryPaginator.isPaginable("WITH t AS (SELECT 1) SELECT * FROM t"))
                .isTrue();
    }

    @Test
    void nonSelectIsNotPaginable() {
        assertThat(QueryPaginator.isPaginable("INSERT INTO users VALUES (1)")).isFalse();
        assertThat(QueryPaginator.isPaginable("UPDATE users SET name = 'x'")).isFalse();
        assertThat(QueryPaginator.isPaginable("DELETE FROM users")).isFalse();
        assertThat(QueryPaginator.isPaginable("CREATE TABLE t (id INT)")).isFalse();
    }

    @Test
    void forUpdateAndIntoAreNotPaginable() {
        assertThat(QueryPaginator.isPaginable("SELECT * FROM users FOR UPDATE")).isFalse();
        assertThat(QueryPaginator.isPaginable("SELECT * INTO t FROM users")).isFalse();
    }

    @Test
    void emptyAndMultiStatementAreNotPaginable() {
        assertThat(QueryPaginator.isPaginable(null)).isFalse();
        assertThat(QueryPaginator.isPaginable("")).isFalse();
        assertThat(QueryPaginator.isPaginable("SELECT 1; SELECT 2;")).isFalse();
    }

    @Test
    void trailingSemicolonIsStrippedBeforePaging() {
        // ⭐ SqlSplitter 默认保留语句末尾的分号，分页判定与改写都必须容忍它。
        assertThat(QueryPaginator.isPaginable("SELECT * FROM users;")).isTrue();
        assertThat(QueryPaginator.isPaginable("  select 1 as n ; ")).isTrue();

        QueryPaginator.PagedSql page = QueryPaginator.pageSql(
                "SELECT * FROM users;", List.of(), 200, 0);
        assertThat(page.sql()).isEqualTo(
                "SELECT * FROM (SELECT * FROM users) AS _p LIMIT ? OFFSET ?");

        QueryPaginator.PagedSql count = QueryPaginator.countSql("SELECT * FROM users;", List.of());
        assertThat(count.sql()).isEqualTo(
                "SELECT COUNT(*) AS adb_count FROM (SELECT * FROM users) AS _p");
    }

    @Test
    void countSqlWrapsOriginalAndPassesParams() {
        QueryPaginator.PagedSql sql = QueryPaginator.countSql(
                "SELECT * FROM users WHERE id = ?", List.of(7));
        assertThat(sql.sql()).isEqualTo(
                "SELECT COUNT(*) AS adb_count FROM (SELECT * FROM users WHERE id = ?) AS _p");
        assertThat(sql.params()).containsExactly(7);
    }

    @Test
    void pageSqlAppendsLimitOffsetAfterInnerParams() {
        QueryPaginator.PagedSql sql = QueryPaginator.pageSql(
                "SELECT * FROM users WHERE id > ?", List.of(3), 200, 400);
        assertThat(sql.sql()).isEqualTo(
                "SELECT * FROM (SELECT * FROM users WHERE id > ?) AS _p LIMIT ? OFFSET ?");
        // 内层参数在前，limit / offset 在后，与驱动按序绑定一致
        assertThat(sql.params()).containsExactly(3, 200, 400);
    }

    @Test
    void pageSqlClampsLimitAndOffset() {
        QueryPaginator.PagedSql huge = QueryPaginator.pageSql("SELECT 1", List.of(), 999_999, -50);
        assertThat(huge.params()).containsExactly(1000, 0);
    }
}
