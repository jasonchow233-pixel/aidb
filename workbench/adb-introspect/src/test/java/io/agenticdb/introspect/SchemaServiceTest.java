package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.introspect.SchemaService.ObjectKind;
import io.agenticdb.introspect.SchemaService.SchemaObject;
import io.agenticdb.introspect.SchemaService.TableSchema;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaServiceTest {

    @TempDir
    Path temp;

    private DbSession session;
    private SchemaService schema;

    @BeforeEach
    void open() throws Exception {
        Path file = DemoDatabase.create(temp.resolve("demo.db"));
        session = JdbcSession.open(ConnectionSpec.sqlite(file));
        schema = new SchemaService();
    }

    @AfterEach
    void close() throws Exception {
        session.close();
    }

    @Test
    void listsTablesBeforeViewsWithoutSystemObjects() throws Exception {
        List<SchemaObject> objects = schema.listObjects(session);

        assertThat(objects).extracting(SchemaObject::name)
                .containsExactly("notes", "orders", "products", "users", "v_active_users");
        assertThat(objects).extracting(SchemaObject::kind)
                .containsExactly(ObjectKind.TABLE, ObjectKind.TABLE, ObjectKind.TABLE,
                        ObjectKind.TABLE, ObjectKind.VIEW);
        assertThat(objects).noneMatch(object -> object.name().startsWith("sqlite_"));
    }

    /**
     * SQLite 没有 schema 概念 —— schema 列表为空，切换器不该出现。
     * 有 schema 的库（PostgreSQL / MySQL）才会有多个。
     */
    @Test
    void sqliteHasNoSchemas() throws Exception {
        assertThat(schema.listSchemas(session))
                .as("SQLite 没有 schema 概念")
                .isEmpty();
        assertThat(schema.listObjects(session))
                .as("对象带上的 schema 应当是空串（不是 null）")
                .allMatch(object -> object.schema().isEmpty());
    }

    @Test
    void describesColumnsAndTypes() throws Exception {
        TableSchema table = schema.describe(session, "users");

        assertThat(table.columns()).extracting(ColumnDef::name)
                .containsExactly("id", "name", "email", "active", "score", "bio", "created_at");
        assertThat(table.columns().get(0).type()).isEqualTo(ColumnType.NUMBER);
        assertThat(table.columns().get(1).type()).isEqualTo(ColumnType.TEXT);
        assertThat(table.columns().get(2).type()).isEqualTo(ColumnType.TEXT);
        assertThat(table.columns().get(3).type()).isEqualTo(ColumnType.BOOLEAN);
        assertThat(table.columns().get(4).type()).isEqualTo(ColumnType.NUMBER);
        assertThat(table.columns().get(6).type()).isEqualTo(ColumnType.TIMESTAMP);
        assertThat(table.primaryKeys()).containsExactly("id");
        assertThat(table.columns().getFirst().primaryKey()).isTrue();
        assertThat(table.editable()).isTrue();
    }

    @Test
    void describesViewsToo() throws Exception {
        TableSchema view = schema.describe(session, "v_active_users");

        assertThat(view.columns()).extracting(ColumnDef::name)
                .containsExactly("id", "name", "email");
        assertThat(view.primaryKeys()).isEmpty();
        assertThat(view.editable()).isFalse();
    }

    @Test
    void countsWithFilters() throws Exception {
        DataQuery query = DataQuery.empty()
                .withFilter(FilterCondition.of("active", FilterOperator.EQ, 1));

        assertThat(schema.count(session, "users", query)).isEqualTo(180);
    }

    @Test
    void countsRowsExactly() throws Exception {
        assertThat(schema.count(session, "users")).isEqualTo(DemoDatabase.USER_COUNT);
        assertThat(schema.count(session, "orders")).isEqualTo(DemoDatabase.ORDER_COUNT);
        assertThat(schema.count(session, "v_active_users")).isEqualTo(180);
    }

    @Test
    void pageSqlChangesWithOffsetAndIsQuoted() {
        String first = SchemaService.pageSql(session, "users", 200, 0);
        String second = SchemaService.pageSql(session, "users", 200, 200);

        assertThat(first).isEqualTo("SELECT * FROM \"users\" LIMIT 200 OFFSET 0");
        assertThat(second).isEqualTo("SELECT * FROM \"users\" LIMIT 200 OFFSET 200");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void pageSqlHonoursHardLimits() {
        assertThat(SchemaService.pageSql(session, "users", 9999, 0))
                .endsWith("LIMIT 1000 OFFSET 0");
        assertThatThrownBy(() -> SchemaService.pageSql(
                session, "users", 200, io.agenticdb.model.QueryLimits.MAX_OFFSET + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("筛选");
    }
}
