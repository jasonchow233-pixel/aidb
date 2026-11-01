package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * 连接 / 读超时（毫秒）。
     *
     * <p>⭐ 同 MySQL：<b>没有超时，卡住的握手会把界面冻死</b>。
     * PostgreSQL 驱动的参数名是 {@code connectTimeout}（秒）与 {@code socketTimeout}（秒）。
     */
    public static final int CONNECT_TIMEOUT_SECONDS = 10;

    public static final int SOCKET_TIMEOUT_SECONDS = 30;

    @Override
    public String jdbcUrl(String host, int port, String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database
                + "?connectTimeout=" + CONNECT_TIMEOUT_SECONDS
                + "&socketTimeout=" + SOCKET_TIMEOUT_SECONDS;
    }

    @Override
    public String uriScheme() {
        return "postgresql";
    }

    @Override
    public List<String> uriAliases() {
        return List.of("postgresql", "postgres");
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

    /** PostgreSQL 的加密开关就是一个 {@code sslmode} 档位串，五档全能表达。 */
    @Override
    public java.util.Map<String, String> sslOptions(SslLevel level) {
        String value = switch (level) {
            case OFF -> null;
            case PREFER -> "prefer";
            case REQUIRE -> "require";
            case VERIFY_CA -> "verify-ca";
            case VERIFY_IDENTITY -> "verify-full";
        };
        return value == null ? java.util.Map.of()
                : java.util.Map.of("sslmode", value);
    }

    @Override
    public SslLevel sslLevel(java.util.Map<String, String> options) {
        String raw = options == null ? null : options.get("sslmode");
        if (raw == null || raw.isBlank()) {
            return SslLevel.OFF;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
            case "allow" -> SslLevel.PREFER;
            case "prefer" -> SslLevel.PREFER;
            case "require" -> SslLevel.REQUIRE;
            case "verify_ca" -> SslLevel.VERIFY_CA;
            case "verify_full" -> SslLevel.VERIFY_IDENTITY;
            default -> SslLevel.OFF;
        };
    }

    @Override
    public String sslRootCertOption() {
        return "sslrootcert";
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

    /**
     * ⭐ PostgreSQL 的 CHECK 约束：{@code pg_constraint} 里的 {@code contype = 'c'}。
     *
     * <p>表达式用 {@code pg_get_constraintdef} 取，它返回 {@code CHECK ((age >= 0))}
     * 这种带外层括号的写法 —— 内省层会把外层括号剥掉。
     */
    @Override
    public String addForeignKeySql(String table, String name, List<String> columns,
                                    String referencedTable, List<String> referencedColumns,
                                    String onUpdate, String onDelete) {
        return "ALTER TABLE " + quote(table) + " ADD CONSTRAINT " + quote(name)
                + " FOREIGN KEY (" + String.join(", ", columns) + ") REFERENCES "
                + quote(referencedTable) + " (" + String.join(", ", referencedColumns) + ")"
                + " ON UPDATE " + onUpdate + " ON DELETE " + onDelete;
    }

    @Override
    public String dropForeignKeySql(String table, String constraintName) {
        return "ALTER TABLE " + quote(table) + " DROP CONSTRAINT " + quote(constraintName);
    }

    /**
     * PostgreSQL 的触发器 = 一个返回 trigger 的函数 + 一个绑在表上的触发器。
     * 所以这里追加**两条**语句。
     */
    @Override
    public String triggerBodyHint() {
        return "PostgreSQL：写返回 trigger 的**函数体**（BEGIN … RETURN NEW; END）";
    }

    @Override
    public void appendCreateTriggerSql(String table, String name, String timing, String event,
                                        String body, List<String> statements) {
        String function = name + "_fn";
        statements.add("CREATE OR REPLACE FUNCTION " + quote(function)
                + "() RETURNS trigger AS $$ " + body + " $$ LANGUAGE plpgsql");
        statements.add("CREATE TRIGGER " + quote(name) + " " + timing + " " + event
                + " ON " + quote(table) + " EXECUTE FUNCTION " + quote(function) + "()");
    }

    /** PostgreSQL：版本 + 当前用户。 */
    @Override
    public String serverInfoSql() {
        return "SELECT version(), current_user";
    }

    @Override
    public String checksSql(String table) {
        return "SELECT c.conname, pg_get_constraintdef(c.oid) "
                + "FROM pg_constraint c "
                + "JOIN pg_class t ON t.oid = c.conrelid "
                + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                + "WHERE c.contype = 'c' AND t.relname = ? AND n.nspname = current_schema() "
                + "ORDER BY c.conname";
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
    public String tableMetaSql() {
        // PostgreSQL 没有「最后修改时间」这一列（relfilenode 变化不代表行变了），
        // 与其拿一个容易误解的时间，不如诚实给 NULL（UI 显示「—」）。
        return "SELECT c.relname AS name, "
                + "c.reltuples::bigint AS rows, "
                + "pg_total_relation_size(c.oid) AS size_bytes, "
                + "CASE c.reltablespace WHEN 0 THEN 'default' "
                + "ELSE ts.spcname END AS engine, "
                + "NULL AS created_at, NULL AS updated_at, "
                // ⚠️ pg_class 没有 relcollation 列（排序规则是「每列」落在 pg_attribute，
                //    不是「每表」）—— 直接引用 c.relcollation 在 PG 16 上直接报
                //    「column c.relcollation does not exist」。PG 的表继承所在库的默认排序规则，
                //    所以这里取库的 datcollate（常见情况下就是这张表的有效排序规则）。
                + "(SELECT datcollate FROM pg_database WHERE datname = current_database()) "
                + "AS collation_name, "
                + "obj_description(c.oid, 'pg_class') AS comment_text "
                + "FROM pg_class c "
                + "LEFT JOIN pg_tablespace ts ON ts.oid = c.reltablespace "
                + "WHERE c.relkind = 'r' AND c.relnamespace = current_schema()::regnamespace "
                + "ORDER BY c.relname";
    }

    @Override
    public String triggersListSql() {
        return "SELECT trigger_name FROM information_schema.triggers "
                + "WHERE event_object_schema = current_schema() ORDER BY trigger_name";
    }

    @Override
    public String setSchemaSql(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        // search_path 决定了未限定表名解析到哪个 schema —— 切 schema 就是切它。
        return "SET search_path TO " + quote(schema);
    }

    @Override
    public boolean switchesSchemaInSession() {
        return true;
    }

    /**
     * ⭐ N32：一条 PG 连接只落在 {@code /database} 上，跨库只能换连接
     * （{@code \c} 在 psql 里也是重连）。库清单读 {@code pg_database}，
     * 模板库在 SQL 里就排除掉。
     */
    @Override
    public boolean switchesCatalogByReconnect() {
        return true;
    }

    @Override
    public String catalogsSql() {
        return "SELECT datname FROM pg_database WHERE NOT datistemplate ORDER BY datname";
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
        // ⭐ R31：PostgreSQL 的排序规则跟在类型后、约束前；字符集是建库时定的，列上没有。
        if (column.collation() != null) {
            definition += " COLLATE \"" + column.collation().replace("\"", "\"\"") + "\"";
        }
        // ⭐ N29：PostgreSQL 只有 STORED 生成列（12+），VIRTUAL 是 18 才有的。
        if (column.generated()) {
            definition += generatedColumnSql(column.generatedExpression());
        }
        if (column.autoIncrement()) {
            // identity 也是一条列约束，必须写在 NOT NULL / DEFAULT 之前。
            definition += " GENERATED BY DEFAULT AS IDENTITY";
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
        // 注释不在列定义里 —— PostgreSQL 用独立的 COMMENT ON 语句。
        return definition;
    }

    /**
     * identity 只认 smallint / integer / bigint（以及它们的别名）——
     * {@code tinyint}、{@code mediumint} 这类 MySQL 习惯写法在 PG 上根本不存在。
     */
    @Override
    public boolean supportsGeneratedColumn() {
        return true;
    }

    @Override
    public String generatedColumnSql(String expression) {
        return " GENERATED ALWAYS AS (" + expression + ") STORED";
    }

    @Override
    public List<ColumnTypeOption> columnTypes() {
        return ColumnTypes.POSTGRESQL;
    }

    @Override
    public List<String> columnCollationPresets() {
        // 每个库都自带的三个；locale 风格的名字（en_US.UTF-8）带点号，
        // 过不了建表器那道「只能是字母 / 数字 / 下划线」的注入防线，不摆。
        return List.of("C", "POSIX", "default");
    }

    @Override
    public String autoIncrementProblem(ColumnSpec column) {
        if (!column.autoIncrement()) {
            return null;
        }
        String base = column.baseType();
        if (!java.util.Set.of("SMALLINT", "INT", "INTEGER", "BIGINT", "INT2", "INT4", "INT8")
                .contains(base)) {
            return "自增列必须是整型 —— PostgreSQL 只认 smallint / integer / bigint："
                    + column.name() + " 现在是 " + column.effectiveType();
        }
        if (column.defaultValue() != null) {
            return "自增列不能同时设默认值：" + column.name();
        }
        return null;
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
    public void appendColumnCommentReset(String table, String columnName,
                                         List<String> statements) {
        statements.add("COMMENT ON COLUMN " + quote(table) + "." + quote(columnName)
                + " IS NULL");
    }

    @Override
    public String dropIndexSql(String table, String indexName) {
        return "DROP INDEX " + quote(indexName);
    }

    /**
     * PostgreSQL 的索引方法是真可选的：这几种都是服务端内置访问方法，
     * 不依赖任何需要 {@code CREATE EXTENSION} 的插件（所以不列 bloom）。
     */
    @Override
    public List<String> indexTypes() {
        return List.of("BTREE", "HASH", "GIST", "SPGIST", "GIN", "BRIN");
    }

    @Override
    public String indexTypeClause(String type) {
        // ⭐ 只认 indexTypes() 里的那几个：这里拼的是裸 SQL，不能把界面传来的字符串直接塞进去。
        if (type == null || !indexTypes().contains(type.toUpperCase(Locale.ROOT))) {
            return "";
        }
        return " USING " + type.toLowerCase(Locale.ROOT);
    }

    /** PG 从 7.4 起就支持索引列 {@code ASC / DESC}，且不区分版本。 */
    @Override
    public boolean supportsIndexColumnDirection() {
        return true;
    }

    @Override
    public String columnCommentsSql(String table) {
        // regclass 会按 search_path 解析表名，跨 schema 也成立。
        return "SELECT a.attname AS column_name,"
                + " col_description(a.attrelid, a.attnum) AS column_comment"
                + " FROM pg_attribute a"
                + " WHERE a.attrelid = '" + table.replace("'", "''") + "'::regclass"
                + " AND a.attnum > 0 AND NOT a.attisdropped";
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
        return "ALTER TABLE " + quote(table) + " ADD COLUMN " + columnDefinition(column, true);
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "ALTER TABLE " + quote(oldName) + " RENAME TO " + quote(newName);
    }

    @Override
    public String truncateTableSql(String table) {
        return "TRUNCATE TABLE " + quote(table);
    }

    @Override
    public String dropTableSql(String table) {
        return "DROP TABLE " + quote(table);
    }

    /**
     * ⭐ R42 N28-a：PostgreSQL 认 {@code CREATE OR REPLACE VIEW}。
     *
     * <p>一条硬约束要如实告诉用户：PG 的 {@code CREATE OR REPLACE VIEW}
     * <b>不允许把列名改掉或删掉列</b>（只能在末尾加新列），改结构必须
     * {@code DROP VIEW} + {@code CREATE VIEW}。这里不替用户偷偷改成先删再建 ——
     * 预览的 SQL 就是执行的 SQL，报错了由 PG 的原话解释（诚实条款）。
     */
    @Override
    public ViewReplace viewReplace() {
        return ViewReplace.OR_REPLACE;
    }

    @Override
    public boolean supportsTableComment() {
        return true;
    }

    @Override
    public String tableCommentSql(String table, String comment) {
        return "COMMENT ON TABLE " + quote(table) + " IS '"
                + comment.replace("'", "''") + "'";
    }

    /** ⭐ 清注释是 {@code IS NULL} —— 写成 {@code IS ''} 等于没清。 */
    @Override
    public String clearTableCommentSql(String table) {
        return "COMMENT ON TABLE " + quote(table) + " IS NULL";
    }

    /**
     * PostgreSQL 的函数 / 过程定义体：{@code pg_get_functiondef(oid)}。
     *
     * <p>⚠️ PostgreSQL 里「函数」与「过程」都是 {@code pg_proc} 的行，
     * 区别只在 {@code prokind}（f=函数 / p=过程）。
     */
    @Override
    public String readRoutineDefinition(Connection connection, String name, boolean procedure)
            throws SQLException {
        String sql = "SELECT pg_get_functiondef(p.oid) FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE p.proname = ? AND n.nspname = current_schema() "
                + (procedure ? "AND p.prokind = 'p' " : "AND p.prokind = 'f' ")
                + "ORDER BY p.oid LIMIT 1";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    String body = rs.getString(1);
                    return body == null ? "" : body;
                }
            }
        }
        return "";
    }

    /** 表注释在 {@code pg_class} 的 {@code obj_description} 里。
    @Override
    public String tableCommentQuery(String table) {
        return "SELECT obj_description(c.oid) FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname = ? AND n.nspname = current_schema()";
    }

    /**
     * 自增列：PostgreSQL 10+ 的 identity 列
     * （{@code information_schema.columns.is_identity = 'YES'}）。
     */
    @Override
    public String autoIncrementColumn(java.sql.Connection connection, String table)
            throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? "
                        + "AND is_identity = 'YES'")) {
            ps.setString(1, table);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
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
