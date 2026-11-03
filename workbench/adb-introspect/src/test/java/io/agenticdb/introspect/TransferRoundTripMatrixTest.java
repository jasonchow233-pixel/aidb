package io.agenticdb.introspect;

import io.agenticdb.jdbc.ExternalDatabase;
import io.agenticdb.jdbc.ExternalUnreachable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ⭐ 导出→导回的真库矩阵（R55）。
 *
 * <p><b>这条路以前只在 SQLite 上跑过。</b>{@code TransferRoundTripTest} 那四条往返门禁
 * 全部跑在内存 SQLite 上：它证明了「CSV 编解码与导入器的约定咬得上」，
 * 证明不了「五台服务器各自把 {@code rs.getObject} 交成什么 Java 类型、
 * 那个类型写进文件后再导回来吃不吃得下」。导出侧写的是
 * {@code String.valueOf(驱动交回来的值)}，导入侧按<b>内省到的列类型</b>再折回去 ——
 * 两头都是服务器说了算的地方，单测里那台 SQLite 恰好两头都顺着我们。
 *
 * <p>更贵的是失败形状：导入器对坏行的策略是「整批失败 → 回滚这批 → 逐行重试 →
 * 坏行跳过并记进报告」，所以真出问题时的症状不是异常，而是
 * <b>报告里少一行</b>。单测全绿 + 用户拿到一份少行的文件，就是这么来的。
 *
 * <p>形状与 {@code adb-editing} 的 {@code EditWriteBackMatrixTest} 一致：
 * 凭据<b>只从环境变量读</b>（{@code ADB_TEST_*}，见 {@link ExternalDatabase}），
 * 仓库里零口令；没配的库记为<b>跳过</b>而不是通过。
 *
 * <h2>矩阵规模（每加一条契约都要回量这一行）</h2>
 * <p>5 引擎 × 1 契约 = <b>5 例</b>。
 */
class TransferRoundTripMatrixTest {

    /** 参与矩阵的库：方言 + 读环境变量的入口。 */
    private record Engine(String label, Supplier<ExternalDatabase> source) {
    }

    private static final List<Engine> ENGINES = List.of(
            new Engine("MySQL", ExternalDatabase::mysql),
            new Engine("PostgreSQL", ExternalDatabase::postgres),
            new Engine("MariaDB", ExternalDatabase::mariadb),
            new Engine("SQLServer", ExternalDatabase::sqlServer),
            new Engine("Oracle", ExternalDatabase::oracle));

    private record Contract(String name, RealTransferRoundTripContract.Body body) {
    }

    private static final List<Contract> CONTRACTS = List.of(
            new Contract("csvRoundTripKeepsWhatTheServerGaveUs",
                    RealTransferRoundTripContract::csvRoundTripKeepsWhatTheServerGaveUs));

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
                              RealTransferRoundTripContract.Body body) throws Exception {
        ExternalDatabase target = source.get();
        assumeTrue(target != null, "未配置 " + engineLabel + " 的 ADB_TEST_* —— 跳过真库契约");
        System.out.println("[导出往返契约] " + target.label() + " · " + contractName);
        contract(contractName + "（" + engineLabel + "）", target, body);
    }

    /**
     * 跑一条导出往返契约。
     *
     * <p>外部真库是第三方依赖：服务器抽风不该让门禁变红，但也不能悄悄算通过 ——
     * 记成<b>跳过</b>，门禁统计里单列可见。
     */
    private static void contract(String what, ExternalDatabase target,
                                 RealTransferRoundTripContract.Body body) throws Exception {
        try {
            body.run(target);
        } catch (java.sql.SQLException e) {
            if (ExternalUnreachable.isCommunications(e)) {
                Assumptions.abort("外部数据库暂时不可达（" + what + "）：" + e.getMessage()
                        + " —— 跳过，不计入门禁");
            }
            throw e;
        }
    }

}
