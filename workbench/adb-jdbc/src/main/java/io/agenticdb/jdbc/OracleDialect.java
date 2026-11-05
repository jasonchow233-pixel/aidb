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
 * <h2>真库验证状态</h2>
 * <p>R46 起本地有 Oracle 23ai Free 容器，{@code ALL_*} 目录查询与 {@code DBMS_METADATA}
 * 都跑过了 —— 并当场抓出一条只有真库才能发现的缺陷（<b>D-22</b>）：
 * 目录查询原先一律用 {@code UPPER(?)} 匹配名字，而我们自己的表设计器建对象<b>总是加引号</b>，
 * Oracle 对加引号的名字原样存，于是这些对象在触发器页 / 约束页 / 注释页里<b>全部隐形</b>。
 * 现在的匹配规则见 {@link #nameMatches}。执行计划（{@code EXPLAIN PLAN FOR} +
 * 读 {@code PLAN_TABLE} 要两段语句）本轮不做，界面收起按钮并说明原因。
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

    /**
     * ⭐ D-68：Oracle 把<b>未引号</b>标识符折成大写（{@code identifiersToUpper=true}），
     * 引号形式才原样解析 —— 所以只有已经是全大写形状的名字裸写能落回自己。
     *
     * <p>与 {@link #nameMatches}（D-22）是同一套目录语义的两端：读目录按「原样 + 大写」两个都认，
     * 写 SQL 时裸名只认折后形式。设计器建的对象总是加引号、原样存小写，
     * 把那种名字裸写进编辑器 = 一执行就 ORA-00942。
     */
    @Override
    public boolean bareResolvesTo(String name) {
        return name != null && name.equals(name.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public String uriScheme() {
        return "oracle";
    }

    /**
     * ⭐ D-22：目录视图（{@code ALL_*} / {@code USER_*}）里的名字匹配 —— <b>精确或大写，两个都要</b>。
     *
     * <p>为什么不能只写 {@code UPPER(?)}：我们自己的表设计器建对象时<b>总是加引号</b>
     * （见 {@link #quote}），而 Oracle 对加引号的名字<b>原样存进目录</b> —— 用户建
     * {@code "orders"}，目录里就是小写的 {@code orders}。真库实测（Oracle 23ai Free）：
     * 建 {@code "adb_case_probe"} 之后 {@code TABLE_NAME = 'adb_case_probe'} 命中 1 行，
     * {@code TABLE_NAME = UPPER('adb_case_probe')} 命中 <b>0</b> 行 ——
     * 也就是说<b>我们自己设计器建出来的表，在触发器页、约束页、注释页里全部隐形</b>。
     *
     * <p>反过来也不能只写精确匹配：没加引号建的常规对象，目录里折成了大写。
     * 所以一条 {@code IN (精确, 大写)} 同时覆盖两条路径 —— 不做「先试一个、
     * 查不到再猜另一个」，那种写法把「查错了名字」和「这表真的没有触发器」混成了同一个结果。
     */
    private static String nameMatches(String column) {
        return column + " IN (?, UPPER(?))";
    }

    /** {@link #nameMatches} 的内联字面量版本 —— 调用方不绑参数的通道用（名字按 SQL 串转义）。 */
    private static String nameMatchesLiteral(String column, String name) {
        String exact = name == null ? "" : name.replace("'", "''");
        return column + " IN ('" + exact + "', '"
                + exact.toUpperCase(java.util.Locale.ROOT) + "')";
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
     * <p>R46 在 Oracle 23ai Free 容器上跑通：占位符按 {@link #nameMatches} 出两个，
     * 调用方（{@code SchemaService#bindTableNames} 与真库契约）要都绑上，少绑一个 ORA-01008。
     */
    @Override
    public String triggersSql(String table) {
        return "SELECT TRIGGER_NAME, TRIGGER_TYPE, TRIGGERING_EVENT, TRIGGER_BODY "
                + "FROM ALL_TRIGGERS WHERE " + nameMatches("TABLE_NAME") + " "
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
     * <p>R46 在 Oracle 23ai Free 容器上跑通（真库契约执行这条查询并读全部列）。
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
     * <p>⭐ D-22：传进去的名字必须是<b>目录里存的那个</b>，所以这里问两次：
     * 先按原样（我们设计器加引号建的对象，目录里就是原样），问不到再按大写
     * （没加引号建的常规对象，目录里折成了大写）。只传大写会把前者全问成
     * 「对象不存在」，只传原样会把后者全问成「对象不存在」—— 两个都会静默退化成
     * 「等价 DDL」，看不出区别。读不到就返回 {@code null}，由界面退化。
     */
    @Override
    public String readDdl(Connection connection, String table, boolean view) throws SQLException {
        String kind = view ? "VIEW" : "TABLE";
        String asIs = getDdl(connection, kind, table);
        if (asIs != null) {
            return asIs;
        }
        String upper = table == null ? "" : table.toUpperCase(java.util.Locale.ROOT);
        return upper.equals(table) ? null : getDdl(connection, kind, upper);
    }

    /** {@code DBMS_METADATA.GET_DDL} 的一次尝试；对象不存在 / 权限不够都返回 {@code null}。 */
    private static String getDdl(Connection connection, String kind, String name)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT DBMS_METADATA.GET_DDL(?, ?, SYS_CONTEXT('USERENV','CURRENT_SCHEMA')) "
                        + "FROM DUAL")) {
            statement.setString(1, kind);
            statement.setString(2, name);
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
                + "AND " + nameMatches("TABLE_NAME");
    }

    @Override
    public String columnCommentsSql(String table) {
        // ⚠️ 这条通道的表名是**内联**的（调用方不绑参数），所以按 SQL 字符串转义。
        return "SELECT COLUMN_NAME AS column_name, COMMENTS AS column_comment "
                + "FROM ALL_COL_COMMENTS "
                + "WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') "
                + "AND " + nameMatchesLiteral("TABLE_NAME", table);
    }

    /** 自增列 = 12c 的 identity 列，在 {@code USER_TAB_IDENTITY_COLS} 里。 */
    @Override
    public String autoIncrementColumn(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM USER_TAB_IDENTITY_COLS WHERE "
                        + nameMatches("TABLE_NAME"))) {
            statement.setString(1, table);
            statement.setString(2, table);
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
                + "WHERE c.CONSTRAINT_TYPE = 'C' AND " + nameMatches("c.TABLE_NAME") + " "
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
