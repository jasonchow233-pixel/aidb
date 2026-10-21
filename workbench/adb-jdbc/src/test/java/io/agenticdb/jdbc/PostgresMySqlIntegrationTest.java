package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ⭐ PostgreSQL / MySQL 的<b>真库联调</b>。
 *
 * <p>没有容器运行时（比如本地没装 Docker）时整个类会被跳过 ——
 * 用 JUnit 的条件执行，不让「机器上没 Docker」变成构建失败。
 * 有 Docker 的机器（CI、开发者本机）上，这些用例会真的把库跑起来。
 *
 * <p>它守的是 {@link DialectSqlConformanceTest} 守不到的那部分：
 * <b>生成的 SQL 在真库上能不能跑</b>。前者锁 SQL 文本，后者锁真实行为。
 */
@Testcontainers
@org.junit.jupiter.api.condition.EnabledIf("io.agenticdb.jdbc.DockerAvailability#isAvailable")
class PostgresMySqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("shop")
                    .withUsername("shop")
                    .withPassword("shop");

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("shop")
                    .withUsername("shop")
                    .withPassword("shop");

    @Test
    void postgresAcceptsTheGeneratedColumnDdl() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.postgres(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword()))) {

            session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(50))");
            session.execute("ALTER TABLE users ADD COLUMN email VARCHAR(120)");
            session.execute("COMMENT ON COLUMN users.email IS '用户邮箱'");
            session.execute("ALTER TABLE users ALTER COLUMN email SET NOT NULL");
            session.execute("ALTER TABLE users RENAME COLUMN name TO nickname");
            session.execute("ALTER TABLE users DROP COLUMN email");

            ExecutionResult result = session.execute(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = 'users' ORDER BY column_name");
            assertThat(result.rows()).hasSize(2);
        }
    }

    @Test
    void postgresSchemaCanBeSwitchedInPlace() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.postgres(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword()))) {

            session.execute("CREATE SCHEMA archive");
            session.execute("CREATE TABLE archive.old_users (id INTEGER PRIMARY KEY)");

            String switchSql = session.dialect().setSchemaSql("archive");
            assertThat(switchSql).isEqualTo("SET search_path TO \"archive\"");
            session.execute(switchSql);

            // search_path 生效后，未限定表名解析到 archive。
            ExecutionResult result = session.execute("SELECT count(*) FROM old_users");
            assertThat(result.rows()).hasSize(1);
        }
    }

    @Test
    void mysqlAcceptsTheGeneratedColumnDdl() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.mysql(
                MYSQL.getHost(), MYSQL.getMappedPort(3306),
                MYSQL.getDatabaseName(), MYSQL.getUsername(), MYSQL.getPassword()))) {

            session.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(50))");
            // MySQL 的注释在列定义里 —— 整条一起跑。
            session.execute("ALTER TABLE `users` ADD COLUMN `email` VARCHAR(120) "
                    + "COMMENT '用户邮箱'");
            session.execute("ALTER TABLE `users` MODIFY COLUMN `email` VARCHAR(120) NOT NULL "
                    + "COMMENT '用户邮箱'");
            session.execute("ALTER TABLE `users` RENAME COLUMN `name` TO `nickname`");
            session.execute("ALTER TABLE `users` DROP COLUMN `email`");

            ExecutionResult result = session.execute(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_name = 'users' ORDER BY column_name");
            assertThat(result.rows()).hasSize(2);
        }
    }

    @Test
    void theDangerGateWorksAgainstRealDatabases() throws Exception {
        try (JdbcSession session = JdbcSession.open(ConnectionSpec.postgres(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword()))) {

            session.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");

            // 把 DELETE 声明成只读 → 第三层门禁要拦下来
            assertThatThrownBy(() -> session.execute("DELETE FROM t", io.agenticdb.model.Danger.READ_ONLY))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("危险等级不符");

            // 声明成「改结构」→ 放行
            assertThat(session.execute("DELETE FROM t", io.agenticdb.model.Danger.DESTRUCTIVE))
                    .isNotNull();
        }
    }
}
