package io.agenticdb.introspect;

import io.agenticdb.jdbc.DialectKind;
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

        var page = QueryPaginator.pageSql(
                DialectKind.SQLITE.dialect(), "SELECT * FROM users;", List.of(), 200, 0);
        assertThat(page.sql()).isEqualTo(
                "SELECT * FROM (SELECT * FROM users) AS _p LIMIT ? OFFSET ?");

        var count = QueryPaginator.countSql(
                DialectKind.SQLITE.dialect(), "SELECT * FROM users;", List.of());
        assertThat(count.sql()).isEqualTo(
                "SELECT COUNT(*) AS adb_count FROM (SELECT * FROM users) AS _p");
    }

    @Test
    void countSqlWrapsOriginalAndPassesParams() {
        var sql = QueryPaginator.countSql(DialectKind.SQLITE.dialect(),
                "SELECT * FROM users WHERE id = ?", List.of(7));
        assertThat(sql.sql()).isEqualTo(
                "SELECT COUNT(*) AS adb_count FROM (SELECT * FROM users WHERE id = ?) AS _p");
        assertThat(sql.params()).containsExactly(7);
    }

    @Test
    void pageSqlAppendsLimitOffsetAfterInnerParams() {
        var sql = QueryPaginator.pageSql(DialectKind.SQLITE.dialect(),
                "SELECT * FROM users WHERE id > ?", List.of(3), 200, 400);
        assertThat(sql.sql()).isEqualTo(
                "SELECT * FROM (SELECT * FROM users WHERE id > ?) AS _p LIMIT ? OFFSET ?");
        // 内层参数在前，limit / offset 在后，与驱动按序绑定一致
        assertThat(sql.params()).containsExactly(3, 200, 400);
    }

    @Test
    void pageSqlClampsLimitAndOffset() {
        var huge = QueryPaginator.pageSql(DialectKind.SQLITE.dialect(),
                "SELECT 1", List.of(), 999_999, -50);
        assertThat(huge.params()).containsExactly(1000, 0);
    }

    @Test
    void limitFamilyDialectsShareTheSamePagingText() {
        // ⭐ D-2：分页写法归方言层，但 LIMIT 族（SQLite / PG / MySQL / MariaDB）
        // 输出必须与抽象前<b>逐字节相同</b> —— 这条是「重构不改行为」的锚。
        // OFFSET/FETCH 族（SQL Server / Oracle）另有自己的用例，所以这里不遍历全枚举。
        for (DialectKind kind : List.of(DialectKind.SQLITE, DialectKind.POSTGRESQL,
                DialectKind.MYSQL, DialectKind.MARIADB)) {
            var page = QueryPaginator.pageSql(kind.dialect(),
                    "SELECT * FROM t", List.of(), 50, 100);
            assertThat(page.sql())
                    .as("%s 的分页句", kind)
                    .isEqualTo("SELECT * FROM (SELECT * FROM t) AS _p LIMIT ? OFFSET ?");
            assertThat(page.params()).containsExactly(50, 100);
            assertThat(QueryPaginator.countSql(kind.dialect(), "SELECT * FROM t", List.of()).sql())
                    .as("%s 的计数句", kind)
                    .isEqualTo("SELECT COUNT(*) AS adb_count FROM (SELECT * FROM t) AS _p");
        }
    }

    @Test
    void offsetFetchFamilyBindsOffsetBeforeLimit() {
        // ⭐ D-3：OFFSET/FETCH 族两个数字的顺序与 LIMIT 族相反（先 offset、后行数），
        //    绑错了就是「翻到第 50 页却拿到 100 行」这种不报错的错。
        var sqlServer = QueryPaginator.pageSql(DialectKind.SQLSERVER.dialect(),
                "SELECT * FROM t", List.of(), 50, 100);
        assertThat(sqlServer.sql())
                .isEqualTo("SELECT * FROM t ORDER BY (SELECT NULL)"
                        + " OFFSET 100 ROWS FETCH NEXT 50 ROWS ONLY");
        assertThat(sqlServer.params()).containsExactly(100, 50);

        var oracle = QueryPaginator.pageSql(DialectKind.ORACLE.dialect(),
                "SELECT * FROM t", List.of(), 50, 100);
        assertThat(oracle.sql())
                .isEqualTo("SELECT * FROM (SELECT * FROM t) _p"
                        + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(oracle.params()).containsExactly(100, 50);
    }
}
