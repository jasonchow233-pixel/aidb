package io.agenticdb.introspect;

import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ P5-4：PostgreSQL 内省契约。
 *
 * <p>SQLite 跑不到这四项（没有序列、默认值走 PRAGMA、CHECK 走 DDL 原文），
 * 所以它们必须在真 PG 上验。没有 Docker 时整类跳过 —— 可见、但不计入门禁。
 *
 * <p>覆盖：
 * <ol>
 *   <li>序列能被列出来；</li>
 *   <li>{@code getExportedKeys} 能指出「谁引用了我」；</li>
 *   <li>列默认值能读回来（否则改列会静默 DROP DEFAULT）；</li>
 *   <li>列注释 / 表注释 / CHECK 能读回来。</li>
 * </ol>
 */
@Testcontainers
@org.junit.jupiter.api.condition.EnabledIf("io.agenticdb.jdbc.DockerAvailability#isAvailable")
class PostgresIntrospectionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("shop")
                    .withUsername("shop")
                    .withPassword("shop");

    private final SchemaService schema = new SchemaService();

    private static DbSession open() throws Exception {
        return JdbcSession.open(ConnectionSpec.postgres(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void sequencesAreListed() throws Exception {
        try (DbSession session = open()) {
            String sequence = "adb_seq_" + System.nanoTime();
            session.execute("CREATE SEQUENCE " + sequence);
            try {
                assertThat(schema.listSequences(session))
                        .as("用户建的序列要出现在清单里")
                        .contains(sequence);
            } finally {
                session.execute("DROP SEQUENCE IF EXISTS " + sequence);
            }
        }
    }

    @Test
    void exportedKeysPointAtTheChildTables() throws Exception {
        try (DbSession session = open()) {
            String parent = "adb_parent_" + System.nanoTime();
            String child = "adb_child_" + System.nanoTime();
            session.execute("CREATE TABLE " + parent + " (id INTEGER PRIMARY KEY)");
            session.execute("CREATE TABLE " + child + " (id INTEGER PRIMARY KEY,"
                    + " parent_id INTEGER,"
                    + " CONSTRAINT fk_" + child + " FOREIGN KEY (parent_id)"
                    + " REFERENCES " + parent + "(id) ON DELETE CASCADE)");
            try {
                List<ExportedKeyDef> keys = schema.exportedKeys(session, parent);

                assertThat(keys).singleElement().satisfies(key -> {
                    assertThat(key.childTable()).isEqualTo(child);
                    assertThat(key.columns()).containsExactly("parent_id");
                    assertThat(key.referencedColumns()).containsExactly("id");
                    assertThat(key.onDelete()).isEqualTo("CASCADE");
                });
            } finally {
                session.execute("DROP TABLE IF EXISTS " + child);
                session.execute("DROP TABLE IF EXISTS " + parent);
            }
        }
    }

    @Test
    void columnDefaultsAreReadBack() throws Exception {
        try (DbSession session = open()) {
            String table = "adb_default_" + System.nanoTime();
            session.execute("CREATE TABLE " + table
                    + " (id INTEGER PRIMARY KEY, score INTEGER DEFAULT 7)");
            try {
                TableStructure structure = schema.structure(session, table);
                ColumnDef score = structure.column("score");

                assertThat(score.defaultValue())
                        .as("默认值读不回来 → 改列时 PG 会静默 DROP DEFAULT")
                        .isNotNull()
                        .contains("7");
            } finally {
                session.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void commentsAndChecksAreReadBack() throws Exception {
        try (DbSession session = open()) {
            String table = "adb_meta_" + System.nanoTime();
            session.execute("CREATE TABLE " + table + " ("
                    + "id INTEGER PRIMARY KEY,"
                    + "score INTEGER,"
                    + "CONSTRAINT chk_score CHECK (score >= 0))");
            session.execute("COMMENT ON COLUMN " + table + ".score IS '分数'");
            session.execute("COMMENT ON TABLE " + table + " IS '测试表'");
            try {
                TableStructure structure = schema.structure(session, table);

                assertThat(structure.column("score").comment())
                        .as("列注释要能读回来")
                        .isEqualTo("分数");
                assertThat(structure.comment())
                        .as("表注释要能读回来")
                        .isEqualTo("测试表");
                assertThat(structure.checks())
                        .as("CHECK 约束要能读回来（方言 SQL 的表名是参数，必须绑定）")
                        .anySatisfy(check -> assertThat(check.expression())
                                .contains("score")
                                .contains("0"));
            } finally {
                session.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
