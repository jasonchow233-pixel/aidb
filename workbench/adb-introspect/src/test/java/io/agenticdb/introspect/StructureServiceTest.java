package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureServiceTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schema;

    @BeforeEach
    void open() throws Exception {
        Path file = DemoDatabase.create(temp.resolve("demo.db"));
        session = JdbcSession.open(ConnectionSpec.sqlite(file));
        schema = new SchemaService();
        session.update("CREATE TRIGGER trg_users_touch AFTER UPDATE ON users "
                + "BEGIN SELECT 1; END");
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    @Test
    void readsColumnsIndexesForeignKeysTriggersAndDdl() throws Exception {
        TableStructure orders = schema.structure(session, "orders");

        assertThat(orders.name()).isEqualTo("orders");
        assertThat(orders.view()).isFalse();
        assertThat(orders.columns()).extracting("name")
                .containsExactly("id", "user_id", "status", "amount", "created_at");
        assertThat(orders.primaryKeys()).containsExactly("id");
        assertThat(orders.indexes()).extracting(IndexDef::name).contains("idx_orders_user");
        assertThat(orders.foreignKeys()).hasSize(1);
        assertThat(orders.foreignKeys().getFirst().referencedTable()).isEqualTo("users");
        assertThat(orders.foreignKeys().getFirst().columns()).containsExactly("user_id");
        assertThat(orders.ddl()).contains("CREATE TABLE orders", "REFERENCES users");
        assertThat(orders.editable()).isTrue();
    }

    @Test
    void readsViewDdlWithoutTreatingItAsEditable() throws Exception {
        TableStructure view = schema.structure(session, "v_active_users");

        assertThat(view.view()).isTrue();
        assertThat(view.editable()).isFalse();
        assertThat(view.ddl()).contains("CREATE VIEW v_active_users");
    }

    @Test
    void readsTriggerStatements() throws Exception {
        TableStructure users = schema.structure(session, "users");

        assertThat(users.triggers()).extracting(TriggerDef::name)
                .contains("trg_users_touch");
        assertThat(users.triggers().getFirst().statement()).contains("CREATE TRIGGER");
        assertThat(users.triggers().getFirst().timing()).isEqualTo("AFTER");
        assertThat(users.triggers().getFirst().event()).isEqualTo("UPDATE");
    }

    @Test
    void addsColumnAndExecutesPlanInTransaction() throws Exception {
        TableStructure users = schema.structure(session, "users");
        StructurePlan plan = StructureSqlBuilder.addColumn(session, users,
                new StructureSqlBuilder.ColumnSpec("nickname", "TEXT", true,
                        "'anonymous'", null, false, false));

        assertThat(plan.statements()).hasSize(1);
        assertThat(plan.sql()).contains("ADD COLUMN \"nickname\" TEXT DEFAULT 'anonymous'");

        execute(plan);
        TableStructure changed = schema.structure(session, "users");
        assertThat(changed.column("nickname")).isNotNull();
        assertThat(changed.column("nickname").defaultValue()).contains("anonymous");
    }

    @Test
    void rebuildsSqliteTableForNullableChangeAndKeepsDataIndexesAndTriggers() throws Exception {
        long before = schema.count(session, "users");
        TableStructure users = schema.structure(session, "users");
        StructurePlan plan = StructureSqlBuilder.alterColumn(session, users, "email",
                new StructureSqlBuilder.ColumnSpec("email", "TEXT", false, null,
                        null, true, false));

        assertThat(plan.approvalRequired()).isTrue();
        assertThat(plan.foreignKeysOff()).isTrue();
        assertThat(plan.sql()).contains("CREATE TABLE \"__adb_rebuild_users_");

        execute(plan);

        TableStructure changed = schema.structure(session, "users");
        assertThat(changed.column("email").nullable()).isFalse();
        assertThat(changed.indexes()).extracting(IndexDef::name).contains("idx_users_email");
        assertThat(changed.triggers()).extracting(TriggerDef::name)
                .contains("trg_users_touch");
        assertThat(schema.count(session, "users")).isEqualTo(before);
    }

    @Test
    void renamesAndDropsColumnsWithGuardedPlans() throws Exception {
        TableStructure users = schema.structure(session, "users");

        execute(StructureSqlBuilder.renameColumn(session, users, "bio", "about"));
        TableStructure renamed = schema.structure(session, "users");
        assertThat(renamed.column("about")).isNotNull();
        assertThat(renamed.column("bio")).isNull();

        StructurePlan drop = StructureSqlBuilder.dropColumn(session, renamed, "about");
        assertThat(drop.approvalRequired()).isTrue();
        execute(drop);
        assertThat(schema.structure(session, "users").column("about")).isNull();
    }

    @Test
    void refusesNotNullAddWithoutDefaultOnSqlite() {
        TableStructure users = assertDoesNotThrowStructure("users");
        assertThatThrownBy(() -> StructureSqlBuilder.addColumn(session, users,
                new StructureSqlBuilder.ColumnSpec("required", "TEXT", false,
                        null, null, false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("默认值");
    }

    @Test
    void refusesDroppingPrimaryKeyColumn() {
        TableStructure users = assertDoesNotThrowStructure("users");
        assertThatThrownBy(() -> StructureSqlBuilder.dropColumn(session, users, "id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主键");
    }

    private TableStructure assertDoesNotThrowStructure(String name) {
        try {
            return schema.structure(session, name);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void execute(StructurePlan plan) throws Exception {
        if (plan.foreignKeysOff()) {
            session.update("PRAGMA foreign_keys = OFF");
        }
        session.begin();
        try {
            for (String sql : plan.statements()) {
                session.execute(sql);
            }
            session.commit();
        } catch (Exception e) {
            session.rollback();
            throw e;
        } finally {
            if (plan.foreignKeysOff()) {
                session.update("PRAGMA foreign_keys = ON");
            }
        }
    }
}
