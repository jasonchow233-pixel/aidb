package io.agenticdb.introspect;

import io.agenticdb.fixtures.DemoDatabase;
import io.agenticdb.jdbc.ConnectionSpec;
import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.JdbcSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRestorerTest {

    @TempDir
    Path temp;

    private DbSession source;
    private DbSession target;

    @BeforeEach
    void open() throws Exception {
        source = JdbcSession.open(ConnectionSpec.sqlite(DemoDatabase.create(temp.resolve("source.db"))));
        target = JdbcSession.open(ConnectionSpec.sqlite(temp.resolve("target.db")));
    }

    @AfterEach
    void close() throws Exception {
        source.close();
        target.close();
    }

    /** 落盘 → 重放到空库，表集合、每行数据与视图都应原样回来（触发器仍不进 dump）。 */
    @Test
    void dumpThenRestoreRoundTripsData() throws Exception {
        StringWriter out = new StringWriter();
        long rows = BackupDumper.dump(source, out);
        assertThat(rows).isGreaterThan(0);

        int statements = BackupRestorer.restore(target.connection(), new StringReader(out.toString()));
        assertThat(statements).isGreaterThan(0);

        SchemaService schema = new SchemaService();
        List<String> targetTables = schema.listObjects(target).stream()
                .filter(object -> object.kind() == SchemaService.ObjectKind.TABLE)
                .map(SchemaService.SchemaObject::name)
                .toList();
        assertThat(targetTables).containsExactly("notes", "orders", "products", "users");

        // 视图排在最后，恢复后应该真的能查 —— 不只是文件里有一行 CREATE VIEW。
        List<String> targetViews = schema.listObjects(target).stream()
                .filter(object -> object.kind() == SchemaService.ObjectKind.VIEW)
                .map(SchemaService.SchemaObject::name)
                .toList();
        assertThat(targetViews).containsExactly("v_active_users");
        try (var statement = target.connection().createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM v_active_users")) {
            rs.next();
            assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(0);
        }

        // 每张表行数应与源库一致。
        for (String table : targetTables) {
            assertThat(count(target, table)).as("表 %s 行数", table).isEqualTo(count(source, table));
        }
    }

    /** 值里含 {@code ;} 时，切分不能把它误断成两条语句。 */
    @Test
    void splitKeepsSemicolonInsideStringLiteral() {
        String script = "INSERT INTO t (c) VALUES ('a;b'), ('c');\nCREATE TABLE x (id INT);";
        List<String> statements = BackupRestorer.splitStatements(script);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("'a;b'").doesNotEndWith(";");
        assertThat(statements.get(1)).isEqualTo("CREATE TABLE x (id INT)");
    }

    /** 注释与块注释里的 {@code ;} 也不应参与切分。 */
    @Test
    void splitIgnoresSemicolonsInComments() {
        String script = "-- a;b comment\n/* c;d */\nCREATE TABLE y (id INT);";
        List<String> statements = BackupRestorer.splitStatements(script);
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).isEqualTo("CREATE TABLE y (id INT)");
    }

    /** {@code -- table: NAME} 标记能取出表名，供「先删旧表」使用；视图另有一套标记。 */
    @Test
    void tableNamesReadsMarkers() throws Exception {
        StringWriter out = new StringWriter();
        BackupDumper.dump(source, out);
        String sql = out.toString();
        List<String> names = BackupRestorer.tableNames(new StringReader(sql));
        assertThat(names).containsExactly("notes", "orders", "products", "users");
        assertThat(BackupRestorer.viewNames(new StringReader(sql)))
                .containsExactly("v_active_users");
        // 两种标记不能互相串台：表名里不该出现视图，视图名里也不该出现表。
        assertThat(names).doesNotContain("v_active_users");
    }

    /** 手写脚本里没有视图段时，viewNames 返回空列表而不是把表当视图。 */
    @Test
    void viewNamesIgnoresTableMarkers() throws Exception {
        String script = "-- table: users\nCREATE TABLE users (id INT);\n-- table: logs\n";
        assertThat(BackupRestorer.viewNames(new StringReader(script))).isEmpty();
        assertThat(BackupRestorer.tableNames(new StringReader(script)))
                .containsExactly("users", "logs");
    }

    private long count(DbSession session, String table) throws Exception {
        try (var statement = session.connection().createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
