package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * MySQL 方言。
 *
 * <p>要点：标识符用<b>反引号</b>；DDL 走 {@code SHOW CREATE TABLE}；
 * 列注释写在<b>列定义里</b>（{@code COMMENT '...'}）；
 * 改列必须一条 {@code MODIFY COLUMN} 带完整定义 ——
 * MySQL 没有独立的 {@code SET NOT NULL} / {@code SET DEFAULT}。
 */
public final class MySqlDialect implements Dialect {

    @Override
    public DialectKind kind() {
        return DialectKind.MYSQL;
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    @Override
    public String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String jdbcUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC";
    }

    @Override
    public String driverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    public int defaultPort() {
        return 3306;
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
        return "SELECT TRIGGER_NAME, ACTION_TIMING, EVENT_MANIPULATION, "
                + "ACTION_STATEMENT FROM information_schema.TRIGGERS "
                + "WHERE EVENT_OBJECT_TABLE = ? AND TRIGGER_SCHEMA = DATABASE() "
                + "ORDER BY TRIGGER_NAME";
    }

    @Override
    public boolean readsDdlFromDatabase() {
        return true;
    }

    @Override
    public String readDdl(Connection connection, String table, boolean view) throws SQLException {
        String sql = "SHOW CREATE " + (view ? "VIEW" : "TABLE") + " " + quote(table);
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(2);
            }
        }
        return null;
    }

    @Override
    public String tableMetaSql() {
        return "SELECT table_name AS name, table_rows AS rows, "
                + "(data_length + index_length) AS size_bytes, engine AS engine, "
                + "create_time AS created_at "
                + "FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                + "ORDER BY table_name";
    }

    @Override
    public String triggersListSql() {
        return "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA = DATABASE() ORDER BY TRIGGER_NAME";
    }

    @Override
    public String setSchemaSql(String schema) {
        // MySQL 的 schema 就是数据库；切换要重连，不是一条 SET。
        return null;
    }

    @Override
    public List<String> dependentViews(Connection connection, String table) throws SQLException {
        // 只有 SQLite 的整表重建需要拆视图。
        return List.of();
    }

    @Override
    public String readViewDdl(Connection connection, String table) throws SQLException {
        // SHOW CREATE VIEW 已经能读出视图定义。
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
        if (column.comment() != null) {
            // MySQL 的注释写在列定义里。
            definition += " COMMENT '" + column.comment().replace("'", "''") + "'";
        }
        return definition;
    }

    @Override
    public void appendColumnComment(String table, ColumnSpec column, List<String> statements) {
        // MySQL 的注释已经写在列定义里，不需要额外语句。
    }

    @Override
    public List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                              String oldRawType) {
        // MySQL 没有独立的 SET NOT NULL / SET DEFAULT —— 一条 MODIFY 必须带完整定义。
        return List.of("ALTER TABLE " + quote(table) + " MODIFY COLUMN "
                + columnDefinition(next, true));
    }

    /**
     * MySQL 5.7 <b>没有</b> {@code RENAME COLUMN}（8.0 才有），
     * 只能用 {@code CHANGE COLUMN} —— 而它要求带上完整列定义。
     *
     * <p>⚠️ 这是真库联调才发现的：生成的 DDL 在 MySQL 5.7 上直接报语法错误。
     */
    @Override
    public String renameColumnSql(String table, String oldName, String newName,
                                  io.agenticdb.model.ColumnDef oldColumn) {
        String definition = oldColumn == null ? "" : columnDefinition(oldColumn);
        if (definition.isBlank()) {
            // 拿不到定义时只能退回 CHANGE 的最小形式（会丢属性）——
            // 宁可让数据库报错，也不要静默丢列属性。
            throw new IllegalArgumentException(
                    "重命名列需要旧列的定义，但没能读到：" + oldName);
        }
        return "ALTER TABLE " + quote(table) + " CHANGE COLUMN "
                + quote(oldName) + " " + quote(newName) + " " + definition;
    }

    /** MySQL 的列定义（CHANGE COLUMN 要用）。 */
    private String columnDefinition(io.agenticdb.model.ColumnDef column) {
        StringBuilder definition = new StringBuilder();
        // ⭐ 用 ddlType() —— 它会把长度 / 精度补上。
        //    直接用 rawType 会得到 "VARCHAR"（MySQL 的 JDBC 类型名不带长度）→ 语法错误。
        definition.append(column.ddlType());
        if (!column.nullable()) {
            definition.append(" NOT NULL");
        }
        if (column.defaultValue() != null && !column.defaultValue().isBlank()) {
            definition.append(" DEFAULT ").append(column.defaultValue());
        }
        if (column.unique() && !column.primaryKey()) {
            definition.append(" UNIQUE");
        }
        return definition.toString();
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
}
