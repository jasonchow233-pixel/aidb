package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnSpec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

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

    String driverClassName();

    int defaultPort();

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

    /** 当前 catalog；没有这个概念时返回 {@code null}。 */
    String currentCatalog(Connection connection) throws SQLException;

    /** 当前 schema；没有这个概念时返回 {@code null}。 */
    String currentSchema(Connection connection) throws SQLException;

    // ─────────────────────────── 结构变更 ───────────────────────────

    /** 列定义（含类型 / NOT NULL / DEFAULT / UNIQUE，注释按方言处理）。 */
    String columnDefinition(ColumnSpec column, boolean includeName);

    /**
     * 追加列注释语句。
     *
     * <p>PostgreSQL 用 {@code COMMENT ON COLUMN}；MySQL 把注释写在列定义里（这里什么都不追加）；
     * SQLite 不支持（这里什么都不追加）。
     */
    void appendColumnComment(String table, ColumnSpec column, List<String> statements);

    /**
     * 改列的语句列表。
     *
     * @param oldRawType 原声明类型；与新类型不同时才会生成类型变更语句
     */
    List<String> alterColumnStatements(String table, String columnName, ColumnSpec next,
                                       String oldRawType);

    String renameColumnSql(String table, String oldName, String newName);

    String dropColumnSql(String table, String columnName);

    String addColumnSql(String table, ColumnSpec column);

    // ─────────────────────────── 执行计划 ───────────────────────────

    /** 执行计划语句。SQLite 用 {@code EXPLAIN QUERY PLAN}，其余用 {@code EXPLAIN}。 */
    String explainSql(String sql);
}
