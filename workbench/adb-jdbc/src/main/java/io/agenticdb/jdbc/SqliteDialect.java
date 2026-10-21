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
    public String renameColumnSql(String table, String oldName, String newName) {
        return "ALTER TABLE " + quote(table)
                + " RENAME COLUMN " + quote(oldName) + " TO " + quote(newName);
    }

    @Override
    public String dropColumnSql(String table, String columnName) {
        return "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(columnName);
    }

    @Override
    public String addColumnSql(String table, ColumnSpec column) {
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + columnDefinition(column, true);
    }

    @Override
    public String explainSql(String sql) {
        return "EXPLAIN QUERY PLAN " + sql;
    }
}
