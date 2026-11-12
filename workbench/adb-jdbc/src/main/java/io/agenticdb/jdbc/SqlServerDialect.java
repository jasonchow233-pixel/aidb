package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ⭐ D-3：SQL Server 方言。
 *
 * <p>它和已支持的三种库<b>不在同一个 SQL 家族</b>，差异集中在四处：
 * <ol>
 *   <li><b>引用符</b>是方括号 {@code [名称]}（内部 {@code ]} 写两遍），
 *       不是反引号也不是双引号；</li>
 *   <li><b>没有 {@code LIMIT}</b>：分页是 {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}
 *       （2012+），而且<b>必须跟在 {@code ORDER BY} 后面</b>，参数顺序还是 offset 在前；
 *       另外派生表里<b>不许</b>出现不带 {@code TOP} 的 {@code ORDER BY}，
 *       所以分页只能把子句<b>追加在原句尾部</b>，不能像 LIMIT 族那样套一层；</li>
 *   <li><b>改名走存储过程</b> {@code sp_rename}，不是 {@code ALTER TABLE … RENAME}；</li>
 *   <li><b>加列没有 {@code COLUMN} 关键字</b>（{@code ALTER TABLE t ADD c INT}），
 *       索引必须 {@code DROP INDEX i ON t} 带表名。</li>
 * </ol>
 *
 * <h2>真库验证状态（诚实条款）</h2>
 * <p>R46 起本地有 SQL Server 2022 容器，{@code sys.*} 目录查询全部进了
 * {@code RealDatabaseContract} 的矩阵，并且当场抓出两条只有真服务器会报的缺陷：
 * <ul>
 *   <li><b>D-20</b>：{@code sys.tables} 没有 {@code collation_name}
 *       （排序规则是列/库的属性），引用它让整条表清单元信息查询报
 *       {@code Invalid column name}，于是结构页和表清单一起空掉；</li>
 *   <li><b>D-24</b>：{@code sys.triggers} 没有 {@code is_insert/update/delete_trigger}，
 *       事件在 {@code sys.trigger_events} 里 —— 触发器页同样静默为空。</li>
 * </ul>
 * 另外实测到：{@code VARCHAR} 走库默认单字节代码页，中文写进去不报错但折成问号，
 * 所以新建列的默认类型是 {@code NVARCHAR}（见 {@link #defaultColumnType()}），
 * 且字符串字面量不带 {@code N} 前缀时同样会走代码页。
 *
 * <p>验证分层：
 * <ul>
 *   <li><b>已在单元测试锁住</b>：引用符、URL 与超时参数、分页与探测语句、
 *       各条 DDL 的文本（{@code SqlServerDialectTest} + 方言一致性快照），
 *       以及上面两条缺陷各自的文本门；</li>
 *   <li><b>明确不做</b>：{@code SHOWPLAN}/{@code DBMS_XPLAN} 那类执行计划
 *       （要两段会话级语句，见 {@link #supportsExplainPlan()}）、
 *       列注释（在扩展属性里，不是 {@code COMMENT}）、
 *       界面 TLS 档位（要两个参数配对，见 {@link #supportsSslOptionsUi()}）。
 *       界面对这些一律收起控件并说明原因，不摆点了没用的按钮。</li>
 * </ul>
 */
public final class SqlServerDialect implements Dialect {

    /**
     * ⭐ 连接 / 读超时。
     *
     * <p>⚠️ 单位不一样，这是从驱动自己的 {@code DriverPropertyInfo} 描述里读出来的：
     * {@code loginTimeout} 按<b>秒</b>，{@code socketTimeout} 按<b>毫秒</b>。
     * 写错单位等于要么 10 毫秒就断，要么 10 秒都不到 —— 都会伪装成「连不上」。
     */
    public static final int CONNECT_TIMEOUT_SECONDS = 10;
    public static final int SOCKET_TIMEOUT_MS = 30_000;

    @Override
    public DialectKind kind() {
        return DialectKind.SQLSERVER;
    }

    @Override
    public String displayName() {
        return "SQL Server";
    }

    /** SQL Server 用方括号引用标识符，内部的 {@code ]} 写成两个。 */
    @Override
    public String quote(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public String uriScheme() {
        return "sqlserver";
    }

    /** 社区里 {@code mssql://} 的写法很常见（旧驱动与文档都用它），一并认。 */
    @Override
    public List<String> uriAliases() {
        return List.of("sqlserver", "mssql");
    }

    /**
     * {@code jdbc:sqlserver://host:port;databaseName=db;…} —— 参数用<b>分号</b>分隔，
     * 不是 {@code ?}/{@code &}；库名也不是路径段，而是 {@code databaseName} 参数。
     *
     * <p>{@code encrypt=false}：mssql-jdbc 12.x 起<b>默认加密</b>，
     * 而内网 SQL Server 大多是自签证书，默认值会让连接直接失败
     * （报「证书不受信任」，看起来像「连不上」）。先按不加密连，
     * 要加密的用户在附加参数里写 {@code encrypt=true} 覆盖。
     */
    @Override
    public String jdbcUrl(String host, int port, String database) {
        StringBuilder url = new StringBuilder("jdbc:sqlserver://")
                .append(host).append(':').append(port)
                .append(";loginTimeout=").append(CONNECT_TIMEOUT_SECONDS)
                .append(";socketTimeout=").append(SOCKET_TIMEOUT_MS)
                .append(";encrypt=false");
        if (database != null && !database.isBlank()) {
            url.append(";databaseName=").append(database);
        }
        return url.toString();
    }

    @Override
    public String driverClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public int defaultPort() {
        return 1433;
    }

    /** SQL Server 的连接串用 {@code ;} 分隔参数，库名在 {@code databaseName=} 上。 */
    @Override
    public boolean usesSemicolonParameters() {
        return true;
    }

    @Override
    public String databaseOptionKey() {
        return "databaseName";
    }

    @Override
    public boolean usesFilePath() {
        return false;
    }

    // ─────────────────────────── 能力 ───────────────────────────

    /** 列注释在 SQL Server 是扩展属性（{@code sp_updateextendedproperty}），不是列定义的一部分。 */
    @Override
    public boolean supportsColumnComment() {
        return false;
    }

    /** 界面不摆 TLS 档位，理由见 {@link Dialect#supportsSslOptionsUi()}。 */
    @Override
    public boolean supportsSslOptionsUi() {
        return false;
    }

    @Override
    public String sslOptionsNoticeKey() {
        return "wizard.ssl.notice.sqlserver";
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

    /** 已有行的表上加 {@code NOT NULL} 列必须给默认值，否则服务端直接拒绝。 */
    @Override
    public boolean requiresDefaultForNotNullColumn() {
        return true;
    }

    @Override
    public boolean supportsTransactionalDdl() {
        return true;
    }

    /** 执行计划要 {@code SET SHOWPLAN_ALL ON} → 跑 → {@code OFF} 三段，一条语句做不到。 */
    @Override
    public boolean supportsExplainPlan() {
        return false;
    }

    @Override
    public String explainSql(String sql) {
        // supportsExplainPlan() = false，正常不会被调用；给个显式错误而不是静默返回 null。
        throw new UnsupportedOperationException(
                "SQL Server 的执行计划要会话级 SET SHOWPLAN_ALL，本轮不支持");
    }

    // ─────────────────────────── 元数据 ───────────────────────────

    /** 列与索引都走 {@code DatabaseMetaData}（驱动实现完整），不自己写 sys 查询。 */
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
     * 触发器：{@code sys.triggers} + {@code sys.sql_modules} + {@code sys.trigger_events}。
     *
     * <p>列序按接口约定：名称 / 时机 / 事件 / 语句。时机看
     * {@code is_instead_of_trigger}；事件在 <b>{@code sys.trigger_events}</b> 里
     * （一个触发器可以挂多个事件，所以聚成一列逗号串）。
     *
     * <p>⭐ D-24（真库 SQL Server 2022 抓到）：上一版读的是
     * {@code t.is_insert_trigger / is_update_trigger / is_delete_trigger} ——
     * <b>{@code sys.triggers} 里没有这三列</b>（实测该视图的列只有
     * {@code name / object_id / parent_id / is_instead_of_trigger / …}），
     * 整条查询报 {@code Invalid column name 'is_insert_trigger'}。
     * 生产代码把这个异常吞成「这张表没有触发器」，所以 SQL Server 的触发器页
     * <b>永远是空的</b> —— 不报错、不闪、什么都不显示。
     *
     * <p>聚合用 {@code FOR XML PATH} 而不是 {@code STRING_AGG}：后者要 2017+，
     * 这里连 2012 也得能用（原注释的约束保留）。
     */
    @Override
    public String triggersSql(String table) {
        return "SELECT t.name, "
                + "CASE WHEN t.is_instead_of_trigger = 1 THEN 'INSTEAD OF' ELSE 'AFTER' END, "
                + "STUFF((SELECT ',' + REPLACE(REPLACE(e.type_desc, 'AFTER_', ''), 'INSTEAD_', '') "
                + "FROM sys.trigger_events e WHERE e.object_id = t.object_id "
                + "ORDER BY e.type FOR XML PATH('')), 1, 1, ''), "
                + "m.definition "
                + "FROM sys.triggers t "
                + "JOIN sys.sql_modules m ON m.object_id = t.object_id "
                + "WHERE t.parent_id = OBJECT_ID(?) "
                + "ORDER BY t.name";
    }

    @Override
    public String triggersListSql() {
        return "SELECT t.name FROM sys.triggers t "
                + "JOIN sys.objects o ON o.object_id = t.parent_id "
                + "WHERE o.is_ms_shipped = 0 ORDER BY t.name";
    }

    /**
     * 表清单的元信息（那 8 个字段；Navicat 的清单只摆其中六列）。
     *
     * <p>{@code rows} 用 {@code sys.partitions} 的 {@code index_id IN (0,1)}
     * （0=堆、1=聚集索引，二者是同一份数据，取并集会翻倍）；
     * 大小用 {@code total_pages * 8KB}。
     *
     * <p>⚠️ SQL Server <b>没有</b>「行最后修改时间」，也没有表注释与「引擎」概念，
     * 这三项直接给 {@code NULL} —— 界面显示「—」，不拿创建时间冒充修改时间。
     *
     * <p>⚠️ D-20：排序规则取<b>库级</b>值，不是表列。真库实测（SQL Server 2022）：
     * {@code sys.tables} <b>根本没有</b> {@code collation_name} 这一列
     * （只有 {@code sys.columns} 有 —— 排序规则是<b>列</b>属性，不是表属性），
     * 上一版写 {@code t.collation_name} 让整条表清单元信息查询直接报
     * {@code Invalid column name 'collation_name'}，于是 SQL Server 的结构页/表清单全空。
     * 这里退一步显示库的默认排序规则（{@code DATABASEPROPERTYEX}），
     * 并在注释里说清它<b>不是</b>每表一个值。
     *
     * <p>⚠️ {@code rows} 是 T-SQL 保留字（{@code OFFSET … ROWS}），别名必须加方括号。
     */
    @Override
    public String tableMetaSql() {
        return "SELECT SCHEMA_NAME(t.schema_id) + '.' + t.name AS name, "
                + "(SELECT SUM(CAST(p.rows AS BIGINT)) FROM sys.partitions p "
                + "WHERE p.object_id = t.object_id AND p.index_id IN (0, 1)) AS [rows], "
                + "(SELECT SUM(a.total_pages) * 8 * 1024 FROM sys.allocation_units a "
                + "JOIN sys.partitions p2 ON p2.partition_id = a.container_id "
                + "WHERE p2.object_id = t.object_id) AS size_bytes, "
                + "NULL AS engine, "
                + "CONVERT(varchar(19), t.create_date, 120) AS created_at, "
                + "NULL AS updated_at, "
                + "CAST(DATABASEPROPERTYEX(DB_NAME(), 'Collation') AS varchar(128)) AS collation_name, "
                + "NULL AS comment_text "
                + "FROM sys.tables t WHERE t.is_ms_shipped = 0 ORDER BY t.name";
    }

    @Override
    public boolean readsDdlFromDatabase() {
        // 没有 SHOW CREATE TABLE —— DDL 由内省生成，界面标注「等价 DDL」。
        return false;
    }

    /**
     * ⭐ D-38：和 PG 同一条规矩 —— 表 DDL 由内省生成（{@link #readsDdlFromDatabase()} 仍是
     * {@code false}），但 view=true 这条路要把 {@code sys.sql_modules} 里的原文自己交出来：
     * 只调 {@code readDdl} 的两个调用方（设计视图开窗前的读回、整表重建时依赖视图的拆/挂清单）
     * 在返回 null 时的表现是「入口打不开」和「视图被级联删掉后没人重建」。
     */
    @Override
    public String readDdl(Connection connection, String table, boolean view) throws SQLException {
        return view ? readViewDdl(connection, table) : null;
    }

    /**
     * ⭐ D-27：读回视图定义走 {@code sys.sql_modules}。
     *
     * <p>这一条以前<b>整段是空的</b>（直接 {@code return null}），后果不是「少一栏显示」：
     * 视图设计器开窗前要先读回库里的定义，读不到就<b>不开窗</b>（不用空 body 盖掉真定义），
     * 于是在 SQL Server 上「设计视图」这个入口<b>一次都打不开</b>。
     * R52 的视图真库契约第一次把这段跑起来，当场就红了。
     *
     * <p>{@code SQL Server 没有} {@code SHOW CREATE TABLE}，但视图的原文确实存在库里
     * （{@code sys.sql_modules.definition}），所以 {@link #readsDdlFromDatabase()} 仍然是
     * {@code false}（表 DDL 还是由内省生成、界面标「等价 DDL」），只有视图这条路是真的读库。
     * 联 {@code sys.views} 而不是用 {@code OBJECT_ID(?)}：后者对同义词/函数也会给定义，
     * 而这条路只该认视图。
     */
    @Override
    public String readViewDdl(Connection connection, String table) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT m.definition FROM sys.sql_modules m "
                        + "JOIN sys.views v ON v.object_id = m.object_id WHERE v.name = ?")) {
            statement.setString(1, table);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String definition = rs.getString(1);
                    return definition == null || definition.isBlank() ? null : definition.trim();
                }
            }
        }
        return null;
    }

    /** {@code ALTER SCHEMA} 是「把对象搬到另一个 schema」，没有 PG 那种会话级切路径。 */
    @Override
    public String setSchemaSql(String schema) {
        return null;
    }

    /**
     * ⭐ N32：一条 SQL Server 连接绑定一个库（JDBC 的 {@code databaseName}），
     * 跨库 = 换连接。库清单读 {@code sys.databases}。
     */
    @Override
    public boolean switchesCatalogByReconnect() {
        return true;
    }

    @Override
    public String catalogsSql() {
        return "SELECT name FROM sys.databases ORDER BY name";
    }

    @Override
    public List<String> dependentViews(Connection connection, String table) throws SQLException {
        return List.of();
    }

    @Override
    public String currentCatalog(Connection connection) throws SQLException {
        return connection.getCatalog();
    }

    @Override
    public String currentSchema(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    // ─────────────────────────── 分页 / 探测（D-3 的正题）───────────────────────────

    /**
     * {@code ORDER BY … OFFSET n ROWS FETCH NEXT m ROWS ONLY}。
     *
     * <p>两个硬约束（都是 LIMIT 族没有的）：
     * <ul>
     *   <li>{@code OFFSET/FETCH} <b>必须</b>跟在 {@code ORDER BY} 之后 ——
     *       原句没排序就补一个常量排序 {@code ORDER BY (SELECT NULL)}，
     *       这是官方文档认可的「不指定顺序」写法；</li>
     *   <li>字面量版（数据页用）里 offset 在前、行数在后，与 {@code LIMIT n OFFSET m}
     *       的两个数字正好<b>相反</b>。</li>
     * </ul>
     */
    @Override
    public String pagingClause(long limit, long offset, boolean hasOrderBy) {
        return (hasOrderBy ? "" : " ORDER BY (SELECT NULL)")
                + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * 参数版分页：把子句<b>追加在原句尾部</b>，不套派生表。
     *
     * <p>⚠️ 为什么不能像 LIMIT 族那样套一层：SQL Server 的派生表里不允许
     * 出现不带 {@code TOP} 的 {@code ORDER BY} —— 用户那句
     * {@code SELECT … ORDER BY id} 一旦进了内层就直接语法错误。
     * 追加还有个好处：用户自己的 {@code ORDER BY} 正好成为分页的稳定排序。
     *
     * <p>参数顺序：原句占位符 → offset → limit（{@code OFFSET ? ROWS FETCH NEXT ?}）。
     */
    @Override
    public PagedSql pagedSelect(String source, List<Object> params, int limit, long offset) {
        List<Object> bound = new ArrayList<>(params == null ? List.of() : params);
        bound.add((int) offset);
        bound.add(limit);
        return new PagedSql(source + pagingClause(limit, offset, hasTopLevelOrderBy(source)),
                bound);
    }

    /**
     * 计数句：先把<b>尾部</b>的 {@code ORDER BY} 剥掉再套派生表。
     *
     * <p>排序不影响行数，但带 {@code ORDER BY} 的原句套进派生表在 SQL Server 上是
     * 语法错误 —— 不剥就数不出总行数（分页条直接显示「共 0 行」）。
     */
    @Override
    public PagedSql countedSelect(String source, List<Object> params) {
        return new PagedSql("SELECT COUNT(*) AS adb_count FROM ("
                + stripTrailingOrderBy(source) + ") AS _p",
                params == null ? List.of() : params);
    }

    /** 列元数据的空页探测：SQL Server 没有 {@code LIMIT 0}，用 {@code TOP 0}。 */
    @Override
    public String probeColumnsSql(String table) {
        return "SELECT TOP 0 * FROM " + quote(table);
    }

    /**
     * 原句尾部是否有 {@code ORDER BY}。
     *
     * <p>只看括号深度为 0 的部分：子查询里的 {@code ORDER BY} 不算这条语句的排序，
     * 拿它当「已经有排序了」会让 {@code OFFSET/FETCH} 落在错误的位置上。
     */
    private static boolean hasTopLevelOrderBy(String source) {
        String text = source == null ? "" : source.toLowerCase(Locale.ROOT);
        int depth = 0;
        for (int i = 0; i + 8 <= text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && text.startsWith("order by", i)
                    && (i == 0 || !isWordChar(text.charAt(i - 1)))
                    && !isWordChar(text.charAt(i + 8))) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉尾部的 {@code ORDER BY …}（含其后的 {@code OFFSET/FETCH}），只在深度 0 处认。 */
    private static String stripTrailingOrderBy(String source) {
        String text = source == null ? "" : source.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        int depth = 0;
        int cut = -1;
        for (int i = 0; i + 8 <= lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && lower.startsWith("order by", i)
                    && (i == 0 || !isWordChar(lower.charAt(i - 1)))
                    && !isWordChar(lower.charAt(i + 8))) {
                cut = i;
            }
        }
        if (cut < 0) {
            return text;
        }
        return text.substring(0, cut).trim();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ─────────────────────────── DDL ───────────────────────────

    /**
     * 列定义：{@code [名] 类型 [IDENTITY(1,1)] [NOT NULL] [DEFAULT 值] [UNIQUE]}。
     *
     * <p>自增是 {@code IDENTITY(1,1)}，跟在类型后；<b>不能</b>与默认值共存，
     * 那条校验在 {@link #autoIncrementProblem(ColumnSpec)}。
     */
    @Override
    public String columnDefinition(ColumnSpec column, boolean includeName) {
        StringBuilder definition = new StringBuilder();
        if (includeName) {
            definition.append(quote(column.name())).append(' ');
        }
        definition.append(column.effectiveType());
        // SQL Server 的语法位置：类型之后、IDENTITY / NULL 之前。
        if (column.collation() != null) {
            definition.append(" COLLATE ").append(column.collation());
        }
        if (column.autoIncrement()) {
            definition.append(" IDENTITY(1,1)");
        }
        definition.append(column.nullable() ? " NULL" : " NOT NULL");
        if (column.defaultValue() != null) {
            definition.append(" DEFAULT ").append(column.defaultValue());
        }
        if (column.unique()) {
            definition.append(" UNIQUE");
        }
        return definition.toString();
    }

    @Override
    public List<ColumnTypeOption> columnTypes() {
        return ColumnTypes.SQLSERVER;
    }

    /**
     * ⭐ D-23：新建列默认给 {@code NVARCHAR}，不是接口默认值 {@code VARCHAR}。
     *
     * <p>{@code VARCHAR} 用的是库默认排序规则（常见 {@code SQL_Latin1_General_CP1_CI_AS}）
     * 那一套<b>单字节代码页</b>：中文写进去不报错，但会静默折成 {@code ?}，
     * 读回来就是脏数据。{@code NVARCHAR} 才是 UTF-16，且建表时不带 {@code N} 前缀的
     * 字符串字面量同样会走代码页 —— 这两条都是 R46 在 SQL Server 2022 容器上实测的。
     */
    @Override
    public String defaultColumnType() {
        return "NVARCHAR";
    }

    /** SQL Server 的排序规则跟列类型走（{@code COLLATE} 在类型后），字符集没有列级概念。 */
    @Override
    public List<String> columnCollationPresets() {
        return List.of("Chinese_PRC_CI_AS", "SQL_Latin1_General_CP1_CI_AS",
                "Latin1_General_100_CI_AS_SC", "Binary");
    }

    @Override
    public ColumnProblem autoIncrementProblem(ColumnSpec column) {
        if (!column.autoIncrement()) {
            return null;
        }
        String base = column.baseType();
        if (!java.util.Set.of("INT", "BIGINT", "SMALLINT", "TINYINT", "DECIMAL", "NUMERIC")
                .contains(base)) {
            return new ColumnProblem("column.error.autoNeedsIntegerSqlServer",
                    column.name(), column.effectiveType());
        }
        if (column.defaultValue() != null) {
            return new ColumnProblem("column.error.autoHasDefault", column.name());
        }
        return null;
    }

    /** 列注释在扩展属性里，本轮不生成语句 —— 界面按 {@link #supportsColumnComment()} 收起。 */
    @Override
    public void appendColumnComment(String table, ColumnSpec column, List<String> statements) {
        // 故意留空：见 supportsColumnComment()。
    }

    @Override
    public String dropIndexSql(String table, String indexName) {
        // ⚠️ T-SQL 的 DROP INDEX 要带表名（和 PG / MySQL 都不一样）。
        return "DROP INDEX " + quote(indexName) + " ON " + quote(table);
    }

    @Override
    public String indexTypeClause(String type) {
        // 索引方法在 T-SQL 写在 CREATE INDEX 之后的 ON 之前（CLUSTERED / NONCLUSTERED），
        // 这条通道服务的是 BTREE/HASH 那类后缀 —— SQL Server 没有对应写法。
        return "";
    }

    @Override
    public boolean supportsIndexColumnDirection() {
        return true;
    }

    @Override
    public List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                              String oldRawType) {
        // T-SQL 的一条 ALTER COLUMN 只能改「类型 + 可空性」，而且改可空性必须连着类型重述，
        // 所以这条无条件发（判「类型没变就不发」在 PG 那边成立，在这里会漏掉可空性改动）。
        List<String> statements = new ArrayList<>(List.of(
                "ALTER TABLE " + quote(table) + " ALTER COLUMN " + quote(columnName)
                        + " " + next.effectiveType()
                        + (next.nullable() ? " NULL" : " NOT NULL")));
        // 默认值是独立约束：加可以（自己起名），**删**要先从目录里找旧约束名 —— 本轮不做，
        // 用户在界面上清空默认值时会看到「约束已存在」的服务端错误，不是静默无效。
        if (next.defaultValue() != null) {
            statements.add("ALTER TABLE " + quote(table) + " ADD CONSTRAINT "
                    + quote(defaultConstraintName(table, columnName))
                    + " DEFAULT " + next.defaultValue() + " FOR " + quote(columnName));
        }
        return statements;
    }

    /** 默认值约束名：T-SQL 不给自动命名，这里按「表_列」拼一个稳定名字。 */
    private static String defaultConstraintName(String table, String column) {
        return "DF_" + table.replace(".", "_") + "_" + column;
    }

    @Override
    public String renameColumnSql(String table, String oldName, String newName,
                                  io.agenticdb.model.ColumnDef oldColumn) {
        // ⚠️ sp_rename 的参数是**字符串**：里面写方括号是官方认可的引用写法，
        //    第三个参数 'COLUMN' 决定改的是列而不是表。
        return "EXEC sp_rename N'" + quotedLiteral(table) + "." + quotedLiteral(oldName)
                + "', N'" + quotedLiteral(newName) + "', 'COLUMN'";
    }

    @Override
    public String dropColumnSql(String table, String columnName) {
        return "ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(columnName);
    }

    @Override
    public String addColumnSql(String table, ColumnSpec column) {
        // T-SQL 没有 ADD COLUMN —— 只有 ADD。⭐ D-94：主键列在这里也要落地。
        return "ALTER TABLE " + quote(table) + " ADD "
                + createTableColumnDefinition(column, column.primaryKey());
    }

    @Override
    public String renameTableSql(String oldName, String newName) {
        return "EXEC sp_rename N'" + quotedLiteral(oldName) + "', N'"
                + quotedLiteral(newName) + "'";
    }

    @Override
    public String renameViewSql(String oldName, String newName) {
        return "EXEC sp_rename N'" + quotedLiteral(oldName) + "', N'"
                + quotedLiteral(newName) + "'";
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
     * ⭐ R42 N28-a：{@code CREATE OR ALTER VIEW} 是 SQL Server 2016 <b>SP1</b> 起
     * 才有的写法（更早的版本只能先删再建）。
     *
     * <p>为什么不为老版本退回两句：退回会连带删掉权限与依赖，比报错更难解释；
     * 未联真库前按主流受支持版本出 DDL，老服务器上执行会收到驱动原话
     * —— 这一条记在 D-3/D-5 的真库待验清单里（没有 Docker，本机验不了）。
     */
    @Override
    public ViewReplace viewReplace() {
        return ViewReplace.OR_ALTER;
    }

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

    /** 版本 + 当前登录用户（状态栏 U6）。 */
    @Override
    public String serverInfoSql() {
        return "SELECT @@VERSION, SUSER_SNAME()";
    }

    /** SQL Server 没有单列自增查询要走目录 —— {@code identity_property} 一列就够。 */
    @Override
    public String autoIncrementColumn(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT c.name FROM sys.columns c "
                        + "WHERE c.object_id = OBJECT_ID(?) AND c.is_identity = 1")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** SQL Server 的 {@code CHECK} 在 {@code sys.check_constraints}。 */
    @Override
    public String checksSql(String table) {
        return "SELECT cc.name, cc.definition FROM sys.check_constraints cc "
                + "WHERE cc.parent_object_id = OBJECT_ID(?) ORDER BY cc.name";
    }

    @Override
    public String dropTriggerSql(String table, String triggerName) {
        return "DROP TRIGGER " + quote(triggerName);
    }

    /** T-SQL 的触发器是一条语句：{@code CREATE TRIGGER … ON 表 AFTER INSERT AS …}。 */
    @Override
    public String triggerBodyHint() {
        return "SQL Server：写触发器主体（AS 之后的语句块），时机与事件由表单拼";
    }

    @Override
    public void appendCreateTriggerSql(String table, String name, String timing, String event,
                                       String body, List<String> statements) {
        statements.add("CREATE TRIGGER " + quote(name) + " ON " + quote(table) + " "
                + timing.toUpperCase(Locale.ROOT) + " " + event.toUpperCase(Locale.ROOT)
                + " AS " + body);
    }

    /**
     * {@code sp_rename} 的字符串字面量：内部单引号写成两个。
     *
     * <p>⚠️ 这里是把标识符放进<b>字符串</b>里，不能走 {@link #quote(String)} 的方括号逻辑，
     * 需要的是 SQL 字符串转义；名字里的 {@code '} 不转义就是语法错误 + 注入面。
     */
    private static String quotedLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
