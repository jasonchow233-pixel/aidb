package io.agenticdb.editing;

import io.agenticdb.jdbc.ExternalDatabase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ 格子写回的真库矩阵（R53）。
 *
 * <p><b>这条路以前只在 SQLite 上跑过。</b>{@link EditSqlBuilder} 生成的
 * UPDATE / DELETE / INSERT 全部用 {@code ?} 绑参，方言只贡献引号，
 * 所以单测（SQLite 内存库）看起来全绿 —— 但「引号形状」「类型能不能吃得下绑进去的值」
 * 「清空一格到底写成什么」这三件事是<b>服务器</b>说了算的，
 * 单测里那台 SQLite 恰好三件都顺着我们。D-38 的教训就是这一类：
 * 门禁只量自己写的东西，不量通道对面返回的东西，于是缺陷一路漂到用户眼前。
 *
 * <p>所以这里把 {@link RealEditWriteBackContract} 摊成「库 × 契约」矩阵，
 * 形状与 {@code adb-jdbc} 的 {@code ExternalRealDatabaseTest} 一致：
 * 凭据<b>只从环境变量读</b>（{@code ADB_TEST_*}，见 {@link ExternalDatabase}），
 * 仓库里零口令；没配的库记为<b>跳过</b>而不是通过。
 *
 * <h2>矩阵规模（每加一条契约都要回量这一行）</h2>
 * <p>5 引擎 × 2 契约 = <b>10 例</b>。
 */
class EditWriteBackMatrixTest {

    /** 参与矩阵的库：方言 + 读环境变量的入口。 */
    private record Engine(String label, Supplier<ExternalDatabase> source) {
    }

    private static final List<Engine> ENGINES = List.of(
            new Engine("MySQL", ExternalDatabase::mysql),
            new Engine("PostgreSQL", ExternalDatabase::postgres),
            new Engine("MariaDB", ExternalDatabase::mariadb),
            new Engine("SQLServer", ExternalDatabase::sqlServer),
            new Engine("Oracle", ExternalDatabase::oracle));

    private record Contract(String name, RealEditWriteBackContract.Body body) {
    }

    private static final List<Contract> CONTRACTS = List.of(
            new Contract("writeBackRoundTrip",
                    RealEditWriteBackContract::writeBackRoundTrip),
            new Contract("emptyCellShapeIsWhatTheServerSays",
                    RealEditWriteBackContract::emptyCellShapeIsWhatTheServerSays));

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
                              Supplier<ExternalDatabase> source,
                              RealEditWriteBackContract.Body body)
            throws Exception {
        ExternalDatabase target = source.get();
        assumeTrue(target != null, "未配置 " + engineLabel + " 的 ADB_TEST_* —— 跳过真库契约");
        if ("writeBackRoundTrip".equals(contractName)) {
            System.out.println("[写回契约] " + target.label());
        }
        contract(contractName + "（" + engineLabel + "）", target, body);
    }

    /**
     * 跑一条写回契约。
     *
     * <p>外部真库是第三方依赖：服务器抽风不该让门禁变红，但也不能悄悄算通过 ——
     * 记成<b>跳过</b>，门禁统计里单列可见。
     */
    private static void contract(String what, ExternalDatabase target,
                                 RealEditWriteBackContract.Body body)
            throws Exception {
        try {
            body.run(target);
        } catch (java.sql.SQLException e) {
            if (isCommunications(e)) {
                Assumptions.abort("外部数据库暂时不可达（" + what + "）：" + e.getMessage()
                        + " —— 跳过，不计入门禁");
            }
            throw e;
        }
    }

    /** 是通信类失败吗（而不是 SQL / 断言问题）？ */
    private static boolean isCommunications(java.sql.SQLException e) {
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
}
