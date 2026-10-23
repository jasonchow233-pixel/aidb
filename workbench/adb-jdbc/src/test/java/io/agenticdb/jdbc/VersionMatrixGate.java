package io.agenticdb.jdbc;

/**
 * 多版本矩阵的门。
 *
 * <p>矩阵要额外起 4 个数据库容器（MySQL 5.7 / 8.0、PostgreSQL 13 / 17），
 * 冷机拉镜像 + 起容器要几分钟。所以它<b>默认不跑</b>，
 * 由 {@code ADB_TEST_VERSION_MATRIX=1} 显式打开：
 *
 * <pre>
 * ADB_TEST_VERSION_MATRIX=1 ./scripts/build.sh
 * </pre>
 *
 * <p>默认门禁里已经有一组容器（{@code postgres:16-alpine} + {@code mysql:8.4}，
 * 见 {@link PostgresMySqlIntegrationTest}）—— 两组加起来覆盖
 * <b>MySQL 5.7 / 8.0 / 8.4、PostgreSQL 13 / 16 / 17</b> 六个版本。
 */
public final class VersionMatrixGate {

    private VersionMatrixGate() {
    }

    /** 跑多版本矩阵吗。 */
    public static boolean isEnabled() {
        String value = System.getenv("ADB_TEST_VERSION_MATRIX");
        return value != null && (value.trim().equals("1") || value.trim().equalsIgnoreCase("true"));
    }

    /**
     * 供 JUnit 的 {@code @EnabledIf} 用：Docker 可用<b>且</b>矩阵开关打开。
     *
     * <p>合成一个方法是因为 {@code @EnabledIf} 不可重复叠加 ——
     * 两个条件都满足才值得起那 4 个容器。
     */
    public static boolean shouldRunMatrix() {
        return DockerAvailability.isAvailable() && isEnabled();
    }
}
