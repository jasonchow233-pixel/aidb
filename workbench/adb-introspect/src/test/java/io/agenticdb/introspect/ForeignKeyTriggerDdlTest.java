package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.DialectKind;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ B5-S2 / S3 门禁：外键与触发器的**增删**。
 *
 * <p>两组断言：
 * <ol>
 *   <li><b>SQL 逐字</b>：三种方言生成的外键 / 触发器语句各不相同 ——
 *       MySQL 删外键是 {@code DROP FOREIGN KEY}（不是 {@code DROP CONSTRAINT}），
 *       PostgreSQL 的触发器是「函数 + 触发器」两条语句，
 *       SQLite 加外键只能整表重建。这类差异正是本地最难发现、线上才炸的那一类。</li>
 *   <li><b>真库往返</b>：SQLite 上真的加一条外键、真的加一个触发器，
 *       验证约束**真的生效**（插入违规数据必须被拒）。</li>
 * </ol>
 */
class ForeignKeyTriggerDdlTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schema;

    @BeforeEach
    void open() throws Exception {
        Path file = DemoDatabase.create(temp.resolve("demo.db"));
        session = JdbcSession.open(ConnectionSpec.sqlite(file));
        schema = new SchemaService();
        // ⭐ SQLite 默认**不执行**外键约束（要显式开）—— 不开的话
        // 「外键拦得住违规数据」这条断言是假的（约束形同虚设）。
        try (java.sql.Statement statement = session.connection().createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    // ─────────────────────── 外键：SQL 逐字 ───────────────────────

    @Test
    void postgresAddsForeignKeyWithAlterTable() {
        StructurePlan plan = StructureSqlBuilder.addForeignKey(FakeDialectSession.of(DialectKind.POSTGRESQL),
                table(), foreignKey("fk_orders_user", "user_id", "users", "id"));

        assertThat(plan.statements()).containsExactly(
                "ALTER TABLE \"orders\" ADD CONSTRAINT \"fk_orders_user\" "
                        + "FOREIGN KEY (user_id) REFERENCES \"users\" (id) "
                        + "ON UPDATE CASCADE ON DELETE NO ACTION");
    }

    @Test
    void mySqlDropsForeignKeyWithDropForeignKey() {
        // ⚠️ MySQL 删外键是 DROP FOREIGN KEY —— 写成 DROP CONSTRAINT 会语法错误。
        StructurePlan plan = StructureSqlBuilder.dropForeignKey(FakeDialectSession.of(DialectKind.MYSQL),
                table(), foreignKey("fk_orders_user", "user_id", "users", "id"));

        assertThat(plan.statements()).containsExactly(
                "ALTER TABLE `orders` DROP FOREIGN KEY `fk_orders_user`");
    }

    @Test
    void sqliteAddsForeignKeyByRebuildingTheTable() {
        StructurePlan plan = StructureSqlBuilder.addForeignKey(FakeDialectSession.of(DialectKind.SQLITE),
                table(), foreignKey("fk_orders_user", "user_id", "users", "id"));

        assertThat(plan.foreignKeysOff())
                .as("SQLite 改外键要整表重建（外键是 CREATE TABLE 的一部分）")
                .isTrue();
        assertThat(plan.sql())
                .contains("FOREIGN KEY (\"user_id\") REFERENCES \"users\" (\"id\")");
    }

    @Test
    void foreignKeyWithMismatchedColumnCountsIsRejected() {
        assertThatThrownBy(() -> StructureSqlBuilder.addForeignKey(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(),
                new ForeignKeyDef("fk", List.of("user_id", "status"), "users",
                        List.of("id"), "CASCADE", "NO ACTION")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须一致");
    }

    // ─────────────────────── 触发器：SQL 逐字 ───────────────────────

    @Test
    void sqliteCreatesTriggerWithBeginEnd() {
        StructurePlan plan = StructureSqlBuilder.createTrigger(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                "trg_orders_audit", "AFTER", "INSERT", "SELECT 1");

        assertThat(plan.statements()).containsExactly(
                "CREATE TRIGGER \"trg_orders_audit\" AFTER INSERT ON \"orders\" BEGIN SELECT 1; END");
    }

    @Test
    void postgresCreatesTriggerAsFunctionPlusTrigger() {
        // ⭐ PostgreSQL 的触发器 = 一个返回 trigger 的函数 + 一个绑在表上的触发器。
        StructurePlan plan = StructureSqlBuilder.createTrigger(
                FakeDialectSession.of(DialectKind.POSTGRESQL), table(),
                "trg_orders_audit", "AFTER", "INSERT", "BEGIN RETURN NEW; END");

        assertThat(plan.statements()).containsExactly(
                "CREATE OR REPLACE FUNCTION \"trg_orders_audit_fn\"() RETURNS trigger AS $$ "
                        + "BEGIN RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER \"trg_orders_audit\" AFTER INSERT ON \"orders\" "
                        + "EXECUTE FUNCTION \"trg_orders_audit_fn\"()");
    }

    @Test
    void triggerWithUnknownTimingIsRejected() {
        assertThatThrownBy(() -> StructureSqlBuilder.createTrigger(
                FakeDialectSession.of(DialectKind.SQLITE), table(),
                "trg", "WHENEVER", "INSERT", "SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEFORE");
    }

    // ─────────────────────── 真库往返 ───────────────────────

    @Test
    void sqliteForeignKeyReallyBlocksViolatingRows() throws Exception {
        TableStructure orders = schema.structure(session, "orders");
        StructurePlan plan = StructureSqlBuilder.addForeignKey(session, orders,
                foreignKey("fk_orders_user", "user_id", "users", "id"));
        execute(plan);

        TableStructure after = schema.structure(session, "orders");
        assertThat(after.foreignKeys()).extracting(ForeignKeyDef::name)
                .as("外键加上了")
                .contains("fk_orders_user");

        // 造一个不存在的用户 id → 外键必须拦住
        assertThatThrownBy(() -> {
            try (java.sql.Statement statement = session.connection().createStatement()) {
                statement.execute("INSERT INTO orders (id, user_id, status, amount, created_at) "
                        + "VALUES (9999, 424242, 'PAID', 1.0, '2026-01-01 00:00:00')");
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    void alteringAColumnOnATableWithForeignKeyUsedToFail() throws Exception {
        // ⭐ 这条守一个**潜伏很久的真 bug**：
        //    外键的 ON UPDATE / ON DELETE 动作码**没被翻译**（驱动给的是数字），
        //    于是重建语句里出现「ON UPDATE 3」—— 语法错误。
        //    结果：**任何带外键的表一改列就失败**（演示库的 orders 正好有外键，
        //    但以前没有测试改过它）。
        TableStructure orders = schema.structure(session, "orders");
        assertThat(orders.foreignKeys()).as("演示库的 orders 本来就有一个外键").isNotEmpty();

        StructurePlan plan = StructureSqlBuilder.alterColumn(session, orders, "status",
                new io.agenticdb.model.ColumnSpec("status", "TEXT", true, null, null, false, false));

        assertThat(plan.sql())
                .as("动作必须是动作名，不能是数字码")
                .doesNotContain("ON UPDATE 3")
                .doesNotContain("ON DELETE 3")
                .containsPattern("ON UPDATE (NO ACTION|CASCADE|RESTRICT|SET NULL|SET DEFAULT)");

        execute(plan);

        TableStructure after = schema.structure(session, "orders");
        assertThat(after.foreignKeys())
                .as("改列之后外键必须还在")
                .isNotEmpty();
        assertThat(schema.count(session, "orders")).isEqualTo(500);
    }

    @Test
    void sqliteTriggerIsCreatedAndFires() throws Exception {
        try (java.sql.Statement statement = session.connection().createStatement()) {
            statement.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY, note TEXT)");
        }
        TableStructure users = schema.structure(session, "users");
        StructurePlan plan = StructureSqlBuilder.createTrigger(session, users,
                "trg_users_log", "AFTER", "INSERT",
                "INSERT INTO audit_log (id, note) VALUES (NEW.id, 'inserted')");
        execute(plan);

        try (java.sql.Statement statement = session.connection().createStatement()) {
            statement.execute("INSERT INTO users (id, name, email, created_at) "
                    + "VALUES (999, '触发器', 'trigger@example.com', '2026-01-01 00:00:00')");
        }
        try (java.sql.Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM audit_log")) {
            rs.next();
            assertThat(rs.getInt(1)).as("触发器必须真的执行了").isEqualTo(1);
        }
    }

    @Test
    void droppingATriggerRemovesIt() throws Exception {
        TableStructure users = schema.structure(session, "users");
        StructurePlan create = StructureSqlBuilder.createTrigger(session, users,
                "trg_tmp", "AFTER", "INSERT", "SELECT 1");
        execute(create);
        assertThat(schema.structure(session, "users").triggers()).extracting(TriggerDef::name)
                .contains("trg_tmp");

        TriggerDef created = schema.structure(session, "users").triggers().stream()
                .filter(trigger -> trigger.name().equals("trg_tmp"))
                .findFirst()
                .orElseThrow();
        execute(StructureSqlBuilder.dropTrigger(session, users, created));
        assertThat(schema.structure(session, "users").triggers()).extracting(TriggerDef::name)
                .doesNotContain("trg_tmp");
    }

    // ─────────────────────── 辅助 ───────────────────────

    private void execute(StructurePlan plan) throws Exception {
        try (java.sql.Statement statement = session.connection().createStatement()) {
            for (String sql : plan.statements()) {
                statement.execute(sql);
            }
        }
    }

    private static ForeignKeyDef foreignKey(String name, String column,
                                            String referencedTable, String referencedColumn) {
        return new ForeignKeyDef(name, List.of(column), referencedTable,
                List.of(referencedColumn), "CASCADE", "NO ACTION");
    }

    /** 一张只有 orders 的表结构（够生成外键 SQL 用）。 */
    private static TableStructure table() {
        List<ColumnDef> columns = List.of(
                new ColumnDef("id", ColumnType.NUMBER, false, true, null, null, null,
                        null, null, null, false, "INTEGER"),
                new ColumnDef("user_id", ColumnType.NUMBER, true, false, null, null, null,
                        null, null, null, false, "INTEGER"));
        return new TableStructure("orders", false, columns, List.of("id"),
                List.of(), List.of(), List.of(), "");
    }
}
