package io.agenticdb.ui.shell;

import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2 Users 只读视图的门禁：方言 → 账号目录查询的映射。
 *
 * <p>这条映射是这一页的<b>全部业务</b> —— 选错系统表就是查错对象，
 * 而且它是「查不到就什么都不显示」的静默失败，不会有任何报错。
 *
 * <p>所以门禁盯三件事：
 * <ol>
 *   <li>每个暴露账号目录的方言都有一句<b>自己的</b>系统视图查询，
 *       不许两个方言共用一条（MySQL 与 MariaDB 同源系统表，是刻意的例外）；</li>
 *   <li>SQLite 之类没有账号目录的方言返回 {@code null} ——
 *       页面据此如实说明「该引擎不暴露账号目录」，而不是拿一条猜的 SQL 去撞库；</li>
 *   <li>每条查询都指向该引擎**真实存在**的目录对象（{@code mysql.user} /
 *       {@code pg_roles} / {@code sys.database_principals} / {@code all_users}）。</li>
 * </ol>
 */
class UsersToolPageTest {

    @Test
    void everyEngineWithAnAccountCatalogHasItsOwnSystemView() {
        assertThat(UsersToolPage.queryFor(DialectKind.MYSQL))
                .as("MySQL 读 mysql.user")
                .contains("mysql.user");
        assertThat(UsersToolPage.queryFor(DialectKind.MARIADB))
                .as("MariaDB 与 MySQL 同源系统表")
                .contains("mysql.user");
        assertThat(UsersToolPage.queryFor(DialectKind.POSTGRESQL))
                .as("PostgreSQL 读 pg_roles")
                .contains("pg_roles");
        assertThat(UsersToolPage.queryFor(DialectKind.SQLSERVER))
                .as("SQL Server 读 sys.database_principals")
                .contains("sys.database_principals");
        assertThat(UsersToolPage.queryFor(DialectKind.ORACLE))
                .as("Oracle 读 all_users")
                .contains("all_users");
    }

    @Test
    void enginesWithoutAnAccountCatalogSaySoInsteadOfGuessing() {
        assertThat(UsersToolPage.queryFor(DialectKind.SQLITE))
                .as("SQLite 没有账号目录 —— 必须返回 null 让页面如实说明")
                .isNull();
    }

    @Test
    void eachQueryIsASelectAndNeverMutatesAnything() {
        for (DialectKind kind : DialectKind.values()) {
            String sql = UsersToolPage.queryFor(kind);
            if (sql == null) {
                continue;
            }
            assertThat(sql.trim().toUpperCase(java.util.Locale.ROOT))
                    .as("%s 的账号目录查询必须是只读 SELECT", kind)
                    .startsWith("SELECT");
            assertThat(sql.toUpperCase(java.util.Locale.ROOT))
                    .as("%s 的账号目录查询不许带写操作", kind)
                    .doesNotContain("INSERT", "UPDATE", "DELETE", "DROP", "ALTER");
        }
    }

    @Test
    void everyEngineWithAnAccountCatalogIsActuallyCovered() {
        // 防止以后新增方言时忘了补这一句 —— 忘了就是「该引擎显示不出账号」。
        long covered = 0;
        for (DialectKind kind : DialectKind.values()) {
            if (UsersToolPage.queryFor(kind) != null) {
                covered++;
            }
        }
        assertThat(covered)
                .as("六种方言里只有 SQLite 没有账号目录，其余五种都该有映射")
                .isEqualTo(5);
    }
}
