package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.Danger;

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
            String table = "adb_contract_ddl_" + System.nanoTime();
            try {
                session.execute("CREATE TABLE " + dialect.quote(table)
                        + " (id INTEGER PRIMARY KEY, name VARCHAR(50))");

                // ── 加列（带注释）──
                ColumnSpec email = new ColumnSpec("email", "VARCHAR(120)", true, null,
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
                ColumnSpec changed = new ColumnSpec("email", "VARCHAR(200)", false, null,
                        null, false, false);
                for (String sql : dialect.alterColumnStatements(table, "email", changed,
                        "varchar(120)")) {
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
            try {
                session.execute("CREATE TABLE " + dialect.quote(table)
                        + " (id INTEGER PRIMARY KEY, " + dialect.quote("原列")
                        + " VARCHAR(50) NOT NULL DEFAULT 'x')");

                // 生产路径上这个 ColumnDef 来自内省；这里按刚建出来的列手工对齐。
                ColumnDef column = new ColumnDef("原列", ColumnType.TEXT, false, false,
                        "'x'", null, null, 50, null, null, false, "VARCHAR");
                session.execute(dialect.renameColumnSql(table, "原列", "新列", column));

                String[] facts = columnFacts(session, table, "新列");
                assertThat(canonicalTypeName(facts[0]))
                        .as("重命名后类型要还在（CHANGE COLUMN 少写定义会变 TEXT）"
                                + " —— PostgreSQL 回报 character varying，与 varchar 同族，先归一")
                        .isEqualTo("varchar");
                assertThat(facts[1])
                        .as("重命名后长度要还在 —— MySQL 的 data_type 不带长度，"
                                + "长度在 character_maximum_length（PostgreSQL 叫 character varying）")
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
                ColumnSpec column = new ColumnSpec("名称", "VARCHAR(50)", true, null,
                        "中文注释", false, false);
                String sql = "CREATE TABLE " + dialect.quote(table) + " ("
                        + dialect.createTableColumnDefinition(column, false) + ")";
                String options = dialect.createTableOptions();
                if (options != null && !options.isBlank()) {
                    sql += " " + options;
                }
                session.execute(sql);

                session.execute("INSERT INTO " + dialect.quote(table) + " ("
                        + dialect.quote("名称") + ") VALUES ('值')");

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
            } finally {
                dropQuietly(session, dialect, table);
            }
        }
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
     *   <li>MySQL：schema 就是数据库，没有 {@code SET} 语句 —— 必须返回 {@code null}，
     *       否则 UI 会生成一条执行不了的切换语句。</li>
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
            } else {
                assertThat(switchSql)
                        .as("MySQL 的 schema 就是数据库，没有 SET 语句")
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

    // ─────────────────────────── 工具 ───────────────────────────

    private static List<String> columnNames(JdbcSession session, String table)
            throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery(
                     "SELECT column_name FROM information_schema.columns WHERE table_name = '"
                             + table + "' AND table_schema = " + schemaFilter(session))) {
            while (rs.next()) {
                names.add(rs.getString(1));
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
     * PostgreSQL 报 {@code character varying}。不归一，就只能给两个库各写一条
     * 断言 —— 而「同一套断言跑遍所有库」正是这个文件的立足点，所以归一在这里做。
     *
     * <p>只归一<b>同义异名</b>，不做类型推断：{@code text} 与 {@code varchar}
     * 是两个类型，改名以后如果变成 {@code text}，这里照样抓得出来。
     */
    private static String canonicalTypeName(String dataType) {
        if (dataType == null) {
            return null;
        }
        return switch (dataType.toLowerCase(Locale.ROOT)) {
            case "character varying" -> "varchar";
            case "character", "nchar" -> "char";
            case "timestamp without time zone", "timestamp with time zone" -> "timestamp";
            case "integer" -> "int";
            default -> dataType.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 列的四项事实：{@code data_type, character_maximum_length, is_nullable, column_default}。
     *
     * <p>两个库的 {@code information_schema.columns} 这几列同名同义 ——
     * 于是一条查询同时伺候 MySQL 与 PostgreSQL。
     */
    private static String[] columnFacts(JdbcSession session, String table, String column)
            throws Exception {
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery(
                     "SELECT data_type, character_maximum_length, is_nullable, column_default "
                             + "FROM information_schema.columns WHERE table_name = '" + table
                             + "' AND table_schema = " + schemaFilter(session)
                             + " AND column_name = '" + column + "'")) {
            if (!rs.next()) {
                return new String[] {null, null, null, null};
            }
            long length = rs.getLong(2);
            return new String[] {
                    rs.getString(1),
                    rs.wasNull() ? null : String.valueOf(length),
                    rs.getString(3),
                    rs.getString(4),
            };
        }
    }

    /** 当前 schema 的取法：MySQL 是 {@code DATABASE()}，PostgreSQL 是 {@code current_schema()}。 */
    private static String schemaFilter(JdbcSession session) {
        return session.dialect().kind() == DialectKind.POSTGRESQL
                ? "current_schema()"
                : "DATABASE()";
    }

    private static String currentSchema(JdbcSession session) throws Exception {
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery("SELECT current_schema()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 尽力清理；清理失败不能让测试结论失真，但一定要看得见。 */
    private static void dropQuietly(JdbcSession session, Dialect dialect, String table) {
        try (Statement statement = session.connection().createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + dialect.quote(table));
        } catch (Exception e) {
            System.err.println("[真库契约] 清理临时表失败（" + table + "）：" + e.getMessage());
        }
    }
}
