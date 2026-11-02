package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.Danger;

import org.junit.jupiter.api.Assumptions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ 真库契约：**同一套断言**跑在外部真库（环境变量）与 Testcontainers 上。
 *
 * <p>它守的是 {@code DialectSqlConformanceTest} 守不到的那部分：
 * <b>我们生成的 SQL 在真库上到底能不能跑</b>。
 * 前者锁 SQL 文本，后者锁真实行为。
 *
 * <h2>为什么断言必须用「方言生成的 SQL」</h2>
 * 早先这个文件里的 MySQL 用例是<b>手写</b> {@code ALTER TABLE … RENAME COLUMN …}，
 * 看起来在 MySQL 8.4 上跑得通 —— 但那是 8.0 才有的语法，
 * 在 5.7 上直接语法错误。**手写的 SQL 测不出我们自己的 bug。**
 * 现在所有 DDL 都走 {@link Dialect} 生成，于是同一套断言在 5.7 与 8.4 上都成立。
 */
public final class RealDatabaseContract {

    private RealDatabaseContract() {
    }

    /**
     * 通信失败时重试。
     *
     * <p>⚠️ 为什么要重试：实测这台 MySQL 的 **TLS 握手会间歇性卡住**
     * （6 次连接里挂了 2 次，报 {@code Communications link failure}）。
     * 那是**服务端 / 网络**的问题，不是我们生成的 SQL 有问题 ——
     * 但它会让门禁变成「随机红」。
     *
     * <p>所以：只对**通信类**失败重试（最多 3 次），
     * 其它异常（SQL 语法错、断言错）**一律立刻失败**，不掩盖真问题。
     */
    private static void withRetry(String what, ThrowingRunnable body) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                body.run();
                return;
            } catch (SQLException e) {
                if (!isCommunications(e)) {
                    throw e;
                }
                last = e;
                System.out.println("[真库契约] " + what + " 第 " + attempt
                        + " 次通信失败（服务端 / 网络问题），重试：" + e.getMessage());
                // 退避一下，别连着撞上同一个卡顿
                Thread.sleep(500L * attempt);
            }
        }
        throw last;
    }

    /** 是通信类失败吗（而不是 SQL / 断言问题）？ */
    private static boolean isCommunications(SQLException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (message.contains("Communications link failure")
                    || message.contains("CommunicationsException")
                    || message.contains("socketTimeout")
                    || message.contains("Read timed out")) {
                return true;
            }
        }
        return false;
    }

    /** 会抛异常的动作。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ─────────────────────────── 契约 1：生成的 DDL 跑得通 ───────────────────────────

    /**
     * 方言生成的结构变更 DDL（加列 / 改列 / 重命名 / 删列）必须在真库上真的跑通。
     *
     * <p>⭐ 全部走 {@link Dialect} 生成 —— 5.7 与 8.4 的写法不同，
     * 由方言负责，我们只验「生成的东西能不能跑」。
     */
    public static void generatedColumnDdlRuns(ExternalDatabase target) throws Exception {
        withRetry("生成的 DDL", () -> generatedColumnDdlRunsOnce(target));
    }

    private static void generatedColumnDdlRunsOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            DialectKind kind = dialect.kind();
            String table = "adb_contract_ddl_" + System.nanoTime();
            try {
                session.execute("CREATE TABLE " + dialect.quote(table)
                        + " (id INTEGER PRIMARY KEY, name " + textType(kind, 50) + ")");

                // ── 加列（带注释）──
                ColumnSpec email = new ColumnSpec("email", textType(kind, 120), true, null,
                        "用户邮箱", false, false);
                List<String> add = new ArrayList<>();
                add.add(dialect.addColumnSql(table, email));
                dialect.appendColumnComment(table, email, add);
                for (String sql : add) {
                    session.execute(sql);
                }
                assertThat(columnNames(session, table))
                        .as("加列之后列要在")
                        .contains("email");

                // ── 改列：加长 + 变不可空 ──
                //    ⭐ 旧类型问库要，不写死 "varchar(120)" —— Oracle 报 VARCHAR2(120)、
                //    SQL Server 报 varchar(120)，写死就是在用 MySQL 的形状测别家。
                ColumnSpec changed = new ColumnSpec("email", textType(kind, 200), false, null,
                        null, false, false);
                for (String sql : dialect.alterColumnStatements(table, "email", changed,
                        columnType(session, table, "email"))) {
                    session.execute(sql);
                }
                assertThat(columnNames(session, table))
                        .as("改列之后列还在")
                        .contains("email");
                assertThat(columnType(session, table, "email"))
                        .as("改列之后类型要真的变了（长度要跟着变）")
                        .contains("200");

                // ── 重命名列（⭐ 版本差异由方言处理）──
                ColumnDef old = new ColumnDef("email", ColumnType.TEXT, false, false,
                        null, null, null, 120, null, null, false, "VARCHAR");
                session.execute(dialect.renameColumnSql(table, "email", "mail", old));
                assertThat(columnNames(session, table))
                        .as("重命名之后新名字在、旧名字不在")
                        .contains("mail")
                        .doesNotContain("email");

                // ── 删列 ──
                session.execute(dialect.dropColumnSql(table, "mail"));
                assertThat(columnNames(session, table))
                        .as("删列之后列不在")
                        .doesNotContain("mail");
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ─────────────────────────── 契约 6：重命名保真 ───────────────────────────

    /**
     * ⭐ 重命名列必须**保住列的完整定义**（类型 / 长度 / 可空性 / 默认值）。
     *
     * <p>为什么单列这条契约：MySQL 8.0 之前没有 {@code RENAME COLUMN}，
     * 方言只能用 {@code CHANGE COLUMN 旧名 新名 <完整定义>} ——
     * 定义少写一项，用户**当场看不出来**，但那项已经静默丢了
     * （一个 {@code NOT NULL DEFAULT 'x'} 的列被改名后变成可空、无默认值，
     * 数据库不报错，数据照常进，只是约束没了）。
     *
     * <p>PostgreSQL 的 {@code RENAME COLUMN} 根本不碰定义，这条对它同样成立 ——
     * 于是同一条断言把两条路径都钉住。
     */
    public static void renameColumnPreservesDefinition(ExternalDatabase target) throws Exception {
        withRetry("重命名保真", () -> renameColumnPreservesDefinitionOnce(target));
    }

    private static void renameColumnPreservesDefinitionOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_rn_" + System.nanoTime();
            DialectKind kind = dialect.kind();
            try {
                // ⭐ 列定义由**方言**生成，不手写 `VARCHAR(50) NOT NULL DEFAULT 'x'`：
                //    Oracle 要求 DEFAULT 在约束<b>前面</b>，手写的 MySQL 顺序在它那儿直接语法错。
                //    这个文件自己的教训就是「手写的 SQL 测不出我们的 bug」——
                //    建表这一步同样得走方言的零件。
                ColumnSpec original = new ColumnSpec("原列", textType(kind, 50), false,
                        "'x'", null, false, false);
                session.execute("CREATE TABLE " + dialect.quote(table)
                        + " (id INTEGER PRIMARY KEY, "
                        + dialect.createTableColumnDefinition(original, false) + ")");

                // 生产路径上这个 ColumnDef 来自内省；这里按刚建出来的列手工对齐。
                ColumnDef column = new ColumnDef("原列", ColumnType.TEXT, false, false,
                        "'x'", null, null, 50, null, null, false, "VARCHAR");
                session.execute(dialect.renameColumnSql(table, "原列", "新列", column));

                String[] facts = columnFacts(session, table, "新列");
                assertThat(canonicalTypeName(facts[0]))
                        .as("重命名后类型要还在（CHANGE COLUMN 少写定义会变 TEXT）"
                                + " —— 各家名字不同，先归一再比（期望值按库给）")
                        .isEqualTo(canonicalTextType(kind));
                assertThat(facts[1])
                        .as("重命名后长度要还在 —— MySQL 的 data_type 不带长度，"
                                + "长度在 character_maximum_length（Oracle 在 DATA_LENGTH）")
                        .isEqualTo("50");
                assertThat(facts[2])
                        .as("重命名后 NOT NULL 不能丢（丢了不报错，但约束没了）")
                        .isEqualTo("NO");
                assertThat(facts[3])
                        .as("重命名后默认值不能丢")
                        .contains("x");
                assertThat(columnNames(session, table))
                        .as("旧名字要真的没了")
                        .doesNotContain("原列");
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ─────────────────────────── 契约 7：新建表存得下中文 ───────────────────────────

    /**
     * ⭐ 用<b>方言自己的零件</b>拼出来的建表语句，必须存得下中文（数据 + 注释）。
     *
     * <p>这条契约抓的是一个只在特定版本上现形的 bug：MySQL 8.0 之前服务器的
     * 默认字符集是 latin1，我们的建表语句以前不带字符集，表就继承 latin1 ——
     * 中文一插就 {@code Incorrect string value}，而且是**驱动原文**报错。
     * 多版本矩阵在 MySQL 5.7 上抓到（8.0 / 8.4 / PostgreSQL 全绿，
     * 因为它们的默认字符集本来就是 UTF-8）。
     *
     * <p>修法是 {@link Dialect#createTableOptions()}（MySQL 带
     * {@code DEFAULT CHARSET=utf8mb4}）。这里用方言零件拼表而不是手写 SQL ——
     * 手写的建表语句测不出我们自己的 bug（这条契约自己的教训）。
     */
    public static void createdTablesAcceptUnicode(ExternalDatabase target) throws Exception {
        withRetry("新建表存中文", () -> createdTablesAcceptUnicodeOnce(target));
    }

    private static void createdTablesAcceptUnicodeOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_cs_" + System.nanoTime();
            try {
                // 中文列名 + 中文注释：两个都要求表 / 列的字符集吃得下 UTF-8。
                ColumnSpec column = new ColumnSpec("名称", textType(target.kind(), 50), true, null,
                        "中文注释", false, false);
                String sql = "CREATE TABLE " + dialect.quote(table) + " ("
                        + dialect.createTableColumnDefinition(column, false) + ")";
                String options = dialect.createTableOptions();
                if (options != null && !options.isBlank()) {
                    sql += " " + options;
                }
                session.execute(sql);
                // ⭐ 注释走 appendColumnComment：Oracle / SQL Server 的注释不是建表语句的一部分
                //    （Oracle 是独立的 COMMENT ON，SQL Server 是扩展属性），
                //    内联在 CREATE 里只有 MySQL / PG 认。生产的建表路径同样是这样拼的。
                List<String> comments = new ArrayList<>();
                dialect.appendColumnComment(table, column, comments);
                for (String statement : comments) {
                    session.execute(statement);
                }

                // ⭐ 值用<b>绑参</b>写进去，不写死字面量：生产的插入路径就是 PreparedStatement，
                //    而裸字面量 '值' 在 SQL Server 上会按库的单字节代码页解释（中文变 ??），
                //    那是**测试自己**造出来的假阳性，不是产品的缺陷。
                try (PreparedStatement insert = session.connection().prepareStatement(
                        "INSERT INTO " + dialect.quote(table) + " ("
                                + dialect.quote("名称") + ") VALUES (?)")) {
                    insert.setString(1, "值");
                    insert.executeUpdate();
                }

                List<Map<String, Object>> rows = session.execute(
                        "SELECT " + dialect.quote("名称") + " FROM " + dialect.quote(table)).rows();
                assertThat(rows)
                        .as("中文要存得进去也读得回来（字符集不对会在这里炸）")
                        .isNotNull();
                assertThat(rows)
                        .as("刚插进去的那行要在")
                        .hasSize(1);
                assertThat(String.valueOf(rows.getFirst().get("名称")))
                        .as("读回来的值不能变乱码")
                        .isEqualTo("值");
                if (dialect.supportsColumnComment()) {
                    assertThat(columnComments(session, table))
                            .as("中文注释要读得回来（读回来是空 = 注释根本没写进去或被静默丢掉）")
                            .containsEntry("名称", "中文注释");
                }
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    /** 列注释：{@code column_name -> comment}，走方言自己的 {@code columnCommentsSql}。 */
    private static Map<String, String> columnComments(JdbcSession session, String table)
            throws Exception {
        String sql = session.dialect().columnCommentsSql(table);
        Map<String, String> comments = new java.util.LinkedHashMap<>();
        if (sql == null) {
            return comments;
        }
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                comments.put(rs.getString(1), rs.getString(2));
            }
        }
        return comments;
    }

    // ─────────────────────────── 契约 2：第三层危险门禁 ───────────────────────────

    /**
     * 第三层门禁（驱动侧复查）必须在真库上生效：
     * 声明成只读的 {@code DELETE} 要被拒，声明成破坏性的要放行。
     */
    public static void dangerGateRejectsUnderDeclaredDanger(ExternalDatabase target)
            throws Exception {
        withRetry("危险门禁", () -> dangerGateOnce(target));
    }

    private static void dangerGateOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_dg_" + System.nanoTime();
            try {
                session.execute("CREATE TABLE " + dialect.quote(table)
                        + " (id INTEGER PRIMARY KEY)");

                assertThatThrownBy(() -> session.execute(
                        "DELETE FROM " + dialect.quote(table), Danger.READ_ONLY))
                        .as("声明成只读的 DELETE 必须被第三层门禁拦下")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("危险等级不符");

                assertThat(session.execute("DELETE FROM " + dialect.quote(table),
                        Danger.DESTRUCTIVE))
                        .as("声明成破坏性的 DELETE 要放行")
                        .isNotNull();
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ─────────────────────────── 契约 3：元信息查询 ───────────────────────────

    /**
     * 方言提供的元信息查询（表元信息 / 触发器 / 列定义）必须在真库上跑得通。
     *
     * <p>不提供这些查询的方言（SQLite 没有大小与创建时间）跳过对应项。
     */
    public static void metadataQueriesRun(ExternalDatabase target) throws Exception {
        withRetry("元信息查询", () -> metadataQueriesOnce(target));
    }

    private static void metadataQueriesOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_md_" + System.nanoTime();
            try {
                session.execute("CREATE TABLE " + dialect.quote(table)
                        + " (id INTEGER PRIMARY KEY, name VARCHAR(50))");

                String meta = dialect.tableMetaSql();
                if (meta != null) {
                    assertThat(session.execute(meta).rows())
                            .as("表元信息查询要能跑")
                            .isNotNull();
                }

                // ⭐ UX-6：结构页「选项」页签的查询 —— 表名参数必须能绑定，
                //    并且在真库上取到引擎 / 排序规则 / 行格式（MySQL 才有）。
                String options = dialect.tableOptionsSql();
                if (options != null) {
                    try (PreparedStatement statement =
                                 session.connection().prepareStatement(options)) {
                        statement.setString(1, table);
                        try (ResultSet rs = statement.executeQuery()) {
                            assertThat(rs.next())
                                    .as("表选项查询要能取到刚建的表")
                                    .isTrue();
                            assertThat(rs.getString("engine"))
                                    .as("MySQL 应回报存储引擎").isNotBlank();
                            assertThat(rs.getString("collation_name"))
                                    .as("MySQL 应回报表排序规则").isNotBlank();
                            assertThat(rs.getString("row_format"))
                                    .as("MySQL 应回报行格式").isNotBlank();
                        }
                    }
                }

                String triggers = dialect.triggersListSql();
                if (triggers != null) {
                    assertThat(session.execute(triggers).rows())
                            .as("触发器列表查询要能跑")
                            .isNotNull();
                }

                String columns = dialect.columnsSql(table);
                if (columns != null) {
                    assertThat(session.execute(columns).rows())
                            .as("列定义查询要能跑")
                            .isNotNull();
                }

                String indexes = dialect.indexesSql(table);
                if (indexes != null) {
                    assertThat(session.execute(indexes).rows())
                            .as("索引查询要能跑")
                            .isNotNull();
                }

                // ⭐ R46：表注释必须**写进去再读回来**。这条是补的洞 ——
                //    一行没闭合的块注释把 MySQL / PG 的 tableCommentQuery 整段吃掉，
                //    接口默认（null）悄悄生效，结构页的表注释永远是空，
                //    而「改一次列」会把注释静默抹掉。只跑不报错的查询门不住这种事。
                if (dialect.supportsTableComment()) {
                    String comment = "契约注释 " + Long.toHexString(System.nanoTime());
                    session.execute(dialect.tableCommentSql(table, comment));
                    try (PreparedStatement statement = session.connection()
                            .prepareStatement(dialect.tableCommentQuery(table))) {
                        bindTableNames(statement, table);
                        try (ResultSet rs = statement.executeQuery()) {
                            assertThat(rs.next())
                                    .as("%s 的表注释查询要能取到刚注释的表", dialect.displayName())
                                    .isTrue();
                            assertThat(rs.getString(1))
                                    .as("%s 写进去的表注释要原样读回来", dialect.displayName())
                                    .isEqualTo(comment);
                        }
                    }
                }

                // ⭐ R46 D-22：设计器建出来的表名是**小写加引号**的，
                //    列注释 / CHECK / 触发器这三条通道都必须看得见它 ——
                //    看不见不报错，界面只会显示「没有」，用户分不清真假。
                if (dialect.supportsColumnComment()) {
                    assertThat(columnComments(session, table).size())
                            .as("%s 的列注释查询要能取到这张表的列（名字大小写要认）",
                                    dialect.displayName())
                            .isPositive();
                }
                String checksOfTable = dialect.checksSql(table);
                if (checksOfTable != null) {
                    try (PreparedStatement statement =
                                 session.connection().prepareStatement(checksOfTable)) {
                        bindTableNames(statement, table);
                        // 这张表没有 CHECK，所以**行数不是重点**；重点是这条查询跑得通、
                        // 占位符绑得上（少绑一个是 ORA-01008，被生产代码吞成「没有约束」）。
                        statement.executeQuery();
                    }
                }
                String triggersOfTable = dialect.triggersSql(table);
                if (triggersOfTable != null) {
                    try (PreparedStatement statement =
                                 session.connection().prepareStatement(triggersOfTable)) {
                        bindTableNames(statement, table);
                        statement.executeQuery();
                    }
                }

                // ⭐ R46：把「每条目录 SELECT 都在真服务器上跑一遍」收全。
                //    catalogs / serverInfo / eventsList 这三条以前一次没跑过 ——
                //    编一个不存在的列名，编译得过、离线门禁绿，
                //    只有真服务器会报（D-20 / D-24 就是这个形状）。
                String catalogs = dialect.catalogsSql();
                if (catalogs != null) {
                    assertThat(session.execute(catalogs).rows())
                            .as("%s 的 catalog 列表查询要能跑", dialect.displayName())
                            .isNotNull();
                }
                String serverInfo = dialect.serverInfoSql();
                if (serverInfo != null) {
                    assertThat(session.execute(serverInfo).rows())
                            .as("%s 的服务器信息查询要至少返回一行", dialect.displayName())
                            .isNotEmpty();
                }
                String events = dialect.eventsListSql();
                if (events != null) {
                    assertThat(session.execute(events).rows())
                            .as("%s 的事件列表查询要能跑", dialect.displayName())
                            .isNotNull();
                }

                // ⭐ D-22 的另一半：readDdl 以前只按大写找对象，
                //    而设计器建的是**小写加引号**的表 → 「查看建表语句」永远是空的。
                //    这条不报错、只是没内容，所以必须由矩阵在这里钉住。
                //    ⚠️ 门要跟着能力位走：PG / SQL Server 没有 SHOW CREATE TABLE，
                //    它们声明 readsDdlFromDatabase()=false 并返回 null 是**正确**的
                //    （界面据此显示「由内省生成的等价 DDL」），不该被当成缺陷。
                if (dialect.readsDdlFromDatabase()) {
                    assertThat(dialect.readDdl(session.connection(), table, false))
                            .as("%s 声称能从库里读 DDL，就得读回刚建的那张表",
                                    dialect.displayName())
                            .isNotBlank();
                } else {
                    assertThat(dialect.readDdl(session.connection(), table, false))
                            .as("%s 既然声称读不到，就必须真返回 null —— "
                                    + "返回空串会让界面走进「读到了但内容为空」的分支，"
                                    + "看起来像库出了问题", dialect.displayName())
                            .isNull();
                }
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ─────────────────────────── 契约 4：schema 切换语义 ───────────────────────────

    /**
     * schema 切换的语义必须符合各数据库的实情。
     *
     * <ul>
     *   <li>PostgreSQL：用 {@code SET search_path}，切完<b>当场生效</b>；</li>
     *   <li>Oracle：用 {@code ALTER SESSION SET CURRENT_SCHEMA}，切完<b>当场生效</b>
     *       —— 它只影响未限定名的解析，不改连接；</li>
     *   <li>MySQL / MariaDB：schema 就是数据库，没有 {@code SET} 语句 —— 必须返回
     *       {@code null}，否则 UI 会生成一条执行不了的切换语句；</li>
     *   <li>SQL Server：{@code ALTER SCHEMA} 是「把对象搬到另一个 schema」，
     *       同样没有会话级切换 —— 也必须是 {@code null}。</li>
     * </ul>
     */
    public static void schemaSwitchSemantics(ExternalDatabase target) throws Exception {
        withRetry("schema 切换", () -> schemaSwitchOnce(target));
    }

    private static void schemaSwitchOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String switchSql = dialect.setSchemaSql("adb_contract_schema");

            if (dialect.kind() == DialectKind.POSTGRESQL) {
                assertThat(switchSql)
                        .as("PostgreSQL 靠 SET search_path 切 schema")
                        .isNotNull();
                // ⚠️ schema 必须**真的存在** —— PG 的 search_path 会跳过不存在的 schema，
                //    那样 current_schema() 会是 null，断言就成了假失败。
                String probe = "adb_contract_schema";
                boolean created = false;
                try (Statement statement = session.connection().createStatement()) {
                    statement.execute("CREATE SCHEMA " + dialect.quote(probe));
                    created = true;
                } catch (Exception e) {
                    System.out.println("[真库契约] 建不了 schema（"
                            + e.getMessage() + "）—— 只校验切换语句本身");
                }
                try {
                    session.execute(switchSql);
                    if (created) {
                        assertThat(currentSchema(session))
                                .as("切完 current_schema 要变")
                                .isEqualTo(probe);
                    }
                } finally {
                    if (created) {
                        try (Statement statement = session.connection().createStatement()) {
                            statement.execute("DROP SCHEMA IF EXISTS " + dialect.quote(probe));
                        } catch (Exception ignored) {
                            // 清理失败不影响结论
                        }
                    }
                }
            } else if (dialect.kind() == DialectKind.ORACLE) {
                assertThat(switchSql)
                        .as("Oracle 靠 ALTER SESSION SET CURRENT_SCHEMA 切 schema")
                        .isNotNull();
                // ⚠️ Oracle 里「schema」就是用户，不能随手建 —— 所以切到**另一个一定存在的
                //    系统 schema**（SYSTEM），验的是「语句跑得通 + 当前 schema 真的变了」。
                //    切回来放在 finally：同一条连接留给后面的用例时用，不能留着别人的 schema。
                String probe = "SYSTEM";
                session.execute(dialect.setSchemaSql(probe));
                try {
                    assertThat(currentSchema(session))
                            .as("切完 SYS_CONTEXT('USERENV','CURRENT_SCHEMA') 要变")
                            .isEqualTo(probe);
                } finally {
                    session.execute(dialect.setSchemaSql(target.user().toUpperCase(Locale.ROOT)));
                }
            } else {
                assertThat(switchSql)
                        .as("%s 的 schema 就是数据库，没有 SET 语句", dialect.displayName())
                        .isNull();
            }
        }
    }

    // ─────────────────────────── 契约 5：标识符引用 ───────────────────────────

    /** 方言的标识符引用必须在真库上真的能防注入 / 不撞语法。 */
    public static void quotedIdentifiersWork(ExternalDatabase target) throws Exception {
        withRetry("标识符引用", () -> quotedIdentifiersOnce(target));
    }

    private static void quotedIdentifiersOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            // 带空格与中文的列名 —— 引用对了才建得出来。
            String table = "adb_contract_q_" + System.nanoTime();
            String column = "显示 名";
            try {
                session.execute("CREATE TABLE " + dialect.quote(table) + " ("
                        + dialect.quote(column) + " VARCHAR(20))");
                session.execute("INSERT INTO " + dialect.quote(table) + " ("
                        + dialect.quote(column) + ") VALUES ('值')");
                ExecutionResult result = session.execute("SELECT " + dialect.quote(column)
                        + " FROM " + dialect.quote(table));
                assertThat(result.rows())
                        .as("带空格的列名要能查得回来")
                        .isNotNull();
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ─────────────────────────── 契约 8：视图生命周期（R52） ───────────────────────────

    /**
     * ⭐ 视图的「建 → 重建 → 读回定义 → 删」必须在真库上跑通，且<b>重建写法由方言自报</b>。
     *
     * <p>R46 把五引擎都起来了，但七条契约里偏偏没有视图这一条 ——
     * 三种重建写法（{@code CREATE OR REPLACE} / {@code CREATE OR ALTER} / 两句先删再建）
     * 此前只由 SQL 快照 + SQLite 契约钉住，<b>一次都没在真服务器上执行过</b>。
     * 这条补的就是那段执行证据。
     *
     * <p>断言的形状照<b>产品路径</b>来，不照方言方法表：视图设计器开窗前会先读回定义
     * （{@code SchemaService#ddl}：先 {@code readDdl}，读不到再 {@code readViewDdl}），
     * 再交给 {@link ViewDefinition#selectBody} 取查询体。所以这里验的是
     * 「重建之后，同一条读路径拿到的查询体里带着<b>新</b>别名」。
     * 只验「DROP+CREATE 没报错」是不够的 —— 读回来是旧的、或读出来是空，界面一样是坏的，
     * 而且坏得更难查：前者让用户在旧定义上接着改，后者直接不开窗。
     */
    public static void viewLifecycleRuns(ExternalDatabase target) throws Exception {
        withRetry("视图生命周期", () -> viewLifecycleOnce(target));
    }

    private static void viewLifecycleOnce(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec())) {
            Dialect dialect = session.dialect();
            String table = "adb_contract_v_" + System.nanoTime();
            String view = "adb_view_" + System.nanoTime();
            // 契约末步自己删掉的视图，finally 就不该再删一次 —— 见 finally 处。
            boolean viewDroppedByContract = false;
            try {
                session.execute("CREATE TABLE " + dialect.quote(table) + " (id INTEGER PRIMARY KEY)");
                session.execute("INSERT INTO " + dialect.quote(table) + " (id) VALUES (1)");

                session.execute(dialect.createViewSql(view,
                        "SELECT id FROM " + dialect.quote(table)));
                assertThat(session.execute("SELECT * FROM " + dialect.quote(view)).rows())
                        .as("%s 的视图建好就该查得出内容", dialect.displayName())
                        .hasSize(1);

                // 重建写法由方言自报：界面上预览几句，这里就跑几句。
                //
                // ⚠️ 新查询体是「在末尾**加**一列」，不是「把 id 改名」—— 这不是随手挑的：
                // R52 首跑用改名（{@code SELECT id AS adb_marker}），PostgreSQL 直接拒绝
                // （{@code cannot change name of view column "id" to "adb_marker"}）。
                // 那是 PG 的既定约束：{@code CREATE OR REPLACE VIEW} 只许在末尾加列，
                // 不许改已有列的名字/类型/删列（因为视图上的权限与依赖要保住）。
                // 契约要验的是「六种方言都承诺的那条重建路径真的生效」，所以用可加列的形状；
                // 「改名列」这一条挂在 **D-28** 待拍板（见 GAP-TODOLIST），拍板之前不假装它通。
                String newBody = "SELECT id, id AS adb_marker FROM " + dialect.quote(table);
                String single = dialect.createOrReplaceViewSql(view, newBody);
                List<String> statements = single == null
                        ? List.of(dialect.dropViewSql(view), dialect.createViewSql(view, newBody))
                        : List.of(single);
                assertThat(statements)
                        .as("%s 的重建写法应该是「%s」", dialect.displayName(),
                                single == null ? "两句先删再建" : "单句")
                        .hasSize(single == null ? 2 : 1);
                for (String sql : statements) {
                    session.execute(sql);
                }

                ExecutionResult after = session.execute("SELECT * FROM " + dialect.quote(view));
                assertThat(after.columnKeys())
                        .as("%s：重建后列数要跟着新查询体走 —— 还是一列就说明重建根本没落地",
                                dialect.displayName())
                        .hasSize(2);
                assertThat(after.columnKeys().get(1))
                        .as("%s：重建后末尾那一列应该是新加的 adb_marker", dialect.displayName())
                        .isEqualToIgnoringCase("adb_marker");

                String fromDatabase = dialect.readDdl(session.connection(), view, true);
                String definition = fromDatabase == null || fromDatabase.isBlank()
                        ? dialect.readViewDdl(session.connection(), view)
                        : fromDatabase;
                assertThat(ViewDefinition.selectBody(definition))
                        .as("%s：「设计视图」开窗要读得到查询体，而且读到的是<b>新</b>的那一版",
                                dialect.displayName())
                        .isNotNull()
                        .containsIgnoringCase("adb_marker");

                session.execute(dialect.dropViewSql(view));
                viewDroppedByContract = true;
                assertThatThrownBy(() -> session.execute("SELECT * FROM " + dialect.quote(view)))
                        .as("%s：删掉的视图不该还能查", dialect.displayName())
                        .isInstanceOf(SQLException.class);
            } finally {
                // ⚠️ 只在契约没走到「删视图」那一步时才补删（中途断言红了、或建完视图就炸）。
                // 走到过了还照删 = 每引擎固定打印一条「清理临时视图失败：视图不存在」，
                // 那是把「状态已经是想要的」报成故障 —— R52 首跑五引擎全中这条，
                // 看日志的人会以为清理有 bug，而真正该报警的泄漏反而被淹在噪音里。
                if (!viewDroppedByContract) {
                    dropViewQuietly(session, dialect, view);
                }
                dropQuietly(session, dialect, table);
            }
        }
    }

    // ─────────────────────────── 契约 9：切库落点与隔离（R52） ───────────────────────────

    /**
     * ⭐ 「切到另一个库」必须真的<b>换掉落点</b>，而且<b>只</b>换落点。
     *
     * <p>查询页那颗库选择器（R44-G1）此前只由单测钉住：
     * {@code CatalogVariantProfileTest} 验「虚拟连接 id = 父 id + @ + 库名、database 字段被换掉」，
     * {@code CatalogTreeTest} 验「下拉里有哪些项」。两条都在<b>断字符串</b> ——
     * 谁也没证明「把这条 SQL 交给绑到新库的会话之后，它真的落在新库里」。
     * 这正是最容易出事故的一类缺口：连接串里少带一个参数、或者某家的驱动认属性不认路径，
     * 表现是<b>界面显示已切到 shop2、语句却打在 shop 上</b>，
     * 用户在一个他没在的库里改了数据。
     *
     * <p>所以这一条的断言形状是「<b>同一条 SQL 文本 + 同一个对象名，两个对端各自的内容</b>」：
     * 第二把库（{@code ADB_TEST_<前缀>_DB2}，由 {@code scripts/dbmatrix.sh} 建）与第一把
     * <b>同主机同端口同用户同口令</b>，只有库名不同 —— 和 {@code catalogVariant()} 给产品的那条路一致。
     * 如果「切库」其实没切，{@code away} 上建同名表会直接撞「表已存在」而红；
     * 如果切了但隔离没切干净，两边的行数会一样。
     */
    public static void catalogSwitchLands(ExternalDatabase target) throws Exception {
        withRetry("切库落点", () -> catalogSwitchOnce(target));
    }

    private static void catalogSwitchOnce(ExternalDatabase target) throws Exception {
        Dialect dialect = target.kind().dialect();
        if (dialect.catalogsSql() == null) {
            // 没有库层的家（Oracle / SQLite）：树里不出现 catalog 层，选择器只展示不可点 ——
            // 这是「不假装有」那一侧的事实。但它仍要在真库上钉一件事：
            // 「当前在哪个库」这个标签读得到真值，否则「不给切」会变成「连在哪儿都不知道」。
            try (JdbcSession session = JdbcSession.open(target.spec())) {
                assertThat(dialect.currentCatalog(session.connection()))
                        .as("%s 没有库层，可状态栏那个当前库标签是照这条读的"
                                + "（Oracle 走 SYS_CONTEXT('USERENV','DB_NAME')，实测回 FREEPDB1；"
                                + "同一个会话里 SERVICE_NAME 是小写的 freepdb1，所以按忽略大小写比）",
                                dialect.displayName())
                        .isEqualToIgnoringCase(target.database());
            }
            return;
        }

        ExternalDatabase away = target.second();
        Assumptions.assumeTrue(away != null,
                () -> dialect.displayName() + " 有库层，但环境里只有一把库 —— 第二把由 "
                        + "scripts/dbmatrix.sh up 建（键 ADB_TEST_*_DB2）。"
                        + "缺它就只能断「连上了」，断不了「落在哪儿」，所以这一条跳过而不是假通过");

        String table = "adb_contract_sw_" + System.nanoTime();
        try (JdbcSession home = JdbcSession.open(target.spec());
             JdbcSession other = JdbcSession.open(away.spec())) {
            try {
                // 1) 下拉里得有第二把库 —— 界面那一项就是 catalogsSql 的第 1 列喂的，
                //    库里存在但查不出来，用户根本点不到，后面的落点无从谈起。
                List<String> catalogs = firstColumn(home, dialect.catalogsSql());
                assertThat(catalogs)
                        .as("%s 的库清单要同时含两把测试库 —— 查询页那颗下拉读的就是这条查询",
                                dialect.displayName())
                        .anyMatch(name -> name.equalsIgnoreCase(target.database()))
                        .anyMatch(name -> name.equalsIgnoreCase(away.database()));

                // 2) 落点：两条会话各自的「当前库」要跟着连接串走。
                //    ⚠️ 这一条不能省 —— SQL Server 的库名走 databaseName 属性、
                //    PG / MySQL 走 URL 路径段，两边写错的 symptom 都是「连得上、落错库」。
                assertThat(dialect.currentCatalog(home.connection()))
                        .as("%s：原会话应落在 %s", dialect.displayName(), target.database())
                        .isEqualToIgnoringCase(target.database());
                assertThat(other.dialect().currentCatalog(other.connection()))
                        .as("%s：切库后的会话应落在 %s —— 这一条就是「已切到数据库 X」那句状态的真值",
                                dialect.displayName(), away.database())
                        .isEqualToIgnoringCase(away.database());

                // 3) 同名对象 + 同一条 SQL 文本，两边各自的内容。
                //    建表语句两边<b>字面完全相同</b>：落点不同才可能都建成功；
                //    没切过去的那一边会撞「表已存在」。
                home.execute("CREATE TABLE " + dialect.quote(table) + " (id INTEGER PRIMARY KEY)");
                other.execute("CREATE TABLE " + other.dialect().quote(table)
                        + " (id INTEGER PRIMARY KEY)");
                home.execute("INSERT INTO " + dialect.quote(table) + " (id) VALUES (1)");
                other.execute("INSERT INTO " + other.dialect().quote(table) + " (id) VALUES (2)");
                other.execute("INSERT INTO " + other.dialect().quote(table) + " (id) VALUES (3)");

                assertThat(home.execute("SELECT id FROM " + dialect.quote(table)).rows())
                        .as("%s：同一句 SELECT 在原库里只看得到原库的行（%s 里那张是另一张表）",
                                dialect.displayName(), away.database())
                        .hasSize(1);
                assertThat(other.execute("SELECT id FROM " + other.dialect().quote(table)).rows())
                        .as("%s：切过去的库里，那句一模一样的 SELECT 读到的是新库的行",
                                dialect.displayName())
                        .hasSize(2);
            } finally {
                // 两条会话各删自己那一张：同一条 dropTableSql、不同的落点。
                dropQuietly(home, home.dialect(), table);
                dropQuietly(other, other.dialect(), table);
            }
        }
    }

    /** 一条目录查询的第 1 列（{@code catalogsSql} 的约定就是「第 1 列 = 库名」）。 */
    private static List<String> firstColumn(JdbcSession session, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /**
     * 列清单。
     *
     * <p>⚠️ R46：<b>Oracle 没有 {@code information_schema}</b> —— 它的目录是
     * {@code ALL_TAB_COLUMNS}，而且「可空」是 {@code NULLABLE='Y'/'N'}
     * 而不是 {@code 'YES'/'NO'}。六方言共用一套<b>断言</b>，就得在这一层按库取数。
     *
     * <p>⚠️ 名字一律<b>绑参</b>，不拼字面量：SQL Server 的裸字面量按库的<b>单字节代码页</b>
     * 解释，中文名字会变成 {@code ??} —— 于是「查不到」被误报成「不存在」
     * （R46 首跑就在 SQL Server 上撞过一次）。绑参走 Unicode，六个库都对的写法。
     */
    private static List<String> columnNames(JdbcSession session, String table)
            throws Exception {
        List<String> names = new ArrayList<>();
        boolean oracle = session.dialect().kind() == DialectKind.ORACLE;
        String sql = oracle
                ? "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE " + ownerFilter()
                        + " AND " + nameMatch("TABLE_NAME", true) + " ORDER BY COLUMN_ID"
                : "SELECT column_name FROM information_schema.columns WHERE "
                        + nameMatch("table_name", false) + " AND table_schema = "
                        + schemaFilter(session);
        try (PreparedStatement statement = session.connection().prepareStatement(sql)) {
            bindName(statement, 1, table, oracle);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static String columnType(JdbcSession session, String table, String column)
            throws Exception {
        String[] facts = columnFacts(session, table, column);
        if (facts[0] == null) {
            return null;
        }
        // 无长度概念的类型（TEXT / INTEGER…）不加括号 —— 保持与库里的原样一致。
        return facts[1] == null ? facts[0] : facts[0] + "(" + facts[1] + ")";
    }

    /**
     * 把 {@code information_schema.data_type} 的名字归一到同一族的名字。
     *
     * <p>同一个类型在两个库里叫两个名字：MySQL 报 {@code varchar}，
     * PostgreSQL 报 {@code character varying}，Oracle 报 {@code VARCHAR2}。
     * 不归一，就只能给每个库各写一条断言 —— 而「同一套断言跑遍所有库」
     * 正是这个文件的立足点，所以归一在这里做。
     *
     * <p>只归一<b>同义异名</b>，不做类型推断：{@code text} 与 {@code varchar}
     * 是两个类型，改名以后如果变成 {@code text}，这里照样抓得出来。
     * {@code nvarchar} 与 {@code varchar} <b>不</b>归一 —— 在 SQL Server 上
     * 这两者的区别恰恰是「中文存得下存不下」；Oracle 的 {@code VARCHAR}
     * 只是 {@code VARCHAR2} 的兼容别名，所以归一。
     */
    private static String canonicalTypeName(String dataType) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType.toLowerCase(Locale.ROOT)) {
            case "character varying", "varchar2" -> "varchar";
            case "character", "nchar" -> "char";
            case "timestamp without time zone", "timestamp with time zone" -> "timestamp";
            case "integer" -> "int";
            default -> dataType.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 列的四项事实：{@code data_type, 长度, is_nullable, column_default}。
     *
     * <p>MySQL / MariaDB / PostgreSQL / SQL Server 的 {@code information_schema.columns}
     * 这几列同名同义 —— 一条查询伺候四个库；Oracle 只能走 {@code ALL_TAB_COLUMNS}，
     * 而且「可空」是 {@code Y/N}，取回来要归一成 {@code YES/NO}。
     */
    private static String[] columnFacts(JdbcSession session, String table, String column)
            throws Exception {
        DialectKind kind = session.dialect().kind();
        boolean oracle = kind == DialectKind.ORACLE;
        String sql = oracle
                ? "SELECT DATA_TYPE, DATA_LENGTH, NULLABLE, DATA_DEFAULT FROM ALL_TAB_COLUMNS "
                        + "WHERE " + ownerFilter() + " AND " + nameMatch("TABLE_NAME", true)
                        + " AND " + nameMatch("COLUMN_NAME", true)
                : "SELECT data_type, character_maximum_length, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE "
                        + nameMatch("table_name", false)
                        + " AND table_schema = " + schemaFilter(session)
                        + " AND " + nameMatch("column_name", false);
        try (PreparedStatement statement = session.connection().prepareStatement(sql)) {
            int next = bindName(statement, 1, table, oracle);
            bindName(statement, next, column, oracle);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new String[] {null, null, null, null};
                }
                long length = rs.getLong(2);
                String nullable = rs.getString(3);
                if (oracle) {
                    // Oracle 用 Y/N，其它库用 YES/NO —— 断言只认后者。
                    nullable = "N".equalsIgnoreCase(nullable) ? "NO" : "YES";
                }
                return new String[] {
                        rs.getString(1),
                        rs.wasNull() ? null : String.valueOf(length),
                        nullable,
                        rs.getString(4),
                };
            }
        }
    }

    /**
     * ⭐ 这个库「文本列」的类型名 —— 六个库三种写法，写死一种就是<b>测试自己的 bug</b>。
     *
     * <p>SQL Server 必须是 {@code NVARCHAR}：它的 {@code VARCHAR} 走库默认排序规则的
     * <b>单字节代码页</b>，中文进去直接变 {@code ??}（真库实测：容器库的排序规则是
     * {@code SQL_Latin1_General_CP1_CI_AS}）。Oracle 的 {@code VARCHAR} 只是
     * {@code VARCHAR2} 的官方兼容别名，这里用推荐的那个。
     *
     * <p>⚠️ 这条<b>不是</b>「为了让断言通过而迁就库」：界面给用户的默认文本类型
     * 必须同样是各库存得下 Unicode 的那个 —— 见 D-23。
     */
    private static String textType(DialectKind kind, Integer length) {
        String size = length == null ? "" : "(" + length + ")";
        return switch (kind) {
            case MYSQL, MARIADB, POSTGRESQL -> size.isEmpty() ? "TEXT" : "VARCHAR" + size;
            case SQLSERVER -> size.isEmpty() ? "NVARCHAR(MAX)" : "NVARCHAR" + size;
            case ORACLE -> size.isEmpty() ? "CLOB" : "VARCHAR2" + size;
            case SQLITE -> "TEXT";
        };
    }

    /** 改名之后<b>应该还是</b>什么类型（按库给期望值，不按 MySQL 的形状写死）。 */
    private static String canonicalTextType(DialectKind kind) {
        return kind == DialectKind.SQLSERVER ? "nvarchar" : "varchar";
    }

    /** 当前 schema 的取法：MySQL/MariaDB 是 {@code DATABASE()}，PG 是 {@code current_schema()}。 */
    private static String schemaFilter(JdbcSession session) {
        return switch (session.dialect().kind()) {
            case POSTGRESQL -> "current_schema()";
            case SQLSERVER -> "SCHEMA_NAME()";
            default -> "DATABASE()";
        };
    }

    /** Oracle：只看当前 schema 里的对象。 */
    private static String ownerFilter() {
        return "OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')";
    }

    /**
     * ⭐ Oracle 目录里的名字匹配谓词：<b>原样或大写都认</b>（与 D-22 同一个理由），
     * 其它四个库目录里怎么存就怎么查 —— 一个占位符。
     *
     * <p>这里<b>故意不复用</b> {@code OracleDialect} 里的那份匹配：测试要是照抄被测代码的规则，
     * 规则写错时两边一起错，缺陷就藏住了。方言那侧的规则由 {@code OracleDialectTest} 单独钉。
     */
    private static String nameMatch(String column, boolean oracle) {
        return oracle ? column + " IN (?, UPPER(?))" : column + " = ?";
    }

    /**
     * 绑一个名字：Oracle 的 {@link #nameMatch} 吃两个占位符，其它库吃一个。
     *
     * @return 下一个可用的占位符序号
     */
    private static int bindName(PreparedStatement statement, int index, String value,
                                boolean oracle) throws SQLException {
        statement.setString(index, value);
        if (oracle) {
            statement.setString(index + 1, value);
            return index + 2;
        }
        return index + 1;
    }

    private static String currentSchema(JdbcSession session) throws Exception {
        String sql = session.dialect().kind() == DialectKind.ORACLE
                ? "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL"
                : "SELECT current_schema()";
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * 把表名绑到 SQL 的<b>每一个</b>占位符（Oracle 的 {@code IN (?, UPPER(?))} 有两个）。
     *
     * <p>生产的 {@code SchemaService} 里有一份同样的逻辑，这里<b>故意不引用它</b>：
     * 契约要是用被测代码去绑参，那侧少绑一个占位符（{@code ORA-01008}）
     * 就会和查询本身一起「测不到」—— 而生产代码把那个异常吞成「注释是空的」，
     * 现场看不出区别。绑参这件事本身也得有人独立验一遍。
     */
    private static void bindTableNames(PreparedStatement statement, String table)
            throws SQLException {
        int count = statement.getParameterMetaData().getParameterCount();
        for (int i = 1; i <= count; i++) {
            statement.setString(i, table);
        }
    }

    /** 尽力清理；清理失败不能让测试结论失真，但一定要看得见。 */
    private static void dropQuietly(JdbcSession session, Dialect dialect, String table) {
        // ⚠️ 走方言的 DROP，不手写 `DROP TABLE IF EXISTS` —— Oracle 没这个写法。
        try (Statement statement = session.connection().createStatement()) {
            statement.execute(dialect.dropTableSql(table));
        } catch (Exception e) {
            System.err.println("[真库契约] 清理临时表失败（" + table + "）：" + e.getMessage());
        }
    }

    /** 尽力清理；清理失败不能让测试结论失真，但一定要看得见。 */
    private static void dropViewQuietly(JdbcSession session, Dialect dialect, String view) {
        try (Statement statement = session.connection().createStatement()) {
            statement.execute(dialect.dropViewSql(view));
        } catch (Exception e) {
            System.err.println("[真库契约] 清理临时视图失败（" + view + "）：" + e.getMessage());
        }
    }
}
