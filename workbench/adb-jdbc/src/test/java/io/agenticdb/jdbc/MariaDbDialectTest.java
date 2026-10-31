package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-1：MariaDB 方言的<b>差异点</b>门禁。
 *
 * <p>MariaDB 走的是「继承 MySQL + 只 override 真实差异」的路线，
 * 所以这里的断言分两半：
 * <ul>
 *   <li><b>不一样的</b>：驱动类名、URL 前缀与参数族、排序规则候选
 *       —— 这些是「照抄 MySQL 就会错」的地方；</li>
 *   <li><b>一样的</b>：SQL 文本与 MySQL 逐字一致（反引号、{@code RENAME TABLE}、
 *       {@code DELETE FROM} 清空、建表带 {@code DEFAULT CHARSET=utf8mb4}）
 *       —— 钉住「继承不是省事，是确认同源」。</li>
 * </ul>
 */
class MariaDbDialectTest {

    private final MariaDbDialect dialect = new MariaDbDialect();
    private final MySqlDialect mysql = new MySqlDialect();

    @Test
    void itIsItsOwnKindNotMysql() {
        assertThat(dialect.kind()).isEqualTo(DialectKind.MARIADB);
        assertThat(dialect.displayName()).isEqualTo("MariaDB");
        assertThat(DialectKind.of("mariadb")).isEqualTo(DialectKind.MARIADB);
        assertThat(DialectKind.of("MariaDB")).isEqualTo(DialectKind.MARIADB);
    }

    @Test
    void driverIsTheMariaDbOne() {
        assertThat(dialect.driverClassName()).isEqualTo("org.mariadb.jdbc.Driver");
        assertThat(mysql.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(dialect.defaultPort()).isEqualTo(3306);
        assertThat(dialect.pooled()).isTrue();
        assertThat(dialect.usesFilePath()).isFalse();
    }

    @Test
    void collationPresetsDropTheMysql8OnlyFamily() {
        assertThat(dialect.columnCollationPresets())
                .as("utf8mb4_0900_* 是 MySQL 8 的 UCA-09.00 实现，MariaDB 没有这一族 —— "
                        + "递进下拉等于递一个「选了必报错」的选项")
                .noneMatch(collation -> collation.startsWith("utf8mb4_0900_"));
        assertThat(dialect.columnCollationPresets()).contains("utf8mb4_general_ci");
        assertThat(mysql.columnCollationPresets())
                .as("对照：MySQL 侧保留 0900 族（差异必须两侧都断言，单侧断言挡不住「把 override 删了」）")
                .anyMatch(collation -> collation.startsWith("utf8mb4_0900_"));
    }

    @Test
    void sharedSqlTextStaysIdenticalToMysql() {
        assertThat(dialect.renameTableSql("a", "b")).isEqualTo(mysql.renameTableSql("a", "b"));
        assertThat(dialect.truncateTableSql("users")).isEqualTo(mysql.truncateTableSql("users"));
        assertThat(dialect.createTableOptions()).isEqualTo(mysql.createTableOptions());
        assertThat(dialect.dropIndexSql("users", "ix_email"))
                .isEqualTo(mysql.dropIndexSql("users", "ix_email"));
        assertThat(dialect.addForeignKeySql("orders", "fk_orders_user", List.of("user_id"),
                        "users", List.of("id"), "CASCADE", "RESTRICT"))
                .isEqualTo(mysql.addForeignKeySql("orders", "fk_orders_user", List.of("user_id"),
                        "users", List.of("id"), "CASCADE", "RESTRICT"));
        ColumnSpec column = new ColumnSpec("email", "TEXT", true, null, "邮箱", false, false);
        assertThat(dialect.addColumnSql("users", column))
                .isEqualTo(mysql.addColumnSql("users", column));
    }

    @Test
    void capabilityFlagsMatchMysql() {
        assertThat(dialect.supportsColumnComment()).isEqualTo(mysql.supportsColumnComment());
        assertThat(dialect.supportsColumnCharset()).isEqualTo(mysql.supportsColumnCharset());
        assertThat(dialect.supportsTableComment()).isEqualTo(mysql.supportsTableComment());
        assertThat(dialect.readsDdlFromDatabase()).isEqualTo(mysql.readsDdlFromDatabase());
        assertThat(dialect.switchesCatalogByReconnect()).isEqualTo(mysql.switchesCatalogByReconnect());
        assertThat(dialect.eventsListSql()).isEqualTo(mysql.eventsListSql());
        assertThat(dialect.checksSql("users")).isEqualTo(mysql.checksSql("users"));
        assertThat(dialect.tableOptionsSql()).isEqualTo(mysql.tableOptionsSql());
        assertThat(dialect.catalogsSql()).isEqualTo(mysql.catalogsSql());
    }
}
