package io.agenticdb.ui.shell;

import io.agenticdb.introspect.BackupRestorer;
import io.agenticdb.jdbc.DialectKind;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P2 Backup 工具页的门禁。
 *
 * <p>这一页的动作会**真的改用户的库**（先删旧表再重放），所以门禁盯两件事：
 * <ol>
 *   <li>DROP 语句的形状 —— 必须带 {@code IF EXISTS}（表不存在时不该整批失败），
 *       且表名走方言引号（{@code order} / {@code group` 这类保留字不加引号会打挂）；</li>
 *   <li>「先删旧表」删的是<b>dump 里出现过的表</b>，不是库里所有表 ——
 *       这一条如果搞错，会把用户库里的无关表一起删掉。</li>
 * </ol>
 */
class BackupToolPageTest {

    @Test
    void theDropStatementIsIdempotentAndUsesTheQuotedName() {
        assertThat(BackupToolPage.dropTableIfExists("\"users\""))
                .isEqualTo("DROP TABLE IF EXISTS \"users\"");
        assertThat(BackupToolPage.dropTableIfExists("`order`"))
                .as("表名必须已带方言引号 —— 页面的职责是拼语句，不是猜引号")
                .contains("`order`");
    }

    @Test
    void onlyTablesPresentInTheDumpAreDropped() throws Exception {
        String dump = """
                -- table: users
                CREATE TABLE users (id INTEGER);
                INSERT INTO users VALUES (1);
                -- table: orders
                CREATE TABLE orders (id INTEGER);
                """;

        List<String> names = BackupRestorer.tableNames(new StringReader(dump));

        assertThat(names).containsExactly("users", "orders");
        assertThat(names)
                .as("库里存在但 dump 里没有的表，绝不能被「先删旧表」删掉")
                .doesNotContain("audit_log", "sessions");
    }

    @Test
    void viewsAreNotTreatedAsTablesWhenDropping() throws Exception {
        String dump = """
                -- table: users
                CREATE TABLE users (id INTEGER);
                -- view: v_active_users
                CREATE VIEW v_active_users AS SELECT * FROM users;
                """;

        assertThat(BackupRestorer.tableNames(new StringReader(dump)))
                .as("视图不是表 —— 恢复前的 DROP 不许带上它")
                .containsExactly("users");
        assertThat(BackupRestorer.viewNames(new StringReader(dump)))
                .containsExactly("v_active_users");
    }

    @Test
    void everyDialectQuotesTheNameSoReservedWordsSurvive() {
        for (DialectKind kind : DialectKind.values()) {
            String quoted = kind.dialect().quote("order");
            assertThat(quoted)
                    .as("%s 的引号必须真的把表名包起来", kind)
                    .isNotEqualTo("order")
                    .contains("order");
            assertThat(BackupToolPage.dropTableIfExists(quoted))
                    .isEqualTo("DROP TABLE IF EXISTS " + quoted);
        }
    }
}
