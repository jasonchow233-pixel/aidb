package io.agenticdb.introspect;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P5-4：序列与外键反向关系（{@code getExportedKeys}）。
 *
 * <p>这里用**真实 SQLite** 跑（不需要 Docker）：
 * <ul>
 *   <li>序列：SQLite 没有 → 必须返回空列表，而不是假装有；</li>
 *   <li>反向外键：SQLite 的驱动支持 {@code getExportedKeys}，
 *       所以「谁引用了我」这条链路能被真正钉住。</li>
 * </ul>
 */
class SequenceExportedKeyTest {

    private final SchemaService schema = new SchemaService();
    private DbSession session;

    @BeforeEach
    void open() throws Exception {
        session = JdbcSession.open(ConnectionSpec.sqliteMemory());
        session.execute("PRAGMA foreign_keys = ON");
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    @Test
    void sqliteHasNoSequences() {
        assertThat(schema.listSequences(session))
                .as("SQLite 没有序列 —— 不能凭空造一个分组")
                .isEmpty();
    }

    @Test
    void exportedKeysReportTheChildrenThatReferenceTheTable() throws Exception {
        session.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY, code TEXT UNIQUE)");
        session.execute("""
                CREATE TABLE child (
                  id INTEGER PRIMARY KEY,
                  parent_id INTEGER,
                  CONSTRAINT fk_child_parent FOREIGN KEY (parent_id)
                    REFERENCES parent(id) ON DELETE CASCADE
                )
                """);

        List<ExportedKeyDef> keys = schema.exportedKeys(session, "parent");

        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key.name()).isEqualTo("fk_child_parent");
            assertThat(key.childTable())
                    .as("要指出是谁在引用 —— 删表 / 改主键前必须知道")
                    .isEqualTo("child");
            assertThat(key.columns()).containsExactly("parent_id");
            assertThat(key.referencedColumns()).containsExactly("id");
            assertThat(key.onDelete()).isEqualTo("CASCADE");
            assertThat(key.onUpdate()).isEqualTo("NO ACTION");
        });
    }

    @Test
    void compositeExportedKeysKeepColumnOrder() throws Exception {
        session.execute("""
                CREATE TABLE parent (
                  a INTEGER,
                  b INTEGER,
                  PRIMARY KEY (a, b)
                )
                """);
        session.execute("""
                CREATE TABLE child (
                  a INTEGER,
                  b INTEGER,
                  CONSTRAINT fk_child_ab FOREIGN KEY (b, a)
                    REFERENCES parent(b, a)
                )
                """);

        ExportedKeyDef key = schema.exportedKeys(session, "parent").getFirst();

        assertThat(key.columns())
                .as("复合键的列顺序就是约束里的顺序，不能按名字排序")
                .containsExactly("b", "a");
        assertThat(key.referencedColumns()).containsExactly("b", "a");
    }

    @Test
    void aTableNobodyReferencesHasNoExportedKeys() throws Exception {
        session.execute("CREATE TABLE lonely (id INTEGER PRIMARY KEY)");

        assertThat(schema.exportedKeys(session, "lonely")).isEmpty();
    }
}
