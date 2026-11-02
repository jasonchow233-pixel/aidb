package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ⭐ D-3：Oracle 方言。
 *
 * <p>与已支持的三种库的关键差异（都是"照着 MySQL 猜一定猜错"的那类）：
 * <ol>
 *   <li><b>没有 {@code LIMIT}</b>：12c 起是 {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}，
 *       且不要求 {@code ORDER BY}（区别于 SQL Server）；更早的版本只有
 *       {@code ROWNUM}（分页要套两层，本轮不支持 12c 以下，见 {@link #minVersionNote()}）；</li>
 *   <li><b>URL 是 EZConnect</b>：{@code jdbc:oracle:thin:@//主机:端口/服务名}，
 *       参数<b>不能</b>写在 URL 查询串里 —— 超时只能走 {@code Properties}
 *       （{@link #connectionProperties()}）；</li>
 *   <li><b>大小写折叠</b>：不加引号的标识符折成<b>大写</b>，
 *       所以 {@code "users"} 与 {@code USERS} 是两个对象；</li>
 *   <li><b>加列/改列要括号</b>：{@code ALTER TABLE t ADD (c NUMBER NOT NULL)}、
 *       {@code MODIFY (…)}；</li>
 *   <li><b>外键只有 {@code ON DELETE}</b>：{@code ON UPDATE} 不是合法语法，
 *       引用动作要靠触发器（见 {@link #supportsForeignKeyOnUpdate()}）。</li>
 * </ol>
 *
 * <h2>本轮的真库验证状态（诚实条款）</h2>
 * <p>开发环境没有 Oracle 实例（无 Docker）。单元测试锁住了引用符、URL、
 * 连接属性、分页与探测语句、各条 DDL 文本；{@code ALL_*} 目录查询与
 * {@code DBMS_METADATA} 没被执行过一次，有实例后交给 {@code RealDatabaseContract}
 * 补验（D-5）。执行计划（{@code EXPLAIN PLAN FOR} + 读 {@code PLAN_TABLE} 要两段语句）
 * 本轮不做，界面收起按钮并说明原因。
 */
public final class OracleDialect implements Dialect {

    /** ⭐ Oracle 的两个超时都按<b>毫秒</b>，且只能作为连接属性给。 */
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 30_000;

    @Override
    public DialectKind kind() {
        return DialectKind.ORACLE;
    }

    @Override
    public String displayName() {
        return "Oracle";
    }

    /** Oracle 用双引号引用标识符；⚠️ 加引号后大小写敏感，名字里的 {@code "} 写两遍。 */
    @Override
    public String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String uriScheme() {
        return "oracle";
    }

    /**
     * EZConnect 写法：{@code jdbc:oracle:thin:@//host:port/service}。
     *
     * <p>{@code database} 这里当<b>服务名</b>（现代部署都用服务名；
     * 用 SID 的老库要在附加参数里给完整 TNS 描述串，界面本轮不提供 SID 表单）。
     * 参数不进 URL —— 见 {@link #connectionProperties()}。
     */
    @Override
    public String jdbcUrl(String host, int port, String database) {
        String service = database == null || database.isBlank() ? "" : "/" + database;
        return "jdbc:oracle:thin:@//" + host + ":" + port + service;
    }

    @Override
    public Map<String, String> connectionProperties() {
        return Map.of("oracle.net.CONNECT_TIMEOUT", String.valueOf(CONNECT_TIMEOUT_MS),
                "oracle.jdbc.ReadTimeout", String.valueOf(READ_TIMEOUT_MS));
    }

    @Override
    public String driverClassName() {
        return "oracle.jdbc.OracleDriver";
    }

    @Override
    public int defaultPort() {
        return 1521;
    }

    @Override
    public boolean pooled() {
        return true;
    }

    @Override
    public boolean usesFilePath() {
        return false;
    }

    // ─────────────────────────── 能力 ───────────────────────────

    /** Oracle 有真正的列注释：{@code COMMENT ON COLUMN}。 */
    @Override
    public boolean supportsColumnComment() {
        return true;
    }

    /** TLS 要走钱包 / {@code oracle.net.*} 一整套参数，界面档位本轮不摆。 */
    @Override
    public boolean supportsSslOptionsUi() {
        return false;
    }

    @Override
    public String sslOptionsNoticeKey() {
        return "wizard.ssl.notice.oracle";
    }

    @Override
    public boolean readsColumnsViaJdbcMetadata() {
        return true;
    }

    @Override
    public boolean readsIndexesViaJdbcMetadata() {
        return true;
    }

    /**
     * Oracle 的 catalog 概念和 MySQL/SQL Server 不一样：
     * 一个连接＝一个可打开的库（服务），里面按<b>用户 = schema</b> 划分。
     * 所以「切库」走重连，「切 schema」走 {@code ALTER SESSION SET CURRENT_SCHEMA}。
     */
    @Override
    public boolean hasCatalog() {
        return false;
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
        return true;
    }

    /** {@code ON UPDATE} 在 Oracle 不是合法的外键语法。 */
    @Override
    public boolean supportsForeignKeyOnUpdate() {
        return false;
    }

    /** 执行计划要两段语句（{@code EXPLAIN PLAN FOR} + 读 {@code PLAN_TABLE}）。 */
    @Override
    public boolean supportsExplainPlan() {
        return false;
    }

    @Override
    public String explainSql(String sql) {
        throw new UnsupportedOperationException(
                "Oracle 的执行计划要先 EXPLAIN PLAN FOR 再读 PLAN_TABLE，本轮不支持");
    }

    /** 12c 以下没有 {@code OFFSET … FETCH}；向导的提示文案用这条说明边界。 */
    public String minVersionNote() {
        return "需要 Oracle 12c 及以上（分页用 OFFSET … FETCH NEXT）";
    }

    // ─────────────────────────── 元数据 ───────────────────────────

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

    /**
     * 触发器：{@code ALL_TRIGGERS}。
     *
     * <p>列序按接口约定：名称 / 时机 / 事件 / 语句。Oracle 把「时机 × 事件」合成一个
     * {@code TRIGGERING_EVENT}（如 {@code INSERT}）与 {@code TRIGGER_TYPE}
     * （如 {@code BEFORE ROW}），这里原样给出，由内省层拼展示。
     *
     * <p>⚠️ 未跑过真库，见类注释。
     */
    @Override
    public String triggersSql(String table) {
        return "SELECT TRIGGER_NAME, TRIGGER_TYPE, TRIGGERING_EVENT, TRIGGER_BODY "
                + "FROM ALL_TRIGGERS WHERE TABLE_NAME = UPPER(?) "
                + "AND TABLE_OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') "
                + "ORDER BY TRIGGER_NAME";
    }

    @Override
    public String triggersListSql() {
        return "SELECT TRIGGER_NAME FROM USER_TRIGGERS ORDER BY TRIGGER_NAME";
    }

    /**
     * 表清单的 8 列。
     *
     * <p>三处 Oracle 特有的取舍：
     * <ul>
     *   <li>{@code NUM_ROWS} 是<b>上次收集统计信息时</b>的行数，没收集过就是 NULL ——
     *       宁可显示「—」也不把它当实时行数报给用户；</li>
     *   <li>大小与建表时间在 {@code USER_SEGMENTS} / {@code USER_OBJECTS} 里，
     *       {@code ALL_TABLES} 自己没有这两列；</li>
     *   <li>没有「行最后修改时间」与「引擎」概念，也不存在<b>表级</b>排序规则
     *       （{@code COLLATION} 是 12c2 起的列级属性）—— 三列给 NULL。</li>
     * </ul>
     *
     * <p>⚠️ 未跑过真库，见类注释。
     */
    @Override
    public String tableMetaSql() {
        return "SELECT t.TABLE_NAME AS name, t.NUM_ROWS AS \"rows\", "
                + "(SELECT s.BYTES FROM USER_SEGMENTS s WHERE s.SEGMENT_NAME = t.TABLE_NAME "
                + "AND s.SEGMENT_TYPE = 'TABLE' AND ROWNUM = 1) AS size_bytes, "
                + "NULL AS engine, "
                + "(SELECT TO_CHAR(o.CREATED, 'YYYY-MM-DD HH24:MI:SS') FROM USER_OBJECTS o "
                + "WHERE o.OBJECT_NAME = t.TABLE_NAME AND o.OBJECT_TYPE = 'TABLE' "
                + "AND ROWNUM = 1) AS created_at, "
                + "NULL AS updated_at, NULL AS collation_name, "
                + "(SELECT c.COMMENTS FROM USER_TAB_COMMENTS c "
                + "WHERE c.TABLE_NAME = t.TABLE_NAME AND ROWNUM = 1) AS comment_text "
                + "FROM ALL_TABLES t "
                + "WHERE t.OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') "
                + "ORDER BY t.TABLE_NAME";
    }

    @Override
    public boolean readsDdlFromDatabase() {
        return true;
    }

    /**
     * Oracle 的 DDL 从 {@code DBMS_METADATA.GET_DDL} 取 —— 这是它唯一可靠的取法，
     * 也是官方推荐的取法。
     *
     * <p>⚠️ 传进去的名字必须是<b>大写</b>（除非建对象时加了引号），
     * 这里按内省层给的原始名直接绑定；读不到就返回 {@code null}，
     * 由界面退化成「等价 DDL」。
     */
    @Override
    public String readDdl(Connection connection, String table, boolean view) throws SQLException {
        String kind = view ? "VIEW" : "TABLE";
        try (var statement = connection.prepareStatement(
                "SELECT DBMS_METADATA.GET_DDL(?, ?, SYS_CONTEXT('USERENV','CURRENT_SCHEMA')) "
                        + "FROM DUAL")) {
            statement.setString(1, kind);
            statement.setString(2, table.toUpperCase(java.util.Locale.ROOT));
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    String ddl = rs.getString(1);
                    return ddl == null ? null : ddl.trim();
                }
            }
        } catch (SQLException e) {
            // 权限不够（DBA 才能读别人的）或对象不存在 —— 都退化成等价 DDL，不挂结构页。
            return null;
        }
        return null;
    }

    @Override
    public String readViewDdl(Connection connection, String table) throws SQLException {
        return readDdl(connection, table, true);
    }

    @Override
    public String setSchemaSql(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        // Oracle 切「当前 schema」只影响未限定名的解析，不改连接。
        return "ALTER SESSION SET CURRENT_SCHEMA = " + schema.replace("'", "''");
    }

    @Override
    public boolean switchesSchemaInSession() {
        return true;
    }

    @Override
    public List<String> dependentViews(Connection connection, String table) throws SQLException {
        return List.of();
    }

    @Override
    public String currentCatalog(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT SYS_CONTEXT('USERENV','DB_NAME') FROM DUAL")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public String currentSchema(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    // ─────────────────────────── 分页 / 探测 ───────────────────────────

    /**
     * {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}（12c+）。
     *
     * <p>与 SQL Server 的区别：Oracle 不要求前面有 {@code ORDER BY}，
     * 所以 {@code hasOrderBy} 在这里不参与拼装。
     */
    @Override
    public String pagingClause(long limit, long offset, boolean hasOrderBy) {
        return " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * 参数版分页：套派生表（和 LIMIT 族同形）。
     *
     * <p>Oracle 的内联视图<b>允许</b>带 {@code ORDER BY}，所以不像 SQL Server
     * 那样必须把子句追加在原句上。⚠️ 别名不能写 {@code AS}：
     * {@code ) AS _p} 在 Oracle 是语法错误，只能 {@code ) _p}。
     */
    @Override
    public PagedSql pagedSelect(String source, List<Object> params, int limit, long offset) {
        List<Object> bound = new ArrayList<>(params == null ? List.of() : params);
        bound.add((int) offset);
        bound.add(limit);
        return new PagedSql("SELECT * FROM (" + source + ") _p"
                + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", bound);
    }

    @Override
    public PagedSql countedSelect(String source, List<Object> params) {
        return new PagedSql("SELECT COUNT(*) AS adb_count FROM (" + source + ") _p",
                params == null ? List.of() : params);
    }

    /** 空页探测：Oracle 没有 {@code LIMIT}，用 {@code ROWNUM}。 */
    @Override
    public String probeColumnsSql(String table) {
        return "SELECT * FROM " + quote(table) + " WHERE ROWNUM = 0";
    }

    // ─────────────────────────── DDL ───────────────────────────

    /**
     * 列定义：{@code "名" 类型 [DEFAULT 值 | GENERATED … IDENTITY] [NOT NULL] [UNIQUE]}。
     *
     * <p>⚠️ Oracle 的 identity 子句占的是 {@code DEFAULT} 那个位置，
     * 两者不能同时出现（{@link #autoIncrementProblem(ColumnSpec)} 挡这件事）。
     */
    @Override
    public String columnDefinition(ColumnSpec column, boolean includeName) {
        StringBuilder definition = new StringBuilder();
        if (includeName) {
            definition.append(quote(column.name())).append(' ');
        }
        definition.append(column.effectiveType());
        // ⭐ N29：Oracle 的虚拟列与 identity 都占 DEFAULT 那个位置，不能同时出现。
        if (column.generated()) {
            definition.append(generatedColumnSql(column.generatedExpression()));
        } else if (column.autoIncrement()) {
            definition.append(" GENERATED BY DEFAULT AS IDENTITY");
        } else if (column.defaultValue() != null) {
            definition.append(" DEFAULT ").append(column.defaultValue());
        }
        if (!column.nullable()) {
            definition.append(" NOT NULL");
        }
        if (column.unique()) {
            definition.append(" UNIQUE");
        }
        return definition.toString();
    }

    @Override
    public List<ColumnTypeOption> columnTypes() {
        return ColumnTypes.ORACLE;
    }

    /** Oracle 的排序走 NLS 会话参数，列级 {@code COLLATION} 是 12c2 的新东西 —— 先不摆候选。 */
    @Override
    public List<String> columnCollationPresets() {
        return List.of();
    }

    @Override
    public ColumnProblem autoIncrementProblem(ColumnSpec column) {
        if (!column.autoIncrement()) {
            return null;
        }
        String base = column.baseType();
        if (!java.util.Set.of("NUMBER", "INTEGER", "INT", "SMALLINT", "DECIMAL", "NUMERIC",
                "FLOAT", "BINARY_INTEGER", "PLS_INTEGER").contains(base)) {
            return new ColumnProblem("column.error.autoNeedsNumericOracle",
                    column.name(), column.effectiveType());
        }
        if (column.defaultValue() != null) {
            return new ColumnProblem("column.error.autoHasDefault", column.name());
        }
        return null;
    }

    @Override
    public boolean supportsGeneratedColumn() {
        return true;
    }

    @Override
    public String generatedColumnSql(String expression) {
        return " GENERATED ALWAYS AS (" + expression + ") VIRTUAL";
    }

    /** ⭐ N29：Oracle 的虚拟列隐式可空，写 NOT NULL 直接 ORA 报错。 */
    @Override
    public ColumnProblem generatedColumnProblem(ColumnSpec column) {
        ColumnProblem problem = Dialect.super.generatedColumnProblem(column);
        if (problem != null || !column.generated()) {
            return problem;
        }
        return column.nullable() ? null
                : new ColumnProblem("newtable.error.generatedNotNull", displayName(), column.name());
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
        statements.add("COMMENT ON COLUMN " + quote(table) + "." + quote(columnName) + " IS NULL");
    }

    @Override
    public String dropIndexSql(String table, String indexName) {
        return "DROP INDEX " + quote(indexName);
    }

    @Override
    public String indexTypeClause(String type) {
        return "";
    }

    @Override
    public boolean supportsIndexColumnDirection() {
        return true;
    }

    @Override
    public List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                              String oldRawType) {
        // Oracle 的 MODIFY 要括号，且类型 / 默认值 / 可空性都能在同一条里一起改。
        // ⚠️ 但不能把 NOT NULL 与 identity 混在一条 MODIFY 里，所以 identity 不进这条。
        StringBuilder modify = new StringBuilder("ALTER TABLE ").append(quote(table))
                .append(" MODIFY (").append(quote(columnName)).append(' ')
                .append(next.effectiveType());
        if (next.defaultValue() != null) {
            modify.append(" DEFAULT ").append(next.defaultValue());
        }
        modify.append(next.nullable() ? " NULL" : " NOT NULL").append(')');
        return new ArrayList<>(List.of(modify.toString()));
    }

    @Override
    public String renameColumnSql(String table, String oldName, String newName,
                                  io.agenticdb.model.ColumnDef oldColumn) {
        return "ALTER TABLE " + quote(table) + " RENAME COLUMN " + quote(oldName)
                + " TO " + quote(newName);
    }

    @Override
    public String dropColumnSql(String table, String columnName) {
        return "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(columnName);
    }

    @Override
    public String addColumnSql(String table, ColumnSpec column) {
        // ⚠️ Oracle 的 ADD 要括号包列定义。
        return "ALTER TABLE " + quote(table) + " ADD (" + columnDefinition(column, true) + ")";
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
     * ⭐ R42 N28-a：Oracle 认 {@code CREATE OR REPLACE VIEW}。
     *
     * <p>不加 {@code FORCE} —— {@code CREATE OR REPLACE FORCE VIEW} 会在引用的表
     * 不存在时<b>照样建出一个 INVALID 视图</b>，用户要到查询时才看见它坏掉；
     * 默认写法（NOFORCE）让「引用写错了」当场报错，更符合本项目
     * 「宁可现在红，不要以后静默」。
     */
    @Override
    public ViewReplace viewReplace() {
        return ViewReplace.OR_REPLACE;
    }

    /**
     * 外键：只有 {@code ON DELETE}。
     *
     * <p>⚠️ {@code ON UPDATE} 在 Oracle 是语法错误，所以这里的 {@code onUpdate}
     * 参数<b>故意不拼进语句</b>；界面按 {@link #supportsForeignKeyOnUpdate()}
     * 把那个选择器收起，不让用户填一个不生效的动作。
     */
    @Override
    public String addForeignKeySql(String table, String name, List<String> columns,
                                    String referencedTable, List<String> referencedColumns,
                                    String onUpdate, String onDelete) {
        return "ALTER TABLE " + quote(table) + " ADD CONSTRAINT " + quote(name)
                + " FOREIGN KEY (" + String.join(", ", columns) + ") REFERENCES "
                + quote(referencedTable) + " (" + String.join(", ", referencedColumns) + ")"
                + " ON DELETE " + onDelete;
    }

    @Override
    public String dropForeignKeySql(String table, String constraintName) {
        return "ALTER TABLE " + quote(table) + " DROP CONSTRAINT " + quote(constraintName);
    }

    /** 版本 + 当前用户（状态栏 U6）。 */
    @Override
    public String serverInfoSql() {
        return "SELECT banner, SYS_CONTEXT('USERENV','CURRENT_USER') FROM V$VERSION "
                + "WHERE ROWNUM = 1";
    }

    @Override
    public String tableCommentSql(String table, String comment) {
        return "COMMENT ON TABLE " + quote(table) + " IS '"
                + comment.replace("'", "''") + "'";
    }

    @Override
    public boolean supportsTableComment() {
        return true;
    }

    @Override
    public String clearTableCommentSql(String table) {
        return "COMMENT ON TABLE " + quote(table) + " IS NULL";
    }

    @Override
    public String tableCommentQuery(String table) {
        return "SELECT COMMENTS FROM ALL_TAB_COMMENTS "
                + "WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') "
                + "AND TABLE_NAME = UPPER(?)";
    }

    @Override
    public String columnCommentsSql(String table) {
        // ⚠️ 这条通道的表名是**内联**的（调用方不绑参数），所以按 SQL 字符串转义。
        String literal = table == null ? "" : table.replace("'", "''").toUpperCase(java.util.Locale.ROOT);
        return "SELECT COLUMN_NAME AS column_name, COMMENTS AS column_comment "
                + "FROM ALL_COL_COMMENTS "
                + "WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') "
                + "AND TABLE_NAME = '" + literal + "'";
    }

    /** 自增列 = 12c 的 identity 列，在 {@code USER_TAB_IDENTITY_COLS} 里。 */
    @Override
    public String autoIncrementColumn(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM USER_TAB_IDENTITY_COLS WHERE TABLE_NAME = UPPER(?)")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * CHECK 约束：{@code USER_CONSTRAINTS} 里 {@code CONSTRAINT_TYPE = 'C'} 的行。
     *
     * <p>⚠️ {@code SEARCH_CONDITION} 是 {@code LONG} 列 —— 只能作为<b>最后一列</b>读，
     * 而且一条语句里不能出现两次，所以这里把它放在第 2 列、别名都不加。
     */
    @Override
    public String checksSql(String table) {
        return "SELECT c.CONSTRAINT_NAME, c.SEARCH_CONDITION FROM USER_CONSTRAINTS c "
                + "WHERE c.CONSTRAINT_TYPE = 'C' AND c.TABLE_NAME = UPPER(?) "
                + "ORDER BY c.CONSTRAINT_NAME";
    }

    @Override
    public String dropTriggerSql(String table, String triggerName) {
        return "DROP TRIGGER " + quote(triggerName);
    }

    @Override
    public String triggerBodyHint() {
        return "Oracle：写触发器主体（BEGIN … END;），DECLARE/行级用 :NEW / :OLD";
    }

    @Override
    public void appendCreateTriggerSql(String table, String name, String timing, String event,
                                       String body, List<String> statements) {
        statements.add("CREATE OR REPLACE TRIGGER " + quote(name) + " "
                + timing.toUpperCase(java.util.Locale.ROOT) + " " + event.toUpperCase(java.util.Locale.ROOT)
                + " ON " + quote(table) + " FOR EACH ROW " + body);
    }
}
