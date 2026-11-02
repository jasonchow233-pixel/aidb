package io.agenticdb.jdbc;

import java.util.List;

/**
 * MariaDB 方言（D-1）。
 *
 * <p>SQL 主体继承 {@link MySqlDialect} —— 两者同源，反引号引用、
 * {@code MODIFY COLUMN}、{@code SHOW CREATE TABLE}、
 * {@code information_schema.CHECK_CONSTRAINTS}（10.2.1+）、{@code SHOW EVENTS}
 * 的写法逐字一致。这里只 override <b>真实存在</b>的差异：
 *
 * <ul>
 *   <li><b>驱动与 URL</b>：{@code org.mariadb.jdbc.Driver} / {@code jdbc:mariadb:} 前缀；
 *       MySQL 的 {@code useUnicode} / {@code characterEncoding} / {@code serverTimezone}
 *       是 mysql-connector-j 的私有参数，MariaDB 驱动不认（超时参数两边同名，沿用）。</li>
 *   <li><b>排序规则族</b>：{@code utf8mb4_0900_*} 是 MySQL 8 引入的 UCA-09.00 实现，
 *       MariaDB <b>没有</b>这一族（10.x 默认 {@code utf8mb4_general_ci}；
 *       10.10+/11.x 才有 {@code utf8mb4_uca14_*}）。把 MySQL 8 的排序规则递进
 *       MariaDB 的下拉，等于递一个「选了必报错」的选项 —— 任务书「不得虚构能力」。</li>
 * </ul>
 *
 * <p>⚠️ 版本差异没有假装抹平：MariaDB 10.5+ 支持 {@code INSERT ... RETURNING}
 * 而 MySQL 8.0 没有 —— 但本项目的写路径走「执行 → 反向重读」，<b>不生成</b>
 * RETURNING，所以这条差异落在界面之外，不进方言层。
 *
 * <p>视图重建（R42 立、R52 收）：本类原来唯一的视图语法 override 是
 * {@code viewReplace() = OR_REPLACE}，理由写的是「MySQL 没有单句形式」。
 * R52 在 MySQL 8.4 容器上实测推翻了那个前提（D-29，见
 * {@link MySqlDialect#viewReplace()}）：两家都收 {@code CREATE OR REPLACE VIEW}，
 * 于是这条 override 删掉、直接继承父类 —— 少一处 override，
 * 也少一处「同源两个方言各写一遍、然后悄悄长出两个版本」的机会。
 *
 * <p>R46 起有 MariaDB 11.8 容器进真库矩阵（{@code ExternalRealDatabaseTest}），
 * 继承来的目录查询因此是被执行过的；实测到 {@code SHOW CREATE EVENT} 的列序与
 * MySQL 一致（{@code Event | sql_mode | time_zone | Create Event | …}），
 * 定义体在第 4 列 —— D-25 那一版按位置猜第 3 列，两边一起读到 {@code "SYSTEM"}。
 */
public final class MariaDbDialect extends MySqlDialect {

    @Override
    public DialectKind kind() {
        return DialectKind.MARIADB;
    }

    @Override
    public String displayName() {
        return "MariaDB";
    }

    /**
     * ⭐ 驱动是 mariadb-java-client 3.3，<b>不是</b> Connector/J：档位参数虽然同名
     * {@code sslMode}，取值集合却是另一套（驱动枚举 {@code org.mariadb.jdbc.export.SslMode}
     * 实测只有 DISABLE / TRUST / VERIFY_CA / VERIFY_FULL）。
     *
     * <p>因此「首选（prefer）」这一档在 MariaDB 上<b>没法表达</b>：
     * 要么明文、要么加密、要么验证书，没有「加密但不拒绝明文」这档。
     * 界面下拉据 {@link #sslLevels()} 少一项 —— 摆一个填了不生效的档位等于骗人。
     */
    @Override
    public java.util.List<SslLevel> sslLevels() {
        return java.util.List.of(SslLevel.OFF, SslLevel.REQUIRE,
                SslLevel.VERIFY_CA, SslLevel.VERIFY_IDENTITY);
    }

    @Override
    public java.util.Map<String, String> sslOptions(SslLevel level) {
        String value = switch (level) {
            case OFF -> null;
            case PREFER -> null;
            case REQUIRE -> "TRUST";
            case VERIFY_CA -> "VERIFY_CA";
            case VERIFY_IDENTITY -> "VERIFY_FULL";
        };
        return value == null ? java.util.Map.of() : java.util.Map.of("sslMode", value);
    }

    @Override
    public SslLevel sslLevel(java.util.Map<String, String> options) {
        String raw = options == null ? null : options.get("sslMode");
        if (raw == null || raw.isBlank()) {
            return SslLevel.OFF;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "TRUST" -> SslLevel.REQUIRE;
            case "VERIFY_CA" -> SslLevel.VERIFY_CA;
            case "VERIFY_FULL" -> SslLevel.VERIFY_IDENTITY;
            default -> SslLevel.OFF;
        };
    }

    /** MariaDB 驱动认 {@code serverSslCert}（驱动参数表实测），不是 PG 的 {@code sslrootcert}。 */
    @Override
    public String sslRootCertOption() {
        return "serverSslCert";
    }

    @Override
    public String jdbcUrl(String host, int port, String database) {
        // mariadb-java-client 的超时参数名与 mysql-connector-j 一致（毫秒）；
        // MySQL 那几个字符集/时区私有参数这里一个都不能带。
        return "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?connectTimeout=" + CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + SOCKET_TIMEOUT_MS;
    }

    @Override
    public String uriScheme() {
        return "mariadb";
    }

    @Override
    public String driverClassName() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public List<String> columnCollationPresets() {
        // 只给 MariaDB 全版本都有的族；uca14 是 10.10+ 才有，不混进通用候选。
        return List.of("utf8mb4_general_ci", "utf8mb4_unicode_ci", "utf8mb4_bin",
                "latin1_swedish_ci");
    }
}
