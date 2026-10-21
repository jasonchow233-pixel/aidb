package io.agenticdb.jdbc;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ PostgreSQL / MySQL 的<b>真库联调</b>（Testcontainers 路线）。
 *
 * <p>没有容器运行时（比如本地没装 Docker）时整个类会被跳过 ——
 * 用 JUnit 的条件执行，不让「机器上没 Docker」变成构建失败。
 * 有 Docker 的机器（CI、开发者本机）上，这些用例会真的把库跑起来。
 *
 * <p><b>断言本体在 {@link RealDatabaseContract}</b>，与「连外部真库」那条路
 * （{@link ExternalRealDatabaseTest}）**共用同一套**。
 * 这样保证：本地连真 MySQL 5.7 与 CI 起 MySQL 8.4 容器，验的是同一件事。
 *
 * <p>⚠️ 早先这里手写 {@code ALTER TABLE … RENAME COLUMN …}，
 * 那是 MySQL 8.0 才有的语法 —— 手写的 SQL 测不出我们自己的 bug。
 * 现在全部走方言生成，5.7 与 8.4 都成立。
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

    private static ExternalDatabase postgresTarget() {
        return new ExternalDatabase(DialectKind.POSTGRESQL,
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static ExternalDatabase mysqlTarget() {
        return new ExternalDatabase(DialectKind.MYSQL,
                MYSQL.getHost(), MYSQL.getMappedPort(3306),
                MYSQL.getDatabaseName(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    // ─────────────────────────── PostgreSQL ───────────────────────────

    @Test
    void postgresGeneratedColumnDdlRuns() throws Exception {
        RealDatabaseContract.generatedColumnDdlRuns(postgresTarget());
    }

    @Test
    void postgresDangerGateRejectsUnderDeclaredDanger() throws Exception {
        RealDatabaseContract.dangerGateRejectsUnderDeclaredDanger(postgresTarget());
    }

    @Test
    void postgresMetadataQueriesRun() throws Exception {
        RealDatabaseContract.metadataQueriesRun(postgresTarget());
    }

    @Test
    void postgresSchemaSwitchSemantics() throws Exception {
        RealDatabaseContract.schemaSwitchSemantics(postgresTarget());
    }

    @Test
    void postgresQuotedIdentifiersWork() throws Exception {
        RealDatabaseContract.quotedIdentifiersWork(postgresTarget());
    }

    // ─────────────────────────── MySQL ───────────────────────────

    @Test
    void mysqlGeneratedColumnDdlRuns() throws Exception {
        RealDatabaseContract.generatedColumnDdlRuns(mysqlTarget());
    }

    @Test
    void mysqlDangerGateRejectsUnderDeclaredDanger() throws Exception {
        RealDatabaseContract.dangerGateRejectsUnderDeclaredDanger(mysqlTarget());
    }

    @Test
    void mysqlMetadataQueriesRun() throws Exception {
        RealDatabaseContract.metadataQueriesRun(mysqlTarget());
    }

    @Test
    void mysqlSchemaSwitchSemantics() throws Exception {
        RealDatabaseContract.schemaSwitchSemantics(mysqlTarget());
    }

    @Test
    void mysqlQuotedIdentifiersWork() throws Exception {
        RealDatabaseContract.quotedIdentifiersWork(mysqlTarget());
    }

    // ─────────────────────────── 容器本身 ───────────────────────────

    @Test
    void theContainersAreReallyTheVersionsWeThinkTheyAre() {
        assertThat(POSTGRES.getJdbcUrl()).contains("postgres");
        assertThat(MYSQL.getJdbcUrl()).contains("mysql");
    }
}
