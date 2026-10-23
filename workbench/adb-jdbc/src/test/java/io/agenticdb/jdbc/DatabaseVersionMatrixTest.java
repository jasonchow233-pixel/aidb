package io.agenticdb.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * ⭐ 多版本真库矩阵：同一套 {@link RealDatabaseContract} 断言，
 * 在<b>默认门禁没覆盖的版本</b>上再跑一遍。
 *
 * <h2>覆盖矩阵</h2>
 * <table>
 *   <tr><td></td><th>MySQL 5.7</th><th>MySQL 8.0</th><th>MySQL 8.4</th>
 *       <th>PostgreSQL 13</th><th>PostgreSQL 16</th><th>PostgreSQL 17</th></tr>
 *   <tr><td>默认门禁</td><td></td><td></td><td>✅</td><td></td><td>✅</td><td></td></tr>
 *   <tr><td>本类（矩阵）</td><td>✅</td><td>✅</td><td></td><td>✅</td><td></td><td>✅</td></tr>
 * </table>
 * 每个版本 8 条动态用例（版本指纹 + 7 条 {@link RealDatabaseContract} 契约）。
 *
 * <h2>为什么要按版本跑</h2>
 * 方言的跨版本策略是「用所有版本都合法的写法」（MySQL 一律 {@code CHANGE COLUMN}，
 * 不用 8.0 才有的 {@code RENAME COLUMN}）。这个策略<b>必须被验证</b>而不是被相信 ——
 * 而且验证方式只能是真库：{@code DialectSqlConformanceTest} 锁的是 SQL 文本，
 * 文本对不对与「数据库收不收」是两件事。
 *
 * <h2>⛔ 默认不跑</h2>
 * 矩阵要起 4 个容器（其中 MySQL 5.7 在 arm64 机器上要走 amd64 模拟，启动以分钟计），
 * 所以由 {@code ADB_TEST_VERSION_MATRIX=1} 显式打开，见 {@link VersionMatrixGate}。
 * 默认门禁因此保持轻量，不会因为多几个容器变慢。
 *
 * <h2>容器起不来怎么办</h2>
 * <b>跳过，不是失败。</b>比如 arm64 机器上 {@code mysql:5.7} 没有原生镜像，
 * 必须先 {@code docker pull --platform linux/amd64 mysql:5.7}；
 * 没预拉过就起不来。这种「机器上没有」与「我们生成的 SQL 有问题」必须分开记 ——
 * 所以每个版本一个 {@link DynamicTest}，起不来的版本整条记跳过。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("io.agenticdb.jdbc.VersionMatrixGate#shouldRunMatrix")
class DatabaseVersionMatrixTest {

    /** 矩阵里的一个版本。 */
    private record MatrixVersion(
            String label,
            DialectKind kind,
            int port,
            String versionPrefix,
            JdbcDatabaseContainer<?> container) {

        ExternalDatabase target() {
            return new ExternalDatabase(kind,
                    container.getHost(), container.getMappedPort(port),
                    container.getDatabaseName(), container.getUsername(), container.getPassword());
        }
    }

    /** 一个<b>起来了</b>的版本。 */
    private record Started(MatrixVersion version, ExternalDatabase target) {
    }

    /**
     * 矩阵本体。
     *
     * <p>⚠️ MySQL 5.7 没有 arm64 镜像（Apple Silicon 上 {@code docker pull mysql:5.7}
     * 直接报 {@code no matching manifest}）—— 在 arm64 机器上它只能靠
     * {@code --platform linux/amd64} 预拉后走模拟运行，启动慢，
     * 所以给它 3 分钟启动超时（默认 60s 不够）。
     */
    private static final List<MatrixVersion> VERSIONS = List.of(
            new MatrixVersion("MySQL 5.7", DialectKind.MYSQL, 3306, "5.7", mysql("5.7", true)),
            new MatrixVersion("MySQL 8.0", DialectKind.MYSQL, 3306, "8.0", mysql("8.0", false)),
            new MatrixVersion("PostgreSQL 13", DialectKind.POSTGRESQL, 5432, "13", postgres("13")),
            new MatrixVersion("PostgreSQL 17", DialectKind.POSTGRESQL, 5432, "17", postgres("17")));

    private static MySQLContainer<?> mysql(String tag, boolean emulated) {
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse("mysql:" + tag))
                .withDatabaseName("shop")
                .withUsername("shop")
                .withPassword("shop")
                .withStartupTimeout(Duration.ofMinutes(emulated ? 3 : 2));
        if (emulated) {
            // ⚠️ 只在 arm64 上需要：镜像本身没有 arm64 层，Docker 会用本机已拉的
            //    amd64 镜像（配 Rosetta/qemu）跑。x86 机器上预拉的就是原生镜像。
            container = container.withCreateContainerCmdModifier(
                    cmd -> cmd.withPlatform("linux/amd64"));
        }
        return container;
    }

    private static PostgreSQLContainer<?> postgres(String tag) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:" + tag + "-alpine"))
                .withDatabaseName("shop")
                .withUsername("shop")
                .withPassword("shop")
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    /** 起来了的版本（键为 label）。起不来的版本<b>不在表里</b>。 */
    private final Map<String, Started> ready = new LinkedHashMap<>();

    @BeforeAll
    void startMatrix() {
        for (MatrixVersion version : VERSIONS) {
            try {
                version.container().start();
                ready.put(version.label(), new Started(version, version.target()));
                System.out.println("[版本矩阵] " + version.label() + " 起来了（"
                        + version.container().getDockerImageName() + "）");
            } catch (Exception e) {
                // 容器起不来 = 这台机器上没有这个版本（镜像没预拉 / 架构不支持）。
                // 记成跳过，别让「机器没镜像」伪装成「SQL 有 bug」。
                System.out.println("[版本矩阵] " + version.label() + " 起不来，该版本的用例全部跳过："
                        + e.getMessage());
            }
        }
    }

    @AfterAll
    void stopMatrix() {
        ready.values().forEach(started -> started.version().container().stop());
    }

    @TestFactory
    Stream<DynamicNode> versionMatrix() {
        return VERSIONS.stream().flatMap(version -> {
            Started started = ready.get(version.label());
            if (started == null) {
                // 整条版本一个跳过节点 —— 不按契约展开，免得报告里出现 6 条一样的跳过。
                return Stream.of(dynamicTest(version.label() + " · 容器不可用",
                        () -> Assumptions.abort(version.label()
                                + " 的容器没起来（镜像未预拉或架构不支持），跳过不计入门禁")));
            }
            return Stream.of(
                    fingerprint(started),
                    contract("生成的 DDL 跑得通", started,
                            RealDatabaseContract::generatedColumnDdlRuns),
                    contract("重命名保住列定义", started,
                            RealDatabaseContract::renameColumnPreservesDefinition),
                    contract("新建表存得下中文", started,
                            RealDatabaseContract::createdTablesAcceptUnicode),
                    contract("危险门禁按声明放行", started,
                            RealDatabaseContract::dangerGateRejectsUnderDeclaredDanger),
                    contract("元信息查询跑得通", started,
                            RealDatabaseContract::metadataQueriesRun),
                    contract("schema 切换语义", started,
                            RealDatabaseContract::schemaSwitchSemantics),
                    contract("标识符引用防注入", started,
                            RealDatabaseContract::quotedIdentifiersWork));
        });
    }

    /**
     * 版本指纹：容器<b>真的是</b>我们以为的那个版本吗。
     *
     * <p>浮动标签（{@code mysql:8.0}）会随发布时间变成 8.0.x —— 那是预期的，
     * 所以只锁主次版本号（{@code 8.0}），不锁补丁号。
     * 这条断言防的是「镜像被换成了别的版本」：那种情况下矩阵结论会失真。
     *
     * <p>⚠️ 不能 {@code startsWith}：PostgreSQL 的版本串是
     * {@code "PostgreSQL 17.11 on …"}，前面还有产品名。
     */
    private DynamicTest fingerprint(Started started) {
        return dynamicTest(started.version().label() + " · 版本指纹", () -> {
            String version = serverVersion(started.target());
            assertThat(version)
                    .as("SELECT VERSION() 要能跑")
                    .isNotBlank();
            assertThat(version)
                    .as("容器必须是 " + started.version().label() + "（防镜像被换版本）")
                    .contains(started.version().versionPrefix() + ".");
            System.out.println("[版本矩阵] " + started.version().label() + " → " + version);
        });
    }

    /** 一条契约用例。 */
    private DynamicTest contract(String what, Started started, ContractBody body) {
        return dynamicTest(started.version().label() + " · " + what,
                () -> body.accept(started.target()));
    }

    /** 契约体（允许抛异常 —— 真库操作一定会抛）。 */
    @FunctionalInterface
    private interface ContractBody {
        void accept(ExternalDatabase target) throws Exception;
    }

    private static String serverVersion(ExternalDatabase target) throws Exception {
        try (JdbcSession session = JdbcSession.open(target.spec());
             Statement statement = session.connection().createStatement();
             var rs = statement.executeQuery("SELECT VERSION()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
