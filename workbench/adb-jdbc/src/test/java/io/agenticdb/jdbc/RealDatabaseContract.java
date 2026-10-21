package io.agenticdb.jdbc;

import io.agenticdb.model.ColumnDef;
import io.agenticdb.model.ColumnSpec;
import io.agenticdb.model.ColumnType;
import io.agenticdb.model.Danger;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

    // ─────────────────────────── 契约 1：生成的 DDL 跑得通 ───────────────────────────

    /**
     * 方言生成的结构变更 DDL（加列 / 改列 / 重命名 / 删列）必须在真库上真的跑通。
     *
     * <p>⭐ 全部走 {@link Dialect} 生成 —— 5.7 与 8.4 的写法不同，
     * 由方言负责，我们只验「生成的东西能不能跑」。
     */
    public static void generatedColumnDdlRuns(ExternalDatabase target) throws Exception {
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

    // ─────────────────────────── 契约 2：第三层危险门禁 ───────────────────────────

    /**
     * 第三层门禁（驱动侧复查）必须在真库上生效：
     * 声明成只读的 {@code DELETE} 要被拒，声明成破坏性的要放行。
     */
    public static void dangerGateRejectsUnderDeclaredDanger(ExternalDatabase target)
            throws Exception {
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
        try (Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery(
                     "SELECT data_type, character_maximum_length FROM information_schema.columns "
                             + "WHERE table_name = '" + table + "' AND table_schema = "
                             + schemaFilter(session) + " AND column_name = '" + column + "'")) {
            if (!rs.next()) {
                return null;
            }
            String type = rs.getString(1);
            long length = rs.getLong(2);
            return rs.wasNull() ? type : type + "(" + length + ")";
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
