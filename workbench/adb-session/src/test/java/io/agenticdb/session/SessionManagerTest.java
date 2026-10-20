package io.agenticdb.session;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.RowPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionManagerTest {

    @Test
    void opensFindsAndClosesSessions() throws Exception {
        SessionManager manager = new SessionManager();
        DbSession session = manager.open("demo", ConnectionSpec.sqliteMemory());

        assertThat(manager.isOpen("demo")).isTrue();
        assertThat(manager.find("demo")).containsSame(session);
        assertThat(manager.require("demo")).isSameAs(session);

        RowPage page = session.query("SELECT 1 AS n", 1);
        assertThat(((Number) page.rows().getFirst().get("n")).intValue()).isEqualTo(1);

        manager.close("demo");
        assertThat(manager.isOpen("demo")).isFalse();
        assertThat(manager.find("demo")).isEmpty();
        manager.close("demo");
        manager.close();
    }

    @Test
    void requireFailsWithReadableMessage() {
        try (SessionManager manager = new SessionManager()) {
            assertThatThrownBy(() -> manager.require("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing");
        }
    }

    @Test
    void reopeningSameIdReplacesOldSession() throws Exception {
        try (SessionManager manager = new SessionManager()) {
            DbSession first = manager.open("demo", ConnectionSpec.sqliteMemory());
            DbSession second = manager.open("demo", ConnectionSpec.sqliteMemory());

            assertThat(second).isNotSameAs(first);
            assertThat(manager.require("demo")).isSameAs(second);
        }
    }
}
