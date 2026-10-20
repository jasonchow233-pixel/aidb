package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 元数据内省。P1 只做三件事：对象列表、列定义、精确行数。
 *
 * <p>⛔ 对象列表<b>不</b>逐表 {@code COUNT(*)}：一万张表时那会是几千次全表扫描。
 * 行数只在打开具体表时按需统计。
 */
public final class SchemaService {

    public enum ObjectKind {
        TABLE("▦"),
        VIEW("◇");

        private final String icon;

        ObjectKind(String icon) {
            this.icon = icon;
        }

        public String icon() {
            return icon;
        }
    }

    public record SchemaObject(String name, ObjectKind kind) {
    }

    public record TableSchema(String name, List<ColumnDef> columns, List<String> primaryKeys) {
        public TableSchema {
            columns = List.copyOf(columns == null ? List.of() : columns);
            primaryKeys = List.copyOf(primaryKeys == null ? List.of() : primaryKeys);
        }

        public TableSchema(String name, List<ColumnDef> columns) {
            this(name, columns, List.of());
        }

        /** 没有主键的表/视图不能安全行内编辑：UPDATE 可能命中多行。 */
        public boolean editable() {
            return !primaryKeys.isEmpty();
        }

        public boolean isPrimaryKey(String column) {
            return primaryKeys.stream().anyMatch(key -> key.equalsIgnoreCase(column));
        }
    }

    /** 表/视图列表，表在前、视图在后，组内按名称排序。 */
    public List<SchemaObject> listObjects(DbSession session) throws SQLException {
        DatabaseMetaData meta = session.connection().getMetaData();
        List<SchemaObject> objects = new ArrayList<>();

        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (name == null || name.isBlank() || isSystemObject(name)) {
                    continue;
                }
                ObjectKind kind = type != null && type.toUpperCase(Locale.ROOT).contains("VIEW")
                        ? ObjectKind.VIEW : ObjectKind.TABLE;
                objects.add(new SchemaObject(name, kind));
            }
        }

        objects.sort(Comparator.comparing(SchemaObject::kind)
                .thenComparing(SchemaObject::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(objects);
    }

    /** 用 {@code SELECT * FROM t LIMIT 0} 拿结果集元数据，表与视图共用。 */
    public TableSchema describe(DbSession session, String name) throws SQLException {
        String sql = "SELECT * FROM " + session.dialect().quote(name) + " LIMIT 0";
        RowPage page = session.query(sql, 1);
        Set<String> primaryKeys = primaryKeys(session, name);
        if (primaryKeys.isEmpty()) {
            return new TableSchema(name, page.columns());
        }
        List<ColumnDef> columns = page.columns().stream()
                .map(column -> primaryKeys.contains(column.name().toLowerCase(Locale.ROOT))
                        ? withPrimaryKey(column) : column)
                .toList();
        List<String> orderedKeys = page.columns().stream()
                .map(ColumnDef::name)
                .filter(key -> primaryKeys.contains(key.toLowerCase(Locale.ROOT)))
                .toList();
        return new TableSchema(name, columns, orderedKeys);
    }

    private static Set<String> primaryKeys(DbSession session, String name) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet rs = session.connection().getMetaData()
                .getPrimaryKeys(null, null, name)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    keys.add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        return keys;
    }

    private static ColumnDef withPrimaryKey(ColumnDef column) {
        return new ColumnDef(column.name(), column.type(), column.nullable(), true,
                column.defaultValue(), column.comment(), column.enumValues(),
                column.charMaxLength(), column.numericPrecision(), column.numericScale(),
                column.unique());
    }

    /** 精确总数。只在打开/刷新数据页时调用；超过 OFFSET 上限后提示改用筛选。 */
    public long count(DbSession session, String name) throws SQLException {
        return count(session, name, DataQuery.empty());
    }

    /** 带筛选条件的精确总数。参数绑定由 {@link DataSqlBuilder} 负责。 */
    public long count(DbSession session, String name, DataQuery query) throws SQLException {
        DataSqlBuilder.Sql sql = DataSqlBuilder.count(session, name, query);
        RowPage page = session.query(sql.text(), 1, sql.params());
        if (page.rows().isEmpty()) {
            return 0;
        }
        Object value = page.rows().getFirst().get("adb_count");
        return value instanceof Number number ? number.longValue() : 0;
    }

    /**
     * 服务端分页 SQL。SQLite / PostgreSQL / MySQL 都支持 {@code LIMIT n OFFSET m}。
     *
     * <p>深翻页直接拒绝，避免用户在百万行表上把 UI 卡成「假死」。
     */
    public static String pageSql(DbSession session, String name, int limit, long offset) {
        int pageSize = QueryLimits.clampPageSize(limit);
        long safeOffset = Math.max(0, offset);
        if (QueryLimits.exceedsOffsetLimit(safeOffset)) {
            throw new IllegalArgumentException(
                    "OFFSET " + safeOffset + " 超过上限 " + QueryLimits.MAX_OFFSET
                            + "，请改用筛选条件");
        }
        return "SELECT * FROM " + session.dialect().quote(name)
                + " LIMIT " + pageSize + " OFFSET " + safeOffset;
    }

    private static boolean isSystemObject(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("sqlite_") || lower.startsWith("pg_") || lower.startsWith("mysql.");
    }
}
