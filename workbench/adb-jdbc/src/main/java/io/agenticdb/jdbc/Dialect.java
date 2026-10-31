package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ⭐ 数据库方言的行为抽象。
 *
 * <p><b>为什么要有它</b>：加一种数据库以前要改 6 个文件 ——
 * {@code DialectKind}（枚举 + 引用 + URL）、{@code JdbcSession}（驱动类）、
 * {@code ConnectionProfile}（端口 + 展示）、{@code SchemaService}（触发器 + DDL + 5 处分支）、
 * {@code StructureSqlBuilder}（6 处分支）、{@code SessionManager} / {@code ConnectionPool}（池化）。
 * 那些分支散在五个文件里，加一种库要同时改五处，漏一处就是「某个方言下功能悄悄不对」。
 *
 * <p>现在：<b>加一种数据库 = 写一个 {@code Dialect} 实现 + 在 {@link DialectKind} 里登记一项</b>。
 * 其它代码一律通过这个接口提问，不再出现 {@code dialect == DialectKind.XXX}。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>标识符引用一律走 {@link #quote(String)}；参数化查询里只有标识符才走它。</li>
 *   <li>DDL 生成也在方言里 —— MySQL 与 PostgreSQL 的「改列」「加注释」写法根本不同，
 *       构造器不该拿布尔开关去猜。</li>
 *   <li>SQLite 是特例：它没有 catalog / schema 概念、结果集元数据不带声明类型、
 *       改列要整表重建。这些差异通过能力查询表达，而不是靠类型判断。</li>
 * </ul>
 */
public interface Dialect {

    // ─────────────────────────── 身份 ───────────────────────────

    DialectKind kind();

    String displayName();

    // ─────────────────────────── 连接 ───────────────────────────

    /** 标识符引用。SQLite / PostgreSQL 用双引号，MySQL 用反引号。 */
    String quote(String identifier);

    String jdbcUrl(String host, int port, String database);

    /**
     * JDBC URL 的方案名（不含 {@code jdbc:} 前缀与结尾冒号），如 {@code "mysql"}。
     *
     * <p>两个用途：① 已连接的会话<b>反查方言</b>（{@code JdbcSession.inferDialect}）；
     * ② 连接向导解析 {@code mysql://…} 这类简写 URI。两边都遍历本方法与
     * {@link #uriAliases()}，不再各写一串 {@code startsWith} 分支 ——
     * 加一种库只改方言自己。
     */
    String uriScheme();

    /** 简写 URI 里额外认得的别名（PostgreSQL 认 {@code postgres://}）。 */
    default List<String> uriAliases() {
        return List.of(uriScheme());
    }

    String driverClassName();

    int defaultPort();

    /**
     * 连接串是否用 {@code ;} 分隔参数（SQL Server 风格：
     * {@code jdbc:sqlserver://host:1433;databaseName=shop;encrypt=false}）。
     *
     * <p>界面的 URI 输入框要据此决定去哪找参数：按 {@code ?} 切会把
     * {@code ;databaseName=shop} 当成库名的一部分，填进去就是一个连不上错库的连接。
     */
    default boolean usesSemicolonParameters() {
        return false;
    }

    /** 「库名 / 服务名」写在参数串里时的参数名；{@code null} 表示它只在路径上。 */
    default String databaseOptionKey() {
        return null;
    }

    /**
     * ⭐ D-3：只能走 {@code Properties}、进不了 URL 查询串的驱动级参数。
     *
     * <p>MySQL / PostgreSQL / MariaDB 把超时写在 URL 里就够了；Oracle 的
     * {@code oracle.net.CONNECT_TIMEOUT} / {@code oracle.jdbc.ReadTimeout}
     * 只认连接属性。没有这类参数的方言返回空 Map。
     */
    default java.util.Map<String, String> connectionProperties() {
        return java.util.Map.of();
    }

    /** 是否走连接池。SQLite 是单文件直连，不池化。 */
    boolean pooled();

    /**
     * 连接目标是「文件路径」还是「主机 + 端口 + 库名」。
     *
     * <p>连接向导要据此换表单：SQLite 问「文件在哪」，其它问「连哪台机器」。
     */
    boolean usesFilePath();

    // ─────────────────────────── 能力 ───────────────────────────

    /**
     * 是否支持列注释（MySQL {@code COMMENT} / PostgreSQL {@code COMMENT ON}）。
     * SQLite 没有列注释概念 —— UI 必须据此收起注释列与输入框，
     * 否则就是永远填不进、读不出的死控件（4.5-22）。
     */
    boolean supportsColumnComment();

    /**
     * ⭐ D-3：连接向导要不要摆「TLS 档位 + 根证书」那一组控件。
     *
     * <p>MySQL / PostgreSQL / MariaDB 的加密开关就是<b>一个档位字符串</b>
     * （{@code sslmode=require} / {@code sslMode=REQUIRED}），界面能如实表达。
     * SQL Server 要 {@code encrypt} 与 {@code trustServerCertificate} 两个参数配对，
     * Oracle 走的是钱包 + {@code oracle.net.*} 一整套 —— 拿 MySQL 那六个档位去套，
     * 用户填了<b>不生效</b>的参数，比没有这个面板更糟。所以这两家先收起整组控件，
     * 在界面上说明「本轮请按驱动参数写在附加参数里」。
     */
    default boolean supportsSslOptionsUi() {
        return true;
    }

    /**
     * ⭐ D-3：连接向导 TLS 页的档位 —— 界面语言，不带驱动色彩。
     *
     * <p>「档位在界面上是同一个词，落到不同驱动是三套完全不同的拼写」这件事，
     * 以前写在界面里（界面 switch 方言）。现在界面只问方言两件事：
     * 这一家<b>能表达哪几档</b>（{@link #sslLevels()}），以及<b>这一档变成什么参数</b>
     * （{@link #sslOptions(SslLevel)}）。摆界面填不进、也不生效的参数 = 骗人。
     */
    enum SslLevel {
        OFF, PREFER, REQUIRE, VERIFY_CA, VERIFY_IDENTITY
    }

    /** 本方言能表达的档位（默认五档全给；驱动里没有 {@code prefer} 这一说的就收掉）。 */
    default List<SslLevel> sslLevels() {
        return List.of(SslLevel.values());
    }

    /**
     * 档位 → 驱动参数。
     *
     * <p>{@link SslLevel#OFF} 与「本方言不摆 TLS 页」时返回空 Map —— 意思是
     * <b>不写任何加密参数</b>，而不是写一个 {@code sslmode=disable}：
     * 各家驱动的默认值不同，留空才是「按服务端默认来」这句实话。
     */
    default Map<String, String> sslOptions(SslLevel level) {
        return Map.of();
    }

    /** 从已保存的 options 还原档位（识别不了按 {@link SslLevel#OFF}）。 */
    default SslLevel sslLevel(Map<String, String> options) {
        return SslLevel.OFF;
    }

    /**
     * 根证书文件的参数名；{@code null} 表示这一家的 TLS 页<b>不单独收 CA 文件</b>。
     *
     * <p>PostgreSQL 有 {@code sslrootcert}，MariaDB 有 {@code serverSslCert}（驱动参数表实测），
     * MySQL Connector/J 8 的 CA 参数本轮未验证 → 给 null，界面把输入框禁用，
     * 比摆一个填了不生效的框诚实。
     */
    default String sslRootCertOption() {
        return null;
    }

    /**
     * TLS 页收起时给用户的说明，返回的是<b>资源键</b>而不是文案
     * （{@link #supportsSslOptionsUi()} 为 false 时该给）。
     *
     * <p>这是界面文案，不是驱动事实：写成中文字面量会让英文界面也长出一段中文
     * （D-7 那一类），也会让模型层的汉字棘轮为了一句 UI 文案上涨。方言只说
     * 「我这份说明在资源包的哪个键上」，两种语言由界面各自去取。
     */
    default String sslOptionsNoticeKey() {
        return null;
    }

    /** 结果集元数据是否带声明类型与默认值（SQLite 不带，要读 PRAGMA）。 */
    boolean readsColumnsViaJdbcMetadata();

    /** {@code DatabaseMetaData.getIndexInfo} 是否可用（SQLite 不可用，要读 PRAGMA）。 */
    boolean readsIndexesViaJdbcMetadata();

    /** 是否有 catalog 概念。 */
    boolean hasCatalog();

    /** 是否有 schema 概念。 */
    boolean hasSchema();

    /** 改列是否必须整表重建（SQLite 是）。 */
    boolean rebuildsTableOnColumnChange();

    /** 新增 NOT NULL 列是否必须提供默认值（SQLite 是）。 */
    boolean requiresDefaultForNotNullColumn();

    // ─────────────────────────── 元数据 ───────────────────────────

    /** 列定义查询；返回 {@code null} 表示走 JDBC 元数据。 */
    String columnsSql(String table);

    /** 索引查询；返回 {@code null} 表示走 JDBC 元数据。 */
    String indexesSql(String table);

    /**
     * 单个索引的列查询（{@code PRAGMA index_info} 形态）。
     *
     * <p>只有走 PRAGMA 的方言需要它；走 JDBC 元数据的方言返回 {@code null}。
     */
    String indexColumnsSql(String indexName);

    /** 触发器查询。列顺序固定为：名称 / 触发时机 / 事件 / 语句。 */
    String triggersSql(String table);

    // ─────────────────── 外键 / 触发器（B5-S2 / S3）───────────────────

    /**
     * ⭐ 外键动作码 → 动作名（{@code NO ACTION} / {@code CASCADE} / …）。
     *
     * <p><b>为什么必须翻译</b>：{@code getImportedKeys()} 返回的是<b>数字码</b>，
     * 不翻译就直接拼进 {@code ON UPDATE <code>} —— 那是一条**语法错误**的 SQL。
     * 而且**各驱动的码不一样**：
     * <ul>
     *   <li>JDBC 标准：0=NO ACTION · 1=RESTRICT · 2=CASCADE · 3=SET NULL · 4=SET DEFAULT</li>
     *   <li>SQLite 驱动（Xerial，实测）：0=CASCADE · 1=RESTRICT · 2=SET NULL ·
     *       <b>3=NO ACTION</b> · 4=SET DEFAULT</li>
     * </ul>
     * 所以这属于**方言差异**，放在方言层，不放通用代码里。
     *
     * @param rawCode 驱动返回的码（或已经是动作名）
     */
    default String foreignKeyActionName(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return "NO ACTION";
        }
        String code = rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            case "0" -> "NO ACTION";
            case "1" -> "RESTRICT";
            case "2" -> "CASCADE";
            case "3" -> "SET NULL";
            case "4" -> "SET DEFAULT";
            // 已经是动作名（有些驱动直接给名字）→ 原样用
            default -> code;
        };
    }

    /**
     * 新增外键的 SQL。
     *
     * <p>返回 {@code null} = 该方言不能「直接 ALTER 加外键」，
     * 必须整表重建（SQLite：外键是 {@code CREATE TABLE} 的一部分）。
     *
     * @param name             约束名（可空 → 由方言生成）
     * @param columns          本表的列
     * @param referencedTable  被引用的表
     * @param referencedColumns 被引用的列
     * @param onUpdate         ON UPDATE 动作（CASCADE / SET NULL / …）
     * @param onDelete         ON DELETE 动作
     */
    default String addForeignKeySql(String table, String name, List<String> columns,
                                    String referencedTable, List<String> referencedColumns,
                                    String onUpdate, String onDelete) {
        return null;
    }

    /** 删除外键的 SQL；返回 {@code null} = 要整表重建。 */
    default String dropForeignKeySql(String table, String constraintName) {
        return null;
    }

    /**
     * ⭐ D-3：这个库的外键能不能声明 {@code ON UPDATE} 动作。
     *
     * <p>MySQL / PostgreSQL / SQL Server 能；<b>Oracle 不能</b>（引用动作只有
     * {@code ON DELETE}，更新级联要靠触发器）。界面据此收起「ON UPDATE」选择器 ——
     * ⛔ 不能让用户填一个根本不写进语句的动作，还以为它生效了。
     */
    default boolean supportsForeignKeyOnUpdate() {
        return true;
    }

    /** 该方言改外键是否必须整表重建（SQLite = true）。 */
    default boolean rebuildsTableOnForeignKeyChange() {
        return false;
    }

    /** 删除触发器。 */
    default String dropTriggerSql(String table, String triggerName) {
        return "DROP TRIGGER " + quote(triggerName);
    }

    /**
     * 触发器体该怎么写 —— 给用户的一句提示。
     *
     * <p>方言差异（PostgreSQL 写函数体、其它写 SQL 体）属于**方言层**的事，
     * UI 只能显示，不能自己判断。
     */
    default String triggerBodyHint() {
        return "触发器体：写 BEGIN … END 里的 SQL（可用 NEW. / OLD. 引用行）";
    }

    /**
     * 新建触发器：把语句追加到 {@code statements}。
     *
     * <p>默认实现是「一条 CREATE TRIGGER」—— SQLite / MySQL 都是这个语法。
     * PostgreSQL 需要先建函数再建触发器，所以它覆写这个方法（追加两条）。
     *
     * @param body 触发器体（{@code BEGIN … END} 里的内容）
     */
    default void appendCreateTriggerSql(String table, String name, String timing, String event,
                                        String body, List<String> statements) {
        // ⭐ 触发器体里最后一条语句**必须带分号**（SQLite / MySQL 语法：
        //    BEGIN <语句>; END）—— 少了分号是「incomplete input / near END」语法错误。
        String trimmed = body == null ? "" : body.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        statements.add("CREATE TRIGGER " + quote(name) + " " + timing + " " + event
                + " ON " + quote(table) + " BEGIN " + trimmed + "; END");
    }

    /**
     * ⭐ CHECK 约束的查询 SQL：**第 1 列 = 约束名，第 2 列 = 表达式**。
     *
     * <p>返回 {@code null} 表示「该方言不通过查询提供 CHECK」——
     * SQLite 就是这种（它只能从 {@code CREATE TABLE} 原文里解析，
     * 见 {@link #parsesChecksFromDdl()}）。
     *
     * <p>⚠️ 旧版本 MySQL（5.7）根本没有 CHECK 约束 —— 那时靠数据库自己忽略；
     * 这里查不到就返回空列表，不报错。
     */
    default String checksSql(String table) {
        return null;
    }

    /**
     * 该方言是否**从 CREATE TABLE 原文**里解析 CHECK 约束。
     *
     * <p>SQLite = true（没有 PRAGMA / information_schema 能读）；
     * PostgreSQL / MySQL = false（走 {@link #checksSql(String)} 查询）。
     */
    default boolean parsesChecksFromDdl() {
        return false;
    }

    /**
     * 能否直接从数据库读 DDL。
     *
     * <p>SQLite 读 {@code sqlite_master}、MySQL 走 {@code SHOW CREATE TABLE}；
     * PostgreSQL 没有这回事 —— DDL 只能由内省生成（UI 会标注「等价 DDL」）。
     */
    boolean readsDdlFromDatabase();

    /**
     * 从数据库直接读 DDL。
     *
     * @param view 目标是不是视图
     * @return DDL 文本；读不到时返回 {@code null}
     */
    String readDdl(Connection connection, String table, boolean view) throws SQLException;

    /**
     * 表元信息查询：一次取出 名称 / 行数 / 大小 / 引擎 / 创建时间。
     *
     * <p>返回 {@code null} 表示这个库不提供这些元信息
     * （SQLite 没有表大小与创建时间），调用方会退化成「只算行数」。
     *
     * <p>列名固定为：{@code name} / {@code rows} / {@code size_bytes} /
     * {@code engine} / {@code created_at}。
     */
    String tableMetaSql();

    /**
     * ⭐ UX-6：表级**物理选项**查询。
     *
     * <p>列名固定为：{@code engine} / {@code collation_name} /
     * {@code auto_increment} / {@code row_format}；表名用<b>一个 {@code ?}
     * 绑定</b>（表名是值不是标识符，不拼进 SQL）。
     *
     * <p>返回 {@code null} = 该方言没有这些表级选项
     * （SQLite / PostgreSQL 的引擎、排序规则、行格式不是表属性）——
     * 于是结构页「选项」页签诚实地显示「—」，而不是给每条连接都编一个 InnoDB。
     */
    default String tableOptionsSql() {
        return null;
    }

    /**
     * 触发器列表查询（只取名字）。
     *
     * <p>JDBC 的 {@code DatabaseMetaData} <b>没有</b>触发器列表，
     * 所以只能各方言自己给一条 SQL。返回 {@code null} 表示不提供。
     */
    String triggersListSql();

    /**
     * ⭐ O1：调度事件列表的查询（**第 1 列 = 事件名**）。
     *
     * <p>只有 MySQL 有这个概念 → 其它方言返回 {@code null}，
     * 于是对象树里**不会出现**「事件」分组（不假装有）。
     */
    default String eventsListSql() {
        return null;
    }

    /**
     * ⭐ O1：读事件的定义体（{@code SHOW CREATE EVENT}）。
     *
     * <p>默认返回空串 = 该方言没有事件（MySQL 覆写）。
     */
    default String readEventDefinition(Connection connection, String name) throws SQLException {
        return "";
    }

    /**
     * 切换 catalog 是否必须**重连**。
     *
     * <p>MySQL 的 schema 就是数据库：一条连接绑定一个库，切库只能重连
     * （{@code USE} 对连接池里的每条连接都要发，管不过来）。
     * PostgreSQL 切 schema 走 {@link #setSchemaSql}（{@code SET search_path}），
     * SQLite 没有 catalog 概念 —— 两者都返回 {@code false}。
     *
     * <p>UI 据此二选一：{@code true} → 对象树出 catalog 层（切库 = 换连接）；
     * {@code false} → schema 切换器（{@link #setSchemaSql} 非空时）。
     */
    default boolean switchesCatalogByReconnect() {
        return false;
    }

    /**
     * catalog（数据库）列表查询：**第 1 列 = 库名**。
     *
     * <p>只有「切 catalog 要重连」的方言提供（MySQL，读
     * {@code information_schema.SCHEMATA}）；其它方言返回 {@code null}
     * （PG 的连接本身绑在一个库上，SQLite 没有库的概念）——
     * 于是对象树里不会出现 catalog 层，不假装有。
     */
    default String catalogsSql() {
        return null;
    }

    /**
     * 切换当前 schema 的语句。
     *
     * <p>PostgreSQL 用 {@code SET search_path}；其它方言返回 {@code null}
     * （MySQL 的 schema 就是数据库，切换要重连；SQLite 没有 schema 概念）。
     */
    String setSchemaSql(String schema);

    /**
     * 列出引用了指定对象的视图。
     *
     * <p>只有整表重建需要它（SQLite 重建表前要先拆掉依赖它的视图），
     * 其它方言返回空列表。
     */
    List<String> dependentViews(Connection connection, String table) throws SQLException;

    /**
     * 读视图定义。
     *
     * <p>PostgreSQL 用 {@code pg_get_viewdef}；其它库返回 {@code null}
     * （它们的 {@code readDdl} 已经能读出视图）。
     */
    String readViewDdl(Connection connection, String table) throws SQLException;

    /**
     * 该表的**自增列**（auto increment / identity / rowid 别名）。
     *
     * <p>为什么要探测：新增行时这一列**应该留空**，交给数据库赋值 ——
     * 用户看到「自增」标记才知道可以不管它；看不到标记就可能手动填一个，
     * 结果撞主键。
     *
     * @return 自增列名；这张表没有自增列时返回 {@code null}
     */
    default String autoIncrementColumn(Connection connection, String table)
            throws SQLException {
        return null;
    }

    /** 当前 catalog；没有这个概念时返回 {@code null}。 */
    String currentCatalog(Connection connection) throws SQLException;

    /** 当前 schema；没有这个概念时返回 {@code null}。 */
    String currentSchema(Connection connection) throws SQLException;

    // ─────────────────────────── 结构变更 ───────────────────────────

    /** 列定义（含类型 / NOT NULL / DEFAULT / UNIQUE，注释按方言处理）。 */
    String columnDefinition(ColumnSpec column, boolean includeName);

    /**
     * 自增列的方言约束；{@code null} = 这一列的设定能用。
     *
     * <p>三条库的限制不一样（MySQL 要整型且必须是键，PostgreSQL identity 只认
     * smallint/integer/bigint，SQLite 的 rowid 别名必须是 {@code INTEGER PRIMARY KEY}），
     * 而「怎么报错」只有方言自己说得清。放在这里而不是 UI：结构页、建表弹窗、
     * 将来的导入建表都走同一条判定。
     */
    default String autoIncrementProblem(ColumnSpec column) {
        if (!column.autoIncrement()) {
            return null;
        }
        if (!INTEGER_TYPES.contains(column.baseType())) {
            return "自增列必须是整型：" + column.name() + " 现在是 " + column.effectiveType();
        }
        if (column.defaultValue() != null) {
            return "自增列不能同时设默认值：" + column.name();
        }
        return null;
    }

    /** 自增列允许的整型名（各库略有出入，超集够用 —— 具体库再收紧）。 */
    Set<String> INTEGER_TYPES = Set.of("TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER",
            "BIGINT", "SERIAL", "BIGSERIAL", "INT2", "INT4", "INT8");

    /**
     * 自增列是否必须是<b>唯一</b>主键（不能是多列主键的一部分）。
     *
     * <p>MySQL 允许自增列是多列索引的第一列，PostgreSQL 的 identity 也不要求是主键；
     * SQLite 的 {@code AUTOINCREMENT} 只能挂在<b>单列</b> {@code INTEGER PRIMARY KEY} 上。
     */
    default boolean autoIncrementNeedsSinglePrimaryKey() {
        return false;
    }

    /**
     * 追加列注释语句。
     *
     * <p>PostgreSQL 用 {@code COMMENT ON COLUMN}；MySQL 把注释写在列定义里（这里什么都不追加）；
     * SQLite 不支持（这里什么都不追加）。
     */
    void appendColumnComment(String table, ColumnSpec column, List<String> statements);

    /**
     * 「修改列」时把注释<b>清空</b>的收尾语句。
     *
     * <p>只有 PostgreSQL 需要：它的注释独立于列定义存在，改类型/NOT NULL
     * 都不会碰它 —— 用户把注释框清空后不下发 {@code IS NULL}，旧注释会一直挂着，
     * 表现成「清空无效」。MySQL 的注释写在列定义里（{@code MODIFY COLUMN} 不带
     * COMMENT 即清空），SQLite 没有注释概念 —— 两者都不需要额外语句。
     *
     * <p>⭐ 只在「原来有注释、现在没了」时调用，避免给每一次改列都塞一条空操作。
     */
    default void appendColumnCommentReset(String table, String columnName,
                                          List<String> statements) {
        // 默认什么都不做。
    }

    /**
     * 删除索引。
     *
     * <p>⭐ 差异真实存在且只有方言知道：MySQL 的 {@code DROP INDEX} 是
     * {@code ALTER TABLE} 的语法糖，<b>必须带 {@code ON 表名}</b>；
     * SQLite / PostgreSQL 只认索引名，带 {@code ON} 反而语法错误。
     */
    String dropIndexSql(String table, String indexName);

    /** 主键索引在各库里的名字 —— 不能直接 {@code DROP INDEX} 掉。 */
    default String primaryIndexName() {
        return "PRIMARY";
    }

    /**
     * 这个库可选的<b>索引类型</b>（{@code CREATE INDEX ... USING method}）。
     *
     * <p>⭐ 空列表 = 这个库没有「类型可选」这回事，UI 必须把整行控件收起来 ——
     * 给 SQLite 摆一个只有 BTREE 的下拉，就是一个永远只会填默认值的死控件
     * （任务书 §六「不得虚构能力」+ §九「不留无用控件」）。
     *
     * <p>MySQL 也返回空：InnoDB 只实现 BTREE，写 {@code USING HASH} 会被引擎直接拒
     * （MyISAM 才两种都有）。递给用户一个「选了必报错」的选项，比不给选项更坏。
     */
    default List<String> indexTypes() {
        return List.of();
    }

    /**
     * 索引类型子句，位置也由方言决定 —— PG 是 {@code ON 表 USING btree (列)}。
     *
     * <p>没选类型或不支持类型的方言返回空串（调用方直接拼接即可）。
     */
    default String indexTypeClause(String type) {
        return "";
    }

    /**
     * 索引列能不能带 {@code ASC / DESC}。
     *
     * <p>只有 PostgreSQL 返回 true。MySQL 的 {@code DESC} 要 8.0 才真正生效
     * （5.7 只解析、忽略），SQLite 也一样 —— 而我们没有运行时版本判定
     * （{@link #serverInfoSql()} 只用来展示）。在拿不准的库上发 {@code DESC}，
     * 用户会看到「建了降序索引，实际是升序」这种最坏的假成功（任务书 §6.5.6），
     * 所以宁可不给这个选项。
     */
    default boolean supportsIndexColumnDirection() {
        return false;
    }

    /**
     * 读列注释的 SQL；返回 {@code null} 表示这种数据库读不到列注释。
     *
     * <p>⭐ 必须放在方言里：SQLite 根本没有注释概念，MySQL 要查
     * {@code information_schema}，PostgreSQL 要查 {@code col_description}。
     * 写死在任何一处，另外两种库就会静默拿到 null —— 也就是「改一次列，注释没了」。
     *
     * <p>结果集固定两列：第 1 列列名、第 2 列注释（可能为 NULL）。
     */
    default String columnCommentsSql(String table) {
        return null;
    }

    /**
     * 改列的语句列表。
     *
     * @param oldRawType 原声明类型；与新类型不同时才会生成类型变更语句
     */
    List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                       String oldRawType);

    /**
     * 重命名列。
     *
     * @param oldColumn 旧列的定义。MySQL 5.7 没有 {@code RENAME COLUMN}，
     *                   只能用 {@code CHANGE COLUMN}（要带完整定义），所以方言需要它。
     */
    String renameColumnSql(String table, String oldName, String newName,
                           io.agenticdb.model.ColumnDef oldColumn);

    String dropColumnSql(String table, String columnName);

    String addColumnSql(String table, ColumnSpec column);

    /**
     * 建表语句里的单列定义。
     *
     * <p>为什么不能直接复用 {@link #columnDefinition}：{@code PRIMARY KEY} 要放在
     * <b>方言要求的顺序</b>上。MySQL 的列属性顺序是
     * {@code … [UNIQUE] [PRIMARY KEY] [COMMENT 'x']} —— 主键必须在注释<b>之前</b>，
     * 而注释已经由 {@link #columnDefinition} 拼好了，插哪儿只有方言自己知道。
     */
    default String createTableColumnDefinition(ColumnSpec column, boolean inlinePrimaryKey) {
        return columnDefinition(column, true) + (inlinePrimaryKey ? " PRIMARY KEY" : "");
    }

    /**
     * ⭐ 建表语句尾部的<b>表级选项</b>（字符集等）；{@code null} / 空 = 不加。
     *
     * <h2>为什么必须有这一条</h2>
     * MySQL 8.0 之前服务器的默认字符集是 <b>latin1</b>（8.0 起才是 utf8mb4）。
     * 我们的建表语句以前不带字符集，于是表继承服务器默认值 ——
     * 在 5.7 上建出来的表是 latin1，中文数据一插就报
     * {@code Incorrect string value: '\xE5\x80\xBC' …}，
     * 而且**报的是驱动原文**，用户完全看不出来是字符集问题。
     *
     * <p>这是多版本真库矩阵（{@code DatabaseVersionMatrixTest}）抓出来的：
     * 同一套断言在 8.0 / 8.4 / PostgreSQL 上全绿，只有 5.7 炸 ——
     * 又一次证明「SQLite 和最新版覆盖不到跨版本行为」。
     *
     * <p>PostgreSQL 的字符集是<b>建库时</b>定的（表级没有 ENCODING 子句），
     * 所以这里返回 {@code null}；SQLite 只有 UTF-8 / UTF-16，也不用指定。
     */
    default String createTableOptions() {
        return null;
    }

    // ─────────────────────────── 表级生命周期 ───────────────────────────

    /**
     * 重命名表。
     *
     * <p>MySQL 用 {@code RENAME TABLE}（{@code ALTER TABLE … RENAME} 在 5.7 上是有的，
     * 但 {@code RENAME TABLE} 才是官方写法且能一次改多张）；
     * PostgreSQL 与 SQLite 用 {@code ALTER TABLE … RENAME TO}。
     */
    String renameTableSql(String oldName, String newName);

    /**
     * 清空表（保留表结构）。
     *
     * <p>SQLite <b>没有</b> {@code TRUNCATE} —— 只能用 {@code DELETE FROM}。
     * 这也是为什么这条语句必须由方言给，不能写死在 UI 里。
     */
    String truncateTableSql(String table);

    String dropTableSql(String table);

    /** 表注释：PostgreSQL / MySQL 支持，SQLite 不支持。 */
    default boolean supportsTableComment() {
        return false;
    }

    /**
     * ⭐ R31：列级字符集。只有 MySQL 有 —— PostgreSQL 的编码是<b>建库时</b>定的，
     * SQLite 只有 UTF-8 / UTF-16，两者在列上写不进任何语句。
     */
    default boolean supportsColumnCharset() {
        return false;
    }

    /** ⭐ R31：列级排序规则。三种方言都有（MySQL / PostgreSQL 要引号，SQLite 直接写）。 */
    default boolean supportsColumnCollation() {
        return true;
    }

    /**
     * ⭐ R31：表设计器「类型」下拉的候选（顺序即显示顺序）。
     *
     * <p>空列表 = 该方言没有可推荐的类型名，UI 退回自由文本输入 ——
     * 不能因为没有清单就禁止建表。
     */
    default List<ColumnTypeOption> columnTypes() {
        return List.of();
    }

    /**
     * ⭐ R31：列级字符集的常用候选（下拉里的建议，控件本身可编辑）。
     *
     * <p>放在方言层而不是 UI 里判 {@code kind() == MYSQL}：A1 的承诺是「加一种数据库
     * = 写一个 {@code Dialect} 实现」，UI 里散落枚举比较就等于承诺是假的
     * （{@code DialectBranchAuditTest} 拦这条）。
     */
    default List<String> columnCharsetPresets() {
        return List.of();
    }

    /** ⭐ R31：列级排序规则的常用候选。 */
    default List<String> columnCollationPresets() {
        return List.of();
    }

    /** 表注释语句。仅在 {@link #supportsTableComment()} 为真时调用。 */
    default String tableCommentSql(String table, String comment) {
        throw new UnsupportedOperationException(displayName() + " 不支持表注释");
    }

    /**
     * ⭐ 读**函数 / 存储过程**的定义体。
     *
     * <p>返回 {@code null} = 该方言读不到（此时 UI 显示「该方言暂不支持查看定义体」）。
     *
     * <p>三种方言的来源完全不同：
     * <ul>
     *   <li>SQLite：{@code sqlite_master.sql}（原文里就是 CREATE FUNCTION/PROCEDURE）；</li>
     *   <li>PostgreSQL：{@code pg_get_functiondef(oid)}；</li>
     *   <li>MySQL：{@code SHOW CREATE FUNCTION / PROCEDURE}。</li>
     * </ul>
     *
     * @param name      函数 / 过程名
     * @param procedure true = 存储过程，false = 函数
     */
    default String readRoutineDefinition(Connection connection, String name, boolean procedure)
            throws SQLException {
        return null;
    }

    /**
     * ⭐ 读表注释的查询：**一列**（注释文本）。
     *
     * <p>返回 {@code null} = 该方言没有表注释（SQLite）——
     * 于是「表注释」在结构页显示为「—」，而不是假装有。
     */
    default String tableCommentQuery(String table) {
        return null;
    }

    /**
     * 清空表注释的语句（仅在 {@link #supportsTableComment()} 为真时调用）。
     *
     * <p>⚠️ PostgreSQL 清注释要写 {@code IS NULL}，不是 {@code IS ''} ——
     * 写错了等于没清。
     */
    default String clearTableCommentSql(String table) {
        return tableCommentSql(table, "");
    }

    // ─────────────────────────── 分页 / 元数据探测 ───────────────────────────

    /**
     * ⭐ D-2/D-3：一条分页取数 SQL 与它的绑定参数（占位符顺序即绑定顺序）。
     */
    record PagedSql(String sql, List<Object> params) {
        public PagedSql {
            params = List.copyOf(params == null ? List.of() : params);
        }
    }

    /**
     * 把一条<b>已裁边界</b>的只读 SELECT 包成分页取数语句。
     *
     * <p>以前这句写死在 {@code QueryPaginator} 里（"三种库都吃
     * {@code LIMIT ? OFFSET ?}"）—— SQL Server 与 Oracle 用
     * {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}，且参数顺序相反、
     * SQL Server 还强制要求 {@code ORDER BY}。分支只能落回方言层（A1 承诺）。
     *
     * <p>注意<b>包装策略也不同</b>：LIMIT 族用派生表包一层最稳；
     * SQL Server 的派生表里不许出现 {@code ORDER BY}（不带 TOP），
     * 所以它直接把子句<b>追加在原句尾部</b>。
     */
    default PagedSql pagedSelect(String source, List<Object> params,
                                 int limit, long offset) {
        List<Object> bound = new ArrayList<>(
                params == null ? List.of() : params);
        bound.add(limit);
        bound.add((int) offset);
        return new PagedSql("SELECT * FROM (" + source + ") AS _p LIMIT ? OFFSET ?", bound);
    }

    /** 同上，但取总行数：{@code SELECT COUNT(*) AS adb_count FROM (原句) AS _p}。 */
    default PagedSql countedSelect(String source, List<Object> params) {
        return new PagedSql("SELECT COUNT(*) AS adb_count FROM (" + source + ") AS _p",
                params == null ? List.of() : params);
    }

    /**
     * 数据页的<b>字面量</b>分页子句（拼在一条已含 WHERE / ORDER BY 的 SELECT 尾部）。
     *
     * <p>与 {@link #pagedSelect} 的分工：那条服务「任意用户 SELECT」（派生表包装、
     * 参数绑定）；这条服务内省器自己拼的定长语句（表名/分页尺寸都是可信整数，
     * 走字面量，和旧行为一致）。{@code hasOrderBy} = 原句尾部是否已有
     * {@code ORDER BY} —— SQL Server 的 {@code OFFSET/FETCH} 必须跟在它后面。
     */
    default String pagingClause(long limit, long offset, boolean hasOrderBy) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    /**
     * 取列元数据的空页探测语句（0 行）—— 只用结果集元数据，不真读数据。
     *
     * <p>SQL Server 没有 {@code LIMIT}，这里必须跟着方言走。
     */
    default String probeColumnsSql(String table) {
        return "SELECT * FROM " + quote(table) + " LIMIT 0";
    }

    /**
     * 「整表取第 {@code offset} 行起 {@code limit} 行」的完整语句 —— 无 WHERE、无排序。
     *
     * <p>用它的是那些「直接对整张表取一页」的地方：表预览、树/结构页上
     * 「复制成 SELECT」、对象 SQL 切面展示的取数语句。
     *
     * <p>⚠️ 返回值<b>不含结尾分号</b> —— 由调用方决定语句风格。
     * 界面各处以前各自写着 {@code SELECT * FROM "t" LIMIT 200;}：双引号对 MySQL
     * 是错的，{@code LIMIT} 对 SQL Server / Oracle 直接语法不通。
     */
    default String plainPageSql(String table, long limit, long offset) {
        return "SELECT * FROM " + quote(table) + pagingClause(limit, offset, false);
    }

    // ─────────────────────────── 执行计划 ───────────────────────────

    /**
     * ⭐ U6：服务器信息查询 —— **第 1 列 = 版本，第 2 列 = 当前用户**。
     *
     * <p>状态栏要显示「连的是哪个版本的什么库、以什么身份连的」——
     * 这是排查「为什么这个库行为不一样」时最先要看的信息。
     */
    default String serverInfoSql() {
        return null;
    }

    /**
     * 执行计划语句。SQLite 用 {@code EXPLAIN QUERY PLAN}，PostgreSQL / MySQL 用
     * {@code EXPLAIN}。不支持的方言（见 {@link #supportsExplainPlan()}）不会被调用。
     */
    String explainSql(String sql);

    /**
     * ⭐ D-3：这个库能不能用<b>一条语句</b>拿到执行计划。
     *
     * <p>SQL Server 的计划要 {@code SET SHOWPLAN_ALL ON} → 跑语句 → {@code OFF}
     * 三段会话级设置；Oracle 要 {@code EXPLAIN PLAN FOR} 再读 {@code PLAN_TABLE}
     * （或 {@code DBMS_XPLAN}）两条语句。查询页的执行按钮只发一条语句，
     * 所以这两条方言先判「不支持」，界面把按钮收起并注明原因 ——
     * ⛔ 不能摆一个「点了什么也不会发生」的按钮。
     */
    default boolean supportsExplainPlan() {
        return true;
    }
}
