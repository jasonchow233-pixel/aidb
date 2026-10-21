package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * 一个数据库对象。
     *
     * @param name   对象名
     * @param kind   对象类型
     * @param schema 所属 schema。SQLite 没有这个概念 → 空串。
     *                PostgreSQL 是 {@code public}，MySQL 是当前数据库名。
     */
    public record SchemaObject(String name, ObjectKind kind, String schema) {

        public SchemaObject {
            name = name == null ? "" : name;
            schema = schema == null ? "" : schema;
        }

        public SchemaObject(String name, ObjectKind kind) {
            this(name, kind, "");
        }
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

    /**
     * 表/视图列表，表在前、视图在后，组内按名称排序。
     *
     * <p>⭐ 每个对象带上它所属的 schema —— 对象树要按 schema 分组，
     * 用户才知道自己看到的是哪个 schema 里的东西。
     */
    public List<SchemaObject> listObjects(DbSession session) throws SQLException {
        return listObjects(session, null);
    }

    /**
     * 表/视图列表，可限定 schema。
     *
     * @param schemaPattern schema 名；{@code null} 表示当前 schema
     */
    public List<SchemaObject> listObjects(DbSession session, String schemaPattern)
            throws SQLException {
        DatabaseMetaData meta = session.connection().getMetaData();
        List<SchemaObject> objects = new ArrayList<>();
        String catalog = currentCatalog(session);
        String schema = schemaPattern == null || schemaPattern.isBlank()
                ? currentSchema(session) : schemaPattern;

        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (name == null || name.isBlank() || isSystemObject(name)) {
                    continue;
                }
                ObjectKind kind = type != null && type.toUpperCase(Locale.ROOT).contains("VIEW")
                        ? ObjectKind.VIEW : ObjectKind.TABLE;
                objects.add(new SchemaObject(name, kind, safeSchema(rs.getString("TABLE_SCHEM"))));
            }
        }

        objects.sort(Comparator.comparing(SchemaObject::kind)
                .thenComparing(SchemaObject::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(objects);
    }

    /**
     * 连接可见的 schema 列表。
     *
     * <p>SQLite 没有 schema 概念 → 返回空列表。
     * PostgreSQL 返回 {@code public} 等；MySQL 返回当前数据库名。
     */
    public List<String> listSchemas(DbSession session) throws SQLException {
        if (!session.dialect().hasSchema()) {
            return List.of();
        }
        List<String> schemas = new ArrayList<>();
        try (ResultSet rs = session.connection().getMetaData()
                .getSchemas(currentCatalog(session), "%")) {
            while (rs.next()) {
                String schema = safeSchema(rs.getString("TABLE_SCHEM"));
                if (!schema.isBlank()) {
                    schemas.add(schema);
                }
            }
        }
        schemas.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(schemas);
    }

    /** JDBC 的 schema 名可能是 null 或空白（SQLite 恒为 null）。 */
    private static String safeSchema(String value) {
        return value == null ? "" : value.trim();
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
                .getPrimaryKeys(currentCatalog(session), currentSchema(session), name)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    keys.add(column.toLowerCase(Locale.ROOT));
                }
            }
        }
        return keys;
    }

    private static List<ColumnDef> markUnique(List<ColumnDef> columns, List<IndexDef> indexes) {
        Set<String> unique = new HashSet<>();
        for (IndexDef index : indexes) {
            if (index.unique() && index.columns().size() == 1) {
                unique.add(index.columns().getFirst().toLowerCase(Locale.ROOT));
            }
        }
        if (unique.isEmpty()) {
            return columns;
        }
        return columns.stream().map(column -> unique.contains(
                        column.name().toLowerCase(Locale.ROOT)) && !column.unique()
                        ? new ColumnDef(column.name(), column.type(), column.nullable(),
                        column.primaryKey(), column.defaultValue(), column.comment(),
                        column.enumValues(), column.charMaxLength(),
                        column.numericPrecision(), column.numericScale(), true,
                        column.rawType())
                        : column)
                .toList();
    }

    private static ColumnDef withPrimaryKey(ColumnDef column) {
        return new ColumnDef(column.name(), column.type(), column.nullable(), true,
                column.defaultValue(), column.comment(), column.enumValues(),
                column.charMaxLength(), column.numericPrecision(), column.numericScale(),
                column.unique(), column.rawType());
    }

    /**
     * 结构页完整快照：列、主键、索引、外键、触发器、DDL。
     *
     * <p>DDL 对 SQLite/MySQL 直接取数据库保存的原文；PostgreSQL 没有
     * {@code SHOW CREATE TABLE}，用内省结果生成等价 DDL，并在 UI 标注为“生成”。
     * 结构变更始终以列/索引元数据为准，不反过来解析 DDL。
     */
    public TableStructure structure(DbSession session, String name) throws SQLException {
        TableSchema table = describe(session, name);
        boolean view = isView(session, name);
        List<IndexDef> indexes = indexes(session, name);
        List<ForeignKeyDef> foreignKeys = foreignKeys(session, name);
        List<TriggerDef> triggers = triggers(session, name);
        // ⭐ 列来源由方言决定（SQLite 的结果集元数据不带声明类型与默认值，要读 PRAGMA）。
        List<ColumnDef> baseColumns = session.dialect().readsColumnsViaJdbcMetadata()
                ? table.columns() : sqliteColumns(session, name);
        List<ColumnDef> columns = markUnique(baseColumns, indexes);
        String ddl = ddl(session, name, view, columns, table.primaryKeys(),
                indexes, foreignKeys, triggers);
        return new TableStructure(name, view, columns, table.primaryKeys(),
                indexes, foreignKeys, triggers, ddl);
    }

    /**
     * SQLite 的结果集元数据不带 DEFAULT/声明类型，结构页必须读 PRAGMA。
     * 数据页仍走 {@link #describe}，避免把结构专用字段混进查询结果。
     */
    private List<ColumnDef> sqliteColumns(DbSession session, String name) throws SQLException {
        List<ColumnDef> columns = new ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     session.dialect().columnsSql(name))) {
            while (rs.next()) {
                String type = rs.getString("type");
                columns.add(new ColumnDef(
                        rs.getString("name"),
                        typeOf(type),
                        rs.getInt("notnull") == 0,
                        rs.getInt("pk") > 0,
                        rs.getString("dflt_value"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        type));
            }
        }
        return List.copyOf(columns);
    }

    private static ColumnType typeOf(String rawType) {
        String type = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        if (type.contains("JSON")) {
            return ColumnType.JSON;
        }
        if (type.contains("UUID")) {
            return ColumnType.UUID;
        }
        if (type.contains("INT")) {
            return ColumnType.NUMBER;
        }
        if (type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB")
                || type.contains("DEC") || type.contains("NUM")) {
            return ColumnType.NUMBER;
        }
        if (type.contains("BOOL")) {
            return ColumnType.BOOLEAN;
        }
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return ColumnType.TIMESTAMP;
        }
        if (type.contains("DATE")) {
            return ColumnType.DATE;
        }
        if (type.contains("TIME")) {
            return ColumnType.TIME;
        }
        if (type.contains("BLOB") || type.contains("BINARY")) {
            return ColumnType.BINARY;
        }
        return ColumnType.TEXT;
    }

    private List<IndexDef> indexes(DbSession session, String name) throws SQLException {
        // ⭐ SQLite 的 getIndexInfo 不可用，要读 PRAGMA。
        if (!session.dialect().readsIndexesViaJdbcMetadata()) {
            return sqliteIndexes(session, name);
        }

        DatabaseMetaData meta = session.connection().getMetaData();
        Map<String, IndexAccumulator> found = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(currentCatalog(session),
                currentSchema(session), name, false, false)) {
            while (rs.next()) {
                String index = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (index == null || index.isBlank() || column == null || column.isBlank()) {
                    continue;
                }
                int position = rs.getInt("ORDINAL_POSITION");
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                found.computeIfAbsent(index, key -> new IndexAccumulator(unique))
                        .columns.put(position > 0 ? position : found.size() + 1, column);
            }
        } catch (SQLException ignored) {
            // 某些驱动/权限不允许读索引；结构页仍应展示列与外键。
        }
        return found.entrySet().stream()
                .map(entry -> new IndexDef(entry.getKey(), entry.getValue().unique,
                        entry.getValue().ordered(), ""))
                .sorted(Comparator.comparing(IndexDef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<IndexDef> sqliteIndexes(DbSession session, String name) throws SQLException {
        List<IndexDef> result = new ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(
                     session.dialect().indexesSql(name))) {
            while (rs.next()) {
                String indexName = rs.getString("name");
                if (indexName == null || indexName.isBlank()) {
                    continue;
                }
                boolean unique = rs.getInt("unique") != 0;
                String origin = rs.getString("origin");
                List<String> columns = new ArrayList<>();
                try (Statement info = session.connection().createStatement();
                     ResultSet columnsRs = info.executeQuery(
                             session.dialect().indexColumnsSql(indexName))) {
                    while (columnsRs.next()) {
                        String column = columnsRs.getString("name");
                        if (column != null) {
                            columns.add(column);
                        }
                    }
                }
                result.add(new IndexDef(indexName, unique, columns, origin));
            }
        }
        result.sort(Comparator.comparing(IndexDef::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private List<ForeignKeyDef> foreignKeys(DbSession session, String name) throws SQLException {
        Map<String, ForeignKeyAccumulator> found = new LinkedHashMap<>();
        try (ResultSet rs = session.connection().getMetaData()
                .getImportedKeys(currentCatalog(session), currentSchema(session), name)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                String refTable = rs.getString("PKTABLE_NAME");
                String update = rs.getString("UPDATE_RULE");
                String delete = rs.getString("DELETE_RULE");
                if (refTable == null) {
                    continue;
                }
                String key = fkName == null || fkName.isBlank()
                        ? "~" + refTable + "~" + update + "~" + delete
                        : fkName;
                ForeignKeyAccumulator accumulator = found.computeIfAbsent(key,
                        ignored -> new ForeignKeyAccumulator(fkName, refTable, update, delete));
                int position = rs.getInt("KEY_SEQ");
                accumulator.columns.put(position > 0 ? position : accumulator.columns.size() + 1,
                        rs.getString("FKCOLUMN_NAME"));
                accumulator.referencedColumns.put(
                        position > 0 ? position : accumulator.referencedColumns.size() + 1,
                        rs.getString("PKCOLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            // 同索引：权限/驱动差异不应让整个结构页失败。
        }

        List<ForeignKeyDef> result = new ArrayList<>();
        int anonymous = 0;
        for (ForeignKeyAccumulator value : found.values()) {
            String fkName = value.name;
            if (fkName == null || fkName.isBlank()) {
                anonymous++;
                fkName = "fk_" + name + "_" + anonymous;
            }
            result.add(new ForeignKeyDef(fkName, value.ordered(value.columns),
                    value.referencedTable, value.ordered(value.referencedColumns),
                    value.updateRule, value.deleteRule));
        }
        result.sort(Comparator.comparing(ForeignKeyDef::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private List<TriggerDef> triggers(DbSession session, String name) throws SQLException {
        List<TriggerDef> result = new ArrayList<>();
        // ⭐ 触发器查询由方言给 —— 以前这里是 switch，加一种库要改这里。
        String sql = session.dialect().triggersSql(name);
        try (var statement = session.connection().prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String triggerName = rs.getString(1);
                    if (!session.dialect().readsIndexesViaJdbcMetadata()) {
                        // SQLite 的第 2 列是触发器 DDL，时机与事件要从 DDL 里解析。
                        String ddl = rs.getString(2);
                        result.add(new TriggerDef(triggerName, timingOf(ddl), eventOf(ddl), ddl));
                    } else {
                        result.add(new TriggerDef(triggerName, rs.getString(2),
                                rs.getString(3), rs.getString(4)));
                    }
                }
            }
        } catch (SQLException ignored) {
            // 没有触发器权限时返回空列表。
        }
        return List.copyOf(result);
    }

    private String ddl(DbSession session, String name, boolean view, List<ColumnDef> columns,
                       List<String> primaryKeys, List<IndexDef> indexes,
                       List<ForeignKeyDef> foreignKeys, List<TriggerDef> triggers)
            throws SQLException {
        // ⭐ DDL 来源完全由方言决定 —— 这一层现在没有一行方言专属的 SQL：
        //    SQLite 读 sqlite_master、MySQL 走 SHOW CREATE TABLE、
        //    PostgreSQL 只能由内省生成（UI 会标注「等价 DDL」）。
        String fromDatabase = session.dialect().readDdl(session.connection(), name, view);
        if (fromDatabase != null && !fromDatabase.isBlank()) {
            return fromDatabase;
        }
        if (view) {
            String viewDdl = session.dialect().readViewDdl(session.connection(), name);
            if (viewDdl != null && !viewDdl.isBlank()) {
                return viewDdl;
            }
        }
        return StructureSqlBuilder.createTableSql(session, name, columns, primaryKeys,
                indexes, foreignKeys);
    }




    private boolean isView(DbSession session, String name) throws SQLException {
        try (ResultSet rs = session.connection().getMetaData().getTables(
                currentCatalog(session), currentSchema(session), name,
                new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    String type = rs.getString("TABLE_TYPE");
                    return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW");
                }
            }
        }
        return false;
    }

    private static String timingOf(String ddl) {
        return match(ddl, "(?i)\\b(BEFORE|AFTER|INSTEAD\\s+OF)\\b");
    }

    private static String eventOf(String ddl) {
        return match(ddl, "(?i)\\b(INSERT|UPDATE|DELETE)\\b");
    }

    private static String match(String value, String pattern) {
        if (value == null) {
            return "";
        }
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(value);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    private static String currentCatalog(DbSession session) {
        try {
            return session.dialect().currentCatalog(session.connection());
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String currentSchema(DbSession session) {
        try {
            return session.dialect().currentSchema(session.connection());
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static final class IndexAccumulator {
        private final boolean unique;
        private final Map<Integer, String> columns = new LinkedHashMap<>();

        private IndexAccumulator(boolean unique) {
            this.unique = unique;
        }

        private List<String> ordered() {
            return columns.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();
        }
    }

    private static final class ForeignKeyAccumulator {
        private final String name;
        private final String referencedTable;
        private final String updateRule;
        private final String deleteRule;
        private final Map<Integer, String> columns = new LinkedHashMap<>();
        private final Map<Integer, String> referencedColumns = new LinkedHashMap<>();

        private ForeignKeyAccumulator(String name, String referencedTable,
                                      String updateRule, String deleteRule) {
            this.name = name;
            this.referencedTable = referencedTable;
            this.updateRule = updateRule;
            this.deleteRule = deleteRule;
        }

        private List<String> ordered(Map<Integer, String> values) {
            return values.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
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
