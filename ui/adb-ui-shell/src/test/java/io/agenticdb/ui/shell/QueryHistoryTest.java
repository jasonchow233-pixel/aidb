package io.agenticdb.ui.shell;

import org.junit.jupiter.api.Test;

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
}
