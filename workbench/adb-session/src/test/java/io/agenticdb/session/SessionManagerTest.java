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
    void openingTheSameIdReusesTheExistingSession() throws Exception {
        // ⭐ U2：以前 open() 是「先关掉再重开」——
        //    切到 B 连接时 **A 连接的会话被关掉了**，A 的标签页当场失效。
        //    现在 open() **复用**已开的会话，两个连接才能并存。
        try (SessionManager manager = new SessionManager()) {
            DbSession first = manager.open("demo", ConnectionSpec.sqliteMemory());
            DbSession second = manager.open("demo", ConnectionSpec.sqliteMemory());

            assertThat(second).as("open() 要复用已开的会话").isSameAs(first);
            assertThat(manager.require("demo")).isSameAs(first);
            assertThat(first.isClosed()).as("复用时不能把旧会话关掉").isFalse();
        }
    }

    @Test
    void reopenForcesAFreshSession() throws Exception {
        // ⭐ 「重新连接」走 reopen：一定要换新的（用户想的就是重连）。
        try (SessionManager manager = new SessionManager()) {
            DbSession first = manager.open("demo", ConnectionSpec.sqliteMemory());
            DbSession second = manager.reopen("demo", ConnectionSpec.sqliteMemory());

            assertThat(second).isNotSameAs(first);
            assertThat(manager.require("demo")).isSameAs(second);
            assertThat(first.isClosed()).as("重开时旧会话要关掉").isTrue();
        }
    }

    @Test
    void twoConnectionsCanStayOpenAtTheSameTime() throws Exception {
        // ⭐ U2 的核心：**两个连接并存**，A 的标签页在切到 B 之后仍然可用。
        try (SessionManager manager = new SessionManager()) {
            DbSession a = manager.open("a", ConnectionSpec.sqliteMemory());
            DbSession b = manager.open("b", ConnectionSpec.sqliteMemory());

            assertThat(manager.openIds()).containsExactlyInAnyOrder("a", "b");
            assertThat(a.isClosed()).as("切到 B 不能把 A 关掉").isFalse();
            assertThat(b.isClosed()).isFalse();
            assertThat(manager.find("a")).containsSame(a);
            assertThat(manager.find("b")).containsSame(b);
        }
    }
}
