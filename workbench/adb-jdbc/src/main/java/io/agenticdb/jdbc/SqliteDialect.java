package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 方言。
 *
 * <p>SQLite 是三种库里最「特殊」的一个：
 * <ul>
 *   <li>没有 catalog / schema 概念；</li>
 *   <li>结果集元数据<b>不带</b>声明类型与默认值 —— 结构页必须读 {@code PRAGMA table_info}；</li>
 *   <li>{@code getIndexInfo} 不可用 —— 索引要读 {@code PRAGMA index_list}；</li>
 *   <li>没有列注释；</li>
 *   <li>{@code ALTER TABLE} 只能加列 —— 改列必须整表重建；</li>
 *   <li>新增 NOT NULL 列必须给默认值（否则已有行违反约束）。</li>
 * </ul>
 * 这些差异以前散落在五个文件的 {@code if (dialect == SQLITE)} 里，
 * 现在集中在这个类里。
 */
public final class SqliteDialect implements Dialect {

    @Override
    public DialectKind kind() {
        return DialectKind.SQLITE;
    }

    @Override
    public String displayName() {
        return "SQLite";
    }

    @Override
    public String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String jdbcUrl(String host, int port, String database) {
        return "jdbc:sqlite:" + database;
    }

    @Override
    public String driverClassName() {
        return "org.sqlite.JDBC";
    }

    @Override
    public int defaultPort() {
        return 0;
    }

    @Override
    public boolean pooled() {
        // 单文件连接，池化没有意义，还会让「改了文件要重新打开」变麻烦。
        return false;
    }

    @Override
    public boolean usesFilePath() {
        return true;
    }

    @Override
    public boolean supportsColumnComment() {
        return false;
    }

    @Override
    public boolean readsColumnsViaJdbcMetadata() {
        return false;
    }

    @Override
    public boolean readsIndexesViaJdbcMetadata() {
        return false;
    }

    @Override
    public boolean hasCatalog() {
        return false;
    }

    @Override
    public boolean hasSchema() {
        return false;
    }

    @Override
    public boolean rebuildsTableOnColumnChange() {
        return true;
    }

    @Override
    public boolean requiresDefaultForNotNullColumn() {
        return true;
    }

    @Override
    public String columnsSql(String table) {
        return "PRAGMA table_info(" + quote(table) + ")";
    }

    @Override
    public String indexesSql(String table) {
        return "PRAGMA index_list(" + quote(table) + ")";
    }

    @Override
    public String indexColumnsSql(String indexName) {
        return "PRAGMA index_info(" + quote(indexName) + ")";
    }

    @Override
    public String triggersSql(String table) {
        return "SELECT name, sql FROM sqlite_master "
                + "WHERE type = 'trigger' AND tbl_name = ? ORDER BY name";
    }

    /**
     * ⭐ SQLite 驱动的外键动作码**与 JDBC 标准不一样**（实测 Xerial 3.45）：
     * 0=CASCADE · 1=RESTRICT · 2=SET NULL · <b>3=NO ACTION</b> · 4=SET DEFAULT。
     *
     * <p>不翻译就会把「3」写进 {@code ON UPDATE 3} —— 语法错误，
     * 于是**任何带外键的表一改列就失败**（以前没人发现，因为没人改过带外键的表）。
     */
    @Override
    public String foreignKeyActionName(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return "NO ACTION";
        }
        String code = rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            case "0" -> "CASCADE";
            case "1" -> "RESTRICT";
            case "2" -> "SET NULL";
            case "3" -> "NO ACTION";
            case "4" -> "SET DEFAULT";
            default -> code;
        };
    }

    /**
     * SQLite 的函数 / 过程定义体就在 {@code sqlite_master.sql} 的原文里。
     */
    @Override
    public String readRoutineDefinition(Connection connection, String name, boolean procedure)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE name = ? "
                        + "AND type IN ('function', 'procedure')")) {
            statement.setString(1, name);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    String sql = rs.getString(1);
                    return sql == null ? "" : sql;
                }
            }
        }
        return "";
    }

    /**
     * ⭐ SQLite 的外键是 {@code CREATE TABLE} 的一部分 ——
     * {@code ALTER TABLE} 改不了它，所以增删外键必须整表重建。
     */
    /** SQLite：版本有，用户概念没有（本地文件）。 */
    @Override
    public String serverInfoSql() {
        return "SELECT sqlite_version(), ''";
    }

    @Override
    public boolean rebuildsTableOnForeignKeyChange() {
        return true;
    }

    /**
     * ⭐ SQLite 的 CHECK 约束只能从 {@code CREATE TABLE} 原文里解析 ——
     * 没有 PRAGMA、也没有 information_schema 能读它。
     */
    @Override
    public boolean parsesChecksFromDdl() {
        return true;
    }

    @Override
    public boolean readsDdlFromDatabase() {
        return true;
    }

    @Override
    public String readDdl(Connection connection, String table, boolean view) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE name = ? AND type IN ('table', 'view')")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    String ddl = rs.getString(1);
                    return ddl == null || ddl.isBlank() ? null : ddl;
                }
            }
        }
        return null;
    }

    @Override
    public String readViewDdl(Connection connection, String table) throws SQLException {
        // SQLite 的视图定义就在 sqlite_master 里，readDdl 已经能读到。
        return null;
    }

    @Override
    public String tableMetaSql() {
        // SQLite 没有表大小与创建时间（要 dbstat 虚拟表，还不一定编进来）——
        // 诚实地说「没有」，让上层退化成只算行数。
        return null;
    }

    @Override
    public String triggersListSql() {
        return "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name";
    }

    @Override
    public String setSchemaSql(String schema) {
        // SQLite 没有 schema 概念。
        return null;
    }

    @Override
    public List<String> dependentViews(Connection connection, String table) throws SQLException {
        // 整表重建前要先拆掉引用了这张表的视图，否则重建后视图定义会失效。
        List<String> names = new ArrayList<>();
        java.util.regex.Pattern reference = java.util.regex.Pattern.compile(
                "(?i)\\b" + java.util.regex.Pattern.quote(table) + "\\b");
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT name, sql FROM sqlite_master WHERE type = 'view' ORDER BY name")) {
            while (rs.next()) {
                String sql = rs.getString(2);
                if (sql != null && reference.matcher(sql).find()) {
                    names.add(rs.getString(1));
                }
            }
        } catch (SQLException ignored) {
            // 没有 sqlite_master 权限时让重建走到数据库自己的错误，UI 会展示原因。
        }
        return names;
    }

    @Override
    public String currentCatalog(Connection connection) throws SQLException {
        return null;
    }

    @Override
    public String currentSchema(Connection connection) throws SQLException {
        return null;
    }

    @Override
    public String columnDefinition(ColumnSpec column, boolean includeName) {
        String definition = (includeName ? quote(column.name()) + " " : "")
                + column.effectiveType();
        // ⭐ R31：SQLite 支持列级排序规则（NOCASE / RTRIM / BINARY），没有列级字符集。
        if (column.collation() != null) {
            definition += " COLLATE " + column.collation();
        }
        if (!column.nullable()) {
            definition += " NOT NULL";
        }
        if (column.defaultValue() != null) {
            definition += " DEFAULT " + column.defaultValue();
        }
        if (column.unique()) {
            definition += " UNIQUE";
        }
        // SQLite 没有列注释，写进定义只会变成语法错误。
        return definition;
    }

    /**
     * SQLite 的自增只有一种写法：单列 {@code INTEGER PRIMARY KEY AUTOINCREMENT}。
     *
     * <p>{@code INT PRIMARY KEY} 不是 rowid 别名、{@code TEXT PRIMARY KEY} 更不行 ——
     * 写错的建表语句能执行，但「自增」悄悄失效，所以这里直接拦下。
     */
    @Override
    public String createTableColumnDefinition(ColumnSpec column, boolean inlinePrimaryKey) {
        if (!column.autoIncrement()) {
            return Dialect.super.createTableColumnDefinition(column, inlinePrimaryKey);
        }
        if (!inlinePrimaryKey) {
            throw new IllegalArgumentException(
                    "SQLite 的自增列必须是单列 INTEGER 主键：" + column.name());
        }
        return columnDefinition(column, true) + " PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public boolean autoIncrementNeedsSinglePrimaryKey() {
        return true;
    }

    @Override
    public List<ColumnTypeOption> columnTypes() {
        return ColumnTypes.SQLITE;
    }

    @Override
    public List<String> columnCollationPresets() {
        return List.of("BINARY", "NOCASE", "RTRIM");
    }

    @Override
    public String autoIncrementProblem(ColumnSpec column) {
        if (!column.autoIncrement()) {
            return null;
        }
        if (!"INTEGER".equals(column.baseType())) {
            return "自增列必须是整型 —— SQLite 要求正好是 INTEGER（INT 不是 rowid 别名）："
                    + column.name() + " 现在是 " + column.effectiveType();
        }
        if (column.defaultValue() != null) {
            return "自增列不能同时设默认值：" + column.name();
        }
        return null;
    }

    @Override
    public void appendColumnComment(String table, ColumnSpec column, List<String> statements) {
        // SQLite 不支持列注释。
    }

    @Override
    public List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                              String oldRawType) {
        // SQLite 的 ALTER TABLE 改不了列 —— 走整表重建（由结构变更构造器处理）。
        throw new UnsupportedOperationException("SQLite 改列需要整表重建");
    }

    @Override
    public String renameColumnSql(String table, String oldName, String newName,
                                  io.agenticdb.model.ColumnDef oldColumn) {
        return "ALTER TABLE " + quote(table)
                + " RENAME COLUMN " + quote(oldName) + " TO " + quote(newName);
    }

    @Override
    public String dropColumnSql(String table, String columnName) {
        return "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(columnName);
    }

    @Override
    public String addColumnSql(String table, ColumnSpec column) {
        if (column.autoIncrement()) {
            // SQLite 的 ALTER TABLE … ADD COLUMN 不接受主键列（更不用说自增）。
            throw new IllegalArgumentException(
                    "SQLite 不能给已有表追加自增列 —— 只能建表时设置");
        }
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + columnDefinition(column, true);
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "ALTER TABLE " + quote(oldName) + " RENAME TO " + quote(newName);
    }

    @Override
    public String truncateTableSql(String table) {
        // ⭐ SQLite 没有 TRUNCATE —— 只有 DELETE FROM。
        //   写成 TRUNCATE 会在运行时才炸，所以这个差异必须在方言里兜住。
        return "DELETE FROM " + quote(table);
    }

    @Override
    public String dropTableSql(String table) {
        return "DROP TABLE " + quote(table);
    }

    @Override
    public String dropIndexSql(String table, String indexName) {
        return "DROP INDEX " + quote(indexName);
    }

    /**
     * SQLite 的主键索引叫 {@code sqlite_autoindex_*}，没有统一的 "PRIMARY"。
     * 好在 {@code IndexDef.autoCreated()} 已经按 {@code origin} 判过了。
     */
    @Override
    public String primaryIndexName() {
        return "";
    }

    /**
     * 自增列：SQLite 没有 AUTO_INCREMENT 语法，
     * 但**恰好声明为 {@code INTEGER PRIMARY KEY} 的单列主键**就是 rowid 的别名 ——
     * 插入时留空（或 NULL）由 SQLite 自动分配。
     *
     * <p>注意 SQLite 的规则很挑：类型必须**恰好**是 {@code INTEGER}
     * （{@code INT PRIMARY KEY} 不算），而且不能是复合主键。
     */
    @Override
    public String autoIncrementColumn(java.sql.Connection connection, String table)
            throws java.sql.SQLException {
        String name = null;
        int primaryKeyCount = 0;
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "PRAGMA table_info(" + quote(table) + ")")) {
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    primaryKeyCount++;
                    name = rs.getString("name");
                    String type = rs.getString("type");
                    if (type == null || !type.trim().equalsIgnoreCase("INTEGER")) {
                        name = null;
                    }
                }
            }
        }
        // 复合主键没有 rowid 别名
        return primaryKeyCount == 1 ? name : null;
    }

    @Override
    public String explainSql(String sql) {
        return "EXPLAIN QUERY PLAN " + sql;
    }
}
