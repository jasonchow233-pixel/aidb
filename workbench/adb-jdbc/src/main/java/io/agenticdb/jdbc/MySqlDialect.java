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

    /**
     * 连接超时（毫秒）。
     *
     * <p>⭐ <b>必须有超时</b>：没有超时的话，服务端在 TLS 握手时卡住，
     * 客户端会<b>无限期</b>阻塞在 socket 读上 —— 界面直接冻住，
     * 而且没有任何报错可看。
     * 实测踩过：真库联调时服务端 TLS 握手停顿，测试挂死，
     * 线程栈停在 {@code performTlsHandshake} 读 socket。
     */
    public static final int CONNECT_TIMEOUT_MS = 10_000;

    /** 读超时（毫秒）—— 一条语句跑太久就断掉。 */
    public static final int SOCKET_TIMEOUT_MS = 30_000;

    @Override
    public String jdbcUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
                + "&connectTimeout=" + CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + SOCKET_TIMEOUT_MS;
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

    /**
     * ⭐ MySQL 8.0.16+ 才有真正的 CHECK 约束（5.7 会**忽略** CHECK 子句）。
     *
     * <p>5.7 上这张表不存在 → 查询抛错 → 内省层按「没有 CHECK 约束」处理，
     * 与数据库的实际行为一致（5.7 确实不执行 CHECK）。
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

    /** ⚠️ MySQL 删外键用的是 {@code DROP FOREIGN KEY}，不是 {@code DROP CONSTRAINT}。 */
    @Override
    public String dropForeignKeySql(String table, String constraintName) {
        return "ALTER TABLE " + quote(table) + " DROP FOREIGN KEY " + quote(constraintName);
    }

    /**
     * ⭐ O1：MySQL 调度事件列表。
     *
     * <p>只有 MySQL 有 {@code information_schema.EVENTS}。
     */
    @Override
    public String eventsListSql() {
        return "SELECT EVENT_NAME FROM information_schema.EVENTS "
                + "WHERE EVENT_SCHEMA = DATABASE() ORDER BY EVENT_NAME";
    }

    /** ⭐ O1：事件定义体（{@code SHOW CREATE EVENT} 的第 3 列）。 */
    @Override
    public String readEventDefinition(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SHOW CREATE EVENT " + quote(name))) {
            if (rs.next()) {
                int columns = rs.getMetaData().getColumnCount();
                String body = columns >= 3 ? rs.getString(3) : rs.getString(1);
                return body == null ? "" : body;
            }
        }
        return "";
    }

    /** MySQL：版本 + 当前登录用户。 */
    @Override
    public String serverInfoSql() {
        return "SELECT VERSION(), USER()";
    }

    @Override
    public String checksSql(String table) {
        return "SELECT tc.constraint_name, cc.check_clause "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.check_constraints cc "
                + "ON cc.constraint_schema = tc.constraint_schema "
                + "AND cc.constraint_name = tc.constraint_name "
                + "WHERE tc.constraint_type = 'CHECK' AND tc.table_name = ? "
                + "ORDER BY tc.constraint_name";
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
        // ⭐ Navicat 表清单的 8 列。update_time / table_collation / table_comment
        //    在 information_schema.tables 里都有，不用逐表再查一次。
        // ⚠️ `rows` 是 MySQL 8.0 的保留字（窗口帧 ROWS BETWEEN …），
        //    不转义直接当别名会语法错误。反引号只在 MySQL 合法，本方法属 MySqlDialect。
        return "SELECT table_name AS name, table_rows AS `rows`, "
                + "(data_length + index_length) AS size_bytes, engine AS engine, "
                + "create_time AS created_at, update_time AS updated_at, "
                + "table_collation AS collation_name, table_comment AS comment_text "
                + "FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                + "ORDER BY table_name";
    }

    /**
     * ⭐ UX-6：结构页「选项」页签。
     *
     * <p>视图在 {@code information_schema.tables} 里也有行，但它们没有
     * 存储引擎 / 行格式 —— 用 {@code BASE TABLE} 过滤掉，让视图真的显示「—」。
     */
    @Override
    public String tableOptionsSql() {
        return "SELECT engine AS engine, table_collation AS collation_name, "
                + "auto_increment AS auto_increment, row_format AS row_format "
                + "FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = ? "
                + "AND table_type = 'BASE TABLE'";
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

    /**
     * ⭐ U1：MySQL 切库只能重连 —— 一条连接绑定一个数据库。
     *
     * <p>{@code USE db} 对单连接有效，但连接池里随时可能换一条连接，
     * 「当前库」这个状态管不住。所以 UI 的做法是切库 = 换连接
     * （对象树里每个库一个虚拟连接，各自持有自己的会话）。
     */
    @Override
    public boolean switchesCatalogByReconnect() {
        return true;
    }

    /**
     * ⭐ U1：可见的数据库列表（catalog = MySQL 的 schema）。
     *
     * <p>走 {@code information_schema.SCHEMATA} 而不是 {@code SHOW DATABASES}：
     * 后者要 SHOW 权限，前者只要能看到 information_schema。
     * 系统库的过滤在 {@code SchemaService.listCatalogs} 里做（可测）。
     */
    @Override
    public String catalogsSql() {
        return "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME";
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
    public String dropIndexSql(String table, String indexName) {
        // ⭐ 少了 ON 表名，MySQL 直接语法错误。
        return "DROP INDEX " + quote(indexName) + " ON " + quote(table);
    }

    @Override
    public String columnCommentsSql(String table) {
        // ⭐ information_schema 是唯一稳定来源：DatabaseMetaData.getColumns 的 REMARKS
        //   要连接串带 remarks=true 才有值，我们不能指望用户那么配。
        return "SELECT COLUMN_NAME AS column_name, COLUMN_COMMENT AS column_comment"
                + " FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = '" + table.replace("'", "''") + "'";
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
    public String createTableColumnDefinition(ColumnSpec column, boolean inlinePrimaryKey) {
        if (!inlinePrimaryKey) {
            return columnDefinition(column, true);
        }
        // ⭐ PRIMARY KEY 必须插在 COMMENT 之前，否则 MySQL 报语法错误。
        String body = columnDefinition(column, false);
        int commentAt = body.indexOf(" COMMENT '");
        String head = commentAt < 0 ? body : body.substring(0, commentAt);
        String tail = commentAt < 0 ? "" : body.substring(commentAt);
        return quote(column.name()) + " " + head + " PRIMARY KEY" + tail;
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        // ⭐ MySQL 的官方写法是 RENAME TABLE（ALTER TABLE … RENAME 是 8.0 才加的）。
        return "RENAME TABLE " + quote(oldName) + " TO " + quote(newName);
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
     * ⭐ 建表必须带 {@code DEFAULT CHARSET=utf8mb4}。
     *
     * <p>见 {@link Dialect#createTableOptions()}：MySQL 8.0 之前默认字符集是
     * latin1，不带这条子句建出来的表存不了中文。utf8mb4 从 5.5.3 就有，
     * 我们支持的所有 MySQL 版本（5.7 / 8.0 / 8.4）都认。
     *
     * <p>⚠️ 不指定 COLLATE：让服务器挑该字符集的默认排序规则 ——
     * 5.7 的默认（utf8mb4_general_ci）与 8.0 的默认（utf8mb4_0900_ai_ci）
     * 不同，写死其中一个在另一个版本上就是「排序行为和别的表不一样」。
     */
    @Override
    public String createTableOptions() {
        return "DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public boolean supportsTableComment() {
        return true;
    }

    @Override
    public String tableCommentSql(String table, String comment) {
        return "ALTER TABLE " + quote(table) + " COMMENT = '"
                + comment.replace("'", "''") + "'";
    }

    /** MySQL 清注释就是置空串（没有 {@code IS NULL} 那套）。 */
    @Override
    public String clearTableCommentSql(String table) {
        return "ALTER TABLE " + quote(table) + " COMMENT = ''";
    }

    /**
     * MySQL 的函数 / 过程定义体：{@code SHOW CREATE FUNCTION / PROCEDURE}。
     *
     * <p>结果集里第 1 列是名字、第 2 列是 sql_mode、<b>第 3 列才是定义体</b>。
     */
    @Override
    public String readRoutineDefinition(Connection connection, String name, boolean procedure)
            throws SQLException {
        String sql = "SHOW CREATE " + (procedure ? "PROCEDURE " : "FUNCTION ")
                + quote(name);
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                int columns = rs.getMetaData().getColumnCount();
                String body = columns >= 3 ? rs.getString(3) : rs.getString(1);
                return body == null ? "" : body;
            }
        }
        return "";
    }

    /** 表注释在 {@code information_schema.TABLES.TABLE_COMMENT} 里。
    @Override
    public String tableCommentQuery(String table) {
        return "SELECT TABLE_COMMENT FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
    }

    /**
     * 自增列：{@code information_schema.columns.EXTRA} 里带 {@code auto_increment}。
     */
    @Override
    public String autoIncrementColumn(java.sql.Connection connection, String table)
            throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                        + "AND EXTRA LIKE '%auto_increment%'")) {
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
}
