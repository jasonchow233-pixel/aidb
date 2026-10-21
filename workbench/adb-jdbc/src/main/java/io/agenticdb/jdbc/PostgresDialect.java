package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 方言。
 *
 * <p>要点：标识符用双引号；DDL 只能由内省生成（没有 {@code SHOW CREATE TABLE}）；
 * 列注释用独立的 {@code COMMENT ON COLUMN} 语句；改列用 {@code ALTER COLUMN} 逐项改。
 */
public final class PostgresDialect implements Dialect {

    @Override
    public DialectKind kind() {
        return DialectKind.POSTGRESQL;
    }

    @Override
    public String displayName() {
        return "PostgreSQL";
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
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    @Override
    public String driverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    public int defaultPort() {
        return 5432;
    }

    @Override
    public boolean pooled() {
        return true;
    }

    @Override
    public boolean usesFilePath() {
        return false;
    }

    @Override
    public boolean supportsColumnComment() {
        return true;
    }

    @Override
    public boolean readsColumnsViaJdbcMetadata() {
        return true;
    }

    @Override
    public boolean readsIndexesViaJdbcMetadata() {
        return true;
    }

    @Override
    public boolean hasCatalog() {
        return true;
    }

    @Override
    public boolean hasSchema() {
        return true;
    }

    @Override
    public boolean rebuildsTableOnColumnChange() {
        return false;
    }

    @Override
    public boolean requiresDefaultForNotNullColumn() {
        return false;
    }

    @Override
    public String columnsSql(String table) {
        return null;
    }

    @Override
    public String indexesSql(String table) {
        return null;
    }

    @Override
    public String indexColumnsSql(String indexName) {
        return null;
    }

    @Override
    public String triggersSql(String table) {
        return "SELECT trigger_name, action_timing, event_manipulation, "
                + "action_statement FROM information_schema.triggers "
                + "WHERE event_object_table = ? AND trigger_schema = current_schema() "
                + "ORDER BY trigger_name";
    }

    @Override
    public boolean readsDdlFromDatabase() {
        // PostgreSQL 没有 SHOW CREATE TABLE —— DDL 只能由内省生成（UI 会标注「等价 DDL」）。
        return false;
    }

    @Override
    public String readDdl(Connection connection, String table, boolean view) throws SQLException {
        return null;
    }

    @Override
    public List<String> dependentViews(Connection connection, String table) throws SQLException {
        // 只有 SQLite 的整表重建需要拆视图。
        return List.of();
    }

    @Override
    public String readViewDdl(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT pg_get_viewdef(?::regclass, true)")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    return "CREATE OR REPLACE VIEW " + quote(table) + " AS\n" + rs.getString(1);
                }
            }
        }
        return null;
    }

    @Override
    public String currentCatalog(Connection connection) throws SQLException {
        return connection.getCatalog();
    }

    @Override
    public String currentSchema(Connection connection) throws SQLException {
        return connection.getSchema();
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
        // 注释不在列定义里 —— PostgreSQL 用独立的 COMMENT ON 语句。
        return definition;
    }

    @Override
    public void appendColumnComment(String table, ColumnSpec column, List<String> statements) {
        if (column.comment() == null) {
            return;
        }
        statements.add("COMMENT ON COLUMN " + quote(table) + "." + quote(column.name())
                + " IS '" + column.comment().replace("'", "''") + "'");
    }

    @Override
    public List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                              String oldRawType) {
        List<String> statements = new ArrayList<>();
        String quotedTable = quote(table);
        String quotedColumn = quote(columnName);
        String type = next.effectiveType();
        if (oldRawType == null || !sameType(oldRawType, type)) {
            statements.add("ALTER TABLE " + quotedTable + " ALTER COLUMN " + quotedColumn
                    + " TYPE " + type);
        }
        statements.add("ALTER TABLE " + quotedTable + " ALTER COLUMN " + quotedColumn
                + (next.nullable() ? " DROP NOT NULL" : " SET NOT NULL"));
        statements.add("ALTER TABLE " + quotedTable + " ALTER COLUMN " + quotedColumn
                + (next.defaultValue() == null ? " DROP DEFAULT"
                : " SET DEFAULT " + next.defaultValue()));
        return statements;
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
        return "EXPLAIN " + sql;
    }

    private static boolean sameType(String left, String right) {
        return left != null && right != null
                && left.replace(" ", "").equalsIgnoreCase(right.replace(" ", ""));
    }
}
