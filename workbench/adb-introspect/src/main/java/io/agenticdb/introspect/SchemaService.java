package io.agenticdb.introspect;

import io.agenticdb.jdbc.DbSession;
import io.agenticdb.jdbc.Dialect;
import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.QueryLimits;
import io.agenticdb.model.RowPage;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
        TABLE,
        VIEW,
        /** ⭐ S3：过程 / 函数 / 触发器也进对象树（Navicat 的对象浏览层）。 */
        PROCEDURE,
        FUNCTION,
        TRIGGER,
        /**
         * ⭐ O1：调度事件（MySQL EVENT）。
         *
         * <p>只有 MySQL 有这个概念（PostgreSQL 用 {@code pg_cron} 扩展、
         * SQLite 完全没有）—— 所以**只有 MySQL 的对象树会出现「事件」分组**。
         */
        EVENT
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

    public record TableSchema(String name, List<ColumnDef> columns, List<String> primaryKeys,
                              String autoIncrementColumn) {
        public TableSchema {
            columns = List.copyOf(columns == null ? List.of() : columns);
            primaryKeys = List.copyOf(primaryKeys == null ? List.of() : primaryKeys);
            autoIncrementColumn = autoIncrementColumn == null || autoIncrementColumn.isBlank()
                    ? null : autoIncrementColumn;
        }

        public TableSchema(String name, List<ColumnDef> columns) {
            this(name, columns, List.of(), null);
        }

        public TableSchema(String name, List<ColumnDef> columns, List<String> primaryKeys) {
            this(name, columns, primaryKeys, null);
        }

        /**
         * 某列是不是**自增列**？
         *
         * <p>新增行时这一列应该留空 —— ���数据库自动赋值。
         */
        public boolean autoIncrement(String columnName) {
            return autoIncrementColumn != null
                    && autoIncrementColumn.equalsIgnoreCase(columnName);
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

        // ⭐ S3：过程与函数走 JDBC 元数据（可移植），触发器各方言自己给 SQL。
        addProcedures(meta, catalog, schema, objects);
        addFunctions(meta, catalog, schema, objects);
        addTriggers(session, objects);
        // ⭐ O1：调度事件（只有 MySQL 有；其它方言安静跳过）。
        addEvents(session, objects);

        objects.sort(Comparator.comparing(SchemaObject::kind)
                .thenComparing(SchemaObject::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(objects);
    }

    /** 过程。驱动不支持时安静跳过 —— 不该因为一个可选能力让整个对象树失败。 */
    private static void addProcedures(DatabaseMetaData meta, String catalog, String schema,
                                      List<SchemaObject> objects) {
        try (ResultSet rs = meta.getProcedures(catalog, schema, "%")) {
            while (rs.next()) {
                String name = rs.getString("PROCEDURE_NAME");
                if (isUsableObjectName(name)) {
                    objects.add(new SchemaObject(name, ObjectKind.PROCEDURE));
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            // 驱动不支持 getProcedures（很多驱动不支持）。
        }
    }

    /** 函数。 */
    private static void addFunctions(DatabaseMetaData meta, String catalog, String schema,
                                     List<SchemaObject> objects) {
        try (ResultSet rs = meta.getFunctions(catalog, schema, "%")) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                if (isUsableObjectName(name)) {
                    objects.add(new SchemaObject(name, ObjectKind.FUNCTION));
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            // 同上。
        }
    }

    /** 触发器。 */
    private void addTriggers(DbSession session, List<SchemaObject> objects) {
        String sql = session.dialect().triggersListSql();
        if (sql == null) {
            return;
        }
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (isUsableObjectName(name)) {
                    objects.add(new SchemaObject(name, ObjectKind.TRIGGER));
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            // 没有触发器权限 / 驱动不支持时安静跳过。
        }
    }

    /**
     * ⭐ O1：调度事件。
     *
     * <p>只有 MySQL 有 {@code information_schema.EVENTS}；其它方言返回 null →
     * 对象树里**不会出现**「事件」分组（不假装有）。
     */
    private void addEvents(DbSession session, List<SchemaObject> objects) {
        String sql = session.dialect().eventsListSql();
        if (sql == null) {
            return;
        }
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (isUsableObjectName(name)) {
                    objects.add(new SchemaObject(name, ObjectKind.EVENT));
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            // 没权限 / 驱动不支持时安静跳过。
        }
    }

    /** 过滤系统内置对象（pg_ 开头、information_schema 之类）。 */
    private static boolean isUsableObjectName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith("pg_") && !lower.startsWith("sys")
                && !lower.startsWith("information_") && !lower.startsWith("sqlite_");
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

    /**
     * ⭐ U1：连接可见的 catalog（数据库）列表。
     *
     * <p>只有「切 catalog 要重连」的方言有（MySQL，见
     * {@link io.agenticdb.jdbc.Dialect#catalogsSql()}）；其它方言返回**空列表**
     * —— 于是对象树里不会出现 catalog 层，不假装有。
     *
     * <p>系统库（{@code information_schema} / {@code mysql} /
     * {@code performance_schema} / {@code sys}）被过滤掉：它们是服务本身，
     * 不是用户的库（Navicat 也是把它们单独分组）。过滤放在 Java 而不是 SQL 里，
     * 是为了能对着替身会话单测。
     */
    public List<String> listCatalogs(DbSession session) throws SQLException {
        String sql = session.dialect().catalogsSql();
        if (sql == null) {
            return List.of();
        }
        List<String> catalogs = new ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.isBlank() && isUserCatalog(name)) {
                    catalogs.add(name);
                }
            }
        }
        catalogs.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(catalogs);
    }

    /** 系统库不进对象树（大小写不敏感）。 */
    private static boolean isUserCatalog(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.equals("information_schema") && !lower.equals("mysql")
                && !lower.equals("performance_schema") && !lower.equals("sys");
    }

    /**
     * ⭐ P5-4：当前 schema 下的序列名。
     *
     * <p>只有 PostgreSQL 有序列；SQLite / MySQL 返回空列表（不假装有）。
     * 走 JDBC 元数据的 {@code SEQUENCE} 类型，驱动不支持时安静返回空。
     */
    public List<String> listSequences(DbSession session) {
        List<String> sequences = new ArrayList<>();
        try (ResultSet rs = session.connection().getMetaData().getTables(
                currentCatalog(session), currentSchema(session), "%",
                new String[]{"SEQUENCE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !name.isBlank() && !isSystemObject(name)) {
                    sequences.add(name);
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            // 驱动不支持序列（SQLite / MySQL）→ 空列表，不让对象树失败。
            return List.of();
        }
        sequences.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(sequences);
    }

    /**
     * 表清单的元信息（Navicat「表清单」那一行的内容）。
     *
     * <p>方言能一条 SQL 取全就取（PG / MySQL）；取不到（SQLite 没有表大小与创建时间）
     * 就退化成「逐表 COUNT(*)」—— 一次全表扫描，所以**不要**在对象树展开时顺带调用。
     */
    public List<TableMeta> listTableMeta(DbSession session) throws SQLException {
        List<TableMeta> result = new ArrayList<>();
        String sql = session.dialect().tableMetaSql();
        if (sql != null) {
            try (Statement statement = session.connection().createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    result.add(new TableMeta(rs.getString("name"),
                            longOrNull(rs, "rows"), longOrNull(rs, "size_bytes"),
                            rs.getString("engine"), rs.getString("created_at"),
                            rs.getString("updated_at"), rs.getString("collation_name"),
                            rs.getString("comment_text")));
                }
            } catch (SQLException ignored) {
                // 取不到就退化成只算行数。
                result.clear();
            }
        }
        if (result.isEmpty()) {
            // 退路：只算行数。演示库表少，代价可以接受；大库要靠调用方限流。
            for (SchemaObject object : listObjects(session)) {
                if (object.kind() != ObjectKind.TABLE) {
                    continue;
                }
                Long rows = null;
                try {
                    rows = count(session, object.name());
                } catch (SQLException ignored) {
                    rows = null;
                }
                result.add(new TableMeta(object.name(), rows, null, null, null));
            }
        }
        return List.copyOf(result);
    }

    /**
     * ⭐ UX-6：表的物理选项（引擎 / 排序规则 / 自增值 / 行格式）。
     *
     * <p>只有方言提供 {@link Dialect#tableOptionsSql()} 时才查一次；
     * 其它方言直接返回 {@link TableOptions#unavailable()}（不执行任何 SQL）。
     *
     * <p>⚠️ 这一块是**锦上添花**：查询失败（老版本库 / 权限不足 / 视图）时
     * 返回空选项，让结构页的其它页签照常可用 —— 不能因为「选项读不到」
     * 就把整张结构页变成错误页。
     */
    public TableOptions tableOptions(DbSession session, String table) {
        String sql = session.dialect().tableOptionsSql();
        if (sql == null || table == null || table.isBlank()) {
            return TableOptions.unavailable();
        }
        try (PreparedStatement statement = session.connection().prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return TableOptions.unavailable();
                }
                long autoIncrement = rs.getLong("auto_increment");
                boolean autoIncrementMissing = rs.wasNull();
                return new TableOptions(rs.getString("engine"),
                        rs.getString("collation_name"),
                        autoIncrementMissing ? null : autoIncrement,
                        rs.getString("row_format"));
            }
        } catch (SQLException | RuntimeException ignored) {
            return TableOptions.unavailable();
        }
    }

    /** 读一个可能为 NULL 的 BIGINT 列。 */
    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** 用方言的空页探测语句拿结果集元数据，表与视图共用。 */
    public TableSchema describe(DbSession session, String name) throws SQLException {
        String sql = session.dialect().probeColumnsSql(name);
        RowPage page = session.query(sql, 1);
        Set<String> primaryKeys = primaryKeys(session, name);
        // ⭐ 自增列：新增行时这一列留空，交给数据库赋值。
        String autoIncrement = autoIncrementColumn(session, name);
        if (primaryKeys.isEmpty()) {
            return new TableSchema(name, page.columns(), List.of(), autoIncrement);
        }
        List<ColumnDef> columns = page.columns().stream()
                .map(column -> primaryKeys.contains(column.name().toLowerCase(Locale.ROOT))
                        ? withPrimaryKey(column) : column)
                .toList();
        List<String> orderedKeys = page.columns().stream()
                .map(ColumnDef::name)
                .filter(key -> primaryKeys.contains(key.toLowerCase(Locale.ROOT)))
                .toList();
        return new TableSchema(name, columns, orderedKeys, autoIncrement);
    }

    /**
     * 探测自增列。
     *
     * <p>探测不到（方言不支持 / 出错）就当没有 —— 探测是**锦上添花**，
     * 不该让整张表的元信息读不出来。
     */
    private static String autoIncrementColumn(DbSession session, String name) {
        try {
            return session.dialect().autoIncrementColumn(session.connection(), name);
        } catch (Exception e) {
            return null;
        }
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

    private static ColumnDef withComment(ColumnDef column, String comment) {
        return new ColumnDef(column.name(), column.type(), column.nullable(),
                column.primaryKey(), column.defaultValue(), comment, column.enumValues(),
                column.charMaxLength(), column.numericPrecision(), column.numericScale(),
                column.unique(), column.rawType());
    }

    private static ColumnDef withDefault(ColumnDef column, String defaultValue) {
        return new ColumnDef(column.name(), column.type(), column.nullable(),
                column.primaryKey(), defaultValue, column.comment(), column.enumValues(),
                column.charMaxLength(), column.numericPrecision(), column.numericScale(),
                column.unique(), column.rawType());
    }

    /**
     * ⭐ P5-4：把 JDBC 元数据里的 {@code COLUMN_DEF} 并进列定义。
     *
     * <p>为什么必须读回来：结构页把内省结果当基底，只覆盖用户改过的字段。
     * 基底里没有默认值 → PostgreSQL 的改列语句会下发
     * <b>{@code ALTER COLUMN … DROP DEFAULT}</b> —— 用户只改了个类型，
     * 默认值却被静默删掉（数据库不报错，下一次插入才出问题）。
     *
     * <p>读不到（驱动不提供 / 权限不够）就当没有 —— 探测是锦上添花。
     */
    private static List<ColumnDef> withDefaults(DbSession session, String table,
                                                List<ColumnDef> columns) {
        if (columns.isEmpty()) {
            return columns;
        }
        Map<String, String> defaults = new HashMap<>();
        try (ResultSet rs = session.connection().getMetaData()
                .getColumns(currentCatalog(session), currentSchema(session), table, "%")) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                String value = rs.getString("COLUMN_DEF");
                if (column != null && value != null && !value.isBlank()) {
                    defaults.put(column.toLowerCase(Locale.ROOT), value);
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            return columns;
        }
        if (defaults.isEmpty()) {
            return columns;
        }
        return columns.stream()
                .map(column -> {
                    String value = defaults.get(column.name().toLowerCase(Locale.ROOT));
                    return value == null || value.equals(column.defaultValue())
                            ? column : withDefault(column, value);
                })
                .toList();
    }

    /**
     * 把列注释并进列定义。
     *
     * <p>⭐ 为什么必须读回来：改列时 UI 拿内省结果当基底，只覆盖用户改过的字段。
     * 基底里没有注释 → 生成的 {@code MODIFY COLUMN} / {@code COMMENT ON} 就没有
     * {@code COMMENT} 子句 → <b>改一次列，注释被静默抹掉</b>。
     * 这不是显示问题，是丢数据，且没有撤销路径。
     *
     * <p>读不到（方言不支持 / 权限不够 / 表是视图）就当没有 —— 探测是锦上添花，
     * 不该让整张表的结构读不出来。
     */
    private static List<ColumnDef> withComments(DbSession session, String name,
                                                List<ColumnDef> columns) {
        String sql = session.dialect().columnCommentsSql(name);
        if (sql == null || columns.isEmpty()) {
            return columns;
        }
        try {
            return applyComments(columns, session.query(sql, Math.max(64, columns.size() * 2)));
        } catch (Exception e) {
            return columns;
        }
    }

    /**
     * 纯函数版：给定「列名 → 注释」的查询结果，并进列定义。
     *
     * <p>单独抽出来是因为 {@code structure()} 要真连接、测不动；
     * 而「注释有没有被并进去」恰恰是最该被门禁钉住的那条。
     *
     * @param page 两列结果集：第 1 列列名、第 2 列注释
     */
    static List<ColumnDef> applyComments(List<ColumnDef> columns, RowPage page) {
        if (page == null || columns.isEmpty()) {
            return columns;
        }
        List<ColumnDef> meta = page.columns();
        if (meta.size() < 2) {
            return columns;
        }
        // ⭐ 按元数据里的列标签取值，不要靠 Map 的迭代顺序。
        String nameKey = meta.get(0).name();
        String commentKey = meta.get(1).name();
        Map<String, String> comments = new HashMap<>();
        for (Map<String, Object> row : page.rows()) {
            Object columnName = row.get(nameKey);
            Object comment = row.get(commentKey);
            if (columnName == null || comment == null) {
                continue;
            }
            String text = String.valueOf(comment);
            if (!text.isBlank()) {
                comments.put(String.valueOf(columnName).toLowerCase(Locale.ROOT), text);
            }
        }
        if (comments.isEmpty()) {
            return columns;
        }
        return columns.stream()
                .map(column -> {
                    String comment = comments.get(column.name().toLowerCase(Locale.ROOT));
                    return comment == null || comment.equals(column.comment())
                            ? column : withComment(column, comment);
                })
                .toList();
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
        // ⭐ P5-4：PG / MySQL 的默认值只能从 getColumns() 的 COLUMN_DEF 读 ——
        //    以前结构页拿到的 default 恒为 null，改列时 PG 会**静默 DROP DEFAULT**。
        List<ColumnDef> baseColumns = session.dialect().readsColumnsViaJdbcMetadata()
                ? withDefaults(session, name, table.columns()) : sqliteColumns(session, name);
        // ⭐ 注释在 markUnique 之后合并：markUnique 会重建 ColumnDef，先并会被丢掉。
        List<ColumnDef> columns = withComments(session, name, markUnique(baseColumns, indexes));
        String ddl = ddl(session, name, view, columns, table.primaryKeys(),
                indexes, foreignKeys, triggers);
        // ⭐ CHECK 约束：以前根本不采 —— 于是 SQLite 改列整表重建时约束静默消失（D3）。
        List<CheckDef> checks = checks(session, name);
        // ⭐ 表注释（B5-S4）：以前**连看都看不了** —— 现在能读回来，结构页能显示。
        String comment = tableComment(session, name);
        return new TableStructure(name, view, columns, table.primaryKeys(),
                indexes, foreignKeys, triggers, checks, comment, ddl);
    }

    /**
     * ⭐ UX-6：概览页只显示索引数量 —— 不为一行数字读整张表的结构。
     */
    public int indexCount(DbSession session, String name) throws SQLException {
        return indexes(session, name).size();
    }

    /**
     * ⭐ 函数 / 存储过程的**定义体**（O2）。
     *
     * <p>以前对象树里只有**名字** —— 点开也看不到参数、返回类型、函数体。
     * 现在三种方言都能读回来（来源见 {@code Dialect.readRoutineDefinition}）。
     *
     * @param procedure true = 存储过程
     * @return 定义体原文；读不到时是空串
     */
    public String routineDefinition(DbSession session, String name, boolean procedure) {
        if (name == null || name.isBlank()) {
            return "";
        }
        try {
            String body = session.dialect()
                    .readRoutineDefinition(session.connection(), name, procedure);
            return body == null ? "" : body;
        } catch (SQLException | RuntimeException e) {
            // 读不到就显示「读不到」，不该让整个页面打不开。
            return "";
        }
    }

    /**
     * ⭐ U6：服务器信息（版本 + 当前用户）。
     *
     * @return 版本 / 用户；读不到时是空串
     */
    public String[] serverInfo(DbSession session) {
        String sql = session.dialect().serverInfoSql();
        if (sql == null) {
            return new String[]{"", ""};
        }
        try (java.sql.Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                String version = rs.getString(1);
                String user = rs.getString(2);
                return new String[]{version == null ? "" : version, user == null ? "" : user};
            }
        } catch (SQLException | RuntimeException ignored) {
            // 读不到就显示「—」，不该让连接失败。
        }
        return new String[]{"", ""};
    }

    /**
     * ⭐ O1：调度事件的定义体。
     *
     * <p>只有 MySQL 有事件 → 其它方言返回空串。
     */
    public String eventDefinition(DbSession session, String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        try {
            String body = session.dialect().readEventDefinition(session.connection(), name);
            return body == null ? "" : body;
        } catch (SQLException | RuntimeException e) {
            return "";
        }
    }

    /**
     * 表注释。
     *
     * <p>SQLite 没有表注释 → 返回空串（结构页显示「—」，不假装有）。
     */
    private String tableComment(DbSession session, String name) {
        String sql = session.dialect().tableCommentQuery(name);
        if (sql == null) {
            return "";
        }
        try (java.sql.PreparedStatement statement =
                     session.connection().prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String comment = rs.getString(1);
                    return comment == null ? "" : comment;
                }
            }
        } catch (SQLException | RuntimeException e) {
            // 读不到就当没有 —— 注释不是关键路径，不该让整个结构页打不开。
            return "";
        }
        return "";
    }

    /**
     * CHECK 约束。
     *
     * <p>两条路：
     * <ul>
     *   <li><b>SQLite</b>：只能从 {@code CREATE TABLE} 原文里解析
     *       （{@code CheckParser}）—— 没有 PRAGMA 能读；</li>
     *   <li><b>PostgreSQL / MySQL</b>：走方言给的查询。</li>
     * </ul>
     * 查不到（权限不足 / 方言不支持 / MySQL 5.7）就返回空列表，
     * **不让结构页整个挂掉**。
     */
    private List<CheckDef> checks(DbSession session, String name) {
        Dialect dialect = session.dialect();
        try {
            if (dialect.parsesChecksFromDdl()) {
                String ddl = dialect.readDdl(session.connection(), name, false);
                return CheckParser.extract(ddl);
            }
            String sql = dialect.checksSql(name);
            if (sql == null) {
                return List.of();
            }
            List<CheckDef> checks = new ArrayList<>();
            // ⭐ P5-4：方言 SQL 里表名是 `?` 占位符 —— 以前用 createStatement()
            //    直接把带 `?` 的文本发出去，PG / MySQL 一律语法错误，于是 CHECK
            //    永远读不到（还被 catch 吞成「没有约束」）。
            try (java.sql.PreparedStatement statement =
                         session.connection().prepareStatement(sql)) {
                statement.setString(1, name);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        checks.add(new CheckDef(rs.getString(1),
                                stripOuterParens(rs.getString(2))));
                    }
                }
            }
            return checks;
        } catch (SQLException | RuntimeException e) {
            // 读不到就当没有 —— 宁可少显示，也不要让整个结构页打不开。
            return List.of();
        }
    }

    /** 剥掉 {@code pg_get_constraintdef} 那种「外层多一圈括号」的写法。 */
    private static String stripOuterParens(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (text.toUpperCase(Locale.ROOT).startsWith("CHECK")) {
            text = text.substring("CHECK".length()).trim();
        }
        if (text.startsWith("(") && text.endsWith(")") && balanced(text)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    /** 括号是否配对（外层那对是否真的包住全部内容）。 */
    private static boolean balanced(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < text.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
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
                boolean primaryKey = rs.getInt("pk") > 0;
                // ⭐ INTEGER PRIMARY KEY 是 rowid 的别名，SQLite 保证它非空
                //    （插入 NULL 会自动分配 rowid），但 PRAGMA 里 notnull 仍是 0。
                //    照抄 notnull 会让结构页把主键显示成「可空 YES」。
                //    ⚠️ 只有 INTEGER 是别名；TEXT PRIMARY KEY 在 SQLite 里
                //    **真的允许 NULL**（历史遗留行为），不能一并改成非空。
                boolean rowidAlias = primaryKey && "INTEGER".equalsIgnoreCase(
                        type == null ? "" : type.trim());
                columns.add(new ColumnDef(
                        rs.getString("name"),
                        typeOf(type),
                        rs.getInt("notnull") == 0 && !rowidAlias,
                        primaryKey,
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
                // ⭐ 动作码必须按方言翻译（各驱动码表不同）——
                //    不翻译就会把「3」写进 ON UPDATE 3，那是一条语法错误的 SQL。
                String update = session.dialect().foreignKeyActionName(rs.getString("UPDATE_RULE"));
                String delete = session.dialect().foreignKeyActionName(rs.getString("DELETE_RULE"));
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

    /**
     * ⭐ P5-4：谁引用了这张表（{@code getExportedKeys}）。
     *
     * <p>{@link #structure} 的外键清单只回答「我的列指向谁」。删表 / 改主键前，
     * 用户还需要知道反向关系：有哪些子表在引用我。
     *
     * <p>读不到（驱动不支持 / 权限不够）时返回空列表 —— 不让结构页失败。
     */
    public List<ExportedKeyDef> exportedKeys(DbSession session, String name)
            throws SQLException {
        Map<String, ExportedKeyAccumulator> found = new LinkedHashMap<>();
        try (ResultSet rs = session.connection().getMetaData()
                .getExportedKeys(currentCatalog(session), currentSchema(session), name)) {
            while (rs.next()) {
                String childTable = rs.getString("FKTABLE_NAME");
                if (childTable == null || childTable.isBlank()) {
                    continue;
                }
                String fkName = rs.getString("FK_NAME");
                String updateRule = session.dialect()
                        .foreignKeyActionName(rs.getString("UPDATE_RULE"));
                String deleteRule = session.dialect()
                        .foreignKeyActionName(rs.getString("DELETE_RULE"));
                String key = (fkName == null || fkName.isBlank() ? "~" : fkName)
                        + '\u0000' + childTable;
                ExportedKeyAccumulator accumulator = found.computeIfAbsent(key,
                        ignored -> new ExportedKeyAccumulator(fkName, childTable,
                                updateRule, deleteRule));
                int position = rs.getInt("KEY_SEQ");
                accumulator.columns.put(
                        position > 0 ? position : accumulator.columns.size() + 1,
                        rs.getString("FKCOLUMN_NAME"));
                accumulator.referencedColumns.put(
                        position > 0 ? position : accumulator.referencedColumns.size() + 1,
                        rs.getString("PKCOLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            return List.of();
        }

        List<ExportedKeyDef> result = new ArrayList<>();
        int anonymous = 0;
        for (ExportedKeyAccumulator value : found.values()) {
            String fkName = value.name;
            if (fkName == null || fkName.isBlank()) {
                anonymous++;
                fkName = "fk_" + value.childTable + "_" + anonymous;
            }
            result.add(new ExportedKeyDef(fkName, value.childTable,
                    value.ordered(value.columns), value.ordered(value.referencedColumns),
                    value.updateRule, value.deleteRule));
        }
        result.sort(Comparator.comparing(ExportedKeyDef::childTable,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ExportedKeyDef::name, String.CASE_INSENSITIVE_ORDER));
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

    private static final class ExportedKeyAccumulator {
        private final String name;
        private final String childTable;
        private final String updateRule;
        private final String deleteRule;
        private final Map<Integer, String> columns = new LinkedHashMap<>();
        private final Map<Integer, String> referencedColumns = new LinkedHashMap<>();

        private ExportedKeyAccumulator(String name, String childTable,
                                       String updateRule, String deleteRule) {
            this.name = name;
            this.childTable = childTable;
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
     * 服务端分页 SQL。分页子句由方言给（D-2/D-3：SQLite / MySQL / PostgreSQL 是
     * {@code LIMIT n OFFSET m}，SQL Server / Oracle 是 {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}）。
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
        return session.dialect().plainPageSql(name, pageSize, safeOffset);
    }

    private static boolean isSystemObject(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("sqlite_") || lower.startsWith("pg_") || lower.startsWith("mysql.");
    }
}
