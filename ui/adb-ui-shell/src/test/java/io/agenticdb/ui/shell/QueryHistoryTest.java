package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryHistoryTest {

    @Test
    void remembersRecentQueriesWithoutDuplicates() {
        QueryHistory history = new QueryHistory(null);
        history.add("SELECT 1;");
        history.add("SELECT 2;");
        history.add("SELECT 1;");

        assertThat(history.recent()).containsExactly("SELECT 1;", "SELECT 2;");
    }

    @Test
    void capsHistoryAtFiftyEntries() {
        QueryHistory history = new QueryHistory(null);
        for (int i = 0; i < 60; i++) {
            history.add("SELECT " + i + ";");
        }

        assertThat(history.recent()).hasSize(50);
        assertThat(history.recent().getFirst()).isEqualTo("SELECT 59;");
        assertThat(history.recent().getLast()).isEqualTo("SELECT 10;");
    }

    @Test
    void clearRemovesEverything() {
        QueryHistory history = new QueryHistory(null);
        history.add("SELECT 1;");
        history.clear();
        assertThat(history.recent()).isEmpty();
    }

    // ─────────────────────────── 对象上下文（P2-3） ───────────────────────────

    @Test
    void entriesCarryTheConnectionAndTheTablesTheSqlTouches() {
        QueryHistory history = new QueryHistory(null);
        history.add("""
                SELECT * FROM public.users u
                JOIN orders o ON o.user_id = u.id
                WHERE u.note = 'from secrets' -- from hidden
                """, "demo");

        QueryHistory.Entry entry = history.entries().getFirst();
        assertThat(entry.connection()).isEqualTo("demo");
        // schema 前缀去掉；字符串字面量与注释里的 "from xxx" 不算表。
        assertThat(entry.tables()).containsExactly("users", "orders");
        assertThat(entry.at()).isPositive();
        assertThat(entry.summary()).contains("SELECT * FROM public.users");
    }

    @Test
    void oldStringOnlyPayloadStillLoads() {
        List<QueryHistory.Entry> entries =
                QueryHistory.parse("[\"SELECT 1;\",\"SELECT 2 FROM users;\"]");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).sql()).isEqualTo("SELECT 1;");
        assertThat(entries.get(0).at()).isZero();
        assertThat(entries.get(1).tables()).containsExactly("users");
    }

    @Test
    void recentForObjectFiltersByTableAndReturnsNewestFirst() {
        QueryHistory history = new QueryHistory(null);
        history.add("SELECT * FROM users;", "demo");
        history.add("SELECT * FROM orders;", "demo");
        history.add("UPDATE users SET name = 'x';", "demo");
        // 重复 SQL 去重后回到最前 —— 用户刚跑过的才是最近一条。
        history.add("SELECT * FROM users;", "demo");

        List<QueryHistory.Entry> users = history.recentForObject("USERS", 10);
        assertThat(users).hasSize(2);
        assertThat(users.getFirst().sql()).isEqualTo("SELECT * FROM users;");
        assertThat(users.getLast().sql()).startsWith("UPDATE users");
        assertThat(history.recentForObject("users", 1)).hasSize(1);
        assertThat(history.recentForObject("nope", 10)).isEmpty();
    }
}
