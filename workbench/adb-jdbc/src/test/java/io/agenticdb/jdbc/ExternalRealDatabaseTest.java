package io.agenticdb.jdbc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ 外部真库契约测试。
 *
 * <p>凭据**只从环境变量读**，仓库里不存任何密码：
 * <pre>
 * ADB_TEST_MYSQL_HOST=…  ADB_TEST_MYSQL_PORT=3306  ADB_TEST_MYSQL_DB=…
 * ADB_TEST_MYSQL_USER=…  ADB_TEST_MYSQL_PASSWORD=…
 *
 * ADB_TEST_PG_*、ADB_TEST_MARIADB_*、ADB_TEST_MSSQL_*、ADB_TEST_ORACLE_* 同理
 * （Oracle 的 DB 是**服务名**，如 FREEPDB1）。
 * </pre>
 *
 * <p>没配的库，对应用例**记为跳过**（不是通过）—— 门禁统计里能看得见。
 *
 * <p>断言本体在 {@link RealDatabaseContract}，与 Testcontainers 那条路**共用**，
 * 保证「本地连真库」和「CI 起容器」验的是同一件事。
 *
 * <h2>为什么是「库 × 契约」一张矩阵（R46）</h2>
 * <p>以前这里是一个引擎一组手写 {@code @Test}：加一种库要复制一整套方法，
 * 于是 MariaDB / SQL Server / Oracle 长期<b>一条都没跑过</b> ——
 * 六个方言里只有两个被真库验过，剩下三个的元数据查询全靠「看起来对」。
 * 事实是：真库首跑当场抓出两条只在那些库上才成立的缺陷（D-20 SQL Server 的
 * {@code sys.tables} 没有 {@code collation_name}、D-22 Oracle 的
 * {@code UPPER(?)} 看不见我们自己加引号建的对象）。
 * 现在矩阵是数据驱动的：<b>加一种库只加一个 supplier，加一条契约只加一行</b>，
 * 全部引擎自动全覆盖 —— R52 的视图生命周期契约（第 8 条）就是这么加进来的，
 * 一个 supplier 没动，五家一起跑。
 *
 * <h2>矩阵规模（每加一条契约都要回量这一行）</h2>
 * <p>5 引擎 × 9 契约 = 45 例，加一条「环境读取不外泄口令」= <b>46 例</b>。
 * 第 9 条（切库落点）另需第二把库 {@code ADB_TEST_*_DB2}（{@code scripts/dbmatrix.sh up} 建）：
 * 有库层的四家缺它就是<b>跳过</b>（缺配置 ≠ 验过了），Oracle 没有库层，
 * 走「不假装可切、但当前库标签读得到」那一支，照常计入通过。
 */
class ExternalRealDatabaseTest {

    /** 参与矩阵的库：方言 + 读环境变量的入口。 */
    private record Engine(String label, Supplier<ExternalDatabase> source) {
    }

    private static final List<Engine> ENGINES = List.of(
            new Engine("MySQL", ExternalDatabase::mysql),
            new Engine("PostgreSQL", ExternalDatabase::postgres),
            new Engine("MariaDB", ExternalDatabase::mariadb),
            new Engine("SQLServer", ExternalDatabase::sqlServer),
            new Engine("Oracle", ExternalDatabase::oracle));

    /** 九条契约的名字 + 实现。 */
    private record Contract(String name, ContractBody body) {
    }

    private static final List<Contract> CONTRACTS = List.of(
            new Contract("generatedColumnDdlRuns",
                    RealDatabaseContract::generatedColumnDdlRuns),
            new Contract("renameColumnPreservesDefinition",
                    RealDatabaseContract::renameColumnPreservesDefinition),
            new Contract("createdTablesAcceptUnicode",
                    RealDatabaseContract::createdTablesAcceptUnicode),
            new Contract("dangerGateRejectsUnderDeclaredDanger",
                    RealDatabaseContract::dangerGateRejectsUnderDeclaredDanger),
            new Contract("metadataQueriesRun", RealDatabaseContract::metadataQueriesRun),
            new Contract("schemaSwitchSemantics", RealDatabaseContract::schemaSwitchSemantics),
            new Contract("quotedIdentifiersWork", RealDatabaseContract::quotedIdentifiersWork),
            new Contract("viewLifecycleRuns", RealDatabaseContract::viewLifecycleRuns),
            new Contract("catalogSwitchLands", RealDatabaseContract::catalogSwitchLands));

    /** 矩阵的展开：库 × 契约。名字带进 {@code @DisplayName}，跳过时看得见是哪个库缺配置。 */
    static Stream<Arguments> matrix() {
        Stream<Arguments> flat = Stream.empty();
        for (Engine engine : ENGINES) {
            for (Contract contract : CONTRACTS) {
                flat = Stream.concat(flat, Stream.of(
                        Arguments.of(engine.label(), contract.name(), engine.source(),
                                contract.body())));
            }
        }
        return flat;
    }

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("matrix")
    void realDatabaseContract(String engineLabel, String contractName,
                              Supplier<ExternalDatabase> source, ContractBody body)
            throws Exception {
        ExternalDatabase target = source.get();
        assumeTrue(target != null, "未配置 " + engineLabel + " 的 ADB_TEST_* —— 跳过真库契约");
        if ("generatedColumnDdlRuns".equals(contractName)) {
            System.out.println("[真库契约] " + target.label());
        }
        contract(contractName + "（" + engineLabel + "）", target, body);
    }

    /**
     * 跑一条真库契约。
     *
     * <p>⚠️ 外部真库是**第三方依赖**：服务器抽风（实测这台 MySQL 的 TLS 握手
     * 会间歇性卡住）不该让门禁变红。但也不能**悄悄算通过** ——
     * 所以这里把它记成**跳过**（Assumption 失败）：
     * 门禁统计里会单列出来，看得见，但不计入门禁。
     */
    private static void contract(String what, ExternalDatabase target, ContractBody body)
            throws Exception {
        try {
            body.accept(target);
        } catch (java.sql.SQLException e) {
            if (ExternalUnreachable.isCommunications(e)) {
                Assumptions.abort("外部数据库暂时不可达（" + what + "）：" + e.getMessage()
                        + " —— 跳过，不计入门禁");
            }
            throw e;
        }
    }

    /** 契约体（允许抛异常）。 */
    @FunctionalInterface
    private interface ContractBody {
        void accept(ExternalDatabase target) throws Exception;
    }

    // ─────────────────────────── 环境变量读取本身 ───────────────────────────

    @Test
    void environmentReaderNeverLeaksThePassword() {
        for (Supplier<ExternalDatabase> source : List.<Supplier<ExternalDatabase>>of(
                ExternalDatabase::mysql,
                ExternalDatabase::postgres, ExternalDatabase::mariadb,
                ExternalDatabase::sqlServer, ExternalDatabase::oracle)) {
            ExternalDatabase target = source.get();
            if (target == null) {
                continue;
            }
            assertThat(target.label())
                    .as("label 只能含主机 / 端口 / 库名 —— 绝不能含口令")
                    .doesNotContain(target.password() == null ? " " : target.password())
                    .doesNotContain("password");
        }
    }
}
